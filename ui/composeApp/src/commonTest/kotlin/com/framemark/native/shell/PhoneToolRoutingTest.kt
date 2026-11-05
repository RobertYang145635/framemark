package com.framemark.native.shell

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.InspectorSectionModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bottom-bar routing: 4 or 5 tools, labels, and the panel section each opens. */
class PhoneToolRoutingTest {

    @Test
    fun a_logo_capability_shows_five_tools_with_the_v06_labels() {
        val tools = phoneEditorTools(hasLogoCapability = true)
        assertEquals(
            listOf(EditorTool.Border, EditorTool.Params, EditorTool.Text, EditorTool.Logo, EditorTool.Watermark),
            tools,
        )
        assertEquals(
            listOf("边框", "调整", "文字", "Logo", "图片水印"),
            tools.map { it.label },
        )
    }

    @Test
    fun without_a_logo_capability_the_bar_has_four_cells() {
        val tools = phoneEditorTools(hasLogoCapability = false)
        assertEquals(4, tools.size)
        assertFalse(EditorTool.Logo in tools)
        assertEquals(
            listOf(EditorTool.Border, EditorTool.Params, EditorTool.Text, EditorTool.Watermark),
            tools,
        )
    }

    @Test
    fun each_tool_routes_to_the_shared_inspector_section_it_needs() {
        assertNull(phoneEditorPanelSection(EditorTool.Border))
        assertEquals(InspectorSection.Layout, phoneEditorPanelSection(EditorTool.Params))
        assertEquals(InspectorSection.Text, phoneEditorPanelSection(EditorTool.Text))
        assertEquals(InspectorSection.Logo, phoneEditorPanelSection(EditorTool.Logo))
        assertNull(phoneEditorPanelSection(EditorTool.Watermark))
    }

    @Test
    fun a_brand_control_counts_as_a_logo_capability() {
        val model = modelWith(
            controls = listOf(control("brand", InspectorSection.Logo, ControlType.Brand)),
            sections = listOf(
                InspectorSectionModel(InspectorSection.Logo, listOf(control("brand", InspectorSection.Logo, ControlType.Brand))),
            ),
        )
        assertTrue(phoneEditorHasLogoCapability(model))
    }

    @Test
    fun a_logo_section_control_counts_as_a_logo_capability() {
        val logo = control("logo.size", InspectorSection.Logo, ControlType.Slider)
        val model = modelWith(
            controls = listOf(logo),
            sections = listOf(InspectorSectionModel(InspectorSection.Logo, listOf(logo))),
        )
        assertTrue(phoneEditorHasLogoCapability(model))
    }

    @Test
    fun a_card_without_logo_rows_has_no_logo_tool() {
        val layout = control("layout.inset", InspectorSection.Layout, ControlType.Slider)
        val model = modelWith(
            controls = listOf(layout),
            sections = listOf(InspectorSectionModel(InspectorSection.Layout, listOf(layout))),
        )
        assertFalse(phoneEditorHasLogoCapability(model))
        assertNull(model?.sections?.firstOrNull { it.section == InspectorSection.Logo })
    }

    @Test
    fun a_missing_model_has_no_logo_capability() {
        assertFalse(phoneEditorHasLogoCapability(null))
    }

    private fun control(
        key: String,
        section: InspectorSection,
        type: ControlType,
    ): InspectorControl = InspectorControl(
        key = key,
        controlId = key,
        param = "",
        label = key,
        kind = "stock",
        section = section,
        type = type,
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

    private fun modelWith(
        controls: List<InspectorControl>,
        sections: List<InspectorSectionModel>,
    ): InspectorCardModel = InspectorCardModel(
        cardId = "card",
        cardName = "card",
        audited = true,
        effectiveCount = null,
        gatedCount = null,
        declaredCount = null,
        effectiveIds = controls.map { it.controlId },
        gatedIds = emptyList(),
        visibleIds = controls.map { it.controlId },
        hiddenIds = emptyList(),
        controls = controls,
        sections = sections,
    )
}
