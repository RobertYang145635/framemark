package com.framemark.native.workflow

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.framemark.native.inspector.FmSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryPreview
import com.framemark.native.library.LibraryPreviewLoader
import com.framemark.native.product.PhotoDesign
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

@Composable
private fun rememberPhotoBitmap(projects: ProjectHolder, path: String, maxEdge: Int): ImageBitmap? {
    val state by produceState<ImageBitmap?>(initialValue = null, path, maxEdge) {
        value = withContext(Dispatchers.Default) {
            projects.thumbnail(path, maxEdge)?.let { bytes ->
                runCatching { LibraryPreviewLoader.decode(bytes) }.getOrNull()
            }
        }
    }
    return state
}

@Composable
private fun rememberBytesBitmap(bytes: ByteArray?): ImageBitmap? {
    val state by produceState<ImageBitmap?>(initialValue = null, bytes) {
        value = if (bytes == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                runCatching { LibraryPreviewLoader.decode(bytes) }.getOrNull()
            }
        }
    }
    return state
}

/** Filmstrip of the project's own photos, below the stage. */
@Composable
fun ProjectPhotoStrip(
    projects: ProjectHolder,
    onAddPhoto: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val photos = projects.photos
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("照片", style = FmType.labelMedium, color = FmPalette.onSurface)
            Spacer(Modifier.width(FmSpacing.sm))
            Text("${photos.size} 张", style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
            Spacer(Modifier.weight(1f))
            Text(
                "添加照片",
                style = FmType.labelSmall,
                color = FmPalette.primaryStrong,
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
                .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
        ) {
            photos.forEachIndexed { index, photo ->
                val active = photo.id == projects.activePhotoId
                Box(
                    modifier = Modifier
                        .width(76.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .background(FmPalette.surfaceMuted)
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) FmPalette.primary else FmPalette.outline,
                            shape = RoundedCornerShape(FmRadii.chip),
                        )
                        .clickable { projects.selectPhoto(photo.id) },
                ) {
                    val bitmap = rememberPhotoBitmap(projects, photo.path, maxEdge = 192)
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = photo.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Text(
                        text = photo.displayName,
                        style = FmType.labelSmall.copy(fontSize = 9.sp),
                        color = FmPalette.onSurfaceSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(FmPalette.surface.copy(alpha = 0.88f))
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                    )
                    Text(
                        text = "×",
                        style = FmType.labelMedium,
                        color = FmPalette.onSurfaceSecondary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .clip(RoundedCornerShape(FmRadii.pill))
                            .background(FmPalette.surface.copy(alpha = 0.9f))
                            .clickable { onRemovePhoto(photo.id) }
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(FmRadii.chip))
                    .background(FmPalette.surfaceMuted)
                    .border(1.dp, FmPalette.outlineStrong, RoundedCornerShape(FmRadii.chip))
                    .clickable(onClick = onAddPhoto),
                contentAlignment = Alignment.Center,
            ) {
                Text("＋", style = FmType.titleMedium, color = FmPalette.onSurfaceMuted)
            }
        }
        Spacer(Modifier.height(FmSpacing.xs))
    }
}

/**
 * Stage photo: native render with the per-call photo + overlay when the card is
 * wired; otherwise the raw photo with a Compose watermark layer that uses the
 * same `product.WatermarkSpec` rect maths as the engine.
 */
@Composable
fun ProjectPhotoStage(
    projects: ProjectHolder,
    card: LibraryCard?,
    design: DesignSnapshot?,
    modifier: Modifier = Modifier,
) {
    val photo = projects.activePhoto
    var geometryVersion by remember { mutableIntStateOf(0) }
    val overlay = remember(projects.activePhotoId, projects.activeDesign?.overlay, geometryVersion) {
        projects.overlaySpec()
    }
    Box(modifier = modifier.padding(FmSpacing.lg), contentAlignment = Alignment.Center) {
        when {
            card != null && design != null && NativeEngine.supports(card.id) -> {
                NativeStagePreview(
                    card = card,
                    design = design,
                    overlay = overlay,
                    photoPath = photo?.path,
                    onRendered = { _, _, _ -> geometryVersion++ },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            photo != null -> {
                PhotoWithWatermark(projects = projects, modifier = Modifier.fillMaxSize())
            }
            card != null -> {
                LibraryPreview(card = card, modifier = Modifier.fillMaxSize())
            }
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("添加照片开始", style = FmType.titleMedium, color = FmPalette.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "从顶部「添加照片」选择 JPEG/PNG/WebP/TIFF",
                        style = FmType.bodyMedium,
                        color = FmPalette.onSurfaceMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoWithWatermark(projects: ProjectHolder, modifier: Modifier) {
    val photo = projects.activePhoto ?: return
    val bitmap = rememberPhotoBitmap(projects, photo.path, maxEdge = 1600)
    val spec = projects.watermark()
    val mark = rememberBytesBitmap(projects.watermarkBytes())
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            Box(Modifier.fillMaxSize().background(FmPalette.surfaceMuted))
            return@BoxWithConstraints
        }
        val density = LocalDensity.current
        val photoAspect = photo.widthPx.toFloat() / photo.heightPx.coerceAtLeast(1)
        val maxW = maxWidth
        val maxH = maxHeight
        val displayW = min(maxW.value, maxH.value * photoAspect).dp
        val displayH = (displayW.value / photoAspect).dp
        Image(
            bitmap = bitmap,
            contentDescription = photo.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(displayW, displayH).clip(RoundedCornerShape(FmRadii.tile)),
        )
        if (spec != null && mark != null) {
            val rect = spec.rect(photoAspect)
            val box = spec.aabb(photoAspect)
            val boxW = (displayW.value * box.w).dp
            val boxH = (displayH.value * box.h).dp
            val markW = (displayW.value * rect.w).dp
            val markH = (displayH.value * rect.h).dp
            Box(
                modifier = Modifier
                    .offset(
                        x = ((maxW - displayW).value / 2f + displayW.value * box.x).dp,
                        y = ((maxH - displayH).value / 2f + displayH.value * box.y).dp,
                    )
                    .size(boxW, boxH)
                    .clipToBounds(),
            ) {
                Box(
                    modifier = Modifier
                        .offset(
                            x = (displayW.value * (rect.x - box.x)).dp,
                            y = (displayH.value * (rect.y - box.y)).dp,
                        )
                        .size(markW, markH)
                        .graphicsLayer {
                            alpha = spec.opacity.coerceIn(0f, 1f)
                            rotationZ = spec.rotation
                        },
                ) {
                    Image(
                        bitmap = mark,
                        contentDescription = spec.asset.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** Watermark panel in the inspector's 水印 tab. */
@Composable
fun DesktopWatermarkPanel(projects: ProjectHolder, modifier: Modifier = Modifier) {
    val photo = projects.activePhoto
    val spec = projects.watermark()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }

    // No verticalScroll here: DesktopInspector already wraps tab content in a
    // verticalScroll Column, and a nested one measures with infinite max height.
    Column(
        modifier = modifier.fillMaxWidth().padding(FmSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(FmSpacing.sm),
    ) {
        if (photo == null) {
            Text("先添加一张照片，再上传水印。", style = FmType.bodyMedium, color = FmPalette.onSurfaceMuted)
            return@Column
        }
        PrimaryButton(
            text = if (spec == null) "上传透明 PNG 水印" else "更换水印",
            onClick = {
                val path = projects.pickWatermark()
                if (path != null) {
                    scope.launch {
                        val result = projects.importWatermark(path)
                        error = result.exceptionOrNull()?.message
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        val bytes = projects.watermarkBytes()
        val preview = rememberBytesBitmap(bytes)
        if (spec != null && preview != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    bitmap = preview,
                    contentDescription = spec.asset.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .background(FmPalette.surfaceMuted)
                        .padding(4.dp),
                )
                Spacer(Modifier.width(FmSpacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(spec.asset.name, style = FmType.bodyMedium, color = FmPalette.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${spec.asset.widthPx} × ${spec.asset.heightPx} · PNG",
                        style = FmType.labelSmall,
                        color = FmPalette.onSurfaceMuted,
                    )
                }
            }
        }

        Text("位置", style = FmType.labelMedium, color = FmPalette.onSurface)
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.xs)) {
                for (col in 0 until 3) {
                    val index = row * 3 + col
                    val active = (spec?.anchor ?: 8) == index
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(FmRadii.chip))
                            .background(if (active) FmPalette.primary else FmPalette.surfaceMuted)
                            .border(
                                1.dp,
                                if (active) FmPalette.primary else FmPalette.outlineStrong,
                                RoundedCornerShape(FmRadii.chip),
                            )
                            .clickable { projects.updateWatermark(anchor = index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = listOf("↖", "↑", "↗", "←", "•", "→", "↙", "↓", "↘")[index],
                            style = FmType.labelSmall,
                            color = if (active) FmPalette.onPrimary else FmPalette.onSurfaceMuted,
                        )
                    }
                }
            }
        }

        SliderRow(
            label = "宽度",
            value = (spec?.width ?: 0.25f).toStringForUi(),
            slider = {
                FmSlider(
                    value = (spec?.width ?: 0.25f).coerceIn(0.05f, 0.60f),
                    onValueChange = { projects.updateWatermark(width = it) },
                    valueRange = 0.05f..0.60f,
                    enabled = spec != null,
                )
            },
        )
        SliderRow(
            label = "透明度",
            value = "${(((spec?.opacity ?: 0.8f) * 100).toInt())}%",
            slider = {
                FmSlider(
                    value = (spec?.opacity ?: 0.8f).coerceIn(0f, 1f),
                    onValueChange = { projects.updateWatermark(opacity = it) },
                    valueRange = 0f..1f,
                    enabled = spec != null,
                )
            },
        )
        SliderRow(
            label = "水平偏移",
            value = (spec?.dx ?: 0f).toStringForUi(),
            slider = {
                FmSlider(
                    value = (spec?.dx ?: 0f).coerceIn(-0.4f, 0.4f),
                    onValueChange = { projects.updateWatermark(dx = it) },
                    valueRange = -0.4f..0.4f,
                    enabled = spec != null,
                )
            },
        )
        SliderRow(
            label = "垂直偏移",
            value = (spec?.dy ?: 0f).toStringForUi(),
            slider = {
                FmSlider(
                    value = (spec?.dy ?: 0f).coerceIn(-0.4f, 0.4f),
                    onValueChange = { projects.updateWatermark(dy = it) },
                    valueRange = -0.4f..0.4f,
                    enabled = spec != null,
                )
            },
        )
        SliderRow(
            label = "旋转",
            value = "${(spec?.rotation ?: 0f).toInt()}°",
            slider = {
                FmSlider(
                    value = (spec?.rotation ?: 0f).coerceIn(-180f, 180f),
                    onValueChange = { projects.updateWatermark(rotation = it) },
                    valueRange = -180f..180f,
                    enabled = spec != null,
                )
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
            GhostButton(
                text = "应用到全部",
                enabled = spec != null,
                onClick = { projects.applyWatermarkToAll() },
            )
            GhostButton(
                text = "清除",
                enabled = spec != null,
                onClick = { projects.clearWatermark() },
            )
        }
        error?.let {
            Text(it, style = FmType.labelSmall, color = FmPalette.error)
        }
    }
}

private fun Float.toStringForUi(): String {
    val rounded = kotlin.math.round(this * 100f) / 100f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
}

@Composable
private fun SliderRow(label: String, value: String, slider: @Composable () -> Unit) {
    Column {
        Row {
            Text(label, style = FmType.labelSmall, color = FmPalette.onSurfaceSecondary)
            Spacer(Modifier.weight(1f))
            Text(value, style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
        }
        slider()
    }
}
