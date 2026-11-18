//! `const glass: TemplateDef` (templates.ts:3137) — 满幅照片上的磨砂玻璃信息卡，
//! 三种排布（card / pill〔含 stack〕/ palette）+ 高光与细边。Card: glass-liquid（pill stack）。

use skia_safe::Rect;

use crate::draw::{
    date_text, draw_brand_logo, draw_fitted, draw_glass_panel, draw_photo, draw_swatches,
    exif_text, extract_palette, line, theme_of, title_of, Env, FittedOpts, LayoutEnv,
};
use crate::hand::{format_date_time, pct};

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    Ok((env.pw, env.ph))
}

fn dot_date(env: &Env) -> String {
    if !env.text.show_date {
        return String::new();
    }
    let dt = format_date_time(&env.exif.date_time);
    let mut out = String::with_capacity(dt.len());
    for (i, c) in dt.chars().enumerate() {
        if (i == 4 || i == 7) && c == '-' {
            out.push('.');
        } else {
            out.push(c);
        }
    }
    out
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let s = w.min(h);
    let theme = theme_of(env.themes, env.params);
    draw_photo(
        &mut env.ctx,
        env.photo,
        &env.crop,
        0.0,
        0.0,
        w,
        h,
        0.0,
        0.0,
        &theme,
        s,
    );
    let portrait = h > w;
    let align = crate::hand::str_param(env.params, "glassAlign", "left");
    let layout_mode = crate::hand::str_param(env.params, "glassLayout", "card");
    let mut card_w = w * if portrait { 0.78 } else { 0.62 };
    let mut card_h = (s * 0.20).max(h * 0.17);
    let mut card_x = if align == "right" {
        w - card_w - w * 0.055
    } else if align == "center" {
        (w - card_w) / 2.0
    } else {
        w * 0.055
    };
    let mut card_y = h - card_h - h * 0.065;
    let mut r = (card_h * 0.18).min(s * 0.028);
    let stack = crate::hand::str_param(env.params, "pillOrder", "") == "stack";
    if layout_mode == "pill" {
        card_w = w * if stack { 0.94 } else { 0.88 };
        // 参考实现的胶囊约占画面高度的 17%：够两行字呼吸，又不至于压住照片。
        card_h = (s * if stack { 0.175 } else { 0.135 })
            .max(h * if stack { 0.175 } else { 0.125 });
        card_x = (w - card_w) / 2.0;
        card_y = h - card_h - h * if stack { 0.028 } else { 0.06 };
        r = card_h / 2.0;
    } else if layout_mode == "palette" {
        card_w = w * if portrait { 0.82 } else { 0.66 };
        card_h = (s * 0.27).max(h * 0.25);
        card_x = if align == "right" {
            w - card_w - w * 0.055
        } else if align == "center" {
            (w - card_w) / 2.0
        } else {
            w * 0.055
        };
        card_y = h - card_h - h * 0.055;
        r = (card_h * 0.13).min(s * 0.03);
    }
    let blur_px = (s * 0.038 * ((pct(env.params, "blur", 24.0) as f32) / 24.0)).max(1.0);
    draw_glass_panel(
        env,
        card_x,
        card_y,
        card_w,
        card_h,
        r,
        "rgba(0,0,0,0.30)",
        "rgba(0,0,0,0.46)",
        0.07,
        blur_px,
        (s * 0.0009).max(1.0),
    );
    let inner_x = card_x + card_w * if layout_mode == "pill" { 0.04 } else { 0.055 };
    if layout_mode == "pill" {
        if stack {
            // 液态玻璃胶囊的字排：左侧「品牌 + 日期」两行，中间 Logo，右侧「参数 +
            // 坐标」两行（对齐参考实现：大写品牌、无等宽字、无宽字距，右列两行同一
            // 左边界）。旧版把参数塞成一行等宽大字，读起来像日志而不是玻璃上的标注。
            let pad_x = card_x + card_w * 0.045;
            let brand_y = card_y + card_h * 0.40;
            let date_y = card_y + card_h * 0.70;
            let topts = FittedOpts::new("Inter")
                .weight(700)
                .tracking(0.05)
                .baseline("middle")
                .min(s * 0.013)
                .shadow(true)
                .slot("glass.title");
            draw_fitted(
                env,
                &title_of(env).to_uppercase(),
                pad_x,
                brand_y,
                card_w * 0.40,
                s * 0.031,
                "#ffffff",
                &topts,
            );
            let dt = dot_date(env);
            let dopts = FittedOpts::new("Inter")
                .weight(400)
                .baseline("middle")
                .min(s * 0.010)
                .shadow(true)
                .slot("glass.date");
            draw_fitted(
                env,
                &dt,
                pad_x,
                date_y,
                card_w * 0.40,
                s * 0.021,
                "rgba(255,255,255,0.94)",
                &dopts,
            );
            if env.text.show_logo {
                let lw = card_w * 0.085;
                let lh = card_h * 0.34;
                draw_brand_logo(
                    env,
                    card_x + card_w * 0.50 - lw / 2.0,
                    card_y + card_h * 0.5 - lh / 2.0,
                    lw,
                    lh,
                    "#ffffff",
                );
            }
            line(
                &mut env.ctx,
                card_x + card_w * 0.575,
                card_y + card_h * 0.22,
                card_x + card_w * 0.575,
                card_y + card_h * 0.78,
                "rgba(255,255,255,0.5)",
                (s * 0.0011).max(1.0),
                None,
            );
            let ex = exif_text(env);
            let eopts = FittedOpts::new("Inter")
                .weight(600)
                .baseline("middle")
                .min(s * 0.011)
                .shadow(true)
                .slot("glass.exif");
            draw_fitted(
                env,
                &ex,
                card_x + card_w * 0.615,
                brand_y,
                card_w * 0.355,
                s * 0.022,
                "#ffffff",
                &eopts,
            );
            let loc = env.text.location.trim();
            let lopts = FittedOpts::new("Inter")
                .weight(400)
                .baseline("middle")
                .min(s * 0.010)
                .shadow(true)
                .slot("glass.location");
            draw_fitted(
                env,
                loc,
                card_x + card_w * 0.615,
                date_y,
                card_w * 0.355,
                s * 0.018,
                "rgba(255,255,255,0.85)",
                &lopts,
            );
            return Ok(Rect::from_xywh(0.0, 0.0, w, h));
        }
        let topts = FittedOpts::new("Inter")
            .weight(600)
            .min(s * 0.012)
            .shadow(true)
            .slot("glass.title");
        draw_fitted(
            env,
            &title_of(env),
            inner_x,
            card_y + card_h * 0.58,
            card_w * 0.30,
            s * 0.023,
            "#ffffff",
            &topts,
        );
        let ex = exif_text(env);
        let eopts = FittedOpts::new("JetBrains Mono")
            .min(s * 0.008)
            .shadow(true)
            .slot("glass.exif");
        draw_fitted(
            env,
            &ex,
            card_x + card_w * 0.40,
            card_y + card_h * 0.58,
            card_w * 0.27,
            s * 0.014,
            "rgba(255,255,255,0.8)",
            &eopts,
        );
        let dt = date_text(env);
        let dopts = FittedOpts::new("JetBrains Mono")
            .min(s * 0.007)
            .shadow(true)
            .slot("glass.date");
        draw_fitted(
            env,
            &dt,
            card_x + card_w * 0.70,
            card_y + card_h * 0.58,
            card_w * 0.16,
            s * 0.012,
            "rgba(255,255,255,0.65)",
            &dopts,
        );
        if env.text.show_logo {
            let lw = card_w * 0.075;
            let lh = card_h * 0.3;
            draw_brand_logo(
                env,
                card_x + card_w * 0.91 - lw / 2.0,
                card_y + card_h / 2.0 - lh / 2.0,
                lw,
                lh,
                "#ffffff",
            );
        }
        line(
            &mut env.ctx,
            card_x + card_w * 0.86,
            card_y + card_h * 0.38,
            card_x + card_w * 0.86,
            card_y + card_h * 0.62,
            &theme.accent,
            (s * 0.0014).max(1.0),
            None,
        );
    } else if layout_mode == "palette" {
        let lopts = FittedOpts::new("JetBrains Mono")
            .weight(500)
            .tracking(0.12)
            .min(s * 0.007)
            .shadow(true)
            .slot("glass.palette.heading");
        draw_fitted(
            env,
            "FRAMEMARK / PALETTE",
            inner_x,
            card_y + card_h * 0.20,
            card_w * 0.55,
            s * 0.011,
            "rgba(255,255,255,0.66)",
            &lopts,
        );
        let topts = FittedOpts::new("Inter")
            .weight(600)
            .min(s * 0.014)
            .shadow(true)
            .slot("glass.title");
        draw_fitted(
            env,
            &title_of(env),
            inner_x,
            card_y + card_h * 0.38,
            card_w * 0.62,
            s * 0.028,
            "#ffffff",
            &topts,
        );
        let ex = exif_text(env);
        let eopts = FittedOpts::new("JetBrains Mono")
            .min(s * 0.008)
            .shadow(true)
            .slot("glass.exif");
        draw_fitted(
            env,
            &ex,
            inner_x,
            card_y + card_h * 0.51,
            card_w * 0.62,
            s * 0.015,
            "rgba(255,255,255,0.78)",
            &eopts,
        );
        let dt = date_text(env);
        let dopts = FittedOpts::new("JetBrains Mono")
            .align("right")
            .min(s * 0.007)
            .shadow(true)
            .slot("glass.date");
        draw_fitted(
            env,
            &dt,
            card_x + card_w - card_w * 0.055,
            card_y + card_h * 0.51,
            card_w * 0.25,
            s * 0.012,
            "rgba(255,255,255,0.64)",
            &dopts,
        );
        let colors = extract_palette(env, 5);
        draw_swatches(
            env,
            &colors,
            card_x + card_w * 0.05,
            card_y + card_h * 0.62,
            card_w * 0.9,
            s * 0.03,
            "rgba(255,255,255,0.62)",
            false,
            "glass.palette.swatch",
            false,
        );
        if env.text.show_logo {
            let lw = card_w * 0.16;
            let lh = card_h * 0.15;
            draw_brand_logo(
                env,
                card_x + card_w - lw - card_w * 0.05,
                card_y + card_h * 0.12,
                lw,
                lh,
                "#ffffff",
            );
        }
    } else {
        let lopts = FittedOpts::new("JetBrains Mono")
            .weight(500)
            .tracking(0.12)
            .min(s * 0.007)
            .shadow(true)
            .slot("glass.liquid.heading");
        draw_fitted(
            env,
            "FRAMEMARK / LIQUID GLASS",
            inner_x,
            card_y + card_h * 0.24,
            card_w * 0.55,
            s * 0.011,
            "rgba(255,255,255,0.66)",
            &lopts,
        );
        let topts = FittedOpts::new("Inter")
            .weight(600)
            .tracking(0.01)
            .min(s * 0.014)
            .shadow(true)
            .slot("glass.title");
        draw_fitted(
            env,
            &title_of(env),
            inner_x,
            card_y + card_h * 0.48,
            card_w * 0.62,
            s * 0.029,
            "#ffffff",
            &topts,
        );
        let ex = exif_text(env);
        let eopts = FittedOpts::new("JetBrains Mono")
            .min(s * 0.009)
            .shadow(true)
            .slot("glass.exif");
        draw_fitted(
            env,
            &ex,
            inner_x,
            card_y + card_h * 0.64,
            card_w * 0.62,
            s * 0.016,
            "rgba(255,255,255,0.78)",
            &eopts,
        );
        let dt = date_text(env);
        let dopts = FittedOpts::new("JetBrains Mono")
            .min(s * 0.008)
            .shadow(true)
            .slot("glass.date");
        draw_fitted(
            env,
            &dt,
            inner_x,
            card_y + card_h * 0.79,
            card_w * 0.55,
            s * 0.013,
            "rgba(255,255,255,0.64)",
            &dopts,
        );
        if env.text.show_logo {
            let la = 0.68 + pct(env.params, "logoScale", 5.0) as f32 * 0.064;
            let lw = card_w * 0.22 * la;
            let lh = card_h * 0.26 * la;
            draw_brand_logo(
                env,
                card_x + card_w - lw - card_w * 0.055,
                card_y + card_h * 0.42,
                lw,
                lh,
                "#ffffff",
            );
        }
        line(
            &mut env.ctx,
            card_x + card_w - card_w * 0.055 - s * 0.02,
            card_y + card_h * 0.82,
            card_x + card_w - card_w * 0.055,
            card_y + card_h * 0.82,
            &theme.accent,
            (s * 0.0018).max(1.0),
            None,
        );
    }
    Ok(Rect::from_xywh(0.0, 0.0, w, h))
}
