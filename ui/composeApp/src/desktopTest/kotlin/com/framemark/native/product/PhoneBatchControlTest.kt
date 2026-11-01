package com.framemark.native.product

import com.framemark.native.workflow.BatchCandidate
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        ): NativeImage {
            photoPaths += photoPath
            entered.complete(photoPath)
            release.await()
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

    private suspend fun <T> withHarness(harness: Harness, block: suspend () -> T): T {
        val oldPlatform = WorkflowEnv.platform
        val oldRender = WorkflowEnv.nativeRender
        WorkflowEnv.platform = harness.platform
        WorkflowEnv.nativeRender = harness.render
        return try {
            block()
        } finally {
            harness.render.release.complete(Unit)
            if (harness.queue.running) awaitStopped(harness.queue)
            WorkflowEnv.platform = oldPlatform
            WorkflowEnv.nativeRender = oldRender
        }
    }

    private suspend fun awaitRenderStart(render: GatedRender): String =
        withTimeout(5_000) { requireNotNull(render.entered.await()) }

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
    fun pause_finishes_current_photo_without_starting_the_next() = kotlinx.coroutines.runBlocking<Unit> {
        val harness = Harness()
        withHarness(harness) {
            harness.queue.submit(listOf(candidate("one"), candidate("two")))
            harness.queue.start()
            assertEquals("/photos/one.jpg", awaitRenderStart(harness.render))

            harness.queue.pause()
            harness.render.release.complete(Unit)
            awaitStopped(harness.queue)

            assertEquals(listOf("/photos/one.jpg"), harness.render.photoPaths.toList())
            val jobs = harness.queue.queue.jobs
            assertEquals(BatchJobStatus.Done, jobs[0].status)
            assertEquals(BatchJobStatus.Queued, jobs[1].status)
            assertTrue(jobs[0].outputPath?.endsWith("/one-1.jpg") == true)
        }
    }

    @Test
    fun cancel_pending_keeps_current_result_and_marks_waiting_jobs_cancelled() = kotlinx.coroutines.runBlocking<Unit> {
        val harness = Harness()
        withHarness(harness) {
            harness.queue.submit(listOf(candidate("one"), candidate("two")))
            harness.queue.start()
            assertEquals("/photos/one.jpg", awaitRenderStart(harness.render))

            harness.queue.cancelPending()
            assertEquals(BatchJobStatus.Running, harness.queue.queue.jobs[0].status)
            assertEquals(BatchJobStatus.Cancelled, harness.queue.queue.jobs[1].status)

            harness.render.release.complete(Unit)
            awaitStopped(harness.queue)

            assertEquals(listOf("/photos/one.jpg"), harness.render.photoPaths.toList())
            val jobs = harness.queue.queue.jobs
            assertEquals(BatchJobStatus.Done, jobs[0].status)
            assertEquals(BatchJobStatus.Cancelled, jobs[1].status)
            assertTrue(jobs[0].outputPath?.endsWith("/one-1.jpg") == true)
        }
    }
}
