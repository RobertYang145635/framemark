package com.framemark.native.shell

import com.framemark.native.library.InMemoryRecentFramesStore
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.LibrarySnapshot
import com.framemark.native.library.RecentFrames
import com.framemark.native.shell.phone.phoneLoadRecentFrames
import com.framemark.native.shell.phone.phoneRecentFramesPath
import com.framemark.native.shell.phone.phoneRecordRecentFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §4.3 / §5.2: the phone's 最近使用 goes through WP-L's codec — this test uses the
 * in-memory store, so it needs no Android device. The real Android file store
 * (`AndroidRecentFramesStore`) only adds the `settings/recent-frames.json` path and
 * the atomic write; the format and the corrupt-file fallback are [RecentFrames]'.
 */
class PhoneRecentFramesWiringTest {

    private fun card(id: String): LibraryCard = LibraryCard(
        id = id,
        name = id,
        category = "white",
        categoryName = "白边边框",
        brand = null,
        vendor = null,
        family = null,
        fidelity = null,
        badge = null,
        en = null,
        systemStyle = null,
        templateCategory = "白边",
        previewSourcePath = "",
        previewFile = "$id.webp",
    )

    private fun index(vararg ids: String): LibraryIndex = LibraryIndex.of(
        LibrarySnapshot(
            cards = ids.map(::card),
            categories = emptyList(),
            mappedPreviews = ids.size,
            unmappedPreviewIds = emptyList(),
            generatedAt = "test",
        ),
    )

    @Test
    fun the_path_is_the_settings_file_of_the_app_data_dir() {
        assertEquals("C:/data/settings/recent-frames.json", phoneRecentFramesPath("C:/data"))
        assertEquals("C:/data/settings/recent-frames.json", phoneRecentFramesPath("C:/data/"))
        assertEquals("C:\\data\\settings\\recent-frames.json", phoneRecentFramesPath("C:\\data"))
        // Same naming rule as WP-T's appearance.json.
        assertTrue(phoneRecentFramesPath("x").endsWith("settings/" + RecentFrames.FILE_NAME))
    }

    @Test
    fun recording_a_pick_moves_it_to_the_front_and_persists_the_encoded_document() {
        val store = InMemoryRecentFramesStore()
        val idx = index("a", "b", "c")

        assertEquals(listOf("a"), phoneRecordRecentFrame(store, idx, "a"))
        assertEquals(listOf("c", "a"), phoneRecordRecentFrame(store, idx, "c"))
        // Re-using a card moves it, it never duplicates.
        assertEquals(listOf("a", "c"), phoneRecordRecentFrame(store, idx, "a"))
        assertEquals(3, store.saveCount)

        // The file text is WP-L's document, and decoding it gives the same list back.
        val text = store.lastSavedText
        assertTrue(text != null && text.startsWith("{\"version\":1,\"cards\":["), "text=$text")
        assertEquals(listOf("a", "c"), RecentFrames.decode(text))
        assertEquals(listOf("a", "c"), phoneLoadRecentFrames(store, idx))
    }

    @Test
    fun at_most_eight_cards_are_kept_most_recent_first() {
        val ids = (1..10).map { "card-$it" }
        val store = InMemoryRecentFramesStore(RecentFrames.encode(ids))
        val idx = index(*ids.toTypedArray())
        assertEquals(ids.take(RecentFrames.MAX), phoneLoadRecentFrames(store, idx))
        val after = phoneRecordRecentFrame(store, idx, "card-10")
        assertEquals("card-10", after.first())
        assertEquals(RecentFrames.MAX, after.size)
    }

    @Test
    fun ids_unknown_to_the_catalog_are_dropped_instead_of_shown() {
        val store = InMemoryRecentFramesStore(RecentFrames.encode(listOf("ghost", "a")))
        val idx = index("a", "b")
        assertEquals(listOf("a"), phoneLoadRecentFrames(store, idx))
        // Recording an id the catalog does not know keeps the list unchanged.
        assertEquals(listOf("a"), phoneRecordRecentFrame(store, idx, "ghost"))
    }

    @Test
    fun a_missing_or_damaged_file_reads_as_an_empty_list() {
        assertTrue(phoneLoadRecentFrames(null, index("a")).isEmpty())
        assertTrue(phoneLoadRecentFrames(InMemoryRecentFramesStore(), index("a")).isEmpty())
        val corrupted = InMemoryRecentFramesStore("{\"version\":1,\"cards\":[")
        assertTrue(phoneLoadRecentFrames(corrupted, index("a")).isEmpty())
        val wrongVersion = InMemoryRecentFramesStore("{\"version\":99,\"cards\":[\"a\"]}")
        assertTrue(phoneLoadRecentFrames(wrongVersion, index("a")).isEmpty())
    }

    @Test
    fun the_last_list_survives_a_reload_of_the_same_file() {
        val file = InMemoryRecentFramesStore()
        val idx = index("a", "b")
        phoneRecordRecentFrame(file, idx, "a")
        phoneRecordRecentFrame(file, idx, "b")
        // A second store reading the same text is what an app restart looks like.
        val reopened = InMemoryRecentFramesStore(file.fileText)
        assertEquals(listOf("b", "a"), phoneLoadRecentFrames(reopened, idx))
        assertNull(reopened.lastSavedText)
    }
}
