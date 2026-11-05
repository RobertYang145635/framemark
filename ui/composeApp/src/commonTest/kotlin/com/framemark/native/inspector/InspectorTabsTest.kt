package com.framemark.native.inspector

import com.framemark.native.workflow.DesignSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP-C §4.3: four pages, per-page modified count, per-page reset, and no Logo
 * page on IR cards.
 */
class InspectorTabsTest {

    private fun control(
        controlId: String,
        param: String,
        label: String,
        kind: String,
        type: ControlType = ControlType.Slider,
        initial: ControlValue = ControlValue.Num(10f),
        min: Float? = 0f,
        max: Float? = 20f,
        step: Float? = 1f,
        section: InspectorSection = InspectorSection.Border,
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
        options = emptyList(),
        multiline = false,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = hidden,
    )

    private fun model(
        isIr: Boolean = false,
        controls: List<InspectorControl>,
    ) = InspectorCardModel(
        cardId = "card",
        cardName = "卡",
        audited = false,
        isIr = isIr,
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

    private val sample = listOf(
        control("slider.params:footerPct", "params.footerPct", "底部留白", "slider"),
        control(
            "theme.theme",
            "theme",
            "底色",
            "themeSwatches",
            type = ControlType.Select,
            initial = ControlValue.Str("white"),
            section = InspectorSection.Theme,
        ),
        control(
            "text.text:fontScale",
            "text.fontScale",
            "整体文字大小",
            "slider",
            min = 0.5f,
            max = 2f,
            step = 0.05f,
            initial = ControlValue.Num(1f),
            section = InspectorSection.Text,
        ),
        control(
            "brand.design:logo",
            "design.logo",
            "Logo",
            "brandPicker",
            type = ControlType.Brand,
            initial = ControlValue.Str("auto"),
            section = InspectorSection.Logo,
        ),
        control(
            "toggle.text:showLogo",
            "text.showLogo",
            "显示 Logo",
            "toggle",
            type = ControlType.Switch,
            initial = ControlValue.Flag(true),
            section = InspectorSection.Text,
        ),
        control(
            "gravity.params:vendor",
            "params.vendor",
            "厂商标识",
            "gravitySegment",
            type = ControlType.Select,
            initial = ControlValue.Str("x"),
            section = InspectorSection.Logo,
        ),
    )

    @Test
    fun pagesFollowTheShowcaseOrderAndNames() {
        val session = DesignSession()
        val tabs = inspectorTabsFor(model(controls = sample), session)
        assertEquals(
            listOf(InspectorPage.Style, InspectorPage.Text, InspectorPage.Logo, InspectorPage.Watermark),
            tabs.tabs.map { it.page },
        )
        assertEquals(listOf("样式", "文字", "Logo", "水印"), tabs.tabs.map { it.label })
    }

    @Test
    fun controlsLandOnThePageTheShowcaseTabOfChooses() {
        assertEquals(InspectorPage.Style, inspectorPageOf(sample[0])) // footerPct
        assertEquals(InspectorPage.Style, inspectorPageOf(sample[1])) // theme
        assertEquals(InspectorPage.Text, inspectorPageOf(sample[2])) // text.fontScale
        assertEquals(InspectorPage.Logo, inspectorPageOf(sample[3])) // brandPicker
        // `text.showLogo` carries a `logo` key, so the showcase `tabOf` puts it on
        // the Logo page (its TAB_KEYS.logo includes showLogo).
        assertEquals(InspectorPage.Logo, inspectorPageOf(sample[4]))
        assertEquals(InspectorPage.Logo, inspectorPageOf(sample[5])) // params.vendor
    }

    @Test
    fun irCardsDoNotGetALogoPage() {
        val session = DesignSession()
        val ir = inspectorTabsFor(model(isIr = true, controls = sample), session)
        assertFalse(ir.has(InspectorPage.Logo))
        assertTrue(ir.has(InspectorPage.Style))
        assertTrue(ir.has(InspectorPage.Watermark))
        val hand = inspectorTabsFor(model(isIr = false, controls = sample), session)
        assertTrue(hand.has(InspectorPage.Logo))
    }

    @Test
    fun theTextPageAppearsForRendererTextRowsEvenWithoutDeclaredTextControls() {
        val controls = sample.filterNot { it.controlId.startsWith("text.") }
        val session = DesignSession()
        assertFalse(inspectorTabsFor(model(controls = controls), session, hasTextSlots = false).has(InspectorPage.Text))
        assertTrue(inspectorTabsFor(model(controls = controls), session, hasTextSlots = true).has(InspectorPage.Text))
    }

    @Test
    fun eachTabCountsOnlyItsOwnModifiedControls() {
        val session = DesignSession()
        session.seed(sample)
        val tabs = inspectorTabsFor(model(controls = sample), session)
        assertEquals(0, tabs.totalModified)

        session.set(sample[0], 18f) // style
        session.set(sample[2], 1.5f) // text
        val counted = inspectorTabsFor(model(controls = sample), session)
        assertEquals(1, counted.tab(InspectorPage.Style)?.modified)
        assertEquals(1, counted.tab(InspectorPage.Text)?.modified)
        assertEquals(0, counted.tab(InspectorPage.Logo)?.modified)
        assertEquals(2, counted.totalModified)
    }

    @Test
    fun resetsOnlyTheRequestedPage() {
        val session = DesignSession()
        session.seed(sample)
        session.set(sample[0], 18f) // style
        session.set(sample[2], 1.5f) // text
        val tabs = inspectorTabsFor(model(controls = sample), session)

        val changed = resetInspectorPage(tabs, InspectorPage.Style, session)
        assertEquals(1, changed)
        assertEquals(10f, session.value(sample[0]) as Float)
        // The text page keeps its edit.
        assertEquals(1.5f, session.value(sample[2]) as Float)
        val after = inspectorTabsFor(model(controls = sample), session)
        assertEquals(0, after.tab(InspectorPage.Style)?.modified)
        assertEquals(1, after.tab(InspectorPage.Text)?.modified)
    }

    @Test
    fun watermarkCountComesFromTheWatermarkLayer() {
        val session = DesignSession()
        val tabs = inspectorTabsFor(model(controls = sample), session, watermarkModified = 3)
        assertEquals(3, tabs.tab(InspectorPage.Watermark)?.modified)
    }

    @Test
    fun aNullModelHasNoTabs() {
        val tabs = inspectorTabsFor(null, DesignSession())
        assertTrue(tabs.tabs.isEmpty())
        assertNull(tabs.tab(InspectorPage.Style))
    }
}
