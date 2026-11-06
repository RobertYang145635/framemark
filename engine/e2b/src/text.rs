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

#[derive(Clone)]
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
    /// W2: 每个厂商自己的中文字形（Latin 面只有拉丁/ASCII）。键是
    /// `font_key_family` 的族名；取不到就是缺字 GAP，落系统 CJK。
    cjk: HashMap<&'static str, Family>,
    fallback: Option<Typeface>,
}

fn load_face(mgr: &FontMgr, path: &std::path::Path) -> Result<Typeface, String> {
    let data =
        Data::from_filename(path).ok_or_else(|| format!("font missing: {}", path.display()))?;
    mgr.new_from_data(data, None)
        .ok_or_else(|| format!("font unreadable: {}", path.display()))
}

/// `.ttc` 容器按 **face 名** 选，而不是永远取 index 0。
/// 实测坑：`AvenirNext.ttc` 的 0 号 face 是 Bold（见 `data/fonts.json` 的
/// `ttc` 段）。`want` 可以是 postscript 名（`AvenirNext-DemiBold`）、
/// full name 或 family+subfamily；大小写/空格不敏感。
pub fn load_ttc_face(
    mgr: &FontMgr,
    path: &std::path::Path,
    want: &str,
) -> Result<Typeface, String> {
    let data =
        Data::from_filename(path).ok_or_else(|| format!("font missing: {}", path.display()))?;
    let norm = |s: &str| s.to_lowercase().replace([' ', '_', '-'], "");
    let want_n = norm(want);
    for i in 0..64u32 {
        let Some(tf) = mgr.new_from_data(data.clone(), Some(i)) else {
            break;
        };
        let family = tf.family_name();
        let ps = tf.post_script_name().unwrap_or_default();
        if norm(&ps) == want_n || norm(&family) == want_n {
            return Ok(tf);
        }
    }
    Err(format!("ttc face not found: {want} in {}", path.display()))
}

/// Compile-time embedded face (P3g-1 Thai fallback). Keeps the script face with
/// the binary so runtime asset copies stay unchanged.
fn load_embedded(mgr: &FontMgr, bytes: &[u8], label: &str) -> Result<Typeface, String> {
    mgr.new_from_data(Data::new_copy(bytes), None)
        .ok_or_else(|| format!("font unreadable: {label}"))
}

fn is_thai(c: char) -> bool {
    ('\u{0E00}'..='\u{0E7F}').contains(&c)
}

/// CJK 判定（汉字/假名/谚文/全角标点/竖排标点）。只用于决定是否切到厂商中文字形。
fn is_cjk(c: char) -> bool {
    matches!(c as u32,
        0x2E80..=0x2FFF | 0x3000..=0x303F | 0x3040..=0x30FF | 0x3400..=0x4DBF |
        0x4E00..=0x9FFF | 0xAC00..=0xD7AF | 0xF900..=0xFAFF | 0xFE30..=0xFE4F |
        0xFF00..=0xFFEF | 0x20000..=0x2FA1F)
}

fn is_thai_mark(c: char) -> bool {
    matches!(c as u32, 0x0E31 | 0x0E34..=0x0E3A | 0x0E47..=0x0E4E)
}

/// UAX #29-style Thai clusters for Canvas2D `letterSpacing`: Chromium inserts
/// spacing between grapheme clusters, while SkShaper applies it per glyph.
/// Only used on lines that took the Thai script face; other scripts keep the
/// existing per-glyph path byte-for-byte.
pub fn thai_clusters(text: &str) -> Vec<&str> {
    let mut out = Vec::new();
    let mut start = 0usize;
    for (i, c) in text.char_indices() {
        if i > 0 && !is_thai_mark(c) {
            out.push(&text[start..i]);
            start = i;
        }
    }
    if start < text.len() {
        out.push(&text[start..]);
    }
    out
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

        // P3g-1: official Noto Sans Thai Looped variable face (SIL OFL 1.1),
        // see FONT-LICENSES.md. The vendor's StockThai fallback draws the looped
        // design; the firmware subset under system/oppo/fonts is reference-only.
        let thai_reg = load_embedded(
            &mgr,
            include_bytes!("../assets/fonts/thai/NotoSansThaiLooped-Regular.ttf"),
            "NotoSansThaiLooped-Regular.ttf",
        )
        .ok();
        let thai_bold = load_embedded(
            &mgr,
            include_bytes!("../assets/fonts/thai/NotoSansThaiLooped-Bold.ttf"),
            "NotoSansThaiLooped-Bold.ttf",
        )
        .ok();
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

        // ── W2: 各厂商自己的中文字形 ─────────────────────────────────────
        // 来源（逐文件 SHA 在 engine/assets/fonts/manifest.json）：
        //   OPPO  SysSans-Hans-Regular.ttf —— 固件 OPPO Sans 4.0 SC（29,198 字）
        //   小米  MiSans-{Regular,Medium,Demibold}.ttf —— ODM 水印模块自带
        //   vivo  暂缺（GAP，落系统 CJK）；放入 vivo/vivoSans-CJK-*.ttf 即自动启用
        let mut cjk: HashMap<&'static str, Family> = HashMap::new();
        let mut cjk_log: Vec<String> = Vec::new();
        let mut add_cjk = |keys: &[&'static str], fam: Family, label: &str| {
            for k in keys {
                cjk.insert(k, fam.clone());
            }
            cjk_log.push(label.to_string());
        };
        if let Ok(hans) = load_face(&mgr, &oppo.join("SysSans-Hans-Regular.ttf")) {
            let fam = Family::new(
                weighted_face(&hans, 400.0),
                Some(weighted_face(&hans, 500.0)),
                Some(weighted_face(&hans, 600.0)),
                Some(weighted_face(&hans, 700.0)),
            );
            add_cjk(
                &["StockSysSans", "StockAvenir", "StockButler"],
                fam,
                "oppo=SysSans-Hans",
            );
        }
        let mi_hans_reg = load_face(&mgr, &mi.join("MiSans-Regular.ttf")).ok();
        if let Some(reg) = mi_hans_reg.clone() {
            let med = load_face(&mgr, &mi.join("MiSans-Medium.ttf")).ok();
            let demi = load_face(&mgr, &mi.join("MiSans-Demibold.ttf")).ok();
            let fam = Family::new(reg, med, demi.clone(), demi);
            add_cjk(
                &["StockMiSans", "StockLG1056", "StockOxanium"],
                fam,
                "xiaomi=MiSans",
            );
        }
        // vivo：文件名固定为 vivo/vivoSans-CJK-Regular.ttf（拿到真字再放进来）。
        if let Ok(vs) = load_face(&mgr, &vivo.join("vivoSans-CJK-Regular.ttf")) {
            add_cjk(
                &["StockVivo", "Zeiss"],
                Family::new(vs, None, None, None),
                "vivo=vivoSans-CJK",
            );
        } else {
            cjk_log.push("vivo=<GAP>".into());
        }

        // `.ttc` 探针（证据用）：`E2B_TTC_PROBE=1` 时按 face 名逐个加载并打印。
        if std::env::var("E2B_TTC_PROBE").ok().as_deref() == Some("1") {
            let ttc = oppo.join("AvenirNext.ttc");
            for want in [
                "AvenirNext-Regular",
                "AvenirNext-Medium",
                "AvenirNext-DemiBold",
                "AvenirNext-Bold",
            ] {
                match load_ttc_face(&mgr, &ttc, want) {
                    Ok(tf) => println!(
                        "e2b.ttc want={} family={} ps={}",
                        want,
                        tf.family_name(),
                        tf.post_script_name().unwrap_or_default()
                    ),
                    Err(e) => println!("e2b.ttc want={} ERR={}", want, e),
                }
            }
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
            "e2b.fonts root={} families={} cjk={} fallback={}",
            assets.display(),
            families.len(),
            cjk_log.join(","),
            fallback
                .as_ref()
                .map(|f| f.family_name())
                .unwrap_or_else(|| "<none>".into())
        );
        Ok(Self {
            families,
            cjk,
            fallback,
        })
    }

    /// W2: 指定 `font_key` 的厂商中文字形（按权重取）。取不到返回 None。
    pub fn cjk_face(&self, key: &str, weight: i32) -> Option<&Typeface> {
        self.cjk.get(font_key_family(key)).map(|f| f.pick(weight))
    }

    /// W2: 仅当这一行确实含 CJK、且厂商中文字形覆盖整行时返回它。
    /// 逻辑与 `script_face` 相同（整行切换），避免半行 Latin 半行 CJK 时
    /// 基线/字宽混排产生偏差。
    pub fn cjk_face_for(&self, key: &str, text: &str, weight: i32) -> Option<Typeface> {
        let has_cjk = text.chars().any(is_cjk);
        if !has_cjk {
            return None;
        }
        let tf = self.cjk_face(key, weight)?;
        let missing = text
            .chars()
            .any(|c| !c.is_whitespace() && tf.unichar_to_glyph(c as i32) == 0);
        if missing {
            return None;
        }
        Some(tf.clone())
    }

    /// CJK 面的 D-021 常数：按厂商取该族已有标定（MiSans 0.0 / OPPO 0.044 / vivo 0.10）。
    pub fn cjk_d021_em(&self, key: &str) -> f32 {
        match font_key_family(key) {
            "StockSysSans" | "StockAvenir" | "StockButler" => 0.044,
            "StockMiSans" | "StockLG1056" | "StockOxanium" => 0.0,
            "StockVivo" | "Zeiss" => 0.10,
            _ => 0.0,
        }
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

    /// P3g-1 script-range fallback: when every glyph the base face lacks is a
    /// Thai codepoint (U+0E00–U+0E7F) and the Thai face covers the whole line,
    /// return the Thai face. Any other missing-script mix keeps the previous
    /// single-fallback behaviour, so non-Thai lines are untouched.
    ///
    /// `E2B_DISABLE_SCRIPT_FALLBACK=1` is the red-proof switch: it restores the
    /// pre-P3g-1 chain (base -> Microsoft YaHei) without touching the code.
    pub fn script_face(&self, base: &Typeface, text: &str, weight: i32) -> Option<Typeface> {
        if std::env::var("E2B_DISABLE_SCRIPT_FALLBACK").ok().as_deref() == Some("1") {
            return None;
        }
        let missing: Vec<char> = text
            .chars()
            .filter(|c| base.unichar_to_glyph(*c as i32) == 0)
            .collect();
        if missing.is_empty() || !missing.iter().all(|c| is_thai(*c)) {
            return None;
        }
        let fam = self.families.get("StockThai")?;
        let tf = fam.pick(weight);
        if text.chars().all(|c| tf.unichar_to_glyph(c as i32) != 0) {
            Some(tf.clone())
        } else {
            None
        }
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
    /// Script-range face chosen by `FontSet::script_face` for this line. Wins
    /// over the engine-wide CJK fallback when present.
    pub script_fallback: Option<Typeface>,
    /// W2: 厂商中文字形（`FontSet::cjk_face_for`）。低于 `script_fallback`、
    /// 高于引擎级系统 CJK。
    pub cjk_fallback: Option<Typeface>,
    pub size: f32,
    pub x: f32,
    pub y: f32,
    pub align: HAlign,
    pub baseline: VBaseline,
    pub tracking: f32,
    pub color: Color,
    pub shadow: Option<Shadow>,
    pub matrix: Option<Matrix>,
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

pub struct TextEngine {
    shaper: Shaper,
    pub subpixel: bool,
    pub linear_metrics: bool,
    pub hinting: FontHinting,
    pub edging: skia_safe::font::Edging,
    pub embolden: bool,
    pub stroke_w: f32,
    pub fallback: Option<Typeface>,
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
        }
    }

    fn face_for(&self, base: &Typeface, text: &str, fb: &Typeface) -> Typeface {
        let missing = text.chars().any(|c| base.unichar_to_glyph(c as i32) == 0);
        if missing {
            fb.clone()
        } else {
            base.clone()
        }
    }

    fn make_font(&self, tf: &Typeface, size: f32) -> Font {
        let mut font = Font::from_typeface(tf.clone(), size);
        font.set_subpixel(self.subpixel);
        font.set_linear_metrics(self.linear_metrics);
        font.set_hinting(self.hinting);
        font.set_edging(self.edging);
        if self.embolden {
            font.set_embolden(true);
        }
        if std::env::var("T1C_BASELINE_SNAP").ok().as_deref() == Some("1") {
            font.set_baseline_snap(true);
        }
        font
    }

    /// HarfBuzz shaping with Canvas2D `letterSpacing` semantics. Returns the
    /// blob, the measured width (trailing spacing included, as `measureText`),
    /// and the number of glyph id 0 (`.notdef`) slots in the shaped output.
    pub fn shape(
        &self,
        text: &str,
        tf: &Typeface,
        size: f32,
        tracking: f32,
    ) -> (Option<TextBlob>, f32, usize) {
        self.shape_with(text, tf, size, tracking, self.fallback.as_ref())
    }

    fn shape_with(
        &self,
        text: &str,
        tf: &Typeface,
        size: f32,
        tracking: f32,
        fb: Option<&Typeface>,
    ) -> (Option<TextBlob>, f32, usize) {
        // WP-X1 / F2-D1: empty text shapes to nothing and Skia's
        // `TextBlobBuilder::make()` returns `None` for it. `expect`-ing here
        // aborted the Android process (the same defect the hand backend had) —
        // a photo without an Exif sub-IFD leaves EXIF rows empty and reaches this
        // through measureText/drawFitted. Empty text also measures 0 in Canvas2D.
        if text.is_empty() {
            return (None, 0.0, 0);
        }
        let tf = match fb {
            Some(fb) => self.face_for(tf, text, fb),
            None => tf.clone(),
        };
        let tracking_em = if size > 0.0 { tracking / size } else { 0.0 };
        let font = self.make_font(&tf, size);
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
        let mut builder = TextBlobBuilder::new();
        let mut end = 0.0f32;
        let mut notdef = 0usize;
        for run in &collector.runs {
            notdef += run.glyphs.iter().filter(|g| **g == 0).count();
            let (glyphs, pos) = builder.alloc_run_pos(&run.font, run.glyphs.len(), None);
            glyphs.copy_from_slice(&run.glyphs);
            for (i, p) in run.pos.iter().enumerate() {
                pos[i] = *p;
            }
            // Rightmost glyph end, not just the last glyph: Thai clusters end
            // in zero-advance marks whose x sits before the base's advance.
            let mut widths = vec![0.0f32; run.glyphs.len()];
            run.font.get_widths(&run.glyphs, &mut widths);
            for (p, w) in run.pos.iter().zip(widths.iter()) {
                end = end.max(p.x + w);
            }
        }
        match builder.make() {
            Some(blob) => (Some(blob), end + tracking, notdef),
            None => (None, end + tracking, notdef),
        }
    }

    /// Returns `(advance width, baseline y, .notdef glyph count)`. A non-zero
    /// count means at least one codepoint fell through every face in the chain
    /// and was drawn as an empty box.
    pub fn draw(&self, canvas: &Canvas, spec: &TextSpec) -> (f32, f32, usize) {
        let fb = spec
            .script_fallback
            .as_ref()
            .or(spec.cjk_fallback.as_ref())
            .or(self.fallback.as_ref());
        let tf = match fb {
            Some(fb) => self.face_for(&spec.typeface, spec.text, fb),
            None => spec.typeface.clone(),
        };
        let (blob, width, notdef) = self.shape_with(spec.text, &tf, spec.size, spec.tracking, None);
        let font = self.make_font(&tf, spec.size);
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
        if let Some(blob) = &blob {
            canvas.draw_text_blob(blob, (spec.x + dx, spec.y + baseline_dy), &paint);
            if self.stroke_w > 0.0 {
                let mut sp = paint.clone();
                sp.set_style(skia_safe::PaintStyle::Stroke);
                sp.set_stroke_width(self.stroke_w);
                sp.set_stroke_join(skia_safe::PaintJoin::Round);
                sp.set_stroke_cap(skia_safe::PaintCap::Round);
                canvas.draw_text_blob(blob, (spec.x + dx, spec.y + baseline_dy), &sp);
            }
        }
        canvas.restore();
        (width, spec.y + baseline_dy, notdef)
    }

    /// Canvas2D `letterSpacing` applies between grapheme clusters. Shape each
    /// Thai cluster without tracking and place them at cumulative advances so
    /// the run width matches Chromium's measureText; per-glyph spacing would
    /// push combining marks apart.
    pub fn draw_clusters(
        &self,
        canvas: &Canvas,
        spec: &TextSpec,
        clusters: &[&str],
    ) -> (f32, f32, usize) {
        let fb = spec
            .script_fallback
            .as_ref()
            .or(spec.cjk_fallback.as_ref())
            .or(self.fallback.as_ref());
        let tf = match fb {
            Some(fb) => self.face_for(&spec.typeface, spec.text, fb),
            None => spec.typeface.clone(),
        };
        let mut blobs: Vec<(Option<TextBlob>, f32)> = Vec::with_capacity(clusters.len());
        let mut notdef = 0usize;
        let mut width = 0.0f32;
        for c in clusters {
            let (blob, w, nd) = self.shape_with(c, &tf, spec.size, 0.0, None);
            width += w;
            notdef += nd;
            blobs.push((blob, w));
        }
        width += spec.tracking * clusters.len() as f32;
        let font = self.make_font(&tf, spec.size);
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
        let mut acc = 0.0f32;
        for (blob, w) in &blobs {
            if let Some(blob) = blob {
                canvas.draw_text_blob(blob, (spec.x + dx + acc, spec.y + baseline_dy), &paint);
            }
            acc += w + spec.tracking;
        }
        if self.stroke_w > 0.0 {
            let mut sp = paint.clone();
            sp.set_style(skia_safe::PaintStyle::Stroke);
            sp.set_stroke_width(self.stroke_w);
            sp.set_stroke_join(skia_safe::PaintJoin::Round);
            sp.set_stroke_cap(skia_safe::PaintCap::Round);
            let mut acc = 0.0f32;
            for (blob, w) in &blobs {
                if let Some(blob) = blob {
                    canvas.draw_text_blob(blob, (spec.x + dx + acc, spec.y + baseline_dy), &sp);
                }
                acc += w + spec.tracking;
            }
        }
        canvas.restore();
        (width, spec.y + baseline_dy, notdef)
    }

    pub fn measure_clusters(&self, text: &str, tf: &Typeface, size: f32, tracking: f32) -> f32 {
        let clusters = thai_clusters(text);
        let base = match self.fallback.as_ref() {
            Some(fb) => self.face_for(tf, text, fb),
            None => tf.clone(),
        };
        let sum: f32 = clusters
            .iter()
            .map(|c| self.shape_with(c, &base, size, 0.0, None).1)
            .sum();
        sum + tracking * clusters.len() as f32
    }

    pub fn metrics(&self, tf: &Typeface, size: f32) -> (f32, f32, f32, f32) {
        let font = self.make_font(tf, size);
        let (_, m) = font.metrics();
        (m.ascent, m.descent, m.cap_height, m.x_height)
    }
}

pub fn measure(engine: &TextEngine, tf: &Typeface, text: &str, size: f32, tracking: f32) -> f32 {
    let (_, w, _) = engine.shape(text, tf, size, tracking);
    w
}

pub fn rect_from_xywh(x: f32, y: f32, w: f32, h: f32) -> Rect {
    Rect::from_xywh(x, y, w, h)
}

pub fn default_matrix(a: f32, b: f32, c: f32, d: f32, e: f32, f: f32) -> Matrix {
    Matrix::new_all(a, b, c, d, e, f, 0.0, 0.0, 1.0)
}
