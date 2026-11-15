package com.framemark.native.product

import java.awt.image.BufferedImage

/**
 * Desktop pixel rotation driven by [PhotoOrientation.rowSource]: every
 * destination row is copied from one source row (or column), so the transform
 * table lives in one common, unit-tested place instead of a Graphics2D formula
 * per orientation.
 *
 * Returns [this] unchanged for orientation 1 (the untouched decode path).
 */
internal fun BufferedImage.orientedImage(orientation: Int): BufferedImage {
    val safe = PhotoOrientation.normalised(orientation)
    if (safe == 1) return this
    val display = PhotoOrientation.displaySize(width, height, safe)
    val out = BufferedImage(display.width, display.height, BufferedImage.TYPE_INT_ARGB)
    val line = IntArray(maxOf(width, height))
    val destRow = IntArray(display.width)
    for (dy in 0 until display.height) {
        val row = PhotoOrientation.rowSource(safe, width, height, dy)
        if (row.column) {
            // One source column (x = row.line), top to bottom.
            getRGB(row.line, 0, 1, height, line, 0, 1)
            for (dx in 0 until display.width) {
                destRow[dx] = line[if (row.reversed) height - 1 - dx else dx]
            }
        } else {
            // One source row (y = row.line), left to right.
            getRGB(0, row.line, width, 1, line, 0, width)
            for (dx in 0 until display.width) {
                destRow[dx] = line[if (row.reversed) width - 1 - dx else dx]
            }
        }
        out.setRGB(0, dy, display.width, 1, destRow, 0, display.width)
    }
    return out
}
