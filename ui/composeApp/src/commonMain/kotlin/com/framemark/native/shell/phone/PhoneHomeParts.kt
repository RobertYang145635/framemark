package com.framemark.native.shell.phone

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.PhotoThumbnail

/**
 * 首页 (report §7.1, demo `03-phone-home.png`).
 *
 * One big card goes straight to the photo picker, 批量 / 预设 are two tiles, the
 * 最近编辑 card lists the project's own photos, and the gear in the corner opens
 * 设置. Every colour comes from [FmPalette] (hand-off §3 decision 1), so both
 * appearances are one token table apart.
 */

/** The gear that opens 设置 (demo `.htop .ibtn`). */
@Composable
fun PhoneHomeGear(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .semantics {
                contentDescription = "设置"
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Read the token outside the draw scope: FmPalette members are composable.
        val ink = FmPalette.onSurfaceSecondary
        Canvas(modifier = Modifier.size(22.dp)) {
            val w = size.width
            val tint = ink
            val stroke = w * 0.085f
            val teeth = 8
            val outer = w * 0.46f
            val inner = w * 0.3f
            repeat(teeth) { index ->
                val angle = (index * (360f / teeth)) * (kotlin.math.PI.toFloat() / 180f)
                val dx = kotlin.math.cos(angle)
                val dy = kotlin.math.sin(angle)
                drawLine(
                    color = tint,
                    start = Offset(w / 2f + dx * inner, w / 2f + dy * inner),
                    end = Offset(w / 2f + dx * outer, w / 2f + dy * outer),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(
                color = tint,
                radius = w * 0.26f,
                center = Offset(w / 2f, w / 2f),
                style = Stroke(width = stroke),
            )
        }
    }
}

/** 首页 tile glyph: 批量 (grid) / 预设 (star) / 相册 / 相机. */
internal enum class PhoneHomeIcon { Grid, Star, Album, Camera, Chevron }

@Composable
internal fun PhoneHomeGlyph(icon: PhoneHomeIcon, tint: Color, size: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = size.toPx()
        val stroke = w * 0.075f
        when (icon) {
            PhoneHomeIcon.Grid -> {
                val cell = w * 0.34f
                val gap = w * 0.12f
                listOf(0f, cell + gap).forEach { y ->
                    listOf(0f, cell + gap).forEach { x ->
                        drawRoundRect(
                            color = tint,
                            topLeft = Offset(w * 0.09f + x, w * 0.09f + y),
                            size = Size(cell, cell),
                            cornerRadius = CornerRadius(w * 0.07f),
                            style = Stroke(width = stroke),
                        )
                    }
                }
            }
            PhoneHomeIcon.Star -> {
                val path = Path()
                val cx = w / 2f
                val cy = w / 2f
                val outer = w * 0.45f
                val innerR = w * 0.19f
                repeat(10) { index ->
                    val radius = if (index % 2 == 0) outer else innerR
                    val angle = (-90f + index * 36f) * (kotlin.math.PI.toFloat() / 180f)
                    val px = cx + kotlin.math.cos(angle) * radius
                    val py = cy + kotlin.math.sin(angle) * radius
                    if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                drawPath(path, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
            }
            PhoneHomeIcon.Album -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.1f, w * 0.16f),
                    size = Size(w * 0.8f, w * 0.68f),
                    cornerRadius = CornerRadius(w * 0.12f),
                    style = Stroke(width = stroke),
                )
                drawCircle(tint, radius = w * 0.08f, center = Offset(w * 0.33f, w * 0.36f))
                val slope = Path().apply {
                    moveTo(w * 0.16f, w * 0.74f)
                    lineTo(w * 0.4f, w * 0.48f)
                    lineTo(w * 0.56f, w * 0.64f)
                    lineTo(w * 0.68f, w * 0.55f)
                    lineTo(w * 0.84f, w * 0.74f)
                }
                drawPath(slope, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
            }
            PhoneHomeIcon.Camera -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.08f, w * 0.28f),
                    size = Size(w * 0.84f, w * 0.5f),
                    cornerRadius = CornerRadius(w * 0.12f),
                    style = Stroke(width = stroke),
                )
                drawCircle(tint, radius = w * 0.16f, center = Offset(w * 0.5f, w * 0.54f), style = Stroke(stroke))
                drawLine(
                    tint,
                    Offset(w * 0.33f, w * 0.28f),
                    Offset(w * 0.33f, w * 0.2f),
                    stroke,
                    StrokeCap.Round,
                )
                drawLine(
                    tint,
                    Offset(w * 0.33f, w * 0.2f),
                    Offset(w * 0.62f, w * 0.2f),
                    stroke,
                    StrokeCap.Round,
                )
            }
            PhoneHomeIcon.Chevron -> {
                val path = Path().apply {
                    moveTo(w * 0.4f, w * 0.26f)
                    lineTo(w * 0.66f, w * 0.5f)
                    lineTo(w * 0.4f, w * 0.74f)
                }
                drawPath(
                    path,
                    color = tint,
                    style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

/** One of the two 首页 tiles (demo `.htile`). */
@Composable
internal fun PhoneHomeTile(
    icon: PhoneHomeIcon,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(FmRadii.card))
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.card))
            .semantics {
                contentDescription = "$title，$subtitle"
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .padding(FmSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(FmSpacing.sm),
    ) {
        PhoneHomeGlyph(icon = icon, tint = FmPalette.primaryStrong, size = 26.dp)
        Text(
            text = title,
            style = FmType.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            color = FmPalette.onSurface,
        )
        Text(
            text = subtitle,
            style = FmType.labelSmall.copy(fontSize = 12.sp),
            color = FmPalette.onSurfaceMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The 最近编辑 card: one row per project photo, newest first, capped at
 * [PhoneHomePolicy.RECENT_LIMIT]; rows open that photo's editor.
 */
@Composable
internal fun PhoneHomeRecentCard(
    entries: List<PhoneRecentEdit>,
    total: Int,
    onOpen: (PhoneRecentEdit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.card))
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.card))
            .padding(FmSpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "最近编辑",
                style = FmType.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = FmPalette.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (total > entries.size) "全部 $total" else "$total 张",
                style = FmType.labelSmall.copy(fontSize = 12.sp),
                color = FmPalette.primaryStrong,
            )
        }
        entries.forEach { entry ->
            Spacer(Modifier.height(FmSpacing.sm))
            PhoneHomeRecentRow(entry = entry, onClick = { onOpen(entry) })
        }
    }
}

@Composable
private fun PhoneHomeRecentRow(entry: PhoneRecentEdit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(FmRadii.input))
            .semantics {
                contentDescription = "打开 ${entry.displayName}"
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .padding(vertical = FmSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 44.dp)
                .clip(RoundedCornerShape(FmRadii.tile))
                .background(FmPalette.surfaceMuted),
        ) {
            PhotoThumbnail(
                path = entry.path,
                maxEdge = 160,
                cornerRadius = FmRadii.tile,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(FmSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = FmType.bodyMedium.copy(fontSize = 13.sp),
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.subtitle(),
                style = FmType.labelSmall.copy(fontSize = 11.5.sp),
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PhoneHomeGlyph(icon = PhoneHomeIcon.Chevron, tint = FmPalette.onSurfaceFaint, size = 18.dp)
    }
}

private fun PhoneHomeRecentEditSubtitle(entry: PhoneRecentEdit): String = entry.subtitle()

/**
 * The 选照片 hero card (demo `.hcard.hero`): a fanned stack of the newest photos
 * over the copy and the primary 选照片 button.
 *
 * [previewPaths] holds up to three real photo paths (the project's newest ones);
 * a null/blank entry falls back to the bundled sample, so an empty project still
 * renders the three cards the demo shows.
 */
@Composable
internal fun PhoneHomeHero(
    previewPaths: List<String?>,
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val paths = List(3) { index -> previewPaths.getOrNull(index) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.card))
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.card))
            .padding(FmSpacing.lg),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Left and right cards lean away behind the front one.
            HeroPhotoCard(
                path = paths[1],
                width = 92.dp,
                rotation = -10f,
                offsetX = -64.dp,
                modifier = Modifier.align(Alignment.Center),
            )
            HeroPhotoCard(
                path = paths[2],
                width = 88.dp,
                rotation = 9f,
                offsetX = 62.dp,
                modifier = Modifier.align(Alignment.Center),
            )
            HeroPhotoCard(
                path = paths[0],
                width = 156.dp,
                rotation = 0f,
                offsetX = 0.dp,
                framed = true,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Spacer(Modifier.height(FmSpacing.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "从相册选择照片",
                    style = FmType.titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    color = FmPalette.onSurface,
                )
                Spacer(Modifier.height(FmSpacing.xxs))
                Text(
                    text = "可多选，每张照片单独保存设计",
                    style = FmType.labelSmall.copy(fontSize = 12.sp),
                    color = FmPalette.onSurfaceMuted,
                )
            }
            Spacer(Modifier.width(FmSpacing.sm))
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.primary)
                    .semantics {
                        contentDescription = "选照片"
                        role = Role.Button
                    }
                    .clickable(onClick = onPickPhoto)
                    .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhoneHomeGlyph(icon = PhoneHomeIcon.Album, tint = FmPalette.onPrimary, size = 18.dp)
                Spacer(Modifier.width(FmSpacing.sm))
                Text(
                    text = "选照片",
                    style = FmType.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = FmPalette.onPrimary,
                )
            }
        }
    }
}

/** One card of the hero fan; the front card gets the white mount a border implies. */
@Composable
private fun HeroPhotoCard(
    path: String?,
    width: Dp,
    rotation: Float,
    offsetX: Dp,
    modifier: Modifier = Modifier,
    framed: Boolean = false,
) {
    Box(
        modifier = modifier
            .offset(x = offsetX)
            .rotate(rotation)
            .width(width)
            .height(width * 0.72f)
            .clip(RoundedCornerShape(FmRadii.tile))
            .background(FmPalette.surfaceMuted)
            .then(if (framed) Modifier.border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.tile)) else Modifier),
    ) {
        PhotoThumbnail(
            path = path,
            maxEdge = if (framed) 480 else 256,
            cornerRadius = FmRadii.tile,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

