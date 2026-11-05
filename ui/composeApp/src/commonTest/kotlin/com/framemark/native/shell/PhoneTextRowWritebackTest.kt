package com.framemark.native.shell

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.InspectorSectionModel
import com.framemark.native.product.PhotoDesign
import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.isNativeTextSlotControl
import com.framemark.native.workflow.withNativeTextSlots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone writes edits back through [foldOverrides], which only knows the
 * controls its model carries. A renderer-backed text row therefore has to be
 * part of the *panel* model on the write path, otherwise the edit stays in the
 * session and never reaches the photo's design (single/batch export, presets,
 * restart).
 */
class PhoneTextRowWritebackTest {

    private fun control(key: String, param: String) = InspectorControl(
        key = key,
        controlId = key,
        param = param,
        label = key,
        kind = "test",
        section = InspectorSection.Text,
        type = ControlType.Text,
        initial = ControlValue.Str(""),
        min = null,
        max = null,
        step = null,
        options = emptyList(),
        multiline = true,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )

    private fun model(vararg controls: InspectorControl) = InspectorCardModel(
        cardId = "card-1",
        cardName = "card-1",
        audited = true,
        effectiveCount = controls.size,
        gatedCount = 0,
        declaredCount = controls.size,
        effectiveIds = controls.map { it.controlId },
        gatedIds = emptyList(),
        visibleIds = controls.map { it.controlId },
        hiddenIds = emptyList(),
        controls = controls.toList(),
        sections = listOf(InspectorSectionModel(InspectorSection.Text, controls.toList())),
    )

    private fun row(key: String, x: Double = 10.0, y: Double = 10.0) = TextSlot(
        id = key,
        text = "row",
        x = x,
        y = y,
        w = 120.0,
        h = 24.0,
        fontSize = 28.0,
        baseline = null,
        lines = 1,
        key = key,
        stable = true,
        located = true,
        raw = "原文字",
    )

    private val slotKey = "hand:card-1:card.footer"

    @Test
    fun aRendererRowEditReachesTheDesignOnlyThroughThePanelModel() {
        val base = model(control("card-1::footer", "params.footerPct"))
        val panel = base.withNativeTextSlots(listOf(row(slotKey)))
        val textControl = panel.controls.first { isNativeTextSlotControl(it) && it.param == "params.slotText:$slotKey" }
        val changes = mapOf(textControl.key to "改过的文字")

        // The declared model cannot own the row, so the edit would be dropped.
        assertNull(
            foldOverrides(PhotoDesign(cardId = "card-1"), base, changes).params["slotText:$slotKey"],
            "the raw model must not silently 'succeed' at writing a row it does not carry",
        )

        val written = foldOverrides(PhotoDesign(cardId = "card-1"), panel, changes)
        assertEquals("改过的文字", written.params["slotText:$slotKey"])
    }

    @Test
    fun hidingAndSizingARowArePersistedAndReadBackForTheNextSession() {
        val panel = model().withNativeTextSlots(listOf(row(slotKey)))
        val off = panel.controls.first { it.param == "params.slotOff:$slotKey" }
        val scale = panel.controls.first { it.param == "params.slotScale:$slotKey" }

        val written = foldOverrides(
            PhotoDesign(cardId = "card-1"),
            panel,
            mapOf(off.key to false, scale.key to 1.4f),
        )
        assertEquals(false, written.params["slotOff:$slotKey"])
        assertEquals(1.4, (written.params["slotScale:$slotKey"] as Number).toDouble(), 1e-6)

        // Re-opening the editor reads the stored values back into the session.
        val hydrated = sessionValuesFor(panel, written)
        assertEquals("", hydrated[panel.controls.first { it.param == "params.slotText:$slotKey" }.key])
        assertEquals(false, hydrated[off.key])
        assertEquals(1.4f, hydrated[scale.key])
    }

    @Test
    fun clearingAnEditRemovesTheOwnedKeyInsteadOfWritingASentinel() {
        val panel = model().withNativeTextSlots(listOf(row(slotKey)))
        val textControl = panel.controls.first { it.param == "params.slotText:$slotKey" }
        val written = foldOverrides(PhotoDesign(cardId = "card-1"), panel, mapOf(textControl.key to "改"))
        assertEquals("改", written.params["slotText:$slotKey"])

        // The UI reset deletes the override; the fold must not leave an empty string.
        val cleared = foldOverrides(written, panel, emptyMap())
        assertTrue(cleared.params["slotText:$slotKey"] == null, "reset removes the key")
    }

    @Test
    fun anUnrelatedControlEditReappliesTheStoredRowValue() {
        val panel = model(control("card-1::footer", "params.footerPct")).withNativeTextSlots(listOf(row(slotKey)))
        val textControl = panel.controls.first { it.param == "params.slotText:$slotKey" }
        val footer = panel.controls.first { !isNativeTextSlotControl(it) }
        val stored = PhotoDesign(cardId = "card-1").withParams(mapOf("slotText:$slotKey" to "保留"))

        // DesignSession hands over *every* value that differs from the declared
        // default, so an untouched row ride along with an unrelated edit and is
        // re-applied instead of being swept away.
        val next = foldOverrides(
            stored,
            panel,
            mapOf(footer.key to 42f, textControl.key to "保留"),
        )
        assertEquals("保留", next.params["slotText:$slotKey"], "another control must not drop the row edit")
        assertEquals(42.0, (next.params["footerPct"] as Number).toDouble())
    }

    @Test
    fun anIrNodePathRowUsesTheSameWritePath() {
        // IR rows are addressed by their engine node path, not a hand key.
        val irKey = "b0/g0/g0/g0"
        val panel = model().withNativeTextSlots(listOf(row(irKey)))
        val textControl = panel.controls.first { it.param == "params.slotText:$irKey" }
        val written = foldOverrides(PhotoDesign(cardId = "stock-x"), panel, mapOf(textControl.key to "IR文字"))
        assertEquals("IR文字", written.params["slotText:$irKey"])
    }
}
