package com.framemark.native.shell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.product.PhotoRef
import com.framemark.native.theme.FmDesktop
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.PhotoThumbnail
import com.framemark.native.workflow.ProjectHolder

/**
 * V2 胶片条（报告 §6）：每张照片是否已选边框一眼可见，并明确提示
 * “每张照片单独保存设计”——切照片不会把当前设计带到别的照片。
 */
@Composable
fun DesktopFilmstrip(
    projects: ProjectHolder,
    onAddPhoto: () -> Unit,
    onRemovePhoto: (String) -> Unit,
) {
    val photos = projects.photos
    val activeId = projects.activePhotoId
    val designs = projects.project.designs
    Column(modifier = Modifier.fillMaxWidth().height(FmDesktop.filmstripHeight)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FmSpacing.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("胶片条", style = FmType.labelMedium, color = FmPalette.onSurface)
            Spacer(Modifier.width(FmSpacing.sm))
            Text(
                "${photos.size} 张 · 每张照片单独保存设计",
                style = FmType.labelSmall.copy(fontSize = 10.sp),
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "添加照片",
                style = FmType.labelSmall,
                color = FmPalette.primaryStrong,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .clickable(onClick = onAddPhoto)
                    .padding(horizontal = FmSpacing.sm, vertical = 2.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = FmSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            photos.forEach { photo ->
                FilmstripPhoto(
                    photo = photo,
                    active = photo.id == activeId,
                    cardName = designs[photo.id]?.cardId,
                    onClick = { projects.selectPhoto(photo.id) },
                    onRemove = { onRemovePhoto(photo.id) },
                )
            }
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(FmRadii.chip))
                    .background(FmPalette.surfaceMuted)
                    .border(1.dp, FmPalette.outlineStrong, RoundedCornerShape(FmRadii.chip))
                    .clickable(onClick = onAddPhoto),
                contentAlignment = Alignment.Center,
            ) {
                Text("＋", style = FmType.titleMedium, color = FmPalette.onSurfaceMuted)
            }
        }
    }
}

@Composable
private fun FilmstripPhoto(
    photo: PhotoRef,
    active: Boolean,
    cardName: String?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(FmRadii.chip)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(52.dp)
                .clip(shape)
                .border(if (active) 2.dp else 1.dp, if (active) FmPalette.primary else FmPalette.outline, shape)
                .clickable(onClick = onClick),
        ) {
            PhotoThumbnail(
                path = photo.path,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                maxEdge = 192,
                cornerRadius = FmRadii.chip,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(6.dp)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(if (cardName != null) FmPalette.success else FmPalette.warning),
            )
        }
        Text(
            text = photo.displayName,
            style = FmType.labelSmall.copy(fontSize = 9.sp),
            color = if (active) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = if (cardName != null) "已选边框" else "未选边框",
            style = FmType.labelSmall.copy(fontSize = 9.sp),
            color = if (cardName != null) FmPalette.onSurfaceMuted else FmPalette.onSurfaceFaint,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}
