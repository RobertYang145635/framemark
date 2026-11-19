package com.framemark.native.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.product.PhotoDesign
import com.framemark.native.product.PhotoCrop
import com.framemark.native.product.PhotoCropParams
import com.framemark.native.product.PhotoRef
import com.framemark.native.product.DocumentKind
import com.framemark.native.product.FileTooLargeException
import com.framemark.native.product.LegacyDocuments
import com.framemark.native.product.ProjectPreset
import com.framemark.native.product.ProductFileSystem
import com.framemark.native.product.PRESET_SCHEMA_VERSION
import com.framemark.native.product.Project
import com.framemark.native.product.Sha256
import com.framemark.native.product.SessionStore
import com.framemark.native.product.StoreCodes
import com.framemark.native.product.StoreError
import com.framemark.native.product.StoreResult
import com.framemark.native.product.valueOrNull
import com.framemark.native.product.WatermarkAsset
import com.framemark.native.product.readFileBytesBounded
import com.framemark.native.product.ProjectWatermarkSpec
import com.framemark.native.product.ExportFormat
import com.framemark.native.product.ExportSettings
import com.framemark.native.product.ExportSizeMode
import com.framemark.native.product.toDesignSnapshot
import com.framemark.native.workflow.BatchInputs
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.PhoneOverlayFiles
import com.framemark.native.workflow.PresetJson
import com.framemark.native.workflow.applyInspectorOverrides
import com.framemark.native.workflow.Preset
import com.framemark.native.workflow.WatermarkSpec
import com.framemark.native.workflow.WorkflowPlatform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.framemark.native.product.FmLog

/** e2b slot override families: the value belongs on one key per bound node. */
/** The bundled sample photo has no file; a fixed id keeps its design persistent. */
const val SAMPLE_PHOTO_ID = "photo:sample"

/**
 * The one sentence a sample export is refused with（UX 审查 P0-1）。
 *
 * 样张是应用自带的山景照片，没有文件路径；导出它写出来的是**别人的照片**。
 * 这句话只有一个出处，导出按钮、导出表单和批量导出都读它。
 */
const val SAMPLE_EXPORT_REFUSAL = "当前是示例照片，请先导入自己的照片再导出。"

/**
 * Pixel size of the watermark the platform picker just normalised to PNG. The
 * shared `WatermarkSelection` object carries only an aspect, and an embedded asset
 * has to declare real dimensions, so the picker publishes them here before it
 * raises the selection.
 */
object PhoneWatermarkPick {
    var widthPx: Int = 0
    var heightPx: Int = 0

    fun set(width: Int, height: Int) {
        widthPx = width
        heightPx = height
    }
}

/**
 * Platform reads the phone project needs beyond [ProductFileSystem]: the raw bytes
 * and pixel size of a watermark asset, and the display size of a photo. Android
 * installs a real implementation; a platform without one falls back to what the
 * picker already reported and keeps working.
 */
interface PhoneAssetIo {
    fun readBytes(path: String): ByteArray?

    /** `[width, height]` of the file, or null when it cannot be decoded. */
    fun imageBounds(path: String): IntArray?

    /** `[width, height, orientation]` after EXIF rotation, or null. */
    fun photoMeta(path: String): IntArray? = imageBounds(path)?.let { intArrayOf(it[0], it[1], 1) }

    fun fingerprint(path: String): String? = null
}

/**
 * Process-wide holder: the project survives recomposition and is reachable from the
 * activity's lifecycle callbacks, which matters because ColorOS freezes the uid
 * seconds after the app leaves the foreground.
 */
object PhoneProjectRuntime {
    private var holder: PhoneProjectStore? = null

    val current: PhoneProjectStore? get() = holder

    /**
     * Installs [store] as the process-wide project. The store owns a coroutine scope,
     * a save channel and a long-lived worker, so the previous holder is disposed
     * instead of being dropped: otherwise every re-install (activity recreation)
     * leaves a worker behind that keeps flushing a store nobody reads.
     */
    fun install(store: PhoneProjectStore): PhoneProjectStore {
        val previous = holder
        holder = store
        if (previous != null && previous !== store) previous.dispose()
        return store
    }

    /** Shared fallback for platforms that installed nothing (a narrow desktop window). */
    fun ensure(fallback: () -> PhoneProjectStore): PhoneProjectStore =
        holder ?: fallback().also { holder = it }

    fun flush() {
        holder?.saveNow()
    }
}

/**
 * The phone's project state: `product.Project` is the single source of truth and
 * `product.SessionStore` persists it. A write is staged into a temp file, the
 * staged size and a streaming SHA-256 are verified before the replace, and when the
 * filesystem refuses an atomic rename the fallback replace re-reads the target and
 * compares it with the bytes it published, so a short write is reported as a failure
 * instead of a saved document. Durability against power loss is still bounded by what the OS
 * flushed: the bytes are correct when the call returns, but a force-stop or a
 * power cut cannot be stronger than the platform's own fsync (the parent directory
 * is flushed where the platform allows it). The photo list, one design per photo
 * (border + control values + embedded watermark), the shared design and the export
 * settings all live here — nowhere else.
 *
 * Every write is addressed by photo id. That is what keeps a signature on the photo
 * it belongs to: there is no "current design" snapshot a photo switch can re-key
 * onto the wrong photo.
 */
class PhoneProjectStore(
    fs: ProductFileSystem,
    private val io: PhoneAssetIo?,
    private val platform: WorkflowPlatform?,
) {
    private enum class PresetDocumentState { Unloaded, Missing, Loaded, Unreadable }

    private val store = SessionStore(fs)

    /** Exposed so the photo batch queue can persist its own document through the same store. */
    val fileSystem: ProductFileSystem = fs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveLock = Any()
    private val writeLock = Any()
    private val disposeLock = Any()
    private val saveWakeup = Channel<Unit>(Channel.CONFLATED)
    private var dirty = false
    private var changeVersion = 0L

    /** Set by [dispose]; from then on nothing is scheduled and no save is written. */
    private var disposed = false

    private val nowMillis: Long get() = platform?.nowMillis() ?: 0L

    var project by mutableStateOf(Project(id = "phone", createdAt = 0, updatedAt = 0))
        private set

    var loaded by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    var saveCount by mutableStateOf(0)
        private set

    /**
     * A photo that was dropped while loading because its file is gone or
     * unreadable, phrased for the user (WP-X1 / F2-D1). Null when nothing was
     * skipped. The next package surfaces it; the store only guarantees the app
     * opens on a usable photo instead of retrying a broken one forever.
     */
    var photoNotice by mutableStateOf<String?>(null)
        private set

    /** An existing but unreadable project is never replaced by the startup model. */
    var projectWritesBlocked by mutableStateOf(true)
        private set

    var presets by mutableStateOf<List<ProjectPreset>>(emptyList())
        private set
    var presetError by mutableStateOf<String?>(null)
        private set
    private var presetDocumentState = PresetDocumentState.Unloaded

    val photos: List<PhotoRef> get() = project.photos
    val activePhotoId: String? get() = project.activePhotoId
    val activePhoto: PhotoRef? get() = project.activePhoto
    val activeDesign: PhotoDesign? get() = project.activeDesign

    init {
        // One worker coalesces all edit bursts. Disk serialization happens on IO
        // and never holds saveLock, so pointer events cannot wait behind a large
        // project write.
        scope.launch {
            for (unused in saveWakeup) {
                delay(AUTOSAVE_MS)
                while (saveWakeup.tryReceive().isSuccess) {
                    // A newer edit resets the debounce to the latest project state.
                }
                if (synchronized(saveLock) { dirty && !projectWritesBlocked }) saveNow()
            }
        }
    }

    // ---- lifecycle ---------------------------------------------------------

    /**
     * Stops the autosave worker and releases the scope and the save channel.
     *
     * A pending debounced write is flushed **synchronously** first, so a dispose
     * cannot lose the last edit. Idempotent, and a disposed store refuses to write
     * instead of throwing: [saveNow] answers `false`, nothing is scheduled, the
     * channel is closed and the scope is cancelled.
     */
    fun dispose() {
        synchronized(disposeLock) {
            if (disposed) return
        }
        // Flush before the flag is set: saveNow is the ordinary, verified write path.
        runCatching { saveNow() }
        synchronized(disposeLock) {
            if (disposed) return
            disposed = true
        }
        runCatching { saveWakeup.close() }
        scope.cancel()
    }

    fun load() {
        synchronized(saveLock) {
            if (loaded) return
            loadProjectLocked()
        }
        loadPresets()
    }

    /** Explicit recovery entry; only a successful read (or Missing) releases writes. */
    fun retryProjectLoad(): Boolean = synchronized(saveLock) {
        if (loaded && !projectWritesBlocked) return@synchronized true
        loadProjectLocked()
    }

    /**
     * Reads the project document once.
     *
     * [keepModelWhenMissing] is set by the deferred read a write may trigger before
     * [load] ran: with no document on disk there is nothing to adopt, so the model the
     * caller already built is the document to create. An existing document is always
     * the authority and is adopted; a failed read keeps [projectWritesBlocked] set.
     */
    private fun loadProjectLocked(keepModelWhenMissing: Boolean = false): Boolean {
        loaded = true
        dirty = false
        changeVersion++
        projectWritesBlocked = true
        loadError = null
        return when (val result = store.loadProject()) {
            is StoreResult.Ok -> {
                project = normalizeLoadedSamplePath(result.value)
                projectWritesBlocked = false
                saveError = null
                FmLog.info("FM_PROJECT_LOADED") {
                    "photos=${project.photos.size} designs=${project.designs.size} " +
                        "active=${project.activePhotoId ?: "-"} wm=${project.designs.values.count { it.overlay != null }}"
                }
                // WP-X1 / F2-D1: never open on a photo that cannot even be read —
                // the best photo the app can actually show becomes the active one.
                avoidUnreadableActivePhoto()
                true
            }
            StoreResult.Missing -> {
                if (!keepModelWhenMissing) project = Project(id = "phone", createdAt = 0, updatedAt = 0)
                projectWritesBlocked = false
                saveError = null
                FmLog.info("FM_PROJECT_NONE")
                true
            }
            is StoreResult.Failed -> {
                loadError = "${result.error.code} ${result.error.message}"
                saveError = projectReadProtectionMessage()
                FmLog.warn("FM_PROJECT_LOAD_FAIL") { "code=${result.error.code} msg=${result.error.message}" }
                false
            }
        }
    }

    /**
     * Makes sure the project does not open on a photo that cannot even be read
     * (WP-X1 / F2-D1).
     *
     * This is deliberately **non-destructive**: the entry stays in the project, so
     * a photo on an unplugged SD card or a temporarily unavailable volume comes
     * back by itself, and no user data is dropped behind their back. What changes
     * is only which photo is active — startup must not render an unreadable file
     * while a usable one is available. A photo the renderer rejects (decodable but
     * failing) is handled by the preview failure path, which keeps the app up and
     * offers a retry.
     */
    private fun avoidUnreadableActivePhoto() {
        val active = project.activePhoto ?: return
        val readable = runCatching { fileSystem.exists(active.path) }.getOrDefault(false)
        if (readable) {
            photoNotice = null
            return
        }
        val fallback = project.photos.firstOrNull { photo ->
            photo.id != active.id && runCatching { fileSystem.exists(photo.path) }.getOrDefault(false)
        }
        photoNotice = "无法打开「${active.displayName}」（文件已被移动或删除）；" +
            if (fallback != null) "已改看在可用的「${fallback.displayName}」上。" else "请重新添加这张照片。"
        FmLog.info("FM_PHOTO_UNAVAILABLE") {
            "active=${active.id} name=${active.displayName} " +
                "fallback=${fallback?.id ?: "-"} photos=${project.photos.size}"
        }
        if (fallback != null) {
            project = project.selectPhoto(fallback.id)
            saveNow()
        }
    }

    fun loadPresets() {
        when (val result = store.loadPresets()) {
            is StoreResult.Ok -> {
                presets = result.value
                presetDocumentState = PresetDocumentState.Loaded
                presetError = null
                FmLog.info("FM_PRESET_DOC_LOADED") { "count=${presets.size} wm=${presets.count { it.overlay != null }}" }
            }
            StoreResult.Missing -> {
                presets = emptyList()
                presetDocumentState = PresetDocumentState.Missing
                presetError = null
            }
            is StoreResult.Failed -> {
                presetDocumentState = PresetDocumentState.Unreadable
                presetError = "${result.error.code} ${result.error.message}"
                FmLog.warn("FM_PRESET_DOC_FAIL") { "code=${result.error.code} msg=${result.error.message}" }
                val legacyText = store.documentText(DocumentKind.PresetLibrary).valueOrNull()
                if (legacyText != null && LegacyDocuments.isEmptyLegacyPresetLibrary(legacyText)) {
                    when (val migration = store.migrateLegacyPresetLibrary(emptyList())) {
                        is StoreResult.Ok -> {
                            presets = migration.value
                            presetDocumentState = PresetDocumentState.Loaded
                            presetError = null
                            FmLog.info("FM_PRESET_EMPTY_LEGACY_MIGRATED")
                        }
                        is StoreResult.Failed -> {
                            presetDocumentState = PresetDocumentState.Unreadable
                            presetError = "${migration.error.code} ${migration.error.message}"
                        }
                        StoreResult.Missing -> Unit
                    }
                }
            }
        }
    }

    /**
     * Writes the project synchronously. The store stages the bytes in a temp file,
     * verifies the length and the staged bytes, and only then replaces the document,
     * so a failure keeps the previous document and raises [saveError] rather than
     * pretending the change was saved. A disposed store answers `false` instead of
     * throwing.
     */
    fun saveNow(): Boolean {
        if (disposed) return false
        return saveNowLocked()
    }

    private fun saveNowLocked(): Boolean = synchronized(writeLock) {
        val captured = synchronized(saveLock) {
            // A save can arrive before anyone called load(): a store driven only through
            // the preset API, or a flush raised while startup is still reading. The
            // document has to be read once before it may be replaced, so the deferred
            // read happens here; the guard keeps blocking when that read fails (only a
            // successful read, or a missing document, releases writes).
            if (!loaded) loadProjectLocked(keepModelWhenMissing = true)
            if (projectWritesBlocked) {
                dirty = false
                saveError = projectReadProtectionMessage()
                FmLog.warn("FM_PROJECT_SAVE_BLOCKED") { "reason=project-read-failed" }
                return@synchronized null
            }
            project to changeVersion
        } ?: return@synchronized false

        val snapshot = projectForPersistence(captured.first)
        val snapshotVersion = captured.second
        when (val result = store.saveProject(snapshot)) {
            is StoreResult.Ok -> {
                synchronized(saveLock) {
                    // A commit may arrive while the IO is running. Keep dirty set so
                    // its follow-up wakeup writes that newer state as well.
                    if (changeVersion == snapshotVersion) dirty = false
                    if (saveError != null) saveError = null
                    saveCount++
                }
                FmLog.info("FM_PROJECT_SAVED") {
                    "photos=${snapshot.photos.size} designs=${snapshot.designs.size} " +
                        "wm=${snapshot.designs.values.count { it.overlay != null }} saves=$saveCount"
                }
                true
            }
            StoreResult.Missing -> false
            is StoreResult.Failed -> {
                synchronized(saveLock) {
                    saveError = "${result.error.code} ${result.error.message}"
                }
                FmLog.warn("FM_PROJECT_SAVE_FAIL") { "code=${result.error.code} msg=${result.error.message}" }
                false
            }
        }
    }

    /** Lands any debounced write before the caller leaves the foreground. */
    fun flushBlocking(): Boolean {
        // The guard means "the document was not read yet" as well as "the read failed";
        // saveNow performs that deferred read, so its answer is the flush's answer.
        if (projectWritesBlocked) return saveNow()
        val pending = synchronized(saveLock) { dirty }
        if (!pending) return true
        var saved = saveNow()
        while (saved && synchronized(saveLock) { dirty }) saved = saveNow()
        FmLog.info("FM_PROJECT_FLUSH") { "pending=true saved=$saved" }
        return saved
    }

    private fun scheduleSave() {
        if (disposed) return
        val scheduled = synchronized(saveLock) {
            if (projectWritesBlocked) false else {
                dirty = true
                true
            }
        }
        if (scheduled) saveWakeup.trySend(Unit)
    }

    // ---- photos ------------------------------------------------------------

    /** Registers each path as a project photo (idempotent by path) and points at [activePath]. */
    fun adoptPaths(
        paths: List<String>,
        activePath: String?,
        displayNamesByPath: Map<String, String> = emptyMap(),
    ): List<String> {
        var next = project
        val fingerprintCandidates = LinkedHashSet<String>()
        paths.forEach { path ->
            if (path.isBlank()) return@forEach
            val id = idForPath(path)
            val displayName = displayNamesByPath[path]?.takeIf { it.isNotBlank() }
                ?: path.substringAfterLast('/').substringAfterLast('\\')
            fingerprintCandidates += id
            val existing = next.photos.firstOrNull { it.id == id }
            if (existing != null) {
                if (displayNamesByPath[path].isNullOrBlank() || existing.displayName == displayName) return@forEach
                next = next.copy(photos = next.photos.map { photo ->
                    if (photo.id == id) photo.copy(displayName = displayName) else photo
                })
                return@forEach
            }
            val meta = io?.photoMeta(path)
            val ref = PhotoRef(
                id = id,
                path = path,
                displayName = displayName,
                orientation = meta?.getOrNull(2) ?: 1,
                widthPx = meta?.getOrNull(0) ?: 0,
                heightPx = meta?.getOrNull(1) ?: 0,
                // An import must not hash hundreds of MB on the caller thread (a Compose
                // LaunchedEffect): the digest is filled in on the store's IO scope below.
                fingerprint = null,
            )
            next = next.addPhoto(ref, inheritFrom(next))
        }
        activePath?.let { next = next.selectPhoto(idForPath(it)) }
        commit(next)
        scheduleFingerprints(fingerprintCandidates)
        return next.photos.map { it.id }
    }

    /**
     * Fills in the fingerprints of just-imported photos on the store's own IO scope.
     *
     * [adoptPaths] stays synchronous (its call sites are Compose effects and the
     * Android activity's pick callback): the photo is usable immediately, and the
     * digest — which reads the whole file — is patched through the ordinary [commit]
     * path once it is known. The patch re-reads the *current* project under the
     * write lock, so a photo removed while the hash was running is not resurrected,
     * a photo that already has a fingerprint is left alone, and a disposed store
     * stops both the hashing and the patch.
     */
    private fun scheduleFingerprints(candidateIds: Set<String>) {
        val assetIo = io ?: return
        if (candidateIds.isEmpty() || isDisposed()) return
        scope.launch {
            val targets = synchronized(saveLock) {
                if (isDisposed()) emptyList() else photosNeedingFingerprint(project, candidateIds)
            }
            if (targets.isEmpty()) return@launch
            val fingerprints = LinkedHashMap<String, String>()
            for (photo in targets) {
                if (isDisposed()) return@launch
                val fingerprint = runCatching { assetIo.fingerprint(photo.path) }.getOrNull()
                if (fingerprint != null) fingerprints[photo.id] = fingerprint
            }
            if (fingerprints.isEmpty() || isDisposed()) return@launch
            commitWith { current ->
                // Re-check under the write lock: a dispose that lands between the hash
                // and this patch must not mutate the model of a dead store.
                if (isDisposed()) current else current.withFingerprints(fingerprints)
            }
        }
    }

    /** True once [dispose] ran; read under its own lock so the IO thread sees it. */
    private fun isDisposed(): Boolean = synchronized(disposeLock) { disposed }

    /**
     * Test/evidence fixture: registers the bundled sample (which has no file) so a
     * project can hold a design without any imported photo.
     *
     * **Product code must never call this** (UX 审查 P0-1: `openCardFromBrowser` did,
     * so picking a border on an empty project wrote 「示例照片」 into the document and
     * the export then rendered the built-in landscape). `SamplePhotoGuardTest` fails
     * the build if any production source mentions it.
     */
    internal fun adoptSampleFixture(): String {
        if (project.photos.none { it.id == SAMPLE_PHOTO_ID }) {
            commit(project.addPhoto(PhotoRef(SAMPLE_PHOTO_ID, "", "示例照片"), inheritFrom(project)))
        }
        return SAMPLE_PHOTO_ID
    }

    /**
     * Why the active photo cannot be exported, in one human sentence; null when it can.
     *
     * 样张与空工程都不许导出，理由是同一条：没有用户的照片。导出入口读这一句，
     * 不要在 UI 里各写一遍（P0-1 的第二种表现就是「导出成功，但出来的是自带样张」）。
     */
    fun exportRefusal(): String? = when {
        project.photos.isEmpty() || project.activePhotoId == null -> "先选择一张照片，再导出。"
        project.activePhotoId == SAMPLE_PHOTO_ID -> SAMPLE_EXPORT_REFUSAL
        else -> null
    }

    fun select(id: String) {
        if (project.activePhotoId == id) return
        if (project.photos.none { it.id == id }) return
        // The active photo's design was already written when it changed, so a switch
        // only re-points the selection and persists it (a restart returns here).
        commit(project.selectPhoto(id))
        saveNow()
        FmLog.info("FM_PROJECT_SELECT") { "id=$id card=${project.designOf(id)?.cardId ?: "-"}" }
    }

    fun remove(id: String) {
        if (project.photos.none { it.id == id }) return
        commit(project.removePhoto(id))
        saveNow()
        FmLog.info("FM_PROJECT_REMOVE") { "id=$id photos=${project.photos.size} designs=${project.designs.size}" }
    }

    fun clearPhotos() {
        if (project.photos.isEmpty()) return
        commit(project.copy(photos = emptyList(), activePhotoId = null, designs = emptyMap()))
        saveNow()
    }

    fun idForPath(path: String?): String = path?.takeIf { it.isNotBlank() } ?: SAMPLE_PHOTO_ID

    fun designOf(id: String): PhotoDesign? = project.designs[id]

    /** Direct design assignment (the pair-demo evidence path and preset applies). */
    fun setDesign(photoId: String, design: PhotoDesign) {
        if (project.designs[photoId] == design) return
        commit(project.setDesign(photoId, design))
    }

    /**
     * The explicit 应用到全部: the active photo's border + values go onto every
     * photo, and its signature spreads only here — never as a side effect of
     * adding or switching photos.
     */
    fun applyActiveDesignToAll() {
        val active = project.activeDesign ?: return
        val border = active.withoutOverlay()
        var next = project
        project.photos.forEach { photo ->
            val current = next.designs[photo.id]
            val merged = (current ?: border).copy(
                cardId = border.cardId,
                styleId = border.styleId,
                theme = border.theme,
                ratio = border.ratio,
                fontScale = border.fontScale,
                logo = border.logo,
                params = border.params,
                text = border.text,
                baseDesign = border.isUntouched,
            )
            next = next.setDesign(photo.id, merged)
        }
        commit(next)
        commit(project.applyWatermarkToAll(active.overlay))
    }

    /** A new photo starts from the shared design, else the active one, else the card. */
    private fun inheritFrom(current: Project): PhotoDesign? =
        current.sharedDesign ?: current.activeDesign ?: current.selectedCardId?.let { PhotoDesign.base(it) }

    // ---- design writes -----------------------------------------------------

    /**
     * Folds the session's changed control values onto the active photo's design.
     * Values equal to the card default are dropped, so an untouched design sends
     * nothing and renders exactly what the engine ships.
     */
    fun writeActiveValues(model: InspectorCardModel, styleId: String?, changes: Map<String, Any>) {
        val photoId = project.activePhotoId ?: return
        val prefix = "${model.cardId}::"
        val scoped = changes.filterKeys { it.startsWith(prefix) }
        val current = ensureDesign(photoId, model.cardId, styleId) ?: return
        val next = foldOverrides(current, model, scoped)
        // Evidence for the "control -> design field" segment: which controls were in the
        // change set, what consumer they carry, and how many fields the fold produced.
        FmLog.info("FM_DESIGN_WRITE") {
            "card=${model.cardId} changes=${changes.size} scoped=${scoped.size} " +
                "sample=${scoped.entries.take(2).joinToString { it.key.removePrefix(prefix) + "=" + it.value }} " +
                "consumers=${model.controls.filter { scoped.containsKey(it.key) }.take(3).joinToString { it.controlId + "->" + it.param }} " +
                "afterParams=${next.params.size} afterText=${next.text.size} same=${next == current}"
        }
        if (next == current) return
        commit(project.setDesign(photoId, next))
    }

    /** Publishes the active photo's border + values as the default for later photos. */
    fun publishSharedDesign(model: InspectorCardModel?, styleId: String?, changes: Map<String, Any>) {
        val photoId = project.activePhotoId ?: return
        val design = ensureDesign(photoId, model?.cardId, styleId) ?: return
        val folded = if (model != null) foldOverrides(design, model, changes) else design
        commit(project.setSharedDesign(folded.withoutOverlay()))
    }

    /**
     * Applies a border to the active photo. The card's style id travels with it
     * (the engine selects a style by styleId); the watermark survives, the
     * parameters restart from the shared design when there is one.
     */
    fun applyCard(cardId: String, styleId: String?) {
        val photoId = project.activePhotoId
        if (photoId == null) {
            commit(project.copy(selectedCardId = cardId))
            return
        }
        val overlay = project.designs[photoId]?.overlay
        val shared = project.sharedDesign
        val next = PhotoDesign.base(cardId, styleId).copy(
            params = shared?.params.orEmpty(),
            text = shared?.text.orEmpty(),
            theme = shared?.theme,
            fontScale = shared?.fontScale,
            logo = shared?.logo,
            ratio = shared?.ratio,
            overlay = overlay,
            baseDesign = shared == null,
        )
        commit(project.setDesign(photoId, next).copy(selectedCardId = cardId))
        FmLog.info("FM_PROJECT_CARD") { "card=$cardId style=${styleId ?: "-"} photo=$photoId wm=${overlay?.asset?.sha256?.take(8) ?: "none"}" }
    }

    private fun ensureDesign(photoId: String, cardId: String?, styleId: String?): PhotoDesign? {
        project.designs[photoId]?.let { return it }
        val resolved = cardId?.takeIf { it.isNotBlank() } ?: project.selectedCardId ?: return null
        val created = PhotoDesign.base(resolved, styleId)
        if (!commit(project.setDesign(photoId, created))) return null
        return created
    }

    // ---- watermark ---------------------------------------------------------

    /**
     * Reads the picked PNG into the project as **embedded bytes**. Returns a
     * structured error (the caller shows it) instead of failing silently.
     */
    fun importWatermark(
        photoId: String,
        path: String,
        widthPx: Int?,
        heightPx: Int?,
        aspect: Float?,
        cardId: String? = null,
        styleId: String? = null,
    ): StoreError? {
        if (projectWritesBlocked) {
            return StoreError("FM-E-IO-002", projectReadProtectionMessage(), path)
        }
        // A photo that has not been given a border yet still gets the signature: the
        // card the editor is showing becomes its design. Only a photo with no card at
        // all can be refused, and it is refused loudly rather than silently dropped.
        val design = ensureDesign(photoId, cardId ?: project.selectedCardId, styleId)
            ?: return StoreError("FM-E-INPUT-001", "还没有选边框，无法加水印", path)
        val bytes = try {
            readWatermarkBytes(path)
        } catch (tooLarge: FileTooLargeException) {
            return StoreError(StoreCodes.IO_READ, tooLarge.message ?: "水印文件过大", path)
        } ?: return StoreError("FM-E-IO-002", "无法读取水印文件", path)
        if (bytes.isEmpty()) return StoreError("FM-E-IO-002", "水印文件为空", path)
        val bounds = io?.imageBounds(path)
        val declaredWidth = widthPx ?: bounds?.getOrNull(0)
        val declaredHeight = heightPx ?: bounds?.getOrNull(1)
        val size = if (declaredWidth != null && declaredHeight != null && declaredWidth > 0 && declaredHeight > 0) {
            declaredWidth to declaredHeight
        } else {
            // Without pixel bounds the aspect still has to be exact, or the drawn
            // signature changes shape; the absolute numbers do not affect the rect.
            val safeAspect = aspect?.takeIf { it.isFinite() && it > 0.01f } ?: 1f
            (1000 * safeAspect).toInt().coerceAtLeast(1) to 1000
        }
        val asset = runCatching {
            WatermarkAsset.create(path.substringAfterLast('/'), "image/png", size.first, size.second, bytes)
        }.getOrElse { return StoreError("FM-E-PRESET-001", "水印素材无法内嵌：${it.message}", path, cause = it) }
        if (!asset.isIntact()) return StoreError("FM-E-PRESET-001", "水印素材校验失败（字节与摘要不符）", path)
        val previous = design.overlay
        val spec = ProjectWatermarkSpec(
            asset = asset,
            anchor = previous?.anchor ?: 8,
            width = previous?.width ?: 0.25f,
            opacity = previous?.opacity ?: 0.8f,
            dx = previous?.dx ?: 0f,
            dy = previous?.dy ?: 0f,
        )
        if (!commit(project.setDesign(photoId, design.withOverlay(spec)))) {
            return StoreError("FM-E-IO-003", saveError ?: "工程修改未保存", path)
        }
        if (!saveNow()) {
            return StoreError("FM-E-IO-003", saveError ?: "工程修改未保存", path)
        }
        FmLog.info("FM_WM_EMBED") { "photo=$photoId sha=${asset.sha256.take(12)} bytes=${asset.byteSize} ${asset.widthPx}x${asset.heightPx}" }
        return null
    }

    /**
     * Reads the picked watermark with the product asset limit applied **before** the
     * bytes are materialised. The bounded local read is tried first (a picked file is
     * a real path, and the platform hook may not bound its own read); the injected
     * hooks remain the fallback for non-file sources and tests.
     */
    private fun readWatermarkBytes(path: String): ByteArray? {
        val local = readFileBytesBounded(path, WatermarkAsset.MAX_BYTES)
        if (local != null) return local
        return io?.readBytes(path) ?: platform?.readBytes(path)
    }

    fun setOverlay(photoId: String, spec: ProjectWatermarkSpec?) {
        val design = project.designs[photoId] ?: return
        if (design.overlay == spec) return
        commit(project.setDesign(photoId, design.withOverlay(spec)))
    }

    fun overlayOf(photoId: String): ProjectWatermarkSpec? = project.designs[photoId]?.overlay

    /** Copies the active photo's signature onto every photo — the only way it spreads. */
    fun applyWatermarkToAll() {
        val spec = project.activeDesign?.overlay
        if (spec == null) {
            FmLog.info("FM_WM_APPLY_ALL") { "skipped: active photo has no watermark" }
            return
        }
        commit(project.applyWatermarkToAll(spec))
        saveNow()
        FmLog.info("FM_WM_APPLY_ALL") { "photos=${project.photos.size} sha=${spec.asset.sha256.take(12)}" }
    }

    fun clearActiveWatermark() {
        val photoId = project.activePhotoId ?: return
        setOverlay(photoId, null)
        saveNow()
    }

    /** Writes the embedded bytes out for the engine once per digest. */
    fun materializeOverlayFile(spec: ProjectWatermarkSpec): String? = PhoneOverlayFiles.materialize(spec)

    /** The editor's path-based watermark state, derived from the project's own spec. */
    fun uiWatermark(photoId: String, photoAspect: Float): WatermarkSpec? {
        val spec = overlayOf(photoId) ?: return null
        val file = materializeOverlayFile(spec) ?: return null
        return WatermarkSpec(
            path = file,
            aspect = spec.asset.aspect,
            anchor = spec.anchor,
            width = spec.width,
            opacity = spec.opacity,
            dx = spec.dx,
            dy = spec.dy,
            photoAspect = if (spec.photoAspect.isFinite() && spec.photoAspect > 0.01f) {
                spec.photoAspect
            } else {
                photoAspect
            },
            rotation = spec.rotation,
        )
    }

    /** Maps the editor's anchor/drag edits back onto the embedded spec. */
    fun projectWatermark(current: ProjectWatermarkSpec?, ui: WatermarkSpec?): ProjectWatermarkSpec? {
        if (ui == null) return null
        val asset = current?.asset ?: return null
        return ProjectWatermarkSpec(
            asset = asset,
            anchor = ui.anchor,
            width = ui.width,
            opacity = ui.opacity,
            dx = ui.dx,
            dy = ui.dy,
            rotation = ui.rotation,
            photoAspect = ui.photoAspect,
        )
    }

    // ---- photo crop --------------------------------------------------------

    /**
     * Writes the photo's crop into the design's own `params` and nowhere else, so
     * autosave, the stage and the batch queue read one value. The defaults remove
     * the keys again, which is what keeps a document that never used the crop UI
     * byte-for-byte identical to one written before the feature existed.
     */
    fun setCrop(photoId: String, crop: PhotoCrop) {
        val design = project.designs[photoId] ?: return
        val next = PhotoCropParams.write(design, crop)
        if (next == design) return
        commit(project.setDesign(photoId, next))
    }

    // ---- export settings ---------------------------------------------------

    /**
     * The export choices are the product document's own type — there is no second
     * settings type to convert, so what the sheet edits is what the project saves.
     */
    fun exportSettingsForUi(): ExportSettings = project.exportSettings

    fun setExportSettings(settings: ExportSettings) {
        if (settings == project.exportSettings) return
        commit(project.withExportSettings(settings))
    }

    // ---- presets -----------------------------------------------------------

    /**
     * Captures the active photo's design (watermark bytes included) as a preset.
     *
     * Same document as the desktop writes (`ProjectPreset`: design + export settings
     * + embedded watermark, persisted through `SessionStore.savePresets`), with only
     * the local JPEG quality pinned to the platform's fixed value. The newest preset
     * goes **first**, like the desktop library, so the design the user just saved is
     * the first row under the save card instead of the last one behind a scroll.
     */
    fun savePreset(name: String): ProjectPreset? {
        if (!ensurePresetDocumentWritable()) return null
        val design = project.activeDesign ?: run {
            presetError = "FM-E-INPUT-001 当前照片还没有边框设计，无法保存预设"
            return null
        }
        val stamp = nowMillis
        val preset = ProjectPreset(
            id = "p$stamp-${presets.size + 1}",
            name = ProjectPreset.cleanName(name),
            design = design,
            exportSettings = project.exportSettings.copy(
                quality = platform?.preferredJpegQuality() ?: project.exportSettings.quality,
            ),
            createdAt = stamp,
            updatedAt = stamp,
        )
        return if (persistPresets(listOf(preset) + presets)) preset else null
    }

    fun deletePreset(id: String): Boolean {
        if (presets.none { it.id == id }) return false
        return persistPresets(presets.filterNot { it.id == id })
    }

    fun renamePreset(id: String, name: String): Boolean {
        val clean = name.trim()
        if (clean.isBlank()) return false
        val index = presets.indexOfFirst { it.id == id }
        if (index < 0) return false
        val next = presets.toMutableList()
        next[index] = next[index].copy(name = clean, updatedAt = nowMillis)
        return persistPresets(next)
    }

    /**
     * Applies a preset to the active photo: border, every control value, the
     * embedded watermark and the export choices. A preset saved by an older build
     * (no watermark) still restores its parameters; nothing is invented for it.
     */
    fun applyPreset(preset: ProjectPreset, styleId: String?): Boolean {
        if (projectWritesBlocked) {
            presetError = projectReadProtectionMessage()
            return false
        }
        val photoId = project.activePhotoId ?: return false
        val cardId = preset.design.cardId.takeIf { it.isNotBlank() } ?: project.selectedCardId ?: return false
        val existing = ensureDesign(photoId, cardId, styleId) ?: return false
        val design = existing.copy(
            cardId = cardId,
            styleId = preset.design.styleId ?: styleId,
            theme = preset.design.theme,
            ratio = preset.design.ratio,
            fontScale = preset.design.fontScale,
            logo = preset.design.logo,
            params = preset.design.params,
            text = preset.design.text,
            baseDesign = preset.design.isUntouched,
            overlay = preset.overlay,
        )
        val committed = commit(
            project.setDesign(photoId, design)
                .withExportSettings(
                    preset.exportSettings.copy(quality = platform?.preferredJpegQuality() ?: project.exportSettings.quality),
                )
                .copy(selectedCardId = cardId),
        )
        if (!committed && projectWritesBlocked) {
            presetError = projectReadProtectionMessage()
            return false
        }
        if (!saveNow()) {
            presetError = saveError ?: "FM-E-IO-003 预设已应用，但工程未写入磁盘"
            FmLog.warn("FM_PRESET_APPLY_FAIL") { "id=${preset.id} save=$saveError" }
            return false
        }
        FmLog.info("FM_PRESET_APPLY") {
            "id=${preset.id} card=$cardId photo=$photoId " +
                "wm=${preset.overlay?.asset?.sha256?.take(12) ?: "none"} params=${design.params.size} text=${design.text.size}"
        }
        return true
    }

    /**
     * Adds or replaces one portable preset. ID conflicts are rejected by default;
     * callers must make an explicit replace choice. State only changes after the
     * complete library has been durably saved.
     */
    fun importPreset(preset: ProjectPreset, replaceExisting: Boolean = false): Boolean {
        if (!ensurePresetDocumentWritable()) return false
        if (preset.schemaVersion != PRESET_SCHEMA_VERSION || preset.id.isBlank() || preset.name.isBlank()) {
            presetError = "FM-E-PRESET-001 导入的预设信息无效"
            return false
        }
        val index = presets.indexOfFirst { it.id == preset.id }
        if (index >= 0 && !replaceExisting) {
            presetError = "FM-E-PRESET-001 预设 ID 已存在；原预设未更改"
            return false
        }

        val localQuality = platform?.preferredJpegQuality() ?: project.exportSettings.quality
        val localPreset = preset.copy(exportSettings = preset.exportSettings.copy(quality = localQuality))
        val next = if (index < 0) {
            presets + localPreset
        } else {
            presets.toMutableList().apply { this[index] = localPreset }
        }
        return persistPresets(next)
    }

    /**
     * Rewrites the old `{"version":1,"presets":[...]}` document only after the
     * exact original bytes are backed up and verified. A conversion that omits any
     * legacy item is refused so the source file cannot be replaced partially.
     *
     * The replace itself is [SessionStore.migrateLegacyPresetLibrary] — the same
     * transaction the empty-legacy branch uses: it stages the converted document,
     * decodes the staged file and compares it against the whole converted list, and
     * only renames over the library once that verification passed. Nothing reaches
     * the live document before a semantic read-back of the staged bytes.
     */
    fun importLegacyPresets(converted: List<ProjectPreset>, expectedCount: Int): Int {
        if (expectedCount <= 0 && converted.isEmpty()) return 0
        if (converted.size != expectedCount) {
            presetError = "FM-E-PRESET-001 旧预设有 $expectedCount 项，仅能完整转换 ${converted.size} 项；原文件未更改"
            return 0
        }
        if (converted.isEmpty()) return 0
        if (presetDocumentState == PresetDocumentState.Unloaded) loadPresets()
        if (presetDocumentState != PresetDocumentState.Unreadable) {
            presetError = "FM-E-PRESET-001 当前预设文档可正常读取，已停止旧格式迁移"
            return 0
        }

        val path = store.documentPath(DocumentKind.PresetLibrary)
        val originalBytes = readRawBytes(path) ?: run {
            presetError = "FM-E-PRESET-001 无法读取旧预设原始字节；原文件未更改"
            return 0
        }
        val originalText = try {
            originalBytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Throwable) {
            presetError = "FM-E-PRESET-001 旧预设不是有效 UTF-8；原文件未更改"
            return 0
        }
        if (!LegacyDocuments.isLegacyPresetLibrary(originalText) || LegacyDocuments.isProductPresetLibrary(originalText)) {
            presetError = "FM-E-PRESET-001 预设文件不是可迁移的旧格式；原文件未更改"
            return 0
        }
        val legacyItems = try {
            PresetJson.decode(originalText)
        } catch (_: Throwable) {
            emptyList()
        }
        if (LegacyDocuments.legacyPresetCount(originalText) != expectedCount ||
            legacyItems.size != expectedCount ||
            legacyItems.map { it.id } != converted.map { it.id }
        ) {
            presetError = "FM-E-PRESET-001 旧预设内容与转换结果不一致；原文件未更改"
            return 0
        }

        val sha = Sha256.hex(originalBytes)
        val backupPath = "$path.legacy-$sha.bak"
        val backupReady = try {
            if (fileSystem.exists(backupPath)) {
                readRawBytes(backupPath)?.contentEquals(originalBytes) == true
            } else {
                fileSystem.writeBytesIfAbsent(backupPath, originalBytes) ||
                    readRawBytes(backupPath)?.contentEquals(originalBytes) == true
            }
        } catch (_: Throwable) {
            false
        }
        if (!backupReady || readRawBytes(backupPath)?.contentEquals(originalBytes) != true) {
            presetError = "FM-E-PRESET-001 旧预设备份校验失败；原文件未更改"
            return 0
        }
        if (readRawBytes(path)?.contentEquals(originalBytes) != true) {
            presetError = "FM-E-PRESET-001 迁移前旧预设已发生变化；原文件未更改（备份：$backupPath）"
            return 0
        }

        val quality = platform?.preferredJpegQuality() ?: project.exportSettings.quality
        val normalized = converted.map { item ->
            item.copy(exportSettings = item.exportSettings.copy(quality = quality))
        }
        if (normalized.map { it.id }.toSet().size != normalized.size ||
            presets.any { existing -> normalized.any { it.id == existing.id } }
        ) {
            presetError = "FM-E-PRESET-001 迁移预设 ID 冲突；原文件未更改（备份：$backupPath）"
            return 0
        }
        // The transaction below replaces the whole library with `normalized`, so it may
        // only run while the unreadable document contributes nothing in memory. A failed
        // `loadPresets` never assigns [presets] and the field starts empty, so an
        // unreadable document means an empty one; refused here instead of silently merged.
        if (presets.isNotEmpty()) {
            presetError = "FM-E-PRESET-001 旧库不可读但内存已有 ${presets.size} 项预设，先解决冲突再迁移；原文件未更改（备份：$backupPath）"
            return 0
        }

        when (val migrated = store.migrateLegacyPresetLibrary(normalized)) {
            is StoreResult.Ok -> {
                presets = normalized
                presetDocumentState = PresetDocumentState.Loaded
                presetError = null
                FmLog.info("FM_PRESET_MIGRATED") { "count=${normalized.size} sha256=$sha backup=$backupPath" }
                return normalized.size
            }
            is StoreResult.Failed -> {
                presetError = "FM-E-PRESET-001 旧预设迁移已停止；原库备份：$backupPath（${migrated.error.message}）"
                presetDocumentState = PresetDocumentState.Unreadable
                return 0
            }
            StoreResult.Missing -> {
                presetError = "FM-E-PRESET-001 旧预设库不存在，未执行迁移；原库备份：$backupPath"
                presetDocumentState = PresetDocumentState.Unreadable
                return 0
            }
        }
    }

    private fun persistPresets(next: List<ProjectPreset>): Boolean {
        if (!ensurePresetDocumentWritable()) return false
        return when (val result = store.savePresets(next)) {
            is StoreResult.Ok -> {
                presets = next
                presetDocumentState = PresetDocumentState.Loaded
                presetError = null
                FmLog.info("FM_PRESET_DOC_SAVED") {
                    "count=${next.size} wm=${next.count { it.overlay != null }} " +
                        "bytes=${next.sumOf { it.embeddedWatermarkBytes }}"
                }
                true
            }
            StoreResult.Missing -> false
            is StoreResult.Failed -> {
                presetError = "${result.error.code} ${result.error.message}"
                FmLog.warn("FM_PRESET_DOC_FAIL") { "code=${result.error.code} msg=${result.error.message}" }
                false
            }
        }
    }

    private fun ensurePresetDocumentWritable(): Boolean {
        if (presetDocumentState == PresetDocumentState.Unloaded) loadPresets()
        if (presetDocumentState == PresetDocumentState.Unreadable) {
            if (presetError == null) presetError = "FM-E-PRESET-001 预设文件无法读取，已禁止覆盖"
            return false
        }
        return presetDocumentState == PresetDocumentState.Missing || presetDocumentState == PresetDocumentState.Loaded
    }

    private fun readRawBytes(path: String): ByteArray? = runCatching { fileSystem.readBytes(path) }.getOrNull()

    // ---- batch snapshot ----------------------------------------------------

    /**
     * Inputs for the 「批量导出」 queue: each item stores the design, the watermark
     * and the export settings it was queued with, plus the photo it must use, so a
     * resumed run reproduces that output instead of falling back to the sample photo.
     */
    fun batchInputs(): BatchInputs = object : BatchInputs {
        override fun designFor(cardId: String, styleId: String?): PhotoDesign =
            activeDesign?.takeIf { it.cardId == cardId } ?: PhotoDesign.base(cardId, styleId)

        override fun overlay(): ProjectWatermarkSpec? = activeDesign?.overlay

        override fun exportSettings(): ExportSettings = project.exportSettings

        override fun photoPath(): String? = activePhoto?.path?.takeIf { it.isNotBlank() }

        override fun fingerprint(path: String?): String? = path?.let { io?.fingerprint(it) }
    }

    /**
     * Every photo paired with **its own** design, in list order (M01 rule 1).
     *
     * An already-existing photo whose binding is missing is 未应用边框 — it is returned
     * with `design == null` and must NOT borrow `sharedDesign`, `activeDesign` or
     * `selectedCardId`. `PhoneShell.buildPhotoBatch()` drops a null design, so such a
     * photo never enters 「按照片批量」 wearing another photo's card. The missing-card
     * fallback this method used to carry was the confirmed P1 batch defect; the shared
     * design remains only a *new-photo* default (see `Project.designForNewPhoto`), and
     * the explicit 应用到全部 (`applyActiveDesignToAll`) is what creates a binding.
     *
     * The list stays aligned with `project.photos`, so a caller's positional index is
     * the photo's real list position (`nameIndex`) and filtering cannot renumber it.
     */
    fun photoEntries(): List<PhonePhotoEntry> = project.photos.map { photo ->
        PhonePhotoEntry(photo, project.designs[photo.id])
    }

    fun customized(photoId: String): Boolean {
        val design = project.designs[photoId] ?: return false
        if (design.overlay != null) return true
        val shared = project.sharedDesign ?: return !design.isUntouched
        return design.copy(overlay = null) != shared.copy(overlay = null)
    }

    private fun commit(next: Project): Boolean = commitWith { next }

    /**
     * Applies [transform] to the project **as it is under the write lock**. The
     * background fingerprint patch must go through this (not through a project
     * snapshot it read earlier): building the next model from a stale snapshot
     * could drop a concurrent edit and would resurrect a photo removed meanwhile.
     */
    private fun commitWith(transform: (Project) -> Project): Boolean {
        val committed = synchronized(saveLock) {
            // Before the first read the flag only means "no document authority was
            // established yet": the in-memory model may still change, while the disk
            // write waits for the deferred read in [saveNow]. Once a read has failed,
            // the edit itself is refused — a model built on top of an unreadable
            // document must never be presented as saved.
            if (projectWritesBlocked && loaded) {
                saveError = projectReadProtectionMessage()
                return@synchronized false
            }
            val next = transform(project)
            if (next == project) return@synchronized false
            project = next.touched(nowMillis)
            changeVersion++
            true
        }
        if (committed) scheduleSave()
        return committed
    }

    private fun projectReadProtectionMessage(): String {
        val reason = loadError ?: "工程尚未成功读取"
        return "FM-E-IO-002 已阻止写入以保护原工程；请重试读取。$reason"
    }

    /**
     * The built-in sample has no file path while it is in memory. The project
     * schema requires a non-empty persisted path, so use an internal sentinel in
     * the document and restore the blank in-memory path after every read.
     */
    private fun projectForPersistence(current: Project): Project = current.copy(
        photos = current.photos.map { photo ->
            if (photo.id == SAMPLE_PHOTO_ID && photo.path.isBlank()) {
                photo.copy(path = SAMPLE_PERSISTED_PATH)
            } else {
                photo
            }
        },
    )

    private fun normalizeLoadedSamplePath(loaded: Project): Project = loaded.copy(
        photos = loaded.photos.map { photo ->
            if (photo.id == SAMPLE_PHOTO_ID && photo.path == SAMPLE_PERSISTED_PATH) {
                photo.copy(path = "")
            } else {
                photo
            }
        },
    )

    private companion object {
        const val AUTOSAVE_MS = 400L
        const val SAMPLE_PERSISTED_PATH = "framemark:sample"
    }
}

data class PhonePhotoEntry(val photo: PhotoRef, val design: PhotoDesign?)

/**
 * The photos among [candidateIds] that still need a fingerprint: still in the
 * project, still carrying a file path, and not hashed yet.
 *
 * Pure so the "what does the background pass actually hash" decision is testable
 * without a scope or a real file: the sample photo (blank path) is skipped, an
 * already-hashed photo is never re-read, and an id that is no longer in the
 * project (removed between adopt and hash) produces nothing.
 */
internal fun photosNeedingFingerprint(project: Project, candidateIds: Collection<String>): List<PhotoRef> {
    val candidates = candidateIds.toSet()
    if (candidates.isEmpty()) return emptyList()
    return project.photos.filter { photo ->
        photo.id in candidates && photo.fingerprint == null && photo.path.isNotBlank()
    }
}

/**
 * Applies [fingerprints] to the photos that are **still present and still
 * un-hashed**. A photo removed while its hash was running is simply absent from
 * [photos], so the patch cannot resurrect it; a photo that already has a digest is
 * left alone (first writer wins).
 */
internal fun Project.withFingerprints(fingerprints: Map<String, String>): Project {
    if (fingerprints.isEmpty()) return this
    var changed = false
    val patched = photos.map { photo ->
        val fingerprint = fingerprints[photo.id]
        if (fingerprint != null && photo.fingerprint == null) {
            changed = true
            photo.copy(fingerprint = fingerprint)
        } else {
            photo
        }
    }
    return if (changed) copy(photos = patched) else this
}

// ---------------------------------------------------------------------------
// Mapping helpers
// ---------------------------------------------------------------------------

/** Declared value of one inspector control, in the session's own types. */
fun declaredValue(value: ControlValue): Any = when (value) {
    is ControlValue.Flag -> value.value
    is ControlValue.Num -> value.value
    is ControlValue.Str -> value.value
}

/**
 * render-v1 inputs for the phone renderer. Its frozen `DesignSnapshot` shape has no
 * theme/ratio field — the renderer reads both out of `params` — so the design's own
 * fields are folded in rather than dropped. `fontScale` and the brand-logo pick are
 * folded the same way: they live on dedicated fields, and leaving them out is how a
 * control that visibly moved in the inspector silently exports unchanged.
 */
fun PhotoDesign.toPhoneSnapshot(): DesignSnapshot {
    val snapshot = toDesignSnapshot()
    // Android's native bridge also accepts this legacy params key directly.
    val logoParam = logo?.takeIf { it.isNotBlank() }?.let { "brandLogoFile" to it }
    return snapshot.copy(params = snapshot.params + listOfNotNull(logoParam))
}

/** Rebuilds params/text/theme/fontScale/logo from the card's changed controls. */
fun foldOverrides(design: PhotoDesign, model: InspectorCardModel, changes: Map<String, Any>): PhotoDesign {
    return design.applyInspectorOverrides(model, changes)
}

/** Session values for one card: the design's overrides on top of the card defaults. */
fun sessionValuesFor(model: InspectorCardModel, design: PhotoDesign?): Map<String, Any> {
    val out = LinkedHashMap<String, Any>()
    model.controls.forEach { control ->
        val stored = design?.let { valueFor(it, control.param) }
        val value = stored ?: declaredValue(control.initial)
        out[control.key] = if (value is Double && control.type == ControlType.Slider) value.toFloat() else value
    }
    return out
}

private fun valueFor(design: PhotoDesign, param: String): Any? = when (param) {
    "theme" -> design.theme
    "ratio" -> design.ratio
    "fontScale" -> design.fontScale
    "text.fontScale" -> design.fontScale
    "design.logo" -> design.logo
    "logo", "brandLogoFile" -> design.logo
    // The brand picker's control param is `brandLogoFile` (IR rows) or
    // `params.brandLogoFile` (capability rows), but the value is stored on the design's
    // own `logo` field (consumer == "logo"). Reading only params made a saved pick show
    // up as 自动匹配 after a restart.
    "params.brandLogoFile", "brandLogoFile" -> design.params["brandLogoFile"] ?: design.logo
    else -> when {
        param.startsWith("params.") -> design.params[param.removePrefix("params.")]
        param.startsWith("text.") -> design.text[param.removePrefix("text.")]
        else -> null
    }
}

/**
 * Evidence-only: an adb launch may choose the format / size the export sheet offers,
 * so a PNG or original-size export can be driven without tapping through the sheet.
 */
internal fun ExportSettings.withLaunchExportOverrides(): ExportSettings {
    val raw = com.framemark.native.LaunchOptions.exportFormat ?: return this
    val format = ExportFormat.entries.firstOrNull {
        it.extension.equals(raw, ignoreCase = true) || it.label.equals(raw, ignoreCase = true)
    } ?: return this
    val edge = com.framemark.native.LaunchOptions.exportLongEdge
    return copy(
        format = format,
        webpLossless = com.framemark.native.LaunchOptions.exportWebpLossless,
        sizeMode = if (edge != null) ExportSizeMode.LongEdge else sizeMode,
        longEdge = edge ?: longEdge,
    )
}

/**
 * A legacy preset stored only session-keyed control values. With the card's model
 * those fold into a real design, so old presets migrate without inventing
 * parameters; they never carried a watermark, so none is added here.
 */
fun legacyToPreset(legacy: Preset, cardId: String, styleId: String?, model: InspectorCardModel?): ProjectPreset {
    val base = PhotoDesign.base(cardId, styleId)
    val design = if (model != null) foldOverrides(base, model, legacy.values) else base
    return ProjectPreset(
        id = legacy.id,
        name = ProjectPreset.cleanName(legacy.name),
        design = design,
        exportSettings = legacy.export,
        createdAt = legacy.updatedAt,
        updatedAt = legacy.updatedAt,
    )
}

/** Fallback so a platform without a file adapter still keeps exactly one authority. */
class InMemoryProductFileSystem(override val dataDir: String = "memory:/") : ProductFileSystem {
    private val files = HashMap<String, ByteArray>()

    override fun exists(path: String): Boolean = files.containsKey(path)

    override fun readText(path: String): String? = files[path]?.decodeToString()

    override fun readBytes(path: String): ByteArray? = files[path]?.copyOf()

    /** In-memory files cannot stream, so the digest is taken from the stored bytes. */
    override fun sha256Of(path: String): String? = files[path]?.let { Sha256.hex(it) }

    override fun writeBytes(path: String, bytes: ByteArray): Boolean {
        files[path] = bytes
        return true
    }

    override fun writeBytesIfAbsent(path: String, bytes: ByteArray): Boolean {
        if (path in files) return false
        files[path] = bytes.copyOf()
        return true
    }

    override fun fileSize(path: String): Long = (files[path]?.size ?: 0).toLong()

    override fun rename(from: String, to: String): Boolean {
        val bytes = files.remove(from) ?: return false
        files[to] = bytes
        return true
    }

    override fun delete(path: String): Boolean = files.remove(path) != null
}
