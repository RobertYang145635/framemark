package com.framemark.native.shell

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryCategory
import com.framemark.native.library.LibraryLoadState
import com.framemark.native.library.LibraryPreview
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType

/** Chip row: a 36 dp pill inside a 48 dp tap band. */
private val ChipRowHeight = 48.dp
private val ChipHeight = 36.dp

/** Style tile: 116 x 96 preview + one name line. */
private val TileWidth = 116.dp
private val TilePreviewHeight = 96.dp

private fun friendlyVendorLabel(label: String): String =
    if (label == "未在固件中证实") "其他模板" else label

/**
 * V06 phone border browser, rendered inside the parameter panel (no full-screen
 * sheet, no search): category → vendor (when there is a choice) → series (when
 * there is a choice) → style tiles. Tapping a style applies it and keeps the
 * browser open, so styles can be compared in place.
 *
 * The four levels come from [buildPhoneBorderBrowser], which defers to
 * `LibraryViewBuilder`/`LibraryGroups`; nothing here invents a brand, family or
 * count. Every card of the snapshot is reachable through 全部 → its vendor group
 * → its series row.
 */
@Composable
fun PhoneBorderBrowser(
    loadState: LibraryLoadState,
    selectedCardId: String?,
    onOpenCard: (LibraryCard) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (loadState) {
        LibraryLoadState.Loading -> BrowserNote(text = "正在载入边框库…", modifier = modifier)
        is LibraryLoadState.Failed -> Column(
            modifier = modifier.fillMaxSize().padding(FmSpacing.md),
            verticalArrangement = Arrangement.Center,
        ) {
            BrowserNote(text = "暂时无法载入边框库。", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(FmSpacing.sm))
            Text(
                text = "重试",
                style = FmType.labelMedium,
                color = FmPalette.primaryStrong,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.primarySoft)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = FmSpacing.md, vertical = 10.dp),
            )
        }
        is LibraryLoadState.Ready -> PhoneBorderBrowserBody(
            cards = loadState.snapshot.cards,
            categories = loadState.snapshot.categories,
            selectedCardId = selectedCardId,
            onOpenCard = onOpenCard,
            modifier = modifier,
        )
    }
}

@Composable
private fun PhoneBorderBrowserBody(
    cards: List<LibraryCard>,
    categories: List<LibraryCategory>,
    selectedCardId: String?,
    onOpenCard: (LibraryCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One opening position per (re)entry: the applied card's own category/vendor/
    // series. Browsing inside the panel is never reset by an applied style, so the
    // user keeps comparing the row they are in.
    var selection by remember(cards.size) {
        mutableStateOf(phoneBorderDefaultSelection(cards, selectedCardId))
    }
    val state = remember(cards, categories, selection) {
        buildPhoneBorderBrowser(cards, categories, selection)
    }
    if (cards.isEmpty()) {
        BrowserNote("边框库为空，请重新载入。", modifier)
        return
    }
    // Border panels get 300–320 dp. On a shorter window or a larger font the panel
    // scrolls instead of shrinking rows/tiles, so nothing is compressed away.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = FmSpacing.md, vertical = FmSpacing.xs),
    ) {
        BrowserChipRow(label = "分类") {
            state.categories.forEach { option ->
                BrowserChip(
                    label = option.label,
                    count = option.count,
                    active = state.selection.categoryKey == option.key,
                    onClick = {
                        selection = selection.copy(
                            categoryKey = option.key,
                            vendorKey = null,
                            familyKey = null,
                        )
                    },
                )
            }
        }
        if (state.vendors.size > 1) {
            BrowserChipRow(label = "厂商") {
                state.vendors.forEach { vendor ->
                    BrowserChip(
                        label = friendlyVendorLabel(vendor.label),
                        count = vendor.count,
                        active = state.selection.vendorKey == vendor.key,
                        onClick = {
                            selection = selection.copy(vendorKey = vendor.key, familyKey = null)
                        },
                    )
                }
            }
        }
        if (state.families.size > 1) {
            BrowserChipRow(label = "系列") {
                state.families.forEach { family ->
                    BrowserChip(
                        label = family.label,
                        count = family.cards.size,
                        active = state.selection.familyKey == family.key,
                        onClick = { selection = selection.copy(familyKey = family.key) },
                    )
                }
            }
        }
        val family = state.activeFamily
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = family?.label ?: "边框",
                style = FmType.labelMedium,
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = " · ${state.cards.size} 款 · 左右滑动",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
            )
        }
        family?.note?.takeIf { it.isNotBlank() }?.let { note ->
            Text(
                text = note,
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
        StyleRow(
            cards = state.cards,
            selectedCardId = selectedCardId,
            onOpenCard = onOpenCard,
        )
    }
}

@Composable
private fun BrowserNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = FmType.bodyMedium,
        color = FmPalette.onSurfaceMuted,
        modifier = modifier.padding(FmSpacing.md),
    )
}

@Composable
private fun BrowserChipRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ChipRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = FmType.labelMedium,
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
            // 30 dp minimum, but let the label take the width a 1.5x font needs.
            modifier = Modifier.widthIn(min = 30.dp),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

@Composable
private fun BrowserChip(
    label: String,
    count: Int,
    active: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(FmRadii.pill)
    Box(
        modifier = Modifier
            .height(ChipRowHeight)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.RadioButton
                this.selected = active
                stateDescription = if (active) "已选中" else "未选中"
            }
            .clickable(onClick = onClick)
            // 6 dp above and below the 36 dp pill: a 48 dp tap band.
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .height(ChipHeight)
                .clip(shape)
                .background(if (active) FmPalette.primarySoft else FmPalette.surface)
                .border(
                    width = 1.dp,
                    color = if (active) FmPalette.primary.copy(alpha = 0.45f) else FmPalette.outline,
                    shape = shape,
                )
                .padding(horizontal = FmSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = FmType.labelMedium,
                color = if (active) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
                maxLines = 1,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = count.toString(),
                style = FmType.labelSmall,
                color = if (active) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun StyleRow(
    cards: List<LibraryCard>,
    selectedCardId: String?,
    onOpenCard: (LibraryCard) -> Unit,
) {
    val rowState = rememberLazyListState()
    // Keep the applied style in view without moving the supervision of the row:
    // only the index is scrolled, the user's horizontal position is respected by
    // animateScrollToItem's minimal scroll.
    LaunchedEffect(cards, selectedCardId) {
        val index = cards.indexOfFirst { it.id == selectedCardId }
        if (index >= 0) rowState.animateScrollToItem(index)
    }
    LazyRow(
        state = rowState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
    ) {
        items(cards.size) { index ->
            val card = cards[index]
            StyleTile(
                card = card,
                current = card.id == selectedCardId,
                onClick = { onOpenCard(card) },
            )
        }
    }
}

@Composable
private fun StyleTile(card: LibraryCard, current: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FmRadii.tile)
    Column(
        modifier = Modifier
            .width(TileWidth)
            .clip(shape)
            .background(if (current) FmPalette.primarySoft else FmPalette.surface)
            .border(
                width = if (current) 2.dp else 1.dp,
                color = if (current) FmPalette.primary else FmPalette.outline,
                shape = shape,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "${card.name}，${card.categoryName}"
                role = Role.RadioButton
                this.selected = current
                stateDescription = if (current) "当前边框" else "未选中"
            }
            .clickable(onClick = onClick)
            .padding(FmSpacing.xs),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TilePreviewHeight)
                .clip(RoundedCornerShape(FmRadii.chip)),
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
                        .padding(4.dp)
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.surface.copy(alpha = 0.9f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = card.name,
            style = FmType.labelMedium,
            color = if (current) FmPalette.primaryStrong else FmPalette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        )
    }
}
