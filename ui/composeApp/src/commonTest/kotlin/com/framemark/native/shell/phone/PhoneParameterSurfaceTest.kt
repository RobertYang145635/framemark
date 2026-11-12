package com.framemark.native.shell.phone

import com.framemark.native.inspector.ControlOption
import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorPage
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.inspectorTabsFor
import com.framemark.native.shell.PHONE_PANEL_HEAD_DP
import com.framemark.native.shell.PHONE_PANEL_GRABBER_DP
import com.framemark.native.shell.PHONE_PANEL_TAB_ROW_DP
import com.framemark.native.shell.PHONE_PARAM_ROW_DP
import com.framemark.native.shell.PhoneGroupIds
import com.framemark.native.shell.PhonePageSpec
import com.framemark.native.shell.phonePanelGroups
import com.framemark.native.shell.phoneParamModeNote
import com.framemark.native.shell.phoneParamOptionLabel
import com.framemark.native.shell.phoneParamOptions
import com.framemark.native.shell.phoneParameterAreaHeightDp
import com.framemark.native.shell.phoneParameterSheetChrome
import com.framemark.native.shell.phonePanelHeightDp
import com.framemark.native.workflow.DesignSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 参数面的「面子」规则（固定画框版，2026-11-10）：
 *
 *  - 一行数值参数的标签行 / 轨道行的高度，与面板里 **唯一**的滚动区；
 *  - 数值文本与「点数值输入精确值」的解析（输入什么才会写回去）；
 *  - 分段选项的短文案（经典 / 简约 / 叠加，居左 / 居中 / 居右）；
 *  - 组 tab 行钉在页头下面，参数区才是滚动区。
 *
 * 旧的那三条（`thePinnedSummaryAppearsOnlyWhenTheToolZoneCanStillScroll` /
 * `theThresholdIsTheBandPlusOneSliderRow` / `thePinnedBandReadsTheSameExportSizeTheStylePageUsedTo`）
 * 断言的是被删掉的「公共区横条」；导出尺寸现在是样式页「留白」组里的一行
 * （`PhonePanelFrameTest.theExportSizeRowStaysReachableOnTheStylePage`）。
 */
class PhoneParameterSurfaceTest {

    // -----------------------------------------------------------------------
    // 参数面的几何：钉住的 tab 行 + 唯一的滚动区
    // -----------------------------------------------------------------------

    @Test
    fun theTabRowIsPinnedAboveOneScrollingParameterArea() {
        // 面板 = chrome（把手 16 + 标题行 28）+ tab 行 44 + 参数区。
        assertEquals(16f, PHONE_PANEL_GRABBER_DP)
        assertEquals(28f, PHONE_PANEL_HEAD_DP)
        assertEquals(44f, PHONE_PANEL_TAB_ROW_DP)
        assertEquals(44f, phoneParameterSheetChrome().totalDp, 0.01f)

        val panel = phonePanelHeightDp(905f)
        val area = phoneParameterAreaHeightDp(panel)
        assertEquals(panel - 44f - 44f, area, 0.01f)
        // 参数区放得下三整行 64 dp，第四行才需要滚。
        assertTrue(area >= 3f * PHONE_PARAM_ROW_DP - 0.01f, "area=$area")
        assertTrue(area < 4f * PHONE_PARAM_ROW_DP, "area=$area")
        // tab 行不会因为参数区变高而移动：它只读面板高度与固定 chrome。
        assertEquals(area, phoneParameterAreaHeightDp(panel), 0.01f)
        assertEquals(0f, phoneParameterAreaHeightDp(0f))
        assertEquals(0f, phoneParameterAreaHeightDp(Float.NaN))
        // 大字号只把标题行撑高（24 × 1.5 = 36），不会多出一行 chrome。
        val large = phoneParameterSheetChrome(1.5f)
        assertEquals(16f, large.grabberDp)
        assertEquals(36f, large.headDp)
        assertEquals(large.totalDp, phoneParameterSheetChrome(2f).totalDp, 0.01f, "钳到 1.5×")
    }

    // -----------------------------------------------------------------------
    // 数值：显示文本与输入
    // -----------------------------------------------------------------------

    @Test
    fun theValueTextIsTheMeasuredFormat() {
        // 量到的 `曝光度 …… -0.13`：两位小数带符号，不补多余的零。
        assertEquals("0.13", phoneParamNumberText(0.13f, 2, null))
        assertEquals("-0.13", phoneParamNumberText(-0.13f, 2, null))
        assertEquals("11", phoneParamNumberText(11f, 0, null))
        assertEquals("47%", phoneParamNumberText(47f, 0, "%"))
        assertEquals("12.5%", phoneParamNumberText(12.5f, 1, "%"))
        assertEquals("-45°", phoneParamNumberText(-45f, 0, "°"))
        // 数值框里不出现单位（单位在输入框右侧单独画）。
        assertEquals("7.5", phoneParamNumberText(7.5f, 1, null))
    }

    @Test
    fun theTypedValueIsParsedAndClamped() {
        assertNull(phoneTypedValue("", 0f, 100f))
        assertNull(phoneTypedValue("abc", 0f, 100f))
        assertNull(phoneTypedValue("NaN", 0f, 100f))
        assertEquals(0.5f, assertNotNull(phoneTypedValue("0,5", 0f, 1f)), 1e-6f, "逗号也可以当小数点")
        assertEquals(-0.13f, assertNotNull(phoneTypedValue("-0.13", -1f, 1f)), 1e-6f)
        // 越界夹到区间里，不写一个拖不到的值。
        assertEquals(100f, assertNotNull(phoneTypedValue("999", 0f, 100f)), 1e-6f)
        assertEquals(0f, assertNotNull(phoneTypedValue("-4", 0f, 100f)), 1e-6f)
        assertEquals(47f, assertNotNull(phoneTypedValue(" 47 ", 0f, 47f)), 1e-6f)
        // 反向区间（调用方写反）也能解析出结果，不会夹成 NaN。
        val reversed = assertNotNull(phoneTypedValue("5", 10f, 0f))
        assertTrue(reversed in 0f..10f)
    }

    @Test
    fun theDisplayScaleIsTheControlsUnitScale() {
        val plain = PhoneTestControls.slider("params:footerPct", label = "底部留白")
        assertEquals(1f, plain.scale(), 0.0001f)
        assertEquals("10", phoneParamNumberText(10f, 0, plain.unit))
        val scaled = plain.copy(unitScale = 100f)
        assertEquals(100f, scaled.scale(), 0.0001f)
        // 显示值 = 控件值 × unitScale（写回时再除回去）。
        assertEquals(55f, 0.55f * scaled.scale(), 0.0001f)
    }

    @Test
    fun theSegmentedCopyIsTheShortOne() {
        assertEquals("经典", phoneParamOptionLabel("classic"))
        assertEquals("简约", phoneParamOptionLabel("simple"))
        assertEquals("叠加", phoneParamOptionLabel("overlay"))
        assertEquals("居左", phoneParamOptionLabel("left"))
        assertEquals("居中", phoneParamOptionLabel("center"))
        assertEquals("居右", phoneParamOptionLabel("right"))
        assertEquals("X-T5", phoneParamOptionLabel("X-T5"), "不认识的选项原样显示")
        // 版式的三个选项按演示稿顺序排，未知项附后。
        val options = listOf(
            ControlOption("overlay", "overlay"),
            ControlOption("other", "other"),
            ControlOption("classic", "classic"),
        )
        assertEquals(
            listOf("classic", "overlay", "other"),
            phoneParamOptions(options).map { it.value },
        )
        assertEquals(listOf("经典", "叠加", "other"), phoneParamOptions(options).map { it.display })
        // 版式说明跟着选中的版式变（行里那句）。
        assertTrue(phoneParamModeNote("classic").isNotBlank())
        assertTrue(phoneParamModeNote("overlay").contains("画布"))
        assertEquals("", phoneParamModeNote("unknown"))
    }

    // -----------------------------------------------------------------------
    // 页签 → 参数面：每个控件落在恰好一组里
    // -----------------------------------------------------------------------

    private fun control(
        controlId: String,
        param: String,
        label: String,
        kind: String = "slider",
        type: ControlType = ControlType.Slider,
        initial: ControlValue = ControlValue.Num(10f),
        section: InspectorSection = InspectorSection.Layout,
        options: List<ControlOption> = emptyList(),
        hidden: Boolean = false,
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
        hidden = hidden,
    )

    private fun cardModel(): InspectorCardModel {
        val controls = listOf(
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
                "theme.theme",
                "theme",
                "底色主题",
                kind = "themeSwatches",
                type = ControlType.Select,
                initial = ControlValue.Str("white"),
                section = InspectorSection.Theme,
                options = listOf(ControlOption("white", "白")),
            ),
            control("text:fontScale", "fontScale", "整卡字号缩放", kind = "text", initial = ControlValue.Num(1.2f)),
        )
        return InspectorCardModel(
            cardId = "card",
            cardName = "卡",
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

    @Test
    fun eachDeclaredControlLandsInExactlyOneGroup() {
        // `InspectorTabs` 把每个控件分到恰好一页；面板再把每一页分到恰好一组。
        val model = cardModel()
        val session = DesignSession()
        session.seed(model.controls)
        val tabs = inspectorTabsFor(model, session, hasTextSlots = true)
        val styleTab = assertNotNull(tabs.tab(InspectorPage.Style))
        val textTab = assertNotNull(tabs.tab(InspectorPage.Text))
        val styleGroups = phonePanelGroups(PhonePageSpec(com.framemark.native.shell.EditorTool.Params, styleTab.controls))
        val textGroups = phonePanelGroups(PhonePageSpec(com.framemark.native.shell.EditorTool.Text, textTab.controls))
        val styleKeys = styleGroups.flatMap { group -> group.rows }.mapNotNull { it.control?.key }
        val textKeys = textGroups.flatMap { group -> group.rows }.mapNotNull { it.control?.key }
        assertEquals(styleTab.controls.map { it.key }.sorted(), styleKeys.sorted())
        assertEquals(textTab.controls.map { it.key }.sorted(), textKeys.sorted())
        // 样式页的控件只有 底色 + 版式 + 底部留白：重新分组后 底色 / 版式 在「外观」，
        // 底部留白只有一行，按「每组 2..3 行」并进外观（3 行）；不再有单行的 tab。
        assertEquals(
            listOf(PhoneGroupIds.STYLE_APPEARANCE),
            styleGroups.map { it.id },
        )
        assertFalse(styleKeys.isEmpty())
        assertFalse(textKeys.isEmpty())
        // 文字页只有一组「文字」（文本框选择器 + 字号）。
        assertTrue(textGroups.map { it.id }.contains(PhoneGroupIds.TEXT_CONTENT))
    }

    @Test
    fun noGroupIsEverEmptyAndNoRowIsShared() {
        val styleTab = assertNotNull(
            inspectorTabsFor(cardModel(), DesignSession(), hasTextSlots = true).tab(InspectorPage.Style),
        )
        val groups = phonePanelGroups(PhonePageSpec(com.framemark.native.shell.EditorTool.Params, styleTab.controls))
        groups.forEach { group ->
            assertTrue(group.rows.isNotEmpty(), "${group.label} 是空 tab")
            assertTrue(group.label.isNotBlank())
        }
        val keys = groups.flatMap { it.rows }.map { it.key }
        assertEquals(keys.size, keys.distinct().size, "同一行不能出现在两个组里")
    }
}
