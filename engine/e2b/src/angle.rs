//! T1c: EGL/ANGLE context creation (the Chromium raster path).
//!
//! Chromium on Windows rasterises through ANGLE -> D3D11 on the NVIDIA adapter.
//! This module loads an ANGLE `libEGL.dll`/`libGLESv2.dll` pair (Chromium and
//! Electron link ANGLE statically, so the DLLs are taken from any installed
//! Chromium-derived app), creates an ES3 pbuffer context on the *NVIDIA* D3D11
//! device, and hands Skia a GL interface assembled from `eglGetProcAddress`.
//!
//! Adapter selection strategy (tried in this order by `EglCtx::new`):
//!   1. `device`   : D3D11CreateDevice on the chosen DXGI adapter, then
//!                   eglCreateDeviceANGLE(EGL_D3D11_DEVICE_ANGLE, device) +
//!                   eglGetPlatformDisplay(TYPE=D3D11). Adapter is explicit.
//!   2. `device_id`: EGL_PLATFORM_ANGLE_DEVICE_ID_ANGLE (0x345D) = PCI device id.
//!   3. `default`  : eglGetPlatformDisplay(TYPE=D3D11, DEVICE_TYPE=HARDWARE)
//!                   which uses the DXGI default adapter (AMD on this machine).

#![cfg(windows)]

use std::ffi::{c_char, c_void, CStr};

use windows::core::{Interface as _, PCSTR};
use windows::Win32::Foundation::HMODULE;
use windows::Win32::Graphics::Direct3D::{D3D_DRIVER_TYPE_UNKNOWN, D3D_FEATURE_LEVEL_11_0};
use windows::Win32::Graphics::Direct3D11::{
    D3D11CreateDevice, ID3D11Device, D3D11_CREATE_DEVICE_BGRA_SUPPORT, D3D11_SDK_VERSION,
};
use windows::Win32::Graphics::Dxgi::IDXGIAdapter1;
use windows::Win32::System::LibraryLoader::{
    GetProcAddress, LoadLibraryExW, LOAD_WITH_ALTERED_SEARCH_PATH,
};

// ---- EGL constants (eglext.h + eglext_angle.h) ----
const EGL_NONE: isize = 0x3038;
const EGL_SURFACE_TYPE: i32 = 0x3033;
const EGL_PBUFFER_BIT: i32 = 0x0001;
const EGL_RENDERABLE_TYPE: i32 = 0x3040;
const EGL_OPENGL_ES3_BIT: i32 = 0x0040;
const EGL_RED_SIZE: i32 = 0x3024;
const EGL_GREEN_SIZE: i32 = 0x3023;
const EGL_BLUE_SIZE: i32 = 0x3022;
const EGL_ALPHA_SIZE: i32 = 0x3021;
const EGL_WIDTH: i32 = 0x3057;
const EGL_HEIGHT: i32 = 0x3056;
const EGL_CONTEXT_CLIENT_VERSION: i32 = 0x3098;
const EGL_OPENGL_ES_API: u32 = 0x30A0;
const EGL_VENDOR: i32 = 0x3053;
const EGL_VERSION: i32 = 0x3054;
const EGL_EXTENSIONS: i32 = 0x3055;

const EGL_PLATFORM_ANGLE_ANGLE: u32 = 0x3202;
const EGL_PLATFORM_ANGLE_TYPE_ANGLE: isize = 0x3203;
const EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE: isize = 0x3208;
const EGL_PLATFORM_ANGLE_DEVICE_TYPE_ANGLE: isize = 0x3209;
const EGL_PLATFORM_ANGLE_DEVICE_TYPE_HARDWARE_ANGLE: isize = 0x320A;
const EGL_PLATFORM_ANGLE_DEVICE_ID_ANGLE: isize = 0x345D;
const EGL_D3D11_DEVICE_ANGLE: i32 = 0x33A1;
const GL_RENDERER: u32 = 0x1F01;
const GL_VENDOR: u32 = 0x1F00;
const GL_VERSION: u32 = 0x1F02;

type EGLDisplay = *mut c_void;
type EGLConfig = *mut c_void;
type EGLSurface = *mut c_void;
type EGLContext = *mut c_void;
type EGLDevice = *mut c_void;

type FnGetPlatformDisplay = unsafe extern "system" fn(u32, *mut c_void, *const isize) -> EGLDisplay;
type FnGetPlatformDisplayExt =
    unsafe extern "system" fn(u32, *mut c_void, *const i32) -> EGLDisplay;
type FnGetDisplay = unsafe extern "system" fn(*mut c_void) -> EGLDisplay;
type FnCreateDevice = unsafe extern "system" fn(i32, *mut c_void, *const isize) -> EGLDevice;
type FnInitialize = unsafe extern "system" fn(EGLDisplay, *mut i32, *mut i32) -> u32;
type FnTerminate = unsafe extern "system" fn(EGLDisplay) -> u32;
type FnQueryString = unsafe extern "system" fn(EGLDisplay, i32) -> *const c_char;
type FnChooseConfig =
    unsafe extern "system" fn(EGLDisplay, *const i32, *mut EGLConfig, i32, *mut i32) -> u32;
type FnCreatePbufferSurface =
    unsafe extern "system" fn(EGLDisplay, EGLConfig, *const i32) -> EGLSurface;
type FnCreateContext =
    unsafe extern "system" fn(EGLDisplay, EGLConfig, EGLContext, *const i32) -> EGLContext;
type FnMakeCurrent = unsafe extern "system" fn(EGLDisplay, EGLSurface, EGLSurface, EGLContext) -> u32;
type FnDestroySurface = unsafe extern "system" fn(EGLDisplay, EGLSurface) -> u32;
type FnDestroyContext = unsafe extern "system" fn(EGLDisplay, EGLContext) -> u32;
type FnBindApi = unsafe extern "system" fn(u32) -> u32;
type FnGetProcAddress = unsafe extern "system" fn(*const c_char) -> *const c_void;
type FnGetString = unsafe extern "system" fn(u32) -> *const c_char;
type FnGetError = unsafe extern "system" fn() -> i32;

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

pub struct EglCtx {
    _lib_egl: HMODULE,
    _lib_gles: HMODULE,
    display: EGLDisplay,
    surface: EGLSurface,
    context: EGLContext,
    get_proc_address: FnGetProcAddress,
    egl_terminate: Option<FnTerminate>,
    egl_destroy_surface: Option<FnDestroySurface>,
    egl_destroy_context: Option<FnDestroyContext>,
    egl_make_current: FnMakeCurrent,
    pub renderer: String,
    pub gl_vendor: String,
    pub gl_version: String,
    pub egl_version: String,
    pub create_mode: String,
    _device: Option<ID3D11Device>,
    _adapter: Option<IDXGIAdapter1>,
}

unsafe fn sym<T>(lib: HMODULE, name: &CStr) -> Option<T> {
    let f = GetProcAddress(lib, PCSTR(name.as_ptr() as *const u8));
    f.map(|f| std::mem::transmute_copy::<_, T>(&f))
}

impl EglCtx {
    /// `dir` holds libEGL.dll + libGLESv2.dll; `want` is the DXGI adapter name
    /// substring; `mode` is `device|device_id|default`.
    pub fn new(dir: &str, want: &str, mode: &str) -> Result<Self, String> {
        let egl_path = format!("{dir}\\libEGL.dll");
        let gles_path = format!("{dir}\\libGLESv2.dll");
        let w_egl = wide(&egl_path);
        let w_gles = wide(&gles_path);
        let lib_egl = unsafe {
            LoadLibraryExW(
                windows::core::PCWSTR(w_egl.as_ptr()),
                None,
                LOAD_WITH_ALTERED_SEARCH_PATH,
            )
        }
        .map_err(|e| format!("LoadLibraryExW {egl_path}: {e}"))?;
        let lib_gles = unsafe {
            LoadLibraryExW(
                windows::core::PCWSTR(w_gles.as_ptr()),
                None,
                LOAD_WITH_ALTERED_SEARCH_PATH,
            )
        }
        .map_err(|e| format!("LoadLibraryExW {gles_path}: {e}"))?;
        println!("angle.libegl={egl_path}");
        println!("angle.libgles={gles_path}");

        unsafe {
            let get_platform_display: Option<FnGetPlatformDisplay> =
                sym(lib_egl, c"eglGetPlatformDisplay");
            let get_platform_display_ext: Option<FnGetPlatformDisplayExt> =
                sym(lib_egl, c"eglGetPlatformDisplayEXT");
            let get_display: Option<FnGetDisplay> = sym(lib_egl, c"eglGetDisplay");
            let create_device: Option<FnCreateDevice> = sym(lib_egl, c"eglCreateDeviceANGLE");
            let egl_initialize: FnInitialize = sym(lib_egl, c"eglInitialize")
                .ok_or("eglInitialize missing")?;
            let egl_query_string: FnQueryString =
                sym(lib_egl, c"eglQueryString").ok_or("eglQueryString missing")?;
            let egl_choose_config: FnChooseConfig =
                sym(lib_egl, c"eglChooseConfig").ok_or("eglChooseConfig missing")?;
            let egl_create_pbuffer_surface: FnCreatePbufferSurface =
                sym(lib_egl, c"eglCreatePbufferSurface").ok_or("eglCreatePbufferSurface missing")?;
            let egl_create_context: FnCreateContext =
                sym(lib_egl, c"eglCreateContext").ok_or("eglCreateContext missing")?;
            let egl_make_current: FnMakeCurrent =
                sym(lib_egl, c"eglMakeCurrent").ok_or("eglMakeCurrent missing")?;
            let egl_bind_api: Option<FnBindApi> = sym(lib_egl, c"eglBindAPI");
            let get_proc_address: FnGetProcAddress =
                sym(lib_egl, c"eglGetProcAddress").ok_or("eglGetProcAddress missing")?;
            let egl_get_error: Option<FnGetError> = sym(lib_egl, c"eglGetError");

            let client_exts = {
                let p = egl_query_string(std::ptr::null_mut(), EGL_EXTENSIONS);
                if p.is_null() {
                    String::new()
                } else {
                    CStr::from_ptr(p).to_string_lossy().into_owned()
                }
            };
            println!("egl.client_extensions={client_exts}");

            // ---- adapter + D3D11 device ----
            let adapter: Option<IDXGIAdapter1> = pick_adapter(want);
            let mut device: Option<ID3D11Device> = None;
            let mut device_note = String::from("none");
            if mode == "device" || mode == "device_ext" {
                if let Some(a) = adapter.as_ref() {
                    match make_d3d11(a) {
                        Ok((d, note)) => {
                            device = Some(d);
                            device_note = note;
                        }
                        Err(e) => println!("angle.d3d11=FAIL {e}"),
                    }
                }
            }
            println!("angle.d3d11={device_note}");

            // ---- display attribute list ----
            let mut attrs: Vec<isize> = vec![
                EGL_PLATFORM_ANGLE_TYPE_ANGLE,
                EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE,
            ];
            let mut native: *mut c_void = std::ptr::null_mut();
            let mut used_mode = mode.to_string();
            if mode == "device" || mode == "device_ext" {
                let dev_ptr: *mut c_void = device
                    .as_ref()
                    .map(|d| windows::core::Interface::as_raw(d))
                    .unwrap_or(std::ptr::null_mut());
                let cd = create_device.ok_or("eglCreateDeviceANGLE missing")?;
                let egl_dev = cd(EGL_D3D11_DEVICE_ANGLE, dev_ptr, std::ptr::null());
                println!("angle.create_device_angle={egl_dev:p} eglGetError=0x{:04x}", egl_get_error.map(|f| f()).unwrap_or(-1));
                if !egl_dev.is_null() && !dev_ptr.is_null() {
                    native = egl_dev;
                } else {
                    used_mode = "default(fallback)".into();
                }
            }
            if used_mode == "device_id" {
                attrs.push(EGL_PLATFORM_ANGLE_DEVICE_TYPE_ANGLE);
                attrs.push(EGL_PLATFORM_ANGLE_DEVICE_TYPE_HARDWARE_ANGLE);
                attrs.push(EGL_PLATFORM_ANGLE_DEVICE_ID_ANGLE);
                attrs.push(0x1F12); // RTX 2060 device id
            }
            if used_mode == "devval" {
                // T1c scan: probe EGL_PLATFORM_ANGLE_DEVICE_TYPE_ANGLE values to
                // find the "high performance adapter" enum (Chromium's
                // --force_high_performance_gpu path through ANGLE).
                let raw = std::env::var("T1C_EGLDEVVAL").unwrap_or_default();
                let v = raw
                    .trim_start_matches("0x")
                    .parse::<isize>()
                    .or_else(|_| isize::from_str_radix(raw.trim_start_matches("0x"), 16))
                    .unwrap_or(0x320A);
                attrs.clear();
                attrs.push(EGL_PLATFORM_ANGLE_TYPE_ANGLE);
                attrs.push(EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE);
                attrs.push(EGL_PLATFORM_ANGLE_DEVICE_TYPE_ANGLE);
                attrs.push(v);
                attrs.push(EGL_NONE);
            }
            attrs.push(EGL_NONE);

            let display: EGLDisplay = if !native.is_null() {
                // ANGLE varies in which attribute sets it accepts together with
                // an EGLDeviceEXT; try the known-good Chromium shape first.
                let variants: Vec<Vec<isize>> = vec![
                    vec![
                        EGL_PLATFORM_ANGLE_TYPE_ANGLE,
                        EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE,
                        EGL_PLATFORM_ANGLE_DEVICE_TYPE_ANGLE,
                        EGL_PLATFORM_ANGLE_DEVICE_TYPE_HARDWARE_ANGLE,
                        EGL_NONE,
                    ],
                    vec![
                        EGL_PLATFORM_ANGLE_TYPE_ANGLE,
                        EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE,
                        EGL_NONE,
                    ],
                    vec![EGL_NONE],
                ];
                let mut out: EGLDisplay = std::ptr::null_mut();
                for (i, v) in variants.iter().enumerate() {
                    let d = if let Some(f) = get_platform_display {
                        f(EGL_PLATFORM_ANGLE_ANGLE, native, v.as_ptr())
                    } else if let Some(f) = get_platform_display_ext {
                        f(EGL_PLATFORM_ANGLE_ANGLE, native, v.as_ptr() as *const i32)
                    } else {
                        return Err("eglGetPlatformDisplay missing".into());
                    };
                    println!(
                        "angle.display(device,variant{i})={d:p} eglGetError=0x{:04x}",
                        egl_get_error.map(|f| f()).unwrap_or(-1)
                    );
                    if !d.is_null() {
                        out = d;
                        break;
                    }
                }
                if out.is_null() && mode == "device_ext" {
                    // EGL_EXT_platform_device: EGLDeviceEXT as the platform display.
                    let d = if let Some(f) = get_platform_display {
                        f(0x313F, native, [EGL_NONE].as_ptr())
                    } else {
                        std::ptr::null_mut()
                    };
                    println!(
                        "angle.display(device_ext)={d:p} eglGetError=0x{:04x}",
                        egl_get_error.map(|f| f()).unwrap_or(-1)
                    );
                    if !d.is_null() {
                        out = d;
                        used_mode = "device_ext".into();
                    }
                }
                if out.is_null() {
                    used_mode = "default(fallback:device-display-failed)".into();
                    let a2: Vec<isize> = vec![
                        EGL_PLATFORM_ANGLE_TYPE_ANGLE,
                        EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE,
                        EGL_PLATFORM_ANGLE_DEVICE_TYPE_ANGLE,
                        EGL_PLATFORM_ANGLE_DEVICE_TYPE_HARDWARE_ANGLE,
                        EGL_NONE,
                    ];
                    out = match get_platform_display {
                        Some(f) => f(EGL_PLATFORM_ANGLE_ANGLE, std::ptr::null_mut(), a2.as_ptr()),
                        None => match get_platform_display_ext {
                            Some(f) => f(
                                EGL_PLATFORM_ANGLE_ANGLE,
                                std::ptr::null_mut(),
                                a2.as_ptr() as *const i32,
                            ),
                            None => std::ptr::null_mut(),
                        },
                    };
                    println!("angle.display(default-fallback)={out:p}");
                }
                out
            } else if used_mode == "device_ext" {
                // EGL_EXT_platform_device: the EGLDeviceEXT is the display key.
                let d = if let Some(f) = get_platform_display {
                    f(0x313F, native, [EGL_NONE].as_ptr())
                } else if let Some(f) = get_platform_display_ext {
                    f(0x313F, native, [EGL_NONE as i32].as_ptr())
                } else {
                    std::ptr::null_mut()
                };
                println!("angle.display(device_ext)={d:p} eglGetError=0x{:04x}", egl_get_error.map(|f| f()).unwrap_or(-1));
                d
            } else if used_mode == "device_id" || used_mode == "devval" {
                let f = get_platform_display.ok_or("eglGetPlatformDisplay missing")?;
                let d = f(EGL_PLATFORM_ANGLE_ANGLE, std::ptr::null_mut(), attrs.as_ptr());
                println!("angle.display({used_mode})={d:p} eglGetError=0x{:04x}", egl_get_error.map(|f| f()).unwrap_or(-1));
                d
            } else {
                // Default: DXGI default adapter, hardware D3D11.
                let mut a2: Vec<isize> = vec![
                    EGL_PLATFORM_ANGLE_TYPE_ANGLE,
                    EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE,
                    EGL_PLATFORM_ANGLE_DEVICE_TYPE_ANGLE,
                    EGL_PLATFORM_ANGLE_DEVICE_TYPE_HARDWARE_ANGLE,
                    EGL_NONE,
                ];
                let d = if let Some(f) = get_platform_display {
                    f(EGL_PLATFORM_ANGLE_ANGLE, std::ptr::null_mut(), a2.as_ptr())
                } else if let Some(f) = get_platform_display_ext {
                    f(EGL_PLATFORM_ANGLE_ANGLE, std::ptr::null_mut(), a2.as_mut_ptr() as *const i32)
                } else {
                    get_display
                        .ok_or("no eglGetPlatformDisplay / eglGetDisplay")?
                        (std::ptr::null_mut())
                };
                println!("angle.display(default)={d:p}");
                d
            };
            if display.is_null() {
                let err = egl_get_error.map(|f| f()).unwrap_or(-1);
                return Err(format!("egl display is null (eglGetError=0x{err:04x})"));
            }

            let (mut maj, mut min) = (0i32, 0i32);
            let ok = egl_initialize(display, &mut maj, &mut min);
            println!("angle.eglInitialize={ok} version={maj}.{min}");
            if ok == 0 {
                let err = egl_get_error.map(|f| f()).unwrap_or(-1);
                return Err(format!(
                    "eglInitialize failed (0x{err:04x}); device-mode={used_mode}"
                ));
            }
            let egl_version = qs(egl_query_string, display, EGL_VERSION);
            let egl_vendor = qs(egl_query_string, display, EGL_VENDOR);
            let dpy_exts = qs(egl_query_string, display, EGL_EXTENSIONS);
            println!("angle.egl_vendor={egl_vendor}");
            println!("angle.egl_version={egl_version}");
            println!("angle.display_extensions={dpy_exts}");

            if let Some(f) = egl_bind_api {
                println!("angle.eglBindAPI(ES)={}", f(EGL_OPENGL_ES_API));
            }

            let cfg_attrs = [
                EGL_SURFACE_TYPE,
                EGL_PBUFFER_BIT,
                EGL_RENDERABLE_TYPE,
                EGL_OPENGL_ES3_BIT,
                EGL_RED_SIZE,
                8,
                EGL_GREEN_SIZE,
                8,
                EGL_BLUE_SIZE,
                8,
                EGL_ALPHA_SIZE,
                8,
                EGL_NONE as i32,
            ];
            let mut cfg: EGLConfig = std::ptr::null_mut();
            let mut n = 0i32;
            let ok = egl_choose_config(display, cfg_attrs.as_ptr(), &mut cfg, 1, &mut n);
            println!("angle.eglChooseConfig={ok} n={n} cfg={cfg:p}");
            if ok == 0 || n == 0 {
                return Err("eglChooseConfig found no ES3 pbuffer config".into());
            }
            let surf_attrs = [EGL_WIDTH, 64, EGL_HEIGHT, 64, EGL_NONE as i32];
            let surface = egl_create_pbuffer_surface(display, cfg, surf_attrs.as_ptr());
            if surface.is_null() {
                let err = egl_get_error.map(|f| f()).unwrap_or(-1);
                return Err(format!("eglCreatePbufferSurface null (0x{err:04x})"));
            }
            let ctx_attrs = [EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE as i32];
            let context = egl_create_context(display, cfg, std::ptr::null_mut(), ctx_attrs.as_ptr());
            if context.is_null() {
                let err = egl_get_error.map(|f| f()).unwrap_or(-1);
                return Err(format!("eglCreateContext null (0x{err:04x})"));
            }
            let ok = egl_make_current(display, surface, surface, context);
            println!("angle.eglMakeCurrent={ok}");
            if ok == 0 {
                let err = egl_get_error.map(|f| f()).unwrap_or(-1);
                return Err(format!("eglMakeCurrent failed (0x{err:04x})"));
            }

            let get_string: FnGetString = {
                let p = get_proc_address(c"glGetString".as_ptr());
                if !p.is_null() {
                    std::mem::transmute::<*const c_void, FnGetString>(p)
                } else {
                    let f = GetProcAddress(lib_gles, PCSTR(c"glGetString".as_ptr() as *const u8));
                    match f {
                        Some(f) => std::mem::transmute_copy::<_, FnGetString>(&f),
                        None => return Err("glGetString missing".into()),
                    }
                }
            };
            let renderer = gs(get_string, GL_RENDERER);
            let gl_vendor = gs(get_string, GL_VENDOR);
            let gl_version = gs(get_string, GL_VERSION);
            println!("gl.vendor={gl_vendor}");
            println!("gl.renderer={renderer}");
            println!("gl.version={gl_version}");

            Ok(Self {
                _lib_egl: lib_egl,
                _lib_gles: lib_gles,
                display,
                surface,
                context,
                get_proc_address,
                egl_terminate: sym(lib_egl, c"eglTerminate"),
                egl_destroy_surface: sym(lib_egl, c"eglDestroySurface"),
                egl_destroy_context: sym(lib_egl, c"eglDestroyContext"),
                egl_make_current,
                renderer,
                gl_vendor,
                gl_version,
                egl_version,
                create_mode: used_mode,
                _device: device,
                _adapter: adapter,
            })
        }
    }

    /// Skia GL interface assembled from `eglGetProcAddress` (+ libGLESv2 fallback).
    pub fn interface(&self) -> Option<skia_safe::gpu::gl::Interface> {
        let gp = self.get_proc_address;
        let gles = self._lib_gles;
        let iface = skia_safe::gpu::gl::Interface::new_load_with_cstr(move |name: &CStr| {
            let p = unsafe { gp(name.as_ptr()) };
            if !p.is_null() {
                return p;
            }
            unsafe {
                GetProcAddress(gles, PCSTR(name.as_ptr() as *const u8))
                    .map(|f| std::mem::transmute_copy::<_, usize>(&f) as *const c_void)
                    .unwrap_or(std::ptr::null())
            }
        })?;
        if iface.validate() {
            Some(iface)
        } else {
            println!("angle.interface.validate=false");
            None
        }
    }
}

fn qs(f: FnQueryString, d: EGLDisplay, name: i32) -> String {
    let p = unsafe { f(d, name) };
    if p.is_null() {
        String::new()
    } else {
        unsafe { CStr::from_ptr(p).to_string_lossy().into_owned() }
    }
}

fn gs(f: FnGetString, name: u32) -> String {
    let p = unsafe { f(name) };
    if p.is_null() {
        String::new()
    } else {
        unsafe { CStr::from_ptr(p).to_string_lossy().into_owned() }
    }
}

/// DXGI adapter whose description contains `want` (case-insensitive).
pub fn pick_adapter(want: &str) -> Option<IDXGIAdapter1> {
    unsafe {
        let factory: windows::Win32::Graphics::Dxgi::IDXGIFactory1 =
            windows::Win32::Graphics::Dxgi::CreateDXGIFactory1().ok()?;
        for i in 0..16u32 {
            let a = factory.EnumAdapters1(i).ok()?;
            let desc = a.GetDesc1().ok()?;
            let len = desc.Description.iter().position(|&c| c == 0).unwrap_or(0);
            let name = String::from_utf16_lossy(&desc.Description[..len]);
            let hit = want.eq_ignore_ascii_case("any")
                || name.to_ascii_uppercase().contains(&want.to_ascii_uppercase());
            println!("angle.dxgi#{i} {name}");
            if hit && (desc.Flags & 2) == 0 {
                return Some(a);
            }
        }
        None
    }
}

fn make_d3d11(adapter: &IDXGIAdapter1) -> Result<(ID3D11Device, String), String> {
    let mut dev: Option<ID3D11Device> = None;
    let mut fl = D3D_FEATURE_LEVEL_11_0;
    unsafe {
        D3D11CreateDevice(
            adapter,
            D3D_DRIVER_TYPE_UNKNOWN,
            HMODULE::default(),
            D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            Some(&[D3D_FEATURE_LEVEL_11_0]),
            D3D11_SDK_VERSION,
            Some(&mut dev),
            Some(&mut fl),
            None,
        )
        .map_err(|e| format!("D3D11CreateDevice: {e}"))?;
    }
    let d = dev.ok_or("D3D11CreateDevice returned null")?;
    Ok((d, format!("OK feature_level={fl:?}")))
}

impl Drop for EglCtx {
    fn drop(&mut self) {
        unsafe {
            let _ = (self.egl_make_current)(
                self.display,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            );
            if let Some(f) = self.egl_destroy_context {
                let _ = f(self.display, self.context);
            }
            if let Some(f) = self.egl_destroy_surface {
                let _ = f(self.display, self.surface);
            }
            if let Some(f) = self.egl_terminate {
                let _ = f(self.display);
            }
        }
    }
}
