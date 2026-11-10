package com.framemark.native.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract of the text-row highlight's label chip ([placeSlotChip]) and of the
 * frame inflation ([inflateHighlightRect]). Pure functions only: no Compose, no
 * renderer, no resources.
 *
 * Stage numbers used throughout: a 300 × 400 px stage, an 80 × 18 px chip, a 3 px
 * gap and the 56 px bottom pill band (经典双行 / 按住看原图).
 */
class SlotChipPlacementTest {

    private val canvasW = 300f
    private val canvasH = 400f
    private val chipW = 80f
    private val chipH = 18f
    private val gap = 3f
    private val band = 56f
    private val bandTop = canvasH - band

    private fun place(rect: FitRectPx) = placeSlotChip(
        rect = rect,
        canvasW = canvasW,
        canvasH = canvasH,
        chipW = chipW,
        chipH = chipH,
        reservedBottom = band,
        gap = gap,
    )

    private fun assertInside(placement: SlotChipPlacement) {
        assertTrue(placement.x >= 0f, "chip starts left of the canvas (${placement.x})")
        assertTrue(placement.x + chipW <= canvasW + 0.01f, "chip ends right of the canvas (${placement.x + chipW})")
        assertTrue(placement.y >= 0f, "chip starts above the canvas (${placement.y})")
        assertTrue(placement.y + chipH <= canvasH + 0.01f, "chip ends below the canvas (${placement.y + chipH})")
    }

    // -- normal placement ------------------------------------------------------

    @Test
    fun a_row_in_the_middle_puts_the_chip_above_it_left_aligned() {
        val placement = place(FitRectPx(x = 80f, y = 200f, w = 120f, h = 20f))
        assertEquals(80f, placement.x, "left-aligned with the row")
        assertEquals(200f - gap - chipH, placement.y, "above the row, with the gap")
        assertFalse(placement.flipped, "the default slot has room, nothing flipped")
        assertInside(placement)
    }

    // -- top edge --------------------------------------------------------------

    @Test
    fun a_row_near_the_top_flips_the_chip_below_and_keeps_it_inside() {
        val placement = place(FitRectPx(x = 100f, y = 4f, w = 120f, h = 20f))
        assertTrue(placement.flipped, "no room above → flipped below")
        assertEquals(100f, placement.x)
        assertEquals(4f + 20f + gap, placement.y, "below the row, with the gap")
        assertInside(placement)
    }

    @Test
    fun a_row_exactly_one_gap_away_from_the_top_still_flips() {
        // rect.y == chipH + gap - 0.5 → strictly "no room", so it must flip.
        val y = chipH + gap - 0.5f
        val placement = place(FitRectPx(x = 40f, y = y, w = 100f, h = 20f))
        assertTrue(placement.flipped)
        assertEquals(y + 20f + gap, placement.y)
    }

    // -- horizontal clamp ------------------------------------------------------

    @Test
    fun a_row_at_the_left_edge_clamps_the_chip_to_zero() {
        assertEquals(0f, place(FitRectPx(x = 0f, y = 200f, w = 120f, h = 20f)).x)
        // The inflated frame is already clamped, but a caller may hand in a negative
        // x (a zoomed photo); the chip may never start left of the canvas.
        val negative = place(FitRectPx(x = -12f, y = 200f, w = 120f, h = 20f))
        assertEquals(0f, negative.x)
        assertInside(negative)
    }

    @Test
    fun a_row_at_the_right_edge_slides_the_chip_back_inside() {
        val placement = place(FitRectPx(x = 250f, y = 200f, w = 40f, h = 20f))
        assertEquals(canvasW - chipW, placement.x, "clamped to canvasW - chipW")
        assertInside(placement)
    }

    @Test
    fun a_chip_wider_than_the_canvas_stops_at_zero() {
        val placement = placeSlotChip(
            rect = FitRectPx(x = 20f, y = 200f, w = 40f, h = 20f),
            canvasW = 60f,
            canvasH = canvasH,
            chipW = 80f,
            chipH = chipH,
            reservedBottom = band,
            gap = gap,
        )
        assertEquals(0f, placement.x)
    }

    // -- bottom reserved band --------------------------------------------------

    @Test
    fun a_row_inside_the_pill_band_puts_the_chip_above_and_out_of_the_band() {
        val placement = place(FitRectPx(x = 60f, y = 350f, w = 120f, h = 20f))
        assertFalse(placement.flipped, "a band row is served from above, not from below")
        assertTrue(
            placement.y + chipH <= bandTop + 0.01f,
            "chip (${placement.y}..${placement.y + chipH}) entered the pill band (top $bandTop)",
        )
        assertInside(placement)
    }

    @Test
    fun a_row_deep_inside_the_band_is_pushed_up_to_the_band_edge() {
        val placement = place(FitRectPx(x = 60f, y = 380f, w = 120f, h = 15f))
        assertEquals(bandTop - chipH, placement.y, "flush on the band's top edge")
        assertTrue(placement.y + chipH <= bandTop + 0.01f)
    }

    @Test
    fun a_flip_that_would_land_in_the_band_goes_above_instead() {
        // Short stage: the band starts at 44, "below" would be 27..45 → inside it.
        val placement = placeSlotChip(
            rect = FitRectPx(x = 60f, y = 4f, w = 120f, h = 20f),
            canvasW = canvasW,
            canvasH = 100f,
            chipW = chipW,
            chipH = chipH,
            reservedBottom = band,
            gap = gap,
        )
        assertFalse(placement.flipped, "flipping into the pills is not allowed")
        assertTrue(placement.y + chipH <= (100f - band) + 0.01f, "chip entered the band")
        assertTrue(placement.y >= 0f)
    }

    @Test
    fun no_row_position_in_a_normal_stage_ever_draws_over_the_pill_band() {
        var y = 0f
        while (y <= canvasH) {
            val placement = place(FitRectPx(x = 40f, y = y, w = 120f, h = 18f))
            assertTrue(
                placement.y + chipH <= bandTop + 0.01f,
                "row y=$y → chip y=${placement.y} overlaps the pill band (top $bandTop)",
            )
            assertInside(placement)
            y += 7f
        }
    }

    // -- degenerate input ------------------------------------------------------

    @Test
    fun broken_sizes_never_throw() {
        val zeroCanvas = placeSlotChip(
            rect = FitRectPx(10f, 10f, 10f, 10f),
            canvasW = 0f,
            canvasH = 0f,
            chipW = 80f,
            chipH = 18f,
            reservedBottom = band,
            gap = gap,
        )
        assertEquals(0f, zeroCanvas.x)
        assertEquals(0f, zeroCanvas.y)

        val nonFinite = placeSlotChip(
            rect = FitRectPx(Float.NaN, Float.POSITIVE_INFINITY, 10f, 10f),
            canvasW = canvasW,
            canvasH = canvasH,
            chipW = Float.NaN,
            chipH = Float.NEGATIVE_INFINITY,
            reservedBottom = Float.NaN,
            gap = Float.NaN,
        )
        assertInside(nonFinite)
    }

    @Test
    fun a_reserved_band_larger_than_the_canvas_is_clamped_not_negative() {
        val placement = placeSlotChip(
            rect = FitRectPx(x = 10f, y = 10f, w = 40f, h = 10f),
            canvasW = canvasW,
            canvasH = 60f,
            chipW = chipW,
            chipH = chipH,
            reservedBottom = 500f,
            gap = gap,
        )
        assertTrue(placement.y >= 0f)
        assertTrue(placement.y + chipH <= 60f + 0.01f)
    }

    // -- frame inflation -------------------------------------------------------

    @Test
    fun the_frame_is_inflated_on_every_side_before_it_is_drawn() {
        val frame = inflateHighlightRect(
            rect = FitRectPx(x = 100f, y = 100f, w = 50f, h = 20f),
            inflate = 5f,
            canvasW = canvasW,
            canvasH = canvasH,
        )
        assertEquals(95f, frame.x)
        assertEquals(95f, frame.y)
        assertEquals(60f, frame.w)
        assertEquals(30f, frame.h)
    }

    @Test
    fun the_inflated_frame_is_clamped_to_the_canvas_at_every_edge() {
        val topLeft = inflateHighlightRect(FitRectPx(0f, 0f, 50f, 20f), 2.5f, canvasW, canvasH)
        assertEquals(0f, topLeft.x)
        assertEquals(0f, topLeft.y)
        assertEquals(52.5f, topLeft.w)
        assertEquals(22.5f, topLeft.h)

        val bottomRight = inflateHighlightRect(FitRectPx(290f, 380f, 10f, 20f), 2.5f, canvasW, canvasH)
        assertEquals(287.5f, bottomRight.x)
        assertEquals(377.5f, bottomRight.y)
        assertEquals(300f - 287.5f, bottomRight.w)
        assertEquals(400f - 377.5f, bottomRight.h)
    }

    @Test
    fun a_row_outside_the_canvas_has_no_frame_to_draw() {
        val outside = inflateHighlightRect(FitRectPx(400f, 10f, 50f, 20f), 2.5f, canvasW, canvasH)
        // The caller draws only when both edges are positive, so a row that fell outside
        // the canvas must collapse instead of turning into a full-height sliver.
        assertTrue(outside.w <= 0f, "a row right of the canvas produced a ${outside.w} px frame")
        assertTrue(outside.w <= 0f || outside.h <= 0f)
    }

    @Test
    fun a_row_away_from_the_edges_is_inflated_by_exactly_the_inflate_amount() {
        val frame = inflateHighlightRect(FitRectPx(150f, 200f, 40f, 16f), 2.5f, canvasW, canvasH)
        assertEquals(147.5f, frame.x)
        assertEquals(197.5f, frame.y)
        assertEquals(45f, frame.w)
        assertEquals(21f, frame.h)
    }
}
