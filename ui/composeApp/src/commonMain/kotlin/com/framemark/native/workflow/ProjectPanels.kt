package com.framemark.native.workflow

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryPreview
import com.framemark.native.library.LibraryPreviewLoader
import com.framemark.native.product.PhotoDesign
import com.framemark.native.product.ProjectWatermarkSpec
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import kotlinx.coroutines.Dispatchers
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
/**
 * Stage photo: native render with the per-call photo + overlay when the card is
 * wired; otherwise the raw photo with a Compose watermark layer that uses the
 * same `product.ProjectWatermarkSpec` rect maths as the engine.
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
                        // `requiredSize`: the rotated mark must keep the rect's
                        // size even though the clip box (the AABB) is the
                        // parent — a plain size would be coerced to it.
                        .requiredSize(markW, markH)
                        .graphicsLayer {
                            alpha = spec.opacity.coerceIn(0f, 1f)
                            rotationZ = spec.rotation.coerceIn(
                                -ProjectWatermarkSpec.MAX_ROTATION,
                                ProjectWatermarkSpec.MAX_ROTATION,
                            )
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
