//! `const sideTicket: TemplateDef` (templates.ts:630) — one landscape ticket
//! with a stub, perforation, barcode and two-line title. Batch-2 card:
//! `ticket-1` (ink theme, green ticket).

use skia_safe::Rect;

use crate::draw::{
    date_text, draw_barcode, draw_fitted, draw_photo_cover, resolve_text_slot, theme_of, Env,
    FittedOpts, LayoutEnv,
};
use crate::hand::{pct, Exif};
use crate::jsval::js_round;

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let s = env.pw.min(env.ph);
    Ok((s * 1.5, s * 2.0))
}

fn seeded_number(text: &str, seed: f64, modulo: f64, pad: usize) -> String {
    let mut acc = seed;
    for c in text.chars() {
        acc = acc * 31.0 + (c as u32 as f64);
    }
    let n = (acc.abs() % modulo).floor() as i64;
    format!("{:0width$}", n, width = pad)
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let s = env.pw.min(env.ph);
    let theme = theme_of(env.themes, env.params);
    let bg = env
        .params
        .get("ticketBg")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| "#244231".into());
    let panel = env
        .params
        .get("ticketPanel")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| "#31533f".into());
    let fg = "#f7f2e7";
    let sub = "rgba(247,242,231,0.72)";
    let card_w = w * 0.878;
    let card_h = card_w * 0.401;
    let card_x = (w - card_w) / 2.0;
    let card_y = (h - card_h) / 2.0;
    let stub_w = card_w * 0.2787;
    let photo_w = card_w * 0.7213;
    let boundary = card_x + photo_w;
    let radius = s * 0.022;
    env.ctx.set_fill(&bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    env.ctx.fill_round_rect_shadow(
        card_x,
        card_y,
        card_w,
        card_h,
        radius,
        &panel,
        "rgba(0,0,0,0.38)",
        s * 0.018,
        s * 0.006,
    );
    env.ctx
        .clip_round_rect(card_x, card_y, card_w, card_h, radius);
    draw_photo_cover(
        &mut env.ctx,
        env.photo,
        &env.crop,
        card_x,
        card_y,
        photo_w,
        card_h,
        0.0,
        0.0,
        s,
    );
    env.ctx
        .fill_rect_color(boundary, card_y, stub_w, card_h, &panel);
    env.ctx.restore_clip();
    // Card drop shadow: canvas shadowBlur S*0.018 with offsetY S*0.006 extends
    // about 1.6x the blur around the card; record that ring as decor.
    let ring = s * 0.018 * 1.6 + s * 0.006;
    for (kind, rect) in [
        (
            "shadow-top",
            skia_safe::Rect::from_xywh(
                card_x - ring,
                card_y - ring + s * 0.006,
                card_w + 2.0 * ring,
                ring,
            ),
        ),
        (
            "shadow-bottom",
            skia_safe::Rect::from_xywh(
                card_x - ring,
                card_y + card_h + s * 0.006,
                card_w + 2.0 * ring,
                ring,
            ),
        ),
        (
            "shadow-left",
            skia_safe::Rect::from_xywh(
                card_x - ring,
                card_y - ring + s * 0.006,
                ring,
                card_h + 2.0 * ring,
            ),
        ),
        (
            "shadow-right",
            skia_safe::Rect::from_xywh(
                card_x + card_w,
                card_y - ring + s * 0.006,
                ring,
                card_h + 2.0 * ring,
            ),
        ),
    ] {
        env.ctx.record_shape(kind, rect, None, true);
    }
    let notch = card_h * 0.024;
    for y in [card_y, card_y + card_h] {
        env.ctx.fill_round_rect(
            boundary - notch,
            y - notch,
            notch * 2.0,
            notch * 2.0,
            notch,
            &bg,
        );
    }
    let dash = [s * 0.012, s * 0.01];
    crate::draw::line(
        &mut env.ctx,
        boundary,
        card_y + notch * 1.3,
        boundary,
        card_y + card_h - notch * 1.3,
        "rgba(247,242,231,0.35)",
        (s * 0.0012).max(1.0),
        Some(&dash),
    );
    let sx = boundary + card_w * 0.0342;
    let sw = card_w * 0.208;
    let stub_title = env
        .params
        .get("stubTitle")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| "HANG ZHOU".into())
        .to_uppercase();
    let lines: Vec<(&str, String)> = {
        let mut words = stub_title.split_whitespace();
        let first = words.next().unwrap_or("");
        let second = words.next().unwrap_or("");
        [
            (
                "sideTicket.title.line1",
                resolve_text_slot(env, "sideTicket.title.line1", first).text,
            ),
            (
                "sideTicket.title.line2",
                resolve_text_slot(env, "sideTicket.title.line2", second).text,
            ),
        ]
        .into_iter()
        .filter(|(_, text)| !text.is_empty())
        .collect()
    };
    let stub_size = card_h * 0.123;
    let stub_pitch = 0.1208_f32 / 0.123;
    let mut stub_y = card_y + card_h * 0.2395;
    for (slot, line_text) in &lines {
        let painted = draw_fitted(
            env,
            line_text,
            sx,
            stub_y,
            sw,
            stub_size,
            fg,
            &FittedOpts::new("Inter")
                .weight(800)
                .baseline("middle")
                .min(card_h * 0.06)
                .slot(slot),
        );
        stub_y += painted * stub_pitch;
    }
    let dt = resolve_text_slot(env, "sideTicket.date", &date_text(env)).text;
    let stub_date = if dt.len() == 10 && dt.as_bytes()[4] == b'-' && dt.as_bytes()[7] == b'-' {
        dt[..7].to_string()
    } else {
        dt.clone()
    };
    if !stub_date.is_empty() {
        draw_fitted(
            env,
            &stub_date,
            sx,
            card_y + card_h * 0.5915,
            sw,
            card_h * 0.0698,
            fg,
            &FittedOpts::new("JetBrains Mono")
                .weight(600)
                .baseline("middle")
                .min(card_h * 0.04)
                .slot("sideTicket.date"),
        );
    }
    let label = resolve_text_slot(
        env,
        "sideTicket.label",
        &env.params
            .get("stubLabel")
            .map(|v| crate::jsval::js_string(Some(v)))
            .unwrap_or_else(|| "GUANGYING 100".into()),
    )
    .text;
    draw_fitted(
        env,
        &label,
        sx,
        card_y + card_h * 0.693,
        sw,
        card_h * 0.0621,
        sub,
        &FittedOpts::new("JetBrains Mono")
            .weight(500)
            .tracking(0.08)
            .baseline("middle")
            .min(card_h * 0.035)
            .slot("sideTicket.label"),
    );
    let seed_text = if env.exif.model.is_empty() {
        "FRAMEMARK".to_string()
    } else {
        env.exif.model.clone()
    };
    draw_barcode(
        &mut env.ctx,
        sx,
        card_y + card_h * 0.817,
        sw * 0.96,
        card_h * 0.106,
        fg,
        &seed_text,
    );
    let _ = (js_round, seeded_number, Exif::default());
    Ok(Rect::from_xywh(card_x, card_y, photo_w, card_h))
}
