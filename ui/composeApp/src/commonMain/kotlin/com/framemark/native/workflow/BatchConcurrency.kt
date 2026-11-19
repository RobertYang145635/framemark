package com.framemark.native.workflow

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Batch export is **CPU** work: the engine renders each frame on the CPU, so the
 * number of photos a batch keeps in flight comes from the core count — not from
 * the GPU, and not from `availableProcessors` directly (a 8-core phone would put
 * eight 4000×3000 renders, their encodes and their file writes on the heap at
 * once).
 *
 * The policy lives here, in one place, so the phone (`PhonePhotoBatchQueue`) and
 * the demo/verify queue (`BatchRunner`) can never disagree about how many images
 * are being rendered at the same time:
 *
 * ```
 * workers = min(4, max(2, cores / 2))
 * ```
 *
 * - `cores / 2` leaves the other half of the cores for the UI, the encoder thread
 *   and file IO; a batch that takes *every* core makes the app feel frozen.
 * - **2** is the floor: the render, the encode and the commit are a pipeline with
 *   IO waits, so even a single-core device gets overlap (unknown core count also
 *   lands here — the safe answer when the platform will not say).
 * - **4** is the ceiling: peak memory and IO contention grow with the number of
 *   in-flight frames, and past four workers a phone loses more to memory pressure
 *   and storage serialisation than it gains in parallelism.
 *
 * A batch whose single frame is larger than [LARGE_FRAME_PIXELS] drops back to the
 * floor ([batchWorkerCountForFrame]): four 24 MP frames at once is how a batch run
 * becomes an OOM kill instead of a slow export.
 */
const val MAX_BATCH_WORKERS: Int = 4

/** Floor of [batchWorkerCount]; also the fallback when the platform cannot tell. */
const val MIN_BATCH_WORKERS: Int = 2

/**
 * One frame above this many pixels is treated as "large" and caps the run at
 * [MIN_BATCH_WORKERS] workers.
 *
 * 12 MP is the size of a current phone camera frame (4000×3000). Below it four
 * frames fit in the memory an app is normally allowed to hold; above it, four
 * canvas + source + encoder buffers at once are an OOM risk on a mid-range device.
 */
const val LARGE_FRAME_PIXELS: Long = 12_000_000L

/**
 * CPU cores this device reports, or **0** when the platform cannot answer.
 *
 * The platform supplies only the core count; the worker formula above stays shared
 * so it is tested once, in `commonTest`, instead of being re-derived per platform.
 */
expect fun platformProcessorCount(): Int

/**
 * Workers for one batch run on a device with [cores] CPUs: `min(4, max(2, cores/2))`.
 *
 * A non-positive [cores] means "unknown" and yields [MIN_BATCH_WORKERS].
 */
fun batchWorkerCount(cores: Int = platformProcessorCount()): Int =
    (cores / 2).coerceIn(MIN_BATCH_WORKERS, MAX_BATCH_WORKERS)

/**
 * Workers for a run whose largest single output frame is [largestFramePixels]
 * pixels; `null` means "unknown" (原尺寸 keeps the source size, which neither
 * queue records) and is treated as large.
 *
 * Exporting N frames at once costs roughly N times the peak memory of one, so a
 * large frame caps the run at [MIN_BATCH_WORKERS]. Everything else keeps the
 * core-based count.
 */
fun batchWorkerCountForFrame(
    largestFramePixels: Long?,
    cores: Int = platformProcessorCount(),
): Int {
    val workers = batchWorkerCount(cores)
    return if (largestFramePixels == null || largestFramePixels > LARGE_FRAME_PIXELS) {
        minOf(workers, MIN_BATCH_WORKERS)
    } else {
        workers
    }
}

/**
 * Runs [workers] identical consumers until [next] reports there is nothing left.
 *
 * Each worker loops on `next()` — the caller owns the ordering, the pause/cancel
 * gate and the claim of the next unit — and hands the result to [run]. Two
 * properties matter to the batch queues:
 *
 * - **bounded**: exactly [workers] units of work are in flight, never more, so the
 *   memory ceiling is the caller's worker count and not the queue length;
 * - **no item loss**: [next] is called by one worker at a time only if the caller
 *   makes it so; this helper never reorders, retries or skips a unit it was given.
 *
 * A unit that throws therefore ends the pool with that failure (the queues wrap
 * each unit in their own `try`, so one failed photo does not stop the rest); a
 * cancelled caller cancels the whole pool, which is how "cancel stops the photos
 * that have not started" is implemented — every worker's next `next()` returns
 * null instead of a job.
 */
internal suspend fun <T> runBatchWorkers(
    workers: Int,
    next: suspend () -> T?,
    run: suspend (T) -> Unit,
) {
    coroutineScope {
        repeat(workers.coerceAtLeast(1)) {
            launch {
                while (true) {
                    val unit = next() ?: break
                    run(unit)
                }
            }
        }
    }
}

/**
 * Splits one run into the items that may start at any time and the items that must
 * keep the queue's order because they aim at the **same** output file.
 *
 * A serial batch assigns the `-2 … -N` disambiguation suffix in queue order, so two
 * jobs that resolve to the same first path must not race for it: the runner exports
 * the [Pair.second] group one at a time, in this order, and the two runs then agree
 * on every file name. Items with no resolvable target ([targetOf] returns null) are
 * treated as independent — there is nothing to collide with.
 */
internal fun <T> batchRunOrder(items: List<T>, targetOf: (T) -> String?): Pair<List<T>, List<T>> {
    val targets = items.map(targetOf)
    val counts = HashMap<String, Int>(items.size)
    targets.forEach { target -> if (target != null) counts[target] = (counts[target] ?: 0) + 1 }
    val sharedTargets = counts.filterValues { it > 1 }.keys
    if (sharedTargets.isEmpty()) return items to emptyList()
    val independent = ArrayList<T>(items.size)
    val ordered = ArrayList<T>()
    items.forEachIndexed { index, item ->
        val target = targets[index]
        if (target != null && target in sharedTargets) ordered += item else independent += item
    }
    return independent to ordered
}
