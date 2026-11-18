//! `const sideCard: TemplateDef` (templates.ts:289) — fixed-aspect card with the
//! photo and an F/MM/S chip column. Batch-3 card: `glass-white-1` (portrait
//! card mode: cardAspect 0.692, text-left, rowsX 14.16).

use skia_safe::Rect;

use crate::draw::{
    draw_brand_logo, draw_fitted, draw_photo_cover, measure_text, resolve_text_slot, theme_of, Env,
    FittedOpts, LayoutEnv,
};
use crate::hand::{format_aperture, format_shutter, pct};
use crate::jsval::js_round;

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let s = env.pw.min(env.ph);
    Ok((s * 1.5, s))
}

/// `(label slot, label, value slot, value)` for the three chip rows. Each row
/// is a declared pair so a label or a value can be edited independently.
fn rows_of(env: &Env) -> Vec<(&'static str, String, &'static str, String)> {
    let aperture = env
        .exif
        .aperture
        .map(format_aperture)
        .unwrap_or_default()
        .trim_start_matches('F')
        .to_string();
    let focal = env
        .exif
        .focal
        .map(|v| crate::jsval::format_js_number(js_round(v)))
        .unwrap_or_default();
    let shutter = env
        .exif
        .shutter
        .map(format_shutter)
        .unwrap_or_default()
        .trim_end_matches('s')
        .to_string();
    [
        ("sideCard.label.f", "F", "sideCard.aperture", aperture),
        ("sideCard.label.mm", "MM", "sideCard.focal", focal),
        ("sideCard.label.s", "S", "sideCard.shutter", shutter),
    ]
    .into_iter()
    .map(|(label_slot, label, value_slot, value)| {
        let label = resolve_text_slot(env, label_slot, label).text;
        let value = resolve_text_slot(env, value_slot, &value).text;
        (label_slot, label, value_slot, value)
    })
    .collect()
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h, pw, ph) = (env.w, env.h, env.pw, env.ph);
    let s = pw.min(ph);
    let theme = theme_of(env.themes, env.params);
    let m = (s * (pct(env.params, "marginPct", 4.0) as f32)) / 100.0;
    let radius = (s * (pct(env.params, "radiusPct", 2.0) as f32)) / 100.0;
    let shadow = pct(env.params, "shadow", 0.8) as f32;
    let layout_mode = env
        .params
        .get("sideLayout")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| "text-right".into());
    env.ctx.set_fill(&theme.bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    // 卡片的画幅：
    //  - `cardFit: "photo"`（产品默认）= 跟照片实际比例走，任何比例的照片都完整入卡，
    //    不再被 0.692 的竖幅窗口裁掉两侧 —— 这是「任意比例适配」的关键；
    //  - 显式 `cardAspect`（> 0）= 保持老的固定画幅语义（冻结卡片 / 黄金图复现）。
    let card_aspect_param = pct(env.params, "cardAspect", 0.0) as f32;
    let card_fit = env
        .params
        .get("cardFit")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_default();
    let follow_photo = card_fit == "photo" || card_aspect_param <= 0.0;
    let photo_aspect = if ph > 0.0 { pw / ph } else { 1.5 };
    let aspect = if follow_photo {
        photo_aspect
    } else {
        card_aspect_param
    }
    .max(0.05);
    let (photo_x, photo_y, ph_w, ph_h);
    if aspect < 0.9 {
        // 竖幅卡：高度吃满，宽度按画幅收；照片比例更窄时也不会顶到边。
        ph_h = h - 2.0 * m;
        ph_w = (ph_h * aspect).min(w - 2.0 * m);
        let auto_cx = if layout_mode == "text-left" {
            2.0 / 3.0
        } else if layout_mode == "text-right" {
            1.0 / 3.0
        } else {
            0.5
        };
        let cx_pct = pct(env.params, "cardCx", 0.0) as f32;
        photo_x = w * if cx_pct > 0.0 {
            cx_pct / 100.0
        } else {
            auto_cx
        } - ph_w / 2.0;
        photo_y = (h - ph_h) / 2.0;
        draw_photo_cover(
            &mut env.ctx,
            env.photo,
            &env.crop,
            photo_x,
            photo_y,
            ph_w,
            ph_h,
            radius,
            shadow,
            s,
        );
        render_portrait(
            env,
            w,
            h,
            s,
            photo_x,
            photo_y,
            ph_w,
            ph_h,
            &theme,
            &layout_mode,
        );
    } else {
        let max_w = w * if layout_mode == "split" { 0.52 } else { 0.58 };
        let mut h2 = h - 2.0 * m;
        let mut w2 = h2 * aspect;
        if w2 > max_w {
            w2 = max_w;
            h2 = w2 / aspect;
        }
        photo_x = if layout_mode == "text-right" {
            m
        } else if layout_mode == "text-left" {
            w - m - w2
        } else {
            (w - w2) / 2.0
        };
        photo_y = (h - h2) / 2.0;
        ph_w = w2;
        ph_h = h2;
        draw_photo_cover(
            &mut env.ctx,
            env.photo,
            &env.crop,
            photo_x,
            photo_y,
            ph_w,
            ph_h,
            radius,
            shadow,
            s,
        );
        render_landscape(
            env,
            w,
            h,
            s,
            m,
            photo_x,
            photo_y,
            ph_w,
            ph_h,
            &theme,
            &layout_mode,
        );
    }
    Ok(Rect::from_xywh(photo_x, photo_y, ph_w, ph_h))
}

#[allow(clippy::too_many_arguments)]
fn render_portrait(
    env: &mut Env,
    w: f32,
    h: f32,
    s: f32,
    photo_x: f32,
    photo_y: f32,
    ph_w: f32,
    ph_h: f32,
    theme: &crate::draw::Theme,
    layout_mode: &str,
) {
    let rows = rows_of(env);
    let box_w = s * 0.086;
    let box_h = s * 0.052;
    let row_gap = (s * (pct(env.params, "rowGap", 6.5) as f32)) / 100.0;
    let row_pitch = box_h + row_gap;
    let label_size = s * 0.023;
    let value_size = s * 0.035;
    let value_ct = s * 0.2032;
    let split = layout_mode == "split";
    let mut value_w: f32 = 0.0;
    for (_, _, _, v) in &rows {
        value_w = value_w.max(measure_text(env, v, "JetBrains Mono", 400, value_size, 0.0));
    }
    let block_w = value_ct + value_w / 2.0;
    let rows_top =
        (h * (pct(env.params, "rowsTop", if split { 39.4 } else { 43.13 }) as f32)) / 100.0;
    let brand_cy =
        (h * (pct(env.params, "brandCY", if split { 49.9 } else { 30.55 }) as f32)) / 100.0;
    let panel_l = photo_x;
    let panel_r = w - (photo_x + ph_w);
    let explicit_x = pct(env.params, "rowsX", 0.0) as f32;
    let mut block_left = if explicit_x > 0.0 {
        (w * explicit_x) / 100.0
    } else if split {
        photo_x + ph_w + (panel_r - block_w) / 2.0
    } else if layout_mode == "text-left" {
        (panel_l - block_w) / 2.0
    } else {
        photo_x + ph_w + (panel_r - block_w) / 2.0
    };
    block_left = block_left.max(s * 0.02).min(w - block_w - s * 0.02);
    let value_cx = block_left + value_ct;
    for (i, (label_slot, label, value_slot, value)) in rows.iter().enumerate() {
        let y = rows_top + i as f32 * row_pitch;
        env.ctx.stroke_round_rect(
            block_left,
            y,
            box_w,
            box_h,
            box_h * 0.22,
            &theme.line,
            (s * 0.0014).max(1.0),
            None,
        );
        draw_fitted(
            env,
            label,
            block_left + box_w / 2.0,
            y + box_h / 2.0,
            box_w * 0.7,
            label_size,
            &theme.sub,
            &FittedOpts::new("JetBrains Mono")
                .align("center")
                .baseline("middle")
                .slot(label_slot),
        );
        draw_fitted(
            env,
            value,
            value_cx,
            y + box_h / 2.0,
            value_w * 1.02,
            value_size,
            &theme.fg,
            &FittedOpts::new("JetBrains Mono")
                .align("center")
                .baseline("middle")
                .slot(value_slot),
        );
    }
    let bw = s * if split { 0.178 } else { 0.256 };
    let bh = s * if split { 0.03 } else { 0.044 };
    let brand_x = if split {
        (photo_x - bw) / 2.0
    } else {
        block_left
    };
    draw_brand_logo(env, brand_x, brand_cy - bh / 2.0, bw, bh, &theme.fg);
    let _ = (photo_y, ph_h);
}

#[allow(clippy::too_many_arguments)]
fn render_landscape(
    env: &mut Env,
    w: f32,
    h: f32,
    s: f32,
    m: f32,
    photo_x: f32,
    photo_y: f32,
    ph_w: f32,
    ph_h: f32,
    theme: &crate::draw::Theme,
    layout_mode: &str,
) {
    let gap = s * 0.045;
    let label_size = s * 0.021;
    let box_w = s * 0.055;
    let box_h = s * 0.038;
    let row_gap = s * 0.035;
    let value_size = s * 0.029;
    let brand_h = s * 0.055;
    let rows = rows_of(env);
    let draw_rows = |env: &mut Env, x: f32, width: f32, top: f32| {
        let row_h = box_h + row_gap;
        for (i, (label_slot, label, value_slot, value)) in rows.iter().enumerate() {
            let y = top + i as f32 * row_h;
            env.ctx.stroke_round_rect(
                x,
                y,
                box_w,
                box_h,
                box_h * 0.22,
                &theme.line,
                (s * 0.0014).max(1.0),
                None,
            );
            draw_fitted(
                env,
                label,
                x + box_w / 2.0,
                y + box_h / 2.0,
                box_w * 0.7,
                label_size,
                &theme.sub,
                &FittedOpts::new("JetBrains Mono")
                    .align("center")
                    .baseline("middle")
                    .slot(label_slot),
            );
            draw_fitted(
                env,
                value,
                x + box_w + s * 0.022,
                y + box_h / 2.0,
                width - box_w - s * 0.022,
                value_size,
                &theme.fg,
                &FittedOpts::new("JetBrains Mono")
                    .baseline("middle")
                    .slot(value_slot),
            );
        }
    };
    let draw_brand = |env: &mut Env, cx: f32, width: f32, cy: f32| {
        let bw = (width * 0.72).min(s * 0.3);
        draw_brand_logo(
            env,
            cx - bw / 2.0,
            cy - brand_h / 2.0,
            bw,
            brand_h,
            &theme.fg,
        );
    };
    // 文字块跟着卡片走：横构图照片的卡片会比画布矮，按 h 的百分比定位会让
    // 品牌和参数行浮在卡片上方，看起来像两块不相干的内容。
    if layout_mode == "split" {
        let left_w = photo_x - gap - m;
        let right_x = photo_x + ph_w + gap;
        let right_w = w - m - right_x;
        draw_brand(env, m + left_w / 2.0, left_w, photo_y + ph_h * 0.5);
        draw_rows(env, right_x, right_w, photo_y + ph_h * 0.29);
    } else {
        let panel_x = if layout_mode == "text-left" {
            m
        } else {
            photo_x + ph_w + gap
        };
        let panel_w = if layout_mode == "text-left" {
            photo_x - gap - m
        } else {
            w - m - panel_x
        };
        draw_brand(env, panel_x + panel_w / 2.0, panel_w, photo_y + ph_h * 0.27);
        draw_rows(env, panel_x, panel_w, photo_y + ph_h * 0.41);
    }
}
