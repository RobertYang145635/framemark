package com.framemark.native.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.framemark.native.library.Json
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.jsonInt
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString
import com.framemark.native.product.BatchJob
import com.framemark.native.product.BatchJobStatus
import com.framemark.native.product.BatchQueue
import com.framemark.native.product.ExportSettings
import com.framemark.native.product.PhotoDesign
import com.framemark.native.product.ProductFileSystem
import com.framemark.native.product.SessionStore
import com.framemark.native.product.StoreResult
import com.framemark.native.product.SubmitOutcome
import com.framemark.native.product.toDesignSnapshot
import com.framemark.native.product.ProjectWatermarkSpec
import com.framemark.native.product.toOverlaySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.framemark.native.product.FmLog

enum class BatchItemStatus { Queued, Running, Done, Failed, Cancelled }

class BatchItem(
    val id: Int,
    val name: String,
    val cardId: String = "",
    val cardName: String = "",
    val styleId: String? = null,
    val photoPath: String? = null,
    val photoFingerprint: String? = null,
    val designKey: String = "",
    val format: SampleFormat = SampleFormat.Jpeg,
    val quality: Int = 100,
    val longEdge: Int = 1600,
    val filenamePattern: String = "{name}-{date}",
    val webpLossless: Boolean = true,
    /**
     * The full render snapshot this item was queued with: photo path, design
     * (params + text), embedded watermark and export settings. A resumed run exports
     * **this**, not whatever the editor is showing, and never falls back to the
     * bundled sample photo.
     */
    val job: BatchJob? = null,
) {
    var progress by mutableFloatStateOf(0f)
    var status by mutableStateOf(BatchItemStatus.Queued)
    var outputPath by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var attempts by mutableIntStateOf(0)
    val isReal: Boolean get() = cardId.isNotBlank()
}

enum class BatchRunState { Idle, Running, Paused, Finished, Cancelled }

private data class BatchCardSpec(val cardId: String, val cardName: String, val styleId: String?)

/**
 * Per-card render inputs a platform can supply. The phone does (so every queued job
 * carries the design, the watermark and the photo it must use); the desktop demo
 * path passes none and keeps its existing card-only queue.
 */
interface BatchInputs {
    fun designFor(cardId: String, styleId: String?): PhotoDesign
    fun overlay(): ProjectWatermarkSpec?
    fun exportSettings(): ExportSettings
    fun photoPath(): String?
    fun fingerprint(path: String?): String?
}

/**
 * Batch queue: real exports through [NativeEngine.exportCard] when seeded with
 * engine-wired cards; the old in-memory animation remains only for the U4
 * verifier's no-engine fixtures.
 *
 * Items are persisted to `dataDir/batch-queue.json`. With a [productFs] (the phone)
 * a queue that holds real items is written as the product document instead — the
 * full snapshot per job (photo path, design, embedded watermark, export settings)
 * plus status — so a killed run resumes with each job's own photo instead of
 * falling back to the bundled sample. [loadPersisted] still reads the older
 * hand-written document, so an upgrade does not lose the queue.
 */
class BatchRunner(
    private val productFs: ProductFileSystem? = null,
    private val inputs: BatchInputs? = null,
) {
    val items = mutableStateListOf<BatchItem>()
    var runState by mutableStateOf(BatchRunState.Idle)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val demoCards = mutableListOf<BatchCardSpec>()
    private var nextId = 1
    private var started = false
    private val productStore: SessionStore? = productFs?.let { SessionStore(it) }

    val completedCount: Int get() = items.count { it.status == BatchItemStatus.Done }
    val failedCount: Int get() = items.count { it.status == BatchItemStatus.Failed }
    val overallProgress: Float
        get() = if (items.isEmpty()) 0f else items.sumOf { it.progress.toDouble() }.toFloat() / items.size
    val hasPending: Boolean
        get() = items.any { it.status == BatchItemStatus.Queued || it.status == BatchItemStatus.Failed }
    val queueFile: String? get() = queuePath()

    init { loadPersisted() }

    private fun queuePath(): String? {
        val platform = WorkflowEnv.platform ?: return null
        return platform.dataDir.trimEnd('/', '\\') + "/batch-queue.json"
    }

    private fun photoMeta(): Pair<String?, String?> {
        val platform = WorkflowEnv.platform
        val path = inputs?.photoPath() ?: platform?.samplePhotoPath()
        val fingerprint = when {
            path == null -> "unavailable"
            inputs != null -> inputs.fingerprint(path) ?: "size:${platform?.fileSize(path) ?: 0}"
            else -> platform?.fileFingerprint(path) ?: "size:${platform?.fileSize(path) ?: 0}"
        }
        return path to fingerprint
    }

    private fun keyFor(cardId: String, styleId: String?, format: SampleFormat, quality: Int, longEdge: Int, pattern: String): String {
        val raw = "$cardId|${styleId ?: ""}|${format.label}|$quality|$longEdge|$pattern"
        // FNV-1a 64: stable, dependency-free design key.
        var h = -0x340d631b7bdddcdbL
        raw.forEach { c -> h = (h xor c.code.toLong()) * 0x100000001b3L }
        return "d" + h.toULong().toString(16)
    }

    /** Seed the demo queue from engine-wired cards (max 10). Existing persisted items win. */
    fun seedDemo(cards: List<LibraryCard>, count: Int = 10) {
        demoCards.clear()
        demoCards.addAll(
            cards.filter { NativeEngine.supportsExport(it.id) }
                .take(count)
                .map { BatchCardSpec(it.id, it.name, it.systemStyle) },
        )
        if (items.isNotEmpty()) {
            FmLog.info("FM_BATCH_KEEP") { "items=${items.size} queue=${queueFile ?: "-"}" }
            return
        }
        if (demoCards.isEmpty()) {
            FmLog.info("FM_BATCH_NO_ENGINE_CARDS")
            return
        }
        val (photo, fp) = photoMeta()
        demoCards.forEach { addReal(it, photo, fp) }
        persist()
        FmLog.info("FM_BATCH_SEEDED") { "items=${items.size} photo=${photo ?: "-"} fingerprint=${fp?.take(16) ?: "-"}" }
    }

    private fun addReal(spec: BatchCardSpec, photo: String?, fp: String?): BatchItem {
        val format = SampleFormat.Jpeg
        val quality = 100
        val longEdge = 1600
        val pattern = "{name}-{date}"
        val item = BatchItem(
            id = nextId,
            name = spec.cardName,
            cardId = spec.cardId,
            cardName = spec.cardName,
            styleId = spec.styleId,
            photoPath = photo,
            photoFingerprint = fp,
            designKey = keyFor(spec.cardId, spec.styleId, format, quality, longEdge, pattern),
            format = format,
            quality = quality,
            longEdge = longEdge,
            filenamePattern = pattern,
            job = snapshotJob(spec, photo, fp),
        )
        nextId += 1
        items.add(item)
        return item
    }

    /**
     * The snapshot a real item resumes from: the card's design, the active photo's
     * watermark, the export settings and the photo this item was queued against.
     */
    private fun snapshotJob(spec: BatchCardSpec, photo: String?, fp: String?): BatchJob? {
        val inputs = inputs ?: return null
        val path = photo ?: return null
        val design = inputs.designFor(spec.cardId, spec.styleId).copy(
            cardId = spec.cardId,
            styleId = spec.styleId,
            overlay = inputs.overlay(),
        )
        return BatchJob.forPhoto(
            id = "batch-${spec.cardId}-${System.nanoTime()}",
            photoPath = path,
            photoFingerprint = fp,
            displayName = spec.cardName,
            design = design,
            exportSettings = inputs.exportSettings(),
            now = WorkflowEnv.platform?.nowMillis() ?: 0L,
        )
    }

    fun addSample(): BatchItem? {
        if (items.size >= 10) return null
        val spec = demoCards.firstOrNull { c -> items.none { it.cardId == c.cardId } } ?: demoCards.firstOrNull()
        if (spec != null) {
            val (photo, fp) = photoMeta()
            val item = addReal(spec, photo, fp)
            persist()
            return item
        }
        val item = BatchItem(nextId, "示例照片 ${nextId.toString().padStart(2, '0')}")
        nextId += 1
        items.add(item)
        return item
    }

    fun remove(item: BatchItem) {
        if (!items.remove(item)) return
        if (items.isEmpty()) {
            runState = BatchRunState.Idle
            started = false
        }
        persist()
    }

    fun start() {
        if (items.isEmpty()) return
        if (items.any { it.isReal }) startReal() else startLegacy()
    }

    private fun startLegacy() {
        items.forEach { item ->
            if (item.status == BatchItemStatus.Done) return@forEach
            item.status = BatchItemStatus.Queued
            item.progress = 0f
        }
        if (items.all { it.status == BatchItemStatus.Done }) {
            runState = BatchRunState.Finished
            return
        }
        started = true
        runState = BatchRunState.Running
    }

    private fun startReal() {
        if (runState == BatchRunState.Running || job?.isActive == true) return
        runState = BatchRunState.Running
        started = true
        persist()
        FmLog.info("FM_BATCH_START") { "items=${items.size} pending=${items.count { it.status != BatchItemStatus.Done }} queue=${queueFile ?: "-"}" }
        job = scope.launch {
            try {
                for (item in items.toList()) {
                    if (runState == BatchRunState.Cancelled) break
                    while (runState == BatchRunState.Paused) delay(80)
                    if (item.status == BatchItemStatus.Done) continue
                    if (item.status != BatchItemStatus.Queued && item.status != BatchItemStatus.Failed) continue
                    item.status = BatchItemStatus.Running
                    item.progress = 0.5f
                    item.error = null
                    item.attempts += 1
                    persist()
                    FmLog.info("FM_BATCH_ITEM_START") { "id=${item.id} card=${item.cardId} attempt=${item.attempts} fingerprint=${item.photoFingerprint?.take(16) ?: "-"} designKey=${item.designKey}" }
                    try {
                        val snapshot = item.job
                        if (snapshot != null) {
                            exportFromSnapshot(item, snapshot)
                        } else {
                            val settings = ExportSettings(
                                format = item.format.toExportFormat(),
                                quality = item.quality,
                                sizeMode = com.framemark.native.product.ExportSizeMode.LongEdge,
                                longEdge = item.longEdge,
                                filenamePattern = item.filenamePattern,
                                webpLossless = item.webpLossless,
                            )
                            val design = DesignSnapshot(item.cardId, item.styleId, emptyMap(), emptyMap())
                            val result = NativeEngine.exportCard(item.cardId, item.cardName, item.styleId, settings, design)
                            item.outputPath = result.path
                        }
                        item.progress = 1f
                        item.status = BatchItemStatus.Done
                        FmLog.info("FM_BATCH_ITEM_DONE") { "id=${item.id} card=${item.cardId} path=${item.outputPath}" }
                    } catch (t: Throwable) {
                        item.progress = 0f
                        item.status = BatchItemStatus.Failed
                        // `EngineFailure.userMessage` is already the sentence; anything else
                        // is a raw Java/engine message, so the copy is fixed and the text
                        // goes to the log (audit items 4 and 5).
                        item.error = (t as? EngineFailure)?.userMessage ?: "导出失败，请重试。"
                        FmLog.warn("FM_BATCH_ITEM_FAIL") { "id=${item.id} card=${item.cardId} raw=${t.message}" }
                    }
                    persist()
                    delay(20)
                }
            } finally {
                if (runState != BatchRunState.Paused) {
                    runState = if (runState == BatchRunState.Cancelled) BatchRunState.Cancelled else BatchRunState.Finished
                    persist()
                    FmLog.info("FM_BATCH_FINISH") { "state=${runState.name} done=$completedCount failed=$failedCount" }
                }
            }
        }
    }

    /**
     * Exports exactly what the queue stored for this item — its photo, its design,
     * its watermark, its export settings. A missing photo path is a visible failure:
     * the bundled sample photo is never substituted.
     */
    private suspend fun exportFromSnapshot(item: BatchItem, snapshot: BatchJob) {
        val path = snapshot.photoPath
        if (path.isNullOrBlank()) {
            throw EngineFailure("FM-E-INPUT-001", "队列里的这条任务没有照片路径，已中止而不是改用示例照片")
        }
        val overlay = snapshot.overlay?.let { spec ->
            val file = PhoneOverlayFiles.materialize(spec)
                ?: throw EngineFailure("FM-E-IO-003", "水印素材无法写出（${spec.asset.name}）")
            val aspect = if (spec.photoAspect.isFinite() && spec.photoAspect > 0.01f) {
                spec.photoAspect
            } else {
                PreviewGeometry.aspectOf(snapshot.design.cardId) ?: 1.5f
            }
            spec.toOverlaySpec(file, aspect)
        }
        val design = snapshot.design
        val settings = snapshot.exportSettings
        val result = NativeEngine.exportCard(
            cardId = design.cardId,
            cardName = item.cardName.ifBlank { design.cardId },
            styleId = design.styleId ?: item.styleId,
            // The snapshot already carries the product's own export settings: no
            // enum/string round-trip, so a resumed job exports exactly what it queued.
            settings = settings.copy(longEdge = settings.longEdge ?: item.longEdge),
            design = design.toDesignSnapshot().copy(styleId = design.styleId ?: item.styleId),
            overlay = overlay,
            photoPath = path,
        )
        item.outputPath = result.path
    }

    fun pause() {
        if (runState != BatchRunState.Running) return
        runState = BatchRunState.Paused
        persist()
        FmLog.info("FM_BATCH_PAUSE") { "done=$completedCount" }
    }

    fun resume() {
        if (runState != BatchRunState.Paused) return
        runState = BatchRunState.Running
        if (items.any { it.isReal } && job?.isActive != true) startReal() else persist()
        FmLog.info("FM_BATCH_RESUME") { "done=$completedCount pending=${items.count { it.status == BatchItemStatus.Queued }}" }
    }

    fun cancel() {
        if (runState != BatchRunState.Running && runState != BatchRunState.Paused) return
        runState = BatchRunState.Cancelled
        items.forEach {
            if (it.status == BatchItemStatus.Running || it.status == BatchItemStatus.Queued) {
                it.status = BatchItemStatus.Cancelled
                it.progress = 0f
            }
        }
        job?.cancel()
        job = null
        persist()
        FmLog.info("FM_BATCH_CANCEL") { "done=$completedCount failed=$failedCount" }
    }

    fun retry(item: BatchItem) {
        if (item.status != BatchItemStatus.Failed && item.status != BatchItemStatus.Cancelled) return
        item.status = BatchItemStatus.Queued
        item.progress = 0f
        item.error = null
        if (runState == BatchRunState.Finished || runState == BatchRunState.Cancelled || runState == BatchRunState.Idle) {
            runState = BatchRunState.Idle
        }
        persist()
        FmLog.info("FM_BATCH_RETRY") { "id=${item.id} card=${item.cardId}" }
    }

    /** Legacy verifier path; real-item queues are advanced by the export coroutine. */
    fun tick() {
        if (items.any { it.isReal }) return
        if (runState != BatchRunState.Running) return
        val active = items.firstOrNull { it.status == BatchItemStatus.Running }
            ?: items.firstOrNull { it.status == BatchItemStatus.Queued }?.also {
                it.status = BatchItemStatus.Running
                it.progress = 0f
            }
            ?: run {
                runState = BatchRunState.Finished
                return
            }
        val step = 0.05f + (active.id % 3) * 0.012f
        active.progress = (active.progress + step).coerceAtMost(1f)
        if (active.progress >= 1f) {
            active.status = BatchItemStatus.Done
            if (items.none { it.status == BatchItemStatus.Queued }) {
                runState = BatchRunState.Finished
            }
        }
    }

    fun resetForVerify() {
        job?.cancel()
        job = null
        items.clear()
        demoCards.clear()
        nextId = 1
        runState = BatchRunState.Idle
        started = false
        persist()
    }

    // ---- persistence -------------------------------------------------------

    /** A queue document is the product one as soon as it carries a real snapshot. */
    private fun useProductDocument(): Boolean = productStore != null && items.any { it.job != null }

    private fun toProductQueue(): BatchQueue {
        var queue = BatchQueue()
        items.forEach { item ->
            val snapshot = item.job ?: return@forEach
            val stamped = snapshot.copy(
                status = when (item.status) {
                    BatchItemStatus.Queued -> BatchJobStatus.Queued
                    BatchItemStatus.Running -> BatchJobStatus.Running
                    BatchItemStatus.Done -> BatchJobStatus.Done
                    BatchItemStatus.Failed -> BatchJobStatus.Failed
                    BatchItemStatus.Cancelled -> BatchJobStatus.Cancelled
                },
                attempts = item.attempts,
                outputPath = item.outputPath,
                error = item.error?.let { com.framemark.native.product.BatchJobError("FM-E-BATCH-001", it) },
            )
            when (val outcome = queue.submit(stamped)) {
                is SubmitOutcome.Added -> queue = outcome.queue
                is SubmitOutcome.Duplicate -> queue = queue.update(outcome.existingId) { stamped }
            }
        }
        return queue
    }

    private fun loadPersisted() {
        val platform = WorkflowEnv.platform ?: return
        val store = productStore
        if (store != null) {
            when (val result = store.loadBatch()) {
                is StoreResult.Ok -> {
                    // This legacy reader shares the phone queue document. It must
                    // use the same receipt verifier as PhonePhotoBatchQueue so a
                    // valid committed output is not downgraded before that queue
                    // performs its normal load.
                    val recovered = recoverBatchQueue(result.value, platform, platform.nowMillis())
                    if (recovered != result.value) store.saveBatch(recovered)
                    recovered.jobs.forEach { snapshot ->
                        val item = BatchItem(
                            id = nextId,
                            name = snapshot.displayName,
                            cardId = snapshot.design.cardId,
                            cardName = snapshot.displayName,
                            styleId = snapshot.design.styleId,
                            photoPath = snapshot.photoPath,
                            photoFingerprint = snapshot.photoFingerprint,
                            designKey = snapshot.designKey,
                            format = snapshot.exportSettings.format.toSampleFormat(),
                            quality = snapshot.exportSettings.quality,
                            longEdge = snapshot.exportSettings.longEdge ?: 1600,
                            filenamePattern = snapshot.exportSettings.filenamePattern,
                            webpLossless = snapshot.exportSettings.webpLossless,
                            job = snapshot,
                        )
                        nextId += 1
                        item.status = when (snapshot.status) {
                            BatchJobStatus.Running -> BatchItemStatus.Queued
                            BatchJobStatus.Done -> BatchItemStatus.Done
                            BatchJobStatus.Failed -> BatchItemStatus.Failed
                            BatchJobStatus.Cancelled -> BatchItemStatus.Cancelled
                            else -> BatchItemStatus.Queued
                        }
                        item.attempts = snapshot.attempts
                        item.outputPath = snapshot.outputPath
                        item.error = snapshot.error?.let { "${it.code} ${it.message}" }
                        item.progress = if (item.status == BatchItemStatus.Done) 1f else 0f
                        items.add(item)
                    }
                    runState = BatchRunState.Idle
                    FmLog.info("FM_BATCH_LOADED") {
                        "items=${items.size} done=$completedCount failed=$failedCount " +
                            "resumed=${result.value.jobs.count { it.status == BatchJobStatus.Running }} " +
                            "snapshots=${items.count { it.job != null }} queue=${queueFile ?: "-"}"
                    }
                    return
                }
                StoreResult.Missing -> Unit
                is StoreResult.Failed -> FmLog.info("FM_BATCH_DOC_LEGACY") { "code=${result.error.code}" }
            }
        }
        val path = queuePath() ?: return
        val text = platform.readTextFile(path) ?: return
        try {
            val root = Json.parse(text).jsonMap()
            val loaded = root["items"].jsonList()
            for (raw in loaded) {
                val m = raw.jsonMap()
                val id = m.jsonInt("id") ?: continue
                val cardId = m.jsonString("cardId") ?: ""
                val status = runCatching { BatchItemStatus.valueOf(m.jsonString("status") ?: "Queued") }
                    .getOrDefault(BatchItemStatus.Queued)
                val item = BatchItem(
                    id = id,
                    name = m.jsonString("name") ?: "未命名",
                    cardId = cardId,
                    cardName = m.jsonString("cardName") ?: "",
                    styleId = m.jsonString("styleId"),
                    photoPath = m.jsonString("photoPath"),
                    photoFingerprint = m.jsonString("photoFingerprint"),
                    designKey = m.jsonString("designKey") ?: "",
                    format = when (m.jsonString("format")) {
                        "PNG" -> SampleFormat.Png
                        "WebP" -> SampleFormat.WebP
                        else -> SampleFormat.Jpeg
                    },
                    quality = m.jsonInt("quality")?.coerceIn(60, 100) ?: 100,
                    longEdge = m.jsonInt("longEdge")?.coerceIn(400, 4096) ?: 1600,
                    filenamePattern = m.jsonString("filenamePattern") ?: "{name}-{date}",
                    webpLossless = m["webpLossless"] as? Boolean ?: true,
                )
                item.progress = (m["progress"] as? Number)?.toFloat()?.coerceIn(0f, 1f) ?: 0f
                item.status = if (status == BatchItemStatus.Running) BatchItemStatus.Queued else status
                item.outputPath = m.jsonString("outputPath")
                item.error = m.jsonString("error")
                item.attempts = m.jsonInt("attempts") ?: 0
                items.add(item)
                if (id >= nextId) nextId = id + 1
            }
            runState = BatchRunState.Idle
            FmLog.info("FM_BATCH_LOADED") { "items=${items.size} queue=$path" }
        } catch (t: Throwable) {
            FmLog.warn("FM_BATCH_LOAD_FAIL") { "${t.message} queue=$path" }
        }
    }

    private fun persist() {
        val platform = WorkflowEnv.platform ?: return
        if (useProductDocument()) {
            val store = productStore ?: return
            when (val result = store.saveBatch(toProductQueue())) {
                is StoreResult.Ok -> FmLog.info("FM_BATCH_PERSISTED") { "product_jobs=${items.count { it.job != null }}" }
                is StoreResult.Failed -> FmLog.warn("FM_BATCH_PERSIST_FAIL") { "code=${result.error.code} msg=${result.error.message}" }
                StoreResult.Missing -> Unit
            }
            return
        }
        val path = queuePath() ?: return
        val body = items.joinToString(",\n") { item ->
            "  {" +
                "\"id\":${item.id}," +
                "\"name\":${q(item.name)}," +
                "\"cardId\":${q(item.cardId)}," +
                "\"cardName\":${q(item.cardName)}," +
                "\"styleId\":${item.styleId?.let(::q) ?: "null"}," +
                "\"photoPath\":${item.photoPath?.let(::q) ?: "null"}," +
                "\"photoFingerprint\":${item.photoFingerprint?.let(::q) ?: "null"}," +
                "\"designKey\":${q(item.designKey)}," +
                "\"format\":${q(item.format.label)}," +
                "\"quality\":${item.quality}," +
                "\"longEdge\":${item.longEdge}," +
                "\"filenamePattern\":${q(item.filenamePattern)}," +
                "\"webpLossless\":${item.webpLossless}," +
                "\"status\":${q(item.status.name)}," +
                "\"progress\":${item.progress}," +
                "\"outputPath\":${item.outputPath?.let(::q) ?: "null"}," +
                "\"error\":${item.error?.let(::q) ?: "null"}," +
                "\"attempts\":${item.attempts}" +
                "}"
        }
        platform.writeTextFile(path, "{\"version\":1,\"items\":[\n$body\n]}\n")
    }

    private fun q(text: String): String {
        val sb = StringBuilder(text.length + 8)
        sb.append('"')
        text.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
