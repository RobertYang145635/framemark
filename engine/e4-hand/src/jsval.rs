//! JavaScript number/string semantics the frozen templates rely on.
//!
//! `pct()` in `core/draw.ts` is `Number.isFinite(Number(v)) ? Number(v) : fallback`,
//! and `Number()` is NOT `as_f64().unwrap_or(fallback)`: `null` and `''` become 0,
//! `undefined` is NaN, `true` is 1. Several templates read a slider through that
//! function, so the difference is a layout difference.

use serde_json::Value;

/// JS `Number(v)` for the JSON shapes params can hold.
pub fn to_number(v: Option<&Value>) -> f64 {
    match v {
        None => f64::NAN, // undefined
        Some(Value::Null) => 0.0,
        Some(Value::Bool(b)) => {
            if *b {
                1.0
            } else {
                0.0
            }
        }
        Some(Value::Number(n)) => n.as_f64().unwrap_or(f64::NAN),
        Some(Value::String(s)) => js_number_from_str(s),
        _ => f64::NAN,
    }
}

fn js_number_from_str(s: &str) -> f64 {
    let t = s.trim();
    if t.is_empty() {
        return 0.0;
    }
    if let Some(hex) = t.strip_prefix("0x").or_else(|| t.strip_prefix("0X")) {
        return i64::from_str_radix(hex, 16).map(|v| v as f64).unwrap_or(f64::NAN);
    }
    if t == "Infinity" || t == "+Infinity" {
        return f64::INFINITY;
    }
    if t == "-Infinity" {
        return f64::NEG_INFINITY;
    }
    t.parse::<f64>().unwrap_or(f64::NAN)
}

/// `Number(undefined)` is NaN, not 0: a missing key must take the fallback.
pub fn to_number_present(v: Option<&Value>) -> f64 {
    match v {
        None => f64::NAN,
        Some(x) => to_number(Some(x)),
    }
}

/// `pct(value, fallback)` — the single most-used parameter reader in draw.ts.
pub fn pct(v: Option<&Value>, fallback: f64) -> f64 {
    let n = to_number_present(v);
    if n.is_finite() {
        n
    } else {
        fallback
    }
}

pub fn js_round(x: f64) -> f64 {
    // Math.round: half toward +Infinity (Rust's f64::round is half away from 0).
    (x + 0.5).floor()
}

pub fn truthy(v: Option<&Value>) -> bool {
    match v {
        None | Some(Value::Null) => false,
        Some(Value::Bool(b)) => *b,
        Some(Value::Number(n)) => n.as_f64().map(|x| x != 0.0 && !x.is_nan()).unwrap_or(false),
        Some(Value::String(s)) => !s.is_empty(),
        Some(Value::Array(_)) | Some(Value::Object(_)) => true,
    }
}

/// `String(v)` after a `?? default` (so null/undefined never reach here in the
/// frozen code paths we port; JSON null is reported by the caller instead).
pub fn js_string(v: Option<&Value>) -> String {
    match v {
        None => "undefined".into(),
        Some(Value::Null) => "null".into(),
        Some(Value::Bool(b)) => b.to_string(),
        Some(Value::Number(n)) => {
            if let Some(i) = n.as_i64() {
                i.to_string()
            } else if let Some(u) = n.as_u64() {
                u.to_string()
            } else {
                let f = n.as_f64().unwrap_or(0.0);
                format_js_number(f)
            }
        }
        Some(Value::String(s)) => s.clone(),
        Some(other) => other.to_string(),
    }
}

/// JS number -> string for the values templates interpolate into text.
pub fn format_js_number(f: f64) -> String {
    if f.fract() == 0.0 && f.abs() < 1e21 {
        format!("{}", f as i64)
    } else {
        let s = format!("{}", f);
        s
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn pct_matches_js_number() {
        assert_eq!(pct(None, 6.0), 6.0); // undefined -> NaN -> fallback
        assert_eq!(pct(Some(&json!(null)), 6.0), 0.0); // Number(null) === 0
        assert_eq!(pct(Some(&json!("")), 6.0), 0.0); // Number('') === 0
        assert_eq!(pct(Some(&json!("3.5")), 6.0), 3.5);
        assert_eq!(pct(Some(&json!(true)), 6.0), 1.0);
        assert_eq!(pct(Some(&json!("abc")), 6.0), 6.0);
    }

    #[test]
    fn round_is_half_up() {
        assert_eq!(js_round(2.5), 3.0);
        assert_eq!(js_round(-2.5), -2.0); // JS, not Rust's -3
    }
}
