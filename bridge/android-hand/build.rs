//! Link the Android platform GL libraries used by `src/egl.rs`.
//!
//! Both are NDK system libraries (`sysroot/usr/lib/<abi>/<api>/libEGL.so`,
//! `libGLESv2.so`), so the linker only needs their sonames — no path or
//! `dlopen` dance. They are emitted for the `cargo build`/`cargo bench` link
//! only: an APK that consumes the staticlib links them from the app's
//! `CMakeLists.txt` (`target_link_libraries(… EGL GLESv2 …)`, which already
//! exists because Skia's GL objects reference `eglGetProcAddress`).
//!
//! The host (`x86_64-pc-windows-msvc`) build must stay untouched: the GPU
//! preview path is `#[cfg(target_os = "android")]`, and linking EGL/GLESv2 on a
//! developer machine would break `cargo test`.

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-env-changed=FM_GPU_PREVIEW");

    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if target_os == "android" {
        println!("cargo:rustc-link-lib=EGL");
        println!("cargo:rustc-link-lib=GLESv2");
    }
}
