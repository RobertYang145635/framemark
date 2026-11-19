//! GPU-preview policy: the three-state switch, the once-per-process probe cache
//! and the CPU retry decision.
//!
//! Everything here is deliberately free of Skia/EGL calls so it stays unit
//! testable on the host where no GL context can exist. The backend glue lives in
//! [`crate::gpu`] and [`crate::egl`].
//!
//! Contract implemented here (X1 GPU preview):
//! * preview = GPU raster, **export stays CPU** — a render is export-class when the
//!   caller asks for the full-resolution frame (`max_edge <= 0`) or is not the
//!   interactive preview tier (`preview_source_edge <= 0`, which is what
//!   `render_card_rgba`/the export JNI hard-code).
//! * `FM_GPU_PREVIEW=auto|off|force` (default `auto`). `auto` uses the GPU only
//!   after the probe succeeded; `force` tries even when the probe failed once.
//! * any GPU-level failure disables the GPU for the rest of the process in `auto`
//!   mode, so a driver that cannot do Ganesh/GL costs one retried frame instead of
//!   one retry per card.

use std::sync::atomic::{AtomicI8, Ordering};
use std::sync::OnceLock;

/// Environment variable read on every render (cheap; lets QA flip it per process).
pub const ENV: &str = "FM_GPU_PREVIEW";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// Probe once, use the GPU when the probe worked.
    Auto,
    /// Never touch the GPU: byte-for-byte the pre-GPU renderer.
    Off,
    /// Try the GPU even if the probe failed (debug/evidence only).
    Force,
}

impl Mode {
    /// `auto` when the value is empty (an exported-but-empty var behaves like unset);
    /// unknown values are rejected so the caller can log a note and keep `auto`.
    pub fn parse(raw: &str) -> Option<Mode> {
        match raw.trim().to_ascii_lowercase().as_str() {
            "" | "auto" => Some(Mode::Auto),
            "off" | "cpu" | "no" | "0" | "false" => Some(Mode::Off),
            "force" | "gpu" | "on" | "yes" | "1" | "true" => Some(Mode::Force),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Mode::Auto => "auto",
            Mode::Off => "off",
            Mode::Force => "force",
        }
    }
}

/// Outcome of the once-per-process Ganesh/EGL probe.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Probe {
    Unknown,
    Ok,
    Failed,
}

/// The decision for one render plus the reason (also used in the returned note).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Decision {
    pub use_gpu: bool,
    pub reason: &'static str,
}

/// Export-class render: full-resolution request (`max_edge <= 0`) or a caller that
/// is not the interactive preview tier (`preview_source_edge <= 0`). Both the RGBA
/// JNI (export) and the QA batch renderer land here.
pub fn is_export_path(max_edge: i32, preview_source_edge: i32) -> bool {
    max_edge <= 0 || preview_source_edge <= 0
}

/// The whole policy in one pure function.
///
/// `backend` is [`crate::egl::available`]: host targets have no GL bootstrap, so the
/// GPU branch is unreachable there no matter what the switch says.
pub fn decide(mode: Mode, export: bool, probe: Probe, backend: bool) -> Decision {
    if export {
        return Decision {
            use_gpu: false,
            reason: "export",
        };
    }
    if !backend {
        return Decision {
            use_gpu: false,
            reason: "no-gl-backend",
        };
    }
    match mode {
        Mode::Off => Decision {
            use_gpu: false,
            reason: "off",
        },
        // `force` is the escape hatch for a probe that failed for a transient
        // reason (e.g. another context was current): the retry still falls back.
        Mode::Force => Decision {
            use_gpu: true,
            reason: "force",
        },
        Mode::Auto => match probe {
            Probe::Failed => Decision {
                use_gpu: false,
                reason: "probe-failed",
            },
            Probe::Unknown | Probe::Ok => Decision {
                use_gpu: true,
                reason: "auto",
            },
        },
    }
}

// 0 = unset, 1 = auto, 2 = off, 3 = force.
static MODE_OVERRIDE: AtomicI8 = AtomicI8::new(0);
static PROBE_STATE: AtomicI8 = AtomicI8::new(0); // 0 unknown, 1 ok, 2 failed
static PROBE_REASON: OnceLock<String> = OnceLock::new();

/// Process-wide override (the `fm_hand_set_gpu_mode` C entry point). Takes
/// precedence over the environment so a future JNI parameter can win without
/// another ABI change.
pub fn set_override(mode: Option<Mode>) {
    let raw = match mode {
        Some(Mode::Auto) => 1,
        Some(Mode::Off) => 2,
        Some(Mode::Force) => 3,
        None => 0,
    };
    MODE_OVERRIDE.store(raw, Ordering::Relaxed);
}

pub fn current_mode() -> Mode {
    match MODE_OVERRIDE.load(Ordering::Relaxed) {
        2 => Mode::Off,
        3 => Mode::Force,
        1 => Mode::Auto,
        _ => std::env::var(ENV)
            .ok()
            .and_then(|raw| Mode::parse(&raw))
            .unwrap_or(Mode::Auto),
    }
}

pub fn probe_state() -> Probe {
    match PROBE_STATE.load(Ordering::Relaxed) {
        1 => Probe::Ok,
        2 => Probe::Failed,
        _ => Probe::Unknown,
    }
}

/// Records a successful probe. Returns `true` on the first success, which is what
/// the renderer uses to log the driver strings exactly once per process.
pub fn record_probe_ok() -> bool {
    PROBE_STATE
        .compare_exchange(0, 1, Ordering::Relaxed, Ordering::Relaxed)
        .is_ok()
}

/// Records a GPU-level failure. In `auto` mode this disables the GPU for the rest
/// of the process (the frame that failed is retried on the CPU by the caller).
pub fn record_probe_failed(reason: &str) {
    PROBE_STATE.store(2, Ordering::Relaxed);
    let _ = PROBE_REASON.set(reason.to_string());
}

pub fn probe_reason() -> String {
    PROBE_REASON.get().cloned().unwrap_or_default()
}

/// Human-readable one-liner for logcat / the render note.
pub fn describe() -> String {
    let probe = match probe_state() {
        Probe::Unknown => "unknown".to_string(),
        Probe::Ok => "ok".to_string(),
        Probe::Failed => format!("failed({})", probe_reason()),
    };
    format!(
        "mode={} backend={} probe={}",
        current_mode().as_str(),
        crate::egl::available(),
        probe
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The whole point of the feature: an export render never enters the GPU
    /// branch, not even under `force`.
    #[test]
    fn export_path_never_uses_the_gpu() {
        for mode in [Mode::Auto, Mode::Off, Mode::Force] {
            for probe in [Probe::Unknown, Probe::Ok, Probe::Failed] {
                let d = decide(mode, true, probe, true);
                assert!(!d.use_gpu, "export went to the GPU with {mode:?}/{probe:?}");
                assert_eq!(d.reason, "export");
            }
        }
    }

    /// `FM_GPU_PREVIEW=off` must reproduce the pre-GPU renderer: CPU every time.
    #[test]
    fn off_mode_stays_on_the_cpu() {
        for probe in [Probe::Unknown, Probe::Ok, Probe::Failed] {
            let d = decide(Mode::Off, false, probe, true);
            assert!(!d.use_gpu);
            assert_eq!(d.reason, "off");
        }
        assert_eq!(Mode::parse("off"), Some(Mode::Off));
        assert_eq!(Mode::parse(" CPU "), Some(Mode::Off));
        assert_eq!(Mode::parse("0"), Some(Mode::Off));
    }

    /// A failed probe (EGL init, GLES2 context, GrDirectContext → None) means the
    /// next renders go straight to the CPU instead of paying the probe per card.
    #[test]
    fn failed_probe_falls_back_to_the_cpu() {
        let d = decide(Mode::Auto, false, Probe::Failed, true);
        assert!(!d.use_gpu);
        assert_eq!(d.reason, "probe-failed");

        // auto before the first probe still tries once…
        assert!(decide(Mode::Auto, false, Probe::Unknown, true).use_gpu);
        // …and keeps using the GPU once the probe is known good.
        assert!(decide(Mode::Auto, false, Probe::Ok, true).use_gpu);
        // `force` ignores the cached failure so a transient error can be retried.
        assert!(decide(Mode::Force, false, Probe::Failed, true).use_gpu);
    }

    /// Host targets (and any future non-GL build) never select the GPU branch.
    #[test]
    fn missing_backend_always_reports_cpu() {
        for mode in [Mode::Auto, Mode::Force] {
            let d = decide(mode, false, Probe::Ok, false);
            assert!(!d.use_gpu);
            assert_eq!(d.reason, "no-gl-backend");
        }
    }

    /// Preview vs export is decided by the two edges, not by the caller's mood.
    #[test]
    fn export_detection_matches_the_jni_contract() {
        // Export JNI (fm_render_hand_rgba): full resolution, no preview tier.
        assert!(is_export_path(0, 0));
        // Export hand PNG path with `edge == 0` → the caller resolves the native
        // long edge, so the tier flag is what still marks it as export-class.
        assert!(is_export_path(2667, 0));
        // Interactive preview tier.
        assert!(!is_export_path(1080, 2160));
        // Defensive: a negative/garbage edge stays on the CPU.
        assert!(is_export_path(-1, 1080));
    }

    /// One failed frame must not be retried per card: the failure is cached
    /// process-wide, so every later `auto` render goes straight to the CPU.
    #[test]
    fn one_failure_disables_the_gpu_for_the_rest_of_the_process() {
        record_probe_failed("gpu:render-target-none 1080x810");
        assert_eq!(probe_state(), Probe::Failed);
        assert!(probe_reason().contains("render-target-none"));

        let d = decide(Mode::Auto, false, probe_state(), true);
        assert!(!d.use_gpu);
        assert_eq!(d.reason, "probe-failed");

        // A later good frame cannot resurrect `auto` by accident either: the cache
        // only flips 0 → ok, and a failure already recorded stays authoritative.
        record_probe_ok();
        assert_eq!(probe_state(), Probe::Failed);
    }

    #[test]
    fn mode_parsing_is_lenient_but_not_silent() {        assert_eq!(Mode::parse("auto"), Some(Mode::Auto));
        assert_eq!(Mode::parse(""), Some(Mode::Auto));
        assert_eq!(Mode::parse("force"), Some(Mode::Force));
        assert_eq!(Mode::parse("ON"), Some(Mode::Force));
        assert_eq!(Mode::parse("enable-gpu-please"), None);
        assert_eq!(Mode::parse("auto").unwrap().as_str(), "auto");
    }

    #[test]
    fn override_beats_the_environment() {
        // The C entry point (`fm_hand_set_gpu_mode`) is the JNI-side switch; it must
        // win so a future Kotlin parameter needs no new ABI.
        set_override(Some(Mode::Off));
        assert_eq!(current_mode(), Mode::Off);
        set_override(Some(Mode::Force));
        assert_eq!(current_mode(), Mode::Force);
        set_override(None);
        // Falls back to the environment (unset in a normal test run) → auto.
        let from_env = std::env::var(ENV)
            .ok()
            .and_then(|raw| Mode::parse(&raw))
            .unwrap_or(Mode::Auto);
        assert_eq!(current_mode(), from_env);
    }
}
