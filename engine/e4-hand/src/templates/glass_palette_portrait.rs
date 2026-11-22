//! `const glassPalettePortrait: TemplateDef` (templates.ts:3415) — 3:4 竖幅满幅照片，
//! 底部玻璃面板内 3×2 圆色卡与十六进制色值。Card: glass-palette.

use skia_safe::Rect;

use crate::draw::{
    draw_glass_panel, draw_photo_cover, draw_text_exact_slot, extract_palette, Env, LayoutEnv,
};
use crate::hand::pct;

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    // 画布跟照片走：设计稿是 3:4 竖幅，但横构图照片塞进 3:4 会被裁掉两侧。
    // 面板尺寸按宽度换算（设计比例 676:310），所以任何比例都排得下。
    Ok((env.pw.round(), env.ph.round()))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let s = w.min(h);
    let cols = (pct(env.params, "swatchCols", 3.0) as f32).round().max(1.0) as i32;
    let rows = (pct(env.params, "swatchRows", 2.0) as f32).round().max(1.0) as i32;
    // 面板尺寸：设计稿是「宽 89.2%、高 29.7%（画布 3:4）」的通栏水晶条，换算成
    // 面板自身比例就是 676:310（≈0.444）。两个百分比都参与换算，所以「面板高度」
    // 滑杆不再是摆设；默认值算出来仍是 0.444，竖幅外观不变。
    //
    // 高度只在极端的宽画布上按画布高度封顶，且封顶后**宽度不动**：旧版把面板
    // 收成 `panel_h / 0.444`，4:3 横图上面板从 89.2% 缩到 54%，通栏色卡变成
    // 居中一小块（3:4 竖图不受影响，所以看着像「只有横图坏了」）。宽度保住之后
    // 由下面的网格等比缩放兜底，六个色值始终在面板里。
    let panel_w_pct = (pct(env.params, "panelWidthPct", 89.2) as f32).clamp(10.0, 100.0);
    let panel_h_pct = (pct(env.params, "panelHeightPct", 29.7) as f32).clamp(4.0, 90.0);
    // 设计画布是 3:4：把画布占比换算成面板自身的高宽比。
    let panel_ratio = (panel_h_pct / panel_w_pct) * (4.0 / 3.0);
    let panel_w = w * panel_w_pct / 100.0;
    let natural_h = panel_w * panel_ratio;
    let max_h = h * ((pct(env.params, "panelMaxHeightPct", 60.0) as f32) / 100.0);
    let panel_h = natural_h.min(max_h);
    let panel_x = (w - panel_w) / 2.0;
    let panel_y = h - (s * ((pct(env.params, "panelBottomPct", 6.5) as f32) / 100.0)) - panel_h;
    let radius =
        (panel_h * ((pct(env.params, "panelRadiusPct", 16.0) as f32) / 100.0)).min(w * 0.09);
    draw_photo_cover(
        &mut env.ctx,
        env.photo,
        &env.crop,
        0.0,
        0.0,
        w,
        h,
        0.0,
        0.0,
        s,
    );
    let tint = (pct(env.params, "tintAlpha", 0.24) as f32).clamp(0.0, 0.6);
    let blur_px = (s * 0.032 * ((pct(env.params, "blur", 24.0) as f32) / 24.0)).max(1.0);
    let tint_str = format!("rgba(0,0,0,{tint})");
    draw_glass_panel(
        env,
        panel_x,
        panel_y,
        panel_w,
        panel_h,
        radius,
        &tint_str,
        &tint_str,
        0.10,
        blur_px,
        (s * 0.001).max(1.0),
    );
    let colors = extract_palette(env, (cols * rows) as usize);
    let mut d = panel_w * ((pct(env.params, "swatchPct", 12.3) as f32) / 100.0);
    // 字号与标签间距按面板宽度换算：横构图时面板变矮，标签不会跟着被压小。
    let mut label_size = panel_w * 0.0329 * ((pct(env.params, "labelPct", 2.2) as f32) / 2.2);
    let mut label_gap = panel_w * 0.0221;
    let mut label_h = label_size * 1.15;
    let mut pitch_x = panel_w * ((pct(env.params, "gridPitchXPct", 31.1) as f32) / 100.0);
    pitch_x = pitch_x.max(d * 1.6);
    // 行距不能小于「圆 + 标签 + 呼吸」，否则横幅照片的扁面板上两行会叠在一起。
    let min_pitch_y = d + label_gap + label_h + panel_h * 0.03;
    let mut pitch_y = (panel_h * ((pct(env.params, "gridPitchYPct", 45.1) as f32) / 100.0))
        .max(min_pitch_y);
    // 面板矮到装不下两行时整组等比缩小，宁可色卡小一点，也不让标签跑出玻璃面板。
    let mut block_h =
        (rows.saturating_sub(1) as f32) * pitch_y + d + label_gap + label_h;
    let avail_h = panel_h * 0.88;
    if block_h > avail_h {
        let k = (avail_h / block_h).max(0.4);
        d *= k;
        label_size *= k;
        label_gap *= k;
        label_h *= k;
        pitch_x *= k;
        pitch_y *= k;
        block_h = (rows.saturating_sub(1) as f32) * pitch_y + d + label_gap + label_h;
    }
    // 网格按面板居中：设计稿的 18.9% / 20.9% 起点正是居中后的结果，
    // 所以竖幅照片外观不变，其他比例也不会偏到一侧。
    let block_w = (cols.saturating_sub(1) as f32) * pitch_x + d;
    let first_x = panel_x + (panel_w - block_w) / 2.0 + d / 2.0;
    let first_y = panel_y + (panel_h - block_h).max(0.0) / 2.0 + d / 2.0;
    let label_top = d / 2.0 + label_gap;
    for r in 0..rows {
        for c in 0..cols {
            let idx = (r * cols + c) as usize;
            let hex = colors.get(idx).cloned().unwrap_or_else(|| "#000000".into());
            let cx = first_x + c as f32 * pitch_x;
            let cy = first_y + r as f32 * pitch_y;
            env.ctx.fill_circle(cx, cy, d / 2.0, &hex);
            env.ctx.record_shape(
                "swatch",
                Rect::from_xywh(cx - d / 2.0, cy - d / 2.0, d, d),
                None,
                true,
            );
            let slot = format!("glassPalette.r{:02}.c{:02}.label", r + 1, c + 1);
            draw_text_exact_slot(
                env,
                &hex.to_uppercase(),
                cx,
                cy + label_top,
                label_size,
                "rgba(255,255,255,0.96)",
                "Inter",
                600,
                "center",
                "top",
                0.0,
                false,
                None,
                &slot,
            );
        }
    }
    Ok(Rect::from_xywh(0.0, 0.0, w, h))
}
