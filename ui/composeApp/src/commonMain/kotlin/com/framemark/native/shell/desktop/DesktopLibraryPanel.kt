package com.framemark.native.shell.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryCardGroup
import com.framemark.native.library.LibraryCategory
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.PreviewDiagnostics
import com.framemark.native.product.ProjectPreset
import com.framemark.native.theme.FmElevation
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmRhythm
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.theme.columnRule
import com.framemark.native.theme.fmElevated
import com.framemark.native.workflow.WorkflowEnv
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * V2 左栏（边框库），逐屏照演示稿 `.rail`：
 *
 *  页签 34px（14px，选中 600 + 2px `--ink` 下划线，计数 11px num）
 *  → 搜索 34px（圆角 8、`--muted-bg`，聚焦白底 + accent 内描边 + ring）
 *  → 类别 `.ltabs` 32px（8 等分、13px、选中 600 + 20% 内缩 2px 下划线）
 *  → 锚点 `.anchors` 26px（`在用 / 最近 / 热门`，选中 accent-soft）
 *  → 吸顶分组标题（12.5/600 + 11px 计数）+ `auto-fill minmax(118,1fr)` 卡片网格。
 *
 * 卡片缩略图是**当前舞台照片**套该边框（[DesktopLibraryThumb]），与演示稿一致。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DesktopLibraryPanel(
    index: LibraryIndex,
    filters: DesktopLibraryFilters,
    inUseIds: List<String>,
    recentIds: List<String>,
    selectedCardId: String?,
    presets: List<ProjectPreset>,
    onOpenCard: (LibraryCard) -> Unit,
    onHoverCard: (LibraryCard?) -> Unit,
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
    val anchors = remember(groups, filters.categoryKey, filters.isSearching) {
        desktopLibraryAnchors(
            groups = groups,
            // 常用页签固定三个槽位；搜索 / 其它类别按实际分组走。
            keepCommonSlots = !filters.isSearching && filters.categoryKey == LibraryIndex.CATEGORY_COMMON,
        )
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val photoPath = desktopActivePhotoPath()
    // “本项目在用”组的副行：几张照片在用 / 照片名在用（演示稿 core.js libGroups 的 desc）。
    val inUseCaptions = remember(WorkflowEnv.projectHolder?.project) {
        val project = WorkflowEnv.projectHolder?.project
        if (project == null) {
            emptyMap()
        } else {
            val byCard = HashMap<String, Int>()
            project.photos.forEach { photo ->
                project.designs[photo.id]?.cardId?.let { cardId ->
                    byCard[cardId] = (byCard[cardId] ?: 0) + 1
                }
            }
            byCard.mapValues { (_, count) -> desktopInUseCaption(count) }
        }
    }

    var hoveredId by remember { mutableStateOf<String?>(null) }
    var hoverNotice by remember { mutableStateOf<String?>(null) }
    var seriesMenuOpen by remember { mutableStateOf(false) }
    var pendingAnchor by remember { mutableStateOf<String?>(null) }

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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 网格列数 = `auto-fill minmax(118px, 1fr)`；左右各 12dp 是 `.gridscroll` 的 padding。
        val columns = desktopLibraryColumns((maxWidth - 24.dp).value)
        val anchorsAtItem = remember(groups, columns) { headerAnchors(groups, columns) }
        val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
        val currentGroup = remember(groups, columns, firstVisible) {
            groupAtItem(groups, columns, firstVisible)
        }
        val activeAnchorId = remember(groups, currentGroup) {
            desktopActiveAnchorId(groups, currentGroup?.key)
        }

        LaunchedEffect(anchorsAtItem, pendingAnchor) {
            val key = pendingAnchor ?: return@LaunchedEffect
            val target = anchorsAtItem[key] ?: return@LaunchedEffect
            listState.animateScrollToItem(target)
            pendingAnchor = null
        }

        fun jumpTo(groupKey: String) {
            pendingAnchor = groupKey
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LibraryPanelHeader(
                borderCount = index.cards.size,
                presetCount = presets.size,
                selectedTab = filters.tab,
                onSelectTab = { filters.tab = it },
                onCollapse = onCollapse,
            )
            if (filters.tab == DesktopLibraryTab.Borders) {
                LibraryFilterZone(
                    categories = categories,
                    filters = filters,
                    anchors = anchors,
                    activeAnchorId = activeAnchorId,
                    onSearchFocusChanged = onSearchFocusChanged,
                    onJump = ::jumpTo,
                    onRepeatCategory = { scope.launch { listState.scrollToItem(0) } },
                    onOpenSeries = { seriesMenuOpen = true },
                )
                SeriesDirectoryMenu(
                    index = index,
                    expanded = seriesMenuOpen,
                    vendorKey = filters.vendorKey,
                    onDismiss = { seriesMenuOpen = false },
                    onPick = { vendor, family ->
                        seriesMenuOpen = false
                        filters.jumpToFamily(vendor, family)
                        if (vendor != null && family != null) pendingAnchor = "vendor|$vendor|$family"
                    },
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LibraryGroupList(
                        groups = groups,
                        columns = columns,
                        photoPath = photoPath,
                        inUseCaptions = inUseCaptions,
                        selectedCardId = selectedCardId,
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
}

/** 页签行 `.rail-top`：`padding: 10px 10px 0 14px`，页签 h34，右端折叠按钮。 */
@Composable
private fun LibraryPanelHeader(
    borderCount: Int,
    presetCount: Int,
    selectedTab: DesktopLibraryTab,
    onSelectTab: (DesktopLibraryTab) -> Unit,
    onCollapse: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            DesktopLibraryTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                val count = if (tab == DesktopLibraryTab.Borders) borderCount else presetCount
                Column(
                    modifier = Modifier
                        .height(34.dp)
                        .width(androidx.compose.foundation.layout.IntrinsicSize.Max)
                        .clickable { onSelectTab(tab) }
                        .semantics {
                            contentDescription = "${tab.label}页签，$count 个"
                            role = Role.Tab
                            this.selected = selected
                        },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = tab.label,
                            style = DesktopLibraryType.tabLabel,
                            color = if (selected) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = count.toString(),
                            style = DesktopLibraryType.tabCount,
                            color = FmPalette.onSurfaceMuted,
                            maxLines = 1,
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (selected) FmPalette.onSurface else Color.Transparent),
                    )
                }
            }
        }
        DesktopCollapseButton(onCollapse = onCollapse)
    }
}

/** `.ibtn.sm`：28×28，16px 侧栏图标（演示稿 `ic('sidebar', 16)`）。 */
@Composable
private fun DesktopCollapseButton(onCollapse: () -> Unit) {
    val ink = FmPalette.onSurfaceMuted
    Box(
        modifier = Modifier
            .width(28.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .clickable(onClick = onCollapse)
            .semantics { contentDescription = "收起边框库" },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.width(16.dp).height(16.dp)) {
            val w = 1.5.dp.toPx()
            val inset = 1.dp.toPx()
            drawRoundRect(
                color = ink,
                topLeft = Offset(inset, 2.dp.toPx()),
                size = Size(size.width - inset * 2f, size.height - 4.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx()),
                style = Stroke(width = w),
            )
            drawLine(
                color = ink,
                start = Offset(size.width * 0.36f, 2.6.dp.toPx()),
                end = Offset(size.width * 0.36f, size.height - 2.6.dp.toPx()),
                strokeWidth = w,
            )
        }
    }
}

/**
 * 固定筛选区（`.rail-filters.lib`）：搜索 / 类别 `.ltabs` / 锚点 `.anchors`。
 * 类别行与锚点行的分隔线横跨整栏，与演示稿一致。
 */
@Composable
private fun LibraryFilterZone(
    categories: List<LibraryCategory>,
    filters: DesktopLibraryFilters,
    anchors: List<DesktopLibraryAnchor>,
    activeAnchorId: String?,
    onSearchFocusChanged: (Boolean) -> Unit,
    onJump: (String) -> Unit,
    onRepeatCategory: () -> Unit,
    onOpenSeries: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(FmRhythm.rowLoose))
        Box(Modifier.fillMaxWidth().padding(horizontal = FmRhythm.group)) {
            DesktopSearchField(
                value = filters.query,
                onValueChange = { filters.query = it },
                onFocusChanged = onSearchFocusChanged,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // `.rail-filters { padding: 10px 12px 10px; display: grid; gap: 10px }`：
        // 搜索框 → 类别行的间距改前是 8（合成 6 dp 的一档），演示稿是 10。
        Spacer(Modifier.height(FmRhythm.rowLoose))
        LibraryCategoryTabs(
            categories = categories,
            selectedKey = filters.categoryKey,
            dimmed = filters.isSearching,
            onSelect = { key ->
                when {
                    filters.isSearching -> {
                        filters.query = ""
                        filters.selectCategory(key)
                    }
                    filters.categoryKey == key -> onRepeatCategory()
                    else -> filters.selectCategory(key)
                }
            },
        )
        LibraryAnchorRow(
            anchors = anchors,
            activeAnchorId = activeAnchorId,
            onJump = onJump,
            onOpenSeries = if (filters.categoryKey == LibraryIndex.CATEGORY_VENDOR && !filters.isSearching) {
                onOpenSeries
            } else {
                null
            },
        )
    }
}

/** `.ltabs`：8 等分、h32、13px；选中 600 + 底部 2px `--ink`（左右各内缩 20%）。 */
@Composable
private fun LibraryCategoryTabs(
    categories: List<LibraryCategory>,
    selectedKey: String?,
    dimmed: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 6.dp)) {
            categories.forEach { category ->
                val selected = !dimmed && category.key == selectedKey
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(category.key) }
                        .semantics {
                            contentDescription = "类别：${category.name}，${category.count} 个"
                            role = Role.Tab
                            this.selected = selected
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = category.name,
                        style = DesktopLibraryType.categoryLabel,
                        color = if (selected) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                    if (selected) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(0.6f)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(FmPalette.onSurface),
                        )
                    }
                }
            }
        }
        // 类别行与锚点行是筛选区里的两个**子分组**：按规则 2 用留白分开，不画线。
        // （改前这里有一条通栏发线，加上锚点行下面那条，筛选区里共有两条线；演示稿
        // 只有 `.rail-filters` 的一条 border-bottom。留白由锚点行自己的上内边距承担。）
    }
}

/** `.anchors`：h26、圆角 6、12.5px；选中 `--accent-soft` 底 + `--accent-strong` 600。 */
@Composable
private fun LibraryAnchorRow(
    anchors: List<DesktopLibraryAnchor>,
    activeAnchorId: String?,
    onJump: (String) -> Unit,
    onOpenSeries: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    // 上 12 / 下 10：上 12 = `.rail-filters` 的 gap 10 + `.subchips` 的
                    // padding-top 2（类别行与锚点行之间那条发线已经去掉，改用留白）；
                    // 下 10 = `.rail-filters` 的 padding-bottom（改前是 6，整块偏紧 4 dp）。
                    .padding(
                        start = FmRhythm.group,
                        end = FmRhythm.group,
                        top = FmRhythm.group,
                        bottom = FmRhythm.rowLoose,
                    ),
                horizontalArrangement = Arrangement.spacedBy(FmRhythm.inline),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                anchors.forEach { anchor ->
                    val on = anchor.id == activeAnchorId && anchor.enabled
                    val enabled = anchor.enabled
                    Row(
                        modifier = Modifier
                            .height(26.dp)
                            .clip(RoundedCornerShape(FmRadii.chip))
                            .background(if (on) FmPalette.primarySoft else Color.Transparent)
                            .then(
                                if (enabled) {
                                    Modifier
                                        .clickable { onJump(anchor.target) }
                                        .semantics { contentDescription = "跳到${anchor.label}，${anchor.count} 个" }
                                } else {
                                    Modifier.semantics {
                                        contentDescription = "${anchor.label}：暂无数据"
                                    }
                                },
                            )
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = anchor.label,
                            style = DesktopLibraryType.chipLabel,
                            color = when {
                                !enabled -> FmPalette.onSurfaceFaint
                                on -> FmPalette.primaryStrong
                                else -> FmPalette.onSurfaceMuted
                            },
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = anchor.count.toString(),
                            style = DesktopLibraryType.chipCount,
                            color = when {
                                !enabled -> FmPalette.onSurfaceFaint
                                on -> FmPalette.primary
                                else -> FmPalette.onSurfaceMuted
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
            if (onOpenSeries != null) {
                DesktopSeriesButton(onClick = onOpenSeries)
            }
        }
        // 规则 1：筛选区（搜索 / 类别 / 锚点）与卡片网格之间的通栏发线
        // —— 对应演示稿 `.rail-filters { border-bottom: 1px solid var(--line) }`。
        Box(Modifier.columnRule())
    }
}

/** `.dirbtn`：h26、圆角 6、12.5px、内描边 `--line-2`，只在厂商类别出现。 */
@Composable
private fun DesktopSeriesButton(onClick: () -> Unit) {
    val glyph = FmPalette.onSurfaceSecondary
    val edge = FmPalette.outlineStrong
    val surface = FmPalette.surface
    Row(
        modifier = Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(surface)
            .drawBehind {
                drawRoundRect(
                    color = edge,
                    cornerRadius = CornerRadius(FmRadii.chip.toPx()),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.width(15.dp).height(15.dp)) {
            val ink = glyph
            val dot = 1.3.dp.toPx()
            val lineW = 1.4.dp.toPx()
            for (i in 0 until 3) {
                val y = (4.dp.toPx() + i * 4.5.dp.toPx())
                drawCircle(color = ink, radius = dot, center = Offset(2.6.dp.toPx(), y))
                drawLine(
                    color = ink,
                    start = Offset(5.6.dp.toPx(), y),
                    end = Offset(13.dp.toPx(), y),
                    strokeWidth = lineW,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "系列",
            style = DesktopLibraryType.chipLabel,
            color = FmPalette.onSurfaceSecondary,
            maxLines = 1,
        )
    }
}

/**
 * `.search`：h34、圆角 8、`--muted-bg`、放大镜 16 + placeholder；
 * 聚焦 = 白底 + 1.5px accent 内描边 + 3px ring（`box-shadow: inset …, 0 0 0 3px ring`）。
 */
@Composable
private fun DesktopSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val background = if (focused) FmPalette.surface else FmPalette.surfaceMuted
    val accent = FmPalette.primary
    val ring = FmPalette.ring
    val placeholder = FmPalette.onSurfaceFaint
    Box(
        modifier = modifier
            .height(34.dp)
            .drawBehind {
                val radius = FmRadii.chip.toPx()
                drawRoundRect(color = background, cornerRadius = CornerRadius(radius))
                if (focused) {
                    val ringWidth = 3.dp.toPx()
                    drawRoundRect(
                        color = ring,
                        topLeft = Offset(-ringWidth / 2f, -ringWidth / 2f),
                        size = Size(size.width + ringWidth, size.height + ringWidth),
                        cornerRadius = CornerRadius(radius + ringWidth / 2f),
                        style = Stroke(width = ringWidth),
                    )
                    val inner = 1.5.dp.toPx()
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(inner / 2f, inner / 2f),
                        size = Size(size.width - inner, size.height - inner),
                        cornerRadius = CornerRadius(radius - inner / 2f),
                        style = Stroke(width = inner),
                    )
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DesktopSearchGlyph(color = FmPalette.onSurfaceFaint)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = DesktopLibraryType.searchInput.copy(color = FmPalette.onSurface),
                cursorBrush = SolidColor(FmPalette.primary),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged {
                        focused = it.isFocused
                        onFocusChanged(it.isFocused)
                    },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = "搜索边框或系列，如“哈苏”",
                                style = DesktopLibraryType.searchInput,
                                color = placeholder,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            if (value.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .clickable { onValueChange("") }
                        .semantics { contentDescription = "清除搜索" },
                    contentAlignment = Alignment.Center,
                ) {
                    DesktopClearGlyph(color = FmPalette.onSurfaceFaint)
                }
            }
        }
    }
}

/** 放大镜 16px（`ic('search', 16)`）。 */
@Composable
private fun DesktopSearchGlyph(color: Color) {
    androidx.compose.foundation.Canvas(modifier = Modifier.width(16.dp).height(16.dp)) {
        val w = 1.5.dp.toPx()
        drawCircle(
            color = color,
            radius = 4.7.dp.toPx(),
            center = Offset(6.9.dp.toPx(), 6.9.dp.toPx()),
            style = Stroke(width = w),
        )
        drawLine(
            color = color,
            start = Offset(10.4.dp.toPx(), 10.4.dp.toPx()),
            end = Offset(14.dp.toPx(), 14.dp.toPx()),
            strokeWidth = w,
        )
    }
}

/** 清除按钮的 ×（`ic('x', 14)`）。 */
@Composable
private fun DesktopClearGlyph(color: Color) {
    androidx.compose.foundation.Canvas(modifier = Modifier.width(14.dp).height(14.dp)) {
        val inset = 3.dp.toPx()
        val w = 1.5.dp.toPx()
        drawLine(
            color = color,
            start = Offset(inset, inset),
            end = Offset(size.width - inset, size.height - inset),
            strokeWidth = w,
        )
        drawLine(
            color = color,
            start = Offset(size.width - inset, inset),
            end = Offset(inset, size.height - inset),
            strokeWidth = w,
        )
    }
}

/**
 * 卡片列表：每个分组一个吸顶标题 + `auto-fill` 网格行（左右 12dp、上 2dp、下 16dp），
 * 与演示稿 `.gridscroll.lib` + `.grid` 的间距一致。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGroupList(
    groups: List<LibraryCardGroup>,
    columns: Int,
    photoPath: String?,
    inUseCaptions: Map<String, String>,
    selectedCardId: String?,
    listState: LazyListState,
    onOpenCard: (LibraryCard) -> Unit,
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
                "换个关键词，或清除搜索。",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 16.dp),
    ) {
        groups.forEach { group ->
            stickyHeader(key = "head:${group.key}") {
                LibraryGroupHeader(group = group)
            }
            cardRows(group.cards, columns).forEachIndexed { rowIndex, row ->
                item(key = "row:${group.key}:$rowIndex") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (rowIndex == 0) 0.dp else 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { card ->
                            Box(Modifier.weight(1f)) {
                                DesktopLibraryCard(
                                    card = card,
                                    selected = card.id == selectedCardId,
                                    photoPath = photoPath,
                                    subtitle = if (group.key == LibraryIndex.GROUP_USE) {
                                        inUseCaptions[card.id] ?: desktopCardSubtitleFor(card)
                                    } else {
                                        desktopCardSubtitleFor(card)
                                    },
                                    onClick = { onOpenCard(card) },
                                    onHoverChange = { entered ->
                                        onHoveredChange(if (entered) card.id else null)
                                    },
                                )
                            }
                        }
                        repeat(columns - row.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item(key = "list:bottom") { Spacer(Modifier.height(8.dp)) }
    }
}

/**
 * 卡片副行术语（AU4-20）。
 *
 * 改前 `desktopCardSubtitle` 直接拼 `类别 · 子类/风格`（「文字 · 参数」「印象 · 液态玻璃」
 * 「白边 · 留白」「卡片 · 色卡」）：**类别**和**风格**混在同一行，而且丢了「边框」后缀，
 * 用户在左栏看不出这张卡属于哪一类。演示稿的副行是「类别 + 边框」（右栏分类 pill
 * 也是「文字边框」「水印边框」这种写法），风格名不进副行 —— 风格是卡片内容的一部分，
 * 已经由缩略图表达。
 *
 * 映射表（类别键 → 副行）：
 *
 * | 类别 | 改前 | 改后 |
 * |---|---|---|
 * | `text` 文字 | 文字 · 参数 / 文字 · 厂商水印 | 文字边框 |
 * | `white` 白边 | 白边 · 留白 | 白边边框 |
 * | `card` 卡片 | 卡片 · 色卡 | 卡片边框 |
 * | `impression` 印象 | 印象 · 液态玻璃 | 印象边框 |
 * | `color` 色彩 | 色彩 · 渐变 | 色彩边框 |
 * | `minimal` 简约 | 简约 · 单行 | 简约边框 |
 * | `vendor` 厂商 | OPPO · 哈苏定制 | OPPO · 哈苏定制（不变，厂商卡按品牌 · 系列） |
 *
 * 「本项目在用」一组仍然优先用张数文案（`desktopInUseCaption`），这条规则只作用于
 * 类别副行。数据侧（[desktopCardSubtitle]）不动：它是 `DesktopLibraryParityTest` 的
 * 断言对象，改它会让演示稿对齐测试失效；这里只做**显示层**的术语归一。
 */
internal fun desktopCardSubtitleFor(card: LibraryCard): String {
    val raw = desktopCardSubtitle(card)
    if (card.category == LibraryIndex.CATEGORY_VENDOR) return raw
    val category = card.categoryName.removeSuffix("边框").trim()
    if (category.isBlank()) return raw
    return "${category}边框"
}

/** `.gsec-h`：sticky、12.5/600 + 11px 计数（紧跟标题）、右侧 11px note。 */
@Composable
private fun LibraryGroupHeader(group: LibraryCardGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FmPalette.surface.copy(alpha = 0.94f))
            .padding(start = 2.dp, end = 2.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = group.label,
            style = DesktopLibraryType.groupTitle,
            color = FmPalette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = group.count.toString(),
            style = DesktopLibraryType.chipCount,
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        if (!group.note.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = group.note.orEmpty(),
                style = DesktopLibraryType.groupNote,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(bottom = 1.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
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

/** 预设页签：现有 preset 列表，点“应用”走 ProjectHolder.applyPreset。 */
@Composable
private fun PresetList(
    presets: List<ProjectPreset>,
    cardName: (String) -> String?,
    onApplyPreset: (String) -> Unit,
    onOpenPresetSheet: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 演示稿预设页顶部说明（`.rail-filters` + `.gsec-note`）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp)
                .clip(RoundedCornerShape(FmRadii.chip))
                .background(FmPalette.surfaceMuted)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = "预设保存边框和参数，不保存照片文字；套用后文字仍读每张照片自己的 EXIF。",
                fontSize = 11.5.sp,
                lineHeight = 18.sp,
                color = FmPalette.onSurfaceMuted,
            )
        }
        Spacer(Modifier.height(10.dp))
        // 规则 1：预设表的标题/说明与列表之间是通栏发线。
        Box(Modifier.columnRule())
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
