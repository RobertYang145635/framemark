//! Minimal JPEG metadata for the prototype's report: SOF sampling factors,
//! DQT all-ones, and the entropy-scan hash (the bytes that must match Chromium).

use crate::sha1;

pub struct Info {
    pub w: u16,
    pub h: u16,
    pub subsampling: String,
    pub dqt_all_ones: bool,
    pub scan_bytes: usize,
    pub scan_sha1: String,
    pub has_app0: bool,
    pub has_icc: bool,
}

/// Header-only facts (SOF sampling, DQT) from a prefix of the file — lets the
/// worker report without reading a 48 MB output back into memory.
pub struct Header {
    pub w: u16,
    pub h: u16,
    pub subsampling: String,
    pub dqt_all_ones: bool,
    pub has_app0: bool,
    pub has_icc: bool,
}

pub fn header(b: &[u8]) -> Result<Header, String> {
    let info = parse_prefix(b)?;
    let (w, h, hs, vs) = info.sof.ok_or("no SOF")?;
    let subsampling = match (hs, vs) {
        (1, 1) => "4:4:4",
        (2, 1) => "4:2:2",
        (2, 2) => "4:2:0",
        other => return Err(format!("unexpected sampling {other:?}")),
    };
    Ok(Header {
        w,
        h,
        subsampling: subsampling.into(),
        dqt_all_ones: info.saw_dqt && info.dqt_all_ones,
        has_app0: info.has_app0,
        has_icc: info.has_icc,
    })
}

struct Prefix {
    sof: Option<(u16, u16, u8, u8)>,
    dqt_all_ones: bool,
    saw_dqt: bool,
    has_app0: bool,
    has_icc: bool,
}

fn parse_prefix(b: &[u8]) -> Result<Prefix, String> {
    let mut info = Prefix { sof: None, dqt_all_ones: true, saw_dqt: false, has_app0: false, has_icc: false };
    let mut i = 2usize;
    while i + 4 <= b.len() {
        if b[i] != 0xFF {
            i += 1;
            continue;
        }
        let m = b[i + 1];
        if m == 0xD8 || m == 0x01 || (0xD0..=0xD7).contains(&m) {
            i += 2;
            continue;
        }
        if m == 0xD9 || m == 0xDA {
            return Ok(info);
        }
        let len = u16::from_be_bytes([b[i + 2], b[i + 3]]) as usize;
        if len < 2 || i + 2 + len > b.len() {
            return Err("truncated header prefix".into());
        }
        let payload = &b[i + 4..i + 2 + len];
        if m == 0xE0 {
            info.has_app0 = true;
        } else if m == 0xE2 && payload.len() >= 12 && &payload[..12] == b"ICC_PROFILE\0" {
            info.has_icc = true;
        } else if (0xC0..=0xC3).contains(&m) && payload.len() >= 6 {
            let h = u16::from_be_bytes([payload[1], payload[2]]);
            let w = u16::from_be_bytes([payload[3], payload[4]]);
            let comps = payload[5] as usize;
            if comps >= 3 {
                let hv = payload[6 + 1];
                info.sof = Some((w, h, hv >> 4, hv & 0x0F));
            }
        } else if m == 0xDB {
            let mut p = 0usize;
            while p < payload.len() {
                let pq = (payload[p] >> 4) as usize;
                let n = 64 * (if pq == 1 { 2 } else { 1 });
                if p + 1 + n > payload.len() {
                    break;
                }
                info.saw_dqt = true;
                if !payload[p + 1..p + 1 + n].iter().all(|&v| v == 1) {
                    info.dqt_all_ones = false;
                }
                p += 1 + n;
            }
        }
        i += 2 + len;
    }
    Ok(info)
}

pub fn parse(b: &[u8]) -> Result<Info, String> {
    if b.len() < 4 || b[0] != 0xFF || b[1] != 0xD8 {
        return Err("not a JPEG".into());
    }
    let mut i = 2usize;
    let mut sof: Option<(u16, u16, u8, u8)> = None; // w,h,h_samp,v_samp
    let mut dqt_all_ones = true;
    let mut saw_dqt = false;
    let mut has_app0 = false;
    let mut has_icc = false;
    let mut scan_start = 0usize;
    while i + 4 <= b.len() {
        if b[i] != 0xFF {
            i += 1;
            continue;
        }
        let m = b[i + 1];
        if m == 0x00 {
            i += 2;
            continue;
        }
        if m == 0xD8 || m == 0x01 || (0xD0..=0xD7).contains(&m) {
            i += 2;
            continue;
        }
        if m == 0xD9 {
            break;
        }
        let len = u16::from_be_bytes([b[i + 2], b[i + 3]]) as usize;
        if len < 2 || i + 2 + len > b.len() {
            return Err(format!("truncated segment 0x{m:02X}"));
        }
        let payload = &b[i + 4..i + 2 + len];
        if m == 0xE0 {
            has_app0 = true;
        } else if m == 0xE2 && payload.len() >= 12 && &payload[..12] == b"ICC_PROFILE\0" {
            has_icc = true;
        } else if (0xC0..=0xC3).contains(&m) && payload.len() >= 6 {
            let h = u16::from_be_bytes([payload[1], payload[2]]);
            let w = u16::from_be_bytes([payload[3], payload[4]]);
            let comps = payload[5] as usize;
            if comps >= 3 {
                let hv = payload[6 + 1];
                sof = Some((w, h, hv >> 4, hv & 0x0F));
            }
        } else if m == 0xDB {
            let mut p = 0usize;
            while p < payload.len() {
                let pq = (payload[p] >> 4) as usize;
                let n = 64 * (if pq == 1 { 2 } else { 1 });
                if p + 1 + n > payload.len() {
                    break;
                }
                saw_dqt = true;
                if !payload[p + 1..p + 1 + n].iter().all(|&v| v == 1) {
                    dqt_all_ones = false;
                }
                p += 1 + n;
            }
        }
        if m == 0xDA {
            scan_start = i;
            break;
        }
        i += 2 + len;
    }
    let scan_end = b.iter().rposition(|&x| x == 0xD9).map(|p| p + 1).unwrap_or(b.len());
    if scan_start == 0 {
        return Err("no SOS".into());
    }
    let (w, h, hs, vs) = sof.ok_or("no SOF")?;
    let subsampling = match (hs, vs) {
        (1, 1) => "4:4:4",
        (2, 1) => "4:2:2",
        (2, 2) => "4:2:0",
        other => return Err(format!("unexpected sampling {other:?}")),
    };
    Ok(Info {
        w,
        h,
        subsampling: subsampling.into(),
        dqt_all_ones: saw_dqt && dqt_all_ones,
        scan_bytes: scan_end - scan_start,
        scan_sha1: sha1::hex(&b[scan_start..scan_end]),
        has_app0,
        has_icc,
    })
}
