//! `const editorial: TemplateDef` (templates.ts:2996) — magazine page: measured
//! masthead/headline flow, framed photo, three-column footer. Card:
//! `fm-editorial`. The frozen #2 fix replaced fixed offsets with em-box flow;
//! both `measureFitted` calls and the 0.85 em clearance are replicated.

use skia_safe::Rect;

use crate::draw::{
    date_text, draw_fitted, draw_photo, ink_tops, measure_fitted, resolve_text_slot, theme_of,
    title_of, Env, FittedOpts, LayoutEnv,
};
use crate::hand::pct;

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let s = env.pw.min(env.ph);
    let m = s * (pct(env.params, "marginPct", 8.5) as f32) / 100.0;
    let header = (s * 0.18_f32).max(s * 0.15);
    let footer = ((s * (pct(env.params, "footerPct", 14.0) as f32)) / 100.0).max(s * 0.13);
    Ok((env.pw + 2.0 * m, env.ph + header + footer))
}

fn issue_line(date_time: &str) -> String {
    let base = if date_time.is_empty() {
        "01"
    } else {
        date_time
    };
    let mut acc = 3.0_f64;
    for c in base.chars() {
        acc = acc * 31.0 + (c as u32 as f64);
    }
    let n = (acc.abs() % 99.0).round();
    format!("NO. {:02}", n as i64)
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h, pw, ph) = (env.w, env.h, env.pw, env.ph);
    let theme = theme_of(env.themes, env.params);
    let s = pw.min(ph);
    let m = s * (pct(env.params, "marginPct", 8.5) as f32) / 100.0;
    let footer_h = ((s * (pct(env.params, "footerPct", 14.0) as f32)) / 100.0).max(s * 0.13);
    let top = h - ph - footer_h;
    env.ctx.set_fill(&theme.bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    let issue = issue_line(&env.exif.date_time);
    let head_y = top * 0.30;
    let head_run = measure_fitted(
        env,
        "FRAMEMARK / PHOTO ANNUAL",
        w * 0.6,
        s * 0.013,
        s * 0.008,
        "JetBrains Mono",
        500,
        0.1,
    );
    draw_fitted(
        env,
        "FRAMEMARK / PHOTO ANNUAL",
        m,
        head_y,
        w * 0.6,
        s * 0.013,
        &theme.sub,
        &FittedOpts::new("JetBrains Mono")
            .weight(500)
            .tracking(0.1)
            .min(s * 0.008)
            .slot("editorial.masthead"),
    );
    draw_fitted(
        env,
        &issue,
        w - m,
        head_y,
        w * 0.22,
        s * 0.013,
        &theme.sub,
        &FittedOpts::new("JetBrains Mono")
            .align("right")
            .tracking(0.06)
            .min(s * 0.008)
            .slot("editorial.issue-number"),
    );
    let title = title_of(env);
    let title_size = s * 0.072;
    let title_render = resolve_text_slot(env, "editorial.title", &title);
    let title_run = measure_fitted(
        env,
        &title_render.text,
        w - 2.0 * m,
        title_size,
        s * 0.028,
        "YouShe",
        400,
        0.0,
    );
    let title_y = (top * 0.62)
        .max(head_y + head_run.0 * 0.21 + head_run.0 * 0.2 + ink_tops(title_run.0, "alphabetic"));
    draw_fitted(
        env,
        &title,
        m,
        title_y,
        w - 2.0 * m,
        title_size,
        &theme.fg,
        &FittedOpts::new("YouShe")
            .min(s * 0.028)
            .slot("editorial.title"),
    );
    let sub = env.text.subtitle.trim();
    let sub_resolved = resolve_text_slot(env, "editorial.subtitle", sub);
    if !sub_resolved.text.is_empty() {
        draw_fitted(
            env,
            sub,
            w - m,
            top * 0.54,
            w * 0.45,
            s * 0.016,
            &theme.sub,
            &FittedOpts::new("Inter")
                .align("right")
                .min(s * 0.009)
                .slot("editorial.subtitle"),
        );
    } else {
        draw_fitted(
            env,
            sub,
            w - m,
            top * 0.54,
            w * 0.45,
            s * 0.016,
            &theme.sub,
            &FittedOpts::new("Inter")
                .align("right")
                .min(s * 0.009)
                .slot("editorial.subtitle"),
        );
    }
    let rule_w = (s * 0.0009).max(1.0);
    crate::draw::line(
        &mut env.ctx,
        m,
        top * 0.86,
        w - m,
        top * 0.86,
        &theme.line,
        rule_w,
        None,
    );
    env.ctx.record_shape(
        "rule",
        Rect::from_xywh(m, top * 0.86 - rule_w / 2.0, w - 2.0 * m, rule_w),
        None,
        true,
    );
    draw_photo(
        &mut env.ctx,
        env.photo,
        &env.crop,
        m,
        top,
        pw,
        ph,
        0.0,
        0.0,
        &theme,
        s,
    );
    let frame_w = (s * 0.0007).max(1.0);
    env.ctx.stroke_rect(m, top, pw, ph, &theme.line, frame_w);
    env.ctx.record_shape(
        "frame-top",
        Rect::from_xywh(m, top, pw, frame_w),
        None,
        true,
    );
    env.ctx.record_shape(
        "frame-bottom",
        Rect::from_xywh(m, top + ph - frame_w, pw, frame_w),
        None,
        true,
    );
    env.ctx.record_shape(
        "frame-left",
        Rect::from_xywh(m, top, frame_w, ph),
        None,
        true,
    );
    env.ctx.record_shape(
        "frame-right",
        Rect::from_xywh(m + pw - frame_w, top, frame_w, ph),
        None,
        true,
    );
    let foot_y = top + ph + s * 0.035;
    crate::draw::line(
        &mut env.ctx,
        m,
        foot_y,
        w - m,
        foot_y,
        &theme.line,
        rule_w,
        None,
    );
    env.ctx.record_shape(
        "rule",
        Rect::from_xywh(m, foot_y - rule_w / 2.0, w - 2.0 * m, rule_w),
        None,
        true,
    );
    let col_w = (w - 2.0 * m) / 3.0;
    let labels = ["CAMERA", "OPTICS", "DATE"];
    let label_slots = [
        "editorial.camera.label",
        "editorial.optics.label",
        "editorial.date.label",
    ];
    let value_slots = [
        "editorial.camera.value",
        "editorial.optics.value",
        "editorial.date.value",
    ];
    let optics = {
        let mut parts: Vec<String> = Vec::new();
        if !env.exif.lens.trim().is_empty() {
            parts.push(env.exif.lens.trim().to_string());
        }
        parts.push(env.exif.line("  "));
        let joined = parts
            .into_iter()
            .filter(|s| !s.is_empty())
            .collect::<Vec<_>>()
            .join("  ");
        if joined.is_empty() {
            "—".to_string()
        } else {
            joined
        }
    };
    let date = date_text(env);
    let values = [
        env.exif.camera_line("FRAMEMARK"),
        optics,
        if date.is_empty() {
            "—".to_string()
        } else {
            date
        },
    ];
    let label_texts = [
        resolve_text_slot(env, label_slots[0], labels[0]).text,
        resolve_text_slot(env, label_slots[1], labels[1]).text,
        resolve_text_slot(env, label_slots[2], labels[2]).text,
    ];
    let value_texts = [
        resolve_text_slot(env, value_slots[0], &values[0]).text,
        resolve_text_slot(env, value_slots[1], &values[1]).text,
        resolve_text_slot(env, value_slots[2], &values[2]).text,
    ];
    let label_sizes: Vec<(f32, f32)> = labels
        .iter()
        .enumerate()
        .map(|(i, _)| {
            measure_fitted(
                env,
                &label_texts[i],
                col_w - s * 0.02,
                s * 0.011,
                s * 0.007,
                "JetBrains Mono",
                500,
                0.12,
            )
        })
        .collect();
    let value_sizes: Vec<(f32, f32)> = value_texts
        .iter()
        .enumerate()
        .map(|(i, t)| {
            let family = if i == 2 { "JetBrains Mono" } else { "Inter" };
            measure_fitted(
                env,
                t,
                col_w - s * 0.02,
                s * 0.016,
                s * 0.009,
                family,
                500,
                0.0,
            )
        })
        .collect();
    let mut col_x: Vec<f32> = Vec::with_capacity(3);
    for i in 0..labels.len() {
        let nominal = m + i as f32 * col_w;
        if i == 0 {
            col_x.push(nominal);
            continue;
        }
        let prev = label_sizes[i - 1].1.max(value_sizes[i - 1].1);
        let gap = value_sizes[i - 1].0.max(label_sizes[i - 1].0) * 0.85;
        col_x.push(nominal.max(col_x[i - 1] + prev + gap));
    }
    let last = col_x.len() - 1;
    let right_edge = w - m - label_sizes[last].1.max(value_sizes[last].1);
    if right_edge > col_x[last - 1] {
        col_x[last] = col_x[last].min(right_edge);
    }
    for i in 0..labels.len() {
        draw_fitted(
            env,
            labels[i],
            col_x[i],
            foot_y + s * 0.03,
            col_w - s * 0.02,
            s * 0.011,
            &theme.sub,
            &FittedOpts::new("JetBrains Mono")
                .weight(500)
                .tracking(0.12)
                .min(s * 0.007)
                .slot(label_slots[i]),
        );
        let family = if i == 2 { "JetBrains Mono" } else { "Inter" };
        draw_fitted(
            env,
            &values[i],
            col_x[i],
            foot_y + s * 0.06,
            col_w - s * 0.02,
            s * 0.016,
            &theme.fg,
            &FittedOpts::new(family)
                .weight(500)
                .min(s * 0.009)
                .slot(value_slots[i]),
        );
    }
    let signature = env.text.signature.trim();
    if !resolve_text_slot(env, "editorial.signature", signature)
        .text
        .is_empty()
    {
        draw_fitted(
            env,
            env.text.signature.trim(),
            w - m,
            h - s * 0.02,
            w * 0.35,
            s * 0.028,
            &theme.fg,
            &FittedOpts::new("Caveat")
                .align("right")
                .min(s * 0.015)
                .slot("editorial.signature"),
        );
    } else {
        draw_fitted(
            env,
            signature,
            w - m,
            h - s * 0.02,
            w * 0.35,
            s * 0.028,
            &theme.fg,
            &FittedOpts::new("Caveat")
                .align("right")
                .min(s * 0.015)
                .slot("editorial.signature"),
        );
    }
    Ok(Rect::from_xywh(m, top, pw, ph))
}
