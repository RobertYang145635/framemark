package com.framemark.native.product

import com.framemark.native.workflow.BatchCandidate
import com.framemark.native.workflow.BatchItemStatus
import com.framemark.native.workflow.BatchRunner
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.NativeImage
import com.framemark.native.workflow.NativeRenderApi
import com.framemark.native.workflow.OverlaySpec
import com.framemark.native.workflow.PhonePhotoBatchQueue
import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.WorkflowEnv
import com.framemark.native.workflow.WorkflowPlatform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.security.MessageDigest

class PhoneBatchControlTest {
    private class ControlledFileSystem(override val dataDir: String) : ProductFileSystem {
        private val files = mutableMapOf<String, ByteArray>()
        val failWrites = AtomicBoolean(false)

        @Synchronized
        fun seed(path: String, bytes: ByteArray) {
            files[path] = bytes.copyOf()
        }

        @Synchronized
        override fun exists(path: String): Boolean = path in files

        @Synchronized
        override fun readText(path: String): String? = files[path]?.decodeToString()

        @Synchronized
        override fun readBytes(path: String): ByteArray? = files[path]?.copyOf()

        @Synchronized
        override fun sha256Of(path: String): String? = files[path]?.let { Sha256.hex(it) }

        @Synchronized
        override fun writeBytes(path: String, bytes: ByteArray): Boolean {
            if (failWrites.get()) return false
            files[path] = bytes.copyOf()
            return true
        }

        @Synchronized
        override fun fileSize(path: String): Long = files[path]?.size?.toLong() ?: 0L

        @Synchronized
        override fun rename(from: String, to: String): Boolean {
            val bytes = files.remove(from) ?: return false
            files[to] = bytes
            return true
        }

        @Synchronized
        override fun delete(path: String): Boolean = files.remove(path) != null
    }

    private class GatedRender : NativeRenderApi {
        lateinit var platform: TestPlatform
        val photoPaths = CopyOnWriteArrayList<String?>()
        val entered = CompletableDeferred<String?>()
        val release = CompletableDeferred<Unit>()
        private val inFlight = AtomicInteger(0)

        /** Most photos this renderer was working on at the same time. */
        val maxInFlight = AtomicInteger(0)

        override fun supports(cardId: String): Boolean = true
        override fun supportsExport(cardId: String): Boolean = true

        override suspend fun render(
            cardId: String,
            design: DesignSnapshot,
            mode: String,
            maxEdge: Int?,
            format: SampleFormat?,
            quality: Int?,
            longEdge: Int?,
            outputPath: String?,
            webpLossless: Boolean,
            overlay: OverlaySpec?,
            photoPath: String?,
            interactive: Boolean,
        ): NativeImage {
            photoPaths += photoPath
            entered.complete(photoPath)
            val concurrent = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, concurrent) }
            try {
                release.await()
            } finally {
                inFlight.decrementAndGet()
            }
            platform.writeBytes(requireNotNull(outputPath), "rendered:${photoPath.orEmpty()}".encodeToByteArray())
            return NativeImage(
                path = requireNotNull(outputPath),
                width = 1200,
                height = 800,
                ms = 1,
            )
        }
    }

    private class TestPlatform(override val dataDir: String) : WorkflowPlatform {
        override val exportDir: String = "$dataDir/exports"
        private val files = mutableMapOf<String, ByteArray>()

        @Synchronized
        fun seed(path: String, bytes: ByteArray) {
            files[path] = bytes.copyOf()
        }

        override fun readTextFile(path: String): String? = null
        override fun writeTextFile(path: String, text: String) = Unit
        @Synchronized
        override fun writeBytes(path: String, bytes: ByteArray): Boolean {
            files[path] = bytes.copyOf()
            return true
        }

        @Synchronized
        override fun exists(path: String): Boolean = path in files

        @Synchronized
        override fun fileSize(path: String): Long = files[path]?.size?.toLong() ?: 0L

        @Synchronized
        override fun readBytes(path: String): ByteArray? = files[path]?.copyOf()

        override fun fileFingerprint(path: String): String? {
            // The harness uses stable source identities and real content hashes for
            // generated export files, matching the two production call sites.
            if (path.startsWith("/photos/")) {
                return "fingerprint-${path.substringAfterLast('/').substringBeforeLast('.')}"
            }
            val bytes = readBytes(path) ?: return null
            return MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        @Synchronized
        override fun commitExport(temporaryPath: String, finalPath: String): Boolean {
            if (finalPath in files) return false
            val bytes = files[temporaryPath] ?: return false
            files[finalPath] = bytes.copyOf()
            files.remove(temporaryPath)
            return true
        }

        @Synchronized
        override fun deleteExportTemporary(path: String) {
            files.remove(path)
        }

        override fun encodeSampleImage(width: Int, height: Int, format: SampleFormat, quality: Int): ByteArray? = null
        override fun dateStamp(): String = "20261101"
        override fun timeStamp(): String = "120000"
        override fun formatDateTime(epochMillis: Long): String = ""
        override fun nowMillis(): Long = 1_800_000_000_000L
    }

    private class Harness {
        val fs = ControlledFileSystem("/phone-batch-test")
        val render = GatedRender()
        val queue = PhonePhotoBatchQueue(fs, cardLookup = { null }, clock = { 1_800_000_000_000L })
        val platform = TestPlatform(fs.dataDir)

        init {
            render.platform = platform
        }
    }

    private fun candidate(id: String) = BatchCandidate(
        photoId = id,
        photoPath = "/photos/$id.jpg",
        photoFingerprint = "fingerprint-$id",
        displayName = "$id.jpg",
        design = PhotoDesign.base("card-$id", "style-$id"),
        card = null,
        settings = ExportSettings(),
    )

    private fun runningWithReceipt(harness: Harness, bytes: ByteArray): Pair<BatchJob, String> {
        val outputPath = "${harness.platform.exportDir}/中文 测试 照片-1-1.jpg"
        val job = BatchJob.forPhoto(
            id = "recovery-job",
            photoPath = "/photos/recovery.jpg",
            photoFingerprint = "fingerprint-recovery",
            displayName = "中文 测试 照片.jpg",
            design = PhotoDesign.base("card-recovery", "style-recovery"),
            exportSettings = ExportSettings(),
            now = 1_800_000_000_000L,
            nameIndex = 1,
            outputDirectory = harness.platform.exportDir,
        ).copy(status = BatchJobStatus.Running, attempts = 1, startedAt = 1_800_000_000_000L)
        val prepared = job.copy(
            preparedOutputPath = outputPath,
            preparedOutputBytes = bytes.size.toLong(),
            preparedOutputSha256 = sha256(bytes),
            preparedInputKey = job.outputReceiptKey,
        )
        harness.fs.seed(
            "${harness.fs.dataDir}/batch-queue.json",
            BatchCodec.encode(BatchQueue(listOf(prepared))).encodeToByteArray(),
        )
        harness.platform.seed(outputPath, bytes)
        return prepared to outputPath
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private suspend fun <T> withHarness(harness: Harness, block: suspend () -> T): T {
        val oldPlatform = WorkflowEnv.platform
        val oldRender = WorkflowEnv.nativeRender
        WorkflowEnv.platform = harness.platform
        WorkflowEnv.nativeRender = harness.render
        com.framemark.native.workflow.PreviewSuspension.resetForVerify()
        return try {
            block()
        } finally {
            harness.render.release.complete(Unit)
            if (harness.queue.running) awaitStopped(harness.queue)
            // The queue holds the process-wide preview gate while it runs; a leaked hold
            // would freeze every later preview in this JVM.
            com.framemark.native.workflow.PreviewSuspension.resetForVerify()
            WorkflowEnv.platform = oldPlatform
            WorkflowEnv.nativeRender = oldRender
        }
    }

    private suspend fun awaitRenderStart(render: GatedRender): String =
        withTimeout(5_000) { requireNotNull(render.entered.await()) }

    /** Waits until the worker pool is fully busy, i.e. [workers] photos are in flight. */
    private suspend fun awaitSaturated(render: GatedRender, workers: Int) {
        withTimeout(5_000) {
            while (render.maxInFlight.get() < workers) yield()
        }
    }

    /**
     * The pool size the queue picks for these candidates. `candidate()` uses the default
     * `ExportSettings()` (原尺寸), whose frame size is unknown, so the memory cap applies
     * and the run is capped at the two-worker floor.
     */
    private fun batchWorkersForQueue(): Int =
        com.framemark.native.workflow.batchWorkerCountForFrame(
            largestFramePixels = null,
            cores = com.framemark.native.workflow.platformProcessorCount(),
        )

    private suspend fun awaitStopped(queue: PhonePhotoBatchQueue) {
        withTimeout(5_000) {
            while (queue.running) yield()
        }
    }

    @Test
    fun failed_queue_claim_persistence_prevents_native_export() = kotlinx.coroutines.runBlocking<Unit> {
        val harness = Harness()
        withHarness(harness) {
            val report = harness.queue.submit(listOf(candidate("one")))
            assertEquals(1, report.added)

            // Submit succeeds; fail the write that persists Running before render.
            harness.fs.failWrites.set(true)
            harness.queue.start()
            awaitStopped(harness.queue)

            assertTrue(harness.render.photoPaths.isEmpty(), "native export must wait for a durable claim")
            assertEquals(BatchJobStatus.Queued, harness.queue.queue.jobs.single().status)
            assertNotNull(harness.queue.error)
        }
    }

    @Test
    fun old_queue_without_name_snapshot_is_not_started_or_guessed() = kotlinx.coroutines.runBlocking<Unit> {
        val harness = Harness()
        withHarness(harness) {
            val oldJob = BatchJob.forPhoto(
                id = "legacy",
                photoPath = "/photos/one.jpg",
                photoFingerprint = "fingerprint-one",
                displayName = "one.jpg",
                design = PhotoDesign.base("card-one"),
                exportSettings = ExportSettings(),
                now = 1,
            )
            val legacyJson = BatchCodec.encode(BatchQueue(listOf(oldJob)))
                .replace(",\"nameIndex\":null", "")
                .replace(",\"outputDirectory\":null", "")
            assertFalse(legacyJson.contains("\"nameIndex\""))
            assertFalse(legacyJson.contains("\"outputDirectory\""))
            harness.fs.seed("${harness.fs.dataDir}/batch-queue.json", legacyJson.encodeToByteArray())

            harness.queue.load()
            assertTrue(harness.queue.queue.jobs.single().requiresRebuild)
            harness.queue.start()

            assertFalse(harness.queue.running)
            assertTrue(harness.render.photoPaths.isEmpty(), "旧任务不得以猜测的文件序号或目录启动")
            assertTrue(harness.queue.error?.contains("缺少稳定序号或输出目录") == true)
        }
    }

    @Test
    fun validReceiptSurvivesLegacySnapshotReaderBeforePhoneQueueLoad() = kotlinx.coroutines.runBlocking<Unit> {
        val harness = Harness()
        val committed = "committed output bytes".encodeToByteArray()
        val (prepared, outputPath) = runningWithReceipt(harness, committed)
        withHarness(harness) {
            // This is the historical initialization order: the optional legacy
            // snapshot reader is constructed before the real per-photo queue loads.
            val legacyReader = BatchRunner(harness.fs)
            assertEquals(BatchItemStatus.Done, legacyReader.items.single().status)

            val restored = harness.queue.load().single()
            assertEquals(BatchJobStatus.Done, restored.status)
            assertEquals(1, restored.attempts)
            assertEquals(outputPath, restored.outputPath)
            assertEquals(prepared.preparedOutputSha256, harness.platform.fileFingerprint(outputPath))
            val persisted = assertNotNull(
                (SessionStore(harness.fs).loadBatch() as? StoreResult.Ok)?.value?.job(prepared.id),
            )
            assertEquals(BatchJobStatus.Done, persisted.status)
            assertEquals(prepared.outputReceiptKey, persisted.preparedInputKey)
            assertEquals(outputPath, persisted.preparedOutputPath)
            assertEquals(prepared.preparedOutputBytes, persisted.preparedOutputBytes)
            assertEquals(prepared.preparedOutputSha256, persisted.preparedOutputSha256)
            assertTrue(harness.render.photoPaths.isEmpty(), "a matching receipt must reconcile without rendering")
        }
    }

    @Test
    fun invalidReceiptIsQueuedBeforeRetryAndDoesNotOverwriteExistingBytes() = kotlinx.coroutines.runBlocking<Unit> {
        val harness = Harness()
        val original = "prepared bytes".encodeToByteArray()
        val decoy = "decoy bytes!!!".encodeToByteArray()
        assertEquals(original.size, decoy.size)
        val (prepared, outputPath) = runningWithReceipt(harness, original)
        harness.platform.seed(outputPath, decoy)
        withHarness(harness) {
            val legacyReader = BatchRunner(harness.fs)
            assertEquals(BatchItemStatus.Queued, legacyReader.items.single().status)

            val restored = harness.queue.load().single()
            assertEquals(BatchJobStatus.Queued, restored.status)
            assertNull(restored.preparedOutputPath)
            assertEquals(1, restored.attempts)
            assertEquals(decoy.toList(), harness.platform.readBytes(outputPath)?.toList())

            harness.queue.start()
            assertEquals("/photos/recovery.jpg", awaitRenderStart(harness.render))
            harness.render.release.complete(Unit)
            awaitStopped(harness.queue)

            val completed = harness.queue.queue.jobs.single()
            assertEquals(BatchJobStatus.Done, completed.status)
            assertEquals(2, completed.attempts)
            assertEquals(listOf("/photos/recovery.jpg"), harness.render.photoPaths.toList())
            assertEquals(decoy.toList(), harness.platform.readBytes(outputPath)?.toList())
        }
    }

    /**
     * 暂停 = 「做完已经在飞的照片，不再开新的」。并行导出后「当前」是一**组**而不是一张，
     * 所以这条用例先让 worker 池吃满再暂停：在飞的都完成，排队的一张不动。
     * 语义（不多跑、不跳号、暂停不再开新任务）与串行时代一致，只有「在飞几张」变了。
     */
    @Test
    fun pause_finishes_the_in_flight_photos_without_starting_the_next() = kotlinx.coroutines.runBlocking<Unit> {
        val harness = Harness()
        withHarness(harness) {
            val workers = batchWorkersForQueue()
            val total = workers + 3
            harness.queue.submit((1..total).map { candidate("p$it") })
            harness.queue.start()
            awaitSaturated(harness.render, workers)

            harness.queue.pause()
            harness.render.release.complete(Unit)
            awaitStopped(harness.queue)

            assertEquals(workers, harness.render.photoPaths.size, "暂停后不得再开新的照片")
            val jobs = harness.queue.queue.jobs
            jobs.take(workers).forEach { job ->
                assertEquals(BatchJobStatus.Done, job.status, "${job.displayName} was in flight and must finish")
                assertTrue(job.outputPath != null, "${job.displayName} in flight must keep its result")
            }
            jobs.drop(workers).forEach { job ->
                assertEquals(BatchJobStatus.Queued, job.status, "${job.displayName} must stay queued")
            }
        }
    }

    @Test
    fun cancel_pending_keeps_the_in_flight_results_and_marks_waiting_jobs_cancelled() = kotlinx.coroutines.runBlocking<Unit> {
        val harness = Harness()
        withHarness(harness) {
            val workers = batchWorkersForQueue()
            val total = workers + 3
            harness.queue.submit((1..total).map { candidate("p$it") })
            harness.queue.start()
            awaitSaturated(harness.render, workers)

            harness.queue.cancelPending()
            harness.queue.queue.jobs.take(workers).forEach {
                assertEquals(BatchJobStatus.Running, it.status, "在飞的照片继续导出，不被取消")
            }
            harness.queue.queue.jobs.drop(workers).forEach {
                assertEquals(BatchJobStatus.Cancelled, it.status, "排队的一张都不再开始")
            }

            harness.render.release.complete(Unit)
            awaitStopped(harness.queue)

            assertEquals(workers, harness.render.photoPaths.size, "取消后不得再开新的照片")
            val jobs = harness.queue.queue.jobs
            jobs.take(workers).forEach { job ->
                assertEquals(BatchJobStatus.Done, job.status)
                assertTrue(job.outputPath != null)
            }
            jobs.drop(workers).forEach { job ->
                assertEquals(BatchJobStatus.Cancelled, job.status)
            }
        }
    }
}
