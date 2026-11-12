package com.framemark.native.shell.phone

import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryCardGroup
import com.framemark.native.library.LibraryCategory
import com.framemark.native.shell.PHONE_FRAME_CARD_SIZE_DP

/**
 * 手机端边框工具的纯决策（无 Compose、无资源读取、无持久化；`PhoneBorderPageStateTest`
 * 直接测这一层）。
 *
 * 和 Logo 页同一套做法：**边框页本身不是库**。工具落地时是一个矮面板
 * （[PhoneBorderPageBody.Panel]）：当前边框 + 一个主按钮 + 一句说明，全部固定，
 * 一屏放得下。库是一层盖在画布上的覆盖层（[PhoneBorderPageBody.Library]），
 * 只有用户点面板上的动作才会出现；点一张卡就是应用并关闭，返回 / 遮罩 / 系统返回
 * 都是取消（什么都不改，回到面板）。
 *
 * 这一层只回答五件事，全部是纯函数：
 *  1. 落地 / 覆盖层这两块 body 的状态与只允许的转换（库只能由动作打开）；
 *  2. 面板文案与竖向预算：缩略图多大、说明行还要不要，保证面板永远不滚动；
 *  3. 分类行 / pager 的页码与分类键之间的换算；
 *  4. 选中态（哪张卡是当前选中的）；
 *  5. 网格条目下标（分组标题 / 卡片在扁平条目列表里的位置），用于跳转。
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

    /** 已经有一个边框时，面板主按钮的文案。 */
    const val CHANGE_LABEL = "换一个边框"

    /** 面板上「当前边框」的名字：没有已应用的边框时用它，而不是空白。 */
    const val NO_CURRENT_LABEL = "未选边框"

    /** 面板主按钮的文案：选过就是「换一个边框」，没选过就是「选择一个边框」。 */
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

    /** 面板第三行（预算允许时才有）：库有多少内容，仍然来自目录，不写死。 */
    fun libraryNote(categoryCount: Int, cardCount: Int): String =
        "边框库：$categoryCount 个分类，共 $cardCount 个边框。"

    // ------------------------------------------------------------------ 面板竖向预算
    //
    // 面板不是滚动容器（owner 第 5 条），所以它的高度必须是**算出来的**而不是「大概
    // 放得下」。下面每个数字都来自 token：上下内边距各 8（`FmSpacing.sm`）、缩略图与
    // 动作之间 8（`FmSpacing.sm`）、动作 48（`PrimaryButton` 的最小高度）、说明行上方
    // 4（`FmSpacing.xs`）、一行 `labelSmall` 的 14 sp 行高。
    //
    // 「缩略图阶梯」替代滚动：窗口矮就换小一号的图，按钮、名称、说明都不会被推到
    // 屏幕外面去。

    /** 面板上下内边距（`FmSpacing.sm`，进预算）。 */
    const val PANEL_PADDING_DP = 8f

    /** 缩略图与动作之间的间距（`FmSpacing.sm`）。 */
    const val PANEL_ACTION_GAP_DP = 8f

    /** 主按钮的高度：`PrimaryButton` 的 `heightIn(min = 48.dp)`。 */
    const val PANEL_ACTION_HEIGHT_DP = 48f

    /** 说明行上方的间距（`FmSpacing.xs`）。 */
    const val PANEL_NOTE_GAP_DP = 4f

    /** 一行 `FmType.labelSmall` 的行高（14 sp），再乘字号缩放。 */
    const val PANEL_NOTE_LINE_SP = 14f

    /** 缩略图右侧名称行的行高（`FmType.titleSmall` 的 15 sp / 21 sp 行高）。 */
    const val PANEL_NAME_LINE_SP = 21f

    /** 名称与说明之间的间距（`FmSpacing.xs`）。 */
    const val PANEL_NAME_GAP_DP = 4f

    /**
     * 缩略图边长阶梯，从大到小。第一档就是库里那张卡自己的尺寸
     * （[PHONE_FRAME_CARD_SIZE_DP]）：面板上的「当前边框」和网格里的同一张卡同样大，
     * 用户一眼能对上号。往下每档只为了在更矮的窗口里仍然没有滚动条。
     */
    val PANEL_TILE_LADDER_DP: List<Float> =
        listOf(PHONE_FRAME_CARD_SIZE_DP, 112f, 96f, 84f, 72f, 60f, 48f)

    /** 面板上缩略图右边那两行文字的高度；字号放大时它可能高过缩略图，也要进预算。 */
    fun textColumnHeight(fontScale: Float = 1f): Float {
        val scale = fontScale.coerceIn(1f, 1.5f)
        return (PANEL_NAME_LINE_SP + PANEL_NOTE_LINE_SP) * scale + PANEL_NAME_GAP_DP
    }

    /**
     * 面板总高度（dp）：上下内边距 + 缩略图那一行 + 间距 + 主按钮 +
     * （说明行：间距 + 一行）+ 下内边距。缩略图那一行取「缩略图」和「两行文字」的较大者。
     */
    fun panelHeight(
        tileDp: Float,
        showsNote: Boolean = true,
        fontScale: Float = 1f,
    ): Float {
        val row = maxOf(tileDp.coerceAtLeast(0f), textColumnHeight(fontScale))
        val note = if (showsNote) PANEL_NOTE_GAP_DP + PANEL_NOTE_LINE_SP * fontScale.coerceIn(1f, 1.5f) else 0f
        return PANEL_PADDING_DP + row + PANEL_ACTION_GAP_DP + PANEL_ACTION_HEIGHT_DP + note +
            PANEL_PADDING_DP
    }

    /**
     * 给定面板可用高度（底板高度减掉把手行与标题行）时，这块面板该怎么排。
     *
     * 规则只有一条：**最大的**能放下的缩略图。都放不下时先丢掉说明行（它只是帮助
     * 文字），缩略图退到最小一档——仍然不引入滚动容器。
     */
    fun panelLayout(availableHeightDp: Float, fontScale: Float = 1f): PhoneBorderPanelLayout {
        val available = if (availableHeightDp.isFinite()) availableHeightDp.coerceAtLeast(0f) else 0f
        val roomy = PANEL_TILE_LADDER_DP.firstOrNull { tile ->
            panelHeight(tile, showsNote = true, fontScale = fontScale) <= available
        }
        if (roomy != null) {
            return PhoneBorderPanelLayout(
                tileDp = roomy,
                showsNote = true,
                heightDp = panelHeight(roomy, showsNote = true, fontScale = fontScale),
            )
        }
        val tightest = PANEL_TILE_LADDER_DP.last()
        return PhoneBorderPanelLayout(
            tileDp = tightest,
            showsNote = false,
            heightDp = panelHeight(tightest, showsNote = false, fontScale = fontScale),
        )
    }

    /** 面板放得下这两块（缩略图 + 动作）用的最小高度：最小一档缩略图、没有说明行。 */
    fun panelFloorHeight(fontScale: Float = 1f): Float =
        panelHeight(PANEL_TILE_LADDER_DP.last(), showsNote = false, fontScale = fontScale)

    /** 这块面板排成什么：缩略图边长、有没有说明行、总高度。 */
    data class PhoneBorderPanelLayout(
        val tileDp: Float,
        val showsNote: Boolean,
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
