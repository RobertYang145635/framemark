package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.framemark.native.workflow.ImageRect
import java.awt.image.BufferedImage
import java.nio.file.Paths
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 缺陷 1 的像素级验收（2026-11-11）：画布上的水印九宫格**只画九个 ~10 dp 的圆点**。
 *
 * 上一版每一格都画一个铺满格子的圆角块（1 dp 白边 + 透明底）并叠一个箭头字形，照片
 * 被切成 3 × 3 的九拼，看起来就是「水印被复制了九次」（设备截图：
 * `E:\FrameMark\execution-ui-v2-20261106\evidence\verify-rc14\_wm-before.png`）。
 *
 * 这一份测试做两件事：
 *  1. 真的把这一层渲染到一张纯色（绿）底上，逐像素量：每一格只有中心一个 ~10 dp 的
 *     圆点；格子的四边和四角一个像素都不许变（没有格子边框、没有铺满格子的底色），
 *     九格以外也没有任何东西（没有图片内容）；
 *  2. 直接读 `PhoneAnchorLayer.kt` 的源码：参数表里没有 image / bitmap / painter，
 *     文件里也不出现任何图片 API —— 这一层在类型上就拿不到水印图。
 */
class PhoneAnchorLayerRenderTest {

    private val densityValue = 3.5f
    private val density = Density(densityValue)
    private val stage = IntSize(1200, 1200)
    private val geo = PreviewGeo(w = 1200, h = 1200, photoRect = ImageRect(0f, 0f, 1200f, 1200f))
    private val background = Color(0xFF00FF00)

    private fun dotPx(): Float = WATERMARK_ANCHOR_DOT_DP * densityValue

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(anchor: Int): BufferedImage {
        val scene = ImageComposeScene(width = stage.width, height = stage.height, density = density) {
            AnchorLayerOnGreen(geo = geo, stage = stage, density = density, anchor = anchor)
        }
        val png = scene.render().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
        scene.close()
        assertNotNull(png, "the anchor layer produced no PNG bytes")
        return javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png))
    }

    @Composable
    private fun AnchorLayerOnGreen(geo: PreviewGeo, stage: IntSize, density: Density, anchor: Int) {
        Box(modifier = Modifier.fillMaxSize().background(background)) {
            PhoneWatermarkAnchorLayer(
                geo = geo,
                stage = stage,
                density = density,
                anchor = anchor,
                onPick = {},
            )
        }
    }

    /** 这个像素还是底色吗（纯绿 `#00FF00`，容许一点抗锯齿抖动）。 */
    private fun isBackground(image: BufferedImage, x: Int, y: Int): Boolean {
        val rgb = image.getRGB(x, y)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return r <= 8 && g >= 247 && b <= 8
    }

    /**
     * 这个像素是**实心点**（`primary` #2F75E5）而不是别的：
     *  - 细环的白 `#FFFFFF` 不算（三个通道都很亮）；
     *  - 未选中格里 18 % 黑的底仍是绿相（红蓝通道都接近 0），也不算。
     */
    private fun isFilledDot(image: BufferedImage, x: Int, y: Int): Boolean {
        val rgb = image.getRGB(x, y)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val white = r > 200 && g > 200 && b > 200
        val greenish = r < 40 && b < 40
        return !white && !greenish
    }

    private data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun cells(box: AnchorLayerBox, image: BufferedImage): List<Region> {
        val out = mutableListOf<Region>()
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val left = (box.leftPx + col * box.cellWidthPx).toInt()
                val top = (box.topPx + row * box.cellHeightPx).toInt()
                val right = (box.leftPx + (col + 1) * box.cellWidthPx).toInt().coerceAtMost(image.width)
                val bottom = (box.topPx + (row + 1) * box.cellHeightPx).toInt().coerceAtMost(image.height)
                out += Region(left.coerceAtLeast(0), top.coerceAtLeast(0), right, bottom)
            }
        }
        return out
    }

    @Test
    fun theLayerDrawsNineSmallDotsAndNoImageOrCellChrome() {
        val anchor = 4
        val image = render(anchor)
        val box = assertNotNull(watermarkAnchorLayerBox(geo, stage, 44f * densityValue))
        val regions = cells(box, image)
        assertEquals(9, regions.size)

        val dotArea = Math.PI.toFloat() * (dotPx() / 2f) * (dotPx() / 2f)
        var cellsWithADot = 0
        var totalChanged = 0
        regions.forEachIndexed { index, region ->
            var changed = 0
            var filled = 0
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            var nearEdge = 0
            for (y in region.top until region.bottom) {
                for (x in region.left until region.right) {
                    if (isBackground(image, x, y)) continue
                    changed++
                    totalChanged++
                    if (isFilledDot(image, x, y)) filled++
                    minX = minOf(minX, x); maxX = maxOf(maxX, x)
                    minY = minOf(minY, y); maxY = maxOf(maxY, y)
                    // 格子的边框 / 铺满格子的底色会在这里留下像素；圆点在正中，碰不到。
                    if (
                        x - region.left < 6 || region.right - x < 6 ||
                        y - region.top < 6 || region.bottom - y < 6
                    ) {
                        nearEdge++
                    }
                }
            }
            assertTrue(changed > 0, "cell $index 必须有可见热点")
            assertEquals(0, nearEdge, "cell $index 的格子边框 / 底色必须没有：$nearEdge 个边缘像素被画了")
            cellsWithADot++
            // 可见的是一个小圆点：外接框 ≈ 10 dp，远小于 44 dp 的格子。
            val widthPx = (maxX - minX + 1).toFloat()
            val heightPx = (maxY - minY + 1).toFloat()
            assertTrue(
                abs(widthPx - dotPx()) <= 2f * densityValue,
                "cell $index 的热点宽 $widthPx px，应该是 ~${dotPx()} px（10 dp）",
            )
            assertTrue(
                abs(heightPx - dotPx()) <= 2f * densityValue,
                "cell $index 的热点高 $heightPx px，应该是 ~${dotPx()} px（10 dp）",
            )
            // 可见面积 ≪ 格子面积（10 dp 的点在 400 px 的格子里不到 0.3 %）。
            val cellArea = (region.right - region.left).toFloat() * (region.bottom - region.top).toFloat()
            assertTrue(changed < cellArea * 0.01f, "cell $index 画了 $changed px（格子 $cellArea px）")
            // 只有当前格是实心点；其余是细环。
            if (index == anchor) {
                assertTrue(filled > dotArea * 0.55f, "当前格必须是实心点：$filled/$dotArea")
            } else {
                assertTrue(filled < dotArea * 0.3f, "cell $index 不该是实心点：$filled/$dotArea")
            }
        }
        assertEquals(9, cellsWithADot)
        // 九格以外一个像素都不许变：没有图片内容被画到格子上。
        assertTrue(
            totalChanged < 9 * dotArea * 2f,
            "九格加起来的可见像素 $totalChanged 太多（圆点面积约 $dotArea px²）",
        )
        // 底色以外的区域（层外面）本来就是底色：抽几个点确认。
        assertTrue(isBackground(image, 2, 2), "层的左上角外面不该有东西")
        assertTrue(isBackground(image, image.width - 3, image.height - 3), "层的右下角外面不该有东西")
    }

    @Test
    fun onlyTheCurrentCellIsFilled() {
        listOf(0, 4, 8).forEach { anchor ->
            val image = render(anchor)
            val box = assertNotNull(watermarkAnchorLayerBox(geo, stage, 44f * densityValue))
            val regions = cells(box, image)
            val filledCells = regions.mapIndexed { index, region ->
                var filled = 0
                for (y in region.top until region.bottom) {
                    for (x in region.left until region.right) {
                        if (isFilledDot(image, x, y)) filled++
                    }
                }
                index to filled
            }.filter { (_, filled) -> filled > dotPx() * dotPx() * 0.4f }
            assertEquals(listOf(anchor), filledCells.map { it.first }, "只有当前格是实心的（anchor=$anchor）")
        }
    }

    // -----------------------------------------------------------------------
    // 「拿不到图片」：源码级断言
    // -----------------------------------------------------------------------

    @Test
    fun theLayerComposableTakesNoImageParameterAndDrawsNoBitmap() {
        val relative = "src/commonMain/kotlin/com/framemark/native/shell/phone/PhoneAnchorLayer.kt"
        val file = listOf(
            Paths.get(relative),
            Paths.get("composeApp", relative),
            Paths.get("ui", "composeApp", relative),
        ).map { it.toAbsolutePath().toFile() }.firstOrNull { it.isFile }
        assertNotNull(file, "PhoneAnchorLayer.kt not found from ${Paths.get("").toAbsolutePath()}")

        val text = file.readText()
        val signature = text
            .substringAfterLast("fun BoxScope.PhoneWatermarkAnchorLayer(")
            .substringBefore("\n) {")
        assertTrue(signature.contains("anchor: Int"), "签名里应该只有当前 anchor：$signature")
        listOf("image", "bitmap", "painter", "drawable").forEach { token ->
            assertFalse(
                signature.contains(token, ignoreCase = true),
                "锚点层的参数表里不该有 $token：$signature",
            )
        }
        // 这一层的文件里根本不出现图片 API：不是「画了但很小」，是拿不到。
        listOf("ImageBitmap", "Bitmap", "painterResource", "Image(", "Painter", "drawImage", "decodeToImageBitmap")
            .forEach { token ->
                assertFalse(text.contains(token), "锚点层不该碰图片 API，却出现了 $token")
            }
        // 可见元素是圆点：CircleShape 有，Text( 没有（旧版的 ↖ ↑ ↗ 箭头字形已删除）。
        assertTrue(text.contains("CircleShape"), "圆点要画成圆")
        assertFalse(text.contains("Text("), "锚点层不再画任何文字")
        assertFalse(text.contains("border(1.dp"), "格子边框（铺满一格的 1 dp 线）必须没有")
    }
}
