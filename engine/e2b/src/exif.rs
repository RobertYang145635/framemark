//! E2b — minimal EXIF reader for the runtime tokens the IR cannot carry.
//!
//! `text.token.kind=model|imageParams|time|timeLocation` store no string in the
//! IR (E2a gap 2). The frozen app derives them from the source photo's EXIF;
//! this module reads the JPEG APP1/TIFF IFD0+ExifIFD tags the token formatters
//! consume, so E2b resolves them from the real fixture photo instead of a CLI
//! constant.

#![allow(dead_code)]

#[derive(Clone, Debug, Default)]
pub struct Exif {
    pub make: String,
    pub model: String,
    pub date_time: String,
    pub focal: Option<f32>,
    pub aperture: Option<f32>,
    pub shutter: Option<f32>,
    pub iso: Option<u32>,
    pub focal_35mm: Option<f32>,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Endian {
    Little,
    Big,
}

struct Tiff<'a> {
    d: &'a [u8],
    e: Endian,
    base: usize,
}

impl<'a> Tiff<'a> {
    fn u16(&self, off: usize) -> Option<u16> {
        let b = self.d.get(self.base + off..self.base + off + 2)?;
        Some(match self.e {
            Endian::Little => u16::from_le_bytes([b[0], b[1]]),
            Endian::Big => u16::from_be_bytes([b[0], b[1]]),
        })
    }
    fn u32(&self, off: usize) -> Option<u32> {
        let b = self.d.get(self.base + off..self.base + off + 4)?;
        Some(match self.e {
            Endian::Little => u32::from_le_bytes([b[0], b[1], b[2], b[3]]),
            Endian::Big => u32::from_be_bytes([b[0], b[1], b[2], b[3]]),
        })
    }
    fn i32(&self, off: usize) -> Option<i32> {
        self.u32(off).map(|v| v as i32)
    }
    fn ascii(&self, off: usize, count: usize) -> Option<String> {
        let b = self.d.get(self.base + off..self.base + off + count)?;
        let end = b.iter().position(|&c| c == 0).unwrap_or(b.len());
        Some(String::from_utf8_lossy(&b[..end]).trim().to_string())
    }
    fn rational(&self, off: usize) -> Option<f32> {
        let n = self.u32(off)? as f32;
        let d = self.u32(off + 4)? as f32;
        if d == 0.0 {
            None
        } else {
            Some(n / d)
        }
    }
}

fn read_entry(t: &Tiff, ifd: usize, idx: usize) -> Option<(u16, u16, u32, usize)> {
    let off = ifd + 2 + idx * 12;
    let tag = t.u16(off)?;
    let typ = t.u16(off + 2)?;
    let count = t.u32(off + 4)?;
    // Value/offset field. For our tags, ASCII/rational values are always
    // out-of-line; SHORT fits inline in the first two bytes.
    let val_off = off + 8;
    Some((tag, typ, count, val_off))
}

fn ifd_entries(t: &Tiff, ifd: usize) -> Option<usize> {
    t.u16(ifd).map(|v| v as usize)
}

fn get_ascii(t: &Tiff, val_off: usize, typ: u16, count: u32) -> Option<String> {
    if typ != 2 {
        return None;
    }
    let n = count as usize;
    let off = if n <= 4 {
        val_off
    } else {
        t.u32(val_off)? as usize
    };
    t.ascii(off, n)
}

/// 光圈 / 快门 / 焦距这类数值在真实文件里有三种写法：
/// - RATIONAL(5) / SRATIONAL(10)：8 字节，值在偏移处；
/// - **SHORT 对(3, count≥2)**：piexif 与部分手机导出把 `35/10` 写成两个 SHORT，
///   直接按整数读会把 f/3.5 读成 35、把 210mm 读成 210 的那一半（用户看到的
///   「210mm 变成 35mm」「ISO 变成 12896」都属于这一类误读）。
fn get_rational(t: &Tiff, val_off: usize, typ: u16, count: u32) -> Option<f32> {
    match typ {
        5 | 10 => {
            let off = t.u32(val_off)? as usize;
            t.rational(off)
        }
        3 if count >= 2 => {
            let a = t.u16(val_off)? as f32;
            let b = t.u16(val_off + 2)? as f32;
            if b > 0.0 {
                Some(a / b)
            } else {
                Some(a)
            }
        }
        3 => t.u16(val_off).map(|v| v as f32),
        _ => None,
    }
}

fn get_u16(t: &Tiff, val_off: usize, typ: u16) -> Option<u32> {
    if typ == 3 {
        t.u16(val_off).map(|v| v as u32)
    } else if typ == 4 {
        t.u32(val_off)
    } else {
        None
    }
}

fn parse_ifd(t: &Tiff, ifd: usize, exif: &mut Exif, in_exif_ifd: bool) -> Option<usize> {
    let n = ifd_entries(t, ifd)?;
    let mut exif_ptr = None;
    for i in 0..n {
        let (tag, typ, count, val_off) = read_entry(t, ifd, i)?;
        match tag {
            0x010F => exif.make = get_ascii(t, val_off, typ, count).unwrap_or_default(),
            0x0110 => exif.model = get_ascii(t, val_off, typ, count).unwrap_or_default(),
            0x0132 if !in_exif_ifd => {
                if exif.date_time.is_empty() {
                    exif.date_time = get_ascii(t, val_off, typ, count).unwrap_or_default();
                }
            }
            0x8769 => exif_ptr = get_u16(t, val_off, typ).or_else(|| get_u16(t, val_off, 4)),
            0x829A => exif.shutter = get_rational(t, val_off, typ, count),
            0x829D => exif.aperture = get_rational(t, val_off, typ, count),
            0x8827 => exif.iso = get_u16(t, val_off, typ),
            0x9003 => {
                let s = get_ascii(t, val_off, typ, count).unwrap_or_default();
                if !s.is_empty() {
                    exif.date_time = s;
                }
            }
            0x920A => exif.focal = get_rational(t, val_off, typ, count),
            0xA405 if in_exif_ifd => {
                // piexif writes the 35 mm equivalent as a SHORT pair [mm*100, 100]
                // (core/exif.ts num() divides it back); reading only the first
                // SHORT turns 35 mm into 3500 mm.
                exif.focal_35mm = if typ == 3 && count >= 2 {
                    let a = t.u16(val_off).unwrap_or(0) as f32;
                    let b = t.u16(val_off + 2).unwrap_or(0) as f32;
                    if b > 0.0 {
                        Some(a / b)
                    } else {
                        Some(a)
                    }
                } else {
                    get_u16(t, val_off, typ).map(|v| v as f32)
                };
            }
            _ => {}
        }
    }
    exif_ptr.map(|v| v as usize)
}

pub fn read(path: &std::path::Path) -> Result<Exif, String> {
    let d = std::fs::read(path).map_err(|e| format!("exif read {}: {e}", path.display()))?;
    if d.len() < 4 || d[0] != 0xFF || d[1] != 0xD8 {
        return Err(format!("not a JPEG: {}", path.display()));
    }
    let mut i = 2usize;
    let mut tiff_off = None;
    while i + 4 <= d.len() {
        if d[i] != 0xFF {
            i += 1;
            continue;
        }
        let marker = d[i + 1];
        if marker == 0xD8 || marker == 0x01 || (0xD0..=0xD7).contains(&marker) {
            i += 2;
            continue;
        }
        if marker == 0xD9 || marker == 0xDA {
            break;
        }
        let len = u16::from_be_bytes([d[i + 2], d[i + 3]]) as usize;
        if len < 2 || i + 2 + len > d.len() {
            break;
        }
        if marker == 0xE1 {
            let payload = &d[i + 4..i + 2 + len];
            if payload.len() >= 6 && &payload[..6] == b"Exif\0\0" {
                tiff_off = Some(i + 4 + 6);
                break;
            }
        }
        i += 2 + len;
    }
    let base = tiff_off.ok_or_else(|| format!("no Exif APP1 in {}", path.display()))?;
    if base + 8 > d.len() {
        return Err("truncated TIFF header".into());
    }
    let e = match &d[base..base + 2] {
        b"II" => Endian::Little,
        b"MM" => Endian::Big,
        _ => return Err("bad TIFF byte order".into()),
    };
    let t = Tiff { d: &d, e, base };
    if t.u16(2) != Some(42) {
        return Err("bad TIFF magic".into());
    }
    let ifd0 = t.u32(4).ok_or("bad IFD0 offset")? as usize;
    let mut exif = Exif::default();
    // P3e: the ExifIFD is optional. A photo may carry only IFD0 (Make/Model/
    // DateTime) or only some fields; whatever is absent stays empty instead of
    // failing the whole read and discarding tags that ARE present.
    if let Some(exif_ifd) = parse_ifd(&t, ifd0, &mut exif, false) {
        parse_ifd(&t, exif_ifd, &mut exif, true);
    }
    Ok(exif)
}

// ---------------------------------------------------------------------------
// Token formatting — port of core/system/tokens.ts + core/exif.ts
// ---------------------------------------------------------------------------

pub fn model_without_make(make: &str, model: &str) -> String {
    let m = make.trim();
    let d = model.trim();
    if m.is_empty() || d.is_empty() {
        return d.to_string();
    }
    if d.to_lowercase()
        .starts_with(&format!("{} ", m.to_lowercase()))
    {
        d[m.len()..].trim().to_string()
    } else {
        d.to_string()
    }
}

impl Exif {
    pub fn model_line(&self) -> String {
        let make = self.make.trim();
        let model = model_without_make(make, &self.model);
        [make, model.as_str()]
            .iter()
            .filter(|s| !s.is_empty())
            .cloned()
            .collect::<Vec<_>>()
            .join(" ")
    }

    fn date_only(&self) -> String {
        // DateTimeOriginal is `YYYY:MM:DD HH:MM:SS`; the frozen formatter
        // returns YYYY.MM.DD (compact, dashes replaced by dots).
        let head = self.date_time.get(0..10).unwrap_or(&self.date_time);
        head.replace(':', ".").replace('-', ".")
    }

    pub fn time_line(&self) -> String {
        self.date_only()
    }

    pub fn image_params(&self) -> String {
        let mut parts: Vec<String> = Vec::new();
        // 等效值写着 0 时回退物理焦距（与 `ir.rs` 的 `@{focal}` 同一条规则）。
        let focal = self
            .focal_35mm
            .filter(|v| v.is_finite() && *v > 0.0)
            .or(self.focal.filter(|v| v.is_finite() && *v > 0.0));
        if let Some(f) = focal {
            if f > 0.0 {
                parts.push(format!("{}mm", f.round() as i64));
            }
        }
        if let Some(a) = self.aperture {
            if a > 0.0 {
                let s = if (a.fract()).abs() < 1e-4 {
                    format!("f/{}", a as i64)
                } else {
                    format!("f/{a:.1}")
                };
                parts.push(s);
            }
        }
        if let Some(s) = self.shutter {
            if s > 0.0 {
                if s >= 1.0 {
                    let v = if (s.fract()).abs() < 1e-4 {
                        format!("{}", s as i64)
                    } else {
                        format!("{s:.1}")
                    };
                    parts.push(format!("{v}s"));
                } else {
                    parts.push(format!("1/{}s", (1.0 / s).round() as i64));
                }
            }
        }
        if let Some(iso) = self.iso {
            if iso > 0 {
                parts.push(format!("ISO{iso}"));
            }
        }
        parts.join("  ")
    }
}
