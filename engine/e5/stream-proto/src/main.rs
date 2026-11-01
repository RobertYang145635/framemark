//! e5-stream — row-fed JPEG in one process (parity + memory bench).
//!
//! Modes:
//!   rgba        one full-size RGBA buffer + row-fed encode
//!   procedural  no image buffer; rows are generated straight into the encoder
//!
//! Usage:
//!   e5-stream --mode rgba --input raw:D:\...\canvas.rgba --w 1200 --h 800 ...
//!   e5-stream --mode rgba --input synthetic --w 8192 --h 6144 ...
//!   e5-stream --mode procedural --input synthetic --w 8192 --h 6144 ...

use fm_e5_stream::enc::{encode_rows, synth_row, Dest, SYNTH_SEED};
use fm_e5_stream::{jpeginfo, rss, sha1};

use serde_json::json;
use std::collections::HashMap;
use std::path::PathBuf;

fn main() {
    if let Err(e) = run() {
        eprintln!("E5-STREAM-ERROR: {e}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let mut it = std::env::args().skip(1);
    let mut args: HashMap<String, String> = HashMap::new();
    while let Some(k) = it.next() {
        if !k.starts_with("--") {
            return Err(format!("unexpected argument {k}"));
        }
        let v = it.next().ok_or_else(|| format!("missing value for {k}"))?;
        args.insert(k.trim_start_matches("--").to_string(), v);
    }
    let req = |k: &str| -> Result<&str, String> {
        args.get(k).map(|s| s.as_str()).ok_or_else(|| format!("missing --{k}"))
    };
    let opt = |k: &str| args.get(k).map(|s| s.as_str());

    let mode = opt("mode").unwrap_or("rgba");
    let input = opt("input").unwrap_or("synthetic");
    let w: u32 = req("w")?.parse().map_err(|_| "bad --w")?;
    let h: u32 = req("h")?.parse().map_err(|_| "bad --h")?;
    let quality: u32 = opt("quality").unwrap_or("100").parse().map_err(|_| "bad --quality")?;
    let optimize = opt("optimize").map(|v| v == "1" || v == "true").unwrap_or(true);
    let out_json = PathBuf::from(req("out")?);
    let write_jpeg = opt("write").map(PathBuf::from);

    let sampler = rss::Sampler::start(20);
    let baseline = rss::counters();
    sampler.mark("baseline");

    let mut prep_ms = 0.0;
    let mut rgba: Option<Vec<u8>> = None;
    if mode != "procedural" {
        let t = std::time::Instant::now();
        match input.strip_prefix("raw:") {
            Some(path) => {
                let buf = std::fs::read(path).map_err(|e| format!("read {path}: {e}"))?;
                if buf.len() < (w as usize) * (h as usize) * 4 {
                    return Err(format!("raw buffer too small: {} bytes", buf.len()));
                }
                rgba = Some(buf);
            }
            None => {
                rgba = Some(synth_rgba(w, h));
            }
        }
        prep_ms = t.elapsed().as_secs_f64() * 1000.0;
    }
    sampler.mark("prepare");

    let dest = match &write_jpeg {
        Some(p) => Dest::File(p),
        None => Dest::Mem,
    };
    let t = std::time::Instant::now();
    let encoded = match mode {
        "procedural" => {
            let mut seed = SYNTH_SEED;
            encode_rows(w, h, quality, optimize, None, None, &dest, |y, row| {
                if y == 0 {
                    seed = SYNTH_SEED;
                }
                synth_row(y as usize, w as usize, h as usize, &mut seed, row);
            })?
        }
        _ => {
            let buf = rgba.as_ref().ok_or("rgba mode needs a buffer")?;
            let stride = w as usize * 4;
            encode_rows(w, h, quality, optimize, None, None, &dest, |y, row| {
                let src = &buf[y as usize * stride..(y as usize + 1) * stride];
                for (i, px) in src.chunks_exact(4).enumerate() {
                    row[i * 3] = px[0];
                    row[i * 3 + 1] = px[1];
                    row[i * 3 + 2] = px[2];
                }
            })?
        }
    };
    let encode_ms = t.elapsed().as_secs_f64() * 1000.0;
    sampler.mark("encode");

    let bytes = if encoded.bytes.is_empty() {
        std::fs::read(write_jpeg.as_ref().unwrap()).map_err(|e| format!("read back: {e}"))?
    } else {
        encoded.bytes.clone()
    };
    let info = jpeginfo::parse(&bytes)?;
    let (samples, marks, interval) = sampler.finish();
    let peak_commit = samples.iter().map(|s| s.commit).max().unwrap_or(0);
    let peak_ws = samples.iter().map(|s| s.ws).max().unwrap_or(0);
    let mut stages = Vec::new();
    for i in 0..marks.len() {
        let start = marks[i].1;
        let end = marks.get(i + 1).map(|m| m.1).unwrap_or(u128::MAX);
        let win = rss::window_peak(&samples, start, end);
        stages.push(json!({
            "stage": marks[i].0,
            "peakCommitMB": mb(win.peak_commit),
            "peakWsMB": mb(win.peak_ws),
        }));
    }

    let report = json!({
        "command": "stream",
        "mode": mode,
        "input": input,
        "encoder": "mozjpeg-sys 2.2.3 (scalar, no SIMD/nasm)",
        "quality": quality,
        "optimizeCoding": optimize,
        "canvas": { "w": w, "h": h, "mp": round4((w as f64 * h as f64) / 1e6), "bytesRgba": (w as usize * h as usize * 4) },
        "baseline": { "wsMB": mb(baseline.ws), "commitMB": mb(baseline.commit) },
        "ms": { "prepare": round4(prep_ms), "encode": round4(encode_ms), "total": round4(prep_ms + encode_ms) },
        "peaks": {
            "kernelPeakCommitMB": mb(rss::counters().peak_commit),
            "kernelPeakWsMB": mb(rss::counters().peak_ws),
            "sampledPeakCommitMB": mb(peak_commit),
            "sampledPeakWsMB": mb(peak_ws),
            "samples": samples.len(),
            "intervalMs": interval,
        },
        "stages": stages,
        "output": {
            "bytes": bytes.len(), "sha1": sha1::hex(&bytes),
            "w": info.w, "h": info.h, "subsampling": info.subsampling,
            "dqtAllOnes": info.dqt_all_ones,
            "scanBytes": info.scan_bytes, "scanSha1": info.scan_sha1,
            "hasApp0": info.has_app0, "hasIcc": info.has_icc,
            "rowsFed": encoded.rows_fed,
        },
    });
    if let Some(parent) = out_json.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("mkdir: {e}"))?;
    }
    std::fs::write(&out_json, serde_json::to_vec_pretty(&report).map_err(|e| e.to_string())?)
        .map_err(|e| format!("write {}: {e}", out_json.display()))?;
    eprintln!("[e5-stream] {}", out_json.display());
    Ok(())
}

fn synth_rgba(w: u32, h: u32) -> Vec<u8> {
    let mut buf = vec![0u8; w as usize * h as usize * 4];
    let mut seed = SYNTH_SEED;
    let mut row = vec![0u8; w as usize * 3];
    for y in 0..h as usize {
        synth_row(y, w as usize, h as usize, &mut seed, &mut row);
        let o = y * w as usize * 4;
        for x in 0..w as usize {
            buf[o + x * 4] = row[x * 3];
            buf[o + x * 4 + 1] = row[x * 3 + 1];
            buf[o + x * 4 + 2] = row[x * 3 + 2];
            buf[o + x * 4 + 3] = 255;
        }
    }
    buf
}

fn round4(v: f64) -> f64 {
    (v * 10000.0).round() / 10000.0
}

fn mb(bytes: u64) -> f64 {
    (bytes as f64 / (1024.0 * 1024.0) * 10.0).round() / 10.0
}
