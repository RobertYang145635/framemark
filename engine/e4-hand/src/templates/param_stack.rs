//! `const paramStack: TemplateDef` (templates.ts:1881) — full-bleed cover and
//! four skewed camera-parameter rows in one corner (Bebas Neue). Cards:
//! `simple-1..4`; batch 1 renders `simple-2`.

use skia_safe::Rect;

use crate::draw::{
    aspect_canvas, draw_fitted, draw_photo_cover, resolve_text_slot, theme_of, Env, FittedOpts,
    LayoutEnv,
};
use crate::hand::{format_aperture, format_shutter, pct};
use crate::jsval::js_round;

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    Ok(aspect_canvas(
        env.pw,
        env.ph,
        pct(env.params, "canvasAspect", 0.0),
    ))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let theme = theme_of(env.themes, env.params);
    let s = w.min(h);
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
    let corner = env
        .params
        .get("corner")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| "tl".into());
    let right = corner.ends_with('r');
    let bottom = corner.starts_with('b');
    let s = w.min(h);
    let size = s * (pct(env.params, "paramSizePct", 5.4) as f32) / 100.0;
    let gap = size * (pct(env.params, "paramGapPct", 20.0) as f32) / 100.0;
    let pad_x_pct = pct(
        env.params,
        "paramPadXPct",
        pct(env.params, "paramPadPct", 3.0),
    );
    let pad_y_pct = pct(
        env.params,
        "paramPadYPct",
        pct(env.params, "paramPadPct", 3.0),
    );
    let pad_x = w * (pad_x_pct as f32) / 100.0;
    let pad_y = s * (pad_y_pct as f32) / 100.0;
    // Every row is declared up front with a stable logical slot; the source
    // value only decides whether the row is painted when nothing overrides it.
    let declared: [(&str, String); 4] = [
        (
            "paramStack.focal",
            env.exif
                .focal
                .map(|v| format!("{}mm", js_round(v)))
                .unwrap_or_default(),
        ),
        (
            "paramStack.aperture",
            env.exif.aperture.map(format_aperture).unwrap_or_default(),
        ),
        (
            "paramStack.shutter",
            env.exif
                .shutter
                .map(|s| format_shutter(s).trim_end_matches('s').to_string())
                .unwrap_or_default(),
        ),
        (
            "paramStack.iso",
            env.exif
                .iso
                .map(|v| format!("ISO {}", crate::jsval::format_js_number(v)))
                .unwrap_or_default(),
        ),
    ];
    let lines: Vec<(&str, String)> = declared
        .into_iter()
        .map(|(slot, raw)| (slot, resolve_text_slot(env, slot, &raw).text))
        .filter(|(_, text)| !text.is_empty())
        .collect();
    if lines.is_empty() {
        return Ok(Rect::from_xywh(0.0, 0.0, w, h));
    }
    let block_h = lines.len() as f32 * size + (lines.len() as f32 - 1.0) * gap;
    let x = if right { w - pad_x } else { pad_x };
    let mut y = if bottom {
        h - pad_y - block_h + size / 2.0
    } else {
        pad_y + size / 2.0
    };
    for (slot, text) in &lines {
        env.ctx.save();
        env.ctx.translate(x, y);
        env.ctx.transform(1.0, 0.0, -0.2, 1.0, 0.0, 0.0);
        let align = if right { "right" } else { "left" };
        draw_fitted(
            env,
            text,
            0.0,
            0.0,
            w * 0.6,
            size,
            "#ffffff",
            &FittedOpts::new("Bebas Neue")
                .align(align)
                .baseline("middle")
                .tracking(0.02)
                .min(size * 0.6)
                .shadow(true)
                .slot(slot),
        );
        env.ctx.restore();
        y += size + gap;
    }
    let _ = theme;
    Ok(Rect::from_xywh(0.0, 0.0, w, h))
}
