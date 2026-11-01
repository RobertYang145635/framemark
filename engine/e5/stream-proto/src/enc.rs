//! Row-fed JPEG encoding with mozjpeg.
//!
//! `Dest::Mem` is what the parity bench uses; `Dest::File` is what the worker
//! uses so the 48 MB entropy-coded output never lives in the worker's heap
//! (mem-dest growth alone peaks ~185 MB at 50 MP).

use mozjpeg_sys as sys;
use std::ffi::CString;
use std::os::raw::{c_int, c_ulong};
use std::path::Path;
use std::ptr;

/// Reported by the worker so a byte mismatch can be attributed to the encoder
/// build rather than to the call parameters.
pub const ENCODER_ID: &str = "mozjpeg-sys 2.2.3 (scalar, no SIMD/nasm)";

pub enum Dest<'a> {
    Mem,
    File(&'a Path),
}

pub struct Encoded {
    pub bytes: Vec<u8>,
    pub file_bytes: u64,
    pub rows_fed: u32,
}

/// Encode by feeding rows. `fill(y, row_rgb)` must write `w * 3` bytes for row
/// `y`; it is called exactly `h` times (libjpeg's optimize pass re-reads its own
/// coefficient array, not this callback — verified with `rows_fed`).
pub fn encode_rows<F>(
    w: u32,
    h: u32,
    quality: u32,
    optimize: bool,
    icc: Option<&[u8]>,
    exif_tiff: Option<&[u8]>,
    dest: &Dest,
    mut fill: F,
) -> Result<Encoded, String>
where
    F: FnMut(u32, &mut [u8]),
{
    if w == 0 || h == 0 || w > 65535 || h > 65535 {
        return Err(format!("unsupported dimensions {w}x{h}"));
    }
    unsafe {
        let mut err: sys::jpeg_error_mgr = std::mem::zeroed();
        let mut cinfo: sys::jpeg_compress_struct = std::mem::zeroed();
        cinfo.common.err = sys::jpeg_std_error(&mut err);
        sys::jpeg_CreateCompress(
            &mut cinfo,
            sys::JPEG_LIB_VERSION,
            std::mem::size_of::<sys::jpeg_compress_struct>(),
        );

        let mut outbuf: *mut u8 = ptr::null_mut();
        let mut outsize: c_ulong = 0;
        let mut file: *mut libc::FILE = ptr::null_mut();
        match dest {
            Dest::Mem => sys::jpeg_mem_dest(&mut cinfo, &mut outbuf, &mut outsize),
            Dest::File(path) => {
                let c = CString::new(path.to_string_lossy().as_bytes())
                    .map_err(|_| "path contains NUL".to_string())?;
                file = libc::fopen(c.as_ptr(), b"wb\0".as_ptr() as *const libc::c_char);
                if file.is_null() {
                    sys::jpeg_destroy_compress(&mut cinfo);
                    return Err(format!("fopen failed: {}", path.display()));
                }
                sys::jpeg_stdio_dest(&mut cinfo, file);
            }
        }

        cinfo.image_width = w;
        cinfo.image_height = h;
        cinfo.input_components = 3;
        cinfo.in_color_space = sys::J_COLOR_SPACE::JCS_RGB;
        // mozjpeg 4 defaults to JCP_MAX_COMPRESSION (progressive + optimized
        // scans + trellis). JCP_FASTEST = libjpeg-turbo defaults (baseline).
        sys::jpeg_c_set_int_param(
            &mut cinfo,
            sys::J_INT_PARAM::JINT_COMPRESS_PROFILE,
            sys::JCP_FASTEST as c_int,
        );
        sys::jpeg_set_defaults(&mut cinfo);
        // Byte parity with Chromium needs optimized Huffman; D-028 only needs
        // pixel parity, so the worker path defaults to standard tables (no
        // full-image coefficient array).
        cinfo.optimize_coding = optimize as c_int;
        sys::jpeg_set_quality(&mut cinfo, quality as c_int, 1);
        let comps = std::slice::from_raw_parts_mut(cinfo.comp_info, 3);
        for c in comps {
            c.h_samp_factor = 1;
            c.v_samp_factor = 1;
        }

        sys::jpeg_start_compress(&mut cinfo, 1);
        // EXIF and ICC go into the stream before the scanlines: no second pass
        // over the 42 MB file. APP1 first (piexif-style), then APP2/ICC.
        if let Some(tiff) = exif_tiff {
            let mut payload = b"Exif\0\0".to_vec();
            payload.extend_from_slice(tiff);
            sys::jpeg_write_marker(&mut cinfo, 0xE1, payload.as_ptr(), payload.len() as u32);
        }
        if let Some(profile) = icc {
            let mut payload = b"ICC_PROFILE\0".to_vec();
            payload.push(1); // sequence
            payload.push(1); // total
            payload.extend_from_slice(profile);
            sys::jpeg_write_marker(
                &mut cinfo,
                0xE2,
                payload.as_ptr(),
                payload.len() as u32,
            );
        }
        let mut row = vec![0u8; w as usize * 3];
        let mut rows_fed = 0u32;
        while cinfo.next_scanline < cinfo.image_height {
            let y = cinfo.next_scanline;
            fill(y, &mut row);
            rows_fed += 1;
            let rows: [sys::JSAMPROW; 1] = [row.as_ptr()];
            let written = sys::jpeg_write_scanlines(&mut cinfo, rows.as_ptr(), 1);
            if written != 1 {
                sys::jpeg_destroy_compress(&mut cinfo);
                if !file.is_null() {
                    libc::fclose(file);
                }
                if !outbuf.is_null() {
                    libc::free(outbuf as *mut libc::c_void);
                }
                return Err(format!("jpeg_write_scanlines returned {written} at row {y}"));
            }
        }
        sys::jpeg_finish_compress(&mut cinfo);

        let bytes = if outbuf.is_null() {
            Vec::new()
        } else {
            std::slice::from_raw_parts(outbuf, outsize as usize).to_vec()
        };
        sys::jpeg_destroy_compress(&mut cinfo);
        if !outbuf.is_null() {
            libc::free(outbuf as *mut libc::c_void);
        }
        let mut file_bytes = 0u64;
        if !file.is_null() {
            libc::fclose(file);
            if let Dest::File(path) = dest {
                file_bytes = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
            }
        }
        Ok(Encoded { bytes, file_bytes, rows_fed })
    }
}

/// Deterministic worst-case canvas row content, shared by every bin so the
/// 50 MP comparisons are the same image. Must match fm-e5's `synth_rgba`.
pub fn synth_row(y: usize, w: usize, h: usize, seed: &mut u32, row: &mut [u8]) {
    let _ = h;
    for x in 0..w {
        *seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
        let n = ((*seed >> 24) & 0x1F) as i32 - 16;
        let gx = (x * 255 / w) as i32;
        let gy = (y * 255 / h.max(1)) as i32;
        let band = if (y / 512) % 7 == 3 { 40 } else { 0 };
        row[x * 3] = (gx + n).clamp(0, 255) as u8;
        row[x * 3 + 1] = ((gx + gy) / 2 + n + band).clamp(0, 255) as u8;
        row[x * 3 + 2] = (gy + n).clamp(0, 255) as u8;
    }
}

/// Seed reset for row 0 keeps procedural output identical across passes.
pub const SYNTH_SEED: u32 = 0x1234_5678;
