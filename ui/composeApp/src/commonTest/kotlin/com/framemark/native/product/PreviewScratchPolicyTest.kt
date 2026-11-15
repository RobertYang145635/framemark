package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A-02: the rules that decide which render-cache files are garbage.
 *
 * The interesting part is the boundary between "scratch the app owns" and "a QA
 * runner's output / the evidence log": only the former may ever be deleted, and
 * the age/count rules must not start deleting fresh files while a render is
 * still in flight.
 */
class PreviewScratchPolicyTest {

    private val now = 1_000_000L

    private fun entry(name: String, ageMillis: Long, size: Long = 16) =
        PreviewScratchPolicy.Entry(name, size, now - ageMillis)

    @Test
    fun only_renderer_scratch_names_are_scratch() {
        for (name in listOf(
            "preview-oppo-gallery_lumo_style_1-1234.png",
            "export-5678",
            "export-5678.geom.json",
            "preview-x.png.report.json",
        )) {
            assertTrue(PreviewScratchPolicy.isScratch(name), name)
        }
        for (name in listOf(
            "evidence.jsonl",
            "p3-batch-row.png",
            "thumbs/123.png",
            "render.png",
            "x1-export",
        )) {
            assertFalse(PreviewScratchPolicy.isScratch(name), name)
        }
    }

    @Test
    fun files_older_than_the_stale_window_are_expired() {
        val entries = listOf(
            entry("preview-old.png", ageMillis = 2L * PreviewScratchPolicy.DEFAULT_STALE_MILLIS),
            entry("preview-fresh.png", ageMillis = 1_000L),
        )
        val expired = PreviewScratchPolicy.expired(entries, now)
        assertEquals(listOf("preview-old.png"), expired.map { it.name })
    }

    @Test
    fun fresh_files_beyond_the_cap_are_dropped_oldest_first() {
        val entries = listOf(
            entry("preview-a.png", ageMillis = 500),
            entry("preview-b.png", ageMillis = 400),
            entry("preview-c.png", ageMillis = 300),
            entry("preview-d.png", ageMillis = 200),
            entry("preview-e.png", ageMillis = 100),
        )
        val expired = PreviewScratchPolicy.expired(entries, now, maxFiles = 2)
        assertEquals(3, expired.size)
        // The two newest (d, e) are kept; the overflow drops the three oldest.
        assertEquals(listOf("preview-a.png", "preview-b.png", "preview-c.png"), expired.map { it.name })
    }

    @Test
    fun a_non_scratch_file_is_never_selected_even_when_it_is_the_oldest() {
        val entries = listOf(
            entry("evidence.jsonl", ageMillis = 10L * PreviewScratchPolicy.DEFAULT_STALE_MILLIS),
            entry("preview-a.png", ageMillis = 500),
            entry("preview-b.png", ageMillis = 400),
        )
        val expired = PreviewScratchPolicy.expired(entries, now, maxFiles = 1)
        assertTrue(expired.none { it.name == "evidence.jsonl" }, "QA evidence is not scratch")
        assertEquals(1, expired.size)
        assertEquals("preview-a.png", expired.single().name)
    }

    @Test
    fun nothing_to_do_is_not_an_error() {
        assertTrue(PreviewScratchPolicy.expired(emptyList(), now).isEmpty())
        assertEquals(
            emptyList(),
            PreviewScratchPolicy.expired(listOf(entry("preview-fresh.png", 1)), now),
        )
    }
}
