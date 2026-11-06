package com.framemark.native.inspector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * D1-3: focus routing for renderer-backed text rows. Only the free-text control
 * owns the stage highlight; the row's visibility switch and size slider must not.
 */
class TextSlotFocusRoutingTest {

    private fun control(
        param: String,
        type: ControlType,
        kind: String = "native_text_slot",
    ) = InspectorControl(
        key = "card::$param",
        controlId = param,
        param = param,
        label = "row",
        kind = kind,
        section = InspectorSection.Text,
        type = type,
        initial = ControlValue.Str(""),
        min = null,
        max = null,
        step = null,
        options = emptyList(),
        multiline = type == ControlType.Text,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )

    @Test
    fun the_text_control_reports_its_stable_slot_key() {
        assertEquals(
            "b0/g1/g2",
            textSlotFocusKey(control("params.slotText:b0/g1/g2", ControlType.Text)),
        )
        assertEquals(
            "hand:footer:brand",
            textSlotFocusKey(control("params.slotText:hand:footer:brand", ControlType.Text)),
        )
    }

    @Test
    fun visibility_and_size_controls_of_the_same_row_do_not_report_focus() {
        assertNull(textSlotFocusKey(control("params.slotOff:b0/g1/g2", ControlType.Switch)))
        assertNull(textSlotFocusKey(control("params.slotScale:b0/g1/g2", ControlType.Slider)))
    }

    @Test
    fun ordinary_text_controls_do_not_report_a_renderer_slot() {
        assertNull(textSlotFocusKey(control("text.showModel", ControlType.Switch)))
        assertNull(textSlotFocusKey(control("params.footerPct", ControlType.Slider)))
    }

    @Test
    fun the_reported_key_is_the_stage_slot_key_the_renderer_reports() {
        val slot = com.framemark.native.workflow.TextSlot(
            id = "row-1",
            text = "样例文字",
            x = 10.0,
            y = 20.0,
            w = 30.0,
            h = 12.0,
            fontSize = 12.0,
            baseline = 9.0,
            lines = 1,
            key = "b0/g1/g2",
            raw = "样例文字",
            stable = true,
            located = true,
        )
        val controls = com.framemark.native.workflow.textSlotControls("stock-oppo-text_style_1", slot)
        val textControl = controls.first { it.type == ControlType.Text }
        assertEquals(
            "b0/g1/g2",
            textSlotFocusKey(textControl),
            "the key InspectorPageContent reports must equal the renderer slot key",
        )
        // The other half of the chain: the stage can locate this key and draw a
        // frame for it (existing WP-C slot-highlight geometry).
        val rect = com.framemark.native.workflow.slotHighlightRect(
            slot = slot,
            bitmapW = 100,
            bitmapH = 100,
            boxW = 300f,
            boxH = 100f,
        )
        assertNotNull(rect, "the focused key must map to a drawable stage frame")
    }
}
