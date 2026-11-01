//! Draw report: everything the renderer painted, so the gate can attribute
//! every pixel to a mark (`text` / `logo` / `photo` / decor) instead of
//! comparing whole images. Mirrors E2a's `schema-gaps.json` shape.

use serde::Serialize;

#[derive(Debug, Clone, Default, Serialize)]
pub struct RectJson {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
}

impl RectJson {
    pub fn new(x: f32, y: f32, w: f32, h: f32) -> Self {
        Self { x, y, w, h }
    }
    pub fn from_skia(r: skia_safe::Rect) -> Self {
        Self { x: r.left, y: r.top, w: r.width(), h: r.height() }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct ImageDraw {
    pub seq: u32,
    pub key: String,
    pub dst: RectJson,
    pub src: Option<RectJson>,
    pub sampling: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct TextDraw {
    pub seq: u32,
    pub s: String,
    pub family: String,
    pub weight: u16,
    pub size: f32,
    pub x: f32,
    pub y: f32,
    pub align: String,
    pub baseline: String,
    pub tracking: f32,
    pub measure: f32,
    pub shadow: bool,
    /// Em box of the painted line (for pixel-region attribution), from the
    /// frozen recorder model: 0.72 em above / 0.21 em below an alphabetic
    /// baseline, 0.465/0.465 around a middle baseline, 0.93 em below top.
    pub rect: RectJson,
    pub matrix: [f32; 6],
}

#[derive(Debug, Clone, Serialize)]
pub struct ShapeDraw {
    pub seq: u32,
    #[serde(default)]
    pub underlay: bool,
    pub kind: String,
    pub rect: RectJson,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub color: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize)]
pub struct Report {
    pub canvas: [i32; 2],
    pub template: String,
    pub variant: Option<String>,
    pub photo: Option<RectJson>,
    pub photo_seq: u32,
    pub photo_radius: f32,
    pub images: Vec<ImageDraw>,
    pub texts: Vec<TextDraw>,
    pub shapes: Vec<ShapeDraw>,
    pub gaps: Vec<String>,
    /// Pixels the template says are *decor* (gradients, blurred backdrops,
    /// panels). The comparator must not charge AA/dither differences here to
    /// the photo.
    pub decor: Vec<ShapeDraw>,
}

impl Report {
    pub fn gap(&mut self, msg: impl Into<String>) {
        let m = msg.into();
        if !self.gaps.contains(&m) {
            self.gaps.push(m);
        }
    }
}
