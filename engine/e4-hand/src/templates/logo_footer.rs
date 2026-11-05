//! `const logoFooter: TemplateDef` (templates.ts:2219) — photo + white footer
//! with a centred brand mark and one classic EXIF row. Card: `card-1`.

use skia_safe::Rect;

use crate::draw::{
    classic_exif_line, draw_brand_logo, draw_photo, draw_photo_cover, draw_tech_text,
    photo_window_of, resolve_text_slot, theme_of, Env, LayoutEnv,
};
use crate::hand::pct;

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let (pw, ph, _cover) = photo_window_of(env.params, env.pw, env.ph);
    let s = pw.min(ph);
    let inset = s * (pct(env.params, "insetPct", 0.0) as f32) / 100.0;
    let footer_h = s * (pct(env.params, "footerPct", 14.8) as f32) / 100.0;
    Ok((pw + 2.0 * inset, inset + ph + footer_h))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let (pw, ph, cover) = photo_window_of(env.params, env.pw, env.ph);
    let theme = theme_of(env.themes, env.params);
    let s = pw.min(ph);
    let inset = (w - pw) / 2.0;
    let footer_h = h - inset - ph;
    env.ctx.set_fill(&theme.bg);
    env.ctx.fill_rect(0.0, 0.0, w, h);
    if cover {
        draw_photo_cover(
            &mut env.ctx,
            env.photo,
            &env.crop,
            inset,
            inset,
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
            inset,
            inset,
            pw,
            ph,
            0.0,
            0.0,
            &theme,
            s,
        );
    }
    let exif_line = classic_exif_line(env);
    // The row is declared even when the photo has no EXIF: a flat slotText
    // override has to reach the renderer before the empty-text guard, and the
    // logo keeps its historical position whenever the row stays empty.
    let exif_resolved = resolve_text_slot(env, "logoFooter.exif", &exif_line);
    let show_exif = env.text.show_exif && !exif_resolved.text.is_empty();
    let logo_h = s * (pct(env.params, "logoPct", 5.5) as f32) / 100.0;
    let logo_w = logo_h;
    let logo_cy = inset + ph + footer_h * if show_exif { 0.32 } else { 0.5 };
    if env.text.show_logo {
        draw_brand_logo(
            env,
            w / 2.0 - logo_w / 2.0,
            logo_cy - logo_h / 2.0,
            logo_w,
            logo_h,
            &theme.fg,
        );
    }
    if env.text.show_exif {
        let size = s * (pct(env.params, "exifSizePct", 2.3) as f32) / 100.0;
        let target_w = s * (pct(env.params, "exifWidthPct", 29.5) as f32) / 100.0;
        draw_tech_text(
            env,
            &exif_line,
            w / 2.0,
            inset + ph + footer_h * 0.72,
            &theme.fg,
            size,
            target_w,
            "center",
            1.0,
            1.0,
            "logoFooter.exif",
        );
    }
    Ok(Rect::from_xywh(inset, inset, pw, ph))
}
