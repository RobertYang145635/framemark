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
