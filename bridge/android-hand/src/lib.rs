//! X1 Android hand-template renderer.
//!
//! Reuses `engine/e4-hand`'s portable modules read-only (`#[path]`): card/design
//! loading, the 32 template definitions (`layout` + `render`), the Canvas2D
//! wrapper, fonts, text shaper and draw report. Only the backend differs: a CPU
//! raster surface instead of D3D12, plus an optional `maxEdge` scale.

#[path = "../../../engine/e4-hand/src/canvas.rs"]
mod canvas;
#[path = "../../../engine/e4-hand/src/css.rs"]
mod css;
#[path = "../../../engine/e4-hand/src/draw.rs"]
mod draw;
#[path = "../../../engine/e4-hand/src/exif.rs"]
mod exif;
#[path = "../../../engine/e4-hand/src/fonts.rs"]
mod fonts;
#[path = "../../../engine/e4-hand/src/hand.rs"]
mod hand;
#[path = "../../../engine/e4-hand/src/jsval.rs"]
mod jsval;
#[path = "../../../engine/e4-hand/src/lines.rs"]
mod lines;
#[path = "../../../engine/e4-hand/src/report.rs"]
mod report;
#[path = "../../../engine/e4-hand/src/templates/mod.rs"]
mod templates;
#[path = "../../../engine/e4-hand/src/text.rs"]
mod text;
// X1-ANDROID8: same desktop TIFF decoder the IR bridge uses.
mod gpu;
mod render;
#[path = "../../../engine/e5/src/tiffsrc.rs"]
pub mod tiffsrc;

use std::ffi::{CStr, CString};
use std::os::raw::c_char;

pub use render::{render_card, render_card_rgba, HandGeom, HandRender};

/// Builds the render-v1 overlay layer (validated + decoded) for CLI/host use.
pub fn overlay_spec(
    path: &str,
    rect: [f32; 4],
    opacity: f32,
    angle: f32,
) -> Result<canvas::OverlaySpec, String> {
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
    if !angle.is_finite() || angle.abs() > 180.0 {
        return Err(format!("overlay angle out of -180..180: {angle}"));
    }
    let image = render::load_overlay(std::path::Path::new(path))?;
    Ok(canvas::OverlaySpec {
        image,
        rect,
        opacity,
        angle,
    })
}

pub const VERSION: &CStr = c"fm-android-hand 0.2.0 (e4-hand templates, cpu raster)";

#[no_mangle]
pub extern "C" fn fm_hand_version() -> *const c_char {
    VERSION.as_ptr()
}

#[no_mangle]
pub extern "C" fn fm_hand_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr));
        }
    }
}

unsafe fn cstr<'a>(ptr: *const c_char, name: &str) -> Result<&'a str, String> {
    if ptr.is_null() {
        return Err(format!("{name} is null"));
    }
    CStr::from_ptr(ptr)
        .to_str()
        .map_err(|e| format!("{name} is not UTF-8: {e}"))
}

/// Renders one hand card to `out_png` (+ `<out>.geom.json`).
///
/// `photo_path` overrides the card's manifest photo like e4-hand's `--photo`
/// (`None`/empty keeps the bundled sample). Returns null on success, else a
/// heap error string (free with [`fm_hand_free_string`]).
#[no_mangle]
pub extern "C" fn fm_render_hand(
    cards_path: *const c_char,
    card_id: *const c_char,
    hand_dir: *const c_char,
    fonts_dir: *const c_char,
    extra_fonts_dir: *const c_char,
    fallback_cjk: *const c_char,
    photo_path: *const c_char,
    photo_override: *const c_char,
    overlay_path: *const c_char,
    overlay_x: f32,
    overlay_y: f32,
    overlay_w: f32,
    overlay_h: f32,
    overlay_opacity: f32,
    overlay_angle: f32,
    // render-v1 design document; empty keeps the bundled card design.
    design_json: *const c_char,
    out_png: *const c_char,
    max_edge: i32,
) -> *mut c_char {
    let result = (|| -> Result<HandRender, String> {
        let cards = unsafe { cstr(cards_path, "cards_path") }?;
        let card = unsafe { cstr(card_id, "card_id") }?;
        let hand_dir = unsafe { cstr(hand_dir, "hand_dir") }?;
        let fonts = unsafe { cstr(fonts_dir, "fonts_dir") }?;
        let extra = unsafe { cstr(extra_fonts_dir, "extra_fonts_dir") }?;
        let fallback = unsafe { cstr(fallback_cjk, "fallback_cjk") }?;
        let photo = unsafe { cstr(photo_path, "photo_path") }?;
        let photo_override = unsafe { cstr(photo_override, "photo_override") }?;
        // Per-call photo (batch pairing): non-empty wins, otherwise the global.
        let photo_final = if photo_override.is_empty() {
            photo
        } else {
            photo_override
        };
        let overlay_arg = unsafe { cstr(overlay_path, "overlay_path") }?;
        let overlay = if overlay_arg.is_empty() {
            None
        } else {
            Some(overlay_spec(
                overlay_arg,
                [overlay_x, overlay_y, overlay_w, overlay_h],
                overlay_opacity,
                overlay_angle,
            )?)
        };
        let design_arg = unsafe { cstr(design_json, "design_json") }?;
        let app_design: Option<serde_json::Value> = if design_arg.trim().is_empty() {
            None
        } else {
            Some(serde_json::from_str(design_arg).map_err(|e| format!("design_json: {e}"))?)
        };
        let out = unsafe { cstr(out_png, "out_png") }?;
        render_card(
            cards,
            card,
            hand_dir,
            fonts,
            extra,
            if fallback.is_empty() {
                None
            } else {
                Some(fallback)
            },
            if photo_final.is_empty() {
                None
            } else {
                Some(std::path::Path::new(photo_final))
            },
            overlay,
            app_design.as_ref(),
            out,
            max_edge,
        )
    })();
    match result {
        Ok(r) => {
            println!(
                "fm_hand.render card={} canvas={}x{} raw={}x{} photo={:?} ms={}",
                r.card,
                r.width,
                r.height,
                r.raw_width,
                r.raw_height,
                r.photo_rect.map(|v| (v * 1000.0).round() / 1000.0),
                r.ms
            );
            std::ptr::null_mut()
        }
        Err(e) => CString::new(e)
            .unwrap_or_else(|_| CString::new("hand render error").unwrap())
            .into_raw(),
    }
}

pub struct FmHandRgbaHandle {
    rgba: Vec<u8>,
    width: u32,
    height: u32,
}

fn set_out_error(out_error: *mut *mut c_char, message: String) {
    if out_error.is_null() {
        return;
    }
    let text =
        CString::new(message).unwrap_or_else(|_| CString::new("hand raw render error").unwrap());
    unsafe {
        *out_error = text.into_raw();
    }
}

/// Same hand render, returned as sRGB RGBA with a separate geometry sidecar.
#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "C" fn fm_render_hand_rgba(
    cards_path: *const c_char,
    card_id: *const c_char,
    hand_dir: *const c_char,
    fonts_dir: *const c_char,
    extra_fonts_dir: *const c_char,
    fallback_cjk: *const c_char,
    photo_path: *const c_char,
    photo_override: *const c_char,
    overlay_path: *const c_char,
    overlay_x: f32,
    overlay_y: f32,
    overlay_w: f32,
    overlay_h: f32,
    overlay_opacity: f32,
    overlay_angle: f32,
    design_json: *const c_char,
    geom_path: *const c_char,
    max_edge: i32,
    out_error: *mut *mut c_char,
) -> *mut FmHandRgbaHandle {
    if !out_error.is_null() {
        unsafe {
            *out_error = std::ptr::null_mut();
        }
    }
    let result = (|| -> Result<FmHandRgbaHandle, String> {
        let cards = unsafe { cstr(cards_path, "cards_path") }?;
        let card = unsafe { cstr(card_id, "card_id") }?;
        let hand_dir = unsafe { cstr(hand_dir, "hand_dir") }?;
        let fonts = unsafe { cstr(fonts_dir, "fonts_dir") }?;
        let extra = unsafe { cstr(extra_fonts_dir, "extra_fonts_dir") }?;
        let fallback = unsafe { cstr(fallback_cjk, "fallback_cjk") }?;
        let photo = unsafe { cstr(photo_path, "photo_path") }?;
        let photo_override = unsafe { cstr(photo_override, "photo_override") }?;
        let photo_final = if photo_override.is_empty() {
            photo
        } else {
            photo_override
        };
        let overlay_arg = unsafe { cstr(overlay_path, "overlay_path") }?;
        let overlay = if overlay_arg.is_empty() {
            None
        } else {
            Some(overlay_spec(
                overlay_arg,
                [overlay_x, overlay_y, overlay_w, overlay_h],
                overlay_opacity,
                overlay_angle,
            )?)
        };
        let design_arg = unsafe { cstr(design_json, "design_json") }?;
        let app_design: Option<serde_json::Value> = if design_arg.trim().is_empty() {
            None
        } else {
            Some(serde_json::from_str(design_arg).map_err(|e| format!("design_json: {e}"))?)
        };
        let geom = unsafe { cstr(geom_path, "geom_path") }?;
        let rendered = render_card_rgba(
            cards,
            card,
            hand_dir,
            fonts,
            extra,
            if fallback.is_empty() {
                None
            } else {
                Some(fallback)
            },
            if photo_final.is_empty() {
                None
            } else {
                Some(std::path::Path::new(photo_final))
            },
            overlay,
            app_design.as_ref(),
            geom,
            max_edge,
        )?;
        Ok(FmHandRgbaHandle {
            rgba: rendered.rgba,
            width: rendered.width,
            height: rendered.height,
        })
    })();
    match result {
        Ok(handle) => Box::into_raw(Box::new(handle)),
        Err(error) => {
            set_out_error(out_error, error);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn fm_rgba_hand_data(handle: *const FmHandRgbaHandle) -> *const u8 {
    if handle.is_null() {
        return std::ptr::null();
    }
    unsafe { (*handle).rgba.as_ptr() }
}

#[no_mangle]
pub extern "C" fn fm_rgba_hand_len(handle: *const FmHandRgbaHandle) -> usize {
    if handle.is_null() {
        return 0;
    }
    unsafe { (*handle).rgba.len() }
}

#[no_mangle]
pub extern "C" fn fm_rgba_hand_width(handle: *const FmHandRgbaHandle) -> u32 {
    if handle.is_null() {
        return 0;
    }
    unsafe { (*handle).width }
}

#[no_mangle]
pub extern "C" fn fm_rgba_hand_height(handle: *const FmHandRgbaHandle) -> u32 {
    if handle.is_null() {
        return 0;
    }
    unsafe { (*handle).height }
}

#[no_mangle]
pub extern "C" fn fm_rgba_hand_free(handle: *mut FmHandRgbaHandle) {
    if !handle.is_null() {
        unsafe {
            drop(Box::from_raw(handle));
        }
    }
}
