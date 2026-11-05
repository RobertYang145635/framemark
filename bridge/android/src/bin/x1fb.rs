//! Host pre-flight for the Android paint code: renders one card with the same
//! `render_design` the .so exposes, so the output can be compared against the
//! desktop engine before a device is involved.
//!
//! x1fb <style.json> <photo> <exif.jpg> <fonts_dir> <out.png>
//!      [--max-edge N] [--theme ID] [--ratio W:H|F] [--brands DIR]
//!      [--asset key=path]...

use std::collections::HashMap;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 6 {
        eprintln!(
            "usage: x1fb <style.json> <photo> <exif.jpg> <fonts_dir> <out.png> [--max-edge N] [--theme ID] [--ratio W:H] [--brands DIR] [--asset key=path]..."
        );
        std::process::exit(2);
    }
    let mut assets: HashMap<String, String> = HashMap::new();
    let mut max_edge = 0i32;
    let mut theme: Option<String> = None;
    let mut ratio: Option<(f32, f32)> = None;
    let mut brands: Option<String> = None;
    let mut fallback: Option<String> = None;
    let mut overlay_path: Option<String> = None;
    let mut overlay_rect: [f32; 4] = [0.0, 0.0, 1.0, 1.0];
    let mut overlay_opacity: f32 = 1.0;
    let mut overlay_angle: f32 = 0.0;
    let mut i = 6;
    while i < args.len() {
        match args[i].as_str() {
            "--max-edge" => {
                max_edge = args.get(i + 1).and_then(|v| v.parse().ok()).unwrap_or(0);
                i += 2;
            }
            "--theme" => {
                theme = args.get(i + 1).cloned().filter(|v| !v.is_empty());
                i += 2;
            }
            "--ratio" => {
                if let Some(spec) = args.get(i + 1) {
                    ratio = if let Some((w, h)) = spec.split_once(':') {
                        Some((w.parse().unwrap_or(0.0), h.parse().unwrap_or(0.0)))
                    } else {
                        Some((spec.parse().unwrap_or(0.0), 1.0))
                    };
                }
                i += 2;
            }
            "--fallback-dir" => {
                fallback = args.get(i + 1).cloned().filter(|v| !v.is_empty());
                i += 2;
            }
            "--brands" => {
                brands = args.get(i + 1).cloned().filter(|v| !v.is_empty());
                i += 2;
            }
            "--asset" => {
                if let Some(spec) = args.get(i + 1) {
                    if let Some((k, v)) = spec.split_once('=') {
                        assets.insert(k.to_string(), v.to_string());
                    }
                }
                i += 2;
            }
            "--overlay" => {
                overlay_path = args.get(i + 1).cloned().filter(|v| !v.is_empty());
                i += 2;
            }
            "--overlay-rect" => {
                if let Some(spec) = args.get(i + 1) {
                    let v: Vec<f32> = spec
                        .split(',')
                        .filter_map(|p| p.trim().parse().ok())
                        .collect();
                    if v.len() != 4 {
                        eprintln!("--overlay-rect needs x,y,w,h");
                        std::process::exit(2);
                    }
                    overlay_rect = [v[0], v[1], v[2], v[3]];
                }
                i += 2;
            }
            "--overlay-opacity" => {
                overlay_opacity = args.get(i + 1).and_then(|v| v.parse().ok()).unwrap_or(1.0);
                i += 2;
            }
            "--overlay-angle" => {
                overlay_angle = args.get(i + 1).and_then(|v| v.parse().ok()).unwrap_or(0.0);
                i += 2;
            }
            other => {
                eprintln!("unknown argument: {other}");
                std::process::exit(2);
            }
        }
    }

    let overlay = match overlay_path.as_deref() {
        Some(path) => Some(
            match fm_android_engine::overlay_layer(
                path,
                overlay_rect,
                overlay_opacity,
                overlay_angle,
            ) {
                Ok(layer) => layer,
                Err(e) => {
                    eprintln!("X1FB_FAIL {e}");
                    std::process::exit(2);
                }
            },
        ),
        None => None,
    };
    // Host-side A/B for the design glue: FM_DESIGN_JSON=<file> feeds the same
    // document the phone would hand to `fm_render_design`, so the bridge's
    // parse can be compared against e2b `--design` without a device.
    let design = std::env::var("FM_DESIGN_JSON")
        .ok()
        .filter(|p| !p.is_empty())
        .and_then(|p| std::fs::read_to_string(p).ok());
    let options = fm_android_engine::RenderOptions {
        theme: theme.as_deref(),
        design_json: design.as_deref(),
        ratio: ratio.filter(|(w, h)| *w > 0.0 && *h > 0.0),
        brands_dir: brands.as_deref(),
        fallback_dir: fallback.as_deref(),
        overlay,
    };
    match fm_android_engine::render_design(
        &args[1], &args[2], &args[3], &assets, &args[4], &args[5], max_edge, &options,
    ) {
        Ok(stats) => {
            println!(
                "X1FB_OK style={} canvas={}x{} layout={}x{} texts={} images={} groups={} shapes={} brand_slots={}",
                stats.style,
                stats.width,
                stats.height,
                stats.layout_w,
                stats.layout_h,
                stats.texts,
                stats.images,
                stats.groups,
                stats.shapes,
                stats.brand_slots
            );
            for warning in &stats.warnings {
                println!("X1FB_WARN {warning}");
            }
        }
        Err(e) => {
            eprintln!("X1FB_FAIL {e}");
            std::process::exit(1);
        }
    }
}
