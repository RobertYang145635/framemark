package com.framemark.native.inspector

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Geometry shared by the slider painter and the drag bubble (motion batch 2,
 * 2026-11-14).
 *
 * The motion spec's drag-safety rule for the bubble is "position is a direct read, only
 * opacity animates". That only holds if the bubble's x is the **same function** the
 * thumb is painted with — otherwise a second copy of the maths would drift by a pixel
 * and the bubble would appear to lag the finger. These tests pin the function, not a
 * pixel: the ends, the middle, the clamp and the degenerate track.
 */
class SliderBubbleGeometryTest {

    private val thumb = 24f

    @Test
    fun theThumbKeepsItsRadiusOfPaddingAtBothEnds() {
        assertEquals(thumb / 2f, sliderThumbCenterPx(trackWidthPx = 300f, thumbSizePx = thumb, fraction = 0f))
        assertEquals(
            300f - thumb / 2f,
            sliderThumbCenterPx(trackWidthPx = 300f, thumbSizePx = thumb, fraction = 1f),
        )
    }

    @Test
    fun theMiddleOfTheTrackIsTheMiddleOfTheThumbTravel() {
        // radius + (w - 2r) * 0.5 == w / 2 for every width, which is why the painter and
        // the bubble can share this one function.
        assertEquals(100f, sliderThumbCenterPx(trackWidthPx = 200f, thumbSizePx = 40f, fraction = 0.5f))
        assertEquals(187f, sliderThumbCenterPx(trackWidthPx = 374f, thumbSizePx = 24f, fraction = 0.5f))
    }

    @Test
    fun anOutOfRangeFractionIsClampedToTheThumbTravel() {
        assertEquals(
            sliderThumbCenterPx(trackWidthPx = 300f, thumbSizePx = thumb, fraction = 1f),
            sliderThumbCenterPx(trackWidthPx = 300f, thumbSizePx = thumb, fraction = 2.5f),
        )
        assertEquals(
            sliderThumbCenterPx(trackWidthPx = 300f, thumbSizePx = thumb, fraction = 0f),
            sliderThumbCenterPx(trackWidthPx = 300f, thumbSizePx = thumb, fraction = -1f),
        )
    }

    @Test
    fun theBubbleIsCentredOnTheThumbWhenThereIsRoom() {
        val track = 374f
        val bubble = 48f
        for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
            val centre = sliderBubbleLeftPx(track, thumb, bubble, fraction) + bubble / 2f
            val thumbCentre = sliderThumbCenterPx(track, thumb, fraction)
            assertTrue(
                abs(centre - thumbCentre) < 0.001f,
                "bubble centre $centre must sit on the thumb $thumbCentre at $fraction",
            )
        }
    }

    @Test
    fun theBubbleStaysInsideTheRowAtBothEnds() {
        val track = 374f
        val bubble = 48f
        assertEquals(0f, sliderBubbleLeftPx(track, thumb, bubble, 0f))
        assertEquals(track - bubble, sliderBubbleLeftPx(track, thumb, bubble, 1f))
        // Every in-range value stays inside; a value outside the range is clamped too.
        for (fraction in listOf(-1f, 0f, 0.1f, 0.9f, 1f, 2f)) {
            val left = sliderBubbleLeftPx(track, thumb, bubble, fraction)
            assertTrue(left >= 0f && left <= track - bubble, "left $left escaped the row at $fraction")
        }
    }

    @Test
    fun aBubbleWiderThanTheTrackPinsToTheLeftEdge() {
        // A very long value string on a narrow row: the bubble cannot be centred, so it
        // starts at 0 instead of hanging off either side.
        assertEquals(0f, sliderBubbleLeftPx(trackWidthPx = 40f, thumbSizePx = thumb, bubbleWidthPx = 80f, fraction = 0.5f))
    }

    @Test
    fun aZeroWidthTrackDoesNotDivideByZero() {
        // First composition measures 0 px; the bubble must not produce NaN.
        val left = sliderBubbleLeftPx(trackWidthPx = 0f, thumbSizePx = thumb, bubbleWidthPx = 48f, fraction = 0.5f)
        assertTrue(left.isFinite(), "left must be finite, was $left")
        assertEquals(0f, left)
    }
}
