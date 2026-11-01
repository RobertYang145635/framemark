package com.framemark.native.workflow

import com.framemark.native.product.BatchJob
import com.framemark.native.product.BatchQueue

/**
 * Reconciles a process-interrupted render only when its pre-commit receipt matches
 * the job and the final file still has the recorded length and SHA-256. A path's
 * existence alone is never evidence that the queue item completed.
 */
fun recoverBatchQueue(
    queue: BatchQueue,
    platform: WorkflowPlatform?,
    now: Long = platform?.nowMillis() ?: 0L,
): BatchQueue = queue.recoverAfterRestart(now) { job ->
    platform != null && validPreparedOutput(job, platform)
}

private fun validPreparedOutput(job: BatchJob, platform: WorkflowPlatform): Boolean {
    val path = job.preparedOutputPath ?: return false
    val size = job.preparedOutputBytes ?: return false
    val expectedSha = job.preparedOutputSha256 ?: return false
    if (job.preparedInputKey != job.outputReceiptKey) return false
    return runCatching {
        platform.exists(path) &&
            platform.fileSize(path) == size &&
            platform.fileFingerprint(path)?.equals(expectedSha, ignoreCase = true) == true
    }.getOrDefault(false)
}
