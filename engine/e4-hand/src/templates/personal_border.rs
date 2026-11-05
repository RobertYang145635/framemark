//! `const personalBorder: TemplateDef` (templates.ts:1394) — 满幅照片 + 细白边 +
//! 底部签名栏（金色头像圆 + 签名 + 机型 + 品牌 + 参数/日期）。Card: personal-simple.

use skia_safe::Rect;

use crate::draw::{
    draw_brand_logo, draw_fitted, draw_photo_cover, draw_text_exact_slot, theme_of, Env,
    FittedOpts, LayoutEnv,
};
use crate::hand::{format_date_only, model_without_make, pct, str_param};

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let b = (env.pw * (pct(env.params, "borderPct", 2.0) as f32)) / 100.0;
    Ok((env.pw + 2.0 * b, env.ph + 2.0 * b))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h, pw, ph) = (env.w, env.h, env.pw, env.ph);
    let theme = theme_of(env.themes, env.params);
    let b = (pw * (pct(env.params, "borderPct", 2.0) as f32)) / 100.0;
    let footer_h = w * ((pct(env.params, "footerPct", 9.5) as f32) / 100.0);
    let photo_h = (ph * 0.4).max(h - b - footer_h);
    env.ctx.set_fill(&theme.bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    draw_photo_cover(
        &mut env.ctx,
        env.photo,
        &env.crop,
        b,
        b,
        pw,
        photo_h,
        0.0,
        0.0,
        pw.min(ph),
    );
    let footer_top = h - footer_h;
    let cy = footer_top + footer_h / 2.0;
    let signature = {
        let s = str_param(env.params, "signature", "").trim().to_string();
        if !s.is_empty() {
            s
        } else if !env.text.custom_line.trim().is_empty() {
            env.text.custom_line.trim().to_string()
        } else if !env.text.title.trim().is_empty() {
            env.text.title.trim().to_string()
        } else {
            "FrameMark".into()
        }
    };
    let avatar_size = w * 0.036;
    let avatar_x = b * 2.0;
    let acx = avatar_x + avatar_size / 2.0;
    env.ctx.fill_circle(acx, cy, avatar_size / 2.0, "#c8a96b");
    env.ctx.record_shape(
        "avatar",
        Rect::from_xywh(
            acx - avatar_size / 2.0,
            cy - avatar_size / 2.0,
            avatar_size,
            avatar_size,
        ),
        None,
        true,
    );
    let initial: String = signature.chars().take(1).collect::<String>().to_uppercase();
    draw_text_exact_slot(
        env,
        &initial,
        acx,
        cy,
        avatar_size * 0.42,
        "#ffffff",
        "OplusSans",
        700,
        "center",
        "middle",
        0.0,
        false,
        None,
        "personalBorder.avatar.initial",
    );
    let signature_x = avatar_x + avatar_size + w * 0.016;
    let opts = FittedOpts::new("OplusSans")
        .weight(600)
        .baseline("middle")
        .min((w * 0.014).max(34.0))
        .slot("personalBorder.signature");
    draw_fitted(
        env,
        &signature,
        signature_x,
        cy,
        w * 0.245,
        (w * 0.021).max(58.0),
        &theme.fg,
        &opts,
    );
    let divider_x = w * 0.663;
    let logo_w = w * 0.097;
    let logo_h = w * 0.0165;
    let gap = w * 0.03;
    let logo_left = divider_x - gap - logo_w;
    let model_right = logo_left - gap;
    let mut model = model_without_make(&env.exif.make, &env.exif.model);
    if model.is_empty() {
        model = env.exif.make.trim().to_string();
    }
    if model.is_empty() {
        model = env.text.title.trim().to_string();
    }
    let opts = FittedOpts::new("OplusSans")
        .weight(400)
        .align("right")
        .baseline("middle")
        .min(w * 0.013)
        .slot("personalBorder.model");
    draw_fitted(
        env,
        &model,
        model_right,
        cy,
        w * 0.17,
        w * 0.0205,
        &theme.fg,
        &opts,
    );
    if env.text.show_logo {
        draw_brand_logo(env, logo_left, cy - logo_h / 2.0, logo_w, logo_h, &theme.fg);
    }
    let line_w = (w * 0.0006).max(1.0);
    crate::draw::line(
        &mut env.ctx,
        divider_x,
        cy - footer_h * 0.28,
        divider_x,
        cy + footer_h * 0.28,
        &theme.line,
        line_w,
        None,
    );
    env.ctx.record_shape(
        "divider",
        Rect::from_xywh(
            divider_x - line_w / 2.0,
            cy - footer_h * 0.28,
            line_w,
            footer_h * 0.56,
        ),
        None,
        true,
    );
    let right_x = w * 0.966;
    let right = if env.text.show_exif {
        env.exif.line(" ")
    } else {
        String::new()
    };
    let opts = FittedOpts::new("OplusSans")
        .weight(400)
        .align("right")
        .baseline("middle")
        .min(w * 0.013)
        .slot("personalBorder.exif");
    draw_fitted(
        env,
        &right,
        right_x,
        cy,
        w * 0.27,
        w * 0.018,
        &theme.fg,
        &opts,
    );
    let dt = if env.text.show_date {
        format_date_only(&env.exif.date_time)
    } else {
        String::new()
    };
    let opts = FittedOpts::new("OplusSans")
        .weight(400)
        .align("right")
        .baseline("middle")
        .min(w * 0.011)
        .slot("personalBorder.date");
    draw_fitted(
        env,
        &dt,
        right_x,
        cy + footer_h * 0.24,
        w * 0.27,
        w * 0.018,
        &theme.sub,
        &opts,
    );
    Ok(Rect::from_xywh(b, b, pw, photo_h))
}
