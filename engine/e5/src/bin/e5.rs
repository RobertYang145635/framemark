//! E5 bench (desktop-measurable only).
//!
//! Subcommands:
//!   encode-parity  q100 4:4:4 JPEG + PNG vs the two recorded frozen encoders
//!   exif           EXIF copy/override/GPS-strip/fallback + readback + pixel invariance
//!   export         the reusable API end-to-end (`api::encode_jpeg` incl. thumbnail)
//!   tiff           TIFF source -> PNG (orientation applied) vs the sharp reference
//!   perf           stage timing + sampled RSS / commit peaks on a 50MP canvas
//!   raw            dump decoded RGBA (+dims) for the Skia-free stream prototype
//!   png-levels     PNG zlib level tradeoff at 50MP
//!   selftest       deliberately-red checks for each comparator (must fail where expected)
//!
//! Nothing here touches `framemark/engine/{src,e2a,e2b,e3-*}` or `engine/data`.

use fm_e5::{api, exif, jpegmeta, rss, sha1, tiffsrc};

use serde_json::{json, Value};
use skia_safe::{jpeg_encoder, AlphaType, ColorSpace, ColorType, Data, Image, ImageInfo};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

fn main() {
    if let Err(e) = run() {
        eprintln!("E5-ERROR: {e}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let mut it = std::env::args().skip(1);
    let cmd = it.next().ok_or("usage: e5 <encode-parity|exif|tiff|perf|selftest> --key value ...")?;
    let mut args: HashMap<String, String> = HashMap::new();
    while let Some(k) = it.next() {
        if !k.starts_with("--") {
            return Err(format!("unexpected argument {k}"));
        }
        let v = it.next().ok_or_else(|| format!("missing value for {k}"))?;
        args.insert(k.trim_start_matches("--").to_string(), v);
    }
    match cmd.as_str() {
        "encode-parity" => cmd_encode_parity(&args),
        "exif" => cmd_exif(&args),
        "export" => cmd_export(&args),
        "tiff" => cmd_tiff(&args),
        "perf" => cmd_perf(&args),
        "raw" => cmd_raw(&args),
        "png-levels" => cmd_png_levels(&args),
        "selftest" => cmd_selftest(&args),
        other => Err(format!("unknown command {other}")),
    }
}

fn req<'a>(args: &'a HashMap<String, String>, key: &str) -> Result<&'a str, String> {
    args.get(key).map(|s| s.as_str()).ok_or_else(|| format!("missing --{key}"))
}

fn opt<'a>(args: &'a HashMap<String, String>, key: &str) -> Option<&'a str> {
    args.get(key).map(|s| s.as_str())
}

fn write_json(path: &Path, value: &Value) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("mkdir: {e}"))?;
    }
    std::fs::write(path, serde_json::to_vec_pretty(value).map_err(|e| e.to_string())?)
        .map_err(|e| format!("write {}: {e}", path.display()))
}

fn read(path: &str) -> Result<Vec<u8>, String> {
    std::fs::read(path).map_err(|e| format!("read {path}: {e}"))
}

fn write(path: &Path, bytes: &[u8]) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("mkdir: {e}"))?;
    }
    std::fs::write(path, bytes).map_err(|e| format!("write {}: {e}", path.display()))
}

// ---------------------------------------------------------------------------
// image helpers (the encoder itself lives in the library: fm_e5::api)
// ---------------------------------------------------------------------------

use api::decode_rgba;
use fm_e5::api::encode_png;

fn encode_jpeg(
    rgba: &mut [u8],
    w: u32,
    h: u32,
    quality: u32,
    downsample: jpeg_encoder::Downsample,
) -> Result<Vec<u8>, String> {
    api::encode_jpeg_raw(rgba, w, h, quality, downsample)
}

fn srgb_info(w: u32, h: u32) -> skia_safe::ImageInfo {
    api::srgb_info(w, h)
}

#[derive(Default, Clone)]
struct Diff {
    max: u8,
    diff_px: usize,
    total_px: usize,
    rmse: f64,
    chroma_rmse: f64,
}

fn compare_rgba(a: &[u8], b: &[u8]) -> Result<Diff, String> {
    if a.len() != b.len() || a.len() % 4 != 0 {
        return Err(format!("size mismatch {} vs {}", a.len(), b.len()));
    }
    let n = a.len() / 4;
    let mut max = 0u8;
    let mut diff_px = 0usize;
    let mut sq = 0f64;
    let mut chroma_sq = 0f64;
    for i in 0..n {
        let o = i * 4;
        let mut px_diff = false;
        for c in 0..3 {
            let d = a[o + c].abs_diff(b[o + c]);
            if d > 0 {
                px_diff = true;
            }
            max = max.max(d);
            sq += (d as f64) * (d as f64);
        }
        if px_diff {
            diff_px += 1;
        }
        let (_, cb1, cr1) = ycbcr(a[o], a[o + 1], a[o + 2]);
        let (_, cb2, cr2) = ycbcr(b[o], b[o + 1], b[o + 2]);
        chroma_sq += (cb1 - cb2) * (cb1 - cb2) + (cr1 - cr2) * (cr1 - cr2);
    }
    Ok(Diff {
        max,
        diff_px,
        total_px: n,
        rmse: (sq / (n as f64 * 3.0)).sqrt(),
        chroma_rmse: (chroma_sq / (n as f64 * 2.0)).sqrt(),
    })
}

fn ycbcr(r: u8, g: u8, b: u8) -> (f64, f64, f64) {
    let (r, g, b) = (r as f64, g as f64, b as f64);
    (
        0.299 * r + 0.587 * g + 0.114 * b,
        -0.168736 * r - 0.331264 * g + 0.5 * b + 128.0,
        0.5 * r - 0.418688 * g - 0.081312 * b + 128.0,
    )
}

fn diff_json(d: &Diff) -> Value {
    json!({
        "maxDelta": d.max,
        "diffPx": d.diff_px,
        "totalPx": d.total_px,
        "diffPct": if d.total_px > 0 { d.diff_px as f64 * 100.0 / d.total_px as f64 } else { 0.0 },
        "rmse": round4(d.rmse),
    })
}

fn round4(v: f64) -> f64 {
    (v * 10000.0).round() / 10000.0
}

fn jpeg_facts(bytes: &[u8]) -> Result<Value, String> {
    let info = jpegmeta::parse(bytes)?;
    let sof = info.sof.as_ref().ok_or("no SOF")?;
    let comps: Vec<Value> = sof
        .comps
        .iter()
        .map(|c| json!({ "id": c.id, "h": c.h, "v": c.v, "tq": c.tq }))
        .collect();
    let dqt_ones: Vec<bool> = info
        .dqt_tables
        .iter()
        .map(|(_, t)| t.iter().all(|&v| v == 1))
        .collect();
    Ok(json!({
        "bytes": bytes.len(),
        "sha1": sha1::hex(bytes),
        "scanBytes": info.scan_end - info.scan_start,
        "scanSha1": sha1::hex(&bytes[info.scan_start..info.scan_end]),
        "sofMarker": format!("0x{:02X}", sof.marker),
        "precision": sof.precision,
        "w": sof.w,
        "h": sof.h,
        "components": comps,
        "subsampling": jpegmeta::subsampling_label(sof),
        "dqtAllOnes": dqt_ones,
        "hasExif": info.has_exif,
        "iccBytes": info.icc.as_ref().map(|v| v.len()).unwrap_or(0),
        "segments": info.segments.iter().map(|s| {
            if s.id.is_empty() { format!("0x{:02X}", s.marker) } else { s.id.clone() }
        }).collect::<Vec<_>>(),
    }))
}

// ---------------------------------------------------------------------------
// encode-parity
// ---------------------------------------------------------------------------

fn cmd_encode_parity(args: &HashMap<String, String>) -> Result<(), String> {
    let src_path = req(args, "src")?;
    let outdir = PathBuf::from(req(args, "outdir")?);
    let out_json = PathBuf::from(req(args, "out")?);
    let src_bytes = read(src_path)?;
    let (mut rgba, w, h) = decode_rgba(&src_bytes)?;
    eprintln!("[e5] source {src_path} {}x{} sha1 {}", w, h, &sha1::hex(&src_bytes)[..12]);

    // native q100 4:4:4
    let t0 = std::time::Instant::now();
    let native_jpeg = encode_jpeg(&mut rgba, w, h, 100, jpeg_encoder::Downsample::No)?;
    let jpeg_ms = t0.elapsed().as_secs_f64() * 1000.0;
    let t1 = std::time::Instant::now();
    let native_png = encode_png(&mut rgba, w, h, 9)?;
    let png_ms = t1.elapsed().as_secs_f64() * 1000.0;
    write(&outdir.join("native-q100-444.jpg"), &native_jpeg)?;
    write(&outdir.join("native.png"), &native_png)?;
    let native_jpeg_facts = jpeg_facts(&native_jpeg)?;
    let (native_jpeg_dec, _, _) = decode_rgba(&native_jpeg)?;
    let (native_png_dec, _, _) = decode_rgba(&native_png)?;
    eprintln!(
        "[e5] native jpeg {} B ({}), png {} B in {:.1}/{:.1} ms",
        native_jpeg.len(),
        native_jpeg_facts["subsampling"].as_str().unwrap_or("?"),
        native_png.len(),
        jpeg_ms,
        png_ms
    );

    // references
    let mut refs = serde_json::Map::new();
    let mut ref_objs: Vec<(String, Vec<u8>, Vec<u8>, u32, u32)> = Vec::new();
    for (key, arg) in [
        ("androidJpeg", "android-jpeg"),
        ("desktopJpeg", "desktop-jpeg"),
        ("androidPng", "android-png"),
        ("desktopPng", "desktop-png"),
    ] {
        if let Some(p) = opt(args, arg) {
            let bytes = read(p)?;
            let (dec, rw, rh) = decode_rgba(&bytes)?;
            let mut entry = serde_json::Map::new();
            entry.insert("path".into(), json!(p));
            entry.insert("sha1".into(), json!(sha1::hex(&bytes)));
            if key.ends_with("Jpeg") {
                let facts = jpeg_facts(&bytes)?;
                entry.insert("facts".into(), facts);
            }
            entry.insert("decodedVsSource".into(), diff_json(&compare_rgba(&dec, &rgba)?));
            refs.insert(key.to_string(), Value::Object(entry));
            ref_objs.push((key.to_string(), bytes, dec, rw, rh));
        }
    }

    // native vs references
    let mut cmp = serde_json::Map::new();
    for (key, bytes, dec, _, _) in &ref_objs {
        let native_bytes = if key.ends_with("Jpeg") { &native_jpeg } else { &native_png };
        let native_dec = if key.ends_with("Jpeg") { &native_jpeg_dec } else { &native_png_dec };
        let scan_equal = if key.ends_with("Jpeg") {
            let a = jpegmeta::scan_bytes(native_bytes)?;
            let b = jpegmeta::scan_bytes(bytes)?;
            a == b
        } else {
            false
        };
        cmp.insert(
            key.clone(),
            json!({
                "fileEqual": native_bytes == bytes,
                "bytesNative": native_bytes.len(),
                "bytesRef": bytes.len(),
                "scanEqual": scan_equal,
                "decoded": diff_json(&compare_rgba(native_dec, dec)?),
            }),
        );
    }

    // ICC: take the profile from a reference JPEG, insert into the native one,
    // then compare the whole file with that reference.
    let mut icc_report = Value::Null;
    if let Some(icc_from) = opt(args, "icc-from") {
        let ref_bytes = read(icc_from)?;
        let ref_info = jpegmeta::parse(&ref_bytes)?;
        if let Some(icc) = ref_info.icc.clone() {
            let with_icc = jpegmeta::insert_icc(&native_jpeg, &icc)?;
            let out_path = outdir.join("native-q100-444-icc.jpg");
            write(&out_path, &with_icc)?;
            let ref_facts = jpeg_facts(&ref_bytes)?;
            let same_scan = jpegmeta::scan_bytes(&with_icc)? == jpegmeta::scan_bytes(&ref_bytes)?;
            let (dec_a, _, _) = decode_rgba(&with_icc)?;
            let (dec_b, _, _) = decode_rgba(&ref_bytes)?;
            icc_report = json!({
                "from": icc_from,
                "iccBytes": icc.len(),
                "nativeWithIccBytes": with_icc.len(),
                "refBytes": ref_bytes.len(),
                "fileEqual": with_icc == ref_bytes,
                "scanEqual": same_scan,
                "decoded": diff_json(&compare_rgba(&dec_a, &dec_b)?),
                "refFacts": ref_facts,
            });
        }
    }

    // q<100: record the 4:2:0 step, never used as an export path.
    let mut q_rows = Vec::new();
    for (q, label) in [(95u32, "q95"), (85u32, "q85")] {
        let j444 = encode_jpeg(&mut rgba, w, h, q, jpeg_encoder::Downsample::No)?;
        let j420 = encode_jpeg(&mut rgba, w, h, q, jpeg_encoder::Downsample::BothDirections)?;
        write(&outdir.join(format!("q{q}-444.jpg")), &j444)?;
        write(&outdir.join(format!("q{q}-420.jpg")), &j420)?;
        let (d444, _, _) = decode_rgba(&j444)?;
        let (d420, _, _) = decode_rgba(&j420)?;
        let f444 = jpeg_facts(&j444)?;
        let f420 = jpeg_facts(&j420)?;
        let mut row = json!({
            "quality": q,
            "label": label,
            "native444": { "bytes": j444.len(), "subsampling": f444["subsampling"], "chromaRmseVsSource": round4(compare_rgba(&d444, &rgba)?.chroma_rmse) },
            "native420": { "bytes": j420.len(), "subsampling": f420["subsampling"], "chromaRmseVsSource": round4(compare_rgba(&d420, &rgba)?.chroma_rmse) },
        });
        if let Some(p) = opt(args, &format!("android-{label}")) {
            if let Ok(bytes) = read(p) {
                let (dec, _, _) = decode_rgba(&bytes)?;
                let f = jpeg_facts(&bytes)?;
                row["android"] = json!({
                    "path": p, "bytes": bytes.len(),
                    "subsampling": f["subsampling"],
                    "chromaRmseVsSource": round4(compare_rgba(&dec, &rgba)?.chroma_rmse),
                });
            }
        }
        q_rows.push(row);
    }

    let report = json!({
        "command": "encode-parity",
        "generatedAt": now_iso(),
        "source": { "path": src_path, "w": w, "h": h, "sha1": sha1::hex(&src_bytes) },
        "native": {
            "jpeg": native_jpeg_facts,
            "png": { "bytes": native_png.len(), "sha1": sha1::hex(&native_png), "zlibLevel": 9, "filterFlags": "ALL",
                     "decodedVsSource": diff_json(&compare_rgba(&native_png_dec, &rgba)?) },
            "msJpegQ100": round4(jpeg_ms),
            "msPng": round4(png_ms),
        },
        "refs": Value::Object(refs),
        "compare": Value::Object(cmp),
        "icc": icc_report,
        "belowQ100": q_rows,
    });
    write_json(&out_json, &report)?;
    eprintln!("[e5] wrote {}", out_json.display());
    Ok(())
}

fn now_iso() -> String {
    // Std-only UTC timestamp (no chrono): seconds since epoch is enough for the log.
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    format!("epoch+{secs}s")
}

// ---------------------------------------------------------------------------
// exif
// ---------------------------------------------------------------------------

fn cmd_exif(args: &HashMap<String, String>) -> Result<(), String> {
    let encoded_path = req(args, "encoded")?;
    let outdir = PathBuf::from(req(args, "outdir")?);
    let out_json = PathBuf::from(req(args, "out")?);
    let encoded = read(encoded_path)?;
    let (enc_dec, ew, eh) = decode_rgba(&encoded)?;

    let (source_block, source_path) = match opt(args, "source") {
        Some(p) => (exif::load(&read(p)?)?, p.to_string()),
        None => (exif::Block::default(), String::new()),
    };
    let source_tokens = source_path_to_tokens(&source_path);

    // 1) copy + override + strip GPS
    let mut block = source_block.clone();
    exif::prepare_export(&mut block, ew, eh, true);
    let tiff = exif::dump(&block);
    let copied = jpegmeta::insert_exif(&encoded, &tiff)?;
    let copied_path = outdir.join("exif-copied.jpg");
    write(&copied_path, &copied)?;
    let copied_read = exif::read(&copied)?;
    let copied_block = exif::load(&copied)?;

    // 2) GPS present then stripped
    let mut gps_block = source_block.clone();
    add_synthetic_gps(&mut gps_block);
    exif::prepare_export(&mut gps_block, ew, eh, false);
    let gps_kept = jpegmeta::insert_exif(&encoded, &exif::dump(&gps_block))?;
    let gps_kept_path = outdir.join("exif-gps-kept.jpg");
    write(&gps_kept_path, &gps_kept)?;
    let kept_gps = exif::load(&gps_kept)?.gps.len();
    let mut strip_block = exif::load(&gps_kept)?;
    exif::prepare_export(&mut strip_block, ew, eh, true);
    let gps_stripped = jpegmeta::insert_exif(&encoded, &exif::dump(&strip_block))?;
    let gps_stripped_path = outdir.join("exif-gps-stripped.jpg");
    write(&gps_stripped_path, &gps_stripped)?;
    let stripped_gps = exif::load(&gps_stripped)?.gps.len();

    // 3) fallback (no source JPEG), same values as buildFallbackExif in main/index.ts
    let mut fb = exif::Block::default();
    fb.set_ascii(exif::Ifd::Ifd0, 0x010F, "Panasonic");
    fb.set_ascii(exif::Ifd::Ifd0, 0x0110, "DC-S1");
    fb.set_ascii(exif::Ifd::Ifd0, 0x0132, "2026:10:29 12:00:00");
    fb.set_ascii(exif::Ifd::Exif, 0x9003, "2026:10:29 12:00:00");
    fb.set_ascii(exif::Ifd::Exif, 0xA434, "LUMIX S 20-60/F3.5-5.6");
    fb.set_rational(exif::Ifd::Exif, 0x829D, 400, 100);
    // 0xA405 = piexif pair [3500, 100] as two SHORTs, exactly like buildFallbackExif.
    fb.set_short_pair(exif::Ifd::Exif, 0xA405, 3500, 100);
    fb.set_u16(exif::Ifd::Exif, 0x8827, 640);
    fb.set_rational(exif::Ifd::Exif, 0x829A, 1, 77);
    exif::prepare_export(&mut fb, ew, eh, true);
    let fallback = jpegmeta::insert_exif(&encoded, &exif::dump(&fb))?;
    let fallback_path = outdir.join("exif-fallback.jpg");
    write(&fallback_path, &fallback)?;
    let fallback_read = exif::read(&fallback)?;

    // 4) pixel invariance + segment order
    let mut invariance = Vec::new();
    for (name, bytes) in [
        ("copied", &copied),
        ("gpsKept", &gps_kept),
        ("gpsStripped", &gps_stripped),
        ("fallback", &fallback),
    ] {
        let (dec, dw, dh) = decode_rgba(bytes)?;
        invariance.push(json!({
            "file": name,
            "dims": [dw, dh],
            "decodedVsEncoded": diff_json(&compare_rgba(&dec, &enc_dec)?),
            "jpegFacts": jpeg_facts(bytes)?,
        }));
    }

    let report = json!({
        "command": "exif",
        "generatedAt": now_iso(),
        "encoded": { "path": encoded_path, "w": ew, "h": eh, "bytes": encoded.len(), "sha1": sha1::hex(&encoded) },
        "source": { "path": source_path, "ifd0Tags": tag_list(&source_block.ifd0), "exifTags": tag_list(&source_block.exif),
                     "gpsTags": tag_list(&source_block.gps), "tokens": source_tokens },
        "copied": {
            "file": copied_path.display().to_string(),
            "tiffBytes": tiff.len(),
            "app1Bytes": copied.len() - encoded.len(),
            "readback": readback_json(&copied_read),
            "ifd0Tags": tag_list(&copied_block.ifd0),
            "exifTags": tag_list(&copied_block.exif),
            "gpsTags": tag_list(&copied_block.gps),
            "tokens": tokens_json(&copied_read),
        },
        "gps": {
            "keptFile": gps_kept_path.display().to_string(),
            "keptGpsEntries": kept_gps,
            "strippedFile": gps_stripped_path.display().to_string(),
            "strippedGpsEntries": stripped_gps,
        },
        "fallback": {
            "file": fallback_path.display().to_string(),
            "readback": readback_json(&fallback_read),
            "tokens": tokens_json(&fallback_read),
        },
        "pixelInvariance": invariance,
    });
    write_json(&out_json, &report)?;
    eprintln!("[e5] exif report {}", out_json.display());
    Ok(())
}

fn source_path_to_tokens(path: &str) -> Value {
    if path.is_empty() {
        return Value::Null;
    }
    match read(path).and_then(|b| exif::read(&b)) {
        Ok(e) => tokens_json(&e),
        Err(e) => json!({ "error": e }),
    }
}

fn tokens_json(e: &exif::Exif) -> Value {
    json!({
        "modelLine": e.model_line(),
        "timeLine": e.time_line(),
        "imageParams": e.image_params(),
        "make": e.make, "model": e.model, "lens": e.lens,
        "focal": e.focal, "focal35": e.focal_35mm,
        "aperture": e.aperture, "shutter": e.shutter, "iso": e.iso,
    })
}

fn readback_json(e: &exif::Exif) -> Value {
    json!({
        "make": e.make, "model": e.model, "lens": e.lens,
        "dateTime": e.date_time,
        "focal": e.focal, "focal35": e.focal_35mm,
        "aperture": e.aperture, "shutter": e.shutter, "iso": e.iso,
    })
}

fn tag_list(list: &[exif::Entry]) -> Vec<String> {
    list.iter().map(|e| format!("0x{:04X}", e.tag)).collect()
}

fn add_synthetic_gps(block: &mut exif::Block) {
    block.gps.clear();
    block.put_raw(0x0000, 1, 4, vec![2, 3, 0, 0]);
    block.put_raw(0x0001, 2, 2, b"N\0".to_vec());
    let mut lat = Vec::new();
    for (n, d) in [(22u32, 1u32), (16u32, 1u32), (4890u32, 100u32)] {
        lat.extend_from_slice(&n.to_le_bytes());
        lat.extend_from_slice(&d.to_le_bytes());
    }
    block.put_raw(0x0002, 5, 3, lat);
    block.put_raw(0x0003, 2, 2, b"E\0".to_vec());
    let mut lon = Vec::new();
    for (n, d) in [(114u32, 1u32), (10u32, 1u32), (1234u32, 100u32)] {
        lon.extend_from_slice(&n.to_le_bytes());
        lon.extend_from_slice(&d.to_le_bytes());
    }
    block.put_raw(0x0004, 5, 3, lon);
}

// ---------------------------------------------------------------------------
// export — the reusable API end-to-end (D-027 policy)
// ---------------------------------------------------------------------------

fn sample_fallback() -> api::FallbackExif {
    api::FallbackExif {
        make: "Panasonic".into(),
        model: "DC-S1".into(),
        date_time: "2026-10-29 12:00:00".into(),
        lens: "LUMIX S 20-60/F3.5-5.6".into(),
        aperture: Some(4.0),
        focal: Some(35.0),
        focal_is_35mm: true,
        iso: Some(640),
        shutter: Some(1.0 / 77.0),
    }
}

fn exif_source_name(s: Option<api::ExifSource>) -> &'static str {
    match s {
        Some(api::ExifSource::Source) => "source",
        Some(api::ExifSource::Fallback) => "fallback",
        Some(api::ExifSource::Minimal) => "minimal",
        None => "none",
    }
}

fn cmd_export(args: &HashMap<String, String>) -> Result<(), String> {
    let src = req(args, "src")?;
    let outdir = PathBuf::from(req(args, "outdir")?);
    let out_json = PathBuf::from(req(args, "out")?);
    let (mut rgba, w, h) = decode_rgba(&read(src)?)?;

    let source_bytes = match opt(args, "exif-source") {
        Some(p) => Some(read(p)?),
        None => None,
    };
    let icc = match opt(args, "icc-source") {
        Some(p) => jpegmeta::parse(&read(p)?)?.icc,
        None => None,
    };
    let fallback = sample_fallback();

    let meta = api::JpegMeta {
        source_jpeg: source_bytes.as_deref(),
        fallback: Some(&fallback),
        remove_gps: true,
        keep_thumbnail: true,
        icc: icc.as_deref(),
    };
    let t0 = std::time::Instant::now();
    let (jpeg, report) = api::encode_jpeg_with_report(&mut rgba, w, h, &meta)?;
    let ms = t0.elapsed().as_secs_f64() * 1000.0;
    write(&outdir.join("api-export.jpg"), &jpeg)?;
    let (dec, dw, dh) = decode_rgba(&jpeg)?;

    // Fallback path (no source JPEG) exercising the same API.
    let meta_fb = api::JpegMeta {
        source_jpeg: None,
        fallback: Some(&fallback),
        remove_gps: true,
        keep_thumbnail: true,
        icc: icc.as_deref(),
    };
    let (fb, fb_report) = api::encode_jpeg_with_report(&mut rgba, w, h, &meta_fb)?;
    write(&outdir.join("api-export-fallback.jpg"), &fb)?;

    // PNG path at the policy default (D-027: level 6).
    let t1 = std::time::Instant::now();
    let png = api::encode_png(&mut rgba, w, h, api::DEFAULT_PNG_LEVEL)?;
    let png_ms = t1.elapsed().as_secs_f64() * 1000.0;
    write(&outdir.join("api-export.png"), &png)?;
    let (png_dec, _, _) = decode_rgba(&png)?;

    let report_json = json!({
        "command": "export",
        "generatedAt": now_iso(),
        "source": { "path": src, "w": w, "h": h },
        "policy": {
            "jpegQuality": api::DEFAULT_JPEG_QUALITY,
            "subsampling": "4:4:4",
            "container": "chromium (Skia APP0 + 456B sRGB ICC)",
            "pngLevel": api::DEFAULT_PNG_LEVEL,
            "removeGps": true,
            "keepThumbnail": true,
            "iccOverrideBytes": icc.as_ref().map(|v| v.len()),
        },
        "jpeg": {
            "file": outdir.join("api-export.jpg").display().to_string(),
            "bytes": jpeg.len(), "sha1": sha1::hex(&jpeg),
            "ms": round4(ms),
            "facts": jpeg_facts(&jpeg)?,
            "decoded": { "dims": [dw, dh], "vsSource": diff_json(&compare_rgba(&dec, &rgba)?) },
        },
        "exif": {
            "source": exif_source_name(report.source),
            "ifd0Tags": report.ifd0_tags,
            "exifTags": report.exif_tags,
            "thumbnailBytes": report.thumbnail_bytes,
            "app1Bytes": report.app1_bytes,
            "warning": report.warning,
        },
        "png": {
            "file": outdir.join("api-export.png").display().to_string(),
            "level": api::DEFAULT_PNG_LEVEL,
            "bytes": png.len(), "sha1": sha1::hex(&png), "ms": round4(png_ms),
            "decodedVsSource": diff_json(&compare_rgba(&png_dec, &rgba)?),
        },
        "fallback": {
            "file": outdir.join("api-export-fallback.jpg").display().to_string(),
            "bytes": fb.len(), "sha1": sha1::hex(&fb),
            "facts": jpeg_facts(&fb)?,
            "exif": {
                "source": exif_source_name(fb_report.source),
                "ifd0Tags": fb_report.ifd0_tags,
                "exifTags": fb_report.exif_tags,
                "thumbnailBytes": fb_report.thumbnail_bytes,
                "app1Bytes": fb_report.app1_bytes,
            },
        },
    });
    write_json(&out_json, &report_json)?;
    eprintln!("[e5] export report {}", out_json.display());
    Ok(())
}

// ---------------------------------------------------------------------------
// raw — dump decoded RGBA so the Skia-free streaming prototype can read the
// exact same canvas without a PNG decoder (it cannot link Skia: libjpeg clash)
// ---------------------------------------------------------------------------

fn cmd_raw(args: &HashMap<String, String>) -> Result<(), String> {
    let src = req(args, "src")?;
    let out = PathBuf::from(req(args, "out")?);
    let meta_path = PathBuf::from(req(args, "meta")?);
    let (rgba, w, h) = decode_rgba(&read(src)?)?;
    write(&out, &rgba)?;
    let meta = json!({
        "src": src, "w": w, "h": h, "bytes": rgba.len(), "layout": "rgba8 straight",
    });
    write_json(&meta_path, &meta)?;
    eprintln!("[e5] raw {w}x{h} -> {}", out.display());
    Ok(())
}

// ---------------------------------------------------------------------------
// shared canvas helpers
// ---------------------------------------------------------------------------

/// Deterministic worst-case canvas: gradients + per-pixel grain + flat bands.
fn synth_rgba(w: u32, h: u32) -> Vec<u8> {
    let px = (w as usize) * (h as usize);
    let mut buf: Vec<u8> = vec![0u8; px * 4];
    let mut seed = 0x1234_5678u32;
    for y in 0..h as usize {
        for x in 0..w as usize {
            seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
            let n = ((seed >> 24) & 0x1F) as i32 - 16;
            let gx = (x * 255 / (w as usize)) as i32;
            let gy = (y * 255 / (h as usize)) as i32;
            let band = if (y / 512) % 7 == 3 { 40 } else { 0 };
            let o = (y * w as usize + x) * 4;
            buf[o] = (gx + n).clamp(0, 255) as u8;
            buf[o + 1] = ((gx + gy) / 2 + n + band).clamp(0, 255) as u8;
            buf[o + 2] = (gy + n).clamp(0, 255) as u8;
            buf[o + 3] = 255;
        }
    }
    buf
}

// ---------------------------------------------------------------------------
// tiff
// ---------------------------------------------------------------------------

fn cmd_tiff(args: &HashMap<String, String>) -> Result<(), String> {
    let src = req(args, "src")?;
    let outdir = PathBuf::from(req(args, "outdir")?);
    let out_json = PathBuf::from(req(args, "out")?);
    let bytes = read(src)?;
    let orientation = tiffsrc::orientation(&bytes);
    let compression = tiffsrc::compression(&bytes);

    let t0 = std::time::Instant::now();
    let img = api::tiff_to_rgba(Path::new(src))?;
    let decode_ms = t0.elapsed().as_secs_f64() * 1000.0;
    let (oriented, ow, oh) = api::apply_orientation(&img.rgba, img.w, img.h, img.orientation);
    let mut rgba = oriented;
    let t1 = std::time::Instant::now();
    // Level 9 kept so this row stays comparable with the E5 baseline table.
    let png = encode_png(&mut rgba, ow, oh, 9)?;
    let png_ms = t1.elapsed().as_secs_f64() * 1000.0;
    let native_path = outdir.join("tiff-native.png");
    write(&native_path, &png)?;

    let mut vs_ref = Value::Null;
    if let Some(p) = opt(args, "ref") {
        let (ref_dec, rw, rh) = decode_rgba(&read(p)?)?;
        vs_ref = json!({
            "path": p, "refDims": [rw, rh], "nativeDims": [ow, oh],
            "sameDims": rw == ow && rh == oh,
            "decoded": diff_json(&compare_rgba(&rgba, &ref_dec)?),
        });
    }

    // orientation 6 path: the caller supplies the patched fixture (same bytes as
    // the sharp reference input), decode, rotate, compare with sharp .rotate().
    let mut rot_report = Value::Null;
    if let Some(ref_rot) = opt(args, "ref-rot") {
        let src_rot = req(args, "src-rot")?;
        let mut patched = read(src_rot)?;
        let ok = tiffsrc::patch_orientation(&mut patched, 6);
        let patched_path = outdir.join("tiff-rot6-src.tif");
        write(&patched_path, &patched)?;
        if ok || tiffsrc::orientation(&patched) == 6 {
            let img6 = tiffsrc::decode(&patched_path)?;
            let (rot, nw, nh) = tiffsrc::apply_orientation(&img6.rgba, img6.w, img6.h, img6.orientation);
            let mut rot_mut = rot;
            let rot_png = encode_png(&mut rot_mut, nw, nh, 9)?;
            let rot_path = outdir.join("tiff-rot6-native.png");
            write(&rot_path, &rot_png)?;
            let (ref_dec, rw, rh) = decode_rgba(&read(ref_rot)?)?;
            rot_report = json!({
                "patchedTiff": patched_path.display().to_string(),
                "patched": ok,
                "orientationRead": img6.orientation,
                "nativeDims": [nw, nh],
                "refPath": ref_rot,
                "refDims": [rw, rh],
                "sameDims": rw == nw && rh == nh,
                "decoded": diff_json(&compare_rgba(&rot_mut, &ref_dec)?),
            });
        } else {
            rot_report = json!({ "patched": false, "reason": "no Orientation tag in IFD0" });
        }
    }

    let report = json!({
        "command": "tiff",
        "generatedAt": now_iso(),
        "source": { "path": src, "bytes": bytes.len(), "sha1": sha1::hex(&bytes),
                     "orientation": orientation, "compressionTag": compression },
        "native": { "w": ow, "h": oh, "color": img.color, "pngBytes": png.len(),
                     "pngSha1": sha1::hex(&png), "msDecode": round4(decode_ms), "msPng": round4(png_ms) },
        "vsSharpReference": vs_ref,
        "orientation6": rot_report,
    });
    write_json(&out_json, &report)?;
    eprintln!("[e5] tiff report {}", out_json.display());
    Ok(())
}

// ---------------------------------------------------------------------------
// perf
// ---------------------------------------------------------------------------

fn prepare_photo(path: &str, w: u32, h: u32) -> Result<(Vec<u8>, f64), String> {
    let t = std::time::Instant::now();
    let img = Image::from_encoded(Data::new_copy(&read(path)?)).ok_or("source decode failed")?;
    let info_premul = ImageInfo::new(
        (w as i32, h as i32),
        ColorType::RGBA8888,
        AlphaType::Premul,
        Some(ColorSpace::new_srgb()),
    );
    let mut surface = skia_safe::surfaces::raster(&info_premul, None, None)
        .ok_or("raster surface alloc failed")?;
    {
        let canvas = surface.canvas();
        let paint = skia_safe::Paint::default();
        canvas.draw_image_rect(
            &img,
            None,
            skia_safe::Rect::from_wh(w as f32, h as f32),
            &paint,
        );
    }
    let info = srgb_info(w, h);
    let mut buf = vec![0u8; w as usize * h as usize * 4];
    if !surface.read_pixels(&info, &mut buf, w as usize * 4, (0, 0)) {
        return Err("surface readback failed".into());
    }
    Ok((buf, t.elapsed().as_secs_f64() * 1000.0))
}

fn cmd_perf(args: &HashMap<String, String>) -> Result<(), String> {
    let w: u32 = opt(args, "w").unwrap_or("8192").parse().map_err(|_| "bad --w")?;
    let h: u32 = opt(args, "h").unwrap_or("6144").parse().map_err(|_| "bad --h")?;
    let outdir = PathBuf::from(req(args, "outdir")?);
    let out_json = PathBuf::from(req(args, "out")?);
    let write_files = opt(args, "write").map(|v| v == "1" || v == "true").unwrap_or(false);
    let photo = opt(args, "src").map(|s| s.to_string());

    let sampler = rss::Sampler::start(20);
    let baseline = rss::counters();
    sampler.mark("baseline");

    let px = (w as usize) * (h as usize);
    let (mut rgba, prep_ms, prep_kind) = if let Some(src) = &photo {
        let (buf, ms) = prepare_photo(src, w, h)?;
        (buf, ms, format!("upscale:{src}"))
    } else {
        // deterministic worst-case canvas: gradients + per-pixel grain + flat bands
        let t = std::time::Instant::now();
        let buf = synth_rgba(w, h);
        (buf, t.elapsed().as_secs_f64() * 1000.0, "synthetic-grain".to_string())
    };
    sampler.mark("prepare");

    let mut stage_ms: Vec<(&str, f64, u64)> = Vec::new();
    stage_ms.push(("prepare", prep_ms, rgba.len() as u64));

    let t0 = std::time::Instant::now();
    let jpeg = encode_jpeg(&mut rgba, w, h, 100, jpeg_encoder::Downsample::No)?;
    stage_ms.push(("jpeg-encode", t0.elapsed().as_secs_f64() * 1000.0, jpeg.len() as u64));
    sampler.mark("jpeg-encode");

    let t1 = std::time::Instant::now();
    let png = encode_png(&mut rgba, w, h, 9)?;
    stage_ms.push(("png-encode", t1.elapsed().as_secs_f64() * 1000.0, png.len() as u64));
    sampler.mark("png-encode");

    let t2 = std::time::Instant::now();
    let (jpeg_dec, jw, jh) = decode_rgba(&jpeg)?;
    stage_ms.push(("jpeg-decode", t2.elapsed().as_secs_f64() * 1000.0, 0));
    sampler.mark("jpeg-decode");
    drop(jpeg_dec);

    let t3 = std::time::Instant::now();
    let (png_dec, pw, ph) = decode_rgba(&png)?;
    stage_ms.push(("png-decode", t3.elapsed().as_secs_f64() * 1000.0, 0));
    sampler.mark("png-decode");
    drop(png_dec);

    if write_files {
        write(&outdir.join("perf-50mp.jpg"), &jpeg)?;
        write(&outdir.join("perf-50mp.png"), &png)?;
    }
    sampler.mark("end");
    let (samples, marks, interval) = sampler.finish();
    let peak_sampled = samples.iter().map(|s| s.commit).max().unwrap_or(0);
    let peak_ws_sampled = samples.iter().map(|s| s.ws).max().unwrap_or(0);

    let mut stages = Vec::new();
    for (i, (name, ms, out_bytes)) in stage_ms.iter().enumerate() {
        let start = marks.get(i).map(|(_, t)| *t).unwrap_or(0);
        let end = marks.get(i + 1).map(|(_, t)| *t).unwrap_or(start);
        let win = rss::window_peak(&samples, start, end);
        stages.push(json!({
            "stage": name,
            "ms": round4(*ms),
            "windowMs": [start, end],
            "peakWsMB": mb(win.peak_ws),
            "peakCommitMB": mb(win.peak_commit),
            "outBytes": out_bytes,
        }));
    }

    let total_ms: f64 = stage_ms.iter().map(|(_, ms, _)| *ms).sum();
    let report = json!({
        "command": "perf",
        "generatedAt": now_iso(),
        "canvas": { "w": w, "h": h, "mp": round4((w as f64 * h as f64) / 1e6), "bytesRgba": px * 4, "kind": prep_kind },
        "baseline": { "wsMB": mb(baseline.ws), "commitMB": mb(baseline.commit) },
        "peaks": {
            "kernelPeakWsMB": mb(rss::counters().peak_ws),
            "kernelPeakCommitMB": mb(rss::counters().peak_commit),
            "sampledPeakWsMB": mb(peak_ws_sampled),
            "sampledPeakCommitMB": mb(peak_sampled),
            "samples": samples.len(),
            "intervalMs": interval,
        },
        "stages": stages,
        "outputs": {
            "jpegBytes": jpeg.len(), "jpegSubsampling": jpeg_facts(&jpeg)?["subsampling"].clone(),
            "pngBytes": png.len(),
            "jpegDecodeDims": [jw, jh], "pngDecodeDims": [pw, ph],
            "wroteFiles": write_files,
        },
        "totalEngineMs": round4(total_ms),
    });
    write_json(&out_json, &report)?;
    eprintln!("[e5] perf report {}", out_json.display());
    Ok(())
}

fn cmd_png_levels(args: &HashMap<String, String>) -> Result<(), String> {
    let src = req(args, "src")?;
    let w: u32 = opt(args, "w").unwrap_or("8192").parse().map_err(|_| "bad --w")?;
    let h: u32 = opt(args, "h").unwrap_or("6144").parse().map_err(|_| "bad --h")?;
    let out_json = PathBuf::from(req(args, "out")?);
    let levels: Vec<i32> = opt(args, "levels")
        .unwrap_or("9,6,3,1")
        .split(',')
        .filter_map(|s| s.trim().parse().ok())
        .collect();

    let sampler = rss::Sampler::start(20);
    let (mut rgba, prep_ms) = if src == "synthetic" {
        let t = std::time::Instant::now();
        let buf = synth_rgba(w, h);
        (buf, t.elapsed().as_secs_f64() * 1000.0)
    } else {
        prepare_photo(src, w, h)?
    };
    sampler.mark("prepare");
    let base = rss::counters();
    let mut rows = Vec::new();
    for (i, level) in levels.iter().enumerate() {
        let t = std::time::Instant::now();
        let png = encode_png(&mut rgba, w, h, *level)?;
        let ms = t.elapsed().as_secs_f64() * 1000.0;
        sampler.mark(&format!("level{level}"));
        rows.push(json!({ "level": level, "ms": round4(ms), "bytes": png.len() }));
        drop(png);
        let _ = i;
    }
    let (samples, marks, interval) = sampler.finish();
    let mut peak_after_prepare = 0u64;
    if let Some((_, t0)) = marks.first() {
        for s in &samples {
            if s.t_ms >= *t0 {
                peak_after_prepare = peak_after_prepare.max(s.commit);
            }
        }
    }
    let report = json!({
        "command": "png-levels",
        "generatedAt": now_iso(),
        "source": src,
        "canvas": { "w": w, "h": h, "bytesRgba": (w as usize * h as usize * 4) },
        "prepareMs": round4(prep_ms),
        "afterPrepare": { "wsMB": mb(base.ws), "commitMB": mb(base.commit) },
        "levels": rows,
        "peakCommitAfterPrepareMB": mb(peak_after_prepare),
        "samples": samples.len(),
        "intervalMs": interval,
    });
    write_json(&out_json, &report)?;
    eprintln!("[e5] png-levels report {}", out_json.display());
    Ok(())
}

fn mb(bytes: u64) -> f64 {
    (bytes as f64 / (1024.0 * 1024.0) * 10.0).round() / 10.0
}

// ---------------------------------------------------------------------------
// selftest — every comparator must be able to go red
// ---------------------------------------------------------------------------

fn cmd_selftest(args: &HashMap<String, String>) -> Result<(), String> {
    let out_json = PathBuf::from(req(args, "out")?);
    let mut checks: Vec<Value> = Vec::new();

    let mut push = |name: &str, pass: bool, detail: Value| {
        checks.push(json!({ "check": name, "pass": pass, "detail": detail }));
    };

    // sha1 known vector
    push("sha1(abc)", sha1::hex(b"abc") == "a9993e364706816aba3e25717850c26c9cd0d89d", json!(sha1::hex(b"abc")));

    // comparator: identical -> green, one-byte flip -> red
    let a = vec![10u8, 20, 30, 255, 40, 50, 60, 255];
    let mut b = a.clone();
    let identical = compare_rgba(&a, &b)?;
    b[4] = 41;
    let flipped = compare_rgba(&a, &b)?;
    push(
        "compare detects identical",
        identical.max == 0 && identical.diff_px == 0,
        json!({ "max": identical.max, "diffPx": identical.diff_px }),
    );
    push(
        "compare detects one-byte flip",
        flipped.max == 1 && flipped.diff_px == 1 && flipped.total_px == 2,
        json!({ "max": flipped.max, "diffPx": flipped.diff_px }),
    );

    // PNG round-trip is lossless; JPEG q100 4:4:4 reports its sampling factors
    let mut rgba = vec![0u8; 64 * 64 * 4];
    for (i, px) in rgba.chunks_exact_mut(4).enumerate() {
        px[0] = (i % 61) as u8 * 4;
        px[1] = (i % 37) as u8 * 6;
        px[2] = (i % 23) as u8 * 10;
        px[3] = 255;
    }
    let png = encode_png(&mut rgba, 64, 64, 9)?;
    let (png_dec, _, _) = decode_rgba(&png)?;
    let png_diff = compare_rgba(&png_dec, &rgba)?;
    push("png round-trip lossless", png_diff.max == 0, json!({ "max": png_diff.max }));

    let jpeg = encode_jpeg(&mut rgba, 64, 64, 100, jpeg_encoder::Downsample::No)?;
    let facts = jpeg_facts(&jpeg)?;
    push("jpeg q100 is 4:4:4", facts["subsampling"] == "4:4:4", facts);
    let jpeg420 = encode_jpeg(&mut rgba, 64, 64, 85, jpeg_encoder::Downsample::BothDirections)?;
    let facts420 = jpeg_facts(&jpeg420)?;
    push("jpeg q85 explicit 4:2:0 reads as 4:2:0", facts420["subsampling"] == "4:2:0", facts420);

    // EXIF round trip: dump -> load -> same text; corrupt -> differs (red proof)
    let mut block = exif::Block::default();
    block.set_ascii(exif::Ifd::Ifd0, 0x010F, "FrameMarkTest");
    block.set_ascii(exif::Ifd::Ifd0, 0x0110, "ModelX");
    block.set_u16(exif::Ifd::Ifd0, 0x0112, 1);
    block.set_rational(exif::Ifd::Exif, 0xA405, 3500, 100);
    let tiff = exif::dump(&block);
    let jpeg_with_exif = jpegmeta::insert_exif(&jpeg, &tiff)?;
    let rt = block.get_ascii(exif::Ifd::Ifd0, 0x010F);
    let rt2 = exif::load(&jpeg_with_exif)?.get_ascii(exif::Ifd::Ifd0, 0x010F);
    push(
        "exif dump/insert/load round-trip",
        rt.as_deref() == Some("FrameMarkTest") && rt2.as_deref() == Some("FrameMarkTest"),
        json!({ "dump": rt, "load": rt2, "tiffBytes": tiff.len() }),
    );
    let mut tampered = jpeg_with_exif.clone();
    if let Some(pos) = find_subslice(&tampered, b"FrameMarkTest") {
        tampered[pos] = b'X';
    }
    let tampered_read = exif::load(&tampered)?.get_ascii(exif::Ifd::Ifd0, 0x010F);
    push(
        "exif reader sees a tampered value (red proof)",
        tampered_read.as_deref() == Some("XrameMarkTest"),
        json!({ "read": tampered_read }),
    );

    // GPS strip proof
    let mut g = block.clone();
    add_synthetic_gps(&mut g);
    let gps_jpeg = jpegmeta::insert_exif(&jpeg, &exif::dump(&g))?;
    let kept = exif::load(&gps_jpeg)?.gps.len();
    let mut s = exif::load(&gps_jpeg)?;
    exif::prepare_export(&mut s, 64, 64, true);
    let stripped_jpeg = jpegmeta::insert_exif(&jpeg, &exif::dump(&s))?;
    let stripped = exif::load(&stripped_jpeg)?.gps.len();
    push(
        "gps kept then stripped",
        kept > 0 && stripped == 0,
        json!({ "kept": kept, "stripped": stripped }),
    );

    let all_pass = checks.iter().all(|c| c["pass"].as_bool().unwrap_or(false));
    let report = json!({ "command": "selftest", "pass": all_pass, "checks": checks });
    write_json(&out_json, &report)?;
    eprintln!("[e5] selftest pass={all_pass} -> {}", out_json.display());
    if !all_pass {
        return Err("selftest failed".into());
    }
    Ok(())
}

fn find_subslice(hay: &[u8], needle: &[u8]) -> Option<usize> {
    hay.windows(needle.len()).position(|w| w == needle)
}
