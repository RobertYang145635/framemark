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
    use std::sync::atomic::{AtomicBool, Ordering};
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
        fn eglGetCurrentContext() -> EGLContext;
        fn eglReleaseThread() -> EGLBoolean;
        fn eglDestroySurface(dpy: EGLDisplay, surface: EGLSurface) -> EGLBoolean;
        fn eglDestroyContext(dpy: EGLDisplay, ctx: EGLContext) -> EGLBoolean;
        fn eglGetError() -> EGLint;
    }

    #[link(name = "GLESv2")]
    extern "C" {
        fn glGetString(name: u32) -> *const u8;
        fn glGetError() -> u32;
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

    /// Driver strings, captured **while a GL context is current** the first time a
    /// Ganesh context is created. `glGetString` outside a current context returns
    /// `NULL` (and is undefined behaviour on some drivers), so nothing may query it
    /// on demand — this cache is the only source.
    static GL_INFO: OnceLock<String> = OnceLock::new();

    /// Whether new sessions should ask for GLES3. Flipped to `false` process-wide by
    /// the GPU probe when a GLES3 context cannot render, so every later session on
    /// every thread uses the GLES2 ladder step.
    static PREFER_ES3: AtomicBool = AtomicBool::new(true);

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
        /// 3 or 2: which GLES context this session ended up with.
        es: u32,
    }

    impl Session {
        fn create() -> Result<Self, String> {
            let display = display_config()?;
            let dpy = display.handle as EGLDisplay;
            // The API binding is per-thread on EGL; re-binding is idempotent.
            if unsafe { eglBindAPI(EGL_OPENGL_ES_API) } == EGL_FALSE {
                return Err(egl_error("egl:bind-api"));
            }
            // A GLES3 context and its pbuffer must come from the *same* config, or
            // `eglMakeCurrent` can succeed while the drawable is not really usable
            // (Ganesh then fails every flush). So: pick one config, build both from
            // it, and only fall back to the GLES2 pair if the 3.x context is refused.
            let mut config = EGL_NO_CONTEXT as EGLConfig;
            let mut context = EGL_NO_CONTEXT;
            let mut es = 2;
            if PREFER_ES3.load(Ordering::Relaxed) && display.config3 != 0 {
                config = display.config3 as EGLConfig;
                context = create_context(dpy, config, EGL_CONTEXT_CLIENT_VERSION, 3);
                if !context.is_null() {
                    es = 3;
                }
            }
            if context.is_null() {
                config = display.config2 as EGLConfig;
                context = create_context(dpy, config, EGL_CONTEXT_CLIENT_VERSION, 2);
                es = 2;
            }
            if context.is_null() {
                return Err(egl_error("egl:create-context"));
            }
            // Ganesh never draws into this surface: every render target gets its own
            // FBO. A 1×1 pbuffer only exists so `eglMakeCurrent` has a drawable.
            let surface = unsafe {
                eglCreatePbufferSurface(dpy, config, [EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE].as_ptr())
            };
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
                es,
            };
            if let Err(e) = session.activate() {
                // `drop` cleans up the pbuffer/context that were just created.
                return Err(e);
            }
            Ok(session)
        }

        fn activate(&mut self) -> Result<(), String> {
            // Never trust the cached flag: something else on this thread may have
            // re-bound (or unbound) EGL since the last call, and a stale `true` would
            // let Ganesh run without a current context.
            self.current = unsafe { eglGetCurrentContext() } == self.context;
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
            // Trust but verify: a driver that returns EGL_TRUE without actually
            // binding the context would poison every later GL call.
            let bound = unsafe { eglGetCurrentContext() };
            if bound != self.context {
                return Err(format!(
                    "egl:make-current-not-bound egl={} session={} err=0x{:04X}",
                    ptr_tag(bound),
                    ptr_tag(self.context),
                    unsafe { eglGetError() } as u32
                ));
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

        fn is_current(&self) -> bool {
            // The EGL state is the truth; the cached flag is only a hint.
            (unsafe { eglGetCurrentContext() }) == self.context
        }
    }

    fn ptr_tag(handle: *mut c_void) -> String {
        if handle.is_null() {
            "none".to_string()
        } else {
            format!("0x{:x}", handle as usize)
        }
    }

    /// EGL handles of this thread's context vs. the one EGL reports as current, for
    /// diagnostics. `("none", "none")` without a session.
    pub fn context_handles() -> (String, String) {
        SESSION.with(|cell| {
            let slot = cell.borrow();
            match slot.as_ref() {
                Some(session) => (
                    ptr_tag(session.context),
                    ptr_tag(unsafe { eglGetCurrentContext() }),
                ),
                None => ("none".to_string(), ptr_tag(unsafe { eglGetCurrentContext() })),
            }
        })
    }

    /// Debug tag of the calling thread (`ThreadId(N)` without the wrapper text).
    pub fn thread_tag() -> String {
        format!("{:?}", std::thread::current().id())
            .replace("ThreadId(", "t")
            .replace(')', "")
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
        // The context is current right here, which is the only safe place to read
        // `GL_VENDOR`/`GL_RENDERER`/`GL_VERSION` (see `GL_INFO`).
        let (es, info) = capture_gl_info();
        let _ = GL_INFO.set(info);
        SESSION.with(|cell| {
            if let Some(session) = cell.borrow_mut().as_mut() {
                session.direct = Some(direct);
                session.es = es;
            }
        });
        Ok(())
    }

    /// Reads the driver strings while a context is current. Returns the context's
    /// GLES major version plus a `vendor=… renderer=… version=…` line.
    fn capture_gl_info() -> (u32, String) {
        let read = |name: u32| -> String {
            let ptr = unsafe { glGetString(name) };
            if ptr.is_null() {
                return "<null>".to_string();
            }
            unsafe { std::ffi::CStr::from_ptr(ptr as *const c_char) }
                .to_string_lossy()
                .trim()
                .to_string()
        };
        let (vendor, renderer, version) = (read(GL_VENDOR), read(GL_RENDERER), read(GL_VERSION));
        // "OpenGL ES 3.2 V@…" → 3
        let es = version
            .strip_prefix("OpenGL ES ")
            .and_then(|rest| rest.chars().next())
            .and_then(|c| c.to_digit(10))
            .unwrap_or(0);
        let es = es.max(current_es_version().unwrap_or(2));
        (
            es,
            format!(
                "vendor='{}' renderer='{}' version='{}'",
                vendor, renderer, version
            ),
        )
    }

    fn current_es_version() -> Option<u32> {
        SESSION.with(|cell| cell.borrow().as_ref().map(|s| s.es))
    }

    /// GLES major version of the session on this thread (3 or 2), if any.
    pub fn es_version() -> Option<u32> {
        current_es_version()
    }

    /// Driver strings captured while a context was current; never calls GL itself.
    pub fn describe() -> Option<String> {
        GL_INFO.get().cloned()
    }

    /// Whether this thread's EGL context is current *right now*.
    pub fn is_current() -> bool {
        SESSION.with(|cell| {
            cell.borrow()
                .as_ref()
                .map(|session| session.is_current())
                .unwrap_or(false)
        })
    }

    /// True when this thread has a Ganesh context installed *and* its GL context is
    /// current. Only then may anything call into that `GrDirectContext`.
    pub fn is_active() -> bool {
        is_current() && with_direct_context(|_| ()).is_some()
    }

    /// Re-binds this thread's EGL context when it is not current and verifies the
    /// bind with `eglGetCurrentContext()`. Every GL entry point in `gpu.rs` calls
    /// this, so a frame cannot lose its context halfway through.
    pub fn ensure_current() -> Result<(), String> {
        SESSION.with(|cell| match cell.borrow_mut().as_mut() {
            Some(session) => session.activate(),
            None => Err("egl:session-missing".to_string()),
        })
    }

    /// Drains `glGetError()` and returns the first code (0 when clean). Only
    /// meaningful while a context is current; returns `u32::MAX` otherwise.
    pub fn drain_gl_error() -> u32 {
        if !is_current() {
            return u32::MAX;
        }
        let mut first = 0;
        // The error queue holds at least one entry; loop a bounded number of times.
        for _ in 0..8 {
            let code = unsafe { glGetError() };
            if code == 0 {
                break;
            }
            if first == 0 {
                first = code;
            }
        }
        first
    }

    /// Stops asking for GLES3 in new sessions (process-wide). Used by the GPU probe
    /// ladder when a GLES3 context cannot render.
    pub fn prefer_es2() {
        PREFER_ES3.store(false, Ordering::Relaxed);
    }

    pub fn prefers_es3() -> bool {
        PREFER_ES3.load(Ordering::Relaxed)
    }

    /// Destroys this thread's EGL session so the next [`with_gl_context`] rebuilds it
    /// with the current preference (probe ladder). Also drops the Ganesh context.
    pub fn reset_session() {
        SESSION.with(|cell| {
            if let Ok(mut slot) = cell.try_borrow_mut() {
                // `Session::drop` makes the context current, releases Ganesh and then
                // destroys the EGL handles.
                *slot = None;
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
}

#[cfg(target_os = "android")]
#[allow(unused_imports)]
pub use imp::{
    context_handles, describe, drain_gl_error, ensure_current, es_version, install_direct_context,
    is_active, is_current, prefer_es2, prefers_es3, reset_session, thread_tag, with_direct_context,
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

    pub fn with_direct_context_mut<R>(_f: impl FnOnce(&mut DirectContext) -> R) -> Option<R> {
        None
    }

    pub fn with_direct_context<R>(_f: impl FnOnce(&DirectContext) -> R) -> Option<R> {
        None
    }

    pub fn describe() -> Option<String> {
        None
    }

    pub fn es_version() -> Option<u32> {
        None
    }

    pub fn is_current() -> bool {
        false
    }

    pub fn is_active() -> bool {
        false
    }

    pub fn ensure_current() -> Result<(), String> {
        Err(super::NO_BACKEND.to_string())
    }

    pub fn context_handles() -> (String, String) {
        ("none".to_string(), "none".to_string())
    }

    pub fn thread_tag() -> String {
        "t0".to_string()
    }

    /// Host: no context, so "no GL error" is 0 and the probe never asks.
    pub fn drain_gl_error() -> u32 {
        0
    }

    pub fn prefer_es2() {}

    pub fn prefers_es3() -> bool {
        false
    }

    pub fn reset_session() {}
}

#[cfg(not(target_os = "android"))]
#[allow(unused_imports)]
pub use stub::{
    context_handles, describe, drain_gl_error, ensure_current, es_version, install_direct_context,
    is_active, is_current, prefer_es2, prefers_es3, reset_session, thread_tag, with_direct_context,
    with_direct_context_mut, with_gl_context,
};

/// True when this target can ever rasterize on the GPU.
pub const fn available() -> bool {
    cfg!(target_os = "android")
}
