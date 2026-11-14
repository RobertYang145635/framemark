package com.framemark.native.shell.phone

import com.framemark.native.shell.EditorTool
import com.framemark.native.theme.FmDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The page-slide direction rule — the pure half of motion spec §3.1 row 2.
 *
 * What this pins, and why each one is a real user-visible decision rather than a
 * restatement of the code:
 *
 *  - the pair travels **towards** the cell the user tapped, so the bar's left/right
 *    order and the page's left/right movement agree;
 *  - a re-tap of the current cell, the first composition and reduced motion never
 *    animate — those are the three ways this transition must be able to *not* exist;
 *  - an index the bar does not have is "no order", not "assume right", because a
 *    guessed direction sends the page the wrong way.
 *
 * The animation itself (both pages present, adjacent, clipped) is verified on rendered
 * frames in `desktopTest/…/SheetPageSlideRenderTest.kt`; a unit test cannot see it.
 */
class SheetPageTransitionTest {

    /** The five-cell bar in its production order (`phoneEditorTools`). */
    private val bar = listOf(
        EditorTool.Border,
        EditorTool.Params,
        EditorTool.Text,
        EditorTool.Logo,
        EditorTool.Watermark,
    )

    private fun move(from: Int, to: Int, scale: Float = 1f) = SheetPageTransitionRules.transition(
        previousIndex = from,
        newIndex = to,
        motionScale = scale,
    )

    // ---- direction ----------------------------------------------------------

    @Test
    fun movingToACellOnTheRightTravelsRight() {
        val step = move(from = 0, to = 1)
        assertEquals(SheetPageDirection.Right, step.direction)
        assertTrue(step.animate)
        // 边框 → 调整, the move the old frame strip captured.
        assertEquals(EditorTool.Params, bar[1])
    }

    @Test
    fun movingToACellOnTheLeftTravelsLeft() {
        val step = move(from = 3, to = 2)
        assertEquals(SheetPageDirection.Left, step.direction)
        assertTrue(step.animate)
    }

    @Test
    fun aLongJumpKeepsTheDirectionOfTheMoveNotTheDistance() {
        // Logo → 边框 is three cells back: it is still one movement, left, and the
        // duration is the same 220 ms — there is no "distance scaling" here because the
        // two pages are always exactly one width apart (see the pager's doc).
        assertEquals(SheetPageDirection.Left, move(from = 3, to = 0).direction)
        assertEquals(SheetPageDirection.Right, move(from = 0, to = 4).direction)
    }

    @Test
    fun everyAdjacentStepInTheRealBarHasADirection() {
        // Guards the production list rather than a hand-written one: if a future bar
        // reorders the cells, this walks whatever it is now and demands the rule agree.
        val expected = bar.indices.zipWithNext().map { (from, to) -> move(from, to).direction }
        assertEquals(List(bar.size - 1) { SheetPageDirection.Right }, expected)
        val backwards = bar.indices.reversed().zipWithNext().map { (from, to) -> move(from, to).direction }
        assertEquals(List(bar.size - 1) { SheetPageDirection.Left }, backwards)
    }

    // ---- the three "do not animate" paths -----------------------------------

    @Test
    fun tappingTheCellThatIsAlreadySelectedIsANoOp() {
        for (index in bar.indices) {
            val step = move(from = index, to = index)
            assertEquals(SheetPageDirection.None, step.direction, "index $index must not travel")
            assertFalse(step.animate, "index $index must not animate")
        }
    }

    @Test
    fun theFirstCompositionNeverAnimates() {
        // There is no previous page to travel from: the sheet opens on its page.
        val step = SheetPageTransitionRules.transition(
            previousIndex = null,
            newIndex = 2,
            motionScale = 1f,
        )
        assertEquals(SheetPageDirection.None, step.direction)
        assertFalse(step.animate)
    }

    @Test
    fun reducedMotionIsAnInstantSwap() {
        // The direction is still reported (it is information), but nothing moves.
        val step = move(from = 1, to = 3, scale = 0f)
        assertEquals(SheetPageDirection.Right, step.direction)
        assertFalse(step.animate)
    }

    @Test
    fun aNegativeScaleIsTreatedAsNoMotion() {
        assertFalse(move(from = 1, to = 3, scale = -1f).animate)
    }

    @Test
    fun aHalfScaleStillAnimates() {
        // 0.5 is a taste value, not "off": the pager scales the duration instead.
        assertTrue(move(from = 1, to = 3, scale = 0.5f).animate)
    }

    @Test
    fun anIndexTheBarDoesNotListHasNoDirection() {
        // `tools.indexOf(tool)` is -1 for a tool the current card's bar dropped (Logo on
        // a card without the capability). Assuming a direction there would slide the
        // page the wrong way, so the slot swaps instead.
        val step = move(from = 1, to = -1)
        assertEquals(SheetPageDirection.None, step.direction)
        assertFalse(step.animate)

        val other = move(from = -1, to = 1)
        assertEquals(SheetPageDirection.None, other.direction)
        assertFalse(other.animate)
    }

    @Test
    fun twoUnknownIndicesAreStillANoOp() {
        val step = move(from = -1, to = -1)
        assertEquals(SheetPageDirection.None, step.direction)
        assertFalse(step.animate)
    }

    // ---- the token the pager travels on ------------------------------------

    @Test
    fun theSlideIsLongerThanAContentCrossfadeAndShorterThanASheetSettle() {
        // A full-width travel needs more time than a 200 ms opacity change to read as
        // movement, and it must never compete with the 320 ms sheet snap.
        assertTrue(
            FmDuration.pageSlide > FmDuration.standard,
            "pageSlide (${FmDuration.pageSlide}) must be longer than standard (${FmDuration.standard})",
        )
        assertTrue(
            FmDuration.pageSlide < FmDuration.emphasized,
            "pageSlide (${FmDuration.pageSlide}) must be shorter than emphasized (${FmDuration.emphasized})",
        )
        assertEquals(220, FmDuration.pageSlide)
    }
}
