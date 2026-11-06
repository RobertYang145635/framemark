package com.framemark.native.shell.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.LibraryIndexView

/** 左栏两个页签：边框 180 / 预设 N（UI 报告 §6）。 */
enum class DesktopLibraryTab(val label: String) {
    Borders("边框"),
    Presets("预设"),
}

/**
 * 左栏筛选状态。默认打开“常用”（报告 §6：最常见的操作是复用刚用过的边框）。
 *
 * 三个筛选维度按 V2 的顺序收窄：类别 → 厂商 → 系列；搜索一旦非空就跨全部 180 张，
 * 与 [LibraryIndex] 的约定一致。
 */
class DesktopLibraryFilters {
    var tab by mutableStateOf(DesktopLibraryTab.Borders)
    var query by mutableStateOf("")
    var categoryKey by mutableStateOf<String?>(LibraryIndex.CATEGORY_COMMON)
    var vendorKey by mutableStateOf<String?>(null)
    var familyKey by mutableStateOf<String?>(null)

    val isSearching: Boolean get() = query.isNotBlank()

    fun selectCategory(key: String?) {
        categoryKey = key
        vendorKey = null
        familyKey = null
    }

    fun selectVendor(key: String?) {
        vendorKey = key
        familyKey = null
    }

    fun jumpToFamily(vendor: String?, family: String?) {
        query = ""
        categoryKey = LibraryIndex.CATEGORY_VENDOR
        vendorKey = vendor
        familyKey = family
    }

    fun clear() {
        query = ""
        categoryKey = LibraryIndex.CATEGORY_COMMON
        vendorKey = null
        familyKey = null
    }

    fun view(inUse: List<String>, recent: List<String>): LibraryIndexView = LibraryIndexView(
        categoryKey = if (query.isBlank()) categoryKey else null,
        vendorKey = if (query.isBlank()) vendorKey else null,
        familyKey = if (query.isBlank()) familyKey else null,
        query = query,
        inUse = inUse,
        recent = recent,
    )
}

/** 列表里一行两卡；用于计算吸顶标题的滚动锚点。 */
internal fun cardRows(cards: List<com.framemark.native.library.LibraryCard>): List<List<com.framemark.native.library.LibraryCard>> =
    if (cards.isEmpty()) emptyList() else cards.chunked(2)

/**
 * 每个分组标题在 LazyColumn 里的 item 下标：标题 1 项 + 卡片行数。
 * 分组行点一下跳到这里（报告 §6 的“锚点”）。
 */
internal fun headerAnchors(groups: List<com.framemark.native.library.LibraryCardGroup>): Map<String, Int> {
    var index = 0
    val out = LinkedHashMap<String, Int>(groups.size)
    groups.forEach { group ->
        out[group.key] = index
        index += 1 + cardRows(group.cards).size
    }
    return out
}

/** 当前滚动位置落在哪个分组，用于“滚动时自动高亮”。 */
internal fun groupAtItem(
    groups: List<com.framemark.native.library.LibraryCardGroup>,
    itemIndex: Int,
): com.framemark.native.library.LibraryCardGroup? {
    var index = 0
    groups.forEach { group ->
        val span = 1 + cardRows(group.cards).size
        if (itemIndex < index + span) return group
        index += span
    }
    return groups.lastOrNull()
}
