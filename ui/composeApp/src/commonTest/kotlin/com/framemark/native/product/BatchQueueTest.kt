package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatchQueueTest {
    private fun job(id: String, photoId: String, seed: Int = 1, nameIndex: Int = 1): BatchJob = BatchJob.forPhoto(
        id = id,
        photoPath = "/photos/$photoId.jpg",
        photoFingerprint = "fp-$photoId",
        displayName = "$photoId.jpg",
        design = PhotoDesign.base("simple-4")
            .withParam("inset", 0.1)
            .withText("model", "X-T5")
            .withOverlay(watermarkSpec(seed)),
        exportSettings = ExportSettings(sizeMode = ExportSizeMode.LongEdge, longEdge = 6000),
        now = 1,
        nameIndex = nameIndex,
        outputDirectory = "/out",
    )

    private fun queueOf(vararg jobs: BatchJob): BatchQueue {
        var queue = BatchQueue()
        jobs.forEach { job ->
            val outcome = queue.submit(job)
            assertTrue(outcome is SubmitOutcome.Added, "提交被拒绝：${job.id}")
            queue = outcome.queue
        }
        return queue
    }

    @Test
    fun snapshot_lifts_the_overlay_out_of_the_design() {
        val job = job("j1", "a", seed = 3)
        assertNull(job.design.overlay, "批次任务的设计里不应再带内嵌水印")
        assertEquals("sig-3.png", job.overlay?.asset?.name)
        assertEquals(0.1, job.design.params["inset"])
        assertEquals("X-T5", job.design.text["model"])
        assertNotNull(job.photoFingerprint)
    }

    @Test
    fun queue_snapshot_survives_a_store_round_trip() {
        val fs = FakeFs()
        val store = SessionStore(fs)
        var queue = queueOf(job("j1", "a"), job("j2", "b"), job("j3", "c", seed = 3))
        queue = queue.claim("j1", 10).complete("j1", "/out/j1.jpg", 11)
        queue = queue.claim("j2", 12).fail("j2", BatchJobError("FM-E-EXPORT-001", "编码失败"), 13)

        assertTrue(store.saveBatch(queue) is StoreResult.Ok)
        val loaded = store.loadBatch().ok()

        assertEquals(queue, loaded)
        assertEquals(3, loaded.jobs.size)
        assertEquals(queue.job("j1")!!.designKey, loaded.job("j1")!!.designKey)
        assertEquals(queue.job("j1")!!.idempotencyKey, loaded.job("j1")!!.idempotencyKey)
        assertEquals("sig-3.png", loaded.job("j3")!!.overlay?.asset?.name)
        assertTrue(loaded.job("j3")!!.overlay!!.asset.isIntact())
        assertEquals(BatchJobStatus.Done, loaded.job("j1")!!.status)
        assertEquals(BatchJobStatus.Failed, loaded.job("j2")!!.status)
        assertEquals("编码失败", loaded.job("j2")!!.error?.message)
    }

    @Test
    fun restart_resumes_without_rerunning_done_items_or_losing_work() {
        val fs = FakeFs()
        val store = SessionStore(fs)
        var queue = queueOf(job("j1", "a"), job("j2", "b"), job("j3", "c"))
        assertTrue(store.saveBatch(queue) is StoreResult.Ok)

        // j1 finished, j2 was killed mid-export, j3 never started.
        queue = queue.claim("j1", 10).complete("j1", "/out/j1.jpg", 11).claim("j2", 12)
        assertTrue(store.saveBatch(queue) is StoreResult.Ok)

        var resumed = store.loadBatch().ok().recoverAfterRestart()
        assertEquals(BatchJobStatus.Done, resumed.job("j1")!!.status)
        assertEquals(BatchJobStatus.Queued, resumed.job("j2")!!.status)
        assertEquals(BatchJobStatus.Queued, resumed.job("j3")!!.status)
        assertEquals(1, resumed.job("j2")!!.attempts)

        val outputs = mutableListOf<String>()
        var guard = 0
        while (true) {
            val next = resumed.nextQueued ?: break
            assertTrue(guard++ < 10, "续跑循环没有收敛")
            resumed = resumed.claim(next.id, 20).complete(next.id, "/out/${next.id}.jpg", 21)
            outputs += "/out/${next.id}.jpg"
        }

        assertEquals(3, resumed.doneCount)
        assertEquals(0, resumed.failedCount)
        assertEquals(listOf("/out/j2.jpg", "/out/j3.jpg"), outputs, "续跑重跑了已完成项或漏项")
        assertEquals(3, resumed.jobs.mapNotNull { it.outputPath }.toSet().size)
        assertEquals(2, resumed.job("j2")!!.attempts)
        assertEquals(1, resumed.job("j1")!!.attempts)

        assertTrue(store.saveBatch(resumed) is StoreResult.Ok)
        val reloaded = store.loadBatch().ok()
        assertEquals(3, reloaded.doneCount)
        assertNull(reloaded.nextQueued)
        assertEquals(reloaded, reloaded.recoverAfterRestart())
    }

    @Test
    fun an_old_running_job_without_output_snapshot_is_rejected_for_rebuild() {
        val fs = FakeFs()
        val oldJob = job("legacy", "a").copy(status = BatchJobStatus.Running, attempts = 1, nameIndex = null, outputDirectory = null)
        val legacyJson = BatchCodec.encode(BatchQueue(listOf(oldJob)))
            .replace(",\"nameIndex\":null", "")
            .replace(",\"outputDirectory\":null", "")
        assertFalse(legacyJson.contains("\"nameIndex\""), "夹具应模拟缺失字段的旧队列")
        assertFalse(legacyJson.contains("\"outputDirectory\""), "夹具应模拟缺失字段的旧队列")
        fs.files[fs.batchPath] = legacyJson.encodeToByteArray()

        val loaded = SessionStore(fs).loadBatch().ok()
        assertTrue(loaded.job("legacy")!!.requiresRebuild)

        val recovered = loaded.recoverAfterRestart()
        val restored = recovered.job("legacy")!!
        assertEquals(BatchJobStatus.Queued, restored.status)
        assertTrue(restored.requiresRebuild, "恢复不得猜测旧任务的输出序号和目录")
        assertNull(restored.outputPath)
        assertNull(restored.preparedOutputPath)
    }

    @Test
    fun resubmitting_the_same_photo_and_design_is_a_duplicate() {
        val queue = queueOf(job("j1", "a"))
        val duplicate = queue.submit(job("j2", "a"))
        assertTrue(duplicate is SubmitOutcome.Duplicate, "同照片同设计被重复入队")
        duplicate as SubmitOutcome.Duplicate
        assertEquals("j1", duplicate.existingId)
        assertEquals(1, duplicate.queue.jobs.size)

        val differentDesign = job("j3", "a").let { it.copy(design = it.design.withParam("inset", 0.5)) }
        val added = queue.submit(differentDesign)
        assertTrue(added is SubmitOutcome.Added, "改了参数应当是新任务")
    }

    @Test
    fun same_render_can_be_queued_to_another_directory_but_name_index_does_not_split_duplicates() {
        val original = job("j1", "a")
        val queue = queueOf(original)
        val anotherDirectory = job("j2", "a").copy(outputDirectory = "/other-out")
        assertEquals(original.idempotencyKey, anotherDirectory.idempotencyKey)
        assertNotEquals(original.copy(id = "j2").outputReceiptKey, anotherDirectory.outputReceiptKey)

        val addedElsewhere = queue.submit(anotherDirectory)
        assertTrue(
            addedElsewhere is SubmitOutcome.Added,
            "同一渲染输入可在不同输出目录再次导出",
        )

        val sameDirectoryDifferentIndex = job("j3", "a", nameIndex = 2).copy(outputDirectory = "/other-out")
        val duplicate = addedElsewhere.queue.submit(sameDirectoryDifferentIndex)
        assertTrue(
            duplicate is SubmitOutcome.Duplicate,
            "同一输出目录仍按渲染输入去重，文件名序号不单独创建重复任务",
        )
        assertEquals("j2", duplicate.existingId)
    }

    @Test
    fun cancelled_items_never_resume_but_can_be_submitted_again() {
        val cancelled = queueOf(job("j1", "a")).cancel("j1", 1)
        assertEquals(BatchJobStatus.Cancelled, cancelled.job("j1")!!.status)
        assertEquals(cancelled, cancelled.recoverAfterRestart())
        assertNull(cancelled.nextQueued)

        val again = cancelled.submit(job("j2", "a"))
        assertTrue(again is SubmitOutcome.Added)
        assertEquals(BatchJobStatus.Queued, again.queue.job("j2")!!.status)
    }

    @Test
    fun failed_items_can_be_retried_with_the_same_snapshot() {
        var queue = queueOf(job("j1", "a"))
            .claim("j1", 1)
            .fail("j1", BatchJobError("FM-E-EXPORT-001", "磁盘满"), 2)
        val failed = queue.job("j1")!!
        assertEquals(BatchJobStatus.Failed, failed.status)
        assertEquals(1, failed.attempts)

        queue = queue.retry("j1", 3)
        val retried = queue.job("j1")!!
        assertEquals(BatchJobStatus.Queued, retried.status)
        assertNull(retried.error)
        assertNull(retried.outputPath)
        assertEquals(1, retried.attempts)
        assertEquals(failed.design, retried.design)
        assertEquals(failed.overlay, retried.overlay)
        assertEquals(failed.idempotencyKey, retried.idempotencyKey)
    }

    @Test
    fun completing_twice_keeps_the_first_result() {
        var queue = queueOf(job("j1", "a")).claim("j1", 1).complete("j1", "/out/first.jpg", 2)
        queue = queue.complete("j1", "/out/second.jpg", 3)
        assertEquals("/out/first.jpg", queue.job("j1")!!.outputPath)
        assertEquals(1, queue.job("j1")!!.attempts)
    }

    @Test
    fun only_queued_items_can_be_claimed() {
        val cancelled = queueOf(job("j1", "a")).cancel("j1", 1)
        assertEquals(cancelled, cancelled.claim("j1", 2))

        val done = queueOf(job("j2", "b")).claim("j2", 1).complete("j2", "/out/j2.jpg", 2)
        assertEquals(done, done.claim("j2", 3).retry("j2", 4).cancel("j2", 5))
    }
}
