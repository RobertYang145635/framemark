package com.framemark.native.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.framemark.native.library.LibraryCard
import com.framemark.native.product.BatchJob
import com.framemark.native.product.BatchJobError
import com.framemark.native.product.BatchJobStatus
import com.framemark.native.product.BatchQueue
import com.framemark.native.product.ExportFormat as ProductExportFormat
import com.framemark.native.product.ExportSettings as ProductExportSettings
import com.framemark.native.product.ExportSizeMode as ProductExportSizeMode
import com.framemark.native.product.PhotoDesign
import com.framemark.native.product.ProductFileSystem
import com.framemark.native.product.Project
import com.framemark.native.product.SessionStore
import com.framemark.native.product.StoreResult
import com.framemark.native.product.SubmitOutcome
import com.framemark.native.product.WatermarkSpec as ProjectWatermark
import com.framemark.native.product.toDesignSnapshot
import com.framemark.native.product.toOverlaySpec
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One row of the photo batch, read off the persisted queue: the photo, its own
 * border design, its own watermark and its own export settings. Nothing here is
 * mutable UI state — a row's status is the job's status, so the sheet cannot drift
 * from the queue it displays.
 */
data class PhoneBatchRow(
    val jobId: String,
    val photoName: String,
    val photoPath: String?,
    val cardId: String,
    val cardName: String,
    val styleId: String?,
    val design: DesignSnapshot,
    val overlay: OverlaySpec?,
    val settings: ExportSettings,
    val status: String,
    val error: String?,
    val outputName: String?,
    val attempts: Int,
)

/** Inputs the queue needs for one photo; built by the shell from `product.Project`. */
data class BatchCandidate(
    val photoId: String,
    val photoPath: String?,
    val photoFingerprint: String?,
    val displayName: String,
    val design: PhotoDesign,
    val card: LibraryCard?,
    val settings: ProductExportSettings,
    val nameIndex: Int = 1,
    val outputDirectory: String? = null,
)

/**
 * An already-existing photo the 「按照片批量」 entry cannot export because it has no
 * border of its own (M01 rule 1). Its real list position is kept so a pre-flight can
 * name it without renumbering anything (M01 rule 3).
 */
data class UndesignedPhoto(val photoId: String, val displayName: String, val nameIndex: Int)

/**
 * The result of a pre-flight check before a batch-by-photo submit is enqueued
 * (M01 rules 1-3). Both the phone (`PhoneShell`) and the desktop (`DesktopProjectController`)
 * reach the same rule here, so the backend — not silent UI filtering — states which
 * photos have their own binding and which are 未应用边框.
 */
data class BatchPreflight(
    val designedPhotoIds: List<String>,
    val undesigned: List<UndesignedPhoto>,
) {
    val designedCount: Int get() = designedPhotoIds.size
    val undesignedCount: Int get() = undesigned.size

    /** Some but not all photos lack a border: needs an explicit 「仅导出已设计的 X 张」 choice. */
    val hasUndesigned: Boolean get() = undesigned.isNotEmpty()

    /** Nothing is exportable: create zero tasks and prompt to apply a border first. */
    val allUndesigned: Boolean get() = designedPhotoIds.isEmpty() && undesigned.isNotEmpty()

    /** The un-borded file names with their original list positions, for the prompt. */
    val undesignedSummary: String
        get() = undesigned.joinToString("、") { "「${it.displayName}」(第${it.nameIndex}张)" }
}

/** Why a pending 「仅导出已设计的 X 张」 confirmation may no longer be honoured. */
enum class BatchConfirmGate { Submit, SelectionChanged, DesignChanged }

/**
 * Re-validates a pending pre-flight confirmation against the state at click time. The
 * desktop sheet holds the confirmation in composable state, so clearing or changing the
 * selection — or an asynchronous project change landing behind it — can leave the panel
 * pointing at a set the user no longer chose. Callers must pass the click through here
 * instead of enqueueing the originally computed ids.
 */
fun batchConfirmGate(
    pendingIds: List<String>,
    currentSelection: Set<String>,
    pending: BatchPreflight,
    current: BatchPreflight,
): BatchConfirmGate = when {
    pendingIds.size != currentSelection.size || pendingIds.toSet() != currentSelection ->
        BatchConfirmGate.SelectionChanged
    current.designedPhotoIds != pending.designedPhotoIds || current.undesigned != pending.undesigned ->
        BatchConfirmGate.DesignChanged
    else -> BatchConfirmGate.Submit
}

/**
 * Splits [photoIds] into exportable (own binding) and 未应用边框 ones using **only**
 * `designs[photo.id]`. A shared / active / selected design is never lent to an existing
 * photo here — borrowing it is the P1 defect M01 rule 1 forbids. The original list
 * index is preserved so filtering cannot renumber a name (M01 rule 3).
 */
fun Project.batchPreflight(photoIds: List<String>): BatchPreflight {
    val designed = ArrayList<String>()
    val undesigned = ArrayList<UndesignedPhoto>()
    photoIds.forEach { id ->
        if (designs.containsKey(id)) {
            designed += id
        } else {
            val photo = photos.firstOrNull { it.id == id } ?: return@forEach
            undesigned += UndesignedPhoto(photo.id, photo.displayName, photos.indexOf(photo) + 1)
        }
    }
    return BatchPreflight(designed, undesigned)
}

/** Writes an embedded watermark out for the engine, once per digest. */
object PhoneOverlayFiles {
    fun pathFor(spec: ProjectWatermark): String? {
        val platform = WorkflowEnv.platform ?: return null
        val dir = platform.dataDir.trimEnd('/', '\\') + "/overlays"
        return "$dir/${spec.asset.sha256.lowercase()}.png"
    }

    fun materialize(spec: ProjectWatermark): String? {
        val platform = WorkflowEnv.platform ?: return null
        val path = pathFor(spec) ?: return null
        if (platform.exists(path) && platform.fileSize(path) == spec.asset.byteSize.toLong()) return path
        return if (platform.writeBytes(path, spec.asset.bytes)) path else null
    }
}

/**
 * The phone's photo batch, backed by `product.BatchQueue`.
 *
 * Each job snapshots everything its output depends on — the photo path, the full
 * design, the watermark bytes and the export settings — so resuming after a kill
 * exports **that photo with that design**, not whatever the editor happens to be
 * showing (or the bundled sample). Status changes are persisted as they happen, so
 * an interrupted run continues where it stopped instead of repeating or skipping.
 */
class PhonePhotoBatchQueue(
    fs: ProductFileSystem,
    private val cardLookup: (String) -> LibraryCard?,
    private val clock: () -> Long = { WorkflowEnv.platform?.nowMillis() ?: 0L },
) {
    private val store = SessionStore(fs)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queueLock = Any()

    var queue by mutableStateOf(BatchQueue())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    private var pauseRequested by mutableStateOf(false)
    var resumed by mutableStateOf(0)
        private set

    val pendingCount: Int get() = queue.jobs.count { it.status == BatchJobStatus.Queued }
    val runnableCount: Int get() = queue.jobs.count { it.status == BatchJobStatus.Queued && !it.requiresRebuild }
    val doneCount: Int get() = queue.doneCount
    val failedCount: Int get() = queue.failedCount

    /** Reads the persisted queue and turns an interrupted Running back into Queued. */
    fun load(): List<BatchJob> {
        when (val result = store.loadBatch()) {
            is StoreResult.Ok -> {
                val recovered = recoverBatchQueue(result.value, WorkflowEnv.platform, clock())
                resumed = result.value.jobs.count { it.status == BatchJobStatus.Running }
                queue = recovered
                val rebuildCount = recovered.jobs.count { it.requiresRebuild && it.status == BatchJobStatus.Queued }
                if (rebuildCount > 0) {
                    error = "$rebuildCount 条旧队列任务缺少稳定序号或输出目录；请移除后重新加入，不会猜测原设置。"
                }
                if (recovered != result.value) persist()
                println(
                    "FM_PHOTO_QUEUE_LOADED jobs=${recovered.jobs.size} done=${recovered.doneCount} " +
                        "failed=${recovered.failedCount} resumedRunning=$resumed",
                )
            }
            StoreResult.Missing -> println("FM_PHOTO_QUEUE_NONE")
            is StoreResult.Failed -> {
                error = "${result.error.code} ${result.error.message}"
                println("FM_PHOTO_QUEUE_LOAD_FAIL code=${result.error.code} msg=${result.error.message}")
            }
        }
        return queue.jobs
    }

    /**
     * Queues one job per candidate. A job whose photo + design + watermark + export
     * settings already exist is not added twice ([BatchQueue.submit] dedupes on the
     * idempotency key), so pressing 开始导出 twice cannot duplicate an output.
     */
    fun submit(candidates: List<BatchCandidate>): SubmitReport = synchronized(queueLock) {
        val now = clock()
        var next = queue
        val added = mutableListOf<String>()
        val duplicates = mutableListOf<String>()
        val rejected = mutableListOf<Pair<String, String>>()
        val platform = WorkflowEnv.platform
        candidates.forEach { candidate ->
            val path = candidate.photoPath
            if (path.isNullOrBlank()) {
                rejected += candidate.displayName to "没有照片路径，无法导出"
                return@forEach
            }
            val outputDirectory = candidate.outputDirectory?.takeIf { it.isNotBlank() }
                ?: platform?.exportDir?.takeIf { it.isNotBlank() }
            if (outputDirectory == null || candidate.nameIndex < 1) {
                rejected += candidate.displayName to "无法固定文件序号或输出目录，请稍后重试"
                return@forEach
            }
            val job = BatchJob.forPhoto(
                id = "job-${candidate.photoId}-${now}-${added.size}",
                photoPath = path,
                photoFingerprint = candidate.photoFingerprint,
                displayName = candidate.displayName,
                design = candidate.design,
                exportSettings = candidate.settings,
                now = now,
                nameIndex = candidate.nameIndex,
                outputDirectory = outputDirectory,
            )
            when (val outcome = next.submit(job)) {
                is SubmitOutcome.Added -> {
                    next = outcome.queue
                    added += outcome.jobId
                }
                is SubmitOutcome.Duplicate -> duplicates += outcome.existingId
            }
        }
        queue = next
        persist()
        val report = SubmitReport(added.size, duplicates.size, rejected.toList())
        println(
            "FM_PHOTO_QUEUE_SUBMIT added=${report.added} duplicate=${report.duplicates} " +
                "rejected=${report.rejected} total=${next.jobs.size}",
        )
        report
    }

    fun rows(): List<PhoneBatchRow> = queue.jobs.map { job -> rowOf(job) }

    private fun rowOf(job: BatchJob): PhoneBatchRow {
        val card = cardLookup(job.design.cardId)
        return PhoneBatchRow(
            jobId = job.id,
            photoName = job.displayName,
            photoPath = job.photoPath,
            cardId = job.design.cardId,
            cardName = card?.name ?: job.design.cardId,
            styleId = job.design.styleId ?: card?.systemStyle,
            design = job.design.toDesignSnapshot(),
            overlay = job.overlayMaterialized(),
            settings = job.exportSettings.toUiExport(),
            status = if (job.requiresRebuild) "需重建" else job.status.label(),
            error = if (job.requiresRebuild) {
                "旧任务缺少稳定序号或输出目录；请移除后重新加入。"
            } else {
                job.error?.let { "${it.code} ${it.message}" }
            },
            outputName = job.outputPath?.substringAfterLast('/')?.substringAfterLast('\\'),
            attempts = job.attempts,
        )
    }

    /** Exports every queued job with **its own** photo, design, watermark and settings. */
    fun start() {
        if (running) return
        if (queue.jobs.none { it.status == BatchJobStatus.Queued && !it.requiresRebuild }) {
            if (queue.jobs.any { it.status == BatchJobStatus.Queued && it.requiresRebuild }) {
                error = "旧队列任务缺少稳定序号或输出目录；请移除后重新加入。"
            }
            return
        }
        running = true
        pauseRequested = false
        error = null
        scope.launch {
            try {
                while (!pauseRequested) {
                    val job = queue.jobs.firstOrNull { it.status == BatchJobStatus.Queued && !it.requiresRebuild }
                    if (job == null) {
                        if (queue.jobs.any { it.status == BatchJobStatus.Queued && it.requiresRebuild }) {
                            error = "旧队列任务缺少稳定序号或输出目录；请移除后重新加入。"
                        }
                        break
                    }
                    runOne(job)
                }
            } finally {
                running = false
            }
        }
    }

    /** Native encoding finishes the current photo; no new photo is started. */
    fun pause() {
        pauseRequested = true
    }

    fun cancelPending() {
        pauseRequested = true
        updateQueue { BatchQueue(it.jobs.map { job ->
            if (job.status == BatchJobStatus.Queued) job.copy(status = BatchJobStatus.Cancelled) else job
        }) }
    }

    fun retry(jobId: String) {
        if (queue.job(jobId)?.requiresRebuild == true) {
            error = "旧队列任务缺少稳定序号或输出目录；请移除后重新加入。"
            return
        }
        val before = queue
        updateQueue { it.retry(jobId) }
        if (queue == before) return
        println("FM_PHOTO_QUEUE_RETRY id=$jobId")
        start()
    }

    fun clearSettled() {
        updateQueue { current -> BatchQueue(current.jobs.filterNot { it.status == BatchJobStatus.Done }) }
    }

    fun removeJob(jobId: String) {
        updateQueue { current -> BatchQueue(current.jobs.filterNot { it.id == jobId }) }
    }

    private suspend fun runOne(job: BatchJob) {
        if (!updateQueue { it.claim(job.id, clock()) }) {
            synchronized(queueLock) { queue = queue.recoverAfterRestart() }
            return
        }
        if (queue.job(job.id)?.status != BatchJobStatus.Running) return
        println(
            "FM_PHOTO_JOB_START id=${job.id} photo=${job.photoPath ?: "<missing>"} card=${job.design.cardId} " +
                "wm=${job.overlay?.asset?.sha256?.take(8) ?: "none"} attempt=${job.attempts + 1} " +
                "settings=${job.exportSettings.format.id}/${job.exportSettings.longEdge ?: "original"}",
        )
        val path = job.photoPath
        if (path.isNullOrBlank()) {
            finishFailed(job.id, "FM-E-INPUT-001", "队列里的这条任务没有照片路径，已跳过而不是改用示例照片")
            return
        }
        if (job.overlay != null && job.overlayMaterialized() == null) {
            finishFailed(job.id, "FM-E-IO-003", "水印素材无法写出（${job.overlay.asset.name}）")
            return
        }
        val card = cardLookup(job.design.cardId)
        try {
            val platform = WorkflowEnv.platform
                ?: throw EngineFailure("FM-E-IO-003", "本地存储不可用")
            val expectedFingerprint = job.photoFingerprint
            if (!expectedFingerprint.isNullOrBlank() && platform.fileFingerprint(path) != expectedFingerprint) {
                throw EngineFailure("FM-E-INPUT-001", "照片内容已变化，请重新加入任务：${job.displayName}")
            }
            val result = NativeEngine.exportCard(
                cardId = job.design.cardId,
                cardName = card?.name ?: job.design.cardId,
                styleId = job.design.styleId ?: card?.systemStyle,
                settings = job.exportSettings.toUiExport(),
                design = job.design.toDesignSnapshot(),
                overlay = job.overlayMaterialized(),
                photoPath = path,
                nameIndex = job.nameIndex ?: throw EngineFailure("FM-E-BATCH-001", "队列缺少稳定文件序号"),
                photoDisplayName = job.displayName,
                outputDirectory = job.outputDirectory,
                onOutputPrepared = { preparedPath, size, sha ->
                    persistPreparedOutput(job.id, preparedPath, size, sha, job.outputReceiptKey)
                },
            )
            // Crash window W3-12: commitExport succeeded (file on disk), Done not yet saved.
            NativeEngine.crashCheckpointBefore(platform, "beforeDone")
            updateQueue { it.complete(job.id, result.path, clock()) }
            println("FM_PHOTO_JOB_DONE id=${job.id} out=${result.path} bytes=${result.bytes}")
        } catch (c: kotlinx.coroutines.CancellationException) {
            synchronized(queueLock) {
                queue = recoverBatchQueue(queue, WorkflowEnv.platform, clock())
                persist()
            }
            throw c
        } catch (t: Throwable) {
            val code = (t as? EngineFailure)?.code ?: "FM-E-ENGINE-002"
            val message = t.message ?: "export failed"
            finishFailed(job.id, code, message.take(300))
        }
    }

    private fun finishFailed(jobId: String, code: String, message: String) {
        updateQueue { it.fail(jobId, BatchJobError(code, message), clock()) }
        println("FM_PHOTO_JOB_FAIL id=$jobId code=$code msg=$message")
    }

    private fun updateQueue(transform: (BatchQueue) -> BatchQueue): Boolean = synchronized(queueLock) {
        queue = transform(queue)
        persist()
    }

    private fun persistPreparedOutput(
        jobId: String,
        path: String,
        size: Long,
        sha256: String,
        expectedReceiptKey: String,
    ): Boolean = synchronized(queueLock) {
        val before = queue
        val current = before.job(jobId) ?: return@synchronized false
        if (current.status != BatchJobStatus.Running || current.outputReceiptKey != expectedReceiptKey) {
            return@synchronized false
        }
        val next = before.update(jobId) { running ->
            running.copy(
                preparedOutputPath = path,
                preparedOutputBytes = size,
                preparedOutputSha256 = sha256,
                preparedInputKey = running.outputReceiptKey,
            )
        }
        if (next == before) return@synchronized false
        queue = next
        if (persist()) true else {
            queue = before
            false
        }
    }

    private fun persist(): Boolean =
        when (val result = store.saveBatch(queue)) {
            is StoreResult.Ok -> true
            is StoreResult.Failed -> {
                pauseRequested = true
                error = "${result.error.code} ${result.error.message}"
                println("FM_PHOTO_QUEUE_SAVE_FAIL code=${result.error.code} msg=${result.error.message}")
                false
            }
            StoreResult.Missing -> false
        }
}

private fun BatchJobStatus.label(): String = when (this) {
    BatchJobStatus.Queued -> "待导出"
    BatchJobStatus.Running -> "导出中"
    BatchJobStatus.Done -> "完成"
    BatchJobStatus.Failed -> "失败"
    BatchJobStatus.Cancelled -> "已取消"
}

/** The job's own watermark, written out for the engine call. */
private fun BatchJob.overlayMaterialized(): OverlaySpec? {
    val spec = overlay ?: return null
    val file = PhoneOverlayFiles.materialize(spec) ?: return null
    val aspect = PreviewGeometry.aspectOf(design.cardId) ?: 1.5f
    return spec.toOverlaySpec(file, aspect)
}

/** Queue result for the UI: how many landed, how many were already there, what was refused. */
data class SubmitReport(val added: Int, val duplicates: Int, val rejected: List<Pair<String, String>>)

private fun ProductExportSettings.toUiExport(): ExportSettings = ExportSettings(
    format = when (format) {
        ProductExportFormat.Jpeg -> SampleFormat.Jpeg
        ProductExportFormat.Png -> SampleFormat.Png
        ProductExportFormat.WebP -> SampleFormat.WebP
    },
    quality = quality,
    sizeMode = when (sizeMode) {
        ProductExportSizeMode.LongEdge -> ExportSizeMode.LongEdge
        else -> ExportSizeMode.Original
    },
    longEdge = longEdge ?: 1600,
    filenamePattern = filenamePattern,
    webpLossless = webpLossless,
)

// ---------------------------------------------------------------------------
// Surface
// ---------------------------------------------------------------------------

/** 原尺寸 or a long edge, in the words the export sheet uses. */
private fun sizeLabel(settings: ExportSettings): String =
    if (settings.sizeMode == ExportSizeMode.Original) "原尺寸" else "长边 ${settings.longEdge}px"

/**
 * Batch export by photo: every row is one queued job with its own photo, border
 * design and watermark. The rows come from the persisted queue, so opening this
 * sheet after a restart shows the same jobs with the same statuses.
 */
@Composable
fun PhotoBatchScreen(
    queue: PhonePhotoBatchQueue,
    settings: ExportSettings,
    onClose: () -> Unit,
) {
    val rows = queue.rows()

    Column(modifier = Modifier.fillMaxSize().padding(FmSpacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("批量导出 · 按照片", style = FmType.titleMedium, color = FmPalette.onSurface)
                Text(
                    text = "每张照片用自己的边框、文字与水印；任务写进队列文件，中断后可继续。",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
            }
            Text(
                text = "关闭",
                style = FmType.labelMedium,
                color = FmPalette.onSurfaceSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.surfaceMuted)
                    .clickable(onClick = onClose)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(FmSpacing.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "完成 ${queue.doneCount} · 待导出 ${queue.pendingCount} · 失败 ${queue.failedCount}",
                style = FmType.labelMedium,
                color = FmPalette.onSurfaceSecondary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (queue.running) "导出中…" else "开始导出",
                style = FmType.labelMedium,
                color = if (queue.running) FmPalette.onSurfaceMuted else FmPalette.onPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(if (queue.running) FmPalette.surfaceMuted else FmPalette.primary)
                    .clickable(enabled = !queue.running && queue.runnableCount > 0) { queue.start() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        queue.error?.let { message ->
            Text(message, style = FmType.labelSmall, color = FmPalette.onSurfaceSecondary)
        }
        if (queue.resumed > 0) {
            Text(
                text = "上次中断的 ${queue.resumed} 个任务已回到待导出。",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
            )
        }
        Spacer(Modifier.height(FmSpacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("完成当前后暂停", { queue.pause() }, enabled = queue.running)
            GhostButton("取消待导出", { queue.cancelPending() }, enabled = queue.pendingCount > 0)
        }
        Spacer(Modifier.height(FmSpacing.sm))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.jobId }) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FmRadii.card))
                        .background(FmPalette.surface)
                        .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.card))
                        .padding(FmSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.photoName, style = FmType.bodyMedium, color = FmPalette.onSurface)
                        Text(
                            text = row.cardName + if (row.overlay != null) " · 有水印" else " · 无水印",
                            style = FmType.labelSmall,
                            color = FmPalette.onSurfaceMuted,
                        )
                        Text(
                            text = "${sizeLabel(row.settings)} · ${row.settings.format.label}",
                            style = FmType.labelSmall,
                            color = FmPalette.onSurfaceMuted,
                        )
                        row.error?.let {
                            Text(it, style = FmType.labelSmall, color = FmPalette.onSurfaceSecondary)
                        }
                    }
                    Spacer(Modifier.width(FmSpacing.sm))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = row.outputName ?: row.status,
                            style = FmType.labelSmall,
                            color = if (row.status == "失败") FmPalette.onSurfaceSecondary else FmPalette.onSurfaceMuted,
                            maxLines = 2,
                        )
                        if (row.status == "失败") {
                            Text(
                                text = "重试",
                                style = FmType.labelMedium,
                                color = FmPalette.onSurface,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(FmRadii.pill))
                                    .background(FmPalette.surfaceMuted)
                                    .clickable { queue.retry(row.jobId) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        } else if (row.status == "需重建") {
                            Text(
                                text = "移除",
                                style = FmType.labelMedium,
                                color = FmPalette.onSurfaceSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(FmRadii.pill))
                                    .background(FmPalette.surfaceMuted)
                                    .clickable { queue.removeJob(row.jobId) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(FmSpacing.sm))
        Text(
            text = "当前编辑的导出设置：${settings.format.label} · 长边 ${settings.longEdge}",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
        )
    }
}
