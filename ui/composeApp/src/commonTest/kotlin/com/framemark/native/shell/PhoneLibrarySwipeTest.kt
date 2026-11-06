package com.framemark.native.shell

import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.LibrarySnapshot
import com.framemark.native.shell.phone.PhoneLibraryView
import com.framemark.native.shell.phone.phoneGridJumpIndex
import com.framemark.native.shell.phone.phoneLibraryItemIndex
import com.framemark.native.shell.phone.phoneLibraryTraversal
import com.framemark.native.shell.phone.phoneLibraryView
import com.framemark.native.shell.phone.phoneStripJumpIndex
import com.framemark.native.shell.phone.phoneSwipeStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §4.3 / §5.2: the left/right swipe follows WP-L's `traversal` — the same order the
 * sheet renders — and never wraps at either end. The category/group view and the
 * jump indices come from the same index, so the chips cannot point at a group the
 * body does not show.
 */
class PhoneLibrarySwipeTest {

    private fun card(
        id: String,
        category: String,
        brand: String? = null,
        template: String? = null,
        badge: String? = null,
        family: String? = null,
    ): LibraryCard = LibraryCard(
        id = id,
        name = id,
        category = category,
        categoryName = if (category == "vendor") "厂商边框" else "白边边框",
        brand = brand,
        vendor = null,
        family = null,
        fidelity = null,
        badge = badge,
        en = null,
        systemStyle = null,
        templateCategory = template,
        styleFamily = family,
        styleFamilyLabel = family,
        previewSourcePath = "",
        previewFile = "$id.webp",
    )

    private fun index(cards: List<LibraryCard>): LibraryIndex = LibraryIndex.of(
        LibrarySnapshot(
            cards = cards,
            categories = emptyList(),
            mappedPreviews = cards.size,
            unmappedPreviewIds = emptyList(),
            generatedAt = "test",
        ),
    )

    private fun view(
        index: LibraryIndex,
        category: String = "white",
        query: String = "",
        inUse: List<String> = emptyList(),
        recent: List<String> = emptyList(),
    ): PhoneLibraryView = phoneLibraryView(
        index = index,
        categoryKey = category,
        vendorKey = null,
        query = query,
        inUse = inUse,
        recent = recent,
    )

    @Test
    fun the_swipe_order_is_exactly_the_order_the_sheet_renders() {
        val cards = listOf(
            card("w1", "white", template = "白边"),
            card("w2", "white", template = "白边"),
            card("w3", "white", template = "窄白边"),
            card("w4", "white", template = "窄白边"),
        )
        val idx = index(cards)
        val sheet = view(idx)
        val traversal = phoneLibraryTraversal(idx, "white", null, "", emptyList(), emptyList())

        assertEquals(sheet.cards.map { it.id }, traversal.ids)
        assertEquals(listOf("w1", "w2", "w3", "w4"), traversal.ids)

        // Walking forward from "not selected yet" visits the display order.
        var current: String? = null
        val visited = ArrayList<String>()
        repeat(traversal.size) {
            val step = phoneSwipeStep(traversal, current, +1)
            assertEquals(false, step.atEdge, "step=$step")
            assertEquals(visited.size + 1, step.position)
            assertEquals(traversal.size, step.total)
            current = step.targetId
            visited += current!!
        }
        assertEquals(traversal.ids, visited)
        // One more forward swipe is the end, with an honest message.
        val edge = phoneSwipeStep(traversal, current, +1)
        assertTrue(edge.atEdge)
        assertNull(edge.targetId)
        assertEquals("已是最后一个", edge.message)
    }

    @Test
    fun the_swipe_never_wraps_at_either_end() {
        val idx = index(listOf(card("w1", "white", template = "白边"), card("w2", "white", template = "白边")))
        val traversal = phoneLibraryTraversal(idx, "white", null, "", emptyList(), emptyList())

        val first = phoneSwipeStep(traversal, "w1", -1)
        assertTrue(first.atEdge)
        assertNull(first.targetId)
        assertEquals("已是第一个", first.message)

        val second = phoneSwipeStep(traversal, "w1", +1)
        assertFalse(second.atEdge)
        assertEquals("w2", second.targetId)
        assertEquals(2, second.position)
        assertEquals("第 2 / 2 个", second.message)

        // Backwards from the last card is ordinary, and stops at the first.
        val back = phoneSwipeStep(traversal, "w2", -1)
        assertEquals("w1", back.targetId)
        assertEquals(1, back.position)
        val beforeFirst = phoneSwipeStep(traversal, "w1", -1)
        assertTrue(beforeFirst.atEdge)
    }

    @Test
    fun an_empty_view_says_so_instead_of_swiping_into_nowhere() {
        val traversal = phoneLibraryTraversal(index(emptyList()), "white", null, "", emptyList(), emptyList())
        assertTrue(traversal.isEmpty)
        val step = phoneSwipeStep(traversal, null, +1)
        assertTrue(step.atEdge)
        assertNull(step.targetId)
        assertEquals(0, step.total)
        assertEquals("这里没有可切换的边框", step.message)
    }

    @Test
    fun the_vendor_row_aggregates_by_brand_and_jumps_to_that_brand() {
        val cards = listOf(
            card("o1", "vendor", brand = "OPPO", family = "OPPO / 徕卡水印"),
            card("o2", "vendor", brand = "OPPO", family = "OPPO / 经典水印"),
            card("m1", "vendor", brand = "小米", family = "小米 / 徕卡水印"),
            card("n1", "vendor"),
        )
        val idx = index(cards)
        val sheet = view(idx, category = LibraryIndex.CATEGORY_VENDOR)
        // One chip per brand, in WP-L's vendor order, with the brand's card count.
        assertEquals(listOf("OPPO", "小米", LibraryIndex.VENDOR_OTHER), sheet.anchors.map { it.label })
        assertEquals(idx.vendors.map { it.key }, sheet.anchors.map { it.key })
        assertEquals(listOf(2, 1, 1), sheet.anchors.map { it.count })
        // Each chip points at the first group of that brand, and every group exists.
        sheet.anchors.forEach { anchor ->
            assertTrue(sheet.groups.any { it.key == anchor.groupKey }, "missing group ${anchor.groupKey}")
            assertTrue(anchor.groupKey.startsWith("vendor|${anchor.key}|"), "bad key ${anchor.groupKey}")
        }
        // The series directory offers the same brands, and every OPPO card is
        // reachable through exactly one of its series rows.
        assertEquals(listOf("OPPO", "小米", LibraryIndex.VENDOR_OTHER), idx.vendors.map { it.label })
        assertEquals(2, idx.families("brand:OPPO").sumOf { it.count })
    }

    @Test
    fun the_common_category_keeps_the_three_groups_in_wp_l_order() {
        val cards = listOf(
            card("in-use", "white", template = "白边"),
            card("recent-1", "white", template = "白边"),
            card("hot-1", "white", template = "窄白边", badge = LibraryIndex.HOT_BADGE),
        )
        val idx = index(cards)
        val sheet = view(idx, category = LibraryIndex.CATEGORY_COMMON, inUse = listOf("in-use"), recent = listOf("recent-1"))
        assertEquals(listOf("use", "recent", "hot"), sheet.groups.map { it.key })
        assertEquals("本项目在用", sheet.groups.first().label)
        assertEquals(
            listOf("in-use", "recent-1", "hot-1"),
            phoneLibraryTraversal(idx, LibraryIndex.CATEGORY_COMMON, null, "", listOf("in-use"), listOf("recent-1")).ids,
        )
    }

    @Test
    fun the_jump_indices_match_the_body_layout_of_both_levels() {
        val cards = listOf(
            card("a1", "white", template = "白边"),
            card("a2", "white", template = "白边"),
            card("a3", "white", template = "白边"),
            card("a4", "white", template = "白边"),
            card("b1", "white", template = "窄白边"),
        )
        val sheet = view(index(cards))
        assertEquals(2, sheet.groups.size)
        val firstKey = sheet.groups[0].key
        val secondKey = sheet.groups[1].key

        // Strip: group tile + one item per card.
        assertEquals(0, phoneStripJumpIndex(sheet.groups, firstKey))
        assertEquals(1 + 4, phoneStripJumpIndex(sheet.groups, secondKey))
        assertEquals(1 + 4, phoneLibraryItemIndex(sheet.groups, secondKey) { g -> 1 + g.count })

        // Grid: header + one row per 3 cards.
        assertEquals(0, phoneGridJumpIndex(sheet.groups, firstKey))
        assertEquals(1 + 2, phoneGridJumpIndex(sheet.groups, secondKey))
        // An unknown key falls back to the first group instead of scrolling nowhere.
        assertEquals(0, phoneGridJumpIndex(sheet.groups, "missing"))
    }

    @Test
    fun search_keeps_the_series_grouping_and_the_same_traversal() {
        val cards = listOf(
            card("x1", "white", template = "白边"),
            card("x2", "white", template = "窄白边"),
            card("y1", "card", template = "卡片"),
        )
        val idx = index(cards)
        val sheet = view(idx, category = "white", query = "x2")
        assertEquals(1, sheet.groups.size)
        assertEquals(listOf("x2"), sheet.cards.map { it.id })
        assertEquals(
            sheet.cards.map { it.id },
            phoneLibraryTraversal(idx, "white", null, "x2", emptyList(), emptyList()).ids,
        )
    }
}
