//! EGL / GLESv2 bootstrap for the Android GPU preview path.
//!
//! The pinned Skia archive is built with Ganesh + GL
//! (`skia-binaries-…-aarch64-linux-android-ganesh-gl-…`), but a `GrDirectContext`
//! cannot be created without a current GL context, and the renderer runs on a JNI
//! thread pool with no window surface. This module owns one **offscreen** EGL
//! context per render thread (a 1×1 pbuffer; Ganesh allocates its own FBOs for
//! every render target) and keeps it cached for the next frame on that thread.
//!
//! Why the `GrDirectContext` lives in this module: it has to be destroyed while
//! its GL context is current, and Rust runs one thread-local destructor per
//! `thread_local!` variable in registration order. Keeping "EGL context" and
//! "Ganesh context" inside one slot makes the teardown order explicit instead of
//! relying on that order (see [`Session`]'s `Drop`).
//!
//! The bindings are written by hand on purpose: the build runs offline, so
//! `khronos-egl`/`glutin` cannot be fetched. Only the ~13 entry points used here
//! are declared; the two libraries are added to the link line by `build.rs`.
//!
//! Every failure returns an `Err(&'static-ish String)`; the caller
//! (`crate::gpu::run_preview`) turns that into a CPU fallback for the frame.

/// Returned when this build target has no GL bootstrap at all (host/CI builds).
pub const NO_BACKEND: &str = "gpu:no-gl-backend";

#[cfg(target_os = "android")]
mod imp {
    use std::cell::RefCell;
    use std::ffi::c_void;
    use std::os::raw::{c_char, c_int};
    use std::sync::OnceLock;

    use skia_safe::gpu::{self, DirectContext};

    // --- EGL types (opaque handles; the same on every NDK level) -------------
    type EGLDisplay = *mut c_void;
    type EGLConfig = *mut c_void;
    type EGLContext = *mut c_void;
    type EGLSurface = *mut c_void;
    type EGLBoolean = u32;
    type EGLint = c_int;

    #[link(name = "EGL")]
    extern "C" {
        fn eglGetDisplay(native_display: *mut c_void) -> EGLDisplay;
        fn eglInitialize(dpy: EGLDisplay, major: *mut EGLint, minor: *mut EGLint) -> EGLBoolean;
        fn eglBindAPI(api: u32) -> EGLBoolean;
        fn eglChooseConfig(
            dpy: EGLDisplay,
            attribs: *const EGLint,
            configs: *mut EGLConfig,
            config_size: EGLint,
            num_config: *mut EGLint,
        ) -> EGLBoolean;
        fn eglCreateContext(
            dpy: EGLDisplay,
            config: EGLConfig,
            share: EGLContext,
            attribs: *const EGLint,
        ) -> EGLContext;
        fn eglCreatePbufferSurface(
            dpy: EGLDisplay,
            config: EGLConfig,
            attribs: *const EGLint,
        ) -> EGLSurface;
        fn eglMakeCurrent(
            dpy: EGLDisplay,
            draw: EGLSurface,
            read: EGLSurface,
            ctx: EGLContext,
        ) -> EGLBoolean;
        fn eglReleaseThread() -> EGLBoolean;
        fn eglDestroySurface(dpy: EGLDisplay, surface: EGLSurface) -> EGLBoolean;
        fn eglDestroyContext(dpy: EGLDisplay, ctx: EGLContext) -> EGLBoolean;
        fn eglGetError() -> EGLint;
    }

    #[link(name = "GLESv2")]
    extern "C" {
        fn glGetString(name: u32) -> *const u8;
    }

    const EGL_DEFAULT_DISPLAY: *mut c_void = std::ptr::null_mut();
    const EGL_NO_CONTEXT: EGLContext = std::ptr::null_mut();
    const EGL_NO_SURFACE: EGLSurface = std::ptr::null_mut();
    const EGL_FALSE: EGLBoolean = 0;

    const EGL_ALPHA_SIZE: EGLint = 0x3021;
    const EGL_BLUE_SIZE: EGLint = 0x3022;
    const EGL_GREEN_SIZE: EGLint = 0x3023;
    const EGL_RED_SIZE: EGLint = 0x3024;
    const EGL_STENCIL_SIZE: EGLint = 0x3026;
    const EGL_HEIGHT: EGLint = 0x3056;
    const EGL_WIDTH: EGLint = 0x3057;
    const EGL_NONE: EGLint = 0x3038;
    const EGL_OPENGL_ES_API: u32 = 0x30A0;
    const EGL_RENDERABLE_TYPE: EGLint = 0x3040;
    const EGL_OPENGL_ES2_BIT: EGLint = 0x0004;
    /// `EGL_OPENGL_ES3_BIT_KHR`; same value as core EGL 1.5's `EGL_OPENGL_ES3_BIT`.
    const EGL_OPENGL_ES3_BIT: EGLint = 0x0040;
    const EGL_SURFACE_TYPE: EGLint = 0x3033;
    const EGL_PBUFFER_BIT: EGLint = 0x0001;
    const EGL_CONTEXT_CLIENT_VERSION: EGLint = 0x3098;

    const GL_VENDOR: u32 = 0x1F00;
    const GL_RENDERER: u32 = 0x1F01;
    const GL_VERSION: u32 = 0x1F02;

    // The display and the chosen configs are process-wide and only need to be
    // resolved once. Raw EGL handles are not `Sync`, so they are cached as
    // `usize` in the `OnceLock` and cast back on use.
    static DISPLAY: OnceLock<Result<Display, String>> = OnceLock::new();

    /// Process-wide EGL display plus the GLES3 config when the device has one
    /// (`0` when it does not) and the always-present GLES2 config.
    struct Display {
        handle: usize,
        config3: usize,
        config2: usize,
    }

    thread_local! {
        static SESSION: RefCell<Option<Session>> = RefCell::new(None);
    }

    /// One render thread's offscreen GL context + the Ganesh context drawn on it.
    struct Session {
        /// Declared first so the Ganesh context is released before the EGL
        /// handles when the struct is dropped (field order == drop order).
        direct: Option<DirectContext>,
        display: EGLDisplay,
        context: EGLContext,
        surface: EGLSurface,
        current: bool,
    }

    impl Session {
        fn create() -> Result<Self, String> {
            let display = display_config()?;
            let dpy = display.handle as EGLDisplay;
            // The API binding is per-thread on EGL; re-binding is idempotent.
            if unsafe { eglBindAPI(EGL_OPENGL_ES_API) } == EGL_FALSE {
                return Err(egl_error("egl:bind-api"));
            }
            // GLES3 first: `GL_SRGB8_ALPHA8` is core there, so Ganesh's sRGB render
            // targets (what every surface in this renderer asks for) are allocated
            // as real sRGB textures instead of being emulated by the shader. Every
            // API-26+ device has GLES3; the GLES2 context is the safety net.
            let mut context = EGL_NO_CONTEXT;
            if display.config3 != 0 {
                context = create_context(
                    dpy,
                    display.config3 as EGLConfig,
                    EGL_CONTEXT_CLIENT_VERSION,
                    3,
                );
            }
            if context.is_null() {
                context = create_context(
                    dpy,
                    display.config2 as EGLConfig,
                    EGL_CONTEXT_CLIENT_VERSION,
                    2,
                );
            }
            if context.is_null() {
                return Err(egl_error("egl:create-context"));
            }
            // Ganesh never draws into this surface: every render target gets its own
            // FBO. A 1×1 pbuffer only exists so `eglMakeCurrent` has a drawable. The
            // GLES2 config is always a valid pbuffer config for a GLES3 context, and
            // it is also what the GLES2 fallback context was created with.
            let config = display.config2 as EGLConfig;
            let mut surface = unsafe {
                eglCreatePbufferSurface(dpy, config, [EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE].as_ptr())
            };
            if surface.is_null() {
                // Some drivers want the pbuffer on the same config as the context; if
                // the GLES2 config was refused, retry with the GLES3 one.
                if display.config3 != 0 {
                    surface = unsafe {
                        eglCreatePbufferSurface(
                            dpy,
                            display.config3 as EGLConfig,
                            [EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE].as_ptr(),
                        )
                    };
                }
            }
            if surface.is_null() {
                unsafe { eglDestroyContext(dpy, context) };
                return Err(egl_error("egl:create-pbuffer"));
            }
            let mut session = Session {
                direct: None,
                display: dpy,
                context,
                surface,
                current: false,
            };
            if let Err(e) = session.activate() {
                // `drop` cleans up the pbuffer/context that were just created.
                return Err(e);
            }
            Ok(session)
        }

        fn activate(&mut self) -> Result<(), String> {
            if self.current {
                return Ok(());
            }
            let ok = unsafe {
                eglMakeCurrent(self.display, self.surface, self.surface, self.context)
            };
            if ok == EGL_FALSE {
                // EGL_CONTEXT_LOST (0x300E) or a driver reset lands here; the frame
                // falls back to CPU and the process stops trying (auto mode).
                return Err(egl_error("egl:make-current"));
            }
            self.current = true;
            Ok(())
        }

        fn release(&mut self) {
            if !self.current {
                return;
            }
            unsafe { eglMakeCurrent(self.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) };
            self.current = false;
        }
    }

    impl Drop for Session {
        fn drop(&mut self) {
            // Ganesh releases GL objects (FBOs/textures/programs) in its destructor,
            // so the context has to be current for the drop below.
            let _ = self.activate();
            self.direct = None;
            // Unbind *before* destroying: EGL leaves it implementation-defined what a
            // destroyed-but-current context does to the thread's error state.
            unsafe { eglMakeCurrent(self.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) };
            self.current = false;
            if !self.surface.is_null() {
                unsafe { eglDestroySurface(self.display, self.surface) };
                self.surface = EGL_NO_SURFACE;
            }
            if !self.context.is_null() {
                unsafe { eglDestroyContext(self.display, self.context) };
                self.context = EGL_NO_CONTEXT;
            }
            unsafe { eglReleaseThread() };
        }
    }

    fn egl_error(what: &str) -> String {
        format!("{what} err=0x{:04X}", unsafe { eglGetError() } as u32)
    }

    fn create_context(
        display: EGLDisplay,
        config: EGLConfig,
        attrib: EGLint,
        value: EGLint,
    ) -> EGLContext {
        unsafe {
            eglCreateContext(
                display,
                config,
                EGL_NO_CONTEXT,
                [attrib, value, EGL_NONE].as_ptr(),
            )
        }
    }

    /// Chooses one config for `renderable_type`, or `0` when the driver has none.
    fn choose_config(display: EGLDisplay, renderable_type: EGLint) -> usize {
        let attribs = [
            EGL_SURFACE_TYPE,
            EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE,
            renderable_type,
            EGL_RED_SIZE,
            8,
            EGL_GREEN_SIZE,
            8,
            EGL_BLUE_SIZE,
            8,
            EGL_ALPHA_SIZE,
            8,
            // Skia's GL backend wants a stencil buffer for clip stacks; 8 bits is
            // the common denominator and what ANGLE/Skia ask for on Android.
            EGL_STENCIL_SIZE,
            8,
            EGL_NONE,
        ];
        let mut config: EGLConfig = std::ptr::null_mut();
        let mut num: EGLint = 0;
        let ok = unsafe { eglChooseConfig(display, attribs.as_ptr(), &mut config, 1, &mut num) };
        if ok == EGL_FALSE || num < 1 || config.is_null() {
            0
        } else {
            config as usize
        }
    }

    fn display_config() -> Result<&'static Display, String> {
        match DISPLAY.get_or_init(|| {
            let display = unsafe { eglGetDisplay(EGL_DEFAULT_DISPLAY) };
            if display.is_null() {
                return Err(egl_error("egl:no-display"));
            }
            let (mut major, mut minor): (EGLint, EGLint) = (0, 0);
            if unsafe { eglInitialize(display, &mut major, &mut minor) } == EGL_FALSE {
                return Err(egl_error("egl:initialize"));
            }
            if unsafe { eglBindAPI(EGL_OPENGL_ES_API) } == EGL_FALSE {
                return Err(egl_error("egl:bind-api"));
            }
            // GLES2 is required (API 8+); GLES3 is preferred when the driver offers a
            // config for it and is used for a proper sRGB render target.
            let config2 = choose_config(display, EGL_OPENGL_ES2_BIT);
            if config2 == 0 {
                return Err(egl_error("egl:no-config"));
            }
            let config3 = choose_config(display, EGL_OPENGL_ES3_BIT);
            Ok(Display {
                handle: display as usize,
                config3,
                config2,
            })
        }) {
            Ok(display) => Ok(display),
            Err(e) => Err(e.clone()),
        }
    }

    /// Runs `f` with this thread's EGL context current, creating it on first use.
    ///
    /// The context is **released** (not destroyed) afterwards so a thread that also
    /// renders for someone else does not keep our context bound to it; the next
    /// frame on the same thread re-binds it. This is the `with_gl_context(|| …)`
    /// entry the GPU preview path calls.
    pub fn with_gl_context<T>(f: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
        ensure_session()?;
        // Release even if `f` unwinds: a `RefCell` left borrowed or a context left
        // current would poison the next frame on this thread. (`gpu::run_preview`
        // additionally drops the Ganesh context when it saw a panic.)
        struct ReleaseOnDrop;
        impl Drop for ReleaseOnDrop {
            fn drop(&mut self) {
                SESSION.with(|cell| {
                    if let Ok(mut slot) = cell.try_borrow_mut() {
                        if let Some(session) = slot.as_mut() {
                            session.release();
                        }
                    }
                });
            }
        }
        let release = ReleaseOnDrop;
        let out = f();
        drop(release);
        out
    }

    fn ensure_session() -> Result<(), String> {
        SESSION.with(|cell| {
            let mut slot = cell.borrow_mut();
            if slot.is_none() {
                *slot = Some(Session::create()?);
            }
            match slot.as_mut() {
                Some(session) => session.activate(),
                None => Err("egl:session-missing".to_string()),
            }
        })
    }

    /// Creates the Ganesh `GrDirectContext` on this thread if it is not there yet.
    /// Must run inside [`with_gl_context`] (the GL context has to be current).
    pub fn install_direct_context() -> Result<(), String> {
        if with_direct_context_mut(|_| ()).is_some() {
            return Ok(());
        }
        // `new_native()` resolves the platform interface, which for the Android
        // Skia archive is the EGL one (`GrGLMakeNativeInterface_egl.o`).
        let interface = gpu::gl::Interface::new_native()
            .ok_or_else(|| "egl:no-native-gl-interface".to_string())?;
        if !interface.validate() {
            return Err("egl:gl-interface-invalid".to_string());
        }
        let direct = gpu::ganesh::gl::direct_contexts::make_gl(interface, None)
            .ok_or_else(|| "ganesh:no-direct-context".to_string())?;
        SESSION.with(|cell| {
            if let Some(session) = cell.borrow_mut().as_mut() {
                session.direct = Some(direct);
            }
        });
        Ok(())
    }

    /// Drops the Ganesh context (while current) so a wedged driver state cannot
    /// leak into the next frame. The EGL context is kept for reuse.
    pub fn drop_direct_context() {
        SESSION.with(|cell| {
            if let Ok(mut slot) = cell.try_borrow_mut() {
                if let Some(session) = slot.as_mut() {
                    session.direct = None;
                }
            }
        });
    }

    pub fn with_direct_context_mut<R>(
        f: impl FnOnce(&mut DirectContext) -> R,
    ) -> Option<R> {
        SESSION.with(|cell| {
            let mut slot = cell.borrow_mut();
            let session = slot.as_mut()?;
            let direct = session.direct.as_mut()?;
            Some(f(direct))
        })
    }

    pub fn with_direct_context<R>(f: impl FnOnce(&DirectContext) -> R) -> Option<R> {
        SESSION.with(|cell| {
            let slot = cell.borrow();
            let session = slot.as_ref()?;
            let direct = session.direct.as_ref()?;
            Some(f(direct))
        })
    }

    /// `vendor/renderer/driver` as the driver reports them; `None` when no context
    /// is current on this thread. Used by the diagnostics entry point.
    pub fn describe() -> Option<String> {
        with_direct_context(|_| ()).map(|_| unsafe {
            let read = |name: u32| -> String {
                let ptr = glGetString(name);
                if ptr.is_null() {
                    return "<null>".to_string();
                }
                std::ffi::CStr::from_ptr(ptr as *const c_char)
                    .to_string_lossy()
                    .into_owned()
            };
            format!(
                "vendor='{}' renderer='{}' version='{}'",
                read(GL_VENDOR),
                read(GL_RENDERER),
                read(GL_VERSION)
            )
        })
    }
}

#[cfg(target_os = "android")]
#[allow(unused_imports)]
pub use imp::{
    describe, drop_direct_context, install_direct_context, with_direct_context,
    with_direct_context_mut, with_gl_context,
};

#[cfg(not(target_os = "android"))]
mod stub {
    use skia_safe::gpu::DirectContext;

    /// Host builds have no EGL: the GPU preview path reports "no backend" and every
    /// render stays on the CPU raster surface (bit-identical to the pre-GPU code).
    pub fn with_gl_context<T>(f: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
        let _ = f;
        Err(super::NO_BACKEND.to_string())
    }

    pub fn install_direct_context() -> Result<(), String> {
        Err(super::NO_BACKEND.to_string())
    }

    pub fn drop_direct_context() {}

    pub fn with_direct_context_mut<R>(_f: impl FnOnce(&mut DirectContext) -> R) -> Option<R> {
        None
    }

    pub fn with_direct_context<R>(_f: impl FnOnce(&DirectContext) -> R) -> Option<R> {
        None
    }

    pub fn describe() -> Option<String> {
        None
    }
}

#[cfg(not(target_os = "android"))]
#[allow(unused_imports)]
pub use stub::{
    describe, drop_direct_context, install_direct_context, with_direct_context,
    with_direct_context_mut, with_gl_context,
};

/// True when this target can ever rasterize on the GPU.
pub const fn available() -> bool {
    cfg!(target_os = "android")
}
