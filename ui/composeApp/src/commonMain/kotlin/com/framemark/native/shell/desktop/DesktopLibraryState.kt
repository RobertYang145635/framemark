package com.framemark.native.shell.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryCardGroup
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.LibraryIndexView
import kotlin.math.floor

/** 左栏两个页签：边框 180 / 预设 N（UI 报告 §6）。 */
enum class DesktopLibraryTab(val label: String) {
    Borders("边框"),
    Presets("预设"),
}

/**
 * 左栏筛选状态。默认打开“常用”（报告 §6：最常见的操作是复用刚用过的边框）。
 *
 * 三个筛选维度按 V2 的顺序收窄：类别 → 厂商 → 系列；搜索一旦非空就跨全部 180 张，
 * 与 [LibraryIndex] 的约定一致。类别页签与演示稿一致：再点当前页签只回到顶部，不切换成“全部”。
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

// ---------------------------------------------------------------- grid geometry
// styles.css `:root` / `.grid`: `repeat(auto-fill, minmax(118px, 1fr))`, `gap: 12px 10px`.

internal const val DESKTOP_CARD_MIN_WIDTH_DP = 118f
internal const val DESKTOP_CARD_ROW_GAP_DP = 10f

/** `auto-fill` 在给定宽度下能放几列；宽度未知时退回一列。 */
internal fun desktopLibraryColumns(availableWidthDp: Float): Int {
    if (availableWidthDp <= 0f) return 1
    val pitch = DESKTOP_CARD_MIN_WIDTH_DP + DESKTOP_CARD_ROW_GAP_DP
    return floor((availableWidthDp + DESKTOP_CARD_ROW_GAP_DP) / pitch).toInt().coerceAtLeast(1)
}

/** 列表里一行卡片；列数由左栏宽度决定（不再固定两列）。 */
internal fun cardRows(cards: List<LibraryCard>, columns: Int): List<List<LibraryCard>> =
    if (cards.isEmpty()) emptyList() else cards.chunked(columns.coerceAtLeast(1))

/**
 * 每个分组标题在 LazyColumn 里的 item 下标：标题 1 项 + 卡片行数。
 * 锚点行点一下跳到这里（报告 §6 的“锚点”）。
 */
internal fun headerAnchors(groups: List<LibraryCardGroup>, columns: Int): Map<String, Int> {
    var index = 0
    val out = LinkedHashMap<String, Int>(groups.size)
    groups.forEach { group ->
        out[group.key] = index
        index += 1 + cardRows(group.cards, columns).size
    }
    return out
}

/** 当前滚动位置落在哪个分组，用于“滚动时自动高亮”。 */
internal fun groupAtItem(
    groups: List<LibraryCardGroup>,
    columns: Int,
    itemIndex: Int,
): LibraryCardGroup? {
    var index = 0
    groups.forEach { group ->
        val span = 1 + cardRows(group.cards, columns).size
        if (itemIndex < index + span) return group
        index += span
    }
    return groups.lastOrNull()
}

// ---------------------------------------------------------------- anchor row (`.anchors`)
/**
 * 子 chips（演示稿 `.anchors`）：常用三组是 `在用 / 最近 / 热门`，其他类别是子类 / 系列名。
 * 同一锚点跨多个分组（厂商的多个系列）时合并计数，跳转指向第一次出现的那一组。
 */
internal data class DesktopLibraryAnchor(
    val id: String,
    val label: String,
    val count: Int,
    /** 跳转目标：该锚点下第一个分组的 [LibraryCardGroup.key]。 */
    val target: String,
) {
    /** 没有数据的锚点仍然占位（评审要求：不要静默少一个 chip），但不可点。 */
    val enabled: Boolean get() = count > 0
}

/** 一个分组落在哪个锚点：常用三组各占一个，厂商按品牌合并，其余一组一个。 */
internal fun desktopAnchorId(group: LibraryCardGroup): String = when {
    group.key == LibraryIndex.GROUP_USE ||
        group.key == LibraryIndex.GROUP_RECENT ||
        group.key == LibraryIndex.GROUP_HOT -> group.key
    group.key.startsWith("vendor|") -> group.key.substringBeforeLast('|')
    else -> group.key
}

/** 锚点文字；常用三组用演示稿的短词（`本项目在用` → `在用`）。 */
internal fun desktopAnchorLabel(group: LibraryCardGroup): String = when (group.key) {
    LibraryIndex.GROUP_USE -> "在用"
    LibraryIndex.GROUP_RECENT -> "最近"
    LibraryIndex.GROUP_HOT -> "热门"
    else -> {
        val raw = if (group.key.startsWith("vendor|")) {
            group.label.substringBefore(" · ")
        } else {
            group.label.substringAfterLast(" · ")
        }
        raw.trim().ifBlank { group.label }
    }
}

internal fun desktopLibraryAnchors(
    groups: List<LibraryCardGroup>,
    /** 常用页签固定保留 `在用 / 最近 / 热门` 三个槽位（无数据时计数 0、禁用态）。 */
    keepCommonSlots: Boolean = false,
): List<DesktopLibraryAnchor> {
    val out = LinkedHashMap<String, DesktopLibraryAnchor>()
    groups.forEach { group ->
        val id = desktopAnchorId(group)
        val existing = out[id]
        out[id] = if (existing == null) {
            DesktopLibraryAnchor(id, desktopAnchorLabel(group), group.count, group.key)
        } else {
            existing.copy(count = existing.count + group.count)
        }
    }
    val merged = out.values.toList()
    if (!keepCommonSlots) return merged
    val slots = listOf(
        LibraryIndex.GROUP_USE to "在用",
        LibraryIndex.GROUP_RECENT to "最近",
        LibraryIndex.GROUP_HOT to "热门",
    )
    val slotIds = slots.map { it.first }.toSet()
    val known = merged.associateBy { it.id }
    val ordered = slots.map { (id, label) ->
        known[id] ?: DesktopLibraryAnchor(id = id, label = label, count = 0, target = id)
    }
    return ordered + merged.filter { it.id !in slotIds }
}

/** 从当前分组反查高亮锚点（厂商的任意系列都高亮同一个品牌锚点）。 */
internal fun desktopActiveAnchorId(groups: List<LibraryCardGroup>, currentGroupKey: String?): String? {
    if (currentGroupKey == null) return null
    val group = groups.firstOrNull { it.key == currentGroupKey } ?: return null
    return desktopAnchorId(group)
}

// ---------------------------------------------------------------- card text
/** 卡片名就是数据里的 `name`（演示稿 `cardEl` 不裁前缀）。 */
internal fun desktopCardName(card: LibraryCard): String = card.name.trim()

/**
 * 副行（styles.css `.card .ds`）：演示稿 `cardDesc` 的等价写法，只读数据字段。
 * 非厂商卡 `类别 · 子类`；厂商卡 `品牌 · 系列`（系列取 `styleFamilyLabel` 的短名）。
 */
internal fun desktopCardSubtitle(card: LibraryCard): String {
    if (card.category == LibraryIndex.CATEGORY_VENDOR) {
        val brand = card.brand?.takeIf { it.isNotBlank() } ?: LibraryIndex.VENDOR_OTHER
        val family = card.styleFamilyLabel.orEmpty()
            .substringAfterLast('/')
            .trim()
        return if (family.isNotBlank() && family != brand) "$brand · $family" else brand
    }
    val category = card.categoryName.removeSuffix("边框").trim().ifBlank { card.category }
    val template = card.templateCategory.orEmpty().trim()
    return if (template.isBlank()) category else "$category · $template"
}

/** 徽标文字：只取数据字段，`近似` 不显示（演示稿 `showB`）。 */
internal fun desktopCardBadge(card: LibraryCard): String? =
    card.badge?.takeIf { it.isNotBlank() && it != "近似" }

/**
 * “本项目在用”一组的副行。评审口径：副行写**照片数**，不写文件名
 * （演示稿截图里这一行是「2 张照片在用」；`core.js` 的单张分支写 `文件stem 在用`，
 * 这里按评审要求统一成张数口径，见报告“评审反馈”）。
 */
internal fun desktopInUseCaption(photoCount: Int): String =
    if (photoCount > 0) "$photoCount 张照片在用" else "已套用"
