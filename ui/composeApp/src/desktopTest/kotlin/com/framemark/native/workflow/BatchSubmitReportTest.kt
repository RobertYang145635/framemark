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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 本次提交的对账，桌面侧真实现：`batchLastSubmit` 必须让「选择 = 入队 + 无设计 + 重复 + 拒绝」
 * 在混合、重复、含未知照片三种情况下都成立，且不偷偷丢项。
 */
class BatchSubmitReportTest {
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
    fun mixed_selection_reports_enqueued_and_undesigned_without_losing_either() = runBlocking {
        val dir = Files.createTempDirectory("fm-b2-submit-mixed-")
        try {
            withPlatform(dir) {
                val (controller, ids) = designedAUndesignedB(dir)
                val (a, b) = ids

                controller.enqueueBatch(listOf(a, b))
                val report = assertNotNull(controller.batchLastSubmit)
                assertEquals(2, report.selected)
                assertEquals(1, report.added)
                assertEquals(1, report.undesigned)
                assertEquals(0, report.duplicates)
                assertEquals(0, report.rejected.size)
                assertTrue(report.reconciled, "选择 2 = 入队 1 + 无设计 1")
                assertEquals(1, SubmitTally.of(controller.batchJobs, report.addedIds).total)
                controller.dispose()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun pressing_again_reports_the_duplicate_instead_of_a_silent_zero() = runBlocking {
        val dir = Files.createTempDirectory("fm-b2-submit-dup-")
        try {
            withPlatform(dir) {
                val (controller, ids) = designedAUndesignedB(dir)
                val a = ids[0]
                controller.enqueueBatch(listOf(a))
                val first = assertNotNull(controller.batchLastSubmit)
                assertEquals(1, first.added)

                controller.enqueueBatch(listOf(a))
                val second = assertNotNull(controller.batchLastSubmit)
                assertEquals(0, second.added)
                assertEquals(1, second.duplicates, "重复必须报数，不能只说“没有新任务”")
                assertEquals(1, second.selected)
                assertTrue(second.reconciled, "选择 1 = 入队 0 + 重复 1")
                controller.dispose()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun an_unknown_id_still_lands_in_one_of_the_four_buckets() = runBlocking {
        val dir = Files.createTempDirectory("fm-b2-submit-ghost-")
        try {
            withPlatform(dir) {
                val (controller, ids) = designedAUndesignedB(dir)
                val a = ids[0]
                controller.enqueueBatch(listOf(a))

                controller.enqueueBatch(listOf(a, "photo-that-does-not-exist"))
                val report = assertNotNull(controller.batchLastSubmit)
                assertEquals(2, report.selected)
                assertEquals(0, report.added)
                assertEquals(1, report.duplicates)
                assertEquals(
                    1,
                    report.undesigned + report.rejected.size,
                    "未知照片必须落在「无设计」或「拒绝」里，不能被静默跳过",
                )
                assertTrue(report.reconciled, "选择 2 = 入队 0 + 重复 1 + (无设计|拒绝) 1")
                controller.dispose()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
