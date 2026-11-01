//! `const locationCard: TemplateDef` (templates.ts:3922) — 顶部地点/时间色块，
//! 下方满幅照片。Card: color-walk.

use skia_safe::Rect;

use crate::draw::{draw_fitted, draw_photo_cover, Env, FittedOpts, LayoutEnv};
use crate::hand::{pct, str_param};

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let s = env.pw.min(env.ph);
    Ok((s * 1.5, s * 2.0))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let s = env.pw.min(env.ph);
    let fg = str_param(env.params, "locationFg", "#f5f2ea");
    let bg = str_param(env.params, "locationBg", "#405c66");
    let header = (h * (pct(env.params, "headerPct", 45.0) as f32)) / 100.0;
    env.ctx.set_fill(&bg);
    env.ctx.fill_rect(0.0, 0.0, w, header);
    env.ctx.record_shape("header", Rect::from_xywh(0.0, 0.0, w, header), None, true);
    let title = str_param(env.params, "locationTitle", "中国");
    let topts = FittedOpts::new("YouShe")
        .weight(500)
        .align("center")
        .baseline("middle")
        .tracking(0.12)
        .min(s * 0.024);
    draw_fitted(env, &title, w / 2.0, header * 0.48, w * 0.5, s * 0.042, &fg, &topts);
    let time = str_param(env.params, "locationTime", "08:00 AM");
    let mopts = FittedOpts::new("JetBrains Mono")
        .align("center")
        .baseline("middle")
        .tracking(0.18)
        .min(s * 0.01);
    draw_fitted(env, &time, w / 2.0, header * 0.535, w * 0.4, s * 0.018, &fg, &mopts);
    draw_photo_cover(&mut env.ctx, env.photo, &env.crop, 0.0, header, w, h - header, 0.0, 0.0, s);
    Ok(Rect::from_xywh(0.0, header, w, h - header))
}
