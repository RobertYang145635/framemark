package com.framemark.native.workflow

import com.framemark.native.inspector.ExifGapNotice
import com.framemark.native.inspector.readonlyTextSlotValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Decision 2026-11-14, palette cards: a preview render's text report is only data when
 * it came from the **final** preview tier.
 *
 * The interactive tier (`product/PreviewTier.kt`) may decode the source photo at a
 * bounded edge; the six hand 色卡 templates sample the photo for their hex labels, so
 * their report labels differ mid-drag while every geometry field is identical.
 * `NativeEngine.preview` marks an interactive report [TextSlot.provisional], and this
 * registry is the one data surface the 文字 page reads: the tests below pin that a
 * provisional report never replaces a final one, and that a provisional row's sampled
 * value is not printed as if it were the palette.
 */
class StageReportAuthorityTest {

    private fun slot(text: String, provisional: Boolean = false) = TextSlot(
        id = "draw#0",
        text = text,
        x = 0.0,
        y = 0.0,
        w = 100.0,
        h = 20.0,
        fontSize = 14.0,
        baseline = null,
        lines = 1,
        raw = text,
        provisional = provisional,
    )

    @Test
    fun aProvisionalReportNeverReplacesAFinalOne() {
        val registry = TextSlotRegistry()
        registry.update(1280, 960, listOf(slot("#111111")))
        registry.update(480, 360, listOf(slot("#222222", provisional = true)))
        assertEquals("#111111", registry.slots.single().text)
        assertEquals(1280, registry.width)
        assertFalse(registry.isProvisional)
    }

    @Test
    fun aFinalReportReplacesAProvisionalOne() {
        val registry = TextSlotRegistry()
        registry.update(480, 360, listOf(slot("#222222", provisional = true)))
        assertTrue(registry.isProvisional)
        registry.update(1280, 960, listOf(slot("#111111")))
        assertEquals("#111111", registry.slots.single().text)
        assertFalse(registry.isProvisional)
    }

    @Test
    fun theFirstFrameIsHeldButMarkedProvisional() {
        // A cold stage renders the interactive frame first; the page must not claim the
        // card has no text, but the rows must still be flagged as a sample.
        val registry = TextSlotRegistry()
        registry.update(480, 360, listOf(slot("#222222", provisional = true)))
        assertTrue(registry.isProvisional)
        assertTrue(registry.slots.single().provisional)
        assertEquals(480, registry.width)
    }

    @Test
    fun clearingForgetsTheFinalReport() {
        // The shell clears on a card/photo change; the new pair's first (provisional)
        // frame must be storable again instead of being ignored forever.
        val registry = TextSlotRegistry()
        registry.update(1280, 960, listOf(slot("#111111")))
        registry.clear()
        registry.update(480, 360, listOf(slot("#333333", provisional = true)))
        assertTrue(registry.isProvisional)
        assertEquals("#333333", registry.slots.single().text)
    }

    @Test
    fun theFailureRecoveryReportStillReplacesTheSnapshot() {
        // `NativeStagePreview` sends recovery rows with a 0 × 0 canvas after a failed
        // render; that is a state reset, not a frame report, and must not be swallowed
        // by the provisional gate.
        val registry = TextSlotRegistry()
        registry.update(1280, 960, listOf(slot("#111111")))
        registry.update(0, 0, emptyList())
        assertTrue(registry.isEmpty)
        assertFalse(registry.isProvisional)
    }

    @Test
    fun aProvisionalRowOmitsItsSampledReadOnlyValue() {
        // The read-only row is where the palette hex would be readable as data.
        assertEquals(ExifGapNotice.MISSING_FIELD, readonlyTextSlotValue(slot("#222222", provisional = true)))
        assertEquals("#111111", readonlyTextSlotValue(slot("#111111")))
    }
}
