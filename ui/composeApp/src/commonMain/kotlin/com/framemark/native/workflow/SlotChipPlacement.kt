package com.framemark.native.workflow

import kotlin.math.max
import kotlin.math.min

/**
 * Where the label chip of the text-row highlight goes, in stage-preview pixels.
 *
 * [x] / [y] are the chip's top-left corner inside the preview box; [flipped] is
 * true only when the chip could not sit in its default "above the row" slot and
 * was moved below it. Draw-only: nothing here is written into the request key,
 * the render cache, the project, a preset or an export.
 */
data class SlotChipPlacement(
    val x: Float,
    val y: Float,
    val flipped: Boolean,
)

/**
 * Grows [rect] by [inflate] on every side, then clamps the result into a
 * `canvasW × canvasH` box.
 *
 * The clamp is why the frame around an edge row still reads as a frame: without
 * it the inflated rectangle would start outside the canvas and the stroke would
 * be cut in half. Degenerate input (non-finite numbers, zero canvas) returns an
 * all-zero rectangle instead of throwing, which callers read as "nothing to
 * draw".
 */
fun inflateHighlightRect(
    rect: FitRectPx,
    inflate: Float,
    canvasW: Float,
    canvasH: Float,
): FitRectPx {
    if (!rect.x.isFinite() || !rect.y.isFinite() || !rect.w.isFinite() || !rect.h.isFinite()) {
        return FitRectPx(0f, 0f, 0f, 0f)
    }
    if (!canvasW.isFinite() || !canvasH.isFinite() || canvasW <= 0f || canvasH <= 0f) {
        return FitRectPx(0f, 0f, 0f, 0f)
    }
    val grow = if (inflate.isFinite()) max(0f, inflate) else 0f
    val left = max(0f, rect.x - grow)
    val top = max(0f, rect.y - grow)
    val right = min(canvasW, rect.x + rect.w + grow)
    val bottom = min(canvasH, rect.y + rect.h + grow)
    return FitRectPx(x = left, y = top, w = max(0f, right - left), h = max(0f, bottom - top))
}

/**
 * Places the highlight's label chip next to the (already inflated) [rect].
 *
 * The contract, in evaluation order — the user has to be able to read the chip
 * without it ever leaving the stage:
 *
 *  1. **Default** above the rect, left-aligned with it: `y = rect.y - gap - chipH`.
 *  2. **Flip below** when there is no room above (`rect.y < chipH + gap`):
 *     `y = rect.y + rect.h + gap`, and [SlotChipPlacement.flipped] is true.
 *  3. **Never enter the bottom reserved band** (`[canvasH - reservedBottom,
 *     canvasH]`, the stage's 经典双行 / 按住看原图 pill row). A chip that would
 *     land in it moves above the rect instead — that is the case of a row inside
 *     the band itself, where "below" would be drawn over the pills. If even the
 *     above position reaches into the band, the chip is pushed up to sit flush on
 *     the band's top edge (it may then overlap the row, never the pills).
 *  4. **Clamp horizontally** into `[0, canvasW - chipW]`, so a row on the left
 *     edge keeps the chip at 0 and a row on the right edge slides it inside.
 *  5. **Clamp vertically** into `[0, canvasH - chipH]`. Only a canvas shorter than
 *     the chip itself can still leave the band uncovered; in that degenerate case
 *     the canvas edge wins.
 *
 * Non-finite or negative sizes are treated as zero, so a broken measurement can
 * never throw from the draw path.
 */
fun placeSlotChip(
    rect: FitRectPx,
    canvasW: Float,
    canvasH: Float,
    chipW: Float,
    chipH: Float,
    reservedBottom: Float = 0f,
    gap: Float = 0f,
): SlotChipPlacement {
    if (!canvasW.isFinite() || !canvasH.isFinite() || canvasW <= 0f || canvasH <= 0f) {
        return SlotChipPlacement(0f, 0f, flipped = false)
    }
    val w = if (chipW.isFinite()) max(0f, chipW) else 0f
    val h = if (chipH.isFinite()) max(0f, chipH) else 0f
    val between = if (gap.isFinite()) max(0f, gap) else 0f
    val band = if (reservedBottom.isFinite()) {
        max(0f, reservedBottom).coerceAtMost(canvasH)
    } else {
        0f
    }
    val bandTop = canvasH - band
    val rectTop = if (rect.y.isFinite()) rect.y else 0f
    val rectHeight = if (rect.h.isFinite()) max(0f, rect.h) else 0f
    val above = rectTop - between - h
    val below = rectTop + rectHeight + between

    // 1 + 2: above by default, below when the row starts too close to the top edge.
    var flipped = rectTop < h + between
    var y = if (flipped) below else above
    // 3: the pill row keeps its band; going below would land on it.
    if (flipped && y + h > bandTop) {
        flipped = false
        y = above
    }
    if (y + h > bandTop) y = bandTop - h
    // 4 + 5: stay on the canvas (a zero-size canvas axis cannot be honoured; see KDoc).
    val maxX = max(0f, canvasW - w)
    val maxY = max(0f, canvasH - h)
    val x = if (rect.x.isFinite()) rect.x.coerceIn(0f, maxX) else 0f
    return SlotChipPlacement(x = x, y = y.coerceIn(0f, maxY), flipped = flipped)
}
