//! Canvas2D colour parsing for the literals the hand templates paint with.
//!
//! The frozen cards use `#rrggbb`, `#rrggbbaa` (Canvas2D: RRGGBBAA, not Android
//! AARRGGBB), `rgba(r,g,b,a)`, and a few names. An unparsable assignment is a
//! silent no-op in Canvas2D (`fillStyle` keeps the previous value), which is a
//! trap worth reporting rather than guessing.

use skia_safe::Color;

#[derive(Clone, Copy, Debug)]
pub struct CssColor {
    pub r: u8,
    pub g: u8,
    pub b: u8,
    pub a: u8,
}

impl CssColor {
    pub fn to_color(self, extra_alpha: f32) -> Color {
        let a = ((self.a as f32 / 255.0) * extra_alpha.clamp(0.0, 1.0) * 255.0).round() as u8;
        Color::from_argb(a, self.r, self.g, self.b)
    }
    pub fn alpha(&self) -> f32 {
        self.a as f32 / 255.0
    }
}

fn hex2(s: &str) -> Option<u8> {
    u8::from_str_radix(s, 16).ok()
}

pub fn parse(value: &str) -> Option<CssColor> {
    let s = value.trim();
    let lower = s.to_ascii_lowercase();
    match lower.as_str() {
        "transparent" => {
            return Some(CssColor {
                r: 0,
                g: 0,
                b: 0,
                a: 0,
            })
        }
        "black" => {
            return Some(CssColor {
                r: 0,
                g: 0,
                b: 0,
                a: 255,
            })
        }
        "white" => {
            return Some(CssColor {
                r: 255,
                g: 255,
                b: 255,
                a: 255,
            })
        }
        "red" => {
            return Some(CssColor {
                r: 255,
                g: 0,
                b: 0,
                a: 255,
            })
        }
        _ => {}
    }
    if let Some(hex) = s.strip_prefix('#') {
        return match hex.len() {
            3 => {
                let r = hex2(&hex[0..1])?;
                let g = hex2(&hex[1..2])?;
                let b = hex2(&hex[2..3])?;
                Some(CssColor {
                    r: r * 17,
                    g: g * 17,
                    b: b * 17,
                    a: 255,
                })
            }
            4 => {
                let r = hex2(&hex[0..1])? * 17;
                let g = hex2(&hex[1..2])? * 17;
                let b = hex2(&hex[2..3])? * 17;
                let a = hex2(&hex[3..4])? * 17;
                Some(CssColor { r, g, b, a })
            }
            6 => Some(CssColor {
                r: hex2(&hex[0..2])?,
                g: hex2(&hex[2..4])?,
                b: hex2(&hex[4..6])?,
                a: 255,
            }),
            8 => Some(CssColor {
                r: hex2(&hex[0..2])?,
                g: hex2(&hex[2..4])?,
                b: hex2(&hex[4..6])?,
                a: hex2(&hex[6..8])?,
            }),
            _ => None,
        };
    }
    if lower.starts_with("rgba(") || lower.starts_with("rgb(") {
        let open = s.find('(')?;
        let close = s.rfind(')')?;
        let inner = &s[open + 1..close];
        let parts: Vec<&str> = inner.split(',').map(|p| p.trim()).collect();
        if parts.len() != 3 && parts.len() != 4 {
            return None;
        }
        let ch = |p: &str| -> Option<u8> {
            if let Some(pct) = p.strip_suffix('%') {
                let v: f32 = pct.trim().parse().ok()?;
                Some(((v / 100.0) * 255.0).round().clamp(0.0, 255.0) as u8)
            } else {
                let v: f32 = p.trim().parse().ok()?;
                Some(v.round().clamp(0.0, 255.0) as u8)
            }
        };
        let r = ch(parts[0])?;
        let g = ch(parts[1])?;
        let b = ch(parts[2])?;
        let a = if parts.len() == 4 {
            let p = parts[3];
            if let Some(pct) = p.strip_suffix('%') {
                let v: f32 = pct.trim().parse().ok()?;
                ((v / 100.0) * 255.0).round().clamp(0.0, 255.0) as u8
            } else {
                let v: f32 = p.trim().parse().ok()?;
                (v * 255.0).round().clamp(0.0, 255.0) as u8
            }
        } else {
            255
        };
        return Some(CssColor { r, g, b, a });
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canvas_rgba_hex_order() {
        let c = parse("#11223344").unwrap();
        assert_eq!((c.r, c.g, c.b, c.a), (0x11, 0x22, 0x33, 0x44));
    }

    #[test]
    fn rgba_alpha_is_unit_float() {
        let c = parse("rgba(0,0,0,0.38)").unwrap();
        assert_eq!((c.r, c.g, c.b), (0, 0, 0));
        assert!((c.a as i32 - 97).abs() <= 1);
    }
}
