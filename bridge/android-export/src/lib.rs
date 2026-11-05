//! X1 Android export encoder.
//!
//! Reuses the E5 in-process API's meta path (`engine/e5/src/{exif,jpegmeta}.rs`,
//! read-only) with Skia's q100 4:4:4 JPEG encoder — the same encoder family the
//! app already links, so no second libjpeg enters the process. The mozjpeg
//! worker path stays a desktop-only concern (D-028 memory), noted as a GAP.

#[path = "../../../engine/e5/src/exif.rs"]
mod exif;
#[path = "../../../engine/e5/src/jpegmeta.rs"]
mod jpegmeta;
mod webpenc;

/// Host harness hook: encode RGBA losslessly with the same path the export uses.
pub fn webp_probe(
    rgba: &[u8],
    w: u32,
    h: u32,
    lossless: bool,
    quality: f32,
) -> Result<Vec<u8>, String> {
    webpenc::encode(rgba, w, h, lossless, quality, None, None).map(|o| o.bytes)
}

use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::path::Path;

use skia_safe::{jpeg_encoder, AlphaType, ColorSpace, ColorType, ImageInfo, Pixmap};

pub const QUALITY: u32 = 100;

/// Raw RGBA of a PNG through this crate's decode path — the exact input the
/// encoders see. Used by the host harness to prove a WebP is lossless.
pub fn decode_png(png: &[u8]) -> Result<(Vec<u8>, u32, u32), String> {
    decode_png_rgba(png)
}

fn srgb_info(w: u32, h: u32) -> ImageInfo {
    ImageInfo::new(
        (w as i32, h as i32),
        ColorType::RGBA8888,
        AlphaType::Unpremul,
        Some(ColorSpace::new_srgb()),
    )
}

pub fn decode_png_rgba(png: &[u8]) -> Result<(Vec<u8>, u32, u32), String> {
    let img = skia_safe::Image::from_encoded(skia_safe::Data::new_copy(png))
        .ok_or("decode png failed")?;
    let (w, h) = (img.width(), img.height());
    if w <= 0 || h <= 0 {
        return Err(format!("png has no pixels: {w}x{h}"));
    }
    let info = srgb_info(w as u32, h as u32);
    let mut rgba = vec![0u8; (w as usize) * (h as usize) * 4];
    let ok = img.read_pixels(
        &info,
        &mut rgba,
        w as usize * 4,
        (0, 0),
        skia_safe::image::CachingHint::Disallow,
    );
    if !ok {
        return Err("read_pixels failed".into());
    }
    Ok((rgba, w as u32, h as u32))
}

fn encode_jpeg_444(rgba: &mut [u8], w: u32, h: u32) -> Result<Vec<u8>, String> {
    let info = srgb_info(w, h);
    let pixmap = Pixmap::new(&info, rgba, w as usize * 4).ok_or("Pixmap::new failed")?;
    let mut out = Vec::new();
    let ok = jpeg_encoder::encode(
        &pixmap,
        &mut out,
        &jpeg_encoder::Options {
            quality: QUALITY,
            downsample: jpeg_encoder::Downsample::No,
            ..Default::default()
        },
    );
    drop(pixmap);
    if ok {
        Ok(out)
    } else {
        Err("jpeg encode failed".into())
    }
}

/// Full export: q100 4:4:4 → ICC APP2 → EXIF APP1 (source copy + overrides,
/// GPS stripped, IFD1 thumbnail carried) — the D-027 policy.
pub fn encode_export(
    rgba: &mut [u8],
    w: u32,
    h: u32,
    icc: Option<&[u8]>,
    source_jpeg: Option<&[u8]>,
    remove_gps: bool,
    keep_thumbnail: bool,
) -> Result<(Vec<u8>, serde_json::Value), String> {
    if rgba.len() != (w as usize) * (h as usize) * 4 {
        return Err(format!("rgba length {} != {w}x{h}x4", rgba.len()));
    }
    let mut out = encode_jpeg_444(rgba, w, h)?;
    if let Some(icc) = icc {
        out = jpegmeta::insert_icc(&out, icc)?;
    }
    let mut block = match source_jpeg {
        Some(src) => exif::load(src).unwrap_or_default(),
        None => exif::Block::default(),
    };
    if !keep_thumbnail {
        block.thumbnail = None;
    }
    exif::prepare_export(&mut block, w, h, remove_gps);
    let thumb_bytes = block.thumbnail.as_ref().map(|t| t.data.len()).unwrap_or(0);
    let tiff = exif::dump(&block);
    let app1_bytes = tiff.len() + 10;
    out = jpegmeta::insert_exif(&out, &tiff)?;
    let info = jpegmeta::parse(&out)?;
    let icc_bytes = info.icc.as_ref().map(|v| v.len()).unwrap_or(0);
    let subsampling = info
        .sof
        .as_ref()
        .map(jpegmeta::subsampling_label)
        .unwrap_or_else(|| "?".to_string());
    let report = serde_json::json!({
        "w": w, "h": h, "bytes": out.len(), "quality": QUALITY,
        "subsampling": subsampling, "iccBytes": icc_bytes,
        "hasExif": info.has_exif, "app1Bytes": app1_bytes, "thumbnailBytes": thumb_bytes,
        "removeGps": remove_gps, "keepThumbnail": keep_thumbnail,
    });
    Ok((out, report))
}

/// Encode an already-rasterised RGBA frame without materialising a PNG. The
/// renderer and exporter share this buffer in-process; metadata and JPEG/WebP
/// policy are identical to the PNG-input path.
pub fn encode_export_rgba(
    rgba: &mut [u8],
    w: u32,
    h: u32,
    icc: Option<&[u8]>,
    source_jpeg: Option<&[u8]>,
    remove_gps: bool,
    keep_thumbnail: bool,
    render_ms: u64,
) -> Result<(Vec<u8>, serde_json::Value), String> {
    let t0 = std::time::Instant::now();
    let (bytes, mut report) =
        encode_export(rgba, w, h, icc, source_jpeg, remove_gps, keep_thumbnail)?;
    if let Some(obj) = report.as_object_mut() {
        obj.insert(
            "msEncode".into(),
            serde_json::json!(t0.elapsed().as_millis() as u64),
        );
        obj.insert("renderMs".into(), serde_json::json!(render_ms));
        obj.insert("input".into(), serde_json::json!("rgba"));
    }
    Ok((bytes, report))
}

/// Decode the engine's PNG inside the library (Skia already linked) and run the
/// full export chain; `msEncode` covers decode + JPEG + meta.
pub fn encode_export_png(
    png: &[u8],
    icc: Option<&[u8]>,
    source_jpeg: Option<&[u8]>,
    remove_gps: bool,
    keep_thumbnail: bool,
) -> Result<(Vec<u8>, serde_json::Value), String> {
    let t0 = std::time::Instant::now();
    let (mut rgba, w, h) = decode_png_rgba(png)?;
    let (bytes, mut report) = encode_export(
        &mut rgba,
        w,
        h,
        icc,
        source_jpeg,
        remove_gps,
        keep_thumbnail,
    )?;
    if let Some(obj) = report.as_object_mut() {
        obj.insert(
            "msEncode".into(),
            serde_json::json!(t0.elapsed().as_millis() as u64),
        );
    }
    Ok((bytes, report))
}

/// WebP export: same metadata policy as the JPEG chain (ICC + EXIF with the
/// source tags, GPS stripped, thumbnail carried) over a VP8L (lossless, the
/// PRD default) or VP8 bitstream.
pub fn encode_export_webp(
    png: &[u8],
    icc: Option<&[u8]>,
    source_jpeg: Option<&[u8]>,
    remove_gps: bool,
    keep_thumbnail: bool,
    lossless: bool,
    quality: f32,
) -> Result<(Vec<u8>, serde_json::Value), String> {
    let t0 = std::time::Instant::now();
    let (rgba, w, h) = decode_png_rgba(png)?;
    encode_export_webp_rgba(
        &rgba,
        w,
        h,
        icc,
        source_jpeg,
        remove_gps,
        keep_thumbnail,
        lossless,
        quality,
        t0,
    )
}

fn encode_export_webp_rgba(
    rgba: &[u8],
    w: u32,
    h: u32,
    icc: Option<&[u8]>,
    source_jpeg: Option<&[u8]>,
    remove_gps: bool,
    keep_thumbnail: bool,
    lossless: bool,
    quality: f32,
    t0: std::time::Instant,
) -> Result<(Vec<u8>, serde_json::Value), String> {
    if rgba.len() != (w as usize) * (h as usize) * 4 {
        return Err(format!("rgba length {} != {w}x{h}x4", rgba.len()));
    }
    let mut block = match source_jpeg {
        Some(src) => exif::load(src).unwrap_or_default(),
        None => exif::Block::default(),
    };
    if !keep_thumbnail {
        block.thumbnail = None;
    }
    exif::prepare_export(&mut block, w, h, remove_gps);
    let thumb_bytes = block.thumbnail.as_ref().map(|t| t.data.len()).unwrap_or(0);
    let tiff = exif::dump(&block);
    let mut exif_payload = b"Exif\0\0".to_vec();
    exif_payload.extend_from_slice(&tiff);
    let encoded = webpenc::encode(&rgba, w, h, lossless, quality, icc, Some(&exif_payload))?;
    let ms = t0.elapsed().as_millis() as u64;
    let report = serde_json::json!({
        "w": w, "h": h, "bytes": encoded.bytes.len(), "lossless": encoded.lossless,
        "quality": if lossless { 100.0 } else { quality },
        "chunks": webpenc::chunks(&encoded.bytes),
        "iccBytes": icc.map(|v| v.len()).unwrap_or(0),
        "hasExif": true, "app1Bytes": exif_payload.len(), "thumbnailBytes": thumb_bytes,
        "removeGps": remove_gps, "keepThumbnail": keep_thumbnail,
        "encoder": webpenc::ENCODER_ID, "msEncode": ms,
    });
    Ok((encoded.bytes, report))
}

fn encode_export_webp_from_rgba(
    rgba: &[u8],
    w: u32,
    h: u32,
    icc: Option<&[u8]>,
    source_jpeg: Option<&[u8]>,
    remove_gps: bool,
    keep_thumbnail: bool,
    lossless: bool,
    quality: f32,
) -> Result<(Vec<u8>, serde_json::Value), String> {
    encode_export_webp_rgba(
        rgba,
        w,
        h,
        icc,
        source_jpeg,
        remove_gps,
        keep_thumbnail,
        lossless,
        quality,
        std::time::Instant::now(),
    )
}

unsafe fn cstr<'a>(ptr: *const c_char, name: &str) -> Result<&'a str, String> {
    if ptr.is_null() {
        return Err(format!("{name} is null"));
    }
    CStr::from_ptr(ptr)
        .to_str()
        .map_err(|e| format!("{name}: {e}"))
}

unsafe fn read_optional(ptr: *const c_char, name: &str) -> Result<Option<Vec<u8>>, String> {
    let p = cstr(ptr, name)?;
    if p.is_empty() {
        Ok(None)
    } else {
        std::fs::read(p)
            .map(Some)
            .map_err(|e| format!("read {name}: {e}"))
    }
}

fn write_outputs(
    out_path: &str,
    report_path: &str,
    bytes: &[u8],
    report: &serde_json::Value,
) -> Result<(), String> {
    use std::io::Write;

    if let Some(parent) = Path::new(out_path).parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("create {}: {e}", parent.display()))?;
    }
    if let Some(parent) = Path::new(report_path).parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("create {}: {e}", parent.display()))?;
    }
    let report_bytes =
        serde_json::to_vec_pretty(report).map_err(|e| format!("serialize report: {e}"))?;
    let mut output = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(out_path)
        .map_err(|e| format!("create {out_path}: {e}"))?;
    let image_write = output.write_all(bytes);
    drop(output);
    if let Err(e) = image_write {
        std::fs::remove_file(out_path).ok();
        return Err(format!("write {out_path}: {e}"));
    }
    let result = (|| {
        let mut report_file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(report_path)
            .map_err(|e| format!("create {report_path}: {e}"))?;
        if let Err(e) = report_file.write_all(&report_bytes) {
            drop(report_file);
            std::fs::remove_file(report_path).ok();
            return Err(format!("write {report_path}: {e}"));
        }
        Ok(())
    })();
    if result.is_err() {
        std::fs::remove_file(out_path).ok();
    }
    result
}

/// JSON envelope: `{"ok":true,"report":{...}}` or `{"ok":false,"error":"..."}`.
fn into_ret(result: Result<serde_json::Value, String>) -> *mut c_char {
    let envelope = match result {
        Ok(report) => serde_json::json!({ "ok": true, "report": report }),
        Err(error) => serde_json::json!({ "ok": false, "error": error }),
    };
    let text = serde_json::to_string(&envelope)
        .unwrap_or_else(|_| "{\"ok\":false,\"error\":\"serialize\"}".into());
    CString::new(text)
        .unwrap_or_else(|_| CString::new("{\"ok\":false,\"error\":\"nul\"}").unwrap())
        .into_raw()
}

/// `fm_export_jpeg(rgba_path, w, h, icc_path, exif_src_jpeg, remove_gps,
/// keep_thumb, out_path, report_path)` → JSON envelope, caller frees with
/// `fm_export_free_string`.
///
/// RGBA travels as a raw file (w*h*4 bytes) to keep the JNI surface small.
#[no_mangle]
pub extern "C" fn fm_export_jpeg(
    rgba_path: *const c_char,
    w: u32,
    h: u32,
    icc_path: *const c_char,
    exif_src: *const c_char,
    remove_gps: i32,
    keep_thumb: i32,
    out_path: *const c_char,
    report_path: *const c_char,
) -> *mut c_char {
    let result = (|| -> Result<serde_json::Value, String> {
        let rgba_path = unsafe { cstr(rgba_path, "rgba_path") }?;
        let out_path = unsafe { cstr(out_path, "out_path") }?;
        let report_path = unsafe { cstr(report_path, "report_path") }?;
        let mut rgba = std::fs::read(rgba_path).map_err(|e| format!("read rgba: {e}"))?;
        let icc = unsafe { read_optional(icc_path, "icc") }?;
        let src = unsafe { read_optional(exif_src, "exif source") }?;
        let (bytes, report) = encode_export(
            &mut rgba,
            w,
            h,
            icc.as_deref(),
            src.as_deref(),
            remove_gps != 0,
            keep_thumb != 0,
        )?;
        write_outputs(out_path, report_path, &bytes, &report)?;
        Ok(report)
    })();
    into_ret(result)
}

/// In-memory sibling of [`fm_export_jpeg`]. The pointer remains owned by the
/// renderer and is valid for this call only.
#[no_mangle]
pub extern "C" fn fm_export_jpeg_rgba(
    rgba: *const u8,
    len: usize,
    w: u32,
    h: u32,
    icc_path: *const c_char,
    exif_src: *const c_char,
    remove_gps: i32,
    keep_thumb: i32,
    out_path: *const c_char,
    report_path: *const c_char,
    render_ms: u64,
) -> *mut c_char {
    let result = (|| -> Result<serde_json::Value, String> {
        if rgba.is_null() {
            return Err("rgba is null".into());
        }
        let expected = (w as usize)
            .checked_mul(h as usize)
            .and_then(|n| n.checked_mul(4))
            .ok_or("rgba dimensions overflow")?;
        if len != expected {
            return Err(format!("rgba length {len} != {w}x{h}x4"));
        }
        let pixels = unsafe { std::slice::from_raw_parts_mut(rgba as *mut u8, len) };
        let out_path = unsafe { cstr(out_path, "out_path") }?;
        let report_path = unsafe { cstr(report_path, "report_path") }?;
        let icc = unsafe { read_optional(icc_path, "icc") }?;
        let src = unsafe { read_optional(exif_src, "exif source") }?;
        let (bytes, report) = encode_export_rgba(
            pixels,
            w,
            h,
            icc.as_deref(),
            src.as_deref(),
            remove_gps != 0,
            keep_thumb != 0,
            render_ms,
        )?;
        write_outputs(out_path, report_path, &bytes, &report)?;
        Ok(report)
    })();
    into_ret(result)
}

/// `fm_export_png(png_path, icc_path, exif_src_jpeg, remove_gps, keep_thumb,
/// out_path, report_path)` → JSON envelope. The engine PNG is decoded with the
/// already-linked Skia, so the caller never materialises a Java bitmap.
#[no_mangle]
pub extern "C" fn fm_export_png(
    png_path: *const c_char,
    icc_path: *const c_char,
    exif_src: *const c_char,
    remove_gps: i32,
    keep_thumb: i32,
    out_path: *const c_char,
    report_path: *const c_char,
) -> *mut c_char {
    let result = (|| -> Result<serde_json::Value, String> {
        let png_path = unsafe { cstr(png_path, "png_path") }?;
        let out_path = unsafe { cstr(out_path, "out_path") }?;
        let report_path = unsafe { cstr(report_path, "report_path") }?;
        let png = std::fs::read(png_path).map_err(|e| format!("read png: {e}"))?;
        let icc = unsafe { read_optional(icc_path, "icc") }?;
        let src = unsafe { read_optional(exif_src, "exif source") }?;
        let (bytes, report) = encode_export_png(
            &png,
            icc.as_deref(),
            src.as_deref(),
            remove_gps != 0,
            keep_thumb != 0,
        )?;
        write_outputs(out_path, report_path, &bytes, &report)?;
        Ok(report)
    })();
    into_ret(result)
}

/// `fm_export_webp(png_path, icc_path, exif_src_jpeg, remove_gps, keep_thumb,
/// lossless, quality, out_webp, report_path)` → JSON envelope.
#[no_mangle]
pub extern "C" fn fm_export_webp(
    png_path: *const c_char,
    icc_path: *const c_char,
    exif_src: *const c_char,
    remove_gps: i32,
    keep_thumb: i32,
    lossless: i32,
    quality: f32,
    out_path: *const c_char,
    report_path: *const c_char,
) -> *mut c_char {
    let result = (|| -> Result<serde_json::Value, String> {
        let png_path = unsafe { cstr(png_path, "png_path") }?;
        let out_path = unsafe { cstr(out_path, "out_path") }?;
        let report_path = unsafe { cstr(report_path, "report_path") }?;
        let png = std::fs::read(png_path).map_err(|e| format!("read png: {e}"))?;
        let icc = unsafe { read_optional(icc_path, "icc") }?;
        let src = unsafe { read_optional(exif_src, "exif source") }?;
        let q = if quality.is_finite() {
            quality.clamp(0.0, 100.0)
        } else {
            100.0
        };
        let (bytes, report) = encode_export_webp(
            &png,
            icc.as_deref(),
            src.as_deref(),
            remove_gps != 0,
            keep_thumb != 0,
            lossless != 0,
            q,
        )?;
        write_outputs(out_path, report_path, &bytes, &report)?;
        Ok(report)
    })();
    into_ret(result)
}

/// In-memory sibling of [`fm_export_webp`]; see [`fm_export_jpeg_rgba`] for
/// buffer lifetime.
#[no_mangle]
pub extern "C" fn fm_export_webp_rgba(
    rgba: *const u8,
    len: usize,
    w: u32,
    h: u32,
    icc_path: *const c_char,
    exif_src: *const c_char,
    remove_gps: i32,
    keep_thumb: i32,
    lossless: i32,
    quality: f32,
    out_path: *const c_char,
    report_path: *const c_char,
    render_ms: u64,
) -> *mut c_char {
    let result = (|| -> Result<serde_json::Value, String> {
        if rgba.is_null() {
            return Err("rgba is null".into());
        }
        let expected = (w as usize)
            .checked_mul(h as usize)
            .and_then(|n| n.checked_mul(4))
            .ok_or("rgba dimensions overflow")?;
        if len != expected {
            return Err(format!("rgba length {len} != {w}x{h}x4"));
        }
        let pixels = unsafe { std::slice::from_raw_parts_mut(rgba as *mut u8, len) };
        let out_path = unsafe { cstr(out_path, "out_path") }?;
        let report_path = unsafe { cstr(report_path, "report_path") }?;
        let icc = unsafe { read_optional(icc_path, "icc") }?;
        let src = unsafe { read_optional(exif_src, "exif source") }?;
        let q = if quality.is_finite() {
            quality.clamp(0.0, 100.0)
        } else {
            100.0
        };
        let (bytes, mut report) = encode_export_webp_from_rgba(
            pixels,
            w,
            h,
            icc.as_deref(),
            src.as_deref(),
            remove_gps != 0,
            keep_thumb != 0,
            lossless != 0,
            q,
        )?;
        if let Some(obj) = report.as_object_mut() {
            obj.insert("renderMs".into(), serde_json::json!(render_ms));
            obj.insert("input".into(), serde_json::json!("rgba"));
        }
        write_outputs(out_path, report_path, &bytes, &report)?;
        Ok(report)
    })();
    into_ret(result)
}

#[no_mangle]
pub extern "C" fn fm_export_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr));
        }
    }
}

#[cfg(test)]
mod output_tests {
    use super::write_outputs;

    #[test]
    fn existing_target_and_report_failure_never_clobber_files() {
        let dir = std::env::temp_dir().join(format!(
            "fm-export-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir(&dir).unwrap();
        let image = dir.join("image.jpg");
        let report = dir.join("report.json");
        std::fs::write(&image, b"original image").unwrap();
        assert!(write_outputs(
            image.to_str().unwrap(),
            report.to_str().unwrap(),
            b"new",
            &serde_json::json!({})
        )
        .is_err());
        assert_eq!(std::fs::read(&image).unwrap(), b"original image");
        assert!(!report.exists());

        std::fs::remove_file(&image).unwrap();
        std::fs::write(&report, b"original report").unwrap();
        assert!(write_outputs(
            image.to_str().unwrap(),
            report.to_str().unwrap(),
            b"new",
            &serde_json::json!({})
        )
        .is_err());
        assert!(!image.exists());
        assert_eq!(std::fs::read(&report).unwrap(), b"original report");
        std::fs::remove_dir_all(dir).unwrap();
    }
}
