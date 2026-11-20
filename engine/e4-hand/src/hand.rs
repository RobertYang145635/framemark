//! E4 hand-template data: the E1b-1 `engine/data/hand/*.json` records, the
//! frozen per-card `Design` (P2b-1 capture) and the comparison manifest built
//! by `tools/card-manifest.cjs`.
//!
//! The hand data is only a *record* of the frozen TS: `TemplateDef.layout` /
//! `render` bodies are code and are re-implemented in `templates/`. Everything
//! that is data (defaults, variant params/text, themes, the captured design)
//! is read from JSON here — nothing is re-typed into Rust.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::jsval;

pub type Params = BTreeMap<String, Value>;

fn de_params<'de, D>(d: D) -> Result<Params, D::Error>
where
    D: serde::Deserializer<'de>,
{
    Ok(BTreeMap::deserialize(d)?)
}

#[derive(Debug, Clone, Deserialize)]
pub struct HandTables {
    pub templates: Vec<HandDef>,
    pub variants: Vec<Variant>,
    pub themes: Value,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HandDef {
    pub id: String,
    pub name: String,
    pub category: String,
    #[serde(default)]
    pub defaults: Value,
    #[serde(default)]
    pub controls: Vec<String>,
    #[serde(default)]
    pub hand_template: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Variant {
    pub id: String,
    pub name: String,
    pub base: String,
    #[serde(default)]
    pub params: Value,
    #[serde(default)]
    pub text: Value,
    #[serde(default)]
    pub vendor_logo: Option<String>,
    #[serde(default)]
    pub auto_logo: bool,
}

impl HandDef {
    pub fn defaults_map(&self) -> Params {
        self.defaults
            .as_object()
            .cloned()
            .map(|m| m.into_iter().collect())
            .unwrap_or_default()
    }
}

impl Variant {
    pub fn params_map(&self) -> Params {
        self.params
            .as_object()
            .cloned()
            .map(|m| m.into_iter().collect())
            .unwrap_or_default()
    }
    pub fn text_map(&self) -> BTreeMap<String, Value> {
        self.text
            .as_object()
            .cloned()
            .map(|m| m.into_iter().collect())
            .unwrap_or_default()
    }
}

pub fn load_tables(hand_dir: &Path) -> Result<HandTables, String> {
    let read = |name: &str| -> Result<Value, String> {
        let p = hand_dir.join(name);
        let raw = std::fs::read_to_string(&p).map_err(|e| format!("read {}: {e}", p.display()))?;
        serde_json::from_str(&raw).map_err(|e| format!("parse {}: {e}", p.display()))
    };
    let t = read("templates.json")?;
    let v = read("variants.json")?;
    let th = read("themes.json")?;
    Ok(HandTables {
        templates: serde_json::from_value(
            t.get("templates").cloned().unwrap_or(Value::Array(vec![])),
        )
        .map_err(|e| format!("templates.json: {e}"))?,
        variants: serde_json::from_value(
            v.get("variants").cloned().unwrap_or(Value::Array(vec![])),
        )
        .map_err(|e| format!("variants.json: {e}"))?,
        themes: th.get("themes").cloned().unwrap_or(Value::Null),
    })
}

// ── frozen Design (P2b-1 capture) ───────────────────────────────────────────

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Design {
    pub template_id: String,
    #[serde(default)]
    pub variant_id: Option<String>,
    #[serde(default = "default_ratio")]
    pub ratio: String,
    /// Optional explicit fit switch (`"native"`); absent = template fit.
    #[serde(default)]
    pub fit_mode: Option<String>,
    #[serde(default, deserialize_with = "de_params")]
    pub params: Params,
    #[serde(default)]
    pub text: TextSettings,
    #[serde(default)]
    pub logo: Option<LogoAsset>,
    #[serde(default)]
    pub avatar: Option<LogoAsset>,
}

fn default_ratio() -> String {
    "original".into()
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct TextSettings {
    pub title: String,
    pub subtitle: String,
    pub signature: String,
    pub custom_line: String,
    pub location: String,
    #[serde(default = "default_true")]
    pub show_make: bool,
    #[serde(default = "default_true")]
    pub show_model: bool,
    #[serde(default = "default_true")]
    pub show_lens: bool,
    #[serde(default = "default_true")]
    pub show_exif: bool,
    #[serde(default = "default_true")]
    pub show_date: bool,
    #[serde(default = "default_true")]
    pub show_logo: bool,
    #[serde(default = "default_one")]
    pub font_scale: f64,
    pub hidden: Option<Vec<String>>,
    pub line_text: Option<BTreeMap<String, String>>,
    pub hidden_lines: Option<Vec<String>>,
    pub slot_values: Option<BTreeMap<String, String>>,
    pub slot_sizes: Option<BTreeMap<String, Value>>,
    pub line_sizes: Option<BTreeMap<String, Value>>,
}

impl Default for TextSettings {
    fn default() -> Self {
        // presets.ts DEFAULT_TEXT
        Self {
            title: String::new(),
            subtitle: String::new(),
            signature: String::new(),
            custom_line: String::new(),
            location: String::new(),
            show_make: true,
            show_model: true,
            show_lens: true,
            show_exif: true,
            show_date: true,
            show_logo: true,
            font_scale: 1.0,
            hidden: None,
            line_text: None,
            hidden_lines: None,
            slot_values: None,
            slot_sizes: None,
            line_sizes: None,
        }
    }
}

fn default_true() -> bool {
    true
}

fn default_one() -> f64 {
    1.0
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LogoAsset {
    pub name: String,
    pub width: f64,
    pub height: f64,
    #[serde(default)]
    pub data_url: String,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Exif {
    pub make: String,
    pub model: String,
    pub lens: String,
    pub focal: Option<f64>,
    pub focal_equivalent: Option<bool>,
    pub aperture: Option<f64>,
    pub shutter: Option<f64>,
    pub iso: Option<f64>,
    pub date_time: String,
    pub width: f64,
    pub height: f64,
    pub has_any: bool,
}

impl Exif {
    /// `exifLine()` in core/exif.ts: `366mm  F6.3  1/400s  ISO125`.
    pub fn line(&self, separator: &str) -> String {
        let mut parts = Vec::new();
        if let Some(f) = self.focal {
            parts.push(format!("{}mm", jsval::js_round(f)));
        }
        if let Some(a) = self.aperture {
            parts.push(format_aperture(a));
        }
        if let Some(s) = self.shutter {
            parts.push(format_shutter(s));
        }
        if let Some(i) = self.iso {
            parts.push(format!("ISO{}", jsval::format_js_number(i)));
        }
        parts.join(separator)
    }
    pub fn model_without_make(&self) -> String {
        model_without_make(&self.make, &self.model)
    }
    pub fn camera_line(&self, fallback: &str) -> String {
        let parts: Vec<String> = [self.make.trim(), self.model_without_make().trim()]
            .into_iter()
            .filter(|s| !s.is_empty())
            .map(|s| s.to_string())
            .collect();
        if parts.is_empty() {
            fallback.to_string()
        } else {
            parts.join("  ")
        }
    }
}

pub fn model_without_make(make: &str, model: &str) -> String {
    let m = make.trim();
    let d = model.trim();
    if d.is_empty() {
        return String::new();
    }
    if !m.is_empty()
        && d.to_lowercase()
            .starts_with(&format!("{} ", m.to_lowercase()))
    {
        return d[m.len()..].trim().to_string();
    }
    d.to_string()
}

pub fn format_aperture(v: f64) -> String {
    // 0 / NaN / inf 不是光圈值：一张没有 APP1 的照片以前会印成 `F0`（或 `Finf`）。
    if !v.is_finite() || v <= 0.0 {
        return String::new();
    }
    if v.fract() == 0.0 {
        format!("F{}", v as i64)
    } else {
        format!("F{:.1}", v)
    }
}

pub fn format_shutter(v: f64) -> String {
    // 同上：`1/inf`（0 s 曝光）是解析残渣，不是快门速度，宁可不印。
    if !v.is_finite() || v <= 0.0 {
        return String::new();
    }
    if v >= 1.0 {
        if v.fract() == 0.0 {
            format!("{}s", v as i64)
        } else {
            format!("{:.1}s", v)
        }
    } else {
        format!("1/{}s", jsval::js_round(1.0 / v))
    }
}

/// `formatDateOnly` / `formatDate` for the ISO-ish strings the capture stores.
/// The design's `exif.dateTime` is already `YYYY-MM-DD HH:MM:SS`; the frozen
/// app formats it through `new Date(...)` in the machine's local zone. The
/// capture was written by that same code path, so the digits pass through and
/// we only split them.
pub fn format_date_only(date_time: &str) -> String {
    let t = date_time.trim();
    if t.is_empty() {
        return String::new();
    }
    let head = t.split(|c: char| c == ' ' || c == 'T').next().unwrap_or("");
    if head.len() >= 10
        && head.as_bytes().get(4) == Some(&b'-')
        && head.as_bytes().get(7) == Some(&b'-')
    {
        return head[..10].to_string();
    }
    // EXIF style `YYYY:MM:DD HH:MM:SS`
    if head.len() >= 10
        && head.as_bytes().get(4) == Some(&b':')
        && head.as_bytes().get(7) == Some(&b':')
    {
        return format!("{}-{}-{}", &head[0..4], &head[5..7], &head[8..10]);
    }
    head.to_string()
}

/// `formatDate()` default style: "YYYY-MM-DD HH:MM:SS".
pub fn format_date_time(date_time: &str) -> String {
    let t = date_time.trim();
    if t.is_empty() {
        return String::new();
    }
    let normalized = t.replace('T', " ");
    let (date, rest) = match normalized.split_once(' ') {
        Some((d, r)) => (d, Some(r)),
        None => (normalized.as_str(), None),
    };
    let date = if date.len() >= 10 && date.as_bytes().get(4) == Some(&b':') {
        format!("{}-{}-{}", &date[0..4], &date[5..7], &date[8..10])
    } else {
        date.to_string()
    };
    match rest {
        Some(r) if !r.is_empty() => format!("{date} {r}"),
        _ => date,
    }
}

pub fn format_date_dot(date_time: &str) -> String {
    let d = format_date_only(date_time);
    d.replace('-', ".")
}

// ── crop (port of scene.ts computeCrop) ────────────────────────────────────

#[derive(Debug, Clone, Copy, Default)]
pub struct Crop {
    pub sx: f32,
    pub sy: f32,
    pub sw: f32,
    pub sh: f32,
    pub pw: f32,
    pub ph: f32,
}

/// The whole sensor, no centre crop: used by `--fit native`.
pub fn crop_full(image_w: f32, image_h: f32) -> Crop {
    Crop {
        sx: 0.0,
        sy: 0.0,
        sw: image_w,
        sh: image_h,
        pw: image_w,
        ph: image_h,
    }
}

/// How the photo is fitted into the template. `Design` is the frozen behaviour
/// (template aspect wins, the photo is resampled or cropped to it); `Native`
/// keeps every photo pixel and lets the canvas grow instead.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FitMode {
    Design,
    Native,
}

/// render-v1 photo crop (`photoCropX` / `photoCropY` / `photoCropZoom`).
///
/// ([cx], [cy]) is normalised to the source photo (`0.5` = the centred region
/// the engine keeps by itself) and [zoom] multiplies that base extent, so `1`
/// is the engine default and a larger value keeps a smaller region. All three
/// keys are omitted at their defaults, so a design without them is unchanged.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct UserCrop {
    pub cx: f32,
    pub cy: f32,
    pub zoom: f32,
}

impl Default for UserCrop {
    fn default() -> Self {
        Self { cx: 0.5, cy: 0.5, zoom: 1.0 }
    }
}

impl UserCrop {
    /// The design document's crop; absent or malformed values are the defaults.
    pub fn read(params: &Params) -> Self {
        let number = |key: &str| {
            params
                .get(key)
                .and_then(Value::as_f64)
                .filter(|n| n.is_finite())
                .map(|n| n as f32)
        };
        Self {
            cx: number("photoCropX").unwrap_or(0.5),
            cy: number("photoCropY").unwrap_or(0.5),
            zoom: number("photoCropZoom").unwrap_or(1.0),
        }
        .normalised()
    }

    /// True when this keeps exactly what the engine kept before the crop.
    pub fn is_default(&self) -> bool {
        self.cx == 0.5 && self.cy == 0.5 && self.zoom == 1.0
    }

    fn normalised(self) -> Self {
        let centre = |v: f32| if v.is_finite() { v.clamp(0.0, 1.0) } else { 0.5 };
        let zoom = if self.zoom.is_finite() { self.zoom.clamp(1.0, 8.0) } else { 1.0 };
        Self { cx: centre(self.cx), cy: centre(self.cy), zoom }
    }
}

/// `base` — the window the template's own ratio chose — narrowed to the region
/// the user kept: the extent shrinks by `zoom` and the origin is the user's
/// source point `(cx * image_w, cy * image_h)`, clamped to the **image** (not to
/// `base`), so dragging to the photo's own edge always reaches it even when the
/// template's ratio crop had already discarded that edge. `pw`/`ph` (the layout
/// box every template reads) never move. At the defaults this is `base`
/// untouched, so an untouched design renders exactly as before.
pub fn crop_window(base: Crop, image_w: f32, image_h: f32, user: UserCrop) -> Crop {
    let user = user.normalised();
    if user.is_default() {
        return base;
    }
    if !(image_w.is_finite() && image_h.is_finite() && image_w > 0.0 && image_h > 0.0) {
        return base;
    }
    if !(base.sw.is_finite() && base.sh.is_finite() && base.sw > 0.0 && base.sh > 0.0) {
        return base;
    }
    // `zoom >= 1`, so the extent only ever shrinks; the floor keeps a 1 px image
    // sampleable and the ceiling keeps the window inside the raster.
    let sw = (base.sw / user.zoom).min(image_w).max(1.0_f32.min(image_w));
    let sh = (base.sh / user.zoom).min(image_h).max(1.0_f32.min(image_h));
    let max_x = (image_w - sw).max(0.0);
    let max_y = (image_h - sh).max(0.0);
    let sx = (user.cx * image_w - sw / 2.0).clamp(0.0, max_x);
    let sy = (user.cy * image_h - sh / 2.0).clamp(0.0, max_y);
    Crop { sx, sy, sw, sh, ..base }
}

/// The `w` x `h` photo box's `cover` source rect inside `window`, as
/// `[x, y, w, h]` in source pixels.
///
/// `window` already carries the user's zoom ([crop_window]); this is where the
/// user's *position* lands. The box's cover rect has slack inside the window
/// whenever the window is wider/taller than the box samples (a ratio-cropped
/// template, or the whole photo), and the centred rect a plain cover draw uses
/// hides a pan there — so a non-default user crop centres the slice on
/// `(cx * image_w, cy * image_h)` and clamps it inside the window. At the
/// defaults this is byte-for-byte the centred rect every template got before.
pub fn cover_src(
    window: Crop,
    user: UserCrop,
    image_w: f32,
    image_h: f32,
    w: f32,
    h: f32,
) -> [f32; 4] {
    let scale = (w / window.sw).max(h / window.sh);
    if !(scale.is_finite() && scale > 0.0) {
        return [window.sx, window.sy, window.sw, window.sh];
    }
    let sw = w / scale;
    let sh = h / scale;
    let mut sx = window.sx + (window.sw - sw) / 2.0;
    let mut sy = window.sy + (window.sh - sh) / 2.0;
    let user = user.normalised();
    let usable =
        image_w.is_finite() && image_h.is_finite() && image_w > 0.0 && image_h > 0.0;
    if !user.is_default() && usable {
        let max_x = (window.sx + window.sw - sw).max(window.sx);
        let max_y = (window.sy + window.sh - sh).max(window.sy);
        sx = (user.cx * image_w - sw / 2.0).clamp(window.sx, max_x);
        sy = (user.cy * image_h - sh / 2.0).clamp(window.sy, max_y);
    }
    [sx, sy, sw, sh]
}

/// `--fit <mode>` overrides `design.fitMode`; an unrecognised value is an error,
/// never a silent fallback to the default.
pub fn resolve_fit(cli: &str, design_field: Option<&str>) -> Result<FitMode, String> {
    let raw = match cli.trim() {
        "" => design_field.unwrap_or("").trim(),
        c => c,
    };
    if raw.is_empty() {
        return Ok(FitMode::Design);
    }
    match raw.to_ascii_lowercase().as_str() {
        "design" | "template" => Ok(FitMode::Design),
        "native" | "original" => Ok(FitMode::Native),
        other => Err(format!(
            "E4H-FIT-ARG: fit must be 'design' or 'native' (got {other:?})"
        )),
    }
}

pub fn compute_crop(image_w: f32, image_h: f32, ratio: &str) -> Crop {
    let target = match ratio {
        "1:1" => Some(1.0_f32),
        "4:5" => Some(4.0 / 5.0),
        "16:9" => Some(16.0 / 9.0),
        "3:2" => Some(3.0 / 2.0),
        _ => None,
    };
    let Some(target) = target else {
        return Crop {
            sx: 0.0,
            sy: 0.0,
            sw: image_w,
            sh: image_h,
            pw: image_w,
            ph: image_h,
        };
    };
    let (mut sw, mut sh, mut sx, mut sy) = (image_w, image_h, 0.0, 0.0);
    if image_w / image_h > target {
        sw = image_h * target;
        sx = (image_w - sw) / 2.0;
    } else {
        sh = image_w / target;
        sy = (image_h - sh) / 2.0;
    }
    Crop {
        sx,
        sy,
        sw,
        sh,
        pw: sw,
        ph: sh,
    }
}

// ── comparison manifest (tools/card-manifest.cjs output) ────────────────────

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CardsFile {
    pub cards: Vec<Card>,
    #[serde(default)]
    pub themes: Value,
    #[serde(default)]
    pub faces_used: Value,
    #[serde(default)]
    pub unused_templates: Vec<String>,
    #[serde(default)]
    pub counts: Value,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Card {
    pub card: String,
    pub template_id: String,
    #[serde(default)]
    pub variant_id: Option<String>,
    #[serde(default)]
    pub category: String,
    pub design: PathBuf,
    pub golden: PathBuf,
    pub golden_sha256: String,
    pub golden_size: [u32; 2],
    pub canvas: [u32; 2],
    pub photo: PhotoRef,
    #[serde(default)]
    pub exif: Option<Exif>,
    #[serde(default)]
    pub logo: Option<ExtractedLogo>,
    #[serde(default)]
    pub extras: Vec<ExtraAsset>,
    #[serde(default)]
    pub texts: Vec<OpText>,
    #[serde(default)]
    pub images: Vec<OpImage>,
    #[serde(default)]
    pub params: Value,
    #[serde(default)]
    pub text: Value,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PhotoRef {
    pub path: PathBuf,
    pub sha256: String,
    pub size: [u32; 2],
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExtractedLogo {
    pub name: String,
    pub path: PathBuf,
    pub sha256: String,
    pub w: u32,
    pub h: u32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ExtraAsset {
    pub kind: String,
    pub source: PathBuf,
    pub png: PathBuf,
    pub w: u32,
    pub h: u32,
}

#[derive(Debug, Clone, Copy, Deserialize)]
pub struct OpBox {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct OpText {
    pub s: String,
    pub font: String,
    pub family: String,
    pub weight: u32,
    #[serde(default)]
    pub italic: bool,
    pub fs: f32,
    pub x: f32,
    pub y: f32,
    pub align: String,
    pub baseline: String,
    pub r#box: OpBox,
    pub m: [f32; 6],
    #[serde(default)]
    pub ascii: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct OpImage {
    pub nat: String,
    pub r#box: OpBox,
    pub m: [f32; 6],
    pub src: String,
}

pub fn load_cards(path: &Path) -> Result<CardsFile, String> {
    let raw = std::fs::read_to_string(path).map_err(|e| format!("read {}: {e}", path.display()))?;
    serde_json::from_str(&raw).map_err(|e| format!("parse {}: {e}", path.display()))
}

pub fn load_design(path: &Path) -> Result<Design, String> {
    let raw = std::fs::read_to_string(path).map_err(|e| format!("read {}: {e}", path.display()))?;
    let v: Value =
        serde_json::from_str(&raw).map_err(|e| format!("parse {}: {e}", path.display()))?;
    let d = v
        .get("design")
        .cloned()
        .ok_or_else(|| format!("{}: no .design", path.display()))?;
    serde_json::from_value(d).map_err(|e| format!("{}: design: {e}", path.display()))
}

/// `scene.ts mergeParams`: `{...template.defaults, ...design.params}`.
pub fn merge_params(defaults: &Params, design: &Params) -> Params {
    let mut out = defaults.clone();
    for (k, v) in design {
        out.insert(k.clone(), v.clone());
    }
    out
}

pub fn param<'a>(params: &'a Params, key: &str) -> Option<&'a Value> {
    params.get(key)
}

pub fn pct(params: &Params, key: &str, fallback: f64) -> f64 {
    jsval::pct(params.get(key), fallback)
}

pub fn str_param(params: &Params, key: &str, fallback: &str) -> String {
    match params.get(key) {
        None | Some(Value::Null) => fallback.to_string(),
        Some(v) => jsval::js_string(Some(v)),
    }
}

pub fn bool_param(params: &Params, key: &str, fallback: bool) -> bool {
    match params.get(key) {
        None => fallback,
        Some(Value::Null) => fallback,
        Some(v) => jsval::truthy(Some(v)),
    }
}

#[cfg(test)]
mod crop_window_tests {
    use super::{
        compute_crop, cover_src, crop_full, crop_window, Crop, Params, UserCrop,
    };
    use serde_json::json;

    /// 4000x3000 into a 1:1 template: the design keeps 3000x3000 at 500,0.
    fn base() -> Crop {
        compute_crop(4000.0, 3000.0, "1:1")
    }

    #[test]
    fn defaults_keep_the_design_window_untouched() {
        let b = base();
        let v = crop_window(b, 4000.0, 3000.0, UserCrop::default());
        assert_eq!((v.sx, v.sy, v.sw, v.sh), (b.sx, b.sy, b.sw, b.sh));
        // The layout box is the template's, always.
        assert_eq!((v.pw, v.ph), (b.pw, b.ph));
        // `--fit native` keeps the whole sensor at the defaults.
        let full = crop_full(4000.0, 3000.0);
        let v = crop_window(full, 4000.0, 3000.0, UserCrop::default());
        assert_eq!((v.sx, v.sy, v.sw, v.sh), (0.0, 0.0, 4000.0, 3000.0));
    }

    #[test]
    fn zoom_two_halves_the_sampled_extent() {
        let b = base();
        let v = crop_window(b, 4000.0, 3000.0, UserCrop { cx: 0.5, cy: 0.5, zoom: 2.0 });
        assert_eq!((v.sx, v.sy, v.sw, v.sh), (1250.0, 750.0, 1500.0, 1500.0));
        // Same aspect as the design window, so the draw never warps.
        assert!((v.sw / v.sh - b.sw / b.sh).abs() < 1e-5);
    }

    #[test]
    fn corner_pan_clamps_into_the_image_not_the_design_window() {
        // The template's ratio crop discarded the photo's left 500 px, but the
        // user may still choose that edge: the clamp is the image, not `base`.
        let b = base();
        let v = crop_window(b, 4000.0, 3000.0, UserCrop { cx: 0.0, cy: 1.0, zoom: 2.0 });
        assert_eq!((v.sx, v.sy, v.sw, v.sh), (0.0, 1500.0, 1500.0, 1500.0));
        let v = crop_window(b, 4000.0, 3000.0, UserCrop { cx: 1.0, cy: 0.0, zoom: 2.0 });
        assert_eq!((v.sx, v.sy, v.sw, v.sh), (2500.0, 0.0, 1500.0, 1500.0));
    }

    /// The bug this pins: at zoom 1 the extent is unchanged, so clamping the
    /// origin to `base` pinned it to one value and "drag to reposition" — the
    /// primary gesture — did nothing whenever the template's window was as wide
    /// as the photo. The origin must follow the user's point inside the image.
    #[test]
    fn pan_at_zoom_one_moves_the_window() {
        let b = base(); // 3000x3000 at 500,0 inside 4000x3000
        let centred = 500.0; // the design window's own origin
        let left = crop_window(b, 4000.0, 3000.0, UserCrop { cx: 0.0, cy: 0.5, zoom: 1.0 });
        assert_eq!(left.sx, 0.0, "panning left must clamp to the photo's left edge");
        assert_ne!(left.sx, centred, "pan at zoom 1 must leave the centred origin");
        let right = crop_window(b, 4000.0, 3000.0, UserCrop { cx: 0.75, cy: 0.5, zoom: 1.0 });
        assert_eq!((right.sx, right.sw), (1000.0, 3000.0));
        assert!(right.sx > 0.0 && right.sx != centred);
        // A window as wide as the photo has no horizontal slack: only `cx`
        // exactly off-centre can move it, and there it picks a slice at once
        // (see `cover_src`), so the origin is pinned by construction.
        let full = crop_full(4000.0, 3000.0);
        let v = crop_window(full, 4000.0, 3000.0, UserCrop { cx: 0.75, cy: 0.5, zoom: 1.0 });
        assert_eq!((v.sx, v.sw), (0.0, 4000.0));
    }

    #[test]
    fn centred_zoom_one_is_the_default_window_exactly() {
        let b = base();
        let v = crop_window(
            b,
            4000.0,
            3000.0,
            UserCrop { cx: 0.5, cy: 0.5, zoom: 1.0 },
        );
        assert_eq!((v.sx, v.sy, v.sw, v.sh), (b.sx, b.sy, b.sw, b.sh));
    }

    #[test]
    fn zoom_two_pan_stays_inside_the_image_and_keeps_the_aspect() {
        let b = base();
        let aspect = b.sw / b.sh;
        for (cx, cy) in [(0.0, 0.0), (0.0, 1.0), (1.0, 0.0), (1.0, 1.0), (0.3, 0.7)] {
            let v = crop_window(b, 4000.0, 3000.0, UserCrop { cx, cy, zoom: 2.0 });
            assert!(v.sx >= 0.0 && v.sy >= 0.0, "negative origin for {cx},{cy}: {v:?}");
            assert!(v.sx + v.sw <= 4000.0 + 1e-3, "past the right edge: {v:?}");
            assert!(v.sy + v.sh <= 3000.0 + 1e-3, "past the bottom edge: {v:?}");
            assert!(
                (v.sw / v.sh - aspect).abs() < 1e-5,
                "aspect drifted for {cx},{cy}: {v:?}"
            );
        }
    }

    #[test]
    fn one_pixel_windows_never_collapse() {
        let tiny = Crop { sx: 0.0, sy: 0.0, sw: 1.0, sh: 1.0, pw: 1.0, ph: 1.0 };
        let v = crop_window(tiny, 1.0, 1.0, UserCrop { cx: 0.0, cy: 0.0, zoom: 8.0 });
        assert_eq!((v.sx, v.sy, v.sw, v.sh), (0.0, 0.0, 1.0, 1.0));
        // A window that cannot describe a rectangle is returned untouched.
        let empty = Crop { sx: 0.0, sy: 0.0, sw: 0.0, sh: 0.0, pw: 0.0, ph: 0.0 };
        let v = crop_window(empty, 4000.0, 3000.0, UserCrop { cx: 0.25, cy: 0.25, zoom: 4.0 });
        assert_eq!((v.sx, v.sy, v.sw, v.sh), (0.0, 0.0, 0.0, 0.0));
    }

    /// The `cover` slice: the defaults are the centred rect every template got
    /// before the crop existed, and a pan moves that slice even when the window
    /// spans the whole photo (the shape the phone's glass card renders).
    #[test]
    fn cover_slice_moves_with_the_user_at_zoom_one() {
        let full = crop_full(8192.0, 6144.0);
        let (w, h) = (3778.86, 5460.79);
        let default = cover_src(full, UserCrop::default(), 8192.0, 6144.0, w, h);
        // Full height, the cover width centred in the raster.
        assert!((default[2] - 4251.65).abs() < 0.5, "{default:?}");
        assert!((default[3] - 6144.0).abs() < 1e-3, "{default:?}");
        assert!((default[0] - (8192.0 - default[2]) / 2.0).abs() < 1e-3, "{default:?}");
        assert_eq!(default[1], 0.0);

        let panned = cover_src(
            full,
            UserCrop { cx: 0.28422307362779975, cy: 0.5, zoom: 1.0 },
            8192.0,
            6144.0,
            w,
            h,
        );
        assert!(
            (panned[0] - (0.28422307362779975 * 8192.0 - panned[2] / 2.0)).abs() < 1e-2,
            "pan ignored: {panned:?}"
        );
        assert_ne!(panned[0], default[0], "pan must not keep the centred slice");
        // Never leaves the photo.
        assert!(panned[0] >= 0.0 && panned[1] >= 0.0);
        assert!(panned[0] + panned[2] <= 8192.0 + 1e-3);
        assert!(panned[1] + panned[3] <= 6144.0 + 1e-3);

        // A corner clamp stops at the raster edge.
        let edge = cover_src(
            full,
            UserCrop { cx: 0.0, cy: 0.0, zoom: 1.0 },
            8192.0,
            6144.0,
            w,
            h,
        );
        assert_eq!(edge[0], 0.0);
        assert_eq!(edge[1], 0.0);
    }

    #[test]
    fn cover_slice_inside_a_zoomed_window_is_the_user_slice() {
        // zoom 2: `crop_window` narrowed the window; inside it the cover rect
        // has slack again, so the slice still centres on the user's point (the
        // same final rectangle the draw produced before, pinned below).
        let full = crop_full(8192.0, 6144.0);
        let user = UserCrop { cx: 0.28422307362779975, cy: 0.5, zoom: 2.0 };
        let view = crop_window(full, 8192.0, 6144.0, user);
        let src = cover_src(view, user, 8192.0, 6144.0, 3778.86, 5460.79);
        assert!(src[0] >= view.sx - 1e-2, "slice left the window: {src:?} vs {view:?}");
        assert!(
            src[0] + src[2] <= view.sx + view.sw + 1e-2,
            "slice left the window: {src:?} vs {view:?}"
        );
        assert!((src[1] - view.sy).abs() < 1e-2, "{src:?} vs {view:?}");
        assert!((src[3] - view.sh).abs() < 1e-2, "{src:?} vs {view:?}");
        // Centred on the user's source point, unchanged from the pre-crop draw.
        assert!((src[0] - 1265.44).abs() < 0.5, "{src:?}");
        assert!((src[2] - 2125.82).abs() < 0.5, "{src:?}");
        // A window smaller than the box's cover still re-centres, never warps.
        assert!((src[0] - (0.28422307362779975 * 8192.0 - src[2] / 2.0)).abs() < 0.5);
    }

    #[test]
    fn design_params_read_the_three_crop_keys() {
        let mut params = Params::new();
        params.insert("photoCropX".into(), json!(0.25));
        params.insert("photoCropY".into(), json!(1.5));
        params.insert("photoCropZoom".into(), json!(3));
        let crop = UserCrop::read(&params);
        assert_eq!((crop.cx, crop.cy, crop.zoom), (0.25, 1.0, 3.0));
        assert!(!crop.is_default());
        // Absent keys are the engine default.
        assert!(UserCrop::read(&Params::new()).is_default());
    }
}
