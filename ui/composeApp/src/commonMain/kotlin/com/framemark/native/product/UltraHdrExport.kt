package com.framemark.native.product

import kotlin.math.roundToInt

/**
 * Turns an exported SDR JPEG into an Ultra HDR JPEG — but only when the source
 * photo carries a gain map that can be reused *faithfully*.
 *
 * ## The geometry is a crop, not a doubt
 *
 * A gain map is relative to the SDR pixels it was computed from, so reusing it
 * means reproducing those pixels in the same place. The renderer draws the photo
 * with `cover` semantics — and that crop is centre-anchored and deterministic
 * (`engine/e2b/src/main.rs`: `s = max(boxW/sourceW, boxH/sourceH)`, then the
 * source rectangle `((iw - sw)/2, (ih - sh)/2, sw, sh)` fills the box). Because
 * the geometry is fully determined by the two aspect ratios, the Kotlin side can
 * recompute exactly which part of the source was used ([sourceRect]) and carry a
 * correctly aligned gain map even when the card crops the photo.
 *
 * When the box aspect equals the source aspect the rectangle is the whole image;
 * that is also the `contain`/stretch case, so one formula covers every fit mode.
 *
 * ## The user may move that crop
 *
 * The centre above is only the *engine's* default. A [PhotoCrop] — the user's
 * choice of which part of the photo survives — replaces it through
 * [CropGeometry.region], which returns the engine's rectangle unchanged at the
 * defaults. An export that pre-cropped the photo for the renderer must pass the
 * same crop here, or the gain map would be cut by a different rectangle than the
 * SDR pixels were and every highlight would land on the wrong part of the frame.
 */
object UltraHdrExport {

    /** Gain map resolution relative to the canvas: half of each side, like OPPO's. */
    const val GAIN_MAP_DIVISOR = 2

    /** Everything needed to wrap, when the source qualifies. */
    data class Eligible(
        val gainMap: UltraHdr.GainMap,
        val metadata: UltraHdr.Metadata,
        /**
         * Frame size, in the **display** (EXIF-applied) pixels the renderer and
         * the export canvas use. `UltraHdr.frameSize` reports the stored raster;
         * [eligible] converts it before this value exists.
         */
        val sourceWidth: Int,
        val sourceHeight: Int,
        /** Part of the display frame the renderer used: x, y, w, h in display pixels. */
        val sourceRect: DoubleArray,
        /** EXIF orientation of the source file; the composer rotates the gain map by it. */
        val orientation: Int,
    )

    /**
     * Whether [source] can contribute HDR to an export whose photo box is
     * [photoRect] (x, y, w, h in canvas pixels). Null means "export SDR".
     *
     * [orientation] is the source photo's EXIF orientation. It decides the display
     * frame the renderer laid the photo out in, and therefore the space both
     * [photoSrc] and [crop] are expressed in.
     *
     * [photoSrc] is the source rectangle the **engine reported** (`photo_src`,
     * x/y/w/h in display pixels). When present it is used as-is: it is the region
     * that really reached the SDR pixels, so no second crop derivation can
     * disagree with it. When absent, [crop] decides: the gain map has to be cut
     * by exactly that region, or every highlight lands on the wrong pixel, and
     * the default crop reproduces the engine's own centred rectangle.
     */
    fun eligible(
        source: ByteArray,
        photoRect: DoubleArray,
        crop: PhotoCrop = PhotoCrop.Default,
        photoSrc: DoubleArray? = null,
        orientation: Int = 1,
    ): Eligible? {
        if (photoRect.size < 4) return null
        val boxWidth = photoRect[2]
        val boxHeight = photoRect[3]
        if (!(boxWidth > 0.0) || !(boxHeight > 0.0)) return null

        val frame = UltraHdr.frameSize(source) ?: return null
        if (frame.first <= 0 || frame.second <= 0) return null
        // SOF dims are the stored raster; the renderer, the crop UI and the export
        // canvas all speak the post-orientation frame.
        val display = PhotoOrientation.displaySize(frame.first, frame.second, orientation)
        val rect = reportedSourceRect(photoSrc, display.width, display.height)
            ?: CropGeometry.region(display.width, display.height, boxWidth, boxHeight, crop)
            ?: return null

        val gainMap = UltraHdr.locate(source) ?: return null
        val metadata = UltraHdr.metadata(source, gainMap) ?: return null
        return Eligible(
            gainMap = gainMap,
            metadata = metadata,
            sourceWidth = display.width,
            sourceHeight = display.height,
            sourceRect = rect,
            orientation = PhotoOrientation.normalised(orientation),
        )
    }

    /**
     * The engine's reported source rectangle, clamped into the [sourceWidth] x
     * [sourceHeight] display frame; null when it cannot describe a region of
     * *this* file (no report from an older engine, or a rectangle that is empty,
     * not finite, or lies entirely outside the source). Callers then fall back to
     * [CropGeometry.region] — the recomputation this field exists to retire.
     */
    fun reportedSourceRect(
        photoSrc: DoubleArray?,
        sourceWidth: Int,
        sourceHeight: Int,
    ): DoubleArray? {
        if (photoSrc == null || photoSrc.size < 4) return null
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val values = (0..3).map { photoSrc[it] }
        if (values.any { !it.isFinite() }) return null
        val x = values[0]
        val y = values[1]
        val width = values[2]
        val height = values[3]
        if (width <= 0.0 || height <= 0.0) return null
        // The engine draws float pixels into an integer raster: clamp the edges,
        // but refuse a rectangle that misses the image altogether.
        val left = x.coerceAtLeast(0.0)
        val top = y.coerceAtLeast(0.0)
        val right = (x + width).coerceAtMost(sourceWidth.toDouble())
        val bottom = (y + height).coerceAtMost(sourceHeight.toDouble())
        if (!(right > left) || !(bottom > top)) return null
        return doubleArrayOf(left, top, right - left, bottom - top)
    }

    /**
     * The part of a [sourceWidth] x [sourceHeight] photo that the renderer's
     * centre-anchored `cover` crop keeps for a [boxWidth] x [boxHeight] box,
     * as x, y, w, h in source pixels. Callers pass the **display** frame
     * ([PhotoOrientation.displaySize]); the renderer's `photo_src` and its
     * `photoCropX/Y/Zoom` handling use that same frame.
     *
     * When the box aspect equals the source aspect the whole image comes back,
     * which is also what `contain` and stretch layouts produce. Null means the
     * inputs cannot describe a rectangle; the caller then exports SDR.
     *
     * The extent is rounded first and the origin is then centred on that rounded
     * extent, so a crop never drifts by half a pixel. The rectangle is clamped
     * into the image and is never zero-sized, even for a 1 px box.
     */
    fun sourceRect(
        sourceWidth: Int,
        sourceHeight: Int,
        boxWidth: Double,
        boxHeight: Double,
    ): DoubleArray? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        if (!boxWidth.isFinite() || !boxHeight.isFinite() || boxWidth <= 0.0 || boxHeight <= 0.0) return null
        val sourceAspect = sourceWidth.toDouble() / sourceHeight
        val boxAspect = boxWidth / boxHeight
        if (!boxAspect.isFinite() || boxAspect <= 0.0) return null

        // Same branch as the renderer: the box's shorter axis decides which
        // source axis fills the box and which one is cropped.
        val width: Double
        val height: Double
        if (boxAspect < sourceAspect) {
            height = sourceHeight.toDouble()
            width = height * boxAspect
        } else {
            width = sourceWidth.toDouble()
            height = width / boxAspect
        }
        if (!width.isFinite() || !height.isFinite()) return null

        val roundedWidth = width.roundToInt().coerceIn(1, sourceWidth)
        val roundedHeight = height.roundToInt().coerceIn(1, sourceHeight)
        val x = ((sourceWidth - width) / 2.0).roundToInt().coerceIn(0, sourceWidth - roundedWidth)
        val y = ((sourceHeight - height) / 2.0).roundToInt().coerceIn(0, sourceHeight - roundedHeight)
        return doubleArrayOf(
            x.toDouble(),
            y.toDouble(),
            roundedWidth.toDouble(),
            roundedHeight.toDouble(),
        )
    }

    /**
     * The Ultra HDR file, or null when anything does not line up (no gain map,
     * a compositor that could not decode or encode). The caller keeps the plain
     * SDR bytes on null.
     *
     * [orientation] is the source photo's EXIF orientation. The SDR rendition was
     * written from the **oriented** raster (the engine decodes with EXIF applied
     * and the export encoder normalises the tag to 1), so the appended gain map
     * must be rotated into that same display frame before it is cropped;
     * [GainMapJob.orientation] carries it to the platform compositor.
     *
     * [photoSrc] is the engine-reported region the render really sampled and
     * wins over [crop] when present. [crop] must otherwise be the region the
     * renderer used: when the export pre-cropped the photo, pass the same
     * [PhotoCrop] the crop was computed from, so the gain map is cut by
     * [CropGeometry.region] of the **original** file rather than by the centred
     * rectangle. The default reproduces today's bytes.
     */
    fun wrap(
        source: ByteArray,
        sdrJpeg: ByteArray,
        canvasWidth: Int,
        canvasHeight: Int,
        photoRect: DoubleArray,
        crop: PhotoCrop = PhotoCrop.Default,
        photoSrc: DoubleArray? = null,
        orientation: Int,
    ): ByteArray? {
        if (canvasWidth <= 0 || canvasHeight <= 0) return null
        val eligible = eligible(source, photoRect, crop, photoSrc, orientation) ?: return null
        if (eligible.gainMap.offset > Int.MAX_VALUE || eligible.gainMap.size > Int.MAX_VALUE) return null

        val gainMapJpeg = GainMapComposer.compose(
            GainMapJob(
                source = source,
                offset = eligible.gainMap.offset.toInt(),
                size = eligible.gainMap.size.toInt(),
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                photoRect = photoRect,
                sourceRect = eligible.sourceRect,
                sourceWidth = eligible.sourceWidth,
                sourceHeight = eligible.sourceHeight,
                orientation = eligible.orientation,
                sentinel = eligible.gainMap.sentinel,
                divisor = GAIN_MAP_DIVISOR,
            ),
        ) ?: return null

        return UltraHdrAssembler.assemble(sdrJpeg, gainMapJpeg, eligible.metadata)
    }
}
