//! Process memory sampler (Windows): current/peak working set (RSS analogue) and
//! current/peak private commit. The kernel-side peak counters are exact; the
//! sampled series is used only to attribute peaks to pipeline stages.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};
use windows::Win32::System::ProcessStatus::{K32GetProcessMemoryInfo, PROCESS_MEMORY_COUNTERS};
use windows::Win32::System::Threading::GetCurrentProcess;

#[derive(Clone, Debug, Default)]
pub struct Sample {
    pub t_ms: u128,
    pub ws: u64,
    pub peak_ws: u64,
    pub commit: u64,
    pub peak_commit: u64,
}

pub fn counters() -> Sample {
    unsafe {
        let mut c = PROCESS_MEMORY_COUNTERS::default();
        let ok = K32GetProcessMemoryInfo(
            GetCurrentProcess(),
            &mut c,
            std::mem::size_of::<PROCESS_MEMORY_COUNTERS>() as u32,
        );
        if !ok.as_bool() {
            return Sample::default();
        }
        Sample {
            t_ms: 0,
            ws: c.WorkingSetSize as u64,
            peak_ws: c.PeakWorkingSetSize as u64,
            commit: c.PagefileUsage as u64,
            peak_commit: c.PeakPagefileUsage as u64,
        }
    }
}

pub struct Sampler {
    stop: Arc<AtomicBool>,
    samples: Arc<Mutex<Vec<Sample>>>,
    marks: Arc<Mutex<Vec<(String, u128)>>>,
    join: Option<JoinHandle<()>>,
    t0: Instant,
    interval: Duration,
}

impl Sampler {
    pub fn start(interval_ms: u64) -> Self {
        let stop = Arc::new(AtomicBool::new(false));
        let samples = Arc::new(Mutex::new(Vec::new()));
        let marks = Arc::new(Mutex::new(Vec::new()));
        let t0 = Instant::now();
        let interval = Duration::from_millis(interval_ms.max(1));
        let join = {
            let stop = stop.clone();
            let samples = samples.clone();
            let t0 = t0;
            std::thread::spawn(move || {
                let mut last = u128::MAX;
                while !stop.load(Ordering::Relaxed) {
                    let mut s = counters();
                    let t = t0.elapsed().as_millis();
                    if t != last {
                        s.t_ms = t;
                        last = t;
                        samples.lock().unwrap().push(s);
                    }
                    std::thread::sleep(interval);
                }
            })
        };
        Sampler { stop, samples, marks, join: Some(join), t0, interval }
    }

    pub fn mark(&self, name: &str) {
        self.marks
            .lock()
            .unwrap()
            .push((name.to_string(), self.t0.elapsed().as_millis()));
    }

    /// Stop sampling and return (samples, marks, interval_ms).
    pub fn finish(mut self) -> (Vec<Sample>, Vec<(String, u128)>, u64) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(j) = self.join.take() {
            let _ = j.join();
        }
        let samples = self.samples.lock().unwrap().clone();
        let marks = self.marks.lock().unwrap().clone();
        (samples, marks, self.interval.as_millis() as u64)
    }
}

pub struct WindowPeak {
    pub peak_ws: u64,
    pub peak_commit: u64,
}

/// Peak of the sampled series inside [start_ms, end_ms).
pub fn window_peak(samples: &[Sample], start_ms: u128, end_ms: u128) -> WindowPeak {
    let mut p = WindowPeak { peak_ws: 0, peak_commit: 0 };
    for s in samples {
        if s.t_ms >= start_ms && s.t_ms < end_ms {
            p.peak_ws = p.peak_ws.max(s.ws);
            p.peak_commit = p.peak_commit.max(s.commit);
        }
    }
    p
}
