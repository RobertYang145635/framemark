package com.framemark.native.shell.phone

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
import com.framemark.native.shell.EditorTool
import com.framemark.native.theme.FmDuration
import com.framemark.native.theme.FmEasing
import com.framemark.native.theme.FmMotionRules
import com.framemark.native.theme.LocalFmMotionScale

/**
 * The parameter area's page slot: one page at a time, swapped with a **full-width
 * horizontal slide** in the direction the tool bar moved ([SheetPageTransitionRules]).
 *
 * ## Why a slide and not a fade
 *
 * The sheet is a deck of pages under one tab row, so the cheapest honest feedback for
 * "you moved one cell to the right" is the page travelling one cell to the left. The
 * previous enter+exit crossfade kept both pages in the same rectangle and blended them:
 * text over text at partial alpha is unreadable, and the rise on top of it made two
 * simultaneous motions out of one tap.
 *
 * ## Why both pages travel, with one spec
 *
 * [slideInHorizontally] and [slideOutHorizontally] share `spec`, so at progress `p` the
 * outgoing page occupies `[-w·p, w·(1-p)]` and the incoming one `[w·(1-p), w·(2-p)]`:
 * they **abut** at `w·(1-p)` at every frame. They never overlap and never leave a gap —
 * that is the whole point of using one duration for both, and the reason a different
 * exit duration (the old 180 ms vs 200 ms) is not allowed here.
 *
 * ## What is not animated
 *
 * Only the page's own translation. The sheet height, the grabber, the header, the tab
 * row and its ink bar are outside this slot and do not move; the page content itself
 * gets no rise, no scale and no stagger. `clipToBounds` makes the parameter area a hard
 * boundary, so a page in flight cannot paint over the header above it or the tool bar
 * below it (and a page taller than the area is cut, not spilled).
 *
 * ## Reduced motion
 *
 * At `LocalFmMotionScale == 0`, `transition` returns `animate = false` and the slot
 * becomes a plain swap: `EnterTransition.None togetherWith ExitTransition.None`.
 */
@Composable
internal fun PhoneSheetPagePager(
    tool: EditorTool,
    tools: List<EditorTool>,
    modifier: Modifier = Modifier,
    content: @Composable (EditorTool) -> Unit,
) {
    val motionScale = LocalFmMotionScale.current
    val durationMs = FmMotionRules.durationMs(FmDuration.pageSlide, motionScale)
    AnimatedContent(
        targetState = tool,
        modifier = modifier.clipToBounds(),
        transitionSpec = {
            val rule = SheetPageTransitionRules.transition(
                previousIndex = tools.indexOf(initialState),
                newIndex = tools.indexOf(targetState),
                motionScale = motionScale,
            )
            pageContentTransform(rule, durationMs)
        },
        label = "fm.sheet.page",
        content = { targetTool -> content(targetTool) },
    )
}

/**
 * The in/out pair for one move, factored out of the `transitionSpec` lambda so the
 * "same spec, opposite ends" rule is one readable block rather than an inline
 * expression that a later edit can quietly break.
 */
private fun pageContentTransform(rule: SheetPageTransition, durationMs: Int): ContentTransform {
    if (!rule.animate) return EnterTransition.None togetherWith ExitTransition.None
    val spec = tween<IntOffset>(durationMs, easing = FmEasing.standard)
    // Right = the pair travels right: the new page comes in from +width and the old
    // leaves towards -width. Left mirrors both. `None` carries no order, so it cannot
    // be slid at all — it is handled above as an instant swap even if a caller ever
    // builds it with `animate = true`.
    val towards = when (rule.direction) {
        SheetPageDirection.Right -> 1
        SheetPageDirection.Left -> -1
        SheetPageDirection.None -> return EnterTransition.None togetherWith ExitTransition.None
    }
    return slideInHorizontally(spec) { fullWidth -> fullWidth * towards } togetherWith
        slideOutHorizontally(spec) { fullWidth -> -fullWidth * towards }
}
