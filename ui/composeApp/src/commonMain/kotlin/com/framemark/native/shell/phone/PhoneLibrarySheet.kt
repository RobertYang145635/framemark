package com.framemark.native.shell.phone

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryIndex
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import kotlinx.coroutines.launch

// C-07: 48 dp is the minimum touch target; the visuals stay inside it.
private val CategoryRowHeight = 48.dp
private val AnchorRowHeight = 48.dp

/**
 * V2 phone border sheet body (report §7.5): 一行类别 → 一行分组跳转 → 卡片.
 *
 * Everything comes from WP-L's [LibraryIndex] ([phoneLibraryView]); this composable
 * only lays it out. The 收起 level is the horizontal strip with 128 dp square
 * cards, the 展开 level is the 3-column grid with sticky group titles; the 厂商
 * category gets the 系列 directory and a 系列 chip on the group header.
 */
@Composable
fun PhoneLibrarySheet(
    index: LibraryIndex?,
    selectedCardId: String?,
    expanded: Boolean,
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
    )

    fun jumpTo(groupKey: String) {
        val target = if (expanded) {
            phoneGridJumpIndex(view.groups, groupKey)
        } else {
            phoneStripJumpIndex(view.groups, groupKey)
        }
        if (target <= 0) return
        scope.launch { bodyState.animateScrollToItem(target) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (searchOpen) {
            PhoneLibrarySearchRow(
                query = query,
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
                onCategoryChange = onCategoryChange,
                onSearch = { searchOpen = true },
            )
        }
        if (view.anchors.isNotEmpty()) {
            AnchorRow(
                anchors = view.anchors,
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
                    onPick = onPick,
                    onOpenSeries = if (categoryKey == LibraryIndex.CATEGORY_VENDOR) {
                        { seriesOpen = true }
                    } else {
                        null
                    },
                )
                else -> PhoneBorderStrip(
                    groups = view.groups,
                    selectedCardId = selectedCardId,
                    state = stripState,
                    onPick = onPick,
                    onJumpGroup = { key ->
                        val indexOfGroup = view.anchors.firstOrNull { it.groupKey == key }
                        if (indexOfGroup != null) jumpTo(key)
                    },
                )
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

@Composable
private fun CategoryTabs(
    view: PhoneLibraryView,
    categoryKey: String,
    onCategoryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CategoryRowHeight)
            .background(FmPalette.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(start = FmSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(FmSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            view.categories.forEach { category ->
                val active = category.key == categoryKey
                Column(
                    modifier = Modifier
                        .height(CategoryRowHeight)
                        .widthIn(min = 48.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${category.name}，${category.count} 个"
                            role = Role.Tab
                        }
                        .clickable { onCategoryChange(category.key) }
                        .padding(horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = category.name,
                        style = FmType.labelMedium.copy(
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        ),
                        color = if (active) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                        maxLines = 1,
                    )
                    Box(modifier = Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .width(20.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(FmRadii.pill))
                            .background(if (active) FmPalette.onSurface else FmPalette.surface),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .size(38.dp)
                .semantics {
                    contentDescription = "搜索边框"
                    role = Role.Button
                }
                .clickable(onClick = onSearch),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "搜索",
                style = FmType.labelSmall.copy(fontSize = 12.sp),
                color = FmPalette.primaryStrong,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AnchorRow(
    anchors: List<PhoneLibraryAnchor>,
    showSeries: Boolean,
    onJump: (PhoneLibraryAnchor) -> Unit,
    onOpenSeries: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AnchorRowHeight)
            .background(FmPalette.surface)
            .padding(start = FmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            anchors.forEach { anchor ->
                // 48 dp tap band around a 30 dp pill (C-07), same as the V06 chips.
                Box(
                    modifier = Modifier
                        .height(AnchorRowHeight)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${anchor.label}，${anchor.count} 个"
                            role = Role.Button
                        }
                        .clickable { onJump(anchor) },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 30.dp)
                            .clip(RoundedCornerShape(FmRadii.pill))
                            .background(FmPalette.surfaceMuted)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = anchor.label,
                            style = FmType.labelSmall.copy(fontSize = 12.sp),
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
            Text(
                text = "系列",
                style = FmType.labelSmall.copy(fontSize = 12.sp),
                color = FmPalette.onSurfaceSecondary,
                maxLines = 1,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .semantics {
                        contentDescription = "系列目录"
                        role = Role.Button
                    }
                    .clickable(onClick = onOpenSeries)
                    .padding(horizontal = 10.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun PhoneLibrarySearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CategoryRowHeight)
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
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .clickable(onClick = onClose)
                .padding(horizontal = 10.dp, vertical = 12.dp),
        )
    }
}
