//! `const overlay: TemplateDef` (templates.ts:2520) — full-bleed photo with a
//! bottom gradient and a cinema-caption block. Card: `fm-overlay`.

use skia_safe::Rect;

use crate::draw::{
    date_text, draw_fitted, draw_photo, exif_text, line, theme_of, title_of, Env, FittedOpts,
    LayoutEnv,
};

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    Ok((env.pw, env.ph))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let s = w.min(h);
    let theme = theme_of(env.themes, env.params);
    draw_photo(&mut env.ctx, env.photo, &env.crop, 0.0, 0.0, w, h, 0.0, 0.0, &theme, s);
    env.ctx.fill_linear_gradient(
        0.0,
        h * 0.48,
        0.0,
        h,
        &[(0.0, "rgba(0,0,0,0)"), (0.55, "rgba(0,0,0,0.28)"), (1.0, "rgba(0,0,0,0.82)")],
        Rect::from_xywh(0.0, 0.0, w, h),
    );
    let x = w * 0.06;
    let max_w = w * 0.84;
    let mut y = h * 0.885;
    let dt = date_text(env);
    if !dt.is_empty() {
        draw_fitted(
            env,
            &dt,
            x,
            y,
            max_w,
            s * 0.015,
            "rgba(255,255,255,0.68)",
            &FittedOpts::new("JetBrains Mono").min(s * 0.009).shadow(true),
        );
        y -= s * 0.035;
    }
    let ex = exif_text(env);
    if !ex.is_empty() {
        draw_fitted(
            env,
            &ex,
            x,
            y,
            max_w,
            s * 0.019,
            "rgba(255,255,255,0.82)",
            &FittedOpts::new("JetBrains Mono").min(s * 0.01).shadow(true),
        );
        y -= s * 0.06;
    }
    let title = title_of(env);
    draw_fitted(
        env,
        &title,
        x,
        y,
        max_w,
        s * 0.058,
        "#ffffff",
        &FittedOpts::new("YouShe").min(s * 0.025).shadow(true),
    );
    let accent_w = (s * 0.0016).max(1.0);
    line(&mut env.ctx, x, y - s * 0.10, x + s * 0.08, y - s * 0.10, &theme.accent, accent_w, None);
    env.ctx.record_shape(
        "rule",
        skia_safe::Rect::from_xywh(x, y - s * 0.10 - accent_w / 2.0, s * 0.08, accent_w),
        None,
        true,
    );
    let cam = if env.text.show_make || env.text.show_model {
        env.exif.camera_line("")
    } else {
        String::new()
    };
    if !cam.is_empty() {
        draw_fitted(
            env,
            &cam.to_uppercase(),
            x,
            y - s * 0.075,
            max_w,
            s * 0.015,
            "rgba(255,255,255,0.72)",
            &FittedOpts::new("Inter").weight(600).tracking(0.16).min(s * 0.008).shadow(true),
        );
    }
    if !env.text.signature.trim().is_empty() {
        draw_fitted(
            env,
            env.text.signature.trim(),
            w - x,
            h * 0.885,
            w * 0.35,
            s * 0.032,
            "rgba(255,255,255,0.85)",
            &FittedOpts::new("Caveat").align("right").min(s * 0.016).shadow(true),
        );
    }
    Ok(Rect::from_xywh(0.0, 0.0, w, h))
}
