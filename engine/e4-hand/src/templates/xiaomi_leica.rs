//! `const xiaomiLeica: TemplateDef` (templates.ts:1782) — 小米徕卡式页脚：
//! 短边 /1080 的 168/138 设计高度、左品牌右参数、分隔线、徕卡红标。
//! Cards: vendor-xiaomi (带日期) / vendor-xiaomi-single.

use skia_safe::Rect;

use crate::draw::{
    draw_brand_logo, draw_fitted, draw_photo, measure_text, resolve_text_slot, theme_of, title_of,
    Env, FittedOpts, LayoutEnv,
};
use crate::hand::{format_date_time, model_without_make, pct};

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let ratio = env.pw.min(env.ph) / 1080.0;
    let design_h = if env.text.show_date { 168.0 } else { 138.0 };
    let raw = design_h * ratio;
    Ok((env.pw, env.ph + (raw / 8.0).floor() * 8.0))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h, pw, ph) = (env.w, env.h, env.pw, env.ph);
    let theme = theme_of(env.themes, env.params);
    let ratio = pw.min(ph) / 1080.0;
    let multi = env.text.show_date;
    let footer_h = h - ph;
    env.ctx.set_fill(&theme.bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    draw_photo(
        &mut env.ctx,
        env.photo,
        &env.crop,
        0.0,
        0.0,
        pw,
        ph,
        0.0,
        0.0,
        &theme,
        pw.min(ph),
    );
    let pad = 50.0 * ratio;
    let brand = {
        let mut parts: Vec<String> = Vec::new();
        if !env.exif.make.trim().is_empty() {
            parts.push(env.exif.make.trim().to_string());
        }
        let model = model_without_make(&env.exif.make, &env.exif.model);
        if !model.trim().is_empty() {
            parts.push(model.trim().to_string());
        }
        if parts.is_empty() {
            title_of(env)
        } else {
            parts.join(" ")
        }
    };
    let exif = if env.text.show_exif {
        env.exif.line("  ")
    } else {
        String::new()
    };
    let brand_size = 30.0 * ratio;
    let small_size = 22.0 * ratio;
    let top_line_h = brand_size * 1.2;
    let bottom_line_h = small_size * 1.2;
    let gap = 20.0 * ratio;
    let block_h = top_line_h + if multi { bottom_line_h + gap } else { 0.0 };
    let top_center = ph + (footer_h - block_h) / 2.0 + top_line_h / 2.0;
    let bottom_center = ph + footer_h - (footer_h - block_h) / 2.0 - bottom_line_h / 2.0;
    let brand_opts = FittedOpts::new("OplusSans")
        .weight(500)
        .baseline("middle")
        .min(brand_size * 0.45)
        .slot("xiaomiLeica.brand");
    draw_fitted(
        env,
        &brand,
        pad,
        top_center,
        (w * 0.2).max(w * 0.55 - pad),
        brand_size,
        &theme.fg,
        &brand_opts,
    );
    let exif_resolved = resolve_text_slot(env, "xiaomiLeica.exif", &exif);
    let opts = FittedOpts::new("OplusSans")
        .weight(500)
        .align("right")
        .baseline("middle")
        .min(brand_size * 0.45)
        .slot("xiaomiLeica.exif");
    draw_fitted(
        env,
        &exif,
        w - pad,
        top_center,
        w * 0.46,
        brand_size,
        &theme.fg,
        &opts,
    );
    let exif_w = if exif_resolved.text.is_empty() || exif_resolved.hidden {
        0.0
    } else {
        measure_text(env, &exif_resolved.text, "OplusSans", 500, brand_size, 0.0)
    };
    if multi {
        let dt = format_date_time(&env.exif.date_time).replace('-', ".");
        let opts = FittedOpts::new("OplusSans")
            .weight(400)
            .baseline("middle")
            .tracking(0.05)
            .min(small_size * 0.5)
            .slot("xiaomiLeica.date");
        draw_fitted(
            env,
            &dt,
            w - pad - exif_w,
            bottom_center,
            (w * 0.2).max(exif_w),
            small_size,
            "rgba(0,0,0,0.55)",
            &opts,
        );
    }
    if exif_w > 0.0 {
        let divider_x = w - pad - exif_w - 22.0 * ratio;
        let divider_top = ph + if multi { 52.0 } else { 44.0 } * ratio;
        env.ctx.fill_rect_color(
            divider_x,
            divider_top,
            (2.0 * ratio).max(1.0),
            h - divider_top,
            "rgba(0,0,0,0.2)",
        );
        env.ctx.record_shape(
            "divider",
            Rect::from_xywh(
                divider_x,
                divider_top,
                (2.0 * ratio).max(1.0),
                h - divider_top,
            ),
            None,
            true,
        );
        if env.text.show_logo && env.images.logo.is_some() {
            let logo_h = if multi { 64.0 } else { 58.0 } * ratio;
            let logo_w = logo_h;
            let logo_x = divider_x - 22.0 * ratio - logo_w;
            let logo_y = ph + (footer_h - logo_h) / 2.0 + if multi { 0.0 } else { 1.0 };
            draw_brand_logo(env, logo_x, logo_y, logo_w, logo_h, &theme.fg);
        }
    }
    Ok(Rect::from_xywh(0.0, 0.0, pw, ph))
}
