//! `const gradFooter: TemplateDef` (templates.ts:1323) — full-bleed photo with a
//! white footer: brand + EXIF left, graduation sticker right. Batch-3 card:
//! `grad-1` (sticker graduation1, loaded by main as images.sticker).

use skia_safe::Rect;

use crate::draw::{draw_fitted, draw_photo, theme_of, Env, FittedOpts, LayoutEnv};
use crate::hand::pct;

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let footer = (env.pw * (pct(env.params, "footerPct", 12.0) as f32)) / 100.0;
    Ok((env.pw, env.ph + footer))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h, pw, ph) = (env.w, env.h, env.pw, env.ph);
    let theme = theme_of(env.themes, env.params);
    let s = pw.min(ph);
    let footer = h - ph;
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
        s,
    );
    let sticker = env.images.sticker.clone();
    if let Some(sticker) = &sticker {
        let sh = (footer * (pct(env.params, "stickerScalePct", 100.0) as f32)) / 100.0;
        let aspect = sticker.width() as f32 / sticker.height().max(1) as f32;
        let sw = sh * aspect;
        let sx = w - sw - (w * (pct(env.params, "stickerRightPct", 0.0) as f32)) / 100.0;
        env.ctx.draw_image(
            sticker,
            Some(Rect::from_xywh(
                0.0,
                0.0,
                sticker.width() as f32,
                sticker.height() as f32,
            )),
            Rect::from_xywh(sx, ph, sw, sh),
            crate::draw::image_sampling(sticker.width() as f32, sw, sticker.height() as f32, sh),
            "sticker",
        );
    }
    let left_x = (w * (pct(env.params, "brandXPct", 3.9) as f32)) / 100.0;
    let left_w = w * if sticker.is_some() { 0.45 } else { 0.9 };
    let make = if env.text.show_make {
        env.exif.make.clone()
    } else {
        String::new()
    };
    let model = if env.text.show_model {
        env.exif.model_without_make()
    } else {
        String::new()
    };
    let brand = [make.trim(), model.trim()]
        .into_iter()
        .filter(|x| !x.is_empty())
        .collect::<Vec<_>>()
        .join(" ")
        .to_uppercase();
    draw_fitted(
        env,
        &brand,
        left_x,
        ph + footer * (pct(env.params, "brandY", 0.46) as f32),
        left_w,
        (s * (pct(env.params, "brandSizePct", 3.7) as f32)) / 100.0,
        &theme.fg,
        &FittedOpts::new("Inter")
            .weight(700)
            .tracking(0.02)
            .min(s * 0.018)
            .slot("gradFooter.brand"),
    );
    let ex = if env.text.show_exif {
        env.exif.line("  ")
    } else {
        String::new()
    };
    draw_fitted(
        env,
        &ex,
        left_x,
        ph + footer * (pct(env.params, "exifY", 0.772) as f32),
        left_w,
        (s * (pct(env.params, "exifSizePct", 2.85) as f32)) / 100.0,
        &theme.sub,
        &FittedOpts::new("Inter")
            .min(s * 0.014)
            .slot("gradFooter.exif"),
    );
    Ok(Rect::from_xywh(0.0, 0.0, pw, ph))
}
