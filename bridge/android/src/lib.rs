//! X1 Android minimal renderer.
//!
//! Reuses the desktop engine's frozen IR solver and text engine **without
//! modifying e2b**: the modules are pulled in with `#[path]` and only the paint
//! subset this card needs is re-implemented here (photo, band/group plates,
//! `contain` images, Canvas2D text). Rendering is CPU raster on the T0b Android
//! Skia prebuilt; the output is a PNG on disk.
//!
//! The JNI shim lives in `ui/androidMain/cpp/fm_engine_jni.cpp` so the JNI types
//! come from the NDK's `jni.h`; this crate exposes a plain C ABI.

#[path = "../../../engine/e2b/src/brand.rs"]
mod brand;
#[path = "../../../engine/e2b/src/exif.rs"]
mod exif;
#[path = "../../../engine/e2b/src/ir.rs"]
mod ir;
#[path = "../../../engine/e2b/src/text.rs"]
mod text;
// X1-ANDROID7: the desktop TIFF decoder (Skia has none), read-only reuse.
#[path = "../../../engine/e5/src/tiffsrc.rs"]
pub mod tiffsrc;

mod paint;

use std::ffi::{CStr, CString};
use std::os::raw::c_char;

pub use paint::{render_design, OverlayLayer, RenderOptions, RenderStats};

/// Builds the render-v1 overlay layer (validated + decoded) for CLI/host use.
pub fn overlay_layer(path: &str, rect: [f32; 4], opacity: f32) -> Result<OverlayLayer, String> {
    for (i, v) in rect.iter().enumerate() {
        if !(0.0..=1.0).contains(v) {
            return Err(format!("overlay rect index {i} out of 0..1: {v}"));
        }
    }
    if rect[2] <= 0.0 || rect[3] <= 0.0 {
        return Err("overlay rect needs positive w/h".to_string());
    }
    if !(0.0..=1.0).contains(&opacity) {
        return Err(format!("overlay opacity out of 0..1: {opacity}"));
    }
    let image = paint::load_overlay_image(path)?;
    Ok(OverlayLayer { image, rect, opacity })
}

pub const VERSION: &CStr = c"fm-android-engine 0.1.0 (e2b IR subset, cpu raster)";

/// Version string for the device probe (static, never freed).
#[no_mangle]
pub extern "C" fn fm_engine_version() -> *const c_char {
    VERSION.as_ptr()
}

/// Frees a string returned by [`fm_render_design`].
#[no_mangle]
pub extern "C" fn fm_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr));
        }
    }
}

/// TIFF → PNG for the import path (same decoder as the renderer, plus the
/// EXIF orientation the frozen app applied with `sharp(file).rotate()`).
/// Returns null on success, else an error string (free with [`fm_free_string`]).
#[no_mangle]
pub extern "C" fn fm_tiff_to_png(tiff_path: *const c_char, out_png: *const c_char) -> *mut c_char {
    let result = (|| -> Result<(), String> {
        let tiff = unsafe { cstr(tiff_path, "tiff_path") }?;
        let out = unsafe { cstr(out_png, "out_png") }?;
        let img = tiffsrc::decode(std::path::Path::new(tiff))?;
        let (rgba, w, h) = tiffsrc::apply_orientation(&img.rgba, img.w, img.h, img.orientation);
        let info = skia_safe::ImageInfo::new(
            (w as i32, h as i32),
            skia_safe::ColorType::RGBA8888,
            skia_safe::AlphaType::Unpremul,
            Some(skia_safe::ColorSpace::new_srgb()),
        );
        let data = skia_safe::Data::new_copy(&rgba);
        let image = skia_safe::images::raster_from_data(&info, data, (w as usize) * 4)
            .ok_or_else(|| "tiff raster failed".to_string())?;
        let png = image
            .encode_to_data(skia_safe::EncodedImageFormat::PNG)
            .ok_or_else(|| "png encode failed".to_string())?;
        std::fs::write(out, png.as_bytes()).map_err(|e| format!("write {out}: {e}"))?;
        Ok(())
    })();
    match result {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => CString::new(e).unwrap_or_else(|_| CString::new("tiff error").unwrap()).into_raw(),
    }
}

/// `""` → original; `"4:3"` → 4/3; `"1.3333"` → float.
fn parse_ratio(raw: &str) -> Result<Option<(f32, f32)>, String> {
    let s = raw.trim();
    if s.is_empty() {
        return Ok(None);
    }
    if let Some((w, h)) = s.split_once(':') {
        let w: f32 = w.trim().parse().map_err(|_| format!("ratio '{raw}'"))?;
        let h: f32 = h.trim().parse().map_err(|_| format!("ratio '{raw}'"))?;
        if w <= 0.0 || h <= 0.0 {
            return Err(format!("ratio '{raw}' must be positive"));
        }
        return Ok(Some((w, h)));
    }
    let v: f32 = s.parse().map_err(|_| format!("ratio '{raw}'"))?;
    if v <= 0.0 {
        return Err(format!("ratio '{raw}' must be positive"));
    }
    Ok(Some((v, 1.0)))
}

unsafe fn cstr<'a>(ptr: *const c_char, name: &str) -> Result<&'a str, String> {
    if ptr.is_null() {
        return Err(format!("{name} is null"));
    }
    CStr::from_ptr(ptr)
        .to_str()
        .map_err(|e| format!("{name} is not UTF-8: {e}"))
}

/// Renders one IR style to `out_png`.
///
/// `assets_json` is a JSON object `{assetKey: absolutePath}`. `max_edge > 0`
/// scales the canvas so its longest edge is at most `max_edge` (preview).
/// Returns null on success, otherwise a heap string with the error (free with
/// [`fm_free_string`]).
#[no_mangle]
pub extern "C" fn fm_render_design(
    style_path: *const c_char,
    photo_path: *const c_char,
    photo_override: *const c_char,
    exif_path: *const c_char,
    assets_json: *const c_char,
    fonts_dir: *const c_char,
    brands_dir: *const c_char,
    fallback_dir: *const c_char,
    theme: *const c_char,
    ratio: *const c_char,
    design_json: *const c_char,
    overlay_path: *const c_char,
    overlay_x: f32,
    overlay_y: f32,
    overlay_w: f32,
    overlay_h: f32,
    overlay_opacity: f32,
    out_png: *const c_char,
    max_edge: i32,
) -> *mut c_char {
    let result = (|| -> Result<RenderStats, String> {
        let style = unsafe { cstr(style_path, "style_path") }?;
        let photo = unsafe { cstr(photo_path, "photo_path") }?;
        let photo_override = unsafe { cstr(photo_override, "photo_override") }?;
        // Per-call photo (batch pairing): non-empty wins, otherwise the global.
        let photo_final = if photo_override.is_empty() { photo } else { photo_override };
        let exif = unsafe { cstr(exif_path, "exif_path") }?;
        let assets_raw = unsafe { cstr(assets_json, "assets_json") }?;
        let fonts = unsafe { cstr(fonts_dir, "fonts_dir") }?;
        let brands = unsafe { cstr(brands_dir, "brands_dir") }?;
        let fallback = unsafe { cstr(fallback_dir, "fallback_dir") }?;
        let theme_arg = unsafe { cstr(theme, "theme") }?;
        let ratio_arg = unsafe { cstr(ratio, "ratio") }?;
        let design_arg = unsafe { cstr(design_json, "design_json") }?;
        let out = unsafe { cstr(out_png, "out_png") }?;

        let assets_value: serde_json::Value =
            serde_json::from_str(assets_raw).map_err(|e| format!("assets_json: {e}"))?;
        let mut assets = std::collections::HashMap::new();
        if let Some(obj) = assets_value.as_object() {
            for (k, v) in obj {
                if let Some(path) = v.as_str() {
                    assets.insert(k.clone(), path.to_string());
                }
            }
        }
        let ratio = parse_ratio(ratio_arg)?;
        let overlay_arg = unsafe { cstr(overlay_path, "overlay_path") }?;
        let overlay = if overlay_arg.is_empty() {
            None
        } else {
            Some(overlay_layer(
                overlay_arg,
                [overlay_x, overlay_y, overlay_w, overlay_h],
                overlay_opacity,
            )?)
        };
        let options = RenderOptions {
            theme: if theme_arg.is_empty() { None } else { Some(theme_arg) },
            design_json: if design_arg.is_empty() { None } else { Some(design_arg) },
            ratio,
            brands_dir: if brands.is_empty() { None } else { Some(brands) },
            fallback_dir: if fallback.is_empty() { None } else { Some(fallback) },
            overlay,
        };
        render_design(style, photo_final, exif, &assets, fonts, out, max_edge, &options)
    })();

    match result {
        Ok(stats) => {
            println!(
                "fm_engine.render style={} canvas={}x{} layout={}x{} texts={} images={} groups={} shapes={} warnings={}",
                stats.style,
                stats.width,
                stats.height,
                stats.layout_w,
                stats.layout_h,
                stats.texts,
                stats.images,
                stats.groups,
                stats.shapes,
                stats.warnings.len()
            );
            std::ptr::null_mut()
        }
        Err(e) => CString::new(e)
            .unwrap_or_else(|_| CString::new("unknown error").unwrap())
            .into_raw(),
    }
}