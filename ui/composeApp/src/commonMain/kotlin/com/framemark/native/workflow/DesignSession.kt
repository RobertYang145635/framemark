package com.framemark.native.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorControl
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Export choices shown in the export sheet and carried by presets. */
data class ExportSettings(
    val format: SampleFormat = SampleFormat.Jpeg,
    /** Effective encoding quality; desktop chooses a local preference, Android uses 100. */
    val quality: Int = 100,
    /** Original size (no downscale) is the default; a custom long edge is opt-in. */
    val sizeMode: ExportSizeMode = ExportSizeMode.Original,
    /** Long edge of the generated image, in pixels. Only used when [sizeMode] is LongEdge. */
    val longEdge: Int = 1600,
    val filenamePattern: String = "{photo}-{index}",
    /** WebP VP8L (lossless) vs VP8 q100 (lossy); ignored by JPEG/PNG. */
    val webpLossless: Boolean = true,
) {
    /** Long edge handed to the engine; null means "render at the engine's native canvas". */
    val effectiveLongEdge: Int? get() = if (sizeMode == ExportSizeMode.LongEdge) longEdge else null
}

/** Whether the export keeps the engine's native canvas or resamples to a long edge. */
enum class ExportSizeMode { Original, LongEdge }


/**
 * Undo/redo over control values only (no renderer, no engine).
 *
 * A step is one user gesture: writes to the same control inside [MERGE_WINDOW_MS]
 * merge into the first step, so typing a name or dragging a slider is one undo.
 * The stack is capped at [limit]; undo/redo states are exposed as Compose state.
 */
class DesignHistory(private val limit: Int = 50) {
    private val undoStack = ArrayDeque<Map<String, Any>>()
    private val redoStack = ArrayDeque<Map<String, Any>>()
    private var lastKey: String? = null
    private var lastMark: TimeMark? = null

    var undoCount by mutableStateOf(0)
        private set
    var redoCount by mutableStateOf(0)
        private set

    val canUndo: Boolean get() = undoCount > 0
    val canRedo: Boolean get() = redoCount > 0

    fun record(before: Map<String, Any>, key: String?) {
        val now = TimeSource.Monotonic.markNow()
        val merge = key == lastKey &&
            lastMark?.let { it.elapsedNow().inWholeMilliseconds < MERGE_WINDOW_MS } == true
        // Any new step invalidates redo, even a merged one.
        redoStack.clear()
        if (!merge) {
            undoStack.addLast(before)
            while (undoStack.size > limit) undoStack.removeFirst()
        }
        lastKey = key
        lastMark = now
        syncCounts()
    }

    fun undo(current: Map<String, Any>): Map<String, Any>? {
        val before = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        while (redoStack.size > limit) redoStack.removeFirst()
        lastKey = null
        lastMark = null
        syncCounts()
        return before
    }

    fun redo(current: Map<String, Any>): Map<String, Any>? {
        val after = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        while (undoStack.size > limit) undoStack.removeFirst()
        lastKey = null
        lastMark = null
        syncCounts()
        return after
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastKey = null
        lastMark = null
        syncCounts()
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

    /** Hydrates the view from a project design without emitting a write-back. */
    fun replaceValues(next: Map<String, Any>) {
        values.clear()
        values.putAll(next)
        history.clear()
    }

    /** Sets export options without notifying the project (used while hydrating). */
    fun setExportSilently(next: ExportSettings) {
        if (next != exportState) exportState = next
    }

    fun value(control: InspectorControl): Any =
        values[control.key] ?: initialValues[control.key] ?: plainValue(control.initial)

    fun set(control: InspectorControl, next: Any) {
        if (value(control) == next) return
        history.record(values.toMap(), control.key)
        values[control.key] = next
        notifyChanged()
    }

    fun undo(): Boolean {
        val restored = history.undo(values.toMap()) ?: return false
        restore(restored)
        return true
    }

    fun redo(): Boolean {
        val restored = history.redo(values.toMap()) ?: return false
        restore(restored)
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
        notifyChanged()
    }

    /** Drops history when the selected border changes (template switches are not undoable). */
    fun onCardChanged() {
        history.clear()
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
