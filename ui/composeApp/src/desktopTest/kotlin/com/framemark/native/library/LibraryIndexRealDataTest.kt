package com.framemark.native.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 用 App 实际打包的那份 `taxonomy.json` / `previews.json` 跑一遍新边框库的数据层。
 *
 * 期望值全部从 taxonomy 自己算出来（类别表、`counts` 头、每张卡的字段），不写死数字：
 * 数据一改测试就跟着改，写死的数字只会在数据漂移时变成假通过。
 *
 * 说明：任务书 §3 把新增测试限定在 `commonTest\…\library\*Test.kt`，所以这里用 JVM 的文件读取
 * 直接打开仓库里的那份数据文件（`desktopTest` 和 androidUnitTest 都是 JVM 编译，都能编过），
 * 没有沿用 `desktopTest` 里 `RealData` 的写法。
 */
class LibraryIndexRealDataTest {

    private val taxonomy: Map<String, Any?> by lazy {
        Json.parse(ShippedData.text("taxonomy.json")).jsonMap()
    }

    private val rawCards: List<Map<String, Any?>> by lazy {
        taxonomy["cards"].jsonList().map { it.jsonMap() }
    }

    private val snapshot: LibrarySnapshot by lazy {
        LibraryDataSource.parse(ShippedData.text("taxonomy.json"), ShippedData.text("previews.json"))
    }

    private val index: LibraryIndex by lazy { LibraryIndex.of(snapshot) }

    // ------------------------------------------------------------------ 全部可达

    @Test
    fun all_shipped_cards_are_reachable_and_each_one_belongs_to_a_category() {
        val declaredCards = taxonomy["counts"].jsonMap().jsonInt("cards") ?: -1
        assertEquals(declaredCards, rawCards.size, "taxonomy 的 counts.cards 要和 cards 数组长度一致")
        assertEquals(rawCards.size, index.cards.size)
        assertEquals(rawCards.size, index.knownIds.size, "卡片 id 不能重复")
        assertTrue(
            index.unmappedCategoryKeys().isEmpty(),
            "taxonomy 出现 8 类之外的新类别，界面会漏卡：" + index.unmappedCategoryKeys(),
        )

        val declaredIds = rawCards.mapNotNull { it.jsonString("id") }
        val reachable = LinkedHashSet<String>()
        for (category in LibraryIndex.CATEGORY_ORDER.filter { it != LibraryIndex.CATEGORY_COMMON }) {
            val traversal = index.traversal(LibraryIndexView(categoryKey = category))
            assertTrue(traversal.ids.isNotEmpty(), "类别 $category 不能是空的")
            reachable.addAll(traversal.ids)
            assertEquals(
                rawCards.count { it.jsonString("category") == category },
                traversal.size,
                "类别 $category 的卡数要和 taxonomy 一致",
            )
        }
        assertEquals(declaredIds.toSet(), reachable, "一张卡都不能漏，没有品牌的卡也要能在某个类别里找到")
        assertEquals(reachable.size, declaredIds.size)

        val all = index.traversal(LibraryIndexView())
        assertEquals(declaredIds.toSet(), all.ids.toSet(), "“全部”视图同样一张不漏")
        assertEquals(all.size, all.ids.toSet().size, "同一张卡不能出现两次")
    }

    // ------------------------------------------------------------------ 类别

    @Test
    fun the_eight_categories_map_one_to_one_onto_the_taxonomy_and_the_counts_add_up() {
        val declared = taxonomy["categories"].jsonList().map { it.jsonMap() }
        val declaredKeys = declared.mapNotNull { it.jsonString("key") }

        assertEquals(8, LibraryIndex.CATEGORY_ORDER.size)
        assertEquals(
            listOf("common", "vendor", "text", "white", "card", "impression", "color", "minimal"),
            LibraryIndex.CATEGORY_ORDER,
            "类别顺序是定死的：常用、厂商、文字、白边、卡片、印象、色彩、简约",
        )
        assertEquals(
            declaredKeys.toSet(),
            LibraryIndex.CATEGORY_ORDER.filter { it != LibraryIndex.CATEGORY_COMMON }.toSet(),
            "taxonomy 的类别和 8 类一一对应，没有对不上的",
        )

        val categories = index.categories()
        val expectedCounts = rawCards.groupingBy { it.jsonString("category").orEmpty() }.eachCount()
        for (category in categories.filter { it.key != LibraryIndex.CATEGORY_COMMON }) {
            assertEquals(expectedCounts[category.key] ?: 0, category.count, "类别 ${category.name} 的卡数")
        }
        for (declaredCategory in declared) {
            val key = declaredCategory.jsonString("key") ?: continue
            val name = declaredCategory.jsonString("name").orEmpty().removeSuffix("边框")
            assertEquals(name, categories.first { it.key == key }.name, "类别名取 taxonomy 的 categoryName")
        }
        assertEquals(
            rawCards.size,
            categories.filter { it.key != LibraryIndex.CATEGORY_COMMON }.sumOf { it.count },
            "7 个真实类别加起来就是全部卡片",
        )

        val inUse = rawCards.take(3).mapNotNull { it.jsonString("id") }
        val recent = rawCards.drop(rawCards.size / 2).take(4).mapNotNull { it.jsonString("id") }
        assertEquals(
            index.common(inUse, recent).sumOf { it.count },
            index.categories(inUse, recent).first { it.key == LibraryIndex.CATEGORY_COMMON }.count,
            "“常用”的卡数就是三组去重后的张数",
        )
    }

    // ------------------------------------------------------------------ 厂商

    @Test
    fun vendor_groups_and_counts_match_the_taxonomy_and_unbranded_cards_go_last() {
        val vendorCards = rawCards.filter { it.jsonString("category") == LibraryIndex.CATEGORY_VENDOR }
        assertEquals(
            taxonomy["counts"].jsonMap().jsonMap("byCategory").jsonInt(LibraryIndex.CATEGORY_VENDOR),
            vendorCards.size,
        )

        val expected = vendorCards
            .groupingBy { raw -> raw.jsonString("brand")?.let { "brand:$it" } ?: LibraryIndex.VENDOR_OTHER }
            .eachCount()
        assertEquals(expected, index.vendors.associate { it.key to it.count }, "厂商卡数要和 taxonomy 一致")

        val unbranded = vendorCards.count { it.jsonString("brand") == null }
        assertEquals(LibraryIndex.VENDOR_OTHER, index.vendors.last().key, "没有品牌的卡放在最后")
        assertEquals(unbranded, index.vendors.last().count)
        assertEquals(
            LibraryGroups.vendorOrder(index.cards.filter { it.category == LibraryIndex.CATEGORY_VENDOR })
                .filter { it.startsWith("brand:") },
            index.vendors.map { it.key }.filter { it != LibraryIndex.VENDOR_OTHER },
            "品牌顺序沿用现有 LibraryGroups 的首次出现顺序",
        )
        assertEquals(
            LibraryGroups.labelOf("brand:OPPO"),
            index.vendors.first { it.key == "brand:OPPO" }.label,
            "显示名沿用现有代码的写法",
        )
    }

    // ------------------------------------------------------------------ 系列

    @Test
    fun the_family_directory_adds_up_to_each_vendor_and_uses_style_family() {
        for (vendor in index.vendors) {
            val families = index.families(vendor.key)
            assertTrue(families.isNotEmpty(), "${vendor.label} 没有系列目录")
            assertEquals(
                vendor.count,
                families.sumOf { it.count },
                "${vendor.label} 各系列的卡数之和必须等于这个厂商的卡数",
            )
            assertEquals(vendor.ids.toSet(), families.flatMap { it.ids }.toSet())
            assertEquals(families.size, families.map { it.key }.toSet().size, "系列键不能重复")

            val expectedFormal = vendor.cards
                .filter { it.familyNameProvenance == LibraryGroups.PROV_VERIFIED_IN_FILE }
                .mapNotNull { it.styleFamily }
                .toSet()
            assertEquals(
                expectedFormal,
                families.filter { it.hasFormalFamily }.mapNotNull { it.styleFamily }.toSet(),
                "${vendor.label} 的正式系列必须逐条来自 styleFamily，不按名称猜",
            )
            for (family in families.filter { it.hasFormalFamily }) {
                assertTrue(family.label.isNotBlank())
                assertFalse(family.label.startsWith("${vendor.label} / "), "系列标题去掉重复的厂商前缀")
            }
        }

        // 三个厂商的正式系列数都从数据算出来核对（vivo 19 张现在带固件族，
        // 见 cards_without_a_formal_family_land_in_one_group_that_keeps_the_source_field）。
        for (brand in listOf("OPPO", "小米", "vivo")) {
            val expectedFamilies = rawCards
                .filter { it.jsonString("category") == LibraryIndex.CATEGORY_VENDOR && it.jsonString("brand") == brand }
                .mapNotNull { it.jsonString("styleFamily") }
                .toSet()
            assertEquals(
                expectedFamilies.size,
                index.families("brand:$brand").count { it.hasFormalFamily },
                "$brand 的正式系列数",
            )
        }
    }

    @Test
    fun cards_without_a_formal_family_land_in_one_group_that_keeps_the_source_field() {
        // 19 张 vivo 卡现在都有固件族（vivo / 蔡司定制 等）；没有族的只剩手写复刻卡
        // （OPPO 的哈苏/LUMO/毕业、小米的徕卡白底、vivo 的蔡司白底等）。它们必须每个
        // 厂商收在同一个“未在固件中证实”分组里，且 styleFamily 保持 null。
        for (brand in listOf("OPPO", "小米", "vivo")) {
            val families = index.families("brand:$brand")
            val informal = families.filter { !it.hasFormalFamily }
            val expected = rawCards.filter {
                it.jsonString("category") == LibraryIndex.CATEGORY_VENDOR &&
                    it.jsonString("brand") == brand &&
                    it.jsonString("familyNameProvenance") != LibraryGroups.PROV_VERIFIED_IN_FILE
            }
            assertEquals(1, informal.size, "$brand 没有正式系列的卡必须收在一个分组里")
            val bucket = informal.single()
            assertEquals(LibraryIndex.NO_FORMAL_FAMILY, bucket.key)
            assertNull(bucket.styleFamily)
            assertEquals(LibraryGroups.NO_FIRMWARE_LABEL, bucket.label)
            assertEquals(expected.mapNotNull { it.jsonString("id") }.toSet(), bucket.ids.toSet())
            val source = expected
                .mapNotNull { it.jsonString("styleFamilyLabelSource")?.takeIf(String::isNotBlank) }
                .distinct()
            assertEquals(source.joinToString("；").ifBlank { null }, bucket.note, "组说明只取来源字段原文")
            assertTrue(
                bucket.cards.all { it.styleFamilyLabelSource.isNullOrBlank() || it.styleFamilyLabelSource in source },
                "来源字段原样保留，不改写",
            )
        }

        val other = index.families(LibraryIndex.VENDOR_OTHER)
        assertEquals(1, other.size)
        assertEquals(LibraryIndex.NO_FORMAL_FAMILY, other.single().key)
        assertTrue(other.single().count > 0)
    }

    // ------------------------------------------------------------------ 热门

    @Test
    fun the_hot_group_only_comes_from_the_badge_field() {
        val declaredHot = taxonomy["counts"].jsonMap().jsonMap("badge").jsonInt(LibraryIndex.HOT_BADGE) ?: -1
        val expectedIds = rawCards
            .filter { it.jsonString("badge") == LibraryIndex.HOT_BADGE }
            .mapNotNull { it.jsonString("id") }
        assertEquals(declaredHot, expectedIds.size, "taxonomy 的 counts.badge.热门 要和卡片对得上")

        val hot = index.common(emptyList(), emptyList()).single { it.key == LibraryIndex.GROUP_HOT }
        assertEquals(LibraryIndex.LABEL_HOT, hot.label)
        assertEquals(expectedIds, hot.ids, "热门只按 badge 取，顺序跟数据顺序一致")
    }

    // ------------------------------------------------------------------ 搜索

    @Test
    fun search_covers_name_vendor_and_series_and_stays_grouped_by_series() {
        val query = "哈苏"
        val groups = index.search(query)
        assertTrue(groups.isNotEmpty())
        assertEquals(groups.size, groups.map { it.key }.toSet().size, "搜索结果的分组键不重复")

        for (group in groups) {
            for (card in group.cards) {
                val haystack = listOfNotNull(card.name, card.brand, card.styleFamily, card.styleFamilyLabel)
                    .joinToString(" ")
                assertTrue(haystack.contains(query), "${card.id} 不该出现在“$query”的结果里")
            }
        }
        assertEquals(
            rawCards.count { raw ->
                listOf("name", "brand", "styleFamily", "styleFamilyLabel")
                    .any { raw.jsonString(it)?.contains(query) == true }
            },
            groups.sumOf { it.count },
            "搜索不能漏也不能多",
        )

        // 哈苏卡现在全部归到厂商类别（OPPO）：结果分成“OPPO · 哈苏定制”（固件族）
        // 和“OPPO · 未在固件中证实”（手写复刻的哈苏卡）两段。
        assertTrue(groups.any { it.label == "OPPO · 哈苏定制" }, groups.map { it.label }.toString())
        assertTrue(groups.any { it.label == "OPPO · ${LibraryGroups.NO_FIRMWARE_LABEL}" }, groups.map { it.label }.toString())

        // 厂商这一路：`vivo` 在 catalog 里只出现在 brand 字段，正好把“厂商”单拎出来验证
        val vivoCards = rawCards.filter { it.jsonString("brand") == "vivo" }.mapNotNull { it.jsonString("id") }
        assertTrue(vivoCards.isNotEmpty())
        val byVendor = index.search("vivo")
        assertEquals(vivoCards.toSet(), byVendor.flatMap { it.ids }.toSet(), "搜厂商要出齐这个厂商的全部卡")

        // 系列这一路：`哈苏定制` 只出现在 styleFamily / styleFamilyLabel 里
        val seriesCards = rawCards.filter { it.jsonString("styleFamily")?.contains("哈苏定制") == true }
        assertTrue(seriesCards.isNotEmpty())
        val bySeries = index.search("哈苏定制")
        assertEquals(seriesCards.size, bySeries.sumOf { it.count }, "搜系列要出齐这个系列的全部卡")
        assertEquals(1, bySeries.size)
        assertEquals("OPPO · 哈苏定制", bySeries.single().label)
    }

    @Test
    fun search_is_case_insensitive_and_full_width_insensitive_on_the_shipped_catalog() {
        assertEquals(
            index.search("oppo").map { it.key },
            index.search("OPPO").map { it.key },
        )
        assertEquals(
            index.search("OPPO").map { it.key },
            index.search("ＯＰＰＯ").map { it.key },
            "全角字母按半角算",
        )
        assertTrue(index.search("").isEmpty())
    }

    // ------------------------------------------------------------------ 逐个试

    @Test
    fun traversal_matches_the_display_order_for_every_category_and_stops_at_both_ends() {
        val inUse = rawCards.take(2).mapNotNull { it.jsonString("id") }
        val recent = rawCards.takeLast(3).mapNotNull { it.jsonString("id") }
        for (category in LibraryIndex.CATEGORY_ORDER) {
            val view = LibraryIndexView(categoryKey = category, inUse = inUse, recent = recent)
            val display = index.groups(view).flatMap { it.ids }
            val traversal = index.traversal(view)
            assertEquals(display, traversal.ids, "$category 的逐个试顺序必须等于显示顺序")
            assertEquals(display.size, display.toSet().size, "$category 里同一张卡不能出现两次")
            if (display.isNotEmpty()) {
                assertNull(traversal.prev(display.first()), "$category 的第一张不能再往前")
                assertNull(traversal.next(display.last()), "$category 的最后一张不能再往后，也不循环")
                assertEquals(1, traversal.positionOf(display.first()))
                assertEquals(display.size, traversal.positionOf(display.last()))
            }
        }
    }
}

/** 打开仓库里 App 实际打包的那份数据（从测试进程的工作目录往上找）。 */
private object ShippedData {

    private const val DATA_DIR = "ui/composeApp/src/commonMain/composeResources/files/data"

    private val directory: java.nio.file.Path = locate()

    private fun locate(): java.nio.file.Path {
        val starts = ArrayList<java.nio.file.Path>()
        runCatching { java.nio.file.Path.of(System.getProperty("user.dir") ?: ".") }
            .getOrNull()
            ?.let { starts.add(it.toAbsolutePath().normalize()) }
        runCatching {
            val location = ShippedData::class.java.protectionDomain?.codeSource?.location
            if (location != null) {
                val path = java.nio.file.Path.of(location.toURI())
                val base = if (java.nio.file.Files.isDirectory(path)) path else path.parent
                starts.add(base.toAbsolutePath().normalize())
            }
        }
        for (start in starts) {
            var candidate: java.nio.file.Path? = start
            while (candidate != null) {
                for (prefix in listOf("", "framemark/")) {
                    val dir = candidate.resolve(prefix + DATA_DIR)
                    if (java.nio.file.Files.isRegularFile(dir.resolve("taxonomy.json"))) return dir
                }
                candidate = candidate.parent
            }
        }
        error("找不到 $DATA_DIR/taxonomy.json（起点：$starts）")
    }

    fun text(name: String): String = java.nio.file.Files.readString(directory.resolve(name))
}

private fun Map<String, Any?>.jsonMap(key: String): Map<String, Any?> = this[key].jsonMap()
