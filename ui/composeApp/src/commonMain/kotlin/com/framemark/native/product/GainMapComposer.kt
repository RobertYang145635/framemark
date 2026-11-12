package com.framemark.native.product

import kotlin.math.roundToInt

/**
 * One gain-map composition job: the source photo, where its gain map lives, and
 * where the photo ended up in the exported canvas.
 *
 * The compositor is platform code because it has to decode and encode a JPEG;
 * everything else about an Ultra HDR export is common and unit-tested.
 */
internal data class GainMapJob(
    /** Whole source photo file. */
    val source: ByteArray,
    /** Byte offset of the appended gain map inside [source]. */
    val offset: Int,
    /** Byte length of the appended gain map. */
    val size: Int,
    /** Size of the exported image, in pixels. */
    val canvasWidth: Int,
    val canvasHeight: Int,
    /** Photo rectangle in canvas pixels: x, y, w, h. */
    val photoRect: DoubleArray,
    /** Piece of the source the renderer used: x, y, w, h in source pixels. */
    val sourceRect: DoubleArray,
    /** Source frame size, in source pixels. */
    val sourceWidth: Int,
    val sourceHeight: Int,
    /** Per-RGB-channel encoded value that means 0 dB (the border fill). */
    val sentinel: List<Int>,
    /** Gain map downscale factor relative to the canvas (2 = half size). */
    val divisor: Int,
)

/**
 * [GainMapJob.sourceRect] expressed in pixels of a decoded gain map of
 * [width] x [height], as x1, y1, x2, y2 (exclusive).
 *
 * A gain map is not always stored at the photo's own resolution — OPPO's gallery
 * exports append a half-size one, for example — so the crop has to be rescaled
 * from source pixels to gain-map pixels before it can be sampled. Edge rounding
 * keeps the region anchored to the same place; the result stays inside the
 * bitmap and is never empty, so an odd scale cannot produce a zero-area source.
 */
internal fun GainMapJob.gainMapRect(width: Int, height: Int): IntArray {
    val bitmapWidth = width.coerceAtLeast(1)
    val bitmapHeight = height.coerceAtLeast(1)
    val sourceW = sourceWidth.coerceAtLeast(1)
    val sourceH = sourceHeight.coerceAtLeast(1)
    val x = sourceRect.getOrElse(0) { 0.0 }
    val y = sourceRect.getOrElse(1) { 0.0 }
    val w = sourceRect.getOrElse(2) { 0.0 }
    val h = sourceRect.getOrElse(3) { 0.0 }

    val left = (x / sourceW * bitmapWidth).roundToInt().coerceIn(0, bitmapWidth - 1)
    val top = (y / sourceH * bitmapHeight).roundToInt().coerceIn(0, bitmapHeight - 1)
    val right = ((x + w) / sourceW * bitmapWidth).roundToInt().coerceIn(left + 1, bitmapWidth)
    val bottom = ((y + h) / sourceH * bitmapHeight).roundToInt().coerceIn(top + 1, bitmapHeight)
    return intArrayOf(left, top, right, bottom)
}

/**
 * Renders the source gain map into the exported layout and encodes it as JPEG.
 *
 * The output canvas is the whole image at `1 / divisor` scale: the part of the
 * gain map that corresponds to [GainMapJob.sourceRect] is drawn into the photo
 * rectangle and every other pixel carries [GainMapJob.sentinel], so a border
 * renders exactly as it does in SDR.
 *
 * Returns null when the gain map cannot be decoded or encoded; the caller then
 * keeps the plain SDR export.
 */
internal expect object GainMapComposer {
    fun compose(job: GainMapJob): ByteArray?
}
