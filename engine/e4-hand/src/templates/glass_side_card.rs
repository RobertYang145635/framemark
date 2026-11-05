//! `const glassSideCard: TemplateDef` (templates.ts:3534) — blurred backdrop,
//! an inset photo card on a frosted matte, and an F/ISO/S chip column.
//! Batch-2 card: `glass-card` (explicitly re-runs the batch-1 blur F1 case).

use skia_safe::Rect;

use crate::draw::{
    draw_blurred_backdrop, draw_brand_logo, draw_fitted, draw_photo_cover, measure_text,
    resolve_text_slot, theme_of, Env, FittedOpts, LayoutEnv,
};
use crate::hand::{format_aperture, format_shutter, pct};
use crate::jsval::{js_round, pct as js_pct};

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    Ok(crate::draw::aspect_canvas(
        env.pw,
        env.ph,
        pct(env.params, "canvasAspect", 0.0),
    ))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let theme = theme_of(env.themes, env.params);
    draw_blurred_backdrop(env, w, h, &theme, pct(env.params, "tintAlpha", 0.2) as f32);
    let side = env
        .params
        .get("cardSide")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| "right".into());
    let side_left = side == "left";
    let content_side = env
        .params
        .get("contentSide")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| {
            if side_left {
                "right".into()
            } else {
                "left".into()
            }
        });
    let aspect = (pct(env.params, "cardAspect", 4.0 / 3.0) as f32).max(0.2);
    let inset = (w * (pct(env.params, "cardInsetPct", 6.35) as f32)) / 100.0;
    let avail_w = (w - 2.0 * inset).max(1.0);
    let avail_h = (h - 2.0 * inset).max(1.0);
    let height_pct = pct(env.params, "cardHeightPct", 0.0) as f32;
    let (mut card_w, mut card_h);
    if height_pct > 0.0 {
        card_h = (h * height_pct) / 100.0;
        card_w = card_h * aspect;
        if card_w > avail_w {
            card_w = avail_w;
            card_h = card_w / aspect;
        }
    } else {
        card_w = avail_w.min((w * (pct(env.params, "cardWidthPct", 56.25) as f32)) / 100.0);
        card_h = card_w / aspect;
        if card_h > avail_h {
            card_h = avail_h;
            card_w = card_h * aspect;
        }
    }
    let card_x = if side_left { inset } else { w - inset - card_w };
    let card_y = (h - card_h) / 2.0;
    let s = card_w.min(card_h);
    let radius = (s * (pct(env.params, "cardRadiusPct", 1.6) as f32)) / 100.0;
    let matte = (s * (pct(env.params, "cardMattePct", 1.8) as f32)) / 100.0;
    env.ctx.save();
    env.ctx.translate(0.0, 0.0);
    env.ctx.fill_round_rect_shadow(
        card_x - matte,
        card_y - matte,
        card_w + 2.0 * matte,
        card_h + 2.0 * matte,
        radius + matte,
        "rgba(255,255,255,0.09)",
        "rgba(0,0,0,0.32)",
        s * 0.07,
        s * 0.015,
    );
    env.ctx.restore();
    draw_photo_cover(
        &mut env.ctx,
        env.photo,
        &env.crop,
        card_x,
        card_y,
        card_w,
        card_h,
        radius,
        1.0,
        s,
    );
    env.ctx.stroke_round_rect(
        card_x,
        card_y,
        card_w,
        card_h,
        radius,
        "rgba(255,255,255,0.34)",
        (s * 0.0022).max(1.0),
        None,
    );
    let panel_left = if side_left { card_x + card_w } else { 0.0 };
    let panel_w = if side_left {
        w - (card_x + card_w)
    } else {
        card_x
    };
    let from_start = content_side == "left";
    let anchor_x = |inset_pct: f32, width: f32| -> f32 {
        if from_start {
            panel_left + panel_w * inset_pct / 100.0
        } else {
            panel_left + panel_w - panel_w * inset_pct / 100.0 - width
        }
    };
    let aperture = env
        .exif
        .aperture
        .map(format_aperture)
        .unwrap_or_default()
        .trim_start_matches('F')
        .to_string();
    let shutter = env
        .exif
        .shutter
        .map(format_shutter)
        .unwrap_or_default()
        .trim_end_matches('s')
        .to_string();
    let iso = env
        .exif
        .iso
        .map(|v| crate::jsval::format_js_number(js_round(v)))
        .unwrap_or_default();
    let focal = env
        .exif
        .focal
        .map(|v| crate::jsval::format_js_number(js_round(v)))
        .unwrap_or_default();
    let row_set = env
        .params
        .get("rowSet")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| "fiso".into());
    let rows: Vec<(&str, &str, String)> = if row_set == "fmm" {
        vec![
            ("aperture", "F", aperture),
            ("focal", "MM", focal),
            ("shutter", "S", shutter),
        ]
    } else {
        vec![
            ("aperture", "F", aperture),
            ("iso", "ISO", iso),
            ("shutter", "S", shutter),
        ]
    };
    let box_w = (card_h * (pct(env.params, "rowBoxWPct", 19.2) as f32)) / 100.0;
    let box_h = (card_h * (pct(env.params, "rowBoxHPct", 9.95) as f32)) / 100.0;
    let pitch = (card_h * (pct(env.params, "rowPitchPct", 16.67) as f32)) / 100.0;
    let value_gap = (card_h * (pct(env.params, "rowValueGapPct", 6.48) as f32)) / 100.0;
    let label_size = (card_h * (pct(env.params, "rowLabelPct", 4.4) as f32)) / 100.0;
    let value_size = (card_h * (pct(env.params, "rowValuePct", 5.8) as f32)) / 100.0;
    let mut value_w: f32 = 0.0;
    for (field, _, value) in &rows {
        let value_slot = format!("glassSide.field.{field}.value");
        let resolved = resolve_text_slot(env, &value_slot, value);
        value_w = value_w.max(measure_text(
            env,
            &resolved.text,
            "JetBrains Mono",
            400,
            value_size,
            0.0,
        ));
    }
    let block_w = box_w + value_gap + value_w;
    let block_x = anchor_x(pct(env.params, "textInsetPct", 30.5) as f32, block_w);
    let brand_w = (panel_w * (pct(env.params, "brandWidthPct", 28.7) as f32)) / 100.0;
    let brand_h = (card_h * (pct(env.params, "brandHeightPct", 17.8) as f32)) / 100.0;
    let brand_x = anchor_x(pct(env.params, "brandInsetPct", 36.8) as f32, brand_w);
    let brand_y = card_y + (card_h * (pct(env.params, "brandTopPct", 12.27) as f32)) / 100.0;
    if env.text.show_logo {
        draw_brand_logo(env, brand_x, brand_y, brand_w, brand_h, "#ffffff");
    }
    let rows_top = card_y + (card_h * (pct(env.params, "rowsTopPct", 43.75) as f32)) / 100.0;
    for (i, (field, label, value)) in rows.iter().enumerate() {
        let y = rows_top + i as f32 * pitch;
        let label_slot = format!("glassSide.field.{field}.label");
        let value_slot = format!("glassSide.field.{field}.value");
        env.ctx.stroke_round_rect(
            block_x,
            y,
            box_w,
            box_h,
            box_h * 0.2,
            "rgba(255,255,255,0.62)",
            (s * 0.0028).max(1.0),
            None,
        );
        draw_fitted(
            env,
            label,
            block_x + box_w / 2.0,
            y + box_h / 2.0,
            box_w * 0.72,
            label_size,
            "rgba(255,255,255,0.92)",
            &FittedOpts::new("JetBrains Mono")
                .align("center")
                .baseline("middle")
                .min(label_size * 0.5)
                .shadow(true)
                .slot(&label_slot),
        );
        draw_fitted(
            env,
            value,
            block_x + box_w + value_gap,
            y + box_h / 2.0,
            value_w * 1.02,
            value_size,
            "#ffffff",
            &FittedOpts::new("JetBrains Mono")
                .baseline("middle")
                .min(value_size * 0.45)
                .shadow(true)
                .slot(&value_slot),
        );
    }
    let _ = js_pct(env.params.get("cardAspect"), 4.0 / 3.0);
    Ok(Rect::from_xywh(card_x, card_y, card_w, card_h))
}
