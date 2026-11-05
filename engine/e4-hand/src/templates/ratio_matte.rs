//! `const ratioMatte: TemplateDef` (templates.ts:4374) — 模糊底衬边、内嵌照片、
//! 径向暗角、品牌与参数居中。Cards: ratio-1 / ratio-2.

use skia_safe::Rect;

use crate::draw::{
    date_text, draw_blurred_backdrop, draw_fitted, draw_photo_cover, exif_text, theme_of, Env,
    FittedOpts, LayoutEnv,
};
use crate::hand::{model_without_make, pct};

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let aspect = (pct(env.params, "canvasAspect", 1.5015) as f32).max(0.5);
    Ok((env.pw, (env.pw / aspect).round().max(2.0)))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let theme = theme_of(env.themes, env.params);
    let tint = (pct(env.params, "tintAlpha", 0.9) as f32).clamp(0.0, 1.0);
    draw_blurred_backdrop(env, w, h, &theme, tint);
    let vig = (pct(env.params, "vignette", 0.0) as f32).clamp(0.0, 1.0);
    if vig > 0.0 {
        let r1 = w.max(h) * 0.68;
        env.ctx.fill_radial_gradient(
            w / 2.0,
            h * 0.42,
            w.min(h) * 0.12,
            w / 2.0,
            h / 2.0,
            r1,
            &[(0.0, "rgba(0,0,0,0)"), (1.0, &format!("rgba(0,0,0,{vig})"))],
            Rect::from_xywh(0.0, 0.0, w, h),
        );
    }
    let photo_y = h * ((pct(env.params, "topMarginPct", 5.72) as f32) / 100.0);
    let aspect = (pct(env.params, "photoAspect", 1.485) as f32).max(0.2);
    let mut photo_w =
        (w - 2.0 * w * ((pct(env.params, "sideMarginPct", 9.03) as f32) / 100.0)).max(1.0);
    let mut photo_h = photo_w / aspect;
    let max_bottom = h * 0.96;
    if photo_y + photo_h > max_bottom {
        photo_h = (max_bottom - photo_y).max(1.0);
        photo_w = photo_h * aspect;
    }
    let photo_x = (w - photo_w) / 2.0;
    let radius = photo_w.min(photo_h) * ((pct(env.params, "radiusPct", 0.0) as f32) / 100.0);
    draw_photo_cover(
        &mut env.ctx,
        env.photo,
        &env.crop,
        photo_x,
        photo_y,
        photo_w,
        photo_h,
        radius,
        0.0,
        w.min(h),
    );
    let photo_bottom = photo_y + photo_h;
    let mut cam_parts: Vec<String> = Vec::new();
    if env.text.show_make && !env.exif.make.trim().is_empty() {
        cam_parts.push(env.exif.make.trim().to_string());
    }
    if env.text.show_model {
        let m = model_without_make(&env.exif.make, &env.exif.model);
        if !m.trim().is_empty() {
            cam_parts.push(m.trim().to_string());
        }
    }
    let cam = cam_parts.join("  ");
    let ex = exif_text(env);
    let meta = if !ex.is_empty() { ex } else { date_text(env) };
    let max_w = photo_w * 0.92;
    let brand_size = h * ((pct(env.params, "brandSizePct", 3.43) as f32) / 100.0);
    let brand_text = cam.to_uppercase();
    let brand_opts = FittedOpts::new("Inter")
        .weight(600)
        .align("center")
        .baseline("middle")
        .tracking(pct(env.params, "brandTracking", 0.14))
        .min(h * 0.012)
        .slot("ratioMatte.camera");
    draw_fitted(
        env,
        &brand_text,
        w / 2.0,
        photo_bottom + h * ((pct(env.params, "brandGapPct", 4.62) as f32) / 100.0),
        max_w,
        brand_size,
        "#ffffff",
        &brand_opts,
    );
    let alpha = (pct(env.params, "metaAlphaPct", 80.0) as f32).clamp(0.0, 100.0) / 100.0;
    let meta_size = h * ((pct(env.params, "metaSizePct", 2.4) as f32) / 100.0);
    let meta_opts = FittedOpts::new("JetBrains Mono")
        .weight(400)
        .align("center")
        .baseline("middle")
        .tracking(0.02)
        .min(h * 0.009)
        .slot("ratioMatte.metadata");
    draw_fitted(
        env,
        &meta,
        w / 2.0,
        photo_bottom + h * ((pct(env.params, "metaGapPct", 8.5) as f32) / 100.0),
        max_w,
        meta_size,
        &format!("rgba(255,255,255,{alpha})"),
        &meta_opts,
    );
    Ok(Rect::from_xywh(photo_x, photo_y, photo_w, photo_h))
}
