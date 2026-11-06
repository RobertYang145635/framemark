package com.framemark.native.shell

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorPage
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.InspectorSectionModel
import com.framemark.native.inspector.InspectorTabInfo
import com.framemark.native.inspector.InspectorTabModel
import com.framemark.native.inspector.inspectorTabsFor
import com.framemark.native.shell.phone.phoneToolForPage
import com.framemark.native.shell.phone.phoneToolLabel
import com.framemark.native.shell.phone.phoneToolModified
import com.framemark.native.shell.phone.phoneToolPage
import com.framemark.native.shell.phone.phoneToolShowsDot
import com.framemark.native.shell.phone.phoneToolsFor
import com.framemark.native.workflow.DesignSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §4.2 / §5.2: the five tools map onto WP-C's page model, in the report §7.3
 * order and labels (边框 · 样式 · 文字 · Logo · 水印), and the small dot is exactly
 * the page's "已改项数 > 0".
 */
class PhoneToolTabMappingTest {

    private fun tab(page: InspectorPage, modified: Int) =
        InspectorTabInfo(page = page, modified = modified)

    private fun model(vararg tabs: InspectorTabInfo) =
        InspectorTabModel(cardId = "card", tabs = tabs.toList())

    @Test
    fun the_v2_tool_labels_are_the_page_names_not_the_v06_ones() {
        assertEquals("边框", phoneToolLabel(EditorTool.Border))
        assertEquals("样式", phoneToolLabel(EditorTool.Params))
        assertEquals("文字", phoneToolLabel(EditorTool.Text))
        assertEquals("Logo", phoneToolLabel(EditorTool.Logo))
        assertEquals("水印", phoneToolLabel(EditorTool.Watermark))
    }

    @Test
    fun every_tool_maps_to_its_wp_c_page_and_back() {
        assertNull(phoneToolPage(EditorTool.Border))
        assertEquals(InspectorPage.Style, phoneToolPage(EditorTool.Params))
        assertEquals(InspectorPage.Text, phoneToolPage(EditorTool.Text))
        assertEquals(InspectorPage.Logo, phoneToolPage(EditorTool.Logo))
        assertEquals(InspectorPage.Watermark, phoneToolPage(EditorTool.Watermark))
        InspectorPage.entries.forEach { page ->
            assertEquals(page, phoneToolPage(phoneToolForPage(page)))
        }
    }

    @Test
    fun the_bar_shows_border_first_then_the_pages_the_card_really_has() {
        val full = model(
            tab(InspectorPage.Style, 0),
            tab(InspectorPage.Text, 0),
            tab(InspectorPage.Logo, 0),
            tab(InspectorPage.Watermark, 0),
        )
        assertEquals(
            listOf(
                EditorTool.Border,
                EditorTool.Params,
                EditorTool.Text,
                EditorTool.Logo,
                EditorTool.Watermark,
            ),
            phoneToolsFor(full),
        )
        // Edit order of the report §7.3: 边框 / 样式 / 文字 / Logo / 水印.
        assertEquals(listOf("边框", "样式", "文字", "Logo", "水印"), phoneToolsFor(full).map(::phoneToolLabel))
    }

    @Test
    fun without_a_page_there_is_no_tool() {
        val noLogo = model(
            tab(InspectorPage.Style, 0),
            tab(InspectorPage.Text, 0),
            tab(InspectorPage.Watermark, 0),
        )
        assertEquals(
            listOf(EditorTool.Border, EditorTool.Params, EditorTool.Text, EditorTool.Watermark),
            phoneToolsFor(noLogo),
        )
        // The inspector has not loaded yet: four tools, never a dead Logo cell.
        assertEquals(
            listOf(EditorTool.Border, EditorTool.Params, EditorTool.Text, EditorTool.Watermark),
            phoneToolsFor(null),
        )
    }

    @Test
    fun an_ir_card_never_gets_a_logo_tool() {
        val irModel = InspectorCardModel(
            cardId = "ir-card",
            cardName = "ir-card",
            audited = true,
            effectiveCount = null,
            gatedCount = null,
            declaredCount = null,
            effectiveIds = listOf("brand"),
            gatedIds = emptyList(),
            visibleIds = listOf("brand"),
            hiddenIds = emptyList(),
            controls = listOf(brandControl()),
            sections = listOf(InspectorSectionModel(InspectorSection.Logo, listOf(brandControl()))),
            isIr = true,
        )
        val tools = phoneToolsFor(inspectorTabsFor(irModel, DesignSession()))
        assertFalse(EditorTool.Logo in tools, "IR card must not offer a Logo tool: $tools")
        assertTrue(EditorTool.Watermark in tools)
    }

    @Test
    fun the_dot_exists_only_when_that_page_has_changed_items() {
        val tabModel = model(
            tab(InspectorPage.Style, 2),
            tab(InspectorPage.Text, 0),
            tab(InspectorPage.Logo, 1),
            tab(InspectorPage.Watermark, 3),
        )
        // 样式 has 2 changed controls, 文字 0, Logo 1, 水印 3.
        assertEquals(2, phoneToolModified(EditorTool.Params, tabModel))
        assertEquals(0, phoneToolModified(EditorTool.Text, tabModel))
        assertEquals(1, phoneToolModified(EditorTool.Logo, tabModel))
        assertEquals(3, phoneToolModified(EditorTool.Watermark, tabModel))
        // 边框 owns no page, so it never carries a dot (showcase `renderTools`).
        assertEquals(0, phoneToolModified(EditorTool.Border, tabModel))
        assertFalse(phoneToolShowsDot(EditorTool.Border, tabModel))
        assertTrue(phoneToolShowsDot(EditorTool.Params, tabModel))
        assertFalse(phoneToolShowsDot(EditorTool.Text, tabModel))
        assertTrue(phoneToolShowsDot(EditorTool.Logo, tabModel))
        assertTrue(phoneToolShowsDot(EditorTool.Watermark, tabModel))
        // No model (still loading) means no dots at all.
        assertFalse(phoneToolShowsDot(EditorTool.Params, null))
    }

    private fun brandControl(): InspectorControl = InspectorControl(
        key = "brand",
        controlId = "brand",
        param = "brand",
        label = "品牌",
        kind = "brandPicker",
        section = InspectorSection.Logo,
        type = ControlType.Brand,
        initial = ControlValue.Str(""),
        min = null,
        max = null,
        step = null,
        options = emptyList(),
        multiline = false,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )
}
