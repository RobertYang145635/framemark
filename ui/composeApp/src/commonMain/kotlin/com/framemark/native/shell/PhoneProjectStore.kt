package com.framemark.native.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.product.PhotoDesign
import com.framemark.native.product.PhotoRef
import com.framemark.native.product.DocumentKind
import com.framemark.native.product.LegacyDocuments
import com.framemark.native.product.Preset as ProjectPreset
import com.framemark.native.product.ProductFileSystem
import com.framemark.native.product.PRESET_SCHEMA_VERSION
import com.framemark.native.product.Project
import com.framemark.native.product.Sha256
import com.framemark.native.product.SessionStore
import com.framemark.native.product.StoreError
import com.framemark.native.product.StoreResult
import com.framemark.native.product.WatermarkAsset
import com.framemark.native.product.valueOrNull
import com.framemark.native.product.WatermarkSpec as ProjectWatermark
import com.framemark.native.product.ExportFormat as ProductExportFormat
import com.framemark.native.product.ExportSettings as ProductExportSettings
import com.framemark.native.product.ExportSizeMode as ProductExportSizeMode
import com.framemark.native.product.applyConsumer
import com.framemark.native.product.toDesignSnapshot
import com.framemark.native.workflow.BatchInputs
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.ExportSizeMode
import com.framemark.native.workflow.PhoneOverlayFiles
import com.framemark.native.workflow.PresetJson
import com.framemark.native.workflow.ExportSettings as UiExportSettings
import com.framemark.native.workflow.Preset as UiPreset
import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.WatermarkSpec as UiWatermarkSpec
import com.framemark.native.workflow.WorkflowPlatform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** e2b slot override families: the value belongs on one key per bound node. */
private val SLOT_CONSUMERS = setOf("slotText", "slotOff", "slotAlpha", "slotGravity")


/** The bundled sample photo has no file; a fixed id keeps its design persistent. */
const val SAMPLE_PHOTO_ID = "photo:sample"

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

    /** Called by the platform entry point before the first composition. */
    fun install(store: PhoneProjectStore): PhoneProjectStore = store.also { holder = it }

    /** Shared fallback for platforms that installed nothing (a narrow desktop window). */
    fun ensure(fallback: () -> PhoneProjectStore): PhoneProjectStore =
        holder ?: fallback().also { holder = it }

    fun flush() {
        holder?.saveNow()
    }
}

/**
 * The phone's project state: `product.Project` is the single source of truth and
 * `product.SessionStore` writes it atomically, so a force-stop loses nothing. The
 * photo list, one design per photo (border + control values + embedded watermark),
 * the shared design and the export settings all live here — nowhere else.
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
    private var dirty = false

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

    var presets by mutableStateOf<List<ProjectPreset>>(emptyList())
        private set
    var presetError by mutableStateOf<String?>(null)
        private set
    private var presetDocumentState = PresetDocumentState.Unloaded

    val photos: List<PhotoRef> get() = project.photos
    val activePhotoId: String? get() = project.activePhotoId
    val activePhoto: PhotoRef? get() = project.activePhoto
    val activeDesign: PhotoDesign? get() = project.activeDesign

    // ---- lifecycle ---------------------------------------------------------

    fun load() {
        if (loaded) return
        loaded = true
        when (val result = store.loadProject()) {
            is StoreResult.Ok -> {
                project = result.value
                println(
                    "FM_PROJECT_LOADED photos=${project.photos.size} designs=${project.designs.size} " +
                        "active=${project.activePhotoId ?: "-"} wm=${project.designs.values.count { it.overlay != null }}",
                )
            }
            StoreResult.Missing -> println("FM_PROJECT_NONE")
            is StoreResult.Failed -> {
                loadError = "${result.error.code} ${result.error.message}"
                println("FM_PROJECT_LOAD_FAIL code=${result.error.code} msg=${result.error.message}")
            }
        }
        loadPresets()
    }

    fun loadPresets() {
        when (val result = store.loadPresets()) {
            is StoreResult.Ok -> {
                presets = result.value
                presetDocumentState = PresetDocumentState.Loaded
                presetError = null
                println("FM_PRESET_DOC_LOADED count=${presets.size} wm=${presets.count { it.overlay != null }}")
            }
            StoreResult.Missing -> {
                presets = emptyList()
                presetDocumentState = PresetDocumentState.Missing
                presetError = null
            }
            is StoreResult.Failed -> {
                presetDocumentState = PresetDocumentState.Unreadable
                presetError = "${result.error.code} ${result.error.message}"
                println("FM_PRESET_DOC_FAIL code=${result.error.code} msg=${result.error.message}")
                val legacyText = store.documentText(DocumentKind.PresetLibrary)
                if (legacyText != null && LegacyDocuments.isEmptyLegacyPresetLibrary(legacyText)) {
                    when (val migration = store.migrateLegacyPresetLibrary(emptyList())) {
                        is StoreResult.Ok -> {
                            presets = migration.value
                            presetDocumentState = PresetDocumentState.Loaded
                            presetError = null
                            println("FM_PRESET_EMPTY_LEGACY_MIGRATED")
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
     * verifies the length and only then atomically replaces the document, so a
     * failure keeps the previous document and raises [saveError] rather than
     * pretending the change was saved.
     */
    fun saveNow(): Boolean = synchronized(saveLock) {
        val snapshot = project
        when (val result = store.saveProject(snapshot)) {
            is StoreResult.Ok -> {
                dirty = false
                if (saveError != null) saveError = null
                saveCount++
                println(
                    "FM_PROJECT_SAVED photos=${snapshot.photos.size} designs=${snapshot.designs.size} " +
                        "wm=${snapshot.designs.values.count { it.overlay != null }} saves=$saveCount",
                )
                true
            }
            StoreResult.Missing -> false
            is StoreResult.Failed -> {
                saveError = "${result.error.code} ${result.error.message}"
                println("FM_PROJECT_SAVE_FAIL code=${result.error.code} msg=${result.error.message}")
                false
            }
        }
    }

    /** Lands any debounced write before the caller leaves the foreground. */
    fun flushBlocking(): Boolean {
        val pending = synchronized(saveLock) { dirty }
        if (!pending) return true
        val saved = saveNow()
        println("FM_PROJECT_FLUSH pending=true saved=$saved")
        return saved
    }

    private fun scheduleSave() {
        synchronized(saveLock) { dirty = true }
        scope.launch {
            delay(AUTOSAVE_MS)
            if (synchronized(saveLock) { dirty }) saveNow()
        }
    }

    // ---- photos ------------------------------------------------------------

    /** Registers each path as a project photo (idempotent by path) and points at [activePath]. */
    fun adoptPaths(
        paths: List<String>,
        activePath: String?,
        displayNamesByPath: Map<String, String> = emptyMap(),
    ): List<String> {
        var next = project
        paths.forEach { path ->
            if (path.isBlank()) return@forEach
            val id = idForPath(path)
            val displayName = displayNamesByPath[path]?.takeIf { it.isNotBlank() }
                ?: path.substringAfterLast('/').substringAfterLast('\\')
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
                fingerprint = io?.fingerprint(path),
            )
            next = next.addPhoto(ref, inheritFrom(next))
        }
        activePath?.let { next = next.selectPhoto(idForPath(it)) }
        commit(next)
        return next.photos.map { it.id }
    }

    fun adoptSample(): String {
        if (project.photos.none { it.id == SAMPLE_PHOTO_ID }) {
            commit(project.addPhoto(PhotoRef(SAMPLE_PHOTO_ID, "", "示例照片"), inheritFrom(project)))
        }
        return SAMPLE_PHOTO_ID
    }

    fun select(id: String) {
        if (project.activePhotoId == id) return
        if (project.photos.none { it.id == id }) return
        // The active photo's design was already written when it changed, so a switch
        // only re-points the selection and persists it (a restart returns here).
        commit(project.selectPhoto(id))
        saveNow()
        println("FM_PROJECT_SELECT id=$id card=${project.designOf(id)?.cardId ?: "-"}")
    }

    fun remove(id: String) {
        if (project.photos.none { it.id == id }) return
        commit(project.removePhoto(id))
        saveNow()
        println("FM_PROJECT_REMOVE id=$id photos=${project.photos.size} designs=${project.designs.size}")
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
        println(
            "FM_DESIGN_WRITE card=${model.cardId} changes=${changes.size} scoped=${scoped.size} " +
                "sample=${scoped.entries.take(2).joinToString { it.key.removePrefix(prefix) + "=" + it.value }} " +
                "consumers=${model.controls.filter { scoped.containsKey(it.key) }.take(3).joinToString { it.controlId + "->" + it.param }} " +
                "afterParams=${next.params.size} afterText=${next.text.size} same=${next == current}",
        )
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
        println("FM_PROJECT_CARD card=$cardId style=${styleId ?: "-"} photo=$photoId wm=${overlay?.asset?.sha256?.take(8) ?: "none"}")
    }

    private fun ensureDesign(photoId: String, cardId: String?, styleId: String?): PhotoDesign? {
        project.designs[photoId]?.let { return it }
        val resolved = cardId?.takeIf { it.isNotBlank() } ?: project.selectedCardId ?: return null
        val created = PhotoDesign.base(resolved, styleId)
        commit(project.setDesign(photoId, created))
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
        // A photo that has not been given a border yet still gets the signature: the
        // card the editor is showing becomes its design. Only a photo with no card at
        // all can be refused, and it is refused loudly rather than silently dropped.
        val design = ensureDesign(photoId, cardId ?: project.selectedCardId, styleId)
            ?: return StoreError("FM-E-INPUT-001", "还没有选边框，无法加水印", path)
        val bytes = io?.readBytes(path) ?: platform?.readBytes(path)
            ?: return StoreError("FM-E-IO-002", "无法读取水印文件", path)
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
        val spec = ProjectWatermark(
            asset = asset,
            anchor = previous?.anchor ?: 8,
            width = previous?.width ?: 0.25f,
            opacity = previous?.opacity ?: 0.8f,
            dx = previous?.dx ?: 0f,
            dy = previous?.dy ?: 0f,
        )
        commit(project.setDesign(photoId, design.withOverlay(spec)))
        saveNow()
        println("FM_WM_EMBED photo=$photoId sha=${asset.sha256.take(12)} bytes=${asset.byteSize} ${asset.widthPx}x${asset.heightPx}")
        return null
    }

    fun setOverlay(photoId: String, spec: ProjectWatermark?) {
        val design = project.designs[photoId] ?: return
        if (design.overlay == spec) return
        commit(project.setDesign(photoId, design.withOverlay(spec)))
    }

    fun overlayOf(photoId: String): ProjectWatermark? = project.designs[photoId]?.overlay

    /** Copies the active photo's signature onto every photo — the only way it spreads. */
    fun applyWatermarkToAll() {
        val spec = project.activeDesign?.overlay
        if (spec == null) {
            println("FM_WM_APPLY_ALL skipped: active photo has no watermark")
            return
        }
        commit(project.applyWatermarkToAll(spec))
        saveNow()
        println("FM_WM_APPLY_ALL photos=${project.photos.size} sha=${spec.asset.sha256.take(12)}")
    }

    fun clearActiveWatermark() {
        val photoId = project.activePhotoId ?: return
        setOverlay(photoId, null)
        saveNow()
    }

    /** Writes the embedded bytes out for the engine once per digest. */
    fun materializeOverlayFile(spec: ProjectWatermark): String? = PhoneOverlayFiles.materialize(spec)

    /** The editor's path-based watermark state, derived from the project's own spec. */
    fun uiWatermark(photoId: String, photoAspect: Float): UiWatermarkSpec? {
        val spec = overlayOf(photoId) ?: return null
        val file = materializeOverlayFile(spec) ?: return null
        return UiWatermarkSpec(
            path = file,
            aspect = spec.asset.aspect,
            anchor = spec.anchor,
            width = spec.width,
            opacity = spec.opacity,
            dx = spec.dx,
            dy = spec.dy,
            photoAspect = photoAspect,
        )
    }

    /** Maps the editor's anchor/drag edits back onto the embedded spec. */
    fun projectWatermark(current: ProjectWatermark?, ui: UiWatermarkSpec?): ProjectWatermark? {
        if (ui == null) return null
        val asset = current?.asset ?: return null
        return ProjectWatermark(asset = asset, anchor = ui.anchor, width = ui.width, opacity = ui.opacity, dx = ui.dx, dy = ui.dy)
    }

    // ---- export settings ---------------------------------------------------

    fun exportSettingsForUi(): UiExportSettings = project.exportSettings.toUi()

    fun setExportSettings(settings: UiExportSettings) {
        val mapped = settings.toProduct()
        if (mapped == project.exportSettings) return
        commit(project.withExportSettings(mapped))
    }

    // ---- presets -----------------------------------------------------------

    /** Captures the active photo's design (watermark bytes included) as a preset. */
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
        return if (persistPresets(presets + preset)) preset else null
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
        commit(
            project.setDesign(photoId, design)
                .withExportSettings(
                    preset.exportSettings.copy(quality = platform?.preferredJpegQuality() ?: project.exportSettings.quality),
                )
                .copy(selectedCardId = cardId),
        )
        saveNow()
        println(
            "FM_PRESET_APPLY id=${preset.id} card=$cardId photo=$photoId " +
                "wm=${preset.overlay?.asset?.sha256?.take(12) ?: "none"} params=${design.params.size} text=${design.text.size}",
        )
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

        val saved = store.savePresets(presets + normalized)
        if (saved !is StoreResult.Ok) {
            if (readRawBytes(path)?.contentEquals(originalBytes) != true) {
                restorePresetBytes(path, originalBytes)
            }
            presetError = "FM-E-PRESET-001 旧预设迁移写入失败；原库已备份：$backupPath"
            return 0
        }

        val verified = store.loadPresets().valueOrNull()
        val expectedReadback = (presets + normalized).map {
            it.copy(exportSettings = it.exportSettings.copy(quality = 100))
        }
        if (verified != expectedReadback) {
            val restored = restorePresetBytes(path, originalBytes)
            presetError = buildString {
                append("FM-E-PRESET-001 迁移读回校验失败；原库备份：").append(backupPath)
                if (!restored) append("（自动恢复未完成，请从备份恢复）")
            }
            presetDocumentState = PresetDocumentState.Unreadable
            return 0
        }

        presets = presets + normalized
        presetDocumentState = PresetDocumentState.Loaded
        presetError = null
        println("FM_PRESET_MIGRATED count=${normalized.size} sha256=$sha backup=$backupPath")
        return normalized.size
    }

    private fun persistPresets(next: List<ProjectPreset>): Boolean {
        if (!ensurePresetDocumentWritable()) return false
        return when (val result = store.savePresets(next)) {
            is StoreResult.Ok -> {
                presets = next
                presetDocumentState = PresetDocumentState.Loaded
                presetError = null
                println(
                    "FM_PRESET_DOC_SAVED count=${next.size} wm=${next.count { it.overlay != null }} " +
                        "bytes=${next.sumOf { it.embeddedWatermarkBytes }}",
                )
                true
            }
            StoreResult.Missing -> false
            is StoreResult.Failed -> {
                presetError = "${result.error.code} ${result.error.message}"
                println("FM_PRESET_DOC_FAIL code=${result.error.code} msg=${result.error.message}")
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

    private fun restorePresetBytes(path: String, bytes: ByteArray): Boolean {
        val temp = "$path.restore.tmp"
        return try {
            if (!fileSystem.writeBytes(temp, bytes) || fileSystem.fileSize(temp) != bytes.size.toLong()) {
                false
            } else if (!fileSystem.rename(temp, path)) {
                false
            } else {
                readRawBytes(path)?.contentEquals(bytes) == true
            }
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { fileSystem.delete(temp) }
        }
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

        override fun overlay(): ProjectWatermark? = activeDesign?.overlay

        override fun exportSettings(): ProductExportSettings = project.exportSettings

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

    private fun commit(next: Project) = synchronized(saveLock) {
        if (next == project) return
        project = next.touched(nowMillis)
        scheduleSave()
    }

    private companion object {
        const val AUTOSAVE_MS = 400L
    }
}

data class PhonePhotoEntry(val photo: PhotoRef, val design: PhotoDesign?)

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
    var next = design.copy(
        params = emptyMap(),
        text = emptyMap(),
        theme = null,
        fontScale = null,
        logo = null,
        baseDesign = changes.isEmpty(),
    )
    model.controls.forEach { control ->
        val raw = changes[control.key] ?: return@forEach
        next = when (control.param) {
            "theme" -> next.withTheme(raw.toString())
            "text.fontScale" -> next.withFontScale((raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull())
            "design.logo" -> next.withLogo(raw.toString())
            // Bare design fields the render request already carries (theme/ratio ride in
            // params, fontScale and the brand-logo pick are folded by toPhoneSnapshot).
            "theme", "ratio", "fontScale", "logo" -> next.applyConsumer(control.param, raw)
            else -> if (control.param.startsWith("params.") || control.param.startsWith("text.")) {
                var d = next.applyConsumer(control.param, raw)
                // A slot control hides / retitles a NODE SET: the same value has to land
                // on every bound node, not only on the primary one.
                control.companions.forEach { c ->
                    if (c.startsWith("params.") || c.startsWith("text.")) d = d.applyConsumer(c, raw)
                }
                d
            } else if (control.param in SLOT_CONSUMERS && control.companions.isNotEmpty()) {
                var d = next
                control.companions.forEach { c ->
                    if (c.startsWith("params.") || c.startsWith("text.")) d = d.applyConsumer(c, raw)
                }
                println("FM_DESIGN_SLOT controlId=${control.controlId} consumer=${control.param} nodes=${control.companions.size} value=$raw")
                d
            } else {
                // A consumer the render request cannot carry must not vanish silently —
                // the user would believe the edit took effect. Keep it on the design under
                // a clearly marked key so it is visible on disk, and say so in the log.
                println(
                    "FM_DESIGN_DROP controlId=${control.controlId} consumer=${control.param} " +
                        "value=$raw bound=${control.companions.size} reason=no-engine-binding",
                )
                if (control.param.isBlank()) next
                else next.withParam("unmapped_" + control.param.replace('.', '_').replace(':', '_'), raw)
            }
        }
    }
    return next
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
    "text.fontScale" -> design.fontScale
    "design.logo" -> design.logo
    else -> when {
        param.startsWith("params.") -> design.params[param.removePrefix("params.")]
        param.startsWith("text.") -> design.text[param.removePrefix("text.")]
        else -> null
    }
}

internal fun UiExportSettings.toProduct(): ProductExportSettings = ProductExportSettings(
    format = when (format) {
        SampleFormat.Jpeg -> ProductExportFormat.Jpeg
        SampleFormat.Png -> ProductExportFormat.Png
        SampleFormat.WebP -> ProductExportFormat.WebP
    },
    quality = quality,
    sizeMode = when (sizeMode) {
        com.framemark.native.workflow.ExportSizeMode.LongEdge -> ProductExportSizeMode.LongEdge
        else -> ProductExportSizeMode.Original
    },
    longEdge = longEdge,
    filenamePattern = filenamePattern,
    webpLossless = webpLossless,
)

internal fun ProductExportSettings.toUi(): UiExportSettings = UiExportSettings(
    format = when (format) {
        ProductExportFormat.Jpeg -> SampleFormat.Jpeg
        ProductExportFormat.Png -> SampleFormat.Png
        ProductExportFormat.WebP -> SampleFormat.WebP
    },
    quality = quality,
    sizeMode = when (sizeMode) {
        ProductExportSizeMode.LongEdge -> com.framemark.native.workflow.ExportSizeMode.LongEdge
        else -> com.framemark.native.workflow.ExportSizeMode.Original
    },
    longEdge = longEdge ?: 1600,
    filenamePattern = filenamePattern,
    webpLossless = webpLossless,
)

/**
 * Evidence-only: an adb launch may choose the format / size the export sheet offers,
 * so a PNG or original-size export can be driven without tapping through the sheet.
 */
internal fun UiExportSettings.withLaunchExportOverrides(): UiExportSettings {
    val raw = com.framemark.native.LaunchOptions.exportFormat ?: return this
    val format = SampleFormat.entries.firstOrNull {
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
fun legacyToPreset(legacy: UiPreset, cardId: String, styleId: String?, model: InspectorCardModel?): ProjectPreset {
    val base = PhotoDesign.base(cardId, styleId)
    val design = if (model != null) foldOverrides(base, model, legacy.values) else base
    return ProjectPreset(
        id = legacy.id,
        name = ProjectPreset.cleanName(legacy.name),
        design = design,
        exportSettings = legacy.export.toProduct(),
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
