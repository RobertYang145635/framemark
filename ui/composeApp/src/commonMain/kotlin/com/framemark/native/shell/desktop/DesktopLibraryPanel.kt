package com.framemark.native.shell.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.library.LibraryCardGroup
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.PreviewDiagnostics
import com.framemark.native.product.Preset
import com.framemark.native.theme.FmElevation
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.theme.fmElevated
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * V2 左栏：边框 / 预设两个页签、固定三行筛选（搜索 · 类别 · 分组）、系列吸顶标题、
 * 系列目录、0.4 秒悬停预览、52 px 收起。
 *
 * 数据全部来自 WP-L 的 [LibraryIndex]：分组顺序、系列目录、搜索、逐个试顺序都由它给出，
 * 界面只负责渲染和锚点跳转。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DesktopLibraryPanel(
    index: LibraryIndex,
    filters: DesktopLibraryFilters,
    inUseIds: List<String>,
    recentIds: List<String>,
    selectedCardId: String?,
    presets: List<Preset>,
    onOpenCard: (com.framemark.native.library.LibraryCard) -> Unit,
    onHoverCard: (com.framemark.native.library.LibraryCard?) -> Unit,
    onApplyPreset: (String) -> Unit,
    onOpenPresetSheet: () -> Unit,
    onCollapse: () -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit = {},
) {
    val categories = remember(index, inUseIds, recentIds) { index.categories(inUseIds, recentIds) }
    val view = remember(index, filters.query, filters.categoryKey, filters.vendorKey, filters.familyKey, inUseIds, recentIds) {
        filters.view(inUseIds, recentIds)
    }
    val groups = remember(index, view) { index.groups(view) }
    val anchors = remember(groups) { headerAnchors(groups) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var hoveredId by remember { mutableStateOf<String?>(null) }
    var hoverNotice by remember { mutableStateOf<String?>(null) }
    var seriesMenuOpen by remember { mutableStateOf(false) }
    var pendingAnchor by remember { mutableStateOf<String?>(null) }

    val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val currentGroup = remember(groups, firstVisible) { groupAtItem(groups, firstVisible) }

    // 0.4 s gate (A-09): a fast sweep cancels the delay, and a card without a
    // preview gets an explicit “暂无预览” notice instead of a silent nothing.
    LaunchedEffect(hoveredId) {
        val id = hoveredId
        if (id == null) {
            onHoverCard(null)
            return@LaunchedEffect
        }
        delay(DesktopTrial.HOVER_PREVIEW_DELAY_MS)
        val card = index.card(id) ?: return@LaunchedEffect
        if (card.previewFile.isBlank() || PreviewDiagnostics.failures.containsKey(id)) {
            hoverNotice = "「${card.name}」暂无预览图"
            onHoverCard(null)
        } else {
            hoverNotice = null
            onHoverCard(card)
        }
    }
    LaunchedEffect(hoverNotice) {
        if (hoverNotice == null) return@LaunchedEffect
        delay(4000)
        hoverNotice = null
    }
    DisposableEffect(Unit) { onDispose { onHoverCard(null) } }

    LaunchedEffect(filters.query, filters.categoryKey, filters.vendorKey, filters.familyKey) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(anchors, pendingAnchor) {
        val key = pendingAnchor ?: return@LaunchedEffect
        val target = anchors[key] ?: return@LaunchedEffect
        listState.animateScrollToItem(target)
        pendingAnchor = null
    }

    fun jumpTo(key: String) {
        pendingAnchor = key
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LibraryPanelHeader(
            borderCount = index.cards.size,
            presetCount = presets.size,
            selectedTab = filters.tab,
            onSelectTab = { filters.tab = it },
            onCollapse = onCollapse,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
        if (filters.tab == DesktopLibraryTab.Borders) {
            LibraryFilterZone(
                index = index,
                filters = filters,
                categories = categories,
                groups = groups,
                currentGroupKey = currentGroup?.key,
                onJump = ::jumpTo,
                onOpenSeries = { seriesMenuOpen = true },
                onSearchFocusChanged = onSearchFocusChanged,
            )
            SeriesDirectoryMenu(
                index = index,
                expanded = seriesMenuOpen,
                vendorKey = filters.vendorKey,
                onDismiss = { seriesMenuOpen = false },
                onPick = { vendor, family ->
                    seriesMenuOpen = false
                    filters.jumpToFamily(vendor, family)
                    pendingAnchor = if (vendor != null && family != null) "vendor|$vendor|$family" else null
                },
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LibraryGroupList(
                    index = index,
                    groups = groups,
                    filters = filters,
                    selectedCardId = selectedCardId,
                    inUse = inUseIds.toSet(),
                    listState = listState,
                    onOpenCard = onOpenCard,
                    onHoveredChange = { hoveredId = it },
                )
                if (hoverNotice != null) {
                    Text(
                        text = hoverNotice.orEmpty(),
                        style = FmType.labelSmall,
                        color = FmPalette.onSurface,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(FmRadii.chip))
                            .background(FmPalette.surfaceMuted)
                            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.chip))
                            .padding(horizontal = FmSpacing.md, vertical = FmSpacing.xs),
                    )
                }
            }
        } else {
            PresetList(
                presets = presets,
                cardName = { id -> index.card(id)?.name },
                onApplyPreset = onApplyPreset,
                onOpenPresetSheet = onOpenPresetSheet,
            )
        }
    }
}
/** 页签行：边框 180 / 预设 4；右端是收起为 52 px 窄条的按钮。 */
@Composable
private fun LibraryPanelHeader(
    borderCount: Int,
    presetCount: Int,
    selectedTab: DesktopLibraryTab,
    onSelectTab: (DesktopLibraryTab) -> Unit,
    onCollapse: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DesktopLibraryTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            val count = if (tab == DesktopLibraryTab.Borders) borderCount else presetCount
            Column(
                modifier = Modifier
                    .clickable { onSelectTab(tab) }
                    .padding(end = 14.dp)
                    .semantics {
                        contentDescription = "${tab.label}页签，$count 个"
                        role = Role.Tab
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "${tab.label} $count",
                    style = FmType.labelMedium,
                    color = if (selected) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 10.dp, bottom = 7.dp),
                )
                Box(
                    Modifier
                        .width(if (selected) 22.dp else 0.dp)
                        .height(2.dp)
                        .background(if (selected) FmPalette.primary else androidx.compose.ui.graphics.Color.Transparent),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .clickable(onClick = onCollapse)
                .semantics { contentDescription = "折叠边框库为 52 px 窄条" },
            contentAlignment = Alignment.Center,
        ) {
            Text("‹", style = FmType.titleMedium, color = FmPalette.onSurfaceMuted)
        }
    }
}

/**
 * 固定三行筛选区（约 124 dp，切换类别时高度不变）：
 * 搜索 / 一行类别 / 一行分组。分组行右侧的“系列”打开系列目录。
 */
@Composable
private fun LibraryFilterZone(
    index: LibraryIndex,
    filters: DesktopLibraryFilters,
    categories: List<com.framemark.native.library.LibraryCategory>,
    groups: List<LibraryCardGroup>,
    currentGroupKey: String?,
    onJump: (String) -> Unit,
    onOpenSeries: () -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(124.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        DesktopSearchField(
            value = filters.query,
            onValueChange = { filters.query = it },
            onFocusChanged = onSearchFocusChanged,
            modifier = Modifier.fillMaxWidth().height(32.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(30.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            categories.forEach { category ->
                DesktopFilterChip(
                    label = category.name,
                    count = category.count,
                    selected = !filters.isSearching && filters.categoryKey == category.key,
                    onClick = {
                        filters.selectCategory(
                            if (filters.categoryKey == category.key) null else category.key,
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (filters.isSearching) {
                Text(
                    text = "搜索：${filters.query}",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else if (filters.categoryKey == LibraryIndex.CATEGORY_VENDOR) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DesktopFilterChip(
                        label = "全部",
                        count = index.vendors.sumOf { it.count },
                        selected = filters.vendorKey == null,
                        onClick = { filters.selectVendor(null) },
                    )
                    index.vendors.forEach { vendor ->
                        DesktopFilterChip(
                            label = vendor.label,
                            count = vendor.count,
                            selected = filters.vendorKey == vendor.key,
                            onClick = {
                                filters.selectVendor(if (filters.vendorKey == vendor.key) null else vendor.key)
                            },
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .border(1.dp, FmPalette.outlineStrong, RoundedCornerShape(FmRadii.chip))
                        .clickable(onClick = onOpenSeries)
                        .padding(horizontal = FmSpacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("系列", style = FmType.labelSmall, color = FmPalette.primaryStrong)
                }
            } else {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val firstKey = groups.firstOrNull()?.key
                    DesktopFilterChip(
                        label = "全部",
                        count = groups.sumOf { it.count },
                        selected = currentGroupKey == null,
                        onClick = { if (firstKey != null) onJump(firstKey) },
                    )
                    groups.forEach { group ->
                        DesktopFilterChip(
                            label = group.label.substringAfter('·').trim().ifBlank { group.label },
                            count = group.count,
                            selected = currentGroupKey == group.key,
                            onClick = { onJump(group.key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopFilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(FmRadii.chip)
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .background(if (selected) FmPalette.primarySoft else FmPalette.surface)
            .border(1.dp, if (selected) FmPalette.primary else FmPalette.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = FmType.labelSmall,
            color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = count.toString(),
            style = FmType.labelSmall.copy(fontSize = 10.sp),
            color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceFaint,
            maxLines = 1,
        )
    }
}

@Composable
private fun DesktopSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(FmRadii.input)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = FmType.labelMedium.copy(color = FmPalette.onSurface),
        cursorBrush = SolidColor(FmPalette.primary),
        modifier = modifier
            .clip(shape)
            .background(FmPalette.surfaceMuted)
            .border(1.dp, FmPalette.outline, shape)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .padding(horizontal = 8.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = "搜索名称、厂商、系列",
                        style = FmType.labelSmall,
                        color = FmPalette.onSurfaceFaint,
                        maxLines = 1,
                    )
                }
                inner()
            }
        },
    )
}
/** 系列目录：选厂商后列出该厂商全部系列；未选厂商时列出“厂商 · 系列”。 */
@Composable
private fun SeriesDirectoryMenu(
    index: LibraryIndex,
    expanded: Boolean,
    vendorKey: String?,
    onDismiss: () -> Unit,
    onPick: (String?, String?) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = 420.dp),
    ) {
        val rows = remember(index, vendorKey) {
            if (vendorKey != null) {
                index.families(vendorKey).map { vendorKey to it.key to it.label }
            } else {
                index.vendors.flatMap { vendor ->
                    index.families(vendor.key).map { family ->
                        (vendor.key to family.key) to "${vendor.label} · ${family.label}"
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            DropdownMenuItem(
                text = { Text("该类别没有系列目录", style = FmType.bodyMedium) },
                onClick = {},
                enabled = false,
            )
        }
        rows.forEach { (pair, label) ->
            DropdownMenuItem(
                text = { Text(label, style = FmType.bodyMedium, maxLines = 1) },
                onClick = { onPick(pair.first, pair.second) },
            )
        }
    }
}

/**
 * 卡片列表：每个分组一个吸顶标题 + 两列卡片。`group.key` 同时是锚点（点分组跳到这里）
 * 和滚动高亮的依据。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGroupList(
    index: LibraryIndex,
    groups: List<LibraryCardGroup>,
    filters: DesktopLibraryFilters,
    selectedCardId: String?,
    inUse: Set<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpenCard: (com.framemark.native.library.LibraryCard) -> Unit,
    onHoveredChange: (String?) -> Unit,
) {
    if (groups.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("没有匹配的边框", style = FmType.titleSmall, color = FmPalette.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                "换个关键词，或点“全部”回到常用。",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
            )
        }
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        groups.forEach { group ->
            stickyHeader(key = "head:${group.key}") {
                LibraryGroupHeader(group = group)
            }
            cardRows(group.cards).forEachIndexed { rowIndex, row ->
                item(key = "row:${group.key}:$rowIndex") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { card ->
                            DesktopLibraryCard(
                                card = card,
                                label = card.name,
                                groupLabel = group.label,
                                selected = card.id == selectedCardId,
                                inUse = card.id in inUse,
                                onClick = { onOpenCard(card) },
                                onHoverChange = { entered ->
                                    onHoveredChange(if (entered) card.id else null)
                                },
                            )
                        }
                    }
                }
            }
        }
        item(key = "list:bottom") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun LibraryGroupHeader(group: LibraryCardGroup) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FmPalette.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = group.label,
                style = FmType.labelMedium,
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = group.count.toString(),
                style = FmType.labelSmall.copy(fontSize = 10.sp),
                color = FmPalette.onSurfaceFaint,
            )
        }
        if (!group.note.isNullOrBlank()) {
            Text(
                text = group.note.orEmpty(),
                style = FmType.labelSmall.copy(fontSize = 10.sp),
                color = FmPalette.onSurfaceMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 预设页签：现有 preset 列表，点“应用”走 ProjectHolder.applyPreset。 */
@Composable
private fun PresetList(
    presets: List<Preset>,
    cardName: (String) -> String?,
    onApplyPreset: (String) -> Unit,
    onOpenPresetSheet: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (presets.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("还没有预设", style = FmType.titleSmall, color = FmPalette.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(
                    "在舞台或右栏保存当前设计，之后可一键套用。",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                items(presets.size, key = { presets[it].id }) { i ->
                    val preset = presets[i]
                    val presetShape = RoundedCornerShape(FmRadii.chip)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(presetShape)
                            .background(FmPalette.surfaceMuted)
                            .fmElevated(FmElevation.card, presetShape)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = preset.name,
                                style = FmType.labelMedium,
                                color = FmPalette.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = cardName(preset.design.cardId) ?: preset.design.cardId,
                                style = FmType.labelSmall.copy(fontSize = 10.sp),
                                color = FmPalette.onSurfaceMuted,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = "应用",
                            style = FmType.labelSmall,
                            color = FmPalette.primaryStrong,
                            modifier = Modifier
                                .clip(RoundedCornerShape(FmRadii.pill))
                                .clickable { onApplyPreset(preset.id) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPresetSheet)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("管理预设…", style = FmType.labelMedium, color = FmPalette.primaryStrong)
        }
    }
}


