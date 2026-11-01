//! `const polaroidFrame: TemplateDef` (templates.ts:4017) — 按画幅选择 Polaroid
//! 相框，照片填入透明窗口，底部写机型/日期/参数。Card: oppo-polaroid.

use skia_safe::Rect;

use crate::draw::{
    classic_exif_line, draw_fitted, draw_photo_cover, image_sampling, Env, FittedOpts, LayoutEnv,
};
use crate::hand::{format_date_only, model_without_make};

struct FrameMeta {
    w: f32,
    h: f32,
    win: [f32; 4],
    aspect: f32,
    landscape: bool,
}

const POLAROID_META: [FrameMeta; 7] = [
    FrameMeta { w: 960.0, h: 1256.0, win: [42.0, 87.0, 876.0, 875.0], aspect: 876.0 / 875.0, landscape: true },
    FrameMeta { w: 1248.0, h: 1256.0, win: [42.0, 87.0, 1164.0, 875.0], aspect: 1164.0 / 875.0, landscape: true },
    FrameMeta { w: 1224.0, h: 942.0, win: [30.0, 64.0, 1164.0, 659.0], aspect: 1164.0 / 659.0, landscape: true },
    FrameMeta { w: 1200.0, h: 616.0, win: [17.0, 40.0, 1166.0, 435.0], aspect: 1166.0 / 435.0, landscape: true },
    FrameMeta { w: 960.0, h: 1544.0, win: [42.0, 87.0, 876.0, 1163.0], aspect: 876.0 / 1163.0, landscape: false },
    FrameMeta { w: 720.0, h: 1446.0, win: [30.0, 64.0, 660.0, 1163.0], aspect: 660.0 / 1163.0, landscape: false },
    FrameMeta { w: 472.0, h: 1344.0, win: [18.0, 38.0, 436.0, 1170.0], aspect: 436.0 / 1170.0, landscape: false },
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

pub fn layout(env: &LayoutEnv) -> Result<(f32, f32), String> {
    let meta = choose_frame(env.pw, env.ph);
    let scale = env.crop.sw / meta.win[2];
    Ok((meta.w * scale, meta.h * scale))
}

pub fn render(env: &mut Env) -> Result<Rect, String> {
    let (w, h) = (env.w, env.h);
    let meta = choose_frame(env.pw, env.ph);
    let Some(frame) = env.images.frame.clone() else {
        env.ctx.record_gap("polaroidFrame: frame image missing".into());
        return Ok(Rect::from_xywh(0.0, 0.0, w, h));
    };
    let k = w / meta.w;
    env.ctx.set_fill("#ffffff");
    env.ctx.fill_rect(0.0, 0.0, w, h);
    // `drawImage` keeps the default `imageSmoothingQuality: 'low'` (bilinear).
    let mut samp = skia_safe::SamplingOptions::default();
    samp.filter = skia_safe::FilterMode::Linear;
    env.ctx.draw_image(
        &frame,
        Some(Rect::from_xywh(0.0, 0.0, frame.width() as f32, frame.height() as f32)),
        Rect::from_xywh(0.0, 0.0, w, h),
        samp,
        "frame",
    );
    let [wx, wy, ww, wh] = meta.win;
    draw_photo_cover(&mut env.ctx, env.photo, &env.crop, wx * k, wy * k, ww * k, wh * k, 0.0, 0.0, w.min(h));
    let bottom = (meta.h - wy - wh) * k;
    let s = w.min(h);
    let mut model = model_without_make(&env.exif.make, &env.exif.model);
    if model.is_empty() {
        model = env.exif.camera_line("");
    }
    let size = s * 0.022;
    let mopts = FittedOpts::new("Inter").weight(600).baseline("middle");
    draw_fitted(env, &model, wx * k, wy * k + wh * k + bottom * 0.42, ww * k * 0.45, size, "#222222", &mopts);
    let dt = if env.text.show_date { format_date_only(&env.exif.date_time) } else { String::new() };
    if !dt.is_empty() {
        let dopts = FittedOpts::new("JetBrains Mono").baseline("middle");
        draw_fitted(env, &dt, wx * k, wy * k + wh * k + bottom * 0.68, ww * k * 0.4, size * 0.8, "#77736c", &dopts);
    }
    let ex = classic_exif_line(env);
    if !ex.is_empty() {
        let eopts = FittedOpts::new("JetBrains Mono").align("right").baseline("middle");
        draw_fitted(env, &ex, (wx + ww) * k, wy * k + wh * k + bottom * 0.52, ww * k * 0.48, size * 0.8, "#77736c", &eopts);
    }
    Ok(Rect::from_xywh(wx * k, wy * k, ww * k, wh * k))
}
