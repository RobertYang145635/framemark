//! FrameMark native engine — E5 IO/export module.
//!
//! Public API lives in [`api`] (`encode_jpeg`, `encode_png`, `tiff_to_rgba`).
//! The bench binary (`src/bin/e5.rs`) drives the same code with recording and
//! memory sampling; nothing here touches the main engine or the oracle.

pub mod api;
pub mod exif;
pub mod jpegmeta;
pub mod rss;
pub mod sha1;
pub mod tiffsrc;

pub use api::{
    apply_orientation, decode_rgba, encode_jpeg, encode_jpeg_raw, encode_jpeg_with_report,
    encode_png, tiff_to_rgba, ExifReport, ExifSource, FallbackExif, JpegMeta, TiffRgba,
    DEFAULT_JPEG_QUALITY, DEFAULT_PNG_LEVEL,
};
