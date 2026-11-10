package com.framemark.native.shell

import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.LibrarySnapshot
import com.framemark.native.shell.phone.PhoneLibraryView
import com.framemark.native.shell.phone.phoneGridItems
import com.framemark.native.shell.phone.phoneGridJumpIndex
import com.framemark.native.shell.phone.phoneLibraryItemIndex
import com.framemark.native.shell.phone.phoneLibraryView
import com.framemark.native.shell.phone.phoneStripItems
import com.framemark.native.shell.phone.phoneStripJumpIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Device bug (OPPO PGEM10): 边框页 → 厂商 → `OPPO 48`, and 常用 → `在用`, did nothing when
 * tapped. Both are the **first** group of their view, and [phoneLibraryItemIndex] used to
 * answer `0` for "this key is not in the body" as well, so the jump-or-not decision in
 * `PhoneLibrarySheet.jumpTo` could not tell "scroll to the very start" (a real, valid
 * destination) from "no such group". The index is `-1` for a key that is not there now,
 * which makes `0` unambiguously the first group.
 */
class PhoneLibraryJumpIndexTest {

    private fun card(
        id: String,
        category: String,
        template: String? = null,
        badge: String? = null,
    ): LibraryCard = LibraryCard(
        id = id,
        name = id,
        category = category,
        categoryName = if (category == "vendor") "厂商边框" else "白边边框",
        brand = null,
        vendor = null,
        family = null,
        fidelity = null,
        badge = badge,
        en = null,
        systemStyle = null,
        templateCategory = template,
        styleFamily = null,
        styleFamilyLabel = null,
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
        inUse: List<String> = emptyList(),
        recent: List<String> = emptyList(),
    ): PhoneLibraryView = phoneLibraryView(
        index = index,
        categoryKey = category,
        vendorKey = null,
        query = "",
        inUse = inUse,
        recent = recent,
    )

    /** Four 白边 cards then one 窄白边 card: two groups, the first one being item 0. */
    private fun twoGroupView(): PhoneLibraryView = view(
        index(
            listOf(
                card("a1", "white", template = "白边"),
                card("a2", "white", template = "白边"),
                card("a3", "white", template = "白边"),
                card("a4", "white", template = "白边"),
                card("b1", "white", template = "窄白边"),
            ),
        ),
    )

    @Test
    fun the_first_group_is_index_zero_and_not_the_not_found_sentinel() {
        val sheet = twoGroupView()
        val firstKey = sheet.groups.first().key

        // 0 is where the first group's tile / header really is: `jumpTo` must scroll there.
        assertEquals(0, phoneStripJumpIndex(sheet.groups, firstKey))
        assertEquals(0, phoneGridJumpIndex(sheet.groups, firstKey))
        assertEquals(0, phoneLibraryItemIndex(sheet.groups, firstKey, ::phoneStripItems))

        // The exact value `jumpTo` used to swallow (`if (target <= 0) return`) ...
        assertEquals(0, phoneStripJumpIndex(sheet.groups, firstKey))
        // ... and the value that now means "no such group" instead.
        assertEquals(-1, phoneStripJumpIndex(sheet.groups, "template|white|not-a-group"))
        assertNotEquals(-1, phoneStripJumpIndex(sheet.groups, firstKey))
    }

    @Test
    fun the_in_use_chip_of_常用_also_lands_on_zero() {
        val sheet = view(
            index(
                listOf(
                    card("in-use", "white", template = "白边"),
                    card("recent-1", "white", template = "窄白边"),
                ),
            ),
            category = LibraryIndex.CATEGORY_COMMON,
            inUse = listOf("in-use"),
            recent = listOf("recent-1"),
        )
        assertEquals(listOf("use", "recent"), sheet.groups.map { it.key })
        // The reported repro: 常用 → the `在用` chip is the first group of the view.
        assertEquals(0, phoneStripJumpIndex(sheet.groups, sheet.groups.first().key))
        assertEquals(0, phoneGridJumpIndex(sheet.groups, sheet.groups.first().key))
    }

    @Test
    fun a_middle_group_keeps_its_computed_index_in_both_levels() {
        val sheet = twoGroupView()
        val first = sheet.groups.first()
        val second = sheet.groups[1]

        // Strip: one tile + one item per card of every group before it.
        assertEquals(1 + first.count, phoneStripJumpIndex(sheet.groups, second.key))
        assertEquals(phoneStripItems(first), phoneStripJumpIndex(sheet.groups, second.key))
        // Grid: one header + one row per 3 cards of every group before it.
        assertEquals(1 + (first.count + 2) / 3, phoneGridJumpIndex(sheet.groups, second.key))
        assertEquals(1 + 2, phoneGridJumpIndex(sheet.groups, second.key))
        // Both levels agree with the per-group item counts the body actually renders.
        assertEquals(phoneGridItems(first), phoneGridJumpIndex(sheet.groups, second.key))
    }

    @Test
    fun a_group_that_is_not_in_the_body_answers_minus_one_everywhere() {
        val sheet = twoGroupView()
        val unknown = "template|white|不在这个视图里"

        assertEquals(-1, phoneStripJumpIndex(sheet.groups, unknown))
        assertEquals(-1, phoneGridJumpIndex(sheet.groups, unknown))
        assertEquals(-1, phoneLibraryItemIndex(sheet.groups, unknown, ::phoneStripItems))
        assertEquals(-1, phoneLibraryItemIndex(sheet.groups, unknown, ::phoneGridItems))
        // No key = no target (the reveal and the chips both have to skip).
        assertEquals(-1, phoneStripJumpIndex(sheet.groups, null))
        assertEquals(-1, phoneGridJumpIndex(sheet.groups, null))
        // An empty body answers -1 for every key instead of claiming item 0.
        assertEquals(-1, phoneStripJumpIndex(emptyList(), "template|white|白边"))
        assertTrue(phoneStripJumpIndex(emptyList(), "template|white|白边") < 0)
    }
}
