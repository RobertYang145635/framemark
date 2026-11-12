//! E4 — native renderer for the hand template family.
//!
//! Phase 1 (this build): data/asset/font probe + harness validation on
//! `video-cover` (`bareCover`, a photo-only card). Phase 2 ports the remaining
//! 31 templates that the 69 frozen hand cards use.
//!
//! Nothing here reads oracle draw-ops for rendering: ops and goldens are only
//! compared afterwards.

#![cfg(windows)]

mod angle;
mod canvas;
mod css;
mod draw;
mod exif;
mod fonts;
mod gpu;
mod hand;
mod jsval;
mod lines;
mod report;
mod templates;
mod text;

use std::cell::{Cell, RefCell};
use std::io::{BufRead, Write};
use std::path::{Path, PathBuf};
use std::time::Instant;

thread_local! {
    static PREVIEW_PHOTO: RefCell<Option<(PathBuf, Image)>> = const { RefCell::new(None) };
    static PREVIEW_GPU: RefCell<Option<gpu::Gpu>> = const { RefCell::new(None) };
    static PREVIEW_TABLES: RefCell<Option<(PathBuf, hand::HandTables)>> = const { RefCell::new(None) };
    static PREVIEW_FONTS: RefCell<Option<(PathBuf, PathBuf, HandFonts)>> = const { RefCell::new(None) };
    static PREVIEW_SERVER_ACTIVE: Cell<bool> = const { Cell::new(false) };
}

use serde_json::{json, Value};
use skia_safe::{Data, Image};

use canvas::Ctx2D;
use draw::{Env, Images, LayoutEnv};
use fonts::HandFonts;
use hand::{Card, Exif};
use report::Report;
use text::TextEngine;

fn arg(args: &[String], name: &str, dflt: &str) -> String {
    args.iter()
        .position(|a| a == &format!("--{name}"))
        .and_then(|i| args.get(i + 1).cloned())
        .unwrap_or_else(|| dflt.to_string())
}

fn flag(args: &[String], name: &str) -> bool {
    args.iter().any(|a| a == &format!("--{name}"))
}

fn load_image(p: &Path) -> Result<Image, String> {
    let data = Data::from_filename(p).ok_or_else(|| format!("missing image {}", p.display()))?;
    Image::from_encoded(data).ok_or_else(|| format!("decode failed {}", p.display()))
}

/// Worker mode keeps the one active source photo decoded between requests.
fn load_cached_photo(p: &Path) -> Result<Image, String> {
    PREVIEW_PHOTO.with(|cell| {
        let mut slot = cell.borrow_mut();
        if let Some((cached_path, image)) = slot.as_ref() {
            if cached_path == p {
                return Ok(image.clone());
            }
        }
        let image = load_image(p)?;
        *slot = Some((p.to_path_buf(), image.clone()));
        Ok(image)
    })
}

fn b64_val(b: u8) -> Option<u8> {
    match b {
        b'A'..=b'Z' => Some(b - b'A'),
        b'a'..=b'z' => Some(b - b'a' + 26),
        b'0'..=b'9' => Some(b - b'0' + 52),
        b'+' => Some(62),
        b'/' => Some(63),
        _ => None,
    }
}

fn b64_decode(s: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(s.len() * 3 / 4);
    let mut acc: u32 = 0;
    let mut bits = 0u32;
    for &b in s.as_bytes() {
        if b == b'=' {
            break;
        }
        if b.is_ascii_whitespace() {
            continue;
        }
        let v = b64_val(b)?;
        acc = (acc << 6) | v as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
        }
    }
    Some(out)
}

fn decode_data_url(url: &str) -> Option<Vec<u8>> {
    let payload = url.split_once(',').map(|(_, p)| p).unwrap_or(url);
    b64_decode(payload)
}

/// `brandPicker` writes the chosen brand onto `design.logo` (name + inline
/// dataUrl); the card's `logo` is only the capture-time default. Honour the
/// design when it carries bytes. The default design always embeds exactly the
/// card's own PNG, so the byte-compare keeps the baseline on the old code path.
fn load_brand_logo(card: &hand::Card, design: &hand::Design) -> Result<Option<Image>, String> {
    if let Ok(p) = std::env::var("E4H_LOGO_FILE") {
        if !p.trim().is_empty() {
            return Ok(Some(load_image(Path::new(&p))?));
        }
    }
    match &design.logo {
        Some(dl) if !dl.data_url.trim().is_empty() => {
            let bytes = decode_data_url(&dl.data_url)
                .ok_or_else(|| "design.logo.dataUrl is not valid base64".to_string())?;
            if let Some(c) = &card.logo {
                if std::fs::read(&c.path).map(|disk| disk == bytes).unwrap_or(false) {
                    return Ok(Some(load_image(&c.path)?));
                }
            }
            Ok(Some(
                Image::from_encoded(Data::new_copy(&bytes))
                    .ok_or_else(|| "design.logo.dataUrl image decode failed".to_string())?,
            ))
        }
        Some(_) => match &card.logo {
            Some(c) => Ok(Some(load_image(&c.path)?)),
            None => Ok(None),
        },
        // design.logo = null is the brand picker's "no brand" value.
        None => Ok(None),
    }
}

fn run_cli(args: Vec<String>) -> Result<(), String> {
    let mode = args.get(1).map(|s| s.as_str()).unwrap_or("probe");
    match mode {
        "probe" => probe(&args),
        "render" => render(&args),
        "fonttest" => fonttest(&args),
        other => Err(format!("unknown mode '{other}' (probe|render)")),
    }
}

fn preview_server_loop() -> Result<(), String> {
    PREVIEW_SERVER_ACTIVE.with(|active| active.set(true));
    let stdin = std::io::stdin();
    let mut stdout = std::io::stdout().lock();
    println!("FM_PREVIEW_WORKER_READY backend=hand");
    stdout.flush().map_err(|e| format!("stdout flush: {e}"))?;
    for line in stdin.lock().lines() {
        let line = line.map_err(|e| format!("stdin read: {e}"))?;
        if line.trim().is_empty() {
            continue;
        }
        let request: Vec<String> =
            serde_json::from_str(&line).map_err(|e| format!("worker request JSON: {e}"))?;
        let mut args = Vec::with_capacity(request.len() + 1);
        args.push("e4hand".to_string());
        args.extend(request);
        match run_cli(args) {
            Ok(()) => println!("FM_PREVIEW_WORKER_DONE code=0"),
            Err(e) => {
                eprintln!("e4hand.error={e}");
                println!("FM_PREVIEW_WORKER_DONE code=2 error={}", e.replace('\n', " "));
            }
        }
        stdout.flush().map_err(|e| format!("stdout flush: {e}"))?;
    }
    Ok(())
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let res = if args.get(1).map(String::as_str) == Some("preview-server") {
        preview_server_loop()
    } else {
        run_cli(args)
    };
    if let Err(e) = res {
        eprintln!("e4hand.error={e}");
        std::process::exit(2);
    }
}

/// `gradFooter.prepare`: the frozen reference resolves the sticker bitmap from
/// `GRAD_STICKER_FILES[params.sticker]` and draws nothing at all for a key that
/// is not in that table. The capture ships every entry as
/// `extras/graduation_<key>.png`, so the card's own asset stays on the old code
/// path for its default value.
fn load_sticker(extra: &hand::ExtraAsset, params: &hand::Params) -> Result<Option<Image>, String> {
    let captured = extra
        .png
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or_default()
        .strip_prefix("graduation_")
        .unwrap_or_default()
        .to_string();
    let key = match params.get("sticker") {
        Some(Value::String(s)) => s.trim().to_string(),
        _ => String::new(),
    };
    // No selection recorded: keep the captured sticker rather than dropping it.
    if key.is_empty() || key == captured {
        return Ok(Some(load_image(&extra.png)?));
    }
    if key.contains('/') || key.contains('\\') || key.contains('.') {
        return Ok(None);
    }
    let dir = extra.png.parent().map(|p| p.to_path_buf()).unwrap_or_default();
    let candidate = dir.join(format!("graduation_{key}.png"));
    if candidate.exists() {
        return Ok(Some(load_image(&candidate)?));
    }
    Ok(None)
}

// ── probe ──────────────────────────────────────────────────────────────────

fn probe(args: &[String]) -> Result<(), String> {
    let cards_path = PathBuf::from(arg(
        args,
        "cards",
        "D:/border&watermark/_research/native/e4-hand/cards.json",
    ));
    let hand_dir = PathBuf::from(arg(
        args,
        "hand",
        "D:/border&watermark/framemark/engine/data/hand",
    ));
    let fonts_dir = PathBuf::from(arg(
        args,
        "fonts",
        "D:/border&watermark/framemark/engine/assets/fonts",
    ));
    let extra_fonts = PathBuf::from(arg(
        args,
        "extra-fonts",
        "D:/border&watermark/_research/native/e4-hand/fonts",
    ));
    let out = PathBuf::from(arg(
        args,
        "out",
        "D:/border&watermark/_research/native/e4-hand/probe.json",
    ));

    let cards = hand::load_cards(&cards_path)?;
    let tables = match PREVIEW_TABLES.with(|cell| cell.borrow_mut().take()) {
        Some((cached_dir, tables)) if cached_dir == hand_dir => tables,
        _ => hand::load_tables(&hand_dir)?,
    };
    let tmap: std::collections::HashMap<String, hand::HandDef> =
        tables.templates.iter().cloned().map(|t| (t.id.clone(), t)).collect();
    let fonts = match PREVIEW_FONTS.with(|cell| cell.borrow_mut().take()) {
        Some((cached_fonts, cached_extra, fonts))
            if cached_fonts == fonts_dir && cached_extra == extra_fonts => fonts,
        _ => HandFonts::load(&fonts_dir, &extra_fonts)?,
    };
    let mut te = text_engine();
    te.fallback = fonts.fallback.clone();
    te.fallback_bold = fonts.fallback_bold.clone();

    let mut gaps: Vec<String> = Vec::new();
    for m in fonts.missing_report() {
        gaps.push(format!("font face unavailable: {m}"));
    }
    if !cards.unused_templates.is_empty() {
        gaps.push(format!(
            "{} TemplateDefs have no card in the frozen 69: {}",
            cards.unused_templates.len(),
            cards.unused_templates.join(", ")
        ));
    }

    let mut per_card = Vec::new();
    let mut face_stats: std::collections::BTreeMap<String, FaceStat> = Default::default();
    let mut unresolved_faces: std::collections::BTreeMap<String, Vec<String>> = Default::default();
    let mut glyph_missing: Vec<String> = Vec::new();
    let mut fallback_lines: Vec<Value> = Vec::new();
    let mut text_extras = 0usize;
    let mut theme_unknown: Vec<String> = Vec::new();
    let mut undeclared: std::collections::BTreeMap<String, Vec<String>> = Default::default();
    let mut logo_mismatch = Vec::new();
    let mut photo_checks = Vec::new();

    for card in &cards.cards {
        let design = hand::load_design(&card.design)?;
        let mut problems = Vec::new();
        if design.template_id != card.template_id {
            problems.push(format!(
                "design.templateId {} != manifest {}",
                design.template_id, card.template_id
            ));
        }
        if !templates::implemented(&card.template_id) {
            problems.push(format!("template '{}' not ported yet", card.template_id));
        }
        if let Some(def) = tmap.get(&card.template_id) {
            let defaults = def.defaults_map();
            let extra: Vec<String> = design
                .params
                .keys()
                .filter(|k| !defaults.contains_key(*k))
                .cloned()
                .collect();
            if !extra.is_empty() {
                undeclared.insert(card.card.clone(), extra);
            }
        } else {
            problems.push(format!("template '{}' missing from hand/templates.json", card.template_id));
        }
        let theme = hand::str_param(&design.params, "theme", "paper");
        if tables.themes.get(theme.as_str()).is_none() {
            theme_unknown.push(format!("{}: theme '{theme}'", card.card));
        }
        if design.text.slot_values.is_some()
            || design.text.slot_sizes.is_some()
            || design.text.line_text.is_some()
            || design.text.hidden_lines.is_some()
            || design.text.line_sizes.is_some()
            || design.text.hidden.is_some()
        {
            text_extras += 1;
        }

        // photo
        match load_image(&card.photo.path) {
            Ok(img) => {
                let dims = [img.width(), img.height()];
                if dims != [card.photo.size[0] as i32, card.photo.size[1] as i32] {
                    problems.push(format!("photo dims {dims:?} != manifest {:?}", card.photo.size));
                }
                photo_checks.push((card.card.clone(), dims));
            }
            Err(e) => problems.push(e),
        }

        // logo
        if let Some(logo) = &card.logo {
            match load_image(&logo.path) {
                Ok(img) => {
                    if img.width() as u32 != logo.w || img.height() as u32 != logo.h {
                        logo_mismatch.push(format!(
                            "{}: {} png {}x{} != manifest {}x{}",
                            card.card,
                            logo.name,
                            img.width(),
                            img.height(),
                            logo.w,
                            logo.h
                        ));
                    }
                }
                Err(e) => problems.push(e),
            }
        }
        for extra in &card.extras {
            if !extra.png.exists() {
                problems.push(format!("extra asset missing {}", extra.png.display()));
            }
        }

        // text measure vs the oracle's own measureText width (op.box.w, which
        // INCLUDES the trailing letter-spacing the template declared).
        let mut lines = Vec::new();
        for op in &card.texts {
            let (px, weight, italic, stack) = fonts::parse_font(&op.font);
            let family = fonts::first_family(&stack);
            let _ = (px, italic);
            let face = fonts.resolve(&family, weight);
            match face {
                None => {
                    unresolved_faces
                        .entry(format!("{family}|{weight}"))
                        .or_default()
                        .push(format!("{} '{}'", card.card, op.s.chars().take(24).collect::<String>()));
                    problems.push(format!("font unresolved: {family} {weight}"));
                    lines.push(json!({
                        "s": op.s, "family": family, "weight": weight, "fs": op.fs,
                        "resolved": false,
                    }));
                }
                Some(f) => {
                    let missing: Vec<char> = op.s.chars().filter(|c| f.tf.unichar_to_glyph(*c as i32) == 0).collect();
                    if !missing.is_empty() {
                        glyph_missing.push(format!("{}: {:?} missing in {family}", card.card, missing));
                        if let Some(fb) = &fonts.fallback {
                            let still: Vec<char> = op.s.chars().filter(|c| fb.unichar_to_glyph(*c as i32) == 0).collect();
                            if still.is_empty() {
                                let (_, fm) = te.shape(&op.s, fb, op.fs, 0.0);
                                let fdelta = fm - op.r#box.w;
                                let chars = op.s.chars().count().max(1) as f32;
                                fallback_lines.push(json!({
                                    "card": card.card, "s": op.s, "family": family, "weight": weight,
                                    "fs": op.fs, "measure": fm, "box_w": op.r#box.w,
                                    "delta_px": fdelta,
                                    "implied_tracking_em": fdelta / (op.fs * chars),
                                    "fallback": fonts.fallback_file,
                                }));
                            }
                        }
                    }
                    let (_, measure) = te.shape(&op.s, &f.tf, op.fs, 0.0);
                    let delta = measure - op.r#box.w;
                    let chars = op.s.chars().count().max(1) as f32;
                    let implied_tracking_em = delta / (op.fs * chars);
                    let key = format!("{family}|{weight}");
                    let st = face_stats.entry(key).or_default();
                    st.lines += 1;
                    st.max_abs_delta = st.max_abs_delta.max(delta.abs() as f64);
                    st.sum_abs_delta += delta.abs() as f64;
                    st.file = f.file.display().to_string();
                    st.face_weight = f.face_weight;
                    st.synthetic = f.synthetic_bold;
                    st.variable = f.variable;
                    lines.push(json!({
                        "s": op.s, "family": family, "weight": weight, "fs": op.fs,
                        "resolved": true, "measure": measure, "box_w": op.r#box.w,
                        "delta_px": delta, "implied_tracking_em": implied_tracking_em,
                        "synthetic": f.synthetic_bold, "face_weight": f.face_weight,
                    }));
                }
            }
        }
        let max_delta = lines
            .iter()
            .filter_map(|l| l.get("delta_px").and_then(|v| v.as_f64()))
            .fold(0.0f64, |a, b| a.max(b.abs()));

        per_card.push(json!({
            "card": card.card,
            "template": card.template_id,
            "canvas": card.canvas,
            "golden": card.golden,
            "implemented": templates::implemented(&card.template_id),
            "params": design.params,
            "textKeys": design.text,
            "textLines": lines.len(),
            "maxDeltaPx": max_delta,
            "lines": lines,
            "problems": problems,
        }));
    }

    let face_rows: Vec<Value> = face_stats
        .iter()
        .map(|(k, s)| {
            json!({
                "face": k,
                "file": s.file,
                "face_weight": s.face_weight,
                "synthetic_bold": s.synthetic,
                "variable": s.variable,
                "lines": s.lines,
                "max_abs_delta_px": s.max_abs_delta,
                "mean_abs_delta_px": if s.lines > 0 { s.sum_abs_delta / s.lines as f64 } else { 0.0 },
            })
        })
        .collect();

    if text_extras > 0 {
        gaps.push(format!("{text_extras} designs carry text override maps (slotValues/lineText/...) — unported"));
    }
    if !theme_unknown.is_empty() {
        gaps.push(format!("unknown theme ids fall back to paper: {theme_unknown:?}"));
    }
    if !glyph_missing.is_empty() {
        gaps.push(format!("{} text lines have glyphs missing from the resolved face (CJK fallback needed)", glyph_missing.len()));
    }
    gaps.push("D-021 middle-baseline offset is calibrated only for Avenir/SysSans/vivo/MiSans (E2a/E2b); Inter, JetBrains Mono, YouShe, Bebas Neue, Caveat, Chakra Petch are uncalibrated".into());

    let implemented = cards
        .cards
        .iter()
        .filter(|c| templates::implemented(&c.template_id))
        .count();
    let used_templates = {
        let mut set = std::collections::BTreeSet::new();
        for c in &cards.cards { set.insert(c.template_id.clone()); }
        set.len()
    };
    let report = json!({
        "generatedAt": chrono_like_now(),
        "inputs": {
            "cards": cards_path.display().to_string(),
            "hand": hand_dir.display().to_string(),
            "fonts": fonts_dir.display().to_string(),
            "extraFonts": extra_fonts.display().to_string(),
        },
        "counts": {
            "cards": cards.cards.len(),
            "implementedCards": implemented,
            "usedTemplates": used_templates,
            "unusedTemplates": cards.unused_templates,
            "templateDefs": tables.templates.len(),
            "variants": tables.variants.len(),
        },
        "facesUsed": cards.faces_used,
        "faceResolution": face_rows,
        "fallbackFile": fonts.fallback_file,
        "fallbackLines": fallback_lines,
        "unresolvedFaces": unresolved_faces,
        "logoMismatch": logo_mismatch,
        "undeclaredParams": undeclared,
        "cards": per_card,
        "gaps": gaps,
    });
    if let Some(parent) = out.parent() {
        std::fs::create_dir_all(parent).ok();
    }
    std::fs::write(&out, serde_json::to_string_pretty(&report).unwrap())
        .map_err(|e| format!("write {}: {e}", out.display()))?;
    println!("e4hand.probe={}", out.display());
    println!(
        "e4hand.probe.cards={} implemented={} faces={} unresolved={}",
        cards.cards.len(),
        implemented,
        face_rows.len(),
        unresolved_faces.len()
    );
    if flag(args, "strict") && implemented != cards.cards.len() {
        return Err(format!("strict: only {implemented}/{} cards implemented", cards.cards.len()));
    }
    Ok(())
}

#[derive(Default)]
struct FaceStat {
    file: String,
    face_weight: u16,
    synthetic: bool,
    variable: bool,
    lines: usize,
    max_abs_delta: f64,
    sum_abs_delta: f64,
}

// ── fonttest ───────────────────────────────────────────────────────────────

/// Shape one line with one face and print its width, optionally against an
/// oracle box width (ops `box.w`) to recover the implied letter-spacing.
fn fonttest(args: &[String]) -> Result<(), String> {
    let text = arg(args, "text", "");
    let size: f32 = arg(args, "size", "48").parse().map_err(|_| "bad --size")?;
    let tracking_em: f32 = arg(args, "tracking-em", "0").parse().map_err(|_| "bad --tracking-em")?;
    let box_w: Option<f32> = arg(args, "box", "").parse().ok();
    let te = text_engine();
    let tf = if let Some(path) = args.iter().position(|a| a == "--font").and_then(|i| args.get(i + 1)) {
        let data = Data::from_filename(path).ok_or_else(|| format!("missing {path}"))?;
        skia_safe::FontMgr::new()
            .new_from_data(data, None)
            .ok_or_else(|| format!("unreadable {path}"))?
    } else {
        let family = arg(args, "family", "Inter");
        let weight: u16 = arg(args, "weight", "400").parse().map_err(|_| "bad --weight")?;
        let fonts_dir = PathBuf::from(arg(args, "fonts", "D:/border&watermark/framemark/engine/assets/fonts"));
        let extra = PathBuf::from(arg(
            args,
            "extra-fonts",
            "D:/border&watermark/_research/native/e4-hand/fonts",
        ));
        let fonts = HandFonts::load(&fonts_dir, &extra)?;
        let tf = fonts
            .resolve(&family, weight)
            .ok_or_else(|| format!("family '{family}' not resolved"))?
            .tf;
        if flag(args, "glyphs") {
            let g: Vec<(char, u16)> = text.chars().map(|c| (c, tf.unichar_to_glyph(c as i32) as u16)).collect();
            println!("fonttest.glyphs={g:?}");
        }
        let tracking_px = tracking_em * size;
        let (_, w) = te.shape(&text, &tf, size, tracking_px);
        println!("fonttest.text={text:?} size={size} tracking_em={tracking_em} measure={w}");
        if let Some(bw) = arg(args, "box", "").parse::<f32>().ok() {
            let implied = (bw - w) / (size * text.chars().count().max(1) as f32);
            println!("fonttest.box={bw} delta={:.4} implied_tracking_em={:.6}", bw - w, implied);
        }
        return Ok(());
    };
    let tracking_px = tracking_em * size;
    let (_, w) = te.shape(&text, &tf, size, tracking_px);
    let glyphs: Vec<(char, u16)> = text.chars().map(|c| (c, tf.unichar_to_glyph(c as i32) as u16)).collect();
    println!("fonttest.style={:?} axes={:?}", tf.font_style(), tf.variation_design_parameters().map(|p| p.len()));
    println!("fonttest.text={text:?} size={size} tracking_em={tracking_em} measure={w}");
    if flag(args, "glyphs") {
        println!("fonttest.family={} glyphs={:?}", tf.family_name(), glyphs);
    }
    if let Some(bw) = box_w {
        let implied = (bw - w) / (size * text.chars().count().max(1) as f32);
        println!("fonttest.box={bw} delta={:.4} implied_tracking_em={:.6}", bw - w, implied);
    }
    Ok(())
}

// ── render ─────────────────────────────────────────────────────────────────

/// Everything a render pass needs that does not depend on the fitted size.
struct PassShared<'a> {
    photo: &'a Image,
    exif: &'a Exif,
    params: &'a hand::Params,
    text: &'a hand::TextSettings,
    design: &'a hand::Design,
    images: &'a Images,
    fonts: &'a HandFonts,
    te: &'a TextEngine,
    themes: &'a Value,
    overlay: Option<canvas::OverlaySpec>,
}

/// One GPU render of the card at design photo box `pw` x `ph`.
///
/// `snap` = the native-fit contract: force every photo draw box onto the pixel
/// grid at exactly that size, so the source pixels land 1:1. `None` (the
/// default path) leaves the template's own box untouched, byte for byte.
/// Returns the RGBA buffer plus the photo box the template reported.
#[allow(clippy::too_many_arguments)]
fn render_pass(
    gpu: &mut gpu::Gpu,
    sh: &PassShared,
    crop: hand::Crop,
    pw: f32,
    ph: f32,
    cw: i32,
    ch: i32,
    report_cell: &RefCell<Report>,
    snap: Option<(f32, f32)>,
) -> Result<(Vec<u8>, [f32; 4]), String> {
    // Chromium samples the palette on a GPU-backed temp canvas; do the same
    // 48 x h downscale on Ganesh when asked. Measured identical to the CPU
    // path on color-1 (3/5 hex), so it stays opt-in.
    let pal_w = 48usize;
    let pal_h = (pal_w as f64 * (ph as f64 / pw as f64)).round().max(24.0) as usize;
    let palette_buf = if std::env::var("E4H_PALETTE_GPU").ok().as_deref() == Some("1") {
        gpu.render_rgba8(pal_w as i32, pal_h as i32, |canvas| {
            canvas.clear(skia_safe::Color::TRANSPARENT);
            let mut p = skia_safe::Paint::default();
            p.set_anti_alias(true);
            let mut samp = skia_safe::SamplingOptions::default();
            samp.filter = skia_safe::FilterMode::Linear;
            canvas.draw_image_rect_with_sampling_options(
                sh.photo,
                Some((&skia_safe::Rect::from_xywh(crop.sx, crop.sy, crop.sw, crop.sh), skia_safe::canvas::SrcRectConstraint::Strict)),
                &skia_safe::Rect::from_xywh(0.0, 0.0, pal_w as f32, pal_h as f32),
                samp,
                &p,
            );
        })
        .ok()
        .map(|px| (px, pal_w, pal_h))
    } else {
        None
    };
    let mut render_err: Option<String> = None;
    let mut photo_rect = [0.0f32; 4];
    let pixels = gpu
        .render_rgba8(cw, ch, |sk_canvas| {
            let mut ctx = Ctx2D::new(sk_canvas, report_cell);
            ctx.set_overlay(sh.overlay.clone());
            ctx.set_photo_1to1(snap);
            // render-v1 photo crop: the draws need the user's region themselves
            // (a pan has to move the cover slice inside the template's window).
            ctx.set_user_crop(hand::UserCrop::read(sh.params));
            let mut env = Env {
                ctx,
                w: cw as f32,
                h: ch as f32,
                pw,
                ph,
                crop,
                photo: sh.photo,
                exif: sh.exif,
                params: sh.params,
                text: sh.text,
                design: sh.design,
                images: sh.images,
                is_preview: false,
                fonts: sh.fonts,
                te: sh.te,
                themes: sh.themes,
                palette48: palette_buf.as_ref(),
                lines: RefCell::new(lines::LineState::new()),
            };
            match templates::render(&mut env) {
                Ok(r) => photo_rect = [r.left, r.top, r.width(), r.height()],
                Err(e) => render_err = Some(e),
            }
        })
        .map_err(|e| format!("render: {e}"))?;
    if let Some(e) = render_err {
        return Err(e);
    }
    if let Some(font_error) = report_cell
        .borrow()
        .gaps
        .iter()
        .find(|gap| gap.starts_with(draw::FONT_ERROR_PREFIX))
    {
        return Err(font_error.clone());
    }
    Ok((pixels, photo_rect))
}

fn render(args: &[String]) -> Result<(), String> {
    let worker_stage_started = Instant::now();
    let card_id = arg(args, "card", "video-cover");
    let cards_path = PathBuf::from(arg(
        args,
        "cards",
        "D:/border&watermark/_research/native/e4-hand/cards.json",
    ));
    let hand_dir = PathBuf::from(arg(args, "hand", "D:/border&watermark/framemark/engine/data/hand"));
    let fonts_dir = PathBuf::from(arg(
        args,
        "fonts",
        "D:/border&watermark/framemark/engine/assets/fonts",
    ));
    let extra_fonts = PathBuf::from(arg(
        args,
        "extra-fonts",
        "D:/border&watermark/_research/native/e4-hand/fonts",
    ));
    let out = PathBuf::from(arg(
        args,
        "out",
        "D:/border&watermark/_research/native/e4-hand/out/raw.png",
    ));
    let report_path = PathBuf::from(arg(
        args,
        "report",
        "D:/border&watermark/_research/native/e4-hand/out/report.json",
    ));
    let backend = arg(args, "backend", "d3d12");
    let adapter = arg(args, "adapter", "nvidia");
    let preview_max_edge = match arg(args, "preview-max-edge", "") {
        raw if raw.trim().is_empty() => None,
        raw => Some(
            raw.trim()
                .parse::<i32>()
                .ok()
                .filter(|edge| *edge > 0)
                .ok_or_else(|| format!("--preview-max-edge must be a positive integer, got '{raw}'"))?,
        ),
    };

    let cards = hand::load_cards(&cards_path)?;
    let card: &Card = cards
        .cards
        .iter()
        .find(|c| c.card == card_id)
        .ok_or_else(|| format!("card '{card_id}' not in {}", cards_path.display()))?;
    let design = hand::load_design(&card.design)?;
    let tables = match PREVIEW_TABLES.with(|cell| cell.borrow_mut().take()) {
        Some((cached_dir, tables)) if cached_dir == hand_dir => tables,
        _ => hand::load_tables(&hand_dir)?,
    };
    let def = tables
        .templates
        .iter()
        .find(|t| t.id == card.template_id)
        .ok_or_else(|| format!("template '{}' not in hand/templates.json", card.template_id))?;
    let mut params = hand::merge_params(&def.defaults_map(), &design.params);

    let overlay = parse_overlay(args)?;
    let fit = hand::resolve_fit(&arg(args, "fit", ""), design.fit_mode.as_deref())?;
    let photo_src: std::path::PathBuf = {
        let overridden = arg(args, "photo", "");
        if overridden.is_empty() { card.photo.path.clone() } else { std::path::PathBuf::from(&overridden) }
    };
    let photo = load_cached_photo(&photo_src)?;
    let (iw, ih) = (photo.width() as f32, photo.height() as f32);
    let crop = match fit {
        hand::FitMode::Design => hand::compute_crop(iw, ih, &design.ratio),
        hand::FitMode::Native => hand::crop_full(iw, ih),
    };
    // Native fit: no lock parameter may keep the photo smaller than its own
    // pixels, so both aspect keys are pinned to the photo's real aspect ratio.
    // Every param-driven lock (aspectCanvas / photoWindowOf / ratio* layouts)
    // then collapses onto the photo box instead of cropping or resampling it.
    if fit == hand::FitMode::Native {
        let v = Value::from((iw / ih) as f64);
        params.insert("canvasAspect".into(), v.clone());
        params.insert("photoAspect".into(), v);
        println!("e4hand.fit=native photo={}x{} lockedAspect={:.6}", iw, ih, iw / ih);
    }
    // render-v1 photo crop: the user's window inside the template's own crop.
    // The layout keeps reading `crop` (pw/ph and the design ratio are the
    // template's), so nothing but the sampled source pixels moves.
    let view = hand::crop_window(crop, iw, ih, hand::UserCrop::read(&params));

    // A1: the photo carries its own shooting information; the card's sample EXIF
    // is only a fallback for the bundled sample photo (no user photo selected).
    let exif: Exif = if photo_src == card.photo.path { card.exif.clone().unwrap_or_default() } else { exif::read_photo(&photo_src) };
    let layout_env = LayoutEnv { pw: crop.pw, ph: crop.ph, crop, exif: &exif, params: &params, text: &design.text };
    let (lw, lh) = templates::layout(&card.template_id, &layout_env)?;
    let cw = (jsval::js_round(lw as f64) as i32).max(2);
    let ch = (jsval::js_round(lh as f64) as i32).max(2);
    println!("e4hand.card={} template={} canvas={}x{} ops_canvas={}x{}", card.card, card.template_id, cw, ch, card.canvas[0], card.canvas[1]);

    let mut images = Images::empty();
    images.logo = load_brand_logo(card, &design)?;
    for extra in &card.extras {
        match extra.kind.as_str() {
            "graduation-sticker" => images.sticker = load_sticker(extra, &params)?,
            "polaroid-frame" => images.frame = Some(load_image(&extra.png)?),
            _ => {}
        }
    }

    let fonts = match PREVIEW_FONTS.with(|cell| cell.borrow_mut().take()) {
        Some((cached_fonts, cached_extra, fonts))
            if cached_fonts == fonts_dir && cached_extra == extra_fonts => fonts,
        _ => HandFonts::load(&fonts_dir, &extra_fonts)?,
    };
    let worker_prep_ms = worker_stage_started.elapsed().as_millis();
    let gpu_started = Instant::now();
    let mut te = text_engine();
    te.fallback = fonts.fallback.clone();
    te.fallback_bold = fonts.fallback_bold.clone();
    // `scene.ts/scaledContext(ctx, scene.text.fontScale)` — the design value,
    // with a CLI override for the 1.22 A/B so designs stay untouched.
    let fs_override = arg(args, "font-scale", "").parse::<f64>().ok();
    te.scale = text_scale_of(fs_override.unwrap_or(design.text.font_scale));
    println!("e4hand.font_scale={}", te.scale);
    let themes = tables.themes.clone();
    let mk_report = |cw: i32, ch: i32| {
        RefCell::new(Report {
            canvas: [cw, ch],
            template: card.template_id.clone(),
            variant: design.variant_id.clone(),
            ..Default::default()
        })
    };

    let backend = backend.as_str();
    let mut gpu = match PREVIEW_GPU.with(|cell| cell.borrow_mut().take()) {
        Some(gpu) => gpu,
        None => match backend {
            "angle" => {
                let dir = std::env::var("T1C_ANGLE_DIR")
                    .unwrap_or_else(|_| r"C:\Program Files (x86)\Steam\bin\cef\cef.win64".to_string());
                let mode = std::env::var("T1C_EGLMODE").unwrap_or_else(|_| "device".to_string());
                let want = std::env::var("T1C_ADAPTER").unwrap_or_else(|_| adapter.clone());
                gpu::Gpu::new_angle(&dir, &want, &mode).map_err(|e| format!("ANGLE context: {e}"))?
            }
            _ => gpu::Gpu::new_d3d12(&adapter).map_err(|e| format!("D3D12 context: {e}"))?,
        },
    };
    let worker_gpu_ms = gpu_started.elapsed().as_millis();
    let shared = PassShared {
        photo: &photo,
        exif: &exif,
        params: &params,
        text: &design.text,
        design: &design,
        images: &images,
        fonts: &fonts,
        te: &te,
        themes: &themes,
        overlay: overlay.clone(),
    };
    // Canvas size for a design photo box. `crop_full(pw, ph)` carries the same
    // sw/sh the real crop does (compute_crop sets pw = sw, ph = sh), so the
    // template layouts that read env.crop keep seeing identical numbers.
    let layout_of = |pw: f32, ph: f32| -> Result<(i32, i32), String> {
        let env = LayoutEnv { pw, ph, crop: hand::crop_full(pw, ph), exif: &exif, params: &params, text: &design.text };
        let (lw, lh) = templates::layout(&card.template_id, &env)?;
        Ok(((jsval::js_round(lw as f64) as i32).max(2), (jsval::js_round(lh as f64) as i32).max(2)))
    };

    let mut fit_info = json!({ "mode": "design", "canvas": [cw, ch] });
    let (pixels, cw, ch, photo_rect, report_cell) = if fit == hand::FitMode::Design {
        let preview_scale = preview_max_edge
            .map(|edge| (edge as f32 / cw.max(ch).max(1) as f32).min(1.0))
            .unwrap_or(1.0);
        if preview_scale < 1.0 {
            let preview_pw = crop.pw * preview_scale;
            let preview_ph = crop.ph * preview_scale;
            let (preview_cw, preview_ch) = layout_of(preview_pw, preview_ph)?;
            let cell = mk_report(preview_cw, preview_ch);
            let (px, rect) = render_pass(
                &mut gpu,
                &shared,
                view,
                preview_pw,
                preview_ph,
                preview_cw,
                preview_ch,
                &cell,
                None,
            )?;
            fit_info = json!({
                "mode": "design",
                "source_canvas": [cw, ch],
                "canvas": [preview_cw, preview_ch],
                "preview_scale": preview_scale,
            });
            (px, preview_cw, preview_ch, rect, cell)
        } else {
            let cell = mk_report(cw, ch);
            let (px, rect) = render_pass(&mut gpu, &shared, view, view.pw, view.ph, cw, ch, &cell, None)?;
            (px, cw, ch, rect, cell)
        }
    } else if let Some(preview_edge) = preview_max_edge {
        // Native-fit preview: measure the fit invariant on a preview-sized
        // pilot, then render the final pass at the requested output scale. No
        // full-resolution canvas is allocated or encoded.
        let pilot_scale = (preview_edge as f32 / cw.max(ch).max(1) as f32).min(1.0);
        let pilot_w = iw * pilot_scale;
        let pilot_h = ih * pilot_scale;
        let (pilot_cw, pilot_ch) = layout_of(pilot_w, pilot_h)?;
        let pilot_cell = mk_report(pilot_cw, pilot_ch);
        let (_pilot_px, pilot_rect) = render_pass(
            &mut gpu,
            &shared,
            view,
            pilot_w,
            pilot_h,
            pilot_cw,
            pilot_ch,
            &pilot_cell,
            Some((pilot_w, pilot_h)),
        )?;
        let (box_w, box_h) = (pilot_rect[2], pilot_rect[3]);
        if !(box_w > 0.0) || !(box_h > 0.0) {
            return Err(format!(
                "E4H-FIT-NATIVE-LOCKED card='{}' template='{}': the template reports no photo box",
                card.card, card.template_id
            ));
        }
        let cx = box_w / pilot_w;
        let mismatch_px = pilot_h * (1.0 - (box_h / pilot_h) / cx).abs();
        if !(cx > 0.0) || !cx.is_finite() || mismatch_px > 0.5 {
            return Err(format!(
                "E4H-FIT-NATIVE-LOCKED card='{}' template='{}': photo box {:.5} vs photo {:.5} (native fit would crop {:.1}px)",
                card.card,
                card.template_id,
                box_w / box_h,
                pilot_w / pilot_h,
                mismatch_px
            ));
        }
        let fit_scale = 1.0 / cx;
        if fit_scale < 1.0 - 1e-6 {
            return Err(format!(
                "E4H-FIT-NATIVE-LOCKED card='{}': photo box {:.0}x{:.0} is larger than the photo {:.0}x{:.0}",
                card.card, box_w, box_h, pilot_w, pilot_h
            ));
        }
        let (full_fit_w, full_fit_h) = (iw * fit_scale, ih * fit_scale);
        let (full_fit_cw, full_fit_ch) = layout_of(full_fit_w, full_fit_h)?;
        let preview_scale = (preview_edge as f32 / full_fit_cw.max(full_fit_ch).max(1) as f32).min(1.0);
        let preview_w = iw * preview_scale;
        let preview_h = ih * preview_scale;
        let (preview_pw, preview_ph) = (full_fit_w * preview_scale, full_fit_h * preview_scale);
        let (preview_cw, preview_ch) = layout_of(preview_pw, preview_ph)?;
        let cell = mk_report(preview_cw, preview_ch);
        let (px, rect) = render_pass(
            &mut gpu,
            &shared,
            view,
            preview_pw,
            preview_ph,
            preview_cw,
            preview_ch,
            &cell,
            Some((preview_w, preview_h)),
        )?;
        let resid_px = (rect[2] - preview_w).abs().max((rect[3] - preview_h).abs());
        if resid_px > 1.0 {
            return Err(format!(
                "E4H-FIT-NATIVE-UNSTABLE card='{}': after preview scale {:.6} the photo box is {:.2}x{:.2}, expected {:.0}x{:.0} (off {:.2}px)",
                card.card, preview_scale, rect[2], rect[3], preview_w, preview_h, resid_px
            ));
        }
        fit_info = json!({
            "mode": "native",
            "source_photo": [iw, ih],
            "source_canvas": [full_fit_cw, full_fit_ch],
            "photo": [preview_w, preview_h],
            "scale": preview_scale,
            "native_scale": fit_scale,
            "passes": 2,
            "boxDesign": pilot_rect,
            "boxScaled": rect,
            "boxDrawn": [rect[0].round(), rect[1].round(), preview_w, preview_h],
            "canvas": [preview_cw, preview_ch],
        });
        (px, preview_cw, preview_ch, rect, cell)
    } else {
        // Pass A pins the design photo box to the photo's own pixels and
        // measures the box the template actually wants.
        let (cw_a, ch_a) = layout_of(iw, ih)?;
        let cell_a = mk_report(cw_a, ch_a);
        let (px_a, rect_a) = render_pass(&mut gpu, &shared, view, iw, ih, cw_a, ch_a, &cell_a, Some((iw, ih)))?;
        let (box_w, box_h) = (rect_a[2], rect_a[3]);
        if !(box_w > 0.0) || !(box_h > 0.0) {
            return Err(format!(
                "E4H-FIT-NATIVE-LOCKED card='{}' template='{}': the template reports no photo box",
                card.card, card.template_id
            ));
        }
        // Scaling cannot repair an aspect mismatch: a box pinned to a different
        // ratio than the photo can only be filled by cropping the photo, which
        // native fit refuses to do.
        let cx = box_w / iw;
        let mismatch_px = ih * (1.0 - (box_h / ih) / cx).abs();
        if !(cx > 0.0) || !cx.is_finite() || mismatch_px > 0.5 {
            return Err(format!(
                "E4H-FIT-NATIVE-LOCKED card='{}' template='{}': photo box {:.5} vs photo {:.5} (native fit would crop {:.1}px)",
                card.card,
                card.template_id,
                box_w / box_h,
                iw / ih,
                mismatch_px
            ));
        }
        let f = 1.0 / cx;
        if f < 1.0 - 1e-6 {
            return Err(format!(
                "E4H-FIT-NATIVE-LOCKED card='{}': photo box {:.0}x{:.0} is larger than the photo {:.0}x{:.0}",
                card.card, box_w, box_h, iw, ih
            ));
        }
        if (f - 1.0).abs() <= 1e-4 {
            fit_info = json!({
                "mode": "native", "photo": [iw, ih], "scale": 1.0, "passes": 1,
                "boxDesign": rect_a, "boxDrawn": [rect_a[0].round(), rect_a[1].round(), iw, ih],
                "canvas": [cw_a, ch_a],
            });
            (px_a, cw_a, ch_a, rect_a, cell_a)
        } else {
            // Pass B: grow the whole template by f so the photo box lands on
            // exactly the photo's pixels; the canvas grows, nothing resamples.
            let (pw_b, ph_b) = (iw * f, ih * f);
            let (cw_b, ch_b) = layout_of(pw_b, ph_b)?;
            let cell_b = mk_report(cw_b, ch_b);
            let (px_b, rect_b) = render_pass(&mut gpu, &shared, view, pw_b, ph_b, cw_b, ch_b, &cell_b, Some((iw, ih)))?;
            let resid_px = (rect_b[2] - iw).abs().max((rect_b[3] - ih).abs());
            if resid_px > 1.0 {
                return Err(format!(
                    "E4H-FIT-NATIVE-UNSTABLE card='{}': after scale {:.6} the photo box is {:.2}x{:.2}, expected {:.0}x{:.0} (off {:.2}px)",
                    card.card, f, rect_b[2], rect_b[3], iw, ih, resid_px
                ));
            }
            if rect_b[0] < -0.5
                || rect_b[1] < -0.5
                || rect_b[0] + iw > cw_b as f32 + 0.5
                || rect_b[1] + ih > ch_b as f32 + 0.5
            {
                return Err(format!(
                    "E4H-FIT-NATIVE-CLIPPED card='{}': photo box at {:.1},{:.1} {:.0}x{:.0} does not fit canvas {}x{}",
                    card.card, rect_b[0], rect_b[1], iw, ih, cw_b, ch_b
                ));
            }
            fit_info = json!({
                "mode": "native", "photo": [iw, ih], "scale": f, "passes": 2,
                "boxDesign": rect_a, "boxScaled": rect_b,
                "boxDrawn": [rect_b[0].round(), rect_b[1].round(), iw, ih],
                "canvas": [cw_b, ch_b],
            });
            (px_b, cw_b, ch_b, rect_b, cell_b)
        }
    };
    println!(
        "e4hand.fit_done mode={} canvas={}x{} scale={:.6}",
        fit_info["mode"].as_str().unwrap_or("?"),
        cw,
        ch,
        fit_info["scale"].as_f64().unwrap_or(1.0)
    );
    gpu::encode_png(&pixels, cw, ch, &out)?;
    println!("e4hand.raw={} bytes={}", out.display(), std::fs::metadata(&out).map(|m| m.len()).unwrap_or(0));
    println!(
        "e4hand.adapter=#{} {} backend={}",
        gpu.adapter_index(),
        gpu.adapter_name(),
        gpu.backend()
    );

    let rep = report_cell.borrow();
    // The source pixels the photo draw sampled. The draw report keeps the exact
    // src rect of every image draw, so this is the rectangle that reached Skia
    // (cover windows and the user crop included) rather than a re-derivation.
    // The **last** `photo` draw pairs with `photo_rect`: `record_photo`
    // overwrites the box on every photo draw, so the two must come from the same
    // draw for the gain-map pass to line them up.
    let photo_src = rep
        .images
        .iter()
        .rev()
        .find(|d| d.key == "photo")
        .and_then(|d| d.src.as_ref())
        .map(|r| [r.x, r.y, r.w, r.h]);
    let mut out_json = json!({
        "card": card.card,
        "template": card.template_id,
        "canvas": [cw, ch],
        "ops_canvas": card.canvas,
        "canvas_matches_ops": cw == card.canvas[0] as i32 && ch == card.canvas[1] as i32,
        "photo_rect": photo_rect,
        "fit": fit_info,
        "overlay": overlay.as_ref().map(|o| json!({
            "rect_norm": o.rect,
            "opacity": o.opacity,
            "angle": o.angle,
            "image": [o.image.width(), o.image.height()],
        })),
        "report": &*rep,
    });
    // `[x, y, w, h]` in the photo file's own raster space; absent when the card
    // has no photo draw at all.
    if let Some(src) = photo_src {
        out_json["photo_src"] = json!(src);
    }
    if let Some(edge) = preview_max_edge {
        out_json["preview"] = json!({
            "max_edge": edge,
            "canvas": [cw, ch],
        });
    }
    if let Some(parent) = report_path.parent() {
        std::fs::create_dir_all(parent).ok();
    }
    std::fs::write(&report_path, serde_json::to_string_pretty(&out_json).unwrap())
        .map_err(|e| format!("write {}: {e}", report_path.display()))?;
    println!("e4hand.report={}", report_path.display());
    drop(shared);
    println!(
        "FM_PREVIEW_WORKER_STAGE backend=hand prepMs={} gpuMs={} totalMs={}",
        worker_prep_ms,
        worker_gpu_ms,
        worker_stage_started.elapsed().as_millis()
    );
    if PREVIEW_SERVER_ACTIVE.with(Cell::get) {
        PREVIEW_TABLES.with(|cell| *cell.borrow_mut() = Some((hand_dir, tables)));
        PREVIEW_FONTS.with(|cell| *cell.borrow_mut() = Some((fonts_dir, extra_fonts, fonts)));
        PREVIEW_GPU.with(|cell| *cell.borrow_mut() = Some(gpu));
    } else {
        // One-shot CLI/smoke/export keeps the V06 teardown contract.
        gpu.abandon();
    }
    Ok(())
}

/// `draw.ts textScaleOf`: clamp 0.5..2; anything within 0.001 of 1 is exactly 1.
fn text_scale_of(raw: f64) -> f32 {
    let s = if raw.is_finite() { raw.clamp(0.5, 2.0) } else { 1.0 };
    if (s - 1.0).abs() < 0.001 { 1.0 } else { s as f32 }
}

/// render-v1 `overlay` from the desktop CLI: `--overlay <path>`,
/// `--overlay-rect x,y,w,h` (normalised to the photo box, default 0,0,1,1) and
/// `--overlay-opacity 0..1` (default 1). Any malformed input is a hard error —
/// the layer must never silently disappear.
fn parse_overlay(args: &[String]) -> Result<Option<canvas::OverlaySpec>, String> {
    let Some(i) = args.iter().position(|a| a == "--overlay") else {
        return Ok(None);
    };
    let path = args.get(i + 1).map(String::as_str).unwrap_or("");
    if path.trim().is_empty() {
        return Err("--overlay needs a file path".into());
    }
    let data = Data::from_filename(path).ok_or_else(|| format!("overlay image missing: {path}"))?;
    let image = Image::from_encoded(data).ok_or_else(|| format!("overlay image decode failed: {path}"))?;
    let rect = if let Some(j) = args.iter().position(|a| a == "--overlay-rect") {
        let raw = args.get(j + 1).map(String::as_str).unwrap_or("");
        let parts: Vec<&str> = raw.split(',').collect();
        if parts.len() != 4 {
            return Err(format!("--overlay-rect needs 4 comma-separated numbers (x,y,w,h), got '{raw}'"));
        }
        let mut r = [0f32; 4];
        for (k, p) in parts.iter().enumerate() {
            r[k] = p
                .trim()
                .parse::<f32>()
                .map_err(|_| format!("--overlay-rect value '{}' is not a number", p.trim()))?;
        }
        r
    } else {
        [0.0, 0.0, 1.0, 1.0]
    };
    for (k, v) in rect.iter().enumerate() {
        if !v.is_finite() || *v < 0.0 || *v > 1.0 {
            return Err(format!(
                "--overlay-rect must be four numbers in 0..1 (x,y,w,h); index {k} is {v}"
            ));
        }
    }
    let opacity = if let Some(j) = args.iter().position(|a| a == "--overlay-opacity") {
        args.get(j + 1)
            .ok_or("--overlay-opacity needs a number in 0..1")?
            .parse::<f32>()
            .map_err(|_| "--overlay-opacity is not a number".to_string())?
    } else {
        1.0
    };
    if !opacity.is_finite() || !(0.0..=1.0).contains(&opacity) {
        return Err(format!("--overlay-opacity must be in 0..1, got {opacity}"));
    }
    let angle = if let Some(j) = args.iter().position(|a| a == "--overlay-angle") {
        args.get(j + 1)
            .ok_or("--overlay-angle needs a number in -180..180")?
            .parse::<f32>()
            .map_err(|_| "--overlay-angle is not a number".to_string())?
    } else {
        0.0
    };
    if !angle.is_finite() || angle.abs() > 180.0 {
        return Err(format!("--overlay-angle must be in -180..180, got {angle}"));
    }
    Ok(Some(canvas::OverlaySpec { image, rect, opacity, angle }))
}

fn text_engine() -> TextEngine {
    let mut te = TextEngine::new();
    te.subpixel = true;
    te.linear_metrics = true;
    te.edging = skia_safe::font::Edging::AntiAlias;
    te.embolden = false;
    te.stroke_w = 0.0;
    te.hinting = match std::env::var("E4H_HINTING").unwrap_or_else(|_| "slight".into()).as_str() {
        "none" => skia_safe::FontHinting::None,
        "normal" => skia_safe::FontHinting::Normal,
        "full" => skia_safe::FontHinting::Full,
        _ => skia_safe::FontHinting::Slight,
    };
    te
}

fn chrono_like_now() -> String {
    // std has no wall-clock formatting; the probe timestamp is informational.
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    format!("unix:{now}")
}

