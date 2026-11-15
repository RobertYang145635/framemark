package com.framemark.native.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * 外观面板的像素级验收（配色方案 §8）。
 *
 * 面板是「四张卡并排比较颜色」的界面，几何与文案可以靠代码读，但「四张卡是不是
 * 真的各用自己的表面色、选中态是不是真的有描边」只有画出来才算数。这里把面板渲染
 * 成位图，用面板自己的颜色定位每张卡，再检查：
 *  - 深色模式下四张卡的表面色都出现（浅色下 graphite 与 indigo 都是纯白，无法区分，
 *    所以「四色可分」这条只在深色模式断言）；
 *  - 选中的那张卡四周是它自己的 `primary` 描边，其余卡是 `outline`；
 *  - 点第二张卡（石墨琥珀）会真的切换 `controller.settings.palette`；
 *  - 面板在浅色 / 深色两种外观下都能渲染，且选中卡描边跟着当前配色走。
 */
class AppearancePanelRenderTest {

    private fun controller(): AppearanceController = AppearanceController()

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(
        controller: AppearanceController,
        dark: Boolean,
        width: Int = 360,
        height: Int = 760,
        interact: (ImageComposeScene) -> Unit = {},
    ): BufferedImage {
        // 面板里的预览卡按 controller 解析出的明暗渲染，所以先把选择设成与 theme 一致，
        // 否则会出现「深色主题里画浅色卡」的假象。
        controller.setMode(if (dark) ThemeMode.Dark else ThemeMode.Light)
        val scene = ImageComposeScene(
            width = width,
            height = height,
            density = Density(1f),
        ) {
            CompositionLocalProvider(LocalAppearance provides controller) {
                FrameMarkTheme(dark = dark, palette = controller.settings.palette) {
                    Box(Modifier.fillMaxSize().background(FmPalette.canvas)) {
                        AppearancePanel(
                            columns = 2,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
        try {
            scene.render()
            interact(scene)
            scene.render()
            val png = scene.render().encodeToData(EncodedImageFormat.PNG)?.bytes
            assertNotNull(png, "the appearance panel produced no PNG bytes")
            return ImageIO.read(ByteArrayInputStream(png))
        } finally {
            scene.close()
        }
    }

    /** 某个 ARGB（忽略 alpha）的像素包围盒 + 质心。 */
    private data class Cluster(val count: Int, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int) {
        val centerX: Float get() = (minX + maxX) / 2f
        val centerY: Float get() = (minY + maxY) / 2f
    }

    private fun cluster(image: BufferedImage, argb: Int): Cluster? {
        var count = 0
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        val want = argb and 0xFFFFFF
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if ((image.getRGB(x, y) and 0xFFFFFF) == want) {
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (count == 0) null else Cluster(count, minX, maxX, minY, maxY)
    }

    private fun rgb(colors: androidx.compose.ui.graphics.Color): Int = colors.toArgb() and 0xFFFFFF

    /**
     * 卡片左缘的描边像素：在卡片顶部向下 20 px 的那一行扫最左侧的卡面像素（卡片
     * 圆角 12 dp，20 px 已经离开圆角；这一行左侧只有 6 dp 内边距露出卡面），它的左边
     * 就是 1~2 dp 描边。不用全局包围盒的 minX——那会被同色的其它控件（明暗段控的
     * 药丸也用当前 `surface`）拉偏。
     */
    private fun borderPixels(image: BufferedImage, surface: Int, card: Cluster): Pair<Int, Int> {
        val y = card.minY + 20
        val want = surface and 0xFFFFFF
        for (x in 1 until image.width) {
            if ((image.getRGB(x, y) and 0xFFFFFF) == want) {
                return (image.getRGB(x - 1, y) and 0xFFFFFF) to (image.getRGB(x - 2, y) and 0xFFFFFF)
            }
        }
        error("no card surface on row y=$y")
    }

    @Test
    fun everyCardUsesItsOwnSurfaceColour() {
        val image = render(controller(), dark = true)
        val clusters = PaletteId.entries.map { id ->
            val surface = rgb(FmPalettes.of(id, dark = true).surface)
            id to assertNotNull(cluster(image, surface), "$id dark surface is not painted on its card")
        }
        clusters.forEach { (id, c) ->
            assertTrue(c.count > 2_000, "$id card surface covers only ${c.count}px")
        }
        // 两列网格：奇数位在左、偶数位在右，下一行比上一行低。用 minY / minX 而不是
        // 质心：明暗段控的选中药丸也用当前配色的 surface，会把该颜色的包围盒往下拉。
        clusters.forEachIndexed { index, (id, c) ->
            if (index >= 2) {
                assertTrue(
                    c.minY > clusters[index - 2].second.minY,
                    "$id card must be below ${clusters[index - 2].first}",
                )
            }
            if (index % 2 == 0 && index + 1 < clusters.size) {
                assertTrue(
                    c.minX < clusters[index + 1].second.minX,
                    "$id card must be left of ${clusters[index + 1].first}",
                )
            }
        }
    }

    @Test
    fun theSelectedCardWearsItsOwnPrimaryBorder() {
        val image = render(controller(), dark = true)
        val forest = FmPalettes.of(PaletteId.Forest, dark = true)
        val graphite = FmPalettes.of(PaletteId.Graphite, dark = true)
        val selected = assertNotNull(cluster(image, rgb(forest.surface)), "forest card missing")
        val other = assertNotNull(cluster(image, rgb(graphite.surface)), "graphite card missing")

        val (insideSelected, outsideSelected) = borderPixels(image, rgb(forest.surface), selected)
        val border = rgb(forest.primary)
        assertTrue(
            insideSelected == border || outsideSelected == border,
            "the selected card must wear its primary border ($border), got $insideSelected/$outsideSelected",
        )
        val (insideOther, outsideOther) = borderPixels(image, rgb(graphite.surface), other)
        // 未选中的卡用**它自己**配色的 outline 描边（卡面是这套配色的界面预览），
        // 不是当前外观的 outline。
        val outline = rgb(graphite.outline)
        assertTrue(
            insideOther == outline || outsideOther == outline,
            "an unselected card must wear its own outline ($outline), got $insideOther/$outsideOther",
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun tappingTheGraphiteCardSwitchesThePalette() {
        val controller = controller()
        assertEquals(PaletteId.Forest, controller.settings.palette)
        val image = render(controller, dark = true) { scene ->
            val graphite = cluster(imageOf(scene), rgb(FmPalettes.of(PaletteId.Graphite, dark = true).surface))
            assertNotNull(graphite, "graphite card missing before the tap")
            scene.sendPointerEvent(PointerEventType.Press, Offset(graphite.centerX, graphite.centerY))
            scene.sendPointerEvent(PointerEventType.Release, Offset(graphite.centerX, graphite.centerY))
        }
        assertTrue(image.width > 0)
        assertEquals(PaletteId.Graphite, controller.settings.palette, "the tap must select 石墨琥珀")
    }

    @Test
    fun thePanelRendersInBothAppearancesAndFollowsTheSelection() {
        val light = render(controller(), dark = false)
        val forestLight = FmPalettes.of(PaletteId.Forest, dark = false)
        assertNotNull(cluster(light, rgb(forestLight.surface)), "forest light card missing")
        assertNotNull(cluster(light, rgb(forestLight.canvas)), "panel canvas missing")

        val controller = controller()
        controller.setPalette(PaletteId.Clay)
        val dark = render(controller, dark = true)
        val clay = FmPalettes.of(PaletteId.Clay, dark = true)
        val clayCard = assertNotNull(cluster(dark, rgb(clay.surface)), "clay card missing")
        val (inside, outside) = borderPixels(dark, rgb(clay.surface), clayCard)
        val primary = rgb(clay.primary)
        assertTrue(
            inside == primary || outside == primary,
            "after selecting clay, its card must wear the clay primary border ($primary)",
        )
    }

    @Test
    fun everyPaletteRendersInBothModesWithItsOwnSelection() {
        // 10 套视觉配置（5 配色 × 明暗）。浅色下 graphite / indigo / classic 的面板色
        // 都是纯白，不能只靠颜色分辨卡片；先用**选中卡自己的主色描边**量出卡片高度与
        // 第一行顶部，再按行距推出目标卡所在的那一条，最后在该行 × 该列里找卡面色。
        for (id in PaletteId.entries) {
            for (dark in listOf(false, true)) {
                val controller = controller()
                controller.setPalette(id)
                val image = render(controller, dark)
                val colors = FmPalettes.of(id, dark)
                val primary = rgb(colors.primary)
                val selectedBorder = assertNotNull(
                    cluster(image, primary),
                    "$id dark=$dark: the selected card border ($primary) is missing",
                )
                val height = selectedBorder.maxY - selectedBorder.minY + 1
                // 选中卡自己的描边给出这一行卡的 y 区间；同行两张卡的卡面色都不同
                // （墨绿/石墨、靛青/陶土、经典蓝单独一行），所以行内再按颜色取即可。
                val band = selectedBorder.minY until (selectedBorder.minY + height)
                val card = assertNotNull(
                    clusterIn(
                        image,
                        rgb(colors.surface),
                        xRange = 0 until image.width,
                        yRange = band,
                    ),
                    "$id dark=$dark: card surface not found in its own row",
                )
                assertTrue(card.count > 1_000, "$id dark=$dark: card covers only ${card.count}px")
                val (inside, outside) = borderPixels(image, rgb(colors.surface), card)
                assertTrue(
                    inside == primary || outside == primary,
                    "$id dark=$dark: selected card must wear $primary, got $inside/$outside",
                )
            }
        }
    }

    /** 在给定矩形里找某个颜色的包围盒（同名颜色在不同卡片上重复时用）。 */
    private fun clusterIn(
        image: BufferedImage,
        argb: Int,
        xRange: IntRange,
        yRange: IntRange,
    ): Cluster? {
        var count = 0
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        val want = argb and 0xFFFFFF
        for (y in yRange) {
            if (y !in 0 until image.height) continue
            for (x in xRange) {
                if (x !in 0 until image.width) continue
                if ((image.getRGB(x, y) and 0xFFFFFF) == want) {
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (count == 0) null else Cluster(count, minX, maxX, minY, maxY)
    }

    /**
     * 桌面把面板放进 `DropdownMenu`，而菜单会用**固有宽度**测量内容。SubcomposeLayout
     * 系（`BoxWithConstraints`、Lazy、TabRow…）不支持固有测量，会抛
     * "Asking for intrinsic measurements of SubcomposeLayout layouts is not supported"，
     * 而 `ImageComposeScene` 里直接画面板不会走到这条路径 —— 这条测试用一次真实的
     * `minIntrinsicWidth` 把那条路走通（2026-11-15 在打包后的桌面 App 上实际炸过）。
     */
    @Test
    fun thePanelSupportsIntrinsicMeasurementLikeDropdownMenuNeeds() {
        val controller = controller()
        val scene = ImageComposeScene(width = 420, height = 900, density = Density(1f)) {
            CompositionLocalProvider(LocalAppearance provides controller) {
                FrameMarkTheme(dark = true) {
                    Box(Modifier.fillMaxSize()) {
                        IntrinsicProbe { AppearancePanel(columns = 2) }
                    }
                }
            }
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
    }

    /** 只做一次固有宽度测量，不落子：`DropdownMenu` 对内容做的正是这件事。 */
    @Composable
    private fun IntrinsicProbe(content: @Composable () -> Unit) {
        Layout(content = content) { measurables, constraints ->
            var width = 0
            measurables.forEach { measurable ->
                width = maxOf(width, measurable.minIntrinsicWidth(constraints.maxHeight))
            }
            layout(width, 0) {}
        }
    }

    /** 交互时读当前帧：`ImageComposeScene.render()` 会推进一帧并返回位图。 */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun imageOf(scene: ImageComposeScene): BufferedImage {
        val png = scene.render().encodeToData(EncodedImageFormat.PNG)?.bytes
        assertNotNull(png, "no frame")
        return ImageIO.read(ByteArrayInputStream(png))
    }
}
