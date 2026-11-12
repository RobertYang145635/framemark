//! Paint path for the X1 Android slice.
//!
//! Field-by-field port of the draw steps `engine/e2b/src/main.rs` performs:
//! photo, whole-band rotation, group plates, `contain`/`cover` images, vector
//! art, brand-logo slots, theme background/foreground, Canvas2D text with the
//! D-021 baseline, outline and shadow. e2b itself is untouched: the IR solver,
//! text engine, EXIF reader and brand registry are pulled in with `#[path]`.

use std::collections::HashMap;
use std::path::Path;

use serde_json::Value;
use skia_safe::{
    canvas::SrcRectConstraint, AlphaType, Canvas, ClipOp, Color, ColorSpace, ColorType, Data,
    EncodedImageFormat, FilterMode, Image, ImageInfo, Paint, Rect, SamplingOptions, Surface,
};

use crate::{brand, exif, ir, text};

const VECTOR_ART: &str = include_str!("../../../engine/e2b/src/vivo_vector_art.json");

/// render-v1 `overlay`: user watermark above the photo and below the template's
/// border/text, rect normalised to the photo draw box.
pub struct OverlayLayer {
    pub image: Image,
    pub rect: [f32; 4],
    pub opacity: f32,
    pub angle: f32,
}

#[derive(Default)]
pub struct RenderOptions<'a> {
    /// Theme id (`--theme`); `None` uses the style's `defaultTheme`.
    pub theme: Option<&'a str>,
    /// The render-v1 design document as JSON (`{"design":{cardId,styleId,params,text}}`),
    /// i.e. exactly the file the desktop hands to `e2b --design`. `None`/empty keeps the
    /// pre-wire path: every override resolves to `Overrides::default()` and font scale 1.
    pub design_json: Option<&'a str>,
    /// Centre-crop the photo to this aspect (w:h) before layout; `None` = original.
    pub ratio: Option<(f32, f32)>,
    /// Directory of decoded brand wordmark PNGs (empty/None = no brand slots).
    pub brands_dir: Option<&'a str>,
    /// Optional fixed fallback faces: `thai.ttf` / `cjk.ttf` in this dir.
    pub fallback_dir: Option<&'a str>,
    /// Optional user watermark layer (already decoded).
    pub overlay: Option<OverlayLayer>,
}

/// Loads and decodes the overlay image with render-v1 error wording.
pub(crate) fn load_overlay_image(path: &str) -> Result<Image, String> {
    if !Path::new(path).is_file() {
        return Err(format!("overlay image missing: {path}"));
    }
    match load_photo(path) {
        Ok(image) => Ok(image),
        Err(_) => Err(format!("overlay image decode failed: {path}")),
    }
}

pub struct RenderStats {
    pub style: String,
    pub width: i32,
    pub height: i32,
    pub layout_w: f32,
    pub layout_h: f32,
    pub texts: usize,
    pub images: usize,
    pub groups: usize,
    pub shapes: usize,
    pub brand_slots: usize,
    pub warnings: Vec<String>,
    pub geom_json: String,
}

#[derive(Default)]
struct ItemGeom {
    kind: &'static str,
    rect: [f32; 4],
    draw: Option<[f32; 4]>,
    fs: Option<f32>,
    /// Exact engine path, independent of draw order and EXIF content.
    path: Option<String>,
    lines: Option<Vec<String>>,
}

#[derive(Default)]
struct BandGeom {
    rect: [f32; 4],
    items: Vec<ItemGeom>,
}

struct Ctx<'a> {
    k: f32,
    cw: f32,
    ch: f32,
    fonts: &'a text::FontSet,
    te: &'a text::TextEngine,
    images: &'a HashMap<String, Image>,
    brands: &'a mut brand::BrandLib,
    brand_key: &'a Option<String>,
    brand_dark: bool,
    /// Design control overrides, so the draw stage can apply `slotAlpha` the way
    /// e2b main.rs does (the solver handles text/off/gravity/anchor itself).
    ov: &'a ir::Overrides,
    stats: &'a mut RenderStats,
    geom: &'a mut Vec<BandGeom>,
}

/// EXIF Orientation (IFD0 tag 0x0112) from a JPEG APP1 block or a WebP EXIF
/// chunk; 1 when absent/unreadable. Phones store portrait shots as landscape
/// pixels + Orientation 6/8, so decoding without this shows them sideways.
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
            let size = u32::from_le_bytes([bytes[i + 4], bytes[i + 5], bytes[i + 6], bytes[i + 7]])
                as usize;
            let start = i + 8;
            let end = start.saturating_add(size).min(bytes.len());
            if fourcc == b"EXIF" {
                let d = &bytes[start..end];
                let tiff = if d.len() > 6 && &d[..6] == b"Exif\0\0" {
                    &d[6..]
                } else {
                    d
                };
                return crate::tiffsrc::orientation(tiff);
            }
            i = start + size + (size & 1);
        }
    }
    1
}

/// Apply the EXIF orientation unless Skia already did. Skia m156's
/// `Image::from_encoded` returns the *oriented* image for JPEG, so rotating
/// again would undo it; the codec's pre-rotation dimensions tell us which case
/// we are in. Orientation 1 stays on the untouched decode path.
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

/// Rotate a decoded image by its EXIF orientation (1 = passthrough so the
/// untouched decode path stays byte-identical).
fn rotate_by_orientation(image: Image, orientation: u16) -> Result<Image, String> {
    if orientation <= 1 || orientation > 8 {
        return Ok(image);
    }
    let (w, h) = (image.width(), image.height());
    let info = ImageInfo::new(
        (w, h),
        ColorType::RGBA8888,
        AlphaType::Unpremul,
        Some(ColorSpace::new_srgb()),
    );
    let mut rgba = vec![0u8; (w as usize) * (h as usize) * 4];
    let ok = image.read_pixels(
        &info,
        &mut rgba,
        (w as usize) * 4,
        (0, 0),
        skia_safe::image::CachingHint::Disallow,
    );
    if !ok {
        return Err("read_pixels failed for orientation".into());
    }
    let (rot, nw, nh) = crate::tiffsrc::apply_orientation(&rgba, w as u32, h as u32, orientation);
    let out_info = ImageInfo::new(
        (nw as i32, nh as i32),
        ColorType::RGBA8888,
        AlphaType::Unpremul,
        Some(ColorSpace::new_srgb()),
    );
    skia_safe::images::raster_from_data(&out_info, Data::new_copy(&rot), (nw as usize) * 4)
        .ok_or_else(|| "orientation raster failed".into())
}

/// Skia decodes JPEG/PNG/WebP but has no TIFF codec. `.tif/.tiff` goes through
/// the desktop E5 decoder (`e5::tiffsrc`, pulled in read-only); JPEG/WebP get
/// their EXIF orientation applied. PNG has no orientation tag in practice.
fn load_photo(path: &str) -> Result<Image, String> {
    let bytes = std::fs::read(path).map_err(|e| format!("photo missing: {path}: {e}"))?;
    let is_tiff = bytes.len() >= 4 && (&bytes[..4] == b"II*\0" || &bytes[..4] == b"MM\0*");
    if !is_tiff {
        let image = Image::from_encoded(Data::new_copy(&bytes))
            .ok_or_else(|| format!("photo decode failed: {path}"))?;
        return orient_decoded(image, &bytes);
    }
    let img = crate::tiffsrc::decode(Path::new(path))?;
    let (rgba, w, h) = crate::tiffsrc::apply_orientation(&img.rgba, img.w, img.h, img.orientation);
    let info = ImageInfo::new(
        (w as i32, h as i32),
        ColorType::RGBA8888,
        AlphaType::Unpremul,
        Some(ColorSpace::new_srgb()),
    );
    skia_safe::images::raster_from_data(&info, Data::new_copy(&rgba), (w as usize) * 4)
        .ok_or_else(|| format!("tiff raster failed: {path}"))
}

/// Engine-side notes. On Android a plain `println!` from an `.so` goes to a stdout
/// nobody reads, so the `e3.*` contract lines are pushed into logcat under the same
/// tag the JNI shim already uses; on the host they stay stdout.
pub(crate) fn engine_note(msg: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        use std::os::raw::c_char;
        extern "C" {
            fn __android_log_print(prio: i32, tag: *const c_char, fmt: *const c_char, ...) -> i32;
        }
        let tag = CString::new("FrameMarkEngine").expect("tag");
        let fmt = CString::new("%s").expect("fmt");
        let body = CString::new(msg).unwrap_or_else(|_| CString::new("e3=log").expect("fallback"));
        unsafe {
            __android_log_print(
                4, /* ANDROID_LOG_INFO */
                tag.as_ptr(),
                fmt.as_ptr(),
                body.as_ptr(),
            )
        };
    }
    #[cfg(not(target_os = "android"))]
    println!("{msg}");
}

/// Reads the app's render-v1 design document into the same `ir::Overrides` the
/// desktop hands to `e2b --design`, plus the `/design/text/fontScale` the desktop
/// resolves at main.rs:595-616. No document (or an empty string) is the strict
/// no-op path: `Overrides::default()` and font scale exactly 1.0.
/// Everything the app's design document contributes to one render: the e2b override
/// struct, the font scale, and the three display toggles that `ir::Env` exposes but
/// neither e2b's own `main.rs` nor this bridge ever set from a document before.
pub(crate) struct DesignBits {
    pub ov: ir::Overrides,
    pub font_scale: f32,
    pub show_model: bool,
    pub show_exif: bool,
    pub show_date: bool,
}

impl Default for DesignBits {
    fn default() -> Self {
        DesignBits {
            ov: ir::Overrides::default(),
            font_scale: 1.0,
            show_model: true,
            show_exif: true,
            show_date: true,
        }
    }
}

fn design_overrides(json: Option<&str>) -> DesignBits {
    let mut bits = DesignBits::default();
    let doc = match json.map(str::trim).filter(|s| !s.is_empty()) {
        Some(text) => match serde_json::from_str::<Value>(text) {
            Ok(v) => v,
            Err(e) => {
                engine_note(&format!(
                    "e3.design=unparsable ({e}); overrides resolve empty"
                ));
                return bits;
            }
        },
        None => return bits,
    };
    bits.ov = parse_overrides(&doc);
    let flag = |k: &str| {
        doc.pointer(&format!("/design/text/{k}"))
            .and_then(|v| v.as_bool())
    };
    bits.show_model = flag("showModel").unwrap_or(true);
    bits.show_exif = flag("showExif").unwrap_or(true);
    bits.show_date = flag("showDate").unwrap_or(true);
    let mut scale = doc
        .pointer("/design/text/fontScale")
        .and_then(|x| x.as_f64())
        .map(|n| n as f32)
        .unwrap_or(1.0);
    if !scale.is_finite() {
        scale = 1.0;
    }
    scale = scale.clamp(0.5, 2.0);
    bits.font_scale = if (scale - 1.0).abs() < 0.001 {
        1.0
    } else {
        scale
    };
    engine_note(&format!(
        "e3.design=ok slotText={} slotOff={} alpha={} gravity={} theme={:?} brand={:?} anchor={:?} fontScale={} show={}:{}:{}",
        bits.ov.slot_text.len(),
        bits.ov.slot_off.len(),
        bits.ov.slot_alpha.len(),
        bits.ov.slot_gravity.len(),
        bits.ov.theme,
        bits.ov.brand,
        bits.ov.stock_anchor,
        bits.font_scale,
        bits.show_model as u8,
        bits.show_exif as u8,
        bits.show_date as u8,
    ));
    bits
}

/// Field-for-field copy of `engine/e2b/src/main.rs::parse_overrides`. e2b is a
/// Windows-gated binary (no `[lib]`), so its reader is structurally unreachable
/// from Android; keeping this verbatim is what makes the two ends agree.
fn parse_overrides(doc: &Value) -> ir::Overrides {
    let mut ov = ir::Overrides::default();
    let Some(params) = doc.pointer("/design/params").and_then(|v| v.as_object()) else {
        return ov;
    };
    for (k, v) in params {
        if let Some(p) = k.strip_prefix("slotText:") {
            if let Some(s) = v.as_str() {
                if !s.is_empty() {
                    ov.slot_text.insert(p.to_string(), s.to_string());
                }
            }
        } else if let Some(p) = k.strip_prefix("slotOff:") {
            if v.as_bool() == Some(false) {
                ov.slot_off.insert(p.to_string());
            }
        } else if let Some(p) = k.strip_prefix("slotScale:") {
            if let Some(n) = v.as_f64().filter(|n| n.is_finite()) {
                ov.slot_scale
                    .insert(p.to_string(), n.clamp(0.6, 1.6) as f32);
            }
        } else if let Some(p) = k.strip_prefix("slotAlpha:") {
            if let Some(n) = v.as_f64() {
                if n.is_finite() {
                    ov.slot_alpha.insert(p.to_string(), n as f32);
                }
            }
        } else if let Some(p) = k.strip_prefix("slotGravity:") {
            if let Some(s) = v.as_str() {
                let t = s.trim();
                if !t.is_empty() && t != "vendor" {
                    ov.slot_gravity.insert(p.to_string(), t.to_string());
                }
            }
        }
    }
    if let Some(arr) = params.get("slotOff").and_then(|v| v.as_array()) {
        for x in arr {
            if let Some(s) = x.as_str() {
                if !s.is_empty() {
                    ov.slot_off.insert(s.to_string());
                }
            }
        }
    }
    if let Some(s) = params
        .get("theme")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        ov.theme = Some(s.to_string());
    }
    if let Some(s) = params
        .get("brandLogoFile")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        ov.brand = Some(s.to_string());
    }
    if let Some(s) = params
        .get("stockAnchor")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("vendor"))
    {
        ov.stock_anchor = Some(s.to_string());
    }
    for (key, slot) in [
        ("stockOffsetXPct", &mut ov.stock_offset.0),
        ("stockOffsetYPct", &mut ov.stock_offset.1),
    ] {
        if let Some(n) = params.get(key).and_then(|v| v.as_f64()) {
            if n.is_finite() {
                *slot = n as f32;
            }
        }
    }
    // render-v1 photo crop: three floats in the same free-form map, omitted at
    // their defaults, so a design without them renders exactly as before.
    for (key, slot) in [
        ("photoCropX", &mut ov.photo_crop.cx),
        ("photoCropY", &mut ov.photo_crop.cy),
    ] {
        if let Some(n) = params.get(key).and_then(|v| v.as_f64()) {
            if n.is_finite() {
                *slot = n as f32;
            }
        }
    }
    if let Some(n) = params.get("photoCropZoom").and_then(|v| v.as_f64()) {
        if n.is_finite() {
            ov.photo_crop.zoom = n as f32;
        }
    }
    ov.photo_crop = ov.photo_crop.normalised();
    ov
}

/// Sub-pixel slack below which a snapped `cover` extent or origin counts as the
/// image edge. A proportional photo box lands within this of the full image, so
/// the default crop stays the whole raster instead of a 1-ulp sliver of it.
const PHOTO_SRC_SNAP: f32 = 1e-3;

/// The part of a `iw` x `ih` photo the `box_w` x `box_h` photo box samples, as
/// `[x, y, w, h]` in source pixels.
///
/// Verbatim port of `engine/e2b/src/main.rs::photo_src_rect` (e2b is a
/// Windows-gated binary with no `[lib]`, so its paint code cannot be pulled in
/// with `#[path]`): `cover` decides the base extent (`s = max(box_w/iw,
/// box_h/ih)`), the user's crop scales that extent by `zoom` (`s_eff = s *
/// zoom`, so a larger zoom keeps a smaller region) and centres it on the source
/// point `(cx * iw, cy * ih)`. The rectangle is clamped so it never leaves the
/// image and never collapses (a 1 px image still samples 1 px), and it is snapped
/// to the full size within [PHOTO_SRC_SNAP] so the defaults — zoom 1, centre
/// 0.5, box aspect equal to the photo's — are exactly the whole image this
/// bridge drew before the crop existed.
fn photo_src_rect(iw: f32, ih: f32, box_w: f32, box_h: f32, crop: ir::PhotoCrop) -> [f32; 4] {
    if !(iw.is_finite() && ih.is_finite() && iw > 0.0 && ih > 0.0) {
        return [0.0, 0.0, 0.0, 0.0];
    }
    let whole = [0.0, 0.0, iw, ih];
    if !(box_w.is_finite() && box_h.is_finite() && box_w > 0.0 && box_h > 0.0) {
        return whole;
    }
    let crop = crop.normalised();
    let s = (box_w / iw).max(box_h / ih);
    let s_eff = s * crop.zoom;
    if !(s.is_finite() && s > 0.0 && s_eff.is_finite() && s_eff > 0.0) {
        return whole;
    }
    let sw = photo_src_extent(box_w / s_eff, iw);
    let sh = photo_src_extent(box_h / s_eff, ih);
    let sx = photo_src_origin(crop.cx * iw - sw / 2.0, iw - sw);
    let sy = photo_src_origin(crop.cy * ih - sh / 2.0, ih - sh);
    [sx, sy, sw, sh]
}

/// A source extent inside `1..=full`, snapped onto `full` at the image edge.
fn photo_src_extent(v: f32, full: f32) -> f32 {
    if (v - full).abs() <= PHOTO_SRC_SNAP {
        full
    } else {
        // `f32::min` returns the non-NaN operand, so a NaN extent keeps the image.
        v.min(full).max(1.0_f32.min(full))
    }
}

/// A source origin inside `0..=max`, snapped onto `0`/`max` at the image edge.
fn photo_src_origin(v: f32, max: f32) -> f32 {
    let hi = if max.is_finite() { max.max(0.0) } else { 0.0 };
    let v = if v.is_finite() { v.clamp(0.0, hi) } else { 0.0 };
    if v <= PHOTO_SRC_SNAP {
        0.0
    } else if hi - v <= PHOTO_SRC_SNAP {
        hi
    } else {
        v
    }
}

pub struct RgbaRender {
    pub rgba: Vec<u8>,
    pub width: u32,
    pub height: u32,
    pub stats: RenderStats,
}

struct RenderProduct {
    rgba: Option<Vec<u8>>,
    stats: RenderStats,
}

pub fn render_design(
    style_path: &str,
    photo_path: &str,
    exif_path: &str,
    assets: &HashMap<String, String>,
    fonts_dir: &str,
    out_png: &str,
    max_edge: i32,
    options: &RenderOptions,
) -> Result<RenderStats, String> {
    let geom_path = format!("{out_png}.geom.json");
    render_design_impl(
        style_path,
        photo_path,
        exif_path,
        assets,
        fonts_dir,
        Some(out_png),
        &geom_path,
        max_edge,
        options,
    )
    .map(|product| product.stats)
}

#[allow(clippy::too_many_arguments)]
pub fn render_design_rgba(
    style_path: &str,
    photo_path: &str,
    exif_path: &str,
    assets: &HashMap<String, String>,
    fonts_dir: &str,
    geom_path: &str,
    max_edge: i32,
    options: &RenderOptions,
) -> Result<RgbaRender, String> {
    let product = render_design_impl(
        style_path, photo_path, exif_path, assets, fonts_dir, None, geom_path, max_edge, options,
    )?;
    let rgba = product
        .rgba
        .ok_or_else(|| "raw renderer produced no pixels".to_string())?;
    Ok(RgbaRender {
        width: product.stats.width as u32,
        height: product.stats.height as u32,
        rgba,
        stats: product.stats,
    })
}

#[allow(clippy::too_many_arguments)]
fn render_design_impl(
    style_path: &str,
    photo_path: &str,
    exif_path: &str,
    assets: &HashMap<String, String>,
    fonts_dir: &str,
    out_png: Option<&str>,
    geom_path: &str,
    max_edge: i32,
    options: &RenderOptions,
) -> Result<RenderProduct, String> {
    let raw = std::fs::read_to_string(style_path).map_err(|e| format!("read style: {e}"))?;
    let style = ir::parse_style(&raw).map_err(|e| format!("parse style: {e}"))?;
    // P3e-b: absent/unreadable EXIF is a contract state, not a render error.
    // The shared e2b modules resolve every EXIF token to empty, so the affected
    // text nodes drop out by the normal empty-node rule. Never fall back to a
    // bundled sample here.
    let exif = if exif_path.trim().is_empty() {
        engine_note("e3.exif=absent (empty exif path); EXIF tokens resolve empty");
        exif::Exif::default()
    } else {
        match exif::read(Path::new(exif_path)) {
            Ok(e) => {
                engine_note(&format!(
                    "e3.exif=ok src={} make={:?} model={:?}",
                    Path::new(exif_path)
                        .file_name()
                        .map(|s| s.to_string_lossy().to_string())
                        .unwrap_or_default(),
                    e.make,
                    e.model,
                ));
                e
            }
            Err(e) => {
                engine_note(&format!(
                    "e3.exif=unreadable ({e}); EXIF tokens resolve empty"
                ));
                exif::Exif::default()
            }
        }
    };
    // One structured line per render call: the evidence harnesses judge "did the
    // design / the user photo / the EXIF state actually reach this call" from it,
    // instead of inferring absence from a missing log line.
    engine_note(&format!(
        "e3.render card={} photo={} designLen={} exifMode={} ratio={:?} out={} edge={}",
        style.id,
        Path::new(photo_path)
            .file_name()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_default(),
        options.design_json.map(|d| d.len()).unwrap_or(0),
        if exif_path.trim().is_empty() {
            "absent"
        } else if exif.make.is_empty() && exif.model.is_empty() {
            "empty-tags"
        } else {
            "ok"
        },
        options.ratio,
        out_png
            .and_then(|p| Path::new(p).file_name())
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_default(),
        max_edge,
    ));

    let mut photo = load_photo(photo_path)?;
    // The ratio hint crops the photo before layout. Remember where that window
    // sits in the file so the reported `photo_src` stays in the file's pixels.
    let mut ratio_view: Option<RatioView> = None;
    if let Some((rw, rh)) = options.ratio {
        let (cropped, view) =
            crop_to_ratio(&photo, rw, rh).ok_or_else(|| "ratio crop failed".to_string())?;
        photo = cropped;
        ratio_view = Some(view);
    }
    let (pw, ph) = (photo.width() as f32, photo.height() as f32);

    let fonts = text::FontSet::load(Path::new(fonts_dir))?;
    let mut te = text::TextEngine::new();
    te.subpixel = true;
    te.linear_metrics = true;
    te.edging = skia_safe::font::Edging::AntiAlias;
    te.embolden = false;
    te.stroke_w = 0.0;
    // Fixed fallback: Android's system CJK/Thai faces differ from the desktop
    // box, so a bundled face is chosen from the scripts the style itself uses.
    te.fallback = pick_fallback(options.fallback_dir, &raw).or_else(|| fonts.fallback_face());

    // The app's design document drives the same override struct the desktop solver
    // sees; parsed before theme so `params.theme` participates in the precedence.
    let design = design_overrides(options.design_json);
    let bits = &design;

    // paint.ts / e2b main.rs precedence: explicit `--theme`-equivalent first, then
    // `design.params.theme`, then the style's defaultTheme.
    let theme_arg = options
        .theme
        .map(str::to_string)
        .or_else(|| bits.ov.theme.clone());
    let theme = style.theme(theme_arg.as_deref());
    let theme_id = theme.map(|t| t.id.clone());
    let foreground = theme.map(|t| t.foreground.clone()).unwrap_or_default();
    let surface_bg = theme
        .and_then(|t| t.background.clone())
        .unwrap_or_else(|| style.background.clone());
    let brand_dark = !light_surface(&surface_bg);
    let bg_fill = if style.transparent_background {
        None
    } else {
        Some(theme_background(theme_id.as_deref(), &surface_bg))
    };
    // Inspector brand pick (`design.params.brandLogoFile`) wins over the EXIF-derived
    // brand, mirroring e2b main.rs.
    let brand_key = bits
        .ov
        .brand
        .clone()
        .or_else(|| brand::match_brand(&exif.make, &exif.model).map(str::to_string));

    let env = ir::Env {
        pw,
        ph,
        exif,
        location: String::new(),
        custom: String::new(),
        show_model: bits.show_model,
        show_exif: bits.show_exif,
        show_date: bits.show_date,
        font_scale: bits.font_scale,
        te: &te,
        fonts: &fonts,
        theme_id: theme_id.clone(),
        foreground,
        ov: &bits.ov,
    };
    let mut gaps = ir::Gaps::default();
    let layout = ir::solve(&style, &env, &mut gaps)?;
    let text_slots = ir::text_slot_manifest(&style, &env, &layout, &mut gaps);

    let base_w = layout.photo.w;
    let k = pw / base_w;
    let cw = (layout.w * k).round() as i32;
    let ch = (layout.h * k).round() as i32;
    let scale = if max_edge > 0 && max_edge < cw.max(ch) {
        max_edge as f32 / cw.max(ch) as f32
    } else {
        1.0
    };
    let out_w = ((cw as f32 * scale).round() as i32).max(1);
    let out_h = ((ch as f32 * scale).round() as i32).max(1);

    let mut stats = RenderStats {
        style: style.id.clone(),
        width: out_w,
        height: out_h,
        layout_w: layout.w,
        layout_h: layout.h,
        texts: 0,
        images: 0,
        groups: 0,
        shapes: 0,
        brand_slots: 0,
        warnings: Vec::new(),
        geom_json: String::new(),
    };
    let mut geom_bands: Vec<BandGeom> = Vec::new();

    let mut images: HashMap<String, Image> = HashMap::new();
    for asset in &style.assets {
        match assets.get(&asset.key) {
            Some(path) => match Image::from_encoded(
                Data::from_filename(path).ok_or_else(|| format!("asset missing: {path}"))?,
            ) {
                Some(img) => {
                    images.insert(asset.key.clone(), img);
                }
                None => stats
                    .warnings
                    .push(format!("asset '{}' decode failed: {path}", asset.key)),
            },
            None => stats
                .warnings
                .push(format!("asset '{}' has no path", asset.key)),
        }
    }

    let brands_path = options.brands_dir.unwrap_or("");
    let mut brands = brand::BrandLib::load(Path::new(brands_path));

    let mut overlay_meta: Option<serde_json::Value> = None;
    // The source pixels the photo draw sampled, in the photo file's own raster
    // space once the ratio pre-crop is undone (see `RatioView`).
    let photo_src_out = std::cell::Cell::new([0.0f32, 0.0f32, pw, ph]);
    let mut surface = Surface::new_raster_n32_premul((out_w, out_h))
        .ok_or_else(|| "cannot create raster surface".to_string())?;
    {
        let canvas = surface.canvas();
        canvas.scale((scale, scale));
        let mut ctx = Ctx {
            k,
            cw: cw as f32,
            ch: ch as f32,
            fonts: &fonts,
            te: &te,
            images: &images,
            brands: &mut brands,
            brand_key: &brand_key,
            brand_dark,
            ov: &bits.ov,
            stats: &mut stats,
            geom: &mut geom_bands,
        };

        if let Some(bg) = &bg_fill {
            let mut p = Paint::default();
            p.set_anti_alias(true);
            p.set_color(css_argb(bg, 1.0));
            canvas.draw_rect(Rect::from_xywh(0.0, 0.0, layout.w * k, layout.h * k), &p);
        }

        let photo_rect = Rect::from_xywh(
            layout.photo.x * k,
            layout.photo.y * k,
            layout.photo.w * k,
            layout.photo.h * k,
        );
        let integral = (photo_rect.left() - photo_rect.left().round()).abs() < 1e-4
            && (photo_rect.top() - photo_rect.top().round()).abs() < 1e-4
            && (photo_rect.width() - pw).abs() < 1e-3
            && (photo_rect.height() - ph).abs() < 1e-3;
        let photo_sampling = if integral {
            SamplingOptions::default()
        } else if (photo_rect.width() - pw).abs() < 1e-3 && (photo_rect.height() - ph).abs() < 1e-3
        {
            SamplingOptions {
                filter: FilterMode::Linear,
                ..SamplingOptions::default()
            }
        } else {
            sampling_high()
        };

        for band in layout.bands.iter().filter(|b| b.draw_first) {
            draw_band(canvas, band, &mut ctx);
        }
        canvas.save();
        canvas.clip_rect(photo_rect, ClipOp::Intersect, true);
        let mut photo_paint = Paint::default();
        photo_paint.set_anti_alias(true);
        // render-v1 photo crop: the photo box is filled by `cover`, and the
        // user's crop moves/zooms the very source rectangle that window samples.
        // At the defaults this is the whole raster — the draw this bridge always
        // made — so an untouched design stays pixel-identical.
        let photo_src =
            photo_src_rect(pw, ph, photo_rect.width(), photo_rect.height(), bits.ov.photo_crop);
        photo_src_out.set(match ratio_view {
            Some(view) => view.to_source(photo_src),
            None => photo_src,
        });
        draw_image_rect(
            canvas,
            &photo,
            Some(Rect::from_xywh(
                photo_src[0],
                photo_src[1],
                photo_src[2],
                photo_src[3],
            )),
            photo_rect,
            photo_sampling,
            Some(&photo_paint),
        );
        // render-v1 overlay: inside the photo's clip (cropped to the photo draw
        // box), before the non-drawFirst bands (border/text/logo). Exactly once
        // per render, mirroring engine/e2b/src/main.rs: if a banded or future
        // path redraws the photo, the watermark must not stack.
        let overlay_drawn = std::cell::Cell::new(false);
        let draw_overlay_once = |canvas: &Canvas, photo_rect: Rect| -> Option<serde_json::Value> {
            if overlay_drawn.get() {
                return None;
            }
            let ov = options.overlay.as_ref()?;
            let iw = ov.image.width() as f32;
            let ih = ov.image.height() as f32;
            let bw = ov.rect[2] * photo_rect.width();
            let bh = ov.rect[3] * photo_rect.height();
            if !(iw > 0.0 && ih > 0.0 && bw > 0.0 && bh > 0.0) {
                return None;
            }
            let bx = photo_rect.left() + ov.rect[0] * photo_rect.width();
            let by = photo_rect.top() + ov.rect[1] * photo_rect.height();
            let s = (bw / iw).min(bh / ih);
            let (dw, dh) = (iw * s, ih * s);
            let od = Rect::from_xywh(bx + (bw - dw) * 0.5, by + (bh - dh) * 0.5, dw, dh);
            let mut op = Paint::default();
            op.set_anti_alias(true);
            op.set_alpha_f(ov.opacity.clamp(0.0, 1.0));
            overlay_drawn.set(true);
            let _ = canvas.save();
            if ov.angle.abs() > 0.0001 {
                canvas.rotate(ov.angle, Some(od.center()));
            }
            draw_image_rect(
                canvas,
                &ov.image,
                Some(Rect::from_xywh(0.0, 0.0, iw, ih)),
                od,
                image_sampling(iw, dw),
                Some(&op),
            );
            let _ = canvas.restore();
            Some(serde_json::json!({
                "rect_norm": ov.rect,
                "opacity": ov.opacity,
                "angle": ov.angle,
                "dst": [od.left(), od.top(), od.width(), od.height()],
            }))
        };
        if let Some(meta) = draw_overlay_once(canvas, photo_rect) {
            overlay_meta = Some(meta);
        }
        canvas.restore();
        for band in layout.bands.iter().filter(|b| !b.draw_first) {
            draw_band(canvas, band, &mut ctx);
        }
    }

    let snapshot = surface.image_snapshot();
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
            AlphaType::Unpremul,
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

    // Geometry sidecar (canvas space, independent of the preview scale) so the
    // Android render can be compared item-by-item against the desktop report.
    let geom = serde_json::json!({
        "canvas": [cw, ch],
        "output": [out_w, out_h],
        "layout": [layout.w, layout.h],
        "photo": [layout.photo.x * k, layout.photo.y * k, layout.photo.w * k, layout.photo.h * k],
        // The source pixels the photo draw sampled (the crop this bridge
        // applied), always in the photo file's own raster space: `[x, y, w, h]`.
        // Same key and unit as the desktop reports, so Kotlin's `photoSrc` feed
        // (and the Ultra HDR gain-map cut) works on both platforms unchanged.
        "photo_src": photo_src_out.get(),
        "overlay": overlay_meta,
        "textSlots": text_slots,
        "bands": geom_bands.iter().map(|b| serde_json::json!({
            "rect": b.rect,
            "items": b.items.iter().map(|i| serde_json::json!({
                "kind": i.kind, "rect": i.rect, "draw": i.draw, "fs": i.fs,
                "path": i.path, "lines": i.lines,
            })).collect::<Vec<_>>(),
        })).collect::<Vec<_>>(),
    });
    stats.geom_json = serde_json::to_string_pretty(&geom).unwrap_or_default();
    std::fs::write(geom_path, &stats.geom_json).ok();
    Ok(RenderProduct {
        rgba: raw_rgba,
        stats,
    })
}

fn draw_band(canvas: &Canvas, band: &ir::PlacedBand, ctx: &mut Ctx) {
    let k = ctx.k;
    let br = Rect::from_xywh(
        band.rect.x * k,
        band.rect.y * k,
        band.rect.w * k,
        band.rect.h * k,
    );
    let mut items: Vec<ItemGeom> = Vec::new();
    let rot_deg = band.rotation.unwrap_or(0.0);
    let rotated = ((rot_deg % 360.0) + 360.0) % 360.0 != 0.0;
    let (rcx, rcy) = (br.left + br.width() / 2.0, br.top + br.height() / 2.0);
    canvas.save();
    if rotated {
        canvas.translate((rcx, rcy));
        canvas.rotate(rot_deg, None);
        canvas.translate((-rcx, -rcy));
    }
    if let Some(bg) = &band.background {
        let lb = &band.layout_rect;
        let lbr = Rect::from_xywh(lb.x * k, lb.y * k, lb.w * k, lb.h * k);
        let bg_rect = if rotated { lbr } else { br };
        let mut p = Paint::default();
        p.set_anti_alias(true);
        p.set_color(css_argb(bg, 1.0));
        if let Some(r) = band.radius {
            canvas.draw_round_rect(bg_rect, r * k, r * k, &p);
        } else {
            canvas.draw_rect(bg_rect, &p);
        }
    }
    for item in &band.items {
        match item {
            ir::Item::Group(g) => {
                let b = Rect::from_xywh(g.rect.x * k, g.rect.y * k, g.rect.w * k, g.rect.h * k);
                let mut p = Paint::default();
                p.set_anti_alias(true);
                p.set_color(css_argb(&g.color, 1.0));
                canvas.draw_rect(b, &p);
                ctx.stats.groups += 1;
                items.push(ItemGeom {
                    kind: "group",
                    rect: [b.left, b.top, b.width(), b.height()],
                    ..Default::default()
                });
            }
            ir::Item::Shape(s) => {
                let b = Rect::from_xywh(s.rect.x * k, s.rect.y * k, s.rect.w * k, s.rect.h * k);
                if let Some(key) = &s.vector_key {
                    match vector_art().get(key) {
                        Some(art) => paint_vector(canvas, art, b),
                        None => ctx
                            .stats
                            .warnings
                            .push(format!("vector art '{key}' missing")),
                    }
                } else {
                    let mut p = Paint::default();
                    p.set_anti_alias(true);
                    p.set_color(css_argb(&s.color, s.alpha));
                    if s.shape == "circle" {
                        canvas.draw_circle(
                            (b.left + b.width() / 2.0, b.top + b.height() / 2.0),
                            b.width().min(b.height()) / 2.0,
                            &p,
                        );
                    } else {
                        canvas.draw_rect(b, &p);
                    }
                }
                ctx.stats.shapes += 1;
                items.push(ItemGeom {
                    kind: "shape",
                    rect: [b.left, b.top, b.width(), b.height()],
                    ..Default::default()
                });
            }
            ir::Item::Image(im) => draw_image_item(canvas, im, ctx, &mut items),
            ir::Item::Text(t) => {
                let fs = draw_text(canvas, t, rotated, ctx);
                ctx.stats.texts += 1;
                items.push(ItemGeom {
                    kind: "text",
                    rect: [t.rect.x * k, t.rect.y * k, t.rect.w * k, t.rect.h * k],
                    fs: Some(fs),
                    path: Some(t.path.clone()),
                    lines: Some(t.lines.clone()),
                    ..Default::default()
                });
            }
        }
    }
    canvas.restore();
    // e2b reports item rects in canvas space (rotation applied); draw rects stay
    // in the band's pre-rotation space. Mirror that so the sidecar is comparable.
    if rotated {
        for item in &mut items {
            let mapped = rotated_rect(
                Rect::from_xywh(item.rect[0], item.rect[1], item.rect[2], item.rect[3]),
                rcx,
                rcy,
                rot_deg,
            );
            item.rect = [mapped.left, mapped.top, mapped.width(), mapped.height()];
        }
    }
    ctx.geom.push(BandGeom {
        rect: [
            band.rect.x * k,
            band.rect.y * k,
            band.rect.w * k,
            band.rect.h * k,
        ],
        items,
    });
}

fn rotated_rect(r: Rect, cx: f32, cy: f32, deg: f32) -> Rect {
    let th = deg.to_radians();
    let (sn, cs) = (th.sin(), th.cos());
    let pts = [
        (r.left, r.top),
        (r.left + r.width(), r.top),
        (r.left, r.top + r.height()),
        (r.left + r.width(), r.top + r.height()),
    ];
    let (mut x0, mut y0, mut x1, mut y1) = (
        f32::INFINITY,
        f32::INFINITY,
        f32::NEG_INFINITY,
        f32::NEG_INFINITY,
    );
    for (x, y) in pts {
        let dx = x - cx;
        let dy = y - cy;
        let rx = cx + dx * cs - dy * sn;
        let ry = cy + dx * sn + dy * cs;
        x0 = x0.min(rx);
        y0 = y0.min(ry);
        x1 = x1.max(rx);
        y1 = y1.max(ry);
    }
    Rect::from_ltrb(x0, y0, x1, y1)
}

fn draw_image_item(
    canvas: &Canvas,
    im: &ir::PlacedImage,
    ctx: &mut Ctx,
    items: &mut Vec<ItemGeom>,
) {
    let k = ctx.k;
    let b = Rect::from_xywh(im.rect.x * k, im.rect.y * k, im.rect.w * k, im.rect.h * k);

    if im.is_brand_logo {
        let slot_file = ctx
            .brand_key
            .as_deref()
            .and_then(|bk| brand::choose_brand_slot(ctx.brands, bk, ctx.brand_dark));
        let Some(file) = slot_file else {
            ctx.stats.warnings.push(format!(
                "brand slot '{}': no wordmark for {:?} on this tone (app rule: empty)",
                im.path, ctx.brand_key
            ));
            return;
        };
        let Some(logo) = ctx.brands.image(&file) else {
            ctx.stats
                .warnings
                .push(format!("brand slot '{}': missing {file}", im.path));
            return;
        };
        let vendor_ink = ctx.images.get(&im.asset).and_then(brand::ink_box);
        let (margin_px, pad_px) = brand::slot_gap_parts(
            &im.margins,
            im.gravity.as_deref(),
            ir::Rect {
                x: b.left,
                y: b.top,
                w: b.width(),
                h: b.height(),
            },
            k,
            vendor_ink.as_ref(),
        );
        let metrics = brand::SlotMetrics {
            sub: logo.ink.as_ref(),
            vendor: vendor_ink.as_ref(),
            margin_px,
            pad_px,
        };
        let free = im
            .brand_free
            .map(|f| [f[0] * k, f[1] * k, f[2] * k, f[3] * k]);
        let rect = brand::brand_slot_rect(
            logo.w,
            logo.h,
            ir::Rect {
                x: b.left,
                y: b.top,
                w: b.width(),
                h: b.height(),
            },
            im.gravity.as_deref(),
            free,
            &metrics,
        );
        let mut p = Paint::default();
        p.set_anti_alias(true);
        p.set_alpha((im.alpha.clamp(0.0, 1.0) * 255.0).round() as u8);
        if let Some(tint) = &im.tint {
            if let Some(cf) =
                skia_safe::color_filters::blend(css_argb(tint, 1.0), skia_safe::BlendMode::SrcIn)
            {
                p.set_color_filter(cf);
            }
        }
        draw_image_rect(
            canvas,
            &logo.image,
            Some(Rect::from_xywh(0.0, 0.0, logo.w as f32, logo.h as f32)),
            Rect::from_xywh(rect.x, rect.y, rect.w, rect.h),
            image_sampling(logo.w as f32, rect.w),
            Some(&p),
        );
        ctx.stats.brand_slots += 1;
        items.push(ItemGeom {
            kind: "brand_slot",
            rect: [b.left, b.top, b.width(), b.height()],
            draw: Some([rect.x, rect.y, rect.w, rect.h]),
            ..Default::default()
        });
        return;
    }

    let Some(img) = ctx.images.get(&im.asset) else {
        ctx.stats
            .warnings
            .push(format!("asset '{}' not loaded", im.asset));
        return;
    };
    let (iw, ih) = (img.width() as f32, img.height() as f32);
    let mut p = Paint::default();
    p.set_anti_alias(true);
    // e2b main.rs: slotAlpha multiplies the solved image alpha.
    let am = ctx.ov.alpha(&im.path);
    p.set_alpha(((im.alpha.clamp(0.0, 1.0) * am.clamp(0.0, 1.0)) * 255.0).round() as u8);
    let dst = if im.scale == "contain" {
        let s = (b.width() / iw).min(b.height() / ih);
        let (dw, dh) = (iw * s, ih * s);
        Rect::from_xywh(
            b.left + (b.width() - dw) / 2.0,
            b.top + (b.height() - dh) / 2.0,
            dw,
            dh,
        )
    } else if im.scale == "cover" {
        let s = (b.width() / iw).max(b.height() / ih);
        let (sw, sh) = (b.width() / s, b.height() / s);
        canvas.save();
        canvas.clip_rect(b, ClipOp::Intersect, true);
        draw_image_rect(
            canvas,
            img,
            Some(Rect::from_xywh((iw - sw) / 2.0, (ih - sh) / 2.0, sw, sh)),
            b,
            image_sampling(sw, b.width()),
            Some(&p),
        );
        canvas.restore();
        b
    } else {
        b
    };
    if im.scale != "cover" {
        draw_image_rect(
            canvas,
            img,
            Some(Rect::from_xywh(0.0, 0.0, iw, ih)),
            dst,
            image_sampling(iw, dst.width()),
            Some(&p),
        );
    }
    ctx.stats.images += 1;
    items.push(ItemGeom {
        kind: "image",
        rect: [b.left, b.top, b.width(), b.height()],
        draw: Some([dst.left, dst.top, dst.width(), dst.height()]),
        ..Default::default()
    });
}

fn draw_text(canvas: &Canvas, t: &ir::PlacedText, rotated: bool, ctx: &mut Ctx) -> f32 {
    let k = ctx.k;
    // e2b main.rs: `slotAlpha:<nodePath>` multiplies the solved text alpha, and the
    // outline plate has to follow the same value or the plate detaches from the ink.
    let alpha_mul = ctx.ov.alpha(&t.path);
    let text_alpha = if alpha_mul == 1.0 {
        t.alpha
    } else {
        (t.alpha * alpha_mul.clamp(0.0, 1.0)).clamp(0.0, 1.0)
    };
    let (cw, ch) = (ctx.cw, ctx.ch);
    let mut bx = t.rect.x * k;
    let mut by = t.rect.y * k;
    let mut bw = t.rect.w * k;
    let mut bh = t.rect.h * k;
    let pad = 36.0f32.min((cw * 0.025).round()).max(3.0);
    let mut fit = 1.0f32;
    if bw > cw - pad * 2.0 || bh > ch - pad * 2.0 {
        fit = ((cw - pad * 2.0) / bw.max(1.0))
            .min((ch - pad * 2.0) / bh.max(1.0))
            .min(1.0);
    }
    let center_x = bx + bw / 2.0;
    let right_x = bx + bw;
    bw *= fit;
    bh *= fit;
    if t.align == "center" {
        bx = center_x - bw / 2.0;
    } else if t.align == "right" {
        bx = right_x - bw;
    }
    if !rotated {
        bx = pad.max(bx.min(cw - pad - bw));
        by = pad.max(by.min(ch - pad - bh));
    }
    let fs = t.font_size * k * fit;
    let x = match halign(&t.align) {
        text::HAlign::Center => bx + bw / 2.0,
        text::HAlign::Right => bx + bw,
        text::HAlign::Left => bx,
    };
    let lh = t.line_height * k * fit;
    let top = by + (bh - lh * t.lines.len() as f32) / 2.0;
    if let Some(o) = &t.outline {
        let lw = o.stroke_width * k * fit;
        if lw > 0.0 {
            let inset = lw / 2.0;
            let radius = o.radius.min(bw.min(bh) / 2.0);
            let mut sp = Paint::default();
            sp.set_anti_alias(true);
            sp.set_style(skia_safe::PaintStyle::Stroke);
            sp.set_stroke_width(lw);
            sp.set_color(css_argb(&o.stroke_color, text_alpha));
            canvas.draw_round_rect(
                Rect::from_xywh(
                    bx + inset,
                    by + inset,
                    (bw - lw).max(0.0),
                    (bh - lw).max(0.0),
                ),
                (radius - inset).max(0.0),
                (radius - inset).max(0.0),
                &sp,
            );
        }
    }
    let d021 = -ctx.fonts.d021_em(&t.font_key) * fs;
    let shadow = t.shadow.as_ref().and_then(|s| {
        let color = css_canvas_rgba(&s.color)?;
        if color.a() == 0 {
            return None;
        }
        Some(text::Shadow {
            color,
            sigma: s.blur * k * fit * 0.5,
            dx: s.x * k * fit,
            dy: s.y * k * fit,
        })
    });
    let tracking = t.tracking * k * fit;
    let tf = ctx.fonts.resolve(&t.font_key, t.weight).clone();
    for (i, line) in t.lines.iter().enumerate() {
        let fallback_line = ctx.fonts.fallback_face().is_some()
            && line.chars().any(|c| tf.unichar_to_glyph(c as i32) == 0);
        // W2: 与桌面同一条链——含 CJK 的行优先用厂商中文字形（OPPO Sans SC / MiSans）。
        let cjk_tf = ctx.fonts.cjk_face_for(&t.font_key, line, t.weight);
        // Desktop default (E2B_D021_CJK unset) applies no extra CJK shift.
        let line_d021 = if cjk_tf.is_some() {
            -ctx.fonts.cjk_d021_em(&t.font_key) * fs
        } else if fallback_line {
            0.0
        } else {
            d021
        };
        let ly = top + lh * (i as f32 + 0.5) + line_d021;
        let spec = text::TextSpec {
            text: line,
            typeface: tf.clone(),
            size: fs,
            x,
            y: ly,
            align: halign(&t.align),
            baseline: text::VBaseline::Middle,
            tracking,
            color: css_argb(&t.color, text_alpha),
            shadow,
            // The Android bridge keeps the engine-wide fallback chain (no per-line
            // script face yet); P3g-1's `script_face` wiring is a separate slice.
            script_fallback: None,
            cjk_fallback: cjk_tf,
            matrix: None,
        };
        ctx.te.draw(canvas, &spec);
    }
    fs
}

fn pick_fallback(dir: Option<&str>, style_raw: &str) -> Option<skia_safe::Typeface> {
    let dir = dir?;
    let has_thai = style_raw.chars().any(|c| ('฀'..='๿').contains(&c));
    let has_cjk = style_raw
        .chars()
        .any(|c| ('一'..='鿿').contains(&c) || ('　'..='〿').contains(&c));
    let name = if has_thai {
        "thai.ttf"
    } else if has_cjk {
        "cjk.ttf"
    } else {
        return None;
    };
    let path = Path::new(dir).join(name);
    let data = Data::from_filename(&path)?;
    skia_safe::FontMgr::new().new_from_data(data, None)
}

fn vector_art() -> &'static Value {
    static ART: std::sync::OnceLock<Value> = std::sync::OnceLock::new();
    ART.get_or_init(|| serde_json::from_str(VECTOR_ART).unwrap_or(Value::Null))
}

fn vnum(v: &Value, key: &str) -> Option<f32> {
    v.get(key).and_then(|x| x.as_f64()).map(|x| x as f32)
}

fn paint_vector(canvas: &Canvas, art: &Value, b: Rect) {
    let vw = art.get("vw").and_then(|x| x.as_f64()).unwrap_or(1.0) as f32;
    let vh = art.get("vh").and_then(|x| x.as_f64()).unwrap_or(1.0) as f32;
    if vw <= 0.0 || vh <= 0.0 {
        return;
    }
    canvas.save();
    canvas.translate((b.left, b.top));
    canvas.scale((b.width() / vw, b.height() / vh));
    if let Some(tree) = art.get("tree").and_then(|x| x.as_array()) {
        paint_vector_nodes(canvas, tree);
    }
    canvas.restore();
}

fn paint_vector_nodes(canvas: &Canvas, nodes: &[Value]) {
    let mut clips = 0usize;
    for n in nodes {
        if let Some(d) = n.get("clip").and_then(|x| x.as_str()) {
            if let Some(path) = skia_safe::Path::from_svg(d) {
                canvas.save();
                clips += 1;
                canvas.clip_path(&path, ClipOp::Intersect, true);
            }
        } else if let Some(children) = n.get("c").and_then(|x| x.as_array()) {
            canvas.save();
            if let (Some(tx), Some(ty)) = (vnum(n, "tx"), vnum(n, "ty")) {
                canvas.translate((tx, ty));
            }
            let (px, py) = (vnum(n, "px"), vnum(n, "py"));
            if let (Some(rot), Some(px), Some(py)) = (vnum(n, "rot"), px, py) {
                canvas.translate((px, py));
                canvas.rotate(rot, None);
                canvas.translate((-px, -py));
            }
            if let (Some(sx), Some(sy), Some(px), Some(py)) = (vnum(n, "sx"), vnum(n, "sy"), px, py)
            {
                canvas.translate((px, py));
                canvas.scale((sx, sy));
                canvas.translate((-px, -py));
            }
            paint_vector_nodes(canvas, children);
            canvas.restore();
        } else if let Some(d) = n.get("d").and_then(|x| x.as_str()) {
            let Some(mut path) = skia_safe::Path::from_svg(d) else {
                continue;
            };
            if n.get("rule").and_then(|x| x.as_str()) == Some("evenodd") {
                path.set_fill_type(skia_safe::PathFillType::EvenOdd);
            }
            if let Some(fill) = n.get("fill").and_then(|x| x.as_str()) {
                let fa = vnum(n, "fa").unwrap_or(1.0);
                let mut p = Paint::default();
                p.set_anti_alias(true);
                p.set_style(skia_safe::PaintStyle::Fill);
                p.set_color(css_argb(fill, fa));
                canvas.draw_path(&path, &p);
            }
            if let Some(stroke) = n.get("stroke").and_then(|x| x.as_str()) {
                let sa = vnum(n, "sa").unwrap_or(1.0);
                let mut p = Paint::default();
                p.set_anti_alias(true);
                p.set_style(skia_safe::PaintStyle::Stroke);
                p.set_color(css_argb(stroke, sa));
                p.set_stroke_width(vnum(n, "sw").unwrap_or(1.0));
                canvas.draw_path(&path, &p);
            }
        }
    }
    for _ in 0..clips {
        canvas.restore();
    }
}

/// Where the ratio pre-crop's raster sits inside the photo file the caller
/// handed in: a rect in the cropped raster maps to the file's own pixels with
/// `x + rect.x * sx` / `y + rect.y * sy`. Kept so the reported `photo_src` stays
/// in the source file's raster space even when `--ratio` (the app's `ratio`
/// inspector hint) already cropped the photo before layout.
#[derive(Debug, Clone, Copy)]
struct RatioView {
    x: f32,
    y: f32,
    sx: f32,
    sy: f32,
}

impl RatioView {
    fn to_source(self, rect: [f32; 4]) -> [f32; 4] {
        [
            self.x + rect[0] * self.sx,
            self.y + rect[1] * self.sy,
            rect[2] * self.sx,
            rect[3] * self.sy,
        ]
    }
}

fn crop_to_ratio(img: &Image, rw: f32, rh: f32) -> Option<(Image, RatioView)> {
    if rw <= 0.0 || rh <= 0.0 {
        return None;
    }
    let (iw, ih) = (img.width() as f32, img.height() as f32);
    let target = rw / rh;
    let (sw, sh) = if iw / ih > target {
        (ih * target, ih)
    } else {
        (iw, iw / target)
    };
    let (sx, sy) = ((iw - sw) / 2.0, (ih - sh) / 2.0);
    let (swr, shr) = (sw.round(), sh.round());
    let mut surface = Surface::new_raster_n32_premul((swr as i32, shr as i32))?;
    let canvas = surface.canvas();
    let dst = Rect::from_xywh(0.0, 0.0, sw, sh);
    draw_image_rect(
        canvas,
        img,
        Some(Rect::from_xywh(sx, sy, sw, sh)),
        dst,
        SamplingOptions::default(),
        None,
    );
    // The raster is the rounded extent, so the inverse map carries that ratio.
    let view = RatioView {
        x: sx,
        y: sy,
        sx: if swr > 0.0 { sw / swr } else { 1.0 },
        sy: if shr > 0.0 { sh / shr } else { 1.0 },
    };
    Some((surface.image_snapshot(), view))
}

fn halign(s: &str) -> text::HAlign {
    match s {
        "center" => text::HAlign::Center,
        "right" => text::HAlign::Right,
        _ => text::HAlign::Left,
    }
}

/// paint.ts `themeBackground()`: an all-00 alpha falls back to a per-theme plate.
fn theme_background(theme_id: Option<&str>, raw: &str) -> String {
    let s = raw;
    let hex = s.strip_prefix('#').unwrap_or("");
    let valid = if hex.len() == 8 {
        hex[2..].chars().all(|c| c.is_ascii_hexdigit())
    } else if hex.len() == 6 {
        true
    } else {
        false
    };
    if valid {
        let alpha = if s.len() >= 9 { &s[1..3] } else { "FF" };
        if alpha != "00" {
            return if alpha == "FF" && s.len() >= 9 {
                s[0..9].to_string()
            } else {
                s[0..7].to_string()
            };
        }
    }
    let fallback = match theme_id {
        Some("gradient_color_red") => "#3b1516",
        Some("gradient_color_blue_dark") => "#101c2b",
        Some("gradient_color_blue_light") => "#dcebf1",
        Some("gradient_color_yellow") => "#d9c48a",
        Some("light_blur") => "#e9e9e9",
        Some("dark_blur") => "#151515",
        _ => "#1c1d1f",
    };
    fallback.to_string()
}

fn parse_solid(value: &str) -> Option<(f32, f32, f32, f32)> {
    let s = value.trim().to_lowercase();
    if s == "white" {
        return Some((255.0, 255.0, 255.0, 1.0));
    }
    if s == "black" {
        return Some((0.0, 0.0, 0.0, 1.0));
    }
    let hex = s.strip_prefix('#')?;
    let parse = |a: &str| u8::from_str_radix(a, 16).unwrap_or(0) as f32;
    match hex.len() {
        8 => Some((
            parse(&hex[2..4]),
            parse(&hex[4..6]),
            parse(&hex[6..8]),
            parse(&hex[0..2]) / 255.0,
        )),
        6 => Some((parse(&hex[0..2]), parse(&hex[2..4]), parse(&hex[4..6]), 1.0)),
        _ => None,
    }
}

fn light_surface(value: &str) -> bool {
    match parse_solid(value) {
        Some((r, g, b, a)) if a >= 0.4 => (r * 0.299 + g * 0.587 + b * 0.114) / 255.0 > 0.7,
        _ => false,
    }
}

fn css_argb(value: &str, alpha: f32) -> Color {
    let s = value.trim();
    if s.eq_ignore_ascii_case("white") {
        return Color::from_argb((255.0 * alpha).round() as u8, 255, 255, 255);
    }
    if s.eq_ignore_ascii_case("black") {
        return Color::from_argb((255.0 * alpha).round() as u8, 0, 0, 0);
    }
    if let Some(rest) = s.strip_prefix("rgba(").or_else(|| s.strip_prefix("rgb(")) {
        let inner = rest.trim_end_matches(')');
        let parts: Vec<&str> = inner.split(',').map(str::trim).collect();
        if parts.len() >= 3 {
            let r = parts[0].parse::<f32>().unwrap_or(0.0).clamp(0.0, 255.0);
            let g = parts[1].parse::<f32>().unwrap_or(0.0).clamp(0.0, 255.0);
            let b = parts[2].parse::<f32>().unwrap_or(0.0).clamp(0.0, 255.0);
            let a = if parts.len() >= 4 {
                parts[3].parse::<f32>().unwrap_or(1.0)
            } else {
                1.0
            };
            return Color::from_argb(
                (a.clamp(0.0, 1.0) * alpha * 255.0).round() as u8,
                r.round() as u8,
                g.round() as u8,
                b.round() as u8,
            );
        }
    }
    let hex = s.strip_prefix('#').unwrap_or(s);
    let parse = |a: &str| u8::from_str_radix(a, 16).unwrap_or(0);
    if hex.len() == 8 {
        Color::from_argb(
            (parse(&hex[0..2]) as f32 * alpha).round() as u8,
            parse(&hex[2..4]),
            parse(&hex[4..6]),
            parse(&hex[6..8]),
        )
    } else if hex.len() == 6 {
        Color::from_argb(
            (255.0 * alpha).round() as u8,
            parse(&hex[0..2]),
            parse(&hex[2..4]),
            parse(&hex[4..6]),
        )
    } else {
        Color::BLACK
    }
}

fn css_canvas_rgba(value: &str) -> Option<Color> {
    let s = value.trim();
    let hex = s.strip_prefix('#').unwrap_or(s);
    let parse = |a: &str| u8::from_str_radix(a, 16).unwrap_or(0);
    if hex.len() == 8 {
        Some(Color::from_argb(
            parse(&hex[6..8]),
            parse(&hex[0..2]),
            parse(&hex[2..4]),
            parse(&hex[4..6]),
        ))
    } else if hex.len() == 6 {
        Some(Color::from_argb(
            255,
            parse(&hex[0..2]),
            parse(&hex[2..4]),
            parse(&hex[4..6]),
        ))
    } else {
        None
    }
}

fn sampling_high() -> SamplingOptions {
    let mut s = SamplingOptions::default();
    s.use_cubic = true;
    s.cubic = skia_safe::CubicResampler::mitchell();
    s
}

fn image_sampling(iw: f32, dw: f32) -> SamplingOptions {
    if dw < iw - 1e-3 {
        SamplingOptions {
            filter: FilterMode::Linear,
            ..SamplingOptions::default()
        }
    } else {
        sampling_high()
    }
}

fn draw_image_rect(
    canvas: &Canvas,
    img: &Image,
    src: Option<Rect>,
    dst: Rect,
    sampling: SamplingOptions,
    paint: Option<&Paint>,
) {
    let p = Paint::default();
    match src {
        Some(r) => canvas.draw_image_rect_with_sampling_options(
            img,
            Some((&r, SrcRectConstraint::Strict)),
            &dst,
            sampling,
            paint.unwrap_or(&p),
        ),
        None => canvas.draw_image_rect_with_sampling_options(
            img,
            None,
            &dst,
            sampling,
            paint.unwrap_or(&p),
        ),
    };
}

/// The Android copy of the crop math must agree with `engine/e2b`'s to the
/// float, because the desktop and the phone have to draw the same frame from the
/// same document. These are the desktop's own cases, plus the ratio-space
/// mapping this bridge needs and the document reader at the other end.
#[cfg(test)]
mod photo_crop_tests {
    use super::{parse_overrides, photo_src_rect, RatioView};
    use crate::ir::PhotoCrop;
    use serde_json::json;

    /// 4000x3000 photo, square 1000 box: `cover` keeps 3000x3000 at 500,0.
    #[test]
    fn defaults_reproduce_todays_centred_rect() {
        assert_eq!(
            photo_src_rect(4000.0, 3000.0, 1000.0, 1000.0, PhotoCrop::default()),
            [500.0, 0.0, 3000.0, 3000.0]
        );
        // A box with the photo's own aspect keeps the whole image, exactly.
        assert_eq!(
            photo_src_rect(4000.0, 3000.0, 800.0, 600.0, PhotoCrop::default()),
            [0.0, 0.0, 4000.0, 3000.0]
        );
        // ...and so does a portrait photo in a square box, mirrored.
        assert_eq!(
            photo_src_rect(3000.0, 4000.0, 1000.0, 1000.0, PhotoCrop::default()),
            [0.0, 500.0, 3000.0, 3000.0]
        );
    }

    #[test]
    fn zoom_two_halves_the_sampled_extent() {
        let r = photo_src_rect(
            4000.0,
            3000.0,
            1000.0,
            1000.0,
            PhotoCrop {
                cx: 0.5,
                cy: 0.5,
                zoom: 2.0,
            },
        );
        assert_eq!(r, [1250.0, 750.0, 1500.0, 1500.0]);
        // The kept aspect stays the photo box's aspect, so the draw never warps.
        assert!((r[2] / r[3] - 1.0).abs() < 1e-5);
    }

    #[test]
    fn corner_pan_clamps_into_the_image() {
        let top_left = photo_src_rect(
            4000.0,
            3000.0,
            1000.0,
            1000.0,
            PhotoCrop {
                cx: 0.0,
                cy: 0.0,
                zoom: 2.0,
            },
        );
        assert_eq!(top_left, [0.0, 0.0, 1500.0, 1500.0]);
        let bottom_right = photo_src_rect(
            4000.0,
            3000.0,
            1000.0,
            1000.0,
            PhotoCrop {
                cx: 1.0,
                cy: 1.0,
                zoom: 2.0,
            },
        );
        assert_eq!(bottom_right, [2500.0, 1500.0, 1500.0, 1500.0]);
        // A centre outside 0..1 is clamped, never pushed off the photo.
        let off = photo_src_rect(
            4000.0,
            3000.0,
            1000.0,
            1000.0,
            PhotoCrop {
                cx: -3.0,
                cy: 4.0,
                zoom: 2.0,
            },
        );
        assert_eq!(off, [0.0, 1500.0, 1500.0, 1500.0]);
    }

    #[test]
    fn zoom_is_clamped_to_the_ui_range() {
        let beyond = photo_src_rect(
            4000.0,
            3000.0,
            1000.0,
            1000.0,
            PhotoCrop {
                cx: 0.5,
                cy: 0.5,
                zoom: 99.0,
            },
        );
        let deepest = photo_src_rect(
            4000.0,
            3000.0,
            1000.0,
            1000.0,
            PhotoCrop {
                cx: 0.5,
                cy: 0.5,
                zoom: 8.0,
            },
        );
        assert_eq!(beyond, deepest);
        let below = photo_src_rect(
            4000.0,
            3000.0,
            1000.0,
            1000.0,
            PhotoCrop {
                cx: 0.5,
                cy: 0.5,
                zoom: 0.0,
            },
        );
        assert_eq!(
            below,
            photo_src_rect(4000.0, 3000.0, 1000.0, 1000.0, PhotoCrop::default())
        );
        let nan = photo_src_rect(
            4000.0,
            3000.0,
            1000.0,
            1000.0,
            PhotoCrop {
                cx: f32::NAN,
                cy: f32::NAN,
                zoom: f32::NAN,
            },
        );
        assert_eq!(
            nan,
            photo_src_rect(4000.0, 3000.0, 1000.0, 1000.0, PhotoCrop::default())
        );
    }

    #[test]
    fn one_pixel_images_never_produce_an_empty_rect() {
        let r = photo_src_rect(
            1.0,
            1.0,
            1.0,
            1.0,
            PhotoCrop {
                cx: 0.0,
                cy: 0.0,
                zoom: 8.0,
            },
        );
        assert_eq!(r, [0.0, 0.0, 1.0, 1.0]);
        let r = photo_src_rect(
            1.0,
            4000.0,
            10.0,
            10.0,
            PhotoCrop {
                cx: 1.0,
                cy: 1.0,
                zoom: 8.0,
            },
        );
        assert_eq!(r[2], 1.0);
        assert!(r[3] >= 1.0 && r[1] + r[3] <= 4000.0 + 1e-3);
        // A box that cannot describe a rectangle keeps the whole image.
        assert_eq!(
            photo_src_rect(4000.0, 3000.0, 0.0, 10.0, PhotoCrop::default()),
            [0.0, 0.0, 4000.0, 3000.0]
        );
        assert_eq!(
            photo_src_rect(0.0, 3000.0, 10.0, 10.0, PhotoCrop::default()),
            [0.0, 0.0, 0.0, 0.0]
        );
    }

    #[test]
    fn design_params_read_the_three_crop_keys() {
        let ov = parse_overrides(&json!({ "design": { "params": {
            "photoCropX": 0.25,
            "photoCropY": "0.75",
            "photoCropZoom": 3
        }}}));
        assert_eq!(ov.photo_crop.cx, 0.25);
        // The keys are numbers in the document, not strings: a string is ignored.
        assert_eq!(ov.photo_crop.cy, 0.5);
        assert_eq!(ov.photo_crop.zoom, 3.0);
        // A design without the keys is the default crop (no-op render).
        let none = parse_overrides(&json!({ "design": { "params": {} } }));
        assert!(none.photo_crop.is_default());
        // Non-numeric values fall back to the default instead of poisoning the rect.
        let bad = parse_overrides(&json!({ "design": { "params": {
            "photoCropX": null,
            "photoCropY": "NaN",
            "photoCropZoom": false
        }}}));
        assert!(bad.photo_crop.is_default());
    }

    /// The ratio hint crops the photo before layout; the reported rect must still
    /// address the file the caller handed in, or the gain map is cut elsewhere.
    #[test]
    fn ratio_pre_crop_maps_the_rect_back_into_the_file() {
        let view = RatioView {
            x: 500.0,
            y: 0.0,
            sx: 1.0,
            sy: 1.0,
        };
        assert_eq!(
            view.to_source([250.0, 100.0, 1500.0, 1500.0]),
            [750.0, 100.0, 1500.0, 1500.0]
        );
        // A rounded raster scales the extent back to the exact window.
        let rounded = RatioView {
            x: 0.0,
            y: 0.0,
            sx: 3000.0 / 2999.0,
            sy: 1.0,
        };
        let mapped = rounded.to_source([0.0, 0.0, 2999.0, 2000.0]);
        assert!((mapped[2] - 3000.0).abs() < 1e-3);
        assert_eq!(mapped[3], 2000.0);
    }
}

