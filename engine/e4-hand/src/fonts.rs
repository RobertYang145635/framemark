//! Face table for the hand templates.
//!
//! The frozen cards name families in `ctx.font` stacks (`draw.ts` FONT_*); the
//! renderer resolves the FIRST family that the app has a face for, exactly as
//! Chromium's @font-face cascade does. Two facts the CSS forces:
//!
//! * `StockSysSans`/`OplusSans` are the same 472 560-byte file declared with
//!   `font-weight: 100 900` — a variable face, so a weight request is an
//!   instance (no synthetic bold).
//! * `Chakra Petch` only ships a 600 *italic* woff2; a normal-style request
//!   still matches it in Chromium's algorithm. Whether the face is loadable at
//!   all is reported, not guessed.
//!
//! Synthetic bold follows Blink: applied when the request is >= 600 and the
//! matched face's own weight is < 600 (`kSyntheticBoldWeightThreshold`).

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use skia_safe::{
    font_arguments::{variation_position::Coordinate, FontArguments, VariationPosition},
    Data, FontMgr, Typeface,
};

pub struct FaceDef {
    pub weight: u16,
    pub file: PathBuf,
    pub tf: Typeface,
    pub variable: bool,
    pub italic: bool,
}

pub struct Family {
    pub name: String,
    pub faces: Vec<FaceDef>,
}

pub struct Resolved {
    pub tf: Typeface,
    pub synthetic_bold: bool,
    pub file: PathBuf,
    pub face_weight: u16,
    pub variable: bool,
}

pub struct HandFonts {
    pub families: HashMap<String, Family>,
    pub missing: Vec<String>,
    pub fallback: Option<Typeface>,
    pub fallback_bold: Option<Typeface>,
    pub fallback_file: Option<String>,
}

impl HandFonts {
    pub fn load(assets: &Path, extra: &Path) -> Result<Self, String> {
        let mgr = FontMgr::new();
        let mut families = HashMap::new();
        let mut missing = Vec::new();
        let mut load = |name: &str, entries: Vec<(u16, PathBuf, bool, bool)>| {
            let mut faces = Vec::new();
            for (weight, path, variable, italic) in entries {
                match load_face(&mgr, &path) {
                    Ok(tf) => faces.push(FaceDef { weight, file: path, tf, variable, italic }),
                    Err(e) => missing.push(format!("{name} w{weight} {}: {e}", path.display())),
                }
            }
            if !faces.is_empty() {
                families.insert(name.to_string(), Family { name: name.to_string(), faces });
            }
        };

        let oppo = assets.join("system/oppo/fonts");
        let mi = assets.join("system/xiaomi/fonts");
        let vivo = assets.join("system/vivo/fonts");
        let app = assets.join("app");

        load(
            "Inter",
            vec![
                (400, extra.join("Inter-400.ttf"), false, false),
                (500, extra.join("Inter-500.ttf"), false, false),
                (600, extra.join("Inter-600.ttf"), false, false),
                (700, extra.join("Inter-700.ttf"), false, false),
            ],
        );
        load(
            "JetBrains Mono",
            vec![
                (400, extra.join("JetBrainsMono-400.ttf"), false, false),
                (500, extra.join("JetBrainsMono-500.ttf"), false, false),
            ],
        );
        load(
            "Caveat",
            vec![(400, extra.join("Caveat-400.ttf"), false, false), (600, extra.join("Caveat-600.ttf"), false, false)],
        );
        load("YouShe", vec![(400, app.join("YouSheBiaoTiHei.ttf"), false, false)]);
        let bebas = std::env::var("E4H_BEBAS_FILE")
            .map(std::path::PathBuf::from)
            .unwrap_or_else(|_| app.join("BebasNeue-Regular.ttf"));
        load("Bebas Neue", vec![(400, bebas, false, false)]);
        // The app ships a woff2, which the prebuilt FontMgr cannot read. The
        // same OFL face (Google Fonts, SemiBold Italic) is used as a stand-in;
        // the probe's measure-vs-oracle check proves whether the advances match.
        load(
            "Chakra Petch",
            vec![(600, extra.join("ChakraPetch-SemiBoldItalic.ttf"), false, true)],
        );
        load("Zeiss", vec![(700, app.join("ZeissFrutigerNext-Bold.ttf"), false, false)]);
        load(
            "OplusSans",
            vec![(400, oppo.join("SysSans-En-Regular.ttf"), true, false)],
        );
        load(
            "StockSysSans",
            vec![(400, oppo.join("SysSans-En-Regular.ttf"), true, false)],
        );
        load(
            "StockAvenir",
            vec![
                (400, oppo.join("AvenirNext-Regular.ttf"), false, false),
                (500, oppo.join("AvenirNext-Medium.ttf"), false, false),
                (600, oppo.join("AvenirNext-DemiBold.ttf"), false, false),
                (700, oppo.join("AvenirNext-Bold.ttf"), false, false),
            ],
        );
        load(
            "StockButler",
            vec![(500, oppo.join("ButlerMedium.otf"), false, false), (700, oppo.join("ButlerBold.otf"), false, false)],
        );
        load("StockFZ", vec![(400, oppo.join("FZCYSK.TTF"), false, false)]);
        load(
            "StockMiSans",
            vec![
                (400, mi.join("misans_latin_regular.ttf"), false, false),
                (600, mi.join("misans_latin_semibold.ttf"), false, false),
            ],
        );
        load(
            "StockOxanium",
            vec![(400, mi.join("Oxanium-Regular.ttf"), false, false), (500, mi.join("Oxanium-Medium.ttf"), false, false)],
        );
        load("StockLG1056", vec![(400, mi.join("LG1056-Regular.otf"), false, false)]);
        load("StockVivo", vec![(400, vivo.join("vivo-Regular.otf"), false, false)]);

        let (fallback, fallback_bold, fallback_file) = load_system_cjk(&mgr);
        if fallback.is_none() {
            missing.push("system CJK fallback (Microsoft YaHei) not found".into());
        }

        Ok(Self { families, missing, fallback, fallback_bold, fallback_file })
    }

    /// CSS weight selection + Blink synthetic-bold rule.
    pub fn resolve(&self, family: &str, requested: u16) -> Option<Resolved> {
        let fam = self.families.get(family)?;
        let exact = fam.faces.iter().find(|f| f.weight == requested);
        let pick = if let Some(f) = exact {
            f
        } else if requested >= 600 {
            fam.faces
                .iter()
                .filter(|f| f.weight >= requested)
                .min_by_key(|f| f.weight)
                .or_else(|| fam.faces.iter().filter(|f| f.weight < requested).max_by_key(|f| f.weight))?
        } else if requested <= 300 {
            fam.faces
                .iter()
                .filter(|f| f.weight <= requested)
                .max_by_key(|f| f.weight)
                .or_else(|| fam.faces.iter().filter(|f| f.weight > requested).min_by_key(|f| f.weight))?
        } else {
            // 400/500: nearest, with 400/500 preferred in order.
            if requested == 500 {
                fam.faces
                    .iter()
                    .find(|f| f.weight == 500)
                    .or_else(|| fam.faces.iter().find(|f| f.weight == 400))
                    .or_else(|| fam.faces.iter().filter(|f| f.weight < 500).max_by_key(|f| f.weight))
                    .or_else(|| fam.faces.iter().filter(|f| f.weight > 500).min_by_key(|f| f.weight))?
            } else {
                fam.faces
                    .iter()
                    .find(|f| f.weight == 400)
                    .or_else(|| fam.faces.iter().filter(|f| f.weight > 400).min_by_key(|f| f.weight))
                    .or_else(|| fam.faces.iter().filter(|f| f.weight < 400).max_by_key(|f| f.weight))?
            }
        };
        let synthetic_bold = requested >= 600 && !pick.variable && pick.weight < 600;
        // A variable face is one `font-weight: 100 900` resource: the CSS weight
        // picks a wght instance, not the face's default. This instance feeds both
        // shaping and the TextBlob, so gvar outline deltas apply to the ink
        // (advances stay from hmtx — the HarfBuzz delta model covers those).
        let tf = if pick.variable {
            weighted_face(&pick.tf, requested as f32)
        } else {
            pick.tf.clone()
        };
        Some(Resolved {
            tf,
            synthetic_bold,
            file: pick.file.clone(),
            face_weight: pick.weight,
            variable: pick.variable,
        })
    }

    /// D-021: Chromium's Canvas2D `textBaseline:'middle'` sits above Skia's
    /// `-(ascent+descent)/2` by a face-dependent fraction of the em.
    /// E2a/E2b measured Avenir 0.08, SysSans 0.044, vivo 0.10, MiSans 0.
    /// The hand-only faces are NOT calibrated yet — reported per card in phase 2.
    pub fn d021_em(&self, family: &str) -> f32 {
        let key = format!("E4H_D021_{}", family.to_uppercase().replace([' ', '-'], "_"));
        if let Ok(v) = std::env::var(&key) {
            if let Ok(x) = v.parse::<f32>() {
                return x;
            }
        }
        if let Ok(v) = std::env::var("E4H_D021_EM") {
            if let Ok(x) = v.parse::<f32>() {
                return x;
            }
        }
        match family {
            "StockAvenir" => 0.08,
            "StockSysSans" | "OplusSans" => 0.044,
            "StockVivo" => 0.10,
            "StockMiSans" => 0.0,
            // E4 batch-1 calibration on the signed goldens (ink-bbox alignment):
            // Bebas Neue 108 px simple-2 (native sat 2 resized px high at 0.08),
            // Chakra 39.07 px card-1 (text-region diff minimum at 0.06).
            "Bebas Neue" => 0.045,
            "Chakra Petch" => 0.06,
            // P3g-2: YouShe was riding the 0.08 default and sat that far high/left.
            // Swept through E4H_D021_YOUSHE on the four middle-baseline cards
            // (fm-vertical rotated 53.35 px, movie-1/2 28.72 px, color-walk 44.81 px):
            // v=0 leaves every line within 0.06 px of the reference (sub-pixel NCC),
            // v=0.08 leaves 2.0-5.2 px. The 2 vs 3 px split at the SAME size comes
            // from the two rasterizers rounding the 0.08 em offset differently, which
            // is why an integer fit looked like scatter. Evidence: p3g2/d021-sweep.json.
            "YouShe" => 0.0,
            _ => 0.08,
        }
    }

    pub fn missing_report(&self) -> Vec<String> {
        self.missing.clone()
    }
}

fn load_face(mgr: &FontMgr, path: &Path) -> Result<Typeface, String> {
    let data = Data::from_filename(path).ok_or_else(|| "missing".to_string())?;
    mgr.new_from_data(data, None).ok_or_else(|| "unreadable (woff2?)".to_string())
}

/// CSS font-weight on a variable face: wght instance + opsz clamped to the
/// face's minimum (Chromium's `font-optical-sizing:auto` clamps below 1010).
fn weighted_face(base: &Typeface, weight: f32) -> Typeface {
    let opsz = std::env::var("E4H_OPSZ")
        .ok()
        .and_then(|v| v.parse::<f32>().ok())
        .unwrap_or(1010.0);
    let coords = [
        Coordinate { axis: Coordinate::wght, value: weight },
        Coordinate { axis: Coordinate::opsz, value: opsz },
    ];
    let args = FontArguments::new().set_variation_design_position(VariationPosition { coordinates: &coords });
    base.clone_with_arguments(&args).unwrap_or_else(|| base.clone())
}

/// Chromium resolves a missing CJK glyph against "Microsoft YaHei" at the
/// requested CSS weight: 500 stays Regular, >=600 matches the Bold face.
fn load_system_cjk(mgr: &FontMgr) -> (Option<Typeface>, Option<Typeface>, Option<String>) {
    for (file, index) in [
        ("C:/Windows/Fonts/msyh.ttc", 0u32),
        ("C:/Windows/Fonts/msyhbd.ttc", 0u32),
        ("C:/Windows/Fonts/simhei.ttf", 0u32),
    ] {
        if let Some(data) = Data::from_filename(file) {
            if let Some(tf) = mgr.new_from_data(data, index) {
                let bold = Data::from_filename("C:/Windows/Fonts/msyhbd.ttc")
                    .and_then(|d| mgr.new_from_data(d, 0));
                return (Some(tf), bold, Some(format!("{file}#{index}")));
            }
        }
    }
    (None, None, None)
}

/// First family of a Canvas2D font stack (`600 48px Inter, "Microsoft YaHei"`).
pub fn first_family(stack: &str) -> String {
    stack
        .split(',')
        .next()
        .unwrap_or("")
        .trim()
        .trim_matches('"')
        .trim_matches('\'')
        .to_string()
}

/// Parse a Canvas2D `ctx.font` string as Chromium serialises it:
/// `[italic ] [bold | <weight> ] <size>px <family stack>`.
pub fn parse_font(font: &str) -> (Option<f32>, u16, bool, String) {
    let mut rest = font.trim();
    let mut italic = false;
    let mut weight = 400u16;
    if let Some(tail) = rest.strip_prefix("italic") {
        italic = true;
        rest = tail.trim_start();
    } else if let Some(tail) = rest.strip_prefix("oblique") {
        italic = true;
        rest = tail.trim_start();
    }
    if let Some(tail) = rest.strip_prefix("bold") {
        weight = 700;
        rest = tail.trim_start();
    } else if let Some(tail) = rest.strip_prefix("normal") {
        rest = tail.trim_start();
    }
    let Some((head, stack)) = rest.split_once("px ") else {
        return (None, weight, italic, rest.to_string());
    };
    let toks: Vec<&str> = head.split_whitespace().collect();
    let px = toks.last().and_then(|v| v.parse::<f32>().ok());
    if toks.len() >= 2 {
        if let Ok(w) = toks[0].parse::<u16>() {
            weight = w;
        }
    }
    (px, weight, italic, stack.trim().to_string())
}
