//! Minimal EXIF reader for USER photos (A1). Contract: whatever the file actually
//! carries; anything missing stays empty — never fall back to the card's sample EXIF.
//! Supported containers: JPEG (APP1 "Exif\0\0"), PNG (eXIf chunk), TIFF (II/MM header).

use crate::hand::Exif;

fn u16at(b: &[u8], o: usize, le: bool) -> Option<u16> {
    let s = b.get(o..o + 2)?;
    Some(if le {
        u16::from_le_bytes([s[0], s[1]])
    } else {
        u16::from_be_bytes([s[0], s[1]])
    })
}
fn u32at(b: &[u8], o: usize, le: bool) -> Option<u32> {
    let s = b.get(o..o + 4)?;
    Some(if le {
        u32::from_le_bytes([s[0], s[1], s[2], s[3]])
    } else {
        u32::from_be_bytes([s[0], s[1], s[2], s[3]])
    })
}
fn ascii(b: &[u8], o: usize, n: usize) -> String {
    let end = (o + n).min(b.len());
    if o >= end {
        return String::new();
    }
    let s = String::from_utf8_lossy(&b[o..end])
        .trim_end_matches('\0')
        .trim()
        .to_string();
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
            if b[i] != 0xFF {
                return None;
            }
            let marker = b[i + 1];
            if marker == 0xDA || marker == 0xD9 {
                return None;
            }
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
            if kind == b"eXIf" {
                return Some(i + 8);
            }
            if kind == b"IEND" {
                return None;
            }
            i += 12 + len;
        }
    }
    None
}

struct Tiff<'a> {
    b: &'a [u8],
    base: usize,
    le: bool,
}
/// One IFD entry. `val` is the 4-byte value field read as a u32 in file byte order;
/// `vpos` is that field's absolute index in `b`. TIFF inlines every value whose total
/// length is <= 4 bytes *inside the value field* (so `val` is then the data itself —
/// e.g. a little-endian SHORT pair `35/10` gives `val` = 0x000A0023); only longer
/// values store an offset relative to the TIFF header. Always resolve through
/// `bytes_of()` instead of computing `base + val` by hand.
#[derive(Clone, Copy)]
struct Entry {
    tag: u16,
    ty: u16,
    cnt: u32,
    val: u32,
    vpos: usize,
}

impl<'a> Tiff<'a> {
    fn ifd(&self, off: u32) -> Vec<Entry> {
        let mut out = Vec::new();
        let p = self.base + off as usize;
        let n = match u16at(self.b, p, self.le) {
            Some(v) => v as usize,
            None => return out,
        };
        for k in 0..n {
            let e = p + 2 + k * 12;
            let (Some(tag), Some(ty), Some(cnt), Some(val)) = (
                u16at(self.b, e, self.le),
                u16at(self.b, e + 2, self.le),
                u32at(self.b, e + 4, self.le),
                u32at(self.b, e + 8, self.le),
            ) else {
                break;
            };
            out.push(Entry {
                tag,
                ty,
                cnt,
                val,
                vpos: e + 8,
            });
        }
        out
    }
    fn bytes_of(&self, e: Entry) -> Option<usize> {
        let unit = match e.ty {
            1 | 2 | 6 | 7 => 1usize,
            3 | 8 => 2,
            4 | 9 | 11 => 4,
            5 | 10 | 12 => 8,
            _ => return None,
        };
        let len = unit * e.cnt as usize;
        if len <= 4 {
            // Inline: the bytes live in the value field itself, so the data starts at
            // `vpos`. `self.base + val` would jump to a garbage offset (a SHORT pair
            // reads as ~0x000100C8, i.e. megabytes past the TIFF header) and every
            // numeric tag would come back as None.
            Some(e.vpos)
        } else {
            Some(self.base + e.val as usize)
        }
    }
    fn ascii(&self, e: Entry) -> String {
        if e.ty != 2 {
            return String::new();
        }
        let n = e.cnt as usize;
        let p = if n <= 4 {
            // ASCII of <= 4 bytes is inline too.
            e.vpos
        } else {
            self.base + e.val as usize
        };
        ascii(self.b, p, n)
    }
    fn num(&self, e: Entry) -> Option<f64> {
        let p = match self.bytes_of(e) {
            Some(p) => p,
            None => return None,
        };
        match e.ty {
            // SHORT 对：piexif / 部分手机把 `35/10` 写成两个 SHORT（count=2），
            // 只读第一个会把 f/3.5 读成 35、210mm 读成 210 的一半，ISO 更会读成乱数。
            3 if e.cnt >= 2 => {
                let a = u16at(self.b, p, self.le)? as f64;
                let d = u16at(self.b, p + 2, self.le)? as f64;
                if d > 0.0 {
                    Some(a / d)
                } else {
                    Some(a)
                }
            }
            3 => u16at(self.b, p, self.le).map(|v| v as f64),
            4 => u32at(self.b, p, self.le).map(|v| v as f64),
            5 | 10 => {
                let n = u32at(self.b, p, self.le)? as f64;
                let d = u32at(self.b, p + 4, self.le)? as f64;
                if d == 0.0 {
                    None
                } else {
                    Some(n / d)
                }
            }
            _ => None,
        }
    }
}

/// Read what the file carries. Unknown container / no EXIF -> all-empty `Exif`.
pub fn read_photo(path: &std::path::Path) -> Exif {
    let mut ex = Exif {
        make: String::new(),
        model: String::new(),
        lens: String::new(),
        focal: None,
        focal_equivalent: None,
        aperture: None,
        shutter: None,
        iso: None,
        date_time: String::new(),
        width: 0.0,
        height: 0.0,
        has_any: false,
    };
    let Ok(bytes) = std::fs::read(path) else {
        return ex;
    };
    let Some(base) = tiff_base(&bytes) else {
        return ex;
    };
    if base + 8 > bytes.len() {
        return ex;
    }
    let le = &bytes[base..base + 2] == b"II";
    let t = Tiff {
        b: &bytes,
        base,
        le,
    };
    let Some(first) = u32at(&bytes, base + 4, le) else {
        return ex;
    };
    let mut exif_ifd_off = None;
    let mut dt_original = String::new();
    let mut iso: Option<f64> = None;
    let mut focal: Option<f64> = None;
    let mut f35: Option<f64> = None;
    let mut fnum: Option<f64> = None;
    let mut sho: Option<f64> = None;
    let mut w: Option<f64> = None;
    let mut h: Option<f64> = None;
    for e in t.ifd(first) {
        match e.tag {
            0x010F => ex.make = t.ascii(e),
            0x0110 => ex.model = t.ascii(e),
            0x0132 => ex.date_time = t.ascii(e),
            // Pointer to the Exif sub-IFD. Normally a LONG (inline, so `e.val` already
            // holds the offset), but go through `num()` so a SHORT-typed pointer and
            // an inline/out-of-line value are both handled correctly.
            0x8769 => exif_ifd_off = t.num(e).map(|v| v as u32),
            // 有些相机/导出工具（含部分手机）把光圈、快门、ISO、焦距直接写在 IFD0，
            // 只扫 Exif 子 IFD 会整行丢掉 —— 这些标签在两处都认，子 IFD 里的值后写、优先。
            0x829D => fnum = t.num(e),
            0x829A => sho = t.num(e),
            0x8827 => iso = t.num(e),
            0x920A => focal = t.num(e),
            0xA405 => f35 = t.num(e),
            _ => {}
        }
    }
    if let Some(off) = exif_ifd_off {
        for e in t.ifd(off) {
            match e.tag {
                0x9003 => dt_original = t.ascii(e),
                0xA434 => ex.lens = t.ascii(e),
                0x829D => fnum = t.num(e),
                0x829A => sho = t.num(e),
                0x8827 => iso = t.num(e),
                0x920A => focal = t.num(e),
                0xA405 => f35 = t.num(e),
                0xA002 => w = t.num(e),
                0xA003 => h = t.num(e),
                _ => {}
            }
        }
    }
    if ex.date_time.is_empty() {
        ex.date_time = dt_original;
    }
    // FocalLengthIn35mm is recorded as a flag in this Exif shape; keep the value only
    // when the file really carries it, and never invent a focal length from it.
    ex.focal_equivalent = f35.filter(|v| plausible(v)).map(|_| true);
    // 手机照片的 `FocalLength`(0x920A) 是**原生焦段**（常常只有 35mm 这种小数字），
    // 相机 App 与厂商水印给用户看的一直是 `FocalLengthIn35mmFilm`(0xA405) 的等效值
    // （例如等效 230mm）。有等效值时优先用它，并标 focal_equivalent。
    ex.focal = f35.filter(|v| plausible(v)).or(focal.filter(|v| plausible(v)));
    ex.aperture = fnum.filter(|v| plausible(v));
    ex.shutter = sho.filter(|v| plausible(v));
    ex.iso = iso.filter(|v| plausible(v));
    ex.width = w.unwrap_or(0.0);
    ex.height = h.unwrap_or(0.0);
    ex.has_any = !ex.make.is_empty()
        || !ex.model.is_empty()
        || !ex.date_time.is_empty()
        || ex.focal.is_some()
        || ex.aperture.is_some()
        || ex.shutter.is_some()
        || ex.iso.is_some()
        || !ex.lens.is_empty();
    ex
}

/// 一个「有意义的」拍摄参数：正的有限数。
///
/// 有些文件把 `ExposureTime` 写成 `0/0`、把光圈写成 0 —— 那是写出方的残渣，不是数据。
/// 以前这些值会一路走到排版：没有 APP1 的照片在玻璃卡上印出 `1/inf`（真机实测，
/// 一张没有 EXIF 的荷花照）。在解析处就丢掉，所有模板都不用各自再防一次。
fn plausible(v: &f64) -> bool {
    v.is_finite() && *v > 0.0
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    static SEQ: AtomicUsize = AtomicUsize::new(0);

    /// Little/big-endian TIFF writer for fixtures. Offsets are relative to the TIFF
    /// header, exactly like a real file, so the tests exercise the real offset math.
    struct Buf {
        b: Vec<u8>,
        le: bool,
    }
    impl Buf {
        fn new(le: bool) -> Self {
            Buf { b: Vec::new(), le }
        }
        fn u16(&mut self, v: u16) {
            let w = if self.le { v.to_le_bytes() } else { v.to_be_bytes() };
            self.b.extend_from_slice(&w);
        }
        fn u32(&mut self, v: u32) {
            let w = if self.le { v.to_le_bytes() } else { v.to_be_bytes() };
            self.b.extend_from_slice(&w);
        }
        fn bytes(&mut self, s: &[u8]) {
            self.b.extend_from_slice(s);
        }
        fn pad_to(&mut self, off: usize) {
            assert!(self.b.len() <= off, "layout overflow: {} > {}", self.b.len(), off);
            self.b.resize(off, 0);
        }
        fn ifd(&mut self, entries: &[(u16, u16, u32, [u8; 4])], next: u32) {
            self.u16(entries.len() as u16);
            for (tag, ty, cnt, raw) in entries {
                self.u16(*tag);
                self.u16(*ty);
                self.u32(*cnt);
                self.bytes(raw);
            }
            self.u32(next);
        }
    }

    fn offset_field(le: bool, off: u32) -> [u8; 4] {
        if le {
            off.to_le_bytes()
        } else {
            off.to_be_bytes()
        }
    }
    /// Inline value field: a value of <= 4 bytes is written into the first bytes of the
    /// field in file byte order, the rest stays zero.
    fn inline_shorts(le: bool, vals: &[u16]) -> [u8; 4] {
        let mut raw = [0u8; 4];
        for (i, v) in vals.iter().enumerate() {
            let w = if le { v.to_le_bytes() } else { v.to_be_bytes() };
            raw[i * 2] = w[0];
            raw[i * 2 + 1] = w[1];
        }
        raw
    }
    fn inline_ascii(s: &[u8]) -> [u8; 4] {
        let mut raw = [0u8; 4];
        raw[..s.len()].copy_from_slice(s);
        raw
    }

    fn parse(block: &[u8]) -> Exif {
        let n = SEQ.fetch_add(1, Ordering::SeqCst);
        let path = std::env::temp_dir().join(format!(
            "fm-e4-exif-{}-{n}.bin",
            std::process::id()
        ));
        std::fs::write(&path, block).expect("write exif fixture");
        let ex = read_photo(&path);
        let _ = std::fs::remove_file(&path);
        ex
    }

    /// The reported bug: a phone wrote ExposureTime/FNumber/FocalLength as SHORT *pairs*
    /// and ISO as a single SHORT, all inline in IFD0. `bytes_of()` used `base + val` for
    /// inline values too, so the "offset" became the raw value field (0x000100C8 etc.)
    /// and landed megabytes past the TIFF header -> every numeric tag parsed as None and
    /// the footer only kept the camera name.
    #[test]
    fn inline_short_pairs_in_ifd0_parse() {
        for le in [true, false] {
            let mut b = Buf::new(le);
            b.bytes(if le { b"II" } else { b"MM" });
            b.u16(0x2A);
            b.u32(8);
            b.pad_to(8);
            b.ifd(
                &[
                    (0x010F, 2, 5, offset_field(le, 110)), // "OPPO\0"
                    (0x0110, 2, 3, inline_ascii(b"X8\0")), // inline ASCII
                    (0x829A, 3, 2, inline_shorts(le, &[1, 200])), // 1/200s
                    (0x829D, 3, 2, inline_shorts(le, &[35, 10])), // F3.5
                    (0x8827, 3, 1, inline_shorts(le, &[100])), // ISO100
                    (0x920A, 3, 2, inline_shorts(le, &[67, 10])), // 6.7mm native
                    (0xA405, 3, 1, inline_shorts(le, &[230])), // 230mm equivalent
                ],
                0,
            );
            assert_eq!(b.b.len(), 8 + 2 + 7 * 12 + 4);
            b.pad_to(110);
            b.bytes(b"OPPO\0");

            let ex = parse(&b.b);
            assert_eq!(ex.make, "OPPO", "le={le}");
            assert_eq!(ex.model, "X8", "inline ASCII, le={le}");
            assert_eq!(ex.aperture, Some(3.5), "le={le}");
            assert_eq!(ex.shutter, Some(0.005), "le={le}");
            assert_eq!(ex.iso, Some(100.0), "le={le}");
            // 用户看到的必须是等效焦段：手机的原生 6.7mm 是传感器物理焦距，
            // 相机 App 和厂商水印印的都是 0xA405 的等效值（这里是 230mm）。
            assert_eq!(ex.focal, Some(230.0), "le={le}");
            assert_eq!(ex.focal_equivalent, Some(true), "le={le}");
            assert_eq!(ex.line("  "), "230mm  F3.5  1/200s  ISO100", "le={le}");
            assert!(ex.has_any, "le={le}");
        }
    }

    /// No 35mm-equivalent tag (ordinary cameras, and phones that omit it): the physical
    /// focal length is the only value in the file, so it is what the footer shows.
    #[test]
    fn native_focal_is_used_when_no_35mm_tag() {
        for le in [true, false] {
            let mut b = Buf::new(le);
            b.bytes(if le { b"II" } else { b"MM" });
            b.u16(0x2A);
            b.u32(8);
            b.pad_to(8);
            b.ifd(&[(0x920A, 3, 2, inline_shorts(le, &[366, 10]))], 0);

            let ex = parse(&b.b);
            assert_eq!(ex.focal, Some(36.6), "le={le}");
            assert_eq!(ex.focal_equivalent, None, "le={le}");
            assert_eq!(ex.line("  "), "37mm", "le={le}");
        }
    }

    /// The shape ordinary cameras write: RATIONALs live out-of-line and the numeric tags
    /// sit in the Exif sub-IFD reached through 0x8769, with ISO/pixels inline. Both
    /// encodings must keep working after the inline fix.
    #[test]
    fn rationals_in_exif_sub_ifd_still_parse() {
        for le in [true, false] {
            let mut b = Buf::new(le);
            b.bytes(if le { b"II" } else { b"MM" });
            b.u16(0x2A);
            b.u32(8);
            b.pad_to(8);
            b.ifd(
                &[
                    (0x010F, 2, 10, offset_field(le, 58)),  // "Panasonic\0"
                    (0x0132, 2, 20, offset_field(le, 180)), // DateTime
                    (0x8769, 4, 1, offset_field(le, 68)),   // inline LONG pointer
                ],
                0,
            );
            assert_eq!(b.b.len(), 50);
            b.u32(63); // FNumber = 63/10 at 50
            b.u32(10);
            b.bytes(b"Panasonic\0"); // 58..68
            assert_eq!(b.b.len(), 68);
            b.ifd(
                &[
                    (0x829D, 5, 1, offset_field(le, 50)),
                    (0x8827, 3, 1, inline_shorts(le, &[125])),
                    (0xA002, 4, 1, offset_field(le, 2667)),
                    (0x9003, 2, 20, offset_field(le, 200)),
                ],
                0,
            );
            assert_eq!(b.b.len(), 122);
            b.pad_to(180);
            b.bytes(b"2022:07:05 16:21:59\0");
            assert_eq!(b.b.len(), 200);
            b.bytes(b"2022:07:05 14:13:37\0");

            let ex = parse(&b.b);
            assert_eq!(ex.make, "Panasonic", "le={le}");
            assert_eq!(ex.date_time, "2022:07:05 16:21:59", "IFD0 wins, le={le}");
            assert_eq!(ex.aperture, Some(6.3), "le={le}");
            assert_eq!(ex.iso, Some(125.0), "le={le}");
            assert_eq!(ex.width, 2667.0, "inline LONG, le={le}");
            // Never invent what the file does not carry.
            assert_eq!(ex.focal, None, "le={le}");
            assert_eq!(ex.shutter, None, "le={le}");
            assert_eq!(ex.line("  "), "F6.3  ISO125", "le={le}");
        }
    }

    /// A JPEG without APP1 stays empty instead of falling back to a card's sample EXIF.
    #[test]
    fn jpeg_without_app1_stays_empty() {
        let ex = parse(&[0xFF, 0xD8, 0xFF, 0xD9]);
        assert_eq!(ex.line("  "), "");
        assert_eq!(ex.make, "");
        assert_eq!(ex.focal, None);
        assert!(!ex.has_any);
    }

    /// 写出方的残渣：`ExposureTime = 0/0`、`FNumber = 0/0`。这些不是数据，落地时丢掉，
    /// 否则玻璃卡会把 `1/inf` 印在快门格上（真机实测：一张没有 APP1 的荷花照）。
    #[test]
    fn zero_rationals_are_dropped_instead_of_printing_infinity() {
        for le in [true, false] {
            let mut b = Buf::new(le);
            b.bytes(if le { b"II" } else { b"MM" });
            b.u16(0x2A);
            b.u32(8);
            b.pad_to(8);
            b.ifd(
                &[
                    (0x829A, 3, 2, inline_shorts(le, &[0, 0])), // ExposureTime 0/0
                    (0x829D, 3, 2, inline_shorts(le, &[0, 0])), // FNumber 0/0
                    (0x8827, 3, 1, inline_shorts(le, &[0])),    // ISO 0
                ],
                0,
            );

            let ex = parse(&b.b);
            assert_eq!(ex.shutter, None, "le={le}");
            assert_eq!(ex.aperture, None, "le={le}");
            assert_eq!(ex.iso, None, "le={le}");
            assert_eq!(ex.line("  "), "", "le={le}");
            assert_eq!(crate::hand::format_shutter(0.0), "");
            assert_eq!(crate::hand::format_aperture(0.0), "");
        }
    }
}
