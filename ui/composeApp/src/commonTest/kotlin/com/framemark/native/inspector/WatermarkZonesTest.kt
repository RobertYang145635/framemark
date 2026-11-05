package com.framemark.native.inspector

import com.framemark.native.workflow.WatermarkSpec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP-C §4.4: nine-cell zones, per-cell axis, max margin, width cap, legacy
 * centred offsets and the "only write after the user edits" rule.
 */
class WatermarkZonesTest {

    private fun spec(
        anchor: Int,
        width: Float = 0.25f,
        aspect: Float = 3f,
        dx: Float = 0f,
        dy: Float = 0f,
        photoAspect: Float = 1.5f,
    ) = WatermarkSpec(
        path = "mark.png",
        aspect = aspect,
        anchor = anchor,
        width = width,
        opacity = 0.8f,
        dx = dx,
        dy = dy,
        photoAspect = photoAspect,
    )

    @Test
    fun nineCellsExposeOnlyTheirOwnAxes() {
        // corners: both axes; edges: one axis; centre: none.
        val expected = mapOf(
            0 to (true to true),
            1 to (false to true),
            2 to (true to true),
            3 to (true to false),
            4 to (false to false),
            5 to (true to false),
            6 to (true to true),
            7 to (false to true),
            8 to (true to true),
        )
        for (anchor in 0..8) {
            val zone = WatermarkZones.zoneFor(anchor)
            val (horizontal, vertical) = expected.getValue(anchor)
            assertEquals(horizontal, zone.hasHorizontal, "anchor=$anchor horizontal axis")
            assertEquals(vertical, zone.hasVertical, "anchor=$anchor vertical axis")
            assertEquals(anchor / 3, zone.row)
            assertEquals(anchor % 3, zone.column)
            if (!horizontal) assertNull(zone.horizontalEdge)
            if (!vertical) assertNull(zone.verticalEdge)
        }
    }

    @Test
    fun widthCapIsFortySevenPercentOnTheSidesAndSixtyInTheMiddle() {
        for (anchor in listOf(0, 2, 3, 5, 6, 8)) {
            assertEquals(0.47f, WatermarkZones.widthCapFor(anchor), 1e-6f, "side column $anchor")
        }
        for (anchor in listOf(1, 4, 7)) {
            assertEquals(0.60f, WatermarkZones.widthCapFor(anchor), 1e-6f, "middle column $anchor")
        }
    }

    @Test
    fun theCentreCellIsPinned() {
        assertTrue(WatermarkZones.zoneFor(4).pinned)
        assertFalse(WatermarkZones.zoneFor(0).pinned)
        // The pane shows an explanation instead of impossible sliders.
        assertNull(WatermarkZones.edgeFor(4, vertical = true))
        assertNull(WatermarkZones.edgeFor(4, vertical = false))
    }

    @Test
    fun maxMarginIsTheDistanceUntilTheWatermarkTouchesTheZoneEdge() {
        // Side columns: the budget is half the photo width (0.5 - 0.25 = 0.25).
        assertEquals(25f, WatermarkZones.maxMarginPct(0, 0.25f, 1.5f, 3f, WatermarkEdge.Left), 1e-4f)
        assertEquals(25f, WatermarkZones.maxMarginPct(8, 0.25f, 1.5f, 3f, WatermarkEdge.Right), 1e-4f)
        // A 0.47-wide watermark leaves exactly the 3 % the report budgets for.
        assertEquals(3f, WatermarkZones.maxMarginPct(0, 0.47f, 1.5f, 3f, WatermarkEdge.Left), 1e-4f)
    }

    @Test
    fun extremePortrait1to3ShrinksTheVerticalRange() {
        // 1:3 photo (w/h = 1/3); a 3:1 mark at 25 % width is only 2.78 % tall.
        val photoAspect = 1f / 3f
        val topMax = WatermarkZones.maxMarginPct(1, 0.25f, photoAspect, 3f, WatermarkEdge.Top)
        // (0.5 - 0.25 * (1/3) / 3) / (1/3) * 100 = 141.67 %.
        assertEquals(141.67f, topMax, 0.05f)
        // The bottom row is the mirror image.
        val bottomMax = WatermarkZones.maxMarginPct(7, 0.25f, photoAspect, 3f, WatermarkEdge.Bottom)
        assertEquals(141.67f, bottomMax, 0.05f)
    }

    @Test
    fun extremeLandscape3to1ShrinksTheVerticalRange() {
        // 3:1 photo (w/h = 3); the same mark is 25 % tall now.
        val photoAspect = 3f
        val bottomMax = WatermarkZones.maxMarginPct(7, 0.25f, photoAspect, 3f, WatermarkEdge.Bottom)
        // (1 - 0.5 - 0.25) / 3 * 100 = 8.33 %.
        assertEquals(8.33f, bottomMax, 0.05f)
        // Middle column keeps its 60 % cap, so the horizontal range is bigger.
        val leftMax = WatermarkZones.maxMarginPct(3, 0.25f, photoAspect, 3f, WatermarkEdge.Left)
        assertEquals(25f, leftMax, 1e-4f)
    }

    @Test
    fun aWatermarkTallerThanItsZoneGetsNoRangeInsteadOfNaN() {
        // 3:1 photo, 60 % wide mark with a tall 1:3 asset → height 1.0.
        val max = WatermarkZones.maxMarginPct(1, 0.60f, 3f, 1f / 3f, WatermarkEdge.Top)
        assertTrue(max >= 0f)
        val moved = WatermarkZones.positionFor(spec(1, width = 0.6f, aspect = 1f / 3f), 3f, WatermarkEdge.Top, 3f)
        assertTrue(moved.first.isFinite() && moved.second.isFinite())
    }

    @Test
    fun everyMarginRoundTripsThroughThePosition() {
        for (anchor in 0..8) {
            val zone = WatermarkZones.zoneFor(anchor)
            val edges = listOfNotNull(zone.horizontalEdge, zone.verticalEdge)
            for (edge in edges) {
                val base = spec(anchor)
                val max = WatermarkZones.maxMarginPct(anchor, base.width, 1.5f, 3f, edge)
                for (target in listOf(0f, max / 2f, max)) {
                    val (dx, dy) = WatermarkZones.positionFor(base, 1.5f, edge, target)
                    val placed = base.copy(dx = dx, dy = dy)
                    val shown = WatermarkZones.marginPct(placed, 1.5f, edge)
                    assertEquals(target, shown, 0.05f, "anchor=$anchor edge=$edge target=$target")
                }
            }
        }
    }

    @Test
    fun aLegacyCentredOffsetIsTreatedAsZeroWithoutRewritingIt() {
        // Top-centre: the horizontal direction is fixed, but an old preset carries dx.
        val legacy = spec(anchor = 1, dx = 0.3f, dy = 0.01f)
        assertEquals(0f, WatermarkZones.normalizedOffsets(legacy).first)
        assertEquals(0.01f, WatermarkZones.normalizedOffsets(legacy).second, 1e-6f)
        // Display/calculation equals the same spec with dx = 0.
        val clean = legacy.copy(dx = 0f)
        assertEquals(
            WatermarkZones.marginPct(clean, 1.5f, WatermarkEdge.Top),
            WatermarkZones.marginPct(legacy, 1.5f, WatermarkEdge.Top),
            1e-4f,
        )
        // The note is available to the pane.
        assertTrue(legacyCentredOffset(legacy) != null)
        assertNull(legacyCentredOffset(clean))
        // The stored value itself is untouched until an edit happens.
        assertEquals(0.3f, legacy.dx)
    }

    @Test
    fun theFirstUserEditWritesTheNormalisedCentredOffset() {
        val legacy = spec(anchor = 1, dx = 0.3f)
        val (dx, dy) = WatermarkZones.positionFor(legacy, 1.5f, WatermarkEdge.Top, 8f)
        // Centred direction is written back as 0 (HANDOFF §3.8), vertical follows.
        assertEquals(0f, dx)
        assertTrue(dy != legacy.dy)
        val edited = legacy.copy(dx = dx, dy = dy)
        assertEquals(0f, WatermarkZones.normalizedOffsets(edited).first)
        assertEquals(8f, WatermarkZones.marginPct(edited, 1.5f, WatermarkEdge.Top), 0.05f)
    }

    @Test
    fun changingTheCellPullsAnOverwideMarkBackIntoTheColumnCap() {
        val wide = spec(anchor = 4, width = 0.6f)
        // Moving from the centre column to a side column must clamp to 47 %.
        val fromSide = wide.copy(anchor = 8)
        assertEquals(0.47f, WatermarkZones.widthFor(fromSide), 1e-6f)
        assertEquals(0.6f, WatermarkZones.widthFor(wide), 1e-6f)
    }

    @Test
    fun draggingStaysInsideTheCellZoneAndMatchesTheSliders() {
        for (anchor in 0..8) {
            if (WatermarkZones.zoneFor(anchor).pinned) continue
            val base = spec(anchor, photoAspect = 1.5f)
            val photoW = 600f
            val photoH = 400f
            val (dx, dy) = WatermarkZones.moveByPixels(
                spec = base,
                photoAspect = 1.5f,
                dxPx = 250f,
                dyPx = 250f,
                photoWidthPx = photoW,
                photoHeightPx = photoH,
            )
            val moved = base.copy(dx = dx, dy = dy)
            assertTrue(WatermarkZones.isInsideZone(moved, 1.5f), "anchor=$anchor must stay in its zone")

            // Same final position reached through a margin slider → same dx/dy.
            val zone = WatermarkZones.zoneFor(anchor)
            val box = moved.aabb(1.5f)
            val edge = zone.horizontalEdge ?: zone.verticalEdge ?: WatermarkEdge.Left
            val margin = WatermarkZones.marginPct(moved, 1.5f, edge)
            val viaSlider = WatermarkZones.positionFor(base, 1.5f, edge, margin)
            val expected = if (zone.horizontalEdge != null) viaSlider else WatermarkZones.positionFor(
                base, 1.5f, zone.verticalEdge ?: WatermarkEdge.Top, margin,
            )
            val settled = base.copy(dx = expected.first, dy = expected.second)
            assertEquals(
                WatermarkZones.marginPct(moved, 1.5f, edge),
                WatermarkZones.marginPct(settled, 1.5f, edge),
                0.1f,
            )
            assertTrue(box[0] >= 0f && box[1] >= 0f)
        }
    }

    @Test
    fun aDragOnAPinnedCellWritesNoOffset() {
        val base = spec(anchor = 4)
        val (dx, dy) = WatermarkZones.moveByPixels(base, 1.5f, 300f, 300f, 600f, 400f)
        assertEquals(0f, dx)
        assertEquals(0f, dy)
    }

    @Test
    fun modifiedCountReadsTheLayerNotTheCard() {
        assertNull(legacyCentredOffset(spec(8)))
        assertEquals(0, WatermarkZones.modifiedCount(spec(8)))
        assertEquals(1, WatermarkZones.modifiedCount(spec(8, width = 0.4f)))
        assertEquals(1, WatermarkZones.modifiedCount(spec(anchor = 7)))
        // A centred axis offset does not add a count: it is shown as 0. The anchor
        // itself is what changed here (1 ≠ the 8 default), so the count is 1.
        assertEquals(1, WatermarkZones.modifiedCount(spec(1, dx = 0.3f)))
        assertEquals(0, WatermarkZones.modifiedCount(null))
    }

    @Test
    fun marginDistanceUsesThePhotoWidthOnBothAxes() {
        // 4:3 photo, mark at top edge with dy = 0.05 normalised height.
        val photoAspect = 4f / 3f
        val placed = spec(anchor = 1, dy = 0f, photoAspect = photoAspect)
        val margin = WatermarkZones.marginPct(placed, photoAspect, WatermarkEdge.Top)
        // Base position is MARGIN = 0.05 of the height → 3.75 % of the width.
        assertEquals(3.75f, margin, 0.05f)
        assertTrue(abs(margin - 3.75f) < 0.05f)
    }
}
