package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryCardGroup
import com.framemark.native.library.LibraryCategory
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.LibraryIndexView
import com.framemark.native.theme.ColumnRule
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import kotlinx.coroutines.launch
import com.framemark.native.workflow.trackTextEditing

/** 覆盖层底部面板占窗口可用高度的比例：与品牌选择器同一个数字（产品决定：85–90 %）。 */
internal const val PHONE_BORDER_LIBRARY_PANEL_FRACTION = 0.88f

/**
 * 手机端边框库的**覆盖层**：一层盖在画布上的面板，不是新页面。
 *
 * 和 `inspector/PhoneLogoPicker.kt` 的做法一致：上半条带铺 `FmPalette.scrim`
 * （舞台在遮罩下仍可见），底部再落一块 88 % 高、圆角朝上的面板。所以「挑边框」
 * 的时候画布没有离开屏幕；选完关掉就精确回到进来之前的面板与工具。
 *
 * 边框工具本来的落地态是面板（[PhoneBorderPanel]）：当前边框 + 一个动作。库只在
 * 用户点那个动作时出现，点一张卡 = 应用并关闭（[onApply] → 调用方的 `pickBorder`），
 * 面板上立刻就是新的那一张；取消（遮罩 / [PhoneBorderPageState.LIBRARY_CLOSE_LABEL] /
 * 系统返回）什么都不改。
 *
 * 布局（[PhoneBorderPageState.LIBRARY_BANDS]，从上到下）：
 *  1. 固定标题行：[PhoneBorderPageState.LIBRARY_TITLE] + 当前边框 + 返回（[LibraryHead]）；
 *  2. 固定分类行：WP-L 的 8 个分类，等宽一格（[CategoryPagerRow]）；
 *  3. 固定搜索行：名称 / 品牌 / 系列（[BorderSearchRow]）；
 *  4. **唯一的滚动区**：一个分类一页的 `HorizontalPager`，每页一个 2 列
 *     `LazyVerticalGrid`（[BorderCardGrid]）——左右滑换分类，上下滚看这一分类。
 *
 * [query] 非空时，网格换成跨全部边框的搜索结果（与面板上的搜索同一条
 * `LibraryIndex.search`）；分类行始终可见，点分类即清空搜索词回到该分类页。
 *
 * 宿主：外壳（`PhoneEditorScreen`）把它画在整个编辑器之上，`borderLibraryOpen` 归
 * 外壳所有，所以打开 / 关闭都不移动底板档位、不改工具。
 */
@Composable
internal fun PhoneBorderLibraryOverlay(
    index: LibraryIndex,
    /** 已经写在设计上的边框，打开时预选并标「当前」。 */
    appliedCard: LibraryCard?,
    categoryKey: String,
    onCategoryChange: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    inUse: List<String>,
    recent: List<String>,
    onApply: (LibraryCard) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val categories = remember(index, inUse, recent) {
        index.categories(inUse = inUse, recent = recent)
    }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = PhoneBorderPageState.pageForCategory(categories, categoryKey),
    ) { PhoneBorderPageState.pageCount(categories).coerceAtLeast(1) }

    fun showCategory(key: String) {
        // 点分类 = 离开搜索、回到该分类页。
        if (query.isNotBlank()) onQueryChange("")
        onCategoryChange(key)
        scope.launch {
            pagerState.animateScrollToPage(PhoneBorderPageState.pageForCategory(categories, key))
        }
    }

    // 页码是分类的唯一事实来源：左右滑过去分类跟着走，关掉再打开还是这一页。
    LaunchedEffect(pagerState.currentPage) {
        if (query.isNotBlank()) return@LaunchedEffect
        val key = PhoneBorderPageState.categoryKeyForPage(categories, pagerState.currentPage)
        if (key != null && key != categoryKey) onCategoryChange(key)
    }

    val scrimInteraction = remember { MutableInteractionSource() }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val panelHeight = maxHeight * PHONE_BORDER_LIBRARY_PANEL_FRACTION
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部条带 = 遮罩下的舞台。点它就关掉库，和标题行的「返回」同一结果；
            // 它只占面板以上那条带，所以点面板本身绝不会误关。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(FmPalette.scrim)
                    .clickable(
                        interactionSource = scrimInteraction,
                        indication = null,
                        onClick = onDismiss,
                    )
                    .semantics { contentDescription = "关闭边框库" },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeight)
                    // Same corner fix as the editor sheet: the scrim is what sits
                    // behind this panel, so the cut-outs must show scrim, not the
                    // editor surface (which produced a square patch).
                    .background(FmPalette.scrim)
                    .clip(RoundedCornerShape(topStart = FmRadii.sheet, topEnd = FmRadii.sheet))
                    .background(FmPalette.canvas)
                    // 面板自己的空白处（标题行的留白、网格下方的内边距）也吃掉点击：
                    // 覆盖层是一层真窗户，点它不该漏到后面底板的把手行或画布手势上。
                    // 子节点先命中，所以卡片、分类、搜索的点击一个都不受影响。
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                BorderLibraryPanel(
                    index = index,
                    categories = categories,
                    categoryKey = categoryKey,
                    onCategory = ::showCategory,
                    query = query,
                    onQueryChange = onQueryChange,
                    inUse = inUse,
                    recent = recent,
                    appliedCard = appliedCard,
                    pagerState = pagerState,
                    // 点一张卡 = 应用 + 关闭：面板上马上就是新的那一张。
                    onPick = { picked ->
                        onApply(picked)
                        onDismiss()
                    },
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

/**
 * 面板内容：固定标题行 + 分类行 + 搜索行 + 唯一的滚动区。高度由
 * [PhoneBorderLibraryOverlay] 决定，内容本身不假设塞满整块屏幕。
 */
@Composable
private fun BorderLibraryPanel(
    index: LibraryIndex,
    categories: List<LibraryCategory>,
    categoryKey: String,
    onCategory: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    inUse: List<String>,
    recent: List<String>,
    appliedCard: LibraryCard?,
    pagerState: PagerState,
    onPick: (LibraryCard) -> Unit,
    onDismiss: () -> Unit,
) {
    var seriesOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        LibraryHead(
            appliedCard = appliedCard,
            categoryCount = index.categories().size,
            cardCount = index.cards.size,
            onOpenSeries = { seriesOpen = true },
            onBack = onDismiss,
        )
        CategoryPagerRow(
            categories = categories,
            categoryKey = categoryKey,
            onCategory = onCategory,
        )
        BorderSearchRow(query = query, onQueryChange = onQueryChange)
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                categories.isEmpty() -> PhoneLibraryNote(PhoneBorderPageState.EMPTY_CATEGORY_NOTE)
                query.isNotBlank() -> BorderSearchGrid(
                    index = index,
                    query = query,
                    selectedCardId = appliedCard?.id,
                    onPick = onPick,
                )
                else -> HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    val key = PhoneBorderPageState.categoryKeyForPage(categories, page)
                    if (key == null) {
                        PhoneLibraryNote(PhoneBorderPageState.EMPTY_CATEGORY_NOTE)
                    } else {
                        BorderCategoryPage(
                            index = index,
                            categoryKey = key,
                            selectedCardId = appliedCard?.id,
                            inUse = inUse,
                            recent = recent,
                            onPick = onPick,
                        )
                    }
                }
            }
        }
    }
    if (seriesOpen) {
        PhoneSeriesDirectory(
            index = index,
            onDismiss = { seriesOpen = false },
            onPick = { groupKey ->
                seriesOpen = false
                // Series pick == the same global search the field already supports;
                // the label comes from the real family table, never a guess.
                val parts = groupKey.split('|')
                val vendorKey = parts.getOrNull(1)
                val label = vendorKey
                    ?.let { index.families(it).firstOrNull { family -> family.key == parts.getOrNull(2) } }
                    ?.label
                if (!label.isNullOrBlank()) onQueryChange(label)
            },
        )
    }
}

/** 固定标题行：库名 + 库规模 / 当前边框 + 系列 / 回到面板。 */
@Composable
private fun LibraryHead(
    appliedCard: LibraryCard?,
    categoryCount: Int,
    cardCount: Int,
    onOpenSeries: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(FmPalette.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = PhoneBorderPageState.LIBRARY_TITLE,
                    style = FmType.titleMedium,
                    color = FmPalette.onSurface,
                )
                Spacer(Modifier.height(FmSpacing.xxs))
                Text(
                    text = PhoneBorderPageState.librarySummary(categoryCount, cardCount) +
                        " · 当前：" + PhoneBorderPageState.currentName(appliedCard),
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(FmSpacing.sm))
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.surfaceMuted)
                    .clickable(role = Role.Button, onClick = onOpenSeries)
                    .semantics { contentDescription = "打开系列目录" }
                    .padding(horizontal = FmSpacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "系列",
                    style = FmType.labelMedium,
                    color = FmPalette.onSurfaceSecondary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(FmSpacing.xxs))
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.surfaceMuted)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "返回边框面板" }
                    .padding(horizontal = FmSpacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = PhoneBorderPageState.LIBRARY_CLOSE_LABEL,
                    style = FmType.labelMedium,
                    color = FmPalette.onSurfaceSecondary,
                    maxLines = 1,
                )
            }
        }
        ColumnRule()
    }
}

/** 固定分类行：WP-L 的每个分类等宽一格，选中的一格是主色。 */
@Composable
private fun CategoryPagerRow(
    categories: List<LibraryCategory>,
    categoryKey: String,
    onCategory: (String) -> Unit,
) {
    if (categories.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FmPalette.surface)
            .padding(horizontal = FmSpacing.sm, vertical = FmSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(FmSpacing.xxs),
    ) {
        categories.forEach { category ->
            val active = category.key == categoryKey
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(if (active) FmPalette.primary else FmPalette.surfaceMuted)
                    .clickable(role = Role.Tab) { onCategory(category.key) }
                    .semantics {
                        selected = active
                        contentDescription = "${category.name}，${category.count} 个边框"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = category.name,
                    style = FmType.labelMedium,
                    color = if (active) FmPalette.onPrimary else FmPalette.onSurfaceSecondary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 固定搜索行：名称 / 品牌 / 系列，非空时网格换成全局搜索结果。 */
@Composable
private fun BorderSearchRow(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FmPalette.surface)
            .padding(start = FmSpacing.lg, end = FmSpacing.lg, top = FmSpacing.xs, bottom = FmSpacing.md),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(FmRadii.input))
                .background(FmPalette.surfaceMuted)
                .padding(horizontal = FmSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhoneSearchGlyph(tint = FmPalette.onSurfaceMuted)
            Spacer(Modifier.width(FmSpacing.sm))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "搜索名称、品牌或系列",
                        style = FmType.labelMedium,
                        color = FmPalette.onSurfaceFaint,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = FmType.labelMedium.copy(color = FmPalette.onSurface),
                    cursorBrush = SolidColor(FmPalette.primary),
                    modifier = Modifier.fillMaxWidth().trackTextEditing()
                        .semantics { contentDescription = "搜索边框" },
                )
            }
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(FmSpacing.xs))
                Box(
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .clickable(role = Role.Button) { onQueryChange("") }
                        .semantics { contentDescription = "清除搜索边框" }
                        .padding(horizontal = FmSpacing.xs),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("清除", style = FmType.labelSmall, color = FmPalette.primaryStrong, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 每个分类自己的网格滚动位置。
 *
 * 覆盖层一关就整棵销毁，`rememberLazyGridState()` 会重置到顶部 —— 用户「选完退出去、
 * 再点回来」看到的是列表最上面，而不是刚才停的地方。这里按分类键缓存状态，跨开关复用。
 */
private object BorderGridStates {
    private val states = mutableMapOf<String, LazyGridState>()

    fun of(categoryKey: String): LazyGridState = states.getOrPut(categoryKey) { LazyGridState() }
}

/** 一个分类页：本分类的分组 → **唯一**的 2 列网格（厂商页多一个「系列目录」入口）。 */
@Composable
private fun BorderCategoryPage(
    index: LibraryIndex,
    categoryKey: String,
    selectedCardId: String?,
    inUse: List<String>,
    recent: List<String>,
    onPick: (LibraryCard) -> Unit,
) {
    val groups = remember(index, categoryKey, inUse, recent) {
        index.groups(LibraryIndexView(categoryKey = categoryKey, inUse = inUse, recent = recent))
    }
    // 厂商 = 品牌 × 系列，分组多，所以这一页最前面放一个系列目录入口。
    val vendorsOnly = categoryKey == LibraryIndex.CATEGORY_VENDOR
    var seriesOpen by remember { mutableStateOf(false) }
    val gridState = BorderGridStates.of(categoryKey)
    val scope = rememberCoroutineScope()

    if (groups.isEmpty()) {
        PhoneLibraryNote(PhoneBorderPageState.emptyNote(""))
        return
    }
    BorderCardGrid(
        groups = groups,
        selectedCardId = selectedCardId,
        onPick = onPick,
        state = gridState,
        seriesCount = if (vendorsOnly) index.vendors.size else null,
        onOpenSeries = if (vendorsOnly) ({ seriesOpen = true }) else null,
    )
    if (seriesOpen) {
        PhoneSeriesDirectory(
            index = index,
            onDismiss = { seriesOpen = false },
            onPick = { groupKey ->
                seriesOpen = false
                // leading = 1：系列目录入口占了网格的第 0 项。
                val target = PhoneBorderPageState.groupItemIndex(
                    groups = groups,
                    groupKey = groupKey,
                    leadingItems = 1,
                )
                if (target >= 0) scope.launch { gridState.animateScrollToItem(target) }
            },
        )
    }
}

/** 搜索结果页：同一个网格，分类提示换成结果分组。 */
@Composable
private fun BorderSearchGrid(
    index: LibraryIndex,
    query: String,
    selectedCardId: String?,
    onPick: (LibraryCard) -> Unit,
) {
    val groups = remember(index, query) { index.search(query) }
    if (groups.isEmpty()) {
        PhoneLibraryNote(PhoneBorderPageState.emptyNote(query))
        return
    }
    BorderCardGrid(groups = groups, selectedCardId = selectedCardId, onPick = onPick)
}

/**
 * 覆盖层里唯一的滚动容器：2 列网格。每个分组是「一个占满整行的标题 + 每张卡一个格子」，
 * 所以标题与卡片在同一个网格里，没有第二个列表。
 */
@Composable
private fun BorderCardGrid(
    groups: List<LibraryCardGroup>,
    selectedCardId: String?,
    onPick: (LibraryCard) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    /** 厂商页最前面的「系列目录」入口；null = 没有这一项。 */
    seriesCount: Int? = null,
    onOpenSeries: (() -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(PhoneBorderPageState.GRID_COLUMNS),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = FmSpacing.lg,
            end = FmSpacing.lg,
            top = FmSpacing.xs,
            bottom = FmSpacing.xl,
        ),
        horizontalArrangement = Arrangement.spacedBy(FmSpacing.md),
        verticalArrangement = Arrangement.spacedBy(FmSpacing.md),
    ) {
        if (seriesCount != null && onOpenSeries != null) {
            item(key = "series", span = { GridItemSpan(maxLineSpan) }) {
                SeriesEntry(count = seriesCount, onOpen = onOpenSeries)
            }
        }
        groups.forEach { group ->
            item(key = "head:${group.key}", span = { GridItemSpan(maxLineSpan) }) {
                BorderGroupTitle(group = group)
            }
            items(group.cards, key = { card -> "card:${group.key}:${card.id}" }) { card ->
                PhoneBorderCard(
                    card = card,
                    selected = PhoneBorderPageState.isSelected(card, selectedCardId),
                    onClick = { onPick(card) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 网格里的分组标题：名称 + 张数 + 数据自带的说明。 */
@Composable
private fun BorderGroupTitle(group: LibraryCardGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FmSpacing.sm, bottom = FmSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.label,
            style = FmType.labelMedium,
            color = FmPalette.onSurfaceSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(FmSpacing.xs))
        Text(
            text = group.count.toString(),
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
        )
        val note = group.note?.takeIf { it.isNotBlank() }
        if (note != null) {
            Spacer(Modifier.width(FmSpacing.xs))
            Text(
                text = note,
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** 厂商页最前面的「系列目录」入口（真实系列数量来自目录，不猜）。 */
@Composable
private fun SeriesEntry(count: Int, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.surfaceMuted)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = "打开系列目录" }
            .padding(horizontal = FmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("系列目录", style = FmType.labelMedium, color = FmPalette.onSurfaceSecondary, maxLines = 1)
        Spacer(Modifier.width(FmSpacing.xs))
        Text(
            text = "$count 个品牌 ›",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
        )
    }
}
