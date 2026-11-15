package com.framemark.native.product

import android.graphics.Bitmap

/**
 * Android pixel rotation driven by [PhotoOrientation.rowSource]. Same contract
 * and same index table as the desktop twin; each destination row is one bulk
 * `getPixels` of a source row or column plus one `setPixels`, so a full-size
 * gain map never needs a 50 MB temporary int array (and never a per-pixel JNI
 * call).
 *
 * Returns [this] unchanged for orientation 1.
 */
internal fun Bitmap.orientedImage(orientation: Int): Bitmap {
    val safe = PhotoOrientation.normalised(orientation)
    if (safe == 1) return this
    val display = PhotoOrientation.displaySize(width, height, safe)
    val out = Bitmap.createBitmap(display.width, display.height, Bitmap.Config.ARGB_8888)
    val line = IntArray(maxOf(width, height))
    val destRow = IntArray(display.width)
    for (dy in 0 until display.height) {
        val row = PhotoOrientation.rowSource(safe, width, height, dy)
        if (row.column) {
            getPixels(line, 0, 1, row.line, 0, 1, height)
            for (dx in 0 until display.width) {
                destRow[dx] = line[if (row.reversed) height - 1 - dx else dx]
            }
        } else {
            getPixels(line, 0, width, 0, row.line, width, 1)
            for (dx in 0 until display.width) {
                destRow[dx] = line[if (row.reversed) width - 1 - dx else dx]
            }
        }
        out.setPixels(destRow, 0, display.width, 0, dy, display.width, 1)
    }
    return out
}
