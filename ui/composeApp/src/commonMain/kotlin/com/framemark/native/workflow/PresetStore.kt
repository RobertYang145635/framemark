package com.framemark.native.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.framemark.native.product.ExportSettings
import com.framemark.native.product.FmLog

/**
 * Local preset list persisted as one JSON file (no backend, no database).
 *
 * Loading is a best-effort read at startup; a missing or unreadable document
 * leaves an empty list instead of blocking the editor. Saving writes the whole
 * document synchronously — it is a few KB and only happens on an explicit action.
 */
object PresetStore {
    private const val FILE_NAME = "presets.json"

    private val itemsState = mutableStateListOf<Preset>()

    val items: List<Preset> get() = itemsState

    var loaded by mutableStateOf(false)
        private set

    fun load() {
        if (loaded) return
        val platform = WorkflowEnv.platform
        if (platform == null) {
            loaded = true
            return
        }
        val text = platform.readTextFile(path(platform))
        if (!text.isNullOrBlank()) {
            try {
                itemsState.clear()
                itemsState.addAll(PresetJson.decode(text))
            } catch (t: Throwable) {
                FmLog.warn("FM_PRESET_LOAD_FAIL") { "${t.message}" }
            }
        }
        loaded = true
        FmLog.info("FM_PRESET_LOADED") { "count=${itemsState.size}" }
    }

    fun save(
        name: String,
        cardId: String,
        cardName: String,
        adjustedCount: Int,
        values: Map<String, Any>,
        export: ExportSettings,
    ): Preset {
        val platform = WorkflowEnv.platform
        val now = platform?.nowMillis() ?: 0L
        val cleanName = name.trim().ifBlank { "我的预设" }
        val preset = Preset(
            id = "p${now}-${itemsState.size + 1}",
            name = cleanName,
            updatedAt = now,
            cardId = cardId,
            cardName = cardName,
            adjustedCount = adjustedCount,
            values = values,
            export = export,
        )
        itemsState.add(0, preset)
        persist()
        FmLog.info("FM_PRESET_SAVED") { "id=${preset.id} name=${preset.name} values=${values.size}" }
        return preset
    }

    fun rename(id: String, name: String): Boolean {
        val clean = name.trim()
        if (clean.isBlank()) return false
        val index = itemsState.indexOfFirst { it.id == id }
        if (index < 0) return false
        itemsState[index] = itemsState[index].copy(name = clean, updatedAt = nowMillis())
        persist()
        return true
    }

    fun delete(id: String): Boolean {
        val removed = itemsState.removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    fun defaultName(): String = "我的预设 ${itemsState.size + 1}"

    /** Verify helper: drops both memory and the persisted document. */
    fun resetForVerify() {
        itemsState.clear()
        val platform = WorkflowEnv.platform
        if (platform != null) {
            runCatching { platform.writeTextFile(path(platform), "{\"version\":1,\"presets\":[]}\n") }
        }
        loaded = false
    }

    /** Verify helper: forces the next [load] to read the document again. */
    fun resetLoadedForVerify() {
        loaded = false
    }

    private fun persist() {
        val platform = WorkflowEnv.platform ?: return
        runCatching { platform.writeTextFile(path(platform), PresetJson.encode(itemsState)) }
            .onFailure { FmLog.warn("FM_PRESET_WRITE_FAIL") { "${it.message}"} }
    }

    private fun nowMillis(): Long = WorkflowEnv.platform?.nowMillis() ?: 0L

    private fun path(platform: WorkflowPlatform): String =
        platform.dataDir.trimEnd('/', '\\') + "/" + FILE_NAME
}
