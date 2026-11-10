package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryIndex
import com.framemark.native.shell.PhoneCompactSheetMetrics
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import kotlinx.coroutines.launch

/**
 * V2 phone border sheet body (report §7.5): 一行类别 → 一行分组跳转 → 卡片.
 *
 * Everything comes from WP-L's [LibraryIndex] ([phoneLibraryView]); this composable
 * only lays it out. The 收起 level is the horizontal strip with 128 dp square
 * cards, the 展开 level is the 3-column grid with sticky group titles; the 厂商
 * category gets the 系列 directory and a 系列 chip on the group header.
 *
 * [metrics] is the vertical stack the 收起 level was budgeted with (WP-P1-2), so the
 * category row, the chip row, the strip padding and the name line are exactly the
 * numbers the height budget assumed.
 */
@Composable
fun PhoneLibrarySheet(
    index: LibraryIndex?,
    selectedCardId: String?,
    expanded: Boolean,
    metrics: PhoneCompactSheetMetrics,
    categoryKey: String,
    onCategoryChange: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    inUse: List<String>,
    recent: List<String>,
    onRetry: (() -> Unit)?,
    onPick: (LibraryCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (index == null) {
        LibraryUnavailable(onRetry = onRetry, modifier = modifier)
        return
    }
    var searchOpen by remember { mutableStateOf(false) }
    var seriesOpen by remember { mutableStateOf(false) }
    // The card the user just picked **in this sheet**. Handing it to the reveal keeps the
    // strip/grid from sliding out from under the finger that picked it (bug: every tap in
    // the 收起 strip re-aligned the strip and left the previous card as a sliver).
    var lastPickedId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val view = remember(index, categoryKey, query, inUse, recent) {
        phoneLibraryView(index, categoryKey, vendorKey = null, query = query, inUse = inUse, recent = recent)
    }
    val stripState = rememberLazyListState()
    val gridState = rememberLazyListState()
    val bodyState = if (expanded) gridState else stripState
    PhoneLibraryReveal(
        groups = view.groups,
        selectedCardId = selectedCardId,
        state = bodyState,
        expanded = expanded,
        skipForId = lastPickedId,
    )
    // One pick skips exactly one reveal. The reveal above is keyed on what the user is
    // looking at, not on the pick, so clearing the marker afterwards cannot restart it —
    // and the next external change (展开/收起, category, search, stage swipe) reveals again.
    LaunchedEffect(view.groups, selectedCardId, expanded) {
        lastPickedId = null
    }

    // Both levels route their pick through here so the marker is set before the
    // selection actually changes.
    val pickInSheet: (LibraryCard) -> Unit = { card ->
        lastPickedId = card.id
        onPick(card)
    }

    fun jumpTo(groupKey: String) {
        val target = if (expanded) {
            phoneGridJumpIndex(view.groups, groupKey)
        } else {
            phoneStripJumpIndex(view.groups, groupKey)
        }
        // `-1` is "no such group"; `0` is the first group and a real scroll target.
        if (target < 0) return
        scope.launch { bodyState.animateScrollToItem(target) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (searchOpen) {
            PhoneLibrarySearchRow(
                query = query,
                height = metrics.categoryRow.dp,
                onQueryChange = onQueryChange,
                onClose = {
                    searchOpen = false
                    onQueryChange("")
                },
            )
        } else {
            CategoryTabs(
                view = view,
                categoryKey = categoryKey,
                height = metrics.categoryRow.dp,
                onCategoryChange = onCategoryChange,
                onSearch = { searchOpen = true },
            )
        }
        if (view.anchors.isNotEmpty()) {
            AnchorRow(
                anchors = view.anchors,
                height = metrics.anchorRow.dp,
                showSeries = categoryKey == LibraryIndex.CATEGORY_VENDOR && !searchOpen,
                onJump = { anchor ->
                    jumpTo(anchor.groupKey)
                },
                onOpenSeries = { seriesOpen = true },
            )
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                view.isEmpty -> PhoneLibraryNote(
                    text = if (query.isBlank()) "这里还没有边框。" else "没有匹配「$query」的边框。",
                )
                expanded -> PhoneBorderGrid(
                    groups = view.groups,
                    selectedCardId = selectedCardId,
                    state = gridState,
                    onPick = pickInSheet,
                    onOpenSeries = if (categoryKey == LibraryIndex.CATEGORY_VENDOR) {
                        { seriesOpen = true }
                    } else {
                        null
                    },
                )
                else -> Column(
                    // The strip is exactly as tall as the card plus its name. If the
                    // 50 % stage floor left the sheet shorter than that (the tightest
                    // window + 3-button nav corner, see
                    // PhoneCompactSheetMetrics.overflowAt) this body scrolls instead of
                    // letting the tool bar cut the name in half.
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    PhoneBorderStrip(
                        groups = view.groups,
                        selectedCardId = selectedCardId,
                        state = stripState,
                        metrics = metrics,
                        onPick = pickInSheet,
                        // A group tile always jumps to its own group. It used to be gated on
                        // the key being one of `view.anchors`, and 厂商 aggregates those by
                        // brand (one chip per brand's *first* series), so every later series
                        // tile silently did nothing. `jumpTo` already answers 0 / -1.
                        onJumpGroup = { key -> jumpTo(key) },
                    )
                }
            }
        }
    }

    if (seriesOpen) {
        PhoneSeriesDirectory(
            index = index,
            onDismiss = { seriesOpen = false },
            onPick = { familyGroupKey ->
                seriesOpen = false
                jumpTo(familyGroupKey)
            },
        )
    }
}

@Composable
private fun LibraryUnavailable(onRetry: (() -> Unit)?, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(FmSpacing.md),
        verticalArrangement = Arrangement.Center,
    ) {
        PhoneLibraryNote(if (onRetry == null) "正在载入边框库…" else "暂时无法载入边框库。", Modifier.fillMaxWidth())
        if (onRetry != null) {
            Spacer(Modifier.height(FmSpacing.sm))
            Text(
                text = "重试",
                style = FmType.labelMedium,
                color = FmPalette.primaryStrong,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.primarySoft)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = FmSpacing.md, vertical = 10.dp),
            )
        }
    }
}

/**
 * Category row (showcase `.ptabs`: `padding 0 8px`, `justify-content: space-between`,
 * 14 px labels, 700 + a 3 px underline on the active one, 38 dp tall).
 *
 * The magnifier at the right end is the only addition: the showcase's phone screens
 * have no search surface at all, and dropping the entry would make the library's
 * query unreachable on the phone (registered as a kept difference in the report).
 */
@Composable
private fun CategoryTabs(
    view: PhoneLibraryView,
    categoryKey: String,
    height: Dp,
    onCategoryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(FmPalette.surface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        view.categories.forEach { category ->
            val active = category.key == categoryKey
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${category.name}，${category.count} 个"
                        role = Role.Tab
                    }
                    .clickable { onCategoryChange(category.key) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = category.name,
                        style = FmType.labelMedium.copy(
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        ),
                        color = if (active) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(FmRadii.pill))
                            .background(if (active) FmPalette.onSurface else FmPalette.surface),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .semantics {
                    contentDescription = "搜索边框"
                    role = Role.Button
                }
                .clickable(onClick = onSearch),
            contentAlignment = Alignment.Center,
        ) {
            PhoneSearchGlyph(tint = FmPalette.onSurfaceMuted)
        }
    }
}

/**
 * Group-jump chips (showcase `.panchors` + `.chip.touch.on`): 30 dp pills, 13 px,
 * `--surface` with a hairline when idle and `--accent-soft` + accent-strong ink when
 * the matching group is the one the body is showing.
 */
@Composable
private fun AnchorRow(
    anchors: List<PhoneLibraryAnchor>,
    height: Dp,
    showSeries: Boolean,
    onJump: (PhoneLibraryAnchor) -> Unit,
    onOpenSeries: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(FmPalette.surface)
            .padding(start = 18.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            anchors.forEach { anchor ->
                // AU4-05 (WP-X3 follow-up): the chip keeps the band's height. A taller
                // hit box was measured NOT to receive touches outside the band (the band
                // gates the pointer), so it would only misreport to a11y.
                // See reports\WP-X3-REPORT.md §4.4.
                Box(
                    modifier = Modifier
                        .height(height)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${anchor.label}，${anchor.count} 个"
                            role = Role.Button
                        }
                        .clickable { onJump(anchor) },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(FmRadii.pill))
                            .background(FmPalette.surfaceMuted)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = anchor.label,
                            style = FmType.labelSmall.copy(fontSize = 13.sp),
                            color = FmPalette.onSurfaceSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = anchor.count.toString(),
                            style = FmType.labelSmall.copy(fontSize = 11.sp),
                            color = FmPalette.onSurfaceMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (showSeries) {
            Box(
                modifier = Modifier
                    .height(height)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .semantics {
                        contentDescription = "系列目录"
                        role = Role.Button
                    }
                    .clickable(onClick = onOpenSeries)
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "系列",
                    style = FmType.labelSmall.copy(fontSize = 13.sp),
                    color = FmPalette.onSurfaceSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PhoneLibrarySearchRow(
    query: String,
    height: Dp,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(FmPalette.surface)
            .padding(start = FmSpacing.md, end = FmSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(30.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.surfaceMuted)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                Text(
                    text = "搜索名称、厂商或系列",
                    style = FmType.labelSmall.copy(fontSize = 12.sp),
                    color = FmPalette.onSurfaceFaint,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = FmType.labelSmall.copy(fontSize = 12.sp, color = FmPalette.onSurface),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索边框" },
            )
        }
        Text(
            text = "关闭",
            style = FmType.labelSmall.copy(fontSize = 12.sp),
            color = FmPalette.primaryStrong,
            maxLines = 1,
            modifier = Modifier
                .heightIn(min = height)
                .clip(RoundedCornerShape(FmRadii.pill))
                .clickable(onClick = onClose)
                .padding(horizontal = 10.dp),
        )
    }
}

