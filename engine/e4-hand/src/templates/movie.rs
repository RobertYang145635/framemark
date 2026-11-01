//! `const movie: TemplateDef` (templates.ts:4190) — 宽银幕黑边 + 片头字幕。
//! `photoAspect > 0` 切 2.34:1 取景，`subtitleMode` 决定字幕压照片内还是下黑边。
//! Cards: movie-1 (bar) / movie-2 (in-photo).

use skia_safe::Rect;

use crate::draw::{
    draw_fitted, draw_photo, draw_photo_cover, theme_of, title_of, Env, FittedOpts, LayoutEnv,
};
use crate::hand::{pct, str_param};

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let aspect = pct(env.params, "photoAspect", 0.0) as f32;
    if aspect > 0.0 {
        let photo_h = env.pw / aspect;
        let bp = (pct(env.params, "barPct", 11.7) as f32).clamp(0.0, 30.0);
        let bar = (photo_h * bp) / (100.0 - 2.0 * bp);
        return Ok((env.pw, photo_h + 2.0 * bar));
    }
    let s = env.pw.min(env.ph);
    let bar = ((s * (pct(env.params, "barPct", 8.0) as f32)) / 100.0).max(s * 0.05);
    Ok((env.pw, env.ph + 2.0 * bar))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let theme = theme_of(env.themes, env.params);
    let aspect = pct(env.params, "photoAspect", 0.0) as f32;
    let wide = aspect > 0.0;
    let photo_w = if wide { w } else { env.pw };
    let photo_h = if wide { w / aspect } else { env.ph };
    let s = photo_w.min(photo_h);
    let bar = (h - photo_h) / 2.0;
    let bottom_top = bar + photo_h;
    let in_photo = wide && str_param(env.params, "subtitleMode", "bar") == "in-photo";
    env.ctx.set_fill(&theme.bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    if wide {
        draw_photo_cover(&mut env.ctx, env.photo, &env.crop, 0.0, bar, photo_w, photo_h, 0.0, 0.0, s);
    } else {
        draw_photo(&mut env.ctx, env.photo, &env.crop, 0.0, bar, photo_w, photo_h, 0.0, 0.0, &theme, s);
    }
    let title = title_of(env);
    let sub = env.text.subtitle.trim().to_string();
    let has_sub = !sub.is_empty();
    let size_title = if wide { s * 0.042 } else { s * 0.036 };
    let size_sub = if wide { s * 0.036 } else { s * 0.023 };
    let centered = |family: &'static str, weight: u16, shadow: bool, min: f32| {
        FittedOpts::new(family)
            .weight(weight)
            .align("center")
            .baseline("middle")
            .shadow(shadow)
            .min(min)
    };
    if in_photo {
        let title_y = bottom_top - s * if has_sub { 0.122 } else { 0.06 };
        let opts = centered("YouShe", 400, true, s * 0.018);
        draw_fitted(env, &title, w / 2.0, title_y, w * 0.82, size_title, &theme.fg, &opts);
        if has_sub {
            let opts = centered("Inter", 400, true, s * 0.012);
            draw_fitted(env, &sub, w / 2.0, bottom_top - s * 0.066, w * 0.82, size_sub, &theme.fg, &opts);
        }
    } else if wide {
        let title_y = bottom_top + bar * if has_sub { 0.265 } else { 0.5 };
        let opts = centered("YouShe", 400, false, s * 0.018);
        draw_fitted(env, &title, w / 2.0, title_y, w * 0.82, size_title, &theme.fg, &opts);
        if has_sub {
            let opts = centered("Inter", 400, false, s * 0.012);
            draw_fitted(env, &sub, w / 2.0, bottom_top + bar * 0.634, w * 0.82, size_sub, &theme.fg, &opts);
        }
    } else {
        let title_y = bottom_top + bar * if has_sub { 0.38 } else { 0.62 };
        let opts = centered("YouShe", 400, false, s * 0.018);
        draw_fitted(env, &title, w / 2.0, title_y, w * 0.82, s * 0.036, &theme.fg, &opts);
        if has_sub {
            let opts = centered("Inter", 400, false, s * 0.012);
            draw_fitted(env, &sub, w / 2.0, bottom_top + bar * 0.76, w * 0.82, s * 0.023, &theme.sub, &opts);
        }
    }
    Ok(Rect::from_xywh(0.0, bar, photo_w, photo_h))
}
