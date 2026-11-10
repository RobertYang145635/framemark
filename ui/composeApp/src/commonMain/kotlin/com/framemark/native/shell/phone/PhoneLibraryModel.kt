package com.framemark.native.shell.phone

import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryCardGroup
import com.framemark.native.library.LibraryCategory
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.LibraryIndexView
import com.framemark.native.library.LibraryTraversal
import com.framemark.native.library.LibraryVendorGroup

/**
 * Pure data model of the V2 phone border sheet (report §7.5): one category row,
 * one group-jump row, then the groups in display order.
 *
 * Everything comes from WP-L's [LibraryIndex] — categories and counts, the
 * "常用" three groups, vendor brands and series, search, and the `traversal`
 * order used by the left/right swipe. Nothing here re-sorts or re-groups: the
 * swipe order is literally `traversal(view).ids`, so it cannot drift from what
 * the sheet shows.
 */

/** One chip of the group-jump row; [groupKey] is the group it scrolls to. */
data class PhoneLibraryAnchor(
    val key: String,
    val label: String,
    val count: Int,
    val groupKey: String,
)

/** One resolved view of the border sheet. */
data class PhoneLibraryView(
    /** 8 categories in WP-L's fixed order (常用 first), with real counts. */
    val categories: List<LibraryCategory>,
    /** Groups in display order; sticky titles when the body is expanded. */
    val groups: List<LibraryCardGroup>,
    /** Group-jump row; for 厂商 it aggregates by brand. */
    val anchors: List<PhoneLibraryAnchor>,
    /** Vendor groups (used by the 系列目录 sheet). */
    val vendors: List<LibraryVendorGroup>,
) {
    val isEmpty: Boolean get() = groups.isEmpty()

    /** Every card of the view, in display order (what the sheet renders). */
    val cards: List<LibraryCard> get() = groups.flatMap { it.cards }

    /** Group that carries [groupKey]; the series directory jumps with it. */
    fun group(groupKey: String?): LibraryCardGroup? =
        groups.firstOrNull { it.key == groupKey }
}

/**
 * Builds the sheet's view for a category, an optional vendor narrowing and a
 * search query. A blank query keeps the category/vendor selection; a non-blank
 * one searches all 180 cards (WP-L contract) and keeps the series grouping.
 */
fun phoneLibraryView(
    index: LibraryIndex,
    categoryKey: String,
    vendorKey: String?,
    query: String,
    inUse: List<String>,
    recent: List<String>,
): PhoneLibraryView {
    val view = LibraryIndexView(
        categoryKey = categoryKey.takeIf { it.isNotBlank() },
        vendorKey = vendorKey,
        query = query.trim(),
        inUse = inUse,
        recent = recent,
    )
    val groups = index.groups(view)
    val anchors = if (view.query.isBlank() && categoryKey == LibraryIndex.CATEGORY_VENDOR) {
        vendorAnchors(groups, index.vendors)
    } else {
        groups.map { group ->
            PhoneLibraryAnchor(group.key, phoneAnchorLabel(group.label), group.count, group.key)
        }
    }
    return PhoneLibraryView(
        categories = index.categories(inUse = inUse, recent = recent),
        groups = groups,
        anchors = anchors,
        vendors = index.vendors,
    )
}

/**
 * Chip wording of the 常用 groups. WP-L names them for the desktop rail
 * (`本项目在用` / `最近使用`); the showcase's phone row says `在用` / `最近` / `热门`
 * (`ANCHOR_LABEL` in core.js), which is what fits a 30 dp chip. Only these three
 * library labels are shortened — every other group keeps its own name.
 */
internal fun phoneAnchorLabel(label: String): String = when (label) {
    LibraryIndex.LABEL_USE -> "在用"
    LibraryIndex.LABEL_RECENT -> "最近"
    else -> label
}

/**
 * 厂商 row aggregates by brand: one chip per brand, jumping to that brand's first
 * series group. Counts are the brand's card count, never a guess.
 */
private fun vendorAnchors(
    groups: List<LibraryCardGroup>,
    vendors: List<LibraryVendorGroup>,
): List<PhoneLibraryAnchor> = vendors.mapNotNull { vendor ->
    val prefix = "vendor|${vendor.key}|"
    val first = groups.firstOrNull { it.key.startsWith(prefix) } ?: return@mapNotNull null
    PhoneLibraryAnchor(
        key = vendor.key,
        label = vendor.label,
        count = vendor.count,
        groupKey = first.key,
    )
}

/** Order used by the photo swipe and by the sheet body: both read [index.traversal]. */
fun phoneLibraryTraversal(
    index: LibraryIndex,
    categoryKey: String,
    vendorKey: String?,
    query: String,
    inUse: List<String>,
    recent: List<String>,
): LibraryTraversal = index.traversal(
    LibraryIndexView(
        categoryKey = categoryKey.takeIf { it.isNotBlank() },
        vendorKey = vendorKey,
        query = query.trim(),
        inUse = inUse,
        recent = recent,
    ),
)

/** One swipe outcome: either a new card, or an honest "already at the end" message. */
data class PhoneSwipeStep(
    val targetId: String?,
    val position: Int,
    val total: Int,
    val atEdge: Boolean,
    val message: String,
)

/**
 * Swipe one card forward ([direction] > 0) or back in [traversal]. It never wraps:
 * the showcase HUD says "已是第一个 / 已是最后一个" instead of jumping around,
 * and the order is exactly the sheet's display order.
 */
fun phoneSwipeStep(
    traversal: LibraryTraversal,
    currentId: String?,
    direction: Int,
): PhoneSwipeStep {
    val total = traversal.size
    if (total == 0) {
        return PhoneSwipeStep(null, 0, 0, atEdge = true, message = "这里没有可切换的边框")
    }
    val forward = direction >= 0
    val target = if (forward) traversal.next(currentId) else traversal.prev(currentId)
    if (target == null) {
        val position = traversal.positionOf(currentId)
        return PhoneSwipeStep(
            targetId = null,
            position = position,
            total = total,
            atEdge = true,
            message = if (forward) "已是最后一个" else "已是第一个",
        )
    }
    val position = traversal.positionOf(target)
    return PhoneSwipeStep(
        targetId = target,
        position = position,
        total = total,
        atEdge = false,
        message = "第 $position / $total 个",
    )
}

/** Selected card's 1-based position inside the traversal, for the stage chip. */
fun phoneBorderPosition(traversal: LibraryTraversal, cardId: String?): Int = traversal.positionOf(cardId)

/**
 * Flat item index of [groupKey]'s header / tile in the sheet body. [perGroup] is
 * the item count a group occupies (`phoneStripItems` / `phoneGridItems`), which is
 * why the jump chips and the body can never disagree about where a group starts.
 *
 * `0` is a real destination — it is the first group's header/tile — so "this key is
 * not in the body" answers `-1`. Returning `0` for a missing key made the first
 * group's jump chip indistinguishable from "nothing to do", and the chip was then
 * dropped on the floor (bug: 厂商 → `OPPO 48` / 常用 → `在用` did nothing).
 */
fun phoneLibraryItemIndex(
    groups: List<LibraryCardGroup>,
    groupKey: String?,
    perGroup: (LibraryCardGroup) -> Int,
): Int {
    // No key = no target: there is nothing to scroll to.
    if (groupKey == null) return -1
    var index = 0
    for (group in groups) {
        if (group.key == groupKey) return index
        index += perGroup(group)
    }
    return -1
}

/** The group a body offset points at, used by the sticky-title styling and QA. */
fun phoneLibraryGroupAt(
    groups: List<LibraryCardGroup>,
    itemIndex: Int,
    perGroup: (LibraryCardGroup) -> Int,
): LibraryCardGroup? {
    if (itemIndex <= 0) return groups.firstOrNull()
    var index = 0
    for (group in groups) {
        index += perGroup(group)
        if (itemIndex < index) return group
    }
    return groups.lastOrNull()
}
