package com.framemark.native.workflow

import com.framemark.native.product.BatchJob
import com.framemark.native.product.BatchJobStatus
import com.framemark.native.product.BatchQueue

/**
 * How the jobs of **one** submission are doing right now.
 *
 * Derived from the persisted queue every time it is read — the queue stays the only
 * authority for job status. [removed] counts submitted jobs the user has since deleted,
 * so the parts always add up to the submission's own size instead of silently shrinking.
 */
data class SubmitTally(
    val queued: Int,
    val running: Int,
    val done: Int,
    val failed: Int,
    val cancelled: Int,
    val removed: Int,
) {
    val total: Int get() = queued + running + done + failed + cancelled + removed

    companion object {
        val Empty = SubmitTally(0, 0, 0, 0, 0, 0)

        fun of(queue: BatchQueue, ids: List<String>): SubmitTally = of(queue.jobs, ids)

        fun of(jobs: List<BatchJob>, ids: List<String>): SubmitTally {
            if (ids.isEmpty()) return Empty
            val byId = jobs.associateBy { it.id }
            var queued = 0
            var running = 0
            var done = 0
            var failed = 0
            var cancelled = 0
            var removed = 0
            ids.forEach { id ->
                when (byId[id]?.status) {
                    BatchJobStatus.Queued -> queued++
                    BatchJobStatus.Running -> running++
                    BatchJobStatus.Done -> done++
                    BatchJobStatus.Failed -> failed++
                    BatchJobStatus.Cancelled -> cancelled++
                    null -> removed++
                }
            }
            return SubmitTally(queued, running, done, failed, cancelled, removed)
        }
    }
}

/**
 * The feedback lines for the press that just happened, and **only** that press:
 * earlier Done rows stay in the queue but never inflate these numbers.
 *
 * Line 1 reconciles the user's selection: N = 入队 + 无设计未加入 + 已在队列 + 拒绝.
 * Line 2 splits the newly enqueued jobs by their current state, so 完成 here means
 * "this submission finished", not "the queue has N done jobs".
 */
fun submitFeedbackLines(
    selected: Int,
    added: Int,
    undesigned: Int,
    duplicates: Int,
    rejected: Int,
    tally: SubmitTally,
): List<String> {
    val lines = mutableListOf(
        "本次：选择 $selected → 入队 $added · 无设计未加入 $undesigned · 已在队列 $duplicates · 拒绝 $rejected",
    )
    if (added > 0) {
        val parts = mutableListOf(
            "待导出 ${tally.queued}",
            "导出中 ${tally.running}",
            "完成 ${tally.done}",
            "失败 ${tally.failed}",
            "已取消 ${tally.cancelled}",
        )
        if (tally.removed > 0) parts += "已移除 ${tally.removed}"
        lines += "本次入队 $added：" + parts.joinToString(" · ")
    }
    return lines
}
