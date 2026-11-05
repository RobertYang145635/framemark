//! `const film: TemplateDef` (templates.ts:2845) — sprocket bands, edge codes
//! and an overlay-blended grain. Batch-2 card: `fm-film` (black theme).

use skia_safe::Rect;

use crate::draw::{
    date_text, draw_fitted, draw_grain, draw_photo, draw_sprockets, theme_of, Env, FittedOpts,
    LayoutEnv,
};
use crate::hand::{pct, Exif};
use crate::jsval::js_round;

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let s = env.pw.min(env.ph);
    let side = s * 0.025;
    let band = s * 0.075;
    Ok((env.pw + 2.0 * side, env.ph + 2.0 * band))
}

/// JS `String#split('').reduce((a,c)=>a*31+code, seed) | abs | % mod`, padded.
fn seeded_number(text: &str, seed: f64, modulo: f64, pad: usize) -> String {
    let mut acc = seed;
    for c in text.chars() {
        acc = acc * 31.0 + (c as u32 as f64);
    }
    let n = (acc.abs() % modulo).floor() as i64;
    format!("{:0width$}", n, width = pad)
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h, pw, ph) = (env.w, env.h, env.pw, env.ph);
    let s = pw.min(ph);
    let side = s * 0.025;
    let band = (h - ph) / 2.0;
    let theme = theme_of(env.themes, env.params);
    let paper = theme.id == "paper";
    let bg = if paper { "#e8e1d2" } else { "#0b0a09" };
    env.ctx.set_fill(bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    draw_photo(
        &mut env.ctx,
        env.photo,
        &env.crop,
        side,
        band,
        pw,
        ph,
        0.0,
        0.0,
        &theme,
        s,
    );
    let frame_color = if paper {
        "rgba(20,20,20,0.35)"
    } else {
        "rgba(230,222,204,0.28)"
    };
    env.ctx
        .stroke_rect(side, band, pw, ph, frame_color, (s * 0.0008).max(1.0));
    let hole_color = if paper {
        "rgba(20,20,20,0.55)"
    } else {
        "#ded5c2"
    };
    let count = (js_round((w / (s * 0.075)) as f64) as i64).max(6) as usize;
    draw_sprockets(&mut env.ctx, 0.0, 0.0, w, band, count, hole_color);
    draw_sprockets(&mut env.ctx, 0.0, h - band, w, band, count, hole_color);
    let ink = if paper { "#141414" } else { "#f0eadc" };
    let sub = if paper {
        "rgba(20,20,20,0.62)"
    } else {
        "rgba(240,234,220,0.66)"
    };
    let x0 = side + s * 0.018;
    let x1 = w - side - s * 0.018;
    draw_fitted(
        env,
        "FRAMEMARK 400",
        x0,
        band + s * 0.042,
        w * 0.38,
        s * 0.013,
        ink,
        &FittedOpts::new("JetBrains Mono")
            .weight(500)
            .tracking(0.1)
            .min(s * 0.008)
            .shadow(true)
            .slot("film.top-left"),
    );
    draw_fitted(
        env,
        "36 EXP",
        x1,
        band + s * 0.042,
        w * 0.22,
        s * 0.013,
        sub,
        &FittedOpts::new("JetBrains Mono")
            .align("right")
            .tracking(0.08)
            .min(s * 0.008)
            .shadow(true)
            .slot("film.top-right"),
    );
    let dt = date_text(env);
    // Call the declared row even when the source EXIF date is absent so a
    // slotText override is resolved before the renderer's empty-text check.
    draw_fitted(
        env,
        &dt,
        x0,
        h - band - s * 0.018,
        w * 0.4,
        s * 0.012,
        sub,
        &FittedOpts::new("JetBrains Mono")
            .min(s * 0.008)
            .shadow(true)
            .slot("film.date"),
    );
    let seed_text = if env.exif.date_time.is_empty() {
        if env.exif.model.is_empty() {
            "FRAME".to_string()
        } else {
            env.exif.model.clone()
        }
    } else {
        env.exif.date_time.clone()
    };
    let frame_no = seeded_number(&seed_text, 7.0, 99.0, 2);
    draw_fitted(
        env,
        &format!("NO. {frame_no}"),
        x1,
        h - band - s * 0.018,
        w * 0.3,
        s * 0.012,
        sub,
        &FittedOpts::new("JetBrains Mono")
            .align("right")
            .min(s * 0.008)
            .shadow(true)
            .slot("film.frame-number"),
    );
    let cam = if env.text.show_make || env.text.show_model {
        env.exif.camera_line("")
    } else {
        String::new()
    };
    draw_fitted(
        env,
        &cam.to_uppercase(),
        x0,
        h - band - s * 0.042,
        w * 0.55,
        s * 0.01,
        sub,
        &FittedOpts::new("Inter")
            .weight(600)
            .tracking(0.14)
            .min(s * 0.007)
            .shadow(true)
            .slot("film.camera"),
    );
    draw_grain(
        &mut env.ctx,
        w,
        h,
        (pct(env.params, "grain", 0.16) as f32) * 0.55,
    );
    let _ = Exif::default();
    Ok(Rect::from_xywh(side, band, pw, ph))
}
