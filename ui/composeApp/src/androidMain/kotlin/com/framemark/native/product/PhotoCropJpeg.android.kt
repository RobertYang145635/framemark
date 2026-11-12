package com.framemark.native.product

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.File
import kotlin.math.roundToInt

/**
 * Android crop primitives: a bounds-only size probe and a region decode.
 *
 * The probe uses `inJustDecodeBounds`, so measuring never allocates a raster. The
 * crop goes through [BitmapRegionDecoder] instead of decoding the whole photo and
 * cutting it: a 50 MP JPEG would cost ~200 MB of heap to keep a 4 MP region, and
 * the export sheet has to survive that on a phone.
 */
internal actual fun photoSourceSize(path: String): IntArray? = runCatching {
    val file = File(path)
    if (!file.isFile) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        intArrayOf(bounds.outWidth, bounds.outHeight)
    }
}.getOrNull()

/**
 * `BitmapRegionDecoder.newInstance` is deprecated in API 31, but its replacements
 * (`ImageDecoder`) only exist from API 28 and this app starts at 26. The
 * alternative — decoding the whole photo and cutting it — needs ~200 MB for a
 * 50 MP JPEG, which is exactly what the export path cannot afford on a phone.
 */
@Suppress("DEPRECATION")
internal actual fun cropJpegTo(sourcePath: String, rect: DoubleArray, outPath: String): Boolean =
    runCatching {
        if (rect.size < 4) return@runCatching false
        val decoder = BitmapRegionDecoder.newInstance(sourcePath, false)
            ?: return@runCatching false
        try {
            if (decoder.width <= 0 || decoder.height <= 0) return@runCatching false
            val x = rect[0].roundToInt().coerceIn(0, decoder.width - 1)
            val y = rect[1].roundToInt().coerceIn(0, decoder.height - 1)
            val width = rect[2].roundToInt().coerceIn(1, decoder.width - x)
            val height = rect[3].roundToInt().coerceIn(1, decoder.height - y)

            val region = decoder.decodeRegion(Rect(x, y, x + width, y + height), BitmapFactory.Options())
                ?: return@runCatching false
            try {
                val target = File(outPath)
                target.parentFile?.mkdirs()
                val written = target.outputStream().use { stream ->
                    region.compress(Bitmap.CompressFormat.JPEG, CROP_QUALITY, stream)
                }
                written && target.isFile && target.length() > 0L
            } finally {
                region.recycle()
            }
        } finally {
            decoder.recycle()
        }
    }.getOrDefault(false)

/** The cropped copy is the renderer's input: quality 100, like the export encoder. */
private const val CROP_QUALITY = 100
