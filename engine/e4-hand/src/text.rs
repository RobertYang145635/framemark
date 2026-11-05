//! E2b — deterministic font loading and Canvas2D-shaped text.
//!
//! E2a hard-wired `AvenirNext-DemiBold.ttf`. E2b ports the app's font
//! key -> family -> file table (`core/system/fonts.ts` + `engine/data/fonts.json`)
//! and picks the face by the IR node's weight, so `avenir`, `sys`, `vivo` and
//! `misans` all resolve to the files Chromium loads for the same @font-face
//! stack. A system CJK fallback keeps a mixed Latin/CJK line from turning into
//! tofu; it is the same Microsoft YaHei face Chromium falls through to.
//!
//! The shaping path is unchanged from T1c/E2a: HarfBuzz (SkShaper) + SkTextBlob,
//! letter spacing applied per glyph by the shaper.

#![allow(dead_code)]

use std::collections::HashMap;

use skia_safe::{
    font_arguments::{variation_position::Coordinate, FontArguments, VariationPosition},
    shaper::{
        self,
        run_handler::{Buffer, RunInfo},
        RunHandler,
    },
    Canvas, Color, Data, Font, FontHinting, FontMgr, FontStyle, GlyphId, Matrix, Paint, Point,
    Rect, Shaper, TextBlob, TextBlobBuilder, Typeface, Vector,
};

struct Family {
    regular: Typeface,
    medium: Option<Typeface>,
    demibold: Option<Typeface>,
    bold: Option<Typeface>,
}

impl Family {
    fn new(
        regular: Typeface,
        medium: Option<Typeface>,
        demibold: Option<Typeface>,
        bold: Option<Typeface>,
    ) -> Self {
        Self {
            regular,
            medium,
            demibold,
            bold,
        }
    }
    fn pick(&self, weight: i32) -> &Typeface {
        if weight >= 700 {
            self.bold
                .as_ref()
                .or(self.demibold.as_ref())
                .or(self.medium.as_ref())
                .unwrap_or(&self.regular)
        } else if weight >= 600 {
            self.demibold
                .as_ref()
                .or(self.medium.as_ref())
                .or(self.bold.as_ref())
                .unwrap_or(&self.regular)
        } else if weight >= 500 {
            self.medium
                .as_ref()
                .or(self.demibold.as_ref())
                .unwrap_or(&self.regular)
        } else {
            &self.regular
        }
    }
}

pub struct FontSet {
    families: HashMap<&'static str, Family>,
    fallback: Option<Typeface>,
}

fn load_face(mgr: &FontMgr, path: &std::path::Path) -> Result<Typeface, String> {
    let data =
        Data::from_filename(path).ok_or_else(|| format!("font missing: {}", path.display()))?;
    mgr.new_from_data(data, None)
        .ok_or_else(|| format!("font unreadable: {}", path.display()))
}

/// CSS `font-weight` on a variable face: clone with a `wght` design coordinate.
/// Static faces (StockVivo) simply return the base face.
fn weighted_face(base: &Typeface, weight: f32) -> Typeface {
    // SysSans carries wght 100..700 and opsz 1010..1090. Chromium's default
    // `font-optical-sizing:auto` maps opsz to the CSS font-size; every size in
    // these cards is far below 1010, so the axis clamps to its minimum. The
    // default 1050 is visibly narrower, so set it explicitly (other faces
    // ignore axes they do not have).
    let coords = [
        Coordinate {
            axis: Coordinate::wght,
            value: weight,
        },
        Coordinate {
            axis: Coordinate::opsz,
            value: 1010.0,
        },
    ];
    let args = FontArguments::new().set_variation_design_position(VariationPosition {
        coordinates: &coords,
    });
    base.clone_with_arguments(&args)
        .unwrap_or_else(|| base.clone())
}

impl FontSet {
    /// `assets` is `framemark/engine/assets/fonts`.
    pub fn load(assets: &std::path::Path) -> Result<Self, String> {
        let mgr = FontMgr::new();
        let app = assets.join("app");
        let sys = assets.join("system");
        let oppo = sys.join("oppo").join("fonts");
        let vivo = sys.join("vivo").join("fonts");
        let mi = sys.join("xiaomi").join("fonts");
        let mut families = HashMap::new();

        let avenir_reg = load_face(&mgr, &oppo.join("AvenirNext-Regular.ttf"))?;
        let avenir_med = load_face(&mgr, &oppo.join("AvenirNext-Medium.ttf")).ok();
        let avenir_demi = load_face(&mgr, &oppo.join("AvenirNext-DemiBold.ttf")).ok();
        let avenir_bold = load_face(&mgr, &oppo.join("AvenirNext-Bold.ttf")).ok();
        families.insert(
            "StockAvenir",
            Family::new(avenir_reg, avenir_med, avenir_demi, avenir_bold),
        );

        let sys_base = load_face(&mgr, &oppo.join("SysSans-En-Regular.ttf"))?;
        // SysSans is a variable wght 100..700 face; the oracle asks for 600/500,
        // and even 400 must carry the clamped opsz=1010 instance.
        let sys_reg = weighted_face(&sys_base, 400.0);
        let sys_med = weighted_face(&sys_base, 500.0);
        let sys_demi = weighted_face(&sys_base, 600.0);
        let sys_bold = weighted_face(&sys_base, 700.0);
        families.insert(
            "StockSysSans",
            Family::new(sys_reg, Some(sys_med), Some(sys_demi), Some(sys_bold)),
        );

        let thai_reg = load_face(&mgr, &oppo.join("NotoSansThai-Regular.ttf")).ok();
        let thai_bold = load_face(&mgr, &oppo.join("NotoSansThai-Bold.ttf")).ok();
        if let Some(reg) = thai_reg {
            families.insert("StockThai", Family::new(reg, None, None, thai_bold));
        }

        let vivo_reg = load_face(&mgr, &vivo.join("vivo-Regular.otf"))?;
        families.insert("StockVivo", Family::new(vivo_reg, None, None, None));

        let mi_reg = load_face(&mgr, &mi.join("misans_latin_regular.ttf"))?;
        let mi_semi = load_face(&mgr, &mi.join("misans_latin_semibold.ttf")).ok();
        // CSS matching over the two shipped faces (400/600): weight 500 falls
        // back to 400, not to the 600 face. Family::pick() reaches `medium`
        // before `demibold`, so the 500 slot must hold the regular face.
        families.insert(
            "StockMiSans",
            Family::new(mi_reg.clone(), Some(mi_reg), mi_semi, None),
        );

        let ox_reg = load_face(&mgr, &mi.join("Oxanium-Regular.ttf")).ok();
        let ox_med = load_face(&mgr, &mi.join("Oxanium-Medium.ttf")).ok();
        if let Some(reg) = ox_reg {
            families.insert("StockOxanium", Family::new(reg, ox_med, None, None));
        }
        let lg_reg = load_face(&mgr, &mi.join("LG1056-Regular.otf")).ok();
        if let Some(reg) = lg_reg {
            families.insert("StockLG1056", Family::new(reg, None, None, None));
        }

        let butler_med = load_face(&mgr, &oppo.join("ButlerMedium.otf")).ok();
        let butler_bold = load_face(&mgr, &oppo.join("ButlerBold.otf")).ok();
        if let Some(reg) = butler_med.clone() {
            families.insert(
                "StockButler",
                Family::new(reg, butler_med, butler_bold.clone(), butler_bold),
            );
        }
        if let Ok(fz) = load_face(&mgr, &oppo.join("FZCYSK.TTF")) {
            families.insert("StockFZ", Family::new(fz, None, None, None));
        }
        if let Ok(zeiss) = load_face(&mgr, &app.join("ZeissFrutigerNext-Bold.ttf")) {
            families.insert("Zeiss", Family::new(zeiss, None, None, None));
        }

        // Chromium's stack falls through `Inter` to "Microsoft YaHei" for CJK.
        // Only used when the selected face has no glyph for a character.
        let fallback = mgr
            .match_family_style("Microsoft YaHei", FontStyle::normal())
            .or_else(|| mgr.match_family_style("Microsoft YaHei UI", FontStyle::normal()))
            .or_else(|| {
                mgr.match_family_style_character("", FontStyle::normal(), &["zh-Hans"], 0x5468)
            })
            .or_else(|| load_face(&mgr, &oppo.join("FZCYSK.TTF")).ok());
        println!(
            "e2b.fonts root={} families={} fallback={}",
            assets.display(),
            families.len(),
            fallback
                .as_ref()
                .map(|f| f.family_name())
                .unwrap_or_else(|| "<none>".into())
        );
        Ok(Self { families, fallback })
    }

    pub fn resolve(&self, key: &str, weight: i32) -> &Typeface {
        let family = font_key_family(key);
        let fam = self
            .families
            .get(family)
            .or_else(|| self.families.get("StockSysSans"))
            .expect("StockSysSans");
        fam.pick(weight)
    }

    /// D-021: Chromium's Canvas2D `textBaseline:'middle'` sits above Skia's
    /// `-(ascent+descent)/2` by a face-dependent fraction of the em. E2a proved
    /// 0.08 em for Avenir; E2b calibrates the other faces (reported per style).
    pub fn d021_em(&self, key: &str) -> f32 {
        match font_key_family(key) {
            "StockAvenir" => 0.08,
            // E2b calibration: SysSans ink at weight 600/500/400 sits 0.036 em
            // lower than Avenir's D-021 constant; 0.044 puts the ink bbox on the
            // oracle (1 resized px at fs 52.78).
            "StockSysSans" => 0.044,
            "StockVivo" => 0.10,
            "StockMiSans" => 0.0,
            _ => 0.08,
        }
    }

    pub fn face_for(&self, base: &Typeface, text: &str) -> Typeface {
        if let Some(fb) = &self.fallback {
            let missing = text.chars().any(|c| base.unichar_to_glyph(c as i32) == 0);
            if missing {
                return fb.clone();
            }
        }
        base.clone()
    }

    pub fn fallback_face(&self) -> Option<Typeface> {
        self.fallback.clone()
    }
}

/// `core/system/fonts.ts` `FONT_FAMILIES` — the IR's `text.font` key -> the
/// first family in the app's CSS stack. Not a file name: the files come from
/// `engine/data/fonts.json`/`engine/assets/fonts`.
pub fn font_key_family(key: &str) -> &'static str {
    match key.to_ascii_lowercase().as_str() {
        "avenir" => "StockAvenir",
        "butler" => "StockButler",
        "fz" => "StockFZ",
        "oxanium" => "StockOxanium",
        "misans" => "StockMiSans",
        "vivo" => "StockVivo",
        "lg1056" => "StockLG1056",
        "zeiss" => "Zeiss",
        _ => "StockSysSans", // sys / oplus / unknown
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum HAlign {
    Left,
    Center,
    Right,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum VBaseline {
    Alphabetic,
    Middle,
}

#[derive(Clone, Copy, Debug)]
pub struct Shadow {
    pub color: Color,
    pub sigma: f32,
    pub dx: f32,
    pub dy: f32,
}

pub struct TextSpec<'a> {
    pub text: &'a str,
    pub typeface: Typeface,
    pub size: f32,
    pub x: f32,
    pub y: f32,
    pub align: HAlign,
    pub baseline: VBaseline,
    pub tracking: f32,
    pub color: Color,
    pub shadow: Option<Shadow>,
    pub matrix: Option<Matrix>,
    /// Blink synthetic bold: requested weight >= 600 and the matched face's own
    /// weight < 600. Does not change advances (Skia fake-bold strokes in place).
    pub embolden: bool,
}

struct Run {
    font: Font,
    glyphs: Vec<GlyphId>,
    pos: Vec<Point>,
}

#[derive(Default)]
struct Collector {
    runs: Vec<Run>,
    glyphs: Vec<GlyphId>,
    positions: Vec<Point>,
    cur_font: Option<Font>,
}

impl RunHandler for Collector {
    fn begin_line(&mut self) {}

    fn run_info(&mut self, info: &RunInfo) {
        self.cur_font = Some(info.font.clone());
    }

    fn commit_run_info(&mut self) {}

    fn run_buffer(&mut self, info: &RunInfo) -> Buffer<'_> {
        let n = info.glyph_count;
        self.glyphs.clear();
        self.glyphs.resize(n, 0);
        self.positions.clear();
        self.positions.resize(n, Point::default());
        Buffer::new(&mut self.glyphs, &mut self.positions, None)
    }

    fn commit_run_buffer(&mut self, _info: &RunInfo) {
        let font = self.cur_font.clone().unwrap_or_default();
        self.runs.push(Run {
            font,
            glyphs: self.glyphs.clone(),
            pos: self.positions.clone(),
        });
    }

    fn commit_line(&mut self) {}
}

fn needs_glyph(c: char) -> bool {
    !c.is_control()
        && !c.is_whitespace()
        && !matches!(c as u32,
            0x200B..=0x200F | 0x202A..=0x202E | 0x2060..=0x206F |
            0xFE00..=0xFE0F | 0xE0100..=0xE01EF)
}

pub struct TextEngine {
    shaper: Shaper,
    pub subpixel: bool,
    pub linear_metrics: bool,
    pub hinting: FontHinting,
    pub edging: skia_safe::font::Edging,
    pub embolden: bool,
    pub stroke_w: f32,
    pub fallback: Option<Typeface>,
    pub fallback_bold: Option<Typeface>,
    /// `design.text.fontScale` after `textScaleOf` clamping. Blink's
    /// scaledContext multiplies only the px inside `ctx.font`, so painted and
    /// measured glyphs use `size * scale` while positions stay in design units.
    pub scale: f32,
}

impl TextEngine {
    pub fn new() -> Self {
        let shaper =
            Shaper::new_shape_dont_wrap_or_reorder(None).expect("HarfBuzz shaper unavailable");
        let embolden = match std::env::var("T1C_EMBOLDEN").ok() {
            Some(v) => v == "1" || v.parse::<f32>().map(|f| f > 0.0).unwrap_or(false),
            None => false,
        };
        let stroke_w = std::env::var("T1C_STROKE_W")
            .ok()
            .and_then(|v| v.parse::<f32>().ok())
            .unwrap_or(0.0);
        Self {
            shaper,
            subpixel: true,
            linear_metrics: true,
            hinting: FontHinting::None,
            edging: skia_safe::font::Edging::AntiAlias,
            embolden,
            stroke_w,
            fallback: None,
            fallback_bold: None,
            scale: 1.0,
        }
    }

    /// The size Blink actually rasterizes/measures: `size * scale`, truncated
    /// to two decimals by the canvas font shorthand.
    pub fn painted_size(&self, size: f32) -> f32 {
        let painted = (size as f64) * (self.scale as f64);
        (painted * 100.0).trunc() as f32 / 100.0
    }

    /// Missing CJK glyphs fall through to the selected fallback face at the
    /// requested CSS weight (Blink's family fallback keeps the weight; >=600
    /// reaches Bold). The whole line switches faces, matching Chromium's line
    /// shaping for these card stacks.
    fn face_for(&self, base: &Typeface, text: &str) -> Typeface {
        let missing = text
            .chars()
            .filter(|c| needs_glyph(*c))
            .any(|c| base.unichar_to_glyph(c as i32) == 0);
        if !missing {
            return base.clone();
        }
        let bold = base.font_style().weight() >= skia_safe::font_style::Weight::SEMI_BOLD;
        let pick = if bold {
            self.fallback_bold.as_ref().or(self.fallback.as_ref())
        } else {
            self.fallback.as_ref()
        };
        pick.cloned().unwrap_or_else(|| base.clone())
    }

    /// Characters that neither the chosen face nor its selected fallback can
    /// draw. Callers turn a non-empty result into a hard render error instead
    /// of accepting Skia's .notdef boxes as a finished frame.
    pub fn missing_glyphs(&self, base: &Typeface, text: &str) -> Vec<char> {
        let face = self.face_for(base, text);
        let mut missing = Vec::new();
        for c in text.chars().filter(|c| needs_glyph(*c)) {
            if face.unichar_to_glyph(c as i32) == 0 && !missing.contains(&c) {
                missing.push(c);
            }
        }
        missing
    }

    fn make_font(&self, tf: &Typeface, size: f32, embolden: bool) -> Font {
        // `scaledContext` rewrites the font px to `size * scale`; the canvas
        // font shorthand then keeps two decimals (12.078 -> 12.07,
        // 55.5555534 -> 55.55, measured against the oracle ops).
        let size = self.painted_size(size);
        let mut font = Font::from_typeface(tf.clone(), size);
        font.set_subpixel(self.subpixel);
        font.set_linear_metrics(self.linear_metrics);
        font.set_hinting(self.hinting);
        font.set_edging(self.edging);
        if self.embolden || embolden {
            font.set_embolden(true);
        }
        if std::env::var("T1C_BASELINE_SNAP").ok().as_deref() == Some("1") {
            font.set_baseline_snap(true);
        }
        font
    }

    /// HarfBuzz shaping with Canvas2D `letterSpacing` semantics. Returns the
    /// blob and the measured width (trailing spacing included, as `measureText`).
    pub fn shape(&self, text: &str, tf: &Typeface, size: f32, tracking: f32) -> (TextBlob, f32) {
        let tf = self.face_for(tf, text);
        // SkShaper takes letter spacing in em of the font it shapes (the
        // PAINTED size); Blink keeps `letterSpacing` in design px, unscaled by
        // scaledContext. Convert through the painted size so the shaper adds
        // exactly `tracking` px per gap at any font scale.
        let shaped_size = self.painted_size(size);
        let tracking_em = if shaped_size > 0.0 {
            tracking / shaped_size
        } else {
            0.0
        };
        let font = self.make_font(&tf, size, false);
        let mut font_run = Shaper::new_trivial_font_run_iterator(&font, text.len());
        let mut bidi = Shaper::new_trivial_bidi_run_iterator(0, text.len());
        let mut script = Shaper::new_trivial_script_run_iterator(0, text.len());
        let mut lang = Shaper::new_trivial_language_run_iterator("en", text.len());
        let mut collector = Collector::default();
        let _ = self.shaper.shape_with_iterators_and_features_and_options(
            text,
            &mut font_run,
            &mut bidi,
            &mut script,
            &mut lang,
            &[],
            f32::MAX,
            tracking_em,
            &mut collector,
        );
        if std::env::var("E4H_DEBUG_SHAPER").ok().as_deref() == Some("1") {
            for run in &collector.runs {
                eprintln!("shaper.glyphs={:?}", run.glyphs);
            }
        }
        let mut builder = TextBlobBuilder::new();
        let mut end = 0.0f32;
        for run in &collector.runs {
            let (glyphs, pos) = builder.alloc_run_pos(&run.font, run.glyphs.len(), None);
            glyphs.copy_from_slice(&run.glyphs);
            for (i, p) in run.pos.iter().enumerate() {
                pos[i] = *p;
            }
            if let Some(last) = run.pos.last() {
                let mut widths = [0.0f32];
                let last_glyph = [run.glyphs[run.glyphs.len() - 1]];
                run.font.get_widths(&last_glyph, &mut widths);
                end = end.max(last.x + widths[0]);
            }
        }
        let blob = builder.make().expect("empty text blob");
        (blob, end + tracking)
    }

    pub fn draw(&self, canvas: &Canvas, spec: &TextSpec) -> (f32, f32) {
        let tf = self.face_for(&spec.typeface, spec.text);
        let (blob, width) = self.shape(spec.text, &tf, spec.size, spec.tracking);
        let font = self.make_font(&tf, spec.size, spec.embolden);
        let (_, metrics) = font.metrics();
        let baseline_dy = match spec.baseline {
            VBaseline::Alphabetic => 0.0,
            VBaseline::Middle => {
                let default = -(metrics.ascent + metrics.descent) * 0.5;
                match std::env::var("T1B_BASELINE_SHIFT")
                    .ok()
                    .and_then(|v| v.parse::<f32>().ok())
                {
                    Some(shift) => default + shift,
                    None => default,
                }
            }
        };
        let dx = match spec.align {
            HAlign::Left => 0.0,
            HAlign::Center => -width * 0.5,
            HAlign::Right => -width,
        };
        let mut paint = Paint::default();
        paint.set_anti_alias(true);
        paint.set_color(spec.color);
        if let Some(sh) = spec.shadow {
            let sigma = (sh.sigma, sh.sigma);
            if let Some(filter) = skia_safe::image_filters::drop_shadow(
                Vector::new(sh.dx, sh.dy),
                sigma,
                skia_safe::Color4f::from(sh.color),
                None,
                None,
                None,
            ) {
                paint.set_image_filter(filter);
            }
        }
        canvas.save();
        if let Some(m) = spec.matrix {
            canvas.concat(&m);
        }
        canvas.draw_text_blob(&blob, (spec.x + dx, spec.y + baseline_dy), &paint);
        if self.stroke_w > 0.0 {
            let mut sp = paint.clone();
            sp.set_style(skia_safe::PaintStyle::Stroke);
            sp.set_stroke_width(self.stroke_w);
            sp.set_stroke_join(skia_safe::PaintJoin::Round);
            sp.set_stroke_cap(skia_safe::PaintCap::Round);
            canvas.draw_text_blob(&blob, (spec.x + dx, spec.y + baseline_dy), &sp);
        }
        canvas.restore();
        (width, spec.y + baseline_dy)
    }

    pub fn metrics(&self, tf: &Typeface, size: f32) -> (f32, f32, f32, f32) {
        let font = self.make_font(tf, size, false);
        let (_, m) = font.metrics();
        (m.ascent, m.descent, m.cap_height, m.x_height)
    }
}

pub fn measure(engine: &TextEngine, tf: &Typeface, text: &str, size: f32, tracking: f32) -> f32 {
    let (_, w) = engine.shape(text, tf, size, tracking);
    w
}

pub fn rect_from_xywh(x: f32, y: f32, w: f32, h: f32) -> Rect {
    Rect::from_xywh(x, y, w, h)
}

pub fn default_matrix(a: f32, b: f32, c: f32, d: f32, e: f32, f: f32) -> Matrix {
    Matrix::new_all(a, b, c, d, e, f, 0.0, 0.0, 1.0)
}
