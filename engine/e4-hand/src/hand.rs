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
    if v.fract() == 0.0 {
        format!("F{}", v as i64)
    } else {
        format!("F{:.1}", v)
    }
}

pub fn format_shutter(v: f64) -> String {
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
