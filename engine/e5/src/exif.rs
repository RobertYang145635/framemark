//! EXIF: the read side ported from E2b (`engine/e2b/src/exif.rs`) for the token
//! formatters, plus the write side E5 needs — a TIFF IFD copy/override/dump that
//! mirrors `injectJpegExif` in `src/main/index.ts` (piexif load → mutate → dump →
//! insert): copy the source block, force Orientation/ImageWidth/Length and
//! PixelX/YDimension, optionally drop GPS, keep every other tag byte-for-byte.

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
    pub lens: String,
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

pub fn type_size(typ: u16) -> usize {
    match typ {
        1 | 2 | 6 | 7 => 1,
        3 | 8 => 2,
        4 | 9 | 11 | 13 => 4,
        5 | 10 | 12 => 8,
        _ => 0,
    }
}

#[derive(Clone)]
pub struct Entry {
    pub tag: u16,
    pub typ: u16,
    pub count: u32,
    pub data: Vec<u8>,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Ifd {
    Ifd0,
    Exif,
    Gps,
}

#[derive(Clone, Default)]
pub struct Thumbnail {
    /// IFD1 tags as stored, except 0x0201/0x0202 which are recomputed on dump.
    pub ifd1: Vec<Entry>,
    /// The JPEG thumbnail bytes, copied verbatim from the source.
    pub data: Vec<u8>,
}

#[derive(Clone, Default)]
pub struct Block {
    pub ifd0: Vec<Entry>,
    pub exif: Vec<Entry>,
    pub gps: Vec<Entry>,
    /// IFD1 + embedded JPEG preview. The frozen `injectJpegExif` (piexif) carries
    /// this through; dropping it silently shrinks APP1 from ~16 KB to <1 KB.
    pub thumbnail: Option<Thumbnail>,
}

fn find_tiff_base(jpeg: &[u8]) -> Option<usize> {
    if jpeg.len() < 4 || jpeg[0] != 0xFF || jpeg[1] != 0xD8 {
        return None;
    }
    let mut i = 2usize;
    while i + 4 <= jpeg.len() {
        if jpeg[i] != 0xFF {
            i += 1;
            continue;
        }
        let marker = jpeg[i + 1];
        if marker == 0xD8 || marker == 0x01 || (0xD0..=0xD7).contains(&marker) {
            i += 2;
            continue;
        }
        if marker == 0xD9 || marker == 0xDA {
            break;
        }
        let len = u16::from_be_bytes([jpeg[i + 2], jpeg[i + 3]]) as usize;
        if len < 2 || i + 2 + len > jpeg.len() {
            break;
        }
        if marker == 0xE1 {
            let payload = &jpeg[i + 4..i + 2 + len];
            if payload.len() >= 6 && &payload[..6] == b"Exif\0\0" {
                return Some(i + 4 + 6);
            }
        }
        i += 2 + len;
    }
    None
}

fn read_ifd(t: &Tiff, off: usize) -> Option<Vec<Entry>> {
    read_ifd_full(t, off).map(|(entries, _)| entries)
}

/// Read one IFD and its "next IFD" offset.
fn read_ifd_full(t: &Tiff, off: usize) -> Option<(Vec<Entry>, u32)> {
    let n = t.u16(off)? as usize;
    if n > 4096 {
        return None;
    }
    let mut out = Vec::with_capacity(n);
    for i in 0..n {
        let e = off + 2 + i * 12;
        let tag = t.u16(e)?;
        let typ = t.u16(e + 2)?;
        let count = t.u32(e + 4)?;
        let sz = type_size(typ);
        if sz == 0 {
            continue;
        }
        let total = sz.checked_mul(count as usize)?;
        if total > 1 << 20 {
            continue;
        }
        let mut data = if total <= 4 {
            t.d.get(t.base + e + 8..t.base + e + 8 + total)?.to_vec()
        } else {
            let voff = t.u32(e + 8)? as usize;
            t.d.get(t.base + voff..t.base + voff + total)?.to_vec()
        };
        // Normalize multi-byte numeric values to little-endian so the rest of the
        // bench (and `dump`) has one byte order regardless of the source's II/MM.
        if t.e == Endian::Big {
            let unit = match typ {
                3 | 8 => 2,
                4 | 9 | 11 | 13 => 4,
                5 | 10 | 12 => 4,
                _ => 0,
            };
            if unit > 0 {
                for chunk in data.chunks_exact_mut(unit) {
                    chunk.reverse();
                }
            }
        }
        out.push(Entry { tag, typ, count, data });
    }
    let next = t.u32(off + 2 + n * 12).unwrap_or(0);
    Some((out, next))
}

/// Load IFD0 + ExifIFD + GPS IFD (+ IFD1 thumbnail) from a JPEG's APP1.
/// Interop and vendor sub-IFDs are not carried (see E5-IMPL.md).
pub fn load(jpeg: &[u8]) -> Result<Block, String> {
    let base = find_tiff_base(jpeg).ok_or("no Exif APP1")?;
    if base + 8 > jpeg.len() {
        return Err("truncated TIFF header".into());
    }
    let e = match &jpeg[base..base + 2] {
        b"II" => Endian::Little,
        b"MM" => Endian::Big,
        _ => return Err("bad TIFF byte order".into()),
    };
    let t = Tiff { d: jpeg, e, base };
    if t.u16(2) != Some(42) {
        return Err("bad TIFF magic".into());
    }
    let ifd0_off = t.u32(4).ok_or("bad IFD0 offset")? as usize;
    let (ifd0_raw, next_ifd) = read_ifd_full(&t, ifd0_off).ok_or("bad IFD0")?;
    let mut exif_ptr = None;
    let mut gps_ptr = None;
    let mut ifd0 = Vec::new();
    for entry in ifd0_raw {
        match entry.tag {
            0x8769 => exif_ptr = Some(le_u32(&entry.data) as usize),
            0x8825 => gps_ptr = Some(le_u32(&entry.data) as usize),
            0xA005 => {} // Interop pointer: not carried
            _ => ifd0.push(entry),
        }
    }
    let exif = exif_ptr.and_then(|o| read_ifd(&t, o)).unwrap_or_default();
    let gps = gps_ptr.and_then(|o| read_ifd(&t, o)).unwrap_or_default();
    let thumbnail = if next_ifd != 0 {
        read_thumbnail(&t, next_ifd as usize)
    } else {
        None
    };
    Ok(Block { ifd0, exif, gps, thumbnail })
}

fn read_thumbnail(t: &Tiff, off: usize) -> Option<Thumbnail> {
    let ifd1 = read_ifd(t, off)?;
    let data_off = ifd1.iter().find(|e| e.tag == 0x0201).map(|e| le_u32(&e.data) as usize)?;
    let len = ifd1.iter().find(|e| e.tag == 0x0202).map(|e| le_u32(&e.data) as usize)?;
    if len == 0 || data_off + len > t.d.len().saturating_sub(t.base) {
        return None;
    }
    let data = t.d.get(t.base + data_off..t.base + data_off + len)?.to_vec();
    Some(Thumbnail { ifd1, data })
}

fn le_u32(data: &[u8]) -> u32 {
    let mut b = [0u8; 4];
    for (i, v) in data.iter().take(4).enumerate() {
        b[i] = *v;
    }
    u32::from_le_bytes(b)
}

impl Block {
    fn list_mut(&mut self, ifd: Ifd) -> &mut Vec<Entry> {
        match ifd {
            Ifd::Ifd0 => &mut self.ifd0,
            Ifd::Exif => &mut self.exif,
            Ifd::Gps => &mut self.gps,
        }
    }
    fn list(&self, ifd: Ifd) -> &Vec<Entry> {
        match ifd {
            Ifd::Ifd0 => &self.ifd0,
            Ifd::Exif => &self.exif,
            Ifd::Gps => &self.gps,
        }
    }
    pub fn get(&self, ifd: Ifd, tag: u16) -> Option<&Entry> {
        self.list(ifd).iter().find(|e| e.tag == tag)
    }
    fn put(&mut self, ifd: Ifd, entry: Entry) {
        let list = self.list_mut(ifd);
        if let Some(slot) = list.iter_mut().find(|e| e.tag == entry.tag) {
            *slot = entry;
        } else {
            list.push(entry);
        }
    }
    pub fn remove(&mut self, ifd: Ifd, tag: u16) {
        self.list_mut(ifd).retain(|e| e.tag != tag);
    }
    pub fn set_ascii(&mut self, ifd: Ifd, tag: u16, s: &str) {
        let mut data = s.as_bytes().to_vec();
        data.push(0);
        self.put(
            ifd,
            Entry { tag, typ: 2, count: data.len() as u32, data },
        );
    }
    pub fn set_u32(&mut self, ifd: Ifd, tag: u16, v: u32) {
        self.put(
            ifd,
            Entry { tag, typ: 4, count: 1, data: v.to_le_bytes().to_vec() },
        );
    }
    pub fn set_u16(&mut self, ifd: Ifd, tag: u16, v: u16) {
        self.put(
            ifd,
            Entry { tag, typ: 3, count: 1, data: v.to_le_bytes().to_vec() },
        );
    }
    /// piexif writes `FocalLengthIn35mmFilm = [mm*100, 100]` as two SHORTs (the
    /// tag is typed SHORT); the frozen `normalizeExif` then reads that pair back
    /// as a ratio. Reproduce both halves of that quirk.
    pub fn set_short_pair(&mut self, ifd: Ifd, tag: u16, a: u16, b: u16) {
        let mut data = a.to_le_bytes().to_vec();
        data.extend_from_slice(&b.to_le_bytes());
        self.put(ifd, Entry { tag, typ: 3, count: 2, data });
    }
    /// Raw entry insert (GPS fixtures, unknown tags).
    pub fn put_raw(&mut self, tag: u16, typ: u16, count: u32, data: Vec<u8>) {
        self.put(Ifd::Gps, Entry { tag, typ, count, data });
    }
    pub fn set_rational(&mut self, ifd: Ifd, tag: u16, num: u32, den: u32) {
        let mut data = num.to_le_bytes().to_vec();
        data.extend_from_slice(&den.to_le_bytes());
        self.put(ifd, Entry { tag, typ: 5, count: 1, data });
    }
    pub fn get_ascii(&self, ifd: Ifd, tag: u16) -> Option<String> {
        let e = self.get(ifd, tag)?;
        if e.typ != 2 {
            return None;
        }
        let end = e.data.iter().position(|&c| c == 0).unwrap_or(e.data.len());
        Some(String::from_utf8_lossy(&e.data[..end]).trim().to_string())
    }
}

/// The export-path mutation, same order as `injectJpegExif`.
pub fn prepare_export(block: &mut Block, w: u32, h: u32, remove_gps: bool) {
    block.set_u16(Ifd::Ifd0, 0x0112, 1);
    block.set_u32(Ifd::Ifd0, 0x0100, w);
    block.set_u32(Ifd::Ifd0, 0x0101, h);
    block.set_u32(Ifd::Exif, 0xA002, w);
    block.set_u32(Ifd::Exif, 0xA003, h);
    if remove_gps {
        block.gps.clear();
    }
}

fn sorted_dedup(mut list: Vec<Entry>) -> Vec<Entry> {
    list.sort_by_key(|e| e.tag);
    list.dedup_by_key(|e| e.tag);
    list
}

/// Serialize as a fresh little-endian TIFF block (II/42):
/// IFD0 → ExifIFD → GPS IFD → IFD1 (thumbnail) → value area → thumbnail bytes.
pub fn dump(block: &Block) -> Vec<u8> {
    let mut ifd0 = sorted_dedup(block.ifd0.clone());
    let mut exif = sorted_dedup(block.exif.clone());
    let mut gps = sorted_dedup(block.gps.clone());
    let has_exif = !exif.is_empty();
    let has_gps = !gps.is_empty();
    let thumb = block.thumbnail.as_ref().filter(|t| !t.data.is_empty());
    let mut ifd1: Vec<Entry> = match thumb {
        Some(t) => {
            let mut v = sorted_dedup(
                t.ifd1
                    .iter()
                    .filter(|e| e.tag != 0x0201 && e.tag != 0x0202)
                    .cloned()
                    .collect(),
            );
            v.push(Entry { tag: 0x0201, typ: 4, count: 1, data: vec![0; 4] });
            v.push(Entry {
                tag: 0x0202,
                typ: 4,
                count: 1,
                data: (t.data.len() as u32).to_le_bytes().to_vec(),
            });
            sorted_dedup(v)
        }
        None => Vec::new(),
    };
    let has_ifd1 = !ifd1.is_empty();

    if has_exif {
        ifd0.push(Entry { tag: 0x8769, typ: 4, count: 1, data: vec![0; 4] });
    }
    if has_gps {
        ifd0.push(Entry { tag: 0x8825, typ: 4, count: 1, data: vec![0; 4] });
    }
    ifd0 = sorted_dedup(ifd0);

    let ifd_bytes = |n: usize| 2 + 12 * n + 4;
    let off_ifd0 = 8u32;
    let off_exif = off_ifd0 + ifd_bytes(ifd0.len()) as u32;
    let off_gps = off_exif + if has_exif { ifd_bytes(exif.len()) as u32 } else { 0 };
    let off_ifd1 = off_gps + if has_gps { ifd_bytes(gps.len()) as u32 } else { 0 };
    let mut data_off = off_ifd1 + if has_ifd1 { ifd_bytes(ifd1.len()) as u32 } else { 0 };

    for e in ifd0.iter_mut() {
        if e.tag == 0x8769 {
            e.data = off_exif.to_le_bytes().to_vec();
        } else if e.tag == 0x8825 {
            e.data = off_gps.to_le_bytes().to_vec();
        }
    }

    let mut data_area: Vec<u8> = Vec::new();
    let place = |list: &mut Vec<Entry>, data_off: &mut u32, data_area: &mut Vec<u8>| {
        for e in list.iter_mut() {
            if e.data.len() > 4 {
                if *data_off % 2 == 1 {
                    data_area.push(0);
                    *data_off += 1;
                }
                let payload = std::mem::take(&mut e.data);
                e.data = data_off.to_le_bytes().to_vec();
                data_area.extend_from_slice(&payload);
                *data_off += payload.len() as u32;
            }
        }
    };
    place(&mut ifd0, &mut data_off, &mut data_area);
    place(&mut exif, &mut data_off, &mut data_area);
    place(&mut gps, &mut data_off, &mut data_area);
    place(&mut ifd1, &mut data_off, &mut data_area);

    let ifd_total = 8
        + ifd_bytes(ifd0.len())
        + if has_exif { ifd_bytes(exif.len()) } else { 0 }
        + if has_gps { ifd_bytes(gps.len()) } else { 0 }
        + if has_ifd1 { ifd_bytes(ifd1.len()) } else { 0 };
    debug_assert_eq!(ifd_total as u32 + data_area.len() as u32, data_off);

    if let Some(t) = thumb {
        if data_off % 2 == 1 {
            data_area.push(0);
            data_off += 1;
        }
        let thumb_off = data_off;
        data_area.extend_from_slice(&t.data);
        for e in ifd1.iter_mut() {
            if e.tag == 0x0201 {
                e.data = thumb_off.to_le_bytes().to_vec();
            }
        }
    }

    let mut out = Vec::with_capacity(data_off as usize + data_area.len());
    out.extend_from_slice(b"II");
    out.extend_from_slice(&42u16.to_le_bytes());
    out.extend_from_slice(&off_ifd0.to_le_bytes());
    write_ifd(&mut out, &ifd0, if has_ifd1 { off_ifd1 } else { 0 });
    if has_exif {
        write_ifd(&mut out, &exif, 0);
    }
    if has_gps {
        write_ifd(&mut out, &gps, 0);
    }
    if has_ifd1 {
        write_ifd(&mut out, &ifd1, 0);
    }
    debug_assert_eq!(out.len(), ifd_total);
    out.extend_from_slice(&data_area);
    out
}

fn write_ifd(out: &mut Vec<u8>, list: &[Entry], next: u32) {
    out.extend_from_slice(&(list.len() as u16).to_le_bytes());
    for e in list {
        out.extend_from_slice(&e.tag.to_le_bytes());
        out.extend_from_slice(&e.typ.to_le_bytes());
        out.extend_from_slice(&e.count.to_le_bytes());
        if e.data.len() <= 4 {
            let mut v = [0u8; 4];
            v[..e.data.len()].copy_from_slice(&e.data);
            out.extend_from_slice(&v);
        } else {
            out.extend_from_slice(&e.data[..4]);
        }
    }
    out.extend_from_slice(&next.to_le_bytes());
}

/// Typed read used by the token formatters (ported from E2b).
pub fn read(jpeg: &[u8]) -> Result<Exif, String> {
    let block = load(jpeg)?;
    let mut exif = Exif::default();
    exif.make = block.get_ascii(Ifd::Ifd0, 0x010F).unwrap_or_default();
    exif.model = block.get_ascii(Ifd::Ifd0, 0x0110).unwrap_or_default();
    exif.lens = block.get_ascii(Ifd::Exif, 0xA434).unwrap_or_default();
    exif.date_time = block.get_ascii(Ifd::Exif, 0x9003).unwrap_or_else(|| {
        block.get_ascii(Ifd::Ifd0, 0x0132).unwrap_or_default()
    });
    let rat = |tag: u16| -> Option<f32> {
        let e = block.get(Ifd::Exif, tag)?;
        if e.typ != 5 || e.data.len() < 8 {
            return None;
        }
        let n = u32::from_le_bytes([e.data[0], e.data[1], e.data[2], e.data[3]]) as f32;
        let d = u32::from_le_bytes([e.data[4], e.data[5], e.data[6], e.data[7]]) as f32;
        if d == 0.0 {
            None
        } else {
            Some(n / d)
        }
    };
    let short = |tag: u16| -> Option<u32> {
        let e = block.get(Ifd::Exif, tag)?;
        if e.typ == 3 && e.data.len() >= 2 {
            Some(u16::from_le_bytes([e.data[0], e.data[1]]) as u32)
        } else if e.typ == 4 && e.data.len() >= 4 {
            Some(u32::from_le_bytes([e.data[0], e.data[1], e.data[2], e.data[3]]))
        } else {
            None
        }
    };
    exif.focal = rat(0x920A);
    exif.aperture = rat(0x829D);
    exif.shutter = rat(0x829A);
    exif.iso = short(0x8827);
    // 0xA405 is SHORT on the wire; when an app export wrote the piexif pair
    // [mm*100, 100] it has count 2 and must be divided (core/exif.ts num()).
    exif.focal_35mm = match block.get(Ifd::Exif, 0xA405) {
        Some(e) if e.typ == 3 && e.count >= 2 && e.data.len() >= 4 => {
            let a = u16::from_le_bytes([e.data[0], e.data[1]]) as f32;
            let b = u16::from_le_bytes([e.data[2], e.data[3]]) as f32;
            if b > 0.0 {
                Some(a / b)
            } else {
                Some(a)
            }
        }
        _ => short(0xA405).map(|v| v as f32),
    };
    Ok(exif)
}

// ---------------------------------------------------------------------------
// Token formatting — port of core/exif.ts (kept identical to E2b's copy).
// ---------------------------------------------------------------------------

pub fn model_without_make(make: &str, model: &str) -> String {
    let m = make.trim();
    let d = model.trim();
    if m.is_empty() || d.is_empty() {
        return d.to_string();
    }
    if d.to_lowercase().starts_with(&format!("{} ", m.to_lowercase())) {
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
        let head = self.date_time.get(0..10).unwrap_or(&self.date_time);
        head.replace(':', ".").replace('-', ".")
    }

    pub fn time_line(&self) -> String {
        self.date_only()
    }

    pub fn image_params(&self) -> String {
        let mut parts: Vec<String> = Vec::new();
        let focal = self.focal_35mm.or(self.focal);
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
