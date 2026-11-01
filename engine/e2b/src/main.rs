//! E2b — IR-driven native (Skia Ganesh GPU) render of five IR styles.
//!
//! Input is the serialized E1b-1 IR (`engine/data/ir/styles/<family>/<style>.json`),
//! the fixture photo, the EXIF source JPEG and the vendor bitmaps named by the
//! IR's `assets[key].file`. No oracle draw-ops are read: layout is solved from
//! the IR by `ir.rs` (port of `core/system/layout.ts`), and painted with the
//! T1c/T1d-proven primitives (Ganesh D3D12 on NVIDIA, Mitchell resampling,
//! grayscale AA, D-021 baseline). E2a gaps ported here: shortEdge reference
//! width, margins/frame bands, band background/plate, group width/height/
//! matchWidth/matchHeight, optional-node margin removal, text width/outline,
//! full template tokens, EXIF model/params/date, font key->face map, 8-digit
//! AARRGGBB app colours + Canvas RRGGBBAA shadow colours, themeAssets/foreground
//! lookup, and real asset-file resolution (webp decoded to PNG for Skia).

#![cfg(windows)]

mod angle;
mod brand;
mod exif;
mod gpu;
mod ir;
mod text;

use std::collections::HashMap;
use std::path::{Path as FsPath, PathBuf};

use serde_json::{json, Value};
use skia_safe::{
    canvas::SrcRectConstraint, Canvas, Color, Data, FilterMode, Image, MipmapMode, Paint, Rect,
    SamplingOptions,
};

use text::{FontSet, HAlign, TextEngine, TextSpec, VBaseline};

fn envf(name: &str, dflt: f32) -> f32 {
    std::env::var(name)
        .ok()
        .and_then(|v| v.parse::<f32>().ok())
        .unwrap_or(dflt)
}

/// P3c: read `design.params` (render-v1 control surface). Keys absent, empty or
/// at their default value are dropped, so an untouched design is a strict no-op.
fn parse_overrides(doc: &Value) -> ir::Overrides {
    let mut ov = ir::Overrides::default();
    let Some(params) = doc.pointer("/design/params").and_then(|v| v.as_object()) else {
        return ov;
    };
    for (k, v) in params {
        if let Some(p) = k.strip_prefix("slotText:") {
            if let Some(s) = v.as_str() {
                if !s.is_empty() {
                    ov.slot_text.insert(p.to_string(), s.to_string());
                }
            }
        } else if let Some(p) = k.strip_prefix("slotOff:") {
            if v.as_bool() == Some(false) {
                ov.slot_off.insert(p.to_string());
            }
        } else if let Some(p) = k.strip_prefix("slotAlpha:") {
            if let Some(n) = v.as_f64() {
                if n.is_finite() {
                    ov.slot_alpha.insert(p.to_string(), n as f32);
                }
            }
        } else if let Some(p) = k.strip_prefix("slotGravity:") {
            if let Some(s) = v.as_str() {
                let t = s.trim();
                if !t.is_empty() && t != "vendor" {
                    ov.slot_gravity.insert(p.to_string(), t.to_string());
                }
            }
        }
    }
    if let Some(arr) = params.get("slotOff").and_then(|v| v.as_array()) {
        for x in arr {
            if let Some(s) = x.as_str() {
                if !s.is_empty() {
                    ov.slot_off.insert(s.to_string());
                }
            }
        }
    }
    if let Some(s) = params
        .get("theme")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        ov.theme = Some(s.to_string());
    }
    if let Some(s) = params
        .get("brandLogoFile")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        ov.brand = Some(s.to_string());
    }
    if let Some(s) = params
        .get("stockAnchor")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("vendor"))
    {
        ov.stock_anchor = Some(s.to_string());
    }
    for (key, slot) in [("stockOffsetXPct", &mut ov.stock_offset.0), ("stockOffsetYPct", &mut ov.stock_offset.1)] {
        if let Some(n) = params.get(key).and_then(|v| v.as_f64()) {
            if n.is_finite() {
                *slot = n as f32;
            }
        }
    }
    ov
}

fn sampling_low() -> SamplingOptions {
    SamplingOptions {
        filter: FilterMode::Linear,
        ..SamplingOptions::default()
    }
}

/// Blink's high-quality image path, which scene.ts installs on the whole
/// canvas (`ctx.imageSmoothingQuality = 'high'`, never restored): an *upscaled*
/// bitmap goes through the Mitchell cubic, a *downscaled* one through bilinear
/// (the ZEISS grid 168→155.6 pins the first arm, the ZEISS stamp 221→237.1 the
/// second). `E3_IMG_FILTER=mitchell|linear` pins one arm for A/B measurement.
fn image_sampling(iw: f32, dw: f32) -> SamplingOptions {
    match std::env::var("E3_IMG_FILTER").ok().as_deref() {
        Some("mitchell") => return sampling_high(),
        Some("linear") => return sampling_low(),
        Some("linear-mip") => return SamplingOptions::new(FilterMode::Linear, MipmapMode::Linear),
        _ => {}
    }
    if dw < iw - 1e-3 {
        if std::env::var("E3_IMG_MIPS").ok().as_deref() == Some("1") {
            SamplingOptions::new(FilterMode::Linear, MipmapMode::Linear)
        } else {
            sampling_low()
        }
    } else {
        sampling_high()
    }
}

/// render-v1 user watermark: photo-box-normalised rect, contain + centre,
/// opacity multiplies alpha, clipped to the photo draw box.
struct OverlaySpec {
    image: Image,
    rect: [f32; 4],
    opacity: f32,
}

fn sampling_high() -> SamplingOptions {    // Blink `imageSmoothingQuality:'high'` == SkCubicResampler::Mitchell() and
    // NO mipmap (T1c). Any mipmap mode makes Ganesh drop cubic.
    let mode = std::env::var("T1C_HIGH").unwrap_or_else(|_| "mitchell".to_string());
    let mut s = SamplingOptions::default();
    match mode.as_str() {
        "linear" => s.filter = FilterMode::Linear,
        "mipmap-linear" => s = SamplingOptions::new(FilterMode::Linear, MipmapMode::Linear),
        "catmull" => {
            s.use_cubic = true;
            s.cubic = skia_safe::CubicResampler::catmull_rom();
        }
        _ => {
            s.use_cubic = true;
            s.cubic = skia_safe::CubicResampler::mitchell();
        }
    }
    s
}

/// Map a band-local rect through the band's whole-band rotation (about the
/// final box centre) into canvas space; used for the report's text mask.
fn rotated_rect(r: Rect, cx: f32, cy: f32, deg: f32) -> Rect {
    let th = deg.to_radians();
    let (sn, cs) = (th.sin(), th.cos());
    let pts = [
        (r.left, r.top),
        (r.left + r.width(), r.top),
        (r.left, r.top + r.height()),
        (r.left + r.width(), r.top + r.height()),
    ];
    let (mut x0, mut y0, mut x1, mut y1) = (f32::INFINITY, f32::INFINITY, f32::NEG_INFINITY, f32::NEG_INFINITY);
    for (x, y) in pts {
        let dx = x - cx;
        let dy = y - cy;
        let rx = cx + dx * cs - dy * sn;
        let ry = cy + dx * sn + dy * cs;
        x0 = x0.min(rx);
        y0 = y0.min(ry);
        x1 = x1.max(rx);
        y1 = y1.max(ry);
    }
    Rect::from_ltrb(x0, y0, x1, y1)
}

fn out_rect(r: ir::Rect, k: f32, rotated: bool, cx: f32, cy: f32, deg: f32) -> Rect {
    let c = Rect::from_xywh(r.x * k, r.y * k, r.w * k, r.h * k);
    if rotated {
        rotated_rect(c, cx, cy, deg)
    } else {
        c
    }
}

/// paint.ts `tinted()`: draw the bitmap 1:1 into an offscreen canvas, then
/// `source-in` fill with the colour, and resample the RESULT. E3_SLOT_TINT=canvas
/// reproduces that path; the default keeps the Skia colour-filter draw.
fn tinted_image(img: &Image, tint: &str) -> Option<Image> {
    let (w, h) = (img.width(), img.height());
    let mut surface = skia_safe::surfaces::raster_n32_premul((w, h))?;
    let c = surface.canvas();
    c.clear(Color::TRANSPARENT);
    c.draw_image(img, (0.0, 0.0), None);
    let mut p = Paint::default();
    p.set_color(css_argb(tint, 1.0));
    p.set_blend_mode(skia_safe::BlendMode::SrcIn);
    c.draw_paint(&p);
    Some(surface.image_snapshot())
}

fn draw_image_rect(
    canvas: &Canvas,
    img: &Image,
    src: Option<Rect>,
    dst: Rect,
    sampling: SamplingOptions,
    paint: Option<&Paint>,
) {
    let p = Paint::default();
    match src {
        Some(r) => canvas.draw_image_rect_with_sampling_options(
            img,
            Some((&r, SrcRectConstraint::Strict)),
            &dst,
            sampling,
            paint.unwrap_or(&p),
        ),
        None => canvas.draw_image_rect_with_sampling_options(img, None, &dst, sampling, paint.unwrap_or(&p)),
    };
}

// ---------------------------------------------------------------------------
// VectorDrawable art (`shape.vectorKey`), ported from paint.ts `paintVectorNode`:
// translate to the node box, scale from the art viewport, walk clip / group /
// path nodes; 8-digit fills are #AARRGGBB like app colours.
// ---------------------------------------------------------------------------

fn vnum(v: &Value, key: &str) -> Option<f32> {
    v.get(key).and_then(|x| x.as_f64()).map(|x| x as f32)
}

fn paint_vector_nodes(canvas: &Canvas, nodes: &[Value]) {
    let mut clips = 0usize;
    for n in nodes {
        if let Some(d) = n.get("clip").and_then(|x| x.as_str()) {
            if let Some(path) = skia_safe::Path::from_svg(d) {
                canvas.save();
                clips += 1;
                canvas.clip_path(&path, skia_safe::ClipOp::Intersect, true);
            }
        } else if let Some(children) = n.get("c").and_then(|x| x.as_array()) {
            canvas.save();
            if let (Some(tx), Some(ty)) = (vnum(n, "tx"), vnum(n, "ty")) {
                canvas.translate((tx, ty));
            }
            let (px, py) = (vnum(n, "px"), vnum(n, "py"));
            if let (Some(rot), Some(px), Some(py)) = (vnum(n, "rot"), px, py) {
                canvas.translate((px, py));
                canvas.rotate(rot, None);
                canvas.translate((-px, -py));
            }
            if let (Some(sx), Some(sy), Some(px), Some(py)) = (vnum(n, "sx"), vnum(n, "sy"), px, py) {
                canvas.translate((px, py));
                canvas.scale((sx, sy));
                canvas.translate((-px, -py));
            }
            paint_vector_nodes(canvas, children);
            canvas.restore();
        } else if let Some(d) = n.get("d").and_then(|x| x.as_str()) {
            let Some(mut path) = skia_safe::Path::from_svg(d) else {
                continue;
            };
            if n.get("rule").and_then(|x| x.as_str()) == Some("evenodd") {
                path.set_fill_type(skia_safe::PathFillType::EvenOdd);
            }
            if let Some(fill) = n.get("fill").and_then(|x| x.as_str()) {
                let fa = vnum(n, "fa").unwrap_or(1.0);
                let mut p = Paint::default();
                p.set_anti_alias(true);
                p.set_style(skia_safe::PaintStyle::Fill);
                p.set_color(css_argb(fill, fa));
                canvas.draw_path(&path, &p);
            }
            if let Some(stroke) = n.get("stroke").and_then(|x| x.as_str()) {
                let sa = vnum(n, "sa").unwrap_or(1.0);
                let mut p = Paint::default();
                p.set_anti_alias(true);
                p.set_style(skia_safe::PaintStyle::Stroke);
                p.set_color(css_argb(stroke, sa));
                p.set_stroke_width(vnum(n, "sw").unwrap_or(1.0));
                canvas.draw_path(&path, &p);
            }
        }
    }
    for _ in 0..clips {
        canvas.restore();
    }
}

fn paint_vector(canvas: &Canvas, art: &Value, b: Rect) {
    let vw = art.get("vw").and_then(|x| x.as_f64()).unwrap_or(1.0) as f32;
    let vh = art.get("vh").and_then(|x| x.as_f64()).unwrap_or(1.0) as f32;
    if vw <= 0.0 || vh <= 0.0 {
        return;
    }
    canvas.save();
    canvas.translate((b.left, b.top));
    canvas.scale((b.width() / vw, b.height() / vh));
    if let Some(tree) = art.get("tree").and_then(|x| x.as_array()) {
        paint_vector_nodes(canvas, tree);
    }
    canvas.restore();
}

/// App colours (`paint.ts/cssColor`): 8-digit values are **#AARRGGBB**
/// (Android convention), 6-digit are #RRGGBB; `alpha` multiplies.
fn css_argb(value: &str, alpha: f32) -> Color {
    let s = value.trim();
    if s.eq_ignore_ascii_case("white") {
        return Color::from_argb((255.0 * alpha).round() as u8, 255, 255, 255);
    }
    if s.eq_ignore_ascii_case("black") {
        return Color::from_argb((255.0 * alpha).round() as u8, 0, 0, 0);
    }
    if let Some(rest) = s.strip_prefix("rgba(").or_else(|| s.strip_prefix("rgb(")) {
        let inner = rest.trim_end_matches(')');
        let parts: Vec<&str> = inner.split(',').map(str::trim).collect();
        if parts.len() >= 3 {
            let r = parts[0].parse::<f32>().unwrap_or(0.0).clamp(0.0, 255.0);
            let g = parts[1].parse::<f32>().unwrap_or(0.0).clamp(0.0, 255.0);
            let b = parts[2].parse::<f32>().unwrap_or(0.0).clamp(0.0, 255.0);
            let a = if parts.len() >= 4 {
                parts[3].parse::<f32>().unwrap_or(1.0)
            } else {
                1.0
            };
            return Color::from_argb(
                (a.clamp(0.0, 1.0) * alpha * 255.0).round() as u8,
                r.round() as u8,
                g.round() as u8,
                b.round() as u8,
            );
        }
    }
    let hex = s.strip_prefix('#').unwrap_or(s);
    let parse = |a: &str| u8::from_str_radix(a, 16).unwrap_or(0);
    if hex.len() == 8 {
        let a = parse(&hex[0..2]);
        Color::from_argb(
            (a as f32 * alpha).round() as u8,
            parse(&hex[2..4]),
            parse(&hex[4..6]),
            parse(&hex[6..8]),
        )
    } else if hex.len() == 6 {
        Color::from_argb(
            (255.0 * alpha).round() as u8,
            parse(&hex[0..2]),
            parse(&hex[2..4]),
            parse(&hex[4..6]),
        )
    } else {
        Color::BLACK
    }
}

/// Canvas2D `shadowColor` follows CSS Color 4: 8-digit is **#RRGGBBAA**
/// (opposite byte order from `css_argb`). This is why the IR's vendor
/// #AARRGGBB shadows render with alpha 0 in the frozen painter.
fn css_canvas_rgba(value: &str) -> Option<Color> {
    let s = value.trim();
    let hex = s.strip_prefix('#').unwrap_or(s);
    let parse = |a: &str| u8::from_str_radix(a, 16).unwrap_or(0);
    if hex.len() == 8 {
        Some(Color::from_argb(
            parse(&hex[6..8]),
            parse(&hex[0..2]),
            parse(&hex[2..4]),
            parse(&hex[4..6]),
        ))
    } else if hex.len() == 6 {
        Some(Color::from_argb(255, parse(&hex[0..2]), parse(&hex[2..4]), parse(&hex[4..6])))
    } else {
        None
    }
}

/// paint.ts `themeBackground()`: an all-00 alpha falls back to a per-theme
/// plate, else `#1c1d1f`. Returns the string passed back through css_argb.
fn theme_background(theme_id: Option<&str>, raw: &str) -> String {
    let s = raw;
    // /^#(?:[0-9a-fA-F]{2})?([0-9a-fA-F]{6})/
    let hex = s.strip_prefix('#').unwrap_or("");
    let valid = if hex.len() == 8 { hex[2..].chars().all(|c| c.is_ascii_hexdigit()) } else if hex.len() == 6 { true } else { false };
    if valid {
        let alpha = if s.len() >= 9 { &s[1..3] } else { "FF" };
        if alpha != "00" {
            return if alpha == "FF" && s.len() >= 9 { s[0..9].to_string() } else { s[0..7].to_string() };
        }
    }
    let mut fallback = "#1c1d1f".to_string();
    if let Some(id) = theme_id {
        let v = match id {
            "gradient_color_red" => "#3b1516",
            "gradient_color_blue_dark" => "#101c2b",
            "gradient_color_blue_light" => "#dcebf1",
            "gradient_color_yellow" => "#d9c48a",
            "light_blur" => "#e9e9e9",
            "dark_blur" => "#151515",
            _ => "#1c1d1f",
        };
        fallback = v.to_string();
    }
    fallback
}

/// paint.ts `parseSolid()`: 8-digit app colours are #AARRGGBB.
fn parse_solid(value: &str) -> Option<(f32, f32, f32, f32)> {
    let s = value.trim().to_lowercase();
    if s == "white" {
        return Some((255.0, 255.0, 255.0, 1.0));
    }
    if s == "black" {
        return Some((0.0, 0.0, 0.0, 1.0));
    }
    let hex = s.strip_prefix('#')?;
    if hex.len() == 8 {
        let a = u8::from_str_radix(&hex[0..2], 16).ok()? as f32 / 255.0;
        let r = u8::from_str_radix(&hex[2..4], 16).ok()? as f32;
        let g = u8::from_str_radix(&hex[4..6], 16).ok()? as f32;
        let b = u8::from_str_radix(&hex[6..8], 16).ok()? as f32;
        Some((r, g, b, a))
    } else if hex.len() == 6 {
        let r = u8::from_str_radix(&hex[0..2], 16).ok()? as f32;
        let g = u8::from_str_radix(&hex[2..4], 16).ok()? as f32;
        let b = u8::from_str_radix(&hex[4..6], 16).ok()? as f32;
        Some((r, g, b, 1.0))
    } else {
        None
    }
}

/// paint.ts `lightSurface()`: the surface a brand wordmark must read on.
fn light_surface(value: &str) -> bool {
    match parse_solid(value) {
        Some((r, g, b, a)) if a >= 0.4 => {
            (r * 0.299 + g * 0.587 + b * 0.114) / 255.0 > 0.7
        }
        _ => false,
    }
}

fn halign(s: &str) -> HAlign {
    match s {
        "center" => HAlign::Center,
        "right" => HAlign::Right,
        _ => HAlign::Left,
    }
}

fn load_image(p: &FsPath) -> Result<Image, String> {
    let data = Data::from_filename(p).ok_or_else(|| format!("missing {}", p.display()))?;
    Image::from_encoded(data).ok_or_else(|| format!("decode failed {}", p.display()))
}

fn walk_basename(root: &FsPath, name: &str, depth: usize) -> Option<PathBuf> {
    if depth == 0 {
        return None;
    }
    let rd = std::fs::read_dir(root).ok()?;
    for e in rd.flatten() {
        let p = e.path();
        if p.is_file() {
            if p.file_name().and_then(|v| v.to_str()) == Some(name) {
                return Some(p);
            }
        } else if p.is_dir() {
            if let Some(hit) = walk_basename(&p, name, depth - 1) {
                return Some(hit);
            }
        }
    }
    None
}

/// Resolve the IR's `assets[key].file` against the same tree the app loads.
/// E1b-1 references e.g. `oppo/camerapro/x.webp` under `assets/system/`, but a
/// few styles store only the basename; the decoded PNG cache mirrors the file
/// path (Pillow decodes WebP because the T0b Skia prebuilt has no WebP codec).
fn resolved_asset_under_roots(candidate: &FsPath, canonical_roots: &[PathBuf]) -> Option<PathBuf> {
    let resolved = candidate.canonicalize().ok()?;
    canonical_roots
        .iter()
        .any(|root| resolved.starts_with(root))
        .then_some(resolved)
}

fn resolve_asset(file: &str, roots: &[PathBuf]) -> Option<PathBuf> {
    let canonical_roots = roots
        .iter()
        .filter_map(|root| root.canonicalize().ok())
        .collect::<Vec<_>>();
    let rel = PathBuf::from(file.replace('\\', "/"));
    let stem = rel.file_stem().and_then(|v| v.to_str()).unwrap_or(file);
    let name = rel.file_name().and_then(|v| v.to_str()).unwrap_or(file);
    for root in roots {
        let rels = [
            rel.clone(),
            rel.with_extension("png"),
            PathBuf::from("system").join(&rel),
            PathBuf::from("system").join(rel.with_extension("png")),
        ];
        for r in rels {
            let p = root.join(&r);
            if p.is_file() {
                if let Some(resolved) = resolved_asset_under_roots(&p, &canonical_roots) {
                    return Some(resolved);
                }
            }
        }
        let p = root.join("img").join(format!("{stem}.png"));
        if p.is_file() {
            if let Some(resolved) = resolved_asset_under_roots(&p, &canonical_roots) {
                return Some(resolved);
            }
        }
        let p = root.join(format!("{stem}.png"));
        if p.is_file() {
            if let Some(resolved) = resolved_asset_under_roots(&p, &canonical_roots) {
                return Some(resolved);
            }
        }
        if let Some(p) = walk_basename(root, name, 6) {
            if let Some(resolved) = resolved_asset_under_roots(&p, &canonical_roots) {
                return Some(resolved);
            }
        }
    }
    None
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let get = |name: &str, dflt: &str| -> String {
        args.iter()
            .position(|a| a == &format!("--{name}"))
            .and_then(|i| args.get(i + 1).cloned())
            .unwrap_or_else(|| dflt.to_string())
    };
    let style_path = PathBuf::from(get(
        "style",
        "D:/border&watermark/framemark/engine/data/ir/styles/oppo-gallery/hassel_text_style_2.json",
    ));
    let photo_path = PathBuf::from(get(
        "photo",
        "D:/border&watermark/_research/native/t1b/assets/img/photo-pil.png",
    ));
    // P3e: there is no implicit sample EXIF. An omitted --exif-source means
    // "no EXIF" (tokens empty), and pointing it at a file without a readable
    // EXIF block is a supported state, not an error.
    let exif_source = PathBuf::from(get("exif-source", ""));
    let fonts_root = PathBuf::from(get(
        "fonts",
        "D:/border&watermark/framemark/engine/assets/fonts",
    ));
    // Merged E3 cache: the union of the three family caches (e2b probe
    // bitmaps + `logos/` brand registry + the OPPO vendored PNGs), built once
    // so a single `--decoded` root serves every style family.
    let decoded_root = PathBuf::from(get(
        "decoded",
        "D:/border&watermark/_research/native/e3/decoded",
    ));
    let assets_root = PathBuf::from(get(
        "assets",
        "D:/border&watermark/framemark/src/renderer/src/assets",
    ));
    let t1b_root = PathBuf::from(get(
        "t1b",
        "D:/border&watermark/_research/native/t1b/assets",
    ));
    let out = PathBuf::from(get(
        "out",
        "D:/border&watermark/_research/native/e2b/out/raw.png",
    ));
    let report_path = PathBuf::from(get(
        "report",
        "D:/border&watermark/_research/native/e2b/reports/raw.json",
    ));
    // render-v1 design document: `design.text.fontScale` (D-023) and the P3c
    // control params (`design.params`). Absent/empty keys are a strict no-op.
    let design_doc: Option<Value> = {
        let design_path = get("design", "");
        if design_path.is_empty() {
            None
        } else {
            std::fs::read_to_string(&design_path)
                .ok()
                .and_then(|t| serde_json::from_str::<Value>(&t).ok())
        }
    };
    let font_scale = {
        let mut s = 1.0f32;
        if let Some(n) = design_doc
            .as_ref()
            .and_then(|v| v.pointer("/design/text/fontScale"))
            .and_then(|x| x.as_f64())
        {
            s = n as f32;
        }
        if let Some(n) = get("font-scale", "").parse::<f32>().ok() {
            s = n;
        }
        if !s.is_finite() {
            s = 1.0;
        }
        s = s.clamp(0.5, 2.0);
        if (s - 1.0).abs() < 0.001 {
            1.0
        } else {
            s
        }
    };
    let overrides = design_doc
        .as_ref()
        .map(parse_overrides)
        .unwrap_or_default();
    println!("e3.font_scale={font_scale}");
    if !overrides.slot_text.is_empty()
        || !overrides.slot_off.is_empty()
        || !overrides.slot_alpha.is_empty()
        || !overrides.slot_gravity.is_empty()
        || overrides.has_box_move()
        || overrides.theme.is_some()
        || overrides.brand.is_some()
    {
        println!(
            "e3.overrides text={} off={} alpha={} gravity={} anchor={:?} offset=({},{}) theme={:?} brand={:?}",
            overrides.slot_text.len(),
            overrides.slot_off.len(),
            overrides.slot_alpha.len(),
            overrides.slot_gravity.len(),
            overrides.stock_anchor,
            overrides.stock_offset.0,
            overrides.stock_offset.1,
            overrides.theme,
            overrides.brand
        );
    }
    let backend = get("backend", "d3d12");
    let adapter = get("adapter", "nvidia");
    let location = get("location", "");
    let custom = get("custom", "");

    println!("e2b.style={}", style_path.display());
    let raw = std::fs::read_to_string(&style_path).expect("read style");
    let style = ir::parse_style(&raw).expect("parse style");
    println!("e2b.style_id={} bands={}", style.id, style.bands.len());

    // paint.ts: `style.themes.find(t => t.id === (params.theme ?? style.defaultTheme))`.
    // CLI `--theme` wins; then the design params theme; then the style default.
    let theme_cli = get("theme", "");
    let theme_arg = if !theme_cli.is_empty() { Some(theme_cli) } else { overrides.theme.clone() };
    let theme = style.theme(theme_arg.as_deref());
    let theme_id = theme.map(|t| t.id.clone());
    let foreground = theme.map(|t| t.foreground.clone()).unwrap_or_default();
    let surface_bg = theme
        .and_then(|t| t.background.clone())
        .unwrap_or_else(|| style.background.clone());
    let brand_dark = !light_surface(&surface_bg);
    println!(
        "e3.theme={:?} default={:?} foreground={} brand_dark={} surface={}",
        theme_id,
        style.default_theme,
        foreground.len(),
        brand_dark,
        surface_bg
    );

    let exif = if exif_source.as_os_str().is_empty() {
        println!("e3.exif=absent (no --exif-source; EXIF tokens resolve empty)");
        exif::Exif::default()
    } else {
        match exif::read(&exif_source) {
            Ok(e) => {
                println!(
                    "e2b.exif src={} make={:?} model={:?} date={:?} focal={:?} f={:?} shutter={:?} iso={:?}",
                    exif_source.display(), e.make, e.model, e.date_time, e.focal_35mm.or(e.focal), e.aperture, e.shutter, e.iso
                );
                e
            }
            Err(e) => {
                // Absent EXIF is a contract state (tokens empty, lines dropped),
                // never a fatal error and never replaced with a bundled sample.
                eprintln!("e3.exif=unreadable ({e}); EXIF tokens resolve empty");
                exif::Exif::default()
            }
        }
    };

    let photo = load_image(&photo_path).expect("photo");
    let (pw, ph) = (photo.width() as f32, photo.height() as f32);
    println!("e2b.photo={}x{}", pw as i32, ph as i32);

    // render-v1 `overlay`: user watermark, above the photo and below the
    // template's remaining bands. Malformed input is fatal, never a silent skip.
    let overlay: Option<OverlaySpec> = {
        let path = get("overlay", "");
        if path.is_empty() {
            None
        } else {
            let fail = |msg: String| -> ! {
                eprintln!("e2b.overlay.error={msg}");
                std::process::exit(2)
            };
            let image = match load_image(FsPath::new(&path)) {
                Ok(i) => i,
                Err(e) => fail(format!("overlay: {e}")),
            };
            let rect = {
                let raw = get("overlay-rect", "0,0,1,1");
                let parts: Vec<&str> = raw.split(',').collect();
                if parts.len() != 4 {
                    fail(format!(
                        "overlay: --overlay-rect needs 4 comma-separated numbers (x,y,w,h), got '{raw}'"
                    ));
                }
                let mut r = [0f32; 4];
                for (k, p) in parts.iter().enumerate() {
                    r[k] = match p.trim().parse::<f32>() {
                        Ok(v) => v,
                        Err(_) => fail(format!(
                            "overlay: --overlay-rect value '{}' is not a number",
                            p.trim()
                        )),
                    };
                }
                r
            };
            for (k, v) in rect.iter().enumerate() {
                if !v.is_finite() || *v < 0.0 || *v > 1.0 {
                    fail(format!(
                        "overlay: --overlay-rect must be four numbers in 0..1 (x,y,w,h); index {k} is {v}"
                    ));
                }
            }
            let opacity = match get("overlay-opacity", "1").parse::<f32>() {
                Ok(v) => v,
                Err(_) => fail("overlay: --overlay-opacity is not a number".to_string()),
            };
            if !opacity.is_finite() || !(0.0..=1.0).contains(&opacity) {
                fail(format!("overlay: --overlay-opacity must be in 0..1, got {opacity}"));
            }
            println!(
                "e2b.overlay={} {}x{} rect={:?} opacity={}",
                path,
                image.width(),
                image.height(),
                rect,
                opacity
            );
            Some(OverlaySpec { image, rect, opacity })
        }
    };

    let mut gaps = ir::Gaps::default();
    gaps.add("env: model/imageParams/time/timeLocation/template tokens resolved from EXIF + env (IR stores no string)");
    gaps.add("env: text.font key -> engine/assets/fonts face via core/system/fonts.ts mapping + fonts.json files");
    gaps.add("rule: image.scale=contain draw rect is a renderer rule (node box vs asset aspect)");
    gaps.add("rule: overlay band content is clamped to the 2.5%/4% inner box and translated (preservePosition)");
    gaps.add("rule: 8-digit app colours are #AARRGGBB; Canvas shadowColor is #RRGGBBAA (vendor #AARRGGBB shadows are invisible, matching the golden)");

    let mut images: HashMap<String, (Image, PathBuf)> = HashMap::new();
    let mut roots = vec![decoded_root.clone(), assets_root.join("system"), assets_root.clone(), t1b_root.clone()];
    roots.dedup();
    for a in &style.assets {
        match resolve_asset(&a.file, &roots) {
            Some(p) => {
                match load_image(&p) {
                    Ok(img) => {
                        println!(
                            "e2b.asset key={} ir_file={} ir_box={}x{} decoded={}x{} path={}",
                            a.key, a.file, a.width, a.height, img.width(), img.height(), p.display()
                        );
                        images.insert(a.key.clone(), (img, p));
                    }
                    Err(e) => gaps.add(format!("asset '{}' resolve={} but decode failed: {e}", a.key, p.display())),
                }
            }
            None => gaps.add(format!("asset '{}' ({}) not found under the asset roots (E3 ships the 119 bitmaps)", a.key, a.file)),
        }
    }
    let mut brands = brand::BrandLib::load(&decoded_root.join("logos"));
    // Inspector brand pick (decoded/logos stem) wins over the EXIF brand.
    let brand_key = overrides
        .brand
        .clone()
        .or_else(|| brand::match_brand(&exif.make, &exif.model).map(str::to_string));
    println!(
        "e3.brand make={:?} model={:?} key={:?} dark={}",
        exif.make, exif.model, brand_key, brand_dark
    );
    gaps.add("rule: isBrandLogo substitutes the EXIF brand wordmark (matchBrand/chooseBrandSlot/brandSlotRect from core/system/assets.ts+paint.ts); baked vendor bitmap only supplies the box/ink");

    let fonts = FontSet::load(&fonts_root).expect("fonts");
    let mut te = TextEngine::new();
    te.subpixel = true;
    te.linear_metrics = true;
    te.edging = skia_safe::font::Edging::AntiAlias;
    te.embolden = false;
    te.stroke_w = 0.0;
    te.fallback = fonts.fallback_face();
    if std::env::var("E2B_LCD").ok().as_deref() == Some("1") {
        te.edging = skia_safe::font::Edging::SubpixelAntiAlias;
    }
    match std::env::var("E2B_HINTING").unwrap_or_else(|_| "slight".into()).as_str() {
        "none" => te.hinting = skia_safe::FontHinting::None,
        "normal" => te.hinting = skia_safe::FontHinting::Normal,
        "full" => te.hinting = skia_safe::FontHinting::Full,
        _ => te.hinting = skia_safe::FontHinting::Slight,
    }

    // Text-line switches from the design document (`design.text.showModel/…`);
    // absent keys keep the frozen behaviour (all shown).
    let text_flag = |key: &str| -> bool {
        design_doc
            .as_ref()
            .and_then(|v| v.pointer(&format!("/design/text/{key}")))
            .and_then(|x| x.as_bool())
            .unwrap_or(true)
    };
    let env = ir::Env {
        pw,
        ph,
        exif,
        location: location.clone(),
        custom: custom.clone(),
        show_model: text_flag("showModel"),
        show_exif: text_flag("showExif"),
        show_date: text_flag("showDate"),
        font_scale,
        te: &te,
        fonts: &fonts,
        theme_id: theme_id.clone(),
        foreground: foreground.clone(),
        ov: &overrides,
    };
    let layout = ir::solve(&style, &env, &mut gaps).expect("layout");
    let base_w = layout.photo.w;
    let k = pw / base_w;
    let cw = (layout.w * k).round() as i32;
    let ch = (layout.h * k).round() as i32;
    println!(
        "e2b.layout_units={}x{} base_w={} k={:.6} canvas={}x{} margins={:?}",
        layout.w, layout.h, base_w, k, cw, ch, style.margins
    );

    let bg_value = theme_background(theme_id.as_deref(), &surface_bg);
    let bg_fill = if style.transparent_background { None } else { Some(bg_value.clone()) };
    println!(
        "e2b.background theme={:?} theme_bg={} style_bg={} transparent={} fill={:?}",
        theme_id, surface_bg, style.background, style.transparent_background, bg_fill
    );

    let backend = backend.as_str();
    let mut gpu = match backend {
        "angle" => {
            let dir = std::env::var("T1C_ANGLE_DIR").unwrap_or_else(|_| {
                r"C:\Program Files (x86)\Steam\bin\cef\cef.win64".to_string()
            });
            let mode = std::env::var("T1C_EGLMODE").unwrap_or_else(|_| "device".to_string());
            let want = std::env::var("T1C_ADAPTER").unwrap_or_else(|_| adapter.clone());
            gpu::Gpu::new_angle(&dir, &want, &mode).expect("ANGLE context")
        }
        _ => gpu::Gpu::new_d3d12(&adapter).expect("D3D12 context"),
    };
    if get("probe", "0") == "1" {
        gpu.abandon();
        return;
    }

    let d021_override = std::env::var("E2B_D021_EM").ok().and_then(|v| v.parse::<f32>().ok());
    let items_out = std::cell::RefCell::new(Vec::<Value>::new());
    let vectors: Value =
        serde_json::from_str(include_str!("vivo_vector_art.json")).expect("vector art");

    let pixels = gpu
        .render_rgba8(cw, ch, |canvas| {
            if let Some(bg) = &bg_fill {
                let mut p = Paint::default();
                p.set_anti_alias(true);
                p.set_color(css_argb(bg, 1.0));
                // Chromium fills `layout.W * scale`, not the rounded canvas
                // size; on gr_style_1 that leaves a sub-pixel right/bottom edge
                // (2755.89 x 2916.67 into a 2756 x 2917 canvas).
                canvas.draw_rect(
                    Rect::from_xywh(0.0, 0.0, layout.w * k, layout.h * k),
                    &p,
                );
            }
            // drawFirst bands are not exercised by these five styles; keep the
            // same order rule as paint.ts anyway.
            let photo_rect = Rect::from_xywh(
                layout.photo.x * k,
                layout.photo.y * k,
                layout.photo.w * k,
                layout.photo.h * k,
            );
            let integral = (photo_rect.left() - photo_rect.left().round()).abs() < 1e-4
                && (photo_rect.top() - photo_rect.top().round()).abs() < 1e-4
                && (photo_rect.width() - pw).abs() < 1e-3
                && (photo_rect.height() - ph).abs() < 1e-3;
            let mode = std::env::var("E2B_PHOTO_MODE").unwrap_or_else(|_| "auto".into());
            let mut photo_sampling = match mode.as_str() {
                "nearest" => SamplingOptions::default(),
                "linear" => SamplingOptions {
                    filter: FilterMode::Linear,
                    ..SamplingOptions::default()
                },
                "catmull" => {
                    let mut s = SamplingOptions::default();
                    s.use_cubic = true;
                    s.cubic = skia_safe::CubicResampler::catmull_rom();
                    s
                }
                "mitchell" => sampling_high(),
                _ => {
                    if integral {
                        SamplingOptions::default()
                    } else if (photo_rect.width() - pw).abs() < 1e-3
                        && (photo_rect.height() - ph).abs() < 1e-3
                    {
                        // 1:1 image at a fractional offset: Blink's high-quality
                        // path is bilinear here, not cubic (E2b gr_style_1).
                        SamplingOptions {
                            filter: FilterMode::Linear,
                            ..SamplingOptions::default()
                        }
                    } else {
                        sampling_high()
                    }
                }
            };
            if std::env::var("E2B_PHOTO_MODE").ok().as_deref() == Some("linear") {
                photo_sampling.filter = FilterMode::Linear;
            }
            let snap = std::env::var("E2B_PHOTO_SNAP").unwrap_or_default();
            let (mut px, mut py) = (photo_rect.left(), photo_rect.top());
            match snap.as_str() {
                "round" => {
                    px = px.round();
                    py = py.round();
                }
                "floor" => {
                    px = px.floor();
                    py = py.floor();
                }
                "ceil" => {
                    px = px.ceil();
                    py = py.ceil();
                }
                _ => {}
            }
            px += envf("E2B_PHOTO_DX", 0.0);
            py += envf("E2B_PHOTO_DY", 0.0);
            let dst = Rect::from_xywh(px, py, photo_rect.width(), photo_rect.height());

            let mut draw_band = |band: &ir::PlacedBand| {
                let br = Rect::from_xywh(band.rect.x * k, band.rect.y * k, band.rect.w * k, band.rect.h * k);
                // Whole-band rotation (vendor `bitmaps[i].rotation`): turn the
                // band's pre-rotation drawing (background + items, one
                // coordinate space) about the band box centre.
                let rot_deg = band.rotation.unwrap_or(0.0);
                let rotated = ((rot_deg % 360.0) + 360.0) % 360.0 != 0.0;
                let (rcx, rcy) = (br.left + br.width() / 2.0, br.top + br.height() / 2.0);
                canvas.save();
                if rotated {
                    canvas.translate((rcx, rcy));
                    canvas.rotate(rot_deg, None);
                    canvas.translate((-rcx, -rcy));
                }
                if let Some(bg) = &band.background {
                    // paint.ts paints a rotated band's plate in the pre-rotation
                    // layoutBox (it rotates with the content).
                    let lb = &band.layout_rect;
                    let lbr = Rect::from_xywh(lb.x * k, lb.y * k, lb.w * k, lb.h * k);
                    let bg_rect = if rotated { lbr } else { br };
                    let mut p = Paint::default();
                    p.set_anti_alias(true);
                    p.set_color(css_argb(bg, 1.0));
                    if let Some(r) = band.radius {
                        canvas.draw_round_rect(bg_rect, r * k, r * k, &p);
                    } else {
                        canvas.draw_rect(bg_rect, &p);
                    }
                }
                for item in &band.items {
                    match item {
                        ir::Item::Group(g) => {
                            let b = Rect::from_xywh(g.rect.x * k, g.rect.y * k, g.rect.w * k, g.rect.h * k);
                            let mut p = Paint::default();
                            p.set_anti_alias(true);
                            p.set_color(css_argb(&g.color, 1.0));
                            canvas.draw_rect(b, &p);
                            let ob = out_rect(g.rect, k, rotated, rcx, rcy, rot_deg);
                            items_out.borrow_mut().push(json!({
                                "kind": "group", "rect": [ob.left, ob.top, ob.width(), ob.height()]
                            }));
                        }
                        ir::Item::Shape(s) => {
                            let b = Rect::from_xywh(s.rect.x * k, s.rect.y * k, s.rect.w * k, s.rect.h * k);
                            if let Some(key) = &s.vector_key {
                                match vectors.get(key) {
                                    Some(art) => paint_vector(canvas, art, b),
                                    None => {
                                        items_out.borrow_mut().push(json!({
                                            "kind": "vector_missing", "key": key,
                                            "rect": [b.left, b.top, b.width(), b.height()]
                                        }));
                                    }
                                }
                            } else {
                                let mut p = Paint::default();
                                p.set_anti_alias(true);
                                p.set_color(css_argb(&s.color, s.alpha));
                                if s.shape == "circle" {
                                    canvas.draw_circle(
                                        (b.left + b.width() / 2.0, b.top + b.height() / 2.0),
                                        b.width().min(b.height()) / 2.0,
                                        &p,
                                    );
                                } else {
                                    canvas.draw_rect(b, &p);
                                }
                            }
                            let ob = out_rect(s.rect, k, rotated, rcx, rcy, rot_deg);
                            items_out.borrow_mut().push(json!({
                                "kind": "shape", "rect": [ob.left, ob.top, ob.width(), ob.height()],
                                "shape": s.shape, "color": s.color, "vectorKey": s.vector_key
                            }));
                        }
                        ir::Item::Image(im) => {
                            let b = Rect::from_xywh(im.rect.x * k, im.rect.y * k, im.rect.w * k, im.rect.h * k);
                            let key = im.asset.clone();
                            let Some((img, _p)) = images.get(&key) else {
                                items_out.borrow_mut().push(json!({
                                    "kind": "image_missing", "path": im.path, "asset": key, "rect": [b.left, b.top, b.width(), b.height()]
                                }));
                                continue;
                            };
                            let (iw, ih) = (img.width() as f32, img.height() as f32);
                            let mut p = Paint::default();
                            p.set_anti_alias(true);
                            let am = env.ov.alpha(&im.path);
                            p.set_alpha(((im.alpha.clamp(0.0, 1.0) * am.clamp(0.0, 1.0)) * 255.0).round() as u8);

                            // Brand slots follow the photo: the baked vendor bitmap
                            // only supplies the node box + ink metrics; the EXIF
                            // brand's wordmark is painted instead (or nothing).
                            if im.is_brand_logo {
                                let slot_file = brand_key
                                    .as_deref()
                                    .and_then(|bk| brand::choose_brand_slot(&mut brands, bk, brand_dark));
                                let Some(file) = slot_file else {
                                    items_out.borrow_mut().push(json!({
                                        "kind": "brand_slot_empty", "path": im.path, "asset": key,
                                        "brand": brand_key, "rect": [b.left, b.top, b.width(), b.height()]
                                    }));
                                    gaps.add(format!(
                                        "brand slot '{}': EXIF brand {:?} has no wordmark on this tone; slot left empty (app rule)",
                                        im.path, brand_key
                                    ));
                                    continue;
                                };
                                let Some(logo) = brands.image(&file) else {
                                    items_out.borrow_mut().push(json!({
                                        "kind": "brand_slot_missing", "path": im.path, "file": file,
                                        "rect": [b.left, b.top, b.width(), b.height()]
                                    }));
                                    continue;
                                };
                                let vendor_ink = brand::ink_box(img);
                                let (margin_px, pad_px) = brand::slot_gap_parts(
                                    &im.margins,
                                    im.gravity.as_deref(),
                                    ir::Rect { x: b.left, y: b.top, w: b.width(), h: b.height() },
                                    k,
                                    vendor_ink.as_ref(),
                                );
                                let metrics = brand::SlotMetrics {
                                    sub: logo.ink.as_ref(),
                                    vendor: vendor_ink.as_ref(),
                                    margin_px,
                                    pad_px,
                                };
                                let free = im.brand_free.map(|f| [f[0] * k, f[1] * k, f[2] * k, f[3] * k]);
                                let rect = brand::brand_slot_rect(
                                    logo.w,
                                    logo.h,
                                    ir::Rect { x: b.left, y: b.top, w: b.width(), h: b.height() },
                                    im.gravity.as_deref(),
                                    free,
                                    &metrics,
                                );
                                let canvas_tint = std::env::var("E3_SLOT_TINT").ok().as_deref() == Some("canvas");
                                let tinted = im.tint.as_deref().and_then(|t| {
                                    if canvas_tint { tinted_image(&logo.image, t) } else { None }
                                });
                                if !canvas_tint {
                                    if let Some(tint) = &im.tint {
                                        if let Some(cf) = skia_safe::color_filters::blend(
                                            css_argb(tint, 1.0),
                                            skia_safe::BlendMode::SrcIn,
                                        ) {
                                            p.set_color_filter(cf);
                                        }
                                    }
                                }
                                draw_image_rect(
                                    canvas,
                                    tinted.as_ref().unwrap_or(&logo.image),
                                    Some(Rect::from_xywh(0.0, 0.0, logo.w as f32, logo.h as f32)),
                                    Rect::from_xywh(rect.x, rect.y, rect.w, rect.h),
                                    image_sampling(logo.w as f32, rect.w),
                                    Some(&p),
                                );
                                let ink_json = |i: Option<&brand::InkBox>| {
                                    i.map(|i| json!([i.x, i.y, i.w, i.h, i.cw, i.ch]))
                                };
                                items_out.borrow_mut().push(json!({
                                    "kind": "brand_slot", "path": im.path, "asset": key,
                                    "brand": brand_key, "file": file, "tint": im.tint,
                                    "rect": [b.left, b.top, b.width(), b.height()],
                                    "draw": [rect.x, rect.y, rect.w, rect.h],
                                    "sub_decoded": [logo.w, logo.h],
                                    "vendor_ink": ink_json(vendor_ink.as_ref()),
                                    "sub_ink": ink_json(logo.ink.as_ref()),
                                    "free": im.brand_free,
                                }));
                                continue;
                            }

                            let dst = if im.scale == "contain" {
                                let s = (b.width() / iw).min(b.height() / ih);
                                let dw = iw * s;
                                let dh = ih * s;
                                Rect::from_xywh(b.left + (b.width() - dw) / 2.0, b.top + (b.height() - dh) / 2.0, dw, dh)
                            } else if im.scale == "cover" {
                                let s = (b.width() / iw).max(b.height() / ih);
                                let sw = b.width() / s;
                                let sh = b.height() / s;
                                canvas.save();
                                canvas.clip_rect(b, skia_safe::ClipOp::Intersect, true);
                                draw_image_rect(
                                    canvas,
                                    img,
                                    Some(Rect::from_xywh((iw - sw) / 2.0, (ih - sh) / 2.0, sw, sh)),
                                    b,
                                    image_sampling(sw, b.width()),
                                    Some(&p),
                                );
                                canvas.restore();
                                b
                            } else {
                                b
                            };
                            if im.scale != "cover" {
                                let sampling = image_sampling(iw, dst.width());
                                let mip = std::env::var("E3_IMG_MIPS").ok().as_deref() == Some("1");
                                let sampled = if mip && dst.width() < iw - 1e-3 {
                                    img.with_default_mipmaps()
                                } else {
                                    None
                                };
                                draw_image_rect(
                                    canvas,
                                    sampled.as_ref().unwrap_or(img),
                                    Some(Rect::from_xywh(0.0, 0.0, iw, ih)),
                                    dst,
                                    sampling,
                                    Some(&p),
                                );
                            }
                            let ob = out_rect(im.rect, k, rotated, rcx, rcy, rot_deg);
                            items_out.borrow_mut().push(json!({
                                "kind": "image", "path": im.path, "asset": key, "scale": im.scale,
                                "rect": [ob.left, ob.top, ob.width(), ob.height()],
                                "draw": [dst.left, dst.top, dst.width(), dst.height()],
                                "decoded": [iw, ih]
                            }));
                        }
                        ir::Item::Text(t) => {
                            let mut bx = t.rect.x * k;
                            let mut by = t.rect.y * k;
                            let mut bw = t.rect.w * k;
                            let mut bh = t.rect.h * k;
                            // paint.ts text box clamp/fit (pad = max(3,min(36,round(W*.025))))
                            let pad = 36.0f32.min((cw as f32 * 0.025).round()).max(3.0);
                            let mut fit = 1.0f32;
                            if bw > cw as f32 - pad * 2.0 || bh > ch as f32 - pad * 2.0 {
                                fit = ((cw as f32 - pad * 2.0) / bw.max(1.0))
                                    .min((ch as f32 - pad * 2.0) / bh.max(1.0))
                                    .min(1.0);
                            }
                            let center_x = bx + bw / 2.0;
                            let right_x = bx + bw;
                            bw *= fit;
                            bh *= fit;
                            if t.align == "center" {
                                bx = center_x - bw / 2.0;
                            } else if t.align == "right" {
                                bx = right_x - bw;
                            }
                            if !rotated {
                                // paint.ts skips the on-canvas clamp inside a
                                // rotated band (the axes are swapped there).
                                bx = pad.max(bx.min(cw as f32 - pad - bw));
                                by = pad.max(by.min(ch as f32 - pad - bh));
                            }
                            let fs = t.font_size * k * fit;
                            let x = match halign(&t.align) {
                                HAlign::Center => bx + bw / 2.0,
                                HAlign::Right => bx + bw,
                                HAlign::Left => bx,
                            };
                            let lh = t.line_height * k * fit;
                            let top = by + (bh - lh * t.lines.len() as f32) / 2.0;
                            let alpha_mul = env.ov.alpha(&t.path);
                            let text_alpha = if alpha_mul == 1.0 {
                                t.alpha
                            } else {
                                (t.alpha * alpha_mul.clamp(0.0, 1.0)).clamp(0.0, 1.0)
                            };
                            // paint.ts outline plate: rounded stroke inside the text
                            // box, painted before the glyphs.
                            if let Some(o) = &t.outline {
                                let lw = o.stroke_width * k * fit;
                                if lw > 0.0 {
                                    let inset = lw / 2.0;
                                    let radius = o.radius.min(bw.min(bh) / 2.0);
                                    let mut sp = Paint::default();
                                    sp.set_anti_alias(true);
                                    sp.set_style(skia_safe::PaintStyle::Stroke);
                                    sp.set_stroke_width(lw);
                                    sp.set_color(css_argb(&o.stroke_color, text_alpha));
                                    canvas.draw_round_rect(
                                        Rect::from_xywh(
                                            bx + inset,
                                            by + inset,
                                            (bw - lw).max(0.0),
                                            (bh - lw).max(0.0),
                                        ),
                                        (radius - inset).max(0.0),
                                        (radius - inset).max(0.0),
                                        &sp,
                                    );
                                }
                            }
                            let d021_em = d021_override.unwrap_or_else(|| fonts.d021_em(&t.font_key));
                            let d021 = -d021_em * fs;
                            let shadow = t.shadow.as_ref().and_then(|s| {
                                let mut color = css_canvas_rgba(&s.color)?;
                                if alpha_mul != 1.0 {
                                    let a = (color.a() as f32 * alpha_mul.clamp(0.0, 1.0)).round() as u8;
                                    color = skia_safe::Color::from_argb(a, color.r(), color.g(), color.b());
                                }
                                if color.a() == 0 {
                                    return None;
                                }
                                Some(text::Shadow {
                                    color,
                                    sigma: s.blur * k * fit * 0.5,
                                    dx: s.x * k * fit,
                                    dy: s.y * k * fit,
                                })
                            });
                            let tracking = t.tracking * k * fit;
                            let tf = fonts.resolve(&t.font_key, t.weight).clone();
                            let cjk_em = std::env::var("E2B_D021_CJK")
                                .ok()
                                .and_then(|v| v.parse::<f32>().ok())
                                .unwrap_or(0.0);
                            let mut measure = 0.0f32;
                            let mut baseline = 0.0f32;
                            let mut notdef_total = 0usize;
                            for (i, line) in t.lines.iter().enumerate() {
                                let fallback_line = fonts.fallback_face().is_some()
                                    && line.chars().any(|c| tf.unichar_to_glyph(c as i32) == 0);
                                let script_tf = fonts.script_face(&tf, line, t.weight);
                                // W2: 含 CJK 的行优先用厂商自己的中文字形（OPPO Sans SC /
                                // MiSans），取不到才落系统 CJK。它与 Thai script face 同层，
                                // 但基线常数按该厂商族的标定值走。
                                let cjk_tf = if script_tf.is_none() {
                                    fonts.cjk_face_for(&t.font_key, line, t.weight)
                                } else {
                                    None
                                };
                                // Canvas2D `middle` sits above Skia's mid-em by a
                                // face-dependent fraction; the Thai face needs its
                                // own constant (E2B_D021_THAI, calibrated on the
                                // songkran pair; see p3g1/report).
                                let thai_em = std::env::var("E2B_D021_THAI")
                                    .ok()
                                    .and_then(|v| v.parse::<f32>().ok())
                                    .unwrap_or(0.0675);
                                let line_d021 = if script_tf.is_some() {
                                    -thai_em * fs
                                } else if cjk_tf.is_some() {
                                    -fonts.cjk_d021_em(&t.font_key) * fs
                                } else if fallback_line {
                                    -cjk_em * fs
                                } else {
                                    d021
                                };
                                let ly = top + lh * (i as f32 + 0.5) + line_d021;
                                if let Some(stf) = &script_tf {
                                    println!(
                                        "e2b.text.script_fallback path={} face={} text={:?}",
                                        t.path,
                                        stf.family_name(),
                                        line
                                    );
                                }
                                if let Some(ctf) = &cjk_tf {
                                    println!(
                                        "e2b.text.cjk_fallback path={} face={} text={:?}",
                                        t.path,
                                        ctf.family_name(),
                                        line
                                    );
                                }
                                let spec = TextSpec {
                                    text: line,
                                    typeface: tf.clone(),
                                    script_fallback: script_tf,
                                    cjk_fallback: cjk_tf,
                                    size: fs,
                                    x,
                                    y: ly,
                                    align: halign(&t.align),
                                    baseline: VBaseline::Middle,
                                    tracking,
                                    color: css_argb(&t.color, text_alpha),
                                    shadow,
                                    matrix: None,
                                };
                                let (lw, lb, nd) = if spec.script_fallback.is_some() {
                                    let clusters = text::thai_clusters(line);
                                    te.draw_clusters(canvas, &spec, &clusters)
                                } else {
                                    te.draw(canvas, &spec)
                                };
                                measure = measure.max(lw);
                                baseline = lb;
                                notdef_total += nd;
                            }
                            // P3g-1 render-level tofu check: glyph id 0 in the
                            // shaped blob is a .notdef empty box. Emit it and,
                            // under the gate env, fail the render.
                            if notdef_total > 0 {
                                println!(
                                    "e2b.text.notdef path={} count={} text={:?} base={} fallback={}",
                                    t.path,
                                    notdef_total,
                                    t.lines.join(" | "),
                                    tf.family_name(),
                                    fonts
                                        .fallback_face()
                                        .map(|f| f.family_name())
                                        .unwrap_or_else(|| "<none>".into())
                                );
                                if std::env::var("E2B_ASSERT_NO_TOFU").ok().as_deref() == Some("1") {
                                    panic!(
                                        "e2b.tofu path={} count={} text={:?}",
                                        t.path,
                                        notdef_total,
                                        t.lines.join(" | ")
                                    );
                                }
                            }
                            let ob = out_rect(t.rect, k, rotated, rcx, rcy, rot_deg);
                            items_out.borrow_mut().push(json!({
                                "kind": "text", "path": t.path,
                                "lines": t.lines, "rect": [ob.left, ob.top, ob.width(), ob.height()],
                                "box": [bx, by, bw, bh],
                                "anchor": [x, by + bh/2.0],
                                "baseline": baseline, "fs": fs, "line_height": lh, "tracking": tracking,
                                "measure": measure, "notdef": notdef_total, "align": t.align, "family": t.family,
                                "font_key": t.font_key, "weight": t.weight, "color": t.color,
                                "alpha": t.alpha, "d021_px": d021,
                                "shadow_alpha": t.shadow.as_ref().map(|s| css_canvas_rgba(&s.color).map(|c| c.a()).unwrap_or(0)).unwrap_or(0)
                            }));
                        }
                    }
                }
                canvas.restore();
            };

            // paint.ts: drawFirst bands -> photo -> the rest.
            for band in layout.bands.iter().filter(|b| b.draw_first) {
                draw_band(band);
            }
            // render-v1 overlay: exactly once per render. The photo is a single
            // draw in this pipeline, but multi-band styles re-enter the photo
            // block on the device's banded export (the phone P3 bug); this flag
            // keeps the watermark from being stacked once per band.
            let overlay_drawn = std::cell::Cell::new(false);
            let draw_overlay_once = |canvas: &Canvas, photo_rect: Rect| -> Option<Value> {
                if overlay_drawn.get() {
                    return None;
                }
                let ov = overlay.as_ref()?;
                let iw = ov.image.width() as f32;
                let ih = ov.image.height() as f32;
                let bw = ov.rect[2] * photo_rect.width();
                let bh = ov.rect[3] * photo_rect.height();
                if !(iw > 0.0 && ih > 0.0 && bw > 0.0 && bh > 0.0) {
                    return None;
                }
                let bx = photo_rect.left() + ov.rect[0] * photo_rect.width();
                let by = photo_rect.top() + ov.rect[1] * photo_rect.height();
                let oscale = (bw / iw).min(bh / ih);
                let (odw, odh) = (iw * oscale, ih * oscale);
                let od = Rect::from_xywh(bx + (bw - odw) * 0.5, by + (bh - odh) * 0.5, odw, odh);
                let mut op = Paint::default();
                op.set_anti_alias(true);
                op.set_alpha_f(ov.opacity.clamp(0.0, 1.0));
                overlay_drawn.set(true);
                draw_image_rect(
                    canvas,
                    &ov.image,
                    Some(Rect::from_xywh(0.0, 0.0, iw, ih)),
                    od,
                    image_sampling(iw, odw),
                    Some(&op),
                );
                Some(json!({
                    "kind": "overlay",
                    "dst": [od.left(), od.top(), od.width(), od.height()],
                    "rect_norm": ov.rect,
                    "opacity": ov.opacity,
                }))
            };
            // paint.ts drawPhoto(): roundRectPath(radius 0) + ctx.clip() then
            // drawImage. The AA clip is what produces the half-covered edge row
            // when the photo rect sits on a fractional device coordinate.
            canvas.save();
            let clip_mode = std::env::var("E2B_PHOTO_CLIP").unwrap_or_else(|_| "aa".into());
            match clip_mode.as_str() {
                "hard" => {
                    canvas.clip_rect(dst, skia_safe::ClipOp::Intersect, false);
                }
                "none" => {}
                _ => {
                    canvas.clip_rect(dst, skia_safe::ClipOp::Intersect, true);
                }
            }
            let mut photo_paint = Paint::default();
            photo_paint.set_anti_alias(true);
            draw_image_rect(
                canvas,
                &photo,
                Some(Rect::from_xywh(0.0, 0.0, pw, ph)),
                dst,
                photo_sampling,
                Some(&photo_paint),
            );
            // render-v1 overlay: inside the photo's clip (cropped to the photo
            // draw box), before the non-drawFirst bands (border/text/logo).
            if let Some(meta) = draw_overlay_once(canvas, dst) {
                items_out.borrow_mut().push(meta);
            }
            // Evidence-only fault injection: mirror the phone's per-band photo
            // redraw (a second photo draw at a band-local, shifted rect) so the
            // once-guard can be shown red/green on the host.
            if std::env::var("E2B_FAULT_PHOTO_TWICE").ok().as_deref() == Some("1") {
                let fake = Rect::from_xywh(dst.left(), dst.top() + dst.height() * 0.35, dst.width(), dst.height());
                draw_image_rect(
                    canvas,
                    &photo,
                    Some(Rect::from_xywh(0.0, 0.0, pw, ph)),
                    fake,
                    photo_sampling,
                    Some(&photo_paint),
                );
                if let Some(meta) = draw_overlay_once(canvas, fake) {
                    items_out.borrow_mut().push(meta);
                }
            }
            canvas.restore();
            for band in layout.bands.iter().filter(|b| !b.draw_first) {
                draw_band(band);
            }
        })
        .expect("render");
    gpu::encode_png(&pixels, cw, ch, &out).expect("png");

    println!(
        "e2b.adapter=#{} {} backend={} feature_level={} max_texture={}",
        gpu.adapter_index(),
        gpu.adapter_name(),
        gpu.backend(),
        gpu.feature_level(),
        gpu.max_texture_size()
    );
    println!("e2b.raw_png={} bytes={}", out.display(), std::fs::metadata(&out).unwrap().len());

    let report = json!({
        "style": style.id,
        "style_path": style_path.display().to_string(),
        "canvas": {"w": cw, "h": ch},
        "layout_units": {"w": layout.w, "h": layout.h, "k": k, "base_w": base_w},
        "reference_width": style.reference_width,
        "reference_width_mode": style.reference_width_mode,
        "margins": {"left": style.margins.left, "top": style.margins.top, "right": style.margins.right, "bottom": style.margins.bottom},
        "photo": [layout.photo.x*k, layout.photo.y*k, layout.photo.w*k, layout.photo.h*k],
        "overlay": overlay.as_ref().map(|o| json!({
            "rect_norm": o.rect,
            "opacity": o.opacity,
            "image": [o.image.width(), o.image.height()],
        })),
        "background": {
            "value": style.background,
            "theme": theme_id,
            "surface": surface_bg,
            "brand_dark": brand_dark,
            "transparent": style.transparent_background,
            "fill": bg_fill
        },
        "theme_foreground": foreground,
        "bands": layout.bands.iter().map(|b| json!({
            "direction": b.direction,
            "rect": [b.rect.x*k, b.rect.y*k, b.rect.w*k, b.rect.h*k],
            "background": b.background,
            "radius": b.radius,
            "items": b.items.len(),
        })).collect::<Vec<_>>(),
        "items": items_out.borrow().clone(),
        "text_settings": {
            "hinting": format!("{:?}", te.hinting),
            "edging": format!("{:?}", te.edging),
            "subpixel": te.subpixel,
            "surface_props": "none",
            "d021_override_em": d021_override,
            "logo_sampling": std::env::var("T1C_HIGH").unwrap_or_else(|_| "mitchell".into()),
        },
        "exif_source": exif_source.display().to_string(),
        "gaps": gaps.notes,
    });
    std::fs::write(&report_path, serde_json::to_string_pretty(&report).unwrap()).ok();
    println!("e2b.report={}", report_path.display());
    gpu.abandon();
}

#[cfg(test)]
mod asset_resolution_tests {
    use super::resolve_asset;
    use std::fs;
    use std::path::PathBuf;
    use std::time::{SystemTime, UNIX_EPOCH};

    struct Scratch(PathBuf);

    impl Scratch {
        fn new() -> Self {
            let nonce = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .expect("clock before unix epoch")
                .as_nanos();
            let path = std::env::temp_dir().join(format!("fm-e2b-resolve-{}-{nonce}", std::process::id()));
            fs::create_dir(&path).expect("create unique test scratch");
            Self(path)
        }
    }

    impl Drop for Scratch {
        fn drop(&mut self) {
            let Ok(temp) = std::env::temp_dir().canonicalize() else {
                return;
            };
            let Ok(scratch) = self.0.canonicalize() else {
                return;
            };
            let belongs_to_test = scratch.parent() == Some(temp.as_path())
                && scratch
                    .file_name()
                    .and_then(|name| name.to_str())
                    .is_some_and(|name| name.starts_with("fm-e2b-resolve-"));
            if belongs_to_test {
                let _ = fs::remove_dir_all(scratch);
            }
        }
    }

    #[test]
    fn rejects_external_absolute_and_parent_escape_asset_paths() {
        let scratch = Scratch::new();
        let allowed = scratch.0.join("package/data/decoded");
        let external = scratch.0.join("outside/dev-only.png");
        fs::create_dir_all(&allowed).expect("create allowed root");
        fs::create_dir_all(external.parent().expect("external parent")).expect("create external parent");
        fs::write(&external, b"external").expect("write external fixture");

        assert!(resolve_asset(&external.to_string_lossy(), std::slice::from_ref(&allowed)).is_none());
        assert!(resolve_asset(
            "../../../outside/dev-only.png",
            std::slice::from_ref(&allowed),
        )
        .is_none());
    }

    #[test]
    fn resolves_absolute_path_contained_by_explicit_root() {
        let scratch = Scratch::new();
        let allowed = scratch.0.join("package/data/decoded");
        fs::create_dir_all(&allowed).expect("create allowed root");
        let contained = allowed.join("contained.png");
        fs::write(&contained, b"package").expect("write contained fixture");

        assert_eq!(
            resolve_asset(&contained.to_string_lossy(), std::slice::from_ref(&allowed)),
            Some(contained.canonicalize().expect("canonical contained fixture")),
        );
    }
}
