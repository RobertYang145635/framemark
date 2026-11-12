package com.framemark.native.shell.phone

import androidx.compose.ui.unit.IntSize
import com.framemark.native.inspector.WatermarkZones
import com.framemark.native.shell.EditorTool
import com.framemark.native.shell.PHONE_PARAM_ROW_MIN_TOUCH_DP
import com.framemark.native.shell.showsWatermarkAnchorLayer
import com.framemark.native.shell.watermarkAnchorAt
import com.framemark.native.shell.watermarkSpecForAnchor
import com.framemark.native.workflow.ImageRect
import com.framemark.native.workflow.WatermarkSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 水印九宫格搬到画布上之后的验收（2026-11-11：改成「九个热点」，不再画图片）。
 *
 * 旧写法：面板里的 `WatermarkZoneGrid`（`AnchorCellsGrid`，行优先 0..8）点一格 →
 * `onAnchor(i)` → `watermark.copy(anchor = i)`（`WatermarkPositionBlock` 还会把两个方向
 * 的距离归零、把宽度收到新列上限）。新写法：画布上一格 → `watermarkAnchorAt(...)` →
 * `watermarkSpecForAnchor(...)` → 同一条 `onWatermarkChange` 通道。
 *
 * 这一份测试钉住四件事：**同样的格子写同样的值**、**只有水印工具 + 有水印时才画**、
 * **九个格子真的铺在照片矩形上且 ≥ 44 dp**、**每一格的可见元素只是一个 ~10 dp 的点
 * （没有图片、没有铺满格子的边框）**。像素级的「画出来只有一个点」在
 * `desktopTest/.../PhoneAnchorLayerRenderTest.kt` 里量。
 */
class PhoneAnchorLayerTest {

    @Test
    fun aTapInsideThePhotoRectMapsToTheSameAnchorKeyTheOldGridWrote() {
        // 行优先，左上 = 0、右下 = 8 —— 与 `AnchorCellsGrid` 的 `row * 3 + col` 同序。
        assertEquals(0, watermarkAnchorAt(0f, 0f))
        assertEquals(1, watermarkAnchorAt(0.5f, 0.1f))
        assertEquals(2, watermarkAnchorAt(1f, 0f))
        assertEquals(3, watermarkAnchorAt(0.1f, 0.5f))
        assertEquals(4, watermarkAnchorAt(0.5f, 0.5f))
        assertEquals(5, watermarkAnchorAt(0.9f, 0.5f))
        assertEquals(6, watermarkAnchorAt(0f, 1f))
        assertEquals(7, watermarkAnchorAt(0.5f, 0.9f))
        assertEquals(8, watermarkAnchorAt(1f, 1f))
        // 九格里每一格的中心都回到它自己的 index，而且与 `WatermarkZones` 的行列互逆。
        (0..8).forEach { index ->
            val col = index % 3
            val row = index / 3
            val centreX = (col + 0.5f) / 3f
            val centreY = (row + 0.5f) / 3f
            assertEquals(index, watermarkAnchorAt(centreX, centreY), "cell $index")
            assertEquals(row, WatermarkZones.rowOf(index))
            assertEquals(col, WatermarkZones.columnOf(index))
        }
        // 点在照片外（或量不出来的坐标）不写。
        assertNull(watermarkAnchorAt(-0.01f, 0.5f))
        assertNull(watermarkAnchorAt(1.01f, 0.5f))
        assertNull(watermarkAnchorAt(0.5f, -0.2f))
        assertNull(watermarkAnchorAt(0.5f, 1.2f))
        assertNull(watermarkAnchorAt(Float.NaN, 0.5f))
        assertNull(watermarkAnchorAt(0.5f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun tappingACellWritesTheSameSpecTheOldGridWrote() {
        val spec = WatermarkSpec(
            path = "/tmp/stamp.png",
            aspect = 1f,
            anchor = 8,
            width = 0.6f,
            opacity = 0.8f,
            dx = 0.2f,
            dy = 0.1f,
        )
        // 点左上：anchor = 0（走 `watermarkAnchorAt`），距离归零，宽度收到新列上限。
        val target = assertNotNull(watermarkAnchorAt(0.1f, 0.1f))
        val written = watermarkSpecForAnchor(spec, target)
        assertEquals(0, written.anchor)
        assertEquals(0f, written.dx)
        assertEquals(0f, written.dy)
        assertEquals(
            WatermarkZones.SIDE_WIDTH_CAP,
            written.width,
            "0.6 超过左列 47 % 的上限，必须收到上限（与旧面板同一顺序）",
        )
        // 宽度本来就没超上限时不动它。
        val narrow = spec.copy(width = 0.2f)
        assertEquals(0.2f, watermarkSpecForAnchor(narrow, target).width, 1e-6f)
        // 点中间：anchor = 4，上限换成中列的 60 %。
        val centre = assertNotNull(watermarkAnchorAt(0.5f, 0.5f))
        assertEquals(4, watermarkSpecForAnchor(spec, centre).anchor)
        assertEquals(0.6f, watermarkSpecForAnchor(spec, centre).width, 1e-6f)
        // 越界的 index 被夹住，而不是写出一个坏 anchor。
        assertEquals(0, watermarkSpecForAnchor(spec, -3).anchor)
        assertEquals(8, watermarkSpecForAnchor(spec, 99).anchor)
        // 写入通道是同一个 `copy`：其余字段原样带过去。
        val next = watermarkSpecForAnchor(spec, target)
        assertEquals(spec.path, next.path)
        assertEquals(spec.opacity, next.opacity)
        assertEquals(spec.aspect, next.aspect)
    }

    @Test
    fun theAnchorLayerOnlyShowsOnTheWatermarkToolWithALayer() {
        assertTrue(showsWatermarkAnchorLayer(EditorTool.Watermark, hasWatermark = true))
        assertFalse(showsWatermarkAnchorLayer(EditorTool.Watermark, hasWatermark = false))
        listOf(EditorTool.Border, EditorTool.Params, EditorTool.Text, EditorTool.Logo).forEach { tool ->
            assertFalse(
                showsWatermarkAnchorLayer(tool, hasWatermark = true),
                "$tool 不该画水印九宫格",
            )
        }
    }

    private val geo = PreviewGeo(
        w = 1000,
        h = 1000,
        photoRect = ImageRect(x = 100f, y = 50f, w = 800f, h = 900f),
    )

    @Test
    fun theNineCellsCoverThePhotoRectWithFortyFourDpTargets() {
        val stage = IntSize(width = 1000, height = 1000)
        val minCell = 44f * 3f // 44 dp @ density 3
        val box = assertNotNull(watermarkAnchorLayerBox(geo, stage, minCell))
        // 照片在舞台上是 contain 铺的：1000×1000 的舞台放 1000×1000 的帧，比例 1，
        // 照片矩形 (100,50,800,900) 因此就是 (100,50,800,900)。
        val photoLeft = 100f
        val photoTop = 50f
        val cellW = box.cellWidthPx
        val cellH = box.cellHeightPx
        assertTrue(cellW >= minCell - 0.01f, "cell width $cellW")
        assertTrue(cellH >= minCell - 0.01f, "cell height $cellH")
        // 九格正好盖住照片矩形：3 × 格子 = 层宽，而且居中在照片上。
        assertEquals(cellW * 3f, box.widthPx, 0.01f)
        assertEquals(cellH * 3f, box.heightPx, 0.01f)
        assertEquals(photoLeft + (800f - box.widthPx) / 2f, box.leftPx, 0.5f)
        assertEquals(photoTop + (900f - box.heightPx) / 2f, box.topPx, 0.5f)
        // 点在每一格的中心 → 那一格的 index（与旧面板九宫格同一个顺序）。
        (0..8).forEach { index ->
            val col = index % 3
            val row = index / 3
            val tapX = box.leftPx + (col + 0.5f) * cellW
            val tapY = box.topPx + (row + 0.5f) * cellH
            assertEquals(index, anchorIndexForTap(box, tapX, tapY), "tap on cell $index")
        }
        // 照片外的点不写。
        assertNull(anchorIndexForTap(box, box.leftPx - 10f, box.topPx + 10f))
        assertNull(anchorIndexForTap(box, box.leftPx + 10f, box.topPx + box.heightPx + 10f))
    }

    @Test
    fun aTinyPhotoKeepsTheTouchTargetsAndStaysOnTheStage() {
        // 很宽的横照片：照片只有 300 px 高，3 × 44 dp 的格子会探出照片一点 ——
        // 触控面不缩，但整层仍然留在舞台里（原点被夹回来，不画到屏幕外）。
        val letterboxed = PreviewGeo(w = 1000, h = 1000, photoRect = ImageRect(0f, 0f, 1000f, 300f))
        val box = assertNotNull(watermarkAnchorLayerBox(letterboxed, IntSize(1000, 1000), 44f * 3f))
        assertEquals(1000f, box.widthPx, 0.5f, "横向仍铺满照片")
        assertEquals(132f * 3f, box.heightPx, 0.5f, "格子保住 44 dp，所以层是 3 × 132 px")
        assertTrue(box.cellWidthPx >= 44f - 0.01f)
        assertTrue(box.cellHeightPx >= 44f - 0.01f)
        // 层不越出舞台。
        assertTrue(box.leftPx >= 0f && box.topPx >= 0f)
        assertTrue(box.leftPx + box.widthPx <= 1000f + 0.5f)
        assertTrue(box.topPx + box.heightPx <= 1000f + 0.5f)
        // 每一格仍然映射到它自己的 index。
        (0..8).forEach { index ->
            val col = index % 3
            val row = index / 3
            assertEquals(
                index,
                anchorIndexForTap(
                    box,
                    box.leftPx + (col + 0.5f) * box.cellWidthPx,
                    box.topPx + (row + 0.5f) * box.cellHeightPx,
                ),
                "cell $index",
            )
        }
    }

    @Test
    fun anUnknownPhotoRectDrawsNoLayerInsteadOfFallingBackToTheCanvas() {
        // 引擎还没报照片框：不画。退到整块画布会把格子放到边框 / 页脚上。
        assertNull(watermarkAnchorLayerBox(geo.copy(photoRect = null), IntSize(1000, 1000), 132f))
        assertNull(watermarkAnchorLayerBox(geo.copy(w = 0), IntSize(1000, 1000), 132f))
        assertNull(watermarkAnchorLayerBox(geo, IntSize(0, 0), 132f))
        assertNull(
            watermarkAnchorLayerBox(
                geo.copy(photoRect = ImageRect(0f, 0f, 0f, 0f)),
                IntSize(1000, 1000),
                132f,
            ),
        )
    }

    @Test
    fun theVisibleElementIsASmallDotInEveryCell() {
        // 缺陷 1：这一层曾经把每一格画成铺满一格的圆角块（照片看起来像 3 × 3 张图）。
        // 现在每格只有一个 ~10 dp 的圆点：选中实心、未选中细环，两种状态同径。
        assertEquals(10f, WATERMARK_ANCHOR_DOT_DP, 0.01f)
        assertTrue(WATERMARK_ANCHOR_RING_DP in 1f..2f, "细环要细：${WATERMARK_ANCHOR_RING_DP}")

        val idle = anchorHotspotVisual(active = false)
        val active = anchorHotspotVisual(active = true)
        assertEquals(WATERMARK_ANCHOR_DOT_DP, idle.diameterDp, 0.01f)
        assertEquals(WATERMARK_ANCHOR_DOT_DP, active.diameterDp, 0.01f, "两种状态同径")
        assertTrue(active.filled, "当前格是实心点")
        assertFalse(idle.filled)
        assertEquals(0f, active.ringWidthDp, 0.01f)
        assertTrue(idle.ringWidthDp > 0f, "未选中的格是细环")

        // 可见面积 ≪ 一格 44 dp 的触控面（5 % 以下）：格子是触控面，不是一块图。
        val cellArea = PHONE_PARAM_ROW_MIN_TOUCH_DP * PHONE_PARAM_ROW_MIN_TOUCH_DP
        assertTrue(active.visibleAreaDp2 < cellArea * 0.05f, "${active.visibleAreaDp2} dp²")
        assertTrue(idle.visibleAreaDp2 < cellArea * 0.05f, "${idle.visibleAreaDp2} dp²")
        // 九格九个点：直径 10 dp 的点在 3 × 44 dp 的层上互不相连（不会拼成一条边）。
        assertTrue(WATERMARK_ANCHOR_DOT_DP < PHONE_PARAM_ROW_MIN_TOUCH_DP / 2f)
    }

    @Test
    fun theLayerLabelsCoverTheWholeGrid() {
        assertEquals(9, WatermarkAnchorLabels.size)
        assertEquals(WatermarkAnchorLabels.size, WatermarkAnchorLabels.distinct().size)
        assertEquals("左上", WatermarkAnchorLabels.first())
        assertEquals("居中", WatermarkAnchorLabels[4])
        assertEquals("右下", WatermarkAnchorLabels.last())
    }
}
