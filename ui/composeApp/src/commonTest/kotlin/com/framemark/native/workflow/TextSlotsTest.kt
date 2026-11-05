package com.framemark.native.workflow

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

/** Contract tests for the shared text-row model (position grouping and overrides). */
class TextSlotsTest {

    private fun slot(
        id: String,
        key: String?,
        x: Double,
        y: Double,
        w: Double = 120.0,
        h: Double = 24.0,
        stable: Boolean = key != null,
        located: Boolean = true,
        hidden: Boolean = false,
        present: Boolean = true,
        raw: String = "",
    ) = TextSlot(
        id = id,
        text = "row $id",
        x = x,
        y = y,
        w = w,
        h = h,
        fontSize = 32.0,
        baseline = null,
        lines = 1,
        key = key,
        stable = stable,
        located = located,
        present = present,
        hidden = hidden,
        raw = raw,
    )

    private fun control(key: String, param: String, section: InspectorSection = InspectorSection.Text) = InspectorControl(
        key = key,
        controlId = key,
        param = param,
        label = key,
        kind = "test",
        section = section,
        type = ControlType.Text,
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

    @Test
    fun onlyTheTwoExplicitRendererIdentitiesAreWritable() {
        assertTrue(isWritableTextSlotKey("b0/g0/g0"))
        assertTrue(isWritableTextSlotKey("b1/g12"))
        assertTrue(isWritableTextSlotKey("hand:blur-classic:blur.title"))
        // Content-derived legacy spellings are metadata, never a write target.
        assertFalse(isWritableTextSlotKey("blur-classic#title#0"))
        assertFalse(isWritableTextSlotKey("wm-black#brand"))
        assertFalse(isWritableTextSlotKey(""))
        assertFalse(isWritableTextSlotKey(null))
    }

    @Test
    fun positionComesFromTheReportedRectangleCenterOnly() {
        val w = 900
        val h = 600
        assertEquals(TextSlotPosition.TopLeft, textSlotPosition(slot("a", "hand:x:a", 10.0, 10.0), w, h))
        assertEquals(TextSlotPosition.BottomRight, textSlotPosition(slot("b", "hand:x:b", 760.0, 560.0), w, h))
        assertEquals(TextSlotPosition.Center, textSlotPosition(slot("c", "hand:x:c", 380.0, 280.0), w, h))
        // Same content, different geometry: the label must follow geometry.
        assertEquals(TextSlotPosition.BottomLeft, textSlotPosition(slot("d", "hand:x:d", 10.0, 560.0), w, h))
    }

    @Test
    fun unlocatedWritableRowsStayListedUnderPendingWhileUnwritableRowsAreDropped() {
        val noGeometry = slot("a", "hand:x:a", 0.0, 0.0, w = 0.0, h = 0.0, located = false)
        val legacyId = slot("b", "blur-classic#title#0", 10.0, 10.0, stable = false)
        assertNull(textSlotPosition(noGeometry, 900, 600), "no geometry means no position")
        assertNull(textSlotPosition(legacyId, 900, 600), "no writable identity means no position")

        val groups = groupTextSlots(listOf(noGeometry, legacyId), 900, 600)
        assertEquals(1, groups.size, "only the writable row is listed")
        assertEquals("待定位", groups.single().label)
        assertEquals(listOf("a"), groups.single().rows.map { it.slot.id })
    }

    @Test
    fun absentDeclarationIsNotListedButExplicitlyHiddenRowIsRestorable() {
        val absent = slot("absent", "b0/g0", 0.0, 0.0, w = 0.0, h = 0.0, located = false, present = false)
        val hidden = slot(
            "hidden",
            "b0/g1",
            0.0,
            0.0,
            w = 0.0,
            h = 0.0,
            located = false,
            present = false,
            hidden = true,
        )
        val painted = slot("painted", "b0/g2", 10.0, 10.0)

        val groups = groupTextSlots(listOf(absent, hidden, painted), 900, 600)
        assertEquals(listOf("painted", "hidden"), groups.flatMap { it.rows }.map { it.slot.id })
        assertFalse(absent.isCurrentTextSlot(), "an unreported declaration is not current canvas text")
        assertTrue(hidden.isCurrentTextSlot(), "an explicit hidden row can still be restored")
    }

    @Test
    fun groupsSortByPositionThenByRowOrderAndKeepUnlocatedLast() {
        val topLeft = slot("tl", "hand:x:tl", 10.0, 10.0)
        val bottomLeftFirst = slot("bl", "hand:x:bl", 20.0, 540.0)
        val bottomLeftSecond = slot("bl2", "hand:x:bl2", 30.0, 500.0)
        val topRight = slot("tr", "hand:x:tr", 760.0, 20.0)
        val unlocated = slot("u", "hand:x:u", 0.0, 0.0, w = 0.0, h = 0.0, located = false)

        val groups = groupTextSlots(
            listOf(bottomLeftFirst, topRight, unlocated, bottomLeftSecond, topLeft),
            900,
            600,
        )
        assertEquals(listOf("左上", "右上", "左下", "待定位"), groups.map { it.label })
        val bottomLeft = groups.first { it.label == "左下" }
        assertEquals(listOf("bl2", "bl"), bottomLeft.rows.map { it.slot.id }, "sorted by y then x")
        assertEquals(listOf(1, 2), bottomLeft.rows.map { it.order })
        assertEquals("待定位", groups.last().label)
    }

    @Test
    fun overrideRoundTripWritesOnlyNonDefaultsAndClampsScale() {
        val key = "hand:blur-classic:blur.title"
        val encoded = encodeTextSlotOverrides(key, TextSlotOverrides(text = "自定义", visible = false, scale = 9f))
        assertEquals("自定义", encoded["slotText:$key"])
        assertEquals(false, encoded["slotOff:$key"])
        assertEquals(1.6, encoded.getValue("slotScale:$key") as Double, 1e-6, "scale clamps into 0.6..1.6")
        assertTrue(encodeTextSlotOverrides(key, TextSlotOverrides()).isEmpty(), "defaults write nothing")

        val decoded = decodeTextSlotOverrides(
            key,
            mapOf("slotText:$key" to "自定义", "slotOff:$key" to false, "slotScale:$key" to 0.1),
        )
        assertEquals("自定义", decoded.text)
        assertFalse(decoded.visible)
        assertEquals(0.6f, decoded.scale, "scale clamps into 0.6..1.6")

        // Missing or malformed values fall back to the source row.
        val absent = decodeTextSlotOverrides(key, mapOf("slotScale:$key" to "oops"))
        assertEquals("", absent.text)
        assertTrue(absent.visible)
        assertEquals(1f, absent.scale)
    }

    @Test
    fun nativeTextControlsReplaceTheConsumersTheyWrite() {
        val key = "hand:blur-classic:blur.title"
        val staticTitle = control("card-1::title", "params.slotText:$key")
        val staticDate = control("card-1::date", "params.slotText:blur-classic#date#0")
        val merged = model(staticTitle, staticDate).withNativeTextSlots(listOf(slot("t", key, 10.0, 10.0)))

        assertFalse(
            merged.controls.any { it.key == "card-1::title" },
            "the renderer-backed row supersedes the static consumer it writes",
        )
        assertTrue(merged.controls.any { it.key == "card-1::date" }, "an unrelated consumer survives")
        val dynamic = merged.controls.filter(::isNativeTextSlotControl)
        assertEquals(3, dynamic.size, "text, visibility and scale per editable row")
        assertTrue(dynamic.all { it.param.startsWith("params.slotText:") || it.param.startsWith("params.slotOff:") || it.param.startsWith("params.slotScale:") })
        assertTrue(merged.visibleIds.containsAll(dynamic.map { it.controlId }))
        assertEquals(InspectorSection.Text, merged.sections.single().section)
    }

    @Test
    fun rowsWithoutAStableKeyGetNoControls() {
        val merged = model().withNativeTextSlots(listOf(slot("legacy", "blur-classic#title#0", 10.0, 10.0, stable = false)))
        assertTrue(merged.controls.isEmpty())
        assertTrue(merged.sections.isEmpty(), "no empty synthetic section is appended")
    }
    @Test
    fun failureRecoveryKeepsOnlyEditableStableRowsWithoutStaleGeometry() {
        val editable = slot("editable", "hand:x:title", 20.0, 30.0)
        val hidden = slot("hidden", "hand:x:date", 0.0, 0.0, w = 0.0, h = 0.0, located = false, present = false, hidden = true)
        val absent = slot("absent", "hand:x:agent", 0.0, 0.0, w = 0.0, h = 0.0, located = false, present = false)
        val legacy = slot("legacy", "blur-classic#title#0", 10.0, 10.0, stable = false)

        val recovered = listOf(editable, hidden, absent, legacy).asFailureRecoverySlots()

        assertEquals(listOf("editable", "hidden"), recovered.map { it.id })
        assertTrue(recovered.all { !it.located && it.x == 0.0 && it.y == 0.0 && it.w == 0.0 && it.h == 0.0 })
    }

}
