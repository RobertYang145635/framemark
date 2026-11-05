package com.framemark.native.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** “最近使用”：上限 8、重复使用移到最前、往返、文件缺失 / 损坏 / 版本号不认识、未知 id 丢弃。 */
class RecentFramesTest {

    private val known = setOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")

    @Test
    fun keeps_at_most_eight_and_puts_the_most_recent_first() {
        var list = emptyList<String>()
        for (id in listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")) {
            list = RecentFrames.touch(list, id)
        }
        assertEquals(RecentFrames.MAX, list.size)
        assertEquals(listOf("j", "i", "h", "g", "f", "e", "d", "c"), list)
    }

    @Test
    fun using_a_card_again_moves_it_to_the_front_without_duplicating() {
        val list = RecentFrames.touch(listOf("c", "b", "a"), "b")
        assertEquals(listOf("b", "c", "a"), list)
        assertEquals(list.size, list.toSet().size)

        var cycle = emptyList<String>()
        repeat(2) { for (id in listOf("a", "b", "c")) cycle = RecentFrames.touch(cycle, id) }
        assertEquals(listOf("c", "b", "a"), cycle, "同一轮顺序重复使用，结果只有顺序变化，不重复记")
        assertEquals(3, cycle.size)
    }

    @Test
    fun encode_uses_the_fixed_format() {
        assertEquals("{\"version\":1,\"cards\":[\"a\",\"b\"]}", RecentFrames.encode(listOf("a", "b")))
        assertEquals("{\"version\":1,\"cards\":[]}", RecentFrames.encode(emptyList()))
        assertEquals(
            "{\"version\":1,\"cards\":[\"a\",\"b\"]}",
            RecentFrames.encode(listOf("a", "a", "  ", "b", "")),
            "写之前先去空去重",
        )
        assertEquals(
            "{\"version\":1,\"cards\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\"]}",
            RecentFrames.encode(listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")),
            "写之前截到 8 个",
        )
    }

    @Test
    fun write_then_read_returns_the_same_list() {
        val store = InMemoryRecentFramesStore()
        var list = RecentFrames.load(store, known)
        assertTrue(list.isEmpty(), "文件不存在时是空列表")
        for (id in listOf("a", "b", "c")) list = RecentFrames.record(store, id, known)
        assertEquals(listOf("c", "b", "a"), list)
        assertEquals(list, RecentFrames.load(store, known))
        assertEquals(3, store.saveCount)

        list = RecentFrames.record(store, "a", known)
        assertEquals(listOf("a", "c", "b"), list)
        assertEquals(listOf("a", "c", "b"), RecentFrames.load(store, known))
    }

    @Test
    fun a_missing_file_reads_as_an_empty_list() {
        assertEquals(emptyList(), RecentFrames.load(InMemoryRecentFramesStore(initialFileText = null), known))
        val store = InMemoryRecentFramesStore(initialFileText = RecentFrames.encode(listOf("a")))
        store.deleteFile()
        assertEquals(emptyList(), RecentFrames.load(store, known))
        assertEquals(emptyList(), RecentFrames.decode(null))
        assertEquals(emptyList(), RecentFrames.decode(""))
        assertEquals(emptyList(), RecentFrames.decode("   \n "))
    }

    @Test
    fun a_corrupt_file_reads_as_an_empty_list_without_throwing() {
        val corrupt = listOf(
            "{",
            "{\"version\":1,\"cards\":[\"a\"",
            "{\"version\":1,\"cards\":}",
            "{\"version\":1,\"cards\":{\"a\":1}}",
            "{\"version\":1}",
            "{}",
            "[]",
            "not json at all",
            "\u0000\u0001",
        )
        for (text in corrupt) {
            assertEquals(emptyList(), RecentFrames.decode(text), "损坏内容应当读成空列表：$text")
            val store = InMemoryRecentFramesStore(initialFileText = text)
            assertEquals(emptyList(), RecentFrames.load(store, known), "损坏文件不能把异常抛到界面：$text")
        }
    }

    @Test
    fun an_unknown_version_reads_as_an_empty_list() {
        assertEquals(emptyList(), RecentFrames.decode("{\"version\":2,\"cards\":[\"a\"]}"))
        assertEquals(emptyList(), RecentFrames.decode("{\"version\":0,\"cards\":[\"a\"]}"))
        assertEquals(emptyList(), RecentFrames.decode("{\"cards\":[\"a\"]}"), "没有版本号也算不认识")
        assertEquals(
            listOf("a"),
            RecentFrames.decode("{\"version\":1,\"cards\":[\"a\"]}"),
            "当前版本仍要能正常读回来",
        )
    }

    @Test
    fun card_ids_that_are_not_in_the_catalog_are_dropped() {
        val store = InMemoryRecentFramesStore(
            initialFileText = RecentFrames.encode(listOf("a", "已经没有这张卡", "b", "card-deleted-in-catalog")),
        )
        assertEquals(listOf("a", "b"), RecentFrames.load(store, known))
        assertEquals(listOf("b"), RecentFrames.load(store, setOf("b")), "只保留传入 known 里的卡")

        val afterRecord = RecentFrames.record(store, "c", known)
        assertEquals(listOf("c", "a", "b"), afterRecord)
        assertEquals(listOf("c", "a", "b"), RecentFrames.load(store, known))

        val unknownId = RecentFrames.record(InMemoryRecentFramesStore(), "不存在的卡", known)
        assertEquals(emptyList(), unknownId, "不认识的卡不记")
    }

    @Test
    fun a_store_that_throws_is_treated_as_an_empty_list() {
        val throwing = object : RecentFramesStore {
            override fun load(): List<String> = error("读文件炸了")
            override fun save(ids: List<String>) = error("写文件炸了")
        }
        assertEquals(emptyList(), RecentFrames.load(throwing, known))
        assertEquals(listOf("a"), RecentFrames.record(throwing, "a", known), "写失败不影响本次结果")
    }

    @Test
    fun decode_skips_non_string_entries_and_caps_at_eight() {
        assertEquals(
            listOf("a", "b"),
            RecentFrames.decode("{\"version\":1,\"cards\":[\"a\",null,3,\"b\",true]}"),
        )
        val ten = RecentFrames.encode(listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"))
        assertEquals(RecentFrames.MAX, RecentFrames.decode(ten).size)
        assertEquals(
            RecentFrames.MAX,
            RecentFrames.decode("{\"version\":1,\"cards\":[\"a\",\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\"]}").size,
        )
    }

    @Test
    fun ids_that_need_escaping_survive_the_round_trip() {
        val weird = listOf("a\"b", "c\\d", "e\tf")
        val text = RecentFrames.encode(weird)
        assertEquals(weird, RecentFrames.decode(text))
    }

    @Test
    fun the_index_supplies_the_known_ids() {
        val index = LibraryIndex.of(
            LibrarySnapshot(
                cards = listOf(
                    LibraryCard(
                        id = "only-card",
                        name = "唯一",
                        category = "text",
                        categoryName = "文字边框",
                        brand = null,
                        vendor = null,
                        family = null,
                        fidelity = null,
                        badge = null,
                        en = null,
                        systemStyle = null,
                        previewSourcePath = "",
                        previewFile = "",
                    ),
                ),
                categories = listOf(LibraryCategory("text", "文字边框", 1)),
                mappedPreviews = 0,
                unmappedPreviewIds = emptyList(),
                generatedAt = "",
            ),
        )
        val store = InMemoryRecentFramesStore(initialFileText = RecentFrames.encode(listOf("only-card", "gone")))
        assertEquals(listOf("only-card"), RecentFrames.load(store, index))
        assertEquals(listOf("only-card"), RecentFrames.record(store, "also-gone", index))
    }
}
