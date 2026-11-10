package com.framemark.native.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.ControlType
import com.framemark.native.product.BatchJob
import com.framemark.native.product.BatchJobError
import com.framemark.native.product.BatchJobStatus
import com.framemark.native.product.BatchQueue
import com.framemark.native.product.DocumentKind
import com.framemark.native.product.LegacyDocuments
import com.framemark.native.product.PhotoDesign
import com.framemark.native.product.PhotoRef
import com.framemark.native.product.ProjectPreset
import com.framemark.native.product.Project
import com.framemark.native.product.SubmitOutcome
import com.framemark.native.product.ExportSettings
import com.framemark.native.product.readFileBytesBounded
import com.framemark.native.product.SessionStore
import com.framemark.native.product.StoreResult
import com.framemark.native.product.WatermarkAsset
import com.framemark.native.product.ProjectWatermarkSpec
import com.framemark.native.product.toDesignSnapshot
import com.framemark.native.product.toOverlaySpec
import com.framemark.native.engine.PhotoSizes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.UUID
import com.framemark.native.product.FmLog

/**
 * Desktop project state: `product.Project` is the single source of truth and
 * [SessionStore] persists it atomically. The Compose shell only reads [project]
 * and calls these operations; it never reads or writes the project file itself.
 *
 * Autosave debounces 500 ms after a change; [saveNow] flushes synchronously for
 * photo switches and app shutdown. A failed write raises [saveError] instead of
 * pretending the change was saved.
 */
class DesktopProjectController(
    private val dataDir: String,
    private val store: SessionStore = SessionStore(JvmProductFileSystem(dataDir)),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ProjectHolder {
    override var project by mutableStateOf(newProject())
        private set

    override var loaded by mutableStateOf(false)
        private set

    override var loadError by mutableStateOf<String?>(null)
        private set

    override var saveError by mutableStateOf<String?>(null)
        private set

    /** True until a project is known to be readable (or genuinely missing). */
    override var projectWritesBlocked by mutableStateOf(true)
        private set

    override var importing by mutableStateOf(false)
        private set

    override var importFailures by mutableStateOf<List<ImportFailure>>(emptyList())
        private set

    override var saveCount by mutableStateOf(0)
        private set

    override var hydrationVersion by mutableStateOf(0)
        private set

    override var presets by mutableStateOf<List<ProjectPreset>>(emptyList())
        private set

    override var presetError by mutableStateOf<String?>(null)
        private set

    override var batchJobs by mutableStateOf<List<BatchJob>>(emptyList())
        private set

    override var batchRunning by mutableStateOf(false)
        private set

    override var batchError by mutableStateOf<String?>(null)
        private set

    /**
     * The photos a 「按照片批量」 enqueue refused because they have no border of their own
     * (M01 rules 2-3). Populated by [enqueueBatch] itself, so the backend states which
     * photos were 未加入 rather than relying on the UI silently dropping them.
     */
    override var batchUndesignedSkipped by mutableStateOf<List<UndesignedPhoto>>(emptyList())
        private set

    /** Counts of the most recent [enqueueBatch] press; the panel reads it for 本次对账. */
    override var batchLastSubmit by mutableStateOf<SubmitReport?>(null)
        private set

    private var batchQueue = BatchQueue()
    private var batchCancelRequested = false
    private var legacyBatchJobs: List<BatchJob>? = null

    override val batchDone: Int get() = batchJobs.count { it.status == BatchJobStatus.Done }
    override val batchFailed: Int get() = batchJobs.count { it.status == BatchJobStatus.Failed }

    private var presetsLoaded = false
    private var presetLoadFailed = false
    private var presetLoadFailure: String? = null
    private var batchLoaded = false

    private var saveJob: Job? = null
    private val projectSaveLock = Any()

    private val thumbnailCache = HashMap<String, ByteArray?>()
    private val overlayFiles = HashMap<String, File>()

    override val photos: List<PhotoRef> get() = project.photos
    override val activePhoto: PhotoRef? get() = project.activePhoto
    override val activeDesign: PhotoDesign? get() = project.activeDesign
    override val activePhotoId: String? get() = project.activePhotoId

    init {
        DesktopProjectRuntime.current = this
    }

    override fun load() {
        synchronized(projectSaveLock) {
            if (loaded) return
            loadProjectLocked()
        }
    }

    override fun retryProjectLoad(): Boolean = synchronized(projectSaveLock) {
        if (loaded && !projectWritesBlocked) return@synchronized true
        val recovered = loadProjectLocked()
        if (recovered) hydrationVersion++
        recovered
    }

    private fun loadProjectLocked(): Boolean {
        saveJob?.cancel()
        saveJob = null
        projectWritesBlocked = true
        loadError = null
        when (val result = store.loadProject()) {
            is StoreResult.Ok -> {
                project = result.value
                projectWritesBlocked = false
                saveError = null
            }
            StoreResult.Missing -> {
                project = newProject()
                projectWritesBlocked = false
                saveError = null
            }
            is StoreResult.Failed -> {
                loadError = "${result.error.code} ${result.error.message}"
                saveError = projectReadProtectionMessage()
            }
        }
        loaded = true
        return !projectWritesBlocked
    }

    // ---- project mutations -------------------------------------------------

    override fun selectPhoto(id: String) {
        if (!requireWritableProject()) return
        if (project.activePhotoId == id) return
        val selected = project.photos.firstOrNull { it.id == id } ?: return
        // AU1-01: the dimensions are already in the project; publish them to the
        // renderer's single size source so a render never re-reads the photo.
        PhotoSizes.prime(selected.path, selected.widthPx, selected.heightPx)
        saveNow() // 先存后载：当前照片的编辑先落盘
        if (!mutate { it.selectPhoto(id) }) return
        saveNow() // 新选中项也立刻持久化，重启/崩溃后仍回到这张照片
    }

    override fun removePhoto(id: String) {
        if (!requireWritableProject()) return
        if (project.photos.none { it.id == id }) return
        saveNow()
        mutate { it.removePhoto(id) }
    }

    override fun applyCard(cardId: String, styleId: String?) {
        mutate { it.applyCard(cardId, styleId) }
    }

    /**
     * WP-D1 phase 2: copy a source photo's border + parameter/text overrides onto
     * other photos. Only `cardId`, `styleId`, `params` and `text` move; the target
     * keeps its own theme, ratio, logo, font scale and watermark, so this cannot
     * silently duplicate a signature or re-flow a photo. Returns the number of
     * designs that actually changed.
     */
    override fun applyDesignFromPhoto(sourcePhotoId: String, targetPhotoIds: List<String>): Int {
        if (!requireWritableProject()) return -1
        val source = project.designs[sourcePhotoId] ?: return 0
        val targets = targetPhotoIds
            .distinct()
            .filter { it != sourcePhotoId && project.designs.containsKey(it) }
        if (targets.isEmpty()) return 0
        var changed = 0
        val mutated = mutate { current ->
            var next = current
            targets.forEach { photoId ->
                val before = next.designs[photoId] ?: return@forEach
                val after = before.copy(
                    cardId = source.cardId,
                    styleId = source.styleId,
                    params = source.params,
                    text = source.text,
                    baseDesign = source.baseDesign,
                )
                if (after != before) {
                    next = next.setDesign(photoId, after)
                    changed++
                }
            }
            next
        }
        if (mutated && changed > 0) {
            // The active photo may be one of the targets: make the shell re-read it.
            hydrationVersion++
            saveNow()
            FmLog.info("FM_DESIGN_COPY") { "source=$sourcePhotoId targets=${targets.size} changed=$changed" }
        }
        return changed
    }

    override fun setExportSettings(settings: ExportSettings) {
        if (settings == project.exportSettings) return
        mutate { it.withExportSettings(settings) }
    }

    /** Project export options in the UI session's type. */
    /**
     * D-2：导出对话框的设置必须带上**本机**的 JPEG 质量偏好。
     *
     * 这里以前直接返回工程文档里的 `exportSettings`，于是
     * `data/local-export.properties` 写 `jpegQuality=60` 也照样按 100 编码 ——
     * 质量轴看着在动，成片字节与 SHA 却完全一样。批量入队与预设套用本来就
     * 读这份偏好，单张导出走同一条路才对得上。
     */
    override fun exportSettingsForUi(): ExportSettings {
        val settings = project.exportSettings
        val local = WorkflowEnv.platform?.configuredJpegQuality() ?: return settings
        return settings.copy(quality = local)
    }

    /** Writes the inspector's overrides onto the active photo's design. */
    override fun applyControlOverrides(model: InspectorCardModel, overrides: Map<String, Any>) {
        val photoId = project.activePhotoId ?: return
        val current = project.designs[photoId] ?: return
        val prefix = "${model.cardId}::"
        val cardOverrides = overrides.filterKeys { it.startsWith(prefix) }
        val next = current.applyInspectorOverrides(model, cardOverrides)
        mutate { it.setDesign(photoId, next) }
    }

    /** Values for hydrating the UI session from the active design. */
    override fun designValues(model: InspectorCardModel): Map<String, Any> {
        val design = activeDesign ?: return emptyMap()
        val out = LinkedHashMap<String, Any>()
        model.controls.forEach { control ->
            val present = (when (control.param) {
                "theme" -> design.theme
                "ratio" -> design.ratio
                "fontScale" -> design.fontScale
                "text.fontScale" -> design.fontScale
                "design.logo" -> design.logo
                "logo", "brandLogoFile" -> design.logo
                else -> when {
                    control.param.startsWith("params.") -> design.params[control.param.removePrefix("params.")]
                    control.param.startsWith("text.") -> design.text[control.param.removePrefix("text.")]
                    else -> null
                }
            }) ?: return@forEach
            out[control.key] = if (present is Double && control.type == ControlType.Slider) {
                present.toFloat()
            } else {
                present
            }
        }
        return out
    }

    // ---- photos ------------------------------------------------------------

    override suspend fun importPhotos(paths: List<String>): ImportReport = withContext(Dispatchers.IO) {
        importing = true
        val failures = mutableListOf<ImportFailure>()
        var added = 0
        var firstAdded: String? = null
        for (path in paths) {
            val file = File(path)
            if (!file.isFile) {
                failures += ImportFailure(path, "文件不存在")
                continue
            }
            if (!supportedPhoto(file.name)) {
                failures += ImportFailure(path, "不支持的格式（JPEG/PNG/WebP/TIFF）")
                continue
            }
            if (project.photos.any { samePath(it.path, file) }) {
                failures += ImportFailure(path, "已在工程中")
                continue
            }
            // AU1-04: header size + pixel/byte caps + one light decode before the
            // photo becomes part of the project. A truncated or absurd header used
            // to be accepted and then failed on every render.
            val validated = PhotoMetadata.validate(file)
            val meta = validated.getOrElse {
                failures += ImportFailure(path, it.message ?: "无法解码（坏图或格式不支持）")
                continue
            }
            val ref = PhotoRef(
                id = "photo-${UUID.randomUUID()}",
                path = file.absolutePath,
                displayName = file.name,
                orientation = meta.orientation,
                widthPx = meta.width,
                heightPx = meta.height,
                fingerprint = PhotoMetadata.fingerprint(file),
            )
            // AU1-01: these are the dimensions every render chain reuses.
            PhotoSizes.prime(ref.path, ref.widthPx, ref.heightPx)
            if (mutate { it.addPhoto(ref) }) {
                added++
                if (firstAdded == null) firstAdded = ref.id
            }
        }
        firstAdded?.let { id -> mutate { it.selectPhoto(id) } }
        importFailures = failures.toList()
        importing = false
        ImportReport(added, failures.toList())
    }

    override fun pickPhotos(): List<String> {
        val dialog = FileDialog(null as Frame?, "添加照片", FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.setFilenameFilter { _, name -> supportedPhoto(name) }
        dialog.isVisible = true
        return dialog.files?.map { it.absolutePath }.orEmpty()
    }

    override fun pickWatermark(): String? {
        val dialog = FileDialog(null as Frame?, "选择透明 PNG 水印", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.lowercase().endsWith(".png") }
        dialog.isVisible = true
        return dialog.files?.firstOrNull()?.absolutePath
    }

    override fun clearImportFailures() {
        importFailures = emptyList()
    }

    override fun thumbnail(path: String, maxEdge: Int): ByteArray? {
        val key = "$path@$maxEdge"
        if (thumbnailCache.containsKey(key)) return thumbnailCache[key]
        val bytes = PhotoThumbs.jpeg(path, maxEdge)
        thumbnailCache[key] = bytes
        return bytes
    }

    // ---- watermark ---------------------------------------------------------

    override suspend fun importWatermark(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(path)
            require(file.isFile) { "水印文件不存在" }
            require(file.name.lowercase().endsWith(".png")) { "水印必须是 PNG" }
            // Bounded: a 500 MB "watermark" is refused before it enters memory.
            val bytes = readFileBytesBounded(path, WatermarkAsset.MAX_BYTES)
                ?: error("水印文件不存在")
            val meta = PhotoMetadata.read(file) ?: error("无法读取水印图片")
            val asset = WatermarkAsset.create(file.name, "image/png", meta.width, meta.height, bytes)
            val applied = updateActiveOverlay { spec ->
                ProjectWatermarkSpec(
                    asset = asset,
                    anchor = spec?.anchor ?: 8,
                    width = spec?.width ?: 0.25f,
                    opacity = spec?.opacity ?: 0.8f,
                    dx = spec?.dx ?: 0f,
                    dy = spec?.dy ?: 0f,
                )
            }
            check(applied) { "当前照片没有设计，无法添加水印" }
        }
    }

    override fun updateWatermark(
        anchor: Int?,
        width: Float?,
        opacity: Float?,
        dx: Float?,
        dy: Float?,
        rotation: Float?,
    ) {
        updateActiveOverlay { spec ->
            if (spec == null) {
                null
            } else {
                spec.copy(
                    anchor = anchor?.coerceIn(0, 8) ?: spec.anchor,
                    width = width?.coerceIn(ProjectWatermarkSpec.MIN_WIDTH, ProjectWatermarkSpec.MAX_WIDTH) ?: spec.width,
                    opacity = opacity?.coerceIn(0f, 1f) ?: spec.opacity,
                    dx = dx ?: spec.dx,
                    dy = dy ?: spec.dy,
                    rotation = rotation?.coerceIn(
                        -ProjectWatermarkSpec.MAX_ROTATION,
                        ProjectWatermarkSpec.MAX_ROTATION,
                    ) ?: spec.rotation,
                )
            }
        }
    }

    override fun applyWatermarkToAll() {
        mutate { it.applyWatermarkToAll() }
    }

    override fun clearWatermark() {
        updateActiveOverlay { null }
    }

    override fun watermark(): ProjectWatermarkSpec? = activeDesign?.overlay

    override fun watermarkBytes(): ByteArray? = activeDesign?.overlay?.asset?.bytes

    /** Engine `overlay` for the active photo; materialises the embedded PNG once per hash. */
    override fun overlaySpec(): OverlaySpec? {
        val design = activeDesign ?: return null
        val spec = design.overlay ?: return null
        val file = materialize(spec.asset)
        val aspect = if (spec.photoAspect.isFinite() && spec.photoAspect > 0.01f) {
            spec.photoAspect
        } else {
            PreviewGeometry.aspectOf(design.cardId)
                ?: activePhoto?.let {
                    it.widthPx.toFloat() / it.heightPx.coerceAtLeast(1)
                }
                ?: 1.5f
        }
        return spec.toOverlaySpec(file.path, aspect)
    }

    private fun materialize(asset: WatermarkAsset): File {
        val dir = File(dataDir, "overlays").apply { mkdirs() }
        val file = File(dir, "${asset.sha256.lowercase()}.png")
        overlayFiles[asset.sha256]?.let { if (it.isFile && it.length() == asset.bytes.size.toLong()) return it }
        if (!file.isFile || file.length() != asset.bytes.size.toLong()) {
            file.writeBytes(asset.bytes)
        }
        overlayFiles[asset.sha256] = file
        return file
    }

    // ---- export ------------------------------------------------------------

    override suspend fun exportActive(cardName: String, settings: ExportSettings): ExportResult {
        val photo = activePhoto ?: throw EngineFailure("FM-E-INPUT-001", "工程里还没有照片")
        val design = activeDesign ?: throw EngineFailure("FM-E-ENGINE-002", "当前照片还没有设计")
        return NativeEngine.exportCard(
            cardId = design.cardId,
            cardName = cardName,
            styleId = design.styleId,
            settings = settings,
            design = design.toDesignSnapshot(),
            overlay = overlaySpec(),
            photoPath = photo.path,
            nameIndex = photoIndex(photo.id),
            photoDisplayName = photo.displayName,
            outputDirectory = WorkflowEnv.platform?.exportDir,
        )
    }

    // ---- presets -----------------------------------------------------------

    override fun loadPresets() {
        if (presetsLoaded) return
        presetsLoaded = true
        when (val result = store.loadPresets()) {
            is StoreResult.Ok -> {
                presets = result.value
                presetLoadFailed = false
                presetLoadFailure = null
                presetError = null
            }
            StoreResult.Missing -> {
                presets = emptyList()
                presetLoadFailed = false
                presetLoadFailure = null
                presetError = null
            }
            is StoreResult.Failed -> {
                presetLoadFailed = true
                presetLoadFailure = "${result.error.code} ${result.error.message}"
                presetError = presetLoadFailure
                val legacyText = store.documentText(DocumentKind.PresetLibrary)
                if (legacyText != null && LegacyDocuments.isEmptyLegacyPresetLibrary(legacyText)) {
                    when (val migration = store.migrateLegacyPresetLibrary(emptyList())) {
                        is StoreResult.Ok -> {
                            presets = migration.value
                            presetLoadFailed = false
                            presetLoadFailure = null
                            presetError = null
                            FmLog.info("FM_PRESET_EMPTY_LEGACY_MIGRATED") { "sha=verified" }
                        }
                        is StoreResult.Failed -> {
                            presetLoadFailure = "${migration.error.code} ${migration.error.message}"
                            presetError = presetLoadFailure
                        }
                        StoreResult.Missing -> Unit
                    }
                }
            }
        }
        FmLog.info("FM_PRODUCT_PRESETS") { "count=${presets.size} error=${presetError ?: "-"}" }
    }

    override fun clearPresetError() {
        presetError = null
    }

    override fun savePreset(name: String): ProjectPreset? {
        loadPresets()
        val preset = ProjectPreset.fromProject(project, id = "p${clock()}", name = name, now = clock())
        if (preset == null) {
            presetError = "FM-E-PRESET-001 当前照片还没有可保存的设计"
            return null
        }
        val next = listOf(preset) + presets
        return if (persistPresets(next)) {
            presetError = null
            preset
        } else {
            null
        }
    }

    override fun deletePreset(id: String): Boolean {
        val target = presets.firstOrNull { it.id == id } ?: return false
        return persistPresets(presets.filterNot { it.id == id }).also { ok ->
            if (ok) FmLog.info("FM_PRODUCT_PRESET_DELETED") { "id=$id name=${target.name}" }
        }
    }

    override fun renamePreset(id: String, name: String): Boolean {
        val clean = name.trim()
        if (clean.isBlank()) return false
        val index = presets.indexOfFirst { it.id == id }
        if (index < 0) return false
        val next = presets.toMutableList()
        next[index] = next[index].copy(name = clean, updatedAt = clock())
        return persistPresets(next)
    }

    override fun applyPreset(id: String): Boolean {
        if (!requireWritableProject()) {
            presetError = projectReadProtectionMessage()
            return false
        }
        val preset = presets.firstOrNull { it.id == id } ?: return false
        val photoId = project.activePhotoId ?: run {
            presetError = "FM-E-INPUT-001 工程里还没有照片，无法套用预设"
            return false
        }
        val design = preset.design
        val applied = design.copy(baseDesign = design.isUntouched)
        val localQuality = WorkflowEnv.platform?.preferredJpegQuality() ?: project.exportSettings.quality
        val changed = mutate {
            it.setDesign(photoId, applied)
                .withExportSettings(preset.exportSettings.copy(quality = localQuality))
        }
        if (!changed && projectWritesBlocked) {
            presetError = projectReadProtectionMessage()
            return false
        }
        // The preset's design may name a different border than the session is showing,
        // so the shell must re-read the active design even when the photo is unchanged.
        hydrationVersion++
        if (!saveNow()) {
            presetError = saveError ?: "FM-E-IO-003 预设已应用，但工程未写入磁盘"
            FmLog.warn("FM_PRODUCT_PRESET_APPLY_FAIL") { "id=$id save=$saveError" }
            return false
        }
        presetError = null
        FmLog.info("FM_PRODUCT_PRESET_APPLIED") {
            "id=$id card=${applied.cardId} " +
                "watermark=${applied.overlay?.asset?.sha256?.take(12) ?: "-"}"
        }
        return true
    }

    override fun exportPresetFile(id: String, path: String): Boolean {
        val preset = presets.firstOrNull { it.id == id } ?: return false
        return when (val result = store.exportPreset(preset, path)) {
            is StoreResult.Ok -> {
                presetError = null
                FmLog.info("FM_PRODUCT_PRESET_EXPORTED") { "id=$id path=$path" }
                true
            }
            is StoreResult.Failed -> {
                presetError = "${result.error.code} ${result.error.message}"
                false
            }
            StoreResult.Missing -> false
        }
    }

    override fun importPresetFile(path: String): ProjectPreset? {
        val imported = readPresetFile(path) ?: return null
        return imported.takeIf { importPreset(it) }
    }

    override fun readPresetFile(path: String): ProjectPreset? {
        loadPresets()
        return when (val result = store.importPreset(path)) {
            is StoreResult.Ok -> result.value
            is StoreResult.Failed -> {
                presetError = "${result.error.code} ${result.error.message}"
                null
            }
            StoreResult.Missing -> {
                presetError = "FM-E-PRESET-001 预设文件不存在或无法读取"
                null
            }
        }
    }

    override fun importPreset(preset: ProjectPreset, replaceExisting: Boolean): Boolean {
        loadPresets()
        // A failed library load must never be followed by a save of an empty or
        // partial list over the user's existing preset file.
        if (blockPresetWriteAfterLoadFailure()) return false

        val existingIndex = presets.indexOfFirst { it.id == preset.id }
        if (existingIndex >= 0 && !replaceExisting) {
            presetError = "FM-E-PRESET-001 预设 ID 已存在；原预设未更改"
            return false
        }

        val next = if (existingIndex >= 0) {
            presets.toMutableList().also { it[existingIndex] = preset }
        } else {
            listOf(preset) + presets
        }
        if (!persistPresets(next)) return false

        presetError = null
        FmLog.info("FM_PRODUCT_PRESET_IMPORTED") { "id=${preset.id} replace=${existingIndex >= 0}" }
        return true
    }

    override fun pickPresetExportPath(suggestedName: String): String? {
        val dialog = FileDialog(null as Frame?, "导出预设", FileDialog.SAVE)
        dialog.file = suggestedName.ifBlank { "framemark-preset" } + ".json"
        dialog.isVisible = true
        return dialog.file?.let { File(it).absolutePath }
    }

    override fun pickPresetImportPath(): String? {
        val dialog = FileDialog(null as Frame?, "导入预设文件", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.lowercase().endsWith(".json") }
        dialog.isVisible = true
        return dialog.files?.firstOrNull()?.absolutePath
    }

    private fun persistPresets(next: List<ProjectPreset>): Boolean {
        if (blockPresetWriteAfterLoadFailure()) return false
        return when (val result = store.savePresets(next)) {
            is StoreResult.Ok -> {
                presets = next
                true
            }
            is StoreResult.Failed -> {
                presetError = "${result.error.code} 预设没有写入：${result.error.message}"
                false
            }
            StoreResult.Missing -> {
                presetError = "FM-E-IO-003 预设没有写入：存储不可用"
                false
            }
        }
    }

    private fun blockPresetWriteAfterLoadFailure(): Boolean {
        if (!presetLoadFailed) return false
        presetError = presetLoadFailure ?: "FM-E-PRESET-001 预设库读取失败；预设没有写入"
        return true
    }

    override fun importLegacyPresets(converted: List<ProjectPreset>): Int {
        if (converted.isEmpty()) return 0
        loadPresets()
        if (!presetLoadFailed) {
            presetError = "FM-E-PRESET-001 当前预设库无需旧格式迁移"
            return 0
        }
        return when (val migration = store.migrateLegacyPresetLibrary(converted)) {
            is StoreResult.Ok -> {
                presets = migration.value
                presetLoadFailed = false
                presetLoadFailure = null
                presetError = null
                FmLog.info("FM_PRESET_MIGRATED") { "count=${converted.size} total=${migration.value.size}" }
                converted.size
            }
            is StoreResult.Failed -> {
                presetError = "${migration.error.code} ${migration.error.message}"
                0
            }
            StoreResult.Missing -> {
                presetError = "FM-E-PRESET-001 旧预设库不存在，未执行迁移"
                0
            }
        }
    }

    override val legacyBatchPending: Boolean get() = legacyBatchJobs != null

    override fun migrateLegacyBatch(): Int {
        val legacy = legacyBatchJobs ?: return 0
        loadBatch()
        val merged = batchQueue
        val added = legacy.count { candidate -> merged.jobs.none { it.idempotencyKey == candidate.idempotencyKey } }
        val next = BatchQueue(merged.jobs + legacy.filter { candidate ->
            merged.jobs.none { it.idempotencyKey == candidate.idempotencyKey }
        })
        batchQueue = next
        batchJobs = next.jobs
        if (!persistBatch()) return 0
        return when (val reread = store.loadBatch()) {
            is StoreResult.Ok -> {
                if (reread.value.recoverAfterRestart().jobs.map { it.idempotencyKey } !=
                    next.jobs.map { it.idempotencyKey }
                ) {
                    batchError = "批量队列迁移后重读不一致"
                    0
                } else {
                    legacyBatchJobs = null
                    FmLog.info("FM_BATCH_MIGRATED") { "count=$added total=${next.jobs.size}" }
                    added
                }
            }
            is StoreResult.Failed -> {
                batchError = "${reread.error.code} 批量队列迁移后无法重读"
                0
            }
            StoreResult.Missing -> {
                batchError = "FM-E-IO-003 批量队列迁移后文档不存在"
                0
            }
        }
    }

    // ---- batch -------------------------------------------------------------

    override fun loadBatch() {
        if (batchLoaded) return
        batchLoaded = true
        when (val result = store.loadBatch()) {
            is StoreResult.Ok -> {
                batchQueue = recoverBatchQueue(result.value, WorkflowEnv.platform, clock())
                batchJobs = batchQueue.jobs
                val rebuildCount = batchQueue.jobs.count { it.requiresRebuild && it.status == BatchJobStatus.Queued }
                if (rebuildCount > 0) {
                    batchError = "$rebuildCount 条旧队列任务缺少稳定序号或输出目录；请移除后重新加入，不会猜测原设置。"
                }
                if (batchQueue.jobs.isNotEmpty()) persistBatch()
            }
            StoreResult.Missing -> {
                batchQueue = BatchQueue()
                batchJobs = emptyList()
                adoptLegacyBatch()
            }
            is StoreResult.Failed -> {
                batchError = "${result.error.code} ${result.error.message}"
                adoptLegacyBatch()
            }
        }
        FmLog.info("FM_PRODUCT_BATCH_LOADED") {
            "items=${batchJobs.size} " +
                "pending=${batchQueue.jobs.count { it.status == BatchJobStatus.Queued }} " +
                "legacyPending=${legacyBatchJobs != null}"
        }
    }

    /** The old hand-written `{"version":1,"items":[…]}` queue, when that is what is on disk. */
    private fun adoptLegacyBatch() {
        val text = store.documentText(DocumentKind.Batch) ?: return
        if (LegacyDocuments.isProductBatch(text)) return
        legacyBatchJobs = LegacyDocuments.legacyBatch(text)
        legacyBatchJobs?.let { batchJobs = it }
    }

    override fun enqueueBatch(photoIds: List<String>): Int {
        loadBatch()
        var added = 0
        var duplicates = 0
        val rejected = mutableListOf<Pair<String, String>>()
        val addedIds = mutableListOf<String>()
        val platform = WorkflowEnv.platform
        if (platform == null) {
            batchError = "存储不可用，无法固定批量导出目录"
            // Nothing could be queued, and the selected photos are accounted for as refused,
            // so 选择 = 入队 + 无设计 + 重复 + 拒绝 still adds up for the panel.
            batchLastSubmit = SubmitReport(
                added = 0,
                duplicates = 0,
                rejected = listOf("<全部>" to "存储不可用"),
                selected = photoIds.size,
                undesigned = 0,
            )
            return 0
        }
        // M01 rules 1-3: an existing photo with no border of its own is 未加入, reported
        // here by the backend rather than silently dropped. Only photos with their own
        // binding are enqueued, each keeping its original list position as nameIndex.
        val preflight = project.batchPreflight(photoIds)
        batchUndesignedSkipped = preflight.undesigned
        // Ids the pre-flight neither designed nor marked as 无设计 (a photo removed between
        // the selection and this call). They used to vanish from every count; name them so
        // 选择 = 入队 + 无设计 + 重复 + 拒绝 still holds.
        val accounted = preflight.designedPhotoIds.toSet() + preflight.undesigned.map { it.photoId }.toSet()
        photoIds.filterNot { it in accounted }.forEach { stale ->
            val name = project.photos.firstOrNull { it.id == stale }?.displayName ?: stale
            rejected += name to "照片已不在项目中，未加入"
        }
        for (photoId in preflight.designedPhotoIds) {
            val photo = project.photos.firstOrNull { it.id == photoId }
            val design = project.designs[photoId]
            if (photo == null || design == null) {
                // The project changed between the pre-flight and the loop; say so instead
                // of dropping the item, otherwise the counts stop reconciling.
                rejected += (photo?.displayName ?: photoId) to "照片或设计已不存在，未加入"
                continue
            }
            val photoIndex = project.photos.indexOfFirst { it.id == photo.id } + 1
            val exportSettings = project.exportSettings.copy(quality = platform.preferredJpegQuality())
            val job = BatchJob.forPhoto(
                id = "job-${UUID.randomUUID().toString().take(8)}",
                photoPath = photo.path,
                photoFingerprint = photo.fingerprint,
                displayName = photo.displayName,
                design = design,
                exportSettings = exportSettings,
                now = clock(),
                nameIndex = photoIndex,
                outputDirectory = platform.exportDir,
            )
            when (val outcome = batchQueue.submit(job)) {
                is SubmitOutcome.Added -> {
                    batchQueue = outcome.queue
                    added++
                    addedIds += outcome.jobId
                }
                is SubmitOutcome.Duplicate -> {
                    duplicates++
                    FmLog.info("FM_PRODUCT_BATCH_DUPLICATE") { "photo=${photo.displayName} existing=${outcome.existingId}" }
                }
            }
        }
        batchJobs = batchQueue.jobs
        if (added > 0) persistBatch()
        batchLastSubmit = SubmitReport(
            added = added,
            duplicates = duplicates,
            rejected = rejected.toList(),
            selected = photoIds.size,
            undesigned = preflight.undesigned.size,
            addedIds = addedIds.toList(),
        )
        FmLog.info("FM_PRODUCT_BATCH_ENQUEUED") {
            "selected=${photoIds.size} added=$added duplicate=$duplicates " +
                "undesigned=${preflight.undesigned.size} rejected=${rejected.size} " +
                "reconciled=${batchLastSubmit?.reconciled} total=${batchJobs.size}"
        }
        return added
    }

    override fun cancelBatch() {
        if (!batchRunning) return
        batchCancelRequested = true
        FmLog.info("FM_PRODUCT_BATCH_CANCEL") { "done=$batchDone" }
    }

    override fun retryBatch(id: String) {
        if (batchQueue.job(id)?.requiresRebuild == true) {
            batchError = "旧队列任务缺少稳定序号或输出目录；请移除后重新加入。"
            return
        }
        batchQueue = batchQueue.retry(id, clock())
        batchJobs = batchQueue.jobs
        batchError = null
        persistBatch()
        FmLog.info("FM_PRODUCT_BATCH_RETRY") { "id=$id" }
    }

    override fun removeBatchJob(id: String) {
        if (batchQueue.jobs.none { it.id == id }) return
        batchQueue = BatchQueue(batchQueue.jobs.filterNot { it.id == id })
        batchJobs = batchQueue.jobs
        persistBatch()
    }

    /**
     * Serial foreground export. Each item is claimed and persisted **before** the
     * engine runs, so a force-kill leaves at most one Running row, which
     * [recoverAfterRestart] puts back in the queue on the next launch.
     */
    override suspend fun runBatch(onProgress: (() -> Unit)?) {
        if (batchRunning) return
        loadBatch()
        batchRunning = true
        batchCancelRequested = false
        batchError = null
        try {
            while (true) {
                if (batchCancelRequested) {
                    batchQueue = BatchQueue(
                        batchQueue.jobs.map {
                            if (it.status == BatchJobStatus.Queued) it.copy(status = BatchJobStatus.Cancelled) else it
                        },
                    )
                    batchJobs = batchQueue.jobs
                    persistBatch()
                    break
                }
                val queued = batchQueue.jobs.firstOrNull {
                    it.status == BatchJobStatus.Queued && !it.requiresRebuild
                }
                if (queued == null) {
                    if (batchQueue.jobs.any { it.status == BatchJobStatus.Queued && it.requiresRebuild }) {
                        batchError = "旧队列任务缺少稳定序号或输出目录；请移除后重新加入。"
                    }
                    break
                }
                batchQueue = batchQueue.claim(queued.id, clock())
                batchJobs = batchQueue.jobs
                if (!persistBatch()) {
                    batchQueue = recoverBatchQueue(batchQueue, WorkflowEnv.platform, clock())
                    batchJobs = batchQueue.jobs
                    break
                }
                onProgress?.invoke()
                var produced: ExportResult? = null
                var failure: String? = null
                try {
                    produced = exportJob(queued)
                } catch (c: CancellationException) {
                    // A cancelled run is not a failed export: the row goes back to
                    // the queue so the next launch (or press of 开始导出) resumes it.
                    batchQueue = recoverBatchQueue(batchQueue, WorkflowEnv.platform, clock())
                    batchJobs = batchQueue.jobs
                    persistBatch()
                    FmLog.info("FM_PRODUCT_BATCH_INTERRUPTED") { "id=${queued.id} photo=${queued.displayName} back_to_queue=true" }
                    throw c
                } catch (t: Throwable) {
                    produced = null
                    failure = (t as? EngineFailure)?.let { "${it.code} ${it.userMessage}" }
                        ?: t.message
                        ?: t::class.simpleName
                        ?: "export failed"
                }
                batchQueue = if (failure == null && produced != null) {
                    batchQueue.complete(queued.id, produced.path, clock())
                } else {
                    batchQueue.fail(queued.id, BatchJobError("FM-E-EXPORT-001", failure ?: "导出失败"), clock())
                }
                batchJobs = batchQueue.jobs
                if (!persistBatch()) break
                onProgress?.invoke()
                FmLog.info("FM_PRODUCT_BATCH_ITEM") {
                    "id=${queued.id} photo=${queued.displayName} " +
                        "status=${batchQueue.job(queued.id)?.status} path=${produced?.path ?: "-"}"
                }
            }
        } finally {
            batchRunning = false
        }
        FmLog.info("FM_PRODUCT_BATCH_SETTLED") { "done=$batchDone failed=$batchFailed cancelled=${batchQueue.cancelledCount}" }
    }

    /**
     * Starts the queue on the controller's own scope. It must not ride a Compose
     * effect scope: a recomposition (library reload, sheet open/close) would cancel
     * the in-flight export, so the work outlives the surface that started it.
     */
    override fun startBatch() {
        loadBatch()
        if (batchRunning) return
        scope.launch { runCatching { runBatch() } }
    }

    private suspend fun exportJob(job: BatchJob): ExportResult {
        if (job.requiresRebuild) {
            throw EngineFailure("FM-E-BATCH-001", "旧队列缺少稳定序号或输出目录，请重新加入任务")
        }
        val photo = project.photos.firstOrNull { it.fingerprint == job.photoFingerprint }
            ?: project.photos.firstOrNull { samePath(it.path, File(job.photoPath ?: "")) }
            ?: throw EngineFailure("FM-E-INPUT-001", "队列里的照片已不在工程中：${job.displayName}")
        if (!File(photo.path).isFile) {
            throw EngineFailure("FM-E-INPUT-001", "照片文件读不到：${photo.path}")
        }
        val platform = WorkflowEnv.platform
            ?: throw EngineFailure("FM-E-IO-003", "本地存储不可用")
        val storedFingerprint = job.photoFingerprint
        if (!storedFingerprint.isNullOrBlank() && platform.fileFingerprint(photo.path) != storedFingerprint) {
            throw EngineFailure("FM-E-INPUT-001", "照片内容已变化，请重新加入任务：${photo.displayName}")
        }
        val settings = job.exportSettings
        return NativeEngine.exportCard(
            cardId = job.design.cardId,
            cardName = job.design.cardId,
            styleId = job.design.styleId,
            settings = settings,
            design = job.design.toDesignSnapshot(),
            overlay = job.overlaySpec(),
            photoPath = photo.path,
            nameIndex = job.nameIndex ?: throw EngineFailure("FM-E-BATCH-001", "队列缺少稳定文件序号"),
            photoDisplayName = job.displayName,
            outputDirectory = job.outputDirectory,
            onOutputPrepared = { path, size, sha ->
                persistPreparedBatchOutput(job.id, path, size, sha, job.outputReceiptKey)
            },
        )
    }

    private fun persistPreparedBatchOutput(
        jobId: String,
        path: String,
        size: Long,
        sha256: String,
        expectedReceiptKey: String,
    ): Boolean {
        val before = batchQueue
        val current = before.job(jobId) ?: return false
        if (current.status != BatchJobStatus.Running || current.outputReceiptKey != expectedReceiptKey) return false
        val next = before.update(jobId) { running ->
            running.copy(
                preparedOutputPath = path,
                preparedOutputBytes = size,
                preparedOutputSha256 = sha256,
                preparedInputKey = running.outputReceiptKey,
            )
        }
        if (next == before) return false
        batchQueue = next
        batchJobs = next.jobs
        if (persistBatch()) return true
        batchQueue = before
        batchJobs = before.jobs
        return false
    }

    /** Engine overlay for a queued job, built from the job's own watermark bytes. */
    private fun BatchJob.overlaySpec(): OverlaySpec? {
        val spec = overlay ?: return null
        val file = materialize(spec.asset)
        val aspect = PreviewGeometry.aspectOf(design.cardId)
            ?: project.photos.firstOrNull { it.fingerprint == photoFingerprint }?.let {
                it.widthPx.toFloat() / it.heightPx.coerceAtLeast(1)
            }
            ?: 1.5f
        return spec.toOverlaySpec(file.path, aspect)
    }

    private fun persistBatch(): Boolean = when (store.saveBatch(batchQueue)) {
        is StoreResult.Ok -> true
        is StoreResult.Failed -> {
            batchError = "批量队列没有写入磁盘"
            false
        }
        StoreResult.Missing -> false
    }

    override fun revealInFolder(path: String): String? = WorkflowEnv.platform?.revealInFolder(path)
        ?: "存储不可用"

    // ---- persistence -------------------------------------------------------

    override fun saveNow(): Boolean = synchronized(projectSaveLock) {
        saveJob?.cancel()
        saveJob = null
        if (!requireWritableProject()) {
            FmLog.warn("FM_PROJECT_SAVE_BLOCKED") { "reason=project-read-failed" }
            return@synchronized false
        }
        val snapshot = project
        when (val result = store.saveProject(snapshot)) {
            is StoreResult.Ok -> {
                saveError = null
                saveCount++
                true
            }
            StoreResult.Missing -> false
            is StoreResult.Failed -> {
                saveError = "${result.error.code} ${result.error.message}"
                false
            }
        }
    }

    override fun flush() {
        saveNow()
    }

    fun dispose() {
        flush()
        scope.cancel()
        if (DesktopProjectRuntime.current === this) DesktopProjectRuntime.current = null
    }

    private fun scheduleSave() {
        if (projectWritesBlocked) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            saveNow()
        }
    }

    private fun mutate(block: (Project) -> Project): Boolean {
        synchronized(projectSaveLock) {
            if (!requireWritableProject()) return false
            val next = block(project)
            if (next == project) return false
            project = next.touched(clock())
            scheduleSave()
            return true
        }
    }

    private fun requireWritableProject(): Boolean {
        if (!projectWritesBlocked) return true
        saveError = projectReadProtectionMessage()
        return false
    }

    private fun projectReadProtectionMessage(): String {
        val reason = loadError ?: "工程尚未成功读取"
        return "FM-E-IO-002 已阻止写入以保护原工程；请重试读取。$reason"
    }

    private fun updateActiveOverlay(transform: (ProjectWatermarkSpec?) -> ProjectWatermarkSpec?): Boolean {
        val photoId = project.activePhotoId ?: return false
        val current = project.designs[photoId] ?: return false
        val next = transform(current.overlay)
        if (next == current.overlay) return false
        return mutate { it.setDesign(photoId, current.withOverlay(next)) }
    }

    private fun samePath(stored: String, file: File): Boolean {
        val a = runCatching { File(stored).canonicalPath }.getOrDefault(stored)
        val b = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        return a == b
    }

    private fun supportedPhoto(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "webp", "tif", "tiff")
    }

    private fun newProject(): Project = Project(
        id = "desktop-${UUID.randomUUID()}",
        createdAt = clock(),
        updatedAt = clock(),
    )
}

/** Entry point for app shutdown: flush whatever project the shell has open. */
object DesktopProjectRuntime {
    var current: DesktopProjectController? = null
        internal set

    fun flush() {
        current?.flush()
    }
}
