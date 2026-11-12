package com.framemark.native.inspector

/**
 * 手机端品牌字标选择器的纯状态（无 Compose、无资源读取；`PhoneLogoPickerStateTest`
 * 直接测这一层）。
 *
 * 选择器是**两页**：第 1 页分类 + 品牌网格，第 2 页当前品牌的**全部**字标款式。
 * 两页之间左右滑动（`HorizontalPager`），所以「换品牌」不再需要在一个很矮的
 * 弹层里滚很长一列。容器是一块盖在画布上的面板（舞台在遮罩下仍可见），不是新页面；
 * 分页与选择逻辑与容器无关，桌面内联页也复用同一套。
 *
 * 取值规则只有一条，与内联版本页完全一致：写进 Brand 控件的值就是**精确 cut 键**
 * （`cut.assetKey`，例如 `bronica_white`）。品牌键、版本名、预览 webp 名都不是
 * 持久化值。
 */
internal enum class PhoneLogoPickerStage(val page: Int) {
    /** 第 1 页：分类筛选 + 品牌网格。 */
    Brands(0),

    /** 第 2 页：当前品牌全部版本的每个 cut，底部固定「添加」按钮。 */
    Styles(1),
}

/** 分类行的一项。[key] 是目录里的 `group`，也允许 [PhoneLogoPickerState.CATEGORY_ALL]。 */
internal data class PhoneLogoCategory(val key: String, val label: String, val count: Int)

/**
 * 选择器的不可变页面状态。分类只影响第 1 页的网格；[brandKey]/[cutKey] 决定第 2 页
 * 展示什么。[stage] 与 pager 的当前页保持同步（[PhoneLogoPickerState.onPage]）。
 */
internal data class PhoneLogoPickerModel(
    val stage: PhoneLogoPickerStage = PhoneLogoPickerStage.Brands,
    val categoryKey: String = PhoneLogoPickerState.CATEGORY_ALL,
    /** 第 1 页搜索框（固定 chrome）的即时过滤文本。 */
    val query: String = "",
    val brandKey: String? = null,
    val cutKey: String? = null,
)

internal object PhoneLogoPickerState {
    /** 「全部」分类：[categories] 固定把它排在第一位。 */
    const val CATEGORY_ALL = "all"

    /** 目录里真实存在的两个分组（`logos.json` 的 `group`）。 */
    const val CATEGORY_PHONE = "phone"
    const val CATEGORY_CAMERA = "camera"

    /** 两个阶段 = 两页。 */
    const val PAGE_COUNT = 2

    /** 最近使用保留的品牌数（产品决定：3 个并排的 chip 在手机宽度里读得清）。 */
    const val RECENT_CAPACITY = 3

    /**
     * 分类行：全部 / 手机 / 相机。顺序是产品决定（手机在前）。
     *
     * 计数只从 [brands] 现算，不写死：数据今天只有 camera / phone 两个分组，所以
     * 也不造第三个分类。未知分组只能通过「全部」到达。
     */
    fun categories(brands: List<LogoBrand>): List<PhoneLogoCategory> = listOf(
        PhoneLogoCategory(CATEGORY_ALL, "全部", brands.size),
        PhoneLogoCategory(CATEGORY_PHONE, "手机", brands.count { it.group == CATEGORY_PHONE }),
        PhoneLogoCategory(CATEGORY_CAMERA, "相机", brands.count { it.group == CATEGORY_CAMERA }),
    )

    /** 按分类过滤；[CATEGORY_ALL] 原样返回（保持目录顺序）。 */
    fun brands(brands: List<LogoBrand>, categoryKey: String): List<LogoBrand> =
        if (categoryKey == CATEGORY_ALL) brands else brands.filter { it.group == categoryKey }

    /**
     * 搜索：品牌英文名 `label` 与中文名 `cn`，大小写不敏感，去掉首尾空白。
     * 空查询返回输入本身。
     */
    fun searchBrands(brands: List<LogoBrand>, query: String): List<LogoBrand> {
        val q = query.trim()
        if (q.isEmpty()) return brands
        return brands.filter { brand ->
            brand.label.contains(q, ignoreCase = true) || brand.cn.contains(q, ignoreCase = true)
        }
    }

    /** 第 1 页列表 = 分类过滤 + 搜索过滤（顺序保持目录顺序）。 */
    fun filteredBrands(brands: List<LogoBrand>, categoryKey: String, query: String): List<LogoBrand> =
        searchBrands(brands(brands, categoryKey), query)

    /**
     * 最近使用 MRU：把 [brandKey] 提到最前、去重、只留 [capacity] 个。
     * 纯函数，[PhoneLogoRecents] 只负责把它挂在进程级快照状态里。
     */
    fun recordRecent(
        recentKeys: List<String>,
        brandKey: String,
        capacity: Int = RECENT_CAPACITY,
    ): List<String> {
        if (brandKey.isBlank() || capacity <= 0) return recentKeys
        return (listOf(brandKey) + recentKeys.filter { it != brandKey }).take(capacity)
    }

    /** MRU 键 → 品牌条目（未知键直接丢弃，不显示空壳）。 */
    fun recentBrands(brands: List<LogoBrand>, recentKeys: List<String>): List<LogoBrand> = recentKeys
        .mapNotNull { key -> brands.firstOrNull { it.key == key } }

    /**
     * 第 2 页的行：一个品牌**每个版本里的每个 cut** 一行，目录顺序。
     * 这里直接复用 [logoCutRows]，所以黑色/白色两枚 cut 都会出现，不会折叠成第一枚。
     */
    fun styleRows(brand: LogoBrand): List<LogoCutRow> = logoCutRows(brand)

    /** 款式标签：目录 `cuts[].ink` 的三个真实取值 + 未知兜底。 */
    fun toneLabel(cut: LogoCut): String = when (cut.ink) {
        "dark" -> "黑标"
        "light" -> "白标"
        "color" -> "彩标"
        else -> "字标"
    }

    /**
     * 打开某个品牌时预选的 cut：已在用的那枚优先（用户回到这里时看到的就是当前
     * 状态）；否则用品牌的代表字标（canonical 版本的第一枚），也就是内联版本页
     * 点品牌后实际会写入的那一枚。没有版本时返回 null。
     */
    fun defaultCutKey(brand: LogoBrand, appliedAssetKey: String): String? {
        val rows = styleRows(brand)
        if (rows.isEmpty()) return null
        rows.firstOrNull { it.cut.assetKey == appliedAssetKey }?.let { return it.cut.assetKey }
        return brand.stemFor(brand.canonicalVersion) ?: rows.first().cut.assetKey
    }

    /**
     * 点「添加」时真正写进设计的值：所选 cut 的 `assetKey`。所选值不在该品牌目录里
     * 时返回 null（界面因此禁用按钮），绝不回退到版本名、品牌键或第一枚 cut。
     */
    fun selectionValue(model: PhoneLogoPickerModel, brand: LogoBrand): String? {
        val key = model.cutKey ?: return null
        return styleRows(brand).firstOrNull { it.cut.assetKey == key }?.cut?.assetKey
    }

    fun pageForStage(stage: PhoneLogoPickerStage): Int = stage.page

    fun stageForPage(page: Int): PhoneLogoPickerStage =
        PhoneLogoPickerStage.entries.firstOrNull { it.page == page } ?: PhoneLogoPickerStage.Brands

    fun initial(): PhoneLogoPickerModel = PhoneLogoPickerModel()

    /** 切换分类不移动 pager：分类行只出现在第 1 页。 */
    fun pickCategory(model: PhoneLogoPickerModel, categoryKey: String): PhoneLogoPickerModel =
        model.copy(categoryKey = categoryKey)

    /** 搜索框逐字过滤，不移动 pager。 */
    fun pickQuery(model: PhoneLogoPickerModel, query: String): PhoneLogoPickerModel =
        model.copy(query = query)

    /**
     * 点品牌 = 去第 2 页，并预选这枚 cut。再点同一个品牌不会丢掉刚挑的款式
     * （滑回品牌页再滑回来时手感一致），换品牌才按 [defaultCutKey] 重新预选。
     */
    fun pickBrand(
        model: PhoneLogoPickerModel,
        brand: LogoBrand,
        appliedAssetKey: String,
    ): PhoneLogoPickerModel {
        val keep = model.brandKey == brand.key &&
            model.cutKey != null &&
            styleRows(brand).any { it.cut.assetKey == model.cutKey }
        return model.copy(
            stage = PhoneLogoPickerStage.Styles,
            brandKey = brand.key,
            cutKey = if (keep) model.cutKey else defaultCutKey(brand, appliedAssetKey),
        )
    }

    fun pickCut(model: PhoneLogoPickerModel, cutKey: String): PhoneLogoPickerModel =
        if (model.stage == PhoneLogoPickerStage.Styles) model.copy(cutKey = cutKey) else model

    /** 返回第 1 页；品牌与 cut 选择保留，再划回来还是同一个品牌。 */
    fun backToBrands(model: PhoneLogoPickerModel): PhoneLogoPickerModel =
        model.copy(stage = PhoneLogoPickerStage.Brands)

    /** pager 落页回调：阶段跟着页码走（左右滑动就是前后两页）。 */
    fun onPage(model: PhoneLogoPickerModel, page: Int): PhoneLogoPickerModel =
        model.copy(stage = stageForPage(page))

    /**
     * 什么时候允许用户左右拖动：打开过品牌之后。没打开过品牌时第 2 页没有内容，
     * 只能点品牌前进；打开过之后向左滑回品牌、向右滑回款式都成立。
     */
    fun canDrag(model: PhoneLogoPickerModel): Boolean =
        model.brandKey != null || model.stage == PhoneLogoPickerStage.Styles
}
