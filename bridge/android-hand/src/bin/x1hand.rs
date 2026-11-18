//! Host pre-flight for the Android hand renderer.
//! x1hand <cards.json> <card> <hand_dir> <fonts> <extra_fonts> <out.png> [--max-edge N]
use std::path::PathBuf;

fn main() {
    let a: Vec<String> = std::env::args().collect();
    if a.len() < 7 {
        eprintln!(
            "usage: x1hand <cards> <card> <hand> <fonts> <extra-fonts> <out.png> [--max-edge N]"
        );
        std::process::exit(2);
    }
    let mut max_edge = 0i32;
    let fallback = {
        let i = a.iter().position(|x| x == "--fallback-cjk");
        i.and_then(|i| a.get(i + 1))
            .cloned()
            .filter(|v| !v.is_empty())
    };
    if let Some(i) = a.iter().position(|x| x == "--max-edge") {
        max_edge = a.get(i + 1).and_then(|v| v.parse().ok()).unwrap_or(0);
    }
    let photo = a
        .iter()
        .position(|x| x == "--photo")
        .and_then(|i| a.get(i + 1))
        .cloned()
        .filter(|v| !v.is_empty())
        .map(std::path::PathBuf::from);
    let overlay = a
        .iter()
        .position(|x| x == "--overlay")
        .and_then(|i| a.get(i + 1))
        .cloned()
        .filter(|v| !v.is_empty());
    let overlay_rect = a
        .iter()
        .position(|x| x == "--overlay-rect")
        .and_then(|i| a.get(i + 1))
        .map(|spec| {
            spec.split(',')
                .filter_map(|p| p.trim().parse::<f32>().ok())
                .collect::<Vec<_>>()
        })
        .filter(|v| v.len() == 4)
        .map(|v| [v[0], v[1], v[2], v[3]])
        .unwrap_or([0.0, 0.0, 1.0, 1.0]);
    let overlay_opacity = a
        .iter()
        .position(|x| x == "--overlay-opacity")
        .and_then(|i| a.get(i + 1))
        .and_then(|v| v.parse::<f32>().ok())
        .unwrap_or(1.0);
    let overlay_angle = a
        .iter()
        .position(|x| x == "--overlay-angle")
        .and_then(|i| a.get(i + 1))
        .and_then(|v| v.parse::<f32>().ok())
        .unwrap_or(0.0);
    let overlay = match overlay.as_deref() {
        Some(path) => Some(
            match fm_android_hand::overlay_spec(path, overlay_rect, overlay_opacity, overlay_angle)
            {
                Ok(spec) => spec,
                Err(e) => {
                    eprintln!("X1HAND_FAIL {e}");
                    std::process::exit(2);
                }
            },
        ),
        None => None,
    };
    let design_arg = a
        .iter()
        .position(|x| x == "--design")
        .and_then(|i| a.get(i + 1))
        .cloned()
        .filter(|v| !v.is_empty());
    let design_doc: Option<serde_json::Value> = match design_arg.as_deref() {
        Some(raw) => {
            let body = match raw.strip_prefix('@') {
                Some(path) => std::fs::read_to_string(path).unwrap_or_default(),
                None => raw.to_string(),
            };
            match serde_json::from_str(&body) {
                Ok(v) => Some(v),
                Err(e) => {
                    eprintln!("X1HAND_FAIL design: {e}");
                    std::process::exit(2);
                }
            }
        }
        None => None,
    };
    match fm_android_hand::render_card(
        &a[1],
        &a[2],
        &a[3],
        &a[4],
        &a[5],
        fallback.as_deref(),
        photo.as_deref(),
        overlay,
        design_doc.as_ref(),
        &a[6],
        max_edge,
        // 交互档的采样长边：命令行证据工具始终走全分辨率，与导出一致。
        0,
    ) {
        Ok(r) => {
            println!(
                "X1HAND_OK card={} canvas={}x{} out={}x{} photo={:?} ms={}",
                r.card, r.raw_width, r.raw_height, r.width, r.height, r.photo_rect, r.ms
            );
            let _ = PathBuf::from(&a[6]);
        }
        Err(e) => {
            eprintln!("X1HAND_FAIL {e}");
            std::process::exit(1);
        }
    }
}
