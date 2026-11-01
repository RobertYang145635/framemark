//! `const blur: TemplateDef` (templates.ts:2674) — 照片虚化底 + 前景圆角卡片 +
//! 页脚参数/日期/品牌。Cards: blur-classic / blur-date.

use skia_safe::Rect;

use crate::draw::{
    date_text, draw_blurred_backdrop, draw_brand_logo, draw_fitted, draw_photo, exif_text, theme_of,
    title_of, Env, FittedOpts, LayoutEnv,
};
use crate::hand::pct;

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let s = env.pw.min(env.ph);
    let m = (s * (pct(env.params, "marginPct", 3.5) as f32)) / 100.0;
    let footer = ((s * (pct(env.params, "footerPct", 11.0) as f32)) / 100.0).max(s * 0.12);
    Ok((env.pw + 2.0 * m, env.ph + 2.0 * m + footer))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h, pw, ph) = (env.w, env.h, env.pw, env.ph);
    let theme = theme_of(env.themes, env.params);
    let s = pw.min(ph);
    let m = (s * (pct(env.params, "marginPct", 3.5) as f32)) / 100.0;
    let footer = h - ph - 2.0 * m;
    let is_dark = theme.id == "ink" || theme.id == "black";
    draw_blurred_backdrop(env, w, h, &theme, if is_dark { 0.42 } else { 0.34 });
    draw_photo(
        &mut env.ctx,
        env.photo,
        &env.crop,
        m,
        m,
        pw,
        ph,
        (s * (pct(env.params, "radiusPct", 0.0) as f32)) / 100.0,
        pct(env.params, "shadow", 6.0) as f32,
        &theme,
        s,
    );
    let cx = w / 2.0;
    let minimal = matches!(env.params.get("minimalFooter"), Some(serde_json::Value::Bool(true)));
    if minimal {
        let dt = date_text(env);
        let topts = FittedOpts::new("Inter")
            .weight(600)
            .align("center")
            .tracking(0.14)
            .min(s * 0.01);
        draw_fitted(env, &title_of(env).to_uppercase(), cx, m + ph + m + footer * 0.42, w - 2.0 * m, s * 0.019, &theme.sub, &topts);
        let dopts = FittedOpts::new("JetBrains Mono").align("center").min(s * 0.012);
        draw_fitted(env, &dt, cx, m + ph + m + footer * 0.68, w - 2.0 * m, s * 0.024, &theme.fg, &dopts);
        return Ok(Rect::from_xywh(m, m, pw, ph));
    }
    let title_y = m + ph + m + footer * 0.34;
    let mut y = title_y;
    let topts = FittedOpts::new("Inter")
        .weight(600)
        .align("center")
        .tracking(0.12)
        .min(s * 0.013);
    draw_fitted(env, &title_of(env).to_uppercase(), cx, y, w - 2.0 * m, s * 0.024, &theme.fg, &topts);
    let ex = exif_text(env);
    if !ex.is_empty() {
        y += footer * 0.26;
        let eopts = FittedOpts::new("JetBrains Mono").align("center").min(s * 0.009);
        y += draw_fitted(env, &ex, cx, y, w - 2.0 * m, s * 0.017, &theme.sub, &eopts);
    }
    let dt = date_text(env);
    if !dt.is_empty() {
        y += footer * 0.2;
        let dopts = FittedOpts::new("JetBrains Mono").align("center").min(s * 0.008);
        draw_fitted(env, &dt, cx, y, w - 2.0 * m, s * 0.014, &theme.sub, &dopts);
    }
    let logo_pos = crate::hand::str_param(env.params, "logoPos", "center");
    if env.text.show_logo && logo_pos != "none" {
        let lw = s * 0.18;
        let lh = footer * 0.18;
        let lx = if logo_pos == "left" { m } else if logo_pos == "right" { w - m - lw } else { cx - lw / 2.0 };
        let ly = if logo_pos == "center" { m + ph + m + footer * 0.08 } else { title_y - lh / 2.0 };
        draw_brand_logo(env, lx, ly, lw, lh, &theme.fg);
    }
    Ok(Rect::from_xywh(m, m, pw, ph))
}
