package com.framemark.native.workflow

import com.framemark.native.product.BatchJob
import com.framemark.native.product.BatchJobStatus
import com.framemark.native.product.BatchQueue
import com.framemark.native.product.PhotoDesign as ProductDesign
import com.framemark.native.product.ExportSettings as ProductSettings
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Supervisor checks of file submission/recovery, separate from renderer/UI acceptance. */
class ExportCommitProtocolTest {
    private class Renderer(private val simultaneous: Boolean = false) : NativeRenderApi {
        val published = AtomicInteger()
        private val arrivals = AtomicInteger()
        private val ready = CompletableDeferred<Unit>()
        override fun supports(cardId: String) = true
        override suspend fun render(cardId: String, design: DesignSnapshot, mode: String,
            maxEdge: Int?, format: SampleFormat?, quality: Int?, longEdge: Int?, outputPath: String?,
            webpLossless: Boolean, overlay: OverlaySpec?, photoPath: String?): NativeImage {
            val target = File(requireNotNull(outputPath))
            target.parentFile.mkdirs()
            target.writeBytes(byteArrayOf(1, 2, 3, 4))
            if (simultaneous) {
                if (arrivals.incrementAndGet() == 2) ready.complete(Unit)
                ready.await()
            }
            return NativeImage(target.path, 1, 1, 1)
        }
        override suspend fun exportCommitted(path: String): String? {
            check(File(path).readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
            published.incrementAndGet()
            return "publication verified"
        }
    }

    private fun withPlatform(block: (DesktopWorkflowPlatform, File) -> Unit) {
        val directory = Files.createTempDirectory("fm-protocol-").toFile()
        val oldPlatform = WorkflowEnv.platform
        val oldRenderer = WorkflowEnv.nativeRender
        val platform = DesktopWorkflowPlatform(directory.resolve("data").path, directory.resolve("out").path)
        WorkflowEnv.platform = platform
        try { block(platform, directory) } finally {
            WorkflowEnv.platform = oldPlatform
            WorkflowEnv.nativeRender = oldRenderer
            directory.deleteRecursively()
        }
    }

    @Test fun failedReceiptPersistenceNeverCommitsOrPublishes() = withPlatform { platform, _ ->
        val renderer = Renderer()
        WorkflowEnv.nativeRender = renderer
        runBlocking {
            assertFailsWith<EngineFailure> {
                NativeEngine.exportCard("card", "边框", null,
                    ExportSettings(format = SampleFormat.Png), DesignSnapshot("card", null, emptyMap(), emptyMap()),
                    nameIndex = 2, photoDisplayName = "原照片.jpg", onOutputPrepared = { path, size, hash ->
                        assertTrue(!File(path).exists())
                        assertEquals(4L, size)
                        assertEquals(64, hash.length)
                        false
                    })
            }
        }
        assertTrue(File(platform.exportDir).listFiles().orEmpty().isEmpty())
        assertEquals(0, renderer.published.get())
    }

    @Test fun simultaneousSubmissionsKeepBothOutputsAndStablePhotoSequence() = withPlatform { platform, _ ->
        val renderer = Renderer(simultaneous = true)
        WorkflowEnv.nativeRender = renderer
        val results = runBlocking {
            val submissions = (1..2).map {
                async(Dispatchers.Default) {
                    NativeEngine.exportCard("card", "边框", null,
                        ExportSettings(format = SampleFormat.Png), DesignSnapshot("card", null, emptyMap(), emptyMap()),
                        nameIndex = 2, photoDisplayName = "中文 照片.jpg")
                }
            }
            submissions.map { it.await() }
        }
        assertNotEquals(results[0].path, results[1].path)
        assertTrue(results.all { it.fileName.startsWith("中文 照片-2") && File(it.path).length() == 4L })
        assertEquals(2, renderer.published.get())
        assertEquals(2, File(platform.exportDir).listFiles().orEmpty().size)
    }

    @Test fun recoveryRequiresMatchingReceiptBytesAndTaskInputs() = withPlatform { platform, directory ->
        val target = directory.resolve("out/finished.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val job = BatchJob.forPhoto("job", "source.jpg", "source-fingerprint", "原名.jpg",
            ProductDesign.base("card", null), ProductSettings(), nameIndex = 2, outputDirectory = platform.exportDir)
            .copy(status = BatchJobStatus.Running)
        val prepared = job.copy(preparedOutputPath = target.path, preparedOutputBytes = target.length(),
            preparedOutputSha256 = platform.fileFingerprint(target.path), preparedInputKey = job.outputReceiptKey)
        fun recovered(item: BatchJob) = recoverBatchQueue(BatchQueue(listOf(item)), platform).jobs.single()
        assertEquals(BatchJobStatus.Queued, recovered(job).status, "File existence alone cannot count as success")
        assertEquals(BatchJobStatus.Done, recovered(prepared).status)
        assertEquals(BatchJobStatus.Queued, recovered(prepared.copy(nameIndex = 3)).status)
        target.writeBytes(byteArrayOf(4, 3, 2, 1))
        assertEquals(BatchJobStatus.Queued, recovered(prepared).status, "Same-length corruption must not pass")
    }
}
