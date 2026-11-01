//! `const bareCover: TemplateDef` (templates.ts:2595) — full-bleed 16:9 photo
//! cover, no text, no logo. Ported first because it is the only hand card whose
//! expected result is pure photo pixels (Δ0), i.e. it validates the whole
//! harness (design → crop → layout → GPU → resize → compare) before any text or
//! decor is involved.

use skia_safe::Rect;

use crate::draw::{draw_photo_cover, photo_window_of, theme_of, Env, LayoutEnv};

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let (w, h, _cover) = photo_window_of(env.params, env.pw, env.ph);
    Ok((w, h))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    env.ctx.set_fill("#000000");
    env.ctx.fill_rect(0.0, 0.0, w, h);
    let theme = theme_of(env.themes, env.params);
    let s = env.pw.min(env.ph);
    draw_photo_cover(&mut env.ctx, env.photo, &env.crop, 0.0, 0.0, w, h, 0.0, 0.0, s);
    let _ = theme;
    Ok(Rect::from_xywh(0.0, 0.0, w, h))
}
