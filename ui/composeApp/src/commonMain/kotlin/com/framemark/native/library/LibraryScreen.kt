package com.framemark.native.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType

/** Search + category + vendor selections; held above the tab crossfade. */
class LibraryFilterState {
    var query by mutableStateOf("")
    var categoryKey by mutableStateOf<String?>("vendor")
    var vendorKey by mutableStateOf<String?>(null)

    val isActive: Boolean get() = query.isNotBlank() || categoryKey != null || vendorKey != null

    fun clear() {
        query = ""
        categoryKey = null
        vendorKey = null
    }
}

/** Data provenance labels are internal shorthand; keep them out of user-facing filters. */
internal fun friendlyVendorLabel(label: String): String =
    if (label == "未在固件中证实") "其他模板" else label

@Composable
fun LibraryScreen(
    loadState: LibraryLoadState,
    filters: LibraryFilterState,
    gridState: LazyGridState,
    onRetry: () -> Unit,
    onOpenCard: (LibraryCard) -> Unit,
    /**
     * U5 desktop rail mode: same filters/cards, 16 dp edges, no marketing
     * subtitle, no floating-nav bottom padding. The phone layout is unchanged
     * because every value below defaults to the U2 geometry.
     */
    rail: Boolean = false,
    /** Optional collapse affordance rendered at the right of the title row. */
    headerTrailing: (@Composable () -> Unit)? = null,
    /** U5: highlight the card currently shown on the stage. Null on phone. */
    selectedCardId: String? = null,
    /**
     * Phone full-screen picker (opened from the editor's 边框 segment): tighter
     * edges, no floating-nav bottom gap, no data-source subtitle.
     */
    sheet: Boolean = false,
) {
    when (loadState) {
        LibraryLoadState.Loading -> LibraryLoading()
        is LibraryLoadState.Failed -> LibraryFailed(message = loadState.message, onRetry = onRetry)
        is LibraryLoadState.Ready -> LibraryReady(
            snapshot = loadState.snapshot,
            filters = filters,
            gridState = gridState,
            onOpenCard = onOpenCard,
            rail = rail,
            headerTrailing = headerTrailing,
            selectedCardId = selectedCardId,
            sheet = sheet,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryReady(
    snapshot: LibrarySnapshot,
    filters: LibraryFilterState,
    gridState: LazyGridState,
    onOpenCard: (LibraryCard) -> Unit,
    rail: Boolean,
    headerTrailing: (@Composable () -> Unit)?,
    selectedCardId: String?,
    sheet: Boolean,
) {
    val view = remember(snapshot, filters.query, filters.categoryKey, filters.vendorKey) {
        LibraryViewBuilder.build(
            cards = snapshot.cards,
            filter = LibraryFilter(
                categoryKey = filters.categoryKey,
                vendorKey = filters.vendorKey,
                query = filters.query,
            ),
        )
    }
    val categoryCards = remember(snapshot.cards, filters.categoryKey) {
        val key = filters.categoryKey
        if (key == null) snapshot.cards else snapshot.cards.filter { it.category == key }
    }
    val vendors = remember(categoryCards) { LibraryViewBuilder.vendorChoices(categoryCards) }
    val failures = PreviewDiagnostics.failures.size

    // A vendor bucket can disappear when a category changes (e.g. OPPO has no
    // 文字边框 card). Drop the stale vendor filter instead of showing an empty
    // grid whose dropdown reads "全部".
    LaunchedEffect(filters.categoryKey, filters.vendorKey, vendors) {
        if (filters.vendorKey != null && vendors.none { it.key == filters.vendorKey }) {
            filters.vendorKey = null
        }
    }

    // A LazyGrid anchors on the first visible item *key* when the data changes,
    // which can keep the user parked on an unrelated section after a filter
    // change. Every filter edit restarts at the top instead.
    LaunchedEffect(filters.query, filters.categoryKey, filters.vendorKey) {
        if (view.count > 0) gridState.scrollToItem(0)
    }

    val edge = when {
        rail -> 16.dp
        sheet -> 16.dp
        else -> FmSpacing.screen
    }
    val headerTop = when {
        rail -> 16.dp
        sheet -> 18.dp
        else -> 28.dp
    }
    val listBottom = when {
        rail -> 16.dp
        sheet -> 24.dp
        else -> FmSpacing.contentBottom
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = edge, end = edge, top = headerTop),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("边框", style = FmType.titleLarge, color = FmPalette.onSurface)
                Spacer(Modifier.weight(1f))
                Text(
                    text = when {
                        rail && filters.isActive -> "显示 ${view.count}"
                        rail -> "${snapshot.cards.size} 套"
                        else -> "${snapshot.cards.size} 套 · ${snapshot.categories.size} 类"
                    },
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                )
                if (headerTrailing != null) {
                    Spacer(Modifier.width(2.dp))
                    headerTrailing()
                }
            }
            if (!rail && !sheet) {
                Spacer(Modifier.height(FmSpacing.xs))
                Text(
                    text = "E1a 数据 · 厂商 / 文字 / 印象 / 白边 / 卡片 / 色彩 / 简约",
                    style = FmType.bodyMedium,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(if (rail) FmSpacing.sm else FmSpacing.md))
            SearchField(filters, rail = rail)
            Spacer(Modifier.height(FmSpacing.sm))
            val categoryChips: @Composable () -> Unit = {
                LibraryChip(
                    label = "全部",
                    count = snapshot.cards.size,
                    selected = filters.categoryKey == null,
                    onClick = { filters.categoryKey = null },
                )
                snapshot.categories.forEach { category ->
                    LibraryChip(
                        label = category.name,
                        count = category.count,
                        selected = filters.categoryKey == category.key,
                        onClick = {
                            filters.categoryKey =
                                if (filters.categoryKey == category.key) null else category.key
                        },
                    )
                }
            }
            // One filter hierarchy only. A compact horizontal rail preserves the
            // full labels without spending two rows before the card grid starts.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
            ) {
                categoryChips()
            }
            if (filters.categoryKey == "vendor" && filters.query.isBlank()) {
                Spacer(Modifier.height(FmSpacing.xs))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(FmSpacing.sm),
                ) {
                    vendors.filter { it.key != null }.forEach { vendor ->
                        LibraryChip(
                            label = friendlyVendorLabel(vendor.label),
                            count = vendor.count,
                            selected = filters.vendorKey == vendor.key,
                            onClick = {
                                filters.vendorKey = if (filters.vendorKey == vendor.key) null else vendor.key
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!rail) {
                    Text(
                        text = if (filters.query.isNotBlank()) "找到 ${view.count} 套" else "显示 ${view.count} / ${view.total}",
                        style = FmType.labelMedium,
                        color = FmPalette.onSurface,
                    )
                }
            }
            if (filters.isActive || failures > 0 || snapshot.unmappedPreviewIds.isNotEmpty()) {
                Spacer(Modifier.height(FmSpacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (filters.isActive) {
                        Text(
                            text = "清除筛选",
                            style = FmType.labelMedium,
                            color = FmPalette.primaryStrong,
                            modifier = Modifier
                                .clip(RoundedCornerShape(FmRadii.pill))
                                .clickable { filters.clear() }
                                .padding(horizontal = FmSpacing.sm, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (failures > 0) {
                        Text(
                            text = "预览失败 $failures",
                            style = FmType.labelSmall,
                            color = FmPalette.warning,
                        )
                    } else if (snapshot.unmappedPreviewIds.isNotEmpty()) {
                        Text(
                            text = "无预览映射 ${snapshot.unmappedPreviewIds.size}",
                            style = FmType.labelSmall,
                            color = FmPalette.warning,
                        )
                    }
                }
            }
            Spacer(Modifier.height(FmSpacing.xs))
        }

        if (view.count == 0) {
            LibraryEmpty(filters = filters)
        } else {
            // U3-D1：族数 >5 的厂商在网格上方给一行可横滚的族索引；点 chip 滚到族头并高亮 1.5s。
            val scope = rememberCoroutineScope()
            var highlightedHeader by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(highlightedHeader) {
                if (highlightedHeader != null) {
                    delay(1500)
                    highlightedHeader = null
                }
            }
            val headerIndex = remember(view) { subgroupHeaderIndices(view) }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = edge,
                    end = edge,
                    top = FmSpacing.xs,
                    bottom = listBottom,
                ),
                horizontalArrangement = Arrangement.spacedBy(if (rail) 10.dp else FmSpacing.md),
                verticalArrangement = Arrangement.spacedBy(if (rail) 10.dp else FmSpacing.md),
            ) {
                view.sections.forEach { section ->
                    item(
                        key = "vendor:${section.vendorKey}",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        VendorHeader(section)
                    }
                    val families = section.subgroups.mapNotNull { it.label }
                    if (families.size > LibraryGroups.CHIP_INDEX_MIN_FAMILIES) {
                        item(
                            key = "families:${section.vendorKey}",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            FamilyIndexChips(
                                labels = families,
                                sectionKey = section.vendorKey,
                                active = highlightedHeader,
                                onPick = { label ->
                                    val key = headerKey(section.vendorKey, label)
                                    highlightedHeader = key
                                    headerIndex[key]?.let { target ->
                                        scope.launch { gridState.animateScrollToItem(target) }
                                    }
                                },
                            )
                        }
                    }
                    section.subgroups.forEach { subgroup ->
                        val key = subgroup.label?.let { headerKey(section.vendorKey, it) }
                        if (subgroup.label != null || subgroup.note != null) {
                            item(
                                key = "group:${section.vendorKey}:${subgroup.label ?: '-'}",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                SubgroupHeader(subgroup, highlighted = key != null && highlightedHeader == key)
                            }
                        }
                        items(items = subgroup.cards, key = { it.id }) { card ->
                            LibraryCardTile(
                                card = card,
                                selected = card.id == selectedCardId,
                                onClick = { onOpenCard(card) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 族索引与族头的配对键（只拼数据字段，不含任何组名常量）。 */
private fun headerKey(vendorKey: String, label: String) = "$vendorKey|$label"

/**
 * 与网格 DSL 相同的计数顺序：厂商头 →（族 chip 行）→ 族头 → 该族卡片。
 * 只用于 chip 的 animateScrollToItem 目标；渲染本身不依赖它。
 */
private fun subgroupHeaderIndices(view: LibraryView): Map<String, Int> {
    val map = HashMap<String, Int>()
    var index = 0
    for (section in view.sections) {
        index += 1
        val familyCount = section.subgroups.count { it.label != null }
        if (familyCount > LibraryGroups.CHIP_INDEX_MIN_FAMILIES) index += 1
        for (subgroup in section.subgroups) {
            if (subgroup.label != null || subgroup.note != null) {
                if (subgroup.label != null) map[headerKey(section.vendorKey, subgroup.label)] = index
                index += 1
            }
            index += subgroup.cards.size
        }
    }
    return map
}

// ---------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------

/** Desktop left-rail parity: 7 category chips laid out as 4 + 3, all visible. */
@Composable
private fun CategoryChipGrid(
    categories: List<LibraryCategory>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
) {
    if (categories.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        categories.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { category ->
                    CategoryGridChip(
                        label = category.name,
                        count = category.count,
                        selected = category.key == selectedKey,
                        onClick = { onSelect(category.key) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CategoryGridChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) FmPalette.primarySoft else FmPalette.surface)
            .border(
                1.dp,
                if (selected) FmPalette.primary.copy(alpha = 0.5f) else FmPalette.outline,
                RoundedCornerShape(10.dp),
            )
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
                stateDescription = if (selected) "已选中" else "未选中"
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = FmType.labelSmall.copy(fontSize = 11.sp),
            color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
            maxLines = 1,
        )
        Text(
            text = count.toString(),
            style = FmType.labelSmall.copy(fontSize = 10.sp),
            color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
        )
    }
}

/** Old BorderLibrary 分类 row: collapsed shows 名称·数量, expanded lists 全部 + 7. */
@Composable
private fun CategoryPicker(
    categories: List<LibraryCategory>,
    total: Int,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = categories.firstOrNull { it.key == selectedKey }
    val name = current?.name ?: "全部"
    val count = current?.count ?: total
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.surfaceMuted)
                .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.pill))
                .semantics(mergeDescendants = true) {
                    contentDescription = "分类：$name，共${count}套"
                    role = Role.Button
                    stateDescription = if (open) "已展开" else "已折叠"
                }
                .clickable { open = !open }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("分类", style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
            Spacer(Modifier.width(6.dp))
            Text(
                text = "$name · $count",
                style = FmType.labelMedium,
                color = FmPalette.primaryStrong,
            )
            Spacer(Modifier.weight(1f))
            Text(if (open) "⌃" else "⌄", style = FmType.labelMedium, color = FmPalette.onSurfaceMuted)
        }
        if (open) {
            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FmRadii.card))
                    .background(FmPalette.surface)
                    .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.card))
                    .padding(vertical = 4.dp),
            ) {
                CategoryRow(label = "全部", count = total, selected = selectedKey == null) {
                    open = false
                    onSelect(null)
                }
                categories.forEach { category ->
                    CategoryRow(
                        label = category.name,
                        count = category.count,
                        selected = selectedKey == category.key,
                    ) {
                        open = false
                        onSelect(category.key)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
                stateDescription = if (selected) "已选中" else "未选中"
            }
            .clickable(onClick = onClick)
            .background(if (selected) FmPalette.primarySoft else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = FmType.bodyMedium,
            color = if (selected) FmPalette.primaryStrong else FmPalette.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = count.toString(),
            style = FmType.labelMedium,
            color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
        )
    }
}

@Composable
private fun SearchField(filters: LibraryFilterState, rail: Boolean) {
    val shape = RoundedCornerShape(FmRadii.input)
    BasicTextField(
        value = filters.query,
        onValueChange = { filters.query = it },
        singleLine = true,
        textStyle = FmType.bodyMedium.copy(color = FmPalette.onSurface),
        cursorBrush = SolidColor(FmPalette.primary),
        modifier = Modifier
            .fillMaxWidth()
            .height(if (rail) 40.dp else 44.dp)
            .semantics {
                contentDescription = "搜索模板、品牌或系列"
            },
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(FmPalette.surfaceMuted)
                    .border(1.dp, FmPalette.outline, shape)
                    .padding(horizontal = FmSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchGlyph()
                Spacer(Modifier.width(FmSpacing.sm))
                Box(modifier = Modifier.weight(1f)) {
                    if (filters.query.isEmpty()) {
                        Text(
                            text = "搜索模板、品牌或系列",
                            style = FmType.bodyMedium,
                            color = FmPalette.onSurfaceMuted,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
                if (filters.query.isNotEmpty()) {
                    Text(
                        text = "清除",
                        style = FmType.labelSmall,
                        color = FmPalette.onSurfaceMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(FmRadii.pill))
                            .clickable { filters.query = "" }
                            .padding(horizontal = FmSpacing.sm, vertical = 4.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun SearchGlyph() {
    // WP-T: read the ink during composition; the Canvas draw scope is not composable.
    val ink = FmPalette.onSurfaceMuted
    Canvas(modifier = Modifier.size(14.dp)) {
        val stroke = 1.5.dp.toPx()
        drawCircle(
            color = ink,
            radius = size.minDimension * 0.32f,
            center = Offset(size.width * 0.42f, size.height * 0.42f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = ink,
            start = Offset(size.width * 0.64f, size.height * 0.64f),
            end = Offset(size.width * 0.9f, size.height * 0.9f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Vendor filter (second level in front of the brand/family sections). A dropdown
 * keeps all six buckets reachable on a 390dp screen without a hidden scroller —
 * including the 68-card 无厂商 / 手写 bucket.
 */
@Composable
private fun VendorFilter(
    vendors: List<VendorChoice>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = vendors.firstOrNull { it.key == selectedKey } ?: vendors.first()
    val shape = RoundedCornerShape(FmRadii.pill)
    Box {
        Row(
            modifier = Modifier
                .height(40.dp)
                .clip(shape)
                .background(if (selectedKey != null) FmPalette.primarySoft else FmPalette.surface)
                .border(
                    width = 1.dp,
                    color = if (selectedKey != null) FmPalette.primary.copy(alpha = 0.45f) else FmPalette.outline,
                    shape = shape,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = "厂商：${selected.label}，${selected.count}套"
                    role = Role.Button
                    stateDescription = if (selectedKey != null) "已筛选" else "全部厂商"
                }
                .clickable { expanded = true }
                .padding(horizontal = FmSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("按厂商", style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
            Spacer(Modifier.width(6.dp))
            Text(
                text = friendlyVendorLabel(selected.label),
                style = FmType.labelMedium,
                color = if (selectedKey != null) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
                maxLines = 1,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = selected.count.toString(),
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
            )
            Spacer(Modifier.width(FmSpacing.xs))
            Text("▾", style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(232.dp)
                .background(FmPalette.surface),
        ) {
            vendors.forEach { vendor ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = friendlyVendorLabel(vendor.label),
                                style = FmType.bodyMedium,
                                color = if (vendor.key == selectedKey) FmPalette.primaryStrong else FmPalette.onSurface,
                                maxLines = 1,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = vendor.count.toString(),
                                style = FmType.labelSmall,
                                color = FmPalette.onSurfaceMuted,
                            )
                        }
                    },
                    onClick = {
                        onSelect(vendor.key)
                        expanded = false
                    },
                    trailingIcon = if (vendor.key == selectedKey) {
                        { Text("✓", style = FmType.labelMedium, color = FmPalette.primaryStrong) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(FmRadii.pill)
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(shape)
            .background(if (selected) FmPalette.primarySoft else FmPalette.surface)
            .border(
                width = 1.dp,
                color = if (selected) FmPalette.primary.copy(alpha = 0.45f) else FmPalette.outline,
                shape = shape,
            )
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
                stateDescription = if (selected) "已选中" else "未选中"
            }
            .clickable(onClick = onClick)
            .padding(horizontal = FmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = FmType.labelMedium,
            color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = count.toString(),
            style = FmType.labelSmall,
            color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
        )
    }
}

// ---------------------------------------------------------------------------
// Grid content
// ---------------------------------------------------------------------------

@Composable
private fun VendorHeader(section: LibrarySection) {
    Column(modifier = Modifier.padding(top = FmSpacing.md, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = friendlyVendorLabel(section.label),
                style = FmType.titleMedium,
                color = FmPalette.onSurface,
            )
            Spacer(Modifier.width(FmSpacing.sm))
            Text(
                text = "${section.count} 套",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
            )
        }
        if (section.note != null) {
            Text(
                text = section.note,
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

@Composable
private fun SubgroupHeader(subgroup: LibrarySubgroup, highlighted: Boolean = false) {
    Column(modifier = Modifier.padding(top = 2.dp)) {
        if (subgroup.label != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(if (highlighted) FmPalette.primarySoft else Color.Transparent)
                    .padding(horizontal = if (highlighted) 8.dp else 0.dp, vertical = if (highlighted) 2.dp else 0.dp),
            ) {
                Text(
                    text = subgroup.label,
                    style = FmType.labelMedium,
                    color = if (highlighted) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = subgroup.cards.size.toString(),
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
            }
        }
        if (subgroup.note != null) {
            Text(
                text = subgroup.note,
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/** U3-D1 族索引：一行可横滚的 chip，文字全部取数据里的 `styleFamilyLabel`。 */
@Composable
private fun FamilyIndexChips(
    labels: List<String>,
    sectionKey: String,
    active: String?,
    onPick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "族 ${labels.size}",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
            modifier = Modifier.padding(end = 2.dp),
        )
        labels.forEach { label ->
            val selected = active == headerKey(sectionKey, label)
            Text(
                text = label,
                style = FmType.labelSmall,
                color = if (selected) FmPalette.onPrimary else FmPalette.onSurfaceSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(if (selected) FmPalette.primaryStrong else FmPalette.surfaceMuted)
                    .semantics(mergeDescendants = true) {
                        role = Role.RadioButton
                        this.selected = selected
                        stateDescription = if (selected) "已选中" else "未选中"
                    }
                    .clickable { onPick(label) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun LibraryCardTile(card: LibraryCard, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FmRadii.card)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) FmPalette.primarySoft else FmPalette.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) FmPalette.primary else FmPalette.outline, shape)
            .semantics(mergeDescendants = true) {
                contentDescription = "${card.name}，${card.categoryName}"
                role = Role.RadioButton
                this.selected = selected
                stateDescription = if (selected) "当前边框" else "未选中"
            }
            .clickable(onClick = onClick)
            .padding(FmSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f),
        ) {
            LibraryPreview(card = card, modifier = Modifier.fillMaxSize())
            if (!card.badge.isNullOrBlank()) {
                Text(
                    text = card.badge,
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceSecondary,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.surface.copy(alpha = 0.9f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(FmSpacing.sm))
        Text(
            text = card.name,
            style = FmType.titleSmall,
            color = FmPalette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = card.categoryName,
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Loading / error / empty
// ---------------------------------------------------------------------------

@Composable
private fun LibraryLoading() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(FmSpacing.screen),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("正在载入边框库…", style = FmType.titleSmall, color = FmPalette.onSurface)
        Spacer(Modifier.height(FmSpacing.xs))
        Text(
            "读取 bundle 的 taxonomy.json / previews.json",
            style = FmType.bodyMedium,
            color = FmPalette.onSurfaceMuted,
        )
    }
}

@Composable
private fun LibraryFailed(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(FmSpacing.screen),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("边框库加载失败", style = FmType.titleSmall, color = FmPalette.error)
        Spacer(Modifier.height(FmSpacing.sm))
        Text(
            text = message,
            style = FmType.bodyMedium,
            color = FmPalette.onSurfaceMuted,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(FmSpacing.md))
        Text(
            text = "重试",
            style = FmType.labelMedium,
            color = FmPalette.primaryStrong,
            modifier = Modifier
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.primarySoft)
                .clickable(onClick = onRetry)
                .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.sm),
        )
    }
}

@Composable
private fun LibraryEmpty(filters: LibraryFilterState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(FmSpacing.screen),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("没有匹配的边框", style = FmType.titleSmall, color = FmPalette.onSurface)
        Spacer(Modifier.height(FmSpacing.xs))
        Text(
            "换一个关键词，或清除分类/厂商筛选",
            style = FmType.bodyMedium,
            color = FmPalette.onSurfaceMuted,
        )
        Spacer(Modifier.height(FmSpacing.md))
        Text(
            text = "清除筛选",
            style = FmType.labelMedium,
            color = FmPalette.primaryStrong,
            modifier = Modifier
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.primarySoft)
                .clickable { filters.clear() }
                .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.sm),
        )
    }
}
