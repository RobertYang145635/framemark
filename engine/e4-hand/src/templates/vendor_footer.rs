//! `const vendorFooter: TemplateDef` (templates.ts:1494) — vendor watermark
//! layouts (Hasselblad h1–h4 / LUMO l1–l3 / ZEISS / Leica). Batch-3 card:
//! `vendor-hasselblad` (h1, white).

use skia_safe::Rect;

use crate::draw::{draw_brand_logo, draw_fitted, draw_photo, date_text, exif_text, theme_of, title_of, Env, FittedOpts, LayoutEnv};
use crate::hand::{pct, Exif};

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let vendor = env
        .params
        .get("vendor")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| "hasselblad".into());
    let default_style = if vendor == "lumo" { "l1" } else { "h1" };
    let style = env
        .params
        .get("vendorStyle")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| default_style.to_string());
    let s = env.pw.min(env.ph);
    if style == "h3" || style == "l2" {
        let side = (s * (pct(env.params, "sidePadPct", 2.78) as f32)) / 100.0;
        let bottom = (s * (pct(env.params, "footerPct", 30.83) as f32)) / 100.0;
        return Ok((env.pw + 2.0 * side, env.ph + side + bottom));
    }
    if style == "h4" || style == "l3" {
        let side = (s * (pct(env.params, "sidePadPct", 2.78) as f32)) / 100.0;
        let top = (s * (pct(env.params, "topPadPct", 21.94) as f32)) / 100.0;
        let bottom = (s * (pct(env.params, "footerPct", 21.94) as f32)) / 100.0;
        return Ok((env.pw + 2.0 * side, env.ph + top + bottom));
    }
    let default_footer = if vendor == "hasselblad" {
        if style == "h2" { 18.89 } else { 16.67 }
    } else if vendor == "lumo" {
        16.67
    } else {
        12.0
    };
    let footer_h = if vendor == "hasselblad" || vendor == "lumo" {
        (s * (pct(env.params, "footerPct", default_footer) as f32)) / 100.0
    } else {
        (env.pw * (pct(env.params, "footerPct", 12.0) as f32)) / 100.0
    };
    Ok((env.pw, env.ph + footer_h))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h, pw, ph) = (env.w, env.h, env.pw, env.ph);
    let theme = theme_of(env.themes, env.params);
    let vendor = env
        .params
        .get("vendor")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| "hasselblad".into());
    let default_style = if vendor == "lumo" { "l1" } else { "h1" };
    let style = env
        .params
        .get("vendorStyle")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| default_style.to_string());
    let s = pw.min(ph);
    let u = s / 360.0;
    let model = env.exif.model_without_make();
    let device = {
        let parts: Vec<String> = [env.exif.make.trim().to_string(), model.clone()]
            .into_iter()
            .filter(|x| !x.is_empty())
            .collect();
        if parts.is_empty() {
            title_of(env)
        } else {
            parts.join(" ")
        }
    };
    let full = {
        let cam = env.exif.camera_line("");
        if !cam.is_empty() {
            cam
        } else if !model.is_empty() {
            model.clone()
        } else {
            title_of(env)
        }
    };
    let sub_text = {
        let d = date_text(env);
        if !d.is_empty() {
            d
        } else {
            env.text.subtitle.trim().to_string()
        }
    };
    let exif_line = exif_text(env);
    let matte = style == "h3" || style == "l2" || style == "h4" || style == "l3";
    let side = if matte { (s * (pct(env.params, "sidePadPct", 2.78) as f32)) / 100.0 } else { 0.0 };
    let top_pad = if style == "h4" || style == "l3" {
        (s * (pct(env.params, "topPadPct", 21.94) as f32)) / 100.0
    } else {
        side
    };
    let footer_h = h - ph - top_pad;
    env.ctx.set_fill(&theme.bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    draw_photo(&mut env.ctx, env.photo, &env.crop, side, top_pad, pw, ph, 0.0, 0.0, &theme, s);
    let photo_bottom = top_pad + ph;
    let fg = theme.fg.clone();
    if style == "h2" {
        let cy = ph + footer_h / 2.0;
        let pad = 19.0 * u;
        let line_h = 15.03 * u;
        let block_h = line_h * 2.0 + 1.0 * u;
        draw_fitted(
            env,
            &device,
            pad,
            cy - block_h / 2.0 + line_h / 2.0,
            230.0 * u,
            11.0 * u,
            &fg,
            &FittedOpts::new("OplusSans").weight(500).baseline("middle").min(8.0 * u),
        );
        if !sub_text.is_empty() {
            env.ctx.save();
            env.ctx.set_global_alpha(0.65);
            draw_fitted(
                env,
                &sub_text,
                pad,
                cy + 1.0 * u + line_h / 2.0,
                230.0 * u,
                11.0 * u,
                &fg,
                &FittedOpts::new("OplusSans").baseline("middle").min(8.0 * u),
            );
            env.ctx.restore();
        }
        draw_brand_logo(env, w - pad - 36.0 * u, cy - 18.0 * u, 36.0 * u, 36.0 * u, &fg);
        return Ok(Rect::from_xywh(side, top_pad, pw, ph));
    }
    if style == "h3" || style == "l2" {
        let cy = photo_bottom + footer_h / 2.0;
        let logo_w = if style == "h3" { 36.0 * u } else { 65.0 * u };
        let logo_h = if style == "h3" { 36.0 * u } else { 40.0 * u };
        let model_size = if style == "h3" { 11.6 * u } else { 10.5 * u };
        let model_lh = if style == "h3" { 15.85 * u } else { 12.0 * u };
        let sub_size = if style == "h3" { 10.0 * u } else { 8.0 * u };
        let sub_lh = if style == "h3" { 13.66 * u } else { 9.0 * u };
        let gap_a = if style == "h3" { 4.0 * u } else { 2.5 * u };
        let gap_b = if style == "h3" { 1.0 * u } else { 2.0 * u };
        let block_h = logo_h + gap_a + model_lh + gap_b + sub_lh;
        let y0 = cy - block_h / 2.0;
        draw_brand_logo(env, (w - logo_w) / 2.0, y0, logo_w, logo_h, &fg);
        draw_fitted(
            env,
            &device,
            w / 2.0,
            y0 + logo_h + gap_a + model_lh / 2.0,
            340.0 * u,
            model_size,
            &fg,
            &FittedOpts::new("OplusSans")
                .weight(500)
                .align("center")
                .baseline("middle")
                .tracking(if style == "l2" { 0.02 } else { 0.0 })
                .min(model_size * 0.55),
        );
        if !sub_text.is_empty() {
            env.ctx.save();
            env.ctx.set_global_alpha(0.65);
            draw_fitted(
                env,
                &sub_text,
                w / 2.0,
                y0 + logo_h + gap_a + model_lh + gap_b + sub_lh / 2.0,
                340.0 * u,
                sub_size,
                &fg,
                &FittedOpts::new("OplusSans")
                    .align("center")
                    .baseline("middle")
                    .tracking(if style == "l2" { 0.06 } else { 0.0 })
                    .min(sub_size * 0.55),
            );
            env.ctx.restore();
        }
        return Ok(Rect::from_xywh(side, top_pad, pw, ph));
    }
    if style == "h4" || style == "l3" {
        let top_area = top_pad;
        if style == "h4" {
            draw_brand_logo(env, (w - 116.0 * u) / 2.0, (top_area - 9.0 * u) / 2.0, 116.0 * u, 9.0 * u, &fg);
        } else {
            draw_brand_logo(env, (w - 65.0 * u) / 2.0, (top_area - 40.0 * u) / 2.0, 65.0 * u, 40.0 * u, &fg);
        }
        let area_top = photo_bottom;
        let area_h = footer_h;
        if style == "h4" {
            let model_lh = 16.39 * u;
            let param_lh = 11.54 * u;
            let block_h = model_lh + 3.0 * u + param_lh;
            let y0 = area_top + (area_h - block_h) / 2.0;
            draw_fitted(
                env,
                &device,
                w / 2.0,
                y0 + model_lh / 2.0,
                340.0 * u,
                12.0 * u,
                &fg,
                &FittedOpts::new("OplusSans")
                    .weight(500)
                    .align("center")
                    .baseline("middle")
                    .tracking(0.03)
                    .min(8.0 * u),
            );
            if !exif_line.is_empty() {
                env.ctx.save();
                env.ctx.set_global_alpha(0.8);
                draw_fitted(
                    env,
                    &exif_line,
                    w / 2.0,
                    y0 + model_lh + 3.0 * u + param_lh / 2.0,
                    340.0 * u,
                    8.45 * u,
                    &fg,
                    &FittedOpts::new("OplusSans")
                        .weight(500)
                        .align("center")
                        .baseline("middle")
                        .tracking(0.03)
                        .min(6.0 * u),
                );
                env.ctx.restore();
            }
        } else {
            let model_lh = 14.0 * u;
            let sub_lh = 9.0 * u;
            let block_h = model_lh + 3.0 * u + sub_lh;
            let y0 = area_top + (area_h - block_h) / 2.0;
            draw_fitted(
                env,
                &device,
                w / 2.0,
                y0 + model_lh / 2.0,
                340.0 * u,
                12.0 * u,
                &fg,
                &FittedOpts::new("OplusSans")
                    .weight(600)
                    .align("center")
                    .baseline("middle")
                    .tracking(0.04)
                    .min(8.0 * u),
            );
            if !sub_text.is_empty() {
                draw_fitted(
                    env,
                    &sub_text,
                    w / 2.0,
                    y0 + model_lh + 3.0 * u + sub_lh / 2.0,
                    340.0 * u,
                    8.0 * u,
                    &fg,
                    &FittedOpts::new("OplusSans")
                        .align("center")
                        .baseline("middle")
                        .tracking(0.08)
                        .min(6.0 * u),
                );
            }
        }
        return Ok(Rect::from_xywh(side, top_pad, pw, ph));
    }
    if style == "l1" {
        let cy = ph + footer_h / 2.0;
        let pad = 20.0 * u;
        let model_lh = 14.0 * u;
        let sub_lh = 8.0 * u;
        let gap = 1.0 * u;
        let block_h = model_lh + gap + sub_lh;
        draw_fitted(
            env,
            &device,
            pad,
            cy - block_h / 2.0 + model_lh / 2.0,
            w * 0.6,
            12.0 * u,
            &fg,
            &FittedOpts::new("OplusSans").weight(600).baseline("middle").tracking(0.04).min(8.0 * u),
        );
        if !sub_text.is_empty() {
            draw_fitted(
                env,
                &sub_text,
                pad,
                cy - block_h / 2.0 + model_lh + gap + sub_lh / 2.0,
                w * 0.6,
                7.0 * u,
                &fg,
                &FittedOpts::new("OplusSans").baseline("middle").tracking(0.08).min(5.0 * u),
            );
        }
        draw_brand_logo(env, w - 16.0 * u - 52.0 * u, cy - 16.0 * u, 52.0 * u, 32.0 * u, &fg);
        return Ok(Rect::from_xywh(0.0, 0.0, pw, ph));
    }
    // h1 (Hasselblad) / zeiss / leica / lumo default
    let cy = ph + footer_h / 2.0;
    if vendor == "hasselblad" {
        let pad = (s * 24.0) / 360.0;
        let date = date_text(env);
        let left = if !date.is_empty() {
            date
        } else if !model.is_empty() {
            model.clone()
        } else {
            full.clone()
        };
        draw_fitted(
            env,
            &left,
            pad,
            cy,
            w * 0.5 - pad,
            (s * 13.0) / 360.0,
            &fg,
            &FittedOpts::new("OplusSans").weight(500).baseline("middle").min(s * 0.018),
        );
        if env.text.show_logo && env.images.logo.is_some() {
            let lw = (s * 112.8) / 360.0;
            let lh = (s * 8.96) / 360.0;
            let rp = (s * 24.0) / 360.0;
            let dot = (s * 7.2) / 360.0;
            let gap = (s * 8.94) / 360.0;
            let text_size = (s * 7.2) / 360.0;
            let total = lh + gap + text_size;
            let x = w - rp - lw;
            let y0 = cy - total / 2.0;
            draw_brand_logo(env, x, y0, lw, lh, &fg);
            let row_y = y0 + lh + gap + text_size / 2.0;
            env.ctx.fill_circle(x + dot / 2.0, row_y, dot / 2.0, "#FF8933");
            env.ctx.save();
            env.ctx.set_global_alpha(0.7);
            let right = if !model.is_empty() { model.clone() } else { full.clone() };
            draw_fitted(
                env,
                &right,
                x + dot + (s * 6.6) / 360.0,
                row_y,
                lw - dot,
                text_size,
                &fg,
                &FittedOpts::new("OplusSans").weight(700).baseline("middle").min(text_size * 0.5),
            );
            env.ctx.restore();
        } else {
            draw_fitted(
                env,
                "HASSELBLAD",
                w - pad,
                cy,
                w * 0.42,
                s * 0.03,
                &fg,
                &FittedOpts::new("OplusSans").weight(700).align("right").baseline("middle").min(s * 0.015),
            );
        }
        return Ok(Rect::from_xywh(0.0, 0.0, pw, ph));
    }
    let pad = w * 0.04;
    let square = vendor == "zeiss";
    let logo_h = if square { footer_h * 0.55 } else { footer_h * 0.32 };
    let logo_w = if square { logo_h } else { (w * 0.15).min(footer_h * 1.2) };
    let logo_x = w - pad - logo_w;
    let text_max = (w * 0.2).max(logo_x - pad - w * 0.03);
    let exif = if env.text.show_exif { env.exif.line(" ") } else { String::new() };
    let date = if env.text.show_date { crate::hand::format_date_only(&env.exif.date_time) } else { String::new() };
    let title_size = w * 0.024;
    let sub_size = w * 0.016;
    let lines: Vec<String> = [full.clone(), exif, date].into_iter().filter(|s| !s.is_empty()).collect();
    let line_gap = sub_size * 0.55;
    let block_h: f32 = lines
        .iter()
        .enumerate()
        .map(|(i, _)| if i == 0 { title_size } else { sub_size } + if i > 0 { line_gap } else { 0.0 })
        .sum();
    let mut y = cy - block_h / 2.0 + title_size * 0.52;
    for (i, text) in lines.iter().enumerate() {
        let size = if i == 0 { title_size } else { sub_size };
        draw_fitted(
            env,
            text,
            pad,
            y,
            text_max,
            size,
            if i == 0 { &fg } else { &theme.sub },
            &FittedOpts::new("OplusSans")
                .weight(if i == 0 { 600 } else { 400 })
                .baseline("middle")
                .min(size * 0.5),
        );
        y += size + line_gap;
    }
    if env.text.show_logo && env.images.logo.is_some() {
        draw_brand_logo(env, logo_x, cy - logo_h / 2.0, logo_w, logo_h, &fg);
    }
    let _ = Exif::default();
    Ok(Rect::from_xywh(0.0, 0.0, pw, ph))
}
