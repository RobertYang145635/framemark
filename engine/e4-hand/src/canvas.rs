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

/// Map a rect written in the photo's logical (full-resolution) pixels onto the
/// raster Skia actually samples. `scale == 1.0` returns the same numbers, so the
/// full decode keeps sampling exactly the rectangle it always did.
pub fn photo_raster_rect(scale: f32, logical: Rect) -> Rect {
    if scale == 1.0 {
        return logical;
    }
    Rect::from_xywh(
        logical.left() * scale,
        logical.top() * scale,
        logical.width() * scale,
        logical.height() * scale,
    )
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
    /// Interactive tier: the source photo's raster is `photo_raster_scale` of its
    /// logical pixels, and `photo_logical` is that logical size. Layout, crop math
    /// and the draw report stay logical; only the sampled rect moves (see
    /// [`Ctx2D::photo_src_rect`]).
    photo_raster_scale: f32,
    photo_logical: Option<(f32, f32)>,
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
            photo_raster_scale: 1.0,
            photo_logical: None,
            user_crop: crate::hand::UserCrop::default(),
        }
    }

    pub fn set_photo_1to1(&mut self, size: Option<(f32, f32)>) {
        self.photo_1to1 = size;
    }

    /// Declare the interactive source decode: `scale` is raster/logical pixels and
    /// `logical` is the photo's full-resolution size. A ratio that is not a real
    /// shrink is ignored — it could only move geometry, never sharpen a frame.
    pub fn set_photo_source(&mut self, scale: f32, logical: (f32, f32)) {
        let usable = scale.is_finite()
            && scale > 0.0
            && scale <= 1.0
            && logical.0.is_finite()
            && logical.1.is_finite()
            && logical.0 > 0.0
            && logical.1 > 0.0;
        if usable {
            self.photo_raster_scale = scale;
            self.photo_logical = Some(logical);
        }
    }

    /// Raster pixels per logical pixel of the source photo (1.0 = full decode).
    pub fn photo_raster_scale(&self) -> f32 {
        self.photo_raster_scale
    }

    /// The photo's logical size when a reduced raster is active, else `None`
    /// (callers fall back to the image's own dimensions).
    pub fn photo_logical(&self) -> Option<(f32, f32)> {
        self.photo_logical
    }

    /// Move a source rect written in the photo's logical pixels onto the decoded
    /// raster. This is the only place the interactive tier changes a draw.
    pub fn photo_src_rect(&self, logical: Rect) -> Rect {
        photo_raster_rect(self.photo_raster_scale, logical)
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
        self.draw_image_recorded(img, src, src, dst, sampling, None, key);
    }

    /// Draw the source photo. `src_logical` is written in the photo's logical
    /// pixels and is what the draw report keeps (so `photo_src`, the layering
    /// checks and the geometry report never see the interactive tier); the rect
    /// handed to Skia is mapped into the decoded raster.
    pub fn draw_photo_image(
        &mut self,
        img: &Image,
        src_logical: Rect,
        dst: Rect,
        sampling: SamplingOptions,
        key: &str,
    ) {
        let raster = photo_raster_rect(self.photo_raster_scale(), src_logical);
        self.draw_image_recorded(img, Some(raster), Some(src_logical), dst, sampling, None, key);
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
        self.draw_image_recorded(img, src, src, dst, sampling, filter, key);
    }

    /// One image draw: `src_raster` is what Skia samples, `src_report` what the
    /// report records (they differ only for a bounded interactive photo decode).
    fn draw_image_recorded(
        &mut self,
        img: &Image,
        src_raster: Option<Rect>,
        src_report: Option<Rect>,
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
        self.scoped(|c| match src_raster {
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
            src: src_report.map(RectJson::from_skia),
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

// Interactive source-decode tests that live next to the draws they change.
//
// The interactive tier decodes the photo at a smaller raster. Layout, crop and
// the geometry report must not move: the same card must produce the same
// `Report` (which *is* the engine's geometry report) whether the draw sampled the
// full raster or a bounded one, and only the rectangle handed to Skia may differ.
#[cfg(test)]
mod photo_source_scale_tests {
    use super::*;
    use crate::draw::{draw_photo, Theme};
    use crate::hand::Crop;
    use skia_safe::{AlphaType, ColorType, ImageInfo};
    use std::cell::RefCell;

    fn theme() -> Theme {
        Theme {
            id: "paper".into(),
            bg: "#f6f3ec".into(),
            fg: "#151515".into(),
            sub: "#777".into(),
            line: "#ddd".into(),
            accent: "#c33".into(),
            paper: "#fff".into(),
            shadow: "#000".into(),
        }
    }

    fn flat_raster(w: i32, h: i32) -> Image {
        let mut surface = skia_safe::surfaces::raster_n32_premul((w, h)).unwrap();
        surface.canvas().clear(Color::from_argb(255, 30, 120, 200));
        surface.image_snapshot()
    }

    /// Draw the same photo box twice, once from the full raster and once from a
    /// half-size raster declared as the same logical photo, and return both draw
    /// reports (the engine's geometry report) plus the sampled rects.
    fn draw_both() -> (String, String, Rect, Rect) {
        // 400x300 photo, 4:3 crop of the whole frame, drawn into a 480x320 canvas.
        let crop = Crop {
            sx: 0.0,
            sy: 0.0,
            sw: 400.0,
            sh: 300.0,
            pw: 400.0,
            ph: 300.0,
        };
        let logical_src = Rect::from_xywh(crop.sx, crop.sy, crop.sw, crop.sh);
        let info = ImageInfo::new((480, 320), ColorType::RGBA8888, AlphaType::Opaque, None);

        let render = |photo: &Image, scale: f32| {
            let mut surface = skia_safe::surfaces::raster(&info, None, None).unwrap();
            surface.canvas().clear(Color::BLACK);
            let report = RefCell::new(Report::default());
            let sampled;
            {
                let mut ctx = Ctx2D::new(surface.canvas(), &report);
                ctx.set_photo_source(scale, (400.0, 300.0));
                sampled = ctx.photo_src_rect(logical_src);
                draw_photo(
                    &mut ctx,
                    photo,
                    &crop,
                    20.0,
                    18.0,
                    440.0,
                    280.0,
                    0.0,
                    0.0,
                    &theme(),
                    280.0,
                );
            }
            let json = serde_json::to_string(&*report.borrow()).unwrap();
            (json, sampled)
        };

        let full = flat_raster(400, 300);
        let half = flat_raster(200, 150);
        let (full_report, full_src) = render(&full, 1.0);
        let (scaled_report, scaled_src) = render(&half, 0.5);
        (full_report, scaled_report, full_src, scaled_src)
    }

    #[test]
    fn scaled_source_decode_keeps_the_geometry_report_identical() {
        let (full_report, scaled_report, full_src, scaled_src) = draw_both();
        // The report is the geometry report: photo box, layers, text slots and the
        // sampled `photo_src` entry all keep their logical numbers.
        assert_eq!(full_report, scaled_report);
        assert!(full_report.contains("\"key\":\"photo\""));
        // The full decode hands Skia the rect it always did...
        assert_eq!(full_src, Rect::from_xywh(0.0, 0.0, 400.0, 300.0));
        // ...while the bounded one maps it into the smaller raster, so the draw is
        // in bounds instead of reading past the image.
        assert_eq!(scaled_src, Rect::from_xywh(0.0, 0.0, 200.0, 150.0));
    }

    #[test]
    fn the_sampled_rect_is_inside_the_bounded_raster() {
        let crop = Crop {
            sx: 40.0,
            sy: 30.0,
            sw: 200.0,
            sh: 150.0,
            pw: 400.0,
            ph: 300.0,
        };
        let info = ImageInfo::new((480, 320), ColorType::RGBA8888, AlphaType::Opaque, None);
        let mut surface = skia_safe::surfaces::raster(&info, None, None).unwrap();
        let report = RefCell::new(Report::default());
        let mut ctx = Ctx2D::new(surface.canvas(), &report);
        ctx.set_photo_source(0.25, (400.0, 300.0));
        let mapped = ctx.photo_src_rect(Rect::from_xywh(crop.sx, crop.sy, crop.sw, crop.sh));
        // 100x75 raster: the logical window only fits once it is mapped.
        assert_eq!(mapped, Rect::from_xywh(10.0, 7.5, 50.0, 37.5));
        assert!(mapped.right() <= 100.0 && mapped.bottom() <= 75.0);
        // A ratio that is not a real shrink is ignored rather than applied.
        ctx.set_photo_source(1.5, (400.0, 300.0));
        assert_eq!(ctx.photo_raster_scale(), 0.25);
        ctx.set_photo_source(0.5, (0.0, 0.0));
        assert_eq!(ctx.photo_raster_scale(), 0.25);
    }

    #[test]
    fn the_photo_box_cover_math_reads_the_logical_size() {
        // `draw_photo_cover` derives its cover slice from the photo's size; with a
        // bounded raster that must still be 400x300, not 200x150.
        let mut ctx_canvas = skia_safe::surfaces::raster_n32_premul((8, 8)).unwrap();
        let report = RefCell::new(Report::default());
        let mut ctx = Ctx2D::new(ctx_canvas.canvas(), &report);
        assert_eq!(ctx.photo_logical(), None);
        ctx.set_photo_source(0.5, (400.0, 300.0));
        assert_eq!(ctx.photo_logical(), Some((400.0, 300.0)));
        assert_eq!(ctx.photo_raster_scale(), 0.5);
    }
}

