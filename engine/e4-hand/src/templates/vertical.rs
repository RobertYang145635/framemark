//! `const vertical: TemplateDef` (templates.ts:2617) — full-bleed photo, left
//! gradient, 90° rotated title/EXIF. Batch-3 card: `fm-vertical`.

use skia_safe::Rect;

use crate::draw::{
    date_text, draw_brand_logo, draw_fitted, draw_photo, draw_vertical_text_slot, theme_of,
    title_of, Env, FittedOpts, LayoutEnv,
};
use crate::hand::pct;
use crate::jsval::pct as js_pct;

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    Ok((env.pw, env.ph))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let s = w.min(h);
    let theme = theme_of(env.themes, env.params);
    draw_photo(
        &mut env.ctx,
        env.photo,
        &env.crop,
        0.0,
        0.0,
        w,
        h,
        0.0,
        0.0,
        &theme,
        s,
    );
    env.ctx.fill_linear_gradient(
        0.0,
        0.0,
        w * 0.48,
        0.0,
        &[
            (0.0, "rgba(0,0,0,0.72)"),
            (0.55, "rgba(0,0,0,0.28)"),
            (1.0, "rgba(0,0,0,0)"),
        ],
        Rect::from_xywh(0.0, 0.0, w, h),
    );
    let x = w * 0.095;
    let y = h * 0.88;
    let max_len = h * 0.64;
    let title = title_of(env);
    // fitSize with tracking 0.04 em, then drawVert (which applies 0.04*size again).
    let title_size = {
        let pick = env.fonts.resolve("YouShe", 400);
        let Some(pick) = pick else {
            env.ctx.record_gap("YouShe unresolved".into());
            return Ok(Rect::from_xywh(0.0, 0.0, w, h));
        };
        crate::draw::fit_size(env.te, &pick.tf, &title, max_len, s * 0.05, s * 0.02, 0.04)
    };
    draw_vertical_text_slot(
        env,
        &title,
        x,
        y,
        title_size,
        "#ffffff",
        "YouShe",
        400,
        0.04,
        "vertical.title",
    );
    let ex = if env.text.show_exif {
        env.exif.line("  ")
    } else {
        String::new()
    };
    let ex_size = {
        let pick = env.fonts.resolve("JetBrains Mono", 400);
        let Some(pick) = pick else {
            return Ok(Rect::from_xywh(0.0, 0.0, w, h));
        };
        crate::draw::fit_size(
            env.te,
            &pick.tf,
            &ex,
            max_len * 0.8,
            s * 0.016,
            s * 0.009,
            0.03,
        )
    };
    draw_vertical_text_slot(
        env,
        &ex,
        x + title_size * 1.9,
        y,
        ex_size,
        "rgba(255,255,255,0.7)",
        "JetBrains Mono",
        400,
        0.03,
        "vertical.exif",
    );
    crate::draw::line(
        &mut env.ctx,
        x - s * 0.03,
        y,
        x - s * 0.03,
        y - max_len,
        &theme.accent,
        (s * 0.0015).max(1.0),
        None,
    );
    let dt = date_text(env);
    draw_fitted(
        env,
        &dt,
        w - w * 0.06,
        h * 0.93,
        w * 0.32,
        s * 0.014,
        "rgba(255,255,255,0.68)",
        &FittedOpts::new("JetBrains Mono")
            .align("right")
            .min(s * 0.008)
            .shadow(true)
            .slot("vertical.date"),
    );
    if env.text.show_logo {
        draw_brand_logo(env, w - w * 0.2, h * 0.055, w * 0.14, h * 0.035, "#ffffff");
    }
    let _ = js_pct(env.params.get("marginPct"), 0.0);
    Ok(Rect::from_xywh(0.0, 0.0, w, h))
}
