package com.framemark.native.shell.phone

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pixel geometry of the live watermark layer (2026-11-14): one axis-aligned
 * **clip box** holding one **rotated image box**.
 *
 * Why the clip box is not the draw rect: the PNG is rotated inside its own
 * layer, so the corners of the rotated image reach outside the unrotated rect.
 * Clipping to the rect therefore cuts the top and bottom off (the owner's
 * 「为什么水印旋转之后，上下会被裁切」). The clip box is the rotation AABB
 * ([com.framemark.native.workflow.WatermarkSpec.aabb]) and is rounded
 * **outward** (`floor` on the two minima, `ceil` on the two maxima), so the
 * integer-sized image can never be cropped by its own box; the photo draw area
 * stays the only legitimate clip, and the AABB itself never leaves it because
 * the drawn rect is clamped inside the photo.
 *
 * Also why the image gets `requiredSize` at the call site: a plain `size` is
 * coerced by the incoming max constraints, so whenever the clip box happened to
 * be shorter than the image (at 90° the old AABB was 224 px tall against a
 * 398 px image) the PNG was squashed to the box and *then* cut — a second,
 * independent defect that this geometry no longer triggers and `requiredSize`
 * makes impossible even if the maths changes again.
 *
 * Pure on purpose: the screenshot-level property is checked by a unit test on
 * these numbers, not by eyeballing a device capture.
 *
 * The clip box doubles as the **drag node's** box (2026-11-18). The gesture used
 * to hang off the rotated image box, which made every drag move along the mark's
 * local axes: at 45° a horizontal finger slid the watermark diagonally. The
 * pointer node now sits on this unrotated box, so [watermarkDragNudge] receives
 * screen-axis deltas and only divides them by the photo size.
 */
internal data class WatermarkLayerPx(
    val clipLeft: Int,
    val clipTop: Int,
    val clipWidth: Int,
    val clipHeight: Int,
    val imageLeft: Int,
    val imageTop: Int,
    val imageWidth: Int,
    val imageHeight: Int,
)

/**
 * @param rect `[x, y, w, h]` normalised draw rect (see `WatermarkSpec.rect`).
 * @param aabb `[x, y, w, h]` normalised rotation AABB (`WatermarkSpec.aabb`).
 * @param photoLeft/photoTop photo draw box origin in stage pixels.
 * @param photoWidth/photoHeight photo draw box size in stage pixels.
 * @param rotation degrees clockwise, the value the layer draws with.
 */
internal fun watermarkLayerPx(
    rect: FloatArray,
    aabb: FloatArray,
    photoLeft: Float,
    photoTop: Float,
    photoWidth: Float,
    photoHeight: Float,
    rotation: Float,
): WatermarkLayerPx {
    val imageWidth = (rect[2] * photoWidth).roundToInt().coerceAtLeast(1)
    val imageHeight = (rect[3] * photoHeight).roundToInt().coerceAtLeast(1)
    val centerX = photoLeft + (rect[0] + rect[2] / 2f) * photoWidth
    val centerY = photoTop + (rect[1] + rect[3] / 2f) * photoHeight

    // Rotated extent of the box actually drawn (integer px), then widen by the
    // AABB as well: the two agree up to float error and neither may be cropped.
    val rad = rotation * PI.toFloat() / 180f
    val rotatedWidth = abs(imageWidth * cos(rad)) + abs(imageHeight * sin(rad))
    val rotatedHeight = abs(imageWidth * sin(rad)) + abs(imageHeight * cos(rad))
    val aabbLeft = photoLeft + aabb[0] * photoWidth
    val aabbTop = photoTop + aabb[1] * photoHeight
    val aabbRight = photoLeft + (aabb[0] + aabb[2]) * photoWidth
    val aabbBottom = photoTop + (aabb[1] + aabb[3]) * photoHeight

    val left = floor(min(aabbLeft, centerX - rotatedWidth / 2f)).toInt()
    val top = floor(min(aabbTop, centerY - rotatedHeight / 2f)).toInt()
    val right = ceil(max(aabbRight, centerX + rotatedWidth / 2f)).toInt()
    val bottom = ceil(max(aabbBottom, centerY + rotatedHeight / 2f)).toInt()

    return WatermarkLayerPx(
        clipLeft = left,
        clipTop = top,
        clipWidth = (right - left).coerceAtLeast(1),
        clipHeight = (bottom - top).coerceAtLeast(1),
        imageLeft = (centerX - imageWidth / 2f).roundToInt(),
        imageTop = (centerY - imageHeight / 2f).roundToInt(),
        imageWidth = imageWidth,
        imageHeight = imageHeight,
    )
}

/**
 * Stage-pixel rect of the watermark's drag node: the clip box, i.e. the rotation
 * AABB. Only this box takes pointer events — the photo around it keeps the stage
 * pan, and the nine anchor hotspots keep their 44 dp squares (they are a
 * later sibling, so they win the hit test first).
 */
internal data class WatermarkHitBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom
}

/** The clip box as a float rect — the same numbers the drag node is sized and offset by. */
internal val WatermarkLayerPx.hitBox: WatermarkHitBox
    get() = WatermarkHitBox(
        left = clipLeft.toFloat(),
        top = clipTop.toFloat(),
        right = (clipLeft + clipWidth).toFloat(),
        bottom = (clipTop + clipHeight).toFloat(),
    )

/** A finger delta already divided by the photo draw box. */
internal data class WatermarkNudge(val dx: Float, val dy: Float)

/**
 * Stage-pixel drag delta → the normalised nudge
 * [com.framemark.native.workflow.WatermarkSpec.withRectNudge] consumes.
 *
 * Rotation is deliberately **not** a parameter. The gesture node sits on the
 * unrotated [hitBox], so `detectDragGestures` already hands over screen-axis
 * pixels; dividing by the photo size is the whole mapping, which makes one pixel
 * of finger travel worth `1/photoWidth` (or `1/photoHeight`) at every angle.
 *
 * The bug this replaces: the node used to be the rotated image box, so its
 * `drag.x/drag.y` were in the mark's local axes — a horizontal drag at 45°
 * arrived as `(dx·cos45, dx·sin45)` and wrote a diagonal move.
 */
internal fun watermarkDragNudge(
    dragX: Float,
    dragY: Float,
    photoWidth: Float,
    photoHeight: Float,
): WatermarkNudge {
    // `!(x > 0f)` also rejects NaN, so a zero-sized photo can never emit infinities.
    if (!(photoWidth > 0f) || !(photoHeight > 0f)) return WatermarkNudge(0f, 0f)
    return WatermarkNudge(dx = dragX / photoWidth, dy = dragY / photoHeight)
}
