//! `const matteFooter: TemplateDef` (templates.ts:2277) — 细白边 + 照片下方留白，
//! 三种页脚内容：图标 + 字标 / 单图标 / 单字标；参数行居中。
//! Cards: card-3 (仅参数) / card-4 (图标+Leica 字标).

use skia_safe::Rect;

use crate::draw::{
    classic_exif_line, draw_brand_logo, draw_fitted, draw_photo, draw_photo_cover, draw_tech_text,
    measure_text, photo_window_of, resolve_text_slot, theme_of, Env, FittedOpts, LayoutEnv,
};
use crate::hand::{pct, str_param};

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let (pw, ph, _cover) = photo_window_of(env.params, env.pw, env.ph);
    let s = pw.min(ph);
    let m = (s * (pct(env.params, "marginPct", 1.15) as f32)) / 100.0;
    let footer_h = (s * (pct(env.params, "footerPct", 9.5) as f32)) / 100.0;
    Ok((pw + 2.0 * m, ph + m + footer_h))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let (pw, ph, cover) = photo_window_of(env.params, env.pw, env.ph);
    let theme = theme_of(env.themes, env.params);
    let s = pw.min(ph);
    let m = (w - pw) / 2.0;
    let footer_h = h - ph - m;
    let cy =
        m + ph + footer_h / 2.0 + (s * (pct(env.params, "footerOffsetPct", 0.0) as f32)) / 100.0;
    env.ctx.set_fill(&theme.bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    if cover {
        draw_photo_cover(
            &mut env.ctx,
            env.photo,
            &env.crop,
            m,
            m,
            pw,
            ph,
            0.0,
            0.0,
            s,
        );
    } else {
        draw_photo(
            &mut env.ctx,
            env.photo,
            &env.crop,
            m,
            m,
            pw,
            ph,
            0.0,
            0.0,
            &theme,
            s,
        );
    }
    let exif_line = classic_exif_line(env);
    let exif_resolved = resolve_text_slot(env, "matteFooter.exif", &exif_line);
    let show_exif = env.text.show_exif && !exif_resolved.text.is_empty();
    let brand_raw = str_param(env.params, "overlayBrand", "").trim().to_string();
    // Resolve the declared row before the branch conditions so a slotText
    // override can fill an otherwise empty wordmark.
    let brand_text = resolve_text_slot(env, "matteFooter.brand", &brand_raw).text;
    if env.text.show_logo && env.images.logo.is_some() && !brand_text.is_empty() {
        let logo_h = (s * (pct(env.params, "logoPct", 5.0) as f32)) / 100.0;
        let text_size = (s * (pct(env.params, "brandTextSizePct", 2.6) as f32)) / 100.0;
        let text_w = measure_text(env, &brand_text, "OplusSans", 600, text_size, 0.0);
        let gap = text_size * 1.3;
        let total = logo_h + gap + text_w;
        let x0 = w / 2.0 - total / 2.0;
        draw_brand_logo(env, x0, cy - logo_h / 2.0, logo_h, logo_h, &theme.fg);
        let opts = FittedOpts::new("OplusSans")
            .weight(600)
            .baseline("middle")
            .tracking(0.03)
            .min(text_size * 0.6)
            .slot("matteFooter.brand");
        draw_fitted(
            env,
            &brand_text,
            x0 + logo_h + gap,
            cy,
            text_w + 2.0,
            text_size,
            &theme.fg,
            &opts,
        );
    } else if env.text.show_logo && brand_text.is_empty() {
        let logo_h = footer_h * if show_exif { 0.45 } else { 0.55 };
        draw_brand_logo(
            env,
            w / 2.0 - logo_h / 2.0,
            cy - logo_h / 2.0,
            logo_h,
            logo_h,
            &theme.fg,
        );
    } else if !brand_text.is_empty() {
        let opts = FittedOpts::new("OplusSans")
            .weight(600)
            .align("center")
            .baseline("middle")
            .tracking(0.02)
            .min(s * 0.015)
            .slot("matteFooter.brand");
        draw_fitted(
            env,
            &brand_text,
            w / 2.0,
            cy,
            w * 0.6,
            (s * (pct(env.params, "brandTextSizePct", 2.6) as f32)) / 100.0,
            &theme.fg,
            &opts,
        );
    }
    if env.text.show_exif {
        let size = (s * (pct(env.params, "exifSizePct", 1.9) as f32)) / 100.0;
        let target = (s * (pct(env.params, "exifWidthPct", 28.3) as f32)) / 100.0;
        draw_tech_text(
            env,
            &exif_line,
            w / 2.0,
            cy,
            &theme.fg,
            size,
            target,
            "center",
            1.0,
            1.0,
            "matteFooter.exif",
        );
    }
    Ok(Rect::from_xywh(m, m, pw, ph))
}
