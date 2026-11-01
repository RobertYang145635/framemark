//! `const brandFooter: TemplateDef` (templates.ts:1120) — the vendor-style footer
//! with three modes (classic / simple / overlay), a JSON geometry override in
//! `params.frame`, a slot whitelist, per-row content/size overrides and the
//! brand-driven font family. Cards: the 7 `wm-*` cards.

use serde_json::Value;
use skia_safe::Rect;

use crate::draw::{
    draw_brand_logo, draw_fitted, draw_photo, exif_text, footer_title, logo_scale,
    simple_footer_metrics, theme_of, Env, FittedOpts, LayoutEnv,
};
use crate::hand::{format_date_only, format_date_time, pct};

fn frame_map(env: &Env) -> std::collections::BTreeMap<String, f32> {
    let mut out = std::collections::BTreeMap::new();
    if let Some(Value::String(raw)) = env.params.get("frame") {
        if raw.trim_start().starts_with('{') {
            if let Ok(Value::Object(o)) = serde_json::from_str::<Value>(raw) {
                for (k, v) in o {
                    if let Some(n) = v.as_f64() {
                        out.insert(k, n as f32);
                    }
                }
            }
        }
    }
    out
}

fn frame_of(f: &std::collections::BTreeMap<String, f32>, key: &str, dflt: f32) -> f32 {
    f.get(key).copied().unwrap_or(dflt)
}

fn slots_of(env: &Env) -> std::collections::BTreeSet<String> {
    match env.params.get("slots") {
        Some(Value::String(raw)) if !raw.trim().is_empty() => {
            raw.split(',').map(|x| x.trim().to_string()).filter(|x| !x.is_empty()).collect()
        }
        _ => Default::default(),
    }
}

fn slot_on(slots: &std::collections::BTreeSet<String>, key: &str) -> bool {
    slots.is_empty() || slots.contains(key)
}

fn slot_of(env: &Env, id: &str) -> String {
    let scope = env
        .design
        .variant_id
        .clone()
        .unwrap_or_else(|| env.design.template_id.clone());
    let key = format!("{scope}#{id}");
    env.text
        .slot_values
        .as_ref()
        .and_then(|m| m.get(&key))
        .map(|s| s.trim().to_string())
        .unwrap_or_default()
}

fn size_of(env: &Env, id: &str) -> f32 {
    let scope = env
        .design
        .variant_id
        .clone()
        .unwrap_or_else(|| env.design.template_id.clone());
    let key = format!("{scope}#{id}");
    let v = env
        .text
        .slot_sizes
        .as_ref()
        .and_then(|m| m.get(&key))
        .map(|v| crate::jsval::pct(Some(v), 1.0) as f32)
        .unwrap_or(1.0);
    v.clamp(0.6, 1.6)
}

fn mode_of(env: &Env) -> String {
    env.params
        .get("footerMode")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| "classic".into())
}

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let mode = env
        .params
        .get("footerMode")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| "classic".into());
    let footer_auto = !matches!(env.params.get("footerAuto"), Some(Value::Bool(false)));
    if mode == "simple" && footer_auto {
        let m = simple_footer_metrics(env.pw, env.ph, env.params);
        return Ok((env.pw, env.ph + m.footer_h));
    }
    if mode == "overlay" {
        return Ok((env.pw, env.ph));
    }
    let s = env.pw.min(env.ph);
    let footer = ((env.pw * (pct(env.params, "footerPct", 7.5) as f32)) / 100.0).max(s * 0.085);
    // Footer frame overrides live inside the params.frame JSON string.
    let mut f = std::collections::BTreeMap::new();
    if let Some(Value::String(raw)) = env.params.get("frame") {
        if raw.trim_start().starts_with('{') {
            if let Ok(Value::Object(o)) = serde_json::from_str::<Value>(raw) {
                for (k, v) in o {
                    if let Some(n) = v.as_f64() {
                        f.insert(k, n as f32);
                    }
                }
            }
        }
    }
    let border = (env.pw * frame_of(&f, "borderPct", 0.0)) / 100.0;
    let top_inset = (env.pw * frame_of(&f, "borderTopPct", frame_of(&f, "borderPct", 0.0))) / 100.0;
    Ok((env.pw + 2.0 * border, env.ph + top_inset + footer))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let mode = mode_of(env);
    let footer_auto = !matches!(env.params.get("footerAuto"), Some(Value::Bool(false)));
    if mode == "simple" {
        return render_simple(env);
    }
    if mode == "overlay" {
        return render_overlay(env);
    }
    let _ = footer_auto;
    render_classic(env)
}

// ── simple (drawSimpleBrandFooter) ─────────────────────────────────────────

fn render_simple(env: &mut Env) -> Result<Rect, String> {
    let (w, ph, pw) = (env.w, env.ph, env.pw);
    let theme = theme_of(env.themes, env.params);
    let m = simple_footer_metrics(pw, ph, env.params);
    env.ctx.set_fill(&theme.bg);
    env.ctx.fill_rect(0.0, 0.0, w, env.h);
    let s = pw.min(ph);
    draw_photo(&mut env.ctx, env.photo, &env.crop, 0.0, 0.0, pw, ph, 0.0, 0.0, &theme, s);
    let cy = ph + m.footer_h / 2.0;
    let logo_pos = env
        .params
        .get("logoPos")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| "center".into());
    let make = env.exif.make.trim().to_string();
    let model = env.exif.model_without_make();
    let left_text = if !model.is_empty() {
        model.clone()
    } else if !make.is_empty() {
        make.clone()
    } else {
        footer_title(env)
    };
    let right_lines: Vec<String> = [
        if env.text.show_exif { env.exif.line(" ") } else { String::new() },
        if env.text.show_date { format_date_only(&env.exif.date_time) } else { String::new() },
    ]
    .into_iter()
    .filter(|s| !s.is_empty())
    .collect();
    let has_logo = env.text.show_logo && env.images.logo.is_some();
    let mut logo_box: Option<(f32, f32, f32, f32)> = None;
    if has_logo {
        let mut lx = w / 2.0 - m.logo_w / 2.0;
        if logo_pos == "left" {
            lx = m.pad;
        } else if logo_pos == "right" {
            lx = w - m.pad - m.logo_w;
        }
        logo_box = Some((lx, cy - m.logo_h / 2.0, m.logo_w, m.logo_h));
    }
    let center_left = logo_box.map(|b| b.0 - m.gap).unwrap_or(w / 2.0);
    let center_right = logo_box.map(|b| b.0 + b.2 + m.gap).unwrap_or(w / 2.0);
    if logo_pos == "left" && logo_box.is_some() {
        let b = logo_box.unwrap();
        let tx = b.0 + b.2 + m.gap;
        draw_fitted(
            env,
            &left_text,
            tx,
            cy,
            (w / 2.0 - tx).max(40.0),
            m.font_left,
            &theme.fg,
            &FittedOpts::new("OplusSans").weight(500).baseline("middle").min(m.font_left * 0.5),
        );
    } else {
        draw_fitted(
            env,
            &left_text,
            m.pad,
            cy,
            (center_left - m.pad).max(40.0),
            m.font_left,
            &theme.fg,
            &FittedOpts::new("OplusSans").weight(500).baseline("middle").min(m.font_left * 0.5),
        );
    }
    if let Some(b) = logo_box {
        draw_brand_logo(env, b.0, b.1, b.2, b.3, &theme.fg);
    } else {
        let text = if make.is_empty() { left_text.clone() } else { make.clone() };
        draw_fitted(
            env,
            &text.to_uppercase(),
            w / 2.0,
            cy,
            w - 2.0 * m.pad,
            m.font_center,
            &theme.fg,
            &FittedOpts::new("OplusSans")
                .weight(700)
                .align("center")
                .baseline("middle")
                .min(m.font_center * 0.5),
        );
    }
    if !right_lines.is_empty() {
        let right_x = if logo_pos == "right" {
            logo_box.map(|b| b.0 - m.gap).unwrap_or(w - m.pad)
        } else {
            w - m.pad
        };
        let max_w = (right_x - center_right).max(60.0);
        let line_gap = m.font_right * 0.95;
        let block_h = m.font_right + (right_lines.len() as f32 - 1.0) * line_gap;
        let mut y = cy - block_h / 2.0 + m.font_right * 0.52;
        for (i, text) in right_lines.iter().enumerate() {
            let size = if i == 0 { m.font_right } else { m.font_right * 0.78 };
            draw_fitted(
                env,
                text,
                right_x,
                y,
                max_w,
                size,
                if i == 0 { &theme.fg } else { &theme.sub },
                &FittedOpts::new("OplusSans")
                    .weight(if i == 0 { 700 } else { 400 })
                    .align("right")
                    .baseline("middle")
                    .min(size * 0.5),
            );
            y += line_gap;
        }
    }
    Ok(Rect::from_xywh(0.0, 0.0, pw, ph))
}

// ── overlay (drawBrandOverlay) ─────────────────────────────────────────────

fn overlay_lens_line(exif: &crate::hand::Exif, optics: &[String]) -> String {
    let raw = exif.lens.trim();
    let name = if !raw.is_empty() && raw.chars().count() <= 24 {
        raw.split('|').next().unwrap_or("").trim().to_string()
    } else {
        String::new()
    };
    let designation = if !name.is_empty() {
        name
    } else if !exif.make.trim().is_empty() {
        exif.make.trim().to_string()
    } else {
        exif.model_without_make()
    };
    let rest: Vec<String> = optics
        .iter()
        .filter(|t| !t.is_empty() && !designation.to_lowercase().contains(&t.to_lowercase()))
        .cloned()
        .collect();
    [designation, rest.join(" ")]
        .into_iter()
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join(" + ")
        .to_uppercase()
}

fn render_overlay(env: &mut Env) -> Result<Rect, String> {
    let (w, h, pw, ph) = (env.w, env.h, env.pw, env.ph);
    let theme = theme_of(env.themes, env.params);
    let s = pw.min(ph);
    let f = frame_map(env);
    let slots = slots_of(env);
    let on = |k: &str| slot_on(&slots, k);
    env.ctx.set_fill(&theme.bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    draw_photo(&mut env.ctx, env.photo, &env.crop, 0.0, 0.0, pw, ph, 0.0, 0.0, &theme, s);
    let left_x = w * frame_of(&f, "ovLeftX", 0.0913);
    let text_x = w * frame_of(&f, "ovTextX", 0.1293);
    let right_x = w * frame_of(&f, "ovRightCenterX", 0.749);
    let logo_w = w * frame_of(&f, "ovLogoW", 0.0323);
    let logo_h = h * frame_of(&f, "ovLogoH", 0.0519);
    let logo_cy = h * frame_of(&f, "ovLogoCenterY", 0.7839);
    let make = env.exif.make.trim().to_string();
    let model = env.exif.model_without_make();
    let title = footer_title(env);
    let brand = if !make.is_empty() { make.clone() } else { model.clone() }.trim().to_string();
    let exif_line = if env.text.show_exif { env.exif.line("  ") } else { String::new() };
    let date_line = if env.text.show_date { format_date_only(&env.exif.date_time) } else { String::new() };
    let optics = vec![
        env.exif.focal.map(|v| format!("{}mm", crate::jsval::js_round(v))).unwrap_or_default(),
        env.exif.aperture.map(crate::hand::format_aperture).unwrap_or_default(),
    ]
    .into_iter()
    .filter(|s| !s.is_empty())
    .collect::<Vec<_>>();
    let lens_mode = env
        .params
        .get("lensRowMode")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| "model".into());
    let spec = if lens_mode == "reference" {
        overlay_lens_line(env.exif, &optics)
    } else {
        let mut parts = vec![model.clone()];
        parts.extend(optics.iter().cloned());
        parts.into_iter().filter(|s| !s.is_empty()).collect::<Vec<_>>().join(" ").to_uppercase()
    };
    if on("logo") && env.text.show_logo {
        draw_brand_logo(env, left_x, logo_cy - logo_h / 2.0, logo_w, logo_h, &theme.fg);
    }
    if on("title") && !title.is_empty() {
        draw_fitted(
            env,
            &title,
            text_x,
            h * frame_of(&f, "ovTitleY", 0.7637),
            w * 0.32,
            s * 0.019,
            &theme.fg,
            &FittedOpts::new("Inter").weight(500).baseline("middle").min(s * 0.011).shadow(true),
        );
    }
    if on("brand") && !brand.is_empty() {
        draw_fitted(
            env,
            &brand.to_uppercase(),
            text_x,
            h * frame_of(&f, "ovBrandY", 0.7968),
            w * 0.3,
            s * 0.019,
            &theme.fg,
            &FittedOpts::new("Inter")
                .weight(600)
                .tracking(0.04)
                .baseline("middle")
                .min(s * 0.011)
                .shadow(true),
        );
    }
    if on("exif") && !exif_line.is_empty() {
        draw_fitted(
            env,
            &exif_line,
            right_x,
            h * frame_of(&f, "ovExifY", 0.7507),
            w * 0.3,
            s * 0.02,
            &theme.fg,
            &FittedOpts::new("JetBrains Mono")
                .baseline("middle")
                .align("center")
                .min(s * 0.012)
                .shadow(true),
        );
    }
    if on("lens") && !spec.is_empty() {
        draw_fitted(
            env,
            &spec,
            right_x,
            h * frame_of(&f, "ovLensY", 0.7925),
            w * 0.26,
            s * 0.0175,
            &theme.fg,
            &FittedOpts::new("JetBrains Mono")
                .baseline("middle")
                .align("center")
                .min(s * 0.01)
                .shadow(true),
        );
    }
    if on("date") && !date_line.is_empty() {
        draw_fitted(
            env,
            &date_line,
            right_x,
            h * frame_of(&f, "ovDateY", 0.8285),
            w * 0.2,
            s * 0.018,
            &theme.fg,
            &FittedOpts::new("JetBrains Mono")
                .baseline("middle")
                .align("center")
                .min(s * 0.01)
                .shadow(true),
        );
    }
    Ok(Rect::from_xywh(0.0, 0.0, pw, ph))
}

// ── classic ────────────────────────────────────────────────────────────────

fn render_classic(env: &mut Env) -> Result<Rect, String> {
    let (w, h, pw, ph) = (env.w, env.h, env.pw, env.ph);
    let theme = theme_of(env.themes, env.params);
    let s = pw.min(ph);
    let f = frame_map(env);
    let slots = slots_of(env);
    let on = |k: &str| slot_on(&slots, k);
    let border = (pw * frame_of(&f, "borderPct", 0.0)) / 100.0;
    let top_inset = (pw * frame_of(&f, "borderTopPct", frame_of(&f, "borderPct", 0.0))) / 100.0;
    let footer_top = top_inset + ph;
    let footer = h - footer_top;
    env.ctx.set_fill(&theme.bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    draw_photo(&mut env.ctx, env.photo, &env.crop, border, top_inset, pw, ph, 0.0, 0.0, &theme, s);
    if frame_of(&f, "bandRule", 99.0) != 0.0 {
        crate::draw::line(
            &mut env.ctx,
            border,
            footer_top,
            border + pw,
            footer_top,
            &theme.line,
            (s * 0.0009).max(1.0),
            None,
        );
    }
    let left_x = w * frame_of(&f, "leftX", 0.04);
    let left_w = w * frame_of(&f, "leftW", 0.28);
    let brand_y = frame_of(&f, "brandY", 0.42);
    let model_y = frame_of(&f, "modelY", 0.70);
    let logo_pos = env
        .params
        .get("logoPos")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| "center".into());
    let make = if env.text.show_make {
        let slot = slot_of(env, "brand");
        if slot.is_empty() { env.exif.make.clone() } else { slot }
    } else {
        String::new()
    };
    let model = if env.text.show_model {
        let slot = slot_of(env, "model");
        if slot.is_empty() { env.exif.model_without_make() } else { slot }
    } else {
        String::new()
    };
    let title = footer_title(env);
    let make_text = if make.is_empty() { title.clone() } else { make.clone() }.to_uppercase();
    let model_text = if model.is_empty() {
        if env.text.show_lens { env.exif.lens.trim().to_string() } else { String::new() }
    } else {
        model.clone()
    };
    let brand_key = format!("{} {}", make, env.exif.model).to_lowercase();
    let auto_font = if brand_key.contains("xiaomi") || brand_key.contains("redmi") || brand_key.contains("poco") {
        "misans"
    } else if brand_key.contains("vivo") || brand_key.contains("iqoo") {
        "vivo"
    } else {
        "oppo"
    };
    let requested = env
        .params
        .get("footerFont")
        .map(|v| crate::jsval::js_string(Some(v)))
        .unwrap_or_else(|| "auto".into());
    let font_style = if requested == "auto" || requested == "oppo" { auto_font } else { requested.as_str() };
    let serif = font_style == "serif";
    let vivo = font_style == "vivo";
    let misans = font_style == "misans";
    let modern = misans || vivo;
    let (brand_font, body_font) = match font_style {
        "serif" => ("StockButler", "StockButler"),
        "vivo" => ("StockVivo", "StockVivo"),
        "misans" => ("StockMiSans", "StockMiSans"),
        _ => ("StockSysSans", "StockSysSans"),
    };
    let brand_weight: u16 = if serif || !modern { 700 } else { 600 };
    let model_weight: u16 = if serif { 500 } else if modern { 400 } else { 700 };
    let body_weight: u16 = if serif { 500 } else if modern { 400 } else { 600 };
    let brand_size = s * if serif { 0.03 } else if modern { 0.028 } else { 0.031 };
    let model_size = s * if serif { 0.027 } else if modern { 0.0255 } else { 0.029 };
    let brand_tracking = if serif { 0.01 } else if modern { 0.02 } else { 0.03 };
    let model_tracking = if serif { 0.005 } else if modern { 0.01 } else { 0.05 };
    let show_brand = on("brand");
    let show_model = on("model") && !model_text.is_empty();
    let show_left_text = !(logo_pos == "left" && env.text.show_logo) && (show_brand || show_model);
    if show_left_text {
        if show_brand {
            draw_fitted(
                env,
                &make_text,
                left_x,
                footer_top + footer * brand_y,
                left_w,
                brand_size * size_of(env, "brand"),
                &theme.fg,
                &FittedOpts::new(brand_font)
                    .weight(brand_weight)
                    .tracking(brand_tracking as f64)
                    .min(s * 0.013),
            );
        }
        if show_model {
            draw_fitted(
                env,
                &model_text,
                left_x,
                footer_top + footer * model_y,
                left_w,
                model_size * size_of(env, "model"),
                &theme.sub,
                &FittedOpts::new(body_font)
                    .weight(model_weight)
                    .tracking(model_tracking as f64)
                    .min(s * 0.01),
            );
        }
    }
    let right_x = w * frame_of(&f, "rightX", if logo_pos == "right" { 0.44 } else { 0.72 });
    let right_w = w * frame_of(&f, "rightW", if logo_pos == "right" { 0.34 } else { 0.25 });
    let exif_y = frame_of(&f, "exifY", 0.42);
    let date_y = frame_of(&f, "dateY", 0.70);
    let ex = if env.text.show_exif {
        let slot = slot_of(env, "exif");
        if slot.is_empty() { exif_text(env) } else { slot }
    } else {
        String::new()
    };
    let dt = if env.text.show_date {
        let slot = slot_of(env, "date");
        if slot.is_empty() { format_date_time(&env.exif.date_time) } else { slot }
    } else {
        String::new()
    };
    let custom = if on("custom") { env.text.custom_line.trim().to_string() } else { String::new() };
    let has_right = (on("exif") && !ex.is_empty()) || (on("date") && !dt.is_empty()) || !custom.is_empty();
    let rule_x = w * frame_of(&f, "ruleX", if logo_pos == "right" { 0.42 } else { 0.7 });
    if on("logo") && env.text.show_logo {
        let grow = logo_scale(&LayoutEnv {
            pw,
            ph,
            crop: env.crop,
            exif: env.exif,
            params: env.params,
            text: env.text,
        });
        let base_w = w * frame_of(&f, "logoW", 0.17);
        let base_h = footer * frame_of(&f, "logoH", 0.36);
        let mut lw = base_w * grow;
        let mut lh = (base_h * grow).min(footer * 0.9);
        let center_x = w * (pct(env.params, "logoCenterX", 0.53) as f32);
        let mut lx = center_x - lw / 2.0;
        if logo_pos == "left" {
            lx = left_x;
        } else if logo_pos == "right" {
            lx = w * 0.96 - lw;
        }
        if f.contains_key("logoCx") {
            lx = w * frame_of(&f, "logoCx", 0.0) - lw / 2.0;
        }
        if grow > 1.0001 {
            let gap = s * 0.008;
            let left_bound = left_x + if show_left_text { left_w } else { 0.0 } + gap;
            let right_bound = (if has_right {
                if frame_of(&f, "rule", 99.0) != 0.0 { rule_x } else { right_x }
            } else {
                w * 0.96
            }) - gap;
            let max_w = (right_bound - left_bound).max(0.0);
            lw = lw.min(max_w);
            if logo_pos == "center" || f.contains_key("logoCx") {
                let half = lw / 2.0;
                let cx = (lx + half).max(left_bound + half).min(right_bound - half);
                lx = cx - half;
            } else if logo_pos == "left" {
                lx = left_bound;
            } else {
                lx = right_bound - lw;
            }
        }
        draw_brand_logo(env, lx, footer_top + footer / 2.0 - lh / 2.0, lw, lh, &theme.fg);
    }
    if frame_of(&f, "rule", 99.0) != 0.0 && has_right {
        crate::draw::line(
            &mut env.ctx,
            rule_x,
            footer_top + footer * 0.22,
            rule_x,
            footer_top + footer * 0.78,
            &theme.line,
            (s * 0.0009).max(1.0),
            None,
        );
    }
    if on("exif") && !ex.is_empty() {
        draw_fitted(
            env,
            &ex,
            right_x,
            footer_top + footer * exif_y,
            right_w,
            s * 0.027 * size_of(env, "exif"),
            &theme.fg,
            &FittedOpts::new(body_font).weight(body_weight).tracking(0.01).min(s * 0.012),
        );
    }
    if on("date") && !dt.is_empty() {
        draw_fitted(
            env,
            &dt,
            right_x,
            footer_top + footer * date_y,
            right_w,
            s * 0.021 * size_of(env, "date"),
            &theme.sub,
            &FittedOpts::new(body_font).weight(400).tracking(0.01).min(s * 0.01),
        );
    }
    if !custom.is_empty() {
        draw_fitted(
            env,
            &custom,
            right_x,
            footer_top + footer * 0.88,
            right_w,
            s * 0.016,
            &theme.accent,
            &FittedOpts::new(body_font).weight(body_weight).min(s * 0.009),
        );
    }
    Ok(Rect::from_xywh(border, top_inset, pw, ph))
}
