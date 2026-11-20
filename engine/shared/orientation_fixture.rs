//! EXIF 方向的测试夹具（两个 Android bridge 共用，`#[path]` 引入，`cfg(test)` only）。
//!
//! 为什么要共享：方向这件事两边各有一份解码入口（`bridge/android` 的 `load_photo`、
//! `bridge/android-hand` 的 `load_image`），以前**两边都**用「宽高是否互换」判断 Skia
//! 有没有应用 origin —— 镜像(2)、180°(3)、镜像+180°(4) 宽高不变，于是二次旋转把方向
//! 还原回原始。夹具放在这里，保证两边被测的是同一组像素与同一个期望。
//!
//! 四象限彩色图（TL 红 / TR 蓝 / BL 绿 / BR 白）：四种颜色正好把 1 / 2 / 3 / 4 / 6 / 8
//! 六个方向区分开，看左上角落在哪个颜色就知道转了几次、往哪个方向转。

use skia_safe::{
    surfaces, AlphaType, Color, ColorSpace, ColorType, EncodedImageFormat, Image, ImageInfo,
    Paint, Rect,
};

/// 64×48 的四象限图（画布实际像素，不含任何 EXIF）。
pub(crate) fn quadrants() -> Image {
    let (w, h) = (64, 48);
    let mut surface = surfaces::raster_n32_premul((w, h)).expect("raster surface");
    let canvas = surface.canvas();
    for (x, y, color) in [
        (0.0f32, 0.0f32, 0xFFFF0000u32), // TL 红
        (32.0, 0.0, 0xFF0000FF),          // TR 蓝
        (0.0, 24.0, 0xFF00FF00),          // BL 绿
        (32.0, 24.0, 0xFFFFFFFF),         // BR 白
    ] {
        let mut paint = Paint::default();
        paint.set_anti_alias(false);
        paint.set_color(Color::new(color));
        canvas.draw_rect(Rect::from_xywh(x, y, 32.0, 24.0), &paint);
    }
    surface.image_snapshot()
}

/// 把四象限图编码成 JPEG，并在 SOI 之后插入只含 Orientation 的 APP1(EXIF)。
pub(crate) fn jpeg_with_orientation(orientation: u16) -> Vec<u8> {
    let jpeg = quadrants()
        .encode_to_data(EncodedImageFormat::JPEG)
        .expect("jpeg encode")
        .as_bytes()
        .to_vec();
    assert_eq!(&jpeg[..2], &[0xFF, 0xD8], "fixture must start with SOI");
    // TIFF 小端：头 + 一条 IFD0（0x0112 SHORT 1，内联值）+ next=0
    let mut tiff = vec![b'I', b'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00];
    tiff.extend_from_slice(&1u16.to_le_bytes());
    tiff.extend_from_slice(&0x0112u16.to_le_bytes());
    tiff.extend_from_slice(&3u16.to_le_bytes());
    tiff.extend_from_slice(&1u32.to_le_bytes());
    tiff.extend_from_slice(&orientation.to_le_bytes());
    tiff.extend_from_slice(&[0, 0]);
    tiff.extend_from_slice(&0u32.to_le_bytes());
    let len = (2 + 6 + tiff.len()) as u16;
    let mut out = Vec::with_capacity(jpeg.len() + 2 + len as usize);
    out.extend_from_slice(&jpeg[..2]);
    out.extend_from_slice(&[0xFF, 0xE1]);
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(b"Exif\0\0");
    out.extend_from_slice(&tiff);
    out.extend_from_slice(&jpeg[2..]);
    out
}

/// 写一个临时夹具文件；调用方负责删除。
pub(crate) fn temp_jpeg(orientation: u16) -> std::path::PathBuf {
    let path = std::env::temp_dir().join(format!(
        "fm-orientation-{}-{}.jpg",
        std::process::id(),
        orientation
    ));
    std::fs::write(&path, jpeg_with_orientation(orientation)).expect("write orientation fixture");
    path
}

/// 左上象限中心（8, 6）的颜色。
pub(crate) fn corner(image: &Image) -> (u8, u8, u8) {
    let info = ImageInfo::new(
        (1, 1),
        ColorType::RGBA8888,
        AlphaType::Unpremul,
        Some(ColorSpace::new_srgb()),
    );
    let mut px = [0u8; 4];
    let ok = image.read_pixels(
        &info,
        &mut px,
        4,
        (8, 6),
        skia_safe::image::CachingHint::Disallow,
    );
    assert!(ok, "read_pixels");
    (px[0], px[1], px[2])
}

pub(crate) fn is_red(c: (u8, u8, u8)) -> bool {
    c.0 > 150 && c.1 < 100 && c.2 < 100
}
pub(crate) fn is_blue(c: (u8, u8, u8)) -> bool {
    c.2 > 150 && c.0 < 100 && c.1 < 100
}
pub(crate) fn is_green(c: (u8, u8, u8)) -> bool {
    c.1 > 120 && c.0 < 110 && c.2 < 110
}
pub(crate) fn is_white(c: (u8, u8, u8)) -> bool {
    c.0 > 180 && c.1 > 180 && c.2 > 180
}
