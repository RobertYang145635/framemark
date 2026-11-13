//! Port of the shared `core/draw.ts` primitives, plus the theme table.
//!
//! Every helper mirrors one function in the frozen file (line numbers below) so
//! a reviewer can diff semantics 1:1. Batch 1 needs the photo primitives, the
//! `drawFitted` fit ladder, `drawBrandLogo`, `line`, `drawBlurredBackdrop`,
//! `drawTechText` and the two EXIF line builders; the rest (grain, barcode,
//! sprockets, palette, swatches, avatar chip) land with their cards.

use std::cell::RefCell;

use serde_json::Value;
use skia_safe::{image_filters, Color, FilterMode, Image, Rect, SamplingOptions, TileMode};

use crate::canvas::Ctx2D;
use crate::css;
use crate::fonts::HandFonts;
use crate::hand::{format_date_only, pct, Crop, Design, Exif, Params, TextSettings};
use crate::jsval::js_round;
use crate::lines::{self, LineState};
use crate::report::{RectJson, TextDraw};
use crate::text::{HAlign, Shadow, TextEngine, TextSpec, VBaseline};

/// Gaps with this prefix are fatal in the Android bridge. Other gaps remain
/// diagnostic because some templates intentionally report unsupported decor.
pub const FONT_ERROR_PREFIX: &str = "font error: ";

pub struct Images {
    pub logo: Option<Image>,
    pub avatar: Option<Image>,
    pub sticker: Option<Image>,
    pub frame: Option<Image>,
}

impl Images {
    pub fn empty() -> Self {
        Self {
            logo: None,
            avatar: None,
            sticker: None,
            frame: None,
        }
    }
}

pub struct Env<'a> {
    pub ctx: Ctx2D<'a>,
    pub w: f32,
    pub h: f32,
    pub pw: f32,
    pub ph: f32,
    pub crop: Crop,
    pub photo: &'a Image,
    pub exif: &'a Exif,
    pub params: &'a Params,
    pub text: &'a TextSettings,
    pub design: &'a Design,
    pub images: &'a Images,
    pub is_preview: bool,
    pub fonts: &'a HandFonts,
    pub te: &'a TextEngine,
    pub themes: &'a Value,
    /// Chromium draws the palette sample on a GPU-backed temp canvas; this is
    /// the same 48 x h downscale read back from Ganesh (None = CPU fallback).
    pub palette48: Option<&'a (Vec<u8>, usize, usize)>,
    /// Per-pass duplicate guard for explicitly declared stable text slots.
    pub lines: RefCell<LineState>,
}

pub struct LayoutEnv<'a> {
    pub pw: f32,
    pub ph: f32,
    pub crop: Crop,
    pub exif: &'a Exif,
    pub params: &'a Params,
    pub text: &'a TextSettings,
}

// ── theme (core/draw.ts:35 THEMES / themeOf) ────────────────────────────────

#[derive(Clone, Debug)]
pub struct Theme {
    pub id: String,
    pub bg: String,
    pub fg: String,
    pub sub: String,
    pub line: String,
    pub accent: String,
    pub paper: String,
    pub shadow: String,
}

impl Theme {
    fn from_value(id: &str, v: &Value) -> Option<Self> {
        let s = |k: &str| v.get(k).and_then(|x| x.as_str()).map(|x| x.to_string());
        Some(Self {
            id: id.to_string(),
            bg: s("bg")?,
            fg: s("fg")?,
            sub: s("sub")?,
            line: s("line")?,
            accent: s("accent")?,
            paper: s("paper")?,
            shadow: s("shadow")?,
        })
    }
}

pub fn theme_of(themes: &Value, params: &Params) -> Theme {
    let id = match params.get("theme") {
        None | Some(Value::Null) => "paper".to_string(),
        Some(v) => crate::jsval::js_string(Some(v)),
    };
    let paper = themes
        .get("paper")
        .and_then(|v| Theme::from_value("paper", v))
        .unwrap_or(Theme {
            id: "paper".into(),
            bg: "#f6f3ec".into(),
            fg: "#151515".into(),
            sub: "#6f6a61".into(),
            line: "rgba(21,21,21,0.16)".into(),
            accent: "#a9824c".into(),
            paper: "#fbf9f4".into(),
            shadow: "rgba(20,18,15,0.22)".into(),
        });
    themes
        .get(id.as_str())
        .and_then(|v| Theme::from_value(id.as_str(), v))
        .unwrap_or(paper)
}

// ── photo (draw.ts:160 drawPhoto / :190 drawPhotoCover) ────────────────────

/// Blink `imageSmoothingQuality:'high'`: upscale -> Mitchell cubic, downscale
/// -> bilinear, exact 1:1 -> nearest (T1c/E2b measured).
pub fn image_sampling(src_w: f32, dst_w: f32, src_h: f32, dst_h: f32) -> SamplingOptions {
    image_sampling_at(src_w, src_h, Rect::from_xywh(0.0, 0.0, dst_w, dst_h))
}

/// Same rule, but an exact 1:1 blit with an INTEGER destination origin is a
/// byte copy (T1c Δ0 with nearest); at a fractional origin Chromium's cubic
/// kernel blends and nearest would visibly snap the image.
pub fn image_sampling_at(src_w: f32, src_h: f32, dst: Rect) -> SamplingOptions {
    let sx = dst.width() / src_w.max(1e-6);
    let sy = dst.height() / src_h.max(1e-6);
    if let Ok(mode) = std::env::var("E4H_IMG_SAMPLING") {
        match mode.as_str() {
            "linear" => {
                let mut s = SamplingOptions::default();
                s.filter = FilterMode::Linear;
                return s;
            }
            "cubic" => {
                let mut s = SamplingOptions::default();
                s.use_cubic = true;
                s.cubic = skia_safe::CubicResampler::mitchell();
                return s;
            }
            "nearest" => return SamplingOptions::default(),
            "mipmap" => {
                return SamplingOptions::new(FilterMode::Linear, skia_safe::MipmapMode::Linear)
            }
            "cubicmip" => {
                let mut s = SamplingOptions::new(FilterMode::Linear, skia_safe::MipmapMode::Linear);
                s.use_cubic = true;
                s.cubic = skia_safe::CubicResampler::mitchell();
                return s;
            }
            _ => {}
        }
    }
    if (sx - 1.0).abs() < 1e-4 && (sy - 1.0).abs() < 1e-4 {
        // Chromium's high-quality 1:1 blit is bilinear: exact at integer
        // offsets (T1c Δ0) and the correct half-pixel blend at fractional ones.
        let mut s = SamplingOptions::default();
        s.filter = FilterMode::Linear;
        return s;
    }
    if sx < 1.0 || sy < 1.0 {
        let mut s = SamplingOptions::default();
        s.filter = FilterMode::Linear;
        s
    } else {
        let mut s = SamplingOptions::default();
        s.use_cubic = true;
        s.cubic = skia_safe::CubicResampler::mitchell();
        s
    }
}

pub fn draw_photo(
    ctx: &mut Ctx2D,
    photo: &Image,
    crop: &Crop,
    x: f32,
    y: f32,
    w: f32,
    h: f32,
    radius: f32,
    shadow: f32,
    theme: &Theme,
    short_side: f32,
) {
    let src = Rect::from_xywh(crop.sx, crop.sy, crop.sw, crop.sh);
    let snap = std::env::var("E4H_SNAP_DST").ok().as_deref() == Some("1");
    let (x, y) = if snap { (x.round(), y.round()) } else { (x, y) };
    let (x, y, w, h) = match ctx.snap_photo(x, y) {
        Some(s) => s,
        None => (x, y, w, h),
    };
    let dst = Rect::from_xywh(x, y, w, h);
    if shadow > 0.0 {
        let sc = if theme.id == "ink" || theme.id == "black" {
            "rgba(0,0,0,0.65)"
        } else {
            "rgba(25,21,16,0.25)"
        };
        ctx.fill_round_rect_shadow(
            x,
            y,
            w,
            h,
            radius,
            "#000",
            sc,
            short_side * 0.03 * shadow,
            short_side * 0.012 * shadow,
        );
        ctx.record_shape("photo-shadow", dst, None, true);
    }
    if radius > 0.0 {
        ctx.clip_round_rect(x, y, w, h, radius);
    }
    // The template's crop window is in the photo's logical pixels; the draw maps
    // it into the decoded raster and keeps the logical rect in the report.
    ctx.draw_photo_image(
        photo,
        src,
        dst,
        image_sampling_at(crop.sw, crop.sh, dst),
        "photo",
    );
    // render-v1 overlay: above the photo, below everything the template paints
    // afterwards (still inside the photo's clip).
    ctx.draw_overlay_once(dst);
    if radius > 0.0 {
        ctx.restore_clip();
    }
    ctx.record_photo(dst, radius);
}

pub fn draw_photo_cover(
    ctx: &mut Ctx2D,
    photo: &Image,
    crop: &Crop,
    x: f32,
    y: f32,
    w: f32,
    h: f32,
    radius: f32,
    shadow: f32,
    short_side: f32,
) {
    let (x, y, w, h) = match ctx.snap_photo(x, y) {
        Some(s) => s,
        None => (x, y, w, h),
    };
    // `cover` slice inside the window the template chose. A user crop moves the
    // slice to its own source point (clamped inside that window), which is what
    // makes a pan visible at zoom 1; at the defaults this is the centred rect.
    // The cover math reads the photo's *logical* size, so a bounded interactive
    // decode cannot move the slice it samples.
    let (iw, ih) = ctx
        .photo_logical()
        .unwrap_or((photo.width() as f32, photo.height() as f32));
    let user = ctx.user_crop().unwrap_or_default();
    let src = crate::hand::cover_src(*crop, user, iw, ih, w, h);
    let (sx, sy, sw, sh) = (src[0], src[1], src[2], src[3]);
    if shadow > 0.0 {
        ctx.fill_round_rect_shadow(
            x,
            y,
            w,
            h,
            radius,
            "#000",
            "rgba(0,0,0,0.5)",
            short_side * 0.03 * shadow,
            0.0,
        );
        ctx.record_shape("photo-shadow", Rect::from_xywh(x, y, w, h), None, true);
    }
    if radius > 0.0 {
        ctx.clip_round_rect(x, y, w, h, radius);
    }
    ctx.draw_photo_image(
        photo,
        Rect::from_xywh(sx, sy, sw, sh),
        Rect::from_xywh(x, y, w, h),
        image_sampling_at(sw, sh, Rect::from_xywh(x, y, w, h)),
        "photo",
    );
    ctx.draw_overlay_once(Rect::from_xywh(x, y, w, h));
    if radius > 0.0 {
        ctx.restore_clip();
    }
    ctx.record_photo(Rect::from_xywh(x, y, w, h), radius);
}

/// `photoWindowOf()` (templates.ts:2208)
pub fn photo_window_of(params: &Params, pw: f32, ph: f32) -> (f32, f32, bool) {
    let aspect = pct(params, "photoAspect", 0.0) as f32;
    if !(aspect > 0.05) {
        return (pw, ph, false);
    }
    if aspect >= pw / ph {
        (pw, pw / aspect, true)
    } else {
        (ph * aspect, ph, true)
    }
}

/// `aspectCanvas()` (templates.ts:1873) — rounded like `scene.ts`.
pub fn aspect_canvas(pw: f32, ph: f32, aspect: f64) -> (f32, f32) {
    if !(aspect > 0.05) {
        return (
            f32::max(2.0, js_round(pw as f64) as f32),
            f32::max(2.0, js_round(ph as f64) as f32),
        );
    }
    let a = aspect as f32;
    if pw / ph >= a {
        (
            f32::max(2.0, js_round((ph * a) as f64) as f32),
            f32::max(2.0, js_round(ph as f64) as f32),
        )
    } else {
        (
            f32::max(2.0, js_round(pw as f64) as f32),
            f32::max(2.0, js_round((pw / a) as f64) as f32),
        )
    }
}

/// `shortSide`, `marginOf`, `radiusOf`, `logoScale`.
pub fn short_side(env: &LayoutEnv) -> f32 {
    env.pw.min(env.ph)
}

pub fn margin_of(env: &LayoutEnv) -> f32 {
    short_side(env) * (pct(env.params, "marginPct", 6.0) as f32 / 100.0)
}

pub fn radius_of(env: &LayoutEnv) -> f32 {
    short_side(env) * (pct(env.params, "radiusPct", 0.0) as f32 / 100.0)
}

pub fn logo_scale(env: &LayoutEnv) -> f32 {
    0.68 + pct(env.params, "logoScale", 5.0) as f32 * 0.064
}

// ── text (draw.ts:331 fitSize / :369 drawFitted) ───────────────────────────

#[derive(Clone, Copy, Debug)]
pub struct FittedOpts<'a> {
    pub family: &'a str,
    pub weight: u16,
    pub align: &'a str,
    pub baseline: &'a str,
    pub tracking: f64,
    pub min: f32,
    pub shadow: bool,
    /// Explicit logical row ID within the current template/variant scope.
    /// `None` means the row is rendered without a stable editing contract.
    pub slot: Option<&'a str>,
    /// Existing declared rows (currently brandFooter) apply their historical
    /// `slotValues`/`slotSizes` in the template, but flat text/visibility still
    /// resolves here. Their flat scale is applied by the template's size helper.
    pub declared_slot: bool,
}

impl<'a> FittedOpts<'a> {
    pub fn new(family: &'a str) -> Self {
        Self {
            family,
            weight: 400,
            align: "left",
            baseline: "alphabetic",
            tracking: 0.0,
            min: 0.0,
            shadow: false,
            slot: None,
            declared_slot: false,
        }
    }
    pub fn weight(mut self, w: u16) -> Self {
        self.weight = w;
        self
    }
    pub fn align(mut self, a: &'a str) -> Self {
        self.align = a;
        self
    }
    pub fn baseline(mut self, b: &'a str) -> Self {
        self.baseline = b;
        self
    }
    pub fn tracking(mut self, t: f64) -> Self {
        self.tracking = t;
        self
    }
    pub fn min(mut self, m: f32) -> Self {
        self.min = m;
        self
    }
    pub fn shadow(mut self, s: bool) -> Self {
        self.shadow = s;
        self
    }
    /// Adds a stable hand text slot and enables the flat `slotText`/`slotOff`/
    /// `slotScale` params contract for this row.
    pub fn slot(mut self, id: &'a str) -> Self {
        self.slot = Some(id);
        self.declared_slot = false;
        self
    }
    /// Adds stable report identity for a row whose template code already
    /// applies its legacy declared-slot values and sizing.
    pub fn declared_slot(mut self, id: &'a str) -> Self {
        self.slot = Some(id);
        self.declared_slot = true;
        self
    }
}

fn halign(s: &str) -> HAlign {
    match s {
        "center" => HAlign::Center,
        "right" => HAlign::Right,
        _ => HAlign::Left,
    }
}

fn vbaseline(s: &str) -> Option<VBaseline> {
    match s {
        "alphabetic" | "top" => Some(VBaseline::Alphabetic),
        "middle" => Some(VBaseline::Middle),
        _ => None,
    }
}

pub struct FacePick {
    pub tf: skia_safe::Typeface,
    pub synthetic: bool,
}

fn pick_face(fonts: &HandFonts, ctx: &mut Ctx2D, family: &str, weight: u16) -> Option<FacePick> {
    match fonts.resolve(family, weight) {
        Some(r) => Some(FacePick {
            tf: r.tf,
            synthetic: r.synthetic_bold,
        }),
        None => {
            ctx.record_gap(format!(
                "{FONT_ERROR_PREFIX}required font family '{family}' weight {weight} was not resolved"
            ));
            None
        }
    }
}

fn ensure_text_glyphs(
    env: &mut Env,
    text: &str,
    family: &str,
    weight: u16,
    pick: &FacePick,
) -> String {
    let missing = env.te.missing_glyphs(&pick.tf, text);
    if missing.is_empty() {
        return text.to_string();
    }

    let mut drawable = String::with_capacity(text.len());
    let mut substitutions = Vec::new();
    for c in text.chars() {
        if !missing.contains(&c) {
            drawable.push(c);
            continue;
        }
        let replacement = replacement_for(c, |candidate| {
            env.te
                .missing_glyphs(&pick.tf, &candidate.to_string())
                .is_empty()
        });
        if let Some(replacement) = replacement {
            substitutions.push(format!("U+{:04X} '{}' -> '{}'", c as u32, c, replacement));
            drawable.push(replacement);
        } else {
            substitutions.push(format!("U+{:04X} '{}' -> removed", c as u32, c));
        }
    }
    if !substitutions.is_empty() {
        env.ctx.record_gap(format!(
            "font substitution for {family} {weight}: {}",
            substitutions.join(", ")
        ));
    }
    drawable
}

fn compatibility_char(c: char) -> Option<char> {
    match c {
        '\u{3000}' => Some(' '),
        '\u{2212}' => Some('-'),
        '\u{FF01}'..='\u{FF5E}' => char::from_u32(c as u32 - 0xFEE0),
        _ => None,
    }
}

fn replacement_for(c: char, supported: impl Fn(char) -> bool) -> Option<char> {
    compatibility_char(c)
        .filter(|candidate| *candidate != c && supported(*candidate))
        .or_else(|| ['\u{FFFD}', '?', ' '].into_iter().find(|candidate| supported(*candidate)))
}

#[cfg(test)]
mod text_fallback_tests {
    use super::*;

    #[test]
    fn unsupported_symbols_keep_the_rest_of_the_line_visible() {
        let compatible = replacement_for('\u{FF0D}', |c| c.is_ascii());
        let substituted = replacement_for('\u{1F626}', |c| c.is_ascii());

        assert_eq!(compatible, Some('-'));
        assert_eq!(substituted, Some('?'));
    }
}

/// `fitSize()` (draw.ts:331). The frozen ladder measures with the tracking
/// computed from the START size (not the shrinking candidate) — replicated.
pub fn fit_size(
    te: &TextEngine,
    tf: &skia_safe::Typeface,
    text: &str,
    max_width: f32,
    start: f32,
    min: f32,
    tracking_em: f64,
) -> f32 {
    // #69 A5: the measured ink is `size * scale`, so the slot's painted room is
    // `max_width * scale`; comparing against the design width would eat a ladder
    // step. `scale == 1` keeps `max_width` byte-for-byte.
    let cap = if te.scale == 1.0 {
        max_width
    } else {
        max_width * te.scale + 1e-6
    };
    let tracking_px = (tracking_em as f32) * start;
    let mut size = start;
    while size > min {
        let (_, w) = te.shape(text, tf, size, tracking_px);
        if w <= cap {
            break;
        }
        size *= 0.96;
    }
    size
}

/// `drawFitted()` (draw.ts:369) — returns the painted design size.
#[allow(clippy::too_many_arguments)]
pub fn draw_fitted(
    env: &mut Env,
    text: &str,
    x: f32,
    y: f32,
    max_width: f32,
    size: f32,
    color: &str,
    opts: &FittedOpts,
) -> f32 {
    let (current_text, slot_scale, meta) = if let Some(slot) = opts.slot {
        match begin_text_slot(env, slot, text) {
            Some((meta, resolved)) => (
                resolved.text,
                if opts.declared_slot {
                    1.0
                } else {
                    resolved.scale
                },
                Some(meta),
            ),
            None => (text.to_string(), 1.0, None),
        }
    } else {
        (text.to_string(), 1.0, None)
    };
    if current_text.is_empty() {
        let Some(meta) = meta else { return 0.0 };
        let rect = fitted_slot_rect(
            x,
            y,
            max_width,
            size * slot_scale as f32,
            opts.align,
            opts.baseline,
        );
        return draw_text_exact_resolved(
            env,
            &current_text,
            x,
            y,
            size * slot_scale as f32,
            color,
            opts.family,
            opts.weight,
            opts.align,
            opts.baseline,
            0.0,
            opts.shadow,
            None,
            text,
            Some(meta),
            Some(rect),
        );
    }
    let Some(pick) = pick_face(env.fonts, &mut env.ctx, opts.family, opts.weight) else {
        return 0.0;
    };
    let current_text = ensure_text_glyphs(env, &current_text, opts.family, opts.weight, &pick);
    let min = if opts.min > 0.0 {
        opts.min
    } else {
        size * 0.45
    };
    let tracking_em = opts.tracking;
    let final_size = fit_size(
        env.te,
        &pick.tf,
        &current_text,
        max_width,
        size,
        min,
        tracking_em,
    );
    let tracking_px = (tracking_em as f32) * final_size;
    let scaled_size = final_size * slot_scale as f32;
    draw_text_exact_resolved(
        env,
        &current_text,
        x,
        y,
        scaled_size,
        color,
        opts.family,
        opts.weight,
        opts.align,
        opts.baseline,
        tracking_px,
        opts.shadow,
        Some(&pick),
        text,
        meta,
        None,
    );
    // #69 A1/A2: stacked rows advance by the ink they took, i.e. the painted
    // size, so 1.22 pitch stays proportional to the glyphs.
    if env.te.scale == 1.0 {
        scaled_size
    } else {
        scaled_size * env.te.scale
    }
}

#[derive(Clone)]
struct TextSlotMeta {
    key: String,
    source: lines::Source,
    hidden: bool,
    stable: bool,
}

fn begin_text_slot(
    env: &mut Env,
    slot: &str,
    raw: &str,
) -> Option<(TextSlotMeta, lines::Resolution)> {
    let scope = env
        .design
        .variant_id
        .clone()
        .unwrap_or_else(|| env.design.template_id.clone());
    let key = lines::stable_key(&scope, slot);
    if !env.lines.borrow_mut().claim(&key) {
        env.ctx
            .record_gap(format!("duplicate hand text slot '{key}'"));
        return None;
    }
    let source = lines::classify(raw, env.text, env.exif, &title_of(env));
    let resolved = lines::resolve(env.params, &key, raw);
    let meta = TextSlotMeta {
        key,
        source,
        hidden: resolved.hidden,
        stable: true,
    };
    Some((meta, resolved))
}

/// Preview the flat slot value for layout decisions made before the row is
/// drawn (for example, whether an edited subtitle reserves a second line).
/// Claiming and reporting still happen only in `draw_fitted`/`draw_text_exact`.
pub fn resolve_text_slot(env: &Env, slot: &str, raw: &str) -> lines::Resolution {
    let scope = env
        .design
        .variant_id
        .clone()
        .unwrap_or_else(|| env.design.template_id.clone());
    lines::resolve(env.params, &lines::stable_key(&scope, slot), raw)
}

fn fitted_slot_rect(
    x: f32,
    y: f32,
    width: f32,
    size: f32,
    align: &str,
    baseline: &str,
) -> RectJson {
    let left = match halign(align) {
        HAlign::Left => x,
        HAlign::Center => x - width / 2.0,
        HAlign::Right => x - width,
    };
    let top = match baseline {
        "top" => y,
        "middle" => y - 0.465 * size,
        _ => y - 0.72 * size,
    };
    RectJson::new(left, top, width.max(0.0), 0.93 * size)
}

fn transform_report_rect(matrix: skia_safe::Matrix, rect: RectJson) -> RectJson {
    let src_pts = [
        skia_safe::Point::new(rect.x, rect.y),
        skia_safe::Point::new(rect.x + rect.w, rect.y),
        skia_safe::Point::new(rect.x, rect.y + rect.h),
        skia_safe::Point::new(rect.x + rect.w, rect.y + rect.h),
    ];
    let mut pts = [skia_safe::Point::default(); 4];
    matrix.map_points(&mut pts, &src_pts);
    let (min_x, max_x) = pts
        .iter()
        .map(|point| point.x)
        .fold((f32::MAX, f32::MIN), |(min, max), value| {
            (min.min(value), max.max(value))
        });
    let (min_y, max_y) = pts
        .iter()
        .map(|point| point.y)
        .fold((f32::MAX, f32::MIN), |(min, max), value| {
            (min.min(value), max.max(value))
        });
    RectJson::new(
        min_x,
        min_y,
        (max_x - min_x).max(0.0),
        (max_y - min_y).max(0.0),
    )
}

/// `measureFitted()` (templates.ts:182) — (painted size, painted width).
pub fn measure_fitted(
    env: &mut Env,
    text: &str,
    max_width: f32,
    size: f32,
    min: f32,
    family: &str,
    weight: u16,
    tracking_em: f64,
) -> (f32, f32) {
    if text.is_empty() {
        return (0.0, 0.0);
    }
    let Some(pick) = pick_face(env.fonts, &mut env.ctx, family, weight) else {
        return (size, 0.0);
    };
    let text = ensure_text_glyphs(env, text, family, weight, &pick);
    let fitted = fit_size(env.te, &pick.tf, &text, max_width, size, min, tracking_em);
    let (_, width) = env
        .te
        .shape(&text, &pick.tf, fitted, (tracking_em as f32) * fitted);
    let painted = if env.te.scale == 1.0 {
        fitted
    } else {
        env.te.painted_size(fitted)
    };
    (painted, width)
}

/// One run at an exact size/baseline (the base under `drawFitted`, the brand
/// wordmark fallback, and the raw `fillText` sites).
#[allow(clippy::too_many_arguments)]
pub fn draw_text_exact(
    env: &mut Env,
    text: &str,
    x: f32,
    y: f32,
    size: f32,
    color: &str,
    family: &str,
    weight: u16,
    align: &str,
    baseline: &str,
    tracking_px: f32,
    shadow: bool,
    pick: Option<&FacePick>,
) -> f32 {
    draw_text_exact_with_slot_internal(
        env,
        text,
        x,
        y,
        size,
        color,
        family,
        weight,
        align,
        baseline,
        tracking_px,
        shadow,
        pick,
        None,
    )
}

/// Exact-size text with an explicit stable hand slot. Used for transformed
/// text paths such as vertical labels; it shares the same resolver and report
/// path as `draw_fitted` rather than maintaining a second implementation.
#[allow(clippy::too_many_arguments)]
pub fn draw_text_exact_slot(
    env: &mut Env,
    text: &str,
    x: f32,
    y: f32,
    size: f32,
    color: &str,
    family: &str,
    weight: u16,
    align: &str,
    baseline: &str,
    tracking_px: f32,
    shadow: bool,
    pick: Option<&FacePick>,
    slot: &str,
) -> f32 {
    draw_text_exact_with_slot_internal(
        env,
        text,
        x,
        y,
        size,
        color,
        family,
        weight,
        align,
        baseline,
        tracking_px,
        shadow,
        pick,
        Some(slot),
    )
}

#[allow(clippy::too_many_arguments)]
fn draw_text_exact_with_slot_internal(
    env: &mut Env,
    raw: &str,
    x: f32,
    y: f32,
    size: f32,
    color: &str,
    family: &str,
    weight: u16,
    align: &str,
    baseline: &str,
    tracking_px: f32,
    shadow: bool,
    pick: Option<&FacePick>,
    slot: Option<&str>,
) -> f32 {
    let (current, size, meta) = if let Some(slot) = slot {
        match begin_text_slot(env, slot, raw) {
            Some((meta, resolved)) => (
                resolved.text,
                (size as f64 * resolved.scale) as f32,
                Some(meta),
            ),
            None => (raw.to_string(), size, None),
        }
    } else {
        (raw.to_string(), size, None)
    };
    draw_text_exact_resolved(
        env,
        &current,
        x,
        y,
        size,
        color,
        family,
        weight,
        align,
        baseline,
        tracking_px,
        shadow,
        pick,
        raw,
        meta,
        None,
    )
}

#[allow(clippy::too_many_arguments)]
fn draw_text_exact_resolved(
    env: &mut Env,
    text: &str,
    x: f32,
    y: f32,
    size: f32,
    color: &str,
    family: &str,
    weight: u16,
    align: &str,
    baseline: &str,
    tracking_px: f32,
    shadow: bool,
    pick: Option<&FacePick>,
    raw: &str,
    meta: Option<TextSlotMeta>,
    empty_rect: Option<RectJson>,
) -> f32 {
    if text.is_empty() {
        if let Some(meta) = meta {
            let matrix = env.ctx.matrix();
            let rect = empty_rect
                .map(|rect| transform_report_rect(matrix, rect))
                .unwrap_or_else(|| transform_report_rect(matrix, RectJson::new(x, y, 0.0, 0.0)));
            let m = matrix;
            env.ctx.record_text(TextDraw {
                seq: 0,
                s: String::new(),
                raw: raw.to_string(),
                family: family.to_string(),
                weight,
                size: if env.te.scale == 1.0 {
                    size
                } else {
                    env.te.painted_size(size)
                },
                x,
                y,
                align: align.to_string(),
                baseline: baseline.to_string(),
                tracking: tracking_px,
                measure: 0.0,
                shadow,
                rect,
                matrix: [
                    m.scale_x(),
                    m.skew_y(),
                    m.skew_x(),
                    m.scale_y(),
                    m.translate_x(),
                    m.translate_y(),
                ],
                key: meta.key,
                source: meta.source.tag().to_string(),
                hidden: meta.hidden,
                stable: true,
                present: true,
            });
        }
        return 0.0;
    }
    let owned;
    let pick = match pick {
        Some(p) => p,
        None => match pick_face(env.fonts, &mut env.ctx, family, weight) {
            Some(p) => {
                owned = p;
                &owned
            }
            None => return 0.0,
        },
    };
    let Some(vb) = vbaseline(baseline) else {
        env.ctx
            .record_gap(format!("textBaseline '{baseline}' not ported"));
        return 0.0;
    };
    let text = ensure_text_glyphs(env, text, family, weight, pick);
    let line_source = meta
        .as_ref()
        .map(|slot| slot.source)
        .unwrap_or_else(|| lines::classify(raw, env.text, env.exif, &title_of(env)));
    let hidden = meta.as_ref().map(|slot| slot.hidden).unwrap_or(false);
    // Canvas2D 'top' puts the em-box top at y: draw on the alphabetic baseline
    // shifted down by the font ascent.
    let top_shift = if baseline == "top" {
        let (ascent, _, _, _) = env.te.metrics(&pick.tf, size);
        -ascent
    } else {
        0.0
    };
    let (_, width) = env.te.shape(&text, &pick.tf, size, tracking_px);
    let alpha = env.ctx.global_alpha();
    let col = match css::parse(color) {
        Some(c) => c.to_color(alpha),
        None => {
            env.ctx.record_gap(format!("invalid text colour '{color}'"));
            Color::TRANSPARENT
        }
    };
    let d021 = if matches!(vb, VBaseline::Middle) {
        env.fonts.d021_em(family) * env.te.painted_size(size)
    } else {
        0.0
    };
    let shadow_spec = if shadow {
        let sc = css::parse("rgba(0,0,0,0.5)")
            .map(|c| c.to_color(1.0))
            .unwrap_or(Color::BLACK);
        let k = std::env::var("E4H_SHADOW_SIGMA")
            .ok()
            .and_then(|v| v.parse::<f32>().ok())
            .unwrap_or(1.0);
        let off = std::env::var("E4H_SHADOW_DY")
            .ok()
            .and_then(|v| v.parse::<f32>().ok())
            .unwrap_or(0.08);
        Some(Shadow {
            color: sc,
            sigma: size * 0.35 * 0.5 * k,
            dx: 0.0,
            dy: size * off,
        })
    } else {
        None
    };
    let matrix = env.ctx.matrix();
    let canvas = env.ctx.canvas_ref();
    let spec = TextSpec {
        text: &text,
        typeface: pick.tf.clone(),
        size,
        x,
        y: y - d021 + top_shift,
        align: halign(align),
        baseline: vb,
        tracking: tracking_px,
        color: col,
        shadow: shadow_spec,
        matrix: Some(matrix),
        embolden: pick.synthetic,
    };
    if !hidden {
        env.te.draw(canvas, &spec);
    }
    let left = match halign(align) {
        HAlign::Left => x,
        HAlign::Center => x - width / 2.0,
        HAlign::Right => x - width,
    };
    let painted = env.te.painted_size(size);
    let (r_top, r_h) = if baseline == "top" {
        (y, 0.93 * painted)
    } else {
        match vb {
            VBaseline::Alphabetic => (y - 0.72 * painted, 0.93 * painted),
            VBaseline::Middle => (y - 0.465 * painted, 0.93 * painted),
        }
    };
    // The rect is in the local space the glyphs are painted in; fold the CTM
    // so the report is in canvas pixels (region attribution).
    let src_pts = [
        skia_safe::Point::new(left, r_top),
        skia_safe::Point::new(left + width.max(1.0), r_top),
        skia_safe::Point::new(left, r_top + r_h),
        skia_safe::Point::new(left + width.max(1.0), r_top + r_h),
    ];
    let mut pts = [skia_safe::Point::default(); 4];
    matrix.map_points(&mut pts, &src_pts);
    let xs = pts.iter().map(|p| p.x);
    let ys = pts.iter().map(|p| p.y);
    let (min_x, max_x) = xs.fold((f32::MAX, f32::MIN), |(a, b), v| (a.min(v), b.max(v)));
    let (min_y, max_y) = ys.fold((f32::MAX, f32::MIN), |(a, b), v| (a.min(v), b.max(v)));
    let m = matrix;
    let rec = TextDraw {
        seq: 0,
        s: text.to_string(),
        raw: raw.to_string(),
        family: family.to_string(),
        weight,
        // The oracle recorder reads back `ctx.font`, which scaledContext has
        // already rewritten: `fs` is the painted size.
        size: if env.te.scale == 1.0 { size } else { painted },
        x,
        y,
        align: align.to_string(),
        baseline: baseline.to_string(),
        tracking: tracking_px,
        measure: width,
        shadow,
        rect: RectJson::new(
            min_x,
            min_y,
            (max_x - min_x).max(1.0),
            (max_y - min_y).max(1.0),
        ),
        matrix: [
            m.scale_x(),
            m.skew_y(),
            m.skew_x(),
            m.scale_y(),
            m.translate_x(),
            m.translate_y(),
        ],
        key: meta
            .as_ref()
            .map(|slot| slot.key.clone())
            .unwrap_or_default(),
        source: line_source.tag().to_string(),
        hidden,
        stable: meta.as_ref().is_some_and(|slot| slot.stable),
        present: true,
    };
    env.ctx.record_text(rec);
    width
}

// ── brand mark (draw.ts:294 drawBrandLogo / :275 drawContain) ──────────────

pub fn draw_brand_logo(env: &mut Env, x: f32, y: f32, w: f32, h: f32, color: &str) -> bool {
    if !env.text.show_logo {
        return false;
    }
    if let Some(logo) = env.images.logo.clone() {
        let iw = logo.width() as f32;
        let ih = logo.height() as f32;
        let s = (w / iw).min(h / ih);
        let dw = iw * s;
        let dh = ih * s;
        let dx = x + (w - dw) / 2.0;
        let dy = y + (h - dh) / 2.0;
        let halve = std::env::var("E4H_LOGO_HALVE").ok().as_deref() != Some("0");
        let trilinear = std::env::var("E4H_LOGO_TRILINEAR").ok().as_deref() != Some("0");
        // Default: floor(lod) — the level whose size is still >= the target —
        // measured best on all five logo cards (round regresses glass-card and
        // vendor-hasselblad, trilinear/blend regresses all).
        let kernel = std::env::var("E4H_LOGO_KERNEL").unwrap_or_else(|_| "mip".into());
        if halve && kernel == "lanczos3" && s < 0.75 {
            let dwi = dw.max(1.0).round() as usize;
            let dhi = dh.max(1.0).round() as usize;
            if let Some(img) = resample_lanczos3(&logo, dwi, dhi) {
                let mut o = SamplingOptions::default();
                o.filter = FilterMode::Linear;
                env.ctx.draw_image(
                    &img,
                    Some(Rect::from_xywh(0.0, 0.0, dwi as f32, dhi as f32)),
                    Rect::from_xywh(dx, dy, dw, dh),
                    o,
                    "logo",
                );
                return true;
            }
        }
        let mode = std::env::var("E4H_LOGO_MODE").unwrap_or_else(|_| "floor".into());
        if halve && mode != "off" && s < 0.75 {
            let levels = pyramid_levels(&logo, dw.max(1.0), dh.max(1.0));
            if levels.len() >= 2 {
                let lod = (iw / dw.max(0.001)).log2().max(0.0);
                if std::env::var("E4H_LOGO_DEBUG").ok().as_deref() == Some("1") {
                    eprintln!(
                        "logo.debug iw={iw} ih={ih} dw={dw} dh={dh} lod={lod:.3} lod_h={:.3} levels={}",
                        (ih / dh.max(0.001)).log2(),
                        levels.len()
                    );
                }
                match mode.as_str() {
                    "trilinear" => {
                        let l0 = (lod.floor() as usize).min(levels.len() - 1);
                        let frac = if l0 + 1 < levels.len() {
                            lod - lod.floor()
                        } else {
                            0.0
                        };
                        let lo = &levels[l0];
                        let hi = &levels[(l0 + 1).min(levels.len() - 1)];
                        env.ctx.draw_image_trilinear(
                            lo,
                            hi,
                            frac,
                            Rect::from_xywh(dx, dy, dw, dh),
                            "logo",
                        );
                    }
                    "floor" | "round" | "roundh" => {
                        let lod_h = (ih / dh.max(0.001)).log2().max(0.0);
                        let l = match mode.as_str() {
                            "roundh" => lod_h.round() as usize,
                            "round" => lod.round() as usize,
                            _ => lod.floor() as usize,
                        };
                        let l = l.min(levels.len() - 1);
                        let img = &levels[l];
                        let (sw, sh) = (img.width() as f32, img.height() as f32);
                        let mut o = SamplingOptions::default();
                        match std::env::var("E4H_LOGO_SAMP").ok().as_deref() {
                            Some("cubic") => {
                                o.use_cubic = true;
                                o.cubic = skia_safe::CubicResampler::mitchell();
                            }
                            Some("catmull") => {
                                o.use_cubic = true;
                                o.cubic = skia_safe::CubicResampler::catmull_rom();
                            }
                            Some("nearest") => {}
                            _ => o.filter = FilterMode::Linear,
                        }
                        env.ctx.draw_image(
                            img,
                            Some(Rect::from_xywh(0.0, 0.0, sw, sh)),
                            Rect::from_xywh(dx, dy, dw, dh),
                            o,
                            "logo",
                        );
                    }
                    _ => {}
                }
                return true;
            }
        }
        let img = if halve && s < 0.5 {
            pyramid_down(&logo, dw.max(1.0), dh.max(1.0))
        } else {
            logo.clone()
        };
        let (sw, sh) = (img.width() as f32, img.height() as f32);
        env.ctx.draw_image(
            &img,
            Some(Rect::from_xywh(0.0, 0.0, sw, sh)),
            Rect::from_xywh(dx, dy, dw, dh),
            match std::env::var("E4H_LOGO_FINAL").ok().as_deref() {
                Some("cubic") => {
                    let mut o = SamplingOptions::default();
                    o.use_cubic = true;
                    o.cubic = skia_safe::CubicResampler::mitchell();
                    o
                }
                _ => {
                    let mut o = SamplingOptions::default();
                    o.filter = FilterMode::Linear;
                    o
                }
            },
            "logo",
        );
        return true;
    }
    let text = format!("{}{}", env.exif.make, env.exif.model_without_make()).to_uppercase();
    if text.trim().is_empty() {
        return false;
    }
    let size = h.min(w / 4.0_f32.max(text.chars().count() as f32 * 0.58));
    draw_text_exact(
        env,
        &text,
        x + w / 2.0,
        y + h / 2.0,
        size,
        color,
        "Inter",
        800,
        "center",
        "middle",
        0.0,
        false,
        None,
    );
    true
}

fn lanczos3(x: f32) -> f32 {
    if x == 0.0 {
        return 1.0;
    }
    if x.abs() >= 3.0 {
        return 0.0;
    }
    let px = std::f32::consts::PI * x;
    let v = 3.0 * px.sin() * (px / 3.0).sin() / (px * px);
    v
}

/// Separable Lanczos3 downscale (premultiplied f32). Blink never offers a
/// Lanczos kernel; this is an A/B candidate for the logo resampling gap.
fn resample_lanczos3(src: &Image, dw: usize, dh: usize) -> Option<Image> {
    let (sw, sh) = (src.width() as usize, src.height() as usize);
    if dw == 0 || dh == 0 || dw > sw || dh > sh {
        return None;
    }
    let info = skia_safe::ImageInfo::new(
        (sw as i32, sh as i32),
        skia_safe::ColorType::RGBA8888,
        skia_safe::AlphaType::Premul,
        Some(skia_safe::ColorSpace::new_srgb()),
    );
    let mut data = vec![0u8; sw * sh * 4];
    if !src.read_pixels(
        &info,
        &mut data,
        sw * 4,
        (0, 0),
        skia_safe::image::CachingHint::Allow,
    ) {
        return None;
    }
    let f: Vec<f32> = data.iter().map(|v| *v as f32 / 255.0).collect();
    let mut tmp = vec![0.0f32; dw * sh * 4];
    let scale_x = sw as f32 / dw as f32;
    let support_x = 3.0 * scale_x;
    for y in 0..sh {
        for x in 0..dw {
            let center = (x as f32 + 0.5) * scale_x;
            let start = ((center - support_x).floor() as isize).max(0) as usize;
            let end = ((center + support_x).ceil() as isize).min(sw as isize - 1) as usize;
            let mut acc = [0.0f32; 4];
            let mut sum = 0.0f32;
            for sx in start..=end {
                let wgt = lanczos3((sx as f32 + 0.5 - center) / scale_x);
                if wgt == 0.0 {
                    continue;
                }
                sum += wgt;
                let i = (y * sw + sx) * 4;
                for k in 0..4 {
                    acc[k] += f[i + k] * wgt;
                }
            }
            if sum != 0.0 {
                let o = (y * dw + x) * 4;
                for k in 0..4 {
                    tmp[o + k] = acc[k] / sum;
                }
            }
        }
    }
    let mut out = vec![0.0f32; dw * dh * 4];
    let scale_y = sh as f32 / dh as f32;
    let support_y = 3.0 * scale_y;
    for x in 0..dw {
        for y in 0..dh {
            let center = (y as f32 + 0.5) * scale_y;
            let start = ((center - support_y).floor() as isize).max(0) as usize;
            let end = ((center + support_y).ceil() as isize).min(sh as isize - 1) as usize;
            let mut acc = [0.0f32; 4];
            let mut sum = 0.0f32;
            for sy in start..=end {
                let wgt = lanczos3((sy as f32 + 0.5 - center) / scale_y);
                if wgt == 0.0 {
                    continue;
                }
                sum += wgt;
                let i = (sy * dw + x) * 4;
                for k in 0..4 {
                    acc[k] += tmp[i + k] * wgt;
                }
            }
            if sum != 0.0 {
                let o = (y * dw + x) * 4;
                for k in 0..4 {
                    out[o + k] = acc[k] / sum;
                }
            }
        }
    }
    let bytes: Vec<u8> = out
        .iter()
        .map(|v| (v * 255.0).round().clamp(0.0, 255.0) as u8)
        .collect();
    let info2 = skia_safe::ImageInfo::new(
        (dw as i32, dh as i32),
        skia_safe::ColorType::RGBA8888,
        skia_safe::AlphaType::Premul,
        Some(skia_safe::ColorSpace::new_srgb()),
    );
    skia_safe::images::raster_from_data(&info2, skia_safe::Data::new_copy(&bytes), dw * 4)
}

/// Mip levels from full resolution down to the first level <= the target width.
fn pyramid_levels(src: &Image, target_w: f32, target_h: f32) -> Vec<Image> {
    let mut levels = vec![src.clone()];
    let mut img = src.clone();
    let (mut w, mut h) = (img.width() as f32, img.height() as f32);
    let _ = (target_w, target_h);
    while w > 1.0 && h > 1.0 {
        let nw = (w / 2.0).round().max(1.0);
        let nh = (h / 2.0).round().max(1.0);
        let mut surface = crate::gpu::raster_surface(nw as i32, nh as i32);
        {
            let canvas = surface.canvas();
            canvas.clear(Color::TRANSPARENT);
            let mut p = skia_safe::Paint::default();
            p.set_anti_alias(true);
            let mut samp = SamplingOptions::default();
            samp.filter = FilterMode::Linear;
            canvas.draw_image_rect_with_sampling_options(
                &img,
                Some((
                    &Rect::from_xywh(0.0, 0.0, w, h),
                    skia_safe::canvas::SrcRectConstraint::Strict,
                )),
                &Rect::from_xywh(0.0, 0.0, nw, nh),
                samp,
                &p,
            );
        }
        img = surface.image_snapshot();
        w = nw;
        h = nh;
        levels.push(img.clone());
        if levels.len() > 12 {
            break;
        }
    }
    levels
}

/// Box-halving pyramid: Blink's high-quality downscale walks a mip chain, so a
/// one-step bilinear 9x reduction aliases differently from the oracle.
fn pyramid_down(src: &Image, target_w: f32, target_h: f32) -> Image {
    let mut img = src.clone();
    let (mut w, mut h) = (img.width() as f32, img.height() as f32);
    let stop = std::env::var("E4H_LOGO_STOP")
        .ok()
        .and_then(|v| v.parse::<f32>().ok())
        .unwrap_or(2.0);
    while w > target_w * stop && h > target_h * stop {
        let nw = (w / 2.0).round().max(1.0);
        let nh = (h / 2.0).round().max(1.0);
        let mut surface = crate::gpu::raster_surface(nw as i32, nh as i32);
        {
            let canvas = surface.canvas();
            canvas.clear(Color::TRANSPARENT);
            let mut p = skia_safe::Paint::default();
            p.set_anti_alias(true);
            let mut samp = SamplingOptions::default();
            samp.filter = FilterMode::Linear;
            if std::env::var("E4H_LOGO_STEP").ok().as_deref() == Some("cubic") {
                samp.use_cubic = true;
                samp.cubic = skia_safe::CubicResampler::mitchell();
            }
            canvas.draw_image_rect_with_sampling_options(
                &img,
                Some((
                    &Rect::from_xywh(0.0, 0.0, w, h),
                    skia_safe::canvas::SrcRectConstraint::Strict,
                )),
                &Rect::from_xywh(0.0, 0.0, nw, nh),
                samp,
                &p,
            );
        }
        img = surface.image_snapshot();
        w = nw;
        h = nh;
    }
    img
}

// ── lines ──────────────────────────────────────────────────────────────────

pub fn line(
    ctx: &mut Ctx2D,
    x1: f32,
    y1: f32,
    x2: f32,
    y2: f32,
    color: &str,
    width: f32,
    dash: Option<&[f32]>,
) {
    ctx.stroke_line(x1, y1, x2, y2, color, width, dash);
}

// ── EXIF lines (templates.ts:2049 techExifLine / :2059 classicExifLine) ────

pub fn tech_exif_line(env: &Env) -> String {
    let e = env.exif;
    let mut parts: Vec<String> = Vec::new();
    if let Some(f) = e.focal {
        parts.push(format!("{}mm", js_round(f)));
    }
    if let Some(s) = e.shutter {
        parts.push(crate::hand::format_shutter(s));
    }
    if let Some(a) = e.aperture {
        parts.push(crate::hand::format_aperture(a));
    }
    if let Some(i) = e.iso {
        parts.push(format!("ISO {}", crate::jsval::format_js_number(i)));
    }
    parts.join("   ")
}

pub fn classic_exif_line(env: &Env) -> String {
    let e = env.exif;
    let mut parts: Vec<String> = Vec::new();
    if let Some(f) = e.focal {
        parts.push(format!("{}mm", js_round(f)));
    }
    if let Some(a) = e.aperture {
        parts.push(crate::hand::format_aperture(a));
    }
    if let Some(i) = e.iso {
        parts.push(format!("ISO{}", crate::jsval::format_js_number(i)));
    }
    if let Some(s) = e.shutter {
        parts.push(crate::hand::format_shutter(s));
    }
    parts.join("  ")
}

// ── tech text (templates.ts:2069 drawTechText) ─────────────────────────────

#[allow(clippy::too_many_arguments)]
pub fn draw_tech_text(
    env: &mut Env,
    text: &str,
    cx: f32,
    cy: f32,
    color: &str,
    size: f32,
    target_w: f32,
    align: &str,
    alpha: f32,
    scale_x: f32,
    slot: &str,
) {
    env.ctx.save();
    env.ctx.set_global_alpha(alpha);
    env.ctx.translate(cx, cy);
    if scale_x != 1.0 {
        env.ctx.scale(scale_x, 1.0);
    }
    let opts = FittedOpts::new("Chakra Petch")
        .weight(600)
        .align(align)
        .baseline("middle")
        .tracking(0.03)
        .min(size * 0.7)
        .shadow(color == "#ffffff")
        .slot(slot);
    draw_fitted(env, text, 0.0, 0.0, target_w, size, color, &opts);
    env.ctx.restore();
}

fn blur_src_sampling(src_w: f32, src_h: f32, dst_w: f32, dst_h: f32) -> SamplingOptions {
    match std::env::var("E4H_BLUR_SRC").ok().as_deref() {
        Some("mipmap") => SamplingOptions::new(FilterMode::Linear, skia_safe::MipmapMode::Linear),
        Some("cubic") => {
            let mut s = SamplingOptions::default();
            s.use_cubic = true;
            s.cubic = skia_safe::CubicResampler::mitchell();
            s
        }
        Some("linear") => {
            let mut s = SamplingOptions::default();
            s.filter = FilterMode::Linear;
            s
        }
        Some("nearest") => SamplingOptions::default(),
        _ => {
            // 8x downscale with high quality: Blink builds a mip chain; plain
            // bilinear aliases. A/B switch above; auto = mipmap-linear.
            let _ = (src_w, src_h, dst_w, dst_h);
            SamplingOptions::new(FilterMode::Linear, skia_safe::MipmapMode::Linear)
        }
    }
}

// ── blurred backdrop (draw.ts:222 drawBlurredBackdrop) ─────────────────────

pub fn draw_blurred_backdrop(env: &mut Env, w: f32, h: f32, theme: &Theme, tint_alpha: f32) {
    let blur_pct = pct(env.params, "blur", 26.0);
    let no_blur = !(blur_pct > 0.0);
    let blur_div = 7.0 * (blur_pct / 26.0);
    let small_w = if no_blur {
        w.max(1.0)
    } else {
        420.0_f64
            .min(160.0_f64.max(js_round(w as f64 / blur_div)))
            .max(1.0) as f32
    };
    let small_h = if no_blur {
        h.max(1.0)
    } else {
        (80.0_f64).max(js_round(small_w as f64 * (h as f64 / w as f64))) as f32
    };
    let crop = env.crop;
    let scale = (small_w / crop.sw).max(small_h / crop.sh);
    let sw = small_w / scale;
    let sh = small_h / scale;
    let sx = crop.sx + (crop.sw - sw) / 2.0;
    let sy = crop.sy + (crop.sh - sh) / 2.0;
    let over = 1.12;
    if no_blur {
        env.ctx.draw_photo_image(
            env.photo,
            Rect::from_xywh(sx, sy, sw, sh),
            Rect::from_xywh(
                -w * (over - 1.0) / 2.0,
                -h * (over - 1.0) / 2.0,
                w * over,
                h * over,
            ),
            image_sampling_at(
                sw,
                sh,
                Rect::from_xywh(
                    -w * (over - 1.0) / 2.0,
                    -h * (over - 1.0) / 2.0,
                    w * over,
                    h * over,
                ),
            ),
            "backdrop",
        );
    } else {
        // Frozen: a fresh 2D canvas, cover-drawn photo, then drawn back through
        // a CSS blur filter. Canvas `filter: blur(Npx)` uses sigma = N.
        let small_iw = small_w.max(1.0) as i32;
        let small_ih = small_h.max(1.0) as i32;
        let backdrop_src = env.ctx.photo_src_rect(Rect::from_xywh(sx, sy, sw, sh));
        let mut surface = crate::gpu::raster_surface(small_iw, small_ih);
        {
            let canvas = surface.canvas();
            canvas.clear(Color::TRANSPARENT);
            let mut paint = skia_safe::Paint::default();
            paint.set_anti_alias(true);
            canvas.draw_image_rect_with_sampling_options(
                env.photo,
                Some((
                    &backdrop_src,
                    skia_safe::canvas::SrcRectConstraint::Strict,
                )),
                &Rect::from_xywh(0.0, 0.0, small_w, small_h),
                blur_src_sampling(sw, sh, small_w, small_h),
                &paint,
            );
        }
        let image = surface.image_snapshot();
        let k = std::env::var("E4H_BLUR_SIGMA")
            .ok()
            .and_then(|v| v.parse::<f32>().ok())
            .unwrap_or(1.0);
        let blur = (env.pw.min(env.ph) * 0.0015 * blur_pct as f32 * k).max(1.0);
        let tile = match std::env::var("E4H_BLUR_TILE").as_deref() {
            Ok("decal") => TileMode::Decal,
            _ => TileMode::Clamp,
        };
        let filter = image_filters::blur((blur, blur), tile, None, None);
        let dst = Rect::from_xywh(
            -w * (over - 1.0) / 2.0,
            -h * (over - 1.0) / 2.0,
            w * over,
            h * over,
        );
        let samp = image_sampling_at(small_w, small_h, dst);
        let use_layer = std::env::var("E4H_BLUR_LAYER").ok().as_deref() != Some("0");
        match (filter, use_layer) {
            (Some(f), true) => env
                .ctx
                .draw_image_layer_filtered(&image, None, dst, samp, f, "backdrop"),
            (Some(f), false) => {
                env.ctx
                    .draw_image_filtered(&image, None, dst, samp, Some(f), "backdrop")
            }
            (None, _) => env.ctx.draw_image(&image, None, dst, samp, "backdrop"),
        }
    }
    env.ctx.set_global_alpha(tint_alpha);
    env.ctx.fill_rect_color(0.0, 0.0, w, h, &theme.bg);
    env.ctx.set_global_alpha(1.0);
    env.ctx
        .record_underlay("backdrop", Rect::from_xywh(0.0, 0.0, w, h), None);
}

/// The 360-px-wide cover photo the glass cards blur behind their panel
/// (`drawPhotoCover` into a temp canvas at `tmpW`).
pub fn cover_temp_image(env: &Env, tmp_w: usize) -> (skia_safe::Image, f32, f32) {
    let tw = tmp_w.max(1);
    let th = (80.0_f64).max(js_round(tw as f64 * (env.h as f64 / env.w as f64))) as usize;
    let crop = env.crop;
    let scale = (tw as f32 / crop.sw).max(th as f32 / crop.sh);
    let sw = tw as f32 / scale;
    let sh = th as f32 / scale;
    let sx = crop.sx + (crop.sw - sw) / 2.0;
    let sy = crop.sy + (crop.sh - sh) / 2.0;
    let cover_src = env.ctx.photo_src_rect(Rect::from_xywh(sx, sy, sw, sh));
    let mut surface = crate::gpu::raster_surface(tw as i32, th as i32);
    {
        let canvas = surface.canvas();
        canvas.clear(Color::TRANSPARENT);
        let mut paint = skia_safe::Paint::default();
        paint.set_anti_alias(true);
        canvas.draw_image_rect_with_sampling_options(
            env.photo,
            Some((
                &cover_src,
                skia_safe::canvas::SrcRectConstraint::Strict,
            )),
            &Rect::from_xywh(0.0, 0.0, tw as f32, th as f32),
            {
                // Chrome's default imageSmoothingQuality ('low') = bilinear for
                // the 360px temp copy.
                let mut samp = SamplingOptions::default();
                samp.filter = FilterMode::Linear;
                samp
            },
            &paint,
        );
    }
    (surface.image_snapshot(), tw as f32, th as f32)
}

/// The frozen glass panel: tint gradient, round-rect clip, the 360-px cover
/// drawn back through `filter: blur(...)`, a flat white wash, a sheen, then the
/// stroke and the top highlight.
#[allow(clippy::too_many_arguments)]
pub fn draw_glass_panel(
    env: &mut Env,
    x: f32,
    y: f32,
    w: f32,
    h: f32,
    radius: f32,
    tint_top: &str,
    tint_bottom: &str,
    white_alpha: f32,
    blur_px: f32,
    stroke_px: f32,
) {
    let s = env.pw.min(env.ph);
    let panel = Rect::from_xywh(x, y, w, h);
    env.ctx.fill_linear_gradient(
        x,
        y,
        x,
        y + h,
        &[(0.0, tint_top), (1.0, tint_bottom)],
        panel,
    );
    env.ctx.clip_round_rect(x, y, w, h, radius);
    let (img, tw, th) = cover_temp_image(env, 360);
    let src = Rect::from_xywh(
        (x / env.w) * tw,
        (y / env.h) * th,
        (w / env.w) * tw,
        (h / env.h) * th,
    );
    if let Some(f) = image_filters::blur(
        (blur_px.max(1.0), blur_px.max(1.0)),
        TileMode::Clamp,
        None,
        None,
    ) {
        let mut samp = SamplingOptions::default();
        samp.filter = FilterMode::Linear;
        env.ctx
            .draw_image_layer_filtered(&img, Some(src), panel, samp, f, "backdrop");
    }
    env.ctx
        .fill_rect_color(x, y, w, h, &format!("rgba(255,255,255,{white_alpha})"));
    env.ctx.fill_linear_gradient(
        x,
        y,
        x,
        y + h,
        &[
            (0.0, "rgba(255,255,255,0.22)"),
            (0.35, "rgba(255,255,255,0.04)"),
            (1.0, "rgba(255,255,255,0)"),
        ],
        panel,
    );
    env.ctx.restore_clip();
    env.ctx.stroke_round_rect(
        x,
        y,
        w,
        h,
        radius,
        "rgba(255,255,255,0.42)",
        stroke_px,
        None,
    );
    line(
        &mut env.ctx,
        x + radius,
        y + 1.0,
        x + w - radius,
        y + 1.0,
        "rgba(255,255,255,0.5)",
        (s * 0.001).max(1.0),
        None,
    );
    env.ctx.record_shape("glass-panel", panel, None, true);
}

// ── sprockets / barcode / grain (draw.ts:627/650/600) ───────────────────────
pub fn draw_barcode(ctx: &mut Ctx2D, x: f32, y: f32, w: f32, h: f32, color: &str, seed_text: &str) {
    let mut seed: u32 = 0;
    for ch in seed_text.chars() {
        seed = seed.wrapping_mul(31).wrapping_add(ch as u32);
    }
    let mut cx = x;
    while cx < x + w {
        seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
        let bw = 1.0 + (seed % 4) as f32;
        if (seed & 7) > 2 {
            ctx.fill_rect_color(cx, y, bw.min(x + w - cx), h, color);
        }
        // JS uses a SIGNED >> here: a seed >= 2^31 makes (seed >> 4) % 3
        // negative, so cx can step backwards. Replicate i32 semantics.
        let adv = ((seed as i32) >> 4) % 3;
        cx += bw + 1.0 + adv as f32;
    }
    ctx.record_shape(
        "barcode",
        Rect::from_xywh(x, y, w, h),
        Some(color.to_string()),
        true,
    );
}

pub fn draw_sprockets(ctx: &mut Ctx2D, x: f32, y: f32, w: f32, h: f32, count: usize, color: &str) {
    let gap = w / count as f32;
    let hole_w = gap * 0.42;
    let hole_h = h * 0.42;
    for i in 0..count {
        let cx = x + i as f32 * gap + gap / 2.0;
        ctx.fill_round_rect(
            cx - hole_w / 2.0,
            y + (h - hole_h) / 2.0,
            hole_w,
            hole_h,
            hole_h * 0.22,
            color,
        );
    }
    ctx.record_shape(
        "sprockets",
        Rect::from_xywh(x, y, w, h),
        Some(color.to_string()),
        true,
    );
}

pub fn draw_grain(ctx: &mut Ctx2D, w: f32, h: f32, opacity: f32) {
    if opacity <= 0.0 || std::env::var("E4H_NO_GRAIN").ok().as_deref() == Some("1") {
        return;
    }
    let s = 128usize;
    let mut buf = vec![0u8; s * s * 4];
    let mut seed: u32 = 1337;
    for i in 0..s * s {
        seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
        let v = (seed & 255) as u8;
        buf[i * 4] = v;
        buf[i * 4 + 1] = v;
        buf[i * 4 + 2] = v;
        buf[i * 4 + 3] = 255;
    }
    let info = skia_safe::ImageInfo::new(
        (s as i32, s as i32),
        skia_safe::ColorType::RGBA8888,
        skia_safe::AlphaType::Unpremul,
        Some(skia_safe::ColorSpace::new_srgb()),
    );
    let data = skia_safe::Data::new_copy(&buf);
    let Some(img) = skia_safe::images::raster_from_data(&info, data, s * 4) else {
        ctx.record_gap("grain image build failed".into());
        return;
    };
    ctx.fill_image_pattern_overlay(&img, Rect::from_xywh(0.0, 0.0, w, h), opacity);
    ctx.record_underlay("grain", Rect::from_xywh(0.0, 0.0, w, h), None);
}

pub fn measure_text(
    env: &mut Env,
    text: &str,
    family: &str,
    weight: u16,
    size: f32,
    tracking_px: f32,
) -> f32 {
    match pick_face(env.fonts, &mut env.ctx, family, weight) {
        Some(p) => {
            let text = ensure_text_glyphs(env, text, family, weight, &p);
            env.te.shape(&text, &p.tf, size, tracking_px).1
        }
        None => 0.0,
    }
}

// ── vertical text (draw.ts:668) ────────────────────────────────────────────

#[allow(clippy::too_many_arguments)]
pub fn draw_vertical_text(
    env: &mut Env,
    text: &str,
    x: f32,
    y: f32,
    size: f32,
    color: &str,
    family: &str,
    weight: u16,
    tracking_em: f64,
) {
    draw_vertical_text_inner(
        env,
        text,
        x,
        y,
        size,
        color,
        family,
        weight,
        tracking_em,
        None,
    );
}

#[allow(clippy::too_many_arguments)]
pub fn draw_vertical_text_slot(
    env: &mut Env,
    text: &str,
    x: f32,
    y: f32,
    size: f32,
    color: &str,
    family: &str,
    weight: u16,
    tracking_em: f64,
    slot: &str,
) {
    draw_vertical_text_inner(
        env,
        text,
        x,
        y,
        size,
        color,
        family,
        weight,
        tracking_em,
        Some(slot),
    );
}

#[allow(clippy::too_many_arguments)]
fn draw_vertical_text_inner(
    env: &mut Env,
    text: &str,
    x: f32,
    y: f32,
    size: f32,
    color: &str,
    family: &str,
    weight: u16,
    tracking_em: f64,
    slot: Option<&str>,
) {
    env.ctx.save();
    env.ctx.translate(x, y);
    env.ctx.rotate(-std::f32::consts::FRAC_PI_2);
    draw_text_exact_with_slot_internal(
        env,
        text,
        0.0,
        0.0,
        size,
        color,
        family,
        weight,
        "left",
        "middle",
        (tracking_em as f32) * size,
        false,
        None,
        slot,
    );
    env.ctx.restore();
}

// ── palette (draw.ts:532 extractPalette / :476 drawSwatches) ───────────────

pub fn draw_swatches(
    env: &mut Env,
    colors: &[String],
    x: f32,
    y: f32,
    max_width: f32,
    size: f32,
    label_color: &str,
    spread: bool,
    slot_prefix: &str,
) {
    let gap = size * 0.34;
    let n = colors.len();
    let total = n as f32 * size + (n as f32 - 1.0) * gap;
    let spread = spread && n > 1;
    let start = if spread {
        x + size / 2.0
    } else {
        x + (max_width - total).max(0.0) / 2.0
    };
    let step = if spread {
        (max_width - size) / (n as f32 - 1.0)
    } else {
        size + gap
    };
    const SWATCH_SLOT_IDS: [&str; 8] = [
        "cell-01.label",
        "cell-02.label",
        "cell-03.label",
        "cell-04.label",
        "cell-05.label",
        "cell-06.label",
        "cell-07.label",
        "cell-08.label",
    ];
    for (i, c) in colors.iter().enumerate() {
        let cx = if spread {
            start + i as f32 * step
        } else {
            start + i as f32 * (size + gap) + size / 2.0
        };
        env.ctx.fill_circle(cx, y + size / 2.0, size / 2.0, c);
        if let Some(cell_slot) = SWATCH_SLOT_IDS.get(i) {
            let slot = format!("{slot_prefix}.{cell_slot}");
            draw_text_exact_slot(
                env,
                &c.replace('#', "").to_uppercase(),
                cx,
                y + size + size * 0.22,
                size * 0.3,
                label_color,
                "JetBrains Mono",
                400,
                "center",
                "top",
                0.0,
                false,
                None,
                &slot,
            );
        } else {
            env.ctx.record_gap(format!(
                "palette has {} swatches but only {} stable label slots are declared",
                colors.len(),
                SWATCH_SLOT_IDS.len()
            ));
            draw_text_exact(
                env,
                &c.replace('#', "").to_uppercase(),
                cx,
                y + size + size * 0.22,
                size * 0.3,
                label_color,
                "JetBrains Mono",
                400,
                "center",
                "top",
                0.0,
                false,
                None,
            );
        }
    }
}

struct PaletteBucket {
    r: f64,
    g: f64,
    b: f64,
    n: f64,
}

fn js_bucket_key(r: f64, g: f64, b: f64) -> i32 {
    (((r as i64 >> 4) << 8) | ((g as i64 >> 4) << 4) | (b as i64 >> 4)) as i32
}

/// Port of draw.ts extractPalette. The frozen bucket lookup recomputes the key
/// from each bucket's RUNNING SUM (not its first pixel), so a bucket stops
/// matching once it grows — replicated bit for bit.
pub fn extract_palette(env: &mut Env, count: usize) -> Vec<String> {
    let w = 48usize;
    let h = (w as f64 * (env.ph as f64 / env.pw as f64))
        .round()
        .max(24.0) as usize;
    if let Some((buf, pw48, ph48)) = env.palette48 {
        if *pw48 == w && *ph48 == h {
            return palette_from_rgba(buf, w, h, count);
        }
    }
    let crop = env.crop;
    let mut surface = crate::gpu::raster_surface(w as i32, h as i32);
    {
        let canvas = surface.canvas();
        canvas.clear(Color::TRANSPARENT);
        let mut p = skia_safe::Paint::default();
        p.set_anti_alias(true);
        // Fresh 2D canvas in the frozen code: default smoothing (low) = linear.
        let mut samp = SamplingOptions::default();
        samp.filter = FilterMode::Linear;
        match std::env::var("E4H_PALETTE_SRC").ok().as_deref() {
            Some("nearest") => samp = SamplingOptions::default(),
            Some("cubic") => {
                samp.use_cubic = true;
                samp.cubic = skia_safe::CubicResampler::mitchell();
            }
            Some("mipmap") => {
                samp = SamplingOptions::new(FilterMode::Linear, skia_safe::MipmapMode::Linear)
            }
            _ => {}
        }
        let src_img;
        let img = if std::env::var("E4H_PALETTE_SRC").ok().as_deref() == Some("mipmap") {
            src_img = env
                .photo
                .with_default_mipmaps()
                .unwrap_or_else(|| env.photo.clone());
            &src_img
        } else {
            env.photo
        };
        let palette_src = env
            .ctx
            .photo_src_rect(Rect::from_xywh(crop.sx, crop.sy, crop.sw, crop.sh));
        canvas.draw_image_rect_with_sampling_options(
            img,
            Some((&palette_src, skia_safe::canvas::SrcRectConstraint::Strict)),
            &Rect::from_xywh(0.0, 0.0, w as f32, h as f32),
            samp,
            &p,
        );
    }
    let info = skia_safe::ImageInfo::new(
        (w as i32, h as i32),
        skia_safe::ColorType::RGBA8888,
        skia_safe::AlphaType::Unpremul,
        Some(skia_safe::ColorSpace::new_srgb()),
    );
    let mut data = vec![0u8; w * h * 4];
    let img = surface.image_snapshot();
    if !img.read_pixels(
        &info,
        &mut data,
        w * 4,
        (0, 0),
        skia_safe::image::CachingHint::Allow,
    ) {
        env.ctx.record_gap("palette read_pixels failed".into());
        return vec![
            "#2b2b2b".into(),
            "#6f6a61".into(),
            "#a99f8c".into(),
            "#d6cfc1".into(),
            "#f2eee6".into(),
        ];
    }
    // Diagnostics only: writing the bucketed buffer is what lets the sampling
    // model be diffed against Chromium's. Requires the env var, so the default
    // render path is untouched.
    if let Ok(p) = std::env::var("E4H_PALETTE_DUMP") {
        if !p.trim().is_empty() {
            let _ = std::fs::write(&p, &data);
        }
    }
    palette_from_rgba(&data, w, h, count)
}

fn palette_from_rgba(data: &[u8], w: usize, h: usize, count: usize) -> Vec<String> {
    let mut buckets: Vec<PaletteBucket> = Vec::new();
    for i in (0..w * h).map(|i| i * 4) {
        let (r, g, b, a) = (
            data[i] as f64,
            data[i + 1] as f64,
            data[i + 2] as f64,
            data[i + 3] as f64,
        );
        if a < 200.0 {
            continue;
        }
        let max = r.max(g).max(b);
        let min = r.min(g).min(b);
        let l = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
        if l > 0.96 || l < 0.035 {
            continue;
        }
        if max - min < 6.0 && l > 0.9 {
            continue;
        }
        let key = js_bucket_key(r, g, b);
        if let Some(found) = buckets
            .iter_mut()
            .find(|x| js_bucket_key(x.r, x.g, x.b) == key)
        {
            found.r += r;
            found.g += g;
            found.b += b;
            found.n += 1.0;
        } else {
            buckets.push(PaletteBucket { r, g, b, n: 1.0 });
        }
    }
    buckets.sort_by(|a, b| b.n.partial_cmp(&a.n).unwrap_or(std::cmp::Ordering::Equal));
    let mut picked: Vec<String> = Vec::new();
    for b in &buckets {
        let c = (b.r / b.n, b.g / b.n, b.b / b.n);
        if !picked.is_empty() {
            let near = picked.iter().any(|hex| {
                let rr = i64::from_str_radix(&hex[1..3], 16).unwrap_or(0) as f64;
                let gg = i64::from_str_radix(&hex[3..5], 16).unwrap_or(0) as f64;
                let bb = i64::from_str_radix(&hex[5..7], 16).unwrap_or(0) as f64;
                (rr - c.0).hypot(gg - c.1).hypot(bb - c.2) < 45.0
            });
            if near {
                continue;
            }
        }
        picked.push(format!(
            "#{:02x}{:02x}{:02x}",
            c.0.round().clamp(0.0, 255.0) as u8,
            c.1.round().clamp(0.0, 255.0) as u8,
            c.2.round().clamp(0.0, 255.0) as u8
        ));
        if picked.len() >= count {
            break;
        }
    }
    while picked.len() < count {
        picked.push(if picked.len() % 2 == 0 {
            "#d8d3c8".into()
        } else {
            "#8f887c".into()
        });
    }
    picked
}

// ── simple footer metrics (watermark-layout.ts) ────────────────────────────

pub struct SimpleFooterMetrics {
    pub footer_h: f32,
    pub pad: f32,
    pub gap: f32,
    pub font_left: f32,
    pub font_right: f32,
    pub font_center: f32,
    pub logo_w: f32,
    pub logo_h: f32,
}

pub fn simple_footer_metrics(pw: f32, ph: f32, params: &Params) -> SimpleFooterMetrics {
    let long = pw.max(ph);
    let aspect = pw / ph.max(1.0);
    let scale = long / 3000.0;
    let text_scale = {
        let v = pct(params, "footerTextScale", 1.0) as f32;
        if v == 0.0 {
            1.0
        } else {
            v
        }
    };
    let auto_h = 0.1 * long - 5.0 + 80.0 * (aspect - 1.0).max(0.0);
    let min_h = 60.0_f32.max((54.0 + 2.0 * 96.0 * 0.6) * scale);
    let footer_h = (min_h.max(auto_h) * text_scale).ceil() + 1.0;
    SimpleFooterMetrics {
        footer_h,
        pad: 96.0 * scale,
        gap: 24.0 * scale,
        font_left: 56.0 * scale * text_scale,
        font_right: 52.0 * scale * text_scale,
        font_center: 52.0 * scale * text_scale,
        logo_w: 352.0 * scale * text_scale,
        logo_h: 54.0 * scale * text_scale,
    }
}

// ── small shared bits ──────────────────────────────────────────────────────

pub fn exif_text(env: &Env) -> String {
    if env.text.show_exif {
        env.exif.line("  ")
    } else {
        String::new()
    }
}

/// `titleOf()` (templates.ts:114)
pub fn title_of(env: &Env) -> String {
    if let Some(hidden) = &env.text.hidden {
        if hidden.iter().any(|t| t == "title") {
            return String::new();
        }
    }
    let t = env.text.title.trim();
    if !t.is_empty() {
        return t.to_string();
    }
    let make = if env.text.show_make {
        env.exif.make.trim()
    } else {
        ""
    };
    let model = if env.text.show_model {
        env.exif.model_without_make()
    } else {
        String::new()
    };
    let parts: Vec<&str> = [make, model.trim()]
        .into_iter()
        .filter(|s| !s.is_empty())
        .collect();
    if !parts.is_empty() {
        parts.join(" ")
    } else {
        env.exif.camera_line("FRAME")
    }
}

/// `footerTitle()` (templates.ts:106) — no invented placeholder when the photo
/// has no EXIF at all.
pub fn footer_title(env: &Env) -> String {
    let typed = env.text.title.trim();
    if !typed.is_empty() {
        return typed.to_string();
    }
    let e = env.exif;
    let has_info = !(e.make.is_empty()
        && e.model.is_empty()
        && e.lens.is_empty()
        && e.focal.is_none()
        && e.date_time.is_empty());
    if has_info {
        title_of(env)
    } else {
        String::new()
    }
}

pub fn date_text(env: &Env) -> String {
    if env.text.show_date {
        format_date_only(&env.exif.date_time)
    } else {
        String::new()
    }
}

pub fn ink_tops(size: f32, baseline: &str) -> f32 {
    if baseline == "middle" {
        size * 0.465
    } else {
        size * 0.72
    }
}
