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

    /**
     * Callback shape of `NativeStagePreview(onTextSlots = …)`. A new report
     * replaces the previous one instead of merging: a re-render can drop rows,
     * and a stale row must never be offered for editing.
     */
    fun update(width: Int, height: Int, slots: List<TextSlot>) {
        this.width = width.coerceAtLeast(0)
        this.height = height.coerceAtLeast(0)
        this.slots = slots.toList()
    }

    /** Drops the snapshot; the next render reports for the new pair. */
    fun clear() {
        slots = emptyList()
        width = 0
        height = 0
    }
}
