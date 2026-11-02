package com.framemark.native.workflow

import com.framemark.native.product.DesktopTestImageFixtures
import com.framemark.native.product.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M01 batch contract, desktop side: the backend (`DesktopProjectController`) itself
 * reports which photos are 未应用边框 rather than leaving the UI to silently drop them,
 * only designed photos reach the queue, the original list index survives filtering,
 * cancelling enqueues nothing, and an all-undesigned selection creates zero tasks.
 */
class DesktopBatchPreflightTest {
    private fun asset(name: String): String = DesktopTestImageFixtures.path(name)

    private class TempPlatform(override val dataDir: String) : WorkflowPlatform {
        override val exportDir: String = "$dataDir/out"
        override fun readTextFile(path: String): String? = File(path).takeIf { it.isFile }?.readText()
        override fun writeTextFile(path: String, text: String) { File(path).apply { parentFile?.mkdirs() }.writeText(text) }
        override fun writeBytes(path: String, bytes: ByteArray): Boolean {
            File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
            return true
        }
        override fun exists(path: String): Boolean = File(path).exists()
        override fun fileSize(path: String): Long = File(path).length()
        override fun readBytes(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()
        override fun encodeSampleImage(width: Int, height: Int, format: SampleFormat, quality: Int): ByteArray? = null
        override fun dateStamp(): String = "20261102"
        override fun timeStamp(): String = "120000"
        override fun formatDateTime(epochMillis: Long): String = ""
        override fun nowMillis(): Long = 1_700_000_000_000
    }

    private fun controller(dir: Path): DesktopProjectController = DesktopProjectController(
        dataDir = dir.toString(),
        store = SessionStore(JvmProductFileSystem(dir.toString())),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        clock = { 1_700_000_000_000 },
    )

    private suspend fun <T> withPlatform(dir: Path, block: suspend () -> T): T {
        val old = WorkflowEnv.platform
        WorkflowEnv.platform = TempPlatform(dir.toString())
        return try {
            block()
        } finally {
            WorkflowEnv.platform = old
        }
    }

    /** A: border applied (has its own binding); B: explicitly left without one; selectedCardId is set by A's apply. */
    private suspend fun designedAUndesignedB(dir: Path): Pair<DesktopProjectController, List<String>> {
        val controller = controller(dir)
        controller.load()
        controller.importPhotos(listOf(asset("vertical.jpg"), asset("p1170883.jpg")))
        val ids = controller.photos.map { it.id }
        controller.selectPhoto(ids[0])
        controller.applyCard("simple-1", "simple-1")
        return controller to ids
    }

    @Test
    fun preflight_reports_unbordered_photos_and_enqueue_keeps_only_designed_with_original_index() = runBlocking {
        val dir = Files.createTempDirectory("fm-w2-batch-preflight-")
        try {
            withPlatform(dir) {
                val (controller, ids) = designedAUndesignedB(dir)
                val (a, b) = ids
                assertNull(controller.project.designOf(b), "照片 b 没有自己的设计绑定")
                // The borrowed-source state M01 forbids is actually present on the project.
                assertEquals("simple-1", controller.project.selectedCardId)

                // Pre-flight itself must not enqueue anything.
                val preflight = controller.preflightBatch(listOf(a, b))
                assertEquals(0, controller.batchJobs.size, "预检阶段不得向队列提交任何任务")

                assertEquals(listOf(a), preflight.designedPhotoIds)
                assertEquals(1, preflight.undesignedCount)
                assertEquals("p1170883.jpg", preflight.undesigned.single().displayName)
                // b is the 2nd photo in the list; filtering must not renumber it.
                assertEquals(2, preflight.undesigned.single().nameIndex)

                // Confirming 「仅导出已设计的 X 张」 — even when the whole (mixed) selection
                // is handed to the backend, it enqueues exactly the designed one and
                // refuses the un-bordered photo itself (not via silent UI filtering).
                val added = controller.enqueueBatch(listOf(a, b))
                assertEquals(1, added)
                val jobs = controller.batchJobs
                assertEquals(1, jobs.size)
                assertEquals("vertical.jpg", jobs.single().displayName)
                assertEquals(1, jobs.single().nameIndex, "a 保持原列表序号 1")
                // The backend states the refused photo explicitly.
                assertEquals(listOf(b), controller.batchUndesignedSkipped.map { it.photoId })
                controller.dispose()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun cancelling_a_mixed_selection_enqueues_nothing() = runBlocking {
        val dir = Files.createTempDirectory("fm-w2-batch-cancel-")
        try {
            withPlatform(dir) {
                val (controller, ids) = designedAUndesignedB(dir)
                val (a, b) = ids
                val preflight = controller.preflightBatch(listOf(a, b))
                assertTrue(preflight.hasUndesigned)
                // 取消 = never call enqueueBatch. Preflight already left the queue untouched.
                assertEquals(0, controller.batchJobs.size)
                assertEquals(a, preflight.designedPhotoIds.single())
                controller.dispose()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun all_photos_undesigned_creates_zero_tasks_and_reports_all() = runBlocking {
        val dir = Files.createTempDirectory("fm-w2-batch-all-undesigned-")
        try {
            withPlatform(dir) {
                val controller = controller(dir)
                controller.load()
                controller.importPhotos(listOf(asset("vertical.jpg"), asset("p1170883.jpg")))
                val ids = controller.photos.map { it.id }
                // No applyCard at all → neither photo has its own binding.
                val preflight = controller.preflightBatch(ids)
                assertTrue(preflight.allUndesigned)
                assertEquals(0, preflight.designedCount)
                assertEquals(2, preflight.undesignedCount)

                assertEquals(0, controller.enqueueBatch(ids), "全部无设计时不创建任何任务")
                assertEquals(0, controller.batchJobs.size)
                assertEquals(2, controller.batchUndesignedSkipped.size)
                controller.dispose()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
