package com.framemark.native.workflow

import com.framemark.native.library.LibraryCard
import com.framemark.native.shell.desktop.DesktopLibraryPhotoThumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 导出期间暂停预览刷新（进程内闸门）。
 *
 * 三件事必须成立：
 *  1. 闸门关着时**没有任何**渲染请求到达引擎（舞台与边框库缩略图都一样）；
 *  2. 恢复后只补渲**最新一次**输入 —— 导出中连拖滑杆不能排成一条渲染队列，
 *     也不能停在导出前那一帧；
 *  3. 闸门一定会重新打开：导出成功、失败、取消三条路径都走 `finally`。
 *
 * 「导出侧」的挂起/恢复由 [BatchParallelExportTest] 从真实的批量导出身上验证；
 * 这里验证闸门本身与两条读闸门的消费路径。
 */
class PreviewSuspensionTest {

    @AfterTest
    fun releaseTheGate() {
        // A leaked hold would freeze every later preview in this JVM.
        PreviewSuspension.resetForVerify()
    }

    // ---- the gate itself -----------------------------------------------------

    @Test
    fun waiting_for_the_gate_returns_at_once_when_it_is_open() = runBlocking {
        PreviewSuspension.resetForVerify()
        assertTrue(!PreviewSuspension.suspended)
        // No suspension outstanding: this must not block a single tick.
        withTimeout(1_000) { PreviewSuspension.awaitResumed() }
    }

    @Test
    fun nested_holds_keep_previews_suspended_until_the_last_release() = runBlocking {
        PreviewSuspension.resetForVerify()
        PreviewSuspension.suspendRendering()
        PreviewSuspension.suspendRendering()
        assertTrue(PreviewSuspension.suspended)
        assertTrue(PreviewSuspension.active.value)

        PreviewSuspension.resumeRendering()
        assertTrue(
            PreviewSuspension.suspended,
            "one release must not reopen the gate while another holder still needs it",
        )

        PreviewSuspension.resumeRendering()
        assertTrue(!PreviewSuspension.suspended)
        // A release with no holder cannot make the depth negative.
        PreviewSuspension.resumeRendering()
        assertTrue(!PreviewSuspension.suspended)
        PreviewSuspension.suspendRendering()
        assertTrue(PreviewSuspension.suspended, "a later hold must still take effect")
        PreviewSuspension.resumeRendering()
    }

    // ---- the stage loop ------------------------------------------------------

    /**
     * The shape of `NativeStagePreview`'s scheduler: wake on a conflated signal, then
     * wait for the gate, **then** read the newest input and submit. Modelled here (rather
     * than driven through Compose) because the property under test is the ordering of
     * those three steps, and it uses the production [PreviewSuspension.awaitResumed].
     */
    @Test
    fun a_suspended_stage_submits_nothing_and_then_only_the_latest_input() = runBlocking {
        PreviewSuspension.resetForVerify()
        val signal = Channel<Unit>(Channel.CONFLATED)
        val submitted = CopyOnWriteArrayList<String>()
        val engineCalls = AtomicInteger(0)
        var latest = "design-v1"

        PreviewSuspension.suspendRendering()
        val loop = launch(Dispatchers.Default) {
            while (isActive) {
                signal.receive()
                // ── the stage's order: gate first, newest input second ──
                PreviewSuspension.awaitResumed()
                submitted += latest
                engineCalls.incrementAndGet()
                // The real loop drains wake-ups that this pass already covered.
                while (signal.tryReceive().isSuccess) Unit
            }
        }
        try {
            // The user keeps dragging sliders while the export runs.
            latest = "design-v2"
            signal.trySend(Unit)
            delay(80)
            latest = "design-v3"
            signal.trySend(Unit)
            delay(80)

            assertEquals(0, engineCalls.get(), "a suspended stage must not reach the engine")

            PreviewSuspension.resumeRendering()
            withTimeout(5_000) { while (submitted.isEmpty()) yield() }
            // Give any surviving wake-up the chance to (wrongly) submit again.
            delay(150)

            assertEquals(
                listOf("design-v3"),
                submitted.toList(),
                "after the export the stage must catch up with the newest design exactly once",
            )
            assertEquals(1, engineCalls.get())
        } finally {
            loop.cancelAndJoin()
        }
    }

    // ---- library thumbnails --------------------------------------------------

    @Test
    fun a_library_thumbnail_does_not_reach_the_engine_while_the_gate_is_held() = runBlocking {
        val oldPlatform = WorkflowEnv.platform
        val oldRender = WorkflowEnv.nativeRender
        val platform = InMemoryWorkflowPlatform("/thumb-gate")
        val render = CountingPreviewRender(platform)
        PreviewSuspension.resetForVerify()
        try {
            WorkflowEnv.platform = platform
            WorkflowEnv.nativeRender = render
            val card = thumbCard("thumb-gate-only")

            PreviewSuspension.suspendRendering()
            val load = launch(Dispatchers.Default) {
                DesktopLibraryPhotoThumbs.load(card, "/photos/gate.jpg", 320)
            }
            delay(150)
            assertEquals(
                0,
                render.calls.get(),
                "a prefetch must not take a core (or the GPU context) from a running export",
            )

            PreviewSuspension.resumeRendering()
            withTimeout(10_000) { load.join() }
            assertEquals(1, render.calls.get(), "after the export the thumbnail renders once")
        } finally {
            PreviewSuspension.resetForVerify()
            WorkflowEnv.platform = oldPlatform
            WorkflowEnv.nativeRender = oldRender
        }
    }

    private class CountingPreviewRender(
        private val platform: InMemoryWorkflowPlatform,
    ) : NativeRenderApi {
        val calls = AtomicInteger(0)

        override fun supports(cardId: String): Boolean = true

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
            calls.incrementAndGet()
            val path = "/thumb-renders/$cardId.png"
            // Not a real PNG: the loader's decode failure is caught, and the engine call
            // count is what this test is about.
            platform.seed(path, "not-a-png".encodeToByteArray())
            return NativeImage(path, 320, 240, 1)
        }
    }

    private fun thumbCard(id: String): LibraryCard = LibraryCard(
        id = id,
        name = "缩略图边框",
        category = "test",
        categoryName = "测试",
        brand = null,
        vendor = null,
        family = null,
        fidelity = null,
        badge = null,
        en = null,
        systemStyle = "style-thumb",
        previewSourcePath = "",
        previewFile = "",
    )
}
