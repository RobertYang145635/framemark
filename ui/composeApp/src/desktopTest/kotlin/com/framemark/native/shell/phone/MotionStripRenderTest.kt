package com.framemark.native.shell.phone

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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
import com.framemark.native.shell.computePhoneEditorHeights
import com.framemark.native.shell.phoneParameterSheetChrome
import com.framemark.native.theme.FmSpring
import com.framemark.native.theme.FrameMarkTheme
import com.framemark.native.theme.fmSpringSpec
import com.framemark.native.workflow.DesignSession
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 交互动画的**帧带证据**（motion spec §5）：把真实的 `PhoneSheetHost` 无头渲染成一串
 * 定时帧，证明动画真的在跑，而不是只有一条曲线常量。
 *
 * 为什么用 `ImageComposeScene` 而不是录屏：桌面端没有 `adb screenrecord`；本仓库已有的
 * 视觉验收（`PhoneFixedFrameRenderTest`、`DesktopTabInkRenderTest`）都是这条无头渲染路子，
 * 它比录屏更硬——每一帧的 `nanoTime` 由测试给定，所以「第 80 ms 长什么样」是可复现的，
 * 不依赖机器快慢。
 *
 * 两行帧带：
 *  1. **底板升起**：`FmSpring.settle` 驱动底板高度（307.7 dp → 507.7 dp），第 40/80/120/
 *     160/260 ms 各一帧 —— 收尾帧必须到位，中间帧必须彼此不同。
 *  2. **换页**：`Border → Params`，真实的 `PhoneSheetPagePager`（整页左右推，220 ms
 *     `FmEasing.standard`），同样取 5 个中间时刻。这一行只证明"换页在动"；几何与时序的
 *     硬断言在 `SheetPageSlideRenderTest`。
 *
 * 底板高度在**生产代码里是 `PhoneEditorScreen` 的内联动画**（`Animatable` + 同一条
 * spring），所以这一行用同一个 token 复刻那一小段；面板内部（把手、页头、tab 行、参数行、
 * 工具条）是真实组件，没有替身。
 *
 * PNG 留在 `ui/composeApp/build/motion-strip/`：接触表 + 每帧原图。
 */
class MotionStripRenderTest {

    private val densityValue = 2f
    private val density = Density(densityValue)
    private val widthDp = 411
    private val heightDp = 905
    private val widthPx = (widthDp * densityValue).toInt()
    private val heightPx = (heightDp * densityValue).toInt()

    /** 收起 / 半开两档，模拟 grabber 连点一次的位移（200 dp 足够看清）。 */
    private val compactDp = 307.7f
    private val openDp = 507.7f

    /** 采样时刻（相对动画起点）：覆盖过渡的前 / 中 / 尾。 */
    private val frameOffsetsMs = listOf(30L, 60L, 90L, 120L, 160L, 240L)

    private fun card() = LibraryCard(
        id = "strip-card",
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

    private fun control(controlId: String, label: String, initial: ControlValue) = InspectorControl(
        key = "card::$controlId",
        controlId = controlId,
        param = controlId,
        label = label,
        kind = "slider",
        section = InspectorSection.Layout,
        type = ControlType.Slider,
        initial = initial,
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
    )

    private fun model(): InspectorCardModel {
        val controls = listOf(
            control("params:footerPct", "底部留白", ControlValue.Num(12f)),
            control("params:insetX", "左右留白", ControlValue.Num(8f)),
            control("params:insetY", "上下留白", ControlValue.Num(10f)),
        )
        return InspectorCardModel(
            cardId = "strip-card",
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

    private fun heights(tool: EditorTool) = computePhoneEditorHeights(
        availableHeightDp = heightDp.toFloat(),
        fontScale = 1f,
        imeVisible = false,
        tool = tool,
        photosOpen = false,
    )

    /** 一帧 = 一张 PNG + 一个 AWT 图像（接触表要拼）。 */
    private fun ImageComposeScene.renderFrame(nanoTime: Long): BufferedImage {
        val png = render(nanoTime)
            .encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)
            ?.bytes
        requireNotNull(png) { "frame produced no PNG bytes" }
        return javax.imageio.ImageIO.read(ByteArrayInputStream(png))
    }

    /**
     * 第 1 行：底板升起。`open` 在第一帧之后翻成 true，随后五帧分别落在 0/40/80/120/160/260 ms。
     *
     * `sheetHeight` 用 `animateDpAsState` + `fmSpringSpec(FmSpring.settle)`——与生产端
     * `PhoneEditorScreen` 里那段 `Animatable` 同一条 spring；这里换用 `animateDpAsState`
     * 只是因为测试场景不需要「拖拽中不动画」那半条规则。
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheetFrameStrip(): List<BufferedImage> {
        val model = model()
        val session = DesignSession().also { it.seed(model.controls) }
        val tabs = inspectorTabsFor(model, session, hasTextSlots = true)
        val chrome = phoneParameterSheetChrome(1f)
        val tool = EditorTool.Params
        val h = heights(tool)
        var open by mutableStateOf(false)
        val scene = ImageComposeScene(width = widthPx, height = heightPx, density = density) {
            FrameMarkTheme {
                val sheetHeight: Dp by animateDpAsState(
                    targetValue = if (open) openDp.dp else compactDp.dp,
                    animationSpec = fmSpringSpec<Dp>(FmSpring.settle),
                    label = "fm.strip.sheet",
                )
                Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.White))
                    PhoneSheetHost(
                        fullParameterPage = false,
                        sheetHeight = sheetHeight,
                        grabberHeight = chrome.grabberDp.dp,
                        headHeight = chrome.headDp.dp,
                        snapHeights = listOf(compactDp.dp, openDp.dp),
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
                        watermark = null,
                        onWatermarkChange = {},
                        onPickWatermark = {},
                        photoWidthPx = 3000,
                        photoHeightPx = 4000,
                        previewGeo = null,
                        tools = listOf(EditorTool.Border, tool, EditorTool.Text),
                        toolBarH = 62.dp,
                    )
                }
            }
        }
        try {
            val t0 = 1_000_000_000L
            val frames = mutableListOf<BufferedImage>()
            // 第 0 帧：落位（此时还没翻 open），作为「动画前」的对照。
            frames += scene.renderFrame(t0)
            open = true
            // 状态翻转后的第一帧只是**启动**动画（Compose 在这一帧才把动画起点定下来），
            // 所以它不采样；从它的下一帧开始才有真正的中间态。
            val tStart = t0 + 16_000_000L
            scene.renderFrame(tStart)
            for (ms in frameOffsetsMs) {
                frames += scene.renderFrame(tStart + ms * 1_000_000L)
            }
            return frames
        } finally {
            scene.close()
        }
    }

    /** 第 2 行：换页（边框 → 调整），真实 `PhoneSheetPagePager`（左右推页）。 */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun toolSwitchFrameStrip(): List<BufferedImage> {
        val model = model()
        val session = DesignSession().also { it.seed(model.controls) }
        val tabs = inspectorTabsFor(model, session, hasTextSlots = true)
        val chrome = phoneParameterSheetChrome(1f)
        var tool by mutableStateOf(EditorTool.Border)
        val sheetPx = compactDp.dp
        val scene = ImageComposeScene(width = widthPx, height = heightPx, density = density) {
            FrameMarkTheme {
                Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.White))
                    PhoneSheetHost(
                        fullParameterPage = false,
                        sheetHeight = sheetPx,
                        grabberHeight = chrome.grabberDp.dp,
                        headHeight = chrome.headDp.dp,
                        snapHeights = listOf(sheetPx, sheetPx),
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
                        watermark = null,
                        onWatermarkChange = {},
                        onPickWatermark = {},
                        photoWidthPx = 3000,
                        photoHeightPx = 4000,
                        previewGeo = null,
                        tools = listOf(EditorTool.Border, EditorTool.Params, EditorTool.Text),
                        toolBarH = 62.dp,
                    )
                }
            }
        }
        try {
            val t0 = 1_000_000_000L
            val frames = mutableListOf<BufferedImage>()
            frames += scene.renderFrame(t0)
            tool = EditorTool.Params
            // 换页是 `AnimatedContent` 的过渡：它要先建过渡、钉动画起点、再丢一帧零进度的
            // 启动帧，所以要几帧才起跑，`+30/+60/+90` 可能落在起跑之前（这一行只证明
            // "换页在动"；精确到毫秒的时序与几何断言在 `SheetPageSlideRenderTest`）。
            val tStart = t0 + 16_000_000L
            scene.renderFrame(tStart)
            for (ms in frameOffsetsMs) {
                frames += scene.renderFrame(tStart + ms * 1_000_000L)
            }
            return frames
        } finally {
            scene.close()
        }
    }

    /** 整图粗扫（不是某一条线）：换页动的是面板中部的参数行。 */
    private fun differs(a: BufferedImage, b: BufferedImage): Boolean {
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return true
                x += 8
            }
            y += 16
        }
        return false
    }

    /** 一行的帧宽（接触表里统一缩到 300 px 宽）。 */
    private fun scaled(image: BufferedImage, width: Int): BufferedImage {
        val height = (image.height.toFloat() * width / image.width).toInt().coerceAtLeast(1)
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g.drawImage(image, 0, 0, width, height, null)
        g.dispose()
        return out
    }

    private fun contactSheet(rows: List<Pair<String, List<BufferedImage>>>): BufferedImage {
        val cell = 300
        val gap = 10
        val labelH = 26
        val columns = rows.maxOf { it.second.size }
        val cellH = rows.first().second.first().let { (it.height.toFloat() * cell / it.width).toInt() }
        val width = gap + columns * (cell + gap)
        val height = gap + rows.sumOf { labelH + cellH + gap }
        val sheet = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g.color = java.awt.Color(0x14, 0x18, 0x1F)
        g.fillRect(0, 0, width, height)
        var y = gap
        for ((label, frames) in rows) {
            g.color = java.awt.Color(0xE6, 0xEA, 0xF0)
            g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 14)
            g.drawString(label, gap, y + 17)
            y += labelH
            frames.forEachIndexed { index, frame ->
                g.drawImage(scaled(frame, cell), gap + index * (cell + gap), y, null)
            }
            y += cellH + gap
        }
        g.dispose()
        return sheet
    }

    /**
     * 产出接触表并断言「动画真的发生了」：相邻帧必须不同。若 `sheetPx` / 工具切换没有
     * 真的推进动画时钟，六帧会一模一样——那正是这条测试要拦下来的失败。
     */
    @Test
    fun theSheetOpenAndTheToolSwitchAreCapturedMidAnimation() {
        val sheetFrames = sheetFrameStrip()
        val switchFrames = toolSwitchFrameStrip()
        assertTrue(sheetFrames.size == 7, "expected 7 sheet frames, got ${sheetFrames.size}")
        assertTrue(switchFrames.size == 7, "expected 7 switch frames, got ${switchFrames.size}")

        val sheetMoved = sheetFrames.zipWithNext().count { (a, b) -> differs(a, b) }
        val switchMoved = switchFrames.zipWithNext().count { (a, b) -> differs(a, b) }
        // 六段相邻帧里至少要动三段：弹簧和换页都是渐变，只动一两段说明采样落空。
        assertTrue(sheetMoved >= 3, "the sheet strip looks frozen ($sheetMoved/6 transitions differ)")
        assertTrue(
            switchMoved >= 2,
            "the tool switch looks frozen ($switchMoved/6 transitions differ)",
        )
        assertTrue(
            differs(switchFrames.first(), switchFrames.last()),
            "the tool switch never reached the other page",
        )
        assertTrue(
            differs(sheetFrames.first(), sheetFrames.last()),
            "the sheet never reached its open stop",
        )

        val sheet = contactSheet(
            listOf(
                "sheet open — FmSpring.settle (before / +30 / +60 / +90 / +120 / +160 / +240 ms)" to
                    sheetFrames,
                "tool switch Border→Params — PhoneSheetPagePager (full-width slide) " +
                    "(before / +30 / +60 / +90 / +120 / +160 / +240 ms)" to switchFrames,
            ),
        )
        val dir = java.nio.file.Paths.get("build", "motion-strip").toAbsolutePath()
        java.nio.file.Files.createDirectories(dir)
        javax.imageio.ImageIO.write(sheet, "png", dir.resolve("motion-batch1-strip.png").toFile())
        sheetFrames.forEachIndexed { i, frame ->
            javax.imageio.ImageIO.write(frame, "png", dir.resolve("sheet-$i.png").toFile())
        }
        switchFrames.forEachIndexed { i, frame ->
            javax.imageio.ImageIO.write(frame, "png", dir.resolve("switch-$i.png").toFile())
        }
        assertTrue(
            java.nio.file.Files.exists(dir.resolve("motion-batch1-strip.png")),
            "the strip was not written",
        )
    }
}
