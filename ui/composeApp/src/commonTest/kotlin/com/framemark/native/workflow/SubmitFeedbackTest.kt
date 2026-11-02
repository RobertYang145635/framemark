package com.framemark.native.workflow

import com.framemark.native.product.BatchJob
import com.framemark.native.product.BatchJobError
import com.framemark.native.product.BatchQueue
import com.framemark.native.product.ExportSettings
import com.framemark.native.product.PhotoDesign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 本次提交的反馈必须只描述这一次按下按钮的结果。
 *
 * These cases drive the real [BatchQueue] transitions, so a broken counter shows up as a
 * wrong number rather than as a formatting difference: 历史 Done 不算本次成功、重试不重复
 * 计数、取消与移除都要有归属。
 */
class SubmitFeedbackTest {
    private fun job(id: String, name: String = "$id.jpg"): BatchJob = BatchJob.forPhoto(
        id = id,
        photoPath = "/photos/$name",
        photoFingerprint = "fp-$id",
        displayName = name,
        design = PhotoDesign.base("simple-4"),
        exportSettings = ExportSettings(),
        now = 1,
        nameIndex = 1,
        outputDirectory = "/out",
    )

    private fun queueOf(vararg ids: String): BatchQueue {
        var queue = BatchQueue()
        ids.forEach { id ->
            val outcome = queue.submit(job(id))
            assertTrue(outcome is com.framemark.native.product.SubmitOutcome.Added, "$id 未入队")
            queue = outcome.queue
        }
        return queue
    }

    @Test
    fun a_finished_job_counts_under_this_submission_until_it_is_done() {
        val queue = queueOf("a", "b")
        val ids = listOf("a", "b")

        assertEquals(SubmitTally(2, 0, 0, 0, 0, 0), SubmitTally.of(queue, ids))

        val running = queue.claim("a", now = 2)
        assertEquals(SubmitTally(1, 1, 0, 0, 0, 0), SubmitTally.of(running, ids))

        val done = running.complete("a", "/out/a.jpg", now = 3)
        assertEquals(SubmitTally(1, 0, 1, 0, 0, 0), SubmitTally.of(done, ids))
    }

    @Test
    fun retrying_the_same_job_never_counts_as_a_second_success() {
        val queue = queueOf("a")
        val ids = listOf("a")
        val failed = queue.claim("a", now = 2)
            .fail("a", BatchJobError("FM-E-IO-003", "磁盘忙"), now = 3)
        assertEquals(1, SubmitTally.of(failed, ids).failed)

        val retried = failed.retry("a", now = 4)
        assertEquals(SubmitTally(1, 0, 0, 0, 0, 0), SubmitTally.of(retried, ids))

        val done = retried.claim("a", now = 5).complete("a", "/out/a.jpg", now = 6)
        val tally = SubmitTally.of(done, ids)
        assertEquals(1, tally.done, "同一任务重试成功后只能记一次成功")
        assertEquals(0, tally.failed, "成功后旧失败不得残留")
        assertEquals(1, tally.total)
    }

    @Test
    fun a_historical_done_job_is_invisible_to_the_next_submission() {
        // 上一批已经完成一条，用户再按一次按钮。
        val history = queueOf("old").claim("old", now = 2).complete("old", "/out/old.jpg", now = 3)
        assertEquals(1, history.doneCount, "队列里确有历史 Done")

        val outcome = history.submit(job("new"))
        assertTrue(outcome is com.framemark.native.product.SubmitOutcome.Added)
        val after = outcome.queue

        val tally = SubmitTally.of(after, listOf("new"))
        assertEquals(0, tally.done, "历史 Done 不得计入本次成功")
        assertEquals(1, tally.queued)
        assertEquals(1, tally.total)
    }

    @Test
    fun cancelled_and_removed_jobs_keep_their_own_bucket() {
        val queue = queueOf("a", "b", "c")
        val cancelled = queue.cancel("a", now = 2)
        val tally = SubmitTally.of(cancelled, listOf("a", "b", "c"))
        assertEquals(1, tally.cancelled)
        assertEquals(2, tally.queued)
        assertEquals(3, tally.total)

        // 用户把其中一条从队列里删掉：它仍属于本次选择，不能被悄悄抹掉。
        val withoutB = BatchQueue(cancelled.jobs.filterNot { it.id == "b" })
        val removed = SubmitTally.of(withoutB, listOf("a", "b", "c"))
        assertEquals(1, removed.removed)
        assertEquals(3, removed.total, "移除后总数仍等于本次入队数")
    }

    @Test
    fun the_two_feedback_lines_reconcile_the_selection_and_never_borrow_history() {
        val queue = queueOf("a", "b").claim("a", now = 2).complete("a", "/out/a.jpg", now = 3)
        val report = SubmitReport(
            added = 2,
            duplicates = 1,
            rejected = listOf("bad.jpg" to "没有照片路径，无法导出"),
            selected = 6,
            undesigned = 2,
            addedIds = listOf("a", "b"),
        )
        assertTrue(report.reconciled, "选择 6 = 入队 2 + 无设计 2 + 已在队列 1 + 拒绝 1")

        val lines = submitFeedbackLines(
            selected = report.selected,
            added = report.added,
            undesigned = report.undesigned,
            duplicates = report.duplicates,
            rejected = report.rejected.size,
            tally = SubmitTally.of(queue, report.addedIds),
        )
        assertEquals(2, lines.size)
        assertTrue("选择 6 → 入队 2" in lines[0], lines[0])
        assertTrue("无设计未加入 2" in lines[0], lines[0])
        assertTrue("已在队列 1" in lines[0], lines[0])
        assertTrue("拒绝 1" in lines[0], lines[0])
        assertTrue("待导出 1" in lines[1], lines[1])
        assertTrue("完成 1" in lines[1], lines[1])
    }

    @Test
    fun a_submission_that_queued_nothing_does_not_print_a_status_line() {
        // 全是重复：入队 0，就不该出现「本次入队 0：…」这种空分解。
        val lines = submitFeedbackLines(
            selected = 3,
            added = 0,
            undesigned = 0,
            duplicates = 3,
            rejected = 0,
            tally = SubmitTally.Empty,
        )
        assertEquals(1, lines.size)
        assertTrue("已在队列 3" in lines[0], lines[0])
    }
}
