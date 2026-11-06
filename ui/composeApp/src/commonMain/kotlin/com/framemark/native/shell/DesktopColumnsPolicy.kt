package com.framemark.native.shell

import com.framemark.native.theme.FmDesktop

/**
 * V06 desktop three-column geometry.
 *
 * One pure source for the widths *and* for the "window is too narrow" decision:
 * the top bar and the column layout both compare the **content** width (window
 * minus the 12 dp edge on each side) against [desktopColumnMinWidth]. The old
 * 688/664 split compared a content width against an edge-inclusive constant, so
 * the bar and the columns disagreed by 24 dp — there is no second threshold now.
 *
 * All values are dp (the unit the Compose layout uses). Every field holds the
 * width actually occupied on screen: a folded rail still contributes
 * [desktopCollapsedRailWidth] because its expand affordance is drawn.
 */
data class DesktopColumns(
    val left: Float,
    val right: Float,
    val stage: Float,
    val showLeft: Boolean,
    val showRight: Boolean,
    val compact: Boolean,
)

/** Left library rail (FmDesktop.libraryMin). */
fun desktopLibraryMinWidth(): Float = FmDesktop.libraryMin.value

/** Left library rail ceiling (FmDesktop.libraryMax). */
fun desktopLibraryMaxWidth(): Float = FmDesktop.libraryMax.value

/** Right inspector rail minimum (FmDesktop.inspectorMin). */
fun desktopInspectorMinWidth(): Float = FmDesktop.inspectorMin.value

/** Right inspector rail ceiling (FmDesktop.inspectorMax). */
fun desktopInspectorMaxWidth(): Float = FmDesktop.inspectorMax.value

/** The stage never goes below this (FmDesktop.stageMin). */
fun desktopStageMinWidth(): Float = FmDesktop.stageMin.value

/** Gap between two columns (FmDesktop.gap). */
fun desktopColumnGapWidth(): Float = FmDesktop.gap.value

/** Width of a folded rail's expand affordance (FmDesktop.collapsedWidth). */
fun desktopCollapsedRailWidth(): Float = FmDesktop.collapsedWidth.value

/**
 * Smallest content width that still shows both rails and the stage at their
 * minima: 180 + 240 + 220 + 2 × 12 = 664 dp.
 *
 * This is derived from the tokens, not written down: a token change moves the
 * three-column floor, the top-bar wrap point and the compact switch together.
 */
fun desktopColumnMinWidth(): Float =
    desktopLibraryMinWidth() + desktopInspectorMinWidth() + desktopStageMinWidth() +
        desktopColumnGapWidth() * 2f

/**
 * Fits the three columns into [availableWidthDp] of content width.
 *
 * Rules, in order:
 *  1. `compact` is `availableWidthDp < desktopColumnMinWidth()`; below that the
 *     shell shows one pane at a time instead of three squeezed columns.
 *  2. The left rail folds (`showLeft = false`) when it cannot pay for itself at
 *     its minimum plus a usable stage.
 *  3. The right inspector folds (`showRight = false`) when the space left after
 *     the left rail — its panel, or its fold — cannot hold the inspector minimum
 *     plus a usable stage. Folding is explicit: the caller draws the collapsed
 *     rail and the top bar keeps a 「参数」 entry, so nothing silently leaves the
 *     window.
 *  4. Visible widths stay inside the token ranges and are additionally capped by
 *     what is left for the stage, so `left + right + 2 × gap + stage` equals
 *     `availableWidthDp` and no width is ever negative.
 *
 * A non-finite or non-positive width yields an empty, non-negative layout, and
 * every `coerceIn` here is applied to a verified `[min ≤ max]` range.
 */
fun computeDesktopColumns(
    availableWidthDp: Float,
    requestedLeft: Float,
    requestedRight: Float,
    leftCollapsed: Boolean,
    rightCollapsed: Boolean,
): DesktopColumns {
    val libraryMin = desktopLibraryMinWidth()
    val libraryMax = desktopLibraryMaxWidth()
    val inspectorMin = desktopInspectorMinWidth()
    val inspectorMax = desktopInspectorMaxWidth()
    val stageMin = desktopStageMinWidth()
    val gapTotal = desktopColumnGapWidth() * 2f
    val rail = desktopCollapsedRailWidth()

    val content = if (availableWidthDp.isFinite() && availableWidthDp > 0f) availableWidthDp else 0f

    // Below two folded rails plus both gaps there is not even room for the
    // affordances; hand everything to the stage rather than go negative.
    if (content < rail * 2f + gapTotal) {
        return DesktopColumns(
            left = 0f,
            right = 0f,
            stage = content,
            showLeft = false,
            showRight = false,
            compact = true,
        )
    }

    // The rails are capped by the stage token floor only. Using a percentage
    // target here made the effective ceiling smaller than the token ceiling, so
    // a request that is inside [min, max] could never reach the max even when
    // the window had room for it (DesktopColumnsTest pinned that mismatch).
    val stageTarget = stageMin
    val showLeft = !leftCollapsed && content >= libraryMin + rail + gapTotal + stageTarget
    val leftBudget = if (showLeft) libraryMin else rail
    val showRight = !rightCollapsed && content >= leftBudget + inspectorMin + gapTotal + stageTarget

    val rightReserve = if (showRight) inspectorMin else rail
    val leftCeiling = clampWidth(
        value = content - rightReserve - gapTotal - stageTarget,
        minimum = libraryMin,
        maximum = libraryMax,
    )
    val left = if (showLeft) clampWidth(requestedLeft, libraryMin, leftCeiling) else rail

    val right = if (showRight) {
        val rightCeiling = clampWidth(
            value = content - left - gapTotal - stageTarget,
            minimum = inspectorMin,
            maximum = inspectorMax,
        )
        clampWidth(requestedRight, inspectorMin, rightCeiling)
    } else {
        rail
    }

    return DesktopColumns(
        left = left,
        right = right,
        stage = (content - left - right - gapTotal).coerceAtLeast(0f),
        showLeft = showLeft,
        showRight = showRight,
        compact = content < desktopColumnMinWidth(),
    )
}

/**
 * Clamps [value] into `[minimum, maximum]` without ever building an invalid
 * range: a non-finite request and an inverted ceiling both fall back to the
 * nearest valid edge instead of throwing (`Float.coerceIn` throws when
 * `minimum > maximum`).
 */
private fun clampWidth(value: Float, minimum: Float, maximum: Float): Float {
    val ceiling = if (maximum < minimum) minimum else maximum
    val requested = if (value.isFinite()) value else minimum
    return requested.coerceIn(minimum, ceiling)
}

// ---------------------------------------------------------------------------
// 顶栏紧凑态（AU4-02）
// ---------------------------------------------------------------------------

/**
 * Content width from which the **full** top bar fits.
 *
 * Measured from the parts the demo deck puts in the bar (1440 dp capture, then
 * re-measured at 1280 after the compact form was added):
 *
 *   词标 65 + 分隔线 13 + 项目卡（名字上限 260 + chevron 14 + `● 已自动保存` 70
 *   + 内边距 16）= 373 + 中缝 24 + 段控 220 + 中缝 24 +
 *   右组（撤销 32 + 重做 32 + 分隔线 5 + 导入照片 102 + 导出 Ctrl E 123 + ⚙ 32
 *   + 6 × 6 间距）= 362 + 左右内边距 30
 *
 * ≈ 1038 dp. Anything narrower than the threshold switches to the compact bar,
 * which keeps every action and every label AU4-02 asks for and only drops the
 * decoration (词标、`Ctrl E` 提示、「导入照片」的文字), measured ≈ 630 dp — well
 * inside the smallest window ([DESKTOP_WINDOW_MIN_WIDTH], 600 dp).
 */
const val DESKTOP_TOP_BAR_FULL_WIDTH: Float = 1040f

/**
 * Smallest content width the compact bar was measured at: 项目卡（可收缩的项目名 +
 * chevron + `● 已自动保存`）+ 段控 160 + 撤销/重做 + 导入图标 + `导出` + ⚙ + 内边距
 * ≈ 630 dp, minus the 90 dp the project name may give up → 540 dp. The floor
 * window is 600 dp of content, so the compact bar always has room to spare.
 */
const val DESKTOP_TOP_BAR_COMPACT_MIN_WIDTH: Float = 540f

/**
 * True when the top bar must use its compact form at this content width.
 *
 * Same input as [computeDesktopColumns] (content width, i.e. the window width
 * under the flush V2 layout), so the bar and the columns agree by construction.
 * A NaN / non-positive width is treated as "no room" → compact.
 */
fun desktopTopBarCompact(contentWidthDp: Float): Boolean =
    !(contentWidthDp.isFinite() && contentWidthDp >= DESKTOP_TOP_BAR_FULL_WIDTH)

