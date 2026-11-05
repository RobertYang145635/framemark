//! TIFF source path: Chromium cannot decode TIFF, so the frozen app transcodes
//! `.tif/.tiff` to PNG in the main process (`sharp(file).rotate().toColourspace
//! ('srgb').png()`). This is the native equivalent: decode, apply EXIF
//! orientation, RGB→RGBA, then PNG-encode.

use std::fs::File;
use std::io::BufReader;
use tiff::decoder::{Decoder, DecodingResult};
use tiff::ColorType;

pub struct TiffImage {
    pub w: u32,
    pub h: u32,
    pub rgba: Vec<u8>,
    pub color: String,
    pub orientation: u16,
}

fn read_u16(d: &[u8], off: usize, le: bool) -> Option<u16> {
    let b = d.get(off..off + 2)?;
    Some(if le {
        u16::from_le_bytes([b[0], b[1]])
    } else {
        u16::from_be_bytes([b[0], b[1]])
    })
}

fn read_u32(d: &[u8], off: usize, le: bool) -> Option<u32> {
    let b = d.get(off..off + 4)?;
    Some(if le {
        u32::from_le_bytes([b[0], b[1], b[2], b[3]])
    } else {
        u32::from_be_bytes([b[0], b[1], b[2], b[3]])
    })
}

fn ifd0_entry_offset(d: &[u8], tag: u16) -> Option<(usize, bool)> {
    let le = match d.get(0..2)? {
        b"II" => true,
        b"MM" => false,
        _ => return None,
    };
    let ifd = read_u32(d, 4, le)? as usize;
    let n = read_u16(d, ifd, le)? as usize;
    for i in 0..n {
        let e = ifd + 2 + i * 12;
        if read_u16(d, e, le)? == tag {
            return Some((e, le));
        }
    }
    None
}

/// IFD0 Orientation (0x0112), defaulting to 1 when absent.
pub fn orientation(d: &[u8]) -> u16 {
    if let Some((e, le)) = ifd0_entry_offset(d, 0x0112) {
        if let Some(v) = read_u16(d, e + 8, le) {
            return v;
        }
    }
    1
}

pub fn compression(d: &[u8]) -> u32 {
    if let Some((e, le)) = ifd0_entry_offset(d, 0x0103) {
        if let Some(v) = read_u16(d, e + 8, le) {
            return v as u32;
        }
    }
    1
}

/// Rewrite the IFD0 Orientation value in place (used to exercise the rotate path
/// with the same fixture instead of hand-crafting a second TIFF).
pub fn patch_orientation(d: &mut [u8], value: u16) -> bool {
    if let Some((e, le)) = ifd0_entry_offset(d, 0x0112) {
        let bytes = if le {
            value.to_le_bytes()
        } else {
            value.to_be_bytes()
        };
        if e + 10 <= d.len() {
            d[e + 8] = bytes[0];
            d[e + 9] = bytes[1];
            return true;
        }
    }
    false
}

pub fn decode(path: &std::path::Path) -> Result<TiffImage, String> {
    let file = File::open(path).map_err(|e| format!("open {}: {e}", path.display()))?;
    let mut dec = Decoder::new(BufReader::new(file)).map_err(|e| format!("tiff decoder: {e}"))?;
    let (w, h) = dec.dimensions().map_err(|e| format!("dimensions: {e}"))?;
    let color = dec.colortype().map_err(|e| format!("colortype: {e}"))?;
    let orientation = dec
        .get_tag_u32(tiff::tags::Tag::Orientation)
        .ok()
        .map(|v| v as u16)
        .unwrap_or(1);
    let img = dec.read_image().map_err(|e| format!("read_image: {e}"))?;
    let color_name = format!("{color:?}");
    let rgba = to_rgba(img, color, (w as usize) * (h as usize))?;
    Ok(TiffImage {
        w,
        h,
        rgba,
        color: color_name,
        orientation,
    })
}

fn to_rgba(img: DecodingResult, color: ColorType, px: usize) -> Result<Vec<u8>, String> {
    let mut out = Vec::with_capacity(px * 4);
    match img {
        DecodingResult::U8(v) => match color {
            ColorType::RGB(8) => {
                if v.len() < px * 3 {
                    return Err("short RGB buffer".into());
                }
                for i in 0..px {
                    out.extend_from_slice(&v[i * 3..i * 3 + 3]);
                    out.push(255);
                }
            }
            ColorType::RGBA(8) => {
                if v.len() < px * 4 {
                    return Err("short RGBA buffer".into());
                }
                out.extend_from_slice(&v[..px * 4]);
            }
            ColorType::Gray(8) => {
                if v.len() < px {
                    return Err("short gray buffer".into());
                }
                for &g in &v[..px] {
                    out.extend_from_slice(&[g, g, g, 255]);
                }
            }
            other => return Err(format!("unsupported 8-bit colour type {other:?}")),
        },
        DecodingResult::U16(v) => {
            // 16-bit samples: scale 0..=65535 to 0..=255 like an 8-bit sRGB source.
            let to8 = |x: u16| ((x as u32 + 128) / 257) as u8;
            match color {
                ColorType::RGB(16) => {
                    if v.len() < px * 3 {
                        return Err("short RGB16 buffer".into());
                    }
                    for i in 0..px {
                        out.extend_from_slice(&[
                            to8(v[i * 3]),
                            to8(v[i * 3 + 1]),
                            to8(v[i * 3 + 2]),
                            255,
                        ]);
                    }
                }
                ColorType::RGBA(16) => {
                    if v.len() < px * 4 {
                        return Err("short RGBA16 buffer".into());
                    }
                    for i in 0..px {
                        out.extend_from_slice(&[
                            to8(v[i * 4]),
                            to8(v[i * 4 + 1]),
                            to8(v[i * 4 + 2]),
                            to8(v[i * 4 + 3]),
                        ]);
                    }
                }
                ColorType::Gray(16) => {
                    for &g in &v[..px] {
                        let g = to8(g);
                        out.extend_from_slice(&[g, g, g, 255]);
                    }
                }
                other => return Err(format!("unsupported 16-bit colour type {other:?}")),
            }
        }
        other => return Err(format!("unsupported sample type {other:?}")),
    }
    Ok(out)
}

/// EXIF orientation 1..=8 → (rotated RGBA, new width, new height).
pub fn apply_orientation(rgba: &[u8], w: u32, h: u32, orientation: u16) -> (Vec<u8>, u32, u32) {
    if orientation <= 1 || orientation > 8 || w == 0 || h == 0 {
        return (rgba.to_vec(), w, h);
    }
    let (wu, hu) = (w as usize, h as usize);
    let (nw, nh) = if orientation >= 5 { (hu, wu) } else { (wu, hu) };
    let mut out = vec![0u8; rgba.len()];
    for y in 0..hu {
        for x in 0..wu {
            let (dx, dy) = match orientation {
                2 => (wu - 1 - x, y),
                3 => (wu - 1 - x, hu - 1 - y),
                4 => (x, hu - 1 - y),
                5 => (y, x),
                6 => (hu - 1 - y, x),
                7 => (hu - 1 - y, wu - 1 - x),
                8 => (y, wu - 1 - x),
                _ => (x, y),
            };
            let si = (y * wu + x) * 4;
            let di = (dy * nw + dx) * 4;
            out[di..di + 4].copy_from_slice(&rgba[si..si + 4]);
        }
    }
    (out, nw as u32, nh as u32)
}
