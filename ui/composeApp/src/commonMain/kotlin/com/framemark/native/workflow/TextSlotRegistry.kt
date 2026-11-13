package com.framemark.native.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Shell-owned snapshot of the text rows the stage renderer reported for the
 * card/photo pair that is on screen right now.
 *
 * [NativeStagePreview] is asynchronous: the render that carries the geometry can
 * land several frames after the user picked a border, and it is also the only
 * place that knows the output pixel size. Keeping the last report in Compose
 * state here means the inspector panel recomposes when the render lands without
 * the shell threading a callback through every intermediate composable.
 *
 * **Final-quality reports win.** The stage also renders a bounded *interactive* tier
 * while the user drags (`product/PreviewTier.kt`); `NativeEngine.preview` marks those
 * rows [TextSlot.provisional]. A provisional report never replaces a final snapshot:
 * the six hand 色卡 templates sample their hex labels from the photo, and the
 * interactive tier decodes a reduced source, so its labels are a mid-drag sample, not
 * the palette (decision 2026-11-14). Before any final frame has landed the provisional
 * rows are held — geometry is identical and the page must not claim the card has no
 * text — and the 文字 page omits their sampled read-only value instead.
 *
 * A snapshot belongs to exactly one pair, so the shell must call [clear] whenever
 * the selected card or the active photo changes; otherwise the previous pair's
 * rows would show up as if they were the current one.
 */
class TextSlotRegistry {
    /** Editable and non-editable rows of the latest report, in renderer order. */
    var slots by mutableStateOf<List<TextSlot>>(emptyList())
        private set

    /** Rendered pixel width the [slots] coordinates are expressed in. */
    var width by mutableIntStateOf(0)
        private set

    /** Rendered pixel height the [slots] coordinates are expressed in. */
    var height by mutableIntStateOf(0)
        private set

    /** True when no renderer report is held (also the state after [clear]). */
    val isEmpty: Boolean get() = slots.isEmpty()

    /** True while [slots] is the provisional interactive-tier report (no final yet). */
    var isProvisional by mutableStateOf(false)
        private set

    /** True after at least one final-quality report landed for this pair. */
    private var hasFinalReport = false

    /**
     * Callback shape of `NativeStagePreview(onTextSlots = …)`. A new final report
     * replaces the previous one instead of merging: a re-render can drop rows, and a
     * stale row must never be offered for editing. A provisional report is taken only
     * when no final report is held yet (first frame of a card), and even then it is
     * flagged so its sampled values are not shown as final.
     */
    fun update(width: Int, height: Int, slots: List<TextSlot>) {
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        if (w <= 0 || h <= 0) {
            // Not a frame report: the failure path of `NativeStagePreview` sends the
            // recovery rows with a 0 × 0 canvas. It replaces what is held (the
            // geometry of a failed render must not stay paired with a new design) and
            // clears the final marker; the next successful render is final again.
            hasFinalReport = false
            isProvisional = false
            this.width = 0
            this.height = 0
            this.slots = slots.toList()
            return
        }
        val provisional = slots.isNotEmpty() && slots.all { it.provisional }
        if (provisional && hasFinalReport) {
            // Keep the last final values through the drag. The palette is sampled from
            // the photo, so this is the difference between "the palette" and "a
            // mid-drag guess"; geometry is identical, so nothing readable is lost.
            return
        }
        hasFinalReport = !provisional
        isProvisional = provisional
        this.width = w
        this.height = h
        this.slots = slots.toList()
    }

    /** Drops the snapshot; the next render reports for the new pair. */
    fun clear() {
        slots = emptyList()
        width = 0
        height = 0
        isProvisional = false
        hasFinalReport = false
    }
}
