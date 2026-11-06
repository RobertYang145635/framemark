package com.framemark.native.shell.phone

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryCardGroup
import com.framemark.native.shell.PhoneCompactSheetMetrics
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType

/** Columns of the expanded grid (report §7.5) and the strip group tile width. */
const val PHONE_LIBRARY_COLUMNS = 3
private val GroupTileWidth = 78.dp

private val GroupTitleStyle = FmType.labelMedium.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
private val GroupCountStyle = FmType.labelSmall.copy(fontSize = 11.sp)

/** Items a group occupies in the strip body: the group tile plus one item per card. */
fun phoneStripItems(group: LibraryCardGroup): Int = 1 + group.count

/** Items a group occupies in the 3-column grid: the header plus one row per 3 cards. */
fun phoneGridItems(group: LibraryCardGroup): Int =
    1 + (group.count + PHONE_LIBRARY_COLUMNS - 1) / PHONE_LIBRARY_COLUMNS

/** What a jump chip scrolls the strip to: the group's tile index. */
fun phoneStripJumpIndex(groups: List<LibraryCardGroup>, groupKey: String?): Int =
    phoneLibraryItemIndex(groups, groupKey, ::phoneStripItems)

/** Same for the expanded grid. */
fun phoneGridJumpIndex(groups: List<LibraryCardGroup>, groupKey: String?): Int =
    phoneLibraryItemIndex(groups, groupKey, ::phoneGridItems)

/**
 * 收起: one continuous horizontal strip — a group tile, then that group's cards, so
 * the compact sheet keeps the "分组 + 卡片" reading in one row.
 *
 * The strip's height is [PhoneCompactSheetMetrics.stripHeight] (strip padding + the
 * 128 dp card + the name line + padding), not "whatever is left": that is what keeps
 * the name under each card whole instead of cutting it at the sheet's bottom edge
 * (WP-P1-2).
 */
@Composable
fun PhoneBorderStrip(
    groups: List<LibraryCardGroup>,
    selectedCardId: String?,
    state: LazyListState,
    metrics: PhoneCompactSheetMetrics,
    onPick: (LibraryCard) -> Unit,
    onJumpGroup: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        state = state,
        modifier = modifier.fillMaxWidth().height(metrics.stripHeight.dp),
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = metrics.stripTop.dp,
            bottom = metrics.stripBottom.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        groups.forEach { group ->
            item(key = "tile:${group.key}") {
                GroupTile(group = group, onClick = { onJumpGroup(group.key) })
            }
            items(group.cards, key = { card -> "card:${group.key}:${card.id}" }) { card ->
                PhoneBorderCard(
                    card = card,
                    selected = card.id == selectedCardId,
                    onClick = { onPick(card) },
                    width = PhoneFrameCardSize,
                    nameGap = metrics.cardNameGap.dp,
                    nameLineSp = metrics.cardNameLineSp,
                    descGap = metrics.cardDescGap.dp,
                )
            }
        }
    }
}

/**
 * 展开: 3 cards per row, sticky group titles and a group header that opens the
 * series directory. Each card is 1/3 of the sheet width (the demo's
 * `repeat(3, 1fr)`) with a square tile, so the whole frame stays visible.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhoneBorderGrid(
    groups: List<LibraryCardGroup>,
    selectedCardId: String?,
    state: LazyListState,
    onPick: (LibraryCard) -> Unit,
    onOpenSeries: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        groups.forEach { group ->
            stickyHeader(key = "head:${group.key}") {
                GroupHeader(group = group, onOpenSeries = onOpenSeries)
            }
            itemsIndexed(group.cards.chunked(PHONE_LIBRARY_COLUMNS), key = { index, _ -> "row:${group.key}:$index" }) { _, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { card ->
                        PhoneBorderCard(
                            card = card,
                            selected = card.id == selectedCardId,
                            onClick = { onPick(card) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keep the last row's tiles the same size as a full row.
                    repeat(PHONE_LIBRARY_COLUMNS - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Group tile of the 收起 strip (showcase `.pstrip .gtile`: 78 dp wide, as tall as a
 * card, `--muted-bg` fill with the 3 px accent bar on its left edge, the group name
 * in 12.5/700 and its count under it).
 */
@Composable
private fun GroupTile(group: LibraryCardGroup, onClick: () -> Unit) {
    val accent = FmPalette.primary
    Column(
        modifier = Modifier
            .width(GroupTileWidth)
            .height(PhoneFrameCardSize)
            .clip(RoundedCornerShape(FmRadii.tile))
            .background(FmPalette.surfaceMuted)
            // `.gtile`'s `box-shadow: inset 3px 0 0 var(--accent)`: a 3 dp accent bar on
            // the tile's left edge, full height.
            .drawBehind {
                drawRect(
                    color = accent,
                    topLeft = androidx.compose.ui.geometry.Offset.Zero,
                    size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
                )
            }
            .clickable(onClick = onClick)
            .padding(start = 11.dp, end = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = group.label,
            style = FmType.labelMedium.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
            color = FmPalette.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${group.count} 个",
            style = GroupCountStyle,
            color = FmPalette.onSurfaceFaint,
            maxLines = 1,
        )
    }
}

/** Sticky group title (showcase `.gsec-h`: 12 px / 2 px padding, 12.5/600 + 11 count). */
@Composable
private fun GroupHeader(group: LibraryCardGroup, onOpenSeries: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FmPalette.surface)
            .padding(start = 2.dp, end = 2.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.label,
            style = GroupTitleStyle,
            color = FmPalette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${group.count}",
            style = GroupCountStyle,
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
        )
        group.note?.takeIf { it.isNotBlank() }?.let { note ->
            Spacer(Modifier.width(6.dp))
            Text(
                text = note,
                style = GroupCountStyle,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (onOpenSeries != null) {
            Spacer(Modifier.width(FmSpacing.xs))
            Text(
                text = "系列 ›",
                style = GroupCountStyle,
                color = FmPalette.primaryStrong,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .clickable(onClick = onOpenSeries)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/** Scroll the body to the card that is currently applied (open, swipe, preset). */
@Composable
fun PhoneLibraryReveal(
    groups: List<LibraryCardGroup>,
    selectedCardId: String?,
    state: LazyListState,
    expanded: Boolean,
) {
    LaunchedEffect(groups, selectedCardId, expanded) {
        if (selectedCardId == null || groups.isEmpty()) return@LaunchedEffect
        val group = groups.firstOrNull { group -> group.cards.any { it.id == selectedCardId } }
            ?: return@LaunchedEffect
        val offset = group.cards.indexOfFirst { it.id == selectedCardId }.coerceAtLeast(0)
        val index = if (expanded) {
            phoneGridJumpIndex(groups, group.key) + offset / PHONE_LIBRARY_COLUMNS
        } else {
            phoneStripJumpIndex(groups, group.key) + 1 + offset
        }
        if (index >= 0) state.animateScrollToItem(index)
    }
}

/** Empty/loading/error note for the sheet body. */
@Composable
fun PhoneLibraryNote(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(FmSpacing.md),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = text, style = FmType.bodyMedium, color = FmPalette.onSurfaceMuted)
    }
}

