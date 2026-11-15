package com.framemark.native.product

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Centre of the source: the region the engine keeps on its own. */
internal const val PHOTO_CROP_CENTRE = 0.5

/** `zoom = 1`: the largest region that fills the photo box with no letterboxing. */
internal const val PHOTO_CROP_ZOOM = 1.0

/**
 * Which part of the source photo survives the card's photo box.
 *
 * The renderer draws the photo with `cover` semantics and a **centre-anchored**
 * crop (`UltraHdrExport.sourceRect` reproduces it exactly), so a box whose aspect
 * differs from the photo's always throws away one side. This type is the user's
 * say over *which* side: the kept region is the engine's centred region scaled by
 * [zoom] and moved to ([centerX], [centerY]) — both normalised to the source, so
 * one value means the same thing at any resolution and in any box.
 *
 * It is persisted in the design's existing free-form `params` map
 * ([PhotoCropParams]), never as a document field, and it only *moves* a crop the
 * engine would have applied anyway: at the defaults the renderer's own output is
 * reproduced exactly.
 */
data class PhotoCrop(
    /** Horizontal centre of the kept region, `0` = left edge, `1` = right edge. */
    val centerX: Double = PHOTO_CROP_CENTRE,
    /** Vertical centre of the kept region, `0` = top edge, `1` = bottom edge. */
    val centerY: Double = PHOTO_CROP_CENTRE,
    /** `1` keeps the largest region that fills the box; larger keeps less. */
    val zoom: Double = PHOTO_CROP_ZOOM,
) {
    /** True when this keeps exactly the region the engine would keep by itself. */
    val isDefault: Boolean
        get() = centerX == PHOTO_CROP_CENTRE &&
            centerY == PHOTO_CROP_CENTRE &&
            zoom == PHOTO_CROP_ZOOM

    /**
     * The value the rest of the code may trust: finite numbers, the centre inside
     * the source and [zoom] inside [MIN_ZOOM]..[MAX_ZOOM]. Hand-edited documents and
     * gestures can both produce anything; [region] never trusts raw input either.
     */
    fun normalised(): PhotoCrop {
        val x = if (centerX.isFinite()) centerX.coerceIn(0.0, 1.0) else PHOTO_CROP_CENTRE
        val y = if (centerY.isFinite()) centerY.coerceIn(0.0, 1.0) else PHOTO_CROP_CENTRE
        val z = if (zoom.isFinite()) zoom.coerceIn(MIN_ZOOM, MAX_ZOOM) else PHOTO_CROP_ZOOM
        return PhotoCrop(x, y, z)
    }

    /**
     * Drags the kept region by ([dx], [dy]) as fractions of the source size.
     *
     * The sign is the finger's: dragging the photo to the right shows more of its
     * left side, so the region centre moves left. The caller clamps the result
     * ([CropGeometry.clamped]) because a drag must not push the box off the photo.
     */
    fun panned(dx: Double, dy: Double): PhotoCrop {
        if (!dx.isFinite() || !dy.isFinite()) return this
        return copy(centerX = centerX - dx, centerY = centerY - dy)
    }

    /** Multiplies [zoom], clamped into [MIN_ZOOM]..[MAX_ZOOM]. */
    fun zoomed(factor: Double): PhotoCrop {
        if (!factor.isFinite() || factor <= 0.0) return this
        val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        return if (next == zoom) this else copy(zoom = next)
    }

    /**
     * Zooms so the source point at ([atX], [atY]) — normalised, i.e. a pinch
     * centroid — stays where it is under the finger. The kept region is a window
     * over a fixed photo, so anchoring only moves the centre: an offset from the
     * centre scales as `zoom / nextZoom`.
     */
    fun zoomedAround(factor: Double, atX: Double, atY: Double): PhotoCrop {
        if (!factor.isFinite() || factor <= 0.0) return this
        if (!atX.isFinite() || !atY.isFinite()) return zoomed(factor)
        val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == zoom) return this
        val ratio = zoom / next
        return PhotoCrop(
            centerX = atX - (atX - centerX) * ratio,
            centerY = atY - (atY - centerY) * ratio,
            zoom = next,
        )
    }

    /**
     * 居中自动吸附: a centre within [toleranceX]/[toleranceY] of the middle (as
     * fractions of the source) is pulled exactly onto it. Called on release, never
     * while the finger is down, so the drag cannot fight the snap.
     */
    fun snapped(toleranceX: Double, toleranceY: Double): PhotoCrop {
        val tx = toleranceX.coerceAtLeast(0.0)
        val ty = toleranceY.coerceAtLeast(0.0)
        return copy(
            centerX = if (abs(centerX - PHOTO_CROP_CENTRE) <= tx) PHOTO_CROP_CENTRE else centerX,
            centerY = if (abs(centerY - PHOTO_CROP_CENTRE) <= ty) PHOTO_CROP_CENTRE else centerY,
        )
    }

    companion object {
        /** The engine's own crop; the value every document without crop keys reads as. */
        val Default: PhotoCrop = PhotoCrop()

        const val MIN_ZOOM: Double = PHOTO_CROP_ZOOM

        /**
         * The deepest zoom the UI offers. Beyond a few times the kept region is a
         * handful of pixels wide, so the export would be an upscale rather than a
         * crop; 8 is still "pick a detail" without inviting that.
         */
        const val MAX_ZOOM: Double = 8.0
    }
}

/**
 * Geometry of a [PhotoCrop] against one box.
 *
 * Everything here is aspect-driven and pixel-exact in the **display frame** — the
 * EXIF-applied pixels the renderer lays the photo out in (`photo_src`,
 * `photoCropX/Y/Zoom`) and the frame the crop editor draws. Callers pass
 * `PhotoOrientation.displaySize(...)` for the source; passing the stored raster
 * size instead would compute the whole crop one rotation away for orientations
 * 5..8.
 */
object CropGeometry {

    /**
     * How different two aspects must be before a box counts as "cropping".
     * 0.25 % is below a pixel at every size the app ships and above the rounding a
     * proportional box (engine canvas pixels) can introduce.
     */
    const val ASPECT_EPSILON: Double = 0.0025

    /**
     * The part of a [sourceWidth] x [sourceHeight] photo [crop] keeps for a
     * [boxWidth] x [boxHeight] box, as x, y, w, h in **display** pixels — pass
     * `PhotoOrientation.displaySize(raw, orientation)` as the source size.
     *
     * At the defaults this **is** [UltraHdrExport.sourceRect] — it delegates to it,
     * so an export with no crop cannot drift from the engine by a single pixel.
     * For a moved/scaled crop the extent is the base extent divided by `zoom` and
     * the origin is that extent centred on the requested point, rounded the same
     * way and clamped into the image; it is never empty and never leaves it.
     *
     * Null when the source or the box cannot describe a rectangle; callers then
     * keep the engine's own behaviour (or export SDR).
     */
    fun region(
        sourceWidth: Int,
        sourceHeight: Int,
        boxWidth: Double,
        boxHeight: Double,
        crop: PhotoCrop,
    ): DoubleArray? {
        val base = UltraHdrExport.sourceRect(sourceWidth, sourceHeight, boxWidth, boxHeight)
            ?: return null
        val safe = crop.normalised()
        if (safe.isDefault) return base

        val width = base[2] / safe.zoom
        val height = base[3] / safe.zoom
        if (!width.isFinite() || !height.isFinite() || width <= 0.0 || height <= 0.0) return null

        val roundedWidth = width.roundToInt().coerceIn(1, sourceWidth)
        val roundedHeight = height.roundToInt().coerceIn(1, sourceHeight)
        val centreX = safe.centerX * sourceWidth
        val centreY = safe.centerY * sourceHeight
        val x = (centreX - width / 2.0).roundToInt().coerceIn(0, sourceWidth - roundedWidth)
        val y = (centreY - height / 2.0).roundToInt().coerceIn(0, sourceHeight - roundedHeight)
        return doubleArrayOf(
            x.toDouble(),
            y.toDouble(),
            roundedWidth.toDouble(),
            roundedHeight.toDouble(),
        )
    }

    /**
     * True when the box cannot show the whole photo, i.e. when the engine's `cover`
     * crop throws something away and the user therefore has something to choose.
     * Aspect-equal boxes (and differences below [epsilon], which are one rounding
     * step) keep the whole image and get no crop UI.
     */
    fun cropsSource(
        sourceWidth: Int,
        sourceHeight: Int,
        boxWidth: Double,
        boxHeight: Double,
        epsilon: Double = ASPECT_EPSILON,
    ): Boolean {
        val base = UltraHdrExport.sourceRect(sourceWidth, sourceHeight, boxWidth, boxHeight)
            ?: return false
        if (base[2] >= sourceWidth && base[3] >= sourceHeight) return false
        val sourceAspect = sourceWidth.toDouble() / sourceHeight
        val boxAspect = boxWidth / boxHeight
        if (!boxAspect.isFinite() || boxAspect <= 0.0 || !sourceAspect.isFinite()) return false
        val relative = abs(sourceAspect - boxAspect) / max(sourceAspect, boxAspect)
        return relative > epsilon.coerceAtLeast(0.0)
    }

    /**
     * Allowed centre range at [zoom] so the kept region stays inside the source:
     * `[minX, maxX, minY, maxY]`, all normalised. A region that already fills an
     * axis pins that axis to the middle (`minX == maxX == 0.5`).
     */
    fun centreLimits(
        sourceWidth: Int,
        sourceHeight: Int,
        boxWidth: Double,
        boxHeight: Double,
        zoom: Double,
    ): DoubleArray? {
        val base = UltraHdrExport.sourceRect(sourceWidth, sourceHeight, boxWidth, boxHeight)
            ?: return null
        val safeZoom = if (zoom.isFinite()) zoom.coerceIn(PhotoCrop.MIN_ZOOM, PhotoCrop.MAX_ZOOM) else 1.0
        val halfW = base[2] / safeZoom / 2.0 / sourceWidth
        val halfH = base[3] / safeZoom / 2.0 / sourceHeight
        return doubleArrayOf(
            min(halfW, 0.5),
            max(1.0 - halfW, 0.5),
            min(halfH, 0.5),
            max(1.0 - halfH, 0.5),
        )
    }

    /** [crop] with its centre pulled back inside [centreLimits] for its own zoom. */
    fun clamped(
        sourceWidth: Int,
        sourceHeight: Int,
        boxWidth: Double,
        boxHeight: Double,
        crop: PhotoCrop,
    ): PhotoCrop {
        val safe = crop.normalised()
        val limits = centreLimits(sourceWidth, sourceHeight, boxWidth, boxHeight, safe.zoom)
            ?: return safe
        return safe.copy(
            centerX = safe.centerX.coerceIn(limits[0], limits[1]),
            centerY = safe.centerY.coerceIn(limits[2], limits[3]),
        )
    }
}

/**
 * The crop keys inside `PhotoDesign.params` — the document's one free-form float
 * map, so the frozen document format keeps its schema and no field is added.
 *
 * The three keys are **omitted at their defaults**. That is what makes the choice
 * backward compatible in both directions: a document written before this feature
 * has no crop keys and re-encodes byte for byte (`WireFormatFreezeTest`), and a
 * document whose user never opened the crop UI never gains one either.
 */
object PhotoCropParams {
    const val X: String = "photoCropX"
    const val Y: String = "photoCropY"
    const val ZOOM: String = "photoCropZoom"

    /** The design's crop; a document without the keys reads as the engine default. */
    fun read(params: Map<String, Any?>): PhotoCrop = PhotoCrop(
        centerX = number(params[X]) ?: PHOTO_CROP_CENTRE,
        centerY = number(params[Y]) ?: PHOTO_CROP_CENTRE,
        zoom = number(params[ZOOM]) ?: PHOTO_CROP_ZOOM,
    ).normalised()

    fun read(design: PhotoDesign): PhotoCrop = read(design.params)

    /** True when the design keeps a region the engine would not have kept. */
    fun hasCrop(params: Map<String, Any?>): Boolean = !read(params).isDefault

    /**
     * [design] with [crop] written into `params`, default values removed.
     *
     * Returns the same instance when nothing changes, so a 重置 of an already
     * default design cannot mark it edited, cannot add a key, and cannot create an
     * undo step.
     */
    fun write(design: PhotoDesign, crop: PhotoCrop): PhotoDesign {
        val safe = crop.normalised()
        val values = LinkedHashMap<String, Any?>(3)
        if (safe.centerX != PHOTO_CROP_CENTRE) values[X] = safe.centerX
        if (safe.centerY != PHOTO_CROP_CENTRE) values[Y] = safe.centerY
        if (safe.zoom != PHOTO_CROP_ZOOM) values[ZOOM] = safe.zoom
        // Keys that are present but no longer wanted are removed by writing null:
        // `DesignValues.normalize` drops null values, so the key disappears instead
        // of becoming `0`/`false`.
        val removals = listOf(X, Y, ZOOM)
            .filter { it in design.params && it !in values }
            .associateWith { null }
        if (values.isEmpty() && removals.isEmpty()) return design
        val next = design.withParams(values + removals)
        return if (next.params == design.params) design else next
    }

    private fun number(raw: Any?): Double? = when (raw) {
        is Double -> raw.takeIf { it.isFinite() }
        is Float -> raw.takeIf { it.isFinite() }?.toDouble()
        is Number -> raw.toDouble().takeIf { it.isFinite() }
        is String -> raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }
}
