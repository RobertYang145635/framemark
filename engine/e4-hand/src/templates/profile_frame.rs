//! `const profileFrame: TemplateDef` (templates.ts:2366) — 头像芯片/昵称与照片卡片，
//! 三种排布 `header` / `chip` / `footer`。Cards: person-1..5.

use skia_safe::Rect;

use crate::draw::{
    draw_brand_logo, draw_fitted, draw_photo, draw_tech_text, measure_text, resolve_text_slot,
    tech_exif_line, theme_of, Env, FittedOpts, LayoutEnv,
};
use crate::hand::{model_without_make, pct, str_param};

fn mode_of(params: &crate::hand::Params) -> String {
    str_param(params, "profileMode", "header")
}

fn profile_name(env: &Env) -> String {
    let raw = str_param(env.params, "profileName", "");
    let picked = if !raw.trim().is_empty() {
        raw
    } else if !env.text.title.trim().is_empty() {
        env.text.title.clone()
    } else if !env.text.subtitle.trim().is_empty() {
        env.text.subtitle.clone()
    } else {
        "FrameMark".into()
    };
    let t = picked.trim();
    let t = if t.is_empty() { "FrameMark" } else { t };
    // The chip label is a declared row: an override must also drive the
    // measured chip width and the avatar initial, not just the painted text.
    resolve_text_slot(env, "profileFrame.name", t).text
}

fn avatar_radius(env: &Env, fallback_pct: f64) -> f32 {
    let s = env.pw.min(env.ph);
    (s * (pct(env.params, "avatarPct", fallback_pct) as f32)) / 200.0
}

fn avatar_name_size(env: &Env, fallback_pct: f64) -> f32 {
    let s = env.pw.min(env.ph);
    (s * (pct(env.params, "nameSizePct", fallback_pct) as f32)) / 100.0
}

fn chip_width(env: &mut Env, r: f32, name_size: f32) -> f32 {
    let name = profile_name(env);
    let name_w = measure_text(env, &name, "Inter", 500, name_size, 0.0);
    r * 2.0 + r * 0.9 + name_w
}

fn footer_brand_cy(brand_center: f32, s: f32, photo_bottom: f32, footer_h: f32) -> f32 {
    if brand_center > 0.0 {
        photo_bottom + (s * brand_center) / 100.0
    } else {
        photo_bottom + footer_h / 2.0
    }
}

fn draw_brand(
    env: &mut Env,
    cx: f32,
    cy: f32,
    color: &str,
    brand_text: &str,
    brand_w: f32,
    brand_h: f32,
) {
    if env.text.show_logo && env.images.logo.is_some() {
        draw_brand_logo(
            env,
            cx - brand_w / 2.0,
            cy - brand_h / 2.0,
            brand_w,
            brand_h,
            color,
        );
    } else {
        let opts = FittedOpts::new("OplusSans")
            .weight(600)
            .align("center")
            .baseline("middle")
            .tracking(0.18)
            .min(brand_h * 0.6)
            .shadow(color == "#ffffff")
            .slot("profileFrame.brand");
        draw_fitted(
            env,
            brand_text,
            cx,
            cy,
            brand_w * 1.1,
            brand_h * 1.35,
            color,
            &opts,
        );
    }
}

fn draw_avatar_chip(env: &mut Env, left: f32, cy: f32, r: f32, color: &str, name_size: f32) {
    let theme = theme_of(env.themes, env.params);
    let name = profile_name(env);
    let cx = left + r;
    let light = color == "#ffffff";
    // The chip is the backdrop; the initial/name text painted on top are their
    // own marks (draw-order attribution in the comparator).
    env.ctx.record_shape(
        "chip",
        Rect::from_xywh(cx - r, cy - r, r * 2.0, r * 2.0),
        None,
        true,
    );
    env.ctx.save();
    if let Some(avatar) = env.images.avatar.clone() {
        env.ctx.clip_circle(cx, cy, r);
        let iw = avatar.width() as f32;
        let ih = avatar.height() as f32;
        let scale = ((r * 2.0) / iw).max((r * 2.0) / ih);
        let (dw, dh) = (iw * scale, ih * scale);
        env.ctx.draw_image(
            &avatar,
            Some(Rect::from_xywh(0.0, 0.0, iw, ih)),
            Rect::from_xywh(cx - dw / 2.0, cy - dh / 2.0, dw, dh),
            crate::draw::image_sampling(iw, dw, ih, dh),
            "avatar",
        );
        env.ctx.restore_clip();
    } else {
        env.ctx.clip_circle(cx, cy, r);
        if light {
            env.ctx.fill_linear_gradient(
                cx - r,
                cy - r,
                cx + r,
                cy + r,
                &[(0.0, "rgba(58,58,64,0.92)"), (1.0, "rgba(12,12,14,0.94)")],
                Rect::from_xywh(cx - r, cy - r, r * 2.0, r * 2.0),
            );
        } else {
            env.ctx.set_fill(&theme.fg);
            env.ctx.fill_rect(cx - r, cy - r, r * 2.0, r * 2.0);
        }
        let initial: String = name.chars().take(1).collect();
        let initial = resolve_text_slot(env, "profileFrame.initial", &initial).text;
        let icol = if light { "#ffffff" } else { theme.bg.as_str() };
        crate::draw::draw_text_exact_slot(
            env,
            &initial,
            cx,
            cy + r * 0.02,
            r * 0.96,
            icol,
            "Inter",
            600,
            "center",
            "middle",
            0.0,
            false,
            None,
            "profileFrame.initial",
        );
        env.ctx.restore_clip();
    }
    env.ctx.restore();
    let ring = if light {
        "rgba(255,255,255,0.72)"
    } else {
        "rgba(255,255,255,0.9)"
    };
    env.ctx.stroke_circle(cx, cy, r, ring, (r * 0.09).max(1.0));
    let opts = FittedOpts::new("Inter")
        .weight(500)
        .baseline("middle")
        .min(name_size * 0.8)
        .shadow(light)
        .slot("profileFrame.name");
    draw_fitted(
        env,
        &name,
        left + r * 2.9,
        cy,
        name_size * 12.0,
        name_size,
        color,
        &opts,
    );
}

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let s = env.pw.min(env.ph);
    let mode = mode_of(env.params);
    let m = (s * (pct(env.params, "marginPct", 4.2) as f32)) / 100.0;
    if mode == "header" {
        let header_h = (s * (pct(env.params, "headerPct", 11.7) as f32)) / 100.0;
        let footer_h = (s * (pct(env.params, "footerPct", 11.3) as f32)) / 100.0;
        return Ok((env.pw + 2.0 * m, header_h + env.ph + footer_h));
    }
    if mode == "chip" {
        let inside = str_param(env.params, "brandPlacement", "inside") != "footer";
        let footer_h = if inside {
            0.0
        } else {
            (s * (pct(env.params, "footerPct", 8.4) as f32)) / 100.0
        };
        let mb_default = pct(env.params, "marginPct", 4.2);
        let mb = (s * (pct(env.params, "marginBottomPct", mb_default) as f32)) / 100.0;
        return Ok((env.pw + 2.0 * m, env.ph + m + mb + footer_h));
    }
    let footer_h = (s * (pct(env.params, "footerPct", 9.8) as f32)) / 100.0;
    Ok((env.pw + 2.0 * m, m + env.ph + footer_h))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h, pw, ph) = (env.w, env.h, env.pw, env.ph);
    let theme = theme_of(env.themes, env.params);
    let s = pw.min(ph);
    let mode = mode_of(env.params);
    let m = (s * (pct(env.params, "marginPct", 4.2) as f32)) / 100.0;
    let radius = (s * (pct(env.params, "radiusPct", 1.4) as f32)) / 100.0;
    let shadow = pct(env.params, "shadow", 0.8) as f32;
    env.ctx.set_fill(&theme.bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);

    let brand_text = str_param(env.params, "overlayBrand", "HASSELBLAD");
    let brand_w = (s * (pct(env.params, "brandWidthPct", 33.7) as f32)) / 100.0;
    let brand_h = brand_w / 14.63;
    let exif_size = (s * (pct(env.params, "exifSizePct", 1.55) as f32)) / 100.0;
    let exif_w = (s * (pct(env.params, "exifWidthPct", 40.0) as f32)) / 100.0;
    let exif_scale_x = pct(env.params, "exifScaleX", 1.0) as f32;
    let exif_offset = (s * (pct(env.params, "exifOffsetPct", 3.9) as f32)) / 100.0;
    let brand_center = pct(env.params, "brandCenterPct", 0.0) as f32;

    let r = avatar_radius(env, 4.3);
    let name_size = avatar_name_size(env, 2.6);
    let chip_w = chip_width(env, r, name_size);

    if mode == "header" {
        let header_h = (s * (pct(env.params, "headerPct", 11.7) as f32)) / 100.0;
        let footer_h = (s * (pct(env.params, "footerPct", 11.3) as f32)) / 100.0;
        draw_photo(
            &mut env.ctx,
            env.photo,
            &env.crop,
            m,
            header_h,
            pw,
            ph,
            radius,
            shadow,
            &theme,
            s,
        );
        draw_avatar_chip(
            env,
            w / 2.0 - chip_w / 2.0,
            header_h * 0.5,
            r,
            &theme.fg,
            name_size,
        );
        if env.text.show_exif {
            let line = tech_exif_line(env);
            draw_tech_text(
                env,
                &line,
                w / 2.0,
                header_h + ph - exif_offset,
                "#ffffff",
                exif_size,
                exif_w,
                "center",
                1.0,
                exif_scale_x,
                "profileFrame.exif",
            );
        }
        let cy = footer_brand_cy(brand_center, s, header_h + ph, footer_h);
        draw_brand(env, w / 2.0, cy, &theme.fg, &brand_text, brand_w, brand_h);
        return Ok(Rect::from_xywh(m, header_h, pw, ph));
    }
    if mode == "chip" {
        let inside = str_param(env.params, "brandPlacement", "inside") != "footer";
        let footer_h = if inside {
            0.0
        } else {
            (s * (pct(env.params, "footerPct", 8.4) as f32)) / 100.0
        };
        draw_photo(
            &mut env.ctx,
            env.photo,
            &env.crop,
            m,
            m,
            pw,
            ph,
            radius,
            shadow,
            &theme,
            s,
        );
        let chip_top = (s * (pct(env.params, "chipTopPct", 4.5) as f32)) / 100.0;
        draw_avatar_chip(
            env,
            w / 2.0 - chip_w / 2.0,
            m + chip_top,
            r,
            "#ffffff",
            name_size,
        );
        if inside {
            let brand_offset = (s * (pct(env.params, "brandOffsetPct", 8.2) as f32)) / 100.0;
            draw_brand(
                env,
                w / 2.0,
                m + ph - brand_offset,
                "#ffffff",
                &brand_text,
                brand_w,
                brand_h,
            );
            if env.text.show_exif {
                let line = tech_exif_line(env);
                draw_tech_text(
                    env,
                    &line,
                    w / 2.0,
                    m + ph - exif_offset,
                    "#ffffff",
                    exif_size,
                    exif_w,
                    "center",
                    1.0,
                    exif_scale_x,
                    "profileFrame.exif",
                );
            }
        } else {
            if env.text.show_exif {
                let line = tech_exif_line(env);
                draw_tech_text(
                    env,
                    &line,
                    w / 2.0,
                    m + ph - exif_offset,
                    "#ffffff",
                    exif_size,
                    exif_w,
                    "center",
                    1.0,
                    exif_scale_x,
                    "profileFrame.exif",
                );
            }
            let cy = footer_brand_cy(brand_center, s, m + ph, footer_h);
            draw_brand(env, w / 2.0, cy, &theme.fg, &brand_text, brand_w, brand_h);
        }
        return Ok(Rect::from_xywh(m, m, pw, ph));
    }

    let footer_h = (s * (pct(env.params, "footerPct", 9.8) as f32)) / 100.0;
    draw_photo(
        &mut env.ctx,
        env.photo,
        &env.crop,
        m,
        m,
        pw,
        ph,
        radius,
        shadow,
        &theme,
        s,
    );
    let cy = m + ph + footer_h / 2.0;
    draw_avatar_chip(env, m + s * 0.027, cy, r, &theme.fg, name_size);
    if env.text.show_exif {
        let right = m + pw - s * 0.004;
        let line = tech_exif_line(env);
        draw_tech_text(
            env,
            &line,
            right,
            cy,
            &theme.fg,
            exif_size,
            exif_w,
            "right",
            1.0,
            exif_scale_x,
            "profileFrame.exif",
        );
        let mut cursor = right - exif_w - s * 0.026;
        env.ctx.stroke_line(
            cursor,
            cy - s * 0.014,
            cursor,
            cy + s * 0.014,
            &theme.line,
            (s * 0.0015).max(1.0),
            None,
        );
        cursor -= s * 0.03;
        let logo_w = (s * (pct(env.params, "footerLogoPct", 13.7) as f32)) / 100.0;
        let logo_h = logo_w / 5.92;
        if env.text.show_logo && env.images.logo.is_some() {
            draw_brand_logo(
                env,
                cursor - logo_w,
                cy - logo_h / 2.0,
                logo_w,
                logo_h,
                &theme.fg,
            );
            cursor -= logo_w + s * 0.03;
        }
        let model_raw = model_without_make(&env.exif.make, &env.exif.model);
        let model_raw = if model_raw.is_empty() {
            env.exif.camera_line("")
        } else {
            model_raw
        };
        let model = resolve_text_slot(env, "profileFrame.model", &model_raw).text;
        let model_size = (s * (pct(env.params, "modelSizePct", 2.6) as f32)) / 100.0;
        let opts = FittedOpts::new("Inter")
            .weight(500)
            .align("right")
            .baseline("middle")
            .min(model_size * 0.7)
            .slot("profileFrame.model");
        draw_fitted(
            env,
            &model,
            cursor,
            cy,
            w * 0.3,
            model_size,
            &theme.fg,
            &opts,
        );
    }
    Ok(Rect::from_xywh(m, m, pw, ph))
}
