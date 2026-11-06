package com.framemark.native.workflow

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D1-3: the watermark overlay must use the engine's photo draw box, not the whole
 * bordered canvas. A card with a bottom border band used to draw the solid frame
 * and dashed zone one band lower than the rendered watermark.
 */
class NativeStageOverlayGeometryTest {

    private fun assertNear(expected: Float, actual: Float, what: String) {
        assertTrue(abs(expected - actual) <= 0.01f, "$what: expected $expected actual $actual")
    }

    @Test
    fun photo_frame_maps_the_engine_rect_and_excludes_the_bottom_border_band() {
        // 1200x900 canvas, photo occupies the top 1200x700 (200 px bottom band).
        val photoRect = ImageRect(x = 0f, y = 0f, w = 1200f, h = 700f)
        val photoFrame = photoFrameFor(
            boxW = 600f,
            boxH = 400f,
            bitmapW = 1200f,
            bitmapH = 900f,
            photoRect = photoRect,
        )
        // ContentScale.Fit: scale = min(600/1200, 400/900) = 4/9.
        val scale = 400f / 900f
        assertNear((600f - 1200f * scale) / 2f, photoFrame.left, "photo left")
        assertNear(0f, photoFrame.top, "photo top")
        assertNear(1200f * scale, photoFrame.width, "photo width")
        assertNear(700f * scale, photoFrame.height, "photo height")

        val spec = WatermarkSpec(
            path = "",
            aspect = 2.5f,
            anchor = 8,
            width = 0.25f,
            opacity = 0.8f,
        )
        val photoAspect = photoRect.w / photoRect.h
        val mark = watermarkRectPx(spec, photoAspect, photoFrame)
        val markBottom = mark[1] + mark[3]
        val frameBottom = photoFrame.top + photoFrame.height
        assertTrue(
            markBottom <= frameBottom + 0.01f,
            "watermark frame must stay inside the photo: mark bottom $markBottom, photo bottom $frameBottom",
        )
        // The same spec against the full canvas would fall into the bottom band.
        val canvasFrame = fitPhotoFrame(600f, 400f, 1200f, 900f)
        val oldMark = watermarkRectPx(spec, photoAspect, canvasFrame)
        assertTrue(oldMark[1] + oldMark[3] > frameBottom + 1f, "regression case must differ from the old frame")
    }

    @Test
    fun photo_frame_honours_a_top_left_offset() {
        val photoRect = ImageRect(x = 100f, y = 50f, w = 1000f, h = 600f)
        val photoFrame = photoFrameFor(
            boxW = 500f,
            boxH = 400f,
            bitmapW = 1200f,
            bitmapH = 900f,
            photoRect = photoRect,
        )
        val canvas = fitPhotoFrame(500f, 400f, 1200f, 900f)
        val scale = canvas.width / 1200f
        assertNear(canvas.left + 100f * scale, photoFrame.left, "offset left")
        assertNear(canvas.top + 50f * scale, photoFrame.top, "offset top")
        assertNear(1000f * scale, photoFrame.width, "offset width")
        assertNear(600f * scale, photoFrame.height, "offset height")
    }

    @Test
    fun no_engine_rect_keeps_the_historical_canvas_frame() {
        val canvas = fitPhotoFrame(600f, 400f, 1200f, 900f)
        val fallback = photoFrameFor(600f, 400f, 1200f, 900f, photoRect = null)
        assertEquals(canvas, fallback, "missing photoRect must not move the overlay")
    }
}
