package com.framemark.native.shell

import com.framemark.native.shell.phone.PhoneTestControls
import com.framemark.native.shell.phone.PhoneTouchTarget
import com.framemark.native.shell.phone.phoneParamNumberText
import com.framemark.native.shell.phone.phoneParamValueLabel
import com.framemark.native.workflow.DesignSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 固定画框（`PhonePanelLayout.kt`）的验收 —— 2026-11-10，照 Adobe Lightroom 手机版
 * 在本机（411×905 dp 竖屏）上的量法。
 *
 * 这一份替换掉旧的 `PhonePageLayoutTest`（那 12 条测试断言的是「按内容算高度 + 横滑
 * 分页」：`theDensifiedPageTableIsTheMeasuredOne` / `everyPageHitsItsTargetOnEveryCarriedWindow`
 * / `everyPageFitsTheWindowAllowanceAndTheDecidedLevel` / `aShortPageOpensShorterThanALongPage`
 * / `thePhotoStripShortensTheWatermarkSheetBecauseTheAllowanceShrinks` 等）。分页机器
 * 已随 `PhonePageLayout.kt` 一起删除，那些断言的对象不存在了。
 *
 * 现在钉住的是四件事：
 *  1. 面板高度 = `clamp(0.34 × 窗口, 280, 330)`，**四个工具同一个数**，与内容无关；
 *  2. 画布 = 剩下的空间，工具变了它不动；
 *  3. 参数行是两行组，轨道 ≥ 内容宽的 85 %（411 dp 屏 = 379 dp）；
 *  4. 组 tab 覆盖每个控件恰好一次，放不下时靠横滑（不是裁字 / 换行）。
 */
class PhonePanelFrameTest {

    private val tools = listOf(
        EditorTool.Params,
        EditorTool.Text,
        EditorTool.Logo,
        EditorTool.Watermark,
    )

    /** 载重窗口：390×844 / 360×800 / 412×915 / 886×886，外加夹到上下限的两档。 */
    private val windows = listOf(844f, 800f, 915f, 886f, 700f, 1200f)

    // -----------------------------------------------------------------------
    // 1. 固定面板高度
    // -----------------------------------------------------------------------

    @Test
    fun thePanelHeightIsTheMeasuredFractionClamped() {
        assertEquals(PHONE_PANEL_FRACTION, 0.34f)
        assertEquals(280f, PHONE_PANEL_MIN_DP)
        assertEquals(330f, PHONE_PANEL_MAX_DP)
        // 量到的那台机器：411×905 → 307.7 dp（905 的 34 %）。
        assertEquals(307.7f, phonePanelHeightDp(905f), 0.01f)
        assertEquals(905f * 0.34f, phonePanelHeightDp(905f), 0.01f)
        assertEquals(844f * 0.34f, phonePanelHeightDp(844f), 0.01f)
        // 比例低于下夹 → 280；高于上夹 → 330。
        assertEquals(PHONE_PANEL_MIN_DP, phonePanelHeightDp(700f), 0.01f)
        assertEquals(PHONE_PANEL_MIN_DP, phonePanelHeightDp(823f), 0.01f)
        assertEquals(PHONE_PANEL_MAX_DP, phonePanelHeightDp(1200f), 0.01f)
        assertEquals(PHONE_PANEL_MAX_DP, phonePanelHeightDp(971f), 0.01f)
        // 量不出来的窗口不猜一个高度。
        assertEquals(0f, phonePanelHeightDp(Float.NaN))
        assertEquals(0f, phonePanelHeightDp(0f))
        assertEquals(0f, phonePanelHeightDp(-5f))
        assertEquals(0f, phonePanelHeightDp(Float.POSITIVE_INFINITY))
    }

    private fun controlsFor(tool: EditorTool) = when (tool) {
        EditorTool.Params -> PhoneTestControls.styleControls()
        EditorTool.Text -> PhoneTestControls.textControls()
        EditorTool.Logo -> PhoneTestControls.logoControls()
        EditorTool.Watermark -> emptyList()
        EditorTool.Border -> emptyList()
    }

    @Test
    fun aFixedPanelHeightForEveryToolAtAGivenWindow() {
        windows.forEach { window ->
            val expected = phonePanelHeightDp(window)
            val heights = tools.map { tool ->
                val h = computePhoneEditorHeights(
                    availableHeightDp = window,
                    fontScale = 1f,
                    imeVisible = false,
                    tool = tool,
                    photosOpen = false,
                )
                assertFalse(h.fullPage, "tool=$tool window=$window must keep the fixed panel")
                assertEquals(expected, h.sheet, 0.01f, "tool=$tool window=$window")
                h
            }
            // 「同一个数」不只是和常量比：四个工具之间也两两相等。
            assertEquals(1, heights.map { it.sheet }.distinct().size, "window=$window ${heights.map { it.sheet }}")
            assertEquals(1, heights.map { it.stage }.distinct().size, "window=$window stage must not move")
        }
    }

    @Test
    fun thePanelHeightDoesNotReadTheContent() {
        // 同一个工具、同一种窗口：空卡 / 一排 / 很长的控件表，读到同一个面板。
        val window = 905f
        val empty = computePhoneEditorHeights(window, 1f, false, EditorTool.Params, false)
        val long = computePhoneEditorHeights(window, 1f, false, EditorTool.Logo, false)
        val tiny = computePhoneEditorHeights(window, 1f, false, EditorTool.Text, false)
        assertEquals(empty.sheet, long.sheet, 0.01f)
        assertEquals(empty.sheet, tiny.sheet, 0.01f)
        // 而且它真的等于那个常量（不是碰巧相等）。
        assertEquals(phonePanelHeightDp(window), empty.sheet, 0.01f)
        // 内容长度相同与否都不影响：控件表根本不进 `computePhoneEditorHeights` 的签名。
        assertEquals(
            phonePanelHeightDp(window),
            computePhoneEditorHeights(window, 1f, false, EditorTool.Params, false).sheet,
            0.01f,
        )
    }

    @Test
    fun theCanvasIsEverythingAboveThePanel() {
        windows.forEach { window ->
            tools.forEach { tool ->
                val h = computePhoneEditorHeights(window, 1f, false, tool, photosOpen = false)
                assertEquals(
                    window - TOP_BAR_DP - TOOL_BAR_DP - phonePanelHeightDp(window),
                    h.stage,
                    0.01f,
                    "tool=$tool window=$window",
                )
                assertTrue(h.stage >= PHONE_PANEL_MIN_STAGE_DP, "tool=$tool window=$window stage=${h.stage}")
            }
        }
        // 照片条打开时它只从画布那一侧扣，面板一个 dp 都不动。
        val closed = computePhoneEditorHeights(800f, 1f, false, EditorTool.Params, photosOpen = false)
        val open = computePhoneEditorHeights(800f, 1f, false, EditorTool.Params, photosOpen = true)
        assertEquals(closed.sheet, open.sheet, 0.01f)
        assertEquals(STRIP_DP, closed.stage - open.stage, 0.01f)
    }

    @Test
    fun theFixedPanelNeedsAUsableStageOrTheFullPageWins() {
        // 固定面板 + 顶栏 + 工具条之后不足 PHONE_PANEL_MIN_STAGE_DP 的窗口退回整页，
        // 而不是把照片压成一条缝。
        val threshold = PHONE_PANEL_MIN_STAGE_DP + TOP_BAR_DP + TOOL_BAR_DP + PHONE_PANEL_MIN_DP
        assertEquals(FORCED_PREVIEW_MIN_DP, threshold, 0.01f)
        assertTrue(computePhoneEditorHeights(threshold, 1f, false, EditorTool.Params, false).fullPage.not())
        assertTrue(computePhoneEditorHeights(threshold - 10f, 1f, false, EditorTool.Params, false).fullPage)
        // 大字号仍是整页（窗口的决定，不是工具的决定）。
        assertTrue(computePhoneEditorHeights(905f, 1.5f, false, EditorTool.Watermark, false).fullPage)
    }

    @Test
    fun leavingTheFullPageComesBackToTheSameFixedPanel() {
        val h = forcedPreviewHeights(905f)
        assertFalse(h.fullPage)
        assertEquals(phonePanelHeightDp(905f), h.sheet, 0.01f)
        assertEquals(52f, h.topBar)
        assertEquals(62f, h.toolBar)
        assertTrue(h.stage >= PHONE_PANEL_MIN_STAGE_DP)
        // 放不下就留在整页。
        assertTrue(forcedPreviewHeights(FORCED_PREVIEW_MIN_DP - 1f).fullPage)
    }

    // -----------------------------------------------------------------------
    // 2. 两行行的几何：标签 / 数值 / 整宽轨道
    // -----------------------------------------------------------------------

    @Test
    fun theTrackIsAlmostTheWholeContentWidth() {
        // 411 dp 屏：左右各 16 dp 内边距 → 内容 379 dp，轨道就是整条内容宽。
        val frame = phoneParamRowFrame(411f)
        assertEquals(379f, frame.contentWidthDp, 0.01f)
        assertEquals(379f, frame.trackWidthDp, 0.01f)
        assertEquals(1f, frame.trackShare, 0.0001f)
        assertTrue(
            frame.trackWidthDp >= PHONE_PARAM_TRACK_MIN_FRACTION * frame.contentWidthDp,
            "track ${frame.trackWidthDp} < 85 % of ${frame.contentWidthDp}",
        )
        // 轨道占屏宽 92.2 % —— 量到的「≈ 屏宽 90 %」。
        assertEquals(0.922f, frame.trackWidthDp / 411f, 0.002f)
        assertEquals(411f - 2f * PHONE_PARAM_INSET_DP, frame.contentWidthDp, 0.01f)
        assertEquals(PHONE_PARAM_VALUE_BAND_DP, frame.valueWidthDp, 0.01f)
        assertEquals(frame.contentWidthDp - frame.valueWidthDp, frame.labelWidthDp, 0.01f)
        assertTrue(frame.labelWidthDp > 0f, "the label band must exist: ${frame.labelWidthDp}")
        // 窄屏也一样（比例不随屏宽变），量不出来的屏宽不产生负宽度。
        assertTrue(phoneParamRowFrame(360f).trackShare >= PHONE_PARAM_TRACK_MIN_FRACTION)
        assertEquals(0f, phoneParamRowFrame(0f).contentWidthDp)
        assertEquals(0f, phoneParamRowFrame(Float.NaN).contentWidthDp)
        assertEquals(phoneParamRowFrame(411f), phoneParamRowFrame(411f))
    }

    @Test
    fun everyParameterRowHasALabelAValueAndATrack() {
        val frame = phoneParamRowFrame(411f)
        val themeOptions = PhoneTestControls.styleControls()
        val cases = listOf(
            EditorTool.Params to themeOptions,
            EditorTool.Text to PhoneTestControls.textControls(),
            EditorTool.Logo to PhoneTestControls.logoControls(),
            EditorTool.Watermark to emptyList(),
        )
        var sliderRows = 0
        cases.forEach { (tool, controls) ->
            val spec = PhonePageSpec(tool, controls, hasTextReport = tool == EditorTool.Text)
            val session = DesignSession().also { it.seed(controls) }
            phonePanelRows(spec).filter { it.kind == PhoneRowKind.Slider }.forEach { row ->
                sliderRows++
                assertTrue(row.label.isNotBlank(), "${tool}: a parameter row needs a label")
                // 量到的行里没有省略号：标签是短文案（放不下就改文案，不缩字号）。
                assertTrue(row.label.length <= 8, "${tool}: '${row.label}' is too long for a 15 sp row")
                // 行距就是量到的 64 dp，且远高于 44 dp 触控地板。
                assertEquals(PHONE_PARAM_ROW_DP, row.heightDp, 0.01f, "${tool}: ${row.label}")
                assertTrue(row.heightDp >= PHONE_PARAM_ROW_MIN_TOUCH_DP, "${tool}: ${row.label}")
                assertTrue(frame.trackWidthDp >= PHONE_PARAM_TRACK_MIN_FRACTION * frame.contentWidthDp)
                // 有数值：声明控件的数值由纯函数格式化；水印那六条由面板自己格式化，
                // 这里断言格式化函数本身输出非空。
                val control = row.control
                if (control != null) {
                    assertTrue(
                        phoneParamValueLabel(control, session).isNotBlank(),
                        "${tool}: ${row.label} has no value text",
                    )
                } else {
                    assertTrue(phoneParamNumberText(0f, 1, "%").isNotBlank())
                }
            }
        }
        assertEquals(9, sliderRows, "样式的底部留白 + Logo 两条 + 水印五条 = 九条数值行")
    }

    @Test
    fun theLabelsAreTheShortMeasuredOnes() {
        val session = DesignSession()
        val style = PhoneTestControls.styleControls()
        session.seed(style)
        assertEquals(
            listOf("底色", "版式", "页脚自适应", "底部留白"),
            phonePanelRows(PhonePageSpec(EditorTool.Params, style)).map { it.label },
        )
        val logo = PhoneTestControls.logoControls()
        assertEquals(
            listOf("显示 Logo", "Logo 位置", "Logo 大小", "水平位置", "品牌字标"),
            phonePanelRows(PhonePageSpec(EditorTool.Logo, logo)).map { it.label },
        )
        assertEquals(
            listOf("图层", "距左", "距上", "宽度", "不透明度", "旋转"),
            phonePanelRows(PhonePageSpec(EditorTool.Watermark, emptyList())).map { it.label },
        )
    }

    @Test
    fun theRowPitchAndTouchFloorMatchTheMeasurement() {
        // 一行 = 标签行 26 + 轨道行 26 = 52 内容，加 12 行间 = 量到的 64 dp 行距。
        assertEquals(64f, PHONE_PARAM_ROW_DP)
        assertEquals(52f, PHONE_PARAM_ROW_CONTENT_DP)
        assertEquals(12f, PHONE_PARAM_ROW_GAP_DP)
        assertEquals(PHONE_PARAM_ROW_CONTENT_DP, PHONE_PARAM_LABEL_LINE_DP + PHONE_PARAM_TRACK_LINE_DP)
        assertEquals(PHONE_PARAM_ROW_DP, PHONE_PARAM_ROW_CONTENT_DP + PHONE_PARAM_ROW_GAP_DP)
        assertEquals(15f, PHONE_PARAM_LABEL_SP)
        assertEquals(15f, PHONE_PARAM_VALUE_SP)
        assertTrue(PHONE_PARAM_ROW_CONTENT_DP >= PHONE_PARAM_ROW_MIN_TOUCH_DP)
        assertEquals(44f, PHONE_PARAM_ROW_MIN_TOUCH_DP)
        assertEquals(PhoneTouchTarget.value, PHONE_PARAM_ROW_MIN_TOUCH_DP, "与 PhoneTouchTarget 同一个数")
    }

    // -----------------------------------------------------------------------
    // 3. 滚动前能看几行
    // -----------------------------------------------------------------------

    @Test
    fun threeNumericRowsFitBeforeTheParameterAreaScrolls() {
        val window = 905f
        val panel = phonePanelHeightDp(window)
        val sliders = phonePanelRows(PhonePageSpec(EditorTool.Watermark, emptyList()))
            .filter { it.kind == PhoneRowKind.Slider }
        assertEquals(5, sliders.size, "水印面板的五条数值行")
        // 三条一行 64 dp 的行 = 192 dp，参数区在 905 dp 的窗口里是 219.7 dp。
        assertEquals(3, phoneVisibleRowCount(panel, sliders))
        assertEquals(192f, sliders.take(3).sumOf { it.heightDp.toDouble() }.toFloat(), 0.01f)
        val area = phoneParameterAreaHeightDp(panel)
        assertEquals(panel - phoneParameterSheetChrome().totalDp - PHONE_PANEL_TAB_ROW_DP, area, 0.01f)
        assertTrue(area >= 3f * PHONE_PARAM_ROW_DP - 0.01f, "area=$area")
        assertTrue(area < 4f * PHONE_PARAM_ROW_DP, "a fourth row must need the scroll: area=$area")
        // 下夹 280 dp 的最紧窗口也是三条（192 ≤ 192）。
        assertEquals(3, phoneVisibleRowCount(PHONE_PANEL_MIN_DP, sliders))
        // 面板高到上夹 330 dp 仍是三条：多出来的 40 dp 是呼吸，不是第四行。
        assertEquals(3, phoneVisibleRowCount(PHONE_PANEL_MAX_DP, sliders))
        assertEquals(0, phoneVisibleRowCount(0f, sliders))
    }

    @Test
    fun theWidestGroupsFitWithoutScrollingOnTheMeasuredWindow() {
        val panel = phonePanelHeightDp(905f)
        // 2026-11-11 重新分组后每一组都是 2..3 行：最大的组（三条 64 dp 的数值行）也要
        // 完整露出。文字页有报告时是两条（选择器 104 + 字号 64）。
        val cases = mapOf(
            PhonePageSpec(EditorTool.Params, PhoneTestControls.styleControls()) to 2,
            PhonePageSpec(EditorTool.Watermark, emptyList()) to 3,
            PhonePageSpec(EditorTool.Text, PhoneTestControls.textControls(), hasTextReport = true) to 2,
            PhonePageSpec(EditorTool.Text, PhoneTestControls.textControls()) to 3,
            PhonePageSpec(EditorTool.Logo, PhoneTestControls.logoControls()) to 3,
        )
        cases.forEach { (spec, maxRows) ->
            phonePanelGroups(spec).forEach { group ->
                assertTrue(
                    phoneVisibleRowCount(panel, group.rows) >= maxRows.coerceAtMost(group.rows.size),
                    "${spec.tool} · ${group.label}: ${group.rows.size} rows, " +
                        "${phoneVisibleRowCount(panel, group.rows)} visible",
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // 4. 组 tab
    // -----------------------------------------------------------------------

    @Test
    fun theGroupTabsAreTheMeasuredOnes() {
        // 2026-11-11：按「每组 2..3 行」重新分组（旧的分组见 git 历史：
        // 样式 `[底色｜版式｜留白]`、文字 `[选择｜大小]`、Logo `[调整｜动作]`、
        // 水印 `[图层｜边距｜外观]`，第一/第三组各只有一行）。
        assertEquals(
            listOf("外观", "留白"),
            phoneGroupTabLabels(PhonePageSpec(EditorTool.Params, PhoneTestControls.styleControls())),
        )
        assertEquals(
            listOf("文字"),
            phoneGroupTabLabels(
                PhonePageSpec(EditorTool.Text, PhoneTestControls.textControls(), hasTextReport = true),
            ),
        )
        assertEquals(
            listOf("显示", "调整"),
            phoneGroupTabLabels(PhonePageSpec(EditorTool.Logo, PhoneTestControls.logoControls())),
        )
        assertEquals(
            listOf("图层", "外观"),
            phoneGroupTabLabels(PhonePageSpec(EditorTool.Watermark, emptyList())),
        )
        // 空组不出现：只有底色的轻量卡（一张卡真的只有一个控件）只剩一组。
        assertEquals(
            listOf("外观"),
            phoneGroupTabLabels(PhonePageSpec(EditorTool.Params, PhoneTestControls.lightStyleControls())),
        )
        // 边框工具没有参数面板（落地面板是 `PhoneBorderPanel`）。
        assertTrue(phoneGroupTabLabels(PhonePageSpec(EditorTool.Border, emptyList())).isEmpty())
        // 水印空态只有 图层 一组（上传入口，是空态不是参数组）。
        assertEquals(
            listOf("图层"),
            phoneGroupTabLabels(
                PhonePageSpec(EditorTool.Watermark, emptyList(), hasWatermarkLayer = false),
            ),
        )
    }

    @Test
    fun theGroupTabsCoverEveryControlExactlyOnce() {
        fun rows(spec: PhonePageSpec) = phonePanelGroups(spec).flatMap { it.rows }

        val styleControls = PhoneTestControls.styleControls()
        val styleRows = rows(PhonePageSpec(EditorTool.Params, styleControls))
        assertEquals(
            styleControls.map { it.key }.sorted(),
            styleRows.mapNotNull { it.control?.key }.sorted(),
            "样式的每个控件必须在且只在一个组里",
        )
        assertEquals(styleRows.size, styleRows.map { it.key }.distinct().size, "行键不能重复")

        val textControls = PhoneTestControls.textControls()
        // 有渲染器报告时，文本框选择器取代声明行 —— 只有字号进面板。
        val textRows = rows(PhonePageSpec(EditorTool.Text, textControls, hasTextReport = true))
        assertEquals(listOf("card::text:fontScale"), textRows.mapNotNull { it.control?.key })
        assertEquals(setOf(PhoneRowKeys.TEXT_SLOTS), textRows.filter { it.control == null }.map { it.key }.toSet())
        // 没有报告时，每一条声明行都在、且只出现一次。
        val staticRows = rows(PhonePageSpec(EditorTool.Text, textControls, hasTextReport = false))
        assertEquals(
            textControls.map { it.key }.sorted(),
            staticRows.mapNotNull { it.control?.key }.sorted(),
        )

        val logoControls = PhoneTestControls.logoControls()
        val logoRows = rows(PhonePageSpec(EditorTool.Logo, logoControls))
        assertEquals(
            logoControls.map { it.key }.sorted(),
            logoRows.mapNotNull { it.control?.key }.sorted(),
        )
        // 品牌字标排在最后一组「调整」里（同组还有 大小 / 水平位置）。
        val actionGroup = phonePanelGroups(PhonePageSpec(EditorTool.Logo, logoControls)).last()
        assertEquals("调整", actionGroup.label)
        assertEquals(
            listOf("card::params:logoScale", "card::params:logoCenterX", "card::design.logo"),
            actionGroup.rows.map { it.key },
        )

        // 水印的六行全是合成行（没有声明控件），键不重复。
        val wmRows = rows(PhonePageSpec(EditorTool.Watermark, emptyList()))
        assertEquals(6, wmRows.size)
        assertEquals(wmRows.size, wmRows.map { it.key }.distinct().size)
        assertEquals(
            setOf(
                PhoneRowKeys.WATERMARK_LAYER,
                PhoneRowKeys.WATERMARK_MARGIN_X,
                PhoneRowKeys.WATERMARK_MARGIN_Y,
                PhoneRowKeys.WATERMARK_WIDTH,
                PhoneRowKeys.WATERMARK_OPACITY,
                PhoneRowKeys.WATERMARK_ROTATION,
            ),
            wmRows.map { it.key }.toSet(),
        )
    }

    @Test
    fun theTabRowFitsOrAllowsHorizontalScroll() {
        // 三个两字 tab 在 411 dp 上放得下（内容宽 379 dp）……
        val fit = phoneTabRowFit(411f, listOf("底色", "版式", "留白"))
        assertFalse(fit.needsScroll, "three tabs must fit: $fit")
        assertTrue(fit.tabWidthDp <= fit.availableWidthDp + 0.01f)
        // ……四个工具的每一个 tab 行都放得下（放不下就必须横滑，composable 永远挂着
        // `horizontalScroll`；这里断言的是「这条 policy 认得溢出」）。
        val specs = listOf(
            PhonePageSpec(EditorTool.Params, PhoneTestControls.styleControls()),
            PhonePageSpec(EditorTool.Text, PhoneTestControls.textControls(), hasTextReport = true),
            PhonePageSpec(EditorTool.Logo, PhoneTestControls.logoControls()),
            PhonePageSpec(EditorTool.Watermark, emptyList()),
        )
        specs.forEach { spec ->
            val labels = phoneGroupTabLabels(spec)
            assertFalse(phoneTabRowFit(411f, labels).needsScroll, "${spec.tool}: $labels")
            assertFalse(phoneTabRowFit(360f, labels).needsScroll, "${spec.tool} @360: $labels")
        }
        // 真放不下时（8 组，量到的 Lightroom tab 行就是这么长）它必须认出来。
        val longRow = List(8) { "细节$it" }
        assertTrue(phoneTabRowFit(360f, longRow).needsScroll, "8 tabs cannot fit 360 dp: $longRow")
        assertTrue(phoneTabRowFit(411f, longRow).needsScroll)
        assertTrue(phoneTabRowFit(360f, emptyList()).needsScroll.not(), "an empty row never scrolls")
        // 每加一个 tab 只会更宽（排序与总宽都是单调的）。
        val two = phoneTabRowFit(411f, listOf("底色", "版式"))
        val three = phoneTabRowFit(411f, listOf("底色", "版式", "留白"))
        assertTrue(three.tabWidthDp > two.tabWidthDp)
    }

    @Test
    fun everyGroupHasALabelAndAtLeastOneRow() {
        val specs = listOf(
            PhonePageSpec(EditorTool.Params, PhoneTestControls.styleControls()),
            PhonePageSpec(EditorTool.Text, PhoneTestControls.textControls(), hasTextReport = true),
            PhonePageSpec(EditorTool.Logo, PhoneTestControls.logoControls()),
            PhonePageSpec(EditorTool.Watermark, emptyList()),
            PhonePageSpec(EditorTool.Watermark, emptyList(), hasWatermarkLayer = false),
        )
        specs.forEach { spec ->
            val groups = phonePanelGroups(spec)
            assertTrue(groups.isNotEmpty(), "${spec.tool} has no group")
            groups.forEach { group ->
                assertTrue(group.label.isNotBlank(), "${spec.tool}: a group needs a tab label")
                assertTrue(group.rows.isNotEmpty(), "${spec.tool} · ${group.label}: empty tab")
                assertTrue(group.id.isNotBlank())
            }
            assertEquals(groups.size, groups.map { it.id }.distinct().size, "${spec.tool}: duplicate group id")
            assertEquals(groups.size, groups.map { it.label }.distinct().size, "${spec.tool}: duplicate tab label")
        }
        // 页签 → 面板工具是一对一（边框没有参数面板）。
        assertEquals(EditorTool.Params, phonePanelToolFor(com.framemark.native.inspector.InspectorPage.Style))
        assertEquals(EditorTool.Text, phonePanelToolFor(com.framemark.native.inspector.InspectorPage.Text))
        assertEquals(EditorTool.Logo, phonePanelToolFor(com.framemark.native.inspector.InspectorPage.Logo))
        assertEquals(
            EditorTool.Watermark,
            phonePanelToolFor(com.framemark.native.inspector.InspectorPage.Watermark),
        )
    }

    @Test
    fun aVendorCardKeepsItsAnchorGridAndSpecInTabs() {
        val anchor = PhoneTestControls.control(
            id = "params:stockAnchor",
            param = "params.stockAnchor",
            label = "水印位置",
            type = com.framemark.native.inspector.ControlType.Select,
            kind = "anchorGrid",
            initial = com.framemark.native.inspector.ControlValue.Str("leftTop"),
            options = listOf(
                "leftTop", "centerTop", "rightTop", "leftCenter", "center",
                "rightCenter", "leftBottom", "centerBottom", "rightBottom",
            ).map { com.framemark.native.inspector.ControlOption(it, it) },
        )
        val spec = PhonePageSpec(
            tool = EditorTool.Params,
            controls = listOf(anchor),
            isIr = true,
            hasExportSize = true,
        )
        val groups = phonePanelGroups(spec)
        // 2026-11-11：九宫格 +「按规格」是「位置」组（2 行），只读规格 + 导出尺寸是
        // 「规格」组（2 行）。旧版把它们拆成「版式」（九宫格）/「留白」（规格）。
        assertEquals(listOf("位置", "规格"), groups.map { it.label })
        val position = groups.first()
        assertEquals(
            listOf(PhoneRowKeys.ANCHOR_GRID, PhoneRowKeys.ANCHOR_FOOTER),
            position.rows.map { it.key },
        )
        assertEquals(
            listOf(PhoneRowKeys.SPEC, PhoneRowKeys.EXPORT_SIZE),
            groups.last().rows.map { it.key },
        )
        // 厂商卡的九宫格仍然是一个 132 dp 的格子块（没有缩成下拉）。
        assertEquals(PhoneRowKind.AnchorGrid, position.rows.first().kind)
    }

    @Test
    fun theExportSizeRowStaysReachableOnTheStylePage() {
        val spec = PhonePageSpec(
            tool = EditorTool.Params,
            controls = PhoneTestControls.lightStyleControls(),
            hasExportSize = true,
        )
        val rows = phonePanelRows(spec)
        assertEquals(PhoneRowKeys.EXPORT_SIZE, rows.last().key, "轻量卡也要能读到导出尺寸")
        assertEquals(PhoneRowKind.ExportSize, rows.last().kind)
        // 没有照片像素时它不出现（不显示一个猜出来的数字）。
        assertFalse(
            phonePanelRows(PhonePageSpec(EditorTool.Params, PhoneTestControls.lightStyleControls()))
                .any { it.key == PhoneRowKeys.EXPORT_SIZE },
        )
    }

    @Test
    fun theShortestLabelWinsOnEveryDeclaredControl() {
        val session = DesignSession()
        val controls = PhoneTestControls.styleControls() + PhoneTestControls.logoControls()
        session.seed(controls)
        controls.forEach { control ->
            val label = phoneParamLabel(control)
            assertTrue(label.isNotBlank(), "control ${control.key} has no phone label")
            assertTrue(label.length <= 8, "$label is too long")
        }
        assertEquals("底色", phoneParamLabel(PhoneTestControls.theme()))
        assertEquals("版式", phoneParamLabel(PhoneTestControls.styleControls()[2]))
    }

    @Test
    fun theSheetLevelsKeepTheDragStopsOnTheFixedPanel() {
        val levels = assertNotNull(phoneSheetLevels(905f, EditorTool.Params, photosOpen = false))
        assertEquals(phonePanelHeightDp(905f), levels.compact, 0.01f)
        assertTrue(levels.half > levels.compact)
        assertTrue(levels.expanded > levels.half)
        // 四个工具的第一档完全相同：拖动只在用户自己拖的时候变。
        tools.forEach { tool ->
            val levelsForTool = assertNotNull(phoneSheetLevels(905f, tool, photosOpen = false))
            assertEquals(levels, levelsForTool, "tool=$tool")
        }
        // 放不下的窗口没有「收起档」可给：整页参数页接管。
        assertEquals(null, phoneSheetLevels(400f, EditorTool.Params, photosOpen = false))
    }

    @Test
    fun theBorderLandingPanelKeepsItsCardStack() {
        // 边框工具没有被固定画框接管：它的落地面板仍然是 128 dp 卡片那一摞。
        val levels = assertNotNull(phoneSheetLevels(844f, EditorTool.Border, photosOpen = false))
        assertEquals(300f, levels.compact, 0.01f)
        assertTrue(levels.expanded > levels.compact)
        val heights = computePhoneEditorHeights(844f, 1f, false, EditorTool.Border, false)
        assertEquals(300f, heights.sheet, 0.01f)
        assertTrue(heights.stage >= 844f * STAGE_MIN_FRACTION - 0.01f)
    }
}
