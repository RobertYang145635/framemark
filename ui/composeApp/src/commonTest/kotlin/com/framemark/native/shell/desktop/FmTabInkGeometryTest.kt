package com.framemark.native.shell.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `.itabs` 指示条几何 / 过渡的纯函数测试（2026-11-13「右边栏横条位置对应不上」）。
 *
 * 契约只有一条参照系：[FmTabInk.geometry] 的 `stripWidthPx` 是**页签行外框**宽度（px，
 * 含两侧 10 dp 内边距）；[FmTabBar.xForIndex] 返回的 x 相对**内容区**左缘。于是横条中心
 * 的绝对位置 = `10 dp × density + x + width / 2`，必须落在选中页签格的中心上。
 *
 * 页签是等宽格（`Modifier.weight(1f)`），横条仍是「标签 + 12 dp」宽、在格内居中：等宽格
 * 决定**位置**，标签宽度只决定**长度**。所以这里同时验「格」和「横条」两套坐标：
 * 格与内容区严丝合缝（位置缺陷的回归），横条中心压在格中心上（用户口径的验收）。
 */
class FmTabInkGeometryTest {

    private val insetDp = FmTabInk.Inset.value

    /** 右栏宽（`FM_DESKTOP_COLUMNS right=336`）、窄窗、以及两个更宽的值。 */
    private val stripWidthsDp = listOf(240f, 296f, 336f, 400f)

    private val densities = listOf(1f, 1.25f, 2f, 3f)

    /** 内容区宽度（px）：外框减掉两侧内边距 —— 在测试里独立算一遍，不复用被测代码。 */
    private fun contentPx(stripDp: Float, density: Float) =
        stripDp * density - 2f * insetDp * density

    @Test
    fun everyTabCellIsFlushWithThePaddedContent() {
        for (count in listOf(4, 5)) {
            for (stripDp in stripWidthsDp) {
                for (density in densities) {
                    val where = "count=$count strip=${stripDp}dp density=$density"
                    val track = FmTabInk.geometry(stripDp * density, count, density)
                    assertTrue(track.visible, "should be drawable: $where")
                    assertEquals(contentPx(stripDp, density), track.contentWidthPx, 1e-3f, "content: $where")
                    val cell = contentPx(stripDp, density) / count
                    assertEquals(cell, track.cellWidthPx, 1e-3f, "cell: $where")
                    for (index in 0 until count) {
                        assertEquals(cell * index, track.leftPx(index), 1e-3f, "cell left $index: $where")
                    }
                    // 末格右缘必须正好是内容区右缘（旧实现每格宽了 2 × 10 / count dp，
                    // 末格右缘因此越过内容区、跑到内边距上）。
                    assertEquals(track.contentWidthPx, track.rightPx(count - 1), 1e-3f, "last right: $where")
                }
            }
        }
    }

    /** 横条的**中心**压在选中页签格的中心上：这就是「位置对应得上」。 */
    @Test
    fun theBarCentreIsTheSelectedTabsCentre() {
        // 4 个页签的真实量级：样式 / Logo / 文字 / 水印（带数字徽标的那组更宽）。
        val labelDp = listOf(26f, 62f, 24f, 28f)
        for (count in listOf(4, 5)) {
            for (stripDp in stripWidthsDp) {
                for (density in densities) {
                    val track = FmTabInk.geometry(stripDp * density, count, density)
                    val cell = track.cellWidthPx
                    for (index in 0 until count) {
                        val where = "count=$count strip=${stripDp}dp density=$density index=$index"
                        val labelPx = labelDp[index % labelDp.size] * density
                        val bar = FmTabInk.bar(track, labelPx, density)
                        assertTrue(bar.visible, "bar should exist: $where")
                        val left = bar.xForIndex(index.toFloat())
                        val right = left + bar.widthPx
                        // 1 px 的验收线：中心对不上就是用户报的那个 bug。
                        assertEquals(cell * (index + 0.5f), left + bar.widthPx / 2f, 1f, "centre: $where")
                        // 横条永远在自己的格子里（不许跨到隔壁页签）。
                        assertTrue(left >= cell * index - 1f, "left inside the cell: $where")
                        assertTrue(right <= cell * (index + 1) + 1f, "right inside the cell: $where")
                    }
                }
            }
        }
    }

    @Test
    fun theBarWidthIsTheLabelPlusTheDesignGap() {
        val density = 2f
        val track = FmTabInk.geometry(336f * density, 4, density)
        val bar = FmTabInk.bar(track, 26f * density, density)
        assertEquals((26f + FmTabInk.BarGap.value) * density, bar.widthPx, 1e-3f)
        // 长标签不顶出自己的格：夹到一格宽。
        val wide = FmTabInk.bar(track, 500f * density, density)
        assertEquals(track.cellWidthPx, wide.widthPx, 1e-3f)
        assertTrue(wide.widthPx > 0f)
    }

    /**
     * 回归断言：旧实现用**外框** / count 当格宽（10 dp 内边距从没被减掉），横条因此
     * 右偏 `(i + 0.5) × 2 × 10 / count` px。这一条把它钉成精确数值：density 1、4 格时
     * 每格 5 px、末格 17.5 px。
     */
    @Test
    fun theOldOuterCellFormulaDriftedByTheMissingInset() {
        val density = 1f
        val count = 4
        val stripPx = 336f * density
        val labelPx = 26f * density
        val track = FmTabInk.geometry(stripPx, count, density)
        val bar = FmTabInk.bar(track, labelPx, density)
        val oldCell = stripPx / count
        for (index in 0 until count) {
            // 旧实现画出来的左缘（density 1 时「px 当 dp」还不显形）：
            // 10 dp 内边距 + 外框格 × index + 居中 − 6 dp。
            val oldLeft = insetDp * density + oldCell * index + (oldCell - labelPx) / 2f - 6f * density
            val newLeft = insetDp * density + bar.xForIndex(index.toFloat())
            val drift = (index + 0.5f) * 2f * insetDp * density / count
            assertEquals(drift, oldLeft - newLeft, 1e-3f, "index=$index drift")
        }
        // 末格 17.5 px，远超 1 px 的验收线。
        assertEquals(17.5f, (3 + 0.5f) * 2f * insetDp / count, 1e-4f)
    }

    /**
     * 旧实现在 density 2 时把 px 当 dp 又乘了一次（`targetX.dp` + `.offset`）：末页签的
     * 横条落在 560 dp 处，而右栏只有 336 dp —— 整条横条被画到栏外（用户截图里就是
     * 「横条不见了」）。这条把旧落点钉出来，防止有人改回去。
     */
    @Test
    fun theOldPixelAsDpBugPutTheLastBarOutsideTheRail() {
        val density = 2f
        val count = 4
        val stripDp = 336f
        val labelPx = 26f * density
        val index = count - 1
        val outerPx = stripDp * density
        val oldCell = outerPx / count
        val oldTargetPx = oldCell * index + (oldCell - labelPx) / 2f - 6f * density
        val oldDrawnLeftPx = density * (oldTargetPx + 10f)
        val track = FmTabInk.geometry(outerPx, count, density)
        val bar = FmTabInk.bar(track, labelPx, density)
        val newDrawnLeftPx = insetDp * density + bar.xForIndex(index.toFloat())
        assertTrue(oldDrawnLeftPx > outerPx, "旧公式应当把横条画到右栏外（$oldDrawnLeftPx px）")
        assertTrue(newDrawnLeftPx + bar.widthPx <= outerPx, "新位置必须在右栏内")
    }

    @Test
    fun theAnimatedIndexIsClampedAndWidthChangesDoNotAnimate() {
        val count = 4
        val density = 2f
        val tracks = listOf(296f, 336f, 400f).map { FmTabInk.geometry(it * density, count, density) }
        val bars = tracks.map { FmTabInk.bar(it, 26f * density, density) }
        // 小数索引越界 -> 夹到两端（永远画不出轨道）。
        for (bar in bars) {
            assertEquals(bar.xForIndex(0f), bar.xForIndex(-7f), 1e-4f)
            assertEquals(bar.xForIndex(3f), bar.xForIndex(99f), 1e-4f)
        }
        // 同一页签、宽度变了：不跑弹簧，直接落在新宽度下的精确位置。
        val step = FmTabInk.motion(2, count, 2)
        assertFalse(step.animate, "a width change must not animate")
        assertEquals(step.fromIndex, step.toIndex)
        for ((track, bar) in tracks.zip(bars)) {
            val cell = track.cellWidthPx
            assertEquals(
                cell * 2f + (cell - bar.widthPx) / 2f,
                bar.xForIndex(step.toIndex),
                1e-3f,
                "height/width change must land exactly, not animate",
            )
        }
    }

    @Test
    fun aRealIndexChangeStartsOnThePreviousTabsExactCentre() {
        val density = 2f
        val count = 4
        val track = FmTabInk.geometry(336f * density, count, density)
        val bar = FmTabInk.bar(track, 26f * density, density)
        for (from in 0 until count) {
            for (to in 0 until count) {
                if (from == to) continue
                val step = FmTabInk.motion(from, count, to)
                assertTrue(step.animate, "a real tab change must animate: $from -> $to")
                assertEquals(from.toFloat(), step.fromIndex)
                assertEquals(to.toFloat(), step.toIndex)
                // 弹簧从「上一页签此刻的精确位置」起步（中心 = 格中心）。
                assertEquals(
                    track.cellWidthPx * (from + 0.5f),
                    bar.xForIndex(step.fromIndex) + bar.widthPx / 2f,
                    1e-3f,
                    "spring starts on the previous tab's centre: $from -> $to",
                )
            }
        }
    }

    @Test
    fun firstPlacementAndOutOfRangeIndicesSnap() {
        for (count in 3..5) {
            for (index in 0 until count) {
                val step = FmTabInk.motion(null, count, index)
                assertFalse(step.animate, "first placement must not animate (count=$count idx=$index)")
                assertEquals(step.toIndex, step.fromIndex, 1e-6f)
                assertEquals(index.toFloat(), step.toIndex)
            }
        }
        // 远越界的两个索引夹到同一格：夹完没换格，因此不动画。
        val clamped = FmTabInk.motion(9, 4, 7)
        assertFalse(clamped.animate)
        assertEquals(3f, clamped.fromIndex)
        assertEquals(3f, clamped.toIndex)
        assertEquals(0f, FmTabInk.motion(-3, 4, -1).toIndex)
        // 没有页签：不画、不动画。
        val none = FmTabInk.motion(0, 0, 0)
        assertFalse(none.animate)
        assertEquals(0f, none.toIndex)
        assertEquals(0f, none.fromIndex)
    }

    @Test
    fun unknownOrDegenerateGeometryDrawsNothing() {
        for (width in listOf(0f, -1f, -500f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val track = FmTabInk.geometry(width, 4, 2f)
            assertFalse(track.visible, "width=$width must not draw")
            val bar = FmTabInk.bar(track, 52f, 2f)
            assertFalse(bar.visible, "width=$width bar must not draw")
            assertEquals(0f, bar.widthPx)
            assertEquals(0f, bar.xForIndex(1f))
            assertTrue(bar.widthPx >= 0f)
        }
        // 窄到连两侧内边距都放不下：内容区 <= 0，同样不画，且宽度不许为负。
        for (width in listOf(1f, 2f * insetDp * 2f - 0.5f)) {
            val track = FmTabInk.geometry(width, 4, 2f)
            assertFalse(track.visible, "width=$width is thinner than the inset pair")
            assertTrue(FmTabInk.bar(track, 52f, 2f).widthPx >= 0f)
        }
        // 没有页签 / 非法密度 / 标签还没量到：一律不画。
        assertFalse(FmTabInk.geometry(672f, 0, 2f).visible)
        assertFalse(FmTabInk.geometry(672f, -2, 2f).visible)
        for (bad in listOf(0f, -1f, Float.NaN)) {
            assertFalse(FmTabInk.geometry(672f, 4, bad).visible, "density=$bad")
        }
        val track = FmTabInk.geometry(672f, 4, 2f)
        for (label in listOf(0f, -5f, Float.NaN)) {
            assertFalse(FmTabInk.bar(track, label, 2f).visible, "label=$label")
        }
    }

    @Test
    fun barWidthIsNeverNegativeAcrossTheGrid() {
        for (count in 0..5) {
            for (stripDp in listOf(-10f, 0f, 1f, 20f, 40f, 240f, 336f, 2000f)) {
                for (density in listOf(0.75f, 1f, 2f, 3f)) {
                    val track = FmTabInk.geometry(stripDp * density, count, density)
                    for (label in listOf(-1f, 0f, 26f * density, 9999f)) {
                        val bar = FmTabInk.bar(track, label, density)
                        val where = "count=$count strip=$stripDp density=$density label=$label"
                        assertTrue(bar.widthPx >= 0f, "width must never be negative: $where")
                        assertTrue(bar.xForIndex(1f) >= 0f, "x must never be negative: $where")
                        if (bar.visible) {
                            assertTrue(bar.widthPx <= track.cellWidthPx + 1e-3f, "width <= cell: $where")
                        }
                    }
                }
            }
        }
    }
}
