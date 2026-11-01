//! `const glassPalettePortrait: TemplateDef` (templates.ts:3415) — 3:4 竖幅满幅照片，
//! 底部玻璃面板内 3×2 圆色卡与十六进制色值。Card: glass-palette.

use skia_safe::Rect;

use crate::draw::{draw_glass_panel, draw_photo_cover, draw_text_exact, extract_palette, Env, LayoutEnv};
use crate::hand::pct;

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let h = (env.pw.max(env.ph)).round();
    Ok(((h * 0.75).round(), h))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let s = w.min(h);
    let cols = (pct(env.params, "swatchCols", 3.0) as f32).round().max(1.0) as i32;
    let rows = (pct(env.params, "swatchRows", 2.0) as f32).round().max(1.0) as i32;
    let panel_w = w * ((pct(env.params, "panelWidthPct", 89.2) as f32) / 100.0);
    let panel_h = h * ((pct(env.params, "panelHeightPct", 29.7) as f32) / 100.0);
    let panel_x = (w - panel_w) / 2.0;
    let panel_y = h - h * ((pct(env.params, "panelBottomPct", 6.5) as f32) / 100.0) - panel_h;
    let radius = (panel_h * ((pct(env.params, "panelRadiusPct", 16.0) as f32) / 100.0)).min(w * 0.09);
    draw_photo_cover(&mut env.ctx, env.photo, &env.crop, 0.0, 0.0, w, h, 0.0, 0.0, s);
    let tint = (pct(env.params, "tintAlpha", 0.3) as f32).clamp(0.0, 0.6);
    let blur_px = (s * 0.012 * ((pct(env.params, "blur", 24.0) as f32) / 24.0)).max(1.0);
    let tint_str = format!("rgba(0,0,0,{tint})");
    draw_glass_panel(
        env,
        panel_x,
        panel_y,
        panel_w,
        panel_h,
        radius,
        &tint_str,
        &tint_str,
        0.10,
        blur_px,
        (s * 0.001).max(1.0),
    );
    let colors = extract_palette(env, (cols * rows) as usize);
    let d = panel_w * ((pct(env.params, "swatchPct", 12.3) as f32) / 100.0);
    let first_x = panel_x + panel_w * ((pct(env.params, "gridFirstXPct", 18.9) as f32) / 100.0);
    let pitch_x = panel_w * ((pct(env.params, "gridPitchXPct", 31.1) as f32) / 100.0);
    let first_y = panel_y + panel_h * ((pct(env.params, "gridFirstYPct", 20.9) as f32) / 100.0);
    let pitch_y = panel_h * ((pct(env.params, "gridPitchYPct", 45.1) as f32) / 100.0);
    let label_size = h * ((pct(env.params, "labelPct", 2.2) as f32) / 100.0);
    let label_top = d / 2.0 + h * 0.0148;
    for r in 0..rows {
        for c in 0..cols {
            let idx = (r * cols + c) as usize;
            let hex = colors.get(idx).cloned().unwrap_or_else(|| "#000000".into());
            let cx = first_x + c as f32 * pitch_x;
            let cy = first_y + r as f32 * pitch_y;
            env.ctx.fill_circle(cx, cy, d / 2.0, &hex);
            env.ctx.record_shape("swatch", Rect::from_xywh(cx - d / 2.0, cy - d / 2.0, d, d), None, true);
            draw_text_exact(
                env,
                &hex.to_uppercase(),
                cx,
                cy + label_top,
                label_size,
                "rgba(255,255,255,0.94)",
                "JetBrains Mono",
                500,
                "center",
                "top",
                0.0,
                false,
                None,
            );
        }
    }
    Ok(Rect::from_xywh(0.0, 0.0, w, h))
}
