//! Stable hand-renderer text-slot identities and flat parameter overrides.
//!
//! Hand templates must pass an explicit logical slot at the draw call site.
//! Content and paint order are deliberately not part of identity: either may
//! change when EXIF is missing, a line is hidden, or the user edits the text.

use crate::hand::{Exif, Params, TextSettings};
use std::collections::BTreeSet;

/// `text-slots.ts:19` norm(): drop every whitespace character, lowercase.
pub fn norm(value: &str) -> String {
    value
        .chars()
        .filter(|c| !c.is_whitespace())
        .collect::<String>()
        .to_lowercase()
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Source {
    Title,
    Subtitle,
    Signature,
    CustomLine,
    Location,
    Make,
    Model,
    Lens,
    Exif,
    Date,
    Photo,
}

impl Source {
    pub fn tag(self) -> &'static str {
        match self {
            Source::Title => "title",
            Source::Subtitle => "subtitle",
            Source::Signature => "signature",
            Source::CustomLine => "customLine",
            Source::Location => "location",
            Source::Make => "make",
            Source::Model => "model",
            Source::Lens => "lens",
            Source::Exif => "exif",
            Source::Date => "date",
            Source::Photo => "photo",
        }
    }
}

/// Descriptive metadata only. It must never be used to construct a slot key.
pub fn classify(raw: &str, text: &TextSettings, exif: &Exif, title: &str) -> Source {
    let hay = norm(raw);
    if hay.is_empty() {
        return Source::Photo;
    }
    let tokens: [(&str, Source); 5] = [
        (title.trim(), Source::Title),
        (text.subtitle.trim(), Source::Subtitle),
        (text.signature.trim(), Source::Signature),
        (text.custom_line.trim(), Source::CustomLine),
        (text.location.trim(), Source::Location),
    ];
    let mut hits: Vec<(usize, Source)> = tokens
        .iter()
        .filter(|(value, _)| !value.is_empty() && hay.contains(&norm(value)))
        .map(|(value, src)| (norm(value).len(), *src))
        .collect();
    if !hits.is_empty() {
        hits.sort_by(|a, b| b.0.cmp(&a.0));
        return hits[0].1;
    }
    let exif_line = exif.line("  ");
    let date_only = crate::hand::format_date_only(&exif.date_time);
    let model_only = exif.model_without_make();
    let flags: [(&str, Source); 5] = [
        (exif.make.as_str(), Source::Make),
        (model_only.as_str(), Source::Model),
        (exif.lens.as_str(), Source::Lens),
        (exif_line.as_str(), Source::Exif),
        (date_only.as_str(), Source::Date),
    ];
    let mut src = Source::Photo;
    let mut count = 0usize;
    for (value, kind) in flags.iter() {
        if !value.is_empty() && hay.contains(&norm(value)) {
            src = *kind;
            count += 1;
        }
    }
    if count == 1 {
        src
    } else {
        Source::Photo
    }
}

/// The public hand-slot contract. The scope is `variantId ?? templateId`; the
/// final component is a literal logical name from the template source.
pub fn stable_key(scope: &str, logical_slot: &str) -> String {
    format!("hand:{scope}:{logical_slot}")
}

/// One render pass rejects duplicate logical IDs instead of silently binding a
/// second row to an already-used override key.
#[derive(Default)]
pub struct LineState {
    seen: BTreeSet<String>,
}

impl LineState {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn claim(&mut self, key: &str) -> bool {
        self.seen.insert(key.to_string())
    }
}

/// Result of the common flat override protocol for a hand text row.
#[derive(Debug, Clone, PartialEq)]
pub struct Resolution {
    pub text: String,
    pub scale: f64,
    pub hidden: bool,
}

/// Resolve `slotText:<stableKey>`, `slotOff:<stableKey>`, and
/// `slotScale:<stableKey>` from design params. Empty text is treated as absent,
/// matching the IR renderer: clearing a field removes its key and reveals the
/// source text. A blank source row is not itself hidden; only an explicit
/// boolean false hides it.
pub fn resolve(params: &Params, key: &str, raw: &str) -> Resolution {
    let text_key = format!("slotText:{key}");
    let text = params
        .get(&text_key)
        .and_then(|value| value.as_str())
        .filter(|value| !value.is_empty())
        .unwrap_or(raw)
        .to_string();

    let hidden_key = format!("slotOff:{key}");
    let hidden = params.get(&hidden_key).and_then(|value| value.as_bool()) == Some(false);

    let scale_key = format!("slotScale:{key}");
    let scale = params
        .get(&scale_key)
        .and_then(|value| value.as_f64())
        .filter(|value| value.is_finite())
        .map(|value| value.clamp(0.6, 1.6))
        .unwrap_or(1.0);

    Resolution {
        text,
        scale,
        hidden,
    }
}

/// Existing declaration-based rows (brandFooter) keep their historical key
/// spelling, separate from the new `hand:` flat-slot namespace.
pub fn declared_key(scope: &str, id: &str) -> String {
    format!("{scope}#{id}")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hand::TextSettings;

    fn exif() -> Exif {
        Exif {
            make: "PANASONIC".into(),
            model: "DC-S1".into(),
            lens: String::new(),
            focal: Some(366.0),
            focal_equivalent: None,
            aperture: Some(6.3),
            shutter: Some(0.0025),
            iso: Some(125.0),
            date_time: "2022:07:05 10:00:00".into(),
            width: 0.0,
            height: 0.0,
            has_any: true,
        }
    }

    #[test]
    fn source_classification_is_descriptive_only() {
        let mut text = TextSettings::default();
        text.title = "A TITLE".into();
        let e = exif();
        assert_eq!(
            classify("A TITLE 2022", &text, &e, "A TITLE"),
            Source::Title
        );
        assert_eq!(classify("36 EXP", &text, &e, ""), Source::Photo);
    }

    #[test]
    fn stable_identity_does_not_depend_on_line_text_or_paint_order() {
        let top_left = stable_key("fm-film", "film.top-left");
        let top_right = stable_key("fm-film", "film.top-right");
        assert_eq!(top_left, stable_key("fm-film", "film.top-left"));
        assert_ne!(top_left, top_right);

        let mut pass = LineState::new();
        // Hiding/omitting one logical row does not renumber another row.
        assert!(pass.claim(&top_right));
        assert!(pass.claim(&top_left));
        assert_eq!(stable_key("fm-film", "film.top-right"), top_right);
        assert!(
            !pass.claim(&top_right),
            "duplicate logical slots must be rejected"
        );
    }

    #[test]
    fn fm_film_hidden_exposure_cannot_move_to_another_line_after_text_changes() {
        let title = stable_key("fm-film", "film.top-left");
        let exposure = stable_key("fm-film", "film.top-right");
        let frame_no = stable_key("fm-film", "film.frame-number");
        let mut params = Params::new();
        params.insert(format!("slotOff:{exposure}"), serde_json::json!(false));

        // Reproducer: a title can become the prior exposure string. Identity
        // remains bound to the explicit template row, never to the text/source.
        let changed_title = resolve(&params, &title, "36 EXP");
        let still_exposure = resolve(&params, &exposure, "36 EXP");
        let frame = resolve(&params, &frame_no, "NO. 27");
        assert!(!changed_title.hidden);
        assert!(still_exposure.hidden);
        assert_eq!(frame.text, "NO. 27");
        assert_ne!(title, exposure);
    }

    #[test]
    fn flat_slot_overrides_follow_shared_contract() {
        let key = stable_key("fm-film", "film.frame-number");
        let mut params = Params::new();
        params.insert(format!("slotText:{key}"), serde_json::json!("36 EXP"));
        params.insert(format!("slotOff:{key}"), serde_json::json!(false));
        params.insert(format!("slotScale:{key}"), serde_json::json!(9.0));
        let resolved = resolve(&params, &key, "NO. 42");
        assert_eq!(resolved.text, "36 EXP");
        assert!(resolved.hidden);
        assert_eq!(resolved.scale, 1.6);

        params.insert(format!("slotOff:{key}"), serde_json::json!(true));
        params.insert(format!("slotScale:{key}"), serde_json::json!(0.2));
        let resolved = resolve(&params, &key, "NO. 42");
        assert!(!resolved.hidden);
        assert_eq!(resolved.scale, 0.6);

        // An empty value is not persisted as a replacement; source text returns.
        params.insert(format!("slotText:{key}"), serde_json::json!(""));
        assert_eq!(resolve(&params, &key, "NO. 42").text, "NO. 42");
    }

    #[test]
    fn blank_source_row_is_not_reported_as_user_hidden() {
        let key = stable_key("fm-film", "film.date");
        let resolved = resolve(&Params::new(), &key, "");
        assert_eq!(resolved.text, "");
        assert!(!resolved.hidden);
    }

    #[test]
    fn declared_rows_keep_their_legacy_namespace() {
        assert_eq!(declared_key("fm-film", "brand"), "fm-film#brand");
        assert_ne!(
            declared_key("fm-film", "brand"),
            stable_key("fm-film", "brand")
        );
    }
}
