//! e5-worker — separate-process JPEG encoder (D-028). Reusable binary.
//!
//! Protocol (see `_research/native/E5-INTEGRATION.md`):
//!   stdin   exactly `h` rows of `w * 3` bytes: RGB, top-to-bottom, no headers,
//!           no padding, 8 bits per sample. Extra trailing bytes are ignored.
//!   stdout  nothing (diagnostics go to stderr).
//!   --out   JPEG written directly (no in-memory output buffer).
//!   --report JSON with size/header facts and the worker's own memory peaks.
//!
//! Exit codes:
//!   0 success
//!   2 usage / argument / input-file error (ICC or EXIF block unreadable)
//!   3 stdin error or short read (fewer than h rows)
//!   4 encoder error (libjpeg rejected the data)
//!   5 output or report write error
//!
//! On a stdin error a partially written `--out` file is removed best-effort.

use fm_e5_stream::enc::{encode_rows, Dest};
use fm_e5_stream::{jpeginfo, rss};

use serde_json::json;
use std::collections::HashMap;
use std::io::Read;
use std::path::PathBuf;

const EXIT_USAGE: i32 = 2;
const EXIT_STDIN: i32 = 3;
const EXIT_ENCODE: i32 = 4;
const EXIT_IO: i32 = 5;

fn main() {
    if std::env::args().any(|a| a == "--version") {
        println!("e5-worker 0.2.0 (JPEG: {}; WebP: {})", fm_e5_stream::enc::ENCODER_ID, fm_e5_stream::webpenc::ENCODER_ID);
        return;
    }
    match run() {
        Ok(()) => {}
        Err((code, msg)) => {
            eprintln!("E5-WORKER-ERROR code={code}: {msg}");
            std::process::exit(code);
        }
    }
}

fn run() -> Result<(), (i32, String)> {
    let mut it = std::env::args().skip(1);
    let mut args: HashMap<String, String> = HashMap::new();
    while let Some(k) = it.next() {
        if !k.starts_with("--") {
            return Err((EXIT_USAGE, format!("unexpected argument {k}")));
        }
        let v = it.next().ok_or((EXIT_USAGE, format!("missing value for {k}")))?;
        args.insert(k.trim_start_matches("--").to_string(), v);
    }
    let req = |k: &str| -> Result<&str, (i32, String)> {
        args.get(k)
            .map(|s| s.as_str())
            .ok_or((EXIT_USAGE, format!("missing --{k}")))
    };
    let num = |k: &str, default: Option<u32>| -> Result<u32, (i32, String)> {
        match args.get(k) {
            Some(v) => v.parse().map_err(|_| (EXIT_USAGE, format!("bad --{k}: {v}"))),
            None => default.ok_or((EXIT_USAGE, format!("missing --{k}"))),
        }
    };

    let w = num("w", None)?;
    let h = num("h", None)?;
    let quality = num("quality", Some(100))?;
    let optimize = args.get("optimize").map(|v| v == "1" || v == "true").unwrap_or(false);
    let out = PathBuf::from(req("out")?);
    let report_path = PathBuf::from(req("report")?);

    // D-027 container: the 456 B sRGB profile, written as APP2 before the
    // scanlines (no second pass over the 42 MB file). Required for pixel parity:
    // an untagged JPEG decodes 1 level off the sRGB-tagged reference.
    let icc: Option<Vec<u8>> = match args.get("icc") {
        Some(p) => Some(
            std::fs::read(p).map_err(|e| (EXIT_USAGE, format!("read icc {p}: {e}")))?,
        ),
        None => None,
    };
    // Pre-built TIFF block (without the "Exif\0\0" prefix) from the app's EXIF
    // module; written as APP1 before the scanlines. Thumbnail included if the
    // app put it in the block.
    let exif_tiff: Option<Vec<u8>> = match args.get("exif-tiff") {
        Some(p) => Some(
            std::fs::read(p).map_err(|e| (EXIT_USAGE, format!("read exif-tiff {p}: {e}")))?,
        ),
        None => None,
    };

    let format = args.get("format").cloned().unwrap_or_else(|| "jpeg".into());
    if format.eq_ignore_ascii_case("webp") {
        let lossless = args.get("webp-lossless").map(|v| v != "0").unwrap_or(true);
        let q: f32 = args.get("quality").and_then(|v| v.parse().ok()).unwrap_or(100.0);
        return run_webp(w, h, q, lossless, icc, exif_tiff, out, report_path);
    }

    // Fail early with the IO code if the output cannot be created, instead of
    // letting libjpeg's fopen failure masquerade as an encoder error.
    if let Some(parent) = out.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| (EXIT_IO, format!("mkdir {}: {e}", parent.display())))?;
    }
    std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .open(&out)
        .map_err(|e| (EXIT_IO, format!("open output {}: {e}", out.display())))?;

    let baseline = rss::counters();
    let t = std::time::Instant::now();
    let mut stdin = std::io::stdin().lock();
    let row_len = w as usize * 3;
    let mut read_error: Option<String> = None;
    // Fill closure: one RGB row per call. encode_rows calls it exactly h times
    // (standard Huffman = one pass, no coefficient array).
    let encoded = encode_rows(
        w,
        h,
        quality,
        optimize,
        icc.as_deref(),
        exif_tiff.as_deref(),
        &Dest::File(&out),
        |_y, row| {
            if read_error.is_some() {
                return;
            }
            if let Err(e) = stdin.read_exact(&mut row[..row_len]) {
                read_error = Some(format!("stdin row read: {e}"));
            }
        },
    )
    .map_err(|e| (EXIT_ENCODE, e))?;
    if let Some(e) = read_error {
        let _ = std::fs::remove_file(&out);
        return Err((EXIT_STDIN, e));
    }
    let encode_ms = t.elapsed().as_secs_f64() * 1000.0;

    // Header-only read: the 48 MB output is never loaded back into this process.
    let bytes_len = std::fs::metadata(&out)
        .map(|m| m.len())
        .map_err(|e| (EXIT_IO, format!("stat {}: {e}", out.display())))?;
    let mut prefix = vec![0u8; 256 * 1024];
    let prefix_len = {
        let mut f = std::fs::File::open(&out)
            .map_err(|e| (EXIT_IO, format!("open {}: {e}", out.display())))?;
        let mut total = 0usize;
        loop {
            let n = f
                .read(&mut prefix[total..])
                .map_err(|e| (EXIT_IO, format!("read {}: {e}", out.display())))?;
            if n == 0 || total == prefix.len() {
                break;
            }
            total += n;
        }
        total
    };
    let info = jpeginfo::header(&prefix[..prefix_len]).map_err(|e| (EXIT_ENCODE, e))?;
    let peaks = rss::counters();

    let report = json!({
        "command": "worker",
        "protocolVersion": 1,
        "pid": std::process::id(),
        "encoder": fm_e5_stream::enc::ENCODER_ID,
        "canvas": { "w": w, "h": h, "mp": round4((w as f64 * h as f64) / 1e6) },
        "quality": quality,
        "optimizeCoding": optimize,
        "iccBytes": icc.as_ref().map(|v| v.len()).unwrap_or(0),
        "exifTiffBytes": exif_tiff.as_ref().map(|v| v.len()).unwrap_or(0),
        "baseline": { "wsMB": mb(baseline.ws), "commitMB": mb(baseline.commit) },
        "ms": { "encode": round4(encode_ms) },
        "rowsFed": encoded.rows_fed,
        "peaks": {
            "kernelPeakCommitMB": mb(peaks.peak_commit),
            "kernelPeakWsMB": mb(peaks.peak_ws),
        },
        "output": {
            "bytes": bytes_len,
            "w": info.w, "h": info.h, "subsampling": info.subsampling,
            "dqtAllOnes": info.dqt_all_ones,
            "hasApp0": info.has_app0, "hasIcc": info.has_icc,
            "sha1": null,
            "note": "worker reports header facts only; the caller hashes the file",
        },
    });
    if let Some(parent) = report_path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| (EXIT_IO, format!("mkdir: {e}")))?;
    }
    std::fs::write(&report_path, serde_json::to_vec_pretty(&report).map_err(|e| (EXIT_IO, e.to_string()))?)
        .map_err(|e| (EXIT_IO, format!("write {}: {e}", report_path.display())))?;
    eprintln!("[e5-worker] {} rows -> {}", encoded.rows_fed, out.display());
    Ok(())
}

fn round4(v: f64) -> f64 {
    (v * 10000.0).round() / 10000.0
}

fn mb(bytes: u64) -> f64 {
    (bytes as f64 / (1024.0 * 1024.0) * 10.0).round() / 10.0
}


fn run_webp(
    w: u32,
    h: u32,
    quality: f32,
    lossless: bool,
    icc: Option<Vec<u8>>,
    exif_tiff: Option<Vec<u8>>,
    out: PathBuf,
    report_path: PathBuf,
) -> Result<(), (i32, String)> {
    let baseline = rss::counters();
    let t = std::time::Instant::now();
    let row_len = w as usize * 3;
    // libwebp has no scanline API for stills: one RGB image lives here.
    let mut rgb = vec![0u8; row_len * h as usize];
    {
        use std::io::Read as _;
        let mut stdin = std::io::stdin().lock();
        for y in 0..h as usize {
            let off = y * row_len;
            stdin
                .read_exact(&mut rgb[off..off + row_len])
                .map_err(|e| (EXIT_STDIN, format!("stdin row {y}: {e}")))?;
        }
    }
    let exif_payload = exif_tiff.as_ref().map(|t| {
        let mut v = b"Exif  ".to_vec();
        v.extend_from_slice(t);
        v
    });
    let encoded = fm_e5_stream::webpenc::encode(
        &rgb,
        w,
        h,
        lossless,
        quality,
        icc.as_deref(),
        exif_payload.as_deref(),
    )
    .map_err(|e| (EXIT_ENCODE, e))?;
    let encode_ms = t.elapsed().as_secs_f64() * 1000.0;
    std::fs::write(&out, &encoded.bytes)
        .map_err(|e| (EXIT_IO, format!("write {}: {e}", out.display())))?;
    let peaks = rss::counters();
    let report = serde_json::json!({
        "command": "worker",
        "protocolVersion": 1,
        "format": if encoded.lossless { "webp-lossless" } else { "webp-lossy" },
        "webpLossless": encoded.lossless,
        "encoder": fm_e5_stream::webpenc::ENCODER_ID,
        "canvas": { "w": w, "h": h, "mp": round4((w as f64 * h as f64) / 1e6) },
        "quality": quality,
        "iccBytes": icc.as_ref().map(|v| v.len()).unwrap_or(0),
        "exifBytes": exif_payload.as_ref().map(|v| v.len()).unwrap_or(0),
        "baseline": { "wsMB": mb(baseline.ws), "commitMB": mb(baseline.commit) },
        "ms": { "encode": round4(encode_ms) },
        "rowsFed": h,
        "peaks": {
            "kernelPeakCommitMB": mb(peaks.peak_commit),
            "kernelPeakWsMB": mb(peaks.peak_ws),
        },
        "output": {
            "bytes": encoded.bytes.len(),
            "chunks": fm_e5_stream::webpenc::chunks(&encoded.bytes),
            "sha1": null,
        },
    });
    if let Some(parent) = report_path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| (EXIT_IO, format!("mkdir: {e}")))?;
    }
    std::fs::write(&report_path, serde_json::to_vec_pretty(&report).map_err(|e| (EXIT_IO, e.to_string()))?)
        .map_err(|e| (EXIT_IO, format!("write {}: {e}", report_path.display())))?;
    eprintln!("[e5-worker] webp {} rows -> {}", h, out.display());
    Ok(())
}