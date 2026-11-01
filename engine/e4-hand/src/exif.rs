//! Minimal EXIF reader for USER photos (A1). Contract: whatever the file actually
//! carries; anything missing stays empty — never fall back to the card's sample EXIF.
//! Supported containers: JPEG (APP1 "Exif\0\0"), PNG (eXIf chunk), TIFF (II/MM header).

use crate::hand::Exif;

fn u16at(b: &[u8], o: usize, le: bool) -> Option<u16> {
    let s = b.get(o..o + 2)?;
    Some(if le { u16::from_le_bytes([s[0], s[1]]) } else { u16::from_be_bytes([s[0], s[1]]) })
}
fn u32at(b: &[u8], o: usize, le: bool) -> Option<u32> {
    let s = b.get(o..o + 4)?;
    Some(if le { u32::from_le_bytes([s[0], s[1], s[2], s[3]]) } else { u32::from_be_bytes([s[0], s[1], s[2], s[3]]) })
}
fn ascii(b: &[u8], o: usize, n: usize) -> String {
    let end = (o + n).min(b.len());
    if o >= end { return String::new(); }
    let s = String::from_utf8_lossy(&b[o..end]).trim_end_matches('\0').trim().to_string();
    s
}

/// Locate the TIFF block inside JPEG/PNG/TIFF and return its offset within `b`.
fn tiff_base(b: &[u8]) -> Option<usize> {
    if b.starts_with(&[0x49, 0x49, 0x2A, 0x00]) || b.starts_with(&[0x4D, 0x4D, 0x00, 0x2A]) {
        return Some(0);
    }
    // JPEG: scan APP segments for Exif
    if b.starts_with(&[0xFF, 0xD8]) {
        let mut i = 2usize;
        while i + 4 <= b.len() {
            if b[i] != 0xFF { return None; }
            let marker = b[i + 1];
            if marker == 0xDA || marker == 0xD9 { return None; }
            let len = u16at(b, i + 2, false)? as usize;
            if marker == 0xE1 && b.get(i + 4..i + 10) == Some(&b"Exif\0\0"[..]) {
                return Some(i + 10);
            }
            i += 2 + len;
        }
        return None;
    }
    // PNG: eXIf chunk holds a bare TIFF block
    if b.starts_with(&[0x89, b'P', b'N', b'G']) {
        let mut i = 8usize;
        while i + 12 <= b.len() {
            let len = u32at(b, i, false)? as usize;
            let kind = b.get(i + 4..i + 8)?;
            if kind == b"eXIf" { return Some(i + 8); }
            if kind == b"IEND" { return None; }
            i += 12 + len;
        }
    }
    None
}

struct Tiff<'a> { b: &'a [u8], base: usize, le: bool }
impl<'a> Tiff<'a> {
    fn ifd(&self, off: u32) -> Vec<(u16, u16, u32, u32)> {
        // (tag, type, count, value_offset)
        let mut out = Vec::new();
        let p = self.base + off as usize;
        let n = match u16at(self.b, p, self.le) { Some(v) => v as usize, None => return out };
        for k in 0..n {
            let e = p + 2 + k * 12;
            let (Some(tag), Some(ty), Some(cnt), Some(val)) = (
                u16at(self.b, e, self.le), u16at(self.b, e + 2, self.le),
                u32at(self.b, e + 4, self.le), u32at(self.b, e + 8, self.le),
            ) else { break };
            out.push((tag, ty, cnt, val));
        }
        out
    }
    fn bytes_of(&self, ty: u16, cnt: u32, val: u32) -> Option<usize> {
        let unit = match ty { 1 | 2 | 6 | 7 => 1usize, 3 | 8 => 2, 4 | 9 | 11 => 4, 5 | 10 | 12 => 8, _ => return None };
        let len = unit * cnt as usize;
        if len <= 4 { Some(self.base + val as usize) } else { Some(self.base + val as usize) }
    }
    fn ascii(&self, ty: u16, cnt: u32, val: u32) -> String {
        if ty != 2 { return String::new(); }
        let p = self.base + val as usize;
        ascii(self.b, p, cnt as usize)
    }
    fn num(&self, ty: u16, cnt: u32, val: u32) -> Option<f64> {
        let p = match self.bytes_of(ty, cnt, val) { Some(p) => p, None => return None };
        match ty {
            3 => u16at(self.b, p, self.le).map(|v| v as f64),
            4 => u32at(self.b, p, self.le).map(|v| v as f64),
            5 | 10 => {
                let n = u32at(self.b, p, self.le)? as f64;
                let d = u32at(self.b, p + 4, self.le)? as f64;
                if d == 0.0 { None } else { Some(n / d) }
            }
            _ => None,
        }
    }
}

/// Read what the file carries. Unknown container / no EXIF -> all-empty `Exif`.
pub fn read_photo(path: &std::path::Path) -> Exif {
    let mut ex = Exif { make: String::new(), model: String::new(), lens: String::new(), focal: None, focal_equivalent: None, aperture: None, shutter: None, iso: None, date_time: String::new(), width: 0.0, height: 0.0, has_any: false };
    let Ok(bytes) = std::fs::read(path) else { return ex };
    let Some(base) = tiff_base(&bytes) else { return ex };
    if base + 8 > bytes.len() { return ex; }
    let le = &bytes[base..base + 2] == b"II";
    let t = Tiff { b: &bytes, base, le };
    let Some(first) = u32at(&bytes, base + 4, le) else { return ex };
    let mut exif_ifd_off = None;
    let mut dt_original = String::new();
    let mut iso: Option<f64> = None;
    let mut focal: Option<f64> = None;
    let mut f35: Option<f64> = None;
    let mut fnum: Option<f64> = None;
    let mut sho: Option<f64> = None;
    let mut w: Option<f64> = None;
    let mut h: Option<f64> = None;
    for (tag, ty, cnt, val) in t.ifd(first) {
        match tag {
            0x010F => ex.make = t.ascii(ty, cnt, val),
            0x0110 => ex.model = t.ascii(ty, cnt, val),
            0x0132 => ex.date_time = t.ascii(ty, cnt, val),
            0x8769 => exif_ifd_off = Some(val),
            _ => {}
        }
    }
    if let Some(off) = exif_ifd_off {
        for (tag, ty, cnt, val) in t.ifd(off) {
            match tag {
                0x9003 => dt_original = t.ascii(ty, cnt, val),
                0xA434 => ex.lens = t.ascii(ty, cnt, val),
                0x829D => fnum = t.num(ty, cnt, val),
                0x829A => sho = t.num(ty, cnt, val),
                0x8827 => iso = t.num(ty, cnt, val),
                0x920A => focal = t.num(ty, cnt, val),
                0xA405 => f35 = t.num(ty, cnt, val),
                0xA002 => w = t.num(ty, cnt, val),
                0xA003 => h = t.num(ty, cnt, val),
                _ => {}
            }
        }
    }
    if ex.date_time.is_empty() { ex.date_time = dt_original; }
    // FocalLengthIn35mm is recorded as a flag in this Exif shape; keep the value only
    // when the file really carries it, and never invent a focal length from it.
    ex.focal_equivalent = f35.map(|_| true);
    // A1: never invent a focal length. 0xA405 is a 35mm-equivalent tag; using it
    // as `focal` would print a value the photo does not carry.
    ex.focal = focal;
    ex.aperture = fnum;
    ex.shutter = sho;
    ex.iso = iso;
    ex.width = w.unwrap_or(0.0);
    ex.height = h.unwrap_or(0.0);
    ex.has_any = !ex.make.is_empty() || !ex.model.is_empty() || !ex.date_time.is_empty()
        || ex.focal.is_some() || ex.aperture.is_some() || ex.shutter.is_some() || ex.iso.is_some() || !ex.lens.is_empty();
    ex
}
