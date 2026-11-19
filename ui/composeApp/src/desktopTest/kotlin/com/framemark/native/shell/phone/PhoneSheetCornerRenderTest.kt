package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorLoadState
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.PhoneLogoPickerOverlay
import com.framemark.native.inspector.inspectorTabsFor
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.LibrarySnapshot
import com.framemark.native.shell.EditorTool
import com.framemark.native.shell.computePhoneEditorHeights
import com.framemark.native.shell.phoneParameterSheetChrome
import com.framemark.native.theme.AppearanceController
import com.framemark.native.theme.AppearanceEnv
import com.framemark.native.theme.FmPalettes
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FrameMarkTheme
import com.framemark.native.theme.PaletteId
import com.framemark.native.theme.StageTone
import com.framemark.native.workflow.DesignSession
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 底部面板**上圆角外侧那两个缺口**的像素证据（真机缺陷 2026-11，用户反馈两轮）。
 *
 * 缺陷：面板顶边是一条横贯全宽的矩形边。面板自己圆了，但**背后那一层是方的**，
 * 于是每个上圆角外侧都留着一个直角缺口，里面填着与面板同色或与遮罩同色的方块 ——
 * 浅色 / 中灰 / 深色画布下都像一个「小耳朵」。
 *
 * 这里不测「代码里有没有某个 background」（那种断言换个写法就假通过），而是渲染真实
 * 组件后量三处像素，把用户看到的那条规则钉死：
 *
 * 1. **缺口 == 面板正上方那条背景带**：面板上圆角只能是唯一的分界。面板盖在舞台上时
 *    背景带是舞台色（= 设置里的画布背景），盖在遮罩上时背景带是遮罩色。缺口颜色跟它
 *    不一致 = 圆角外面多出一个直角。
 * 2. **缺口 != 面板色**：不允许出现「与面板同色」的直角（那就是耳朵本身）。
 * 3. **缺口 != 页面底色**（底板那一例）：以前缺口露的是 `FmPalette.canvas`，它每套配色
 *    里都只比 `surface` 深一档 —— 这条断言就是那一版的回归闸门。
 *
 * 三处同款缺陷（编辑器底板 / 边框库覆盖层 / 品牌选择器覆盖层）各有一个用例，因为它们
 * 背后那一层不一样：底板背后是舞台（缺口必须等于舞台色），两个覆盖层背后是铺满整屏的
 * 遮罩（缺口必须等于遮罩色）。
 *
 * 三种画布背景（深灰 / 中灰 / 浅灰）× 明暗两种外观 × 两套配色各跑一遍：修法必须与
 * 主题无关，写死任何一种颜色的做法会在别的格子上红。
 *
 * PNG 留在 `ui/composeApp/build/panel-corner/` 供人眼复核。
 */
class PhoneSheetCornerRenderTest {

    private val densityValue = 3.5f
    private val density = Density(densityValue)
    private val widthDp = 411
    private val heightDp = 905
    private val widthPx = (widthDp * densityValue).toInt()
    private val heightPx = (heightDp * densityValue).toInt()
    private val toolBarDp = 62

    /** 半径读产品令牌，不在测试里另写一个数。 */
    private val radiusPx = (FmRadii.sheet.value * densityValue).roundToInt()

    /** 缺口采样：贴着左/右边缘（这里一定落在圆角**外**侧）。 */
    private val notchLeftX = 2
    private val notchRightX = widthPx - 3

    /** 弧线之内、又避开 1 dp 描边的采样列。 */
    private val insideX = 8

    /** 一丝不苟地把「同一颜色」定义成一个紧容差：渲染色值不会差 2 个台阶。 */
    private fun sameColor(a: Int, b: Int): Boolean {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db <= 9
    }

    private fun rgb(argb: Int): String = "(%d,%d,%d)".format(
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF,
    )

    // ---- sheet ------------------------------------------------------------

    private fun card() = LibraryCard(
        id = "corner-card",
        name = "测试边框",
        category = "classic",
        categoryName = "经典",
        brand = "TEST",
        vendor = "TEST",
        family = null,
        fidelity = null,
        badge = null,
        en = "Test frame",
        systemStyle = null,
        previewSourcePath = "",
        previewFile = "",
    )

    private fun model(): InspectorCardModel {
        val controls = listOf(
            InspectorControl(
                key = "card::params:footerPct",
                controlId = "params:footerPct",
                param = "params.footerPct",
                label = "底部留白",
                kind = "slider",
                section = InspectorSection.Layout,
                type = ControlType.Slider,
                initial = ControlValue.Num(12f),
                min = 0f,
                max = 100f,
                step = 1f,
                options = emptyList(),
                multiline = false,
                declaredDefault = null,
                currentText = null,
                autoMatch = null,
                unit = null,
                hidden = false,
            ),
        )
        return InspectorCardModel(
            cardId = "corner-card",
            cardName = "测试边框",
            audited = false,
            effectiveCount = controls.size,
            gatedCount = null,
            declaredCount = controls.size,
            effectiveIds = controls.map { it.controlId },
            gatedIds = emptyList(),
            visibleIds = controls.map { it.controlId },
            hiddenIds = emptyList(),
            controls = controls,
            sections = emptyList(),
        )
    }

    /**
     * 复刻生产的三层（`PhoneShell` 根 Box → `StageArea` → `PhoneSheetHost`）：
     * 页面底色（`FmPalette.canvas`）在最下面，舞台带铺舞台色，底板落在舞台带上。
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheetFrame(tone: StageTone, dark: Boolean, palette: PaletteId): BufferedImage {
        val controller = AppearanceController(store = null).also { it.setStageTone(tone) }
        val previous = AppearanceEnv.controller
        AppearanceEnv.controller = controller
        try {
            val model = model()
            val session = DesignSession().also { it.seed(model.controls) }
            val tabs = inspectorTabsFor(model, session, hasTextSlots = true)
            val heights = computePhoneEditorHeights(
                availableHeightDp = heightDp.toFloat(),
                fontScale = 1f,
                imeVisible = false,
                tool = EditorTool.Params,
                photosOpen = false,
            )
            assertTrue(!heights.fullPage, "the fixed panel must be the layout at ${widthDp}x$heightDp")
            val chrome = phoneParameterSheetChrome(1f)
            val canvas = FmPalettes.of(palette, dark).canvas
            val scene = ImageComposeScene(width = widthPx, height = heightPx, density = density) {
                FrameMarkTheme(dark = dark, palette = palette) {
                    Box(modifier = Modifier.fillMaxSize().background(canvas)) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(phoneStageColor()),
                            )
                            PhoneSheetHost(
                                fullParameterPage = false,
                                sheetHeight = heights.sheet.dp,
                                grabberHeight = chrome.grabberDp.dp,
                                headHeight = chrome.headDp.dp,
                                snapHeights = listOf(heights.sheet.dp, (heights.sheet + 40f).dp),
                                sheetLevel = 0,
                                onSheetLevelChange = {},
                                sheetDragPx = null,
                                onSheetDragPx = {},
                                density = density,
                                tool = EditorTool.Params,
                                tabModel = tabs,
                                card = card(),
                                libraryIndex = null,
                                onRetryLibrary = {},
                                onOpenPresets = {},
                                onToolChange = {},
                                model = model,
                                session = session,
                                inspectorState = InspectorLoadState.Ready(),
                                onRetryInspector = {},
                                textSlots = emptyList(),
                                textOutputWidth = 0,
                                textOutputHeight = 0,
                                onFocusedTextSlotChange = {},
                                watermark = null,
                                onWatermarkChange = {},
                                onPickWatermark = {},
                                photoWidthPx = 3000,
                                photoHeightPx = 4000,
                                previewGeo = null,
                                tools = listOf(EditorTool.Border, EditorTool.Params, EditorTool.Text),
                                toolBarH = toolBarDp.dp,
                            )
                        }
                    }
                }
            }
            val image = scene.render()
            val png = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
            scene.close()
            assertTrue(png != null && png.isNotEmpty(), "the sheet scene produced no PNG bytes")
            write("sheet-${tone.wire}-${if (dark) "dark" else "light"}-${palette.wire}.png", png!!)
            return javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png))
        } finally {
            AppearanceEnv.controller = previous
        }
    }

    /** 某一列上「背景带」还没结束的最后一行：它下面那一行就是面板的第一行。 */
    private fun lastRowOf(image: BufferedImage, x: Int, color: Int, fromY: Int): Int {
        var y = fromY
        while (y < image.height) {
            if (!sameColor(image.getRGB(x, y), color)) return y - 1
            y++
        }
        return image.height - 1
    }

    private fun write(name: String, png: ByteArray) {
        val dir = Paths.get("build", "panel-corner").toAbsolutePath()
        Files.createDirectories(dir)
        Files.write(dir.resolve(name), png)
    }

    @Test
    fun theSheetCornerShowsTheStageAndNotThePageColour() {
        val combos = listOfNotNull(
            PaletteId.Default to false,
            PaletteId.Default to true,
            PaletteId.Graphite to false,
            PaletteId.Graphite to true,
        )
        for ((palette, dark) in combos) {
            val colors = FmPalettes.of(palette, dark)
            for (tone in StageTone.entries) {
                val label = "tone=${tone.wire} dark=$dark palette=${palette.wire}"
                val image = sheetFrame(tone, dark, palette)
                val stage = colors.stageColor(tone).toArgb()
                // 舞台带是纯色（这一帧里没有照片），所以竖直方向上「舞台色结束」的那一行
                // 就是底板顶边；圆心列落在圆角的平直段上，取到的是同一条边。
                val sheetTop = lastRowOf(image, widthPx / 2, stage, 0) + 1

                // 这条断言只说明这一格**能**测出缺陷：两种颜色必须不同，否则缺口换成哪种都
                // 看不出来，测试就是空转。
                assertNotEquals(
                    stage,
                    colors.canvas.toArgb(),
                    "$label: stage and canvas are the same colour, the case cannot fail",
                )

                // 面板正上方（背景带）和面板正下方（缺口）必须同色 —— 圆角是唯一的分界。
                val aboveLeft = image.getRGB(notchLeftX, sheetTop - 6)
                val aboveRight = image.getRGB(notchRightX, sheetTop - 6)
                assertEquals(stage, aboveLeft, "$label: the band above the panel must be the stage")
                assertEquals(stage, aboveRight, "$label: the band above the panel must be the stage")
                for (dy in intArrayOf(6, 9, 12)) {
                    assertEquals(
                        aboveLeft,
                        image.getRGB(notchLeftX, sheetTop + dy),
                        "$label: the corner notch at (notchLeftX=$notchLeftX, sheetTop+$dy) is not the stage: " +
                            "notch=${rgb(image.getRGB(notchLeftX, sheetTop + dy))} " +
                            "stage=${rgb(stage)} canvas=${rgb(colors.canvas.toArgb())} " +
                            "surface=${rgb(colors.surface.toArgb())}",
                    )
                    assertEquals(
                        aboveRight,
                        image.getRGB(notchRightX, sheetTop + dy),
                        "$label: the corner notch at (notchRightX=$notchRightX, sheetTop+$dy) is not the stage: " +
                            "notch=${rgb(image.getRGB(notchRightX, sheetTop + dy))} stage=${rgb(stage)}",
                    )
                }
                // 不允许「与面板同色」的直角：缺口不是 surface。
                assertTrue(
                    !sameColor(image.getRGB(notchLeftX, sheetTop + 9), colors.surface.toArgb()),
                    "$label: a panel-coloured square corner is painted outside the sheet's arc",
                )
                // 缺口也不是页面底色（上一版就是它，才看起来像耳朵）。
                assertTrue(
                    !sameColor(image.getRGB(notchLeftX, sheetTop + 9), colors.canvas.toArgb()),
                    "$label: the notch still shows FmPalette.canvas (the old defect)",
                )
                // 圆角是真的存在：跨过圆弧之后，同一列已经是面板（不是背景带）。
                val insideY = sheetTop + radiusPx + 8
                assertTrue(
                    !sameColor(image.getRGB(insideX, insideY), stage),
                    "$label: no arc at all — (insideX=$insideX, $insideY) is still the stage",
                )
            }
        }
    }

    // ---- border library overlay -------------------------------------------

    private fun emptyIndex(): LibraryIndex = LibraryIndex.of(
        LibrarySnapshot(
            cards = emptyList(),
            categories = emptyList(),
            mappedPreviews = 0,
            unmappedPreviewIds = emptyList(),
            generatedAt = "corner-test",
        ),
    )

    /**
     * 库覆盖层：遮罩铺满整屏、面板盖在上面。缺口必须是**遮罩色**（也就是面板正上方那条
     * 带子的颜色），不能是没压暗的舞台 —— 后者就是「深色遮罩边上一块亮方角」。
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun libraryFrame(tone: StageTone, dark: Boolean): BufferedImage {
        val controller = AppearanceController(store = null).also { it.setStageTone(tone) }
        val previous = AppearanceEnv.controller
        AppearanceEnv.controller = controller
        try {
            val index = emptyIndex()
            val scene = ImageComposeScene(width = widthPx, height = heightPx, density = density) {
                FrameMarkTheme(dark = dark) {
                    // 覆盖层背后 = 编辑器（舞台带 / 底板都在下面），这里只要它的颜色。
                    Box(modifier = Modifier.fillMaxSize().background(phoneStageColor())) {
                        PhoneBorderLibraryOverlay(
                            index = index,
                            appliedCard = null,
                            categoryKey = LibraryIndex.CATEGORY_COMMON,
                            onCategoryChange = {},
                            query = "",
                            onQueryChange = {},
                            inUse = emptyList(),
                            recent = emptyList(),
                            onApply = {},
                            onDismiss = {},
                        )
                    }
                }
            }
            val image = scene.render()
            val png = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
            scene.close()
            assertTrue(png != null && png.isNotEmpty(), "the library scene produced no PNG bytes")
            write("library-${tone.wire}-${if (dark) "dark" else "light"}.png", png!!)
            return javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png))
        } finally {
            AppearanceEnv.controller = previous
        }
    }

    @Test
    fun theLibraryPanelCornerShowsTheScrimAndNotTheStage() {
        for (dark in listOf(false, true)) {
            val colors = FmPalettes.of(PaletteId.Default, dark)
            for (tone in StageTone.entries) {
                val label = "library tone=${tone.wire} dark=$dark"
                val image = libraryFrame(tone, dark)
                assertScrimOverlayCorner(
                    image = image,
                    label = label,
                    stage = colors.stageColor(tone).toArgb(),
                    panel = colors.canvas.toArgb(),
                )
            }
        }
    }

    /**
     * 遮罩型覆盖层（边框库 / 品牌选择器）的通例：面板盖在**铺满整屏**的遮罩上，所以
     * 缺口必须是遮罩色 = 面板正上方那条带的颜色；不能是没压暗的舞台（上一版就是它，
     * 深色遮罩边上一块亮方角），也不能是面板自己的底色。
     */
    private fun assertScrimOverlayCorner(image: BufferedImage, label: String, stage: Int, panel: Int) {
        val scrimBand = image.getRGB(widthPx / 2, 4)
        assertNotEquals(
            stage,
            scrimBand,
            "$label: the scrim does not dim the stage, the case cannot fail",
        )
        val panelTop = lastRowOf(image, widthPx / 2, scrimBand, 4) + 1
        assertTrue(panelTop in 1 until image.height, "$label: the panel never starts")

        for (dy in intArrayOf(6, 9, 12)) {
            assertEquals(
                scrimBand,
                image.getRGB(notchLeftX, panelTop + dy),
                "$label: the corner notch at (notchLeftX=$notchLeftX, panelTop+$dy) is not the scrim: " +
                    "notch=${rgb(image.getRGB(notchLeftX, panelTop + dy))} " +
                    "scrim=${rgb(scrimBand)} stage=${rgb(stage)}",
            )
            assertEquals(
                scrimBand,
                image.getRGB(notchRightX, panelTop + dy),
                "$label: the corner notch at (notchRightX=$notchRightX, panelTop+$dy) is not the scrim: " +
                    "notch=${rgb(image.getRGB(notchRightX, panelTop + dy))}",
            )
        }
        // 没压暗的舞台不许出现在缺口里（上一版就是它）。
        assertTrue(
            !sameColor(image.getRGB(notchLeftX, panelTop + 9), stage),
            "$label: the notch shows the undimmed stage (the old defect)",
        )
        // 也不许是与面板同色的直角。
        assertTrue(
            !sameColor(image.getRGB(notchLeftX, panelTop + 9), panel),
            "$label: the notch shows the panel's own colour outside the arc",
        )
        val insideY = panelTop + radiusPx + 8
        assertTrue(
            !sameColor(image.getRGB(insideX, insideY), scrimBand),
            "$label: no arc at all — (insideX=$insideX, $insideY) is still the scrim",
        )
    }

    // ---- brand picker overlay ----------------------------------------------

    /** 品牌选择器：和边框库同一套「整屏遮罩 + 圆角面板」，同一个根因。 */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun logoPickerFrame(tone: StageTone, dark: Boolean): BufferedImage {
        val controller = AppearanceController(store = null).also { it.setStageTone(tone) }
        val previous = AppearanceEnv.controller
        AppearanceEnv.controller = controller
        try {
            val scene = ImageComposeScene(width = widthPx, height = heightPx, density = density) {
                FrameMarkTheme(dark = dark) {
                    Box(modifier = Modifier.fillMaxSize().background(phoneStageColor())) {
                        PhoneLogoPickerOverlay(
                            appliedAssetKey = "auto",
                            onDismiss = {},
                            onApply = {},
                        )
                    }
                }
            }
            val image = scene.render()
            val png = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
            scene.close()
            assertTrue(png != null && png.isNotEmpty(), "the brand picker scene produced no PNG bytes")
            write("logo-${tone.wire}-${if (dark) "dark" else "light"}.png", png!!)
            return javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png))
        } finally {
            AppearanceEnv.controller = previous
        }
    }

    @Test
    fun theBrandPickerCornerShowsTheScrimAndNotTheStage() {
        for (dark in listOf(false, true)) {
            val colors = FmPalettes.of(PaletteId.Default, dark)
            for (tone in StageTone.entries) {
                val image = logoPickerFrame(tone, dark)
                assertScrimOverlayCorner(
                    image = image,
                    label = "brand-picker tone=${tone.wire} dark=$dark",
                    stage = colors.stageColor(tone).toArgb(),
                    panel = colors.canvas.toArgb(),
                )
            }
        }
    }
}
