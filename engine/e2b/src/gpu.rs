//! T1b GPU plumbing: a headless D3D12 Ganesh DirectContext on an explicitly
//! selected adapter, a render-target surface, flush, and readback.
//!
//! This is the T0b-proven path (`engine/gpu-probe`) minus the probe prints and
//! plus a full-surface readback. No window, no swapchain, no D3D11on12.

#![cfg(windows)]

use skia_safe::{
    gpu, surfaces, AlphaType, ColorType, ImageInfo, Surface,
};
use crate::angle;
use windows::core::{Interface, IUnknown};
use windows::Win32::Graphics::Direct3D::D3D_FEATURE_LEVEL_11_0;
use windows::Win32::Graphics::Direct3D12::{
    D3D12_COMMAND_LIST_TYPE_DIRECT, D3D12_COMMAND_QUEUE_DESC, D3D12_COMMAND_QUEUE_FLAG_NONE,
    D3D12CreateDevice, ID3D12CommandQueue, ID3D12Device,
};
use windows::Win32::Graphics::Dxgi::{CreateDXGIFactory1, IDXGIAdapter1, IDXGIFactory1};

pub struct AdapterInfo {
    pub index: u32,
    pub name: String,
    pub vram: u64,
    pub flags: u32,
}

pub struct Gpu {
    ctx: gpu::DirectContext,
    adapter_index: u32,
    adapter_name: String,
    feature_level: String,
    backend: String,
    max_texture_size: usize,
    pub props: Option<skia_safe::SurfaceProps>,
    _angle: Option<angle::EglCtx>,
}

impl Gpu {
    pub fn adapter_name(&self) -> &str {
        &self.adapter_name
    }
    pub fn adapter_index(&self) -> u32 {
        self.adapter_index
    }
    pub fn backend(&self) -> &str {
        &self.backend
    }
    pub fn feature_level(&self) -> &str {
        &self.feature_level
    }
    pub fn max_texture_size(&self) -> usize {
        self.max_texture_size
    }

    /// Enumerate DXGI adapters (`#0..#n`) and create a D3D12 device on the
    /// requested one. `want` is `nvidia`, `amd` or `#<index>`.
    pub fn new_d3d12(want: &str) -> Result<Self, String> {
        let factory: IDXGIFactory1 = unsafe { CreateDXGIFactory1() }
            .map_err(|e| format!("CreateDXGIFactory1: {e}"))?;
        let mut all: Vec<(u32, IDXGIAdapter1, AdapterInfo)> = Vec::new();
        for i in 0..16u32 {
            match unsafe { factory.EnumAdapters1(i) } {
                Ok(a) => {
                    let desc = unsafe { a.GetDesc1() }.map_err(|e| format!("GetDesc1: {e}"))?;
                    let len = desc.Description.iter().position(|&c| c == 0).unwrap_or(0);
                    let name = String::from_utf16_lossy(&desc.Description[..len]);
                    let info = AdapterInfo {
                        index: i,
                        name,
                        vram: desc.DedicatedVideoMemory as u64,
                        flags: desc.Flags,
                    };
                    println!(
                        "dxgi.adapter=#{} {} flags=0x{:x} vram={}",
                        info.index, info.name, info.flags, info.vram
                    );
                    all.push((i, a, info));
                }
                Err(_) => break,
            }
        }
        let picked = match want {
            "nvidia" => all.iter().position(|(_, _, a)| {
                a.name.to_ascii_uppercase().contains("NVIDIA") && a.flags & 2 == 0
            }),
            "amd" => all.iter().position(|(_, _, a)| {
                a.name.to_ascii_uppercase().contains("AMD") && a.flags & 2 == 0
            }),
            w if w.starts_with('#') => {
                let idx: u32 = w[1..].parse().map_err(|_| format!("bad adapter {w}"))?;
                all.iter().position(|(i, _, _)| *i == idx)
            }
            _ => None,
        }
        .ok_or_else(|| format!("adapter '{want}' not found"))?;

        let (idx, adapter, info) = all.into_iter().nth(picked).unwrap();
        let unk: IUnknown = adapter
            .cast()
            .map_err(|e| format!("adapter->IUnknown: {e}"))?;
        let mut device_out: Option<ID3D12Device> = None;
        unsafe { D3D12CreateDevice(&unk, D3D_FEATURE_LEVEL_11_0, &mut device_out) }
            .map_err(|e| format!("D3D12CreateDevice: {e}"))?;
        let device = device_out.ok_or("D3D12CreateDevice returned null")?;
        let queue: ID3D12CommandQueue = unsafe {
            device.CreateCommandQueue(&D3D12_COMMAND_QUEUE_DESC {
                Type: D3D12_COMMAND_LIST_TYPE_DIRECT,
                Priority: 0,
                Flags: D3D12_COMMAND_QUEUE_FLAG_NONE,
                NodeMask: 0,
            })
        }
        .map_err(|e| format!("CreateCommandQueue: {e}"))?;
        let bcx = gpu::d3d::BackendContext {
            adapter: adapter.clone(),
            device,
            queue,
            memory_allocator: None,
            protected_context: gpu::Protected::No,
        };
        let ctx = unsafe { gpu::direct_contexts::make_d3d(&bcx, None) }
            .ok_or("gpu::direct_contexts::make_d3d returned None")?;
        println!(
            "d3d12.ctx=OK backend={:?} max_texture_size={}",
            ctx.backend(),
            ctx.max_texture_size()
        );
        Ok(Gpu {
            max_texture_size: ctx.max_texture_size() as usize,
            adapter_index: idx,
            adapter_name: info.name,
            feature_level: "11_0".into(),
            backend: format!("D3D12/Ganesh ({:?})", ctx.backend()),
            ctx,
            props: None,
            _angle: None,
        })
    }

    /// ANGLE/EGL context on an explicit D3D11 adapter (the Chromium raster path).
    pub fn new_angle(dir: &str, want: &str, mode: &str) -> Result<Self, String> {
        let egl = angle::EglCtx::new(dir, want, mode)?;
        let iface = egl
            .interface()
            .ok_or("Skia GL interface assembly/validate failed")?;
        let ctx = gpu::direct_contexts::make_gl(iface, None)
            .ok_or("gpu::direct_contexts::make_gl returned None")?;
        println!(
            "angle.skia_ctx=OK backend={:?} max_texture_size={}",
            ctx.backend(),
            ctx.max_texture_size()
        );
        Ok(Gpu {
            max_texture_size: ctx.max_texture_size() as usize,
            adapter_index: 0,
            adapter_name: egl.renderer.clone(),
            feature_level: egl.gl_version.clone(),
            backend: format!("ANGLE/EGL (GaneshGL {:?})", ctx.backend()),
            ctx,
            props: None,
            _angle: Some(egl),
        })
    }

    /// Render `f` into a `w*h` RGBA8888 GPU surface, flush, read back unpremul
    /// RGBA (top-left origin). Returns the pixel buffer and the flush result.
    pub fn render_rgba8(
        &mut self,
        w: i32,
        h: i32,
        f: impl FnOnce(&skia_safe::Canvas),
    ) -> Result<Vec<u8>, String> {
        // Chromium's exporter canvas is `{alpha:false}` (core/exporter.ts), i.e.
        // an OPAQUE surface, which is what enables LCD/ClearType text there.
        // T1C_ALPHA=premul restores the T1b premultiplied surface for A/B.
        let alpha = match std::env::var("T1C_ALPHA").ok().as_deref() {
            Some("premul") => AlphaType::Premul,
            _ => AlphaType::Opaque,
        };
        let info = ImageInfo::new(
            (w, h),
            ColorType::RGBA8888,
            alpha,
            Some(skia_safe::ColorSpace::new_srgb()),
        );
        let mut surface: Surface = gpu::surfaces::render_target(
            &mut self.ctx,
            gpu::Budgeted::Yes,
            &info,
            None,
            gpu::SurfaceOrigin::TopLeft,
            self.props.as_ref(),
            None,
            None,
        )
        .ok_or_else(|| format!("render_target {w}x{h} failed"))?;
        {
            let canvas = surface.canvas();
            canvas.clear(skia_safe::Color::BLACK);
            f(canvas);
        }
        self.ctx
            .flush_and_submit()
            .map_err(|e| format!("flush_and_submit: {e:?}"))?;
        let image = surface.image_snapshot();
        let mut pixels = vec![0u8; (w as usize) * (h as usize) * 4];
        let dst = ImageInfo::new(
            (w, h),
            ColorType::RGBA8888,
            AlphaType::Unpremul,
            Some(skia_safe::ColorSpace::new_srgb()),
        );
        let ok = image.read_pixels_with_context(
            &mut self.ctx,
            &dst,
            &mut pixels,
            (w as usize) * 4,
            (0, 0),
            skia_safe::image::CachingHint::Allow,
        );
        if !ok {
            return Err("read_pixels_with_context failed".into());
        }
        Ok(pixels)
    }

    pub fn abandon(&mut self) {
        self.ctx.abandon();
    }
}

/// Encode a CPU RGBA8888 unpremul buffer as PNG through Skia.
pub fn encode_png(pixels: &[u8], w: i32, h: i32, path: &std::path::Path) -> Result<(), String> {
    let info = ImageInfo::new(
        (w, h),
        ColorType::RGBA8888,
        AlphaType::Unpremul,
        Some(skia_safe::ColorSpace::new_srgb()),
    );
    let data = skia_safe::Data::new_copy(pixels);
    let image = skia_safe::images::raster_from_data(&info, data, (w as usize) * 4)
        .ok_or("raster_from_data failed")?;
    let png = image
        .encode(None, skia_safe::EncodedImageFormat::PNG, 100)
        .ok_or("PNG encode failed")?;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("mkdir: {e}"))?;
    }
    std::fs::write(path, png.as_bytes()).map_err(|e| format!("write {}: {e}", path.display()))
}

/// A CPU raster surface, used only for offscreen intermediates (e.g. the glass
/// backdrop's 330x220 downsample) that Canvas2D would make with a 2D canvas.
pub fn raster_surface(w: i32, h: i32) -> Surface {
    let info = ImageInfo::new(
        (w, h),
        ColorType::RGBA8888,
        AlphaType::Premul,
        Some(skia_safe::ColorSpace::new_srgb()),
    );
    surfaces::raster(&info, None, None).expect("raster surface alloc")
}
