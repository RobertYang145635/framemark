package com.framemark.native.shell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.framemark.native.inspector.InspectorPage
import com.framemark.native.inspector.InspectorTabInfo
import com.framemark.native.inspector.InspectorTabModel
import java.awt.image.BufferedImage
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `.itabs` 指示条的**像素级**验收（2026-11-13「右边栏横条位置对应不上」）。
 *
 * 几何函数只证明算术对，证明不了「画出来的横条真的压在选中页签上」—— 中间还隔着
 * `onGloballyPositioned` 量的是哪一段宽度、`.offset` 的参照系、`.padding(horizontal =
 * 10.dp)` 的先后顺序、以及 px→dp 的换算点。这几处正是原缺陷的所在。
 *
 * 右栏取 336 dp（`FM_DESKTOP_COLUMNS right=336`），density 2 → 外框 672 px、内容区
 * 632 px、4 格每格 158 px，所有边界都是整数像素。
 *
 * 量法：横条是 `onSurface`（浅色主题 #14181F）自绘的 2 dp 圆角条，画在绿色底上；文字
 * 在它上方两行之外，所以只扫 `BarTop .. BarTop + 2 dp` 这条像素带。横条两端是圆角，
 * 边缘像素会被抗锯齿吃掉 1 px 左右，因此位置用**中心**判（容差 1.5 px），左右缘只用来
 * 验「没跨出自己的格」。
 */
class DesktopTabInkRenderTest {

    /** 右栏宽度（`FM_DESKTOP_COLUMNS right=336`）。 */
    private val railDp = 336f

    private fun modelOf(count: Int): InspectorTabModel =
        InspectorTabModel(
            cardId = "tab-ink-render",
            tabs = InspectorPage.entries.take(count).map { InspectorTabInfo(it, modified = 0) },
        )

    @OptIn(ExperimentalComposeUiApi::class)
    private fun <T> withScene(
        density: Float,
        content: @Composable () -> Unit,
        block: (ImageComposeScene) -> T,
    ): T {
        val scene = ImageComposeScene(
            width = (railDp * density).roundToInt(),
            height = (48f * density).roundToInt(),
            density = Density(density),
            content = content,
        )
        // 必须无条件关闭：漏一个 scene 会留下非守护线程，把测试 worker 挂住。
        try {
            return block(scene)
        } finally {
            scene.close()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.renderFrames(count: Int): List<BufferedImage> = buildList {
        repeat(count) {
            val png = render().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
            add(javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(requireNotNull(png))))
        }
    }

    private fun frame(
        density: Float,
        count: Int = 4,
        selected: InspectorPage,
        frames: Int = 6,
    ): BufferedImage = withScene(density, content = { Host(count, selected) }) { scene ->
        scene.renderFrames(frames).last()
    }

    @Composable
    private fun Host(
        count: Int,
        selected: InspectorPage,
        onSelect: (InspectorPage) -> Unit = {},
    ) {
        // 铺满场景：右栏宽度 = 场景宽度，页签条从 (0, 0) 开始，量出来的像素坐标就是
        // 右栏坐标（不依赖场景根节点怎么摆放内容）。
        Box(Modifier.fillMaxSize().background(Color(0xFF00FF00))) {
            InspectorPageTabs(modelOf(count), selected, onSelect)
        }
    }

    /** 横条所在的像素带：`BarTop` 到 `BarTop + BarHeight`。 */
    private fun barBand(density: Float): IntRange {
        val top = (FmTabInk.BarTop.value * density).roundToInt()
        val bottom = ((FmTabInk.BarTop + FmTabInk.BarHeight).value * density).roundToInt()
        return top until bottom
    }

    /** 横条左右边缘（闭区间）；一个深色像素都没有就是 null（= 没画横条）。 */
    private fun barBounds(image: BufferedImage, density: Float): IntRange? {
        var left = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        for (y in barBand(density)) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                // 场景里横条以外的区域是透明的：Alpha = 0 的像素不算（否则会被当成纯黑）。
                if ((argb ushr 24) == 0) continue
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                if (r < 120 && g < 120 && b < 120) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        return if (right < 0) null else left..right
    }

    /** 横条中心 = 选中格中心（内容区左缘 + 格宽 × (index + 0.5)）。 */
    private fun assertBarOnTab(index: Int, count: Int, density: Float) {
        val where = "count=$count index=$index density=$density"
        val selected = modelOf(count).tabs[index].page
        val image = frame(density, count = count, selected = selected)
        val bar = assertNotNull(barBounds(image, density), "no ink bar drawn: $where")
        val track = FmTabInk.geometry(railDp * density, count, density)
        val insetPx = FmTabInk.Inset.value * density
        val cell = track.cellWidthPx
        val expectedCentre = insetPx + cell * (index + 0.5f)
        val actualCentre = (bar.first + bar.last + 1) / 2f
        assertEquals(
            expectedCentre,
            actualCentre,
            1.5f,
            "the ink bar centre $actualCentre is not on tab $index (expected $expectedCentre): $where ($bar)",
        )
        // 横条必须整个落在自己的格子里（不许跨到隔壁页签）。
        assertTrue(
            bar.first >= insetPx + cell * index - 1f,
            "bar left ${bar.first} escaped tab $index (left ${insetPx + cell * index}): $where",
        )
        assertTrue(
            bar.last + 1 <= insetPx + cell * (index + 1) + 1f,
            "bar right ${bar.last + 1} escaped tab $index: $where",
        )
    }

    @Test
    fun theBarSitsOnTheFirstTabAtDensityTwo() =
        assertBarOnTab(index = 0, count = 4, density = 2f)

    @Test
    fun theBarSitsOnTheLastTabAtDensityTwo() =
        assertBarOnTab(index = 3, count = 4, density = 2f)

    /**
     * 缺陷 2 的像素级反证：旧实现的弹簧 target 由测量宽度算出，量到宽度时会把横条从
     * x≈0 拉一遍。这里逐帧比对——横条从第一次出现起就必须一动不动（测量 / 重组 / 动画
     * 时钟都不该动它）。
     */
    @Test
    fun theBarDoesNotSlideOnceTheWidthIsMeasured() {
        val density = 2f
        val count = 4
        val selected = modelOf(count).tabs[2].page
        val frames = withScene(density, content = { Host(count, selected) }) { scene ->
            scene.renderFrames(16)
        }
        val measured = frames.mapIndexedNotNull { i, image ->
            barBounds(image, density)?.let { i to it }
        }
        assertTrue(measured.isNotEmpty(), "the ink bar was never drawn")
        val first = measured.first().second
        for ((frameIndex, bounds) in measured) {
            assertEquals(first.first, bounds.first, "bar moved on frame $frameIndex ($bounds vs $first)")
            assertEquals(first.last, bounds.last, "bar resized on frame $frameIndex ($bounds vs $first)")
        }
    }

    /**
     * 缺陷 1 + 3 的反证：旧实现用**外框** / count 当格宽，还把 px 当 dp（`.offset` 又乘
     * 一次 density）。density 2 时末页签的横条落在 1120 px 处，而右栏只有 672 px —— 整条
     * 横条被画到栏外，这就是用户截图里「横条不见了」的样子。
     */
    @Test
    fun theOldDensityScaledPositionWouldBeOutsideTheRail() {
        val density = 2f
        val count = 4
        val index = count - 1
        val image = frame(density, count = count, selected = modelOf(count).tabs[index].page)
        val bar = assertNotNull(barBounds(image, density), "no ink bar drawn")
        // 反推标签宽度：新横条宽 = 标签宽 + 12 dp。
        val labelPx = (bar.last + 1 - bar.first) - FmTabInk.BarGap.value * density
        val outerPx = railDp * density
        val oldCell = outerPx / count
        val oldTargetPx = oldCell * index + (oldCell - labelPx) / 2f - 6f * density
        val oldDrawnLeftPx = density * (oldTargetPx + 10f)
        assertTrue(
            oldDrawnLeftPx > outerPx,
            "the old px-as-dp formula drew the last bar at $oldDrawnLeftPx px, " +
                "outside the $outerPx px rail",
        )
        assertTrue(bar.first < outerPx, "the bar must be inside the rail")
    }

    /**
     * 命中分区来自 Row 的 weight，与横条位置无关；但两边共用同一套格子，所以顺手证明
     * 「点第 i 格选中的就是第 i 页」——防止修指示条时顺手改了格子的划分。
     */
    @Test
    fun tappingATabSelectsThatPage() {
        val density = 2f
        val count = 4
        val model = modelOf(count)
        val track = FmTabInk.geometry(railDp * density, count, density)
        val insetPx = FmTabInk.Inset.value * density
        for (start in 0 until count) {
            var picked: InspectorPage? = null
            var selected by mutableStateOf(model.tabs[start].page)
            withScene(
                density,
                content = {
                    Host(count, selected) {
                        picked = it
                        selected = it
                    }
                },
            ) { scene ->
                scene.renderFrames(4)
                for (target in 0 until count) {
                    if (target == start) continue
                    picked = null
                    val cx = insetPx + track.cellWidthPx * (target + 0.5f)
                    val cy = 20f * density
                    scene.sendPointerEvent(PointerEventType.Press, Offset(cx, cy))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(cx, cy))
                    scene.render()
                    assertEquals(
                        model.tabs[target].page,
                        picked,
                        "tap tab $target (start=$start)",
                    )
                }
            }
        }
    }
}
