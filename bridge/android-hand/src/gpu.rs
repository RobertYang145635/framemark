//! Surface factory for the hand renderer: GPU (Ganesh + GL) when the preview path
//! asked for it, CPU raster everywhere else.
//!
//! `raster_surface(w, h)` keeps its pre-GPU signature on purpose — `canvas.rs` and
//! `draw.rs` (shared with the desktop engine) call it for every offscreen temp
//! canvas (blur, trilinear mips, backdrop, palette …), so the backend switch is
//! made here once instead of at each call site. `surface_for(info)` is the same
//! switch for callers that already built their own `ImageInfo` (the final canvas
//! in `render::draw_pass`, which needs `AlphaType::Opaque`).
//!
//! The GPU branch only exists while a Ganesh `GrDirectContext` is installed for
//! the current thread by [`run_preview`]; with no session every call is the plain
//! CPU raster path, byte-identical to the previous renderer.
//!
//! Before the first real frame every process runs [`probe_cached`]: a 4×4 render
//! target that is cleared, clipped, layered, flushed **and read back**. A driver
//! that cannot do Ganesh/GL then costs one self-test instead of a full-size frame
//! that has to be thrown away, and the probe result — including the readback pixel
//! value and any `glGetError()` — is logged as `e4.gpu.probe …` evidence.

use std::cell::{Cell, RefCell};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::OnceLock;

use skia_safe::gpu::{Budgeted, SurfaceOrigin};
use skia_safe::{surfaces, AlphaType, ColorSpace, ColorType, ImageInfo, Surface};

use crate::egl;

thread_local! {
    /// First GPU-level failure seen by this thread inside the current frame. The
    /// surface factory cannot return `Result` (its signature is frozen by the
    /// shared engine modules), so it records the reason here and the frame wrapper
    /// reads it after the pass to decide the CPU retry.
    static GPU_ERROR: RefCell<Option<String>> = RefCell::new(None);

    /// Whether this thread is currently inside [`run_preview`]. Only then may
    /// anything submit to the Ganesh context: a CPU render that happens to run on a
    /// thread which still caches a context must never touch GL (its flush would fail
    /// with "no current context" and, worse, be reported as a GPU defect).
    static FRAME_ACTIVE: Cell<bool> = Cell::new(false);
}

/// `true` while [`run_preview`] drives a GPU frame on this thread.
fn frame_active() -> bool {
    FRAME_ACTIVE.with(|flag| flag.get())
}

/// Clears [`FRAME_ACTIVE`] on every exit path (including a panic or an early `?`).
struct FrameScope;

impl FrameScope {
    fn begin() -> Self {
        FRAME_ACTIVE.with(|flag| flag.set(true));
        FrameScope
    }
}

impl Drop for FrameScope {
    fn drop(&mut self) {
        FRAME_ACTIVE.with(|flag| flag.set(false));
    }
}

/// Process-wide probe outcome: the evidence line on success, the reason on failure.
static PROBE: OnceLock<Result<String, String>> = OnceLock::new();

fn record_error(reason: String) {
    GPU_ERROR.with(|slot| {
        let mut slot = slot.borrow_mut();
        if slot.is_none() {
            // logcat on Android (and stdout on the host) so a fallback is visible
            // next to the render line that triggered it.
            crate::render::note(&format!("fm_hand.gpu.error {reason}"));
            *slot = Some(reason);
        }
    });
}

pub fn take_error() -> Option<String> {
    GPU_ERROR.with(|slot| slot.borrow_mut().take())
}

pub fn clear_error() {
    GPU_ERROR.with(|slot| *slot.borrow_mut() = None);
}

/// True when this target has a GL bootstrap at all (Android only today).
pub const fn backend_available() -> bool {
    egl::available()
}

/// The frozen offscreen `ImageInfo` the shared modules expect (RGBA8888 premul +
/// sRGB). Kept identical to the previous CPU-only implementation.
fn temp_info(w: i32, h: i32) -> ImageInfo {
    ImageInfo::new(
        (w.max(1), h.max(1)),
        ColorType::RGBA8888,
        AlphaType::Premul,
        Some(ColorSpace::new_srgb()),
    )
}

fn cpu_raster(info: &ImageInfo) -> Option<Surface> {
    surfaces::raster(info, None, None)
}

/// Ganesh render target for `info` on this thread's context. `None` outside a GPU
/// frame or with no session, or after recording why the target could not be made.
fn gpu_surface(info: &ImageInfo) -> Option<Surface> {
    // `render_card_body` serves both backends. A CPU frame that happens to run on a
    // thread which still caches a Ganesh context must stay CPU — the frame's mode
    // decides the backend, not a leftover session. (This is also what keeps a CPU
    // retry after a GPU failure on the pure raster path.)
    if !frame_active() {
        return None;
    }
    let (w, h) = (info.width(), info.height());
    if w <= 0 || h <= 0 {
        return None;
    }
    match render_target(w, h, info) {
        Ok(Some(surface)) => Some(surface),
        Ok(None) => None,
        Err(reason) => {
            record_error(reason);
            None
        }
    }
}

/// Shared render-target creation for the frame surfaces and the probe.
fn render_target(w: i32, h: i32, info: &ImageInfo) -> Result<Option<Surface>, String> {
    if egl::with_direct_context(|_| ()).is_none() {
        return Ok(None);
    }
    // Every GL entry point re-establishes the context: a frame must not depend on a
    // bind that happened one call earlier.
    egl::ensure_current()?;
    let made = egl::with_direct_context_mut(|direct| {
        // Fail *before* asking Skia for a texture the driver cannot allocate: some
        // GL implementations hand back a null FBO instead of reporting an error.
        let max = direct.max_texture_size();
        if w > max || h > max {
            return Err(format!("gpu:texture-too-large {w}x{h}>max{max}"));
        }
        // `TopLeft` matches the raster surface orientation the shared draw code was
        // written against, so no caller has to flip coordinates. Budgeted so the
        // context's cache reclaims the texture once the frame is done.
        Ok(skia_safe::gpu::surfaces::render_target(
            direct,
            Budgeted::Yes,
            info,
            None,
            SurfaceOrigin::TopLeft,
            None,
            false,
            None,
        ))
    });
    match made {
        None => Ok(None),
        Some(Err(reason)) => Err(reason),
        Some(Ok(Some(surface))) => Ok(Some(surface)),
        Some(Ok(None)) => Err(format!("gpu:render-target-none {w}x{h}")),
    }
}

/// Same `raster_surface` contract as before the GPU work: never `None`, and a
/// CPU raster surface whenever the GPU path is off or unavailable.
pub fn raster_surface(w: i32, h: i32) -> Surface {
    let info = temp_info(w, h);
    if let Some(surface) = gpu_surface(&info) {
        return surface;
    }
    cpu_raster(&info).expect("raster surface alloc")
}

/// Backend switch for callers that own their `ImageInfo` (the final canvas).
/// Falls back to the CPU raster surface on any GPU problem; returns `None` only
/// when the CPU allocation itself fails (same contract as `surfaces::raster`).
pub fn surface_for(info: &ImageInfo) -> Option<Surface> {
    if let Some(surface) = gpu_surface(info) {
        return Some(surface);
    }
    cpu_raster(info)
}

/// Submits the current frame's Ganesh work. Must run before any pixel readback of
/// a GPU surface (`image_snapshot` → `read_pixels`/PNG encode) and before the EGL
/// context is released.
///
/// No-op outside a GPU frame: `render_card_body` calls this for **every** render,
/// and a CPU render that happens to run on a thread which still caches a Ganesh
/// context has nothing to submit — submitting anyway was what produced
/// `gpu:flush … current=no` on a device (and, through the `?` in the caller, failed
/// an otherwise fine CPU frame).
///
/// The CPU sync variant is used on purpose: this path always reads the finished
/// pixels back into a CPU image in the same frame, so waiting here costs nothing it
/// would not cost later — and it turns a wedged driver into a visible error instead
/// of a frame of undefined pixels.
pub fn flush_active() -> Result<(), String> {
    if !frame_active() {
        return Ok(());
    }
    let result = flush_raw();
    if let Err(reason) = &result {
        record_error(reason.clone());
    }
    result
}

fn flush_raw() -> Result<(), String> {
    if egl::with_direct_context(|_| ()).is_none() {
        return Ok(());
    }
    if !egl::is_current() {
        // The frame's GL context is not current any more. Re-bind once, but leave a
        // trace: a frame that needs this means something unbound the context between
        // two GL calls, and that is worth seeing on the device.
        egl::ensure_current().map_err(|e| {
            let (session, bound) = egl::context_handles();
            format!(
                "gpu:flush-not-current {} session={} egl-current={} thread={}",
                e,
                session,
                bound,
                egl::thread_tag()
            )
        })?;
        note_once_rebound();
    }
    let gl_before = egl::drain_gl_error();
    let flushed = egl::with_direct_context_mut(|direct| direct.flush_submit_and_sync_cpu());
    match flushed {
        None => Ok(()),
        Some(Ok(_)) => Ok(()),
        Some(Err(err)) => {
            // Everything a remote driver report needs: was our context still the
            // current one, and did GL itself flag an error?
            let gl_after = egl::drain_gl_error();
            let (session, bound) = egl::context_handles();
            Err(format!(
                "gpu:flush {err} current={} session={} egl-current={} thread={} glerror={} glbefore={}",
                if egl::is_current() { "yes" } else { "no" },
                session,
                bound,
                egl::thread_tag(),
                gl_code(gl_after),
                gl_code(gl_before),
            ))
        }
    }
}

/// Logs once per process that a frame had to re-bind its GL context.
fn note_once_rebound() {
    static ONCE: AtomicBool = AtomicBool::new(false);
    if !ONCE.swap(true, Ordering::Relaxed) {
        crate::render::note(&format!(
            "e4.gpu.rebound the frame's GL context was not current; re-bound it (thread={})",
            egl::thread_tag()
        ));
    }
}

fn gl_code(code: u32) -> String {
    if code == u32::MAX {
        "<no-context>".to_string()
    } else {
        format!("0x{code:04X}")
    }
}

/// Rasterizes a GPU-backed snapshot once, so the PNG encode and the RGBA readback
/// stay on the proven CPU path (a texture-backed image has to be read back anyway,
/// and `read_pixels` on it would do that copy per call). CPU images pass through
/// untouched, which keeps `off` mode free of extra copies.
pub fn to_raster(image: &skia_safe::Image) -> skia_safe::Image {
    if !image.is_texture_backed() {
        return image.clone();
    }
    // A texture can only be read back with its own context current; re-bind first.
    if let Err(reason) = egl::ensure_current() {
        record_error(format!("gpu:readback-not-current {reason}"));
        return image.clone();
    }
    // The context has to be passed explicitly: a null context would make Skia guess
    // (and a texture from a context it cannot see is not readable).
    let raster = egl::with_direct_context_mut(|direct| {
        image.make_raster_image(Some(direct), skia_safe::image::CachingHint::Disallow)
    })
    .flatten();
    match raster {
        Some(raster) => raster,
        None => {
            // Keep the texture-backed image; the caller's encode/readback then fails
            // loudly and the frame is retried on the CPU.
            record_error("gpu:make-raster-image-none".to_string());
            image.clone()
        }
    }
}

// --- probe ------------------------------------------------------------------

/// Runs the GPU self-test once per process and caches the outcome.
pub fn probe_cached() -> Result<String, String> {
    PROBE
        .get_or_init(|| {
            let result = probe_now();
            let line = match &result {
                Ok(evidence) => format!("e4.gpu.probe result=ok {evidence}"),
                Err(reason) => format!("e4.gpu.probe result=fail reason={reason}"),
            };
            crate::render::note(&line);
            result
        })
        .clone()
}

/// The probe result without running it again (empty when it never ran).
pub fn probe_evidence() -> String {
    match PROBE.get() {
        Some(Ok(evidence)) => evidence.clone(),
        Some(Err(reason)) => format!("failed({reason})"),
        None => String::new(),
    }
}

/// Probes the GPU on the calling thread right now, ignoring the cached result.
pub fn probe_now() -> Result<String, String> {
    if !backend_available() {
        return Err(egl::NO_BACKEND.to_string());
    }
    match self_test_caught() {
        Ok(evidence) => Ok(evidence),
        Err(first) => {
            // Ladder: a GLES3 context that cannot render falls back to GLES2 for the
            // whole process before we give up on the GPU.
            if !egl::prefers_es3() {
                return Err(first);
            }
            egl::prefer_es2();
            egl::reset_session();
            match self_test_caught() {
                Ok(evidence) => Ok(format!("{evidence} ladder=es3-failed({first})")),
                Err(second) => Err(format!("{second} (es3: {first})")),
            }
        }
    }
}

/// `self_test` with panics turned into failures: a Skia assertion inside the probe
/// must degrade to the CPU, not unwind through the render entry point.
fn self_test_caught() -> Result<String, String> {
    match catch_unwind(AssertUnwindSafe(self_test)) {
        Ok(result) => result,
        Err(payload) => {
            egl::reset_session();
            Err(format!("probe:panic {}", panic_message(payload)))
        }
    }
}

/// Clear → clip → layer → draw → flush → read back, on a 4×4 render target. Only a
/// driver that survives all of that may rasterize preview frames.
fn self_test() -> Result<String, String> {
    egl::with_gl_context(|| {
        egl::install_direct_context()?;
        // Start from a clean GL error state: an error left by someone else would
        // otherwise be reported as ours.
        let before = egl::drain_gl_error();

        const CLEAR: [u8; 3] = [0x12, 0x34, 0x56];
        let info = temp_info(4, 4);
        let mut surface = match render_target(4, 4, &info)? {
            Some(surface) => surface,
            None => return Err("probe:no-gpu-session".to_string()),
        };
        {
            let canvas = surface.canvas();
            canvas.clear(skia_safe::Color::from_argb(255, CLEAR[0], CLEAR[1], CLEAR[2]));
            // Same primitives the cards use: a clip (stencil) and a layer (scratch
            // target) around a translucent fill.
            canvas.save();
            canvas.clip_rect(
                skia_safe::Rect::from_xywh(1.0, 1.0, 2.0, 2.0),
                None,
                false,
            );
            canvas.save_layer(&skia_safe::canvas::SaveLayerRec::default());
            let mut paint = skia_safe::Paint::default();
            paint.set_color(skia_safe::Color::from_argb(128, 255, 255, 255));
            canvas.draw_rect(
                skia_safe::Rect::from_xywh(1.0, 1.0, 2.0, 2.0),
                &paint,
            );
            canvas.restore();
            canvas.restore();
        }
        flush_raw()?;
        let image = surface.image_snapshot();
        let pixels = read_back_4x4(&image)?;

        let corner = [pixels[0], pixels[1], pixels[2]];
        let drawn = [pixels[(1 * 4 + 1) * 4], pixels[(1 * 4 + 1) * 4 + 1], pixels[(1 * 4 + 1) * 4 + 2]];
        if corner != CLEAR {
            return Err(format!(
                "probe:readback-corner {corner:02X?} expected {CLEAR:02X?}"
            ));
        }
        if drawn == CLEAR {
            return Err(format!(
                "probe:readback-no-draw {drawn:02X?} (rect/layer did not rasterize)"
            ));
        }
        let after = egl::drain_gl_error();
        Ok(format!(
            "es={} thread={} readback=#{:02X}{:02X}{:02X} drawn=#{:02X}{:02X}{:02X} glerror={} before={} maxTex={} {}",
            egl::es_version().unwrap_or(0),
            egl::thread_tag(),
            corner[0],
            corner[1],
            corner[2],
            drawn[0],
            drawn[1],
            drawn[2],
            gl_code(after),
            gl_code(before),
            egl::with_direct_context(|direct| direct.max_texture_size()).unwrap_or(-1),
            egl::describe().unwrap_or_else(|| "gl=<unknown>".to_string()),
        ))
    })
}

/// Reads the whole 4×4 surface back as unpremultiplied RGBA8888.
fn read_back_4x4(image: &skia_safe::Image) -> Result<Vec<u8>, String> {
    egl::ensure_current()?;
    let dst = ImageInfo::new(
        (4, 4),
        ColorType::RGBA8888,
        AlphaType::Unpremul,
        Some(ColorSpace::new_srgb()),
    );
    let mut pixels = vec![0u8; 4 * 4 * 4];
    let ok = egl::with_direct_context_mut(|direct| {
        image.read_pixels_with_context(
            Some(direct),
            &dst,
            pixels.as_mut_slice(),
            4 * 4,
            (0, 0),
            skia_safe::image::CachingHint::Disallow,
        )
    })
    .unwrap_or(false);
    if !ok {
        return Err(format!(
            "probe:read-pixels-failed current={} glerror={}",
            if egl::is_current() { "yes" } else { "no" },
            gl_code(egl::drain_gl_error()),
        ));
    }
    Ok(pixels)
}

// --- frame ------------------------------------------------------------------

/// Runs one preview render on the GPU.
///
/// Order: probe once per process (4×4 self-test) → make the thread's EGL context
/// current → install/refresh the Ganesh context → clear the per-frame error slot →
/// run the body → submit → report the first GPU-level failure (or a panic) back to
/// the caller, which then retries the same render on the CPU exactly once.
///
/// A panic inside a GPU pass is treated like a GPU error *on purpose*: `Skia`
/// asserts/`expect`s that would abort a CPU render are already caught by the C ABI
/// guard, but on the GPU they usually mean "this driver/feature combination is
/// unsupported", and degrading to the CPU keeps the preview alive.
pub fn run_preview<T>(f: impl Fn() -> Result<T, String>) -> Result<T, String> {
    if !backend_available() {
        return Err(egl::NO_BACKEND.to_string());
    }
    // The probe is the only thing that may flip "unknown" to "ok"; a failure here
    // disables the GPU for the rest of the process (auto mode) without ever asking a
    // driver to rasterize a real card.
    if crate::gpu_preview::probe_state() == crate::gpu_preview::Probe::Unknown {
        match probe_cached() {
            Ok(_) => {
                crate::gpu_preview::record_probe_ok();
            }
            Err(reason) => {
                crate::gpu_preview::record_probe_failed(&reason);
                return Err(reason);
            }
        }
    }
    egl::with_gl_context(|| {
        // From here to the end of the closure the whole frame (layout, draw, flush,
        // snapshot, readback) runs with this thread's EGL context current. Nothing
        // outside the scope may touch GL/Ganesh.
        let _frame = FrameScope::begin();
        egl::install_direct_context()?;
        clear_error();
        let body = catch_unwind(AssertUnwindSafe(&f));
        let flush = flush_raw();
        if let Some(reason) = take_error() {
            // A wedged driver state must not survive into the next frame.
            egl::reset_session();
            return Err(reason);
        }
        flush?;
        match body {
            Ok(result) => result,
            Err(payload) => {
                egl::reset_session();
                Err(format!("gpu:panic {}", panic_message(payload)))
            }
        }
    })
}

fn panic_message(payload: Box<dyn std::any::Any + Send>) -> String {
    payload
        .downcast_ref::<&str>()
        .map(|s| (*s).to_string())
        .or_else(|| payload.downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "non-string panic payload".to_string())
}

/// Driver/GL details for the diagnostics entry point. Never touches GL: the strings
/// were captured while a context was current, so this is safe to call at any time.
pub fn describe() -> String {
    let mut out = crate::gpu_preview::describe();
    out.push_str(&format!(
        " gl={}",
        egl::describe().unwrap_or_else(|| "<none>".to_string())
    ));
    let evidence = probe_evidence();
    if !evidence.is_empty() {
        out.push_str(&format!(" probe={evidence}"));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// On the host there is no GL session, so the backend switch must hand back a
    /// plain raster surface — the property `FM_GPU_PREVIEW=off` relies on.
    #[test]
    fn without_a_session_every_surface_is_cpu_raster() {
        assert!(!backend_available() || egl::with_direct_context(|_| ()).is_none());
        let info = temp_info(8, 4);
        let mut surface = surface_for(&info).expect("cpu raster");
        assert_eq!(surface.image_info().width(), 8);
        assert_eq!(surface.image_info().height(), 4);
        assert!(!surface.image_snapshot().is_texture_backed());

        let mut legacy = raster_surface(3, 5);
        assert!(!legacy.image_snapshot().is_texture_backed());
        assert_eq!(legacy.image_info().width(), 3);
        assert_eq!(legacy.image_info().height(), 5);

        // Flushing and reading back with no session is a no-op, not an error.
        assert!(flush_active().is_ok());
        assert!(take_error().is_none());
    }

    /// Regression guard for the device bug rc32/rc33 exposed: a **CPU** render runs
    /// `render_card_body` too, and on a thread that still caches a Ganesh context it
    /// used to submit that context without a current GL context → a bogus
    /// `gpu:flush … current=no` that also failed the CPU frame. Only a frame opened
    /// by `run_preview` may flush now.
    #[test]
    fn only_a_gpu_frame_may_flush() {
        assert!(!frame_active());
        assert!(flush_active().is_ok(), "a CPU render must not submit anything");
        assert!(take_error().is_none(), "a CPU render must record nothing");

        {
            let _scope = FrameScope::begin();
            assert!(frame_active());
            // Host: still no session, so nothing to submit — but the scope is armed,
            // which is what makes a device frame flush at all.
            assert!(flush_active().is_ok());
        }
        assert!(!frame_active());

        // A panicking frame must not leave the scope armed for the next CPU render.
        let panicked = catch_unwind(AssertUnwindSafe(|| {
            let _scope = FrameScope::begin();
            panic!("boom");
        }));
        assert!(panicked.is_err());
        assert!(!frame_active());
        assert!(flush_active().is_ok());
    }

    /// A CPU frame must never allocate a GPU surface, even on a thread that still
    /// caches a Ganesh context: the frame's mode decides the backend.
    #[test]
    fn cpu_frames_never_allocate_gpu_surfaces() {
        assert!(!frame_active());
        let info = temp_info(4, 4);
        let mut surface = surface_for(&info).expect("cpu raster");
        assert!(!surface.image_snapshot().is_texture_backed());
        assert!(take_error().is_none());
    }

    /// A GPU pass that fails must surface a reason (so the frame can be retried on
    /// the CPU and the note can say `gpu-fallback=<reason>`), and the probe must
    /// refuse on a target without a GL backend.
    #[test]
    fn failed_gpu_pass_reports_its_reason() {
        if backend_available() {
            // Real Android device: the probe may legitimately succeed, so accept
            // either an error or a successful no-op render.
            let _ = run_preview(|| Ok::<_, String>(()));
            return;
        }
        let err = run_preview(|| Ok::<_, String>(())).expect_err("host has no GL backend");
        assert_eq!(err, crate::egl::NO_BACKEND);
        let probe = probe_now().expect_err("host has no GL backend");
        assert_eq!(probe, crate::egl::NO_BACKEND);
    }

    /// The recorded error is sticky-first: the earliest cause wins, which is the
    /// one worth putting in the note.
    #[test]
    fn first_gpu_error_is_the_one_kept() {
        clear_error();
        record_error("gpu:first".to_string());
        record_error("gpu:second".to_string());
        assert_eq!(take_error().as_deref(), Some("gpu:first"));
        assert!(take_error().is_none());
    }

    /// The probe outcome must survive into the diagnostics string, so a device
    /// report can be answered from `fm_hand_gpu_info` alone.
    #[test]
    fn probe_evidence_records_the_failure_for_diagnostics() {
        let cached = probe_cached();
        assert!(cached.is_err(), "host has no GL backend");
        let evidence = probe_evidence();
        assert!(
            evidence.starts_with("failed("),
            "unexpected evidence: {evidence}"
        );
        assert!(
            evidence.contains(crate::egl::NO_BACKEND),
            "unexpected evidence: {evidence}"
        );
        let described = describe();
        assert!(described.contains("gl=<none>"), "unexpected: {described}");
        assert!(described.contains("probe=failed("), "unexpected: {described}");
    }

    /// `to_raster` must not touch a CPU image (that is what keeps `off` mode
    /// byte-identical: no extra copy, no colour-space round trip).
    #[test]
    fn cpu_images_pass_through_to_raster_untouched() {
        let info = temp_info(2, 2);
        let mut surface = cpu_raster(&info).expect("cpu raster");
        surface.canvas().clear(skia_safe::Color::RED);
        let snapshot = surface.image_snapshot();
        let raster = to_raster(&snapshot);
        assert!(!raster.is_texture_backed());
        assert!(take_error().is_none());
    }
}
