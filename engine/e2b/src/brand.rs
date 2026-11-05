//! E3 (Xiaomi) — brand-slot substitution (`image.isBrandLogo`).
//!
//! The frozen app treats an `isBrandLogo` image node as a *slot*: the vendor
//! bitmap only supplies the box and ink metrics, and paint.ts draws the
//! wordmark of the photo's own EXIF brand instead (`core/system/assets.ts`
//! `resolveBrandSlot()`, `core/system/paint.ts` `brandSlotRect()`). Drawing the
//! baked vendor asset is the "xiaomi DC-S1" defect.
//!
//! This module ports the pure parts:
//!   * `matchBrand()` (make/model -> registry key),
//!   * `chooseBrandSlot()` (candidate ranking: wordmark shape, tone, aspect),
//!   * `brandSlotRect()` (ink-aware size + optical-centre placement),
//!   * ink bbox measurement (alpha > 8) of a decoded bitmap.
//!
//! The registry is derived from the app-bitmap tree the engine ships
//! (`assets/logos/app`), decoded to PNG because the T0b Skia prebuilt has no
//! WebP codec. Files are chosen exactly like `BRANDS[].files` in
//! `core/brands.ts` for the brands the fixtures exercise (prefix-scoped).

#![allow(dead_code)]

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use skia_safe::Image;

use crate::ir::Rect;

pub const WORDMARK_MIN_ASPECT: f32 = 1.6;
const INK_ALPHA_THRESHOLD: f32 = 8.0 / 255.0;
const SLOT_GAP_FLOOR: f32 = 2.0;
const SLOT_MARGIN_FALLBACK_UNITS: f32 = 8.0;

#[derive(Clone, Copy, Debug)]
pub struct InkBox {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
    pub cw: i32,
    pub ch: i32,
}

pub struct LogoImage {
    pub image: Image,
    pub path: PathBuf,
    pub w: i32,
    pub h: i32,
    pub ink: Option<InkBox>,
}

impl LogoImage {
    pub fn aspect(&self) -> f32 {
        if self.h > 0 {
            self.w as f32 / self.h as f32
        } else {
            0.0
        }
    }
}

pub struct BrandLib {
    dir: PathBuf,
    stems: Vec<String>,
    cache: HashMap<String, Option<LogoImage>>,
}

/// `core/brands.ts matchBrand()` — order matters (first hit wins).
pub fn match_brand(make: &str, model: &str) -> Option<&'static str> {
    let s = format!("{make} {model}").to_lowercase();
    let exact = make.trim().to_lowercase();
    if exact == "red" || exact == "arri" {
        return Some(if exact == "red" { "red" } else { "arri" });
    }
    let map: &[(&str, &[&str])] = &[
        ("laika", &["leica", "leitz"]),
        ("canon", &["canon"]),
        ("nikon", &["nikon"]),
        ("sony", &["sony", "ilce", "alpha"]),
        ("panasionic", &["panasonic", "dc-", "lumix"]),
        ("lumix", &["lumix"]),
        ("fushi", &["fuji", "fujifilm"]),
        ("hasu", &["hasselblad"]),
        ("aolinbasi", &["olympus", "om system"]),
        ("binde", &["pentax"]),
        ("ricoh", &["ricoh", "gr "]),
        ("caisi", &["zeiss", "carl zeiss"]),
        ("dji", &["dji", "mavic", "osmo"]),
        ("insta360", &["insta360"]),
        ("konica_minolta", &["konica minolta", "konicaminolta"]),
        ("contax", &["contax"]),
        ("minolta", &["minolta"]),
        ("konica", &["konica"]),
        ("yashica", &["yashica"]),
        ("rollei", &["rollei", "rolleiflex"]),
        ("voigtlander", &["voigtlander", "voigtländer"]),
        ("mamiya", &["mamiya"]),
        ("bronica", &["bronica", "zenza"]),
        ("kodak", &["kodak", "eastman"]),
        ("polaroid", &["polaroid"]),
        ("agfa", &["agfa"]),
        ("praktica", &["praktica", "practika"]),
        ("exakta", &["exakta"]),
        ("minox", &["minox"]),
        ("sinar", &["sinar"]),
        ("linhof", &["linhof"]),
        ("alpa", &["alpa"]),
        ("zenit", &["zenit"]),
        ("lomo", &["lomo", "lomography"]),
        ("seagull", &["seagull"]),
        ("casio", &["casio"]),
        ("cosina", &["cosina"]),
        ("horseman", &["horseman"]),
        ("graflex", &["graflex"]),
        ("sigma", &["sigma"]),
        ("tamron", &["tamron"]),
        ("tokina", &["tokina"]),
        ("samyang", &["samyang"]),
        ("phaseone", &["phase one", "phaseone", "leaf"]),
        ("gopro", &["gopro"]),
        ("nokia", &["nokia"]),
        ("motorola", &["motorola", "moto "]),
        ("google", &["google", "pixel"]),
        ("realme", &["realme"]),
        ("nothing", &["nothing"]),
        ("nubia", &["nubia", "zte "]),
        ("honor", &["honor"]),
        ("huawei", &["huawei"]),
        ("xiaomi", &["xiaomi", "redmi", "mi "]),
        ("oppo", &["oppo"]),
        ("vivo", &["vivo"]),
        ("samsung", &["samsung", "galaxy"]),
        ("iqoo", &["iqoo"]),
        ("meizu", &["meizu"]),
        ("yijia", &["oneplus", "one plus"]),
        ("apple", &["apple", "iphone"]),
    ];
    for (key, words) in map {
        if words.iter().any(|w| s.contains(w)) {
            return Some(key);
        }
    }
    None
}

/// Port of `core/system/assets.ts chooseBrandSlot()`: wordmark-shaped first,
/// then tone that reads on the card, then the widest candidate. Catalog cut
/// metadata is not carried into the engine, so the registry's `_black`/
/// `_white` suffix is the tone signal (the same fallback the app uses for
/// uncatalogued legacy files).
pub fn choose_brand_slot(lib: &mut BrandLib, key: &str, dark: bool) -> Option<String> {
    let mut candidates: Vec<(String, f32)> = Vec::new();
    let prefix = format!("{key}_");
    let stems = lib.stems.clone();
    for stem in stems {
        if stem != key && !stem.starts_with(&prefix) {
            continue;
        }
        if dark && stem.ends_with("_black") {
            continue;
        }
        if !dark && stem.ends_with("_white") {
            continue;
        }
        if lib.load_stem(&stem).is_none() {
            continue;
        }
        let aspect = match lib.cache.get(&stem).and_then(|v| v.as_ref()) {
            Some(logo) => logo.aspect(),
            None => continue,
        };
        if aspect <= 0.0 {
            continue;
        }
        candidates.push((stem, aspect));
    }
    if candidates.is_empty() {
        return None;
    }
    candidates.sort_by(|a, b| {
        let wa = (a.1 >= WORDMARK_MIN_ASPECT) as i32;
        let wb = (b.1 >= WORDMARK_MIN_ASPECT) as i32;
        wb.cmp(&wa)
            .then_with(|| tone_rank(&a.0, dark).cmp(&tone_rank(&b.0, dark)))
            .then_with(|| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal))
    });
    candidates.first().map(|(s, _)| s.clone())
}

fn tone_rank(file: &str, dark: bool) -> i32 {
    let white = file.ends_with("_white");
    let black = file.ends_with("_black");
    if dark {
        if white {
            0
        } else if black {
            2
        } else {
            1
        }
    } else if black {
        0
    } else if white {
        2
    } else {
        1
    }
}

/// Alpha bbox of a decoded bitmap (threshold 8/255, like `inkBoxOf()`).
pub fn ink_box(image: &Image) -> Option<InkBox> {
    // `from_encoded` yields a lazy image; peek_pixels() only works on a raster
    // image (the app measures the decoded <img>, so rasterise first).
    let raster = if image.is_lazy_generated() || image.peek_pixels().is_none() {
        image.to_raster_image(None)?
    } else {
        image.clone()
    };
    let pixmap = raster.peek_pixels()?;
    let cw = pixmap.width();
    let ch = pixmap.height();
    if cw <= 0 || ch <= 0 {
        return None;
    }
    let (mut min_x, mut min_y, mut max_x, mut max_y) = (cw, ch, -1, -1);
    for y in 0..ch {
        for x in 0..cw {
            if pixmap.get_alpha_f((x, y)) > INK_ALPHA_THRESHOLD {
                if x < min_x {
                    min_x = x;
                }
                if x > max_x {
                    max_x = x;
                }
                if y < min_y {
                    min_y = y;
                }
                if y > max_y {
                    max_y = y;
                }
            }
        }
    }
    if max_x < 0 {
        None
    } else {
        Some(InkBox {
            x: min_x,
            y: min_y,
            w: max_x - min_x + 1,
            h: max_y - min_y + 1,
            cw,
            ch,
        })
    }
}

impl BrandLib {
    /// `dir` holds the decoded `assets/logos/app/*.png` tree.
    pub fn load(dir: &Path) -> Self {
        let mut stems: Vec<String> = Vec::new();
        if let Ok(rd) = std::fs::read_dir(dir) {
            for e in rd.flatten() {
                let p = e.path();
                if p.extension().and_then(|v| v.to_str()) == Some("png") {
                    if let Some(stem) = p.file_stem().and_then(|v| v.to_str()) {
                        stems.push(stem.to_string());
                    }
                }
            }
        }
        stems.sort();
        println!("e3.brand_dir={} logos={}", dir.display(), stems.len());
        Self {
            dir: dir.to_path_buf(),
            stems,
            cache: HashMap::new(),
        }
    }

    pub fn image(&mut self, file: &str) -> Option<&LogoImage> {
        self.load_stem(file);
        self.cache.get(file).and_then(|v| v.as_ref())
    }

    fn load_stem(&mut self, stem: &str) -> Option<()> {
        if self.cache.contains_key(stem) {
            return Some(());
        }
        let path = self.dir.join(format!("{stem}.png"));
        let data = skia_safe::Data::from_filename(&path)?;
        let image = Image::from_encoded(data)?;
        let w = image.width();
        let h = image.height();
        let ink = ink_box(&image);
        self.cache.insert(
            stem.to_string(),
            Some(LogoImage {
                image,
                path,
                w,
                h,
                ink,
            }),
        );
        Some(())
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum H {
    Left,
    Center,
    Right,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum V {
    Top,
    Center,
    Bottom,
}

fn gravity_anchor(gravity: Option<&str>) -> (H, V) {
    let s = gravity.unwrap_or("leftTop");
    let lower = s.to_lowercase();
    let h = if lower.contains("right") {
        H::Right
    } else if (lower.contains("hor") || lower.contains("center")) && !lower.contains("left") {
        H::Center
    } else {
        H::Left
    };
    let v = if lower.contains("bottom") {
        V::Bottom
    } else if (lower.contains("verticalcenter") || lower.contains("center"))
        && !lower.contains("top")
    {
        V::Center
    } else {
        V::Top
    };
    (h, v)
}

fn ink_pads(ink: Option<&InkBox>) -> (f32, f32, f32, f32) {
    match ink {
        Some(i) if i.cw > 0 && i.ch > 0 => (
            i.x as f32 / i.cw as f32,
            (i.cw - i.x - i.w) as f32 / i.cw as f32,
            i.y as f32 / i.ch as f32,
            i.h as f32 / i.ch as f32,
        ),
        _ => (0.0, 0.0, 0.0, 1.0),
    }
}

pub struct SlotMetrics<'a> {
    pub sub: Option<&'a InkBox>,
    pub vendor: Option<&'a InkBox>,
    /// Declared slot->sibling margin in output px.
    pub margin_px: f32,
    /// The vendor bitmap's own transparent side pad in output px.
    pub pad_px: f32,
}

/// `paint.ts brandSlotRect()` — match the vendor's visible ink, grow into the
/// band's slack, never overlap; box/free are output pixels.
pub fn brand_slot_rect(
    sub_w: i32,
    sub_h: i32,
    rect_box: Rect,
    gravity: Option<&str>,
    free: Option<[f32; 4]>,
    metrics: &SlotMetrics,
) -> Rect {
    if sub_w <= 0 || sub_h <= 0 || rect_box.w <= 0.0 || rect_box.h <= 0.0 {
        return rect_box;
    }
    let asset_aspect = sub_w as f32 / sub_h as f32;
    let box_aspect = rect_box.w / rect_box.h;
    let (ha, va) = gravity_anchor(gravity);
    let (sl, sr, st, sh) = ink_pads(metrics.sub);
    let (vl, vr, vt, vh) = ink_pads(metrics.vendor);
    let has_ink = metrics.sub.is_some() && metrics.vendor.is_some();

    let (mut want_w, mut want_h) = if asset_aspect >= box_aspect {
        let want_h = if has_ink {
            (rect_box.h * vh) / sh
        } else {
            rect_box.h
        };
        (want_h * asset_aspect, want_h)
    } else {
        (rect_box.w, rect_box.w / asset_aspect)
    };

    if has_ink {
        let gap = SLOT_GAP_FLOOR.max(metrics.margin_px + metrics.pad_px);
        let gap_extra = SLOT_GAP_FLOOR.max(gap - metrics.margin_px);
        if let Some(f) = free {
            let slack_x = match ha {
                H::Center => f[0].min(f[1]) * 2.0,
                H::Left => f[1],
                H::Right => f[0],
            };
            let max_ink_w = if ha == H::Right {
                rect_box.w * (1.0 - vr) + slack_x - gap_extra
            } else {
                rect_box.w * (1.0 - vl) + slack_x
                    - if ha == H::Center {
                        gap_extra * 2.0
                    } else {
                        gap_extra
                    }
            };
            let max_w = (rect_box.w * 0.2).max(max_ink_w) / (1.0 - sl - sr).max(0.05);
            let slack_y = match va {
                V::Top => f[3],
                V::Bottom => f[2],
                V::Center => f[2].min(f[3]) * 2.0,
            };
            let max_h = rect_box.h + (slack_y - gap).max(0.0);
            let k = 1.0f32.min(max_w / want_w).min(max_h / want_h);
            want_w *= k;
            want_h *= k;
        }
    }

    let w = want_w;
    let h = want_h;
    let x = match ha {
        H::Right => rect_box.x + rect_box.w * (1.0 - vr) - w * (1.0 - sr),
        H::Center => rect_box.x + rect_box.w / 2.0 - w / 2.0,
        H::Left => rect_box.x + rect_box.w * vl - w * sl,
    };
    let mut y = if !has_ink && va != V::Center {
        if va == V::Bottom {
            rect_box.y + rect_box.h - h
        } else {
            rect_box.y
        }
    } else {
        rect_box.y + rect_box.h * (vt + vh / 2.0) - h * (st + sh / 2.0)
    };
    if va == V::Top {
        y = y.max(rect_box.y);
    } else if va == V::Bottom {
        y = y.min(rect_box.y + rect_box.h - h);
    }
    Rect { x, y, w, h }
}

/// `brandSlotGapParts()` — declared margin (layout units → px) plus the
/// vendor bitmap's transparent pad on the growth side.
pub fn slot_gap_parts(
    margins: &crate::ir::Margins,
    gravity: Option<&str>,
    rect_box: Rect,
    k: f32,
    vendor_ink: Option<&InkBox>,
) -> (f32, f32) {
    let prefer_right = !gravity
        .map(|g| g.to_lowercase().contains("right"))
        .unwrap_or(false);
    let declared = if prefer_right {
        let m = margins.right;
        if m != 0.0 {
            m
        } else {
            margins.left
        }
    } else {
        let m = margins.left;
        if m != 0.0 {
            m
        } else {
            margins.right
        }
    };
    let declared = if declared == 0.0 {
        SLOT_MARGIN_FALLBACK_UNITS
    } else {
        declared
    };
    let mut pad_side = 0.0;
    if let Some(ink) = vendor_ink {
        if ink.cw > 0 {
            let pad = if prefer_right {
                ink.cw - ink.x - ink.w
            } else {
                ink.x
            };
            pad_side = (pad as f32 / ink.cw as f32) * rect_box.w;
        }
    }
    (declared * k, pad_side)
}
