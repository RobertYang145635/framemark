//! `const poetryCard: TemplateDef` (templates.ts:748) — 标题/日期 + 圆角照片 +
//! 色板瓦片与机型/参数页脚。Card: new-color-2.

use skia_safe::Rect;

use crate::draw::{
    draw_fitted, draw_photo, draw_photo_cover, extract_palette, tech_exif_line, theme_of, title_of,
    Env, FittedOpts, LayoutEnv,
};
use crate::hand::{format_date_only, model_without_make, pct};

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let s = env.pw.min(env.ph);
    let m = (s * (pct(env.params, "marginPct", 6.5) as f32)) / 100.0;
    let header = (s * (pct(env.params, "headerPct", 15.5) as f32)) / 100.0;
    let footer = (s * (pct(env.params, "footerPct", 15.9) as f32)) / 100.0;
    let aspect = pct(env.params, "photoAspect", 0.0) as f32;
    let window_h = if aspect > 0.0 {
        env.pw / aspect
    } else {
        env.ph
    };
    Ok((env.pw + 2.0 * m, header + window_h + footer + 2.0 * m))
}

fn draw_palette_tiles(env: &mut Env, colors: &[String], x: f32, y: f32, width: f32, height: f32) {
    if colors.is_empty() || width <= 0.0 || height <= 0.0 {
        return;
    }
    let tw = width / colors.len() as f32;
    let radius = (height * 0.12).min(tw * 0.06);
    for (i, c) in colors.iter().enumerate() {
        let tx = x + i as f32 * tw;
        env.ctx.fill_round_rect(tx, y, tw + 0.5, height, radius, c);
        env.ctx.record_shape(
            "palette-tile",
            Rect::from_xywh(tx, y, tw + 0.5, height),
            None,
            true,
        );
    }
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h, pw, ph) = (env.w, env.h, env.pw, env.ph);
    let s = pw.min(ph);
    let theme = theme_of(env.themes, env.params);
    let m = (w - pw) / 2.0;
    let header = (s * (pct(env.params, "headerPct", 15.5) as f32)) / 100.0;
    let aspect = pct(env.params, "photoAspect", 0.0) as f32;
    let window_h = if aspect > 0.0 { pw / aspect } else { ph };
    let footer = h - header - window_h - 2.0 * m;
    let radius = (s * (pct(env.params, "radiusPct", 2.0) as f32)) / 100.0;
    env.ctx.set_fill(&theme.bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    let title = title_of(env);
    let topts = FittedOpts::new("Inter")
        .weight(600)
        .min(window_h * 0.024)
        .slot("poetry.title");
    draw_fitted(
        env,
        &title,
        m,
        m + header * 0.305,
        pw,
        window_h * 0.0492,
        &theme.fg,
        &topts,
    );
    let dt = if env.text.show_date {
        format_date_only(&env.exif.date_time)
    } else {
        String::new()
    };
    let date_line = if dt.is_empty() {
        String::new()
    } else {
        format!("PHOTOGRAPHED IN: {dt}")
    };
    let dopts = FittedOpts::new("JetBrains Mono")
        .tracking(0.02)
        .min(window_h * 0.012)
        .slot("poetry.date");
    draw_fitted(
        env,
        &date_line,
        m,
        m + header * 0.72,
        pw,
        window_h * 0.023,
        &theme.sub,
        &dopts,
    );
    let shadow = pct(env.params, "shadow", 0.3) as f32;
    if aspect > 0.0 {
        draw_photo_cover(
            &mut env.ctx,
            env.photo,
            &env.crop,
            m,
            m + header,
            pw,
            window_h,
            radius,
            shadow,
            s,
        );
    } else {
        draw_photo(
            &mut env.ctx,
            env.photo,
            &env.crop,
            m,
            m + header,
            pw,
            ph,
            radius,
            shadow,
            &theme,
            s,
        );
    }
    let footer_top = m + header + window_h;
    let below = footer + m;
    let right_x = w - m - w * 0.018;
    let colors = extract_palette(env, 5);
    draw_palette_tiles(
        env,
        &colors,
        m,
        footer_top + below * 0.464,
        w * 0.317,
        window_h * 0.0253,
    );
    let mut model = model_without_make(&env.exif.make, &env.exif.model);
    if model.is_empty() {
        model = env.exif.camera_line("");
    }
    let mopts = FittedOpts::new("Inter")
        .weight(500)
        .align("right")
        .min(window_h * 0.018)
        .slot("poetry.model");
    draw_fitted(
        env,
        &model,
        right_x,
        footer_top + below * 0.537,
        w * 0.42,
        window_h * 0.038,
        &theme.fg,
        &mopts,
    );
    let ex = tech_exif_line(env)
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    let eopts = FittedOpts::new("JetBrains Mono")
        .align("right")
        .min(window_h * 0.01)
        .slot("poetry.exif");
    draw_fitted(
        env,
        &ex,
        right_x,
        footer_top + below * 0.757,
        w * 0.5,
        window_h * 0.0185,
        &theme.sub,
        &eopts,
    );
    Ok(Rect::from_xywh(m, m + header, pw, window_h))
}
