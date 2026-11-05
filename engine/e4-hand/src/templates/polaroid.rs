//! `const polaroid: TemplateDef` (templates.ts:2925) — white paper, tape,
//! hand-written title/date and an overlay grain. Batch-2 card: `fm-polaroid`
//! (paper theme, Caveat 600/400).

use skia_safe::Rect;

use crate::draw::{
    date_text, draw_fitted, draw_grain, draw_photo, theme_of, title_of, Env, FittedOpts, LayoutEnv,
};
use crate::hand::pct;

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let s = env.pw.min(env.ph);
    let m = s * (pct(env.params, "marginPct", 5.5) as f32) / 100.0;
    let bottom = ((s * (pct(env.params, "footerPct", 22.0) as f32)) / 100.0).max(s * 0.22);
    Ok((env.pw + 2.0 * m, env.ph + m + bottom))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h, pw, ph) = (env.w, env.h, env.pw, env.ph);
    let theme = theme_of(env.themes, env.params);
    let s = pw.min(ph);
    let m = s * (pct(env.params, "marginPct", 5.5) as f32) / 100.0;
    let bottom = h - ph - m;
    let base_y = m + ph;
    let bg = if theme.id == "paper" {
        "#fbf8f0"
    } else {
        theme.bg.as_str()
    };
    env.ctx.set_fill(bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    env.ctx.save();
    env.ctx.set_global_alpha(0.55);
    env.ctx.translate(w / 2.0, m * 0.45);
    env.ctx.rotate(-0.018);
    env.ctx.fill_rect_color(
        -w * 0.1,
        -s * 0.018,
        w * 0.2,
        s * 0.036,
        "rgba(255,250,236,0.7)",
    );
    env.ctx.stroke_rect(
        -w * 0.1,
        -s * 0.018,
        w * 0.2,
        s * 0.036,
        "rgba(120,110,90,0.12)",
        1.0,
    );
    env.ctx.restore();
    draw_photo(
        &mut env.ctx,
        env.photo,
        &env.crop,
        m,
        m,
        pw,
        ph,
        s * (pct(env.params, "radiusPct", 0.5) as f32) / 100.0,
        pct(env.params, "shadow", 3.0) as f32,
        &theme,
        s,
    );
    env.ctx
        .stroke_rect(m, m, pw, ph, "rgba(80,72,60,0.22)", (s * 0.0008).max(1.0));
    let title = title_of(env);
    draw_fitted(
        env,
        &title,
        m,
        base_y + bottom * 0.38,
        w - 2.0 * m - s * 0.16,
        s * 0.062,
        "#2a241e",
        &FittedOpts::new("Caveat")
            .weight(600)
            .min(s * 0.026)
            .slot("polaroid.title"),
    );
    let dt = date_text(env);
    draw_fitted(
        env,
        &dt,
        w - m,
        base_y + bottom * 0.38,
        s * 0.26,
        s * 0.03,
        "#6d6254",
        &FittedOpts::new("Caveat")
            .align("right")
            .min(s * 0.016)
            .slot("polaroid.date"),
    );
    let ex = if env.text.show_exif {
        env.exif.line("  ")
    } else {
        String::new()
    };
    draw_fitted(
        env,
        &ex,
        m,
        base_y + bottom * 0.53,
        w - 2.0 * m,
        s * 0.014,
        "#7b7264",
        &FittedOpts::new("JetBrains Mono")
            .min(s * 0.008)
            .slot("polaroid.exif"),
    );
    draw_fitted(
        env,
        env.text.signature.trim(),
        m,
        base_y + bottom * 0.70,
        w - 2.0 * m,
        s * 0.039,
        "#3a332b",
        &FittedOpts::new("Caveat")
            .min(s * 0.02)
            .slot("polaroid.signature"),
    );
    draw_grain(
        &mut env.ctx,
        w,
        h,
        (pct(env.params, "grain", 0.06) as f32) * 0.55,
    );
    Ok(Rect::from_xywh(m, m, pw, ph))
}
