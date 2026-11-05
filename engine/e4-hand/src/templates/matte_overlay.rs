//! `const matteOverlay: TemplateDef` (templates.ts:2116) — photo on a matte with
//! an overlaid brand mark and one EXIF row. Batch-2 card: `white-2`
//! (mode `brand-exif`, white theme, exifScaleX 1.47).

use skia_safe::Rect;

use crate::draw::{
    classic_exif_line, draw_brand_logo, draw_fitted, draw_photo, draw_tech_text, resolve_text_slot,
    theme_of, Env, FittedOpts, LayoutEnv,
};
use crate::hand::pct;

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let s = env.pw.min(env.ph);
    let m = s * (pct(env.params, "marginPct", 4.5) as f32) / 100.0;
    let mt = s
        * (pct(
            env.params,
            "marginTopPct",
            pct(env.params, "marginPct", 4.5),
        ) as f32)
        / 100.0;
    let mb = s
        * (pct(
            env.params,
            "marginBottomPct",
            pct(env.params, "marginPct", 4.5),
        ) as f32)
        / 100.0;
    Ok((env.pw + 2.0 * m, env.ph + mt + mb))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h, pw, ph) = (env.w, env.h, env.pw, env.ph);
    let theme = theme_of(env.themes, env.params);
    let s = pw.min(ph);
    let m = (w - pw) / 2.0;
    let mt = s
        * (pct(
            env.params,
            "marginTopPct",
            pct(env.params, "marginPct", 4.5),
        ) as f32)
        / 100.0;
    let radius = s * (pct(env.params, "radiusPct", 1.8) as f32) / 100.0;
    env.ctx.set_fill(&theme.bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    draw_photo(
        &mut env.ctx,
        env.photo,
        &env.crop,
        m,
        mt,
        pw,
        ph,
        radius,
        0.0,
        &theme,
        s,
    );
    let mode = env
        .params
        .get("overlayMode")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| "brand".into());
    if mode == "none" {
        return Ok(Rect::from_xywh(m, mt, pw, ph));
    }
    let brand_w = s * (pct(env.params, "brandWidthPct", 26.8) as f32) / 100.0;
    let brand_h = brand_w / 14.63;
    let brand_cy = mt + ph - s * (pct(env.params, "brandOffsetPct", 4.5) as f32) / 100.0;
    let exif_size = s * (pct(env.params, "exifSizePct", 1.55) as f32) / 100.0;
    let exif_w = s * (pct(env.params, "exifWidthPct", 42.0) as f32) / 100.0;
    let exif_scale_x = pct(env.params, "exifScaleX", 1.0) as f32;
    let exif_cy = mt + ph - s * (pct(env.params, "exifOffsetPct", 5.0) as f32) / 100.0;
    if mode == "brand" || mode == "brand-exif" {
        if env.text.show_logo && env.images.logo.is_some() {
            draw_brand_logo(
                env,
                w / 2.0 - brand_w / 2.0,
                brand_cy - brand_h / 2.0,
                brand_w,
                brand_h,
                "#ffffff",
            );
        } else {
            let raw = env
                .params
                .get("overlayBrand")
                .map(|v| crate::jsval::js_string(Some(v)))
                .unwrap_or_default();
            let text = resolve_text_slot(env, "matteOverlay.brand", raw.trim()).text;
            if !text.is_empty() {
                draw_fitted(
                    env,
                    &text,
                    w / 2.0,
                    brand_cy,
                    w * 0.8,
                    brand_h,
                    "#ffffff",
                    &FittedOpts::new("OplusSans")
                        .weight(600)
                        .align("center")
                        .baseline("middle")
                        .tracking(0.18)
                        .min(brand_h * 0.6)
                        .shadow(true)
                        .slot("matteOverlay.brand"),
                );
            }
        }
    }
    if mode == "profile-chip" {
        env.ctx
            .record_gap("matteOverlay profile-chip not ported (no card uses it in the 69)".into());
        return Ok(Rect::from_xywh(m, mt, pw, ph));
    }
    if (mode == "exif" || mode == "brand-exif") && env.text.show_exif {
        let align = env
            .params
            .get("exifAlign")
            .map(|v| crate::jsval::js_string(Some(v)))
            .unwrap_or_else(|| "center".into());
        let x = match align.as_str() {
            "left" => m + s * 0.04,
            "right" => m + pw - s * 0.04,
            _ => w / 2.0,
        };
        let line = classic_exif_line(env);
        draw_tech_text(
            env,
            &line,
            x,
            exif_cy,
            "#ffffff",
            exif_size,
            exif_w,
            &align,
            1.0,
            exif_scale_x,
            "matteOverlay.exif",
        );
    }
    Ok(Rect::from_xywh(m, mt, pw, ph))
}
