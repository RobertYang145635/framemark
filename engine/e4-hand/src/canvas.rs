//! Canvas2D-shaped drawing over a Skia canvas.
//!
//! State is exactly what the hand templates read/write through `ctx`:
//! `fillStyle`, `globalAlpha`, the transform stack, and a `save()/restore()`
//! stack. Stroke colour/width/dash are passed explicitly by the helpers (the
//! frozen code always sets them inside a save scope).
//!
//! Implemented: fill/stroke rect, rounded-rect fill/stroke, line, linear
//! gradient fill, rounded-rect clip, image blits (optionally through an image
//! filter), text blobs, and the draw report. Gradients/interpolation flags and
//! filter tile modes are calibrated against the goldens in phase 2.

use skia_safe::{
    canvas::SrcRectConstraint, Canvas, Color, FilterMode, Image, ImageFilter, Matrix, Paint,
    PathBuilder, Point, RRect, Rect, SamplingOptions, Shader, TextBlob,
};

use crate::css;
use crate::report::{ImageDraw, RectJson, Report, ShapeDraw, TextDraw};

#[derive(Clone)]
struct State {
    fill: Color,
    alpha: f32,
    matrix: Matrix,
}

/// Optional user watermark layer (render-v1 `overlay`). The rect is normalised
/// to the photo draw box; the image is contained + centred inside it and
/// clipped to the photo box.
#[derive(Clone)]
pub struct OverlaySpec {
    pub image: Image,
    pub rect: [f32; 4],
    pub opacity: f32,
    /// Degrees clockwise around the final dst centre; 0 keeps the old path.
    pub angle: f32,
}

pub struct Ctx2D<'a> {
    canvas: &'a Canvas,
    report: &'a std::cell::RefCell<Report>,
    st: State,
    stack: Vec<State>,
    seq: u32,
    overlay: Option<OverlaySpec>,
    overlay_done: bool,
    /// `--fit native`: force the photo's own draw box to integral pixels at
    /// exactly this size, so the source pixels land 1:1 (see `snap_photo`).
    photo_1to1: Option<(f32, f32)>,
    /// render-v1 photo crop: where the user's kept region sits in the source.
    /// The photo draws read it so a pan moves the sampled slice even when the
    /// template's window spans the whole photo (see `hand::cover_src`).
    user_crop: crate::hand::UserCrop,
}

impl<'a> Ctx2D<'a> {
    pub fn new(canvas: &'a Canvas, report: &'a std::cell::RefCell<Report>) -> Self {
        Self {
            canvas,
            report,
            st: State {
                fill: Color::BLACK,
                alpha: 1.0,
                matrix: Matrix::new_identity(),
            },
            stack: Vec::new(),
            seq: 0,
            overlay: None,
            overlay_done: false,
            photo_1to1: None,
            user_crop: crate::hand::UserCrop::default(),
        }
    }

    pub fn set_photo_1to1(&mut self, size: Option<(f32, f32)>) {
        self.photo_1to1 = size;
    }

    pub fn set_user_crop(&mut self, user: crate::hand::UserCrop) {
        self.user_crop = user;
    }

    /// The design's crop, or `None` at the defaults — every photo draw keeps
    /// today's geometry unless the user actually chose a region.
    pub fn user_crop(&self) -> Option<crate::hand::UserCrop> {
        if self.user_crop.is_default() {
            None
        } else {
            Some(self.user_crop)
        }
    }

    /// Snap a template's photo box onto the pixel grid at the source size.
    /// `None` (the default fit) means "paint the template box exactly as the
    /// template computed it" — the numbers are never round-tripped through a
    /// Rect, because `(x + w) - x` is not `w` in f32 and that shift alone moves
    /// rasterised pixels.
    pub fn snap_photo(&self, x: f32, y: f32) -> Option<(f32, f32, f32, f32)> {
        self.photo_1to1
            .map(|(sw, sh)| (x.round(), y.round(), sw, sh))
    }

    pub fn set_overlay(&mut self, overlay: Option<OverlaySpec>) {
        self.overlay = overlay;
    }

    /// Paint the user overlay once, immediately above the photo layer. Called
    /// from `draw_photo`/`draw_photo_cover` while the photo's own clip is
    /// active, so the layer is clipped to the photo draw box and anything the
    /// template paints afterwards (border/text/logo) stays above it.
    pub fn draw_overlay_once(&mut self, photo: Rect) {
        if self.overlay_done {
            return;
        }
        let Some(ov) = self.overlay.take() else {
            return;
        };
        self.overlay_done = true;
        let (pw, ph) = (photo.width(), photo.height());
        if pw <= 0.0 || ph <= 0.0 {
            return;
        }
        let bx = photo.left() + ov.rect[0] * pw;
        let by = photo.top() + ov.rect[1] * ph;
        let bw = ov.rect[2] * pw;
        let bh = ov.rect[3] * ph;
        if bw <= 0.0 || bh <= 0.0 {
            return;
        }
        let iw = ov.image.width() as f32;
        let ih = ov.image.height() as f32;
        if iw <= 0.0 || ih <= 0.0 {
            return;
        }
        let s = (bw / iw).min(bh / ih);
        let (dw, dh) = (iw * s, ih * s);
        let dst = Rect::from_xywh(bx + (bw - dw) * 0.5, by + (bh - dh) * 0.5, dw, dh);
        let samp = crate::draw::image_sampling(iw, dw, ih, dh);
        let mut p = Paint::default();
        p.set_anti_alias(true);
        p.set_alpha_f((self.st.alpha * ov.opacity).clamp(0.0, 1.0));
        let _ = self.canvas.save();
        self.canvas.concat(&self.st.matrix);
        self.canvas
            .clip_rect(photo, skia_safe::ClipOp::Intersect, true);
        if ov.angle.abs() > 0.0001 {
            self.canvas.rotate(ov.angle, Some(dst.center()));
        }
        self.canvas
            .draw_image_rect_with_sampling_options(&ov.image, None, &dst, samp, &p);
        let _ = self.canvas.restore();
        let seq = self.next_seq();
        self.report.borrow_mut().images.push(ImageDraw {
            seq,
            key: "overlay".into(),
            dst: RectJson::from_skia(dst),
            src: None,
            sampling: format!("{samp:?}"),
        });
        let seq = self.next_seq();
        self.report.borrow_mut().decor.push(ShapeDraw {
            seq,
            underlay: false,
            kind: "overlay".into(),
            rect: RectJson::from_skia(dst),
            color: None,
        });
    }

    pub fn canvas(&self) -> &Canvas {
        self.canvas
    }

    /// The canvas with the struct's own lifetime, so a caller can hold it while
    /// mutating other fields of the `Env`.
    pub fn canvas_ref(&self) -> &'a Canvas {
        self.canvas
    }

    pub fn save(&mut self) {
        self.stack.push(self.st.clone());
    }

    pub fn restore(&mut self) {
        if let Some(s) = self.stack.pop() {
            self.st = s;
        }
    }

    pub fn set_fill(&mut self, css_value: &str) {
        match css::parse(css_value) {
            Some(c) => self.st.fill = c.to_color(1.0),
            None => self.report.borrow_mut().gap(format!(
                "invalid Canvas2D colour '{css_value}' (assignment is a no-op)"
            )),
        }
    }

    pub fn set_global_alpha(&mut self, a: f32) {
        self.st.alpha = a.clamp(0.0, 1.0);
    }

    pub fn global_alpha(&self) -> f32 {
        self.st.alpha
    }

    pub fn translate(&mut self, dx: f32, dy: f32) {
        self.st.matrix.pre_translate((dx, dy));
    }

    pub fn scale(&mut self, sx: f32, sy: f32) {
        self.st.matrix.pre_concat(&Matrix::scale((sx, sy)));
    }

    pub fn rotate(&mut self, radians: f32) {
        self.st.matrix.pre_concat(&Matrix::rotate_rad(radians));
    }

    /// `ctx.transform(a, b, c, d, e, f)` — Canvas's 2x3 is (a,b,c,d,e,f) with
    /// x' = a*x + c*y + e, y' = b*x + d*y + f; Skia's new_all is column-major.
    pub fn transform(&mut self, a: f32, b: f32, c: f32, d: f32, e: f32, f: f32) {
        self.st
            .matrix
            .pre_concat(&Matrix::new_all(a, c, e, b, d, f, 0.0, 0.0, 1.0));
    }

    pub fn matrix(&self) -> Matrix {
        self.st.matrix
    }

    fn paint(&self, color: Color) -> Paint {
        let mut p = Paint::default();
        p.set_anti_alias(true);
        // Canvas2D globalAlpha MULTIPLIES the colour's own alpha; Skia's
        // set_alpha_f would replace it.
        let a = (color.a() as f32 / 255.0) * self.st.alpha;
        // Chromium keeps globalAlpha as a FLOAT in the paint (setAlphaf); a
        // rounded u8 alpha shifts every blend by up to 1/255.
        p.set_color(Color::from_argb(255, color.r(), color.g(), color.b()));
        p.set_alpha_f(a.clamp(0.0, 1.0));
        p
    }

    fn color_of(&self, css_value: &str) -> Color {
        match css::parse(css_value) {
            Some(c) => c.to_color(1.0),
            None => {
                self.report
                    .borrow_mut()
                    .gap(format!("invalid Canvas2D colour '{css_value}'"));
                Color::TRANSPARENT
            }
        }
    }

    fn next_seq(&mut self) -> u32 {
        self.seq += 1;
        self.seq
    }

    fn scoped(&mut self, f: impl FnOnce(&Canvas)) {
        let _ = self.canvas.save();
        self.canvas.concat(&self.st.matrix);
        f(self.canvas);
        let _ = self.canvas.restore();
    }

    pub fn fill_rect(&mut self, x: f32, y: f32, w: f32, h: f32) {
        let p = self.paint(self.st.fill);
        self.scoped(|c| {
            c.draw_rect(Rect::from_xywh(x, y, w, h), &p);
        });
    }

    pub fn fill_circle(&mut self, cx: f32, cy: f32, radius: f32, color: &str) {
        let col = self.color_of(color);
        let p = self.paint(col);
        self.scoped(|c| {
            c.draw_circle(Point::new(cx, cy), radius.max(0.0), &p);
        });
    }

    pub fn fill_rect_color(&mut self, x: f32, y: f32, w: f32, h: f32, color: &str) {
        let col = self.color_of(color);
        let p = self.paint(col);
        self.scoped(|c| {
            c.draw_rect(Rect::from_xywh(x, y, w, h), &p);
        });
    }

    /// Canvas `shadowColor/shadowBlur/shadowOffsetY` on a rounded-rect fill.
    /// Blink maps shadowBlur to a Gaussian sigma = blur/2 (SkBlurMaskFilter).
    #[allow(clippy::too_many_arguments)]
    pub fn fill_round_rect_shadow(
        &mut self,
        x: f32,
        y: f32,
        w: f32,
        h: f32,
        r: f32,
        color: &str,
        shadow_color: &str,
        blur: f32,
        offset_y: f32,
    ) {
        let path = round_rect_path(x, y, w, h, r);
        let sc = self.color_of(shadow_color);
        let mut p = Paint::default();
        p.set_anti_alias(true);
        p.set_color(Color::from_argb(255, sc.r(), sc.g(), sc.b()));
        p.set_alpha_f((sc.a() as f32 / 255.0) * self.st.alpha);
        if let Some(mf) =
            skia_safe::MaskFilter::blur(skia_safe::BlurStyle::Normal, blur * 0.5, None)
        {
            p.set_mask_filter(mf);
        }
        self.scoped(|c| {
            let _ = c.save();
            c.translate((0.0, offset_y));
            c.draw_path(&path, &p);
            let _ = c.restore();
        });
        self.fill_round_rect(x, y, w, h, r, color);
    }

    pub fn fill_round_rect(&mut self, x: f32, y: f32, w: f32, h: f32, r: f32, color: &str) {
        let col = self.color_of(color);
        let p = self.paint(col);
        let path = round_rect_path(x, y, w, h, r);
        self.scoped(|c| {
            c.draw_path(&path, &p);
        });
    }

    pub fn stroke_round_rect(
        &mut self,
        x: f32,
        y: f32,
        w: f32,
        h: f32,
        r: f32,
        color: &str,
        width: f32,
        dash: Option<&[f32]>,
    ) {
        let col = self.color_of(color);
        let mut p = self.paint(col);
        p.set_style(skia_safe::PaintStyle::Stroke);
        p.set_stroke_width(width.max(0.0001));
        let path = round_rect_path(x, y, w, h, r);
        if let Some(effect) = dash.and_then(dash_effect) {
            p.set_path_effect(effect);
        }
        self.scoped(|c| {
            c.draw_path(&path, &p);
        });
    }

    pub fn stroke_rect(&mut self, x: f32, y: f32, w: f32, h: f32, color: &str, width: f32) {
        let col = self.color_of(color);
        let mut p = self.paint(col);
        p.set_style(skia_safe::PaintStyle::Stroke);
        p.set_stroke_width(width.max(0.0001));
        self.scoped(|c| {
            c.draw_rect(Rect::from_xywh(x, y, w, h), &p);
        });
    }

    pub fn stroke_line(
        &mut self,
        x1: f32,
        y1: f32,
        x2: f32,
        y2: f32,
        color: &str,
        width: f32,
        dash: Option<&[f32]>,
    ) {
        let col = self.color_of(color);
        let mut p = self.paint(col);
        p.set_style(skia_safe::PaintStyle::Stroke);
        p.set_stroke_width(width.max(0.0001));
        if let Some(d) = dash {
            if let Some(effect) = dash_effect(d) {
                p.set_path_effect(effect);
            }
        }
        self.scoped(|c| {
            c.draw_line(Point::new(x1, y1), Point::new(x2, y2), &p);
        });
    }

    /// `createLinearGradient(x0,y0,x1,y1)` + `fillRect`: stops are (offset, css).
    pub fn fill_linear_gradient(
        &mut self,
        x0: f32,
        y0: f32,
        x1: f32,
        y1: f32,
        stops: &[(f32, &str)],
        rect: Rect,
    ) {
        let mut colors = Vec::with_capacity(stops.len());
        let mut pos = Vec::with_capacity(stops.len());
        for (o, c) in stops {
            let col = self.color_of(c);
            colors.push(col);
            pos.push(*o);
        }
        let shader = Shader::linear_gradient(
            (Point::new(x0, y0), Point::new(x1, y1)),
            colors.as_slice(),
            Some(pos.as_slice()),
            skia_safe::TileMode::Clamp,
            None,
            None,
        );
        let Some(shader) = shader else {
            self.report
                .borrow_mut()
                .gap("linear gradient shader build failed");
            return;
        };
        let mut p = Paint::default();
        p.set_anti_alias(true);
        p.set_shader(shader);
        p.set_alpha_f(self.st.alpha);
        self.scoped(|c| {
            c.draw_rect(rect, &p);
        });
        let seq = self.next_seq();
        self.report.borrow_mut().decor.push(ShapeDraw {
            seq,
            underlay: false,
            kind: "linear-gradient".into(),
            rect: RectJson::from_skia(rect),
            color: None,
        });
    }

    /// `createRadialGradient(x0,y0,r0,x1,y1,r1)` — a two-point conical shader.
    #[allow(deprecated)]
    pub fn fill_radial_gradient(
        &mut self,
        x0: f32,
        y0: f32,
        r0: f32,
        x1: f32,
        y1: f32,
        r1: f32,
        stops: &[(f32, &str)],
        rect: Rect,
    ) {
        let mut colors = Vec::with_capacity(stops.len());
        let mut pos = Vec::with_capacity(stops.len());
        for (o, c) in stops {
            let col = self.color_of(c);
            colors.push(col);
            pos.push(*o);
        }
        let shader = Shader::two_point_conical_gradient(
            Point::new(x0, y0),
            r0.max(0.0),
            Point::new(x1, y1),
            r1.max(0.0),
            colors.as_slice(),
            Some(pos.as_slice()),
            skia_safe::TileMode::Clamp,
            None,
            None,
        );
        let Some(shader) = shader else {
            self.report
                .borrow_mut()
                .gap("radial gradient shader build failed");
            return;
        };
        let mut p = Paint::default();
        p.set_anti_alias(true);
        p.set_shader(shader);
        p.set_alpha_f(self.st.alpha);
        self.scoped(|c| {
            c.draw_rect(rect, &p);
        });
        let seq = self.next_seq();
        self.report.borrow_mut().decor.push(ShapeDraw {
            seq,
            underlay: false,
            kind: "radial-gradient".into(),
            rect: RectJson::from_skia(rect),
            color: None,
        });
    }

    /// `createPattern(repeat)` + `globalCompositeOperation:'overlay'` fill —    /// the film/polaroid grain layer. The pattern is anchored at the canvas
    /// origin, like Canvas2D pattern space for an identity CTM.
    pub fn fill_image_pattern_overlay(&mut self, img: &Image, rect: Rect, alpha: f32) {
        // A/B: cubic sampling made the grain much worse (polaroid 189k -> 695k),
        // so Chromium samples the pattern like a nearest-tiled bitmap here.
        let samp = SamplingOptions::default();
        let Some(shader) = img.to_shader(
            (skia_safe::TileMode::Repeat, skia_safe::TileMode::Repeat),
            samp,
            None,
        ) else {
            self.record_gap("pattern shader build failed".into());
            return;
        };
        let mut p = Paint::default();
        p.set_anti_alias(true);
        p.set_shader(shader);
        p.set_blend_mode(skia_safe::BlendMode::Overlay);
        p.set_alpha_f(alpha.clamp(0.0, 1.0) * self.st.alpha);
        self.scoped(|c| {
            c.draw_rect(rect, &p);
        });
    }

    /// Trilinear draw: two mip levels are rendered into a transparent layer
    /// with weights (1-frac, frac) via Plus blending, then composited. This is
    /// what Blink's mipmapped filtering does for a downscaled bitmap.
    pub fn draw_image_trilinear(
        &mut self,
        lo: &Image,
        hi: &Image,
        frac: f32,
        dst: Rect,
        key: &str,
    ) {
        let w = dst.width().round().max(1.0) as i32;
        let h = dst.height().round().max(1.0) as i32;
        let mut surface = crate::gpu::raster_surface(w, h);
        {
            let canvas = surface.canvas();
            canvas.clear(Color::TRANSPARENT);
            let mut p0 = skia_safe::Paint::default();
            p0.set_anti_alias(true);
            p0.set_alpha_f(1.0 - frac);
            let mut samp = SamplingOptions::default();
            samp.filter = FilterMode::Linear;
            canvas.draw_image_rect_with_sampling_options(
                lo,
                None,
                &Rect::from_xywh(0.0, 0.0, w as f32, h as f32),
                samp,
                &p0,
            );
            let mut p1 = skia_safe::Paint::default();
            p1.set_anti_alias(true);
            p1.set_alpha_f(frac);
            p1.set_blend_mode(skia_safe::BlendMode::Plus);
            canvas.draw_image_rect_with_sampling_options(
                hi,
                None,
                &Rect::from_xywh(0.0, 0.0, w as f32, h as f32),
                samp,
                &p1,
            );
        }
        let img = surface.image_snapshot();
        let p = self.paint(Color::WHITE);
        let _ = self.canvas.save();
        self.canvas.concat(&self.st.matrix);
        let mut fsamp = SamplingOptions::default();
        fsamp.filter = FilterMode::Linear;
        self.canvas
            .draw_image_rect_with_sampling_options(&img, None, &dst, fsamp, &p);
        let _ = self.canvas.restore();
        self.report.borrow_mut().images.push(ImageDraw {
            seq: self.next_seq(),
            key: key.to_string(),
            dst: RectJson::from_skia(dst),
            src: None,
            sampling: "trilinear".into(),
        });
    }

    pub fn draw_image(
        &mut self,
        img: &Image,
        src: Option<Rect>,
        dst: Rect,
        sampling: SamplingOptions,
        key: &str,
    ) {
        self.draw_image_filtered(img, src, dst, sampling, None, key);
    }

    pub fn draw_image_filtered(
        &mut self,
        img: &Image,
        src: Option<Rect>,
        dst: Rect,
        sampling: SamplingOptions,
        filter: Option<ImageFilter>,
        key: &str,
    ) {
        let p = self.paint(Color::WHITE);
        let mut p = p;
        if let Some(f) = filter {
            p.set_image_filter(f);
        }
        let mip = matches!(
            std::env::var("E4H_IMG_SAMPLING").ok().as_deref(),
            Some("mipmap") | Some("cubicmip")
        );
        let img_owned;
        let img = if mip {
            img_owned = img.with_default_mipmaps().unwrap_or_else(|| img.clone());
            &img_owned
        } else {
            img
        };
        self.scoped(|c| match src {
            Some(s) => {
                c.draw_image_rect_with_sampling_options(
                    img,
                    Some((&s, SrcRectConstraint::Strict)),
                    &dst,
                    sampling,
                    &p,
                );
            }
            None => {
                c.draw_image_rect_with_sampling_options(img, None, &dst, sampling, &p);
            }
        });
        let seq = self.next_seq();
        self.report.borrow_mut().images.push(ImageDraw {
            seq,
            key: key.to_string(),
            dst: RectJson::from_skia(dst),
            src: src.map(RectJson::from_skia),
            sampling: format!("{sampling:?}"),
        });
        if key != "photo" && key != "logo" {
            let seq = self.next_seq();
            self.report.borrow_mut().decor.push(ShapeDraw {
                seq,
                underlay: key == "backdrop",
                kind: key.to_string(),
                rect: RectJson::from_skia(dst),
                color: None,
            });
        }
    }

    /// Chromium's canvas `filter` path: the draw goes into a layer, and the
    /// filter is applied to the LAYER in device space (image first upscaled,
    /// then blurred). A paint image filter would filter the source bitmap
    /// instead and is numerically different for large sigmas.
    pub fn draw_image_layer_filtered(
        &mut self,
        img: &Image,
        src: Option<Rect>,
        dst: Rect,
        sampling: SamplingOptions,
        filter: ImageFilter,
        key: &str,
    ) {
        let mut layer_paint = Paint::default();
        layer_paint.set_image_filter(filter);
        let rec = skia_safe::canvas::SaveLayerRec::default().paint(&layer_paint);
        let _ = self.canvas.save();
        self.canvas.concat(&self.st.matrix);
        let _ = self.canvas.save_layer(&rec);
        let p = self.paint(Color::WHITE);
        match src {
            Some(s) => {
                self.canvas.draw_image_rect_with_sampling_options(
                    img,
                    Some((&s, SrcRectConstraint::Strict)),
                    &dst,
                    sampling,
                    &p,
                );
            }
            None => {
                self.canvas
                    .draw_image_rect_with_sampling_options(img, None, &dst, sampling, &p);
            }
        }
        let _ = self.canvas.restore();
        let _ = self.canvas.restore();
        let seq = self.next_seq();
        self.report.borrow_mut().images.push(ImageDraw {
            seq,
            key: key.to_string(),
            dst: RectJson::from_skia(dst),
            src: src.map(RectJson::from_skia),
            sampling: format!("{sampling:?}"),
        });
        self.report.borrow_mut().decor.push(ShapeDraw {
            seq,
            underlay: key == "backdrop",
            kind: key.to_string(),
            rect: RectJson::from_skia(dst),
            color: None,
        });
    }

    pub fn clip_round_rect(&mut self, x: f32, y: f32, w: f32, h: f32, r: f32) {
        let path = round_rect_path(x, y, w, h, r);
        let _ = self.canvas.save();
        self.canvas.concat(&self.st.matrix);
        self.canvas
            .clip_path(&path, skia_safe::ClipOp::Intersect, true);
    }

    pub fn clip_circle(&mut self, cx: f32, cy: f32, radius: f32) {
        let r = radius.max(0.0);
        let path = skia_safe::Path::oval(Rect::from_xywh(cx - r, cy - r, r * 2.0, r * 2.0), None);
        let _ = self.canvas.save();
        self.canvas.concat(&self.st.matrix);
        self.canvas
            .clip_path(&path, skia_safe::ClipOp::Intersect, true);
    }

    pub fn stroke_circle(&mut self, cx: f32, cy: f32, radius: f32, color: &str, width: f32) {
        let col = self.color_of(color);
        let mut p = self.paint(col);
        p.set_style(skia_safe::PaintStyle::Stroke);
        p.set_stroke_width(width.max(0.0001));
        self.scoped(|c| {
            c.draw_circle(Point::new(cx, cy), radius.max(0.0), &p);
        });
    }

    pub fn clip_rect(&mut self, x: f32, y: f32, w: f32, h: f32) {
        let _ = self.canvas.save();
        self.canvas.concat(&self.st.matrix);
        self.canvas.clip_rect(
            Rect::from_xywh(x, y, w, h),
            skia_safe::ClipOp::Intersect,
            true,
        );
    }

    pub fn restore_clip(&mut self) {
        let _ = self.canvas.restore();
    }

    pub fn draw_text_blob(
        &mut self,
        blob: &TextBlob,
        x: f32,
        y: f32,
        color: Color,
        rec: &TextDraw,
    ) {
        let p = self.paint(color);
        let _ = self.canvas.save();
        self.canvas.concat(&self.st.matrix);
        self.canvas.draw_text_blob(blob, (x, y), &p);
        let _ = self.canvas.restore();
        self.report.borrow_mut().texts.push(rec.clone());
    }

    pub fn record_shape(&mut self, kind: &str, rect: Rect, color: Option<String>, decor: bool) {
        let seq = self.next_seq();
        let mut r = self.report.borrow_mut();
        let rec = ShapeDraw {
            seq,
            underlay: false,
            kind: kind.into(),
            rect: RectJson::from_skia(rect),
            color,
        };
        if decor {
            r.decor.push(rec);
        } else {
            r.shapes.push(rec);
        }
    }

    pub fn record_photo(&mut self, rect: Rect, radius: f32) {
        let seq = self.next_seq();
        let mut r = self.report.borrow_mut();
        r.photo = Some(RectJson::from_skia(rect));
        r.photo_seq = seq;
        r.photo_radius = radius;
    }

    pub fn record_text(&mut self, mut rec: TextDraw) {
        rec.seq = self.next_seq();
        self.report.borrow_mut().texts.push(rec);
    }

    pub fn record_underlay(&mut self, kind: &str, rect: Rect, color: Option<String>) {
        let seq = self.next_seq();
        let mut r = self.report.borrow_mut();
        r.decor.push(ShapeDraw {
            seq,
            underlay: true,
            kind: kind.into(),
            rect: RectJson::from_skia(rect),
            color,
        });
    }

    pub fn record_gap(&mut self, msg: String) {
        self.report.borrow_mut().gap(msg);
    }
}

pub fn round_rect_path(x: f32, y: f32, w: f32, h: f32, r: f32) -> skia_safe::Path {
    let rr = r.max(0.0).min(w.min(h) / 2.0);
    let mut b = PathBuilder::new();
    b.add_rrect(
        RRect::new_rect_xy(Rect::from_xywh(x, y, w, h), rr, rr),
        skia_safe::PathDirection::CW,
        None,
    );
    b.detach()
}

fn dash_effect(dash: &[f32]) -> Option<skia_safe::PathEffect> {
    if dash.len() < 2 {
        return None;
    }
    skia_safe::PathEffect::dash(dash, 0.0)
}
