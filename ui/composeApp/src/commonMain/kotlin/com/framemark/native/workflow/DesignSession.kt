package com.framemark.native.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.product.ExportSettings
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// The export choices are the product document's own type (product/ExportSettings.kt).
// This file used to declare a second `ExportSettings` with a different default long
// edge; the session and the export sheet now carry the product type, and the only
// UI-level difference is how a missing (`null`) long edge is displayed.


/**
 * One undoable step: the editor state **before** the gesture.
 *
 * A step carries the border as well as the control values. Switching a border is a
 * user gesture like any other and has to be undoable (2026-11 UX audit P0-2); the
 * old `history.clear()` made 「换错了」 unrecoverable *and* threw away every edit
 * made before the switch. Because the border travels with the values, undoing a
 * switch puts the previous border back together with the values it had.
 */
data class DesignStep(
    val values: Map<String, Any>,
    /** Border these values belong to; null for steps taken before any border existed. */
    val cardId: String? = null,
)

/**
 * Undo/redo over control values + the border they belong to (no renderer, no engine).
 *
 * A step is one user gesture: writes to the same control inside [MERGE_WINDOW_MS]
 * merge into the first step, so typing a name or dragging a slider is one undo.
 * A border switch is recorded with [recordBorder] and is **always** its own step —
 * it must not merge with a neighbouring slider drag, or 「连换 3 张，撤销回上一张」
 * would jump three borders back.
 * The stack is capped at [limit]; undo/redo states are exposed as Compose state.
 */
class DesignHistory(private val limit: Int = 50) {
    private val undoStack = ArrayDeque<DesignStep>()
    private val redoStack = ArrayDeque<DesignStep>()
    private var lastKey: String? = null
    private var lastMark: TimeMark? = null

    /**
     * Border the live design belongs to. Every recorded step carries it, so an undo
     * that crosses a border switch can hand the old border back to the shell
     * (`DesignSession.onCardRestored`). The session keeps this in sync.
     */
    var currentCardId: String? = null
        internal set

    var undoCount by mutableStateOf(0)
        private set
    var redoCount by mutableStateOf(0)
        private set

    /**
     * True when the newest step was a border switch. The stage reads it for the
     * one-shot 「已换边框 · 撤销」 notice; any undo/redo clears it (that gesture is
     * already its own feedback).
     */
    var lastStepIsBorder by mutableStateOf(false)
        private set

    val canUndo: Boolean get() = undoCount > 0
    val canRedo: Boolean get() = redoCount > 0

    fun record(before: Map<String, Any>, key: String?) {
        val now = TimeSource.Monotonic.markNow()
        val merge = key == lastKey &&
            lastMark?.let { it.elapsedNow().inWholeMilliseconds < MERGE_WINDOW_MS } == true
        // Any new step invalidates redo, even a merged one.
        redoStack.clear()
        if (!merge) push(DesignStep(before, currentCardId))
        lastKey = key
        lastMark = now
        lastStepIsBorder = false
        syncCounts()
    }

    /**
     * A border switch: one discrete step, never merged.
     *
     * The merge window is reset as well, so the user's very next edit starts a step
     * of its own instead of folding into the switch.
     */
    fun recordBorder(before: Map<String, Any>) {
        redoStack.clear()
        push(DesignStep(before, currentCardId))
        lastKey = null
        lastMark = null
        lastStepIsBorder = true
        syncCounts()
    }

    fun undo(current: DesignStep): DesignStep? {
        val before = undoStack.removeLastOrNull() ?: return null
        pushRedo(current)
        resetWindow()
        lastStepIsBorder = false
        syncCounts()
        return before
    }

    fun redo(current: DesignStep): DesignStep? {
        val after = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        while (undoStack.size > limit) undoStack.removeFirst()
        resetWindow()
        lastStepIsBorder = false
        syncCounts()
        return after
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastKey = null
        lastMark = null
        lastStepIsBorder = false
        syncCounts()
    }

    private fun push(step: DesignStep) {
        undoStack.addLast(step)
        while (undoStack.size > limit) undoStack.removeFirst()
    }

    private fun pushRedo(step: DesignStep) {
        redoStack.addLast(step)
        while (redoStack.size > limit) redoStack.removeFirst()
    }

    private fun resetWindow() {
        lastKey = null
        lastMark = null
    }

    private fun syncCounts() {
        undoCount = undoStack.size
        redoCount = redoStack.size
    }

    private companion object {
        const val MERGE_WINDOW_MS = 700L
    }
}

/**
 * Design draft shared by the editor, the toolbar, the preset sheet and the export
 * sheet. Values are keyed by the inspector's per-card control key, so switching
 * cards keeps each card's edits; a preset stores the whole map.
 *
 * Nothing here renders: the preview stays the static border image.
 */
class DesignSession {
    val values = mutableStateMapOf<String, Any>()
    private val initialValues = mutableMapOf<String, Any>()
    val history = DesignHistory()

    /** Write-through hook: Project is the source of truth; the session is a view. */
    var onValuesChanged: ((values: Map<String, Any>, overrides: Map<String, Any>) -> Unit)? = null
    var onExportChanged: ((ExportSettings) -> Unit)? = null

    /**
     * Undo/redo landed on a step taken on a **different border**: the shell has to
     * re-point the stage at it (one code path for phone and desktop). Null means the
     * step had no border at all (未选边框).
     */
    var onCardRestored: ((String?) -> Unit)? = null

    /** Border the live [values] belong to; the shell keeps it in sync while browsing. */
    var cardId: String? = null
        private set

    private var exportState by mutableStateOf(ExportSettings())
    var export: ExportSettings
        get() = exportState
        set(value) {
            if (value == exportState) return
            exportState = value
            onExportChanged?.invoke(value)
        }

    var lastExport by mutableStateOf<ExportResult?>(null)
    var exporting by mutableStateOf(false)

    /** Seeds declared values for a card. Existing user values are never overwritten. */
    fun seed(controls: List<InspectorControl>) {
        controls.forEach { control ->
            val declared = plainValue(control.initial)
            if (!initialValues.containsKey(control.key)) initialValues[control.key] = declared
            if (!values.containsKey(control.key)) values[control.key] = declared
        }
    }

    /**
     * Hydrates the view from a project design without emitting a write-back.
     *
     * [clearHistory] is off for a re-hydrate that only follows a **border switch**:
     * the switch is itself an undoable step now (P0-2), and wiping the stack here
     * would delete the very step the user needs to go back. A photo switch keeps the
     * clearing — undoing one photo's values onto another photo is not a thing.
     */
    fun replaceValues(next: Map<String, Any>, clearHistory: Boolean = true) {
        values.clear()
        values.putAll(next)
        if (clearHistory) history.clear()
    }

    /** Hydrates newly discovered controls without overwriting edits in progress. */
    fun mergeMissingValues(next: Map<String, Any>) {
        next.forEach { (key, value) ->
            if (!values.containsKey(key)) values[key] = value
        }
    }

    /** Sets export options without notifying the project (used while hydrating). */
    fun setExportSilently(next: ExportSettings) {
        if (next != exportState) exportState = next
    }

    fun value(control: InspectorControl): Any =
        values[control.key] ?: initialValues[control.key] ?: plainValue(control.initial)

    fun isModified(control: InspectorControl): Boolean =
        value(control) != (initialValues[control.key] ?: plainValue(control.initial))

    fun set(control: InspectorControl, next: Any) {
        if (value(control) == next) return
        history.record(values.toMap(), control.key)
        values[control.key] = next
        notifyChanged()
    }

    /** Restores one control to its declared value and records the action for undo. */
    fun resetControl(control: InspectorControl) {
        val initial = initialValues[control.key] ?: plainValue(control.initial)
        if (value(control) == initial) return
        history.record(values.toMap(), control.key)
        values[control.key] = initial
        notifyChanged()
    }

    fun undo(): Boolean {
        val restored = history.undo(DesignStep(values.toMap(), cardId)) ?: return false
        applyStep(restored)
        return true
    }

    fun redo(): Boolean {
        val restored = history.redo(DesignStep(values.toMap(), cardId)) ?: return false
        applyStep(restored)
        return true
    }

    /** Number of controls of [cardId] that currently differ from their declared value. */
    fun adjustedCount(cardId: String): Int {
        val prefix = "$cardId::"
        return values.count { (key, value) ->
            key.startsWith(prefix) && initialValues[key] != null && initialValues[key] != value
        }
    }

    fun snapshotValues(): Map<String, Any> = values.toMap()

    /**
     * Values the user changed away from their seeded/declared value, keyed by
     * control key. Preset-loaded values count as changes even before seeding.
     */
    fun overrides(cardId: String): Map<String, Any> {
        val prefix = "$cardId::"
        return values.filter { (key, value) ->
            key.startsWith(prefix) && initialValues[key] != value
        }
    }

    /** True when the card has any user value that differs from its declared one. */
    fun hasOverrides(cardId: String): Boolean = overrides(cardId).isNotEmpty()

    /** Drops every override of one card (undoable); watermark and photo stay. */
    fun resetCard(cardId: String) {
        val prefix = "$cardId::"
        if (!values.keys.any { it.startsWith(prefix) && initialValues[it] != values[it] }) return
        history.record(values.toMap(), null)
        values.keys.filter { it.startsWith(prefix) }.forEach { key ->
            val declared = initialValues[key]
            if (declared != null) values[key] = declared else values.remove(key)
        }
        notifyChanged()
    }

    /** Loads a saved preset; the caller switches the selected border if needed. */
    fun applyPreset(preset: Preset) {
        values.clear()
        preset.values.forEach { (key, value) -> values[key] = coerceNumber(value) }
        export = preset.export
        lastExport = null
        history.clear()
        // A preset names its own border; the caller's `selectedCard` follows, so the
        // switch must not be recorded as a step on top of the fresh, empty history.
        cardId = preset.cardId
        history.currentCardId = preset.cardId
        notifyChanged()
    }

    /**
     * The user moved to another border.
     *
     * One undoable step, recorded **before** the switch so undo lands on the border
     * the user came from (P0-2). The stack is kept: a border switch must not be a
     * one-way door that also costs every earlier edit.
     */
    fun onCardChanged(nextCardId: String?) {
        if (nextCardId == cardId) return
        if (nextCardId == null) {
            // 没有边框就没有可回退的一步（清空照片会走到这里）：只清指针，历史留着。
            cardId = null
            history.currentCardId = null
            return
        }
        history.recordBorder(values.toMap())
        cardId = nextCardId
        history.currentCardId = nextCardId
    }

    private fun applyStep(step: DesignStep) {
        val borderChanged = step.cardId != cardId
        cardId = step.cardId
        history.currentCardId = step.cardId
        restore(step.values)
        if (borderChanged) onCardRestored?.invoke(step.cardId)
    }

    private fun restore(snapshot: Map<String, Any>) {
        values.clear()
        values.putAll(snapshot)
        notifyChanged()
    }

    private fun notifyChanged() {
        val snapshot = values.toMap()
        val overrides = snapshot.filter { (key, value) -> initialValues[key] != value }
        onValuesChanged?.invoke(snapshot, overrides)
    }

    private fun plainValue(value: ControlValue): Any = when (value) {
        is ControlValue.Flag -> value.value
        is ControlValue.Num -> value.value
        is ControlValue.Str -> value.value
    }

    private fun coerceNumber(value: Any): Any = when (value) {
        is Double -> value.toFloat()
        is Float -> value
        else -> value
    }
}
