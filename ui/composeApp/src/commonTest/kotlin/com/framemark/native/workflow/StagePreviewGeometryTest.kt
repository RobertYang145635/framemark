package com.framemark.native.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WP-C §4.5: the stage geometry used to drag a watermark and the A-07 wait
 * phases. Both are pure so they can be checked without a window.
 */
class StagePreviewGeometryTest {

    @Test
    fun fittedPhotoFrameMatchesContentScaleFit() {
        // 600×400 box, 4:3 photo → height-limited: 533.33 wide, 400 tall, centred.
        val frame = fitPhotoFrame(boxW = 600f, boxH = 400f, bitmapW = 1200f, bitmapH = 900f)
        assertEquals(400f * 4f / 3f, frame.width, 1e-3f)
        assertEquals(400f, frame.height, 1e-3f)
        assertEquals((600f - 400f * 4f / 3f) / 2f, frame.left, 1e-3f)
        assertEquals(0f, frame.top, 1e-3f)
        assertEquals(4f / 3f, frame.aspect, 1e-4f)
    }

    @Test
    fun aTallPhotoIsPillarboxedNotStretched() {
        val frame = fitPhotoFrame(boxW = 400f, boxH = 600f, bitmapW = 1000f, bitmapH = 3000f)
        assertEquals(200f, frame.width, 1e-3f)
        assertEquals(600f, frame.height, 1e-3f)
        assertEquals(100f, frame.left, 1e-3f)
        assertEquals(0f, frame.top, 1e-3f)
    }

    @Test
    fun degenerateBoxesDoNotProduceNaN() {
        val frame = fitPhotoFrame(0f, 0f, 100f, 100f)
        assertEquals(0f, frame.width)
        assertEquals(0f, frame.height)
    }

    @Test
    fun watermarkRectProjectsIntoStagePixels() {
        val frame = fitPhotoFrame(boxW = 600f, boxH = 400f, bitmapW = 600f, bitmapH = 400f)
        // Bottom-right default: 25 % wide, anchored with the model's 5 % margin.
        val spec = WatermarkSpec(path = "m.png", aspect = 3f, anchor = 8, width = 0.25f)
        val rect = watermarkRectPx(spec, photoAspect = 1.5f, frame = frame)
        assertEquals(0.25f * 600f, rect[2], 1e-3f)
        // x + w is one margin away from the right edge.
        assertTrue(rect[0] + rect[2] <= 600f)
        assertTrue(rect[1] + rect[3] <= 400f)
    }

    @Test
    fun underTwoHundredMillisecondsNothingIsShown() {
        assertEquals(StageRenderPhase.Idle, StageRenderTiming.phaseAt(0))
        assertEquals(StageRenderPhase.Idle, StageRenderTiming.phaseAt(199))
        assertEquals("", StageRenderTiming.label(StageRenderPhase.Idle))
    }

    @Test
    fun betweenTwoHundredMillisecondsAndOneSecondASmallRenderingBadgeShows() {
        assertEquals(StageRenderPhase.Hint, StageRenderTiming.phaseAt(200))
        assertEquals(StageRenderPhase.Hint, StageRenderTiming.phaseAt(999))
        assertEquals("渲染中", StageRenderTiming.label(StageRenderPhase.Hint))
    }

    @Test
    fun pastOneSecondProgressShows() {
        assertEquals(StageRenderPhase.Progress, StageRenderTiming.phaseAt(1000))
        assertEquals(StageRenderPhase.Progress, StageRenderTiming.phaseAt(4200))
        assertEquals("渲染中 4s", StageRenderTiming.label(StageRenderPhase.Progress, 4200))
    }
}
