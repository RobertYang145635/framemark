package com.framemark.native.shell

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 浮起玻璃胶囊的**几何纯函数**测试（没有任何 Compose 组合，只算 dp）。
 *
 * 契约三条：
 *  1. 胶囊装在调用者给的槽位里（58 / 62 dp），**槽位高度不变** —— 所以
 *     `PhoneEditor` 的面积预算与 `PhoneEditorHeightsTest` 不受影响；
 *  2. 等宽格必须严丝合缝地铺满轨道，末格右缘正好落在「胶囊宽 − 内边距 − 尾部动作」
 *     上（差一格宽就是「指示条偏了」那类缺陷）；
 *  3. 每格的触控高度 = 胶囊 − 上下内缩，**永不小于 48 dp**。
 */
class FmNavCapsuleTest {

    /** 手机槽位：小窗 58 dp、常规 62 dp（`PhoneEditor.kt` 的 `toolBarH`）。 */
    private val slots = listOf(58f, 62f)

    /** 胶囊宽度：390 dp 屏 − 左右各 8 dp、320 dp 的窄屏、以及一个很宽的窗口。 */
    private val capsuleWidths = listOf(304f, 374f, 254f, 600f)

    private val counts = listOf(4, 5)

    @Test
    fun theCapsuleStaysInsideItsSlotAndNeverGrowsIt() {
        for (slot in slots) {
            val capsule = FmNavCapsule.capsuleHeightDp(slot.dp)
            assertEquals(slot - FmNavCapsule.bottomInset.value, capsule.value, 1e-4f, "slot=$slot")
            assertTrue(capsule.value <= slot, "capsule must fit in its slot (slot=$slot)")
            assertTrue(capsule.value > 0f, "capsule must have a height (slot=$slot)")
        }
        // 0 高槽位（全高参数页）不画。
        assertEquals(0f, FmNavCapsule.capsuleHeightDp(0.dp).value, 1e-4f)
        // 极端矮窗口：胶囊被夹回槽位，绝不撑出去（宁可触控小，也不能盖住上面的面板）。
        assertEquals(30f, FmNavCapsule.capsuleHeightDp(30.dp).value, 1e-4f)
    }

    @Test
    fun everyCellClearsTheTouchTargetFloor() {
        for (slot in slots) {
            val capsule = FmNavCapsule.capsuleHeightDp(slot.dp)
            val cellHeight = FmNavCapsule.pillHeightDp(capsule)
            assertTrue(
                cellHeight.value + 1e-4f >= FmNavCapsule.minTouchTarget.value,
                "slot=$slot → cell=${cellHeight.value} dp must be >= 48 dp",
            )
            // 最矮的真实槽位（58 dp）刚好是 48 dp：再小就要破触控底线了，这条把它钉住。
            if (slot == 58f) assertEquals(48f, cellHeight.value, 1e-4f)
            if (slot == 62f) assertEquals(52f, cellHeight.value, 1e-4f)
        }
    }

    @Test
    fun cellsTileTheTrackExactly() {
        for (slot in slots) {
            for (width in capsuleWidths) {
                for (count in counts) {
                    for (trailing in listOf(0f, 63f)) {
                        val where = "slot=$slot width=$width count=$count trailing=$trailing"
                        val cell = FmNavCapsule.cellWidthDp(width.dp, count, trailing.dp)
                        assertTrue(cell.value > 0f, "cell width must be positive: $where")
                        for (index in 0 until count) {
                            val left = FmNavCapsule.cellLeftDp(index, width.dp, count, trailing.dp)
                            assertEquals(
                                FmNavCapsule.innerPad.value + cell.value * index,
                                left.value,
                                1e-3f,
                                "cell left $index: $where",
                            )
                        }
                        // 末格右缘 = 胶囊宽 − 内边距 − 尾部动作（差一格宽就是「偏了」）。
                        val lastLeft = FmNavCapsule.cellLeftDp(count - 1, width.dp, count, trailing.dp)
                        assertEquals(
                            width - FmNavCapsule.innerPad.value - trailing,
                            lastLeft.value + cell.value,
                            1e-3f,
                            "last cell right edge: $where",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun thePillSitsCentredInItsOwnCellAndNeverLeavesTheCapsule() {
        for (width in capsuleWidths) {
            for (count in counts) {
                for (trailing in listOf(0f, 63f)) {
                    val cell = FmNavCapsule.cellWidthDp(width.dp, count, trailing.dp)
                    val pillWidth = FmNavCapsule.pillWidthDp(width.dp, count, trailing.dp)
                    assertTrue(pillWidth.value > 0f, "pill must be drawable (width=$width count=$count)")
                    for (index in 0 until count) {
                        val where = "width=$width count=$count trailing=$trailing index=$index"
                        val pillLeft = FmNavCapsule.pillLeftDp(index, width.dp, count, trailing.dp)
                        val cellLeft = FmNavCapsule.cellLeftDp(index, width.dp, count, trailing.dp)
                        // 左右各内缩一个 pillInsetH。
                        assertEquals(
                            cellLeft.value + FmNavCapsule.pillInsetH.value,
                            pillLeft.value,
                            1e-3f,
                            "pill inset from its cell: $where",
                        )
                        // 中心压在格中心上。
                        assertEquals(
                            cellLeft.value + cell.value / 2f,
                            pillLeft.value + pillWidth.value / 2f,
                            1e-3f,
                            "pill centre: $where",
                        )
                        // 永远在自己的格子里。
                        assertTrue(pillLeft.value >= cellLeft.value - 1e-3f, "pill inside its cell: $where")
                        assertTrue(
                            pillLeft.value + pillWidth.value <= cellLeft.value + cell.value + 1e-3f,
                            "pill must not cross into the next cell: $where",
                        )
                        assertTrue(
                            pillLeft.value + pillWidth.value <= width - FmNavCapsule.innerPad.value - trailing + 1e-3f,
                            "pill must stay inside the track: $where",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun thePillIsNeverWiderThanItsCellAndTheShapeIsAStadium() {
        for (width in capsuleWidths) {
            for (count in counts) {
                val cell = FmNavCapsule.cellWidthDp(width.dp, count)
                val pill = FmNavCapsule.pillWidthDp(width.dp, count)
                assertTrue(pill.value <= cell.value + 1e-4f, "pill <= cell (width=$width count=$count)")
            }
        }
        for (slot in slots) {
            val capsule = FmNavCapsule.capsuleHeightDp(slot.dp)
            // 全圆：半径 = 高度一半。
            assertEquals(capsule.value / 2f, FmNavCapsule.radiusDp(capsule).value, 1e-4f, "slot=$slot")
        }
    }

    @Test
    fun degenerateInputsDoNotDivideByZero() {
        for (width in listOf(0f, -10f, 1f, 2f)) {
            for (count in listOf(0, -3)) {
                val where = "width=$width count=$count"
                assertEquals(0f, FmNavCapsule.cellWidthDp(width.dp, count).value, 1e-6f, "cell: $where")
                assertEquals(0f, FmNavCapsule.pillWidthDp(width.dp, count).value, 1e-6f, "pill width: $where")
                assertEquals(0f, FmNavCapsule.pillLeftDp(1, width.dp, count).value, 1e-6f, "pill left: $where")
                assertEquals(0f, FmNavCapsule.cellLeftDp(1, width.dp, count).value, 1e-6f, "cell left: $where")
            }
        }
        // 窄到连内边距都放不下：宽度夹到 0，绝不出现负数。
        val narrow = FmNavCapsule.pillWidthDp(3.dp, 5)
        assertTrue(narrow.value >= 0f, "narrow=$narrow")
        // 索引越界夹到两端。
        assertEquals(
            FmNavCapsule.cellLeftDp(0, 300.dp, 5).value,
            FmNavCapsule.cellLeftDp(-9, 300.dp, 5).value,
            1e-6f,
        )
        assertEquals(
            FmNavCapsule.cellLeftDp(4, 300.dp, 5).value,
            FmNavCapsule.cellLeftDp(99, 300.dp, 5).value,
            1e-6f,
        )
    }

    @Test
    fun theSelectedIndexFollowsTheVisibleToolsOnly() {
        val all = EditorTool.entries
        assertEquals(0, FmNavCapsule.selectedIndex(all, EditorTool.Border))
        assertEquals(4, FmNavCapsule.selectedIndex(all, EditorTool.Watermark))
        // 没有 Logo 能力时的四格：Logo 不在这一排，所以没有 pill。
        val withoutLogo = all.filter { it != EditorTool.Logo }
        assertEquals(-1, FmNavCapsule.selectedIndex(withoutLogo, EditorTool.Logo))
        assertEquals(3, FmNavCapsule.selectedIndex(withoutLogo, EditorTool.Watermark))
        assertEquals(-1, FmNavCapsule.selectedIndex(emptyList(), EditorTool.Border))
    }

    /** 枚举值 / 顺序是外部契约（路由与测试都按它们取），这条钉住。 */
    @Test
    fun theToolEnumKeepsItsOrderAndLabels() {
        assertEquals(
            listOf("边框", "调整", "文字", "Logo", "图片水印"),
            EditorTool.entries.map { it.label },
        )
        assertEquals(
            listOf("Border", "Params", "Text", "Logo", "Watermark"),
            EditorTool.entries.map { it.name },
        )
    }

    @Test
    fun theGlassFallsBackToTintWhenThereIsNothingToBlurOrNoBlurSupport() {
        // 有采集层 / 半径为正 / 平台能糊 → 真玻璃。
        assertEquals(
            FmNavGlassLevel.Backdrop,
            FmNavGlass.decide(hasBackdrop = true, blurRadius = 24.dp, blurSupported = true),
        )
        // 没有采集层：没什么可糊的。
        assertEquals(
            FmNavGlassLevel.TintOnly,
            FmNavGlass.decide(hasBackdrop = false, blurRadius = 24.dp, blurSupported = true),
        )
        // API < 31（`RenderEffect` 不可用）：宁可纯半透明，也不画一张没糊的拷贝
        // —— 那会是错位的鬼影，比没有玻璃更糟。
        assertEquals(
            FmNavGlassLevel.TintOnly,
            FmNavGlass.decide(hasBackdrop = true, blurRadius = 24.dp, blurSupported = false),
        )
        // 半径 0：等于不糊。
        assertEquals(
            FmNavGlassLevel.TintOnly,
            FmNavGlass.decide(hasBackdrop = true, blurRadius = 0.dp, blurSupported = true),
        )
        // 负半径同样退化（防御：不让一个坏值画出鬼影）。
        assertEquals(
            FmNavGlassLevel.TintOnly,
            FmNavGlass.decide(hasBackdrop = true, blurRadius = (-1).dp, blurSupported = true),
        )
    }
}
