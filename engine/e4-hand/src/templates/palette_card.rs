//! `const paletteCard: TemplateDef` (templates.ts:3973) — photo card with an
//! extracted five-colour palette and hex labels. Batch-3 card: `color-1`
//! (paletteRowPct 120.5 → spread row, paletteGapPct 3.6).

use skia_safe::Rect;

use crate::draw::{draw_photo, draw_swatches, extract_palette, theme_of, Env, LayoutEnv};
use crate::hand::pct;
use crate::jsval::pct as js_pct;

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let s = env.pw.min(env.ph);
    let m = (s * (pct(env.params, "marginPct", 2.0) as f32)) / 100.0;
    let palette = (s * (pct(env.params, "palettePct", 22.0) as f32)) / 100.0;
    Ok((env.pw + 2.0 * m, env.ph + 2.0 * m + palette))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h, pw, ph) = (env.w, env.h, env.pw, env.ph);
    let s = pw.min(ph);
    let theme = theme_of(env.themes, env.params);
    let m = (w - pw) / 2.0;
    let palette = h - ph - 2.0 * m;
    let radius = (s * (pct(env.params, "radiusPct", 2.0) as f32)) / 100.0;
    env.ctx.set_fill(&theme.bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    draw_photo(
        &mut env.ctx,
        env.photo,
        &env.crop,
        m,
        m,
        pw,
        ph,
        radius,
        pct(env.params, "shadow", 0.4) as f32,
        &theme,
        s,
    );
    let size = s * 0.082;
    let colors = extract_palette(env, 5);
    let gap_pct = js_pct(env.params.get("paletteGapPct"), f64::NAN);
    let row_pct = js_pct(env.params.get("paletteRowPct"), f64::NAN);
    let block = size * 1.52;
    let y = if gap_pct.is_finite() {
        m + ph + (s * gap_pct as f32) / 100.0
    } else {
        m + ph + m + (palette - block).max(0.0) / 2.0
    };
    let spread = row_pct.is_finite() && row_pct > 0.0;
    // A spread row may ask for >100% of the short side (color-3: 115.4%), which
    // used to push the first/last swatch and their hex labels outside the canvas.
    // Clamp to the canvas and leave room for the label's half-width at both ends.
    let label_pad = (size * 0.62).min(w * 0.08);
    let mut row_w = if spread {
        w.min((s * row_pct as f32) / 100.0)
    } else {
        w - 2.0 * m
    };
    row_w = row_w.min(w - 2.0 * label_pad);
    let row_x = if spread {
        (w - row_w) / 2.0
    } else {
        m.max(label_pad)
    };
    draw_swatches(
        env,
        &colors,
        row_x,
        y,
        row_w,
        size,
        &theme.sub,
        spread,
        "paletteCard.swatch",
    );
    Ok(Rect::from_xywh(m, m, pw, ph))
}
