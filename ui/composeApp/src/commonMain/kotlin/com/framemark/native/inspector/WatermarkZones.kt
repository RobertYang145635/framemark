package com.framemark.native.inspector

import com.framemark.native.workflow.WatermarkSpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * WP-C §4.4: the 3×3 watermark zones and the margin math behind the pane.
 *
 * Rules (report §9.1, HANDOFF part 2 §3.8, HANDOFF WP-C row):
 *  - the grid picks the cell; then only the margins of that cell are editable;
 *  - a centred direction is fixed: its offset is treated as **0** for display
 *    and for calculation, and a legacy preset that carries an offset there is
 *    never rewritten until the user edits the watermark;
 *  - the watermark may only move inside its own zone (quarter / half / full);
 *  - the width cap follows the column: 47 % on left/right, 60 % in the middle;
 *  - distances are "distance to the chosen edge" in % of the photo **width** on
 *    both axes (report §9.1), so equal numbers look equal.
 *
 * The data model (`anchor + dx/dy`, `nudged` / `withRectNudge`) is read-only;
 * this file converts between it and the UI, and reuses `withRectNudge` so a
 * dragged watermark can never leave the photo.
 */
enum class WatermarkEdge { Left, Right, Top, Bottom }

/** One cell's editable axes, zone and caps. All coordinates are normalised. */
data class WatermarkZone(
    val anchor: Int,
    val column: Int,
    val row: Int,
    /** Left/right margin is editable (false for the middle column). */
    val hasHorizontal: Boolean,
    /** Top/bottom margin is editable (false for the middle row). */
    val hasVertical: Boolean,
    val horizontalEdge: WatermarkEdge?,
    val verticalEdge: WatermarkEdge?,
    /** Zone the watermark rect must stay inside: x ∈ [xMin, xMax]. */
    val xMin: Float,
    val xMax: Float,
    /** Zone the watermark rect must stay inside: y ∈ [yMin, yMax]. */
    val yMin: Float,
    val yMax: Float,
    /** Width cap for this column: 0.47 left/right, 0.60 middle. */
    val widthCap: Float,
) {
    val fixedX: Boolean get() = !hasHorizontal
    val fixedY: Boolean get() = !hasVertical
    /** The centre cell cannot move at all; the pane explains that instead. */
    val pinned: Boolean get() = fixedX && fixedY
}

internal object WatermarkZones {

    /** Report §9.1: 1/2 of the photo width for the side columns is the budget. */
    const val SIDE_WIDTH_CAP = 0.47f

    /** Report §9.1: the middle column may use 60 %. */
    const val MIDDLE_WIDTH_CAP = 0.60f

    /** Model default (product/workflow `WatermarkSpec.MARGIN`), in width units. */
    const val DEFAULT_MARGIN = 0.05f
    const val DEFAULT_WIDTH = 0.25f
    const val DEFAULT_OPACITY = 0.8f
    const val DEFAULT_ANCHOR = 8

    /** Cell coordinates: 0 = top/left, 1 = centre, 2 = bottom/right. */
    fun columnOf(anchor: Int): Int = (anchor.coerceIn(0, 8)) % 3
    fun rowOf(anchor: Int): Int = (anchor.coerceIn(0, 8)) / 3

    fun zoneFor(anchor: Int): WatermarkZone {
        val col = columnOf(anchor)
        val row = rowOf(anchor)
        val horizontalEdge = when (col) {
            0 -> WatermarkEdge.Left
            2 -> WatermarkEdge.Right
            else -> null
        }
        val verticalEdge = when (row) {
            0 -> WatermarkEdge.Top
            2 -> WatermarkEdge.Bottom
            else -> null
        }
        return WatermarkZone(
            anchor = anchor.coerceIn(0, 8),
            column = col,
            row = row,
            hasHorizontal = horizontalEdge != null,
            hasVertical = verticalEdge != null,
            horizontalEdge = horizontalEdge,
            verticalEdge = verticalEdge,
            xMin = if (col == 2) 0.5f else 0f,
            xMax = if (col == 0) 0.5f else 1f,
            yMin = if (row == 2) 0.5f else 0f,
            yMax = if (row == 0) 0.5f else 1f,
            widthCap = if (col == 1) MIDDLE_WIDTH_CAP else SIDE_WIDTH_CAP,
        )
    }

    fun widthCapFor(anchor: Int): Float = zoneFor(anchor).widthCap

    /** Watermark height as a fraction of the photo height, from its own aspect. */
    fun watermarkHeight(width: Float, photoAspect: Float, assetAspect: Float): Float {
        val safeAsset = if (assetAspect.isFinite() && assetAspect > 0.01f) assetAspect else 1f
        val w = width.coerceAtLeast(0.01f)
        return (w * safeAspect(photoAspect) / safeAsset).coerceIn(0.02f, 1f)
    }

    private fun safeAspect(aspect: Float): Float =
        if (aspect.isFinite() && aspect > 0.01f) aspect else 1.5f

    /** Display spec: the centred axis offsets are treated as 0, never written. */
    fun normalizedOffsets(spec: WatermarkSpec): Pair<Float, Float> {
        val col = columnOf(spec.anchor)
        val row = rowOf(spec.anchor)
        return (if (col == 1) 0f else spec.dx) to (if (row == 1) 0f else spec.dy)
    }

    /** Same rule when the UI only has the three numbers. */
    fun normalizedOffsets(anchor: Int, dx: Float, dy: Float): Pair<Float, Float> =
        (if (columnOf(anchor) == 1) 0f else dx) to (if (rowOf(anchor) == 1) 0f else dy)

    private fun box(spec: WatermarkSpec, photoAspect: Float): FloatArray {
        val (dx, dy) = normalizedOffsets(spec)
        return spec.copy(dx = dx, dy = dy).aabb(photoAspect)
    }

    /** Current margin (in % of photo width) for [edge]. */
    fun marginPct(spec: WatermarkSpec, photoAspect: Float, edge: WatermarkEdge): Float {
        val b = box(spec, photoAspect)
        val aspect = safeAspect(photoAspect)
        val fraction = when (edge) {
            WatermarkEdge.Left -> b[0]
            WatermarkEdge.Right -> 1f - (b[0] + b[2])
            WatermarkEdge.Top -> b[1] / aspect
            WatermarkEdge.Bottom -> (1f - (b[1] + b[3])) / aspect
        }
        return (fraction * 100f).coerceAtLeast(0f)
    }

    /**
     * Largest margin this cell can show, in % of photo width: the watermark may
     * only move until it touches the zone's far edge (report §9.1: "最大值到
     * 水印碰到中线为止").
     *
     * Returns 0 when the watermark is already too tall/wide for the zone — the
     * pane then shows a fixed position with an explanation instead of an
     * impossible slider.
     */
    fun maxMarginPct(
        anchor: Int,
        width: Float,
        photoAspect: Float,
        assetAspect: Float,
        edge: WatermarkEdge,
    ): Float {
        val zone = zoneFor(anchor)
        val w = width.coerceIn(0.01f, zone.widthCap)
        val h = watermarkHeight(w, photoAspect, assetAspect)
        val aspect = safeAspect(photoAspect)
        val fraction = when (edge) {
            WatermarkEdge.Left -> max(0f, zone.xMax - w)
            WatermarkEdge.Right -> max(0f, 1f - zone.xMin - w)
            WatermarkEdge.Top -> max(0f, zone.yMax - h) / aspect
            WatermarkEdge.Bottom -> max(0f, 1f - zone.yMin - h) / aspect
        }
        return fraction * 100f
    }

    /**
     * Absolute `dx/dy` for placing the watermark with [margin] on [edge].
     *
     * The other axis keeps its current offset, except on a centred direction
     * where it is written back as 0 (HANDOFF §3.8: only after a user edit).
     */
    fun positionFor(
        spec: WatermarkSpec,
        photoAspect: Float,
        edge: WatermarkEdge,
        marginPct: Float,
    ): Pair<Float, Float> {
        val zone = zoneFor(spec.anchor)
        val b = box(spec, photoAspect)
        val aspect = safeAspect(photoAspect)
        val width = b[2]
        val height = b[3]
        val margin = (marginPct.coerceAtLeast(0f)) / 100f
        var x = b[0]
        var y = b[1]
        when (edge) {
            WatermarkEdge.Left -> x = margin
            WatermarkEdge.Right -> x = 1f - margin - width
            WatermarkEdge.Top -> y = margin * aspect
            WatermarkEdge.Bottom -> y = 1f - margin * aspect - height
        }
        // Stay inside the cell's zone; if the zone is smaller than the watermark
        // the target collapses to the zone start instead of producing a NaN range.
        val xLo = max(0f, zone.xMin)
        val xHi = max(xLo, min(1f - width, zone.xMax - width))
        val yLo = max(0f, zone.yMin)
        val yHi = max(yLo, min(1f - height, zone.yMax - height))
        x = x.coerceIn(xLo, xHi)
        y = y.coerceIn(yLo, yHi)
        val targetDx = spec.dx + (x - b[0])
        val targetDy = spec.dy + (y - b[1])
        val (ndx, ndy) = normalizedOffsets(spec.anchor, targetDx, targetDy)
        return ndx to ndy
    }

    /**
     * Drag on the stage: [dxPx]/[dyPx] are pointer deltas inside the photo box,
     * [photoWidthPx]/[photoHeightPx] its pixel size. Reuses
     * [WatermarkSpec.withRectNudge] and then clamps to the cell's zone, so a drag
     * can never leave the quadrant/half the user picked.
     */
    fun moveByPixels(
        spec: WatermarkSpec,
        photoAspect: Float,
        dxPx: Float,
        dyPx: Float,
        photoWidthPx: Float,
        photoHeightPx: Float,
    ): Pair<Float, Float> {
        if (photoWidthPx <= 0f || photoHeightPx <= 0f) return normalizedOffsets(spec)
        val nudged = spec
            .copy(dx = normalizedOffsets(spec).first, dy = normalizedOffsets(spec).second)
            .withRectNudge(dxPx / photoWidthPx, dyPx / photoHeightPx, photoAspect)
        val zone = zoneFor(spec.anchor)
        val b = nudged.aabb(photoAspect)
        val xLo = max(0f, zone.xMin)
        val xHi = max(xLo, min(1f - b[2], zone.xMax - b[2]))
        val yLo = max(0f, zone.yMin)
        val yHi = max(yLo, min(1f - b[3], zone.yMax - b[3]))
        val x = b[0].coerceIn(xLo, xHi)
        val y = b[1].coerceIn(yLo, yHi)
        val targetDx = nudged.dx + (x - b[0])
        val targetDy = nudged.dy + (y - b[1])
        return normalizedOffsets(spec.anchor, targetDx, targetDy)
    }

    /** True when the watermark rect still lies inside its cell's zone. */
    fun isInsideZone(spec: WatermarkSpec, photoAspect: Float): Boolean {
        val zone = zoneFor(spec.anchor)
        val b = box(spec, photoAspect)
        return b[0] >= zone.xMin - 1e-4f &&
            b[0] + b[2] <= zone.xMax + 1e-4f &&
            b[1] >= zone.yMin - 1e-4f &&
            b[1] + b[3] <= zone.yMax + 1e-4f
    }

    /** A width that respects the column cap; a new column pulls an excess back. */
    fun widthFor(spec: WatermarkSpec): Float = spec.width.coerceIn(0.05f, widthCapFor(spec.anchor))

    /**
     * Changed-item count for the 水印 tab badge: anchor, width, opacity and each
     * movable offset against the model defaults.
     */
    fun modifiedCount(spec: WatermarkSpec?): Int {
        if (spec == null) return 0
        var n = 0
        if (spec.anchor != DEFAULT_ANCHOR) n++
        if (abs(spec.width - DEFAULT_WIDTH) > 1e-4f) n++
        if (abs(spec.opacity - DEFAULT_OPACITY) > 1e-4f) n++
        val (dx, dy) = normalizedOffsets(spec)
        if (abs(dx) > 1e-4f) n++
        if (abs(dy) > 1e-4f) n++
        return n
    }

    /** Margin value for a cell axis, or null when that axis is fixed. */
    fun edgeFor(anchor: Int, vertical: Boolean): WatermarkEdge? {
        val zone = zoneFor(anchor)
        return if (vertical) zone.verticalEdge else zone.horizontalEdge
    }
}
