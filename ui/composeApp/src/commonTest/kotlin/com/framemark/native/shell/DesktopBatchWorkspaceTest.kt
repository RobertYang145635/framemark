package com.framemark.native.shell

import com.framemark.native.product.BatchJob
import com.framemark.native.product.BatchJobError
import com.framemark.native.product.BatchJobStatus
import com.framemark.native.product.ExportSettings
import com.framemark.native.product.FmLog
import com.framemark.native.product.PhotoDesign
import com.framemark.native.product.PhotoRef
import com.framemark.native.shell.desktop.BatchPhotoRow
import com.framemark.native.shell.desktop.BatchPrimaryAction
import com.framemark.native.shell.desktop.BatchQueueView
import com.framemark.native.shell.desktop.BatchRowState
import com.framemark.native.shell.desktop.BatchSelection
import com.framemark.native.shell.desktop.batchPhotoRows
import com.framemark.native.shell.desktop.batchSettings
import com.framemark.native.shell.desktop.batchSettingsSummary
import com.framemark.native.shell.desktop.probeJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP-D2 批量导出的纯逻辑：跳过已导出（设计没变才跳）、未选边框不许勾、
 * 队列状态（进行中 / 失败带原因 / 重试 / 取消剩余）、设置摘要。
 */
class DesktopBatchWorkspaceTest {

    private val settings = ExportSettings()

    private fun photo(id: String, name: String) = PhotoRef(
        id = id,
        path = "C:/photos/$name.jpg",
        displayName = name,
        widthPx = 4000,
        heightPx = 3000,
        fingerprint = "sha-$id",
    )

    private fun design(cardId: String) = PhotoDesign(cardId = cardId)

    /** 一条“已经导出成功”的任务，键与当前输入一致。 */
    private fun doneJob(photo: PhotoRef, design: PhotoDesign): BatchJob =
        probeJob(photo, design, settings).copy(
            id = "job-${photo.id}",
            status = BatchJobStatus.Done,
            outputPath = "C:/out/${photo.displayName}.jpg",
        )

    private fun rows(): List<BatchPhotoRow> {
        val photos = listOf(photo("p1", "IMG_1"), photo("p2", "IMG_2"), photo("p3", "IMG_3"))
        val designs = mapOf(
            "p1" to design("stock-a"),
            "p2" to design("stock-b"),
            // p3 没有设计：还没选边框
        )
        val jobs = listOf(doneJob(photos[0], designs.getValue("p1")))
        return batchPhotoRows(photos, designs, settings, jobs) { id -> "边框 $id" }
    }

    // ---- 跳过已导出 / 未选边框 ----------------------------------------------

    @Test
    fun a_photo_already_exported_unchanged_is_skipped_by_default() {
        val rows = rows()
        assertTrue(rows[0].skipByDefault, "已导出且设计没变：默认不勾")
        assertNotNull(rows[0].unchangedExport)
        assertTrue(rows[0].detail.contains("已导出过"))
        assertFalse(rows[1].skipByDefault, "没导出过的照片默认要勾")
        val selected = BatchSelection.defaultSelection(rows)
        assertEquals(setOf("p2"), selected)
    }

    @Test
    fun a_skipped_photo_can_still_be_selected_by_hand() {
        val rows = rows()
        val selected = BatchSelection.toggle(BatchSelection.defaultSelection(rows), rows[0])
        assertEquals(setOf("p1", "p2"), selected, "手动勾回来表示再导一次")
        assertEquals(setOf("p2"), BatchSelection.toggle(selected, rows[0]))
    }

    @Test
    fun an_edited_design_is_not_skipped_any_more() {
        val photos = listOf(photo("p1", "IMG_1"))
        val before = design("stock-a")
        val jobs = listOf(doneJob(photos[0], before))
        val edited = before.copy(params = mapOf("padding" to 12))
        val rows = batchPhotoRows(photos, mapOf("p1" to edited), settings, jobs) { "边框" }
        assertFalse(rows[0].skipByDefault, "设计改了就不再算「已导出」")
        assertNull(rows[0].unchangedExport)
    }

    @Test
    fun changed_export_settings_also_stop_the_skip() {
        val photos = listOf(photo("p1", "IMG_1"))
        val d = design("stock-a")
        val jobs = listOf(doneJob(photos[0], d))
        val other = settings.copy(quality = 80)
        val rows = batchPhotoRows(photos, mapOf("p1" to d), other, jobs) { "边框" }
        assertFalse(rows[0].skipByDefault, "导出参数变了要重新导出")
    }

    @Test
    fun a_photo_without_a_border_cannot_be_selected() {
        val rows = rows()
        assertFalse(rows[2].designed)
        assertFalse(BatchSelection.selectable(rows[2]))
        assertEquals(emptySet(), BatchSelection.toggle(emptySet(), rows[2]))
        assertEquals(setOf("p1", "p2"), BatchSelection.selectAll(rows))
        assertTrue(rows[2].detail.contains("未选边框"))
        val line = BatchSelection.summaryLine(rows, setOf("p2"))
        assertTrue(line.contains("已选 1 张"), line)
        assertTrue(line.contains("跳过已导出 1 张"), line)
        assertTrue(line.contains("未选边框 1 张"), line)
    }

    // ---- 队列状态 -----------------------------------------------------------

    @Test
    fun queue_state_counts_and_progress() {
        val photos = listOf(photo("p1", "IMG_1"))
        val jobs = listOf(
            doneJob(photos[0], design("stock-a")).copy(id = "a", status = BatchJobStatus.Done),
            doneJob(photos[0], design("stock-b")).copy(id = "b", status = BatchJobStatus.Failed),
            doneJob(photos[0], design("stock-c")).copy(id = "c", status = BatchJobStatus.Running),
            doneJob(photos[0], design("stock-d")).copy(id = "d", status = BatchJobStatus.Queued),
            doneJob(photos[0], design("stock-e")).copy(id = "e", status = BatchJobStatus.Cancelled),
        )
        val summary = BatchQueueView.summary(jobs)
        assertEquals(5, summary.total)
        assertEquals(1, summary.done)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.running)
        assertEquals(1, summary.queued)
        assertEquals(1, summary.cancelled)
        assertEquals(3, summary.settled)
        assertEquals(60, summary.percent)
        assertTrue(summary.isRunning)
        assertEquals(2, BatchQueueView.cancelableCount(jobs), "排队中的 + 正在导出的都算「剩余」")
        assertEquals(listOf("b", "e"), BatchQueueView.retryableIds(jobs))
        assertEquals(BatchPrimaryAction.CancelRemaining, BatchQueueView.primaryAction(running = true, jobs = jobs))
    }

    @Test
    fun a_failed_row_carries_a_readable_reason() {
        val photos = listOf(photo("p1", "IMG_1"))
        val failed = doneJob(photos[0], design("stock-a")).copy(
            id = "bad",
            status = BatchJobStatus.Failed,
            outputPath = null,
            error = BatchJobError("FM-E-EXPORT-001", "文件被占用：另一个程序正在使用它"),
        )
        val rows = BatchQueueView.rows(listOf(failed))
        assertEquals(BatchRowState.Failed, rows[0].state)
        assertTrue(rows[0].reason?.contains("文件被占用") == true, rows[0].reason)
        assertEquals("失败", BatchQueueView.statusLabel(rows[0].state))
        // 非失败状态不带原因。
        val done = doneJob(photos[0], design("stock-a"))
        assertNull(BatchQueueView.rows(listOf(done))[0].reason)
    }

    /**
     * UI copy audit 2026-11-13, item 4: the failure copy used to append the support code
     * (`…（FM-E-EXPORT-001）`). The sentence on screen carries no code now; the code is
     * logged under `FM_BATCH_ITEM_FAIL` and stays in the structured `BatchJobError`, so
     * support can still match a screenshot to a run.
     */
    @Test
    fun failure_text_translates_the_system_message_and_drops_the_code() {
        assertTrue(
            BatchQueueView.failureText(null, "另一个程序正在使用此文件，进程无法访问。").contains("文件被占用"),
        )
        assertTrue(
            BatchQueueView.failureText("FM-E-EXPORT-001", "java.nio.file.AccessDeniedException: Access is denied")
                .contains("没有写入权限"),
        )
        assertTrue(BatchQueueView.failureText(null, "Not a directory").contains("输出路径不存在"))
        assertTrue(BatchQueueView.failureText(null, "引擎返回了无法解析的返回码").contains("无法解析"))
        assertTrue(BatchQueueView.failureText(null, null).contains("原因未知"))

        // The code is the support handle: it stays in the structured failure and in the
        // producer's log (`BatchRunner`, FM_BATCH_ITEM_FAIL), never in the sentence.
        val job = doneJob(photo("p1", "IMG_1"), design("stock-a")).copy(
            id = "failed",
            status = BatchJobStatus.Failed,
            error = BatchJobError("FM-E-INPUT-001", "FM-E-INPUT-001 照片文件读不到：C:/x.jpg"),
        )
        val row = BatchQueueView.rows(listOf(job)).single()
        assertEquals("照片文件读不到：C:/x.jpg", row.reason)
        assertFalse(row.reason.orEmpty().contains("FM-E"), row.reason.orEmpty())
        assertEquals("FM-E-INPUT-001", job.error?.code)
    }

    @Test
    fun the_primary_action_follows_the_queue() {
        assertEquals(BatchPrimaryAction.Start, BatchQueueView.primaryAction(false, emptyList()))
        val photos = listOf(photo("p1", "IMG_1"))
        val failed = listOf(doneJob(photos[0], design("a")).copy(id = "f", status = BatchJobStatus.Failed))
        assertEquals(BatchPrimaryAction.RetryFailed, BatchQueueView.primaryAction(false, failed))
        val done = listOf(doneJob(photos[0], design("a")).copy(id = "d", status = BatchJobStatus.Done))
        assertEquals(BatchPrimaryAction.ExportAgain, BatchQueueView.primaryAction(false, done))
        val queued = listOf(doneJob(photos[0], design("a")).copy(id = "q", status = BatchJobStatus.Queued))
        assertEquals(BatchPrimaryAction.Start, BatchQueueView.primaryAction(false, queued))
    }

    // ---- 设置摘要 -----------------------------------------------------------

    @Test
    fun the_settings_summary_names_format_size_name_and_location() {
        val lines = batchSettingsSummary(settings, "C:/out")
        val map = lines.toMap()
        assertEquals("JPG · 质量 100", map["格式"])
        assertEquals("原尺寸", map["尺寸"])
        assertEquals("{photo}-{index}.jpg", map["文件名"])
        assertEquals("C:/out", map["位置"])

        val png = batchSettingsSummary(settings.copy(format = com.framemark.native.product.ExportFormat.Png), "C:/out")
        assertEquals("PNG · 无损编码", png.toMap()["格式"])

        val losslessWebp =
            batchSettingsSummary(settings.copy(format = com.framemark.native.product.ExportFormat.WebP), "C:/out")
        assertEquals("WebP 无损 · 无损编码", losslessWebp.toMap()["格式"])

        val lossy = batchSettingsSummary(
            settings.copy(format = com.framemark.native.product.ExportFormat.WebP, webpLossless = false),
            "C:/out",
        )
        assertEquals("WebP 有损 · 质量 100", lossy.toMap()["格式"])

        val longEdge = batchSettingsSummary(
            settings.copy(
                sizeMode = com.framemark.native.product.ExportSizeMode.LongEdge,
                longEdge = 2048,
            ),
            "C:/out",
        )
        assertEquals("长边 2048 px", longEdge.toMap()["尺寸"])
    }

    @Test
    fun batch_quality_defaults_to_the_local_jpeg_preference() {
        val clamped = batchSettings(settings.copy(quality = 100), preferredQuality = 77)
        assertEquals(77, clamped.quality)
        assertEquals(100, batchSettings(settings, preferredQuality = 500).quality, "越界回落到 100")
    }
}
