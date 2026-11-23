//! `const polaroidFrame: TemplateDef` (templates.ts:4017) — 按画幅选择 Polaroid
//! 相框，照片填入透明窗口，底部写机型/日期/参数。Card: oppo-polaroid.

use skia_safe::Rect;

use crate::draw::{
    classic_exif_line, draw_fitted, draw_photo_cover, image_sampling, Env, FittedOpts, LayoutEnv,
};
use crate::hand::{format_date_only, model_without_make};

struct FrameMeta {
    /// 相框图在 bundle 里的文件名 stem（`extras/…_<file>.png`）。
    file: &'static str,
    w: f32,
    h: f32,
    win: [f32; 4],
    aspect: f32,
    landscape: bool,
}

const POLAROID_META: [FrameMeta; 7] = [
    FrameMeta {
        file: "pld_frame_1_1_landscape",
        w: 960.0,
        h: 1256.0,
        win: [42.0, 87.0, 876.0, 875.0],
        aspect: 876.0 / 875.0,
        landscape: true,
    },
    FrameMeta {
        file: "pld_frame_4_3_landscape",
        w: 1248.0,
        h: 1256.0,
        win: [42.0, 87.0, 1164.0, 875.0],
        aspect: 1164.0 / 875.0,
        landscape: true,
    },
    FrameMeta {
        file: "pld_frame_16_9_landscape",
        w: 1224.0,
        h: 942.0,
        win: [30.0, 64.0, 1164.0, 659.0],
        aspect: 1164.0 / 659.0,
        landscape: true,
    },
    FrameMeta {
        file: "pld_frame_wide_landscape",
        w: 1200.0,
        h: 616.0,
        win: [17.0, 40.0, 1166.0, 435.0],
        aspect: 1166.0 / 435.0,
        landscape: true,
    },
    FrameMeta {
        file: "pld_frame_4_3_portrait",
        w: 960.0,
        h: 1544.0,
        win: [42.0, 87.0, 876.0, 1163.0],
        aspect: 876.0 / 1163.0,
        landscape: false,
    },
    FrameMeta {
        file: "pld_frame_16_9_portrait",
        w: 720.0,
        h: 1446.0,
        win: [30.0, 64.0, 660.0, 1163.0],
        aspect: 660.0 / 1163.0,
        landscape: false,
    },
    FrameMeta {
        file: "pld_frame_wide_portrait",
        w: 472.0,
        h: 1344.0,
        win: [18.0, 38.0, 436.0, 1170.0],
        aspect: 436.0 / 1170.0,
        landscape: false,
    },
];

fn choose_frame(pw: f32, ph: f32) -> &'static FrameMeta {
    let target = pw / ph;
    if (target - 1.0).abs() < 0.06 {
        return &POLAROID_META[0];
    }
    let landscape = target > 1.0;
    let mut best: Option<&FrameMeta> = None;
    for m in POLAROID_META.iter().filter(|m| m.landscape == landscape) {
        match best {
            None => best = Some(m),
            Some(b) => {
                if (m.aspect / target).ln().abs() < (b.aspect / target).ln().abs() {
                    best = Some(m);
                }
            }
        }
    }
    best.unwrap_or(&POLAROID_META[0])
}

/// 这张照片会用到哪张相框图（渲染前只解码这一张：7 张全解会白付 6 次解码）。
pub fn chosen_stem(pw: f32, ph: f32) -> &'static str {
    choose_frame(pw, ph).file
}

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let meta = choose_frame(env.pw, env.ph);
    let scale = env.crop.sw / meta.win[2];
    Ok((meta.w * scale, meta.h * scale))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let meta = choose_frame(env.pw, env.ph);
    let Some(frame) = env.images.frame_for(meta.file) else {
        env.ctx.record_gap(format!(
            "polaroidFrame: frame '{}' missing (bundle carries: {})",
            meta.file,
            env.images.frames.keys().cloned().collect::<Vec<_>>().join(", ")
        ));
        return Ok(Rect::from_xywh(0.0, 0.0, w, h));
    };
    // 框图必须和这一档的窗口同比例，否则会被非等比拉伸（旧包只带 4:3 横版时就是这样）。
    let art_aspect = frame.width() as f32 / frame.height().max(1) as f32;
    let want = meta.w / meta.h;
    if (art_aspect / want - 1.0).abs() > 0.01 {
        env.ctx.record_gap(format!(
            "polaroidFrame: frame '{}' art {:.3} vs layout {:.3} (stretched)",
            meta.file, art_aspect, want
        ));
    }
    let k = w / meta.w;
    env.ctx.set_fill("#ffffff");
    env.ctx.fill_rect(0.0, 0.0, w, h);
    // `drawImage` keeps the default `imageSmoothingQuality: 'low'` (bilinear).
    let mut samp = skia_safe::SamplingOptions::default();
    samp.filter = skia_safe::FilterMode::Linear;
    env.ctx.draw_image(
        frame,
        Some(Rect::from_xywh(
            0.0,
            0.0,
            frame.width() as f32,
            frame.height() as f32,
        )),
        Rect::from_xywh(0.0, 0.0, w, h),
        samp,
        "frame",
    );
    let [wx, wy, ww, wh] = meta.win;
    draw_photo_cover(
        &mut env.ctx,
        env.photo,
        &env.crop,
        wx * k,
        wy * k,
        ww * k,
        wh * k,
        0.0,
        0.0,
        w.min(h),
    );
    let bottom = (meta.h - wy - wh) * k;
    let s = w.min(h);
    let mut model = model_without_make(&env.exif.make, &env.exif.model);
    if model.is_empty() {
        model = env.exif.camera_line("");
    }
    let size = s * 0.022;
    let mopts = FittedOpts::new("Inter")
        .weight(600)
        .baseline("middle")
        .slot("polaroidFrame.model");
    draw_fitted(
        env,
        &model,
        wx * k,
        wy * k + wh * k + bottom * 0.42,
        ww * k * 0.45,
        size,
        "#222222",
        &mopts,
    );
    let dt = if env.text.show_date {
        format_date_only(&env.exif.date_time)
    } else {
        String::new()
    };
    let dopts = FittedOpts::new("JetBrains Mono")
        .baseline("middle")
        .slot("polaroidFrame.date");
    draw_fitted(
        env,
        &dt,
        wx * k,
        wy * k + wh * k + bottom * 0.68,
        ww * k * 0.4,
        size * 0.8,
        "#77736c",
        &dopts,
    );
    let ex = classic_exif_line(env);
    let eopts = FittedOpts::new("JetBrains Mono")
        .align("right")
        .baseline("middle")
        .slot("polaroidFrame.exif");
    draw_fitted(
        env,
        &ex,
        (wx + ww) * k,
        wy * k + wh * k + bottom * 0.52,
        ww * k * 0.48,
        size * 0.8,
        "#77736c",
        &eopts,
    );
    Ok(Rect::from_xywh(wx * k, wy * k, ww * k, wh * k))
}
