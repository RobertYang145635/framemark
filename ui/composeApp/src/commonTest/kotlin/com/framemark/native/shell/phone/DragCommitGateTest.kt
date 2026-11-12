package com.framemark.native.shell.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 拖动气泡的判据（2026-11-13）：一次拖动里**最多 10 次/秒**的写回，松手补最后一笔，
 * 显示值始终跟手。
 *
 * 时钟是注入的，所以这些断言不依赖真实时间：一个 320 ms、40 个样本的拖动（60–120 Hz
 * 手指量级）在改动前是 40 次 `session.set`，现在是 4 次 + 松手那 1 次。
 */
class DragCommitGateTest {

    private class Harness(intervalMs: Long = DRAG_COMMIT_INTERVAL_MS) {
        var now = 0L
        val published = mutableListOf<Float>()
        val gate = DragCommitGate(
            initial = 0f,
            publish = { published += it },
            intervalMs = intervalMs,
            clock = { now },
        )

        /** 定时期的一步（真实实现在 `rememberDragCommit` 的协程里）。 */
        fun tick() {
            gate.publishIfDue()
        }

        fun sample(value: Float, atMs: Long) {
            now = atMs
            gate.sample(value)
            tick()
        }
    }

    @Test
    fun aFastDragWritesAtMostTenTimesPerSecond() {
        val h = Harness()
        // 40 samples over 312 ms, 8 ms apart — one per touch sample of a real finger.
        for (index in 0 until 40) {
            h.sample(index.toFloat(), atMs = index * 8L)
        }
        // t = 0, 104, 208, 312 are the only moments the gate is due.
        assertEquals(listOf(0f, 13f, 26f, 39f), h.published)
        assertEquals(39f, h.gate.live)
        assertTrue(h.gate.dragging)

        // Release is a no-op here: the last sample was already written.
        h.now = 321
        h.gate.release()
        assertEquals(listOf(0f, 13f, 26f, 39f), h.published)
        assertFalse(h.gate.dragging)
    }

    @Test
    fun releaseFlushesASampleThatDidNotFitInTheWindow() {
        val h = Harness()
        // Stops at sample 38 (t = 304): the window closed at t = 208 with value 26.
        for (index in 0 until 39) h.sample(index.toFloat(), atMs = index * 8L)
        assertEquals(listOf(0f, 13f, 26f), h.published)
        h.now = 305
        h.gate.release()
        assertEquals(listOf(0f, 13f, 26f, 38f), h.published)
    }

    @Test
    fun theDisplayValueTracksEverySampleEvenWhenNothingIsWritten() {
        val h = Harness()
        for (index in 0 until 40) h.sample(index.toFloat(), atMs = index * 8L)
        assertEquals(39f, h.gate.live)
        // 40 samples in, 4 writes out: the number on screen never lags the finger.
        assertEquals(4, h.published.size)
    }

    @Test
    fun releaseSkipsADuplicateWrite() {
        val h = Harness()
        h.sample(5f, atMs = 0L)
        assertEquals(listOf(5f), h.published)
        h.gate.release()
        assertEquals(listOf(5f), h.published)
    }

    @Test
    fun anExternalChangeIsFollowedOnlyWhileNotDragging() {
        val h = Harness()
        h.sample(10f, atMs = 0L)
        // A preset / undo landing mid-drag must not yank the value under the finger.
        h.gate.syncExternal(99f)
        assertEquals(10f, h.gate.live)
        h.gate.release()
        h.gate.syncExternal(99f)
        assertEquals(99f, h.gate.live)
    }

    @Test
    fun aPausedFingerStillGetsThePendingValueWritten() {
        val h = Harness()
        h.sample(1f, atMs = 0L)
        h.now = 8
        h.gate.sample(2f) // finger pauses after this sample
        // The timer wakes up 100 ms after the first write and publishes the pause value.
        h.now = 100
        h.tick()
        assertEquals(listOf(1f, 2f), h.published)
    }

    @Test
    fun theIntervalIsOneHundredMilliseconds() {
        assertEquals(100L, DRAG_COMMIT_INTERVAL_MS)
        val h = Harness()
        h.now = 0
        h.gate.publishLatest()
        h.now = 99
        assertFalse(h.gate.publishIfDue())
        h.now = 100
        assertTrue(h.gate.publishIfDue())
    }
}
