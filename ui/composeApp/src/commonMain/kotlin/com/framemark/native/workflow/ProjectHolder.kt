package com.framemark.native.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.product.BatchJob
import com.framemark.native.product.PhotoDesign
import com.framemark.native.product.PhotoRef
import com.framemark.native.product.Preset
import com.framemark.native.product.Project
import com.framemark.native.product.WatermarkSpec

data class ImportFailure(val path: String, val reason: String)

data class ImportReport(val added: Int, val failures: List<ImportFailure>)

/**
 * The desktop shell's project state port. `product.Project` is the authority;
 * desktop installs [com.framemark.native.workflow.DesktopProjectController]
 * (atomic file store), while other targets get [InMemoryProjectHolder] so the
 * shared three-column shell still compiles.
 */
interface ProjectHolder {
    val project: Project
    val photos: List<PhotoRef>
    val activePhoto: PhotoRef?
    val activeDesign: PhotoDesign?
    val activePhotoId: String?
    val loaded: Boolean
    val loadError: String?
    val saveError: String?
    /** True while a failed project read must prevent every project-file write. */
    val projectWritesBlocked: Boolean
    val importing: Boolean
    val importFailures: List<ImportFailure>
    val saveCount: Int

    fun load()
    /** Re-reads after a failed load; true means the project is writable again. */
    fun retryProjectLoad(): Boolean = false
    fun flush()
    fun saveNow(): Boolean

    fun selectPhoto(id: String)
    fun removePhoto(id: String)
    fun applyCard(cardId: String, styleId: String?)
    fun setExportSettings(settings: ExportSettings)
    fun exportSettingsForUi(): ExportSettings
    fun applyControlOverrides(model: InspectorCardModel, overrides: Map<String, Any>)
    fun designValues(model: InspectorCardModel): Map<String, Any>

    suspend fun importPhotos(paths: List<String>): ImportReport
    fun pickPhotos(): List<String>
    fun pickWatermark(): String?
    fun clearImportFailures()
    fun thumbnail(path: String, maxEdge: Int = 192): ByteArray?

    suspend fun importWatermark(path: String): Result<Unit>
    fun updateWatermark(
        anchor: Int? = null,
        width: Float? = null,
        opacity: Float? = null,
        dx: Float? = null,
        dy: Float? = null,
        rotation: Float? = null,
    )
    fun applyWatermarkToAll()
    fun clearWatermark()
    fun watermark(): WatermarkSpec?
    fun watermarkBytes(): ByteArray?

    fun overlaySpec(): OverlaySpec?
    suspend fun exportActive(cardName: String, settings: ExportSettings): ExportResult

    /**
     * Bumped whenever the controller changes the active design behind the session's
     * back (preset applied, batch requeued), so the shell re-hydrates its view.
     */
    val hydrationVersion: Int

    /** 1-based position of [photoId] in the strip; used by the `{index}` name token. */
    fun photoIndex(photoId: String?): Int = photos.indexOfFirst { it.id == photoId } + 1

    // ---- presets (P2b) ----------------------------------------------------

    val presets: List<Preset>
    val presetError: String?

    /** Reads `presets.json`; a corrupt or future-version document sets [presetError]. */
    fun loadPresets()
    fun clearPresetError()

    /** Captures the active photo's design, watermark bytes included. */
    fun savePreset(name: String): Preset?
    fun deletePreset(id: String): Boolean
    fun renamePreset(id: String, name: String): Boolean

    /** Writes the preset onto the active photo (design + embedded watermark). */
    fun applyPreset(id: String): Boolean

    /** Single-file export/import so a preset travels without its own PNG. */
    fun exportPresetFile(id: String, path: String): Boolean
    /** Reads and validates a portable preset without changing the library. */
    fun readPresetFile(path: String): Preset?
    /** Persists a preset after an explicit replace decision; ID conflicts refuse by default. */
    fun importPreset(preset: Preset, replaceExisting: Boolean = false): Boolean
    fun importPresetFile(path: String): Preset?
    fun pickPresetExportPath(suggestedName: String): String?
    fun pickPresetImportPath(): String?

    /**
     * Folds migrated legacy presets (the pre-`SessionStore` `presets.json`) into the
     * product library. Returns how many landed; the write is verified by reading the
     * document back and comparing, and a mismatch reports through [presetError].
     */
    fun importLegacyPresets(converted: List<Preset>): Int

    /** True when a legacy batch document is waiting to be folded into the queue. */
    val legacyBatchPending: Boolean

    /** Converts the legacy `batch-queue.json` into product jobs and rewrites the document. */
    fun migrateLegacyBatch(): Int

    // ---- batch (P2b) ------------------------------------------------------

    val batchJobs: List<BatchJob>
    val batchRunning: Boolean
    val batchError: String?
    val batchDone: Int
    val batchFailed: Int

    /** The photos the last 按照片批量 refused for having no border of their own (M01). */
    val batchUndesignedSkipped: List<UndesignedPhoto>
        get() = emptyList()

    /**
     * Counts of the last 按照片批量 press only (selection, enqueued, 无设计, 重复, 拒绝).
     * Null before the first submission; earlier Done rows never feed into it.
     */
    val batchLastSubmit: SubmitReport?
        get() = null

    /** Reads the persisted queue and puts an interrupted item back in the queue. */
    fun loadBatch()

    /**
     * Pre-flight for 「按照片批量」 before anything is enqueued (M01 rules 1-3): which
     * photos have their own binding and which are 未应用边框. Uses only
     * `designs[photo.id]` — it never borrows the shared / active / selected design —
     * so the backend, not silent UI filtering, drives the confirmation prompt.
     */
    fun preflightBatch(photoIds: List<String>): BatchPreflight = project.batchPreflight(photoIds)

    /** Queues one job per photo with that photo's own design + watermark. Returns the count added. */
    fun enqueueBatch(photoIds: List<String>): Int

    /** Foreground serial export; returns when the queue settles or is cancelled. */
    suspend fun runBatch(onProgress: (() -> Unit)? = null)

    /**
     * Starts the queue on a scope the caller does not own. The UI must use this
     * rather than launching [runBatch] from a Compose effect: a recomposition would
     * cancel the in-flight export.
     */
    fun startBatch()
    fun cancelBatch()
    fun retryBatch(id: String)
    fun removeBatchJob(id: String)

    /** Reveals an exported file in the system file manager; null when it worked. */
    fun revealInFolder(path: String): String?
}

/** No-persistence fallback for shared UI on targets without a desktop controller. */
class InMemoryProjectHolder : ProjectHolder {
    override var project by mutableStateOf(Project(id = "memory"))
        private set
    override val photos: List<PhotoRef> get() = project.photos
    override val activePhoto: PhotoRef? get() = project.activePhoto
    override val activeDesign: PhotoDesign? get() = project.activeDesign
    override val activePhotoId: String? get() = project.activePhotoId
    override val loaded: Boolean = true
    override val loadError: String? = null
    override val saveError: String? = null
    override val projectWritesBlocked: Boolean = false
    override val importing: Boolean = false
    override val importFailures: List<ImportFailure> = emptyList()
    override val saveCount: Int = 0

    override fun load() = Unit
    override fun retryProjectLoad(): Boolean = true
    override fun flush() = Unit
    override fun saveNow(): Boolean = true
    override fun selectPhoto(id: String) = Unit
    override fun removePhoto(id: String) = Unit
    override fun applyCard(cardId: String, styleId: String?) = Unit
    override fun setExportSettings(settings: ExportSettings) = Unit
    override fun exportSettingsForUi(): ExportSettings = ExportSettings()
    override fun applyControlOverrides(model: InspectorCardModel, overrides: Map<String, Any>) = Unit
    override fun designValues(model: InspectorCardModel): Map<String, Any> = emptyMap()
    override suspend fun importPhotos(paths: List<String>): ImportReport =
        ImportReport(0, paths.map { ImportFailure(it, "当前平台不支持照片导入") })
    override fun pickPhotos(): List<String> = emptyList()
    override fun pickWatermark(): String? = null
    override fun clearImportFailures() = Unit
    override fun thumbnail(path: String, maxEdge: Int): ByteArray? = null
    override suspend fun importWatermark(path: String): Result<Unit> =
        Result.failure(IllegalStateException("当前平台不支持水印导入"))
    override fun updateWatermark(anchor: Int?, width: Float?, opacity: Float?, dx: Float?, dy: Float?, rotation: Float?) = Unit
    override fun applyWatermarkToAll() = Unit
    override fun clearWatermark() = Unit
    override fun watermark(): WatermarkSpec? = null
    override fun watermarkBytes(): ByteArray? = null
    override fun overlaySpec(): OverlaySpec? = null
    override suspend fun exportActive(cardName: String, settings: ExportSettings): ExportResult =
        throw EngineFailure("FM-E-ENGINE-001", "当前平台没有桌面引擎")

    override val hydrationVersion: Int = 0
    override val presets: List<Preset> = emptyList()
    override val presetError: String? = null
    override fun loadPresets() = Unit
    override fun clearPresetError() = Unit
    override fun savePreset(name: String): Preset? = null
    override fun deletePreset(id: String): Boolean = false
    override fun renamePreset(id: String, name: String): Boolean = false
    override fun applyPreset(id: String): Boolean = false
    override fun exportPresetFile(id: String, path: String): Boolean = false
    override fun readPresetFile(path: String): Preset? = null
    override fun importPreset(preset: Preset, replaceExisting: Boolean): Boolean = false
    override fun importPresetFile(path: String): Preset? = null
    override fun pickPresetExportPath(suggestedName: String): String? = null
    override fun pickPresetImportPath(): String? = null
    override fun importLegacyPresets(converted: List<Preset>): Int = 0
    override val legacyBatchPending: Boolean = false
    override fun migrateLegacyBatch(): Int = 0

    override val batchJobs: List<BatchJob> = emptyList()
    override val batchRunning: Boolean = false
    override val batchError: String? = null
    override val batchDone: Int = 0
    override val batchFailed: Int = 0
    override fun loadBatch() = Unit
    override fun enqueueBatch(photoIds: List<String>): Int = 0
    override suspend fun runBatch(onProgress: (() -> Unit)?) = Unit
    override fun startBatch() = Unit
    override fun cancelBatch() = Unit
    override fun retryBatch(id: String) = Unit
    override fun removeBatchJob(id: String) = Unit
    override fun revealInFolder(path: String): String? = "当前平台不支持打开所在目录"
}
