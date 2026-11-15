package com.framemark.native.product

/**
 * Which way a photo is stored: the one coordinate authority for the whole app.
 *
 * ## Why this exists (B-02 / A-01)
 *
 * The engines do **not** draw the raw raster. Skia m156's `Image::from_encoded`
 * returns the *oriented* image for JPEG (`bridge/android/src/paint.rs` documents
 * and applies exactly that rule), so `engine/e2b` and both Android bridges lay
 * the photo out in post-orientation ("logical") pixels and report `photo_src`
 * and the `photoCropX/Y/Zoom` region in that same frame. The Kotlin side had
 * been mixing the two spaces: `UltraHdr.frameSize` reads the JPEG SOF (raw),
 * the crop UI measured the file with `inJustDecodeBounds` (raw) while drawing a
 * thumbnail that had already been rotated, and the gains map was cropped in raw
 * pixels to be pasted into a display-oriented canvas. Every disagreement came
 * from having no single definition of "the photo's pixels".
 *
 * This object is that definition:
 *
 *  - [displaySize] converts the stored raster size (SOF / `inJustDecodeBounds`)
 *    into the frame the renderer, the crop UI and the export canvas use;
 *  - [displayRectToRaw] / [rawRectToDisplay] move a **continuous** rectangle
 *    between the two spaces (used by the fallback pre-crop, which has to cut the
 *    raw JPEG);
 *  - [rowSource] describes, for a pixel-level rotation, which source line each
 *    destination row is built from — so both platforms share one index table
 *    instead of three hand-written rotate/mirror formulas.
 *
 * Orientation is EXIF 1..8; anything else is treated as 1 (no transform). The
 * canonical pixel mapping is the one Android's `ExifInterface` uses (and
 * `ExifProbe.rotate` already implemented): 2 flip-H, 3 rotate-180, 4 flip-V,
 * 5 transpose, 6 rotate-90-CW, 7 transverse, 8 rotate-90-CCW.
 */
object PhotoOrientation {

    /** Width/height of the frame the renderer and every display surface use. */
    data class DisplaySize(val width: Int, val height: Int)

    /**
     * One destination row's source line for a pixel rotation.
     *
     * [column] picks a source column (`line` is an x) instead of a row (`line` is
     * a y); [reversed] means the destination x increases while the source
     * coordinate decreases. Both cases copy a whole line, so a rotation never
     * needs a per-pixel random access.
     */
    data class RowSource(val column: Boolean, val line: Int, val reversed: Boolean)

    /** True for the four orientations that swap the axes (EXIF 5..8). */
    fun isTransposed(orientation: Int): Boolean = orientation in 5..8

    fun normalised(orientation: Int): Int = if (orientation in 1..8) orientation else 1

    /** The renderer's frame for a photo whose stored raster is [width] x [height]. */
    fun displaySize(width: Int, height: Int, orientation: Int): DisplaySize {
        val safe = normalised(orientation)
        return if (isTransposed(safe)) DisplaySize(height, width) else DisplaySize(width, height)
    }

    /**
     * Destination (display) pixel `(dx, dy)` → source (raw) pixel `(sx, sy)`.
     *
     * The inverse of the EXIF transform: it is what a rotation loop needs, and
     * its forward twin is tested against it so neither can drift.
     */
    fun sourcePixel(orientation: Int, rawWidth: Int, rawHeight: Int, dx: Int, dy: Int): Pair<Int, Int> {
        if (rawWidth <= 0 || rawHeight <= 0) return dx to dy
        return when (normalised(orientation)) {
            2 -> rawWidth - 1 - dx to dy
            3 -> rawWidth - 1 - dx to rawHeight - 1 - dy
            4 -> dx to rawHeight - 1 - dy
            5 -> dy to dx
            6 -> dy to rawHeight - 1 - dx
            7 -> rawWidth - 1 - dy to rawHeight - 1 - dx
            8 -> rawWidth - 1 - dy to dx
            else -> dx to dy
        }
    }

    /** Forward map: source (raw) pixel → destination (display) pixel. */
    fun displayPixel(orientation: Int, rawWidth: Int, rawHeight: Int, sx: Int, sy: Int): Pair<Int, Int> {
        if (rawWidth <= 0 || rawHeight <= 0) return sx to sy
        return when (normalised(orientation)) {
            2 -> rawWidth - 1 - sx to sy
            3 -> rawWidth - 1 - sx to rawHeight - 1 - sy
            4 -> sx to rawHeight - 1 - sy
            5 -> sy to sx
            6 -> rawHeight - 1 - sy to sx
            7 -> rawHeight - 1 - sy to rawWidth - 1 - sx
            8 -> sy to rawWidth - 1 - sx
            else -> sx to sy
        }
    }

    /**
     * Which source line builds destination row [dy].
     *
     * For orientation 1 and its mirrors the rows map to rows; the rotated
     * orientations map each destination row to a source **column**. Row order is
     * the one [sourcePixel] produces, so this cannot disagree with the rect and
     * pixel functions.
     */
    fun rowSource(orientation: Int, rawWidth: Int, rawHeight: Int, dy: Int): RowSource {
        val safe = normalised(orientation)
        val first = sourcePixel(safe, rawWidth, rawHeight, 0, dy)
        val second = sourcePixel(safe, rawWidth, rawHeight, 1, dy)
        return if (first.second == second.second) {
            // The destination row walks along source x: a row read.
            RowSource(
                column = false,
                line = first.second,
                reversed = second.first < first.first,
            )
        } else {
            // The destination row walks along source y: a column read.
            RowSource(
                column = true,
                line = first.first,
                reversed = second.second < first.second,
            )
        }
    }

    /**
     * A rectangle in display pixels → the rectangle it covers in the raw raster.
     *
     * Continuous edges, not pixel centres: the caller uses this to cut the raw
     * JPEG (the fallback pre-crop), so a 1 px difference is acceptable but a
     * half-image shift is not. Works for every orientation; the result is
     * normalised (x1 ≥ x0, y1 ≥ y0) and never leaves the raw frame.
     */
    fun displayRectToRaw(
        rect: DoubleArray,
        orientation: Int,
        rawWidth: Int,
        rawHeight: Int,
    ): DoubleArray? {
        if (rect.size < 4) return null
        if (rawWidth <= 0 || rawHeight <= 0) return null
        val x0 = rect[0]
        val y0 = rect[1]
        val x1 = rect[0] + rect[2]
        val y1 = rect[1] + rect[3]
        if (!(x1 > x0) || !(y1 > y0)) return null
        if (!listOf(x0, y0, x1, y1).all { it.isFinite() }) return null
        val safe = normalised(orientation)
        val mapped = listOf(
            displayPointToRaw(safe, rawWidth, rawHeight, x0, y0),
            displayPointToRaw(safe, rawWidth, rawHeight, x1, y0),
            displayPointToRaw(safe, rawWidth, rawHeight, x0, y1),
            displayPointToRaw(safe, rawWidth, rawHeight, x1, y1),
        )
        return bounds(mapped, rawWidth.toDouble(), rawHeight.toDouble())
    }

    /** The forward twin of [displayRectToRaw]; used by tests and diagnostics. */
    fun rawRectToDisplay(
        rect: DoubleArray,
        orientation: Int,
        rawWidth: Int,
        rawHeight: Int,
    ): DoubleArray? {
        if (rect.size < 4) return null
        if (rawWidth <= 0 || rawHeight <= 0) return null
        val x0 = rect[0]
        val y0 = rect[1]
        val x1 = rect[0] + rect[2]
        val y1 = rect[1] + rect[3]
        if (!(x1 > x0) || !(y1 > y0)) return null
        if (!listOf(x0, y0, x1, y1).all { it.isFinite() }) return null
        val safe = normalised(orientation)
        val display = displaySize(rawWidth, rawHeight, safe)
        val mapped = listOf(
            rawPointToDisplay(safe, rawWidth, rawHeight, x0, y0),
            rawPointToDisplay(safe, rawWidth, rawHeight, x1, y0),
            rawPointToDisplay(safe, rawWidth, rawHeight, x0, y1),
            rawPointToDisplay(safe, rawWidth, rawHeight, x1, y1),
        )
        return bounds(mapped, display.width.toDouble(), display.height.toDouble())
    }

    private fun displayPointToRaw(
        orientation: Int,
        rawWidth: Int,
        rawHeight: Int,
        x: Double,
        y: Double,
    ): Pair<Double, Double> {
        val w = rawWidth.toDouble()
        val h = rawHeight.toDouble()
        return when (orientation) {
            2 -> (w - x) to y
            3 -> (w - x) to (h - y)
            4 -> x to (h - y)
            5 -> y to x
            6 -> y to (h - x)
            // Transverse/anti-transverse: the continuous inverse of the pixel map,
            // which is NOT the identity nor a simple swap of the two lines.
            7 -> (w - y) to (h - x)
            8 -> (w - y) to x
            else -> x to y
        }
    }

    private fun rawPointToDisplay(
        orientation: Int,
        rawWidth: Int,
        rawHeight: Int,
        x: Double,
        y: Double,
    ): Pair<Double, Double> {
        val w = rawWidth.toDouble()
        val h = rawHeight.toDouble()
        return when (orientation) {
            2 -> (w - x) to y
            3 -> (w - x) to (h - y)
            4 -> x to (h - y)
            5 -> y to x
            6 -> (h - y) to x
            7 -> (h - y) to (w - x)
            8 -> y to (w - x)
            else -> x to y
        }
    }

    private fun bounds(points: List<Pair<Double, Double>>, width: Double, height: Double): DoubleArray {
        val left = points.minOf { it.first }.coerceIn(0.0, width)
        val top = points.minOf { it.second }.coerceIn(0.0, height)
        val right = points.maxOf { it.first }.coerceIn(0.0, width)
        val bottom = points.maxOf { it.second }.coerceIn(0.0, height)
        return doubleArrayOf(left, top, (right - left).coerceAtLeast(0.0), (bottom - top).coerceAtLeast(0.0))
    }
}
