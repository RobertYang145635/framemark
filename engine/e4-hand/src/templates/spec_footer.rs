//! `const specFooter: TemplateDef` (templates.ts:463) — 照片 + 白底参数页脚：
//! 机型/品牌与 F/MM/S 三框。Card: glass-card-4.

use skia_safe::Rect;

use crate::draw::{
    draw_brand_logo, draw_fitted, draw_photo, resolve_text_slot, theme_of, Env, FittedOpts,
    LayoutEnv,
};
use crate::hand::{format_aperture, format_shutter, model_without_make, pct};

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let s = env.pw.min(env.ph);
    let m = (s * (pct(env.params, "marginPct", 3.0) as f32)) / 100.0;
    let footer_h = (s * (pct(env.params, "footerPct", 22.0) as f32)) / 100.0;
    Ok((env.pw + 2.0 * m, env.ph + 2.0 * m + footer_h))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h, pw, ph) = (env.w, env.h, env.pw, env.ph);
    let s = pw.min(ph);
    let theme = theme_of(env.themes, env.params);
    let m = (w - pw) / 2.0;
    let footer_h = h - ph - 2.0 * m;
    let radius = (s * (pct(env.params, "radiusPct", 1.5) as f32)) / 100.0;
    env.ctx.set_fill(&theme.bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    draw_photo(
        &mut env.ctx,
        env.photo,
        &env.crop,
        m,
        m,
        pw,
        ph,
        radius,
        pct(env.params, "shadow", 0.5) as f32,
        &theme,
        s,
    );

    let content_w = (w * (pct(env.params, "contentW", 82.0) as f32)) / 100.0;
    let footer_top = m + ph + m;
    let brand_y = footer_top + footer_h * 0.25;
    let mut model = model_without_make(&env.exif.make, &env.exif.model);
    if model.is_empty() {
        model = env.exif.camera_line("");
    }
    let model = resolve_text_slot(env, "specFooter.model", &model).text;
    let sep_x = (w * (pct(env.params, "sepX", 50.0) as f32)) / 100.0;
    let opts = FittedOpts::new("Inter")
        .weight(600)
        .align("right")
        .baseline("middle")
        .slot("specFooter.model");
    draw_fitted(
        env,
        &model,
        sep_x - s * 0.035,
        brand_y,
        w * 0.24,
        s * 0.022,
        &theme.fg,
        &opts,
    );
    let line_w = (s * 0.0012).max(1.0);
    env.ctx.stroke_line(
        sep_x,
        brand_y - s * 0.028,
        sep_x,
        brand_y + s * 0.028,
        &theme.line,
        line_w,
        None,
    );
    env.ctx.record_shape(
        "divider",
        Rect::from_xywh(sep_x - line_w / 2.0, brand_y - s * 0.028, line_w, s * 0.056),
        None,
        true,
    );
    let logo_w = s * 0.14;
    let logo_h = s * 0.035;
    let logo_gap = (s * (pct(env.params, "logoGap", 3.5) as f32)) / 100.0;
    draw_brand_logo(
        env,
        sep_x + logo_gap,
        brand_y - logo_h / 2.0,
        logo_w,
        logo_h,
        &theme.fg,
    );

    let aperture = env.exif.aperture.map(format_aperture).unwrap_or_default();
    let focal = env
        .exif
        .focal
        .map(|f| format!("{}", crate::jsval::js_round(f)))
        .unwrap_or_default();
    let shutter = format_shutter(env.exif.shutter.unwrap_or(0.0))
        .trim_end_matches('s')
        .to_string();
    // Labels and values are two declared rows per cell, matching the legacy
    // seven-line inventory for this card.
    let values = [
        (
            "specFooter.label.f",
            "F",
            "specFooter.aperture",
            aperture.trim_start_matches('F'),
        ),
        (
            "specFooter.label.mm",
            "MM",
            "specFooter.focal",
            focal.as_str(),
        ),
        (
            "specFooter.label.s",
            "S",
            "specFooter.shutter",
            shutter.as_str(),
        ),
    ];
    let row_w = content_w;
    let cell_w = row_w / 3.0;
    let box_w = s * 0.075;
    let box_h = s * 0.04;
    let box_y = footer_top + footer_h * 0.50;
    for (i, (label_slot, label_raw, value_slot, value_raw)) in values.iter().enumerate() {
        let cx = (w - row_w) / 2.0 + cell_w * (i as f32 + 0.5);
        let bx = cx - box_w / 2.0;
        env.ctx.stroke_round_rect(
            bx,
            box_y,
            box_w,
            box_h,
            box_h * 0.25,
            &theme.line,
            (s * 0.0014).max(1.0),
            None,
        );
        env.ctx.record_shape(
            "spec-box",
            Rect::from_xywh(bx, box_y, box_w, box_h),
            None,
            true,
        );
        let label = resolve_text_slot(env, label_slot, label_raw).text;
        let value = resolve_text_slot(env, value_slot, value_raw).text;
        let lopts = FittedOpts::new("JetBrains Mono")
            .align("center")
            .baseline("middle")
            .slot(label_slot);
        draw_fitted(
            env,
            &label,
            cx,
            box_y + box_h / 2.0,
            box_w * 0.7,
            s * 0.018,
            &theme.sub,
            &lopts,
        );
        let vopts = FittedOpts::new("JetBrains Mono")
            .align("center")
            .baseline("middle")
            .slot(value_slot);
        draw_fitted(
            env,
            &value,
            cx,
            footer_top + footer_h * 0.80,
            cell_w * 0.8,
            s * 0.026,
            &theme.fg,
            &vopts,
        );
    }
    Ok(Rect::from_xywh(m, m, pw, ph))
}
