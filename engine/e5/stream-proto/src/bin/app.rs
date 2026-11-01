//! e5-app — app-side of the D-028 worker design.
//!
//! Holds at most one full-size RGBA canvas, feeds RGB rows over a pipe to a
//! separate worker process, and samples BOTH processes so the report can state a
//! system peak (app + worker), not a single-process number.
//!
//! Usage:
//!   e5-app --mode rgba|procedural --w 8192 --h 6144 --quality 100
//!          --worker <path e5-worker.exe> --out <jpg> --report <json>

use fm_e5_stream::enc::{synth_row, SYNTH_SEED};
use fm_e5_stream::rss;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::Write;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use windows::Win32::Foundation::HANDLE;
use windows::Win32::System::ProcessStatus::{K32GetProcessMemoryInfo, PROCESS_MEMORY_COUNTERS};
use windows::Win32::System::Threading::{
    GetCurrentProcess, OpenProcess, PROCESS_QUERY_INFORMATION, PROCESS_VM_READ,
};

#[derive(Clone, Copy)]
struct Counters {
    ws: u64,
    commit: u64,
    peak_ws: u64,
    peak_commit: u64,
}

fn read_counters(h: HANDLE) -> Option<Counters> {
    // NOTE: GetCurrentProcess() is the pseudo-handle -1, which `is_invalid()`
    // rejects; only a null handle is unusable here.
    if h.0.is_null() {
        return None;
    }
    unsafe {
        let mut c = PROCESS_MEMORY_COUNTERS::default();
        let ok = K32GetProcessMemoryInfo(
            h,
            &mut c,
            std::mem::size_of::<PROCESS_MEMORY_COUNTERS>() as u32,
        );
        if !ok.as_bool() {
            return None;
        }
        Some(Counters {
            ws: c.WorkingSetSize as u64,
            commit: c.PagefileUsage as u64,
            peak_ws: c.PeakWorkingSetSize as u64,
            peak_commit: c.PeakPagefileUsage as u64,
        })
    }
}

struct Sample {
    t_ms: u128,
    app_ws: u64,
    app_commit: u64,
    child_ws: u64,
    child_commit: u64,
    child_seen: bool,
}

fn main() {
    if let Err(e) = run() {
        eprintln!("E5-APP-ERROR: {e}");
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
    let w: u32 = req("w")?.parse().map_err(|_| "bad --w")?;
    let h: u32 = req("h")?.parse().map_err(|_| "bad --h")?;
    let quality: u32 = opt("quality").unwrap_or("100").parse().map_err(|_| "bad --quality")?;
    let optimize = opt("optimize").map(|v| v == "1" || v == "true").unwrap_or(false);
    let worker_exe = req("worker")?.to_string();
    let out = PathBuf::from(req("out")?);
    let report_path = PathBuf::from(req("report")?);
    let worker_report = report_path.with_extension("worker.json");
    if let Some(parent) = out.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("mkdir: {e}"))?;
    }

    let baseline = rss::counters();
    let t_alloc = std::time::Instant::now();
    let rgba: Option<Vec<u8>> = if mode == "procedural" {
        None
    } else {
        match opt("input").unwrap_or("synthetic").strip_prefix("rgba:") {
            Some(path) => {
                let buf = std::fs::read(path).map_err(|e| format!("read {path}: {e}"))?;
                if buf.len() < (w as usize) * (h as usize) * 4 {
                    return Err(format!("rgba buffer too small: {} bytes", buf.len()));
                }
                Some(buf)
            }
            None => Some(synth_rgba(w, h)),
        }
    };
    let alloc_ms = t_alloc.elapsed().as_secs_f64() * 1000.0;

    // Spawn the worker (separate process: the encoder's bookkeeping never counts
    // against the app's memory).
    let t_spawn = std::time::Instant::now();
    let mut worker_args: Vec<String> = vec![
        "--w".into(), w.to_string(),
        "--h".into(), h.to_string(),
        "--quality".into(), quality.to_string(),
        "--optimize".into(), if optimize { "1" } else { "0" }.into(),
        "--out".into(), out.to_string_lossy().into_owned(),
        "--report".into(), worker_report.to_string_lossy().into_owned(),
    ];
    if let Some(exif_path) = opt("exif-tiff") {
        worker_args.push("--exif-tiff".into());
        worker_args.push(exif_path.to_string());
    }
    if let Some(fmt) = opt("format") {
        worker_args.push("--format".into());
        worker_args.push(fmt.to_string());
    }
    if let Some(l) = opt("webp-lossless") {
        worker_args.push("--webp-lossless".into());
        worker_args.push(l.to_string());
    }
    if let Some(icc_path) = opt("icc") {
        worker_args.push("--icc".into());
        worker_args.push(icc_path.to_string());
    }
    let mut child = Command::new(&worker_exe)
        .args(&worker_args)
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::inherit())
        .spawn()
        .map_err(|e| format!("spawn {worker_exe}: {e}"))?;

    let child_pid = child.id();
    let child_handle = unsafe {
        OpenProcess(
            PROCESS_QUERY_INFORMATION | PROCESS_VM_READ,
            false,
            child_pid,
        )
    }
    .ok();

    let stop = Arc::new(AtomicBool::new(false));
    let samples: Arc<Mutex<Vec<Sample>>> = Arc::new(Mutex::new(Vec::new()));
    let sampler = {
        let stop = stop.clone();
        let samples = samples.clone();
        let child_raw: usize = child_handle.map(|h| h.0 as usize).unwrap_or(0);
        let t0 = std::time::Instant::now();
        std::thread::spawn(move || {
            while !stop.load(Ordering::Relaxed) {
                let app = read_counters(unsafe { GetCurrentProcess() });
                let child = if child_raw != 0 {
                    read_counters(HANDLE(child_raw as *mut _))
                } else {
                    None
                };
                if let Some(a) = app {
                    samples.lock().unwrap().push(Sample {
                        t_ms: t0.elapsed().as_millis(),
                        app_ws: a.ws,
                        app_commit: a.commit,
                        child_ws: child.map(|c| c.ws).unwrap_or(0),
                        child_commit: child.map(|c| c.commit).unwrap_or(0),
                        child_seen: child.is_some(),
                    });
                }
                std::thread::sleep(Duration::from_millis(20));
            }
        })
    };

    // Feed rows.
    let mut stdin = child.stdin.take().ok_or("worker stdin not piped")?;
    let mut row = vec![0u8; w as usize * 3];
    let mut seed = SYNTH_SEED;
    let t_pump = std::time::Instant::now();
    for y in 0..h as usize {
        match &rgba {
            Some(buf) => {
                let stride = w as usize * 4;
                let src = &buf[y * stride..(y + 1) * stride];
                for (i, px) in src.chunks_exact(4).enumerate() {
                    row[i * 3] = px[0];
                    row[i * 3 + 1] = px[1];
                    row[i * 3 + 2] = px[2];
                }
            }
            None => {
                if y == 0 {
                    seed = SYNTH_SEED;
                }
                synth_row(y, w as usize, h as usize, &mut seed, &mut row);
            }
        }
        stdin
            .write_all(&row)
            .map_err(|e| format!("pipe write at row {y}: {e}"))?;
    }
    drop(stdin);
    let pump_ms = t_pump.elapsed().as_secs_f64() * 1000.0;
    let status = child.wait().map_err(|e| format!("wait: {e}"))?;
    let wall_ms = t_spawn.elapsed().as_secs_f64() * 1000.0;
    stop.store(true, Ordering::Relaxed);
    let _ = sampler.join();
    let app_peaks = read_counters(unsafe { GetCurrentProcess() });
    let _ = rss::counters();

    if !status.success() {
        return Err(format!("worker exited with {status:?}"));
    }
    let worker: Value = serde_json::from_slice(
        &std::fs::read(&worker_report).map_err(|e| format!("worker report: {e}"))?,
    )
    .map_err(|e| format!("worker report json: {e}"))?;

    let samples = samples.lock().unwrap();
    let system_peak_ws = samples.iter().map(|s| s.app_ws + s.child_ws).max().unwrap_or(0);
    let system_peak_commit = samples
        .iter()
        .map(|s| s.app_commit + s.child_commit)
        .max()
        .unwrap_or(0);
    let child_seen = samples.iter().filter(|s| s.child_seen).count();

    let app_peak_commit = app_peaks.map(|c| c.peak_commit).unwrap_or(0);
    let app_peak_ws = app_peaks.map(|c| c.peak_ws).unwrap_or(0);
    let worker_peak_commit = worker["peaks"]["kernelPeakCommitMB"]
        .as_f64()
        .map(|mb| (mb * 1024.0 * 1024.0) as u64)
        .unwrap_or(0);
    let _worker_peak_ws = worker["peaks"]["kernelPeakWsMB"]
        .as_f64()
        .map(|mb| (mb * 1024.0 * 1024.0) as u64)
        .unwrap_or(0);

    let report = json!({
        "command": "app+worker",
        "mode": mode,
        "optimizeCoding": optimize,
        "policy": "D-028: pixel parity (G2), standard Huffman allowed, encoder in a separate process, G3 = system peak (app+worker)",
        "pids": { "app": std::process::id(), "worker": child_pid },
        "canvas": { "w": w, "h": h, "mp": round4((w as f64 * h as f64) / 1e6), "bytesRgba": (w as usize * h as usize * 4) },
        "baseline": { "wsMB": mb(baseline.ws), "commitMB": mb(baseline.commit) },
        "ms": {
            "alloc": round4(alloc_ms),
            "spawnToExit": round4(wall_ms),
            "pump": round4(pump_ms),
            "workerEncode": worker["ms"]["encode"].clone(),
            "appFirstRowToWorkerReady": round4(wall_ms - pump_ms),
        },
        "rowsFed": worker["rowsFed"].clone(),
        "systemPeak": {
            "sampledWsMB": mb(system_peak_ws),
            "sampledCommitMB": mb(system_peak_commit),
            "samples": samples.len(),
            "childSamples": child_seen,
            "upperBoundSumOfKernelPeaksMB": mb(app_peak_commit + worker_peak_commit),
        },
        "app": {
            "kernelPeakCommitMB": mb(app_peak_commit),
            "kernelPeakWsMB": mb(app_peak_ws),
        },
        "worker": {
            "report": worker_report.display().to_string(),
            "kernelPeakCommitMB": worker["peaks"]["kernelPeakCommitMB"].clone(),
            "kernelPeakWsMB": worker["peaks"]["kernelPeakWsMB"].clone(),
            "output": worker["output"].clone(),
        },
        "output": {
            "path": out.display().to_string(),
            "bytes": worker["output"]["bytes"].clone(),

        },
    });
    std::fs::write(&report_path, serde_json::to_vec_pretty(&report).map_err(|e| e.to_string())?)
        .map_err(|e| format!("write {}: {e}", report_path.display()))?;
    eprintln!(
        "[e5-app] system peak {:.1} MB (app {:.1} + worker {:.1}), wall {:.0} ms",
        mb(system_peak_commit),
        mb(app_peak_commit),
        mb(worker_peak_commit),
        wall_ms
    );
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
