//! `const portraitTicket: TemplateDef` (templates.ts:3804) — 浅色背景中的竖票卡：
//! 照片在上、白色存根在下（缺口/虚线/条码/序列号）。Card: ticket-2.

use skia_safe::Rect;

use crate::draw::{
    draw_barcode, draw_fitted, draw_photo_cover, theme_of, Env, FittedOpts, LayoutEnv,
};
use crate::hand::{format_date_only, str_param};

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let s = env.pw.min(env.ph);
    Ok((s * 1.5, s * 2.0))
}

fn width_or(value: f64, fallback: f64) -> f64 {
    if value.is_finite() && value != 0.0 {
        value
    } else {
        fallback
    }
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let s = env.pw.min(env.ph);
    let bg = str_param(env.params, "ticketBg", "#c7dfe6");
    let card_bg = str_param(env.params, "ticketCard", "#ffffff");
    let ink = str_param(env.params, "ticketInk", "#2d6f7c");
    let sub = "rgba(45,111,124,0.62)";
    let card_w = w * 0.7627;
    let card_h = h * 0.776;
    let card_x = (w - card_w) / 2.0;
    let card_y = (h - card_h) / 2.0;
    let photo_h = card_h * 0.6823;
    let stub_h = card_h - photo_h;
    let seam = card_y + photo_h;
    let radius = s * 0.025;
    env.ctx.set_fill(&bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    env.ctx.fill_round_rect_shadow(
        card_x,
        card_y,
        card_w,
        card_h,
        radius,
        &card_bg,
        "rgba(0,0,0,0.15)",
        s * 0.035,
        s * 0.012,
    );
    env.ctx.record_shape(
        "ticket-card",
        Rect::from_xywh(card_x, card_y, card_w, card_h),
        None,
        true,
    );
    env.ctx
        .clip_round_rect(card_x, card_y, card_w, card_h, radius);
    draw_photo_cover(
        &mut env.ctx,
        env.photo,
        &env.crop,
        card_x,
        card_y,
        card_w,
        photo_h,
        0.0,
        0.0,
        s,
    );
    env.ctx
        .fill_rect_color(card_x, seam, card_w, stub_h, &card_bg);
    env.ctx.restore_clip();

    let notch = s * 0.016;
    for x in [card_x, card_x + card_w] {
        env.ctx.fill_circle(x, seam, notch, &bg);
        env.ctx.record_shape(
            "notch",
            Rect::from_xywh(x - notch, seam - notch, notch * 2.0, notch * 2.0),
            None,
            true,
        );
    }
    let dash = [s * 0.012, s * 0.01];
    env.ctx.stroke_line(
        card_x + notch * 1.4,
        seam,
        card_x + card_w - notch * 1.4,
        seam,
        "rgba(45,111,124,0.35)",
        (s * 0.001).max(1.0),
        Some(&dash),
    );
    env.ctx.record_shape(
        "seam",
        Rect::from_xywh(card_x + notch * 1.4, seam - 1.0, card_w - notch * 2.8, 2.0),
        None,
        true,
    );

    let left = card_x + card_w * 0.0908;
    let title = str_param(env.params, "stubTitle", "QING DAO").to_uppercase();
    let raw_title_size = match env.params.get("stubTitleSize") {
        Some(v) => crate::jsval::pct(Some(v), 0.0) as f64,
        None => 0.0,
    };
    let title_size = card_w * width_or(raw_title_size, 0.0635) as f32;
    let topts = FittedOpts::new("Inter")
        .weight(800)
        .baseline("middle")
        .min(card_w * 0.045)
        .slot("portraitTicket.title");
    draw_fitted(
        env,
        &title,
        left,
        seam + stub_h * 0.2373,
        card_w * 0.58,
        title_size,
        &ink,
        &topts,
    );
    let dt = if env.text.show_date {
        format_date_only(&env.exif.date_time)
    } else {
        String::new()
    };
    let bytes: Vec<char> = dt.chars().collect();
    let stub_date = if bytes.len() == 10
        && bytes.iter().enumerate().all(|(i, c)| {
            if i == 4 || i == 7 {
                *c == '-'
            } else {
                c.is_ascii_digit()
            }
        }) {
        dt.chars().take(7).collect::<String>()
    } else {
        dt.clone()
    };
    let dopts = FittedOpts::new("JetBrains Mono")
        .weight(500)
        .baseline("middle")
        .min(card_w * 0.024)
        .slot("portraitTicket.date");
    draw_fitted(
        env,
        &stub_date,
        left,
        seam + stub_h * 0.4219,
        card_w * 0.30,
        card_w * 0.0374,
        &ink,
        &dopts,
    );
    let seed_src = if !env.exif.date_time.is_empty() {
        env.exif.date_time.clone()
    } else if !env.exif.model.is_empty() {
        env.exif.model.clone()
    } else {
        "TICKET".to_string()
    };
    let mut acc: f64 = 7.0;
    for ch in seed_src.chars() {
        acc = acc * 31.0 + (ch as u32) as f64;
    }
    let code = acc.abs() % 99999.0;
    let code_s = if code.fract() == 0.0 {
        format!("{:.0}", code)
    } else {
        crate::jsval::format_js_number(code)
    };
    let no = format!("NO.{}", format!("{:0>5}", code_s));
    let nopts = FittedOpts::new("JetBrains Mono")
        .baseline("middle")
        .min(card_w * 0.018)
        .slot("portraitTicket.number");
    draw_fitted(
        env,
        &no,
        left,
        seam + stub_h * 0.7941,
        card_w * 0.4,
        card_w * 0.0297,
        sub,
        &nopts,
    );
    let right_x = card_x + card_w * 0.847;
    let label = str_param(env.params, "stubLabel", "GUANGYING 100");
    let lopts = FittedOpts::new("JetBrains Mono")
        .weight(500)
        .align("right")
        .tracking(0.06)
        .baseline("middle")
        .min(card_w * 0.018)
        .slot("portraitTicket.label");
    draw_fitted(
        env,
        &label,
        right_x,
        seam + stub_h * 0.6237,
        card_w * 0.33,
        card_w * 0.0295,
        &ink,
        &lopts,
    );
    let seed_text = if !env.exif.model.is_empty() {
        env.exif.model.clone()
    } else {
        "FRAMEMARK".to_string()
    };
    draw_barcode(
        &mut env.ctx,
        card_x + card_w * 0.6114,
        seam + stub_h * 0.6917,
        card_w * 0.3019,
        stub_h * 0.1461,
        &ink,
        &seed_text,
    );
    Ok(Rect::from_xywh(card_x, card_y, card_w, photo_h))
}
