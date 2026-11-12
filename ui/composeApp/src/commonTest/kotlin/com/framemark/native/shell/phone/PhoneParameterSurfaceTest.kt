package com.framemark.native.shell.phone

import androidx.compose.foundation.ScrollState
import com.framemark.native.inspector.ControlOption
import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorPage
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.OutputSize
import com.framemark.native.inspector.inspectorTabsFor
import com.framemark.native.shell.PHONE_PARAMETER_SUMMARY_DP
import com.framemark.native.shell.PHONE_PARAMETER_SUMMARY_MIN_DP
import com.framemark.native.shell.showsPhoneParameterSummary
import com.framemark.native.workflow.DesignSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The parameter surface split («不要让用户为了调参数上下来回滚»): one pinned 公共区,
 * one scrolling 工具区, one scroll offset per page.
 *
 *  - [showsPhoneParameterSummary] is the guard that keeps the pinned band from
 *    starving the tool zone at the sheet's floor (120 dp sheet → ~68 dp page).
 *  - [phoneToolScrollFor] is the "each tab opens where its own adjustment was
 *    left" rule; the old page shared one offset between all four tabs.
 *  - the 公共区's number is provably the number the 样式 page used to print: it is
 *    computed from the whole visible card, the page used to compute it from its own
 *    tab, and those agree because `OutputSize` only reads the layout params (which
 *    the page partition puts on 样式 anyway). That is also why **no editable control
 *    can join the 公共区** — [inspectorTabsFor] gives every control exactly one page.
 */
class PhoneParameterSurfaceTest {

    // -----------------------------------------------------------------------
    // The pinned band never starves the tool zone
    // -----------------------------------------------------------------------

    @Test
    fun thePinnedSummaryAppearsOnlyWhenTheToolZoneCanStillScroll() {
        // At the sheet floor (MIN_SHEET_DP = 120 dp) the page viewport after the
        // grabber and the head is ~68 dp, and forcedPreviewHeights can hand out
        // less than one row: the band must not take the last of it.
        assertFalse(showsPhoneParameterSummary(0f))
        assertFalse(showsPhoneParameterSummary(8f), "the forced-preview extreme")
        assertFalse(showsPhoneParameterSummary(68f), "the 120 dp sheet floor")
        assertFalse(showsPhoneParameterSummary(PHONE_PARAMETER_SUMMARY_MIN_DP - 0.5f))
        assertTrue(showsPhoneParameterSummary(PHONE_PARAMETER_SUMMARY_MIN_DP))
        assertTrue(showsPhoneParameterSummary(400f))
        // An unbounded page (listed inside another scroll) keeps the band; NaN is a
        // broken measurement and drops it instead of crashing the layout.
        assertTrue(showsPhoneParameterSummary(Float.POSITIVE_INFINITY))
        assertFalse(showsPhoneParameterSummary(Float.NaN))
    }

    @Test
    fun theThresholdIsTheBandPlusOneSliderRow() {
        assertTrue(
            PHONE_PARAMETER_SUMMARY_DP == 34f,
            "band: 17 dp row + 8 + 8 + 1 dp rule, was $PHONE_PARAMETER_SUMMARY_DP",
        )
        assertTrue(
            PHONE_PARAMETER_SUMMARY_MIN_DP == PHONE_PARAMETER_SUMMARY_DP + 72f,
            "band + one PhoneSliderRow (8 + 20 + 40 + 4), was $PHONE_PARAMETER_SUMMARY_MIN_DP",
        )
    }

    // -----------------------------------------------------------------------
    // One scroll offset per page
    // -----------------------------------------------------------------------

    @Test
    fun eachPageKeepsItsOwnToolScrollOffset() {
        val offsets = mutableMapOf<InspectorPage, ScrollState>()
        val style = phoneToolScrollFor(offsets, InspectorPage.Style)
        val text = phoneToolScrollFor(offsets, InspectorPage.Text)
        assertNotSame(style, text, "两个页签不能共用一条位移")
        assertSame(style, phoneToolScrollFor(offsets, InspectorPage.Style), "切回来还是同一条")
        assertEquals(2, offsets.size)
        // A page opens at the top: its first controls always sit at the same y.
        assertTrue(offsets.values.all { it.value == 0 })
    }

    // -----------------------------------------------------------------------
    // The pinned number is the number the 样式 page used to print
    // -----------------------------------------------------------------------

    private fun control(
        controlId: String,
        param: String,
        label: String,
        kind: String = "slider",
        type: ControlType = ControlType.Slider,
        initial: ControlValue = ControlValue.Num(10f),
        min: Float? = 0f,
        max: Float? = 100f,
        step: Float? = 1f,
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
        min = min,
        max = max,
        step = step,
        options = options,
        multiline = false,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = hidden,
    )

    private val modeOptions = listOf(
        ControlOption("classic", "classic"),
        ControlOption("simple", "simple"),
        ControlOption("overlay", "overlay"),
    )

    /**
     * A card with one control on each of three pages. The hidden `footerMode` sits
     * **first** on purpose: `OutputSize` takes the first match by param name, so any
     * consumer that reads raw `model.controls` would print the overlay canvas.
     */
    private fun cardModel(): InspectorCardModel {
        val hidden = control(
            controlId = "params:footerMode.hidden",
            param = "params.footerMode",
            label = "页脚版式",
            kind = "select",
            type = ControlType.Select,
            initial = ControlValue.Str("overlay"),
            options = modeOptions,
            section = InspectorSection.Layout,
            hidden = true,
        )
        val mode = control(
            controlId = "params:footerMode",
            param = "params.footerMode",
            label = "页脚版式",
            kind = "select",
            type = ControlType.Select,
            initial = ControlValue.Str("classic"),
            options = modeOptions,
            section = InspectorSection.Layout,
        )
        val controls = listOf(
            hidden,
            mode,
            control("params:footerPct", "params.footerPct", "底部留白", initial = ControlValue.Num(12f)),
            control(
                controlId = "theme.theme",
                param = "theme",
                label = "底色主题",
                kind = "themeSwatches",
                type = ControlType.Select,
                initial = ControlValue.Str("white"),
                section = InspectorSection.Theme,
                options = listOf(ControlOption("white", "白")),
            ),
            control("text:fontScale", "fontScale", "整卡字号缩放", kind = "text", initial = ControlValue.Num(1.2f)),
            control(
                controlId = "params:showLogo",
                param = "params.showLogo",
                label = "显示标志",
                kind = "switch",
                type = ControlType.Switch,
                initial = ControlValue.Flag(true),
            ),
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
            visibleIds = controls.filterNot { it.hidden }.map { it.controlId },
            hiddenIds = controls.filter { it.hidden }.map { it.controlId },
            controls = controls,
            sections = emptyList(),
        )
    }

    @Test
    fun thePinnedBandReadsTheSameExportSizeTheStylePageUsedTo() {
        val model = cardModel()
        val session = DesignSession()
        session.seed(model.controls)
        val styleTab = inspectorTabsFor(model, session, hasTextSlots = true).tab(InspectorPage.Style)
        assertNotNull(styleTab, "the card has 样式 controls")

        val photoWidth = 3000
        val photoHeight = 4000
        // 公共区 (PhoneDesignSummary): the whole visible card.
        val pinned = OutputSize.canvas(photoWidth, photoHeight, phoneVisibleControls(model.controls)) {
            session.value(it)
        }
        // 样式 page before this change: its own tab's controls.
        val stylePage = OutputSize.canvas(photoWidth, photoHeight, styleTab.controls) {
            session.value(it)
        }
        assertNotNull(pinned)
        assertEquals(stylePage, pinned, "调这一行不该改变导出的数字")
        // Both go through the same export long-edge tier.
        assertEquals(
            OutputSize.applyLongEdge(stylePage, session.export.sizeMode, session.export.longEdge),
            OutputSize.applyLongEdge(pinned, session.export.sizeMode, session.export.longEdge),
        )
        // The hidden row really is a difference: reading it would print the overlay
        // canvas (bare photo), not the classic one the style page shows.
        val raw = OutputSize.canvas(photoWidth, photoHeight, model.controls) { session.value(it) }
        assertNotEquals(stylePage, raw, "hidden rows must not leak into the pinned number")
    }

    @Test
    fun noDeclaredControlBelongsToTwoPages() {
        // The reason the 公共区 holds the design summary and not an editable control:
        // the page model is a partition, so hoisting one would duplicate a binding.
        val model = cardModel()
        val session = DesignSession()
        session.seed(model.controls)
        val tabs = inspectorTabsFor(model, session, hasTextSlots = true)
        val keys = tabs.tabs.flatMap { tab -> tab.controls.map { it.key } }
        assertEquals(keys.size, keys.distinct().size, "同一个控件不能出现在两个页签里")
        assertEquals(
            setOf(InspectorPage.Style, InspectorPage.Text, InspectorPage.Logo),
            tabs.tabs.filter { it.controls.isNotEmpty() }.map { it.page }.toSet(),
            "这份卡三页都有控制项",
        )
        // Each page keeps its own rows; the 公共区 reads across all of them.
        val style = tabs.tab(InspectorPage.Style)!!.controls.map { it.param }.toSet()
        assertTrue("theme" in style)
        assertTrue("fontScale" !in style)
        assertTrue(phoneVisibleControls(model.controls).any { it.param == "fontScale" })
    }
}
