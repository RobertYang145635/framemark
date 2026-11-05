//! JPEG marker walker: sampling factors (the 4:4:4 claim is measured, not assumed),
//! DQT tables, segment order, ICC extraction/insertion, entropy-scan slicing.

pub struct SofComp {
    pub id: u8,
    pub h: u8,
    pub v: u8,
    pub tq: u8,
}

pub struct Sof {
    pub marker: u8,
    pub precision: u8,
    pub w: u16,
    pub h: u16,
    pub comps: Vec<SofComp>,
}

#[derive(Clone)]
pub struct Segment {
    pub marker: u8,
    pub start: usize,
    pub end: usize,
    pub id: String,
}

pub struct JpegInfo {
    pub segments: Vec<Segment>,
    pub sof: Option<Sof>,
    pub dqt_tables: Vec<(u8, Vec<u8>)>,
    pub has_exif: bool,
    pub icc: Option<Vec<u8>>,
    pub scan_start: usize,
    pub scan_end: usize,
}

pub fn parse(b: &[u8]) -> Result<JpegInfo, String> {
    if b.len() < 4 || b[0] != 0xFF || b[1] != 0xD8 {
        return Err("not a JPEG".into());
    }
    let mut segments = Vec::new();
    let mut sof = None;
    let mut dqt_tables: Vec<(u8, Vec<u8>)> = Vec::new();
    let mut has_exif = false;
    let mut icc_parts: Vec<(u8, Vec<u8>)> = Vec::new();
    let mut i = 2usize;
    let mut scan_start = 0usize;
    while i + 4 <= b.len() {
        if b[i] != 0xFF {
            i += 1;
            continue;
        }
        let marker = b[i + 1];
        if marker == 0x00 {
            i += 2;
            continue;
        }
        if marker == 0xD8 || marker == 0x01 || (0xD0..=0xD7).contains(&marker) {
            i += 2;
            continue;
        }
        if marker == 0xD9 {
            break;
        }
        let len = u16::from_be_bytes([b[i + 2], b[i + 3]]) as usize;
        if len < 2 || i + 2 + len > b.len() {
            return Err(format!("truncated segment 0x{marker:02X} at {i}"));
        }
        let payload = &b[i + 4..i + 2 + len];
        let mut id = String::new();
        if marker == 0xE0 && payload.len() >= 5 {
            id = format!(
                "APP0:{}",
                String::from_utf8_lossy(&payload[..5]).replace('\0', "")
            );
        } else if marker == 0xE1 && payload.len() >= 6 && &payload[..6] == b"Exif\0\0" {
            id = "APP1:Exif".into();
            has_exif = true;
        } else if marker == 0xE2 && payload.len() >= 12 && &payload[..12] == b"ICC_PROFILE\0" {
            id = "APP2:ICC".into();
            let seq = payload[12];
            icc_parts.push((seq, payload[14..].to_vec()));
        } else if marker == 0xE2 && payload.len() >= 11 && &payload[..11] == b"MPF\0" {
            id = "APP2:MPF".into();
        }
        if (0xC0..=0xC3).contains(&marker)
            || (0xC5..=0xC7).contains(&marker)
            || (0xC9..=0xCB).contains(&marker)
            || (0xCD..=0xCF).contains(&marker)
        {
            if payload.len() >= 6 {
                let precision = payload[0];
                let h = u16::from_be_bytes([payload[1], payload[2]]);
                let w = u16::from_be_bytes([payload[3], payload[4]]);
                let n = payload[5] as usize;
                let mut comps = Vec::new();
                for c in 0..n {
                    let o = 6 + c * 3;
                    if o + 3 > payload.len() {
                        break;
                    }
                    comps.push(SofComp {
                        id: payload[o],
                        h: payload[o + 1] >> 4,
                        v: payload[o + 1] & 0x0F,
                        tq: payload[o + 2],
                    });
                }
                sof = Some(Sof {
                    marker,
                    precision,
                    w,
                    h,
                    comps,
                });
            }
        } else if marker == 0xDB {
            let mut p = 0usize;
            while p < payload.len() {
                let pq_tq = payload[p];
                let pq = (pq_tq >> 4) as usize;
                let tq = pq_tq & 0x0F;
                let n = 64 * (if pq == 1 { 2 } else { 1 });
                if p + 1 + n > payload.len() {
                    break;
                }
                dqt_tables.push((tq, payload[p + 1..p + 1 + n].to_vec()));
                p += 1 + n;
            }
        }
        segments.push(Segment {
            marker,
            start: i,
            end: i + 2 + len,
            id,
        });
        if marker == 0xDA {
            scan_start = i;
            break;
        }
        i += 2 + len;
    }
    let scan_end = b
        .iter()
        .rposition(|&x| x == 0xD9)
        .map(|p| p + 1)
        .unwrap_or(b.len());
    if scan_start == 0 {
        return Err("no SOS".into());
    }
    icc_parts.sort_by_key(|(s, _)| *s);
    let icc = if icc_parts.is_empty() {
        None
    } else {
        let mut v = Vec::new();
        for (_, p) in icc_parts {
            v.extend_from_slice(&p);
        }
        Some(v)
    };
    Ok(JpegInfo {
        segments,
        sof,
        dqt_tables,
        has_exif,
        icc,
        scan_start,
        scan_end,
    })
}

/// Bytes from the SOS marker through the EOI marker — the entropy-coded scan plus
/// the marker pair, which is the part that must match between encoders at q100.
pub fn scan_bytes(b: &[u8]) -> Result<&[u8], String> {
    let info = parse(b)?;
    Ok(&b[info.scan_start..info.scan_end])
}

pub fn subsampling_label(sof: &Sof) -> String {
    if sof.comps.len() < 3 {
        return "gray".into();
    }
    let h = sof.comps[0].h;
    let v = sof.comps[0].v;
    match (h, v) {
        (1, 1) => "4:4:4".into(),
        (2, 1) => "4:2:2".into(),
        (2, 2) => "4:2:0".into(),
        (1, 2) => "4:4:0".into(),
        _ => format!("{h}x{v}"),
    }
}

fn build_icc_segments(icc: &[u8]) -> Vec<Vec<u8>> {
    let mut icc_segments = Vec::new();
    // 65519 = 65535 max segment payload - 14 (ICC profile header) - 2 (length field)
    const CHUNK: usize = 65519 - 14;
    let chunks: Vec<&[u8]> = icc.chunks(CHUNK).collect();
    let total = chunks.len() as u8;
    for (idx, chunk) in chunks.iter().enumerate() {
        let mut seg = Vec::with_capacity(chunk.len() + 18);
        seg.extend_from_slice(&[0xFF, 0xE2]);
        let len = (chunk.len() + 16) as u16;
        seg.extend_from_slice(&len.to_be_bytes());
        seg.extend_from_slice(b"ICC_PROFILE\0");
        seg.push(idx as u8 + 1);
        seg.push(total);
        seg.extend_from_slice(chunk);
        icc_segments.push(seg);
    }
    icc_segments
}

fn build_app1(tiff: &[u8]) -> Result<Vec<u8>, String> {
    if tiff.len() + 8 > 65535 {
        return Err("EXIF block too large for one APP1".into());
    }
    let mut app1 = Vec::with_capacity(tiff.len() + 10);
    app1.extend_from_slice(&[0xFF, 0xE1]);
    app1.extend_from_slice(&((tiff.len() + 8) as u16).to_be_bytes());
    app1.extend_from_slice(b"Exif\0\0");
    app1.extend_from_slice(tiff);
    Ok(app1)
}

/// Drop every APP2/ICC_PROFILE and insert `icc` after APP0 (or right after SOI
/// when there is no APP0) — the position sharp uses.
pub fn insert_icc(jpeg: &[u8], icc: &[u8]) -> Result<Vec<u8>, String> {
    let info = parse(jpeg)?;
    let icc_segments = build_icc_segments(icc);
    let has_app0 = info.segments.iter().any(|s| s.id == "APP0:JFIF");
    let mut out = Vec::with_capacity(jpeg.len() + icc.len() + 32);
    out.extend_from_slice(&jpeg[..2]);
    if !has_app0 {
        for s in &icc_segments {
            out.extend_from_slice(s);
        }
    }
    let mut pos = 2usize;
    for seg in &info.segments {
        if seg.start > pos {
            out.extend_from_slice(&jpeg[pos..seg.start]);
        }
        if seg.id == "APP2:ICC" {
            pos = seg.end;
            continue;
        }
        out.extend_from_slice(&jpeg[seg.start..seg.end]);
        if seg.id == "APP0:JFIF" {
            for s in &icc_segments {
                out.extend_from_slice(s);
            }
        }
        pos = seg.end;
    }
    if pos < jpeg.len() {
        out.extend_from_slice(&jpeg[pos..]);
    }
    Ok(out)
}

/// Replace (or add) the APP1/Exif segment, keeping every other byte. The new
/// APP1 goes where the old one was, else after APP0, else right after SOI.
pub fn insert_exif(jpeg: &[u8], tiff: &[u8]) -> Result<Vec<u8>, String> {
    let info = parse(jpeg)?;
    let app1 = build_app1(tiff)?;
    let has_app0 = info.segments.iter().any(|s| s.id == "APP0:JFIF");
    let has_exif = info.segments.iter().any(|s| s.id == "APP1:Exif");
    let mut out = Vec::with_capacity(jpeg.len() + app1.len());
    out.extend_from_slice(&jpeg[..2]);
    if !has_app0 && !has_exif {
        out.extend_from_slice(&app1);
    }
    let mut inserted = false;
    let mut pos = 2usize;
    for seg in &info.segments {
        if seg.start > pos {
            out.extend_from_slice(&jpeg[pos..seg.start]);
        }
        if seg.id == "APP1:Exif" {
            if !inserted {
                out.extend_from_slice(&app1);
                inserted = true;
            }
            pos = seg.end;
            continue;
        }
        out.extend_from_slice(&jpeg[seg.start..seg.end]);
        if seg.id == "APP0:JFIF" && !inserted {
            out.extend_from_slice(&app1);
            inserted = true;
        }
        pos = seg.end;
    }
    if pos < jpeg.len() {
        out.extend_from_slice(&jpeg[pos..]);
    }
    Ok(out)
}
