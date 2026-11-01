//! `const ratioBlurSquare: TemplateDef` (templates.ts:4466) — blurred backdrop
//! + centred square cover, no text. Card: `ratio-3`.

use skia_safe::Rect;

use crate::draw::{draw_blurred_backdrop, draw_photo_cover, theme_of, Env, LayoutEnv};
use crate::hand::pct;
use crate::jsval::{js_round, pct as js_pct};

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let aspect = js_pct(env.params.get("canvasAspect"), 1.5015).max(0.5);
    Ok((env.pw, f32::max(2.0, js_round(env.pw as f64 / aspect) as f32)))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let theme = theme_of(env.themes, env.params);
    let tint = js_pct(env.params.get("tintAlpha"), 0.18).clamp(0.0, 1.0) as f32;
    draw_blurred_backdrop(env, w, h, &theme, tint);
    let side = w.min(h) * (pct(env.params, "squarePct", 88.27) as f32) / 100.0;
    let x = (w - side) / 2.0;
    let y = (h - side) / 2.0;
    let radius = side * (pct(env.params, "radiusPct", 2.6) as f32) / 100.0;
    draw_photo_cover(&mut env.ctx, env.photo, &env.crop, x, y, side, side, radius, 0.0, w.min(h));
    Ok(Rect::from_xywh(x, y, side, side))
}
