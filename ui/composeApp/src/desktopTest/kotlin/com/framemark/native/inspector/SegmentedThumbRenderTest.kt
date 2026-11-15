package com.framemark.native.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.framemark.native.theme.FmPalettes
import com.framemark.native.theme.PaletteId
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `.seg` 滑块的**像素级**验收（2026-11-11「位置对应不上」）。
 *
 * 为什么还要量像素：几何函数只证明算术对，证明不了「画出来的白条真的压在选中格
 * 上」—— 中间还隔着 `onGloballyPositioned` 量的是哪一段宽度、`.offset` 的参照系、
 * `.padding(2.dp)` 的先后顺序。这三处正是原缺陷的所在。
 *
 * 外框取 304 dp → 内容区 300 px（density 1）/ 600 px（density 2）/ 900 px（density 3），
 * 都能被 2/3/4/5 整除，所以每一格的边界都是整数像素。
 *
 * 量法：白条是 `surface`（亮色主题 #FFFFFF）自绘的圆角矩形，只有它的**填充**是纯白。
 * 它自己的 1 dp 阴影会把填充边缘按亚像素位置吃掉 2 dp 上下（实测 density 1 是 2 px、
 * density 3 是 5~7 px，且左右可能差 2 px）。所以：
 *  - **中心**与阴影基本无关（腐蚀大致对称），是位置的主判据；
 *  - 尺寸用「填充 + 两侧腐蚀」还原，腐蚀量由滑块高度（设计稿 28 dp / 触控 40 dp）反推；
 *  - 容差 [tolerancePx] / [edgeTolerancePx] 是这套阴影量法的物理下限（实测修好后中心
 *    偏差 ≤1 px，density 3 时 = 0.33 dp）。float 层面的精确性由 [FmSegmentedGeometryTest]
 *    负责；这一份负责「控件真的把它画在那一格」——旧实现在此偏 2~8 px，见
 *    `theOldDriftedPositionIsGone`。
 */
class SegmentedThumbRenderTest {

    /** 外框宽度：内容区 = 304 − 2×2 = 300 px（density 1）。 */
    private val outerWidthDp = 304f

    private val desktopThumbDp = 28f
    private val touchThumbDp = 40f

    /**
     * 阴影量法的不确定度上限（中心/左缘）。实测修好后偏差 ≤1 px；给到 2 px 是为了吸收
     * 白条自身阴影边缘的亚像素不对称（density 3 时实测左右腐蚀量可差 2 px）。
     */
    private val tolerancePx = 2.0f

    /** 右缘/宽度：两条边各自受阴影亚像素影响，再放宽一像素。 */
    private val edgeTolerancePx = 3.0f

    /** 白条填充（纯白像素）的边界，闭区间。 */
    private data class Fill(val left: Int, val right: Int, val top: Int, val bottom: Int) {
        val centerX: Float get() = (left + right + 1) / 2f
        val centerY: Float get() = (top + bottom + 1) / 2f
        val height: Float get() = (bottom - top + 1).toFloat()
        override fun toString(): String = "x=$left..$right y=$top..$bottom"
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun <T> withScene(
        density: Float,
        heightDp: Float = 60f,
        content: @Composable () -> Unit,
        block: (ImageComposeScene) -> T,
    ): T {
        val scene = ImageComposeScene(
            width = (outerWidthDp * density).roundToInt() + 16,
            height = (heightDp * density).roundToInt(),
            density = Density(density),
            content = content,
        )
        // 必须无条件关闭：漏一个 scene 会留下非守护线程，把测试 worker 挂住、锁死
        // build/test-results 下的 output.bin，下一次 Gradle 跑就删不掉。
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

    /** 渲染若干帧，返回最后一帧。 */
    private fun frame(
        density: Float,
        heightDp: Float = 60f,
        frames: Int = 4,
        content: @Composable () -> Unit,
    ): BufferedImage = withScene(density, heightDp, content) { scene ->
        scene.renderFrames(frames).last()
    }

    /** 纯白填充的包围盒；一个纯白像素都没有就是 null（= 没画白条）。 */
    private fun fill(image: BufferedImage): Fill? {
        val surfaceRgb = FmPalettes.of(PaletteId.Forest, dark = false).surface.toArgb() and 0xFFFFFF
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if ((image.getRGB(x, y) and 0xFFFFFF) == surfaceRgb) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        return Fill(minX, maxX, minY, maxY)
    }

    /** 阴影把填充边缘压掉的像素数（由高度反推；上下两条长边最稳，所以取高度）。 */
    private fun erosion(fill: Fill, thumbDp: Float, density: Float): Float =
        (thumbDp * density - fill.height) / 2f

    @Composable
    private fun Host(
        options: List<ControlOption>,
        selected: String,
        touch: Boolean,
        onSelect: (String) -> Unit = {},
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF00FF00))) {
            CuratedSegmented(
                options = options,
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier.width(outerWidthDp.dp),
                touch = touch,
            )
        }
    }

    private fun optionsOf(count: Int) = (0 until count).map { ControlOption("v$it", "选项$it") }

    private fun insetPx(density: Float) = FmSegmented.Inset.value * density

    private fun cellPx(density: Float, count: Int) =
        (outerWidthDp * density - 2f * insetPx(density)) / count

    /** 选中格左缘的绝对 px（相对控件外框左缘）。 */
    private fun cellLeftPx(density: Float, count: Int, index: Int) =
        insetPx(density) + cellPx(density, count) * index

    /**
     * 旧实现的落点（内容宽度又被减了一次 4 dp，外加 `2/density − 2` dp）：
     * `2 + ((content − 4·density)/count) · index`。只给 `theOldDriftedPositionIsGone` 用。
     */
    private fun oldDriftedLeftPx(density: Float, count: Int, index: Int): Float {
        val content = outerWidthDp * density - 2f * insetPx(density)
        val oldCell = (content - 4f * density) / count
        return 2f + oldCell * index
    }

    private fun assertThumbMatchesCell(
        image: BufferedImage,
        density: Float,
        count: Int,
        index: Int,
        thumbDp: Float,
        touch: Boolean,
    ): Float {
        val where = "density=$density count=$count index=$index touch=$touch"
        val thumb = assertNotNull(fill(image), "no white thumb drawn: $where")
        val e = erosion(thumb, thumbDp, density)
        val plateLeft = thumb.left - e
        val plateRight = thumb.right + 1 + e
        val plateTop = thumb.top - e
        val plateBottom = thumb.bottom + 1 + e
        val cellLeft = cellLeftPx(density, count, index)
        val cell = cellPx(density, count)
        // 1) 位置：中心必须压在选中格中心上。
        assertTrue(
            abs(thumb.centerX - (cellLeft + cell / 2f)) <= tolerancePx,
            "thumb centre ${thumb.centerX} != selected cell centre ${cellLeft + cell / 2f}: $where ($thumb)",
        )
        // 2) 左缘：滑块左缘 = 选中格左缘（旧实现第 i 格偏 2~8 px，这里只有阴影的
        //    亚像素噪声，所以左右两条边用比位置稍宽的容差）。
        assertTrue(
            abs(plateLeft - cellLeft) <= tolerancePx,
            "thumb left $plateLeft != cell left $cellLeft: $where ($thumb)",
        )
        // 3) 右缘：宽度不许短一截，末格要顶到内容区右缘。
        assertTrue(
            abs(plateRight - (cellLeft + cell)) <= edgeTolerancePx,
            "thumb right $plateRight != cell right ${cellLeft + cell}: $where ($thumb)",
        )
        assertTrue(
            abs((plateRight - plateLeft) - cell) <= edgeTolerancePx,
            "thumb width ${plateRight - plateLeft} != cell width $cell: $where ($thumb)",
        )
        // 4) 竖向没被改：还是设计稿高度、还是贴在内容区里（顶距 = 2 dp 内边距 + 2 dp）。
        assertTrue(
            abs((plateBottom - plateTop) - thumbDp * density) <= tolerancePx,
            "thumb height ${plateBottom - plateTop} != ${thumbDp * density}px: $where ($thumb)",
        )
        val expectedCenterY = (FmSegmented.Inset.value + 2f + thumbDp / 2f) * density
        assertTrue(
            abs(thumb.centerY - expectedCenterY) <= tolerancePx,
            "thumb centre y ${thumb.centerY} != $expectedCenterY: $where ($thumb)",
        )
        return plateLeft
    }

    @Test
    fun theThumbSitsExactlyOnTheSelectedCell() {
        for (density in listOf(1f, 2f, 3f)) {
            for (count in 2..5) {
                val options = optionsOf(count)
                for (index in 0 until count) {
                    val image = frame(density) {
                        Host(options, selected = options[index].value, touch = false)
                    }
                    assertThumbMatchesCell(image, density, count, index, desktopThumbDp, false)
                }
            }
        }
    }

    @Test
    fun theTouchThumbUsesTheSameCells() {
        // 手机底板（`touch = true`，40 dp 高）走同一个几何：只有高度不同。
        for (density in listOf(2f, 3f)) {
            for (count in 2..5) {
                val options = optionsOf(count)
                val index = count - 1
                val image = frame(density, heightDp = 80f) {
                    Host(options, selected = options[index].value, touch = true)
                }
                assertThumbMatchesCell(image, density, count, index, touchThumbDp, true)
            }
        }
    }

    /**
     * 这条是这份测试的“反证”：把白条量出来的位置和**旧实现**的落点比，必须差得远
     * （旧实现第 i 格偏 2~8 px）。这样一旦有人把参照系改回去，测试会明确指出来。
     */
    @Test
    fun theOldDriftedPositionIsGone() {
        val density = 2f
        for (count in 3..5) {
            val options = optionsOf(count)
            // 最后一格最明显：旧实现偏 4*index/count + 1 dp（5 格时 ≈ 4.2 dp = 8.4 px）。
            val index = count - 1
            val image = frame(density) { Host(options, selected = options[index].value, touch = false) }
            val thumb = assertNotNull(fill(image), "no white thumb drawn (count=$count)")
            val e = erosion(thumb, desktopThumbDp, density)
            val measuredLeft = thumb.left - e
            val oldLeft = oldDriftedLeftPx(density, count, index)
            assertTrue(
                abs(measuredLeft - oldLeft) > 2.5f,
                "the thumb still lands where the old double-subtracted geometry put it " +
                    "($measuredLeft vs $oldLeft, count=$count index=$index)",
            )
            assertTrue(
                abs(measuredLeft - cellLeftPx(density, count, index)) <= tolerancePx,
                "the thumb must sit on the selected cell (count=$count index=$index, $thumb)",
            )
        }
    }

    @Test
    fun theThumbDoesNotMoveOnceTheWidthIsMeasured() {
        // 旧实现的弹簧 target 由测量宽度算出，量到宽度时会把白条从 x≈0 拉一遍。这里
        // 逐帧比对：白条从第一次出现起就必须一动不动（测量/重组/动画时钟都不该动它）。
        val density = 2f
        val count = 5
        val options = optionsOf(count)
        val frames = withScene(density, content = {
            Host(options, selected = options[3].value, touch = false)
        }) { scene -> scene.renderFrames(16) }
        val fills = frames.mapIndexedNotNull { i, image -> fill(image)?.let { i to it } }
        assertTrue(fills.isNotEmpty(), "the thumb was never drawn")
        val first = fills.first().second
        for ((frameIndex, f) in fills) {
            assertEquals(first.left, f.left, "thumb moved on frame $frameIndex ($f vs $first)")
            assertEquals(first.right, f.right, "thumb resized on frame $frameIndex ($f vs $first)")
        }
        assertThumbMatchesCell(frames.last(), density, count, 3, desktopThumbDp, false)
    }

    @Test
    fun tappingAnOptionSelectsThatOption() {
        // 白条画错格时最怕的次生问题：点到的格子 ≠ 生效的格子。逐个点每一格的中心。
        // 命中分区来自 Row 的 weight，与白条位置无关；这条防的是「按错格」回归。
        for (density in listOf(1f, 2f, 3f)) {
            for (count in 2..5) {
                val options = optionsOf(count)
                for (startIndex in 0 until count) {
                    var picked: String? = null
                    var selected by mutableStateOf(options[startIndex].value)
                    withScene(density, content = {
                        Host(options, selected = selected, touch = false) { value ->
                            picked = value
                            selected = value
                        }
                    }) { scene ->
                        scene.renderFrames(4)
                        for (target in 0 until count) {
                            if (target == startIndex) continue
                            picked = null
                            val cx = cellLeftPx(density, count, target) + cellPx(density, count) / 2f
                            val cy = (FmSegmented.Inset.value + desktopThumbDp / 2f) * density
                            scene.sendPointerEvent(PointerEventType.Press, Offset(cx, cy))
                            scene.sendPointerEvent(PointerEventType.Release, Offset(cx, cy))
                            scene.render()
                            assertEquals(
                                options[target].value,
                                picked,
                                "tap cell $target (density=$density count=$count start=$startIndex)",
                            )
                        }
                    }
                }
            }
        }
    }
}
