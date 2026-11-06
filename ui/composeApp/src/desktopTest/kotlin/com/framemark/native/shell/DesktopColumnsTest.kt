package com.framemark.native.shell

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V06 desktop column geometry: pure arithmetic only, no Compose, no window.
 *
 * The contract these tests pin down:
 *  - the three-column floor is derived from the `FmDesktop` tokens, not a
 *    hard-coded 688/664 pair, and `compact` flips exactly at that floor;
 *  - a visible rail always sits inside its token range, a folded one occupies
 *    the collapsed-rail width, and the four widths plus both gaps add up to the
 *    content width — nothing overflows and nothing is negative;
 *  - the right inspector is folded *explicitly* (a rail is drawn, the top bar
 *    keeps its 「参数」 entry) instead of being pushed off the window.
 */
class DesktopColumnsTest {

    /** FmDesktop.libraryWidth / inspectorWidth: the shipped V2 default panel widths (296 / 336). */
    private val defaultLeft = 296f
    private val defaultRight = 336f
    private val epsilon = 0.01f

    private fun assertNear(expected: Float, actual: Float, message: String) {
        assertTrue(
            abs(expected - actual) <= epsilon,
            "$message: expected $expected, actual $actual",
        )
    }

    private fun columns(content: Float, leftCollapsed: Boolean = false, rightCollapsed: Boolean = false) =
        computeDesktopColumns(
            availableWidthDp = content,
            requestedLeft = defaultLeft,
            requestedRight = defaultRight,
            leftCollapsed = leftCollapsed,
            rightCollapsed = rightCollapsed,
        )

    @Test
    fun min_width_is_derived_from_tokens() {
        val expected = desktopLibraryMinWidth() + desktopInspectorMinWidth() +
            desktopStageMinWidth() + desktopColumnGapWidth() * 2f
        assertEquals(expected, desktopColumnMinWidth(), "three-column floor must come from FmDesktop")
        assertTrue(desktopColumnMinWidth() > desktopStageMinWidth(), "floor must exceed the stage minimum")
    }

    @Test
    fun compact_flips_at_the_derived_minimum() {
        val floor = desktopColumnMinWidth()
        assertFalse(columns(floor).compact, "content at the floor still shows three columns")
        assertTrue(columns(floor - 0.5f).compact, "content below the floor is compact")
        // Regression for the old 688 vs 664 pair: a 664..688 window used to be
        // judged "narrow" by the top bar while the columns still showed both rails.
        val nearFloor = columns(floor)
        assertTrue(nearFloor.showLeft, "left rail must show at the floor")
        assertTrue(nearFloor.showRight, "right rail must show at the floor")
    }

    @Test
    fun wide_content_widths_keep_both_rails_and_sum_exactly() {
        val gapTotal = desktopColumnGapWidth() * 2f
        for (content in listOf(1280f, 1256f, 960f, 700f, 688f)) {
            val c = columns(content)
            assertFalse(c.compact, "content $content is not compact")
            assertTrue(c.showLeft, "content $content keeps the library rail")
            assertTrue(c.showRight, "content $content keeps the inspector rail")
            assertTrue(c.left >= desktopLibraryMinWidth() && c.left <= desktopLibraryMaxWidth(),
                "left out of token range at $content: ${c.left}")
            assertTrue(c.right >= desktopInspectorMinWidth() && c.right <= desktopInspectorMaxWidth(),
                "right out of token range at $content: ${c.right}")
            assertTrue(c.stage >= desktopStageMinWidth() - epsilon, "stage below minimum at $content: ${c.stage}")
            assertNear(content, c.left + c.right + gapTotal + c.stage, "column sum at $content")
            assertTrue(c.left >= 0f && c.right >= 0f && c.stage >= 0f, "negative width at $content")
        }
    }

    @Test
    fun right_rail_folds_explicitly_when_the_stage_would_starve() {
        val content = 600f
        val c = columns(content)
        assertTrue(c.compact, "600 dp of content is below the three-column floor")
        assertTrue(c.showLeft, "library rail still fits at 600")
        assertFalse(c.showRight, "inspector must fold, not overflow the window")
        assertEquals(desktopCollapsedRailWidth(), c.right, "folded rail keeps its expand affordance")
        assertTrue(c.left >= desktopLibraryMinWidth() && c.left <= desktopLibraryMaxWidth())
        assertNear(
            content,
            c.left + c.right + desktopColumnGapWidth() * 2f + c.stage,
            "folded-right column sum",
        )
        assertTrue(c.stage >= 0f)
    }

    @Test
    fun collapsed_left_rail_lets_the_inspector_stay() {
        // V2 collapsed rail is 52 px (was 24), so the narrowest content width that
        // still holds a folded rail + inspector minimum + stage minimum moved up by
        // 28 dp. Derive it instead of pinning the old 520.
        val content = desktopCollapsedRailWidth() + desktopInspectorMinWidth() +
            desktopColumnGapWidth() * 2f + desktopStageMinWidth()
        val c = columns(content, leftCollapsed = true)
        assertFalse(c.showLeft)
        assertEquals(desktopCollapsedRailWidth(), c.left)
        assertTrue(c.showRight, "the inspector fits once the library is folded")
        assertTrue(c.right >= desktopInspectorMinWidth() && c.right <= desktopInspectorMaxWidth())
        assertNear(desktopStageMinWidth(), c.stage, "stage keeps its minimum")
        assertNear(
            content,
            c.left + c.right + desktopColumnGapWidth() * 2f + c.stage,
            "collapsed-left column sum",
        )
    }

    @Test
    fun collapsed_left_but_narrow_still_folds_the_inspector() {
        val content = 500f
        val c = columns(content, leftCollapsed = true)
        assertFalse(c.showLeft)
        assertFalse(c.showRight, "500 dp cannot hold a folded rail plus the inspector minimum")
        assertEquals(desktopCollapsedRailWidth(), c.left)
        assertEquals(desktopCollapsedRailWidth(), c.right)
        assertNear(
            content - desktopCollapsedRailWidth() * 2f - desktopColumnGapWidth() * 2f,
            c.stage,
            "stage takes everything the two folded rails leave",
        )
    }

    @Test
    fun folded_flags_are_respected() {
        val both = columns(1280f, leftCollapsed = true, rightCollapsed = true)
        assertFalse(both.showLeft)
        assertFalse(both.showRight)
        assertEquals(desktopCollapsedRailWidth(), both.left)
        assertEquals(desktopCollapsedRailWidth(), both.right)
        assertNear(
            1280f,
            both.left + both.right + desktopColumnGapWidth() * 2f + both.stage,
            "both folded",
        )

        val rightOnly = columns(1280f, rightCollapsed = true)
        assertTrue(rightOnly.showLeft)
        assertFalse(rightOnly.showRight)
        assertNear(
            1280f,
            rightOnly.left + rightOnly.right + desktopColumnGapWidth() * 2f + rightOnly.stage,
            "right collapsed by the user",
        )
    }

    @Test
    fun requested_widths_outside_the_tokens_are_clamped_not_overflowed() {
        val huge = computeDesktopColumns(1280f, 9_999f, 9_999f, false, false)
        assertEquals(desktopLibraryMaxWidth(), huge.left, "left request capped by the token ceiling")
        assertEquals(desktopInspectorMaxWidth(), huge.right, "right request capped by the token ceiling")

        val tiny = computeDesktopColumns(1280f, 1f, 1f, false, false)
        assertEquals(desktopLibraryMinWidth(), tiny.left)
        assertEquals(desktopInspectorMinWidth(), tiny.right)

        val notANumber = computeDesktopColumns(1280f, Float.NaN, Float.POSITIVE_INFINITY, false, false)
        assertEquals(desktopLibraryMinWidth(), notANumber.left, "non-finite request falls back to the minimum")
        assertEquals(desktopInspectorMinWidth(), notANumber.right)
    }

    @Test
    fun requests_are_capped_by_what_the_stage_can_spare() {
        val content = 700f
        val c = columns(content, leftCollapsed = false)
        assertTrue(c.showRight)
        assertTrue(c.left <= content - c.right - desktopColumnGapWidth() * 2f - desktopStageMinWidth() + epsilon,
            "left must leave the stage minimum")
        assertNear(desktopStageMinWidth(), c.stage, "700 dp spends every spare dp on the stage")
    }

    @Test
    fun degenerate_content_widths_never_go_negative() {
        val gapTotal = desktopColumnGapWidth() * 2f
        val railsOnly = desktopCollapsedRailWidth() * 2f + gapTotal
        for (content in listOf(0f, -10f, Float.NaN, 50f, 100f, gapTotal)) {
            val c = columns(content)
            assertTrue(c.compact, "content $content is compact")
            assertTrue(c.left >= 0f && c.right >= 0f && c.stage >= 0f, "negative width at $content")
            val usable = if (content.isFinite() && content > 0f) content else 0f
            if (usable < railsOnly) {
                assertEquals(0f, c.left, "no rail room at $content")
                assertEquals(0f, c.right, "no rail room at $content")
                assertEquals(usable, c.stage, "stage takes the whole box at $content")
            } else {
                assertNear(usable, c.left + c.right + gapTotal + c.stage, "sum at $content")
            }
        }

        // Narrower than two folded rails plus both gaps: no rails are drawn at
        // all, and the stage gets the whole box instead of a negative width.
        val noRails = computeDesktopColumns(50f, defaultLeft, defaultRight, false, false)
        assertFalse(noRails.showLeft)
        assertFalse(noRails.showRight)
        assertEquals(0f, noRails.left)
        assertEquals(0f, noRails.right)
        assertEquals(50f, noRails.stage)
    }

    @Test
    fun every_flag_combination_keeps_the_invariants() {
        val gapTotal = desktopColumnGapWidth() * 2f
        val railsOnly = desktopCollapsedRailWidth() * 2f + gapTotal
        var content = 0f
        while (content <= 1_600f) {
            for (leftCollapsed in listOf(false, true)) {
                for (rightCollapsed in listOf(false, true)) {
                    val c = columns(content, leftCollapsed, rightCollapsed)
                    val label = "content=$content leftCollapsed=$leftCollapsed rightCollapsed=$rightCollapsed"
                    assertTrue(c.left >= 0f && c.right >= 0f && c.stage >= 0f, "negative width: $label")
                    assertTrue(c.stage <= content + epsilon, "stage larger than the box: $label")
                    assertEquals(content < desktopColumnMinWidth(), c.compact, "compact flag: $label")
                    if (content < railsOnly) {
                        // Degenerate box: no rail is drawn, so only non-negativity
                        // and the stage taking the remainder are required.
                        continue
                    }
                    assertNear(content, c.left + c.right + gapTotal + c.stage, "column sum $label")
                    if (c.showLeft) {
                        assertTrue(
                            c.left >= desktopLibraryMinWidth() && c.left <= desktopLibraryMaxWidth(),
                            "left out of range: $label ${c.left}",
                        )
                    } else {
                        assertEquals(desktopCollapsedRailWidth(), c.left, "folded left: $label")
                    }
                    if (c.showRight) {
                        assertTrue(
                            c.right >= desktopInspectorMinWidth() && c.right <= desktopInspectorMaxWidth(),
                            "right out of range: $label ${c.right}",
                        )
                        assertTrue(
                            c.stage >= desktopStageMinWidth() - epsilon,
                            "stage starved while the inspector is open: $label ${c.stage}",
                        )
                    } else {
                        assertEquals(desktopCollapsedRailWidth(), c.right, "folded right: $label")
                    }
                }
            }
            content += 7f
        }
    }
}
