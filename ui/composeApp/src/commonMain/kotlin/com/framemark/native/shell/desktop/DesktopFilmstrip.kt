package com.framemark.native.shell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.framemark.native.product.PhotoRef
import com.framemark.native.theme.FmDesktop
import com.framemark.native.theme.FmNumeric
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmType
import com.framemark.native.theme.columnRule
import com.framemark.native.workflow.PhotoThumbnail
import com.framemark.native.workflow.ProjectHolder

/** 胶片条右侧信息里的「批量导出」链接（`.strip .sinfo` 里那个 `link`）。 */
@Composable
fun DesktopFilmstrip(
    projects: ProjectHolder,
    onAddPhoto: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    onOpenBatch: () -> Unit,
) {
    val photos = projects.photos
    val activeId = projects.activePhotoId
    val designs = projects.project.designs
    val selected = photos.count { designs[it.id]?.cardId != null }
    Column(modifier = Modifier.fillMaxWidth().height(FmDesktop.filmstripHeight)) {
        // `.strip { border-top: 1px solid var(--line) }` —— 规则 1：栏级发线，贴边通栏。
        Box(Modifier.columnRule())
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState())
                    // `.frames { padding: 8px 2px }` 叠在 `.strip { padding: 0 16px }` 上。
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                photos.forEachIndexed { index, photo ->
                    FilmstripFrame(
                        photo = photo,
                        index = index,
                        active = photo.id == activeId,
                        hasBorder = designs[photo.id]?.cardId != null,
                        onClick = { projects.selectPhoto(photo.id) },
                        onRemove = { onRemovePhoto(photo.id) },
                    )
                }
                AddFrame(onAddPhoto = onAddPhoto)
            }
            Spacer(Modifier.width(10.dp))
            StripInfo(
                selected = selected,
                total = photos.size,
                onOpenBatch = onOpenBatch,
            )
        }
    }
}

/**
 * 一帧（`styles.css` 420-427 `.frame`）：缩略图高 56、圆角 6、`0 0 0 1px hairline`；
 * 下面一行 10.5 px 编号 + 6 px 状态点（实心绿=已选边框，空心=未选）。
 * 选中：外环 `2px accent + 4.5px ring`，编号变 `accent-strong`。
 */
@Composable
private fun FilmstripFrame(
    photo: PhotoRef,
    index: Int,
    active: Boolean,
    hasBorder: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val width = frameWidth(photo)
    val (source, hovered) = rememberDeskHover()
    val accent = FmPalette.primary
    val ring = FmPalette.ring
    Column(
        modifier = Modifier
            .width(width)
            .clip(RoundedCornerShape(6.dp))
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(FmDesktop.filmstripFrameHeight)
                .drawBehind {
                    // `.frame.on .fimg { box-shadow: 0 0 0 2px accent, 0 0 0 4.5px ring }`
                    if (active) {
                        val inset = 2.dp.toPx()
                        drawRoundRect(
                            color = accent,
                            topLeft = Offset(inset / 2f, inset / 2f),
                            size = Size(size.width - inset, size.height - inset),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                            style = Stroke(width = inset),
                        )
                        drawRoundRect(
                            color = ring,
                            topLeft = Offset(-2.5.dp.toPx(), -2.5.dp.toPx()),
                            size = Size(size.width + 5.dp.toPx(), size.height + 5.dp.toPx()),
                            cornerRadius = CornerRadius(8.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(FmPalette.surfaceMuted)
                    .border(
                        width = 1.dp,
                        color = if (active) Color.Transparent else FmPalette.outline,
                        shape = shape,
                    ),
            ) {
                PhotoThumbnail(
                    path = photo.path,
                    modifier = Modifier.fillMaxSize(),
                    maxEdge = 192,
                    cornerRadius = 6.dp,
                )
                if (hovered && !active) {
                    Box(Modifier.fillMaxSize().background(FmPalette.hover))
                }
                // 缩略图右上角的"移除"只在悬停时出现，演示稿没有这个按钮但功能不能丢。
                if (hovered) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(FmPalette.scrim)
                            .clickable(onClick = onRemove),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("×", style = FmType.desktopFootnote, color = FmPalette.onPrimary)
                    }
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = photoLabel(photo, index),
                style = FmNumeric.frameNumber.copy(
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (active) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            StatusDot(hasBorder = hasBorder)
        }
    }
}

/** `.frame .fl i`：6 px，实心 `--ok` 或 `inset 1.2px ink-4` 的空心圈。 */
@Composable
private fun StatusDot(hasBorder: Boolean) {
    if (hasBorder) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(FmPalette.success))
    } else {
        Box(Modifier.size(6.dp).clip(CircleShape).border(1.2.dp, FmPalette.onSurfaceFaint, CircleShape))
    }
}

/** `.frame.add`：56×56、圆角 6、`muted-bg` 底 + `line-2` 内描边，下面是「添加」。 */
@Composable
private fun AddFrame(onAddPhoto: () -> Unit) {
    val (source, hovered) = rememberDeskHover()
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = Modifier
            .width(FmDesktop.filmstripAddSize)
            .clip(RoundedCornerShape(6.dp))
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onAddPhoto),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(FmDesktop.filmstripAddSize)
                .clip(shape)
                .background(FmPalette.surfaceMuted)
                .border(1.dp, FmPalette.outlineStrong, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "＋",
                style = FmType.desktopTab,
                color = if (hovered) FmPalette.primary else FmPalette.onSurfaceMuted,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = "添加",
            style = FmNumeric.frameNumber,
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * `.strip .sinfo`：右对齐两行 12 px `ink-3`。
 * 第一行「N / M 张已选边框」（数字 600 `ink`），第二行「每张照片单独保存设计 · 批量导出」。
 */
@Composable
private fun StripInfo(selected: Int, total: Int, onOpenBatch: () -> Unit) {
    val (source, hovered) = rememberDeskHover()
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$selected / $total",
                style = FmNumeric.value.copy(fontWeight = FontWeight.SemiBold),
                color = FmPalette.onSurface,
                maxLines = 1,
            )
            Text(
                text = " 张已选边框",
                style = FmType.desktopCaption,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "每张照片单独保存设计 · ",
                style = FmType.desktopCaption,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
            )
            Text(
                text = "批量导出",
                style = FmType.desktopChip.copy(fontWeight = FontWeight.Medium),
                color = if (hovered) FmPalette.primaryStrong else FmPalette.primary,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(FmRadii.chip))
                    .hoverable(source)
                    .clickable(interactionSource = source, indication = null, onClick = onOpenBatch)
                    .semantics {
                        contentDescription = "批量导出"
                        role = Role.Button
                    },
            )
        }
    }
}

/** 缩略图宽度：按照片宽高比撑开，未知尺寸时用正方形（`.fimg img { height: 100%; width: auto }`）。 */
private fun frameWidth(photo: PhotoRef) = when {
    photo.widthPx > 0 && photo.heightPx > 0 -> {
        val raw = FmDesktop.filmstripFrameHeight.value * photo.widthPx.toFloat() / photo.heightPx
        raw.coerceIn(28f, 112f).dp
    }
    else -> FmDesktop.filmstripFrameHeight
}

/** 编号：演示稿用文件名去掉扩展名（`P1070512`）。 */
private fun photoLabel(photo: PhotoRef, index: Int): String {
    val base = photo.displayName.substringBeforeLast('.', photo.displayName)
    return base.ifBlank { "#${index + 1}" }
}
