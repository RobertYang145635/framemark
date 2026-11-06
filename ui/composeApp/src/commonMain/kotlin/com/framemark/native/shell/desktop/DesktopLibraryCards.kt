package com.framemark.native.shell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryPreview
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType

/** V2 卡片边长（与手机底板一致的 128 dp 方形，边框完整显示在深灰底上）。 */
internal val DesktopCardSize = 128.dp

/** 卡片名去掉分组标题里重复的厂商 / 系列前缀（报告 §6：`理光 GR · 上框` 只写 `上框`）。 */
internal fun desktopCardLabel(card: LibraryCard, groupLabel: String): String {
    val prefix = groupLabel.substringBefore('·').trim()
    var name = card.name.trim()
    if (prefix.isNotBlank() && name.startsWith(prefix)) {
        name = name.removePrefix(prefix).trim().removePrefix("·").trim()
    }
    return name.ifBlank { card.name }
}

/** 卡片上的例外标记：只显示 `热门` / `原版` / `官方规格`，满屏的“近似”不再出现。 */
internal fun desktopCardBadge(card: LibraryCard): String? {
    card.badge?.takeIf { it.isNotBlank() }?.let { return it }
    return card.fidelity?.takeIf { it == "原版" || it == "官方规格" }
}

/**
 * One V2 library card on the deep stage grey. Hover is reported upward only after
 * the 0.4 s gate in [DesktopLibraryPanel]; the pointer handler here just says
 * “entered / left”, so a fast sweep across 20 cards never fires a preview.
 */
@Composable
internal fun DesktopLibraryCard(
    card: LibraryCard,
    label: String,
    groupLabel: String,
    selected: Boolean,
    inUse: Boolean,
    onClick: () -> Unit,
    onHoverChange: (Boolean) -> Unit,
) {
    val noPreview = card.previewFile.isBlank()
    val shape = RoundedCornerShape(FmRadii.tile)
    Column(
        modifier = Modifier
            .width(DesktopCardSize)
            .clip(shape)
            .background(FmPalette.surfaceMuted)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) FmPalette.primary else FmPalette.outline,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .pointerInput(card.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> onHoverChange(true)
                            PointerEventType.Exit -> onHoverChange(false)
                        }
                    }
                }
            }
            .semantics {
                contentDescription = "边框：${card.name}"
                role = Role.Button
                this.selected = selected
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(DesktopCardSize - 42.dp)
                .background(FmPalette.stage),
        ) {
            LibraryPreview(
                card = card,
                modifier = Modifier.fillMaxSize().padding(6.dp),
            )
            val badge = desktopCardBadge(card)
            if (badge != null) {
                Text(
                    text = badge,
                    style = FmType.labelSmall.copy(fontSize = 9.sp),
                    color = FmPalette.onPrimary,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(4.dp)
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.primary.copy(alpha = 0.88f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (inUse) {
                Text(
                    text = "在用",
                    style = FmType.labelSmall.copy(fontSize = 9.sp),
                    color = FmPalette.onSurface,
                    maxLines = 1,
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.surface.copy(alpha = 0.92f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (noPreview) {
                Text(
                    text = "暂无预览",
                    style = FmType.labelSmall.copy(fontSize = 9.sp),
                    color = FmPalette.stageInk,
                    maxLines = 1,
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.scrim.copy(alpha = 0.55f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = desktopCardLabel(card, groupLabel),
                style = FmType.labelSmall.copy(fontSize = 11.sp),
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Text(
            // V2: “类别 · 来源”副标题（01-desktop.png）。来源只取数据字段。
            text = "${card.categoryName.removeSuffix("边框").ifBlank { card.category }} · " +
                (card.fidelity ?: card.badge ?: "内置"),
            style = FmType.labelSmall.copy(fontSize = 9.sp),
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(bottom = 3.dp),
        )
    }
}
