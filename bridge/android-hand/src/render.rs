//! Hand-template render: the same steps `engine/e4-hand/src/main.rs` runs for
//! `render`, with a CPU raster surface (no D3D12/ANGLE) and an optional scale.

use std::cell::RefCell;
use std::path::Path;
use std::time::Instant;

use serde_json::json;
use skia_safe::{AlphaType, ColorSpace, ColorType, Data, EncodedImageFormat, Image, ImageInfo, Surface};

use crate::canvas::{Ctx2D, OverlaySpec};
use crate::draw::{Env, Images, LayoutEnv};
use crate::fonts::HandFonts;
use crate::hand::{self, Card, Exif};
use crate::report::Report;
use crate::text::TextEngine;

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
    pub ms: u128,
}

/// EXIF Orientation (IFD0 tag 0x0112) from a JPEG APP1 block or a WebP EXIF
/// chunk; 1 when absent. Mirrors the IR bridge so hand photos orient the same.
fn exif_orientation(bytes: &[u8]) -> u16 {
    if bytes.len() > 4 && bytes[0] == 0xFF && bytes[1] == 0xD8 {
        let mut i = 2usize;
        while i + 4 <= bytes.len() {
            if bytes[i] != 0xFF {
                break;
            }
            let marker = bytes[i + 1];
            if marker == 0xD8 || marker == 0x01 || (0xD0..=0xD7).contains(&marker) {
                i += 2;
                continue;
            }
            if marker == 0xD9 || marker == 0xDA {
                break;
            }
            let len = u16::from_be_bytes([bytes[i + 2], bytes[i + 3]]) as usize;
            if len < 2 || i + 2 + len > bytes.len() {
                break;
            }
            if marker == 0xE1 {
                let p = &bytes[i + 4..i + 2 + len];
                if p.len() > 6 && &p[..6] == b"Exif\0\0" {
                    return crate::tiffsrc::orientation(&p[6..]);
                }
            }
            i += 2 + len;
        }
        return 1;
    }
    if bytes.len() > 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        let mut i = 12usize;
        while i + 8 <= bytes.len() {
            let fourcc = &bytes[i..i + 4];
            let size = u32::from_le_bytes([bytes[i + 4], bytes[i + 5], bytes[i + 6], bytes[i + 7]]) as usize;
            let start = i + 8;
            let end = start.saturating_add(size).min(bytes.len());
            if fourcc == b"EXIF" {
                let d = &bytes[start..end];
                let tiff = if d.len() > 6 && &d[..6] == b"Exif\0\0" { &d[6..] } else { d };
                return crate::tiffsrc::orientation(tiff);
            }
            i = start + size + (size & 1);
        }
    }
    1
}

/// Apply the EXIF orientation unless Skia already did (see the IR bridge):
/// the codec's pre-rotation dimensions decide, so JPEG never rotates twice.
fn orient_decoded(image: Image, bytes: &[u8]) -> Result<Image, String> {
    let orientation = exif_orientation(bytes);
    if orientation <= 1 || orientation > 8 {
        return Ok(image);
    }
    if let Some(codec) = skia_safe::codec::Codec::from_data(Data::new_copy(bytes)) {
        let d = codec.dimensions();
        if image.width() != d.width || image.height() != d.height {
            return Ok(image);
        }
    }
    rotate_by_orientation(image, orientation)
}

fn rotate_by_orientation(image: Image, orientation: u16) -> Result<Image, String> {
    if orientation <= 1 || orientation > 8 {
        return Ok(image);
    }
    let (w, h) = (image.width(), image.height());
    let info = skia_safe::ImageInfo::new(
        (w, h),
        skia_safe::ColorType::RGBA8888,
        skia_safe::AlphaType::Unpremul,
        Some(skia_safe::ColorSpace::new_srgb()),
    );
    let mut rgba = vec![0u8; (w as usize) * (h as usize) * 4];
    let ok = image.read_pixels(&info, &mut rgba, (w as usize) * 4, (0, 0), skia_safe::image::CachingHint::Disallow);
    if !ok {
        return Err("read_pixels failed for orientation".into());
    }
    let (rot, nw, nh) = crate::tiffsrc::apply_orientation(&rgba, w as u32, h as u32, orientation);
    let out_info = skia_safe::ImageInfo::new(
        (nw as i32, nh as i32),
        skia_safe::ColorType::RGBA8888,
        skia_safe::AlphaType::Unpremul,
        Some(skia_safe::ColorSpace::new_srgb()),
    );
    skia_safe::images::raster_from_data(&out_info, Data::new_copy(&rot), (nw as usize) * 4)
        .ok_or_else(|| "orientation raster failed".into())
}

/// Skia decodes JPEG/PNG/WebP but no TIFF; `.tif/.tiff` uses the E5 decoder
/// (same as the IR bridge) plus the EXIF orientation step. JPEG/WebP get their
/// EXIF orientation applied; orientation 1 keeps the untouched decode path.
fn load_image(p: &Path) -> Result<Image, String> {
    let bytes = std::fs::read(p).map_err(|e| format!("missing image {}: {e}", p.display()))?;
    let is_tiff = bytes.len() >= 4 && (&bytes[..4] == b"II*\0" || &bytes[..4] == b"MM\0*");
    if !is_tiff {
        let image = Image::from_encoded(Data::new_copy(&bytes))
            .ok_or_else(|| format!("decode failed {}", p.display()))?;
        return orient_decoded(image, &bytes);
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

fn text_engine() -> TextEngine {
    let mut te = TextEngine::new();
    te.subpixel = true;
    te.linear_metrics = true;
    te.edging = skia_safe::font::Edging::AntiAlias;
    te.embolden = false;
    te.stroke_w = 0.0;
    te.hinting = match std::env::var("E4H_HINTING").unwrap_or_else(|_| "slight".into()).as_str() {
        "none" => skia_safe::FontHinting::None,
        "normal" => skia_safe::FontHinting::Normal,
        "full" => skia_safe::FontHinting::Full,
        _ => skia_safe::FontHinting::Slight,
    };
    te
}

/// `draw.ts textScaleOf`: clamp 0.5..2; anything within 0.001 of 1 is exactly 1.
fn text_scale_of(raw: f64) -> f32 {
    let s = if raw.is_finite() { raw.clamp(0.5, 2.0) } else { 1.0 };
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
/// size, so the source pixels land 1:1.
#[allow(clippy::too_many_arguments)]
fn draw_pass(
    card: &Card,
    design: &hand::Design,
    params: &hand::Params,
    exif: &Exif,
    photo: &Image,
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
    let mut surface = skia_safe::surfaces::raster(&info, None, None)
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
        ctx.set_overlay(overlay);
        let mut env = Env {
            ctx,
            w: cw as f32,
            h: ch as f32,
            pw,
            ph,
            crop,
            photo,
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
        };
        match crate::templates::render(&mut env) {
            Ok(r) => photo_rect = [r.left, r.top, r.width(), r.height()],
            Err(e) => render_err = Some(e),
        }
    }
    if let Some(e) = render_err {
        return Err(e);
    }
    Ok(PassOut {
        surface,
        photo_rect,
        report: report_cell.into_inner(),
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
    mut overlay: Option<OverlaySpec>,
    // The app's design document (`{"design":{params,text}}`), merged over the card's
    // own design exactly like the desktop `--design` path; `None` keeps the card as is.
    app_design: Option<&serde_json::Value>,
    out_png: &str,
    max_edge: i32,
) -> Result<HandRender, String> {
    let started = Instant::now();
    let cards = hand::load_cards(Path::new(cards_path))?;
    let card: &Card = cards
        .cards
        .iter()
        .find(|c| c.card == card_id)
        .ok_or_else(|| format!("card '{card_id}' not in {cards_path}"))?;
    let mut design = hand::load_design(&card.design)?;
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

    let photo = load_image(photo_override.unwrap_or(&card.photo.path))?;
    let (iw, ih) = (photo.width() as f32, photo.height() as f32);
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
            println!("e4hand.fit=native photo={}x{} lockedAspect={:.6}", iw, ih, iw / ih);
            hand::crop_full(iw, ih)
        }
    };

    // A1: a user photo carries its own shooting information. The card's sample
    // EXIF is only used when no user photo was supplied (bundled sample preview).
    let exif: Exif = match photo_override {
        Some(p) if !p.is_empty() => crate::exif::read_photo(Path::new(p)),
        _ => card.exif.clone().unwrap_or_default(),
    };
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
    if let Some(logo) = &card.logo {
        images.logo = Some(load_image(&logo.path)?);
    }
    for extra in &card.extras {
        let img = load_image(&extra.png)?;
        match extra.kind.as_str() {
            "graduation-sticker" => images.sticker = Some(img),
            "polaroid-frame" => images.frame = Some(img),
            _ => {}
        }
    }

    let fonts = HandFonts::load(Path::new(fonts_dir), Path::new(extra_fonts))?;
    let mut te = text_engine();
    te.fallback = fonts.fallback.clone();
    te.fallback_bold = fonts.fallback_bold.clone();
    // Deterministic CJK fallback: when the card's design carries CJK and a
    // bundled face is provided, use it instead of the platform's system face.
    if let Some(path) = fallback_cjk {
        let design_raw = std::fs::read_to_string(&card.design).unwrap_or_default();
        let has_cjk = design_raw.chars().any(|c| ('一'..='鿿').contains(&c) || ('　'..='〿').contains(&c));
        if has_cjk {
            if let Some(data) = Data::from_filename(path) {
                if let Some(face) = skia_safe::FontMgr::new().new_from_data(data, None) {
                    te.fallback = Some(face.clone());
                    te.fallback_bold = Some(face);
                }
            }
        }
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
            card, &design, &params, &exif, &photo, &images, &fonts, &te, &themes,
            hand::crop_full(piw, pih), piw, pih, cw_a, ch_a, 1.0, None, None,
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
            card, &design, &params, &exif, &photo, &images, &fonts, &te, &themes,
            crop, pw_b, ph_b, cw_b, ch_b, scale, Some((iw, ih)), overlay.take(),
        )?;
        let resid_px = (out.photo_rect[2] - iw).abs().max((out.photo_rect[3] - ih).abs());
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
                card, &design, &params, &exif, &photo, &images, &fonts, &te, &themes,
                crop, crop.pw, crop.ph, cw, ch, scale, None, overlay.take(),
            )?,
            scale,
        )
    };

    let out_w = ((cw as f32 * scale).round() as i32).max(1);
    let out_h = ((ch as f32 * scale).round() as i32).max(1);
    let photo_rect = pass.photo_rect;
    let mut surface = pass.surface;
    let rep = pass.report;

    let snapshot = surface.image_snapshot();
    let data = snapshot
        .encode_to_data(EncodedImageFormat::PNG)
        .ok_or_else(|| "PNG encode failed".to_string())?;
    if let Some(parent) = Path::new(out_png).parent() {
        std::fs::create_dir_all(parent).ok();
    }
    std::fs::write(out_png, data.as_bytes()).map_err(|e| format!("write {out_png}: {e}"))?;

    let geom_doc = json!({
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
    let geom_json = serde_json::to_string_pretty(&geom_doc).unwrap_or_default();
    let geom = HandGeom {
        canvas: [cw, ch],
        output: [out_w, out_h],
        photo_rect,
        json: geom_json.clone(),
    };
    std::fs::write(format!("{out_png}.geom.json"), &geom_json).ok();

    Ok(HandRender {
        card: card.card.clone(),
        width: out_w,
        height: out_h,
        raw_width: cw,
        raw_height: ch,
        photo_rect,
        geom: Some(geom),
        ms: started.elapsed().as_millis(),
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
        let raw = fit.as_str().ok_or_else(|| "design.fitMode must be a string".to_string())?;
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
        design.text =
            serde_json::from_value(merged).map_err(|e| format!("design.text: {e}"))?;
    }
    Ok(())
}

fn note(msg: &str) {
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
