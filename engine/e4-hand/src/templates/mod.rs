//! Hand TemplateDef dispatch.
//!
//! `layout()` and `render()` are the two functions the frozen `TemplateDef`
//! carries as code; E1b-1 serialized only their fingerprints. Each module here
//! is a line-by-line port of the matching `const <id>: TemplateDef` in
//! `src/renderer/src/core/templates.ts` (line numbers in the table below).
//!
//! | id | TS line | cards (frozen 175) |
//! |---|---|---:|
//! | bareCover | 2595 | 1 (`video-cover`) |
//! ... (full table in _research/native/E4.md)

pub mod bare_cover;
pub mod blur;
pub mod brand_footer;
pub mod editorial;
pub mod film;
pub mod glass;
pub mod glass_palette_portrait;
pub mod glass_side_card;
pub mod grad_footer;
pub mod location_card;
pub mod logo_footer;
pub mod matte_footer;
pub mod matte_overlay;
pub mod movie;
pub mod overlay;
pub mod palette_card;
pub mod param_stack;
pub mod personal_border;
pub mod poetry_card;
pub mod polaroid;
pub mod polaroid_frame;
pub mod portrait_ticket;
pub mod profile_frame;
pub mod ratio_blur_square;
pub mod ratio_matte;
pub mod side_card;
pub mod side_ticket;
pub mod spec_footer;
pub mod split_reveal;
pub mod vendor_footer;
pub mod vertical;
pub mod xiaomi_leica;

use skia_safe::Rect;

use crate::draw::{Env, LayoutEnv};

pub type RenderResult = Result<Rect, String>;

/// Layout for one template. Returns the unrounded stage size; the caller
/// rounds and clamps it exactly like `scene.ts prepareScene`.
pub fn layout(template_id: &str, env: &LayoutEnv) -> Result<(f32, f32), String> {
    match template_id {
        "bareCover" => bare_cover::layout(env),
        "overlay" => overlay::layout(env),
        "logoFooter" => logo_footer::layout(env),
        "ratioBlurSquare" => ratio_blur_square::layout(env),
        "paramStack" => param_stack::layout(env),
        "editorial" => editorial::layout(env),
        "matteOverlay" => matte_overlay::layout(env),
        "film" => film::layout(env),
        "sideTicket" => side_ticket::layout(env),
        "glassSideCard" => glass_side_card::layout(env),
        "polaroid" => polaroid::layout(env),
        "sideCard" => side_card::layout(env),
        "paletteCard" => palette_card::layout(env),
        "vendorFooter" => vendor_footer::layout(env),
        "gradFooter" => grad_footer::layout(env),
        "vertical" => vertical::layout(env),
        "brandFooter" => brand_footer::layout(env),
        "blur" => blur::layout(env),
        "ratioMatte" => ratio_matte::layout(env),
        "glass" => glass::layout(env),
        "glassPalettePortrait" => glass_palette_portrait::layout(env),
        "xiaomiLeica" => xiaomi_leica::layout(env),
        "matteFooter" => matte_footer::layout(env),
        "specFooter" => spec_footer::layout(env),
        "locationCard" => location_card::layout(env),
        "personalBorder" => personal_border::layout(env),
        "poetryCard" => poetry_card::layout(env),
        "portraitTicket" => portrait_ticket::layout(env),
        "polaroidFrame" => polaroid_frame::layout(env),
        "splitReveal" => split_reveal::layout(env),
        "movie" => movie::layout(env),
        "profileFrame" => profile_frame::layout(env),
        other => Err(format!("E4 batch-1: layout for '{other}' not ported yet")),
    }
}

/// 渲染前该解码哪张相框图。
///
/// `polaroidFrame` 按照片比例从 7 张框图里挑一张，框图之间的画布比例互不相同；
/// 调用方（桌面 CLI / Android bridge）只解码这一张，不再把 7 张全解一遍。
pub fn frame_stem_for(template_id: &str, pw: f32, ph: f32) -> Option<&'static str> {
    match template_id {
        "polaroidFrame" => Some(polaroid_frame::chosen_stem(pw, ph)),
        _ => None,
    }
}

pub fn render(env: &mut Env) -> RenderResult {
    let id = env.design.template_id.clone();
    match id.as_str() {
        "bareCover" => bare_cover::render(env),
        "overlay" => overlay::render(env),
        "logoFooter" => logo_footer::render(env),
        "ratioBlurSquare" => ratio_blur_square::render(env),
        "paramStack" => param_stack::render(env),
        "editorial" => editorial::render(env),
        "matteOverlay" => matte_overlay::render(env),
        "film" => film::render(env),
        "sideTicket" => side_ticket::render(env),
        "glassSideCard" => glass_side_card::render(env),
        "polaroid" => polaroid::render(env),
        "sideCard" => side_card::render(env),
        "paletteCard" => palette_card::render(env),
        "vendorFooter" => vendor_footer::render(env),
        "gradFooter" => grad_footer::render(env),
        "vertical" => vertical::render(env),
        "brandFooter" => brand_footer::render(env),
        "blur" => blur::render(env),
        "ratioMatte" => ratio_matte::render(env),
        "glass" => glass::render(env),
        "glassPalettePortrait" => glass_palette_portrait::render(env),
        "xiaomiLeica" => xiaomi_leica::render(env),
        "matteFooter" => matte_footer::render(env),
        "specFooter" => spec_footer::render(env),
        "locationCard" => location_card::render(env),
        "personalBorder" => personal_border::render(env),
        "poetryCard" => poetry_card::render(env),
        "portraitTicket" => portrait_ticket::render(env),
        "polaroidFrame" => polaroid_frame::render(env),
        "splitReveal" => split_reveal::render(env),
        "movie" => movie::render(env),
        "profileFrame" => profile_frame::render(env),
        other => Err(format!("E4 batch-1: render for '{other}' not ported yet")),
    }
}

/// Which templates this build can render — used by the probe to report
/// coverage instead of failing per card.
pub fn implemented(template_id: &str) -> bool {
    matches!(
        template_id,
        "bareCover"
            | "overlay"
            | "logoFooter"
            | "ratioBlurSquare"
            | "paramStack"
            | "editorial"
            | "matteOverlay"
            | "film"
            | "sideTicket"
            | "glassSideCard"
            | "polaroid"
            | "sideCard"
            | "paletteCard"
            | "vendorFooter"
            | "gradFooter"
            | "vertical"
            | "brandFooter"
            | "profileFrame"
            | "movie"
            | "xiaomiLeica"
            | "matteFooter"
            | "specFooter"
            | "locationCard"
            | "personalBorder"
            | "poetryCard"
            | "portraitTicket"
            | "polaroidFrame"
            | "splitReveal"
            | "blur"
            | "ratioMatte"
            | "glass"
            | "glassPalettePortrait"
    )
}
