package com.framemark.native.shell

import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryCardGroup
import com.framemark.native.library.LibraryIndex
import com.framemark.native.shell.desktop.cardRows
import com.framemark.native.shell.desktop.desktopActiveAnchorId
import com.framemark.native.shell.desktop.desktopCardBadge
import com.framemark.native.shell.desktop.desktopCardSubtitle
import com.framemark.native.shell.desktop.desktopInUseCaption
import com.framemark.native.shell.desktop.desktopLibraryAnchors
import com.framemark.native.shell.desktop.desktopLibraryColumns
import com.framemark.native.shell.desktop.groupAtItem
import com.framemark.native.shell.desktop.headerAnchors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP-V2 左栏：网格列数（`auto-fill minmax(118px,1fr)`）、锚点行（`在用/最近/热门` + 系列名）、
 * 卡片名/副行/徽标、以及吸顶标题的 item 下标。纯逻辑，不依赖 Compose。
 */
class DesktopLibraryParityTest {

    private fun card(
        id: String,
        category: String = "white",
        name: String = id,
        categoryName: String = "白边边框",
        brand: String? = null,
        styleFamilyLabel: String? = null,
        templateCategory: String? = null,
        badge: String? = null,
        previewFile: String = "p.webp",
    ): LibraryCard = LibraryCard(
        id = id,
        name = name,
        category = category,
        categoryName = categoryName,
        brand = brand,
        vendor = null,
        family = null,
        fidelity = null,
        badge = badge,
        en = null,
        systemStyle = null,
        templateCategory = templateCategory,
        styleFamilyLabel = styleFamilyLabel,
        previewSourcePath = "previews/$id.webp",
        previewFile = previewFile,
    )

    private fun group(key: String, label: String, cards: List<LibraryCard>, note: String? = null) =
        LibraryCardGroup(key = key, label = label, note = note, cards = cards)

    @Test
    fun gridColumnsMatchAutoFill() {
        // 左栏 296 - 左右各 12 的内边距 = 272 → 两列（131dp）。
        assertEquals(2, desktopLibraryColumns(272f))
        // 刚好放不下两列（2*118 + 10 = 246 > 245）。
        assertEquals(1, desktopLibraryColumns(245f))
        assertEquals(2, desktopLibraryColumns(246f))
        assertEquals(3, desktopLibraryColumns(400f))
        assertEquals(1, desktopLibraryColumns(118f))
        assertEquals(1, desktopLibraryColumns(0f))
    }

    @Test
    fun rowsAreChunkedByColumnCount() {
        val cards = (1..5).map { card("c$it") }
        assertEquals(listOf(3, 2), cardRows(cards, 3).map { it.size })
        assertEquals(listOf(2, 2, 1), cardRows(cards, 2).map { it.size })
        assertTrue(cardRows(emptyList(), 2).isEmpty())
    }

    @Test
    fun commonGroupsUseShortAnchorWords() {
        val groups = listOf(
            group(LibraryIndex.GROUP_USE, LibraryIndex.LABEL_USE, listOf(card("u1"))),
            group(LibraryIndex.GROUP_RECENT, LibraryIndex.LABEL_RECENT, listOf(card("r1"), card("r2"))),
            group(LibraryIndex.GROUP_HOT, LibraryIndex.LABEL_HOT, listOf(card("h1"), card("h2"), card("h3"))),
        )
        val anchors = desktopLibraryAnchors(groups)
        assertEquals(listOf("在用", "最近", "热门"), anchors.map { it.label })
        assertEquals(listOf(1, 2, 3), anchors.map { it.count })
        assertEquals("use", anchors[0].target)
    }

    @Test
    fun vendorFamiliesMergeIntoOneBrandAnchor() {
        val groups = listOf(
            group("vendor|brand:OPPO|A", "OPPO · 理光 GR", listOf(card("v1", "vendor", brand = "OPPO"), card("v2", "vendor", brand = "OPPO"))),
            group("vendor|brand:OPPO|B", "OPPO · 其他", listOf(card("v3", "vendor", brand = "OPPO"))),
            group("vendor|brand:小米|C", "小米 · 经典", listOf(card("v4", "vendor", brand = "小米"))),
        )
        val anchors = desktopLibraryAnchors(groups)
        assertEquals(listOf("OPPO", "小米"), anchors.map { it.label })
        assertEquals(listOf(3, 1), anchors.map { it.count })
        assertEquals("vendor|brand:OPPO|A", anchors[0].target)
        assertEquals("vendor|brand:OPPO", desktopActiveAnchorId(groups, "vendor|brand:OPPO|B"))
    }

    @Test
    fun templateGroupLabelsDropCategoryPrefix() {
        val groups = listOf(
            group("template|white|厂商水印", "白边 · 厂商水印", listOf(card("w1"))),
            group("template|text|水印边框", "文字 · 水印边框", listOf(card("t1", "text"))),
        )
        // 只在类别页签里显示时没有前缀，锚点文字仍然是子类名。
        val anchors = desktopLibraryAnchors(groups)
        assertEquals(listOf("厂商水印", "水印边框"), anchors.map { it.label })
    }

    @Test
    fun groupHeadersCountCardRows() {
        val groups = listOf(
            group("a", "A", (1..4).map { card("a$it") }),
            group("b", "B", listOf(card("b1"))),
        )
        // 3 列：A = 标题 + 2 行 = 3 项；B 从第 3 项开始。
        assertEquals(mapOf("a" to 0, "b" to 3), headerAnchors(groups, 3))
        assertEquals("a", groupAtItem(groups, 3, 0)?.key)
        assertEquals("a", groupAtItem(groups, 3, 2)?.key)
        assertEquals("b", groupAtItem(groups, 3, 3)?.key)
        assertEquals("b", groupAtItem(groups, 3, 99)?.key)
    }

    @Test
    fun cardSubtitleUsesDataFieldsOnly() {
        val nonVendor = card("w1", templateCategory = "厂商水印")
        assertEquals("白边 · 厂商水印", desktopCardSubtitle(nonVendor))

        val vendor = card("v1", category = "vendor", categoryName = "厂商边框", brand = "OPPO", styleFamilyLabel = "OPPO / 理光 GR")
        assertEquals("OPPO · 理光 GR", desktopCardSubtitle(vendor))

        val noFamily = card("v2", category = "vendor", categoryName = "厂商边框", brand = "小米")
        assertEquals("小米", desktopCardSubtitle(noFamily))

        val brandless = card("v3", category = "vendor", categoryName = "厂商边框")
        assertEquals("其他", desktopCardSubtitle(brandless))
    }

    @Test
    fun badgeHidesApproximateButKeepsHot() {
        assertEquals("热门", desktopCardBadge(card("a", badge = "热门")))
        assertEquals("官方规格", desktopCardBadge(card("b", badge = "官方规格")))
        assertNull(desktopCardBadge(card("c", badge = "近似")))
        assertNull(desktopCardBadge(card("d", badge = "")))
        assertNull(desktopCardBadge(card("e")))
    }

    @Test
    fun inUseCaptionFollowsPhotoCount() {
        assertEquals("2 张照片在用", desktopInUseCaption(2))
        assertEquals("1 张照片在用", desktopInUseCaption(1))
        assertEquals("已套用", desktopInUseCaption(0))
    }

    @Test
    fun commonAnchorsKeepThreeSlotsWhenDataMissing() {
        val groups = listOf(group(LibraryIndex.GROUP_HOT, LibraryIndex.LABEL_HOT, listOf(card("h1"))))
        val kept = desktopLibraryAnchors(groups, keepCommonSlots = true)
        assertEquals(listOf("在用", "最近", "热门"), kept.map { it.label })
        assertEquals(listOf(0, 0, 1), kept.map { it.count })
        assertEquals(listOf(false, false, true), kept.map { it.enabled })
        // 不加槽位时保持演示稿 libAnchors 的“空组不出现”。
        assertEquals(listOf("热门"), desktopLibraryAnchors(groups).map { it.label })
    }
}
