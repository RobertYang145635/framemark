package com.framemark.native.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Geometry contract for the V06 text-row highlight. Pure functions only: no
 * Compose, no renderer, no resources.
 */
class SlotHighlightTest {

    private fun slot(
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        located: Boolean = true,
        key: String? = "b0/g0",
    ) = TextSlot(
        id = "slot",
        text = "row",
        x = x,
        y = y,
        w = w,
        h = h,
        fontSize = 32.0,
        baseline = null,
        lines = 1,
        key = key,
        stable = key != null,
        located = located,
    )

    private fun assertRect(expected: FitRectPx, actual: FitRectPx) {
        assertEquals(expected.x, actual.x, "x")
        assertEquals(expected.y, actual.y, "y")
        assertEquals(expected.w, actual.w, "w")
        assertEquals(expected.h, actual.h, "h")
    }

    // -- fitImageRect ----------------------------------------------------------

    @Test
    fun squareBitmapIsCentredInWideBox() {
        // scale = min(300/100, 100/100) = 1 → the image is the full box height and
        // the two horizontal bands are 100 px each.
        assertRect(FitRectPx(x = 100f, y = 0f, w = 100f, h = 100f), fitImageRect(100, 100, 300f, 100f))
    }

    @Test
    fun squareBitmapIsCentredInTallBox() {
        // scale = min(100/100, 300/100) = 1 → vertical letterbox bands of 100 px.
        assertRect(FitRectPx(x = 0f, y = 100f, w = 100f, h = 100f), fitImageRect(100, 100, 100f, 300f))
    }

    @Test
    fun nonSquareBitmapScalesByTheShorterAxis() {
        // 200×100 (2:1) in a 100×100 box → scale 0.5 → 100×50, centred vertically.
        assertRect(FitRectPx(x = 0f, y = 25f, w = 100f, h = 50f), fitImageRect(200, 100, 100f, 100f))
    }

    @Test
    fun nonSquareBitmapLeavesHorizontalBandsWhenBoxIsWider() {
        // 100×200 (1:2) in a 200×200 box → scale 1 → 100×200, centred horizontally.
        assertRect(FitRectPx(x = 50f, y = 0f, w = 100f, h = 200f), fitImageRect(100, 200, 200f, 200f))
    }

    @Test
    fun zeroOrNegativeBitmapReturnsAnEmptyRect() {
        assertRect(FitRectPx(0f, 0f, 0f, 0f), fitImageRect(0, 100, 300f, 100f))
        assertRect(FitRectPx(0f, 0f, 0f, 0f), fitImageRect(100, 0, 300f, 100f))
        assertRect(FitRectPx(0f, 0f, 0f, 0f), fitImageRect(-10, 100, 300f, 100f))
    }

    @Test
    fun zeroOrInvalidBoxReturnsAnEmptyRect() {
        assertRect(FitRectPx(0f, 0f, 0f, 0f), fitImageRect(100, 100, 0f, 100f))
        assertRect(FitRectPx(0f, 0f, 0f, 0f), fitImageRect(100, 100, 300f, 0f))
        assertRect(FitRectPx(0f, 0f, 0f, 0f), fitImageRect(100, 100, -5f, 100f))
        assertRect(FitRectPx(0f, 0f, 0f, 0f), fitImageRect(100, 100, Float.NaN, 100f))
        assertRect(FitRectPx(0f, 0f, 0f, 0f), fitImageRect(100, 100, 300f, Float.POSITIVE_INFINITY))
    }

    // -- slotHighlightRect -----------------------------------------------------

    @Test
    fun mapsSlotToTheCentredFitRect() {
        // bitmap 100×100 in a 300×100 box: origin (100, 0), scale 1 → the slot
        // rectangle is shifted by the letterbox band only.
        val rect = slotHighlightRect(slot(x = 10.0, y = 20.0, w = 30.0, h = 40.0), 100, 100, 300f, 100f)
        assertNotNull(rect)
        assertRect(FitRectPx(x = 110f, y = 20f, w = 30f, h = 40f), rect)
    }

    @Test
    fun mapsSlotWithNonUnitScale() {
        // bitmap 200×100 in a 100×100 box: origin (0, 25), scale 0.5.
        val rect = slotHighlightRect(slot(x = 40.0, y = 10.0, w = 20.0, h = 30.0), 200, 100, 100f, 100f)
        assertNotNull(rect)
        assertRect(FitRectPx(x = 20f, y = 30f, w = 10f, h = 15f), rect)
    }

    @Test
    fun unlocatedSlotHasNoRectangle() {
        assertNull(slotHighlightRect(slot(10.0, 20.0, 30.0, 40.0, located = false), 100, 100, 300f, 100f))
    }

    @Test
    fun nonFiniteSlotGeometryHasNoRectangle() {
        assertNull(slotHighlightRect(slot(Double.NaN, 20.0, 30.0, 40.0), 100, 100, 300f, 100f))
        assertNull(slotHighlightRect(slot(10.0, Double.POSITIVE_INFINITY, 30.0, 40.0), 100, 100, 300f, 100f))
        assertNull(slotHighlightRect(slot(10.0, 20.0, Double.NaN, 40.0), 100, 100, 300f, 100f))
        assertNull(slotHighlightRect(slot(10.0, 20.0, 30.0, Double.NaN), 100, 100, 300f, 100f))
        // Finite double that overflows Float becomes Infinity after conversion.
        assertNull(slotHighlightRect(slot(1e300, 20.0, 30.0, 40.0), 100, 100, 300f, 100f))
    }

    @Test
    fun zeroOrNegativeSlotSizeHasNoRectangle() {
        assertNull(slotHighlightRect(slot(10.0, 20.0, 0.0, 40.0), 100, 100, 300f, 100f))
        assertNull(slotHighlightRect(slot(10.0, 20.0, 30.0, 0.0), 100, 100, 300f, 100f))
        assertNull(slotHighlightRect(slot(10.0, 20.0, -30.0, 40.0), 100, 100, 300f, 100f))
    }

    @Test
    fun degenerateBitmapOrBoxHasNoRectangle() {
        assertNull(slotHighlightRect(slot(10.0, 20.0, 30.0, 40.0), 0, 100, 300f, 100f))
        assertNull(slotHighlightRect(slot(10.0, 20.0, 30.0, 40.0), 100, 100, 0f, 100f))
        assertNull(slotHighlightRect(slot(10.0, 20.0, 30.0, 40.0), 100, 100, 300f, Float.NaN))
    }

    @Test
    fun mappedRectangleStaysInsideTheFitArea() {
        // A row reported at the far corner must map inside the drawn image, not
        // into the letterbox band.
        val rect = slotHighlightRect(slot(x = 70.0, y = 60.0, w = 30.0, h = 40.0), 100, 100, 300f, 100f)
        assertNotNull(rect)
        assertTrue(rect.x >= 100f && rect.x + rect.w <= 200f, "x range: ${rect.x}..${rect.w}")
        assertTrue(rect.y >= 0f && rect.y + rect.h <= 100f, "y range: ${rect.y}..${rect.h}")
    }
}
