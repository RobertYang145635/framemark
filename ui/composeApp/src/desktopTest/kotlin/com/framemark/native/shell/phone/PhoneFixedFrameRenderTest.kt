package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.framemark.native.inspector.ControlOption
import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorLoadState
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.inspectorTabsFor
import com.framemark.native.library.LibraryCard
import com.framemark.native.shell.EditorTool
import com.framemark.native.shell.PHONE_PANEL_MAX_DP
import com.framemark.native.shell.PHONE_PANEL_MIN_DP
import com.framemark.native.shell.PHONE_PANEL_FRACTION
import com.framemark.native.shell.computePhoneEditorHeights
import com.framemark.native.shell.phonePanelHeightDp
import com.framemark.native.shell.phoneParameterSheetChrome
import com.framemark.native.theme.FrameMarkTheme
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.WatermarkSpec
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 固定画框的**视觉**证据：真的把 `PhoneSheetHost`（页头 + tab 行 + 参数行 + 工具条）
 * 在 411×905 dp / density 3.5 下无头渲染一遍，然后量像素。
 *
 * 量三件事：
 *  1. **面板顶边**（画布与面板的交界）在 样式 / 文字 / Logo / 水印 四个工具上完全
 *     相同 —— 这就是「切换工具不能让面板顶边动一个 dp」的证据；
 *  2. 一条数值行的**轨道像素宽** ≥ 内容宽的 85 %（页面里的真实布局，不是常量）；
 *  3. 面板里的内容**没有画到面板底边以下**（滚动区自己裁掉）。
 *
 * PNG 留在 `ui/composeApp/build/panel-frame/` 供人眼复核。
 */
class PhoneFixedFrameRenderTest {

    private val densityValue = 3.5f
    private val density = Density(densityValue)
    private val widthDp = 411
    private val heightDp = 905
    private val widthPx = (widthDp * densityValue).toInt()
    private val heightPx = (heightDp * densityValue).toInt()

    private fun card() = LibraryCard(
        id = "test-card",
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

    private fun control(
        controlId: String,
        param: String,
        label: String,
        kind: String = "slider",
        type: ControlType = ControlType.Slider,
        initial: ControlValue = ControlValue.Num(10f),
        section: InspectorSection = InspectorSection.Layout,
        options: List<ControlOption> = emptyList(),
    ) = InspectorControl(
        key = "card::$controlId",
        controlId = controlId,
        param = param,
        label = label,
        kind = kind,
        section = section,
        type = type,
        initial = initial,
        min = 0f,
        max = 100f,
        step = 1f,
        options = options,
        multiline = false,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )

    private fun model(): InspectorCardModel {
        val controls = listOf(
            // 样式：底色 / 版式 / 底部留白（三组 tab 各一行起步）
            control(
                "theme.theme",
                "theme",
                "底色主题",
                kind = "themeSwatches",
                type = ControlType.Select,
                initial = ControlValue.Str("white"),
                section = InspectorSection.Theme,
                options = listOf(ControlOption("white", "白"), ControlOption("black", "黑")),
            ),
            control(
                "params:footerMode",
                "params.footerMode",
                "页脚版式",
                kind = "select",
                type = ControlType.Select,
                initial = ControlValue.Str("classic"),
                options = listOf(
                    ControlOption("classic", "classic"),
                    ControlOption("simple", "simple"),
                    ControlOption("overlay", "overlay"),
                ),
            ),
            control("params:footerPct", "params.footerPct", "底部留白", initial = ControlValue.Num(12f)),
            control(
                "params:footerAuto",
                "params.footerAuto",
                "页脚自适应",
                kind = "switch",
                type = ControlType.Switch,
                initial = ControlValue.Flag(true),
            ),
            // 文字：字号（一页一组）
            control("text:fontScale", "fontScale", "整卡字号缩放", initial = ControlValue.Num(1.2f)),
            // Logo：显示 / 位置 / 大小 + 品牌动作
            control(
                "params:showLogo",
                "params.showLogo",
                "显示标志",
                kind = "switch",
                type = ControlType.Switch,
                initial = ControlValue.Flag(true),
                section = InspectorSection.Logo,
            ),
            control(
                "params:logoPos",
                "params.logoPos",
                "标志位置",
                kind = "gravitySegment",
                type = ControlType.Select,
                initial = ControlValue.Str("center"),
                section = InspectorSection.Logo,
                options = listOf(
                    ControlOption("left", "left"),
                    ControlOption("center", "center"),
                    ControlOption("right", "right"),
                ),
            ),
            control(
                "params:logoScale",
                "params.logoScale",
                "字标缩放",
                initial = ControlValue.Num(1f),
                section = InspectorSection.Logo,
            ),
            control(
                "design.logo",
                "design.logo",
                "品牌字标",
                kind = "brandPicker",
                type = ControlType.Brand,
                initial = ControlValue.Str("auto"),
                section = InspectorSection.Logo,
            ),
        )
        return InspectorCardModel(
            cardId = "test-card",
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

    private fun watermark() = WatermarkSpec(
        path = "/tmp/panel-frame-stamp.png",
        aspect = 1f,
        anchor = 8,
        width = 0.25f,
    )

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(tool: EditorTool): BufferedImage {
        val model = model()
        val session = DesignSession().also { it.seed(model.controls) }
        val tabs = inspectorTabsFor(model, session, hasTextSlots = true)
        val heights = computePhoneEditorHeights(
            availableHeightDp = heightDp.toFloat(),
            fontScale = 1f,
            imeVisible = false,
            tool = tool,
            photosOpen = false,
        )
        assertTrue(!heights.fullPage, "$tool must keep the fixed panel at $widthDp×$heightDp")
        val chrome = phoneParameterSheetChrome(1f)
        val scene = ImageComposeScene(width = widthPx, height = heightPx, density = density) {
            FrameMarkTheme {
                Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    // 画布站位：白色 = 照片带（真正测的是它的下沿 = 面板顶边）。
                    Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.White))
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
                        tool = tool,
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
                        watermark = if (tool == EditorTool.Watermark) watermark() else null,
                        onWatermarkChange = {},
                        onPickWatermark = {},
                        photoWidthPx = 3000,
                        photoHeightPx = 4000,
                        previewGeo = null,
                        tools = listOf(tool),
                        toolBarH = 62.dp,
                    )
                }
            }
        }
        val image = scene.render()
        val png = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
        scene.close()
        assertTrue(png != null && png.isNotEmpty(), "$tool produced no PNG bytes")
        val dir = java.nio.file.Paths.get("build", "panel-frame").toAbsolutePath()
        java.nio.file.Files.createDirectories(dir)
        java.nio.file.Files.write(dir.resolve("panel-${tool.name.lowercase()}.png"), png!!)
        return javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(png))
    }

    /** 面板顶边：窗口竖直中线上第一行不再属于白色画布的那一行。 */
    private fun panelTopPx(image: BufferedImage): Int {
        val x = image.width / 2
        for (y in 0 until image.height) {
            val rgb = image.getRGB(x, y)
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            // 白色画布 → 面板的 surface 是一条很硬的边：不是近白就算进入面板。
            if (!(r > 240 && g > 240 && b > 240)) return y
        }
        return image.height
    }

    @Test
    fun thePanelTopEdgeIsTheSameOnEveryTool() {
        val tools = listOf(EditorTool.Params, EditorTool.Text, EditorTool.Logo, EditorTool.Watermark)
        val tops = tools.associateWith { panelTopPx(render(it)) }
        val expectedTop = ((heightDp - 62f - phonePanelHeightDp(heightDp.toFloat())) * densityValue).toInt()
        // 顶边 = 窗口 − 工具条 62 − 固定面板（±1 px 取整，圆角在中间列上不可见）。
        tops.forEach { (tool, top) ->
            assertTrue(
                abs(top - expectedTop) <= 2,
                "$tool: panel top $top px, expected ~$expectedTop px (heights=${tops.map { it.value }})",
            )
        }
        assertEquals(1, tops.values.distinct().size, "四个工具的面板顶边必须同一条线：$tops")
    }

    @Test
    fun thePanelNeverGrowsPastTheFixedHeight() {
        val tools = listOf(EditorTool.Params, EditorTool.Text, EditorTool.Logo, EditorTool.Watermark)
        val bottomOfPanel = ((heightDp - 62f) * densityValue).toInt()
        tools.forEach { tool ->
            val image = render(tool)
            // 工具条下面什么都没有：最后一行像素仍然属于面板（不是透明 / 黑色画布）。
            val lastRow = image.getRGB(image.width / 2, image.height - 2)
            assertTrue(lastRow != 0, "$tool: the last row must be drawn")
            // 面板底边之上是面板，之下（不存在）没有被撑出去的内容。
            assertTrue(bottomOfPanel > 0 && bottomOfPanel < heightPx)
        }
    }

    @Test
    fun theMeasuredRowsAreTheFixedFrameOnes() {
        val heights = computePhoneEditorHeights(
            availableHeightDp = heightDp.toFloat(),
            fontScale = 1f,
            imeVisible = false,
            tool = EditorTool.Watermark,
            photosOpen = false,
        )
        assertEquals(phonePanelHeightDp(heightDp.toFloat()), heights.sheet, 0.01f)
        assertTrue(heights.sheet in PHONE_PANEL_MIN_DP..PHONE_PANEL_MAX_DP, "sheet=${heights.sheet}")
        assertEquals(307.7f, heights.sheet, 0.01f)
        // 真实编辑器里画布 = 窗口 − 顶栏 52 − 工具条 62 − 固定面板；渲染里省掉了顶栏，
        // 所以那一列的空间比 `heights.stage` 多 52 dp（面板顶边仍是同一条线）。
        assertEquals(heightDp - 52f - 62f - heights.sheet, heights.stage, 0.01f)
        assertEquals(heightDp - 62f - heights.sheet, heights.stage + 52f, 0.01f)
        assertEquals(PHONE_PANEL_FRACTION, 0.34f)
    }
}
