package com.framemark.native.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 新边框库数据层的分组、搜索、逐个试顺序和“常用”三组。
 *
 * 夹具按真实 taxonomy 的形状搭：厂商卡带 `brand` + `styleFamily`，vivo 那种没有正式系列的卡
 * （`no-family-by-design`），没有品牌的厂商卡，非厂商卡带 `templateCategory`，两张 `badge == "热门"`。
 */
class LibraryIndexTest {

    private val verified = LibraryGroups.PROV_VERIFIED_IN_FILE
    private val noFamily = LibraryGroups.PROV_NO_FAMILY
    private val noFamilySource = "无族（no-family-by-design）"

    private fun card(
        id: String,
        category: String,
        name: String = id,
        brand: String? = null,
        vendor: String? = null,
        styleFamily: String? = null,
        styleFamilyLabel: String? = null,
        provenance: String? = null,
        provenanceSource: String? = null,
        badge: String? = null,
        templateCategory: String? = null,
    ): LibraryCard = LibraryCard(
        id = id,
        name = name,
        category = category,
        categoryName = "",
        brand = brand,
        vendor = vendor,
        family = null,
        fidelity = null,
        badge = badge,
        en = null,
        systemStyle = null,
        templateCategory = templateCategory,
        styleFamily = styleFamily,
        styleFamilyLabel = styleFamilyLabel,
        familyNameProvenance = provenance,
        styleFamilyLabelSource = provenanceSource,
        previewSourcePath = "",
        previewFile = "",
    )

    private fun verifiedFamily(id: String, brand: String, family: String, name: String = id): LibraryCard =
        card(
            id = id,
            category = LibraryIndex.CATEGORY_VENDOR,
            name = name,
            brand = brand,
            vendor = brand.lowercase(),
            styleFamily = "$brand / $family",
            styleFamilyLabel = "$brand / $family",
            provenance = verified,
        )

    private fun cards(): List<LibraryCard> = listOf(
        // 厂商卡：OPPO 两个系列、小米一个系列、vivo 无正式系列、一张没有品牌
        verifiedFamily("v-oppo-1", "OPPO", "哈苏定制", name = "哈苏 · 1"),
        verifiedFamily("v-oppo-2", "OPPO", "哈苏定制", name = "哈苏 · 2"),
        verifiedFamily("v-oppo-3", "OPPO", "理光 GR", name = "理光 GR · 上框"),
        verifiedFamily("v-xiaomi-1", "小米", "经典水印", name = "经典水印 · 1"),
        verifiedFamily("v-xiaomi-2", "小米", "经典水印", name = "经典水印 · 2"),
        card(
            id = "v-vivo-1",
            category = LibraryIndex.CATEGORY_VENDOR,
            name = "地理 · 信息叠加",
            brand = "vivo",
            vendor = "vivo",
            styleFamily = "UNVERIFIED(非固件) / -",
            styleFamilyLabel = "UNVERIFIED(非固件) / -",
            provenance = noFamily,
            provenanceSource = noFamilySource,
        ),
        card(
            id = "v-other-1",
            category = LibraryIndex.CATEGORY_VENDOR,
            name = "液态玻璃",
            vendor = "oppo",
            styleFamily = "UNVERIFIED(非固件) / -",
            styleFamilyLabel = "UNVERIFIED(非固件) / -",
            provenance = noFamily,
            provenanceSource = noFamilySource,
        ),
        // 非厂商卡：子类取 templateCategory
        card(
            id = "t-1",
            category = "text",
            name = "哈苏 · 白底",
            badge = LibraryIndex.HOT_BADGE,
            templateCategory = "厂商水印",
        ),
        card(id = "t-2", category = "text", name = "徕卡 · 白底", templateCategory = "厂商水印"),
        card(id = "t-3", category = "text", name = "自由文字"),
        card(
            id = "c-1",
            category = "card",
            name = "色卡 01",
            badge = LibraryIndex.HOT_BADGE,
            templateCategory = "卡片",
        ),
        card(id = "w-1", category = "white", name = "纯白 01", templateCategory = "留白"),
        card(id = "i-1", category = "impression", name = "Glass 01", templateCategory = "液态玻璃"),
        card(id = "col-1", category = "color", name = "色块", templateCategory = "色卡"),
        card(id = "m-1", category = "minimal", name = "极简 01", templateCategory = "比例"),
    )

    private fun categories(): List<LibraryCategory> = listOf(
        LibraryCategory("vendor", "厂商边框", 0),
        LibraryCategory("text", "文字边框", 0),
        LibraryCategory("impression", "印象边框", 0),
        LibraryCategory("white", "白边边框", 0),
        LibraryCategory("card", "卡片边框", 0),
        LibraryCategory("color", "色彩边框", 0),
        LibraryCategory("minimal", "简约边框", 0),
    )

    private fun snapshot(extra: List<LibraryCard> = emptyList()): LibrarySnapshot {
        val all = cards() + extra
        return LibrarySnapshot(
            cards = all,
            categories = categories(),
            mappedPreviews = 0,
            unmappedPreviewIds = emptyList(),
            generatedAt = "2026-11-06",
        )
    }

    private fun index() = LibraryIndex.of(snapshot())

    // ------------------------------------------------------------------ 类别

    @Test
    fun category_order_is_fixed_and_every_category_carries_its_count() {
        val index = index()
        val categories = index.categories()
        assertEquals(
            listOf("common", "vendor", "text", "white", "card", "impression", "color", "minimal"),
            categories.map { it.key },
        )
        assertEquals(
            listOf("常用", "厂商", "文字", "白边", "卡片", "印象", "色彩", "简约"),
            categories.map { it.name },
            "类别名取自 taxonomy 的 categoryName，砍掉“边框”两字",
        )
        assertEquals(index.cards.size, categories.filter { it.key != LibraryIndex.CATEGORY_COMMON }.sumOf { it.count })
        assertEquals(index.cards.count { it.category == "vendor" }, categories.first { it.key == "vendor" }.count)
        assertEquals(index.cards.count { it.category == "text" }, categories.first { it.key == "text" }.count)
        assertTrue(index.unmappedCategoryKeys().isEmpty())
    }

    @Test
    fun every_card_is_reachable_and_appears_exactly_once_across_the_categories() {
        val index = index()
        val seen = ArrayList<String>()
        for (category in LibraryIndex.CATEGORY_ORDER.filter { it != LibraryIndex.CATEGORY_COMMON }) {
            index.groups(LibraryIndexView(categoryKey = category)).forEach { seen.addAll(it.ids) }
        }
        assertEquals(index.cards.size, seen.size)
        assertEquals(index.cards.map { it.id }.toSet(), seen.toSet())
        assertEquals(seen.size, seen.toSet().size, "一张卡只能出现一次")
    }

    @Test
    fun taxonomy_categories_outside_the_eight_are_reported_not_swallowed() {
        val broken = LibraryIndex.of(
            snapshot(extra = listOf(card(id = "x-1", category = "video", name = "视频边框"))).copy(
                categories = categories() + LibraryCategory("video", "视频边框", 0),
            ),
        )
        assertEquals(listOf("video"), broken.unmappedCategoryKeys())
    }

    // ------------------------------------------------------------------ 厂商 / 系列

    @Test
    fun vendors_follow_library_groups_and_unbranded_cards_go_last() {
        val index = index()
        val vendors = index.vendors
        assertEquals(listOf("brand:OPPO", "brand:小米", "brand:vivo", LibraryIndex.VENDOR_OTHER), vendors.map { it.key })
        assertEquals(listOf("OPPO", "小米", "vivo", "其他"), vendors.map { it.label })
        assertEquals(listOf(3, 2, 1, 1), vendors.map { it.count })
        assertEquals(LibraryIndex.VENDOR_OTHER, index.vendorKeyOf(index.card("v-other-1")!!))
        assertNull(index.vendorKeyOf(index.card("t-1")!!), "非厂商卡不属于任何厂商分组")
    }

    @Test
    fun families_come_from_style_family_and_add_up_to_the_vendor_count() {
        val index = index()
        for (vendor in index.vendors) {
            val families = index.families(vendor.key)
            assertEquals(vendor.count, families.sumOf { it.count }, "${vendor.label} 的系列卡数之和必须等于厂商卡数")
            assertEquals(vendor.ids.toSet(), families.flatMap { it.ids }.toSet())
        }
        assertEquals(listOf("哈苏定制", "理光 GR"), index.families("brand:OPPO").map { it.label })
        assertEquals(listOf("经典水印"), index.families("brand:小米").map { it.label })
    }

    @Test
    fun cards_without_a_formal_family_land_in_one_understandable_group_and_keep_the_source_field() {
        val index = index()
        val vivoFamilies = index.families("brand:vivo")
        assertEquals(1, vivoFamilies.size)
        val bucket = vivoFamilies.single()
        assertEquals(LibraryIndex.NO_FORMAL_FAMILY, bucket.key)
        assertEquals(LibraryGroups.NO_FIRMWARE_LABEL, bucket.label)
        assertFalse(bucket.hasFormalFamily)
        assertNull(bucket.styleFamily, "没有正式系列时 styleFamily 原值不存在，不能编一个")
        assertEquals(noFamilySource, bucket.note, "来源字段原文保留")
        assertEquals(listOf("v-vivo-1"), bucket.ids)
        assertEquals(1, index.families(LibraryIndex.VENDOR_OTHER).single().count)
    }

    @Test
    fun series_directory_keys_are_the_same_keys_the_vendor_page_groups_by() {
        // 「点系列」要能定位到分组，靠的是目录和列表用同一个键公式；靠显示名去搜，
        // 没有正式系列的组（vivo）标签是「未在固件中证实」，和任何卡都对不上。
        val index = index()
        val vendorPage = index.groups(LibraryIndexView(categoryKey = LibraryIndex.CATEGORY_VENDOR))
        val realKeys = vendorPage.map { it.key }.toSet()
        for (vendor in index.vendors) {
            for (family in index.families(vendor.key)) {
                val key = LibraryIndex.vendorGroupKey(vendor.key, family.key)
                assertTrue(key in realKeys, "目录给的键必须是厂商页里的真实分组键：$key")
                assertEquals(key, vendorPage.first { it.key == key }.key)
            }
        }

        val vivo = index.families("brand:vivo").single()
        val vivoKey = LibraryIndex.vendorGroupKey("brand:vivo", vivo.key)
        assertEquals("vendor|brand:vivo|no-formal-family", vivoKey)
        assertTrue(vivoKey in realKeys, "vivo 的落点是品牌下唯一的分组，不是显示名")
        // 旧路径把组标签当搜索词：这句话不在任何卡的名称 / 厂商 / 系列里，只会「未匹配」。
        assertEquals(LibraryGroups.NO_FIRMWARE_LABEL, vivo.label)
        assertTrue(index.search(vivo.label).isEmpty())
    }

    @Test
    fun a_group_key_says_which_category_page_it_belongs_to() {
        assertEquals(
            LibraryIndex.CATEGORY_VENDOR,
            LibraryIndex.categoryKeyOfGroup("vendor|brand:vivo|no-formal-family"),
        )
        assertEquals("impression", LibraryIndex.categoryKeyOfGroup("template|impression|国风"))
        assertEquals(
            "template|text|厂商水印",
            LibraryIndex.templateGroupKey("text", "厂商水印"),
        )
        assertEquals("text", LibraryIndex.categoryKeyOfGroup(LibraryIndex.templateGroupKey("text", "厂商水印")))
        // 认不出的前缀 / 不在分类行里的分类 / 缺分类段 / 空键一律不猜。
        assertNull(LibraryIndex.categoryKeyOfGroup(null))
        assertNull(LibraryIndex.categoryKeyOfGroup(""))
        assertNull(LibraryIndex.categoryKeyOfGroup("   "))
        assertNull(LibraryIndex.categoryKeyOfGroup("vendor"))
        assertNull(LibraryIndex.categoryKeyOfGroup("brand:vivo"))
        assertNull(LibraryIndex.categoryKeyOfGroup("template|video|短剧"))
        assertNull(LibraryIndex.categoryKeyOfGroup("group|text|厂商水印"))
    }

    // ------------------------------------------------------------------ 搜索

    @Test
    fun search_matches_name_vendor_and_series_and_keeps_the_series_grouping() {
        val index = index()

        val bySeries = index.search("哈苏定制")
        assertEquals(1, bySeries.size)
        assertEquals("OPPO · 哈苏定制", bySeries.single().label)
        assertEquals(listOf("v-oppo-1", "v-oppo-2"), bySeries.single().ids)

        val byName = index.search("哈苏")
        assertEquals(listOf("OPPO · 哈苏定制", "文字 · 厂商水印"), byName.map { it.label })
        assertEquals(listOf("v-oppo-1", "v-oppo-2"), byName[0].ids)
        assertEquals(listOf("t-1"), byName[1].ids)

        val byVendor = index.search("OPPO")
        assertEquals(listOf("OPPO · 哈苏定制", "OPPO · 理光 GR"), byVendor.map { it.label })

        assertTrue(index.search("").isEmpty(), "空搜索词没有结果")
        assertTrue(index.search("   ").isEmpty())
        assertTrue(index.search("不存在的边框").isEmpty())
    }

    @Test
    fun search_ignores_case_and_full_width_and_needs_every_token() {
        val index = index()
        assertEquals(listOf("i-1"), index.search("glass").flatMap { it.ids })
        assertEquals(listOf("i-1"), index.search("GLASS").flatMap { it.ids })
        assertEquals(listOf("i-1"), index.search("ＧＬＡＳＳ").flatMap { it.ids }, "全角字母按半角算")
        assertEquals(index.search("OPPO").map { it.key }, index.search("ＯＰＰＯ").map { it.key })
        assertEquals(listOf("v-oppo-3"), index.search("理光  上框").flatMap { it.ids })
        assertTrue(index.search("理光 小米").isEmpty(), "多个词要求同时命中同一张卡")
    }

    @Test
    fun search_always_spans_every_category_even_when_a_category_is_selected() {
        val index = index()
        val view = LibraryIndexView(categoryKey = "text", query = "哈苏")
        assertEquals(
            index.search("哈苏").map { it.key },
            index.groups(view).map { it.key },
            "搜索不按当前类别收窄",
        )
    }

    // ------------------------------------------------------------------ 逐个试

    @Test
    fun traversal_matches_the_display_order_and_stops_at_both_ends() {
        val index = index()
        for (view in listOf(
            LibraryIndexView(),
            LibraryIndexView(categoryKey = LibraryIndex.CATEGORY_VENDOR),
            LibraryIndexView(categoryKey = LibraryIndex.CATEGORY_VENDOR, vendorKey = "brand:OPPO"),
            LibraryIndexView(categoryKey = "text"),
            LibraryIndexView(query = "哈苏"),
            LibraryIndexView(categoryKey = LibraryIndex.CATEGORY_COMMON, inUse = listOf("v-oppo-1")),
        )) {
            val display = index.groups(view).flatMap { it.ids }
            val traversal = index.traversal(view)
            assertEquals(display, traversal.ids, "逐个试的顺序必须和显示顺序逐项一致：$view")
            assertEquals(display.size, traversal.size)
            assertNull(traversal.prev(display.first()), "第一张不能再往前")
            assertNull(traversal.next(display.last()), "最后一张不能再往后，也不循环")
            assertEquals(display[1], traversal.next(display.first()))
            assertEquals(display[0], traversal.prev(display[1]))
            assertEquals(1, traversal.positionOf(display.first()))
            assertEquals(display.size, traversal.positionOf(display.last()))
        }
    }

    @Test
    fun traversal_of_an_empty_view_has_no_next_or_prev() {
        val empty = LibraryTraversal(emptyList())
        assertTrue(empty.isEmpty)
        assertEquals(0, empty.size)
        assertNull(empty.next(null))
        assertNull(empty.prev(null))
        assertNull(empty.next("v-oppo-1"))

        val traversal = index().traversal(LibraryIndexView(categoryKey = LibraryIndex.CATEGORY_VENDOR))
        assertNull(traversal.next("不在这个视图里的卡"))
        assertNull(traversal.prev("不在这个视图里的卡"))
        assertEquals(0, traversal.positionOf("不在这个视图里的卡"))
        assertEquals(traversal.ids.first(), traversal.next(null), "还没选时 next 给第一张")
        assertEquals(traversal.ids.last(), traversal.prev(null), "还没选时 prev 给最后一张")
    }

    @Test
    fun vendor_view_narrows_to_one_vendor_and_one_family() {
        val index = index()
        val oppo = index.groups(LibraryIndexView(categoryKey = LibraryIndex.CATEGORY_VENDOR, vendorKey = "brand:OPPO"))
        assertEquals(listOf("哈苏定制", "理光 GR"), oppo.map { it.label })
        assertEquals(3, oppo.sumOf { it.count })

        val ricoh = index.groups(
            LibraryIndexView(
                categoryKey = LibraryIndex.CATEGORY_VENDOR,
                vendorKey = "brand:OPPO",
                familyKey = "OPPO / 理光 GR",
            ),
        )
        assertEquals(listOf("v-oppo-3"), ricoh.flatMap { it.ids })
    }

    @Test
    fun non_vendor_categories_are_split_by_template_category() {
        val index = index()
        val text = index.groups(LibraryIndexView(categoryKey = "text"))
        assertEquals(listOf("厂商水印", "文字"), text.map { it.label })
        assertEquals(listOf("t-1", "t-2"), text[0].ids)
        assertEquals(listOf("t-3"), text[1].ids, "没有子类的卡自己成一段，标签退回类别名")

        val all = index.groups(LibraryIndexView())
        assertEquals("文字 · 厂商水印", all.first { it.key == "template|text|厂商水印" }.label)
    }

    // ------------------------------------------------------------------ 常用

    @Test
    fun common_groups_dedupe_by_first_appearance() {
        val index = index()
        val groups = index.common(
            inUse = listOf("v-oppo-1", "v-oppo-1", "v-xiaomi-1", "catalog-里没有的卡"),
            recent = listOf("v-oppo-1", "t-1", "c-1", "v-vivo-1"),
        )
        assertEquals(
            listOf(LibraryIndex.GROUP_USE, LibraryIndex.GROUP_RECENT),
            groups.map { it.key },
            "在用的卡已经在第一组出现过，最近使用和第二组热门都不再重复出现",
        )
        assertEquals(listOf(LibraryIndex.LABEL_USE, LibraryIndex.LABEL_RECENT), groups.map { it.label })
        assertEquals(listOf("v-oppo-1", "v-xiaomi-1"), groups[0].ids, "按出现顺序去重")
        assertEquals(listOf("t-1", "c-1", "v-vivo-1"), groups[1].ids)

        val hotOnly = index.common(inUse = emptyList(), recent = emptyList())
        assertEquals(listOf(LibraryIndex.GROUP_HOT), hotOnly.map { it.key }, "没有在用和最近使用时不返回空组")
        assertEquals(listOf("t-1", "c-1"), hotOnly.single().ids, "热门只取 badge == 热门，按数据顺序")
    }

    @Test
    fun common_recent_is_capped_and_hot_never_repeats_a_card() {
        val index = index()
        val eight = index.cards.map { it.id }.take(RecentFrames.MAX)
        assertEquals(RecentFrames.MAX, index.common(inUse = emptyList(), recent = eight).first { it.key == LibraryIndex.GROUP_RECENT }.count)

        val ten = index.cards.map { it.id }.take(RecentFrames.MAX + 2)
        assertEquals(
            RecentFrames.MAX,
            index.common(inUse = emptyList(), recent = ten).first { it.key == LibraryIndex.GROUP_RECENT }.count,
            "最近使用最多 8 个",
        )

        val everything = index.common(inUse = index.cards.map { it.id }, recent = emptyList())
        assertEquals(listOf(LibraryIndex.GROUP_USE), everything.map { it.key }, "全都在用时，热门一组是空的")
    }

    @Test
    fun common_count_feeds_the_common_category_and_the_category_row_hides_nothing() {
        val index = index()
        val inUse = listOf("v-oppo-1")
        val recent = listOf("v-oppo-2")
        val commonCount = index.common(inUse, recent).sumOf { it.count }
        assertEquals(commonCount, index.categories(inUse, recent).first { it.key == LibraryIndex.CATEGORY_COMMON }.count)
        assertEquals(commonCount, index.traversal(LibraryIndexView(categoryKey = LibraryIndex.CATEGORY_COMMON, inUse = inUse, recent = recent)).size)
    }
}
