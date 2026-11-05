//! CPU replacement for e4-hand's Ganesh helpers.
//!
//! The only entry the portable modules use is `raster_surface` (offscreen temp
//! canvases for blur/palette work). It keeps e2b/e4-hand's exact ImageInfo
//! (RGBA8888, premul, sRGB) so the offscreen math stays the same; only the
//! backend changes from Ganesh to the CPU raster surface.

use skia_safe::{surfaces, AlphaType, ColorSpace, ColorType, ImageInfo, Surface};

pub fn raster_surface(w: i32, h: i32) -> Surface {
    let info = ImageInfo::new(
        (w.max(1), h.max(1)),
        ColorType::RGBA8888,
        AlphaType::Premul,
        Some(ColorSpace::new_srgb()),
    );
    surfaces::raster(&info, None, None).expect("raster surface alloc")
}
