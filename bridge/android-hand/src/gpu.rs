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

use std::cell::RefCell;
use std::panic::{catch_unwind, AssertUnwindSafe};

use skia_safe::gpu::{Budgeted, SurfaceOrigin};
use skia_safe::{surfaces, AlphaType, ColorSpace, ColorType, ImageInfo, Surface};

use crate::egl;

thread_local! {
    /// First GPU-level failure seen by this thread inside the current frame. The
    /// surface factory cannot return `Result` (its signature is frozen by the
    /// shared engine modules), so it records the reason here and the frame wrapper
    /// reads it after the pass to decide the CPU retry.
    static GPU_ERROR: RefCell<Option<String>> = RefCell::new(None);
}

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

/// GPU render target for `info`, or `None` when no GL session is installed.
///
/// A failure here is recorded (texture limit, `render_target` returning `None`,
/// driver returning a null texture) and reported to the caller as "use the CPU";
/// the whole frame is then retried on the CPU by [`run_preview`]'s caller.
fn gpu_surface(info: &ImageInfo) -> Option<Surface> {
    let (w, h) = (info.width(), info.height());
    if w <= 0 || h <= 0 {
        return None;
    }
    egl::with_direct_context_mut(|direct| {
        // Fail *before* asking Skia for a texture the driver cannot allocate: some
        // GL implementations hand back a null FBO instead of reporting an error.
        let max = direct.max_texture_size();
        if w > max || h > max {
            record_error(format!("gpu:texture-too-large {w}x{h}>max{max}"));
            return None;
        }
        // `TopLeft` matches the raster surface orientation the shared draw code was
        // written against, so no caller has to flip coordinates. Budgeted so the
        // context's cache reclaims the texture once the frame is done.
        let surface = skia_safe::gpu::surfaces::render_target(
            direct,
            Budgeted::Yes,
            info,
            None,
            SurfaceOrigin::TopLeft,
            None,
            false,
            None,
        );
        if surface.is_none() {
            record_error(format!("gpu:render-target-none {w}x{h}"));
        }
        surface
    })
    .flatten()
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
/// context is released. No-op without a GPU session.
pub fn flush_active() -> Result<(), String> {
    match egl::with_direct_context_mut(|direct| direct.flush_and_submit()) {
        Some(Ok(_)) => Ok(()),
        Some(Err(err)) => {
            let reason = format!("gpu:flush {err}");
            record_error(reason.clone());
            Err(reason)
        }
        None => Ok(()),
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
    match image.make_raster_image(None, skia_safe::image::CachingHint::Disallow) {
        Some(raster) => raster,
        None => {
            // Fall back to the texture-backed image: `read_pixels`/encode can still
            // resolve it through the image's own context, and a failed readback is
            // reported by the caller (→ CPU retry).
            record_error("gpu:make-raster-image-none".to_string());
            image.clone()
        }
    }
}

/// Runs one preview render on the GPU.
///
/// Order: make the thread's EGL context current → install/refresh the Ganesh
/// context → clear the per-frame error slot → run the body → submit → report the
/// first GPU-level failure (or a panic) back to the caller, which then retries the
/// same render on the CPU exactly once.
///
/// A panic inside a GPU pass is treated like a GPU error *on purpose*: `Skia`
/// asserts/`expect`s that would abort a CPU render are already caught by the C ABI
/// guard, but on the GPU they usually mean "this driver/feature combination is
/// unsupported", and degrading to the CPU keeps the preview alive.
pub fn run_preview<T>(f: impl Fn() -> Result<T, String>) -> Result<T, String> {
    if !backend_available() {
        return Err(crate::egl::NO_BACKEND.to_string());
    }
    egl::with_gl_context(|| {
        egl::install_direct_context()?;
        clear_error();
        let body = catch_unwind(AssertUnwindSafe(&f));
        let flush = flush_active();
        if let Some(reason) = take_error() {
            egl::drop_direct_context();
            return Err(reason);
        }
        flush?;
        match body {
            Ok(result) => result,
            Err(payload) => {
                egl::drop_direct_context();
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

/// Driver/GL details for the diagnostics entry point; empty when no context.
pub fn describe() -> String {
    let mut out = crate::gpu_preview::describe();
    match egl::describe() {
        Some(gl) => {
            out.push_str(" gl={");
            out.push_str(&gl);
            out.push('}');
        }
        None => out.push_str(" gl=<none>"),
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

    /// A GPU pass that fails must surface a reason (so the frame can be retried on
    /// the CPU and the note can say `gpu-fallback=<reason>`).
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
}
