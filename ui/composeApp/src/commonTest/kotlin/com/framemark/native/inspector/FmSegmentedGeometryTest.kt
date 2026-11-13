package com.framemark.native.inspector

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `.seg` 滑块几何 / 过渡的纯函数测试（对应 2026-11-11 「位置对应不上」修复）。
 *
 * 契约只有一条参照系：`geometry(widthPx, …)` 的 `widthPx` 是**控件外框**宽度（px，
 * 含两侧 2 dp 内边距），返回的 `xPx` 相对**内容区**左缘。于是滑块左缘的绝对位置 =
 * `2 dp × density + xPx`，必须正好压在选中格的左缘上。
 */
class FmSegmentedGeometryTest {

    private val insetDp = FmSegmented.Inset.value

    /** 内容区宽度（px）：外框减掉两侧内边距 —— 在测试里独立算一遍，不复用被测代码。 */
    private fun contentPx(widthPx: Float, density: Float) = widthPx - 2f * insetDp * density

    @Test
    fun everyIndexLandsFlushOnItsOwnCell() {
        val widths = listOf(320f, 296f, 240f, 400f)
        val densities = listOf(1f, 1.25f, 2f, 3f, 3.5f)
        for (count in 2..5) {
            for (widthPx in widths) {
                for (density in densities) {
                    val content = contentPx(widthPx, density)
                    val cell = content / count
                    for (index in 0 until count) {
                        val geo = FmSegmented.geometry(widthPx, count, index, density)
                        val where = "count=$count width=$widthPx density=$density index=$index"
                        assertTrue(geo.visible, "should be drawable: $where")
                        assertEquals(cell, geo.cellPx, 1e-3f, "cell width: $where")
                        // 滑块宽度 = 一格宽度（设计稿里滑块就是盖住一格的白板）。
                        assertEquals(cell, geo.widthPx, 1e-3f, "thumb width: $where")
                        // 滑块左缘 = 内容区左缘 + 格宽 × index。
                        assertEquals(cell * index, geo.xPx, 1e-3f, "thumb x: $where")
                        // 绝对位置（内容区左缘 2 dp × density 起算）。
                        assertEquals(
                            2f * insetDp * density + cell * index,
                            2f * insetDp * density + geo.xPx,
                            1e-3f,
                            "absolute thumb x: $where",
                        )
                        // 右缘紧贴下一格左缘；末格右缘紧贴内容区右缘（不许短一截）。
                        val right = geo.xPx + geo.widthPx
                        val expectedRight = if (index == count - 1) content else cell * (index + 1)
                        assertTrue(
                            abs(right - expectedRight) <= 1e-3f,
                            "thumb right edge $right != $expectedRight: $where",
                        )
                    }
                }
            }
        }
    }

    /**
     * 回归断言：旧实现把「已经减过内边距」的内容宽度又减了一次 4 dp，误差正好是
     * `4 × density / count × index` px（越靠右越大）。这一条把它钉成精确数值。
     */
    @Test
    fun theDoubleSubtractedInsetIsGoneWithTheExactMagnitude() {
        val widthPx = 320f
        val density = 2f
        for (count in 2..5) {
            val content = contentPx(widthPx, density)
            val oldCell = (content - 4f * density) / count
            for (index in 0 until count) {
                val geo = FmSegmented.geometry(widthPx, count, index, density)
                val oldX = oldCell * index
                assertEquals(
                    4f * density / count * index,
                    geo.xPx - oldX,
                    1e-3f,
                    "count=$count index=$index: the old arithmetic drifted by 4*density/count*index",
                )
            }
        }
    }

    @Test
    fun indexOutOfRangeClampsToAnExistingCell() {
        val widthPx = 320f
        val density = 2f
        for (count in 2..5) {
            assertEquals(0f, FmSegmented.geometry(widthPx, count, -7, density).xPx, "low clamp")
            assertEquals(0f, FmSegmented.geometry(widthPx, count, Int.MIN_VALUE, density).xPx)
            val last = FmSegmented.geometry(widthPx, count, count - 1, density)
            assertEquals(last.xPx, FmSegmented.geometry(widthPx, count, 99, density).xPx, "high clamp")
            assertEquals(
                last.xPx,
                FmSegmented.geometry(widthPx, count, Int.MAX_VALUE, density).xPx,
            )
        }
    }

    @Test
    fun unknownWidthDrawsNothing() {
        val density = 2f
        for (width in listOf(0f, -1f, -500f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val geo = FmSegmented.geometry(width, 3, 1, density)
            assertFalse(geo.visible, "width=$width must not draw")
            assertEquals(0f, geo.widthPx, "width=$width thumb width")
            assertEquals(0f, geo.xForIndex(1f), "width=$width animated x")
        }
        // 窄到连两侧内边距都放不下：内容区 <= 0，同样什么都不画（且不许出现负宽度）。
        for (width in listOf(1f, 4f * density, 4f * density - 0.5f)) {
            val geo = FmSegmented.geometry(width, 3, 0, density)
            assertFalse(geo.visible, "width=$width is thinner than the inset pair")
            assertTrue(geo.widthPx >= 0f, "width=$width must never be negative")
        }
        // 没有选项 / 非法密度：同样不画。
        assertFalse(FmSegmented.geometry(320f, 0, 0, density).visible)
        assertFalse(FmSegmented.geometry(320f, -2, 0, density).visible)
        for (bad in listOf(0f, -1f, Float.NaN)) {
            assertFalse(FmSegmented.geometry(320f, 3, 0, bad).visible, "density=$bad")
        }
    }

    @Test
    fun thumbWidthIsNeverNegativeAcrossTheGrid() {
        for (count in 0..5) {
            for (width in listOf(-100f, -0.5f, 0f, 0.5f, 4f, 7.9f, 8f, 9f, 320f, 2000f)) {
                for (density in listOf(0.75f, 1f, 2f, 3.5f)) {
                    val geo = FmSegmented.geometry(width, count, 2, density)
                    assertTrue(geo.widthPx >= 0f, "count=$count width=$width density=$density")
                    assertTrue(geo.xPx >= 0f, "count=$count width=$width density=$density x")
                    if (geo.visible) {
                        assertEquals(geo.cellPx, geo.widthPx, 1e-4f)
                        assertTrue(geo.cellPx > 0f)
                    }
                }
            }
        }
    }

    @Test
    fun sameIndexWithChangedWidthDoesNotAnimate() {
        val count = 4
        val index = 2
        for (previousWidth in listOf(280f, 320f, 400f)) {
            for (width in listOf(280f, 320f, 400f)) {
                if (previousWidth == width) continue
                val motion = FmSegmented.motion(
                    previousIndex = index,
                    widthPx = width,
                    count = count,
                    index = index,
                    density = 2f,
                )
                val where = "previousWidth=$previousWidth width=$width"
                assertFalse(motion.animate, "a width change must not animate: $where")
                // 立即落位：起点就是终点的精确位置。
                assertEquals(motion.toXPx, motion.fromXPx, 1e-4f, "instant: $where")
                assertEquals(index.toFloat(), motion.toIndex)
                assertEquals(index.toFloat(), motion.fromIndex)
                // 终点就是当前宽度下这一格的精确左缘。
                val cell = contentPx(width, 2f) / count
                assertEquals(cell * index, motion.toXPx, 1e-3f, "exact rest position: $where")
            }
        }
    }

    @Test
    fun changedIndexAnimatesFromThePreviousCell() {
        val count = 3
        val width = 320f
        val density = 2f
        val cell = contentPx(width, density) / count
        for (from in 0 until count) {
            for (to in 0 until count) {
                if (from == to) continue
                val motion = FmSegmented.motion(from, width, count, to, density)
                val where = "$from -> $to"
                assertTrue(motion.animate, "a real selection change animates: $where")
                assertEquals(cell * from, motion.fromXPx, 1e-3f, "starts on the previous cell: $where")
                assertEquals(cell * to, motion.toXPx, 1e-3f, "ends on the selected cell: $where")
                assertEquals(from.toFloat(), motion.fromIndex)
                assertEquals(to.toFloat(), motion.toIndex)
            }
        }
    }

    @Test
    fun firstPlacementNeverAnimates() {
        for (count in 2..5) {
            for (index in 0 until count) {
                val motion = FmSegmented.motion(null, 320f, count, index, 2f)
                assertFalse(motion.animate, "first measure must not animate (count=$count idx=$index)")
                assertEquals(motion.toXPx, motion.fromXPx, 1e-4f)
                val cell = contentPx(320f, 2f) / count
                assertEquals(cell * index, motion.toXPx, 1e-3f)
            }
        }
        // 宽度还没量到时也不能动画（此时没有格子可落）。
        val unknown = FmSegmented.motion(2, 0f, 3, 1, 2f)
        assertFalse(unknown.animate)
        assertEquals(0f, unknown.toXPx)
    }

    @Test
    fun motionClampsBothEndsToTheSameCellSet() {
        val motion = FmSegmented.motion(
            previousIndex = 9,
            widthPx = 320f,
            count = 3,
            index = 4,
            density = 2f,
        )
        // previous=9 与 index=4 都夹到最后一格（2）：没有换格，因此不动画。
        assertEquals(2f, motion.fromIndex)
        assertEquals(2f, motion.toIndex)
        assertFalse(motion.animate)
    }

    @Test
    fun animatedIndexInterpolatesBetweenCellsAndStaysInsideTheTrack() {
        val count = 4
        val density = 2f
        val cell = contentPx(320f, density) / count
        val geo = FmSegmented.geometry(320f, count, 1, density)
        assertEquals(cell * 0.5f, geo.xForIndex(0.5f), 1e-3f, "half a cell")
        assertEquals(cell * 1f, geo.xForIndex(1f), 1e-3f)
        assertEquals(0f, geo.xForIndex(-3f), 1e-3f, "never left of the track")
        assertEquals(cell * (count - 1), geo.xForIndex(99f), 1e-3f, "never right of the track")
    }
}
