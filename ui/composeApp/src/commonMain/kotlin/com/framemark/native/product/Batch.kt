package com.framemark.native.product

const val BATCH_SCHEMA_VERSION = 1

enum class BatchJobStatus { Queued, Running, Done, Failed, Cancelled }

data class BatchJobError(val code: String, val message: String)

/**
 * One queued export. The job owns a **complete snapshot**: the design (params and
 * text), the watermark (embedded bytes), export settings and the photo identity,
 * so restarting the app can resume exactly what the user queued instead of
 * re-reading mutable editor state.
 *
 * [design] never carries an overlay: the overlay lives in [overlay] because batch
 * items allow per-photo watermark overrides and the engine call takes them apart.
 */
data class BatchJob(
    val id: String,
    val photoPath: String?,
    val photoFingerprint: String?,
    val displayName: String,
    val design: PhotoDesign,
    val overlay: WatermarkSpec?,
    val exportSettings: ExportSettings = ExportSettings(),
    val status: BatchJobStatus = BatchJobStatus.Queued,
    val attempts: Int = 0,
    val outputPath: String? = null,
    /** Stable 1-based project position captured when the task was enqueued. */
    val nameIndex: Int? = null,
    /** Machine-local destination captured when the task was enqueued. */
    val outputDirectory: String? = null,
    /** Durable pre-commit receipt, written before the final path is created. */
    val preparedOutputPath: String? = null,
    val preparedOutputBytes: Long? = null,
    val preparedOutputSha256: String? = null,
    val preparedInputKey: String? = null,
    val error: BatchJobError? = null,
    val createdAt: Long = 0,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
) {
    /** Border design only; the watermark is pulled up into [overlay] and folded into [idempotencyKey]. */
    val designKey: String get() = BatchKeys.designKey(design)

    val exportKey: String get() = BatchKeys.exportKey(exportSettings)

    /**
     * Same photo + same render inputs ⇒ the same output job. Covers the photo
     * identity, every design field, the job-level watermark and the export
     * settings, so changing any of them queues a new job instead of colliding
     * with an earlier one.
     */
    val idempotencyKey: String
        get() = BatchKeys.idempotencyKey(photoFingerprint ?: photoPath, design, overlay, exportSettings)

    /** Render identity plus every snapshotted value that can change the output path. */
    val outputReceiptKey: String
        get() = BatchKeys.outputReceiptKey(id, idempotencyKey, displayName, nameIndex, outputDirectory)

    /** Older queues lack enough information to resume without guessing. */
    val requiresRebuild: Boolean
        get() = nameIndex == null || nameIndex < 1 || outputDirectory.isNullOrBlank()

    companion object {
        fun forPhoto(
            id: String,
            photoPath: String?,
            photoFingerprint: String?,
            displayName: String,
            design: PhotoDesign,
            exportSettings: ExportSettings,
            now: Long = 0,
            nameIndex: Int? = null,
            outputDirectory: String? = null,
        ): BatchJob = BatchJob(
            id = id,
            photoPath = photoPath,
            photoFingerprint = photoFingerprint,
            displayName = displayName,
            design = design.copy(overlay = null),
            overlay = design.overlay,
            exportSettings = exportSettings,
            createdAt = now,
            nameIndex = nameIndex,
            outputDirectory = outputDirectory,
        )
    }
}

internal object BatchKeys {
    /**
     * Bumped whenever the covered inputs or the encoding change. Keys never
     * outlive a scheme change, so a stale queue cannot silently swallow a job
     * whose design gained a field that the old key did not look at.
     */
    const val SCHEME = "batch-key/3"

    /** Identity of everything a [PhotoDesign] renders, watermark included. */
    fun designKey(design: PhotoDesign): String = digest(
        "batch-design/2",
        listOf(
            "cardId" to design.cardId,
            "styleId" to design.styleId,
            "theme" to design.theme,
            "ratio" to design.ratio,
            "fontScale" to design.fontScale?.let { ProductJson.number(it) },
            "logo" to design.logo,
            "params" to ProductJson.encodeValues(design.params),
            "text" to ProductJson.encodeValues(design.text),
            "overlay" to design.overlay?.let(::overlayKey),
        ),
    )

    /**
     * The watermark's own identity. The embedded bytes are the source of truth
     * (there is deliberately no file path); the declared pixel size is kept
     * because it drives the drawn aspect ratio.
     */
    fun overlayKey(spec: WatermarkSpec): String = digest(
        "batch-overlay/3",
        listOf(
            "asset.sha256" to spec.asset.sha256.lowercase(),
            "asset.name" to spec.asset.name,
            "asset.mimeType" to spec.asset.mimeType,
            "asset.widthPx" to spec.asset.widthPx.toString(),
            "asset.heightPx" to spec.asset.heightPx.toString(),
            "anchor" to spec.anchor.toString(),
            "width" to ProductJson.number(spec.width),
            "opacity" to ProductJson.number(spec.opacity),
            "dx" to ProductJson.number(spec.dx),
            "dy" to ProductJson.number(spec.dy),
            "rotation" to ProductJson.number(spec.rotation),
            "photoAspect" to ProductJson.number(spec.photoAspect),
        ),
    )

    /** Its input set is already complete, so this stays byte-identical to the v1 key. */
    fun exportKey(settings: ExportSettings): String = Sha256.hex(
        buildString {
            append(settings.format.id)
            append('|').append(settings.quality)
            append('|').append(settings.sizeMode.id)
            append('|').append(settings.longEdge ?: "")
            append('|').append(settings.filenamePattern)
            append('|').append(settings.webpLossless)
        }.encodeToByteArray(),
    )

    fun idempotencyKey(
        photo: String?,
        design: PhotoDesign,
        overlay: WatermarkSpec?,
        export: ExportSettings,
    ): String = digest(
        SCHEME,
        listOf(
            "photo" to photo,
            "design" to designKey(design),
            "overlay" to overlay?.let(::overlayKey),
            "export" to exportKey(export),
        ),
    )

    fun outputReceiptKey(
        jobId: String,
        idempotencyKey: String,
        displayName: String,
        nameIndex: Int?,
        outputDirectory: String?,
    ): String = digest(
        "batch-output-receipt/1",
        listOf(
            "jobId" to jobId,
            "idempotencyKey" to idempotencyKey,
            "displayName" to displayName,
            "nameIndex" to nameIndex?.toString(),
            "outputDirectory" to outputDirectory,
        ),
    )

    /**
     * Length-prefixed fields, so no value can impersonate a field boundary and
     * `null` can never collide with the empty string.
     */
    private fun digest(scheme: String, fields: List<Pair<String, String?>>): String = Sha256.hex(
        buildString {
            append(scheme)
            fields.forEach { (name, value) ->
                append('\n').append(name).append('=')
                if (value == null) append('~') else append(value.length).append(':').append(value)
            }
        }.encodeToByteArray(),
    )
}

/**
 * The persisted queue. Every transition returns a new queue and only accepts a
 * legal source state, so a resumed run cannot re-complete a Done item, revive a
 * Cancelled one or export the same photo + design twice.
 */
data class BatchQueue(val jobs: List<BatchJob> = emptyList()) {
    fun job(id: String): BatchJob? = jobs.firstOrNull { it.id == id }

    val nextQueued: BatchJob? get() = jobs.firstOrNull { it.status == BatchJobStatus.Queued }

    val doneCount: Int get() = jobs.count { it.status == BatchJobStatus.Done }
    val failedCount: Int get() = jobs.count { it.status == BatchJobStatus.Failed }
    val cancelledCount: Int get() = jobs.count { it.status == BatchJobStatus.Cancelled }

    val isSettled: Boolean
        get() = jobs.isNotEmpty() && jobs.all {
            it.status == BatchJobStatus.Done ||
                it.status == BatchJobStatus.Failed ||
                it.status == BatchJobStatus.Cancelled
        }

    fun submit(job: BatchJob): SubmitOutcome {
        val duplicate = jobs.firstOrNull {
            it.id == job.id ||
                (it.idempotencyKey == job.idempotencyKey &&
                    it.outputDirectory == job.outputDirectory &&
                    it.status != BatchJobStatus.Cancelled &&
                    !it.requiresRebuild)
        }
        if (duplicate != null) return SubmitOutcome.Duplicate(this, duplicate.id)
        return SubmitOutcome.Added(copy(jobs = jobs + job), job.id)
    }

    fun update(id: String, transform: (BatchJob) -> BatchJob): BatchQueue {
        val index = jobs.indexOfFirst { it.id == id }
        if (index < 0) return this
        val current = jobs[index]
        val next = transform(current)
        if (next == current) return this
        val copy = jobs.toMutableList()
        copy[index] = next
        return BatchQueue(copy)
    }

    fun claim(id: String, now: Long = 0): BatchQueue = update(id) { job ->
        if (job.status != BatchJobStatus.Queued) {
            job
        } else {
            job.copy(
                status = BatchJobStatus.Running,
                attempts = job.attempts + 1,
                startedAt = now,
                finishedAt = null,
                outputPath = null,
                preparedOutputPath = null,
                preparedOutputBytes = null,
                preparedOutputSha256 = null,
                preparedInputKey = null,
                error = null,
            )
        }
    }

    /** Completing a Done job again is a no-op, so a resumed runner cannot double-count. */
    fun complete(id: String, outputPath: String, now: Long = 0): BatchQueue = update(id) { job ->
        if (job.status != BatchJobStatus.Running) {
            job
        } else {
            job.copy(status = BatchJobStatus.Done, outputPath = outputPath, finishedAt = now, error = null)
        }
    }

    fun fail(id: String, error: BatchJobError, now: Long = 0): BatchQueue = update(id) { job ->
        if (job.status != BatchJobStatus.Running) {
            job
        } else {
            job.copy(status = BatchJobStatus.Failed, error = error, finishedAt = now)
        }
    }

    fun retry(id: String, now: Long = 0): BatchQueue = update(id) { job ->
        if (job.status != BatchJobStatus.Failed && job.status != BatchJobStatus.Cancelled) {
            job
        } else {
            job.copy(
                status = BatchJobStatus.Queued,
                error = null,
                outputPath = null,
                preparedOutputPath = null,
                preparedOutputBytes = null,
                preparedOutputSha256 = null,
                preparedInputKey = null,
                startedAt = null,
                finishedAt = null,
            )
        }
    }

    fun cancel(id: String, now: Long = 0): BatchQueue = update(id) { job ->
        if (job.status != BatchJobStatus.Queued && job.status != BatchJobStatus.Running) {
            job
        } else {
            job.copy(status = BatchJobStatus.Cancelled, finishedAt = now)
        }
    }

    /**
     * Restart recovery (ARCH §4.4): a Running item was interrupted, so it goes back
     * to Queued. Done stays Done, Failed stays retryable, Cancelled never revives.
     */
    fun recoverAfterRestart(
        now: Long = 0,
        isPreparedOutputValid: (BatchJob) -> Boolean = { false },
    ): BatchQueue {
        if (jobs.none { it.status == BatchJobStatus.Running }) return this
        return BatchQueue(
            jobs.map { job ->
                if (job.status == BatchJobStatus.Running) {
                    val receiptMatchesInputs = job.preparedOutputPath != null &&
                        job.preparedOutputBytes != null &&
                        job.preparedOutputSha256 != null &&
                        job.preparedInputKey == job.outputReceiptKey &&
                        isPreparedOutputValid(job)
                    if (receiptMatchesInputs) {
                        job.copy(
                            status = BatchJobStatus.Done,
                            outputPath = job.preparedOutputPath,
                            finishedAt = now,
                            error = null,
                        )
                    } else {
                        job.copy(
                            status = BatchJobStatus.Queued,
                            startedAt = null,
                            outputPath = null,
                            preparedOutputPath = null,
                            preparedOutputBytes = null,
                            preparedOutputSha256 = null,
                            preparedInputKey = null,
                        )
                    }
                } else {
                    job
                }
            },
        )
    }
}

sealed interface SubmitOutcome {
    data class Added(val queue: BatchQueue, val jobId: String) : SubmitOutcome

    data class Duplicate(val queue: BatchQueue, val existingId: String) : SubmitOutcome
}
