package com.framemark.native.workflow

import com.framemark.native.library.LibraryCard
import com.framemark.native.product.ExportSettings
import com.framemark.native.product.ExportSizeMode
import com.framemark.native.product.FakeFs
import com.framemark.native.product.PhotoDesign
import com.framemark.native.product.ProjectWatermarkSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 批量导出是 CPU 多核任务：一次跑多张，而不是一张一张顺着跑。
 *
 * 这些测试盯住「并行」本身（同一时刻真的有多张在渲染、总耗时明显低于串行），
 * 以及并行**不许**动的那些语义：
 *  - 每张的成片内容仍然是它自己的（不串图），命名公式不变；
 *  - 单张失败不打断其它张；
 *  - 取消后没开始的一张都不再开始；
 *  - 导出期间预览闸门关着，且无论成功 / 失败 / 取消都一定会重新打开。
 *
 * 渲染时间用 `delay` 模拟：`delay` 必然挂起，所以即便宿主机只有一个核，两个 worker
 * 也是真重叠，断言不依赖线程数刚好够用。
 */
class BatchParallelExportTest {

    /** One fake export takes this long; the serial baseline for 8 photos is 8× this. */
    private val renderMs = 100L

    // ---- fakes -------------------------------------------------------------

    /**
     * Slow renderer: counts the frames it is working on at once, can hold every frame on
     * a [gate] (for the cancel test) and can fail chosen cards.
     */
    private class CountingRender(
        private val platform: InMemoryWorkflowPlatform,
        private val renderMs: Long,
        private val failing: Set<String> = emptySet(),
        private val gate: CompletableDeferred<Unit>? = null,
    ) : NativeRenderApi {
        val startedCards = CopyOnWriteArrayList<String>()
        private val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)

        /** Frames that started while previews were suspended / allowed. */
        val startedWhilePreviewSuspended = CopyOnWriteArrayList<String>()
        val startedWhilePreviewActive = CopyOnWriteArrayList<String>()

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
            interactive: Boolean,
        ): NativeImage {
            startedCards += cardId
            if (PreviewSuspension.suspended) {
                startedWhilePreviewSuspended += cardId
            } else {
                startedWhilePreviewActive += cardId
            }
            val concurrent = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, concurrent) }
            try {
                gate?.await()
                delay(renderMs)
                if (cardId in failing) throw IllegalStateException("injected render failure for $cardId")
                val target = requireNotNull(outputPath) { "an export render needs a target" }
                // Per-card payload: the committed file proves no two items were mixed up.
                platform.writeBytes(target, "rendered:$cardId".encodeToByteArray())
                return NativeImage(target, 1600, 1200, renderMs)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /**
     * Everything one test needs. [install] must run **after** the fakes are published to
     * [WorkflowEnv]: the queue seeds its items from the installed engine.
     */
    private class Harness(
        cardCount: Int,
        renderMs: Long,
        failing: Set<String> = emptySet(),
        gate: CompletableDeferred<Unit>? = null,
        filenamePattern: String = "{name}-{date}",
        private val sourcePhoto: String = "/parallel-batch/photos/IMG_1.jpg",
    ) {
        val fs = FakeFs("/parallel-batch")
        val platform = InMemoryWorkflowPlatform(fs.dataDir)
        val cards = (1..cardCount).map { index ->
            LibraryCard(
                id = "card-$index",
                name = "边框 $index",
                category = "test",
                categoryName = "测试",
                brand = null,
                vendor = null,
                family = null,
                fidelity = null,
                badge = null,
                en = null,
                systemStyle = "style-$index",
                previewSourcePath = "",
                previewFile = "",
            )
        }
        val render = CountingRender(platform, renderMs, failing, gate)
        private val inputs = object : BatchInputs {
            override fun designFor(cardId: String, styleId: String?): PhotoDesign =
                PhotoDesign.base(cardId, styleId)

            override fun overlay(): ProjectWatermarkSpec? = null

            override fun exportSettings(): ExportSettings = ExportSettings(
                sizeMode = ExportSizeMode.LongEdge,
                longEdge = 1600,
                filenamePattern = filenamePattern,
            )

            override fun photoPath(): String? = sourcePhoto

            override fun fingerprint(path: String?): String? = path?.let { "fingerprint-1" }
        }
        lateinit var runner: BatchRunner

        fun install() {
            platform.seed(sourcePhoto, "photo".encodeToByteArray())
            runner = BatchRunner(fs, inputs)
            runner.seedDemo(cards)
            check(runner.items.size == cards.size) { "the harness must queue one item per card" }
        }

        suspend fun awaitSettled() {
            withTimeout(30_000) {
                while (runner.runState != BatchRunState.Finished &&
                    runner.runState != BatchRunState.Cancelled
                ) {
                    yield()
                }
            }
        }

        suspend fun awaitInFlight(count: Int) {
            withTimeout(30_000) {
                while (render.maxInFlight.get() < count) yield()
            }
        }
    }

    private suspend fun <T> withHarness(harness: Harness, block: suspend () -> T): T {
        val oldPlatform = WorkflowEnv.platform
        val oldRender = WorkflowEnv.nativeRender
        PreviewSuspension.resetForVerify()
        try {
            WorkflowEnv.platform = harness.platform
            WorkflowEnv.nativeRender = harness.render
            harness.install()
            return block()
        } finally {
            PreviewSuspension.resetForVerify()
            WorkflowEnv.platform = oldPlatform
            WorkflowEnv.nativeRender = oldRender
        }
    }

    /**
     * The worker count the queue itself uses for these settings, taken from the
     * production policy so the assertions follow the formula instead of hard-coding 4.
     */
    private val expectedWorkers: Int
        get() = batchWorkerCountForFrame(1600L * 1600L)

    // ---- 1. really parallel, same outputs --------------------------------

    @Test
    fun eight_items_export_at_once_and_each_keeps_its_own_output() = runBlocking {
        val harness = Harness(cardCount = 8, renderMs = renderMs)
        withHarness(harness) {
            val workers = expectedWorkers
            val startedAt = System.nanoTime()
            harness.runner.start()
            harness.awaitSettled()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            // Every photo exported exactly once, and the queue still lists them in order.
            assertEquals(8, harness.runner.completedCount)
            assertEquals(0, harness.runner.failedCount)
            assertEquals(harness.cards.map { it.id }.toSet(), harness.render.startedCards.toSet())
            assertEquals(harness.cards.map { it.name }, harness.runner.items.map { it.name })

            // Naming and content stay per item: `{name}-{date}.jpg` holding its own frame.
            harness.runner.items.forEach { item ->
                val path = assertNotNull(item.outputPath, "item ${item.name} has no output")
                assertTrue(
                    path.endsWith("${item.name}-20261101.jpg"),
                    "unexpected name for ${item.name}: $path",
                )
                assertEquals(
                    "rendered:${item.cardId}",
                    harness.platform.readBytes(path)?.decodeToString(),
                    "item ${item.name} did not get its own frame",
                )
            }
            assertEquals(
                8,
                harness.runner.items.mapNotNull { it.outputPath }.distinct().size,
                "two items must never share an output path",
            )

            // Parallelism: the pool really had `workers` frames in flight...
            assertEquals(minOf(workers, 8), harness.render.maxInFlight.get())
            // ...and serial would be `photos × render`, i.e. eight waves. The bound is
            // half of that whenever a pool of this size can reach it (workers ≥ 3); on a
            // two-worker machine the floor is `⌈n/2⌉` waves — exactly half — so the bound
            // there is that floor plus scheduling slack.
            val serialMs = 8 * renderMs
            val floorMs = ((8 + workers - 1) / workers) * renderMs
            val bound = maxOf(serialMs / 2, floorMs + 250)
            assertTrue(
                elapsedMs < bound,
                "8 photos took ${elapsedMs}ms; serial is ${serialMs}ms, " +
                    "a pool of $workers should need about ${floorMs}ms (bound ${bound}ms)",
            )
        }
    }

    // ---- 2. names stay the serial names, even for a same-target group ----

    /**
     * `{photo}` alone makes all eight items aim at the same file, which is the one
     * situation where a parallel run could hand the `-2 … -8` disambiguation suffixes
     * out in a different order than a serial run. The queue keeps those items in queue
     * order, so the names are byte-identical to serial — and each item still ends up
     * with its own file.
     */
    @Test
    fun items_that_aim_at_the_same_file_keep_the_serial_name_order() = runBlocking {
        val harness = Harness(
            cardCount = 8,
            renderMs = 5L,
            filenamePattern = "{photo}",
            sourcePhoto = "/parallel-batch/photos/IMG_9.jpg",
        )
        withHarness(harness) {
            harness.runner.start()
            harness.awaitSettled()

            assertEquals(8, harness.runner.completedCount)
            assertEquals(
                (1..8).map { number ->
                    if (number == 1) "/parallel-batch/exports/IMG_9.jpg"
                    else "/parallel-batch/exports/IMG_9-$number.jpg"
                },
                harness.runner.items.mapNotNull { it.outputPath },
                "同目标的任务必须按队列顺序拿 -N 后缀，和串行逐字节一致",
            )
            // Content is still per item, in queue order.
            harness.runner.items.forEach { item ->
                val path = assertNotNull(item.outputPath)
                assertEquals("rendered:${item.cardId}", harness.platform.readBytes(path)?.decodeToString())
            }
        }
    }

    // ---- 3. one failure does not stop the rest ---------------------------

    @Test
    fun one_failed_photo_does_not_stop_the_others() = runBlocking {
        val harness = Harness(cardCount = 8, renderMs = renderMs, failing = setOf("card-3"))
        withHarness(harness) {
            harness.runner.start()
            harness.awaitSettled()

            assertEquals(7, harness.runner.completedCount, "the other seven must still export")
            assertEquals(1, harness.runner.failedCount)
            val failed = harness.runner.items.single { it.cardId == "card-3" }
            assertEquals(BatchItemStatus.Failed, failed.status)
            // The user-facing sentence is the engine failure's own copy: a render that
            // throws becomes `EngineFailure.userMessage`, and the raw text stays in the log.
            assertEquals("这张照片没能渲染成功，请重试。", failed.error)
            harness.runner.items.filter { it.cardId != "card-3" }.forEach { item ->
                assertEquals(BatchItemStatus.Done, item.status, "${item.name} should have finished")
                assertNotNull(item.outputPath)
            }
            // A failure never serialises the pool: the remaining items still overlapped.
            assertTrue(
                harness.render.maxInFlight.get() >= 2,
                "the pool must stay parallel after a failure",
            )
        }
    }

    // ---- 4. cancel never starts the un-started --------------------------

    @Test
    fun cancel_stops_the_photos_that_have_not_started() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val harness = Harness(cardCount = 8, renderMs = renderMs, gate = gate)
        withHarness(harness) {
            val workers = minOf(expectedWorkers, 8)
            harness.runner.start()
            // Every worker has taken one item; the rest of the queue is untouched.
            harness.awaitInFlight(workers)
            val startedBeforeCancel = harness.render.startedCards.size
            assertEquals(workers, startedBeforeCancel)

            harness.runner.cancel()
            gate.complete(Unit)
            harness.awaitSettled()

            assertEquals(BatchRunState.Cancelled, harness.runner.runState)
            // Cancelling hands nothing new to the engine...
            assertEquals(
                startedBeforeCancel,
                harness.render.startedCards.size,
                "a cancelled run must not start another photo",
            )
            // ...and every photo that never started reads as cancelled, not as failed.
            val neverStarted = harness.runner.items.drop(startedBeforeCancel)
            assertEquals(8 - startedBeforeCancel, neverStarted.size)
            neverStarted.forEach { item ->
                assertEquals(
                    BatchItemStatus.Cancelled,
                    item.status,
                    "${item.name} was never started and must read as cancelled",
                )
            }
            // The frames that were already running are the only ones that may have
            // settled, and nothing was reported as a clean success.
            assertTrue(
                harness.runner.completedCount + harness.runner.failedCount <= startedBeforeCancel,
                "only the in-flight photos may settle",
            )
        }
    }

    // ---- 5. previews stand down for the whole run -----------------------

    @Test
    fun a_running_export_holds_the_preview_gate_and_always_releases_it() = runBlocking {
        val harness = Harness(cardCount = 6, renderMs = renderMs)
        withHarness(harness) {
            assertTrue(!PreviewSuspension.suspended, "the gate starts open")
            harness.runner.start()
            harness.awaitInFlight(1)
            assertTrue(
                PreviewSuspension.suspended,
                "preview rendering must stand down while a batch export runs",
            )
            harness.awaitSettled()
            assertTrue(!PreviewSuspension.suspended, "the gate must reopen when the run ends")
            assertEquals(
                harness.render.startedCards.size,
                harness.render.startedWhilePreviewSuspended.size,
                "every export frame must have run behind the gate",
            )
            assertTrue(harness.render.startedWhilePreviewActive.isEmpty())
        }
    }

    @Test
    fun a_failed_export_still_releases_the_preview_gate() = runBlocking {
        // Every card fails: the run ends through the failure path, which must still
        // reopen the gate — a batch that failed every photo cannot leave previews frozen.
        val harness = Harness(
            cardCount = 4,
            renderMs = renderMs,
            failing = (1..4).map { "card-$it" }.toSet(),
        )
        withHarness(harness) {
            harness.runner.start()
            harness.awaitSettled()
            assertEquals(4, harness.runner.failedCount)
            assertTrue(
                !PreviewSuspension.suspended,
                "a run that failed every photo must not leave previews frozen",
            )
        }
    }

    @Test
    fun pausing_a_run_hands_the_previews_back_and_resuming_takes_them_again() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val harness = Harness(cardCount = 8, renderMs = renderMs, gate = gate)
        withHarness(harness) {
            harness.runner.start()
            harness.awaitInFlight(minOf(expectedWorkers, 8))
            harness.runner.pause()
            gate.complete(Unit)
            // The paused run does no CPU work, so the previews must come back while it
            // waits — a frozen stage on a paused batch looks like a broken editor.
            withTimeout(10_000) { while (PreviewSuspension.suspended) yield() }
            assertEquals(BatchRunState.Paused, harness.runner.runState)

            harness.runner.resume()
            harness.awaitSettled()
            assertEquals(8, harness.runner.completedCount)
            assertTrue(
                !PreviewSuspension.suspended,
                "the gate must be balanced after a pause/resume cycle",
            )
        }
    }
}
