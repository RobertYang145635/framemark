package com.framemark.native.shell.phone

import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryCardGroup
import com.framemark.native.library.LibraryCategory
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.LibrarySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 手机端全高边框页的纯状态契约（无 Compose、无资源、无持久化）：
 * 分类 ⇄ 页码双向换算、选中态、网格条目下标，以及「整页只有一个滚动区」这条
 * 只能靠结构表达的不变量。
 *
 * 页面本身不再把卡片条塞进一个很矮的底板里横竖两轴滚：分类是一页一个
 * （`HorizontalPager`），每页一个 2 列网格，页脚的主按钮永远固定。
 */
class PhoneBorderPageStateTest {

    private fun card(
        id: String,
        name: String = id,
        category: String = "vendor",
        categoryName: String = "厂商",
        brand: String? = null,
        templateCategory: String? = null,
    ) = LibraryCard(
        id = id,
        name = name,
        category = category,
        categoryName = categoryName,
        brand = brand,
        vendor = null,
        family = null,
        fidelity = null,
        badge = null,
        en = null,
        systemStyle = null,
        templateCategory = templateCategory,
        previewSourcePath = "previews/$id.webp",
        previewFile = "$id.webp",
    )

    private fun group(
        key: String,
        cards: List<LibraryCard>,
        label: String = key,
        note: String? = null,
    ) = LibraryCardGroup(key = key, label = label, note = note, cards = cards)

    private fun category(key: String, name: String = key) = LibraryCategory(key = key, name = name, count = 0)

    private val oppo = card("oppo-fin", name = "OPPO 经典", brand = "OPPO", templateCategory = "经典")
    private val vivo = card("vivo-fin", name = "vivo 标准", brand = "vivo", templateCategory = "标准")
    private val white = card("white-1", name = "白边 细", category = "white", categoryName = "白边", templateCategory = "细")

    private val groups = listOf(
        group("template|vendor|经典", listOf(oppo), label = "经典"),
        group("template|vendor|标准", listOf(vivo, white), label = "标准"),
    )

    /** 分类行的一项一格；顺序就是 taxonomy 的顺序（这里只取前四项）。 */
    private val categories = listOf(
        category(LibraryIndex.CATEGORY_COMMON, "常用"),
        category(LibraryIndex.CATEGORY_VENDOR, "厂商"),
        category("text", "文字"),
        category("white", "白边"),
    )

    // -- 分类 ⇄ 页码 -----------------------------------------------------------

    @Test
    fun onePagePerCategoryInTheTaxonomyOrder() {
        // 分类不从这一页“发明”：8 页 = `LibraryIndex.CATEGORY_ORDER` 的 8 项。
        val cards = LibraryIndex.CATEGORY_ORDER.map { key -> card("c-$key", category = key) }
        val index = LibraryIndex.of(
            LibrarySnapshot(
                cards = cards,
                categories = LibraryIndex.CATEGORY_ORDER.map { key -> LibraryCategory(key, key, 1) },
                mappedPreviews = cards.size,
                unmappedPreviewIds = emptyList(),
                generatedAt = "test",
            ),
        )
        val real = index.categories()
        assertEquals(LibraryIndex.CATEGORY_ORDER.size, PhoneBorderPageState.pageCount(real))
        assertEquals(8, PhoneBorderPageState.pageCount(real))
        assertEquals(LibraryIndex.CATEGORY_ORDER, real.map { it.key })
        assertEquals(0, PhoneBorderPageState.pageForCategory(real, LibraryIndex.CATEGORY_COMMON))
        assertEquals(1, PhoneBorderPageState.pageForCategory(real, LibraryIndex.CATEGORY_VENDOR))
        assertEquals(7, PhoneBorderPageState.pageForCategory(real, "minimal"))
    }

    @Test
    fun everyPageMapsBackToItsOwnCategoryKey() {
        assertEquals(categories.size, PhoneBorderPageState.pageCount(categories))
        categories.indices.forEach { page ->
            val key = PhoneBorderPageState.categoryKeyForPage(categories, page)
            assertEquals(categories[page].key, key)
            // 页码 → 键 → 页码 是恒等：左右滑过去再滑回来落在同一页。
            assertEquals(page, PhoneBorderPageState.pageForCategory(categories, key))
        }
    }

    @Test
    fun anUnknownCategoryKeyOpensTheFirstPageInsteadOfAnIllegalPageIndex() {
        // `rememberPagerState` 的首页必须是合法页：未知 / 空键都回第一页，绝不返回 -1。
        assertEquals(0, PhoneBorderPageState.pageForCategory(categories, "not-a-category"))
        assertEquals(0, PhoneBorderPageState.pageForCategory(categories, null))
        assertEquals(0, PhoneBorderPageState.pageForCategory(emptyList(), "vendor"))
        assertEquals(1, PhoneBorderPageState.pageForCategory(categories, LibraryIndex.CATEGORY_VENDOR))
    }

    @Test
    fun anOutOfRangePageAnswersNullSoTheCallerKeepsTheCurrentCategory() {
        // 越界不是“第一页”：调用方应当保持现状，而不是替用户换分类。
        assertNull(PhoneBorderPageState.categoryKeyForPage(categories, -1))
        assertNull(PhoneBorderPageState.categoryKeyForPage(categories, categories.size))
        assertNull(PhoneBorderPageState.categoryKeyForPage(emptyList(), 0))
        assertEquals("text", PhoneBorderPageState.categoryKeyForPage(categories, 2))
    }

    // -- 选中态 ---------------------------------------------------------------

    @Test
    fun selectedStateFollowsTheAppliedBorderId() {
        assertTrue(PhoneBorderPageState.isSelected(oppo, "oppo-fin"))
        assertFalse(PhoneBorderPageState.isSelected(oppo, "vivo-fin"))
        // 没有已应用的边框时没有任何一格是“选中”。
        assertFalse(PhoneBorderPageState.isSelected(oppo, null))
    }

    @Test
    fun theFooterNamesTheSelectionAndOnlyThenEnablesThePrimaryAction() {
        assertEquals("将使用：OPPO 经典", PhoneBorderPageState.footerTitle(oppo))
        assertEquals(PhoneBorderPageState.APPLY_LABEL, PhoneBorderPageState.applyLabel(oppo))
        assertTrue(PhoneBorderPageState.canApply(oppo))

        assertEquals("还没有选边框", PhoneBorderPageState.footerTitle(null))
        assertEquals(PhoneBorderPageState.APPLY_LABEL_EMPTY, PhoneBorderPageState.applyLabel(null))
        assertFalse(PhoneBorderPageState.canApply(null))
        // 主按钮的文案就是需求里的那一个，不能被悄悄改掉。
        assertEquals("使用此边框", PhoneBorderPageState.APPLY_LABEL)
    }

    @Test
    fun theSelectedBorderIsFoundAnywhereOnThePage() {
        assertEquals(oppo, PhoneBorderPageState.selectedCard(groups, "oppo-fin"))
        assertEquals(white, PhoneBorderPageState.selectedCard(groups, "white-1"))
        assertNull(PhoneBorderPageState.selectedCard(groups, "sony-fin"))
        assertNull(PhoneBorderPageState.selectedCard(groups, null))
        assertNull(PhoneBorderPageState.selectedCard(emptyList(), "oppo-fin"))
    }

    // -- 网格条目下标 ---------------------------------------------------------

    @Test
    fun groupHeaderIndexCountsOneHeaderPerGroup() {
        assertEquals(2, PhoneBorderPageState.groupItems(groups[0]))
        assertEquals(3, PhoneBorderPageState.groupItems(groups[1]))
        // 第一组：标题 0 + 卡片 1；第二组标题紧跟在 1 + 张数之后。
        assertEquals(0, PhoneBorderPageState.groupItemIndex(groups, groups[0].key))
        assertEquals(1 + groups[0].count, PhoneBorderPageState.groupItemIndex(groups, groups[1].key))
        assertEquals(2, PhoneBorderPageState.groupItemIndex(groups, groups[1].key))
    }

    @Test
    fun aLeadingItemShiftsEveryGroupByItsOwnCount() {
        // 厂商页最前面是「系列目录」入口，分组标题因此整体后移一位。
        assertEquals(1, PhoneBorderPageState.groupItemIndex(groups, groups[0].key, leadingItems = 1))
        assertEquals(3, PhoneBorderPageState.groupItemIndex(groups, groups[1].key, leadingItems = 1))
    }

    @Test
    fun anUnknownGroupAnswersMinusOneBecauseZeroIsARealDestination() {
        // 0 是真实目标（第一组标题），所以“找不到”只能是 -1。
        assertEquals(-1, PhoneBorderPageState.groupItemIndex(groups, "template|vendor|不存在"))
        assertEquals(-1, PhoneBorderPageState.groupItemIndex(groups, null))
        assertEquals(-1, PhoneBorderPageState.groupItemIndex(emptyList(), groups[0].key))
        assertEquals(-1, PhoneBorderPageState.groupItemIndex(groups, groups[0].key, leadingItems = -1))
        assertEquals(0, PhoneBorderPageState.groupItemIndex(groups, groups[0].key))
    }

    // -- 只有一个滚动区（结构不变量） -----------------------------------------

    @Test
    fun theGridIsTheOnlyScrollableBandOnThePage() {
        assertEquals(
            listOf(PhoneBorderPageBand.Grid),
            PhoneBorderPageState.SCROLLABLE_BANDS,
        )
        // 标题行、分类行、搜索行、页脚全部固定：竖着滚的容器不会再套一层横着滚的列表。
        assertEquals(
            listOf(
                PhoneBorderPageBand.Header,
                PhoneBorderPageBand.Categories,
                PhoneBorderPageBand.Search,
                PhoneBorderPageBand.Footer,
            ),
            PhoneBorderPageState.fixedBands(),
        )
        assertFalse(PhoneBorderPageBand.Categories in PhoneBorderPageState.SCROLLABLE_BANDS)
        assertFalse(PhoneBorderPageBand.Footer in PhoneBorderPageState.SCROLLABLE_BANDS)
    }

    @Test
    fun theBandsPartitionThePageFromHeaderToPinnedAction() {
        // 分区不重不漏，顺序就是布局顺序：标题在顶、主操作在底。
        assertEquals(
            PhoneBorderPageState.fixedBands().size + PhoneBorderPageState.SCROLLABLE_BANDS.size,
            PhoneBorderPageState.BANDS.size,
        )
        assertEquals(PhoneBorderPageState.BANDS.size, PhoneBorderPageState.BANDS.distinct().size)
        assertEquals(PhoneBorderPageBand.Header, PhoneBorderPageState.BANDS.first())
        assertEquals(PhoneBorderPageBand.Footer, PhoneBorderPageState.BANDS.last())
        // 主操作固定在最底部那一段，永不进滚动区。
        assertEquals(PhoneBorderPageBand.Footer, PhoneBorderPageState.PRIMARY_ACTION_BAND)
        assertFalse(PhoneBorderPageState.PRIMARY_ACTION_BAND in PhoneBorderPageState.SCROLLABLE_BANDS)
    }

    @Test
    fun theGridIsTwoColumnsSoTheTileStaysLegible() {
        // 3 列时 128 dp 的方图在手机宽度下看不清、点不准；两列是阅读决定。
        assertEquals(2, PhoneBorderPageState.GRID_COLUMNS)
        assertTrue(PhoneBorderPageState.GRID_COLUMNS >= 2)
    }

    // -- 空态 ----------------------------------------------------------------

    @Test
    fun theEmptyNoteSaysWhetherItIsTheCategoryOrTheSearch() {
        assertEquals("这个分类下还没有边框。", PhoneBorderPageState.emptyNote(""))
        assertEquals("这个分类下还没有边框。", PhoneBorderPageState.emptyNote("   "))
        assertEquals("没有匹配「OPPO」的边框。", PhoneBorderPageState.emptyNote(" OPPO "))
        assertNotNull(PhoneBorderPageState.EMPTY_CATEGORY_NOTE)
    }
}
