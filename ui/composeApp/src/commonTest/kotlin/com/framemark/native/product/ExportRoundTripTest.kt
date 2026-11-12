package com.framemark.native.product

import com.framemark.native.shell.desktop.ExportRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The document must be able to read back everything the export form can write.
 *
 * These two sides used to be maintained separately: the form accepted a long edge
 * of 1 while the codec only read 64 and up, and a cleared filename pattern was
 * written happily but rejected on load. Either one left the user with a project
 * that would not open — no photos, no designs, no way back except editing JSON by
 * hand. The round trip is asserted directly now, so the two ranges cannot drift
 * apart again without a test failing.
 */
class ExportRoundTripTest {

    private fun roundTrip(settings: ExportSettings): ExportSettings {
        val encoded = encodeExport(settings)
        val root = ProductJson.parseRoot("{\"export\":$encoded}")
        return decodeExport(root["export"], "export")
    }

    @Test
    fun every_long_edge_the_form_accepts_survives_a_save_and_a_reload () {
        val edges = listOf(ExportRules.LONG_EDGE_MIN, 1200, 4096, ExportRules.LONG_EDGE_MAX)
        for (edge in edges) {
            val settings = ExportSettings(
                format = ExportFormat.Jpeg,
                sizeMode = ExportSizeMode.LongEdge,
                longEdge = edge,
            )
            assertEquals(settings, roundTrip(settings), "longEdge=$edge")
        }
    }

    @Test
    fun a_document_written_before_the_ranges_were_unified_still_loads () {
        // 1..63 was representable in the old form, so such documents exist on disk.
        // They must load (clamped), never turn the whole project unreadable.
        val back = roundTrip(ExportSettings(sizeMode = ExportSizeMode.LongEdge, longEdge = 1))
        assertEquals(ExportSizeMode.LongEdge, back.sizeMode)
        assertEquals(ExportRules.LONG_EDGE_MIN, back.longEdge)
    }

    @Test
    fun a_cleared_filename_pattern_falls_back_instead_of_blocking_the_project () {
        assertEquals("{photo}-{index}", roundTrip(ExportSettings(filenamePattern = "")).filenamePattern)
        assertEquals("{photo}-{index}", roundTrip(ExportSettings(filenamePattern = "   ")).filenamePattern)
    }

    @Test
    fun the_hdr_preference_survives_a_round_trip_and_stays_out_of_the_file_when_off () {
        assertEquals(ExportSettings(preserveHdr = true), roundTrip(ExportSettings(preserveHdr = true)))
        // Off is the default, so it must not change the bytes of an existing
        // document (WireFormatFreezeTest pins the same property from the other side).
        assertTrue(!encodeExport(ExportSettings()).contains("preserveHdr"))
    }

    @Test
    fun the_form_and_the_codec_agree_on_the_lower_bound () {
        assertEquals(64, ExportRules.LONG_EDGE_MIN)
        assertNull(ExportRules.parseLongEdge("1"), "the form must refuse a value the codec would clamp")
        assertNull(ExportRules.parseLongEdge("63"))
        assertEquals(ExportRules.LONG_EDGE_MIN, ExportRules.parseLongEdge("64"))
    }
}
