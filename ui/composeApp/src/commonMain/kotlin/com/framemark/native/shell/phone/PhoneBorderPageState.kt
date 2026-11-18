package com.framemark.native.shell.phone

import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryCardGroup
import com.framemark.native.library.LibraryCategory
import com.framemark.native.library.LibraryIndex

/**
 * 手机端边框工具的纯决策（无 Compose、无资源读取、无持久化；`PhoneBorderPageStateTest`
 * 直接测这一层）。
 *
 * 和 Logo 页同一套做法：**边框页本身不是库**。工具落地时是一个矮面板
 * （[PhoneBorderPageBody.Panel]）：当前项（小缩略图 + 名称/来源 + 更换）+（没有
 * 边框时的）全宽主按钮，全部固定，一屏放得下。库是一层盖在画布上的覆盖层
 * （[PhoneBorderPageBody.Library]），只有用户点面板上的动作才会出现；点一张卡就是
 * 应用并关闭，返回 / 遮罩 / 系统返回都是取消（什么都不改，回到面板）。
 *
 * 这一层只回答五件事，全部是纯函数：
 *  1. 落地 / 覆盖层这两块 body 的状态与只允许的转换（库只能由动作打开）；
 *  2. 面板文案与竖向预算：缩略图多大，保证面板永远不滚动；
 *  3. 分类行 / pager 的页码与分类键之间的换算；
 *  4. 选中态（哪张卡是当前选中的）；
 *  5. 网格条目下标（分组标题 / 卡片在扁平条目列表里的位置），用于跳转；
 *  6. 系列目录里点一项之后落到哪一页、哪一个真实分组（不是搜索词）。
 *
 * 分类一律来自 WP-L 的 `LibraryIndex.categories`（`LibraryIndex.CATEGORY_ORDER` 的 8 项），
 * 本对象不造分类、不改顺序、不猜名称。
 */
internal object PhoneBorderPageState {

    /**
     * 覆盖层里网格的列数。两列是阅读决定：3 列时 128 dp 的方图在手机宽度下低于可辨识阈值
     * （原来的 3 列网格就是「看不清、点不准」的来源之一）。
     */
    const val GRID_COLUMNS = 2

    /** 覆盖层标题。 */
    const val LIBRARY_TITLE = "边框库"

    /** 覆盖层标题行右边那个按钮：回到面板（不是「返回预览」——面板只盖住底板，不盖画布）。 */
    const val LIBRARY_CLOSE_LABEL = "返回"

    /** 覆盖层没有分类时的说明。 */
    const val EMPTY_CATEGORY_NOTE = "边框库暂时没有可用的分类。"

    // ------------------------------------------------------------------ 落地 / 覆盖层

    /**
     * 边框页的两块 body。
     *
     * 面板就是边框工具自己的页；库是盖在画布上的一层，不是第二个页。所以「打开工具」
     * 永远落到 [Panel]，「关掉库」也永远回到 [Panel]——不存在「返回时落到库」这种
     * 需要第二拍才能回舞台的路径（rc14 P3-1 那一类）。
     */
    enum class PhoneBorderPageBody {
        /** 落地面板：当前边框 + 动作，不滚动。 */
        Panel,

        /** 边框库覆盖层：遮罩 + 底部面板，唯一的滚动区是 2 列网格。 */
        Library,
    }

    /**
     * 工具落地时画哪一块：**永远是面板**。这一条是 owner 要求的可测表达——
     * 边框页不能一进来就是一屏列表，库必须由用户明确要求。
     */
    fun landingBody(): PhoneBorderPageBody = PhoneBorderPageBody.Panel

    /**
     * 面板上那个主按钮被点之后：面板 → 库。
     *
     * 已经从库里调用（重复请求）时保持现状：覆盖层不叠第二层。
     */
    fun bodyAfterAction(body: PhoneBorderPageBody): PhoneBorderPageBody =
        if (body == PhoneBorderPageBody.Panel) PhoneBorderPageBody.Library else body

    /**
     * 点一张卡之后：库 → 面板。
     *
     * 库里的点选就是应用（调用方的 `pickBorder` 写设计），所以这里没有「选中但未
     * 应用」的中间态：关掉库时面板上已经是新的那一张。
     */
    fun bodyAfterPick(body: PhoneBorderPageBody): PhoneBorderPageBody = PhoneBorderPageBody.Panel

    /**
     * 取消（遮罩点击 / 标题行的返回 / 系统返回）：库 → 面板，**什么都不改**。
     * 面板的当前边框来自已生效的设计值，所以「取消后是原来那张」是结构保证，
     * 不是靠回滚。
     */
    fun bodyAfterCancel(body: PhoneBorderPageBody): PhoneBorderPageBody = PhoneBorderPageBody.Panel

    /** 库现在是不是开着（宿主据此画覆盖层、挂系统返回）。 */
    fun isLibraryOpen(body: PhoneBorderPageBody): Boolean = body == PhoneBorderPageBody.Library

    // ------------------------------------------------------------------ 面板文案

    /** 没有已应用的边框时，面板主按钮的文案。 */
    const val CHOOSE_LABEL = "选择一个边框"

    /** 面板上当前项右侧的次级动作文案（配色方案 §4.1：更换不再是全宽主按钮）。 */
    const val CHANGE_LABEL = "更换"

    /** 面板上「当前边框」的名字：没有已应用的边框时用它，而不是空白。 */
    const val NO_CURRENT_LABEL = "未选边框"

    /** 面板动作的文案：选过是「更换」，没选过是「选择一个边框」。 */
    fun actionLabel(card: LibraryCard?): String =
        if (card == null) CHOOSE_LABEL else CHANGE_LABEL

    /** 面板第一行：当前边框的名字，没有就是 [NO_CURRENT_LABEL]。 */
    fun currentName(card: LibraryCard?): String = card?.name ?: NO_CURRENT_LABEL

    /**
     * 面板第二行：没有边框时说清「选了之后会发生什么」，有边框时写数据自带的
     * 类别 / 品牌 / 系列（由调用方传 [description]，见 `phoneCardDescription`）。
     */
    fun currentDescription(card: LibraryCard?, description: String?): String =
        if (card == null) "选一个边框后，样式 / 文字 / Logo / 水印才会出现。"
        else description?.takeIf { it.isNotBlank() } ?: NO_CURRENT_LABEL

    /**
     * 库页头那一行：分类数 / 边框数。**不再占用面板**（配色方案 §4.1：库信息只在
     * 浏览边框库时出现），所以在库的固定标题行与「当前：…」同行展示。
     */
    fun librarySummary(categoryCount: Int, cardCount: Int): String =
        "$categoryCount 个分类 · $cardCount 个边框"

    // ------------------------------------------------------------------ 面板竖向预算
    //
    // 面板不是滚动容器（owner 第 5 条），所以它的高度必须是**算出来的**而不是「大概
    // 放得下」。配色方案 §4.1 把面板收成一行「当前项」：
    //   - 有边框：8（上内边距）+ max(72 缩略图, 两行文字, 44「更换」)+ 8；
    //   - 没有边框：8 + max(72, 两行文字) + 8 + 48（全宽主按钮）+ 8。
    // 名称/来源两行分别是 `titleSmall` 21 sp 与 `labelSmall` 14 sp；字号放大时两行
    // 文字可能高过缩略图，预算跟着长大。矮窗换小一档缩略图，仍然不引入滚动容器。

    /** 面板上下内边距（`FmSpacing.sm`，进预算）。 */
    const val PANEL_PADDING_DP = 8f

    /** 当前项那一行的小缩略图边长（最大一档，配色方案 §4.1「小缩略图」）。 */
    const val PANEL_TILE_DP = 72f

    /** 矮窗里允许再小一档，但不引入滚动容器。 */
    const val PANEL_TILE_MIN_DP = 56f

    /** 缩略图边长阶梯，从大到小；只用于极矮窗口的兜底。 */
    val PANEL_TILE_LADDER_DP: List<Float> = listOf(PANEL_TILE_DP, 64f, PANEL_TILE_MIN_DP)

    /** 「更换」次级按钮的最小高度（触控地板，`GhostButton` 传 44）。 */
    const val PANEL_CHANGE_ACTION_HEIGHT_DP = 44f

    /** 没有边框时的全宽主按钮高度（`PrimaryButton` 默认）。 */
    const val PANEL_PRIMARY_ACTION_HEIGHT_DP = 48f

    /** 当前项与下方主按钮之间的间距（只在没有边框时才有主按钮）。 */
    const val PANEL_ACTION_GAP_DP = 8f

    /** 缩略图右侧名称行的行高（`FmType.titleSmall` 的 15 sp / 21 sp 行高）。 */
    const val PANEL_NAME_LINE_SP = 21f

    /** 来源 / 类别行的行高（`FmType.labelSmall` 的 14 sp 行高）。 */
    const val PANEL_SOURCE_LINE_SP = 14f

    /** 名称与来源之间的间距（`FmSpacing.xs`）。 */
    const val PANEL_NAME_GAP_DP = 4f

    /** 面板上缩略图右边那两行文字的高度；字号放大时它可能高过缩略图，也要进预算。 */
    fun textColumnHeight(fontScale: Float = 1f): Float {
        val scale = fontScale.coerceIn(1f, 1.5f)
        return (PANEL_NAME_LINE_SP + PANEL_SOURCE_LINE_SP) * scale + PANEL_NAME_GAP_DP
    }

    /**
     * 面板总高度（dp）。
     *
     * 有边框时「更换」在行内，不再有第二条全宽动作；没有边框时才出现全宽主按钮。
     */
    fun panelHeight(
        tileDp: Float,
        hasCard: Boolean,
        fontScale: Float = 1f,
    ): Float {
        val row = maxOf(
            tileDp.coerceAtLeast(0f),
            textColumnHeight(fontScale),
            if (hasCard) PANEL_CHANGE_ACTION_HEIGHT_DP else 0f,
        )
        val action = if (hasCard) {
            0f
        } else {
            PANEL_ACTION_GAP_DP + PANEL_PRIMARY_ACTION_HEIGHT_DP
        }
        return PANEL_PADDING_DP + row + action + PANEL_PADDING_DP
    }

    /**
     * 给定面板可用高度（底板高度减掉把手行与标题行）时，这块面板该怎么排。
     *
     * 规则只有一条：**最大的**能放下的缩略图；都放不下时退到最小一档（56 dp），
     * 仍然不引入滚动容器。
     */
    fun panelLayout(
        availableHeightDp: Float,
        hasCard: Boolean,
        fontScale: Float = 1f,
    ): PhoneBorderPanelLayout {
        val available = if (availableHeightDp.isFinite()) availableHeightDp.coerceAtLeast(0f) else 0f
        val roomy = PANEL_TILE_LADDER_DP.firstOrNull { tile ->
            panelHeight(tile, hasCard, fontScale = fontScale) <= available
        }
        val tile = roomy ?: PANEL_TILE_MIN_DP
        return PhoneBorderPanelLayout(
            tileDp = tile,
            hasCard = hasCard,
            heightDp = panelHeight(tile, hasCard, fontScale = fontScale),
        )
    }

    /** 面板的最小高度：最小一档缩略图 + 对应动作。 */
    fun panelFloorHeight(hasCard: Boolean, fontScale: Float = 1f): Float =
        panelHeight(PANEL_TILE_MIN_DP, hasCard, fontScale = fontScale)

    /** 这块面板排成什么：缩略图边长、有没有已应用的边框、总高度。 */
    data class PhoneBorderPanelLayout(
        val tileDp: Float,
        val hasCard: Boolean,
        val heightDp: Float,
    )

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

    /** 当前选中的卡（覆盖层标「当前」用）；不在 [groups] 里时返回 `null`。 */
    fun selectedCard(groups: List<LibraryCardGroup>, selectedCardId: String?): LibraryCard? {
        if (selectedCardId == null) return null
        return groups.firstNotNullOfOrNull { group ->
            group.cards.firstOrNull { it.id == selectedCardId }
        }
    }

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

    // ------------------------------------------------------------------ 系列目录 → 分组

    /**
     * 从系列目录落地时清空搜索框：是。
     *
     * 系列跳转是「定位」不是「筛选」。词不清掉的话网格仍被搜索结果占着，分类页的
     * pager 根本不渲染，滚过去也看不见；两套状态还会互相覆盖（选完系列又冒出一个
     * 搜索词，用户想改还得先清空）。搜索框本身保持独立，照样能自己输入。
     */
    const val SERIES_CLEARS_SEARCH = true

    /**
     * 系列目录点中一项之后的落点。
     *
     * [groupKey] 是 `LibraryIndex.groups` 给出的**真实分组键**（`vendor|<品牌键>|<系列键>`），
     * 不是显示名：拿显示名拼搜索词，vivo 那种没有正式系列的组标签是「未在固件中证实」，
     * 和任何一张卡都对不上，于是永远显示「未匹配」。
     */
    data class PhoneSeriesLanding(
        val categoryKey: String,
        val groupKey: String,
        val page: Int,
    )

    /**
     * 组键 → 落点：分类键、真实分组键、pager 页码。
     *
     * 空键、认不出的键、分类行里没有的分类一律返回 `null` —— 调用方保持现状，绝不拿
     * 显示名退化成搜索词（那正是「点 vivo 显示未匹配」的来源）。
     */
    fun seriesLanding(categories: List<LibraryCategory>, groupKey: String?): PhoneSeriesLanding? {
        val key = groupKey?.trim().orEmpty()
        if (key.isEmpty()) return null
        val categoryKey = LibraryIndex.categoryKeyOfGroup(key) ?: return null
        if (categories.none { it.key == categoryKey }) return null
        return PhoneSeriesLanding(
            categoryKey = categoryKey,
            groupKey = key,
            page = pageForCategory(categories, categoryKey),
        )
    }

    /**
     * 系列跳转的滚动落点：该分组的**标题**条目，第一张卡就在下一行、同屏可见。
     *
     * 滚到标题而不是滚过标题，是因为落地后要能看见「这是哪个系列」；标题只占一行，
     * 卡片不会被裁掉，用户接着上下滑就是相邻系列（这一页不做任何过滤）。
     * 不是这一页的分组、空键返回 `-1`：目标在别的分类页时调用方保留 pending。
     */
    fun seriesScrollTarget(
        groups: List<LibraryCardGroup>,
        groupKey: String?,
        leadingItems: Int = 0,
    ): Int = groupItemIndex(groups, groupKey, leadingItems)

    // ------------------------------------------------------------------ 空态

    /** 网格空态：有搜索词时说的是「没匹配」，不是「这里没有」。 */
    fun emptyNote(query: String): String =
        if (query.isBlank()) "这个分类下还没有边框。" else "没有匹配「${query.trim()}」的边框。"

    // ------------------------------------------------------------------ 分区（唯一滚动区在库里，面板没有）

    /**
     * 覆盖层从上到下的分区，顺序就是布局顺序：标题行、分类行、搜索行，最后是唯一的
     * 滚动区。没有页脚——点一张卡就是应用，不再有第二个确认按钮。
     */
    val LIBRARY_BANDS: List<PhoneBorderPageBand> = listOf(
        PhoneBorderPageBand.Header,
        PhoneBorderPageBand.Categories,
        PhoneBorderPageBand.Search,
        PhoneBorderPageBand.Grid,
    )

    /**
     * 覆盖层里唯一允许滚动的分区。整页只有一个 `Lazy*` 容器：标题行、分类行、搜索行
     * 全部固定，所以「竖着滚的容器套着横着滚的容器」不会再次出现。
     */
    val LIBRARY_SCROLLABLE_BANDS: List<PhoneBorderPageBand> = listOf(PhoneBorderPageBand.Grid)

    /** 覆盖层里的固定 chrome = 除滚动区以外的每一段。 */
    fun libraryFixedBands(): List<PhoneBorderPageBand> =
        LIBRARY_BANDS.filter { it !in LIBRARY_SCROLLABLE_BANDS }

    /**
     * 落地面板的滚动分区：**没有**。缩略图、动作、说明都是固定的一段，靠
     * [panelLayout] 决定大小，不靠滚动条让用户去够（owner 第 5 条）。
     */
    val PANEL_SCROLLABLE_BANDS: List<PhoneBorderPageBand> = emptyList()
}

/** 边框库覆盖层的竖向分区，从上到下。 */
internal enum class PhoneBorderPageBand {
    Header,
    Categories,
    Search,
    Grid,
}
