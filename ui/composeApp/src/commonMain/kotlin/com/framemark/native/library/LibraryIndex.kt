package com.framemark.native.library

/**
 * 新边框库（桌面左栏、手机边框底板）的数据层：把一份 [LibrarySnapshot] 编成有序、可查的索引。
 *
 * 纯逻辑，不含界面，也没有 Compose 依赖。界面上的一行类别、厂商分组、系列目录、搜索结果分组、
 * “常用”三组，以及 ← → / 左右滑逐个试的顺序，全部由这里给出；D1 和 P1 只负责渲染和接线。
 *
 * 约定（都在数据里判断，不按厂商名写死）：
 *
 *  - **类别**：[CATEGORY_ORDER] 固定 8 项，“常用”是合成类别，其余 7 项与 taxonomy 的 7 个
 *    `category` 一一对应（见 [unmappedCategoryKeys]，对不上的类别会被点出来而不是吞掉）。
 *  - **厂商**：分组键沿用 [LibraryGroups.keyOf] 的品牌键（`"brand:OPPO"`），顺序沿用
 *    [LibraryGroups.vendorOrder] 的首次出现顺序；没有品牌的卡统一进 [VENDOR_OTHER]，排在最后。
 *  - **系列**：只取数据字段 `styleFamily` / `styleFamilyLabel`，不按名称猜组；没有正式系列
 *    （`familyNameProvenance != verified-in-file`）的卡进 [NO_FORMAL_FAMILY] 一个独立分组，
 *    `styleFamilyLabelSource` 原文作为该组说明保留。
 *  - **热门**：唯一来源是 taxonomy 的 `badge == "热门"`（演示稿 `core.js` 的
 *    `CARDS.filter(c => c.badge === '热门')` 也是这么取的），不另造排行。
 */
class LibraryIndex(val snapshot: LibrarySnapshot) {

    companion object {
        const val CATEGORY_COMMON = "common"
        const val CATEGORY_VENDOR = "vendor"

        /** 没有品牌的卡的厂商分组键和显示名。 */
        const val VENDOR_OTHER = "其他"

        /** 没有正式系列的卡的独立分组键（显示名用 [LibraryGroups.NO_FIRMWARE_LABEL]）。 */
        const val NO_FORMAL_FAMILY = "no-formal-family"

        /** taxonomy 里“热门”卡片的 badge 值。 */
        const val HOT_BADGE = "热门"

        const val GROUP_USE = "use"
        const val GROUP_RECENT = "recent"
        const val GROUP_HOT = "hot"

        const val LABEL_COMMON = "常用"
        const val LABEL_USE = "本项目在用"
        const val LABEL_RECENT = "最近使用"
        const val LABEL_HOT = "热门"

        /** 8 个类别，顺序固定：常用、厂商、文字、白边、卡片、印象、色彩、简约。 */
        val CATEGORY_ORDER: List<String> = listOf(
            CATEGORY_COMMON,
            CATEGORY_VENDOR,
            "text",
            "white",
            "card",
            "impression",
            "color",
            "minimal",
        )

        /** taxonomy 没有声明类别名时的兜底写法。 */
        private val FALLBACK_NAMES: Map<String, String> = mapOf(
            CATEGORY_COMMON to LABEL_COMMON,
            CATEGORY_VENDOR to "厂商",
            "text" to "文字",
            "white" to "白边",
            "card" to "卡片",
            "impression" to "印象",
            "color" to "色彩",
            "minimal" to "简约",
        )

        private val WHITESPACE = Regex("\\s+")

        fun of(snapshot: LibrarySnapshot): LibraryIndex = LibraryIndex(snapshot)

        /**
         * 全角转半角 + 小写，用于搜索。只做“同一个字的不同写法和大小写”的折叠，
         * 不改字序、不去空格、不做拼音或近义词，匹配结果仍然只看名称 / 厂商 / 系列。
         */
        internal fun foldText(text: String): String {
            val out = StringBuilder(text.length)
            for (ch in text) {
                val mapped = when {
                    ch == '\u3000' -> ' '
                    ch in '\uFF01'..'\uFF5E' -> (ch.code - 0xFEE0).toChar()
                    else -> ch
                }
                out.append(mapped)
            }
            return out.toString().lowercase()
        }
    }

    /** taxonomy 里的卡片，顺序即数据顺序。 */
    val cards: List<LibraryCard> = snapshot.cards

    private val cardsById: Map<String, LibraryCard> = cards.associateBy { it.id }

    /** 可用于校验“最近使用”里是不是 catalog 里的卡。 */
    val knownIds: Set<String> = cardsById.keys

    private val declaredNames: Map<String, String> = snapshot.categories.associate { it.key to it.name }

    /** 厂商分组，带卡数，没有品牌的卡在最后的 [VENDOR_OTHER]。 */
    val vendors: List<LibraryVendorGroup> by lazy(LazyThreadSafetyMode.NONE) {
        buildVendors(cards.filter { it.category == CATEGORY_VENDOR })
    }

    private val familiesByVendor: Map<String, List<LibraryFamilyGroup>> by lazy(LazyThreadSafetyMode.NONE) {
        vendors.associate { group -> group.key to buildFamilies(group.cards) }
    }

    fun card(id: String?): LibraryCard? = if (id == null) null else cardsById[id]

    fun nameOfCategory(categoryKey: String): String = categoryName(categoryKey)

    // ------------------------------------------------------------------ 类别

    /**
     * 8 个类别，顺序固定，每类带卡数。“常用”的卡数就是三组去重后的张数，
     * 所以要把当前的“本项目在用”和“最近使用”传进来（[LibraryIndexView] 里也有同样的两个字段）。
     */
    fun categories(
        inUse: List<String> = emptyList(),
        recent: List<String> = emptyList(),
    ): List<LibraryCategory> = CATEGORY_ORDER.map { key ->
        val count = if (key == CATEGORY_COMMON) {
            commonCards(inUse, recent).size
        } else {
            cards.count { it.category == key }
        }
        LibraryCategory(key = key, name = categoryName(key), count = count)
    }

    /**
     * taxonomy 里有、但 8 个类别对不上的 `category` 值。正常情况下返回空列表；
     * 非空说明数据里出现了新类别，界面会漏卡 —— 测试拿它当“180 张全部可达”的闸门。
     */
    fun unmappedCategoryKeys(): List<String> = cards
        .map { it.category }
        .filter { it.isNotBlank() && it !in CATEGORY_ORDER }
        .distinct()

    // ------------------------------------------------------------------ 厂商 / 系列

    /** 某个厂商下的系列目录（正式系列在前，没有正式系列的独立分组在最后）。 */
    fun families(vendorKey: String): List<LibraryFamilyGroup> =
        familiesByVendor[vendorKey] ?: emptyList()

    /**
     * 某张卡落在哪个厂商分组：厂商类别里按 `brand` 分，没有品牌的进 [VENDOR_OTHER]；
     * 其他类别不属于任何厂商分组，返回 `null`。
     */
    fun vendorKeyOf(card: LibraryCard): String? =
        if (card.category == CATEGORY_VENDOR) card.brand ?: VENDOR_OTHER else null

    /**
     * 某张卡落在哪个系列分组：正式系列取 `styleFamily` 原值，没有正式系列的取 [NO_FORMAL_FAMILY]。
     * 非厂商卡不属于厂商系列，返回 `null`（它们的“系列”是 `templateCategory`，见 [groups]）。
     */
    fun familyKeyOf(card: LibraryCard): String? {
        if (card.category != CATEGORY_VENDOR) return null
        if (!LibraryGroups.hasThirdLevel(card)) return NO_FORMAL_FAMILY
        return card.styleFamily?.takeIf { it.isNotBlank() } ?: card.styleFamilyLabel ?: NO_FORMAL_FAMILY
    }

    // ------------------------------------------------------------------ 常用三组

    /**
     * “常用”三组：本项目在用 → 最近使用 → 热门。三组之间按顺序去重，一张卡只出现在它
     * 最先出现的那一组；空组不返回。未知 id（catalog 里没有）直接丢掉。
     */
    fun common(
        inUse: List<String>,
        recent: List<String>,
    ): List<LibraryCardGroup> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<LibraryCardGroup>(3)

        fun take(ids: List<String>, limit: Int): List<LibraryCard> {
            val taken = ArrayList<LibraryCard>()
            for (id in ids) {
                if (taken.size >= limit) break
                val card = cardsById[id] ?: continue
                if (!seen.add(id)) continue
                taken.add(card)
            }
            return taken
        }

        val use = take(inUse, Int.MAX_VALUE)
        if (use.isNotEmpty()) out.add(LibraryCardGroup(GROUP_USE, LABEL_USE, null, use))
        val recentCards = take(recent, RecentFrames.MAX)
        if (recentCards.isNotEmpty()) {
            out.add(LibraryCardGroup(GROUP_RECENT, LABEL_RECENT, null, recentCards))
        }
        val hot = take(cards.filter { it.badge == HOT_BADGE }.map { it.id }, Int.MAX_VALUE)
        if (hot.isNotEmpty()) out.add(LibraryCardGroup(GROUP_HOT, LABEL_HOT, null, hot))
        return out
    }

    private fun commonCards(inUse: List<String>, recent: List<String>): List<LibraryCard> =
        common(inUse, recent).flatMap { it.cards }

    // ------------------------------------------------------------------ 列表分组

    /**
     * 当前视图的列表分组，顺序就是显示顺序。界面按这个顺序渲染，[traversal] 按同一个顺序返回
     * id 列表，所以“逐个试”的顺序一定和看到的顺序一致。
     *
     * - `categoryKey == null`：全部卡片，按类别顺序，厂商再按厂商 / 系列分组；
     * - `categoryKey == "common"`：常用三组；
     * - `categoryKey == "vendor"`：厂商下按品牌，品牌下按系列（[LibraryIndexView.vendorKey] /
     *   [LibraryIndexView.familyKey] 用来收窄）；
     * - 其他类别：按 `templateCategory` 分子类；
     * - `query` 非空：搜索永远跨全部 180 张，返回按系列分组的结果（类别和厂商筛选不参与）。
     */
    fun groups(view: LibraryIndexView): List<LibraryCardGroup> {
        if (view.query.isNotBlank()) return search(view.query)
        return when (val categoryKey = view.categoryKey) {
            null -> allGroups()
            CATEGORY_COMMON -> common(view.inUse, view.recent)
            CATEGORY_VENDOR -> vendorGroups(view)
            else -> templateGroups(categoryKey, prefixed = false)
        }
    }

    /**
     * 搜索：匹配名称、厂商（`brand`）、系列（`styleFamily` / `styleFamilyLabel`），
     * 不区分大小写和全角半角；空白分词后要求每个词都出现。结果复用 [groups] 的分组，
     * 因此一定按系列分组、顺序和列表顺序一致。
     */
    fun search(query: String): List<LibraryCardGroup> {
        if (query.isBlank()) return emptyList()
        return allGroups().mapNotNull { group ->
            val hits = group.cards.filter { matches(it, query) }
            if (hits.isEmpty()) null else group.copy(cards = hits)
        }
    }

    /** 单张卡是否命中搜索词。空词命中一切；分词后要求每个词都在名称 / 厂商 / 系列里出现。 */
    fun matches(card: LibraryCard, query: String): Boolean {
        val tokens = foldText(query).split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        val haystack = foldText(
            buildString {
                append(card.name)
                append('\n')
                append(card.brand.orEmpty())
                append('\n')
                append(card.styleFamily.orEmpty())
                append('\n')
                append(card.styleFamilyLabel.orEmpty())
            },
        )
        return tokens.all { haystack.contains(it) }
    }

    // ------------------------------------------------------------------ 逐个试

    /**
     * 逐个试（桌面 ← →、手机左右滑）用的顺序：把 [groups] 的卡片按显示顺序摊平后的 id 列表，
     * 和显示顺序逐项一致。`next` / `prev` 到两端返回 `null`，不循环。
     */
    fun traversal(view: LibraryIndexView): LibraryTraversal =
        LibraryTraversal(groups(view).flatMap { group -> group.cards.map { it.id } })

    // ------------------------------------------------------------------ 内部

    private fun categoryName(categoryKey: String): String {
        if (categoryKey == CATEGORY_COMMON) return LABEL_COMMON
        val declared = declaredNames[categoryKey]
        if (declared != null) {
            val short = declared.removeSuffix("边框").trim()
            if (short.isNotEmpty()) return short
            if (declared.isNotBlank()) return declared.trim()
        }
        return FALLBACK_NAMES[categoryKey] ?: categoryKey
    }

    private fun allGroups(): List<LibraryCardGroup> =
        CATEGORY_ORDER
            .filter { it != CATEGORY_COMMON }
            .flatMap { key ->
                if (key == CATEGORY_VENDOR) {
                    vendorGroups(LibraryIndexView(categoryKey = CATEGORY_VENDOR))
                } else {
                    templateGroups(key, prefixed = true)
                }
            }

    private fun vendorGroups(view: LibraryIndexView): List<LibraryCardGroup> {
        val selected = view.vendorKey?.let { key -> vendors.filter { it.key == key } } ?: vendors
        val out = ArrayList<LibraryCardGroup>()
        for (vendor in selected) {
            for (family in buildFamilies(vendor.cards)) {
                if (view.familyKey != null && family.key != view.familyKey) continue
                val label = if (view.vendorKey == null) "${vendor.label} · ${family.label}" else family.label
                out.add(
                    LibraryCardGroup(
                        key = "vendor|${vendor.key}|${family.key}",
                        label = label,
                        note = family.note,
                        cards = family.cards,
                    ),
                )
            }
        }
        return out
    }

    private fun templateGroups(categoryKey: String, prefixed: Boolean): List<LibraryCardGroup> {
        val ordered = LinkedHashMap<String, MutableList<LibraryCard>>()
        cards.filter { it.category == categoryKey }.forEach { card ->
            ordered.getOrPut(card.templateCategory.orEmpty()) { mutableListOf() }.add(card)
        }
        val categoryLabel = categoryName(categoryKey)
        return ordered.map { (templateCategory, group) ->
            val label = when {
                templateCategory.isBlank() -> categoryLabel
                prefixed -> "$categoryLabel · $templateCategory"
                else -> templateCategory
            }
            LibraryCardGroup(
                key = "template|$categoryKey|$templateCategory",
                label = label,
                note = null,
                cards = group,
            )
        }
    }

    private fun buildVendors(vendorCards: List<LibraryCard>): List<LibraryVendorGroup> {
        // 品牌顺序沿用 LibraryGroups（首次出现顺序），没有品牌的卡统一放到最后。
        val brandKeys = LibraryGroups.vendorOrder(vendorCards).filter { it.startsWith("brand:") }
        val out = ArrayList<LibraryVendorGroup>(brandKeys.size + 1)
        for (key in brandKeys) {
            val group = vendorCards.filter { LibraryGroups.keyOf(it) == key }
            if (group.isNotEmpty()) {
                out.add(LibraryVendorGroup(key = key, label = LibraryGroups.labelOf(key), cards = group))
            }
        }
        val unbranded = vendorCards.filter { !LibraryGroups.keyOf(it).startsWith("brand:") }
        if (unbranded.isNotEmpty()) {
            out.add(LibraryVendorGroup(key = VENDOR_OTHER, label = VENDOR_OTHER, cards = unbranded))
        }
        return out
    }

    private fun buildFamilies(vendorCards: List<LibraryCard>): List<LibraryFamilyGroup> {
        val formal = LinkedHashMap<String, MutableList<LibraryCard>>()
        val informal = ArrayList<LibraryCard>()
        for (card in vendorCards) {
            if (LibraryGroups.hasThirdLevel(card)) {
                val key = card.styleFamily?.takeIf { it.isNotBlank() }
                    ?: card.styleFamilyLabel?.takeIf { it.isNotBlank() }
                    ?: NO_FORMAL_FAMILY
                formal.getOrPut(key) { mutableListOf() }.add(card)
            } else {
                informal.add(card)
            }
        }
        val out = ArrayList<LibraryFamilyGroup>(formal.size + 1)
        for ((key, group) in formal) {
            val first = group.first()
            out.add(
                LibraryFamilyGroup(
                    key = key,
                    label = LibraryGroups.displayFamilyLabel(first) ?: key,
                    note = null,
                    styleFamily = key,
                    cards = group,
                ),
            )
        }
        if (informal.isNotEmpty()) {
            out.add(
                LibraryFamilyGroup(
                    key = NO_FORMAL_FAMILY,
                    label = LibraryGroups.NO_FIRMWARE_LABEL,
                    note = informal
                        .mapNotNull { it.styleFamilyLabelSource?.takeIf(String::isNotBlank) }
                        .distinct()
                        .joinToString("；")
                        .ifBlank { null },
                    styleFamily = null,
                    cards = informal,
                ),
            )
        }
        return out
    }
}

/** 列表里的一个分组：常用三组的一组、某个厂商下的一个系列、其他类别的一个子类、搜索结果的一段。 */
data class LibraryCardGroup(
    /** 稳定键，做吸顶标题的锚点用；搜索前后同一个系列键不变。 */
    val key: String,
    /** 显示标题。 */
    val label: String,
    /** 组级说明，只来自数据字段（如无正式系列的来源标注）。 */
    val note: String?,
    val cards: List<LibraryCard>,
) {
    val count: Int get() = cards.size
    val ids: List<String> get() = cards.map { it.id }
}

/**
 * 厂商分组。`key` 与 [LibraryGroups.keyOf] 的品牌键同构（`"brand:OPPO"`），
 * 没有品牌的卡是 [LibraryIndex.VENDOR_OTHER]。
 */
data class LibraryVendorGroup(
    val key: String,
    val label: String,
    val cards: List<LibraryCard>,
) {
    val count: Int get() = cards.size
    val ids: List<String> get() = cards.map { it.id }
}

/**
 * 系列目录的一项。`styleFamily` 保留 `styleFamily` 原值（没有正式系列时为 `null`），
 * `note` 保留 `styleFamilyLabelSource` 原文，卡片本身也带着原字段，不做任何改写。
 */
data class LibraryFamilyGroup(
    val key: String,
    val label: String,
    val note: String?,
    val styleFamily: String?,
    val cards: List<LibraryCard>,
) {
    val count: Int get() = cards.size
    val hasFormalFamily: Boolean get() = styleFamily != null
    val ids: List<String> get() = cards.map { it.id }
}

/**
 * 一个视图：类别 + 厂商 + 系列 + 搜索词，外加“常用”要用的两张 id 列表。
 * 全部有默认值，`LibraryIndexView()` 就是“全部卡片”。
 */
data class LibraryIndexView(
    /** [LibraryIndex.CATEGORY_ORDER] 之一，`null` 表示全部。 */
    val categoryKey: String? = null,
    /** 厂商分组的键（见 [LibraryVendorGroup.key]）。 */
    val vendorKey: String? = null,
    /** 系列分组的键（见 [LibraryFamilyGroup.key]）。 */
    val familyKey: String? = null,
    /** 搜索词；非空时跨全部卡片搜索，类别和厂商筛选不参与。 */
    val query: String = "",
    /** 本项目在用的卡 id，按出现顺序。 */
    val inUse: List<String> = emptyList(),
    /** 最近使用的卡 id，最近的在前。 */
    val recent: List<String> = emptyList(),
)

/**
 * 逐个试的顺序。[ids] 与 [LibraryIndex.groups] 的显示顺序逐项一致；
 * `next` / `prev` 到两端返回 `null`，不循环。
 */
data class LibraryTraversal(val ids: List<String>) {

    val size: Int get() = ids.size

    val isEmpty: Boolean get() = ids.isEmpty()

    /** `null` 表示“还没选”，此时 `next` 给第一张、`prev` 给最后一张。 */
    fun next(id: String?): String? {
        if (ids.isEmpty()) return null
        if (id == null) return ids.first()
        val index = ids.indexOf(id)
        return if (index < 0 || index >= ids.lastIndex) null else ids[index + 1]
    }

    fun prev(id: String?): String? {
        if (ids.isEmpty()) return null
        if (id == null) return ids.last()
        val index = ids.indexOf(id)
        return if (index <= 0) null else ids[index - 1]
    }

    /** 第几个（从 1 开始）；不在列表里返回 0。用于“第几个 / 共几个”。 */
    fun positionOf(id: String?): Int {
        if (id == null) return 0
        val index = ids.indexOf(id)
        return if (index < 0) 0 else index + 1
    }
}
