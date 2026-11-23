//! Hand-template render: the same steps `engine/e4-hand/src/main.rs` runs for
//! `render`, with a CPU raster surface (no D3D12/ANGLE) and an optional scale.
//!
//! Interactive preview frames may rasterize on the GPU (Ganesh + GL, see
//! `gpu.rs`/`egl.rs`) when `FM_GPU_PREVIEW` allows it; export-class renders and
//! every GPU failure stay on the CPU raster path, which is byte-for-byte the
//! pre-GPU renderer.

use std::cell::RefCell;
use std::path::Path;
use std::sync::{Mutex, OnceLock};
use std::time::Instant;

use serde_json::json;
use skia_safe::{
    AlphaType, ColorSpace, ColorType, Data, EncodedImageFormat, Image, ImageInfo, Surface,
};

use crate::canvas::{Ctx2D, OverlaySpec};
use crate::draw::{Env, Images, LayoutEnv};
use crate::fonts::HandFonts;
use crate::gpu_preview;
use crate::hand::{self, Card, Exif};
use crate::photo_source::{self, DecodeReport, PhotoSource};
use crate::report::Report;
use crate::text::TextEngine;

/// One decoded interactive source photo is enough for the stage: it renders one
/// photo at a time and a drag alternates only between that photo's interactive and
/// full decodes. The slot is process-wide because the JNI render may run on a pool
/// thread, and it lives next to the renderer for the same reason the IR bridge's
/// does (see `bridge/android/src/paint.rs`).
static PHOTO_CACHE: OnceLock<Mutex<photo_source::PhotoCache>> = OnceLock::new();

fn photo_cache() -> &'static Mutex<photo_source::PhotoCache> {
    PHOTO_CACHE.get_or_init(|| Mutex::new(photo_source::PhotoCache::new(1)))
}

pub struct HandGeom {
    pub canvas: [i32; 2],
    pub output: [i32; 2],
    pub photo_rect: [f32; 4],
    pub json: String,
}

pub struct HandRender {
    pub card: String,
    pub width: i32,
    pub height: i32,
    pub raw_width: i32,
    pub raw_height: i32,
    pub photo_rect: [f32; 4],
    pub geom: Option<HandGeom>,
    /// Which backend rasterized this frame: `cpu:<why>`, `gpu:<why>` or
    /// `gpu-fallback=<reason>`. Logged by the C ABI so logcat answers "did the
    /// preview actually use the GPU?" without a rebuild.
    pub gpu: String,
    pub ms: u128,
}




/// Skia decodes JPEG/PNG/WebP but no TIFF; `.tif/.tiff` uses the E5 decoder
/// (same as the IR bridge) plus the EXIF orientation step.
///
/// **JPEG/WebP 不再自己旋转**（2026-11-20）：Skia m156 的 `Image::from_encoded`
/// 已经按 EXIF 方向解出像素 —— `photo_source::decode_bounded` 的整套逻辑都建立在这个
/// 前提上，桌面两个渲染器（`e4-hand` / `e2b`）也是「解码即终态、不再旋转」，而手机竖拍
/// 照片在桌面显示是正确的。以前这里又按方向转了一次，判断依据是「宽高是否互换」：
/// 镜像(2)、180°(3)、镜像+180°(4) 宽高不变，猜不出来，于是**二次旋转把方向转回原样**。
/// 受限解码的交互预览不走这条函数，所以预览是正的、导出是反的（审计实测 2 / 3）。
/// 现在统一成「谁解出像素谁负责方向」。
fn load_image(p: &Path) -> Result<Image, String> {
    let bytes = std::fs::read(p).map_err(|e| format!("missing image {}: {e}", p.display()))?;
    let is_tiff = bytes.len() >= 4 && (&bytes[..4] == b"II*\0" || &bytes[..4] == b"MM\0*");
    if !is_tiff {
        return Image::from_encoded(Data::new_copy(&bytes))
            .ok_or_else(|| format!("decode failed {}", p.display()));
    }
    let img = crate::tiffsrc::decode(p)?;
    let (rgba, w, h) = crate::tiffsrc::apply_orientation(&img.rgba, img.w, img.h, img.orientation);
    let info = skia_safe::ImageInfo::new(
        (w as i32, h as i32),
        skia_safe::ColorType::RGBA8888,
        skia_safe::AlphaType::Unpremul,
        Some(skia_safe::ColorSpace::new_srgb()),
    );
    skia_safe::images::raster_from_data(&info, Data::new_copy(&rgba), (w as usize) * 4)
        .ok_or_else(|| format!("tiff raster failed {}", p.display()))
}

/// Loads the overlay image with render-v1 error wording.
pub(crate) fn load_overlay(path: &Path) -> Result<Image, String> {
    if !path.is_file() {
        return Err(format!("overlay image missing: {}", path.display()));
    }
    load_image(path).map_err(|_| format!("overlay image decode failed: {}", path.display()))
}

/// The source photo for one frame.
///
/// `preview_source_edge <= 0` is today's full decode, untouched (final frames,
/// exports, evidence renders). A positive edge asks for a bounded decode; the
/// shared cache key is (path, length, mtime, source edge), so a drag never decodes
/// twice and the full frame after a release is a different entry rather than an
/// eviction. Layout keeps reading the photo's logical pixels either way.
fn load_photo_source(
    path: &Path,
    preview_source_edge: i32,
) -> Result<(PhotoSource, Option<DecodeReport>), String> {
    let edge = preview_source_edge.max(0) as u32;
    if edge == 0 {
        return Ok((PhotoSource::full(load_image(path)?), None));
    }
    let mut cache = photo_cache()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    // Skia has no TIFF codec: those photos keep the engine's own full decode
    // (which applies the TIFF orientation) instead of a bounded one.
    let (photo, report) = photo_source::load_cached_photo(&mut cache, path, edge, &|bytes| {
        let is_tiff = bytes.len() >= 4 && (&bytes[..4] == b"II*\0" || &bytes[..4] == b"MM\0*");
        if is_tiff {
            load_image(path).ok()
        } else {
            None
        }
    })?;
    Ok((photo, Some(report)))
}

fn text_engine() -> TextEngine {
    let mut te = TextEngine::new();
    te.subpixel = true;
    te.linear_metrics = true;
    te.edging = skia_safe::font::Edging::AntiAlias;
    te.embolden = false;
    te.stroke_w = 0.0;
    te.hinting = match std::env::var("E4H_HINTING")
        .unwrap_or_else(|_| "slight".into())
        .as_str()
    {
        "none" => skia_safe::FontHinting::None,
        "normal" => skia_safe::FontHinting::Normal,
        "full" => skia_safe::FontHinting::Full,
        _ => skia_safe::FontHinting::Slight,
    };
    te
}

/// `draw.ts textScaleOf`: clamp 0.5..2; anything within 0.001 of 1 is exactly 1.
fn text_scale_of(raw: f64) -> f32 {
    let s = if raw.is_finite() {
        raw.clamp(0.5, 2.0)
    } else {
        1.0
    };
    if (s - 1.0).abs() < 0.001 {
        1.0
    } else {
        s as f32
    }
}

struct PassOut {
    surface: Surface,
    photo_rect: [f32; 4],
    report: Report,
}

/// One render of the card. `snap` is the native-fit contract (same as the
/// desktop `set_photo_1to1`): every photo draw box is forced onto exactly that
/// size, so the source pixels land 1:1. `photo` carries the interactive tier's
/// raster/logical ratio: the draws sample the (possibly reduced) raster while
/// every box, crop and report entry stays in the photo's logical pixels.
#[allow(clippy::too_many_arguments)]
fn draw_pass(
    card: &Card,
    design: &hand::Design,
    params: &hand::Params,
    exif: &Exif,
    photo: &PhotoSource,
    images: &Images,
    fonts: &HandFonts,
    te: &TextEngine,
    themes: &serde_json::Value,
    crop: hand::Crop,
    pw: f32,
    ph: f32,
    cw: i32,
    ch: i32,
    scale: f32,
    snap: Option<(f32, f32)>,
    overlay: Option<OverlaySpec>,
) -> Result<PassOut, String> {
    let out_w = ((cw as f32 * scale).round() as i32).max(1);
    let out_h = ((ch as f32 * scale).round() as i32).max(1);
    // Match e4-hand's GPU render target: opaque RGBA8888 + sRGB colour space,
    // cleared to black. A plain N32 premul surface has no colour space and
    // blends translucent content differently (global tint on blur/glass cards).
    let info = ImageInfo::new(
        (out_w, out_h),
        ColorType::RGBA8888,
        AlphaType::Opaque,
        Some(ColorSpace::new_srgb()),
    );
    // Same `ImageInfo` either way; `gpu::surface_for` returns a Ganesh render
    // target only while a GPU preview session is installed on this thread, and a
    // CPU raster surface otherwise (export-class renders and `FM_GPU_PREVIEW=off`
    // always land in the second branch).
    let mut surface = crate::gpu::surface_for(&info)
        .ok_or_else(|| "cannot create raster surface".to_string())?;
    surface.canvas().clear(skia_safe::Color::BLACK);
    let report_cell = RefCell::new(Report {
        canvas: [cw, ch],
        template: card.template_id.clone(),
        variant: design.variant_id.clone(),
        ..Default::default()
    });
    let mut photo_rect = [0.0f32; 4];
    let mut render_err: Option<String> = None;
    {
        let canvas = surface.canvas();
        canvas.scale((scale, scale));
        let mut ctx = Ctx2D::new(canvas, &report_cell);
        ctx.set_photo_1to1(snap);
        // Interactive tier: photo draws map their source rect into the decoded
        // raster; layout, crop and the draw report stay logical.
        ctx.set_photo_source(photo.scale, photo.logical_size());
        // render-v1 photo crop: the photo draws read the user's region so a pan
        // moves the cover slice inside the template's window (see hand::cover_src).
        ctx.set_user_crop(hand::UserCrop::read(params));
        ctx.set_overlay(overlay);
        let mut env = Env {
            ctx,
            w: cw as f32,
            h: ch as f32,
            pw,
            ph,
            crop,
            photo: &photo.image,
            exif,
            params,
            text: &design.text,
            design,
            images,
            is_preview: false,
            fonts,
            te,
            themes,
            palette48: None,
            lines: RefCell::new(crate::lines::LineState::new()),
        };
        match crate::templates::render(&mut env) {
            Ok(r) => photo_rect = [r.left, r.top, r.width(), r.height()],
            Err(e) => render_err = Some(e),
        }
    }
    if let Some(e) = render_err {
        return Err(e);
    }
    let report = report_cell.into_inner();
    if let Some(font_error) = report
        .gaps
        .iter()
        .find(|gap| gap.starts_with(crate::draw::FONT_ERROR_PREFIX))
    {
        return Err(font_error.clone());
    }
    Ok(PassOut {
        surface,
        photo_rect,
        report,
    })
}

#[allow(clippy::too_many_arguments)]
pub fn render_card(
    cards_path: &str,
    card_id: &str,
    hand_dir: &str,
    fonts_dir: &str,
    extra_fonts: &str,
    fallback_cjk: Option<&str>,
    photo_override: Option<&Path>,
    overlay: Option<OverlaySpec>,
    app_design: Option<&serde_json::Value>,
    out_png: &str,
    max_edge: i32,
    preview_source_edge: i32,
) -> Result<HandRender, String> {
    let geom_path = format!("{out_png}.geom.json");
    render_card_impl(
        cards_path,
        card_id,
        hand_dir,
        fonts_dir,
        extra_fonts,
        fallback_cjk,
        photo_override,
        overlay,
        app_design,
        Some(out_png),
        &geom_path,
        max_edge,
        preview_source_edge,
        // GPU raster is a preview-only optimisation: the export-class request
        // (`max_edge <= 0`) and every non-interactive tier stay on the CPU.
        !gpu_preview::is_export_path(max_edge, preview_source_edge),
    )
    .map(|product| product.render)
}

pub struct RgbaCardRender {
    pub rgba: Vec<u8>,
    pub width: u32,
    pub height: u32,
}

struct CardRenderProduct {
    render: HandRender,
    rgba: Option<Vec<u8>>,
}

#[allow(clippy::too_many_arguments)]
pub fn render_card_rgba(
    cards_path: &str,
    card_id: &str,
    hand_dir: &str,
    fonts_dir: &str,
    extra_fonts: &str,
    fallback_cjk: Option<&str>,
    photo_override: Option<&Path>,
    overlay: Option<OverlaySpec>,
    app_design: Option<&serde_json::Value>,
    geom_path: &str,
    max_edge: i32,
) -> Result<RgbaCardRender, String> {
    let product = render_card_impl(
        cards_path,
        card_id,
        hand_dir,
        fonts_dir,
        extra_fonts,
        fallback_cjk,
        photo_override,
        overlay,
        app_design,
        None,
        geom_path,
        max_edge,
        // Raw RGBA is the export path: it always keeps the full decode.
        0,
        // …and it is export-class by definition, so it must never touch the GPU
        // (`fm_render_hand_rgba` feeds the JPEG/WebP encoder).
        false,
    )?;
    let rgba = product
        .rgba
        .ok_or_else(|| "raw renderer produced no pixels".to_string())?;
    Ok(RgbaCardRender {
        rgba,
        width: product.render.width as u32,
        height: product.render.height as u32,
    })
}

/// Backend selection + the one-shot CPU retry, then the real render.
///
/// Export-class frames (`gpu_preview == false`, i.e. the RGBA export JNI, any
/// `max_edge <= 0` request and every non-interactive tier) never touch GL: the
/// decision short-circuits on `export` before the switch/probe are even consulted,
/// so `FM_GPU_PREVIEW=force` cannot change an exported pixel. Interactive preview
/// frames run on the GPU when the switch allows it, and any GPU-level failure
/// (EGL/GLES init, `GrDirectContext` → `None`, texture limit, submit error, panic
/// inside a GPU pass) re-runs the same render on the CPU exactly once and reports
/// the cause as `gpu-fallback=<reason>`.
#[allow(clippy::too_many_arguments)]
fn render_card_impl(
    cards_path: &str,
    card_id: &str,
    hand_dir: &str,
    fonts_dir: &str,
    extra_fonts: &str,
    fallback_cjk: Option<&str>,
    photo_override: Option<&Path>,
    overlay: Option<OverlaySpec>,
    app_design: Option<&serde_json::Value>,
    out_png: Option<&str>,
    geom_path: &str,
    max_edge: i32,
    preview_source_edge: i32,
    // GPU raster is only allowed for the interactive preview tier. `render_card`
    // derives it from the two edges ([`gpu_preview::is_export_path`]); the RGBA
    // export entry point passes `false` unconditionally.
    gpu_preview: bool,
) -> Result<CardRenderProduct, String> {
    let decision = gpu_preview::decide(
        gpu_preview::current_mode(),
        !gpu_preview,
        gpu_preview::probe_state(),
        crate::gpu::backend_available(),
    );
    // `overlay` is cloned per attempt: the first (GPU) attempt consumes its own copy
    // so a CPU retry still draws the watermark.
    let body = || {
        render_card_body(
            cards_path,
            card_id,
            hand_dir,
            fonts_dir,
            extra_fonts,
            fallback_cjk,
            photo_override,
            overlay.clone(),
            app_design,
            out_png,
            geom_path,
            max_edge,
            preview_source_edge,
        )
    };
    if !decision.use_gpu {
        note(&format!("e4.gpu=cpu reason={}", decision.reason));
        let mut product = body()?;
        product.render.gpu = format!("cpu:{}", decision.reason);
        return Ok(product);
    }
    match crate::gpu::run_preview(body) {
        Ok(mut product) => {
            // One line on the first clean GPU frame: it answers "is the preview
            // really on the GPU?" without printing per dragged frame. The driver
            // strings, readback colour and glGetError live in the `e4.gpu.probe …`
            // line that `gpu::probe_cached()` logs before this frame.
            if gpu_preview::note_first_gpu_frame() {
                note(&format!(
                    "e4.gpu=gpu reason={} thread={} {}",
                    decision.reason,
                    crate::egl::thread_tag(),
                    crate::gpu::describe()
                ));
            }
            product.render.gpu = format!("gpu:{}", decision.reason);
            Ok(product)
        }
        Err(reason) => {
            // Auto mode stops trying for the rest of the process; the frame itself
            // always succeeds on the CPU.
            gpu_preview::record_probe_failed(&reason);
            note(&format!("e4.gpu=fallback reason={reason}"));
            let mut product = body()?;
            product.render.gpu = format!("gpu-fallback={reason}");
            Ok(product)
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn render_card_body(
    cards_path: &str,
    card_id: &str,
    hand_dir: &str,
    fonts_dir: &str,
    extra_fonts: &str,
    fallback_cjk: Option<&str>,
    photo_override: Option<&Path>,
    mut overlay: Option<OverlaySpec>,
    // The app's design document (`{"design":{params,text}}`), merged over the card's
    // own design exactly like the desktop `--design` path; `None` keeps the card as is.
    app_design: Option<&serde_json::Value>,
    out_png: Option<&str>,
    geom_path: &str,
    max_edge: i32,
    // Interactive preview tier: the source photo may be decoded no larger than
    // this long edge. 0 keeps the full-resolution decode (final frames, exports,
    // evidence renders), so the flag alone decides which frame may change its
    // sampled resolution.
    preview_source_edge: i32,
) -> Result<CardRenderProduct, String> {
    let started = Instant::now();
    let cards = hand::load_cards(Path::new(cards_path))?;
    let card: &Card = cards
        .cards
        .iter()
        .find(|c| c.card == card_id)
        .ok_or_else(|| format!("card '{card_id}' not in {cards_path}"))?;
    let mut design = hand::load_design(&card.design)?;
    let app_logo_override = app_design
        .and_then(|app| app.get("design").unwrap_or(app).get("logo"))
        .is_some();
    if let Some(app) = app_design {
        apply_app_design(&mut design, app)?;
        note(&format!(
            "e4.design=ok len={} params={} text={}",
            app.to_string().len(),
            design
                .params
                .iter()
                .map(|(k, v)| format!("{k}={v}"))
                .collect::<Vec<_>>()
                .join(","),
            design.text.title,
        ));
    } else {
        note("e4.design=absent");
    }
    let tables = hand::load_tables(Path::new(hand_dir))?;
    let def = tables
        .templates
        .iter()
        .find(|t| t.id == card.template_id)
        .ok_or_else(|| format!("template '{}' not in hand/templates.json", card.template_id))?;
    let mut params = hand::merge_params(&def.defaults_map(), &design.params);
    // 卡片自带的示例文本不是用户编辑。设计从别的照片带过来时，`slotText:` 可能还留着
    // 上一张样片的品牌/机型（"PANASONIC"、"DC-S1" …），照原样渲染就会出现
    // 「OPPO 照片 + 经典双行显示 Panasonic」。丢弃与卡片示例文本相同的覆盖，
    // 让槽位回到照片 EXIF；用户真改过的文本不等于示例文本，因此不会被误删。
    // 与 `engine/e4-hand/src/main.rs` 的同名处理保持一致。
    drop_sample_slot_text(&mut params, card);

    let (photo_source, decode_report) =
        load_photo_source(photo_override.unwrap_or(&card.photo.path), preview_source_edge)?;
    if let Some(report) = decode_report {
        note(&format!("fm_hand.decode {}", report.fields()));
    }
    // Layout reads the photo's *logical* pixels: a bounded interactive decode
    // changes how many pixels are sampled, never the geometry that is solved.
    let (iw, ih) = photo_source.logical_size();
    // A2: same semantics as the desktop `--fit native`: the photo keeps every
    // pixel and the canvas grows around it. The template ratio only applies when
    // the design did not ask for native fit.
    let fit = hand::resolve_fit("", design.fit_mode.as_deref())?;
    let crop = match fit {
        hand::FitMode::Design => hand::compute_crop(iw, ih, &design.ratio),
        hand::FitMode::Native => {
            let v = serde_json::Value::from((iw / ih) as f64);
            params.insert("canvasAspect".into(), v.clone());
            params.insert("photoAspect".into(), v);
            println!(
                "e4hand.fit=native photo={}x{} lockedAspect={:.6}",
                iw,
                ih,
                iw / ih
            );
            hand::crop_full(iw, ih)
        }
    };
    // render-v1 photo crop: the user's window inside the template's own crop,
    // exactly as `engine/e4-hand/src/main.rs` computes it. The layout keeps
    // reading `crop` (`pw`/`ph` and the template ratio are the template's), so
    // nothing but the sampled source pixels moves; at the defaults this is
    // `crop` untouched and today's render is reproduced byte for byte.
    let view = hand::crop_window(crop, iw, ih, hand::UserCrop::read(&params));

    // A1: a user photo carries its own shooting information. The card's sample
    // EXIF is only used when no user photo was supplied (bundled sample preview).
    //
    // 用户换上来的照片常常不是 JPEG（HEIC 导入后被烤成 PNG，或来自社交软件），
    // 这类文件读不到 APP1；此时不能退回卡片自带的样片 EXIF（那会把松下 DC-S1
    // 印到 OPPO 照片上），而要继续找导入时写出的同名 sidecar
    // （`files/exif/<photo>.jpg`，只含 APP1），找不到就留空。
    let exif: Exif = match photo_override {
        Some(p) if !p.is_empty() => {
            let direct = crate::exif::read_photo(Path::new(p));
            if direct.has_any {
                direct
            } else {
                exif_sidecar_for(Path::new(p))
                    .and_then(|side| {
                        let side_exif = crate::exif::read_photo(&side);
                        side_exif.has_any.then_some(side_exif)
                    })
                    .unwrap_or_default()
            }
        }
        _ => card.exif.clone().unwrap_or_default(),
    };
    let fallback_text = text_needing_fallback(&design.text, &exif, &params);
    let requires_bundled_cjk = crate::fonts::contains_cjk(&fallback_text);
    let layout_env = LayoutEnv {
        pw: crop.pw,
        ph: crop.ph,
        crop,
        exif: &exif,
        params: &params,
        text: &design.text,
    };
    let (lw, lh) = crate::templates::layout(&card.template_id, &layout_env)?;
    let cw = (crate::jsval::js_round(lw as f64) as i32).max(2);
    let ch = (crate::jsval::js_round(lh as f64) as i32).max(2);

    let mut images = Images::empty();
    if app_logo_override {
        if let Some(logo) = &design.logo {
            let bytes = decode_logo_data_url(&logo.data_url)?;
            images.logo = Some(
                Image::from_encoded(Data::new_copy(&bytes))
                    .ok_or_else(|| "design.logo.dataUrl image decode failed".to_string())?,
            );
        }
    } else if let Some(logo) = &card.logo {
        images.logo = Some(load_image(&logo.path)?);
    }
    for extra in &card.extras {
        match extra.kind.as_str() {
            "graduation-sticker" => images.sticker = Some(load_image(&extra.png)?),
            "polaroid-frame" => {
                // 7 种画幅各一张框图，模板按照片比例挑一张：只解码会用到的那张，
                // 按文件名 stem 登记，由 `Images::frame_for` 取回。
                let stem = extra
                    .png
                    .file_stem()
                    .and_then(|s| s.to_str())
                    .unwrap_or_default()
                    .to_string();
                let wanted = crate::templates::frame_stem_for(&card.template_id, crop.pw, crop.ph);
                let matches = wanted.is_none_or(|want| stem.ends_with(want));
                // 老包（只带一张框图）里没有对应那一张时退回那张：宁可拉伸也不白屏。
                let only_frame = card
                    .extras
                    .iter()
                    .filter(|e| e.kind == "polaroid-frame")
                    .count()
                    == 1;
                if matches || only_frame {
                    images.frames.insert(stem, load_image(&extra.png)?);
                }
            }
            _ => {}
        }
    }

    let fonts = HandFonts::load(Path::new(fonts_dir), Path::new(extra_fonts))?;
    let mut te = text_engine();
    te.fallback = fonts.fallback.clone();
    te.fallback_bold = fonts.fallback_bold.clone();
    // Select the packaged CJK face from all text the renderer can emit,
    // including camera metadata supplied by the active photo.
    if requires_bundled_cjk {
        let path = fallback_cjk
            .filter(|path| !path.trim().is_empty())
            .ok_or_else(|| {
                "required CJK fallback font path is empty for non-empty Chinese text".to_string()
            })?;
        let face = crate::fonts::load_required_face(Path::new(path))
            .map_err(|e| format!("required CJK fallback font cannot be loaded ({path}): {e}"))?;
        te.fallback = Some(face.clone());
        te.fallback_bold = Some(face);
    }
    te.scale = text_scale_of(design.text.font_scale);
    let themes = tables.themes.clone();

    let mut cw = cw;
    let mut ch = ch;
    let (pass, scale) = if fit == hand::FitMode::Native {
        // Desktop `--fit native` parity (e4-hand main.rs): pass A pins the photo
        // box to the photo's pixels and measures the box the template wants, then
        // pass B grows the whole template so that box lands on exactly (iw, ih).
        // The probe runs at <=1024px — layouts scale with the canvas, and pass B
        // is validated at full size; any template that does not scale linearly
        // fails E4H-FIT-NATIVE-UNSTABLE instead of silently resampling.
        let s = (1024.0f32 / iw.max(ih)).min(1.0);
        let (piw, pih) = (iw * s, ih * s);
        let probe_layout = LayoutEnv {
            pw: piw,
            ph: pih,
            crop: hand::crop_full(piw, pih),
            exif: &exif,
            params: &params,
            text: &design.text,
        };
        let (lw_a, lh_a) = crate::templates::layout(&card.template_id, &probe_layout)?;
        let cw_a = (crate::jsval::js_round(lw_a as f64) as i32).max(2);
        let ch_a = (crate::jsval::js_round(lh_a as f64) as i32).max(2);
        let probe = draw_pass(
            card,
            &design,
            &params,
            &exif,
            &photo_source,
            &images,
            &fonts,
            &te,
            &themes,
            hand::crop_full(piw, pih),
            piw,
            pih,
            cw_a,
            ch_a,
            1.0,
            None,
            None,
        )?;
        let (box_w, box_h) = (probe.photo_rect[2], probe.photo_rect[3]);
        if !(box_w > 0.0) || !(box_h > 0.0) {
            return Err(format!(
                "E4H-FIT-NATIVE-LOCKED card='{}' template='{}': the template reports no photo box",
                card.card, card.template_id
            ));
        }
        let cx = box_w / piw;
        let mismatch_px = ih * (1.0 - (box_h / pih) / cx).abs();
        if !(cx > 0.0) || !cx.is_finite() || mismatch_px > 0.5 {
            return Err(format!(
                "E4H-FIT-NATIVE-LOCKED card='{}' template='{}': photo box {:.5} vs photo {:.5} (native fit would crop {:.1}px)",
                card.card,
                card.template_id,
                box_w / box_h,
                iw / ih,
                mismatch_px
            ));
        }
        let f = 1.0 / cx;
        if f < 1.0 - 1e-6 {
            return Err(format!(
                "E4H-FIT-NATIVE-LOCKED card='{}': photo box {:.0}x{:.0} is larger than the photo {:.0}x{:.0}",
                card.card, box_w / s, box_h / s, iw, ih
            ));
        }
        let (pw_b, ph_b) = (iw * f, ih * f);
        let layout_b = LayoutEnv {
            pw: pw_b,
            ph: ph_b,
            crop: hand::crop_full(pw_b, ph_b),
            exif: &exif,
            params: &params,
            text: &design.text,
        };
        let (lw_b, lh_b) = crate::templates::layout(&card.template_id, &layout_b)?;
        let cw_b = (crate::jsval::js_round(lw_b as f64) as i32).max(2);
        let ch_b = (crate::jsval::js_round(lh_b as f64) as i32).max(2);
        let scale = if max_edge > 0 && max_edge < cw_b.max(ch_b) {
            max_edge as f32 / cw_b.max(ch_b) as f32
        } else {
            1.0
        };
        // The real crop (full photo) stays the source rect; snap forces the draw
        // box onto the photo's own pixels.
        let out = draw_pass(
            card,
            &design,
            &params,
            &exif,
            &photo_source,
            &images,
            &fonts,
            &te,
            &themes,
            view,
            pw_b,
            ph_b,
            cw_b,
            ch_b,
            scale,
            Some((iw, ih)),
            overlay.take(),
        )?;
        let resid_px = (out.photo_rect[2] - iw)
            .abs()
            .max((out.photo_rect[3] - ih).abs());
        if resid_px > 1.0 {
            return Err(format!(
                "E4H-FIT-NATIVE-UNSTABLE card='{}': after scale {:.6} the photo box is {:.2}x{:.2}, expected {:.0}x{:.0} (off {:.2}px)",
                card.card, f, out.photo_rect[2], out.photo_rect[3], iw, ih, resid_px
            ));
        }
        if out.photo_rect[0] < -0.5
            || out.photo_rect[1] < -0.5
            || out.photo_rect[0] + iw > cw_b as f32 + 0.5
            || out.photo_rect[1] + ih > ch_b as f32 + 0.5
        {
            return Err(format!(
                "E4H-FIT-NATIVE-CLIPPED card='{}': photo box at {:.1},{:.1} {:.0}x{:.0} does not fit canvas {}x{}",
                card.card, out.photo_rect[0], out.photo_rect[1], iw, ih, cw_b, ch_b
            ));
        }
        println!(
            "e4hand.fit=native photo={}x{} scale={:.6} canvas={}x{} box={:.2},{:.2} outScale={:.6}",
            iw, ih, f, cw_b, ch_b, out.photo_rect[0], out.photo_rect[1], scale
        );
        cw = cw_b;
        ch = ch_b;
        (out, scale)
    } else {
        let scale = if max_edge > 0 && max_edge < cw.max(ch) {
            max_edge as f32 / cw.max(ch) as f32
        } else {
            1.0
        };
        (
            draw_pass(
                card,
                &design,
                &params,
                &exif,
                &photo_source,
                &images,
                &fonts,
                &te,
                &themes,
                view,
                view.pw,
                view.ph,
                cw,
                ch,
                scale,
                None,
                overlay.take(),
            )?,
            scale,
        )
    };

    let out_w = ((cw as f32 * scale).round() as i32).max(1);
    let out_h = ((ch as f32 * scale).round() as i32).max(1);
    let photo_rect = pass.photo_rect;
    let mut surface = pass.surface;
    let rep = pass.report;
    // The source pixels the photo draw sampled. The draw report keeps the exact
    // src rect of every image draw, so this is the rectangle that reached Skia
    // (the template's own `cover` window and the user crop included) rather than
    // a re-derivation — the same derivation `engine/e4-hand/src/main.rs` makes.
    // The **last** `photo` draw pairs with `photo_rect`: `record_photo` overwrites
    // the box on every photo draw, so the two must come from the same draw for the
    // gain-map pass to line them up.
    let photo_src = rep
        .images
        .iter()
        .rev()
        .find(|d| d.key == "photo")
        .and_then(|d| d.src.as_ref())
        .map(|r| [r.x, r.y, r.w, r.h]);

    // GPU frames must be submitted before any pixel readback below; on the CPU
    // path this is a no-op (no GPU session → nothing to flush).
    crate::gpu::flush_active()?;
    let snapshot = crate::gpu::to_raster(&surface.image_snapshot());
    let raw_rgba = if let Some(out_png) = out_png {
        let data = snapshot
            .encode_to_data(EncodedImageFormat::PNG)
            .ok_or_else(|| "PNG encode failed".to_string())?;
        if let Some(parent) = Path::new(out_png).parent() {
            std::fs::create_dir_all(parent).ok();
        }
        std::fs::write(out_png, data.as_bytes()).map_err(|e| format!("write {out_png}: {e}"))?;
        None
    } else {
        let info = ImageInfo::new(
            (out_w, out_h),
            ColorType::RGBA8888,
            AlphaType::Opaque,
            Some(ColorSpace::new_srgb()),
        );
        let mut rgba = vec![0u8; (out_w as usize) * (out_h as usize) * 4];
        if !snapshot.read_pixels(
            &info,
            &mut rgba,
            (out_w as usize) * 4,
            (0, 0),
            skia_safe::image::CachingHint::Disallow,
        ) {
            return Err("read raster pixels failed".into());
        }
        Some(rgba)
    };

    let mut geom_doc = json!({
        "card": card.card,
        "template": card.template_id,
        "canvas": [cw, ch],
        "output": [out_w, out_h],
        "photo_rect": photo_rect,
        "fit": if fit == hand::FitMode::Native {
            json!({ "mode": "native", "photo": [iw, ih] })
        } else {
            json!({ "mode": "design" })
        },
        "report": &rep,
    });
    // `[x, y, w, h]` in the photo file's own raster space; absent when the card
    // has no photo draw at all (same contract as the desktop hand report, so
    // Kotlin's `photoSrc` feed reads both with one parser).
    if let Some(src) = photo_src {
        geom_doc["photo_src"] = json!(src);
    }
    let geom_json = serde_json::to_string_pretty(&geom_doc).unwrap_or_default();
    let geom = HandGeom {
        canvas: [cw, ch],
        output: [out_w, out_h],
        photo_rect,
        json: geom_json.clone(),
    };
    std::fs::write(geom_path, &geom_json).ok();

    let render = HandRender {
        card: card.card.clone(),
        width: out_w,
        height: out_h,
        raw_width: cw,
        raw_height: ch,
        photo_rect,
        geom: Some(geom),
        // Filled in by `render_card_impl` once it knows which backend ran.
        gpu: String::new(),
        ms: started.elapsed().as_millis(),
    };
    Ok(CardRenderProduct {
        render,
        rgba: raw_rgba,
    })
}
/**
 * Layers the app's design over the card's own one, with the same meaning the desktop
 * gives `--design`: `params` are merged over the card defaults, `text` is a partial
 * camelCase `TextSettings` laid over the card's text. `design` may be the document
 * root or the inner object.
 */
fn apply_app_design(design: &mut hand::Design, app: &serde_json::Value) -> Result<(), String> {
    let node = app.get("design").unwrap_or(app);
    if let Some(fit) = node.get("fitMode") {
        let raw = fit
            .as_str()
            .ok_or_else(|| "design.fitMode must be a string".to_string())?;
        hand::resolve_fit("", Some(raw))?;
        design.fit_mode = Some(raw.to_string());
    }
    if let Some(p) = node.get("params") {
        let over: hand::Params =
            serde_json::from_value(p.clone()).map_err(|e| format!("design.params: {e}"))?;
        design.params = hand::merge_params(&design.params, &over);
    }
    if let Some(t) = node.get("text") {
        let base = serde_json::to_value(&design.text).map_err(|e| e.to_string())?;
        let merged = deep_merge(base, t.clone());
        design.text = serde_json::from_value(merged).map_err(|e| format!("design.text: {e}"))?;
    }
    if let Some(logo) = node.get("logo") {
        design.logo = if logo.is_null() {
            None
        } else {
            Some(serde_json::from_value(logo.clone()).map_err(|e| format!("design.logo: {e}"))?)
        };
    }
    Ok(())
}

fn decode_logo_data_url(url: &str) -> Result<Vec<u8>, String> {
    let (header, payload) = url
        .split_once(',')
        .ok_or_else(|| "design.logo.dataUrl is not a valid data URL".to_string())?;
    if !header.starts_with("data:image/") || !header.ends_with(";base64") {
        return Err("design.logo.dataUrl must be a base64 image data URL".to_string());
    }
    let mut out = Vec::with_capacity(payload.len() * 3 / 4);
    let mut acc = 0u32;
    let mut bits = 0u8;
    for byte in payload.bytes() {
        let value = match byte {
            b'A'..=b'Z' => byte - b'A',
            b'a'..=b'z' => byte - b'a' + 26,
            b'0'..=b'9' => byte - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' => break,
            b'\r' | b'\n' | b' ' | b'\t' => continue,
            _ => return Err("design.logo.dataUrl contains invalid base64".to_string()),
        };
        acc = (acc << 6) | value as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
        }
    }
    if out.is_empty() {
        return Err("design.logo.dataUrl is empty".to_string());
    }
    Ok(out)
}

pub(crate) fn note(msg: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        use std::os::raw::c_char;
        extern "C" {
            fn __android_log_print(prio: i32, tag: *const c_char, fmt: *const c_char, ...) -> i32;
        }
        let tag = CString::new("FrameMarkEngine").expect("tag");
        let fmt = CString::new("%s").expect("fmt");
        let body = CString::new(msg).unwrap_or_else(|_| CString::new("e4=log").expect("fallback"));
        unsafe { __android_log_print(4, tag.as_ptr(), fmt.as_ptr(), body.as_ptr()) };
    }
    #[cfg(not(target_os = "android"))]
    println!("{msg}");
}

fn final_user_text(text: &hand::TextSettings) -> String {
    let mut out = String::new();
    for value in [
        &text.title,
        &text.subtitle,
        &text.signature,
        &text.custom_line,
        &text.location,
    ] {
        out.push_str(value);
        out.push('\n');
    }
    for value in text
        .line_text
        .iter()
        .flat_map(|values| values.values())
        .chain(text.slot_values.iter().flat_map(|values| values.values()))
    {
        out.push_str(value);
        out.push('\n');
    }
    out
}

/// 丢掉「不是用户编辑」的槽位文本：覆盖值等于卡片样片文本的那些键。
///
/// 经典双行这类卡片的样片是 PANASONIC DC-S1；设计如果从样片带过来（或由旧版本把样片
/// 文本写进设计），换一张 OPPO 照片后仍会显示 Panasonic。这里把与样片文本完全相同的
/// 覆盖去掉，让槽位回到照片自身的 EXIF；用户手动改过的文本不等于样片文本，不受影响。
fn drop_sample_slot_text(params: &mut hand::Params, card: &Card) {
    let sample: std::collections::HashSet<String> = card
        .texts
        .iter()
        .map(|t| t.s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();
    if sample.is_empty() {
        return;
    }
    params.retain(|key, value| {
        if !key.starts_with("slotText:") {
            return true;
        }
        match value.as_str() {
            Some(text) => !sample.contains(text.trim()),
            None => true,
        }
    });
}

fn text_needing_fallback(
    text: &hand::TextSettings,
    exif: &Exif,
    params: &hand::Params,
) -> String {    let mut out = format!(
        "{}\n{}\n{}\n{}\n{}\n{}",
        final_user_text(text), exif.make, exif.model, exif.lens, exif.date_time, exif.line(" ")
    );
    for key in ["stubTitle", "stubLabel"] {
        if let Some(value) = params.get(key).and_then(serde_json::Value::as_str) {
            out.push('\n');
            out.push_str(value);
        }
    }
    // 模板参数里的中文同样是「渲染器会画出来的字」。以前只扫了 stubTitle/stubLabel，
    // 于是 profileFrame 的 `profileName`（人物 01–05 的示例名「鹤羽轻风」）这类参数
    // 在 Android 上被判成「不需要 CJK 回退」→ 不装进包里那套中文，逐字画成「?」
    // （桌面 CLI 无条件装了回退字体，所以只有手机端复现）。这里把参数里的字符串
    // 全部纳入判定：宁可多装一次字体，也不能让用户看到问号。
    push_param_strings(params, &mut out);
    out
}

/// 递归收集参数里的字符串值（数组 / 对象都走），供 [text_needing_fallback] 判断。
fn push_param_strings(params: &hand::Params, out: &mut String) {
    fn walk(value: &serde_json::Value, out: &mut String) {
        match value {
            serde_json::Value::String(text) => {
                if !text.is_empty() {
                    out.push('\n');
                    out.push_str(text);
                }
            }
            serde_json::Value::Array(items) => items.iter().for_each(|item| walk(item, out)),
            serde_json::Value::Object(map) => map.values().for_each(|item| walk(item, out)),
            _ => {}
        }
    }
    params.values().for_each(|value| walk(value, out));
}


/// 照片没有自带 APP1 时，去导入时写出的 sidecar 找：`.../photos/x.png` →
/// `.../exif/x.jpg`（与 Kotlin 侧 `exifSidecarFor` 同一约定）。
fn exif_sidecar_for(photo: &Path) -> Option<std::path::PathBuf> {
    let parent = photo.parent()?;                  // .../photos
    let root = parent.parent()?;                   // files/
    let stem = photo.file_stem()?.to_str()?;
    let candidate = root.join("exif").join(format!("{stem}.jpg"));
    candidate.is_file().then_some(candidate)
}

fn deep_merge(base: serde_json::Value, over: serde_json::Value) -> serde_json::Value {
    match (base, over) {
        (serde_json::Value::Object(mut a), serde_json::Value::Object(b)) => {
            for (k, v) in b {
                let merged = match a.remove(&k) {
                    Some(prev) => deep_merge(prev, v),
                    None => v,
                };
                a.insert(k, merged);
            }
            serde_json::Value::Object(a)
        }
        (_, over) => over,
    }
}

#[cfg(test)]
mod tests {
    use super::{apply_app_design, final_user_text, text_needing_fallback};
    use crate::guard_render;

    /// WP-X1 / F2-D1 root cause: `TextBlobBuilder::make()` returns `None` for text
    /// that shapes to nothing, and `expect`-ing on it aborted the process. Empty
    /// text must shape to no blob and measure 0 — that is Canvas2D's `measureText("")`.
    #[test]
    fn empty_text_shapes_to_nothing_instead_of_panicking() {
        let te = super::text_engine();
        // Empty text returns before the typeface is used, so any face will do.
        let tf = skia_safe::FontMgr::new()
            .legacy_make_typeface(None, skia_safe::FontStyle::default())
            .expect("a default typeface");
        let (blob, width) = te.shape("", &tf, 48.0, 0.0);
        assert!(blob.is_none(), "empty text must not produce a text blob");
        assert_eq!(width, 0.0, "measureText(\"\") is 0");

        // Whitespace still goes through the shaper, but an empty blob result is
        // reported instead of unwrapped.
        let (_, spaced) = te.shape(" ", &tf, 48.0, 0.0);
        assert!(spaced >= 0.0);
    }

    /// The C ABI guard is what keeps an unknown panic from aborting the process
    /// (`extern "C"` cannot unwind).
    #[test]
    fn c_abi_guard_turns_a_panic_into_an_error() {
        let caught = guard_render::<i32>("test_render", || panic!("boom"));
        let error = caught.expect_err("a panic must surface as Err");
        assert!(error.contains("panicked"), "unexpected error: {error}");
        assert!(error.contains("boom"), "panic detail is lost: {error}");

        let ok = guard_render("test_render", || Ok::<i32, String>(7)).expect("no panic passes through");
        assert_eq!(ok, 7);
    }

    #[test]
    fn merged_text_controls_cjk_fallback_selection() {
        let mut design: crate::hand::Design = serde_json::from_value(serde_json::json!({
            "templateId": "all-latin",
            "text": { "title": "FRAME MARK" }
        }))
        .unwrap();
        assert!(!crate::fonts::contains_cjk(&final_user_text(&design.text)));

        let override_doc = serde_json::json!({
            "design": { "text": { "title": "中文边框" } }
        });
        apply_app_design(&mut design, &override_doc).unwrap();
        assert!(crate::fonts::contains_cjk(&final_user_text(&design.text)));

        let mapped: crate::hand::Design = serde_json::from_value(serde_json::json!({
            "templateId": "all-latin",
            "text": {
                "lineText": { "caption": "中文行" },
                "slotValues": { "custom": "中文槽" }
            }
        }))
        .unwrap();
        assert!(crate::fonts::contains_cjk(&final_user_text(&mapped.text)));
    }

    #[test]
    fn exif_fullwidth_camera_punctuation_selects_cjk_fallback() {
        let text: crate::hand::TextSettings = serde_json::from_value(serde_json::json!({})).unwrap();
        let exif = crate::hand::Exif {
            make: "PANASONIC".into(),
            model: "DC－S1".into(),
            ..Default::default()
        };
        assert!(crate::fonts::contains_cjk(&text_needing_fallback(
            &text,
            &exif,
            &Default::default(),
        )));

        let params = serde_json::from_value(serde_json::json!({ "stubTitle": "中文标题" })).unwrap();
        let latin_exif = crate::hand::Exif {
            make: "PANASONIC".into(),
            model: "DC-S1".into(),
            ..Default::default()
        };
        assert!(crate::fonts::contains_cjk(&text_needing_fallback(
            &text,
            &latin_exif,
            &params,
        )));
    }

    /// 人物 01–05 的示例名放在 `params.profileName` 里：这条链以前不参与「要不要装
    /// CJK 回退」的判定，Android 上逐字画成「?」。钉住它，别再退回去。
    #[test]
    fn chinese_in_card_params_selects_the_bundled_cjk_face() {
        let text: crate::hand::TextSettings = serde_json::from_value(serde_json::json!({})).unwrap();
        let params: crate::hand::Params =
            serde_json::from_value(serde_json::json!({ "profileName": "鹤羽轻风" })).unwrap();
        assert!(crate::fonts::contains_cjk(&text_needing_fallback(
            &text,
            &Default::default(),
            &params,
        )));
    }

    /// 中文也可能藏在嵌套的数组 / 对象里（例如毕业季的贴纸配置）。
    #[test]
    fn chinese_nested_in_params_selects_the_bundled_cjk_face() {
        let text: crate::hand::TextSettings = serde_json::from_value(serde_json::json!({})).unwrap();
        let params: crate::hand::Params = serde_json::from_value(serde_json::json!({
            "stickers": [{ "label": "毕业快乐" }]
        }))
        .unwrap();
        assert!(crate::fonts::contains_cjk(&text_needing_fallback(
            &text,
            &Default::default(),
            &params,
        )));
    }

    /// 纯拉丁参数不该把 10MB 的中文字体白装一遍。
    #[test]
    fn latin_only_params_do_not_select_the_cjk_face() {
        let text: crate::hand::TextSettings = serde_json::from_value(serde_json::json!({})).unwrap();
        let params: crate::hand::Params =
            serde_json::from_value(serde_json::json!({ "profileName": "Haru Kazama" })).unwrap();
        assert!(!crate::fonts::contains_cjk(&text_needing_fallback(
            &text,
            &Default::default(),
            &params,
        )));
    }
}

/// The hand bridge's copy of the crop maths is `engine/e4-hand/src/hand.rs`'s
/// `crop_window` (pulled in read-only via `#[path]`), which is why these cases
/// are the desktop's: if they ever disagree, the phone renders a different frame
/// from the same document. They pin exactly what the phone did before the crop
/// existed — a `crop` that reaches the draw untouched.
#[cfg(test)]
mod photo_crop_tests {
    use crate::hand::{compute_crop, cover_src, crop_full, crop_window, Crop, UserCrop};

    fn same(a: Crop, b: Crop) -> bool {
        a.sx == b.sx && a.sy == b.sy && a.sw == b.sw && a.sh == b.sh && a.pw == b.pw && a.ph == b.ph
    }

    /// The app's design document is the only way the crop reaches this bridge;
    /// if a param is lost between `apply_app_design` and `UserCrop::read`, the
    /// user's window silently reverts to the engine default. This is the exact
    /// document the phone logs for the glass-white-1 card.
    #[test]
    fn app_design_crop_reaches_the_window() {
        let mut design: crate::hand::Design =
            serde_json::from_value(serde_json::json!({ "templateId": "all-latin" })).unwrap();
        let app = serde_json::json!({ "design": {
            "cardId": "glass-white-1",
            "styleId": null,
            "params": { "blur": 39, "photoCropX": 0.28422307362779975, "theme": "white" },
            "text": {}
        }});
        super::apply_app_design(&mut design, &app).unwrap();
        let params = crate::hand::merge_params(&Default::default(), &design.params);
        let user = crate::hand::UserCrop::read(&params);
        assert!(
            !user.is_default(),
            "crop lost before crop_window: {user:?} params={params:?}"
        );
        let base = compute_crop(8192.0, 6144.0, "");
        let view = crop_window(base, 8192.0, 6144.0, user);
        assert!(view.sx < 1000.0, "window did not move left: {view:?}");
    }

    /// 4000x3000 photo in the template's `1:1` window: `cover` keeps 3000x3000
    /// at 500,0, and the default crop must keep exactly that.
    #[test]
    fn defaults_reproduce_todays_window() {
        let base = compute_crop(4000.0, 3000.0, "1:1");
        assert_eq!(
            (base.sx, base.sy, base.sw, base.sh, base.pw, base.ph),
            (500.0, 0.0, 3000.0, 3000.0, 3000.0, 3000.0)
        );
        let view = crop_window(base, 4000.0, 3000.0, UserCrop::default());
        assert!(same(view, base), "default crop changed the window: {view:?}");

        // A design-ratio window keeps the whole photo and stays untouched too.
        let full = compute_crop(4000.0, 3000.0, "");
        let view = crop_window(full, 4000.0, 3000.0, UserCrop::default());
        assert!(same(view, full));
        assert!(UserCrop::read(&Default::default()).is_default());
    }

    #[test]
    fn zoom_two_halves_the_sampled_extent() {
        let base = compute_crop(4000.0, 3000.0, "1:1");
        let view = crop_window(
            base,
            4000.0,
            3000.0,
            UserCrop {
                cx: 0.5,
                cy: 0.5,
                zoom: 2.0,
            },
        );
        assert_eq!((view.sw, view.sh), (1500.0, 1500.0));
        // Centred on the base window's centre (2000, 1500).
        assert_eq!((view.sx, view.sy), (1250.0, 750.0));
        // `pw`/`ph` are the layout's, not the sample's: the box never moves.
        assert_eq!((view.pw, view.ph), (base.pw, base.ph));
        // Keeping the same aspect as the base window means the draw never warps.
        assert!((view.sw / view.sh - base.sw / base.sh).abs() < 1e-5);
    }

    #[test]
    fn corner_pan_clamps_into_the_image() {
        let base = crop_full(4000.0, 3000.0);
        let top_left = crop_window(
            base,
            4000.0,
            3000.0,
            UserCrop {
                cx: 0.0,
                cy: 0.0,
                zoom: 2.0,
            },
        );
        assert_eq!((top_left.sx, top_left.sy, top_left.sw, top_left.sh), (0.0, 0.0, 2000.0, 1500.0));
        let bottom_right = crop_window(
            base,
            4000.0,
            3000.0,
            UserCrop {
                cx: 1.0,
                cy: 1.0,
                zoom: 2.0,
            },
        );
        assert_eq!(
            (bottom_right.sx, bottom_right.sy, bottom_right.sw, bottom_right.sh),
            (2000.0, 1500.0, 2000.0, 1500.0)
        );
        // A centre outside 0..1 is clamped, never pushed off the photo.
        let off = crop_window(
            base,
            4000.0,
            3000.0,
            UserCrop {
                cx: -3.0,
                cy: 4.0,
                zoom: 2.0,
            },
        );
        assert_eq!((off.sx, off.sy), (0.0, 1500.0));
        // The same pan inside a 1:1 template window still reaches the photo's
        // own edge: the origin clamps to the image, not to the design window.
        let square = compute_crop(4000.0, 3000.0, "1:1");
        let square_tl = crop_window(
            square,
            4000.0,
            3000.0,
            UserCrop {
                cx: 0.0,
                cy: 0.0,
                zoom: 2.0,
            },
        );
        assert_eq!((square_tl.sx, square_tl.sy), (0.0, 0.0));
        let square_br = crop_window(
            square,
            4000.0,
            3000.0,
            UserCrop {
                cx: 1.0,
                cy: 1.0,
                zoom: 2.0,
            },
        );
        assert_eq!((square_br.sx, square_br.sy), (2500.0, 1500.0));
        // ...and the window never leaves the photo.
        for view in [top_left, bottom_right, off, square_tl, square_br] {
            assert!(view.sx >= 0.0 && view.sy >= 0.0);
            assert!(view.sx + view.sw <= 4000.0 && view.sy + view.sh <= 3000.0);
        }
    }

    /// The phone's primary crop gesture is a drag, and at zoom 1 the extent
    /// does not change — so the origin must follow the user's point inside the
    /// image. Clamping it to the design window made "drag to reposition" a
    /// no-op on every template whose window is the whole photo.
    #[test]
    fn pan_at_zoom_one_moves_the_window() {
        let base = compute_crop(4000.0, 3000.0, "1:1"); // 3000x3000 at 500,0
        let left = crop_window(
            base,
            4000.0,
            3000.0,
            UserCrop {
                cx: 0.0,
                cy: 0.5,
                zoom: 1.0,
            },
        );
        assert_eq!(left.sx, 0.0, "pan left must clamp to the photo's left edge");
        assert_ne!(left.sx, 500.0, "pan at zoom 1 must leave the centred origin");
        let right = crop_window(
            base,
            4000.0,
            3000.0,
            UserCrop {
                cx: 0.75,
                cy: 0.5,
                zoom: 1.0,
            },
        );
        assert_eq!((right.sx, right.sw), (1000.0, 3000.0));
        assert!(right.sx > 0.0 && right.sx != 500.0);
        // A window as wide as the photo has no horizontal slack; the slice is
        // chosen by `cover_src` instead (next test).
        let full = crop_full(4000.0, 3000.0);
        let view = crop_window(
            full,
            4000.0,
            3000.0,
            UserCrop {
                cx: 0.75,
                cy: 0.5,
                zoom: 1.0,
            },
        );
        assert_eq!((view.sx, view.sw), (0.0, 4000.0));
    }

    /// `glass-white-1` draws a portrait box over a 4:3 photo with the whole
    /// photo as its window, so this is the exact shape that used to ignore the
    /// drag: at zoom 1 the cover slice must slide with the user.
    #[test]
    fn cover_slice_moves_with_the_user_at_zoom_one() {
        let full = crop_full(8192.0, 6144.0);
        let (w, h) = (3778.86, 5460.79);
        let default = cover_src(full, UserCrop::default(), 8192.0, 6144.0, w, h);
        assert!((default[2] - 4251.65).abs() < 0.5, "{default:?}");
        assert!((default[3] - 6144.0).abs() < 1e-3, "{default:?}");
        assert!((default[0] - (8192.0 - default[2]) / 2.0).abs() < 1e-3, "{default:?}");

        let cx = 0.28422307362779975;
        let panned = cover_src(
            full,
            UserCrop {
                cx,
                cy: 0.5,
                zoom: 1.0,
            },
            8192.0,
            6144.0,
            w,
            h,
        );
        assert!((panned[0] - (cx as f32 * 8192.0 - panned[2] / 2.0)).abs() < 1e-2);
        assert_ne!(panned[0], default[0], "pan must not keep the centred slice");
        assert!(panned[0] >= 0.0 && panned[0] + panned[2] <= 8192.0 + 1e-3);

        // The left corner clamps onto the raster edge, not onto a negative one.
        let edge = cover_src(
            full,
            UserCrop {
                cx: 0.0,
                cy: 0.0,
                zoom: 1.0,
            },
            8192.0,
            6144.0,
            w,
            h,
        );
        assert_eq!((edge[0], edge[1]), (0.0, 0.0));

        // zoom 2: the narrowed window still leaves the cover slice centred on
        // the user's point — the same rectangle the phone produced before.
        let view = crop_window(
            full,
            8192.0,
            6144.0,
            UserCrop {
                cx,
                cy: 0.5,
                zoom: 2.0,
            },
        );
        let src = cover_src(
            view,
            UserCrop {
                cx,
                cy: 0.5,
                zoom: 2.0,
            },
            8192.0,
            6144.0,
            w,
            h,
        );
        assert!(src[0] >= view.sx - 1e-2 && src[0] + src[2] <= view.sx + view.sw + 1e-2);
        assert!((src[0] - 1265.44).abs() < 0.5, "{src:?}");
        assert!((src[2] - 2125.82).abs() < 0.5, "{src:?}");
    }

    #[test]
    fn one_pixel_images_never_produce_an_empty_window() {
        let tiny = crop_full(1.0, 1.0);
        let view = crop_window(
            tiny,
            1.0,
            1.0,
            UserCrop {
                cx: 0.0,
                cy: 0.0,
                zoom: 8.0,
            },
        );
        assert_eq!(
            (view.sx, view.sy, view.sw, view.sh),
            (0.0, 0.0, 1.0, 1.0),
            "a 1 px photo must still sample 1 px"
        );
        // One axis is a single pixel, the other is not: neither may collapse.
        let slab = crop_full(1.0, 4000.0);
        let view = crop_window(
            slab,
            1.0,
            4000.0,
            UserCrop {
                cx: 1.0,
                cy: 1.0,
                zoom: 8.0,
            },
        );
        assert_eq!(view.sw, 1.0);
        assert!(view.sh >= 1.0 && view.sy + view.sh <= 4000.0 + 1e-3);
        // A source or window that cannot describe a rectangle keeps the base.
        let base = crop_full(4000.0, 3000.0);
        assert!(same(
            crop_window(
                base,
                0.0,
                3000.0,
                UserCrop {
                    cx: 0.0,
                    cy: 0.0,
                    zoom: 2.0,
                },
            ),
            base
        ));
        assert!(same(
            crop_window(
                Crop::default(),
                4000.0,
                3000.0,
                UserCrop {
                    cx: 0.0,
                    cy: 0.0,
                    zoom: 2.0,
                },
            ),
            Crop::default()
        ));
        // Zoom below the UI's 1 and non-finite centres are the defaults, not a
        // degenerate window.
        let below = crop_window(
            base,
            4000.0,
            3000.0,
            UserCrop {
                cx: 0.5,
                cy: 0.5,
                zoom: 0.0,
            },
        );
        assert!(same(below, base));
        let nan = crop_window(
            base,
            4000.0,
            3000.0,
            UserCrop {
                cx: f32::NAN,
                cy: f32::NAN,
                zoom: f32::NAN,
            },
        );
        assert!(same(nan, base));
    }
}

/// 方向只应用一次（2026-11-20 真机缺陷）：JPEG/WebP 的像素已经由 Skia 按 EXIF origin
/// 解好，`load_image` 不能再转一次。以前用「宽高是否互换」猜 Skia 有没有转过 —— 镜像(2)、
/// 180°(3)、镜像+180°(4) 宽高不变，猜不出来，于是二次旋转把方向还原：预览是正的、导出
/// 是反的。夹具在 `engine/shared/orientation_fixture.rs`（IR bridge 用同一份）。
#[cfg(test)]
mod orientation_tests {
    use super::load_image;
    use crate::orientation_fixture::{
        corner, is_blue, is_green, is_red, is_white, temp_jpeg,
    };
    use skia_safe::Image;

    fn decoded_at(orientation: u16) -> Image {
        let path = temp_jpeg(orientation);
        let image = load_image(&path).expect("decode orientation fixture");
        let _ = std::fs::remove_file(&path);
        image
    }

    #[test]
    fn orientation_1_keeps_the_quadrants() {
        let image = decoded_at(1);
        assert_eq!((image.width(), image.height()), (64, 48));
        assert!(is_red(corner(&image)), "TL 应该还是红");
    }

    /// 2 = 水平镜像：红蓝互换。宽高不变，正是「靠尺寸猜不出来」的那一档。
    #[test]
    fn orientation_2_mirrors_once() {
        let image = decoded_at(2);
        assert_eq!((image.width(), image.height()), (64, 48));
        let c = corner(&image);
        assert!(is_blue(c), "镜像后 TL 应为蓝，实际 {c:?}");
    }

    /// 3 = 180°：对角互换。
    #[test]
    fn orientation_3_rotates_once() {
        let image = decoded_at(3);
        assert_eq!((image.width(), image.height()), (64, 48));
        let c = corner(&image);
        assert!(is_white(c), "180° 后 TL 应为白，实际 {c:?}");
    }

    /// 4 = 垂直镜像：上下互换。
    #[test]
    fn orientation_4_flips_vertically_once() {
        let image = decoded_at(4);
        assert_eq!((image.width(), image.height()), (64, 48));
        let c = corner(&image);
        assert!(is_green(c), "垂直镜像后 TL 应为绿，实际 {c:?}");
    }

    /// 6 = 顺时针 90°：底左（绿）转到上左，宽高互换。
    #[test]
    fn orientation_6_rotates_and_swaps_dimensions_once() {
        let image = decoded_at(6);
        assert_eq!((image.width(), image.height()), (48, 64), "90° 后宽高互换");
        let c = corner(&image);
        assert!(is_green(c), "顺时针 90° 后 TL 应为绿，实际 {c:?}");
    }

    /// 8 = 逆时针 90°：右上（蓝）转到上左。
    #[test]
    fn orientation_8_rotates_the_other_way_once() {
        let image = decoded_at(8);
        assert_eq!((image.width(), image.height()), (48, 64));
        let c = corner(&image);
        assert!(is_blue(c), "逆时针 90° 后 TL 应为蓝，实际 {c:?}");
    }
}
