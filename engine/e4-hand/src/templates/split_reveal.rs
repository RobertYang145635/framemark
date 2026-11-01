//! `const splitReveal: TemplateDef` (templates.ts:554) — 整幅彩色照片 +
//! 纵向三色点取色器（single 径）。Card: color-reveal.

use skia_safe::Rect;

use crate::draw::{draw_photo, draw_photo_cover, extract_palette, theme_of, Env, LayoutEnv};
use crate::hand::{pct, str_param};

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let aspect = (pct(env.params, "canvasAspect", 16.0 / 9.0) as f32).max(0.5);
    Ok((env.pw, (env.pw / aspect).round().max(2.0)))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let s = w.min(h);
    let split = w / 2.0;
    let single = str_param(env.params, "frameMode", "single") == "single";
    if single {
        draw_photo_cover(&mut env.ctx, env.photo, &env.crop, 0.0, 0.0, w, h, 0.0, 0.0, s);
    } else {
        // Legacy split needs `ctx.filter = 'grayscale(1)'` on half the photo;
        // the Ctx2D port has no color-filter path. No frozen card uses it.
        draw_photo(&mut env.ctx, env.photo, &env.crop, 0.0, 0.0, w, h, 0.0, 0.0, &theme_of(env.themes, env.params), s);
        env.ctx.record_gap("splitReveal: frameMode 'split' (grayscale half) not ported".into());
    }
    let palette = extract_palette(env, 5);
    let vertical = str_param(env.params, "dotOrientation", "vertical") == "vertical";
    let count = match crate::jsval::pct(env.params.get("dotCount"), 3.0) as f64 {
        v if !v.is_finite() => 3,
        v if v.round() == 0.0 => 3,
        v => (v.round() as i64).clamp(1, 6),
    };
    let r = if vertical { s * ((pct(env.params, "dotSizePct", 2.45) as f32) / 100.0) } else { s * 0.0105 };
    let gap = if vertical { h * ((pct(env.params, "dotGapPct", 7.7) as f32) / 100.0) } else { r * 2.9 };
    let cx = if vertical { w * ((pct(env.params, "dotXPct", 50.0) as f32) / 100.0) } else { split };
    let cy = if vertical { h * ((pct(env.params, "dotTopPct", 46.3) as f32) / 100.0) } else { h * 0.62 };
    for i in 0..count {
        let x = if vertical { cx } else { split + (i as f32 - (count - 1) as f32 / 2.0) * gap };
        let y = if vertical { cy + i as f32 * gap } else { cy };
        let color = palette
            .get(i as usize + 1)
            .or_else(|| palette.get(i as usize))
            .cloned()
            .unwrap_or_else(|| "#ffffff".into());
        env.ctx.fill_circle(x, y, r, &color);
        env.ctx.stroke_circle(x, y, r, "rgba(255,255,255,0.82)", (s * if vertical { 0.0025 } else { 0.0012 }).max(1.0));
        env.ctx.record_shape("dot", Rect::from_xywh(x - r, y - r, r * 2.0, r * 2.0), None, true);
    }
    Ok(Rect::from_xywh(0.0, 0.0, w, h))
}
