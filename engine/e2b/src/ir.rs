//! E2b — IR (`engine/data/ir/styles/<family>/<style>.json`) → placed layout.
//!
//! This is a field-by-field port of the frozen TS `core/system/layout.ts` solve
//! path for the subset the target style exercises. Every number comes from the
//! IR file (referenceWidth, band thickness/offset/gravity, node sizes, margins,
//! tracking, lineHeight, image asset rect); nothing about the card is hardcoded.
//!
//! Unsupported-but-present IR semantics are returned as `Gap` records instead of
//! being silently defaulted; the caller prints them as the E2→E3 input.

#![allow(dead_code)]

use serde_json::Value;

use crate::text::{FontSet, TextEngine};

// ---------------------------------------------------------------------------
// minimal geometry
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, Default)]
pub struct Rect {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct Margins {
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
}

impl Margins {
    fn sum_x(&self) -> f32 {
        self.left + self.right
    }
    fn sum_y(&self) -> f32 {
        self.top + self.bottom
    }
}

#[derive(Clone, Debug, Default)]
pub struct Shadow {
    pub color: String,
    pub blur: f32,
    pub x: f32,
    pub y: f32,
}

// ---------------------------------------------------------------------------
// IR model (fields as serialized by E1b-1)
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, Default)]
pub struct Theme {
    pub id: String,
    pub background: Option<String>,
    pub foreground: Vec<String>,
}

#[derive(Clone, Debug)]
pub struct Style {
    pub id: String,
    pub reference_width: f32,
    pub reference_width_mode: Option<String>,
    pub margins: Margins,
    pub background: String,
    pub transparent_background: bool,
    pub bands: Vec<Band>,
    pub assets: Vec<Asset>,
    /// E3: vendor theme table (`themes[].background/foreground`) and the
    /// default the frozen app selects when the design does not override it.
    pub themes: Vec<Theme>,
    pub default_theme: Option<String>,
}

/// `text.outline` — vendor pill/plate around a text node. The stroke colour is
/// a theme `foreground[]` slot when `strokeColorIndex` is present.
#[derive(Clone, Debug, Default)]
pub struct Outline {
    pub padding_x: f32,
    pub padding_y: f32,
    pub width: Option<f32>,
    pub height: Option<f32>,
    pub radius: f32,
    pub stroke_width: f32,
    pub stroke_color: Option<String>,
    pub stroke_color_index: Option<i64>,
}

#[derive(Clone, Debug)]
pub struct Asset {
    pub key: String,
    pub file: String,
    pub width: f32,
    pub height: f32,
}

#[derive(Clone, Debug)]
pub struct Band {
    pub direction: String,
    pub thickness: Option<f32>,
    pub width: Option<f32>,
    pub offset: (f32, f32),
    pub gravity: Option<String>,
    pub background: Option<String>,
    /// E2b: rounded plate radius (IR `radius`, reference units).
    pub radius: Option<f32>,
    pub rotation: Option<f32>,
    /// paint.ts: drawFirst bands paint before the photo (vendor art behind the punch-out).
    pub draw_first: bool,
    /// IR `fit`: "none" keeps band content at its declared rect instead of the
    /// legacy 2.5%/4% inner-box clamp. Full-bleed vendor art (PAG-derived Disney
    /// cards) is authored to the photo+bar canvas, so the clamp misaligned it.
    /// Absent = legacy clamp, byte-identical for every pre-existing style.
    pub fit: Option<String>,
    pub children: Vec<Node>,
}

#[derive(Clone, Debug)]
pub enum Node {
    Group(GroupNode),
    Text(TextNode),
    Image(ImageNode),
    Shape(ShapeNode),
    Space(SpaceNode),
}

#[derive(Clone, Debug)]
pub struct GroupNode {
    pub orientation: String,
    pub gravity: Option<String>,
    pub gap: f32,
    pub background: Option<String>,
    pub margins: Margins,
    /// E2b: declared group frame (layout.ts `intrinsic` honours width/height).
    pub width: Option<f32>,
    pub height: Option<f32>,
    pub match_width: bool,
    pub match_height: bool,
    pub children: Vec<Node>,
}

#[derive(Clone, Debug)]
pub struct TextNode {
    pub token_kind: String,
    /// literal token payload when `token.kind == "literal"` (not used by E2a).
    pub token_value: Option<String>,
    /// E2b: layout.ts `textWidth = node.width ?? max(line measures)`.
    pub width: Option<f32>,
    pub font: String,
    pub size: f32,
    /// The IR's decimal size as parsed, kept in f64 because Chromium's canvas
    /// metric is defined on it (see `measure_em`); `size as f32` would lose the
    /// value that decides the 1/100 px truncation.
    pub size_raw: f64,
    pub weight: i32,
    pub color: String,
    pub alpha: f32,
    pub tracking: f32,
    pub line_height: Option<f32>,
    pub align: String,
    pub gravity: Option<String>,
    pub margins: Margins,
    pub optional: bool,
    pub shadow: Option<Shadow>,
    pub outline: Option<Outline>,
    pub color_index: Option<i64>,
}

#[derive(Clone, Debug)]
pub struct ImageNode {
    pub asset: String,
    pub width: f32,
    pub height: f32,
    pub scale: String,
    pub gravity: Option<String>,
    pub margins: Margins,
    pub alpha: f32,
    pub is_brand_logo: bool,
    pub theme_assets: Option<Value>,
    pub color_index: Option<i64>,
    pub tint: Option<String>,
}

#[derive(Clone, Debug)]
pub struct ShapeNode {
    pub shape: String,
    pub width: f32,
    pub height: f32,
    pub color: String,
    pub alpha: f32,
    pub gravity: Option<String>,
    pub margins: Margins,
    pub vector_key: Option<String>,
}

#[derive(Clone, Debug)]
pub struct SpaceNode {
    pub width: Option<f32>,
    pub height: Option<f32>,
    pub weight: Option<f32>,
}

// ---------------------------------------------------------------------------
// parsing
// ---------------------------------------------------------------------------

fn f32of(v: &Value, key: &str) -> Option<f32> {
    v.get(key).and_then(|x| x.as_f64()).map(|x| x as f32)
}

fn str_of(v: &Value, key: &str) -> Option<String> {
    v.get(key).and_then(|x| x.as_str()).map(str::to_string)
}

fn margins_of(v: &Value) -> Margins {
    let m = match v.get("margins") {
        Some(m) => m,
        None => return Margins::default(),
    };
    Margins {
        left: f32of(m, "left").unwrap_or(0.0),
        top: f32of(m, "top").unwrap_or(0.0),
        right: f32of(m, "right").unwrap_or(0.0),
        bottom: f32of(m, "bottom").unwrap_or(0.0),
    }
}

fn parse_node(v: &Value) -> Result<Node, String> {
    let kind = str_of(v, "kind").ok_or("node.kind missing")?;
    Ok(match kind.as_str() {
        "group" => Node::Group(GroupNode {
            orientation: str_of(v, "orientation").unwrap_or_else(|| "row".into()),
            gravity: str_of(v, "gravity"),
            gap: f32of(v, "gap").unwrap_or(0.0),
            background: str_of(v, "background"),
            margins: margins_of(v),
            width: f32of(v, "width"),
            height: f32of(v, "height"),
            match_width: v.get("matchWidth").and_then(|x| x.as_bool()).unwrap_or(false),
            match_height: v.get("matchHeight").and_then(|x| x.as_bool()).unwrap_or(false),
            children: parse_children(v)?,
        }),
        "text" => {
            let token = v.get("token").cloned().unwrap_or(Value::Null);
            Node::Text(TextNode {
                token_kind: str_of(&token, "kind").unwrap_or_else(|| "literal".into()),
                token_value: str_of(&token, "value"),
                width: f32of(v, "width"),
                font: str_of(v, "font").unwrap_or_else(|| "sys".into()),
                size: f32of(v, "size").unwrap_or(0.0),
                size_raw: v.get("size").and_then(|x| x.as_f64()).unwrap_or(0.0),
                weight: f32of(v, "weight").unwrap_or(400.0) as i32,
                color: str_of(v, "color").unwrap_or_else(|| "#000000".into()),
                alpha: f32of(v, "alpha").unwrap_or(1.0),
                tracking: f32of(v, "tracking").unwrap_or(0.0),
                line_height: f32of(v, "lineHeight"),
                align: str_of(v, "align").unwrap_or_else(|| "left".into()),
                gravity: str_of(v, "gravity"),
                margins: margins_of(v),
                optional: v.get("optional").and_then(|x| x.as_bool()).unwrap_or(false),
                shadow: v.get("shadow").map(|s| Shadow {
                    color: str_of(s, "color").unwrap_or_else(|| "#00000000".into()),
                    blur: f32of(s, "blur").unwrap_or(0.0),
                    x: f32of(s, "x").unwrap_or(0.0),
                    y: f32of(s, "y").unwrap_or(0.0),
                }),
                outline: v.get("outline").map(|o| Outline {
                    padding_x: f32of(o, "paddingX").unwrap_or(0.0),
                    padding_y: f32of(o, "paddingY").unwrap_or(0.0),
                    width: f32of(o, "width"),
                    height: f32of(o, "height"),
                    radius: f32of(o, "radius").unwrap_or(0.0),
                    stroke_width: f32of(o, "strokeWidth").unwrap_or(0.0),
                    stroke_color: str_of(o, "strokeColor"),
                    stroke_color_index: o.get("strokeColorIndex").and_then(|x| x.as_i64()),
                }),
                color_index: v.get("colorIndex").and_then(|x| x.as_i64()),
            })
        }
        "image" => Node::Image(ImageNode {
            asset: str_of(v, "asset").unwrap_or_default(),
            width: f32of(v, "width").unwrap_or(0.0),
            height: f32of(v, "height").unwrap_or(0.0),
            scale: str_of(v, "scale").unwrap_or_else(|| "fill".into()),
            gravity: str_of(v, "gravity"),
            margins: margins_of(v),
            alpha: f32of(v, "alpha").unwrap_or(1.0),
            is_brand_logo: v.get("isBrandLogo").and_then(|x| x.as_bool()).unwrap_or(false),
            theme_assets: v.get("themeAssets").cloned(),
            color_index: v.get("colorIndex").and_then(|x| x.as_i64()),
            tint: str_of(v, "tint"),
        }),
        "shape" => Node::Shape(ShapeNode {
            shape: str_of(v, "shape").unwrap_or_else(|| "rect".into()),
            width: f32of(v, "width").unwrap_or(0.0),
            height: f32of(v, "height").unwrap_or(0.0),
            color: str_of(v, "color").unwrap_or_else(|| "#000000".into()),
            alpha: f32of(v, "alpha").unwrap_or(1.0),
            gravity: str_of(v, "gravity"),
            margins: margins_of(v),
            vector_key: str_of(v, "vectorKey"),
        }),
        "space" => Node::Space(SpaceNode {
            width: f32of(v, "width"),
            height: f32of(v, "height"),
            weight: f32of(v, "weight"),
        }),
        other => return Err(format!("node.kind '{other}' not in E1b-1 schema")),
    })
}

fn parse_children(v: &Value) -> Result<Vec<Node>, String> {
    let mut out = Vec::new();
    if let Some(arr) = v.get("children").and_then(|c| c.as_array()) {
        for c in arr {
            out.push(parse_node(c)?);
        }
    }
    Ok(out)
}

pub fn parse_style(raw: &str) -> Result<Style, String> {
    let v: Value = serde_json::from_str(raw.trim_start_matches('\u{feff}'))
        .map_err(|e| format!("style json: {e}"))?;
    let mut bands = Vec::new();
    for b in v.get("bands").and_then(|x| x.as_array()).ok_or("style.bands missing")? {
        let off = b.get("offset").cloned().unwrap_or(Value::Null);
        bands.push(Band {
            direction: str_of(b, "direction").unwrap_or_else(|| "bottom".into()),
            thickness: f32of(b, "thickness"),
            width: f32of(b, "width"),
            offset: (
                f32of(&off, "x").unwrap_or(0.0),
                f32of(&off, "y").unwrap_or(0.0),
            ),
            gravity: str_of(b, "gravity"),
            background: str_of(b, "background"),
            radius: f32of(b, "radius"),
            rotation: f32of(b, "rotation"),
            draw_first: b.get("drawFirst").and_then(|x| x.as_bool()).unwrap_or(false),
            fit: str_of(b, "fit"),
            children: parse_children(b)?,
        });
    }
    let mut assets = Vec::new();
    if let Some(obj) = v.get("assets").and_then(|x| x.as_object()) {
        for (k, a) in obj {
            assets.push(Asset {
                key: k.clone(),
                file: str_of(a, "file").unwrap_or_default(),
                width: f32of(a, "width").unwrap_or(0.0),
                height: f32of(a, "height").unwrap_or(0.0),
            });
        }
    }
    let mut themes = Vec::new();
    if let Some(arr) = v.get("themes").and_then(|x| x.as_array()) {
        for t in arr {
            let foreground = t
                .get("foreground")
                .and_then(|x| x.as_array())
                .map(|a| a.iter().filter_map(|s| s.as_str().map(str::to_string)).collect())
                .unwrap_or_default();
            themes.push(Theme {
                id: str_of(t, "id").unwrap_or_default(),
                background: str_of(t, "background"),
                foreground,
            });
        }
    }
    Ok(Style {
        id: str_of(&v, "id").unwrap_or_default(),
        reference_width: f32of(&v, "referenceWidth").unwrap_or(360.0),
        reference_width_mode: str_of(&v, "referenceWidthMode"),
        margins: margins_of(&v),
        background: str_of(&v, "background").unwrap_or_else(|| "#00000000".into()),
        transparent_background: v
            .get("transparentBackground")
            .and_then(|x| x.as_bool())
            .unwrap_or(false),
        bands,
        assets,
        themes,
        default_theme: str_of(&v, "defaultTheme"),
    })
}

impl Style {
    pub fn asset(&self, key: &str) -> Option<&Asset> {
        self.assets.iter().find(|a| a.key == key)
    }
    pub fn theme(&self, id: Option<&str>) -> Option<&Theme> {
        let want = id.or(self.default_theme.as_deref())?;
        self.themes.iter().find(|t| t.id == want)
    }
}

// ---------------------------------------------------------------------------
// solve — port of layout.ts
// ---------------------------------------------------------------------------

#[derive(Clone, Debug)]
pub struct PlacedText {
    pub rect: Rect,
    pub lines: Vec<String>,
    pub family: String,
    pub font_key: String,
    pub font_size: f32,
    pub weight: i32,
    pub line_height: f32,
    pub color: String,
    pub alpha: f32,
    pub tracking: f32,
    pub align: String,
    pub path: String,
    pub shadow: Option<Shadow>,
    /// E3: vendor outline plate (stroke width/colour/radius), painted behind
    /// the glyphs inside the same text box.
    pub outline: Option<PlacedOutline>,
}

#[derive(Clone, Debug)]
pub struct PlacedOutline {
    pub radius: f32,
    pub stroke_width: f32,
    pub stroke_color: String,
}

#[derive(Clone, Debug)]
pub struct PlacedImage {
    pub rect: Rect,
    /// E3: theme-selected asset key (`themeAssets[theme] ?? asset`).
    pub asset: String,
    pub scale: String,
    pub alpha: f32,
    pub path: String,
    pub is_brand_logo: bool,
    /// E3: theme foreground tint for brand slots (`node.tint` / `colorIndex`).
    pub tint: Option<String>,
    /// E3: empty room the solved band leaves around a brand slot, in layout
    /// units (layout.ts `annotateBrandFree`). `left,right,top,bottom`.
    pub brand_free: Option<[f32; 4]>,
    /// E3: node gravity + margins, needed by `brandSlotRect()`.
    pub gravity: Option<String>,
    pub margins: Margins,
}

#[derive(Clone, Debug)]
pub struct PlacedShape {
    pub rect: Rect,
    pub shape: String,
    pub color: String,
    pub alpha: f32,
    /// E3: VectorDrawable art key (`shape.vectorKey`, painted from the generated art).
    pub vector_key: Option<String>,
}

#[derive(Clone, Debug)]
pub struct PlacedGroupBg {
    pub rect: Rect,
    pub color: String,
}

#[derive(Clone, Debug)]
pub enum Item {
    Text(PlacedText),
    Image(PlacedImage),
    Shape(PlacedShape),
    Group(PlacedGroupBg),
}

impl Item {
    pub fn rect(&self) -> &Rect {
        match self {
            Item::Text(t) => &t.rect,
            Item::Image(i) => &i.rect,
            Item::Shape(s) => &s.rect,
            Item::Group(g) => &g.rect,
        }
    }
    pub fn rect_mut(&mut self) -> &mut Rect {
        match self {
            Item::Text(t) => &mut t.rect,
            Item::Image(i) => &mut i.rect,
            Item::Shape(s) => &mut s.rect,
            Item::Group(g) => &mut g.rect,
        }
    }
}

#[derive(Clone, Debug)]
pub struct PlacedBand {
    pub direction: String,
    /// Final canvas-space band box (paint.ts `band.box`).
    pub rect: Rect,
    /// Pre-rotation rect the items were laid out in (layout.ts
    /// `layoutBoxForBand`: w/h swapped about the same centre for ±90°).
    pub layout_rect: Rect,
    pub background: Option<String>,
    pub radius: Option<f32>,
    pub rotation: Option<f32>,
    pub draw_first: bool,
    pub items: Vec<Item>,
}

#[derive(Clone, Debug)]
pub struct Layout {
    pub w: f32,
    pub h: f32,
    pub photo: Rect,
    pub bands: Vec<PlacedBand>,
}

#[derive(Clone, Debug, Default)]
pub struct Gaps {
    pub notes: Vec<String>,
}

/// P3c render-v1 control overrides (all keys absent/empty/default = no-op).
/// Paths are bundle-IR node paths (`b<band>/g<child>...`), re-keyed by the
/// bridge from the stable controlId binding table; the engine never sees
/// vendor targetNode paths.
#[derive(Clone, Debug, Default)]
pub struct Overrides {
    /// `slotText:<path>` — non-empty only (empty string = fall back to token).
    pub slot_text: std::collections::HashMap<String, String>,
    /// `slotOff:<path> === false` (and array form params.slotOff).
    pub slot_off: std::collections::HashSet<String>,
    /// `slotAlpha:<path>` multiplier (1 = untouched).
    pub slot_alpha: std::collections::HashMap<String, f32>,
    /// `slotGravity:<path>` override ('vendor' = untouched).
    pub slot_gravity: std::collections::HashMap<String, String>,
    /// `stockAnchor` nine-grid (None/'vendor' = untouched).
    pub stock_anchor: Option<String>,
    /// `stockOffsetXPct` / `stockOffsetYPct` percentages.
    pub stock_offset: (f32, f32),
    /// `theme` from the design document (the --theme CLI still wins).
    pub theme: Option<String>,
    /// `brandLogoFile` — a decoded/logos stem picked in the Inspector.
    pub brand: Option<String>,
}

impl Overrides {
    pub fn text(&self, path: &str) -> Option<&str> {
        self.slot_text.get(path).map(String::as_str)
    }
    /// Exact path or any ancestor container is off.
    pub fn is_off(&self, path: &str) -> bool {
        if self.slot_off.is_empty() {
            return false;
        }
        if self.slot_off.contains(path) {
            return true;
        }
        let mut p = path;
        while let Some(i) = p.rfind('/') {
            p = &p[..i];
            if self.slot_off.contains(p) {
                return true;
            }
        }
        false
    }
    pub fn alpha(&self, path: &str) -> f32 {
        self.slot_alpha.get(path).copied().unwrap_or(1.0)
    }
    pub fn gravity(&self, path: &str) -> Option<&str> {
        self.slot_gravity.get(path).map(String::as_str)
    }
    pub fn has_box_move(&self) -> bool {
        self.stock_anchor.is_some() || self.stock_offset.0 != 0.0 || self.stock_offset.1 != 0.0
    }
}

impl Gaps {
    pub fn add(&mut self, s: impl Into<String>) {
        let s = s.into();
        if !self.notes.contains(&s) {
            self.notes.push(s);
        }
    }
}

pub struct Env<'a> {
    pub pw: f32,
    pub ph: f32,
    /// E2b: the runtime data the IR deliberately does not carry.
    pub exif: crate::exif::Exif,
    pub location: String,
    pub custom: String,
    pub show_model: bool,
    pub show_exif: bool,
    pub show_date: bool,
    pub font_scale: f32,
    pub te: &'a TextEngine,
    pub fonts: &'a FontSet,
    /// E3: selected theme id (`--theme` or `style.defaultTheme`).
    pub theme_id: Option<String>,
    /// E3: selected theme `foreground[]`; `colorIndex` slots read this table.
    pub foreground: Vec<String>,
    /// P3c control overrides (empty by default ⇒ every branch below is a no-op).
    pub ov: &'a Overrides,
}

fn gravity_parts(g: Option<&str>) -> (char, char) {
    // 'l'|'c'|'r' and 't'|'c'|'b' — same regex order as layout.ts.
    let s = g.unwrap_or("leftTop");
    let h = if s.to_lowercase().contains("right") {
        'r'
    } else if (s.to_lowercase().contains("hor") || s.to_lowercase().contains("center"))
        && !s.to_lowercase().contains("left")
    {
        'c'
    } else {
        'l'
    };
    let v = if s.to_lowercase().contains("bottom") {
        'b'
    } else if (s.to_lowercase().contains("verticalcenter") || s.to_lowercase().contains("center"))
        && !s.to_lowercase().contains("top")
    {
        'c'
    } else {
        't'
    };
    (h, v)
}

/// Effective gravity: control override ⇉ node's own gravity ⇉ parent fallback
/// (layout.ts `effectiveGravity`; absent override returns the original expression).
fn gravity_eff(env: &Env, path: &str, node: &Node, fallback: Option<String>) -> Option<String> {
    if let Some(g) = env.ov.gravity(path) {
        return Some(g.to_string());
    }
    node_gravity(node).or(fallback)
}

fn align_cross(g: Option<&str>, outer: f32, inner: f32, horizontal: bool) -> f32 {    let (h, v) = gravity_parts(g);
    if horizontal {
        match v {
            't' => 0.0,
            'b' => outer - inner,
            _ => (outer - inner) / 2.0,
        }
    } else {
        match h {
            'l' => 0.0,
            'r' => outer - inner,
            _ => (outer - inner) / 2.0,
        }
    }
}

fn line_height_of(node: &TextNode, font_scale: f32) -> f32 {
    let lh = node.line_height.unwrap_or(node.size * 1.2);
    if (font_scale - 1.0).abs() < 1e-9 {
        lh
    } else {
        lh * font_scale
    }
}

/// Chromium parses a canvas `ctx.font` px length into 1/100 px units and
/// **truncates**, so `"400 12.078px …"` measures at 12.07 (verified on
/// Chromium 156, the Electron 45 the frozen app runs: 12.07 and 12.078 both
/// give 164.6225, 12.08 gives 164.7589). layout.ts measures every stock line
/// through that canvas at `size * fontScale` design units, so the 1.22 arm
/// spends `floor(12.078 * 100) / 100 = 12.07` — 0.066% narrower than a linear
/// measure, which is the full 1 px the xpan_1 wordmark bar was off by.
/// The `1e-6` (in hundredths, i.e. 1e-8 px) keeps values that *are* on the grid
/// from falling through on binary noise (8.45 is stored as 8.44999…, and a bare
/// floor would spend 8.44 — that alone moved `hassel_style_4`'s 1.0 text boxes).
/// Every IR size has at most 2 decimals, so at fontScale 1 this is an exact
/// no-op and the 1.0 arm stays byte-identical.
fn measure_em(size_raw: f64, font_scale: f32) -> f32 {
    let em = size_raw * font_scale as f64;
    ((em * 100.0 + 1e-6).floor() / 100.0) as f32
}

fn measure_line(env: &Env, text: &str, node: &TextNode, font_scale: f32) -> f32 {
    let base = env.fonts.resolve(&node.font, node.weight);
    let script = env.fonts.script_face(base, text, node.weight);
    // W2: 含 CJK 的行按厂商中文字形量宽（与绘制用同一张面），否则布局与成片不一致。
    let cjk = if script.is_none() { env.fonts.cjk_face_for(&node.font, text, node.weight) } else { None };
    let tf = script.clone().or(cjk).unwrap_or_else(|| base.clone());
    let em = measure_em(node.size_raw, font_scale);
    let tracking = node.tracking * node.size;
    if script.is_some() {
        env.te.measure_clusters(text, &tf, em, tracking)
    } else {
        env.te.shape(text, &tf, em, tracking).1
    }
}

fn exif_date_parts(date_time: &str) -> (String, String, String, String, String) {
    // "YYYY:MM:DD HH:MM:SS" -> dotted date, HH:MM, weekday, MM, DD.
    // P3e: an absent/partial DateTimeOriginal resolves to EMPTY strings, never a
    // fabricated epoch/00:00 value. Empty date parts make the template empty and
    // the engine's existing "empty text node is not drawn" rule removes the line.
    if date_time.trim().is_empty() {
        return (String::new(), String::new(), String::new(), String::new(), String::new());
    }
    let (d, t) = date_time.split_once(' ').unwrap_or((date_time, ""));
    let dparts: Vec<&str> = d.split([':', '-']).collect();
    if dparts.len() < 3 {
        return (String::new(), String::new(), String::new(), String::new(), String::new());
    }
    let (Ok(y), Ok(m), Ok(day)) = (
        dparts[0].parse::<i32>(),
        dparts[1].parse::<u32>(),
        dparts[2].parse::<u32>(),
    ) else {
        return (String::new(), String::new(), String::new(), String::new(), String::new());
    };
    if !(1..=12).contains(&m) || !(1..=31).contains(&day) {
        return (String::new(), String::new(), String::new(), String::new(), String::new());
    }
    let date = format!("{y:04}.{m:02}.{day:02}");
    let tparts: Vec<&str> = t.split(':').collect();
    let time = match (
        tparts.first().and_then(|v| v.parse::<u32>().ok()),
        tparts.get(1).and_then(|v| v.parse::<u32>().ok()),
    ) {
        (Some(hh), Some(mm)) if hh < 24 && mm < 60 => format!("{hh:02}:{mm:02}"),
        _ => String::new(),
    };
    // Sakamoto's weekday: 0 = Sunday.
    let table = [0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4];
    let yy = if m < 3 { y - 1 } else { y };
    let wd = ((yy + yy / 4 - yy / 100 + yy / 400 + table[(m as usize - 1) % 12] as i32 + day as i32) % 7 + 7) % 7;
    let week = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"][wd as usize];
    (date, time, week.to_string(), format!("{m:02}"), format!("{day:02}"))
}

fn model_number_name(model: &str) -> (String, String) {
    // JS /^([A-Za-z]*\d+[A-Za-z0-9]*)(?:\s+(.+))?$/
    let bytes = model.as_bytes();
    let mut i = 0;
    while i < bytes.len() && bytes[i].is_ascii_alphabetic() {
        i += 1;
    }
    let mut j = i;
    while j < bytes.len() && bytes[j].is_ascii_digit() {
        j += 1;
    }
    if j == i {
        return (model.to_string(), String::new());
    }
    while j < bytes.len() && bytes[j].is_ascii_alphanumeric() {
        j += 1;
    }
    let number = model[..j].to_string();
    let rest = model[j..].trim();
    (number, rest.to_string())
}

fn template_text(env: &Env, value: &str) -> String {
    // Port of core/system/tokens.ts templateText().
    let mut s = value.trim().to_string();
    if let Some(eq) = s.find('=') {
        let head = &s[..eq];
        if head.starts_with('@') {
            let key = head.trim_start_matches('@');
            if ["model", "exif", "time", "location", "custom", "filter"].contains(&key) {
                s = s[eq + 1..].to_string();
            }
        }
    }
    let make = env.exif.make.trim().to_string();
    let model_full = crate::exif::model_without_make(&make, &env.exif.model).trim().to_string();
    // 机型开关作用在“组件”层：关掉机型时，任何含机型的占位符（独立行或合成行）
    // 都取空串，剩余分隔符随后收掉；开时逐字节保持原行为。
    let model = if env.show_model { model_full.clone() } else { String::new() };
    let (number, name) = model_number_name(&model);
    let full = [make.as_str(), model.as_str()]
        .iter()
        .filter(|v| !v.is_empty())
        .cloned()
        .collect::<Vec<_>>()
        .join(" ");
    let series_number = [make.as_str(), number.as_str()]
        .iter()
        .filter(|v| !v.is_empty())
        .cloned()
        .collect::<Vec<_>>()
        .join(" ");
    let number_name = [number.as_str(), name.as_str()]
        .iter()
        .filter(|v| !v.is_empty())
        .cloned()
        .collect::<Vec<_>>()
        .join(" ");
    let (date, time, week, month, day) = exif_date_parts(&env.exif.date_time);
    let location = env.location.trim();
    s = s.replace("@{series}@{versionNumber}@{versionName}", &full);
    s = s.replace("@{series}@{versionNumber}", &series_number);
    s = s.replace("@{versionNumber}@{versionName}", &number_name);
    s = s.replace("@{versionName}", &name);
    s = s.replace("@{versionNumber}", &number);
    s = s.replace("@{series}", &make);
    if !env.show_model {
        // 关掉机型后收掉悬空分隔符与重复空白（开启时完全不动，保持逐字节不变）。
        while s.contains("  ") { s = s.replace("  ", " "); }
        for sep in [" ·", " |", " /", " —", " -"] {
            s = s.replace(&format!("{sep}{sep}"), sep);
        }
        s = s.trim().to_string();
    }
    let focal = env
        .exif
        .focal_35mm
        .or(env.exif.focal)
        .map(|v| format!("{}", v.round() as i64))
        .unwrap_or_default();
    s = s.replace("@{focal}", &focal);
    let aperture = env
        .exif
        .aperture
        .map(|a| {
            if (a.fract()).abs() < 1e-4 {
                format!("{}", a as i64)
            } else {
                format!("{a:.1}")
            }
        })
        .unwrap_or_default();
    s = s.replace("@{aperture}", &aperture);
    let speed = env
        .exif
        .shutter
        .map(|v| {
            if v >= 1.0 {
                if (v.fract()).abs() < 1e-4 {
                    format!("{}", v as i64)
                } else {
                    format!("{v:.1}")
                }
            } else {
                format!("1/{}", (1.0 / v).round() as i64)
            }
        })
        .unwrap_or_default();
    s = s.replace("@{speed}", &speed);
    let iso = env.exif.iso.map(|v| format!("{v}")).unwrap_or_default();
    s = s.replace("@{iso}", &iso);
    s = s.replace("@{lens}", "");
    s = s.replace(
        "@wm_time_yyyy_MM_dd_HH_mm",
        &[date.as_str(), time.as_str()]
            .iter()
            .filter(|v| !v.is_empty())
            .cloned()
            .collect::<Vec<_>>()
            .join(" "),
    );
    s = s.replace("@wm_time_yyyy_MM_dd", &date);
    s = s.replace("@wm_time_yyyy_MM", &date.chars().take(7).collect::<String>());
    s = s.replace("@wm_time_HH_mm", &time);
    s = s.replace("@wm_time_week", &week);
    s = s.replace("@wm_time_MM", &month);
    s = s.replace("@wm_time_dd", &day);
    for (pat, repl) in [
        ("location_address_list", location),
        ("location_address", location),
        ("location_latlng_switch", location),
        ("location_latlng", location),
    ] {
        s = s.replace(pat, repl);
    }
    // @locationLimit=<n> / @locationMaxLines=<n> / @LOC=<...>
    let mut out = String::new();
    let mut rest = s.as_str();
    while let Some(at) = rest.find('@') {
        out.push_str(&rest[..at]);
        let tail = &rest[at..];
        let end = tail[1..]
            .find([';', ' ', '@'])
            .map(|v| v + 1)
            .unwrap_or(tail.len());
        let token = &tail[..end];
        if token.starts_with("@locationLimit=")
            || token.starts_with("@locationMaxLines=")
            || token.starts_with("@LOC=")
        {
            rest = &tail[end..];
        } else {
            out.push_str(token);
            rest = &tail[end..];
        }
    }
    out.push_str(rest);
    out = out.replace(";;", ";");
    // P3e: suppress a template only when NONE of the placeholders it actually
    // references resolved to a value. This keeps unrelated tags from keeping a
    // line alive (e.g. Make present must not keep the optics line) and never
    // surfaces leftover unit literals ("mm", "f/", "s", "ISO") for a missing
    // family. Templates that reference several optics fields still render the
    // fields that ARE present (per-field, reference stockTokenText behaviour).
    let refs_exif = ["@{focal}", "@{aperture}", "@{speed}", "@{iso}", "@{lens}"]
        .iter()
        .any(|p| value.contains(p));
    let refs_time = value.contains("@wm_time_");
    let refs_model = ["@{series}", "@{versionNumber}", "@{versionName}"]
        .iter()
        .any(|p| value.contains(p));
    let refs_location = value.contains("location_");
    let has_placeholder = refs_exif || refs_time || refs_model || refs_location || value.contains("@{");
    let mut relevant = false;
    if refs_exif {
        relevant |= !focal.is_empty() || !aperture.is_empty() || !speed.is_empty() || !iso.is_empty();
    }
    if refs_time {
        relevant |= !date.is_empty() || !time.is_empty() || !week.is_empty() || !month.is_empty() || !day.is_empty();
    }
    if refs_model {
        relevant |= !make.is_empty() || !model.is_empty() || !number.is_empty() || !name.is_empty();
    }
    if refs_location {
        relevant |= !location.is_empty();
    }
    if has_placeholder && !relevant {
        return String::new();
    }
    out.trim_matches(|c: char| c == ';' || c == '；' || c.is_whitespace())
        .to_string()
}

fn token_text(env: &Env, node: &TextNode, path: &str, gaps: &mut Gaps) -> String {
    // render-v1 slotText override (empty string = "not overridden").
    if let Some(s) = env.ov.text(path) {
        if !s.is_empty() {
            return s.to_string();
        }
    }
    match node.token_kind.as_str() {
        "literal" => node.token_value.clone().unwrap_or_default(),
        "model" => {
            if env.show_model {
                env.exif.model_line()
            } else {
                String::new()
            }
        }
        "imageParams" => {
            if env.show_exif {
                env.exif.image_params()
            } else {
                String::new()
            }
        }
        "time" => {
            if env.show_date {
                env.exif.time_line()
            } else {
                String::new()
            }
        }
        "timeLocation" => {
            let t = if env.show_date {
                env.exif.time_line()
            } else {
                String::new()
            };
            let l = env.location.trim();
            [t.as_str(), l]
                .iter()
                .filter(|s| !s.is_empty())
                .cloned()
                .collect::<Vec<_>>()
                .join("\n")
        }
        "location" => env.location.trim().to_string(),
        "custom" | "greeting" => env.custom.trim().to_string(),
        "template" => template_text(env, node.token_value.as_deref().unwrap_or("")),
        "filter" => node.token_value.clone().unwrap_or_default(),
        other => {
            gaps.add(format!(
                "token.kind='{other}' is not resolvable from this style alone (needs EXIF/env); node left empty"
            ));
            String::new()
        }
    }
}

#[derive(Clone, Debug)]
struct Measured {
    w: f32,
    h: f32,
    text: Option<PlacedText>,
}

fn text_measured(env: &Env, node: &TextNode, path: &str, gaps: &mut Gaps) -> Measured {
    let value = token_text(env, node, path, gaps);
    if value.is_empty() {
        return Measured {
            w: 0.0,
            h: 0.0,
            text: None,
        };
    }
    // wrapText(): node.width is the only wrap cap; absent/0 => hard newlines.
    let max_w = node.width.unwrap_or(0.0);
    let mut lines: Vec<String> = Vec::new();
    for paragraph in value.split('\n') {
        if max_w <= 0.0 {
            lines.push(paragraph.to_string());
            continue;
        }
        if paragraph.is_empty() {
            lines.push(String::new());
            continue;
        }
        let mut line = String::new();
        for ch in paragraph.chars() {
            let next = format!("{line}{ch}");
            if !line.is_empty() && measure_line(env, &next, node, env.font_scale) > max_w {
                lines.push(line);
                line = ch.to_string();
            } else {
                line = next;
            }
        }
        lines.push(line);
    }
    // layout.ts `lineHeightOf()` scales the row step by fontScale (the frozen
    // solver measures and pitches at `size * fontScale`), so this must too.
    let lh = line_height_of(node, env.font_scale);
    let measured = lines
        .iter()
        .map(|l| measure_line(env, l, node, env.font_scale))
        .fold(0.0f32, f32::max);
    // layout.ts: textWidth = node.width ?? max(line measures); outline padding
    // enlarges the solved box and forces centre alignment.
    let tw = node.width.unwrap_or(measured);
    let th = lh * lines.len().max(1) as f32;
    let outline = node.outline.as_ref();
    let pad_x = outline.map(|o| o.padding_x).unwrap_or(0.0);
    let pad_y = outline.map(|o| o.padding_y).unwrap_or(0.0);
    let ow = outline.and_then(|o| o.width).unwrap_or(tw + pad_x * 2.0);
    let oh = outline.and_then(|o| o.height).unwrap_or(th + pad_y * 2.0);
    let align = if outline.is_some() {
        "center".to_string()
    } else {
        node.align.clone()
    };
    // E3: colourIndex resolves through the selected theme's foreground table.
    let color = match node.color_index {
        Some(i) => match env.foreground.get(i.max(0) as usize) {
            Some(c) => c.clone(),
            None => {
                gaps.add(format!(
                    "text.colorIndex {i} outside theme '{}' foreground[] ({} entries); node colour '{}' used",
                    env.theme_id.as_deref().unwrap_or("<none>"),
                    env.foreground.len(),
                    node.color
                ));
                node.color.clone()
            }
        },
        None => node.color.clone(),
    };
    let placed_outline = outline.and_then(|o| {
        if o.stroke_width <= 0.0 {
            return None;
        }
        let stroke = match o.stroke_color_index {
            Some(i) => env.foreground.get(i.max(0) as usize).cloned().or_else(|| o.stroke_color.clone()),
            None => o.stroke_color.clone(),
        }?;
        Some(PlacedOutline {
            radius: o.radius,
            stroke_width: o.stroke_width,
            stroke_color: stroke,
        })
    });
    Measured {
        w: ow,
        h: oh,
        text: Some(PlacedText {
            rect: Rect {
                x: 0.0,
                y: 0.0,
                w: ow,
                h: oh,
            },
            lines,
            family: crate::text::font_key_family(&node.font).to_string(),
            font_key: node.font.clone(),
            font_size: node.size * env.font_scale,
            weight: node.weight,
            line_height: lh,
            color,
            alpha: node.alpha,
            tracking: node.tracking * node.size * env.font_scale,
            align,
            path: path.to_string(),
            shadow: node.shadow.clone(),
            outline: placed_outline,
        }),
    }
}

fn intrinsic(env: &Env, node: &Node, path: &str, gaps: &mut Gaps) -> Measured {
    match node {
        Node::Text(t) => text_measured(env, t, path, gaps),
        Node::Image(i) => Measured {
            w: i.width,
            h: i.height,
            text: None,
        },
        Node::Shape(s) => Measured {
            w: s.width,
            h: s.height,
            text: None,
        },
        Node::Space(s) => Measured {
            w: s.width.unwrap_or(0.0),
            h: s.height.unwrap_or(0.0),
            text: None,
        },
        Node::Group(g) => {
            let (flow_w, flow_h) = measure_group(env, g, path, gaps);
            Measured {
                w: g.width.unwrap_or(flow_w),
                h: g.height.unwrap_or(flow_h),
                text: None,
            }
        }
    }
}

fn node_margins(node: &Node) -> Option<Margins> {
    match node {
        Node::Text(t) => Some(t.margins),
        Node::Image(i) => Some(i.margins),
        Node::Shape(s) => Some(s.margins),
        Node::Group(g) => Some(g.margins),
        Node::Space(_) => None,
    }
}

fn node_gravity(node: &Node) -> Option<String> {
    match node {
        Node::Text(t) => t.gravity.clone(),
        Node::Image(i) => i.gravity.clone(),
        Node::Shape(s) => s.gravity.clone(),
        Node::Group(g) => g.gravity.clone(),
        Node::Space(_) => None,
    }
}

fn measure_group(env: &Env, node: &GroupNode, path: &str, gaps: &mut Gaps) -> (f32, f32) {
    // layout.ts measureGroup() first removes optional nodes with no value; their
    // margins must not contribute either (E2a included them).
    let kids = visible_pairs(env, &node.children, path, gaps);
    let row = node.orientation == "row";
    let mut sum_w = 0.0f32;
    let mut sum_h = 0.0f32;
    let mut max_w = 0.0f32;
    let mut max_h = 0.0f32;
    let n = kids.len();
    for (child, p) in &kids {
        let m = intrinsic(env, child, p, gaps);
        let mg = node_margins(child);
        let mx = mg.map(|m| m.sum_x()).unwrap_or(0.0);
        let my = mg.map(|m| m.sum_y()).unwrap_or(0.0);
        if row {
            sum_w += m.w + mx;
            max_h = max_h.max(m.h + my);
        } else {
            sum_h += m.h + my;
            max_w = max_w.max(m.w + mx);
        }
    }
    let gap = node.gap * n.saturating_sub(1) as f32;
    if row {
        (sum_w + gap, max_h)
    } else {
        (max_w, sum_h + gap)
    }
}

fn visible_pairs<'a>(
    env: &Env,
    children: &'a [Node],
    path: &str,
    gaps: &mut Gaps,
) -> Vec<(&'a Node, String)> {
    let mut out = Vec::new();
    for (i, c) in children.iter().enumerate() {
        let p = format!("{path}/g{i}");
        // render-v1 slotOff (exact path or an ancestor container).
        if env.ov.is_off(&p) {
            continue;
        }
        if let Node::Text(t) = c {
            let v = token_text(env, t, &p, gaps);
            if t.optional && v.is_empty() {
                continue;
            }
        }
        out.push((c, p));
    }
    out
}

fn place_text(node: &TextNode, rect: Rect, path: &str, env: &Env, gaps: &mut Gaps, out: &mut Vec<Item>) {
    let m = text_measured(env, node, path, gaps);
    if let Some(mut t) = m.text {
        t.rect = rect;
        out.push(Item::Text(t));
    }
}

fn place_node(env: &Env, node: &Node, rect: Rect, path: &str, gaps: &mut Gaps, out: &mut Vec<Item>) {
    match node {
        Node::Text(t) => place_text(t, rect, path, env, gaps, out),
        Node::Image(i) => {
            // paint.ts: bakedKey = themeAssets[theme] ?? asset.
            let themed = env
                .theme_id
                .as_deref()
                .and_then(|t| i.theme_assets.as_ref().and_then(|m| m.get(t)))
                .and_then(|v| v.as_str())
                .map(str::to_string);
            let asset = themed.clone().unwrap_or_else(|| i.asset.clone());
            // A brand slot is tinted by its colorIndex; other images only by an
            // explicit `tint` (paint.ts ignores colorIndex on baked assets).
            let tint = if i.is_brand_logo {
                i.tint.clone().or_else(|| {
                    i.color_index.and_then(|ci| env.foreground.get(ci.max(0) as usize).cloned())
                })
            } else {
                i.tint.clone()
            };
            out.push(Item::Image(PlacedImage {
                rect: rect,
                asset,
                scale: i.scale.clone(),
                alpha: i.alpha,
                path: path.to_string(),
                is_brand_logo: i.is_brand_logo,
                tint,
                brand_free: None,
                gravity: i.gravity.clone(),
                margins: i.margins,
            }))
        }
        Node::Shape(s) => {
            if s.shape == "circle" {
                gaps.add("shape=circle not implemented in E2b (this style uses rect)");
            }
            out.push(Item::Shape(PlacedShape {
                rect: rect,
                shape: s.shape.clone(),
                color: s.color.clone(),
                alpha: s.alpha,
                vector_key: s.vector_key.clone(),
            }))
        }
        Node::Group(g) => {
            if let Some(bg) = g.background.clone() {
                out.push(Item::Group(PlacedGroupBg {
                    rect: rect,
                    color: bg,
                }));
            }
            layout_children(env, g, rect, path, gaps, out);
        }
        Node::Space(_) => {}
    }
}

fn layout_children(env: &Env, node: &GroupNode, rect: Rect, path: &str, gaps: &mut Gaps, out: &mut Vec<Item>) {
    let kids = visible_pairs(env, &node.children, path, gaps);
    if kids.is_empty() {
        return;
    }
    if kids.len() == 1 {
        let (child, p) = &kids[0];
        if matches!(child, Node::Space(_)) {
            return;
        }
        let mg = node_margins(child).unwrap_or_default();
        let m = intrinsic(env, child, p, gaps);
        let (mw, mh) = match child {
            Node::Group(g) if g.match_width => (rect.w - mg.sum_x(), m.h),
            _ => (m.w, m.h),
        };
        let (mw, mh) = match child {
            Node::Group(g) if g.match_height => (mw, rect.h - mg.sum_y()),
            _ => (mw, mh),
        };
        let (gh, gv) = gravity_parts(gravity_eff(env, p, child, node.gravity.clone()).as_deref());
        let content_w = rect.w - mg.sum_x();
        let content_h = rect.h - mg.sum_y();
        let x = rect.x
            + mg.left
            + match gh {
                'r' => content_w - mw,
                'c' => (content_w - mw) / 2.0,
                _ => 0.0,
            };
        let y = rect.y
            + mg.top
            + match gv {
                'b' => content_h - mh,
                'c' => (content_h - mh) / 2.0,
                _ => 0.0,
            };
        place_node(env, child, Rect { x, y, w: mw, h: mh }, p, gaps, out);
        return;
    }

    let horizontal = node.orientation == "row";
    let gap = node.gap;
    let has_space = kids.iter().any(|(c, _)| matches!(c, Node::Space(_)));
    let sizes: Vec<Measured> = kids
        .iter()
        .map(|(c, p)| {
            let mut m = intrinsic(env, c, p, gaps);
            if let Node::Group(g) = c {
                let mg = node_margins(c).unwrap_or_default();
                if g.match_width {
                    m.w = rect.w - mg.sum_x();
                }
                if g.match_height {
                    m.h = rect.h - mg.sum_y();
                }
            }
            m
        })
        .collect();

    if horizontal && !has_space {
        // Left/right split branch (same as layout.ts): only used when BOTH a
        // left/start child and a right/end child exist.
        let mut lefts = Vec::new();
        let mut rights = Vec::new();
        let mut centers = Vec::new();
        for (i, (c, _)) in kids.iter().enumerate() {
            let (gh, _) = gravity_parts(gravity_eff(env, &kids[i].1, c, None).as_deref());
            match gh {
                'r' => rights.push(i),
                'c' => centers.push(i),
                _ => lefts.push(i),
            }
        }
        if !lefts.is_empty() && !rights.is_empty() {
            let mut xl = rect.x;
            for &i in &lefts {
                let (c, p) = &kids[i];
                let mg = node_margins(c).unwrap_or_default();
                let s = sizes[i].clone();
                let x = xl + mg.left;
                let y = rect.y
                    + mg.top
                    + align_cross(
                        gravity_eff(env, p, c, node.gravity.clone()).as_deref(),
                        rect.h - mg.sum_y(),
                        s.h,
                        true,
                    );
                place_node(env, c, Rect { x, y, w: s.w, h: s.h }, p, gaps, out);
                xl = x + s.w + mg.right;
            }
            let mut xr = rect.x + rect.w;
            for &i in rights.iter().rev() {
                let (c, p) = &kids[i];
                let mg = node_margins(c).unwrap_or_default();
                let s = sizes[i].clone();
                let x = xr - mg.right - s.w;
                let y = rect.y
                    + mg.top
                    + align_cross(
                        gravity_eff(env, p, c, node.gravity.clone()).as_deref(),
                        rect.h - mg.sum_y(),
                        s.h,
                        true,
                    );
                place_node(env, c, Rect { x, y, w: s.w, h: s.h }, p, gaps, out);
                xr = x - mg.left;
            }
            for &i in &centers {
                let (c, p) = &kids[i];
                let mg = node_margins(c).unwrap_or_default();
                let s = sizes[i].clone();
                let x = rect.x + (rect.w - s.w) / 2.0;
                let y = rect.y
                    + mg.top
                    + align_cross(
                        gravity_eff(env, p, c, node.gravity.clone()).as_deref(),
                        rect.h - mg.sum_y(),
                        s.h,
                        true,
                    );
                place_node(env, c, Rect { x, y, w: s.w, h: s.h }, p, gaps, out);
            }
            return;
        }
    }

    let total_weight: f32 = kids
        .iter()
        .filter_map(|(c, _)| match c {
            Node::Space(s) => Some(s.weight.unwrap_or(1.0).max(0.001)),
            _ => None,
        })
        .sum();
    let mut total_fixed = 0.0f32;
    for (i, (c, _)) in kids.iter().enumerate() {
        if !matches!(c, Node::Space(_)) {
            let mg = node_margins(c).unwrap_or_default();
            total_fixed += if horizontal { sizes[i].w + mg.sum_x() } else { sizes[i].h + mg.sum_y() };
        }
    }
    total_fixed += gap * kids.len().saturating_sub(1) as f32;
    let available = if horizontal { rect.w } else { rect.h };
    let free = (available - total_fixed).max(0.0);
    let mut cursor = if horizontal { rect.x } else { rect.y };
    for (i, (c, p)) in kids.iter().enumerate() {
        let mg = node_margins(c).unwrap_or_default();
        if i > 0 {
            cursor += gap;
        }
        if let Node::Space(s) = c {
            cursor += free * s.weight.unwrap_or(1.0).max(0.001) / total_weight.max(0.001);
            continue;
        }
        let sz = &sizes[i];
        if horizontal {
            let x = cursor + mg.left;
            let y = rect.y
                + mg.top
                + align_cross(
                    gravity_eff(env, p, c, node.gravity.clone()).as_deref(),
                    rect.h - mg.sum_y(),
                    sz.h,
                    true,
                );
            place_node(env, c, Rect { x, y, w: sz.w, h: sz.h }, p, gaps, out);
            cursor += sz.w + mg.sum_x();
        } else {
            let x = rect.x
                + mg.left
                + align_cross(
                    gravity_eff(env, p, c, node.gravity.clone()).as_deref(),
                    rect.w - mg.sum_x(),
                    sz.w,
                    false,
                );
            let y = cursor + mg.top;
            place_node(env, c, Rect { x, y, w: sz.w, h: sz.h }, p, gaps, out);
            cursor += sz.h + mg.sum_y();
        }
    }
}

fn placed_bounds(items: &[Item]) -> Option<Rect> {
    let mut x1 = f32::INFINITY;
    let mut y1 = f32::INFINITY;
    let mut x2 = f32::NEG_INFINITY;
    let mut y2 = f32::NEG_INFINITY;
    for it in items {
        let b = it.rect();
        if b.w <= 0.0 || b.h <= 0.0 {
            continue;
        }
        x1 = x1.min(b.x);
        y1 = y1.min(b.y);
        x2 = x2.max(b.x + b.w);
        y2 = y2.max(b.y + b.h);
    }
    if !x1.is_finite() {
        None
    } else {
        Some(Rect {
            x: x1,
            y: y1,
            w: x2 - x1,
            h: y2 - y1,
        })
    }
}

fn fit_band_items(rect: Rect, items: &mut [Item], preserve_position: bool) {
    let bounds = match placed_bounds(items) {
        Some(b) => b,
        None => return,
    };
    let pad_x = rect.w * 0.025;
    let pad_y = rect.h * 0.04;
    let inner = Rect {
        x: rect.x + pad_x,
        y: rect.y + pad_y,
        w: (rect.w - pad_x * 2.0).max(1.0),
        h: (rect.h - pad_y * 2.0).max(1.0),
    };
    let overflow = bounds.x < inner.x - 0.5
        || bounds.y < inner.y - 0.5
        || bounds.x + bounds.w > inner.x + inner.w + 0.5
        || bounds.y + bounds.h > inner.y + inner.h + 0.5;
    if !overflow {
        return;
    }
    let scale = (inner.w / bounds.w.max(1.0))
        .min(inner.h / bounds.h.max(1.0))
        .min(1.0);
    if preserve_position && scale >= 0.999 {
        let tx = if bounds.x < inner.x {
            inner.x - bounds.x
        } else if bounds.x + bounds.w > inner.x + inner.w {
            inner.x + inner.w - (bounds.x + bounds.w)
        } else {
            0.0
        };
        let ty = if bounds.y < inner.y {
            inner.y - bounds.y
        } else if bounds.y + bounds.h > inner.y + inner.h {
            inner.y + inner.h - (bounds.y + bounds.h)
        } else {
            0.0
        };
        for it in items {
            let b = it.rect_mut();
            b.x += tx;
            b.y += ty;
        }
        return;
    }
    let offset_x = inner.x + (inner.w - bounds.w * scale) / 2.0 - bounds.x * scale;
    let offset_y = inner.y + (inner.h - bounds.h * scale) / 2.0 - bounds.y * scale;
    for it in items {
        let b = it.rect_mut();
        b.x = b.x * scale + offset_x;
        b.y = b.y * scale + offset_y;
        b.w *= scale;
        b.h *= scale;
        if let Item::Text(t) = it {
            t.font_size *= scale;
            t.line_height *= scale;
            t.tracking *= scale;
        }
    }
}

/// layout.ts `clearArtFromScaledText()`: at fontScale != 1 the ink of a text node
/// grows but a sibling art slot keeps its vendor frame, so the art is moved out
/// of the measured penetration along the axis that packed them. xpan_1 is the
/// proof: the scaled imageParams line overlaps the HASSELBLAD bar by 10.85 units
/// and the frozen app translates the bar right by that + 0.5, putting its right
/// edge at 305.9 units; without this pass 1.22 keeps the bar at 295 units and the
/// right edge misses the reference by ~101 canvas px (Δ255 block diff).
/// `font_scale == 1` returns before touching a box, so the 1.0 arm cannot move.
fn clear_art_from_scaled_text(items: &mut [Item], font_scale: f32) {
    if (font_scale - 1.0).abs() < 1e-9 {
        return;
    }
    for ti in 0..items.len() {
        let t = match &items[ti] {
            Item::Text(t) => t.rect,
            _ => continue,
        };
        if t.w <= 0.0 || t.h <= 0.0 {
            continue;
        }
        for ai in 0..items.len() {
            if ai == ti {
                continue;
            }
            let a = match &items[ai] {
                Item::Image(i) => i.rect,
                _ => continue,
            };
            if a.w <= 0.0 || a.h <= 0.0 {
                continue;
            }
            let ox = (t.x + t.w).min(a.x + a.w) - t.x.max(a.x);
            let oy = (t.y + t.h).min(a.y + a.h) - t.y.max(a.y);
            if ox <= 0.0 || oy <= 0.0 {
                continue;
            }
            let smaller = (t.w * t.h).min(a.w * a.h);
            if smaller <= 0.0 || (ox * oy) / smaller < 0.02 {
                continue;
            }
            if let Item::Image(im) = &mut items[ai] {
                if im.rect.x >= t.x {
                    im.rect.x += ox + 0.5;
                } else {
                    im.rect.y += oy + 0.5;
                }
            }
        }
    }
}

fn band_inner_box(rect_box: Rect) -> Rect {
    // Same 2.5% / 4% inset fit_band_items() keeps.
    let pad_x = rect_box.w * 0.025;
    let pad_y = rect_box.h * 0.04;
    Rect {
        x: rect_box.x + pad_x,
        y: rect_box.y + pad_y,
        w: (rect_box.w - pad_x * 2.0).max(1.0),
        h: (rect_box.h - pad_y * 2.0).max(1.0),
    }
}

fn shares_cross_axis(a: &Rect, b: &Rect, axis: char) -> bool {
    let overlap = if axis == 'x' {
        (a.x + a.w).min(b.x + b.w) - a.x.max(b.x)
    } else {
        (a.y + a.h).min(b.y + b.h) - a.y.max(b.y)
    };
    overlap > 0.5
}

/// layout.ts `annotateBrandFree()`: empty room around each brand slot, after
/// the band content has been placed (and clamped). Text/image siblings only.
fn annotate_brand_free(band_box: Rect, items: &mut [Item]) {
    let inner = band_inner_box(band_box);
    let boxes: Vec<(bool, bool, Rect)> = items
        .iter()
        .map(|it| {
            (
                matches!(it, Item::Text(_)),
                matches!(it, Item::Image(_)),
                *it.rect(),
            )
        })
        .collect();
    for idx in 0..items.len() {
        let (is_text, is_image, b) = boxes[idx];
        if is_text || !is_image {
            continue;
        }
        let brand = matches!(&items[idx], Item::Image(im) if im.is_brand_logo);
        if !brand {
            continue;
        }
        let mut right = inner.x + inner.w - (b.x + b.w);
        let mut left = b.x - inner.x;
        let mut bottom = inner.y + inner.h - (b.y + b.h);
        let mut top = b.y - inner.y;
        for (j, (o_is_text, o_is_image, o)) in boxes.iter().enumerate() {
            if j == idx || !(*o_is_text || *o_is_image) || o.w <= 0.0 || o.h <= 0.0 {
                continue;
            }
            if shares_cross_axis(&b, o, 'y') {
                if o.x >= b.x + b.w - 0.5 {
                    right = right.min(o.x - (b.x + b.w));
                } else if o.x + o.w <= b.x + 0.5 {
                    left = left.min(b.x - (o.x + o.w));
                } else {
                    right = right.min(0.0);
                    left = left.min(0.0);
                }
            }
            if shares_cross_axis(&b, o, 'x') {
                if o.y >= b.y + b.h - 0.5 {
                    bottom = bottom.min(o.y - (b.y + b.h));
                } else if o.y + o.h <= b.y + 0.5 {
                    top = top.min(b.y - (o.y + o.h));
                } else {
                    bottom = bottom.min(0.0);
                    top = top.min(0.0);
                }
            }
        }
        if let Item::Image(im) = &mut items[idx] {
            im.brand_free = Some([left.max(0.0), right.max(0.0), top.max(0.0), bottom.max(0.0)]);
        }
    }
}

/// layout.ts `layoutBoxForBand()`: a ±90° band lays its content out in the
/// pre-rotation rect (the final box with w/h swapped about the same centre).
fn layout_box_for_band(band: &Band, rect: Rect) -> Rect {
    let rot = ((band.rotation.unwrap_or(0.0) % 360.0) + 360.0) % 360.0;
    if rot != 90.0 && rot != 270.0 {
        return rect;
    }
    let cx = rect.x + rect.w / 2.0;
    let cy = rect.y + rect.h / 2.0;
    Rect {
        x: cx - rect.h / 2.0,
        y: cy - rect.w / 2.0,
        w: rect.h,
        h: rect.w,
    }
}

fn band_rect(style: &Style, band: &Band, photo: Rect, _w: f32, _h: f32) -> Option<Rect> {
    let m = style.margins;
    let frame_w = photo.x + photo.w + m.right;
    Some(match band.direction.as_str() {
        "top" => Rect {
            x: 0.0,
            y: 0.0,
            w: frame_w,
            h: band.thickness.unwrap_or(m.top),
        },
        "bottom" => Rect {
            x: 0.0,
            y: photo.y + photo.h,
            w: frame_w,
            h: band.thickness.unwrap_or(m.bottom),
        },
        "left" => Rect {
            x: 0.0,
            y: photo.y,
            w: band.thickness.unwrap_or(m.left),
            h: photo.h,
        },
        "right" => Rect {
            x: photo.x + photo.w,
            y: photo.y,
            w: band.thickness.unwrap_or(m.right),
            h: photo.h,
        },
        "overlay" => {
            let w = band.width.unwrap_or(photo.w);
            let h = band.thickness.unwrap_or(photo.h);
            let (gh, gv) = gravity_parts(band.gravity.as_deref());
            let x = match gh {
                'r' => photo.x + photo.w - w - band.offset.0,
                'c' => photo.x + (photo.w - w) / 2.0 + band.offset.0,
                _ => photo.x + band.offset.0,
            };
            let y = match gv {
                'b' => photo.y + photo.h - h - band.offset.1,
                'c' => photo.y + (photo.h - h) / 2.0 + band.offset.1,
                _ => photo.y + band.offset.1,
            };
            Rect { x, y, w, h }
        }
        other => {
            return {
                eprintln!("E2b: band.direction '{other}' unsupported");
                None
            }
        }
    })
}

fn place_band_root(
    env: &Env,
    band: &Band,
    rect: Rect,
    path: &str,
    preserve_position: bool,
    clamp: bool,
    gaps: &mut Gaps,
) -> Vec<Item> {
    let mut out = Vec::new();
    let band_kids = visible_pairs(env, &band.children, path, gaps);
    if band_kids.is_empty() {
        return out;
    }
    if band_kids.len() == 1 {
        let (child, p) = &band_kids[0];
        let mg = node_margins(child).unwrap_or_default();
        let m = intrinsic(env, child, p, gaps);
        let (mw, mh) = match child {
            Node::Group(g) if g.match_width => (rect.w - mg.sum_x(), m.h),
            _ => (m.w, m.h),
        };
        let (mw, mh) = match child {
            Node::Group(g) if g.match_height => (mw, rect.h - mg.sum_y()),
            _ => (mw, mh),
        };
        let g = gravity_eff(env, p, child, band.gravity.clone());
        let (gh, gv) = gravity_parts(g.as_deref());
        let content_w = rect.w - mg.sum_x();
        let content_h = rect.h - mg.sum_y();
        let x = rect.x
            + mg.left
            + match gh {
                'r' => content_w - mw,
                'c' => (content_w - mw) / 2.0,
                _ => 0.0,
            };
        let y = rect.y
            + mg.top
            + match gv {
                'b' => content_h - mh,
                'c' => (content_h - mh) / 2.0,
                _ => 0.0,
            };
        place_node(env, child, Rect { x, y, w: mw, h: mh }, p, gaps, &mut out);
    } else {
        let g = GroupNode {
            orientation: "row".into(),
            gravity: Some("center".into()),
            gap: 0.0,
            background: None,
            margins: Margins::default(),
            width: Some(rect.w),
            height: Some(rect.h),
            match_width: false,
            match_height: false,
            children: band.children.clone(),
        };
        layout_children(env, &g, rect, path, gaps, &mut out);
    }
    clear_art_from_scaled_text(&mut out, env.font_scale);
    if clamp {
        fit_band_items(rect, &mut out, preserve_position);
    }
    annotate_brand_free(rect, &mut out);
    out
}

fn band_visual_extent(b: &PlacedBand) -> Rect {
    if b.rotation.is_some() {
        return b.rect;
    }
    let mut rect: Option<Rect> = if b.background.is_some() { Some(b.rect) } else { None };
    for it in &b.items {
        let r = *it.rect();
        if r.w <= 0.0 || r.h <= 0.0 {
            continue;
        }
        rect = Some(match rect {
            None => r,
            Some(u) => Rect {
                x: u.x.min(r.x),
                y: u.y.min(r.y),
                w: (u.x + u.w).max(r.x + r.w) - u.x.min(r.x),
                h: (u.y + u.h).max(r.y + r.h) - u.y.min(r.y),
            },
        });
    }
    rect.unwrap_or(b.rect)
}

fn clamp_shift(total: f32, min: f32, max: f32) -> f32 {
    if max < min {
        min.max(0.0)
    } else {
        total.max(min).min(max)
    }
}

fn translate_band(b: &mut PlacedBand, tx: f32, ty: f32) {
    if tx == 0.0 && ty == 0.0 {
        return;
    }
    b.rect.x += tx;
    b.rect.y += ty;
    b.layout_rect.x += tx;
    b.layout_rect.y += ty;
    for it in &mut b.items {
        let r = it.rect_mut();
        r.x += tx;
        r.y += ty;
    }
}

fn is_movable_band(b: &PlacedBand) -> bool {
    b.direction == "overlay" || b.rotation.is_some()
}

/// Nine-grid anchor: accepts the controls domain (`leftTop`) and the hyphen
/// form (`left-top`); 'vendor' never reaches here.
fn anchor_axes(s: &str) -> (char, char) {
    let l = s.to_lowercase();
    let h = if l.contains("right") {
        'r'
    } else if l.contains("left") {
        'l'
    } else {
        'c'
    };
    let v = if l.contains("bottom") {
        'b'
    } else if l.contains("top") {
        't'
    } else {
        'c'
    };
    (h, v)
}

pub fn solve(style: &Style, env: &Env, gaps: &mut Gaps) -> Result<Layout, String> {
    gaps.add("env: text tokens model/imageParams/time/timeLocation resolved from the source photo's EXIF (IR stores no string)");
    gaps.add("env: text.font is a key, not a file -> StockAvenir/StockSysSans/StockVivo via the renderer key->file map; CSS weight applied as a variable wght axis");
    gaps.add("rule: image.scale=contain draw rect is a renderer rule (node box vs asset aspect); IR stores only the node box");
    gaps.add("rule: overlay band content is clamped to a 2.5%/4% inner box and translated (preservePosition) unless the band declares fit:\"none\"");
    if let Some(mode) = &style.reference_width_mode {
        if mode != "shortEdge" {
            gaps.add(format!("referenceWidthMode '{mode}' not implemented in E2b"));
        }
    }
    let refw = style.reference_width;
    let (base_w, base_photo_h) = if style.reference_width_mode.as_deref() == Some("shortEdge") {
        // layout.ts stockReferenceWidth(): referenceWidth maps to the photo's
        // short edge (max(1, pw/ph) multiplier on a landscape photo).
        if env.pw <= env.ph {
            (refw, refw * env.ph / env.pw)
        } else {
            (refw * env.pw / env.ph, refw)
        }
    } else {
        (refw, refw * env.ph / env.pw)
    };
    let m = style.margins;
    let w = m.left + base_w + m.right;
    let h = m.top + base_photo_h + m.bottom;
    let photo = Rect {
        x: m.left,
        y: m.top,
        w: base_w,
        h: base_photo_h,
    };
    let mut bands = Vec::new();
    for (bi, band) in style.bands.iter().enumerate() {
        let path = format!("b{bi}");
        let rect = band_rect(style, band, photo, w, h)
            .ok_or_else(|| format!("band {bi}: unsupported direction"))?;
        let layout_rect = layout_box_for_band(band, rect);
        let preserve = band.direction == "overlay";
        let clamp = band.fit.as_deref() != Some("none");
        let items = place_band_root(env, band, layout_rect, &path, preserve, clamp, gaps);
        bands.push(PlacedBand {
            direction: band.direction.clone(),
            rect,
            layout_rect,
            background: band.background.clone(),
            radius: band.radius,
            rotation: band.rotation,
            draw_first: band.draw_first,
            items,
        });
    }
    // render-v1 stockAnchor / stockOffsetXPct|YPct: translate the movable
    // watermark bands rigidly. Absent/0 skips the whole block (byte-identical).
    if env.ov.has_box_move() {
        let dx = (env.ov.stock_offset.0 / 100.0) * photo.w;
        let dy = (env.ov.stock_offset.1 / 100.0) * photo.w.min(photo.h);
        let movable: Vec<usize> = bands
            .iter()
            .enumerate()
            .filter(|(_, b)| is_movable_band(b))
            .map(|(i, _)| i)
            .collect();
        if !movable.is_empty() {
            let axes = env
                .ov
                .stock_anchor
                .as_deref()
                .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("vendor"))
                .map(anchor_axes);
            match axes {
                None => {
                    if dx != 0.0 || dy != 0.0 {
                        for &i in &movable {
                            let e = band_visual_extent(&bands[i]);
                            let tx = clamp_shift(dx, -e.x, w - (e.x + e.w));
                            let ty = clamp_shift(dy, -e.y, h - (e.y + e.h));
                            translate_band(&mut bands[i], tx, ty);
                        }
                    }
                }
                Some((ah, av)) => {
                    let mut union: Option<Rect> = None;
                    for &i in &movable {
                        let e = band_visual_extent(&bands[i]);
                        union = Some(match union {
                            None => e,
                            Some(u) => Rect {
                                x: u.x.min(e.x),
                                y: u.y.min(e.y),
                                w: (u.x + u.w).max(e.x + e.w) - u.x.min(e.x),
                                h: (u.y + u.h).max(e.y + e.h) - u.y.min(e.y),
                            },
                        });
                    }
                    if let Some(e) = union {
                        let tx0 = match ah {
                            'l' => photo.x - e.x,
                            'r' => photo.x + photo.w - (e.x + e.w),
                            _ => photo.x + (photo.w - e.w) / 2.0 - e.x,
                        };
                        let ty0 = match av {
                            't' => photo.y - e.y,
                            'b' => photo.y + photo.h - (e.y + e.h),
                            _ => photo.y + (photo.h - e.h) / 2.0 - e.y,
                        };
                        let tx = clamp_shift(tx0 + dx, -e.x, w - (e.x + e.w));
                        let ty = clamp_shift(ty0 + dy, -e.y, h - (e.y + e.h));
                        for &i in &movable {
                            translate_band(&mut bands[i], tx, ty);
                        }
                    }
                }
            }
        }
    }
    Ok(Layout {
        w,
        h,
        photo,
        bands,
    })
}
