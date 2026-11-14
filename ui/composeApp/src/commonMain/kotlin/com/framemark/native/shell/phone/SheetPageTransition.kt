package com.framemark.native.shell.phone

import com.framemark.native.theme.FmMotionRules

/**
 * The sheet's page transition — a **pager**, not a crossfade.
 *
 * The five tool cells (边框 / 样式 / 文字 / Logo / 水印) sit in one row, so moving
 * between them has a natural left/right order. The old transition faded the outgoing
 * page out while the incoming one faded in *on top of it*; two blocks of text at 50 %
 * alpha in the same rectangle read as mush, and the small vertical rise added a second
 * motion nobody asked for.
 *
 * The replacement is the gesture the tab row already implies: both pages travel
 * **together, one full width, in the same direction**, so mid-flight they sit side by
 * side like two adjacent pages of a deck — never stacked, never blended.
 *
 * This file is the pure half: given the index the sheet showed and the index it shows
 * now, which way does the pair travel and should it move at all. It has no Compose
 * dependency so the rule is unit-tested without a composition; the animation itself
 * lives in [PhoneSheetPagePager], and the duration/curve are the tokens
 * `FmDuration.pageSlide` + `FmEasing.standard`.
 */
enum class SheetPageDirection {
    /** The selected cell moved **left** in the bar: the pair travels left, the new page arrives from the left. */
    Left,

    /** The selected cell moved **right**: the pair travels right, the new page arrives from the right. */
    Right,

    /** No order to follow (same page, first composition, unknown index): do not travel. */
    None,
}

/**
 * What the page slot should do for one target tool.
 *
 * [animate] is the single switch the pager reads: when it is false the slot swaps
 * content with no transition at all (reduced motion, first composition, re-tap of the
 * current cell). [direction] is still reported for an ordered move at scale 0 — the
 * caller just does not travel — so a test can tell "which way it would have gone"
 * from "did it move".
 */
data class SheetPageTransition(
    val direction: SheetPageDirection,
    val animate: Boolean,
)

object SheetPageTransitionRules {

    /**
     * The direction rule, in one place.
     *
     * [previousIndex] / [newIndex] are positions in the **visible tool bar**
     * (`PhoneSheetHost`'s `tools`), not enum ordinals: the bar drops the Logo cell for a
     * card without a Logo capability, so the ordinal would report a move the user never
     * made. `null` means "this is the first composition" — there is no previous page and
     * therefore nothing to travel from.
     *
     *  - first composition (`null`) → [SheetPageDirection.None], no animation;
     *  - same index → `None`, no animation (re-tapping the current cell is a no-op, not
     *    a 220 ms flicker of the page it is already showing);
     *  - either index unknown (`< 0`, e.g. a tool the bar does not list) → `None`, no
     *    animation: without an order there is no honest direction, and a made-up one
     *    would send the page the wrong way;
     *  - otherwise → `Right` when the cell moved right, `Left` when it moved left, with
     *    `animate` following the motion scale (0 ⇒ instant swap).
     */
    fun transition(
        previousIndex: Int?,
        newIndex: Int,
        motionScale: Float,
    ): SheetPageTransition {
        val ordered = previousIndex != null && previousIndex >= 0 && newIndex >= 0
        if (!ordered || previousIndex == newIndex) {
            return SheetPageTransition(SheetPageDirection.None, animate = false)
        }
        return SheetPageTransition(
            direction = if (newIndex > previousIndex) SheetPageDirection.Right else SheetPageDirection.Left,
            animate = FmMotionRules.motionEnabled(motionScale),
        )
    }
}
