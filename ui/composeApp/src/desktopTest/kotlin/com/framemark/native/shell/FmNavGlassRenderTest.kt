package com.framemark.native.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.framemark.native.shell.phone.PhoneToolBar
import com.framemark.native.shell.phone.PresetTrailingDp
import com.framemark.native.theme.FrameMarkTheme
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 浮起玻璃胶囊的**视觉**像素证据：把活的工具条（[PhoneToolBar]）叠在一张红蓝条纹
 * 「照片」上无头渲染，然后量像素。
 *
 * ## 这一组测试到底在保证什么
 *
 * 「采集背后的内容 → 糊掉 → 叠在胶囊里」这条路在 **Skiko 桌面渲染**上走不通：实测
 * `GraphicsLayer` + `drawLayer` 画出来的东西，无论套 `Modifier.blur` 还是给 layer 自己
 * 设 `renderEffect`，像素都和没糊时一模一样（四条路径逐一试过，见
 * `fmBackdropBlurSupported` 的实现说明）。而**没糊的拷贝**在 70 % 洗色下会留下 30 % 的
 * 错位重影 —— 比没有玻璃更难看。所以：
 *
 *  - 桌面：胶囊判定为 `TintOnly`，画不透明近白填充；
 *  - 「有采集层、但平台糊不了」时**绝不许**画那份拷贝 —— 这一条用真实渲染钉住
 *    （[theCapsuleNeverPaintsAnUnblurredGhost]）；
 *  - 图标文字永远画在最上层，不参与任何模糊（[theGlyphsAndLabelsStayCrispOnTopOfTheGlass]）。
 *
 * ## 真机（Android API 31+）怎么验
 *
 * 采集路径在 Android 上是 `RenderEffect`，与桌面不是同一个实现，必须真机确认：
 *  1. 临时把 `fmBackdropBlurSupported()` 的桌面实现改成 `true`（或直接在 Android 上
 *     把工具条放在舞台上方，参见报告里的「浮到舞台」补丁）；
 *  2. 打开编辑器 → 需要胶囊后面真的是照片（不是面板表面）；
 *  3. 拖动 / 缩放照片：胶囊里应是**糊成一片、跟着动的照片**；若看到**清晰的错位重影**，
 *     说明这份 `drawLayer` 拷贝在 Android 上也不吃模糊 —— 保持 `false`，别上真机。
 *
 * PNG 落在 `ui/composeApp/build/nav-glass/`，真机前先看这张图。
 */
class FmNavGlassRenderTest {

    private val densityValue = 2f
    private val density = Density(densityValue)
    private val widthDp = 390
    private val heightDp = 240
    private val widthPx = (widthDp * densityValue).toInt()
    private val heightPx = (heightDp * densityValue).toInt()

    /** 槽位高度：手机上的常规值（`PhoneEditor.kt` 的 `toolBarH`）。 */
    private val slotDp = 62

    /** 条带宽度 2 dp：高频图案，任何真实模糊都会把它抹掉。 */
    private val stripeDp = 2

    private val tools = EditorTool.entries

    /** 选中 调整，所以 文字 / Logo / 图片水印 三格是「纯玻璃」。 */
    private val selectedTool = EditorTool.Params

    private val capsuleWidthDp = widthDp - 2 * FmNavCapsule.horizontalInset.value

    private val capsuleHeightDp = FmNavCapsule.capsuleHeightDp(slotDp.dp).value

    /** 胶囊顶边的屏幕 y（dp）：槽位贴底，胶囊 = 槽位 − 底部内缩。 */
    private val capsuleTopDp = heightDp - slotDp

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(withBackdrop: Boolean): BufferedImage {
        val scene = ImageComposeScene(width = widthPx, height = heightPx, density = density) {
            FrameMarkTheme {
                // 采集层必须和胶囊在同一个组合里（`rememberGraphicsLayer`）。
                val backdrop = rememberFmNavBackdrop()
                val source = if (withBackdrop) backdrop.source() else Modifier
                Box(modifier = Modifier.fillMaxSize()) {
                    // 「照片」：红蓝竖条。它既是背景，也是玻璃要透出来的内容。
                    Box(
                        modifier = source
                            .fillMaxSize()
                            .background(Color.Black),
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val stripe = stripeDp.dp.toPx()
                            var x = 0f
                            var red = true
                            while (x < size.width) {
                                drawRect(
                                    color = if (red) Color.Red else Color.Blue,
                                    topLeft = Offset(x, 0f),
                                    size = Size(stripe, size.height),
                                )
                                red = !red
                                x += stripe
                            }
                        }
                    }
                    PhoneToolBar(
                        height = slotDp.dp,
                        tool = selectedTool,
                        availableTools = tools,
                        modifiedOf = { false },
                        onSelect = {},
                        onOpenPresets = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                        backdrop = if (withBackdrop) backdrop else null,
                    )
                }
            }
        }
        // 第一帧量位置，第二帧才是稳定画面（采集层的屏幕偏移由 onGloballyPositioned 写入）。
        scene.render()
        val image = scene.render()
        val png = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
        scene.close()
        assertTrue(png != null && png.isNotEmpty(), "no PNG bytes (backdrop=$withBackdrop)")
        val dir = java.nio.file.Paths.get("build", "nav-glass").toAbsolutePath()
        java.nio.file.Files.createDirectories(dir)
        java.nio.file.Files.write(
            dir.resolve(if (withBackdrop) "capsule-with-backdrop.png" else "capsule-tint-only.png"),
            png!!,
        )
        return javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png))
    }

    private fun BufferedImage.rgb(x: Int, y: Int): Triple<Int, Int, Int> {
        val argb = getRGB(x, y)
        return Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }

    private fun BufferedImage.luminance(x: Int, y: Int): Int {
        val (r, g, b) = rgb(x, y)
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** 一行像素的每通道均值。 */
    private fun BufferedImage.meanRgb(y: Int, xFrom: Int, xTo: Int): Triple<Int, Int, Int> {
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (x in xFrom until xTo) {
            val c = rgb(x, y)
            r += c.first
            g += c.second
            b += c.third
            n++
        }
        return Triple(r / n, g / n, b / n)
    }

    /** 一行像素的每通道极差（max − min）。 */
    private fun BufferedImage.rangeRgb(y: Int, xFrom: Int, xTo: Int): Triple<Int, Int, Int> {
        var rMin = 255
        var rMax = 0
        var gMin = 255
        var gMax = 0
        var bMin = 255
        var bMax = 0
        for (x in xFrom until xTo) {
            val c = rgb(x, y)
            if (c.first < rMin) rMin = c.first
            if (c.first > rMax) rMax = c.first
            if (c.second < gMin) gMin = c.second
            if (c.second > gMax) gMax = c.second
            if (c.third < bMin) bMin = c.third
            if (c.third > bMax) bMax = c.third
        }
        return Triple(rMax - rMin, gMax - gMin, bMax - bMin)
    }

    /** 一段像素（多行）里的最暗亮度 —— 用来判断「图标文字还在」。 */
    private fun BufferedImage.darkestIn(yFrom: Int, yTo: Int, xFrom: Int, xTo: Int): Int {
        var min = 255
        for (y in yFrom until yTo) {
            for (x in xFrom until xTo) {
                val l = luminance(x, y)
                if (l < min) min = l
            }
        }
        return min
    }

    /**
     * 一块「纯玻璃」采样区（px）：未选中的几格，横向各留 10 dp 躲开 glyph；
     * 纵向取胶囊顶端往下 5 dp 的那一带，正好在内容之上。
     */
    private fun glassSample(): Triple<Int, Int, Int> {
        val fromCell = 2
        val toCell = tools.lastIndex
        val trailing = PresetTrailingDp
        val count = tools.size
        val cellWidth = FmNavCapsule.cellWidthDp(capsuleWidthDp.dp, count, trailing)
        val leftDp = FmNavCapsule.horizontalInset.value +
            FmNavCapsule.cellLeftDp(fromCell, capsuleWidthDp.dp, count, trailing).value + 10f
        val rightDp = FmNavCapsule.horizontalInset.value +
            FmNavCapsule.cellLeftDp(toCell, capsuleWidthDp.dp, count, trailing).value +
            cellWidth.value - 10f
        val y = ((capsuleTopDp + 5f) * densityValue).toInt()
        return Triple(y, (leftDp * densityValue).toInt(), (rightDp * densityValue).toInt())
    }

    /**
     * **最重要的一条**：平台糊不了的时候，胶囊里不能再画一份没糊的拷贝。
     *
     * 测试里真的给了采集层（`backdrop` 非空），但桌面实现判定「糊不了」—— 于是胶囊必须
     * 退回近白填充：三个通道几乎相等，条纹一点都不许透进来。要是有人把
     * `FmNavGlass.decide` 的 `blurSupported` 分支删掉，这一条会立刻变红。
     */
    @Test
    fun theCapsuleNeverPaintsAnUnblurredGhost() {
        val withBackdrop = render(withBackdrop = true)
        val without = render(withBackdrop = false)
        val (glassY, from, to) = glassSample()

        val fill = withBackdrop.meanRgb(glassY, from, to)
        assertTrue(
            fill.second > 200,
            "an unblurrable backdrop must fall back to the light fill, got $fill",
        )
        assertTrue(
            abs(fill.first - fill.second) < 20 && abs(fill.third - fill.second) < 20,
            "the fallback must stay neutral — a red/blue bleed here means an unblurred copy " +
                "was painted (ghost), got $fill",
        )
        // 而且和「根本没给采集层」画出来的一致（±8，圆角抗锯齿的余量）。
        val baseline = without.meanRgb(glassY, from, to)
        assertTrue(
            abs(fill.first - baseline.first) <= 8 &&
                abs(fill.second - baseline.second) <= 8 &&
                abs(fill.third - baseline.third) <= 8,
            "with/without an unblurrable backdrop must render the same fill: $fill vs $baseline",
        )
        // 顺带证明采样区外面确实是强对比条纹（否则上面的「中性」可能只是采错了地方）。
        val source = withBackdrop.rangeRgb((60 * densityValue).toInt(), from, to)
        assertTrue(
            source.first > 200 && source.third > 200,
            "the stripe source itself must be high contrast, got $source",
        )
    }

    /** 图标与标签画在最上层，不参与任何模糊 / 洗色。 */
    @Test
    fun theGlyphsAndLabelsStayCrispOnTopOfTheGlass() {
        val image = render(withBackdrop = true)
        val (_, from, to) = glassSample()
        val contentTop = ((capsuleTopDp + 4f) * densityValue).toInt()
        val contentBottom = ((capsuleTopDp + capsuleHeightDp - 4f) * densityValue).toInt()
        val darkest = image.darkestIn(contentTop, contentBottom, from, to)
        assertTrue(
            darkest < 90,
            "the label/glyph ink must stay crisp on top of the glass (a blurred row has no dark " +
                "pixels); darkest=$darkest",
        )
    }

    /** 没有采集层时的填充：近白、中性、不是「没画」。 */
    @Test
    fun withoutABackdropTheCapsuleFallsBackToTheTranslucentFill() {
        val tintOnly = render(withBackdrop = false)
        val (glassY, from, to) = glassSample()
        val fill = tintOnly.meanRgb(glassY, from, to)
        assertTrue(fill.second > 200, "the tint-only fallback must be the light inset fill, got $fill")
        assertTrue(
            abs(fill.first - fill.second) < 20 && abs(fill.third - fill.second) < 20,
            "the fallback must stay a neutral fill, got $fill",
        )
    }
}
