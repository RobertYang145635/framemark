package com.framemark.native.shell.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.product.CropGeometry
import com.framemark.native.product.PhotoCrop
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.ImageRect
import com.framemark.native.workflow.PhotoThumbnail
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Everything the stage needs to offer and edit the photo crop.
 *
 * The state lives one level up (the editor owns the pending value and only writes
 * it into the design on 完成), so this type is the whole contract between the crop
 * UI and the shell — no callbacks hidden inside the composable.
 */
internal data class PhoneCropUi(
    /** True while the user is choosing the region; the stage then shows the photo. */
    val active: Boolean,
    /** The pending value: the saved crop when entering, the gesture's result after. */
    val crop: PhotoCrop,
    /**
     * Source photo size in the frame the renderer draws, in **display** pixels
     * (EXIF applied) — the same frame the preview bitmap below is drawn in and the
     * same frame `photoCropX/Y/Zoom` is interpreted in.
     */
    val sourceWidth: Int,
    val sourceHeight: Int,
    /** Engine-reported photo box (canvas pixels); only its aspect is used here. */
    val box: ImageRect,
    /** Photo the crop is chosen on; null keeps the neutral placeholder. */
    val photoPath: String?,
    val onCropChange: (PhotoCrop) -> Unit,
    val onEnter: () -> Unit,
    val onReset: () -> Unit,
    val onDone: () -> Unit,
)

/** The source photo laid out inside the stage's photo band (`ContentScale.Fit`). */
internal data class CropStageFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
}

/**
 * Pure layout of the crop preview: where a [sourceWidth] x [sourceHeight] photo
 * sits inside a [bandWidth] x [bandHeight] band when it is fitted (letterboxed,
 * never cropped). Kept out of the compose function so the geometry can be tested.
 */
internal fun cropStageFrame(
    bandWidth: Float,
    bandHeight: Float,
    sourceWidth: Int,
    sourceHeight: Int,
): CropStageFrame {
    if (bandWidth <= 0f || bandHeight <= 0f || sourceWidth <= 0 || sourceHeight <= 0) {
        return CropStageFrame(0f, 0f, 0f, 0f)
    }
    val scale = min(bandWidth / sourceWidth, bandHeight / sourceHeight)
    val width = sourceWidth * scale
    val height = sourceHeight * scale
    return CropStageFrame(
        left = (bandWidth - width) / 2f,
        top = (bandHeight - height) / 2f,
        width = width,
        height = height,
    )
}

/** Drag/pinch distances that count as "the middle" on release (居中自动吸附). */
private val CropSnapDistance = 10.dp
private val CropFrameStroke = 2.dp
private val CropFrameRing = 1.dp
/** How much of the discarded area the scrim hides. */
private const val CROP_SCRIM_ALPHA = 0.55f
/** Preview decode edge: the frame only needs to look right, not to be exportable. */
private const val CROP_PREVIEW_EDGE = 1536

/**
 * The crop editor over the photo band: the whole photo, the keep-region frame the
 * export will use, a scrim on everything the crop throws away, and the gestures.
 *
 * The frame is not an independent rectangle: it is [CropGeometry.region] — the
 * same function the export calls — mapped onto the fitted photo, so what the user
 * frames here is pixel-for-pixel what the pre-cropped render keeps.
 */
@Composable
internal fun PhoneCropLayer(
    ui: PhoneCropUi,
    modifier: Modifier = Modifier,
    /**
     * Opacity of the crop **grid** (scrim + keep frame + thirds) while crop mode is
     * entering, 0..1. Driven by the stage's single `cropEnter` progress so the grid
     * and the chrome crossfade together (motion spec §3.1 #7). The photo below it is
     * deliberately *not* scaled by this: the pixels and the crop rectangle are never
     * animated, only the overlay is.
     */
    appearProgress: () -> Float = { 1f },
) {
    if (ui.box.w <= 0f || ui.box.h <= 0f) return
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val bandWidth = constraints.maxWidth.toFloat()
        val bandHeight = constraints.maxHeight.toFloat()
        val frame = cropStageFrame(bandWidth, bandHeight, ui.sourceWidth, ui.sourceHeight)
        if (frame.isEmpty) return@BoxWithConstraints

        val region = cropFrameBounds(frame, ui.sourceWidth, ui.sourceHeight, ui.box, ui.crop)
            ?: return@BoxWithConstraints
        // The frame in band pixels: the kept region's normalised rectangle scaled
        // into the fitted photo. Rounding to whole source pixels happened in
        // `CropGeometry.region`, so this is exactly the exported rectangle's aspect.
        val keepLeft = region[0]
        val keepTop = region[1]
        val keepWidth = region[2]
        val keepHeight = region[3]

        val cropNow by rememberUpdatedState(ui.crop)
        val changeNow by rememberUpdatedState(ui.onCropChange)
        val snapPx = with(density) { CropSnapDistance.toPx() }
        val scrim = FmPalette.stage.copy(alpha = CROP_SCRIM_ALPHA)
        val frameInk = FmPalette.stageInk
        val frameRing = FmPalette.stage

        Box(
            modifier = Modifier
                .offset { IntOffset(frame.left.roundToInt(), frame.top.roundToInt()) }
                .size(with(density) { frame.width.toDp() }, with(density) { frame.height.toDp() }),
        ) {
            // The whole photo, so the user sees what is being discarded — the stage
            // behind it shows the engine frame, which has already cropped it.
            PhotoThumbnail(
                path = ui.photoPath,
                maxEdge = CROP_PREVIEW_EDGE,
                cornerRadius = 0.dp,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // Draw-phase read: the enter progress is an animated float, and an
                // animated float must never be a composition input (it would
                // recompose the whole crop layer every frame just to set an alpha).
                .graphicsLayer { alpha = appearProgress().coerceIn(0f, 1f) }
                .pointerInput(frame, ui.sourceWidth, ui.sourceHeight, ui.box) {
                    // One detector for both gestures: `detectTransformGestures` hides
                    // which finger count produced a sample, and the release snap has to
                    // run exactly once per gesture.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoomChange != 1f) {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                if (centroid.isSpecified) {
                                    val atX = (centroid.x - frame.left) / frame.width
                                    val atY = (centroid.y - frame.top) / frame.height
                                    changeNow(
                                        CropGeometry.clamped(
                                            ui.sourceWidth,
                                            ui.sourceHeight,
                                            ui.box.w.toDouble(),
                                            ui.box.h.toDouble(),
                                            cropNow.zoomedAround(zoomChange.toDouble(), atX.toDouble(), atY.toDouble()),
                                        ),
                                    )
                                }
                            } else if (pan != Offset.Zero) {
                                // Dragging the photo to the right reveals its left
                                // side, so the keep window moves left with the same
                                // sign `panned` applies.
                                changeNow(
                                    CropGeometry.clamped(
                                        ui.sourceWidth,
                                        ui.sourceHeight,
                                        ui.box.w.toDouble(),
                                        ui.box.h.toDouble(),
                                        cropNow.panned(
                                            (pan.x / frame.width).toDouble(),
                                            (pan.y / frame.height).toDouble(),
                                        ),
                                    ),
                                )
                            }
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        // 居中自动吸附: a centre released near the middle lands on it,
                        // while the finger is already up so it cannot fight the drag.
                        val snapped = CropGeometry.clamped(
                            ui.sourceWidth,
                            ui.sourceHeight,
                            ui.box.w.toDouble(),
                            ui.box.h.toDouble(),
                            cropNow.snapped(
                                (snapPx / frame.width).toDouble(),
                                (snapPx / frame.height).toDouble(),
                            ),
                        )
                        if (snapped != cropNow) changeNow(snapped)
                    }
                },
        ) {
            if (keepWidth <= 0f || keepHeight <= 0f) return@Canvas
            // Scrim on everything the crop discards: four strips around the frame,
            // so the kept area stays untouched instead of being covered and redrawn.
            drawRect(scrim, Offset(0f, 0f), Size(size.width, keepTop.coerceAtLeast(0f)))
            drawRect(
                scrim,
                Offset(0f, (keepTop + keepHeight).coerceAtMost(size.height)),
                Size(size.width, (size.height - keepTop - keepHeight).coerceAtLeast(0f)),
            )
            drawRect(scrim, Offset(0f, keepTop), Size(keepLeft.coerceAtLeast(0f), keepHeight))
            drawRect(
                scrim,
                Offset((keepLeft + keepWidth).coerceAtMost(size.width), keepTop),
                Size((size.width - keepLeft - keepWidth).coerceAtLeast(0f), keepHeight),
            )
            val stroke = CropFrameStroke.toPx()
            val ring = CropFrameRing.toPx()
            // Dark ring outside, light stroke inside: readable on a white photo and
            // on a night shot alike (same pairing as the text-row highlight).
            drawRect(
                color = frameRing,
                topLeft = Offset(keepLeft - ring / 2f, keepTop - ring / 2f),
                size = Size(keepWidth + ring, keepHeight + ring),
                style = Stroke(width = ring),
            )
            drawRect(
                color = frameInk,
                topLeft = Offset(keepLeft + stroke / 2f, keepTop + stroke / 2f),
                size = Size((keepWidth - stroke).coerceAtLeast(stroke), (keepHeight - stroke).coerceAtLeast(stroke)),
                style = Stroke(width = stroke),
            )
            // Thirds: a quiet composition aid while framing the surviving part.
            val thin = 1.dp.toPx()
            for (index in 1..2) {
                val x = keepLeft + keepWidth * index / 3f
                val y = keepTop + keepHeight * index / 3f
                drawLine(frameInk.copy(alpha = 0.35f), Offset(x, keepTop), Offset(x, keepTop + keepHeight), thin)
                drawLine(frameInk.copy(alpha = 0.35f), Offset(keepLeft, y), Offset(keepLeft + keepWidth, y), thin)
            }
        }
    }
}

/**
 * 裁切 entry: a small, always-visible pill next to 按住看原图, not a long-press.
 * It only appears when the box really crops the photo and the crop can be mapped
 * onto a reported photo box ([CropGeometry.cropsSource] is that decision).
 *
 * [marked] draws the same dot the tool bar uses for "this page has been edited",
 * so a saved crop stays visible on the stage after the editor is closed.
 */
@Composable
internal fun PhoneCropEntryChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    marked: Boolean = false,
) {
    val shape = RoundedCornerShape(FmRadii.pill)
    Box(
        modifier = modifier
            .heightIn(min = PhoneTouchTarget)
            .semantics(mergeDescendants = true) {
                contentDescription = if (marked) "裁切照片，已设置保留区域" else "裁切照片，选择保留区域"
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Row(
                modifier = Modifier
                    .height(34.dp)
                    .clip(shape)
                    .background(FmPalette.scrim)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CropGlyph(tint = FmPalette.stageInk)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "裁切",
                    style = FmType.labelSmall.copy(fontSize = 12.5.sp),
                    color = FmPalette.stageInk,
                    maxLines = 1,
                )
            }
            if (marked) {
                Box(
                    modifier = Modifier
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(7.dp)
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.primary),
                )
            }
        }
    }
}

/** 重置 / 完成 pair shown while the crop editor is open. */
@Composable
internal fun PhoneCropActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    val shape = RoundedCornerShape(FmRadii.pill)
    Box(
        modifier = modifier
            .heightIn(min = PhoneTouchTarget)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(34.dp)
                .clip(shape)
                .background(if (emphasised) FmPalette.primary else FmPalette.scrim)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = FmType.labelSmall.copy(fontSize = 12.5.sp),
                color = if (emphasised) FmPalette.onPrimary else FmPalette.stageInk,
                maxLines = 1,
            )
        }
    }
}

/** Two opposed corners: the universal crop mark, stroke-based so it follows tint. */
@Composable
private fun CropGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(13.dp)) {
        val w = size.width
        val stroke = 1.4.dp.toPx()
        drawLine(tint, Offset(0f, w * 0.35f), Offset(0f, 0f), stroke)
        drawLine(tint, Offset(0f, 0f), Offset(w * 0.35f, 0f), stroke)
        drawLine(tint, Offset(w * 0.65f, w), Offset(w, w), stroke)
        drawLine(tint, Offset(w, w), Offset(w, w * 0.65f), stroke)
    }
}

/** Keeps the crop band's own spacing on the same scale as the stage pills. */
internal val CropActionGap = FmSpacing.sm

/**
 * The stage's crop action row, drawn in the pill band. Kept here so the crop UI
 * owns its one layout: [onReset] and [onDone] are the only exits.
 */
@Composable
internal fun PhoneCropActionRow(
    onReset: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CropActionGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhoneCropActionChip(label = "重置", onClick = onReset)
        PhoneCropActionChip(label = "完成", onClick = onDone, emphasised = true)
    }
}

/** True when the two aspects differ enough that the box really crops the photo. */
internal fun cropApplies(
    sourceWidth: Int,
    sourceHeight: Int,
    box: ImageRect?,
): Boolean {
    if (box == null) return false
    if (sourceWidth <= 0 || sourceHeight <= 0) return false
    if (box.w <= 0f || box.h <= 0f) return false
    return CropGeometry.cropsSource(
        sourceWidth,
        sourceHeight,
        box.w.toDouble(),
        box.h.toDouble(),
    )
}

/** The keep-region frame in band pixels, or null when the box cannot describe one. */
internal fun cropFrameBounds(
    frame: CropStageFrame,
    sourceWidth: Int,
    sourceHeight: Int,
    box: ImageRect?,
    crop: PhotoCrop,
): FloatArray? {
    if (box == null || frame.isEmpty) return null
    val region = CropGeometry.region(
        sourceWidth,
        sourceHeight,
        box.w.toDouble(),
        box.h.toDouble(),
        crop,
    ) ?: return null
    val left = frame.left + region[0].toFloat() / sourceWidth * frame.width
    val top = frame.top + region[1].toFloat() / sourceHeight * frame.height
    val width = region[2].toFloat() / sourceWidth * frame.width
    val height = region[3].toFloat() / sourceHeight * frame.height
    if (abs(width) < 0.5f || abs(height) < 0.5f) return null
    return floatArrayOf(left, top, width, height)
}
