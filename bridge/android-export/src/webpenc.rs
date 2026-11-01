//! WebP encoding for the Android export bridge: VP8L (lossless, default) or VP8
//! at a given quality, with the same metadata policy as the desktop worker —
//! ICC becomes an `ICCP` chunk and EXIF an `EXIF` chunk whose payload is the
//! `Exif\0\0` + TIFF block the caller builds (what browsers and libwebp's muxer
//! round-trip).
//!
//! libwebp 1.5.0 is compiled from the bundled C sources by `libwebp-sys2`
//! (static), so the APK gains one self-contained encoder and no system library.

use libwebp_sys as webp;
use std::ffi::CString;
use std::os::raw::c_void;
use std::ptr;

const MUX_ABI: std::os::raw::c_int = 0x0109; // libwebp 1.5.0 WEBP_MUX_ABI_VERSION

pub struct WebpOutput {
    pub bytes: Vec<u8>,
    pub lossless: bool,
}

fn encode_bitstream(
    rgba: &[u8],
    w: u32,
    h: u32,
    lossless: bool,
    quality: f32,
) -> Result<Vec<u8>, String> {
    let stride = (w as usize * 4) as i32;
    if lossless {
        // `WebPEncodeLosslessRGBA` is the exact path the desktop worker uses for
        // VP8L; the picture/config API (below) is kept for the VP8 side only.
        return unsafe {
            let mut out: *mut u8 = std::ptr::null_mut();
            let n = webp::WebPEncodeLosslessRGBA(rgba.as_ptr(), w as i32, h as i32, stride, &mut out);
            if n == 0 || out.is_null() {
                return Err("WebPEncodeLosslessRGBA failed".into());
            }
            let v = std::slice::from_raw_parts(out, n).to_vec();
            webp::WebPFree(out as *mut c_void);
            Ok(v)
        };
    }
    unsafe {
        let mut cfg: webp::WebPConfig = std::mem::zeroed();
        if webp::WebPConfigInitInternal(
            &mut cfg,
            webp::WEBP_PRESET_DEFAULT,
            quality,
            webp::WEBP_ENCODER_ABI_VERSION,
        ) == 0
        {
            return Err("WebPConfigInit failed".into());
        }
        let mut pic: webp::WebPPicture = std::mem::zeroed();
        if webp::WebPPictureInitInternal(&mut pic, webp::WEBP_ENCODER_ABI_VERSION) == 0 {
            return Err("WebPPictureInit failed".into());
        }
        pic.width = w as i32;
        pic.height = h as i32;
        if webp::WebPPictureImportRGBA(&mut pic, rgba.as_ptr(), stride) == 0 {
            webp::WebPPictureFree(&mut pic);
            return Err("WebPPictureImportRGBA failed".into());
        }
        let mut writer: webp::WebPMemoryWriter = std::mem::zeroed();
        webp::WebPMemoryWriterInit(&mut writer);
        extern "C" fn writer_cb(
            data: *const u8,
            size: usize,
            pic: *const webp::WebPPicture,
        ) -> std::os::raw::c_int {
            unsafe { webp::WebPMemoryWrite(data, size, pic) }
        }
        pic.writer = Some(writer_cb);
        pic.custom_ptr = &mut writer as *mut _ as *mut c_void;
        let ok = webp::WebPEncode(&cfg, &mut pic);
        webp::WebPPictureFree(&mut pic);
        if ok == 0 {
            webp::WebPMemoryWriterClear(&mut writer);
            return Err("WebPEncode failed".into());
        }
        let v = std::slice::from_raw_parts(writer.mem, writer.size).to_vec();
        webp::WebPMemoryWriterClear(&mut writer);
        Ok(v)
    }
}

/// Wrap the bitstream in a RIFF container carrying ICCP / EXIF when provided.
fn mux_metadata(
    bitstream: &[u8],
    icc: Option<&[u8]>,
    exif: Option<&[u8]>,
) -> Result<Vec<u8>, String> {
    if icc.is_none() && exif.is_none() {
        return Ok(bitstream.to_vec());
    }
    unsafe {
        let src = webp::WebPData { bytes: bitstream.as_ptr(), size: bitstream.len() };
        let mux = webp::WebPMuxCreateInternal(&src, 0, MUX_ABI);
        if mux.is_null() {
            return Err("WebPMuxCreate failed".into());
        }
        for (fourcc, data) in [("ICCP", icc), ("EXIF", exif)] {
            let Some(d) = data else { continue };
            let cc = CString::new(fourcc).unwrap();
            let chunk = webp::WebPData { bytes: d.as_ptr(), size: d.len() };
            if webp::WebPMuxSetChunk(mux, cc.as_ptr(), &chunk, 1) != webp::WEBP_MUX_OK {
                webp::WebPMuxDelete(mux);
                return Err(format!("WebPMuxSetChunk {fourcc} failed"));
            }
        }
        let mut assembled: webp::WebPData = std::mem::zeroed();
        if webp::WebPMuxAssemble(mux, &mut assembled) != webp::WEBP_MUX_OK {
            webp::WebPMuxDelete(mux);
            return Err("WebPMuxAssemble failed".into());
        }
        let v = std::slice::from_raw_parts(assembled.bytes, assembled.size).to_vec();
        webp::WebPDataClear(&mut assembled);
        webp::WebPMuxDelete(mux);
        Ok(v)
    }
}

pub fn encode(
    rgba: &[u8],
    w: u32,
    h: u32,
    lossless: bool,
    quality: f32,
    icc: Option<&[u8]>,
    exif: Option<&[u8]>,
) -> Result<WebpOutput, String> {
    let bitstream = encode_bitstream(rgba, w, h, lossless, quality)?;
    let bytes = mux_metadata(&bitstream, icc, exif)?;
    Ok(WebpOutput { bytes, lossless })
}

/// RIFF chunk list, e.g. `["VP8L", "ICCP", "EXIF"]`.
pub fn chunks(b: &[u8]) -> Vec<String> {
    let mut out = Vec::new();
    if b.len() < 12 || &b[0..4] != b"RIFF" || &b[8..12] != b"WEBP" {
        return out;
    }
    let mut i = 12usize;
    while i + 8 <= b.len() {
        let id = String::from_utf8_lossy(&b[i..i + 4]).to_string();
        let len = u32::from_le_bytes([b[i + 4], b[i + 5], b[i + 6], b[i + 7]]) as usize;
        out.push(id);
        i += 8 + len + (len & 1);
    }
    out
}

pub const ENCODER_ID: &str = "libwebp 1.5.0 (bundled, static)";