package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorLoadState
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.inspectorTabsFor
import com.framemark.native.library.LibraryCard
import com.framemark.native.shell.EditorTool
import com.framemark.native.shell.phoneParameterSheetChrome
import com.framemark.native.theme.FmDuration
import com.framemark.native.theme.FmEasing
import com.framemark.native.theme.FrameMarkTheme
import com.framemark.native.theme.LocalFmMotionScale
import com.framemark.native.workflow.DesignSession
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 换页滑动的**帧级证据**（motion spec §3.1 #2 / §5）—— 把真实的 [PhoneSheetPagePager]
 * 无头渲染成定时帧，证明它真的是"左右推页"，而不是又一条曲线常量。
 *
 * 与 `MotionStripRenderTest` 同一套办法：`ImageComposeScene` + 测试给定的 `nanoTime`，
 * 所以"第 110 ms 长什么样"可复现，与机器快慢无关。页眉带和工具条带是参数区的邻居，
 * 用纯色画着，专门用来证明"换页不惊动它们"、"滑动的页不许画出去"。
 *
 * ## 为什么要自校准
 *
 * `AnimatedContent` 的过渡不是在状态翻转的那一帧就起跑的：`ImageComposeScene` 要先创建
 * 过渡、把动画起点钉在某一帧上，再丢掉一帧"零进度"的启动帧（实测，见
 * `MotionStripRenderTest` 里同一现象的注释）。钉在第几帧取决于帧时序，**不是**产品行为，
 * 所以这里不去猜：先按 4 ms 步长探到"入页第一次动"的那一帧，用**产品自己的** `FmEasing.standard`
 * 反解出这一帧已经走了多少毫秒，从而换算出动画起点 `S`，再从 `S` 起按 0/55/110/165/220/280 ms
 * 取帧。平台若改了这套时序、或者有人把滑动换回淡入淡出，下面的几何断言会先红。
 *
 * ## 断言
 *
 * 1. **中途两页都在场，且左右相邻**：入页的绘制边界与出页的绘制边界不相交。这是"pager
 *    而不是叠化"的全部证据——换回 fade，两页会重合在同一矩形里，这条立刻红。
 * 2. **整体单调同向平移**：出页的推进边在 55 → 110 → 165 ms 上严格单调。"中途回退"
 *    （错用 spring、或 in/out 用了不同时长）会被抓住。
 * 3. **收尾精确**：`≥ 220 ms` 时出页一个像素都不剩，入页左边界正好 0、右边界正好面板宽，
 *    即 offset 恰好为 0。
 * 4. **裁切**：内容比参数区高 90 dp 时，页眉带与工具条带必须一个像素都不变。
 * 5. **reduce motion**：`LocalFmMotionScale = 0` 时翻转帧即落位，逐帧都不漂移。
 *
 * PNG 留在 `ui/composeApp/build/motion-strip/`，归档在 `evidence/motion-batch2/`。
 */
class SheetPageSlideRenderTest {

    private val densityValue = 2f
    private val density = Density(densityValue)
    private val widthDp = 320
    private val heightDp = 250
    private val widthPx = (widthDp * densityValue).toInt()
    private val heightPx = (heightDp * densityValue).toInt()

    // 参数区上下两块"不许动"的邻居：页眉带、工具条带。
    private val headerDp = 40
    private val slotDp = 160
    private val barDp = 40

    private val headerTopPx = 0
    private val headerBottomPx = headerDp * densityValue.toInt()
    private val slotTopPx = headerBottomPx
    private val slotBottomPx = (headerDp + slotDp) * densityValue.toInt()
    private val barTopPx = slotBottomPx
    private val barBottomPx = (headerDp + slotDp + barDp) * densityValue.toInt()

    /** 帧带的采样时刻（相对动画起点）：0 / 55 / 110 / 165 / 220 ms，外加一个"早已收尾"的对照。 */
    private val frameOffsetsMs = listOf(0L, 55L, 110L, 165L, 220L, 280L)

    private val pageARed = Color(0xFFFF0000)
    private val pageBGreen = Color(0xFF00FF00)
    private val headerBlue = Color(0xFF0000FF)
    private val barCyan = Color(0xFF00FFFF)

    /** 真实底板那一行的密度：1.5 只是为了接触表不至于太长，几何与真机同构。 */
    private val realDensity = 1.5f

    // ---- pixel classification ----------------------------------------------

    private fun isRed(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r >= 200 && g <= 60 && b <= 60
    }

    private fun isGreen(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return g >= 200 && r <= 60 && b <= 60
    }

    private fun isBlue(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return b >= 200 && r <= 60 && g <= 60
    }

    private fun isCyan(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return g >= 200 && b >= 200 && r <= 60
    }

    /** 一页在某一帧里的水平绘制范围（整像素，量自渲染结果而不是读动画值）。 */
    private data class Span(val minX: Int, val maxX: Int) {
        val widthPx: Int get() = maxX - minX + 1
        override fun toString(): String = "[$minX..$maxX]"
    }

    /** 在参数区内部横扫几行，取某种颜色的最小/最大列。没画到就是 null。 */
    private fun span(
        frame: BufferedImage,
        yFrom: Int,
        yTo: Int,
        match: (Int) -> Boolean,
    ): Span? {
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var y = yFrom
        while (y < yTo) {
            var x = 0
            while (x < frame.width) {
                if (match(frame.getRGB(x, y))) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
                x++
            }
            y += 8
        }
        return if (maxX >= minX) Span(minX, maxX) else null
    }

    /** 参数区内部（避开上下边缘）的几行。入页永远是绿色，出页永远是红色。 */
    private fun pageSpan(frame: BufferedImage, red: Boolean): Span? =
        span(frame, slotTopPx + 8, slotBottomPx - 8) { if (red) isRed(it) else isGreen(it) }

    // ---- scene -------------------------------------------------------------

    /**
     * 渲染帧带：[columns][0] 是 `0 ms` 一列 —— 滑动用换页前的静止帧（那一帧与 p = 0 逐像素
     * 相同，入页完全在面板右侧之外），reduce motion 用翻转后的第一帧（它已经是终态）。
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun renderColumns(
        motionScale: Float,
        first: EditorTool,
        second: EditorTool,
        clipContent: Boolean,
    ): List<BufferedImage> {
        val tools = listOf(EditorTool.Border, EditorTool.Params, EditorTool.Text)
        var tool by mutableStateOf(first)
        val scene = ImageComposeScene(width = widthPx, height = heightPx, density = density) {
            CompositionLocalProvider(LocalFmMotionScale provides motionScale) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth().height(headerDp.dp).background(headerBlue))
                        PhoneSheetPagePager(
                            tool = tool,
                            tools = tools,
                            modifier = Modifier.fillMaxWidth().height(slotDp.dp),
                        ) { target ->
                            val color = if (target == second) pageBGreen else pageARed
                            if (!clipContent) {
                                Box(Modifier.fillMaxSize().background(color))
                            } else {
                                // 比参数区高 70 dp、且从 -20 dp 开始：不裁切就会画到页眉带和
                                // 工具条带上。`requiredHeight` 而不是 `height`，因为后者会被
                                // 父约束压回 160 dp —— 那样根本没有溢出可裁。
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .offset(y = (-20).dp)
                                        .requiredHeight((slotDp + 70).dp)
                                        .background(color),
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(barDp.dp).background(barCyan))
                    }
                }
            }
        }
        try {
            val t0 = 1_000_000_000L
            val before = scene.renderFrame(t0)
            tool = second
            if (motionScale <= 0f) {
                // 无动画：探到"新页第一次出现"的那一帧，从它起算 0 ms。不假设它一定是第一帧
                // ——`AnimatedContent` 要几帧才把状态提交下来，这是 harness 的事，不是产品的事。
                var probe = t0
                var landed: BufferedImage? = null
                while (landed == null) {
                    probe += 4_000_000L
                    require(probe < t0 + 200_000_000L) { "the instant swap never landed" }
                    val frame = scene.renderFrame(probe)
                    if (pageSpan(frame, red = false) != null) landed = frame
                }
                return listOf(landed) + frameOffsetsMs.drop(1).map { ms ->
                    scene.renderFrame(probe + ms * 1_000_000L)
                }
            }
            // 自校准：4 ms 步长扫到过渡中段，用**产品曲线**把每一帧的"已用时间"反解出来，
            // 取 5 个拟合值的中位数当起点。只看过渡中段、并且取中位数，是因为起跑那几帧
            // 不可信：`AnimatedContent` 要先建过渡、再钉动画起点，紧跟其后的那一帧仍然可能
            // 画的是零进度（或上一帧的旧值）。拿它算起点会整体偏掉几十毫秒。
            val fits = ArrayList<Long>(5)
            var probe = t0
            while (fits.size < 5) {
                probe += 4_000_000L
                require(probe < t0 + 400_000_000L) { "the page slide never started" }
                val frame = scene.renderFrame(probe)
                val progress = pageSpan(frame, red = false)?.let { it.widthPx.toFloat() / widthPx } ?: 0f
                if (progress >= 0.25f) {
                    fits += probe - (elapsedMsFor(progress) * 1_000_000f).toLong()
                }
            }
            val start = fits.sorted()[fits.size / 2]
            return listOf(before) + frameOffsetsMs.drop(1).map { ms ->
                scene.renderFrame(start + ms * 1_000_000L)
            }
        } finally {
            scene.close()
        }
    }

    /** 时间 → 进度用产品的曲线；这里只需要反解，二分 24 次足够（误差 < 1 ms）。 */
    private fun elapsedMsFor(progress: Float): Float {
        val duration = FmDuration.pageSlide.toFloat()
        var lo = 0f
        var hi = duration
        repeat(24) {
            val mid = (lo + hi) / 2f
            if (FmEasing.standard.transform(mid / duration) < progress) lo = mid else hi = mid
        }
        return (lo + hi) / 2f
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.renderFrame(nanoTime: Long): BufferedImage {
        val png = render(nanoTime)
            .encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)
            ?.bytes
        requireNotNull(png) { "frame produced no PNG bytes" }
        return javax.imageio.ImageIO.read(ByteArrayInputStream(png))
    }

    // ---- the slide itself ---------------------------------------------------

    @Test
    fun movingRightPushesTheOldPageLeftAndBringsTheNewOneFromTheRight() {
        val frames = renderColumns(1f, EditorTool.Params, EditorTool.Text, clipContent = false)
        assertEquals(frameOffsetsMs.size, frames.size, "one frame per strip column")

        val resting = assertNotNull(
            pageSpan(frames.first(), red = true),
            "the first page was not drawn at all",
        )
        assertEquals(0, resting.minX, "the resting page must start at the panel's left edge")
        assertEquals(widthPx - 1, resting.maxX, "the resting page must fill the panel width")
        assertNull(
            pageSpan(frames.first(), red = false),
            "the incoming page is already visible at 0 ms: the transition did not start at 0",
        )

        val outgoing = frames.map { pageSpan(it, red = true) }
        val incoming = frames.map { pageSpan(it, red = false) }
        val label = { i: Int -> "+${frameOffsetsMs[i]} ms" }

        // 1. 中途两页都在场，且严格左右分开（不相交），间距只是抗锯齿。
        for (i in 1..3) {
            val out = assertNotNull(
                outgoing[i],
                "the outgoing page vanished at ${label(i)} instead of sliding",
            )
            val incomingPage = assertNotNull(
                incoming[i],
                "the incoming page is missing at ${label(i)}: a slide must show both pages",
            )
            assertTrue(
                out.maxX < incomingPage.minX,
                "the pages overlap at ${label(i)}: outgoing $out, incoming $incomingPage",
            )
            val gap = incomingPage.minX - out.maxX
            assertTrue(
                gap <= 3,
                "the pages are not adjacent at ${label(i)} (gap ${gap}px): " +
                    "in and out must share one duration or they stop abutting",
            )
        }

        // 2. 整体同向单调：出页的推进边一直向左，入页的左边界也一直向左。
        for (i in 1 until 3) {
            val out = outgoing[i]!!
            val nextOut = outgoing[i + 1]!!
            val incomingPage = incoming[i]!!
            val nextIncoming = incoming[i + 1]!!
            assertTrue(
                nextOut.maxX < out.maxX,
                "the outgoing page stopped moving left between ${label(i)} and ${label(i + 1)} " +
                    "(own edge ${out.maxX} → ${nextOut.maxX})",
            )
            assertTrue(
                nextIncoming.minX < incomingPage.minX,
                "the incoming page stopped moving left between ${label(i)} and ${label(i + 1)} " +
                    "(${incomingPage.minX} → ${nextIncoming.minX})",
            )
        }

        // 3. 收尾精确：出页消失，入页 offset 恰好为 0。
        for (i in 4..5) {
            assertNull(outgoing[i], "the outgoing page is still painted at ${label(i)}")
            val landed = assertNotNull(incoming[i], "the incoming page is missing after the move")
            assertEquals(0, landed.minX, "the incoming page did not land at offset 0 (${label(i)})")
            assertEquals(
                widthPx - 1,
                landed.maxX,
                "the incoming page did not cover the whole panel (${label(i)})",
            )
        }

        // 邻居不动：页眉带、工具条带逐帧纯色。
        for ((i, frame) in frames.withIndex()) {
            assertBandsUntouched(frame, "right move ${label(i)}")
        }
    }

    @Test
    fun movingLeftMirrorsTheSlide() {
        val frames = renderColumns(1f, EditorTool.Text, EditorTool.Params, clipContent = false)
        // 入页永远是绿色（它总是 `second`），出页永远是红色，两个方向都成立。
        val outgoing = frames.map { pageSpan(it, red = true) }
        val incoming = frames.map { pageSpan(it, red = false) }
        val label = { i: Int -> "+${frameOffsetsMs[i]} ms" }

        assertEquals(0, assertNotNull(outgoing[0]).minX, "the resting page must start at the left edge")
        assertNull(incoming[0], "the incoming page is already visible at 0 ms")

        for (i in 1..3) {
            val out = assertNotNull(outgoing[i], "the outgoing page vanished at ${label(i)}")
            val incomingPage = assertNotNull(incoming[i], "the incoming page is missing at ${label(i)}")
            assertTrue(
                incomingPage.maxX < out.minX,
                "the pages overlap at ${label(i)}: outgoing $out, incoming $incomingPage",
            )
            assertTrue(
                out.minX - incomingPage.maxX <= 3,
                "the pages are not adjacent at ${label(i)} (gap ${out.minX - incomingPage.maxX}px)",
            )
        }
        for (i in 1 until 3) {
            assertTrue(
                outgoing[i + 1]!!.minX > outgoing[i]!!.minX,
                "the outgoing page stopped moving right between ${label(i)} and ${label(i + 1)}",
            )
            assertTrue(
                incoming[i + 1]!!.maxX > incoming[i]!!.maxX,
                "the incoming page stopped moving right between ${label(i)} and ${label(i + 1)}",
            )
        }
        for (i in 4..5) {
            assertNull(outgoing[i], "the outgoing page is still painted at ${label(i)}")
            val landed = assertNotNull(incoming[i], "the incoming page is missing after the move")
            assertEquals(0, landed.minX, "the incoming page did not land at offset 0 (${label(i)})")
            assertEquals(widthPx - 1, landed.maxX, "the incoming page did not fill the panel")
        }
        for ((i, frame) in frames.withIndex()) {
            assertBandsUntouched(frame, "left move ${label(i)}")
        }
    }

    // ---- the two boundaries the slide must not cross -----------------------

    @Test
    fun aPageTallerThanTheParameterAreaIsClippedToIt() {
        // 内容 90 dp 超高、并且从 -20 dp 起画。没有裁切，这 90 dp 会直接盖住页眉带和工具条带。
        val frames = renderColumns(1f, EditorTool.Params, EditorTool.Text, clipContent = true)
        for ((i, frame) in frames.withIndex()) {
            assertBandsUntouched(frame, "clipped slide +${frameOffsetsMs[i]} ms")
            // 裁切不能把内容整个裁没：参数区里必须还有页。
            assertTrue(
                pageSpan(frame, red = true) != null || pageSpan(frame, red = false) != null,
                "the clipped scene painted nothing at +${frameOffsetsMs[i]} ms",
            )
        }
    }

    @Test
    fun reducedMotionSwapsOnTheFirstFrame() {
        val frames = renderColumns(0f, EditorTool.Params, EditorTool.Text, clipContent = false)
        val landed = assertNotNull(
            pageSpan(frames.first(), red = false),
            "the incoming page is missing under reduced motion",
        )
        assertEquals(0, landed.minX, "reduced motion must land instantly, not slide")
        assertEquals(widthPx - 1, landed.maxX)
        assertNull(pageSpan(frames.first(), red = true), "the old page survived the instant swap")
        for ((i, frame) in frames.withIndex()) {
            assertBandsUntouched(frame, "reduced motion +${frameOffsetsMs[i]} ms")
            assertNull(pageSpan(frame, red = true), "the old page came back at +${frameOffsetsMs[i]} ms")
            assertEquals(
                0,
                assertNotNull(pageSpan(frame, red = false)).minX,
                "reduced motion drifted at +${frameOffsetsMs[i]} ms",
            )
        }
    }

    /** 页眉带必须整条是 headerBlue、工具条带整条是 barCyan：换页不许惊动邻居。 */
    private fun assertBandsUntouched(frame: BufferedImage, whenLabel: String) {
        for (y in intArrayOf(headerTopPx + 2, (headerTopPx + headerBottomPx) / 2)) {
            for (x in 0 until frame.width step 4) {
                assertTrue(
                    isBlue(frame.getRGB(x, y)),
                    "something painted over the header at ($x, $y) $whenLabel",
                )
            }
        }
        for (y in intArrayOf(barTopPx + 2, (barTopPx + barBottomPx) / 2)) {
            for (x in 0 until frame.width step 4) {
                assertTrue(
                    isCyan(frame.getRGB(x, y)),
                    "something painted over the tool bar at ($x, $y) $whenLabel",
                )
            }
        }
    }

    // ---- the same slide on the real sheet ----------------------------------

    /**
     * 真实 `PhoneSheetHost` 的一行：列 = 0 / +55 / +110 / +165 / +220 / +280 ms。
     *
     * 上面那几行用纯色替身把**几何**钉死；这一行换回真实页面（参数行、文字行、Logo 行），
     * 让验收的人看到的是真的那一屏，而不是红绿方块。起点同样靠"画面第一次变化"校准。
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun realSheetColumns(): List<BufferedImage> {
        val model = stripModel()
        val session = DesignSession().also { it.seed(model.controls) }
        val tabs = inspectorTabsFor(model, session, hasTextSlots = true)
        val chrome = phoneParameterSheetChrome(1f)
        val sheetPx = 307.7.dp
        var tool by mutableStateOf(EditorTool.Params)
        val scene = ImageComposeScene(
            width = (411 * realDensity).toInt(),
            height = (905 * realDensity).toInt(),
            density = Density(realDensity),
        ) {
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
                        density = Density(realDensity),
                        tool = tool,
                        tabModel = tabs,
                        card = stripCard(),
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
            val before = scene.renderFrame(t0)
            tool = EditorTool.Text
            // 真实页面没有纯色替身可以量进度，所以只校准**起点**：画面第一次与换页前不同的
            // 那一帧（4 ms 步长，误差 < 4 ms），从它起算 0 ms。
            var probe = t0
            var start = t0
            while (true) {
                probe += 4_000_000L
                require(probe < t0 + 400_000_000L) { "the real sheet never started sliding" }
                if (framesDiffer(before, scene.renderFrame(probe))) {
                    start = probe
                    break
                }
            }
            return listOf(before) + frameOffsetsMs.drop(1).map { ms ->
                scene.renderFrame(start + ms * 1_000_000L)
            }
        } finally {
            scene.close()
        }
    }

    private fun framesDiffer(a: BufferedImage, b: BufferedImage): Boolean {
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return true
                x += 8
            }
            y += 8
        }
        return false
    }

    private fun stripCard() = LibraryCard(
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

    private fun stripControl(id: String, label: String, initial: ControlValue) = InspectorControl(
        key = "card::$id",
        controlId = id,
        param = id,
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

    private fun stripModel(): InspectorCardModel {
        val controls = listOf(
            stripControl("params:footerPct", "底部留白", ControlValue.Num(12f)),
            stripControl("params:insetX", "左右留白", ControlValue.Num(8f)),
            stripControl("params:insetY", "上下留白", ControlValue.Num(10f)),
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

    // ---- frame strip --------------------------------------------------------

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
        val cell = 260
        val gap = 10
        val labelH = 26
        val columns = rows.maxOf { it.second.size }
        val cellH = rows.maxOf { row ->
            (row.second.first().height.toFloat() * cell / row.second.first().width).toInt()
        }
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
            g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 13)
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
     * 产出证据帧带：右移一行、左移一行、reduce-motion 一行，列 = 0 / +55 / +110 / +165 /
     * +220 / +280 ms。这些帧与上面几条测试用的是同一批像素，所以"帧带好看"与"断言通过"
     * 不可能各说各话。
     */
    @Test
    fun theSlideFramesAreCapturedIntoTheBatchTwoStrip() {
        val right = renderColumns(1f, EditorTool.Params, EditorTool.Text, clipContent = false)
        val left = renderColumns(1f, EditorTool.Text, EditorTool.Params, clipContent = false)
        val still = renderColumns(0f, EditorTool.Params, EditorTool.Text, clipContent = false)
        val columns = frameOffsetsMs.map { "+$it ms" }

        val sheet = contactSheet(
            listOf(
                ("page slide →  right  Params→Text · ${FmDuration.pageSlide} ms FmEasing.standard — " +
                    columns.joinToString(" / ")) to right,
                ("page slide ←  left  Text→Params — ${columns.joinToString(" / ")}") to left,
                ("reduce motion (scale 0) — instant swap — ${columns.joinToString(" / ")}") to still,
            ),
        )
        val dir = java.nio.file.Paths.get("build", "motion-strip").toAbsolutePath()
        java.nio.file.Files.createDirectories(dir)
        // 上一轮留下的帧比这一轮多时会把接触表之外的文件留在目录里，先清掉自己的前缀。
        val stale = java.nio.file.Files.list(dir).use { paths ->
            paths.filter { it.fileName.toString().startsWith("page-") }.toList()
        }
        stale.forEach { java.nio.file.Files.deleteIfExists(it) }
        javax.imageio.ImageIO.write(sheet, "png", dir.resolve("motion-batch2-strip.png").toFile())
        right.forEachIndexed { i, frame ->
            javax.imageio.ImageIO.write(frame, "png", dir.resolve("page-right-$i.png").toFile())
        }
        left.forEachIndexed { i, frame ->
            javax.imageio.ImageIO.write(frame, "png", dir.resolve("page-left-$i.png").toFile())
        }
        still.forEachIndexed { i, frame ->
            javax.imageio.ImageIO.write(frame, "png", dir.resolve("page-still-$i.png").toFile())
        }
        assertTrue(
            java.nio.file.Files.exists(dir.resolve("motion-batch2-strip.png")),
            "the strip was not written",
        )
        for (row in listOf(right, left, still)) {
            assertEquals(columns.size, row.size, "the strip rows must match the column labels")
        }
    }

    /**
     * 真实底板的一行（同一批时刻），单独一张 PNG：这是给验收人看的那一屏。
     * 从"参数区 边框/调整"页滑到"文字"页，页头、tab 行、工具条都该纹丝不动。
     */
    @Test
    fun theRealSheetSlideIsCapturedBesideTheGeometryStrip() {
        val frames = realSheetColumns()
        assertEquals(frameOffsetsMs.size, frames.size, "one frame per strip column")
        // 帧带必须真的抓到换页：首尾不同，否则这一行只是同一张图重复六次。
        assertTrue(
            framesDiffer(frames.first(), frames.last()),
            "the real sheet never reached the other page",
        )
        val columns = frameOffsetsMs.map { "+$it ms" }
        val sheet = contactSheet(
            listOf(
                ("real sheet  PhoneSheetHost · 调整→文字 · full-width slide — " +
                    columns.joinToString(" / ")) to frames,
            ),
        )
        val dir = java.nio.file.Paths.get("build", "motion-strip").toAbsolutePath()
        java.nio.file.Files.createDirectories(dir)
        javax.imageio.ImageIO.write(sheet, "png", dir.resolve("motion-batch2-sheet-strip.png").toFile())
        frames.forEachIndexed { i, frame ->
            javax.imageio.ImageIO.write(frame, "png", dir.resolve("sheet-slide-$i.png").toFile())
        }
        assertTrue(
            java.nio.file.Files.exists(dir.resolve("motion-batch2-sheet-strip.png")),
            "the real-sheet strip was not written",
        )
    }
}
