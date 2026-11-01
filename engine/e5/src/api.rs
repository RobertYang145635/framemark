//! Reusable export API (E5 implementation).
//!
//! Policy is fixed by D-027: JPEG q100 4:4:4 at every quality, Chromium container
//! (Skia's APP0 + 456 B sRGB ICC), PNG level 6, EXIF tag-for-tag like the frozen
//! `injectJpegExif` with the source thumbnail carried through.

use skia_safe::image::CachingHint;
use skia_safe::{
    jpeg_encoder, png_encoder, AlphaType, ColorSpace, ColorType, Data, Image, ImageInfo, Pixmap,
};
use std::path::Path;

use crate::{exif, jpegmeta};

/// D-027: the export path is q100 4:4:4.
pub const DEFAULT_JPEG_QUALITY: u32 = 100;
/// D-027: PNG level 6 (50 MP: 3.05 s, +7.2 % vs level 9's 17–24 s).
pub const DEFAULT_PNG_LEVEL: i32 = 6;

pub fn srgb_info(w: u32, h: u32) -> ImageInfo {
    ImageInfo::new(
        (w as i32, h as i32),
        ColorType::RGBA8888,
        AlphaType::Unpremul,
        Some(ColorSpace::new_srgb()),
    )
}

/// Decode any Skia-supported image to straight RGBA8 in sRGB.
pub fn decode_rgba(bytes: &[u8]) -> Result<(Vec<u8>, u32, u32), String> {
    let img = Image::from_encoded(Data::new_copy(bytes)).ok_or("Skia decode failed")?;
    let (w, h) = (img.width(), img.height());
    if w <= 0 || h <= 0 {
        return Err("decoded image has no pixels".into());
    }
    let info = srgb_info(w as u32, h as u32);
    let mut buf = vec![0u8; w as usize * h as usize * 4];
    let ok = img.read_pixels(&info, &mut buf, w as usize * 4, (0, 0), CachingHint::Disallow);
    if !ok {
        return Err("read_pixels failed".into());
    }
    drop(img);
    Ok((buf, w as u32, h as u32))
}

/// Raw JPEG encode (no EXIF). Exposed for the q<100 evidence rows; the product
/// path is [`encode_jpeg`].
pub fn encode_jpeg_raw(
    rgba: &mut [u8],
    w: u32,
    h: u32,
    quality: u32,
    downsample: jpeg_encoder::Downsample,
) -> Result<Vec<u8>, String> {
    let info = srgb_info(w, h);
    let pixmap = Pixmap::new(&info, rgba, w as usize * 4).ok_or("Pixmap::new failed")?;
    let mut out = Vec::new();
    let ok = jpeg_encoder::encode(
        &pixmap,
        &mut out,
        &jpeg_encoder::Options { quality, downsample, ..Default::default() },
    );
    drop(pixmap);
    if ok {
        Ok(out)
    } else {
        Err("jpeg encode failed".into())
    }
}

/// PNG encode. `level` is the zlib level (D-027 default: 6); libpng's adaptive
/// filter heuristic is used (same strategy as the frozen desktop policy).
pub fn encode_png(rgba: &mut [u8], w: u32, h: u32, level: i32) -> Result<Vec<u8>, String> {
    let info = srgb_info(w, h);
    let pixmap = Pixmap::new(&info, rgba, w as usize * 4).ok_or("Pixmap::new failed")?;
    let mut opts = png_encoder::Options::default();
    opts.filter_flags = png_encoder::FilterFlag::ALL;
    opts.z_lib_level = level;
    let mut out = Vec::new();
    let ok = png_encoder::encode(&pixmap, &mut out, &opts);
    drop(pixmap);
    if ok {
        Ok(out)
    } else {
        Err("png encode failed".into())
    }
}

/// The `fallbackExif` object the frozen app passes to `injectJpegExif` when the
/// source has no readable EXIF (`src/main/index.ts` → `buildFallbackExif`).
#[derive(Clone, Debug, Default)]
pub struct FallbackExif {
    pub make: String,
    pub model: String,
    /// `YYYY:MM:DD HH:MM:SS` (the frozen code replaces `-` with `:`).
    pub date_time: String,
    pub lens: String,
    pub aperture: Option<f32>,
    /// Physical focal length in mm, or the 35 mm equivalent when
    /// `focal_is_35mm` is true (the frozen `__focal35` flag).
    pub focal: Option<f32>,
    pub focal_is_35mm: bool,
    pub iso: Option<u32>,
    pub shutter: Option<f32>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ExifSource {
    Source,
    Fallback,
    Minimal,
}

#[derive(Clone, Debug, Default)]
pub struct ExifReport {
    pub source: Option<ExifSource>,
    pub ifd0_tags: usize,
    pub exif_tags: usize,
    pub thumbnail_bytes: usize,
    /// 10 = APP1 marker + length + "Exif\0\0".
    pub app1_bytes: usize,
    pub warning: Option<String>,
}

pub struct JpegMeta<'a> {
    /// A source JPEG whose EXIF (and thumbnail) is copied. `None` → fallback.
    pub source_jpeg: Option<&'a [u8]>,
    pub fallback: Option<&'a FallbackExif>,
    /// D-027 / frozen default: GPS is stripped on export.
    pub remove_gps: bool,
    /// D-027: keep the source IFD1 thumbnail.
    pub keep_thumbnail: bool,
    /// Override the encoder's embedded ICC (Skia writes a 456 B sRGB profile by
    /// itself; the frozen desktop policy wrote 588 B). `None` = Chromium口径.
    pub icc: Option<&'a [u8]>,
}

impl Default for JpegMeta<'_> {
    fn default() -> Self {
        Self {
            source_jpeg: None,
            fallback: None,
            remove_gps: true,
            keep_thumbnail: true,
            icc: None,
        }
    }
}

fn fallback_block(f: &FallbackExif) -> exif::Block {
    let mut b = exif::Block::default();
    if !f.make.is_empty() {
        b.set_ascii(exif::Ifd::Ifd0, 0x010F, &f.make);
    }
    if !f.model.is_empty() {
        b.set_ascii(exif::Ifd::Ifd0, 0x0110, &f.model);
    }
    if !f.date_time.is_empty() {
        let dt = f.date_time.replace('-', ":");
        b.set_ascii(exif::Ifd::Ifd0, 0x0132, &dt);
        b.set_ascii(exif::Ifd::Exif, 0x9003, &dt);
    }
    if !f.lens.is_empty() {
        b.set_ascii(exif::Ifd::Exif, 0xA434, &f.lens);
    }
    if let Some(a) = f.aperture {
        b.set_rational(exif::Ifd::Exif, 0x829D, (a * 100.0).round() as u32, 100);
    }
    if let Some(focal) = f.focal {
        // The frozen code passes [mm*100, 100] for both tags; 0xA405 is typed
        // SHORT, so it lands as two SHORTs — reproduce it exactly.
        if f.focal_is_35mm {
            b.set_short_pair(exif::Ifd::Exif, 0xA405, (focal * 100.0).round() as u16, 100);
        } else {
            b.set_rational(exif::Ifd::Exif, 0x920A, (focal * 100.0).round() as u32, 100);
        }
    }
    if let Some(iso) = f.iso {
        b.set_u16(exif::Ifd::Exif, 0x8827, iso.min(u16::MAX as u32) as u16);
    }
    if let Some(s) = f.shutter {
        if s < 1.0 {
            b.set_rational(exif::Ifd::Exif, 0x829A, 1, (1.0 / s).round() as u32);
        } else {
            b.set_rational(exif::Ifd::Exif, 0x829A, (s * 100.0).round() as u32, 100);
        }
    }
    b
}

/// Full export encode: q100 4:4:4 + EXIF (copy/override/GPS/thumbnail) on the
/// Chromium container. Returns the bytes plus a report of what was embedded.
pub fn encode_jpeg_with_report(
    rgba: &mut [u8],
    w: u32,
    h: u32,
    meta: &JpegMeta,
) -> Result<(Vec<u8>, ExifReport), String> {
    let mut out = encode_jpeg_raw(rgba, w, h, DEFAULT_JPEG_QUALITY, jpeg_encoder::Downsample::No)?;
    if let Some(icc) = meta.icc {
        out = jpegmeta::insert_icc(&out, icc)?;
    }

    let (mut block, source) = match meta.source_jpeg {
        Some(src) => match exif::load(src) {
            Ok(b) => (b, ExifSource::Source),
            Err(_) => match meta.fallback {
                Some(f) => (fallback_block(f), ExifSource::Fallback),
                None => (exif::Block::default(), ExifSource::Minimal),
            },
        },
        None => match meta.fallback {
            Some(f) => (fallback_block(f), ExifSource::Fallback),
            None => (exif::Block::default(), ExifSource::Minimal),
        },
    };
    if !meta.keep_thumbnail {
        block.thumbnail = None;
    }
    exif::prepare_export(&mut block, w, h, meta.remove_gps);

    let thumbnail_bytes = block.thumbnail.as_ref().map(|t| t.data.len()).unwrap_or(0);
    let mut report = ExifReport {
        source: Some(source),
        ifd0_tags: block.ifd0.len(),
        exif_tags: block.exif.len(),
        thumbnail_bytes,
        app1_bytes: 0,
        warning: None,
    };
    let tiff = exif::dump(&block);
    report.app1_bytes = tiff.len() + 10;
    match jpegmeta::insert_exif(&out, &tiff) {
        Ok(bytes) => Ok((bytes, report)),
        Err(e) => {
            report.warning = Some(format!("exif inject failed, plain JPEG returned: {e}"));
            Ok((out, report))
        }
    }
}

/// `encode_jpeg(rgba,w,h,meta) -> Vec<u8>` (D-027 policy).
pub fn encode_jpeg(rgba: &mut [u8], w: u32, h: u32, meta: &JpegMeta) -> Result<Vec<u8>, String> {
    let (bytes, report) = encode_jpeg_with_report(rgba, w, h, meta)?;
    if let Some(w) = report.warning {
        eprintln!("[fm-e5] {w}");
    }
    Ok(bytes)
}

pub struct TiffRgba {
    pub rgba: Vec<u8>,
    pub w: u32,
    pub h: u32,
    /// EXIF orientation 1..=8 as read from IFD0; the caller decides whether to
    /// apply it (the frozen import path does: `sharp(file).rotate()`).
    pub orientation: u16,
    /// Source sample layout, e.g. `RGB(8)`, for logging.
    pub color: String,
}

/// TIFF source → RGBA (Skia has no TIFF decoder; this is the engine-side
/// replacement for the main-process `sharp` transcode).
pub fn tiff_to_rgba(path: &Path) -> Result<TiffRgba, String> {
    let img = crate::tiffsrc::decode(path)?;
    Ok(TiffRgba {
        rgba: img.rgba,
        w: img.w,
        h: img.h,
        orientation: img.orientation,
        color: img.color,
    })
}

/// Apply an EXIF orientation to an RGBA buffer (the `sharp .rotate()` step).
pub fn apply_orientation(rgba: &[u8], w: u32, h: u32, orientation: u16) -> (Vec<u8>, u32, u32) {
    crate::tiffsrc::apply_orientation(rgba, w, h, orientation)
}
