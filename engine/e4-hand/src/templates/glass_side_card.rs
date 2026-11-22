//! `const glassSideCard: TemplateDef` (templates.ts:3534) — blurred backdrop,
//! an inset photo card on a frosted matte, and an F/ISO/S chip column.
//! Batch-2 card: `glass-card` (explicitly re-runs the batch-1 blur F1 case).
//!
//! 画布规则（「任意比例适配」的落点）：
//!  - `canvasAspect > 0`：画布按声明的比例（冻结卡 / 黄金图复现用）。
//!  - 否则：画布由构图推出来。高度驱动的卡（玻璃 05）要保持「卡片 88.7%H、
//!    照片从 45.9% 开始、上下 5.65% 留白」，卡片又跟照片同比例、不裁照片，
//!    画布就必须比照片宽：`画布宽高比 = 卡片宽高比 × 0.887 / 0.4089`。
//!    参考设计（卡片 0.6903）算出来正好是原来的 1.4971，所以同一套公式既复现
//!    冻结构图，又能在 3:4 / 1:1 / 16:9 下把照片完整放进卡片。
//!  - 宽度驱动的卡（玻璃卡片）不用这套：卡片本来就是画布的 56.25%W，画布跟
//!    照片走即可，缩进来的空白由居中的卡片自己吸收。

use skia_safe::Rect;

use crate::draw::{
    draw_blurred_backdrop, draw_brand_logo, draw_fitted, draw_photo_cover, measure_text,
    resolve_text_slot, theme_of, Env, FittedOpts, LayoutEnv,
};
use crate::hand::{format_aperture, format_shutter, pct};
use crate::jsval::{js_round, pct as js_pct};

/// 照片卡最多占「可用宽度」的百分比。参考设计（1024×684 画布、419×607 卡片、
/// inset 13.18%）里是 419 / (1024 - 2×135) = 55.53%，取它当缺省值，参考构图
/// 就正好落在上限上（画布推导与卡片收口用同一个数，不会自相矛盾）。
const DEFAULT_CARD_MAX_WIDTH_PCT: f64 = 55.53;

fn card_follows_photo(params: &crate::hand::Params) -> bool {
    params
        .get("cardFit")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_default()
        == "photo"
}

/// 卡片的宽高比：`cardFit=photo` 时跟照片一致（不裁照片），否则用 `cardAspect`。
fn card_aspect_of(params: &crate::hand::Params, pw: f32, ph: f32) -> f32 {
    if card_follows_photo(params) {
        (pw / ph.max(1.0)).max(0.05)
    } else {
        (pct(params, "cardAspect", 4.0 / 3.0) as f32).max(0.2)
    }
}

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let declared = pct(env.params, "canvasAspect", 0.0);
    let height_pct = pct(env.params, "cardHeightPct", 0.0) as f32;
    if height_pct > 0.0 && !(declared > 0.05) {
        // 卡片高度占比 ÷ 卡片宽度占比 = 画布宽高比 ÷ 卡片宽高比。
        let inset_pct = (pct(env.params, "cardInsetPct", 6.35) as f32).clamp(0.0, 45.0);
        let max_pct = (pct(env.params, "cardMaxWidthPct", DEFAULT_CARD_MAX_WIDTH_PCT) as f32)
            .clamp(20.0, 100.0);
        let width_share = ((1.0 - 2.0 * inset_pct / 100.0) * max_pct / 100.0).max(0.05);
        let k = (height_pct / 100.0) / width_share;
        return Ok(crate::draw::aspect_canvas(
            env.pw,
            env.ph,
            (card_aspect_of(env.params, env.pw, env.ph) * k) as f64,
        ));
    }
    Ok(crate::draw::aspect_canvas(env.pw, env.ph, declared))
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
    // 卡片画幅：`cardFit=photo` 时跟照片自身比例走（照片不裁；玻璃 05 靠 layout()
    // 把画布加宽来容纳侧栏，玻璃卡片靠 `cardWidthPct` 收口）；否则用 `cardAspect`。
    let aspect = card_aspect_of(env.params, env.pw, env.ph);
    let inset = (w * (pct(env.params, "cardInsetPct", 6.35) as f32)) / 100.0;
    let avail_w = (w - 2.0 * inset).max(1.0);
    let avail_h = (h - 2.0 * inset).max(1.0);
    let height_pct = pct(env.params, "cardHeightPct", 0.0) as f32;
    // 照片卡最多占「可用宽度」的这么多，剩下的必须留给文字栏。
    //
    // 高度驱动的卡（玻璃 05）在「画布跟照片」模式下由 layout() 保证画布够宽，
    // 这里只是兜住滑杆极值：卡片顶到 avail_w 时文字栏只剩一个 `cardInsetPct`，
    // 而 F/MM/S 的字号、行距仍按 card_h 算，三个数值就会压到照片上（rc57 的
    // 13.18% 就是这么来的）。参考设计的 55.53% 同时是 layout() 推导画布的系数，
    // 两边用同一个数，正常构图不会被收口。
    let max_card_w = avail_w
        * (pct(env.params, "cardMaxWidthPct", DEFAULT_CARD_MAX_WIDTH_PCT) as f32).clamp(20.0, 100.0)
        / 100.0;
    let (mut card_w, mut card_h);
    if height_pct > 0.0 {
        card_h = (h * height_pct) / 100.0;
        card_w = card_h * aspect;
        if card_w > max_card_w {
            card_w = max_card_w;
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
    let (mut box_w, mut box_h, mut pitch, mut value_gap, mut label_size, mut value_size) = (
        (card_h * (pct(env.params, "rowBoxWPct", 19.2) as f32)) / 100.0,
        (card_h * (pct(env.params, "rowBoxHPct", 9.95) as f32)) / 100.0,
        (card_h * (pct(env.params, "rowPitchPct", 16.67) as f32)) / 100.0,
        (card_h * (pct(env.params, "rowValueGapPct", 6.48) as f32)) / 100.0,
        (card_h * (pct(env.params, "rowLabelPct", 4.4) as f32)) / 100.0,
        (card_h * (pct(env.params, "rowValuePct", 5.8) as f32)) / 100.0,
    );
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
    let text_inset = pct(env.params, "textInsetPct", 30.5) as f32;
    // 整块字必须留在文字栏里：竖图 + 宽卡时面板只剩 37%，按 card_h 算出来的
    // 标签框/行距/数值宽会比面板还宽，数值就压到照片上（玻璃卡片 3:4 就是这样）。
    // 装不下时整组等比缩小，参考比例（4:3 的两张卡）本来就有余量，不受影响。
    let panel_avail = (panel_w * (1.0 - text_inset / 100.0)).max(1.0);
    let mut block_w = box_w + value_gap + value_w;
    if block_w > panel_avail * 0.96 {
        let k = (panel_avail * 0.96 / block_w).clamp(0.6, 1.0);
        box_w *= k;
        box_h *= k;
        pitch *= k;
        value_gap *= k;
        label_size *= k;
        value_size *= k;
        value_w *= k;
        block_w = box_w + value_gap + value_w;
    }
    let block_x = anchor_x(text_inset, block_w);
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
