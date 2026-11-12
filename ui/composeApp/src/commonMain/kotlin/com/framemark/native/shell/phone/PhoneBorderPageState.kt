package com.framemark.native.shell.phone

import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryCardGroup
import com.framemark.native.library.LibraryCategory

/**
 * 手机端全高边框页的纯决策（无 Compose、无资源读取、无持久化；`PhoneBorderPageStateTest`
 * 直接测这一层）。
 *
 * 页面只有一条竖向滚动区域：网格。分类改成**左右翻页**（`HorizontalPager`，一页一个分类），
 * 所以「换分类」不再要求用户先在矮底板里横着找 chip、再竖着滚卡片。
 *
 * 这一层只回答四件事，全部是纯函数：
 *  1. 分类行 / pager 的页码与分类键之间的换算；
 *  2. 选中态（哪张卡是当前选中的、页脚说什么、主按钮是否可点）；
 *  3. 网格条目下标（分组标题 / 卡片在扁平条目列表里的位置），用于跳转；
 *  4. 页面的分区与「哪一段能滚」——这正是「整页只有一个滚动容器」的可测表达。
 *
 * 分类一律来自 WP-L 的 `LibraryIndex.categories`（[LibraryIndex.CATEGORY_ORDER] 的 8 项），
 * 本对象不造分类、不改顺序、不猜名称。
 */
internal object PhoneBorderPageState {

    /**
     * 网格列数。两列是阅读决定：3 列时 128 dp 的方图在手机宽度下低于可辨识阈值
     * （原来的 3 列网格就是「看不清、点不准」的来源之一）。
     */
    const val GRID_COLUMNS = 2

    /** 页脚主按钮的文案；选中态写在 [footerTitle] 里。 */
    const val APPLY_LABEL = "使用此边框"

    /** 没有选中时页脚的主按钮文案（禁用态，说清楚下一步）。 */
    const val APPLY_LABEL_EMPTY = "先点一个边框"

    /** 页面上没有分类时的说明。 */
    const val EMPTY_CATEGORY_NOTE = "边框库暂时没有可用的分类。"

    // ------------------------------------------------------------------ 分类 ⇄ 页码

    /** 一页一个分类：页数就是分类数（8）。 */
    fun pageCount(categories: List<LibraryCategory>): Int = categories.size

    /**
     * 分类键 → pager 页码。未知键（数据换了、状态过期）回第一页，绝不返回 `-1`：
     * 页码是 `rememberPagerState` 的入参，`-1` 不是合法页。
     */
    fun pageForCategory(categories: List<LibraryCategory>, categoryKey: String?): Int {
        val index = categories.indexOfFirst { it.key == categoryKey }
        return if (index >= 0) index else 0
    }

    /** pager 页码 → 分类键。越界返回 `null`（调用方保持现状，不替用户猜分类）。 */
    fun categoryKeyForPage(categories: List<LibraryCategory>, page: Int): String? =
        categories.getOrNull(page)?.key

    // ------------------------------------------------------------------ 选中态

    /** 这张卡是不是当前选中的那张。 */
    fun isSelected(card: LibraryCard, selectedCardId: String?): Boolean =
        selectedCardId != null && card.id == selectedCardId

    /** 当前选中的卡（页脚写名字用）；不在 [groups] 里时返回 `null`。 */
    fun selectedCard(groups: List<LibraryCardGroup>, selectedCardId: String?): LibraryCard? {
        if (selectedCardId == null) return null
        return groups.firstNotNullOfOrNull { group ->
            group.cards.firstOrNull { it.id == selectedCardId }
        }
    }

    /** 页脚第一行：用户永远看得见「现在会用上哪一个边框」。 */
    fun footerTitle(selected: LibraryCard?): String =
        if (selected == null) "还没有选边框" else "将使用：${selected.name}"

    /** 页脚主按钮的文案：有选中才写「使用此边框」。 */
    fun applyLabel(selected: LibraryCard?): String =
        if (selected == null) APPLY_LABEL_EMPTY else APPLY_LABEL

    /** 主按钮是否可点：没有选中就什么都不写。 */
    fun canApply(selected: LibraryCard?): Boolean = selected != null

    // ------------------------------------------------------------------ 网格条目下标

    /** 一个分组在网格里占的条目数：1 个分组标题 + 每张卡 1 个格子。 */
    fun groupItems(group: LibraryCardGroup): Int = 1 + group.count

    /**
     * 分组标题在扁平条目列表里的下标；[leadingItems] 是网格最前面不属于任何分组的条目数
     * （厂商页第一项是「系列目录」入口）。未知分组、空键返回 `-1` —— `0` 是真实下标，
     * 不能拿它表示「找不到」。
     */
    fun groupItemIndex(
        groups: List<LibraryCardGroup>,
        groupKey: String?,
        leadingItems: Int = 0,
    ): Int {
        if (groupKey == null || leadingItems < 0) return -1
        var index = leadingItems
        for (group in groups) {
            if (group.key == groupKey) return index
            index += groupItems(group)
        }
        return -1
    }

    // ------------------------------------------------------------------ 空态

    /** 网格空态：有搜索词时说的是「没匹配」，不是「这里没有」。 */
    fun emptyNote(query: String): String =
        if (query.isBlank()) "这个分类下还没有边框。" else "没有匹配「${query.trim()}」的边框。"

    // ------------------------------------------------------------------ 分区（只有一个滚动区）

    /**
     * 页面从上到下的分区，顺序就是布局顺序。固定 chrome 与滚动区的划分见
     * [SCROLLABLE_BANDS] / [fixedBands]。
     */
    val BANDS: List<PhoneBorderPageBand> = listOf(
        PhoneBorderPageBand.Header,
        PhoneBorderPageBand.Categories,
        PhoneBorderPageBand.Search,
        PhoneBorderPageBand.Grid,
        PhoneBorderPageBand.Footer,
    )

    /**
     * 唯一允许滚动的分区。整页只有网格是 `Lazy*` 容器：分类行、搜索框、标题行、页脚
     * 全部固定，所以「竖着滚的容器套着横着滚的容器」不会再次出现。
     */
    val SCROLLABLE_BANDS: List<PhoneBorderPageBand> = listOf(PhoneBorderPageBand.Grid)

    /** 固定 chrome = 除滚动区以外的每一段。 */
    fun fixedBands(): List<PhoneBorderPageBand> =
        BANDS.filter { it !in SCROLLABLE_BANDS }

    /** 主操作所在的固定分区（「使用此边框」永不进滚动区）。 */
    val PRIMARY_ACTION_BAND: PhoneBorderPageBand = PhoneBorderPageBand.Footer
}

/** 边框页的竖向分区，从上到下。 */
internal enum class PhoneBorderPageBand {
    Header,
    Categories,
    Search,
    Grid,
    Footer,
}
