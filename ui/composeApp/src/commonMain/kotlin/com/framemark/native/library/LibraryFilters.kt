package com.framemark.native.library

/** Active library filters. `null` means "all". */
data class LibraryFilter(
    val categoryKey: String? = null,
    val vendorKey: String? = null,
    val query: String = "",
) {
    val isActive: Boolean get() = categoryKey != null || vendorKey != null || query.isNotBlank()
}

data class VendorChoice(
    val key: String?,
    val label: String,
    val count: Int,
)

/**
 * 第三级（样式族）分组。[label] 为 `null` 时不渲染组标题（例如厂商没有固件族，直接列卡）；
 * [note] 是组级人话说明，只来自数据字段（如无族档的 `styleFamilyLabelSource`）。
 */
data class LibrarySubgroup(
    val label: String?,
    val note: String?,
    val cards: List<LibraryCard>,
)

data class LibrarySection(
    val vendorKey: String,
    val label: String,
    val note: String?,
    val subgroups: List<LibrarySubgroup>,
) {
    val count: Int get() = subgroups.sumOf { it.cards.size }
}

data class LibraryView(
    val sections: List<LibrarySection>,
    val count: Int,
    val total: Int,
)

/**
 * 分组键。`brand` 驱动厂商分组（D-010）；第三级只由数据字段决定：
 *
 *  - `familyNameProvenance == "no-family-by-design"` → [UNVERIFIED]（独立卡组，不归任何厂商族）
 *  - brand non-null                                  → 该 brand（OPPO / 小米 / vivo）
 *  - brand + vendor both null                        → [NONE]（手写模板）
 *  - brand null, vendor non-null                     → [UNLABELED]（保留 vendor 原值）
 *
 * 第三级族名一律取 `styleFamilyLabel`（固件中文族名），本文件不写死任何厂商族名或族数。
 */
object LibraryGroups {
    const val NONE = "none"
    const val UNLABELED = "unlabeled"
    const val UNVERIFIED = "unverified"

    /** 只有这条来路等级才建第三级；其余（name-exists / key-token / unverified）不冒充厂商组名。 */
    const val PROV_VERIFIED_IN_FILE = "verified-in-file"
    const val PROV_NO_FAMILY = "no-family-by-design"

    /**
     * 手写复刻卡：族名沿用同名**固件**水印（哈苏 / LUMO / 蔡司 / 徕卡 / 小米），
     * 但族名不是从固件里读出来的，而是照那套水印手写时对齐的。
     *
     * 单列一个来路等级，避免两种错误：一是把它们记成 `verified-in-file`（欺骗读者，
     * 以为是从固件里读的）；二是丢进「未在固件中证实」桶（用户点哈苏只看到一桶
     * 手写卡，还以为是没做完）。
     */
    const val PROV_HAND_AUTHORED_REPLICA = "hand-authored-replica"

    /**
     * 素材来自我们手里真实存在、但不是系统固件的包（例如 OPPO 相机包的毕业季贴纸）。
     * 族名同样是从文件里核出来的，只是来源不是固件，所以和 `verified-in-file` 分开记。
     */
    const val PROV_VERIFIED_IN_PACKAGE = "verified-in-package"

    /** 无族档的 UI 标题（判别条件是数据字段 `no-family-by-design`，不是厂商名）。 */
    const val NO_FIRMWARE_LABEL = "未在固件中证实"

    /** 族索引（chip 行）出现门槛：该厂商的固件族数 > 此值才渲染。 */
    const val CHIP_INDEX_MIN_FAMILIES = 5

    /** 厂商没有固件族时的一句人话说明（同样是数据条件触发，不按厂商写死）。 */
    const val NO_GROUP_NOTE = "厂商未在固件中给这些卡分组"

    fun keyOf(card: LibraryCard): String = when {
        // Brand wins over the "no family" marker: vivo cards are firmware-confirmed
        // but their group name is untraceable, so `no-family-by-design` must not
        // hide a real vendor behind the UNVERIFIED bucket (P9 W4: vivo 19 was only
        // reachable through 未在固件中证实).
        card.brand != null -> "brand:${card.brand}"
        card.familyNameProvenance == PROV_NO_FAMILY -> UNVERIFIED
        card.vendor == null -> NONE
        else -> UNLABELED
    }

    fun labelOf(key: String): String = when {
        key == NONE -> "无厂商 / 手写"
        key == UNLABELED -> "品牌未标注"
        key == UNVERIFIED -> NO_FIRMWARE_LABEL
        key.startsWith("brand:") -> key.removePrefix("brand:")
        else -> key
    }

    fun noteOf(key: String): String? = when (key) {
        NONE -> "源数据 vendor 为空 · 手写模板"
        UNLABELED -> "源数据 brand 为空 · 保留 vendor 原值"
        else -> null
    }

    /**
     * 第三级显示名：`styleFamilyLabel` 当前存的是带命名空间的族键（"<厂商> / <族>"），
     * 在本厂商 section 下去掉前缀，仅作展示格式化，不发明任何族名。
     */
    fun displayFamilyLabel(card: LibraryCard): String? {
        val label = card.styleFamilyLabel ?: return null
        val prefix = card.brand?.let { "$it / " } ?: return label
        return if (label.startsWith(prefix)) label.removePrefix(prefix) else label
    }

    /** 该卡是否有第三级（固件族）。 */
    fun hasThirdLevel(card: LibraryCard): Boolean =
        card.familyNameProvenance in FORMAL_FAMILY_PROVENANCE &&
            !card.styleFamilyLabel.isNullOrBlank()

    /** 能撑起第三级（系列）的来路等级；其余一律进「未在固件中证实」桶。 */
    private val FORMAL_FAMILY_PROVENANCE = setOf(
        PROV_VERIFIED_IN_FILE,
        PROV_VERIFIED_IN_PACKAGE,
        PROV_HAND_AUTHORED_REPLICA,
    )

    /** Stable order: brands by first appearance, then the null-brand / unverified buckets. */
    fun vendorOrder(cards: List<LibraryCard>): List<String> {
        val seen = LinkedHashSet<String>()
        cards.forEach { seen.add(keyOf(it)) }
        val brands = seen.filter { it.startsWith("brand:") }
        return brands + listOf(UNVERIFIED, NONE, UNLABELED).filter { seen.contains(it) }
    }
}

object LibraryQuery {
    /**
     * Search over the card's display name, English/style name and style id.
     * `systemStyle` (`oppo-gallery:personalize_brand_1`) and `id`
     * (`stock-oppo-personalize_brand_1`) are both styleId-like, so both match.
     */
    fun matches(card: LibraryCard, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val haystack = buildString {
            append(card.name.lowercase())
            append('\n')
            append((card.en ?: "").lowercase())
            append('\n')
            append(card.id.lowercase())
            append('\n')
            append((card.systemStyle ?: "").lowercase())
        }
        return q.split(Regex("\\s+")).all { haystack.contains(it) }
    }
}

object LibraryViewBuilder {
    fun vendorChoices(cards: List<LibraryCard>): List<VendorChoice> {
        val all = VendorChoice(key = null, label = "全部", count = cards.size)
        return listOf(all) + LibraryGroups.vendorOrder(cards).map { key ->
            VendorChoice(key = key, label = LibraryGroups.labelOf(key), count = cards.count { LibraryGroups.keyOf(it) == key })
        }
    }

    fun build(cards: List<LibraryCard>, filter: LibraryFilter): LibraryView {
        // A search always spans every category (old BorderLibrary behaviour).
        val searching = filter.query.isNotBlank()
        val filtered = cards.filter { card ->
            (searching || filter.categoryKey == null || card.category == filter.categoryKey) &&
                (searching || filter.vendorKey == null || LibraryGroups.keyOf(card) == filter.vendorKey) &&
                LibraryQuery.matches(card, filter.query)
        }
        val byVendor = filtered.groupBy { LibraryGroups.keyOf(it) }
        val sections = LibraryGroups.vendorOrder(filtered).mapNotNull { key ->
            val groupCards = byVendor[key] ?: return@mapNotNull null
            LibrarySection(
                vendorKey = key,
                label = LibraryGroups.labelOf(key),
                note = LibraryGroups.noteOf(key),
                subgroups = subgroupsOf(key, groupCards),
            )
        }
        return LibraryView(sections = sections, count = filtered.size, total = cards.size)
    }

    /**
     * 第三级分组（纯数据驱动）：
     *  - 无族档：按 `styleFamilyLabelSource` 分组，组说明就是这张卡的来源标注；
     *  - 有固件族的卡：按 `styleFamilyLabel` 逐字分组（顺序=首次出现）；
     *  - 一整个 section 都没有固件族：不建第三级，直接列卡，并给一句人话说明。
     */
    private fun subgroupsOf(sectionKey: String, cards: List<LibraryCard>): List<LibrarySubgroup> {
        if (sectionKey == LibraryGroups.UNVERIFIED) {
            return listOf(
                LibrarySubgroup(
                    label = null,
                    note = cards.mapNotNull { it.styleFamilyLabelSource?.takeIf(String::isNotBlank) }.distinct().joinToString("；").ifBlank { null },
                    cards = cards,
                )
            )
        }
        val withFamily = cards.filter { LibraryGroups.hasThirdLevel(it) }
        if (withFamily.isEmpty()) {
            return listOf(LibrarySubgroup(label = null, note = noGroupNote(cards), cards = cards))
        }
        val ordered = LinkedHashMap<String, MutableList<LibraryCard>>()
        withFamily.forEach { card ->
            val label = LibraryGroups.displayFamilyLabel(card).orEmpty()
            ordered.getOrPut(label) { mutableListOf() }.add(card)
        }
        val groups = ordered.map { (label, list) -> LibrarySubgroup(label = label, note = null, cards = list) }.toMutableList()
        val rest = cards.filter { !LibraryGroups.hasThirdLevel(it) }
        if (rest.isNotEmpty()) groups.add(LibrarySubgroup(label = null, note = noGroupNote(rest), cards = rest))
        return groups
    }

    /** 该 section 里存在"没有固件族"的卡时，才给说明（判别依据是来路字段，不是厂商名）。 */
    private fun noGroupNote(cards: List<LibraryCard>): String? {
        val anyNonVerified = cards.any { it.familyNameProvenance != LibraryGroups.PROV_VERIFIED_IN_FILE }
        return if (anyNonVerified) LibraryGroups.NO_GROUP_NOTE else null
    }
}
