package com.framemark.native.shell

import com.framemark.native.shell.desktop.DesktopTrial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V2 左栏行为：逐个试的撤销合并（0.7 s）、悬停预览（0.4 s）、FM_SAMPLE_PHOTO 只导入一次。
 * 纯逻辑，不启动窗口，阈值直接引用实现里的常量。
 */
class DesktopTrialTest {

    @Test
    fun rapid_switches_become_one_undo_step() {
        var state = DesktopTrial.UndoStep.Empty
        state = DesktopTrial.recordSwitch(state, "a", nowMs = 0)
        state = DesktopTrial.recordSwitch(state, "b", nowMs = 100)
        state = DesktopTrial.recordSwitch(state, "c", nowMs = 450)
        state = DesktopTrial.recordSwitch(state, "d", nowMs = 1_100) // 650 ms after the previous
        assertEquals("a", DesktopTrial.undoAnchor(state), "连续切换只回到第一张之前")
        assertEquals(4, state.switches)
    }

    @Test
    fun a_pause_longer_than_the_window_starts_a_new_step() {
        var state = DesktopTrial.UndoStep.Empty
        state = DesktopTrial.recordSwitch(state, "a", nowMs = 0)
        state = DesktopTrial.recordSwitch(state, "b", nowMs = DesktopTrial.UNDO_MERGE_WINDOW_MS)
        assertEquals("b", DesktopTrial.undoAnchor(state), "停满 0.7 秒后重新记一步")
        assertEquals(1, state.switches)
    }

    @Test
    fun undo_consumes_the_merged_step_exactly_once() {
        var state = DesktopTrial.recordSwitch(DesktopTrial.UndoStep.Empty, "a", nowMs = 0)
        state = DesktopTrial.recordSwitch(state, "b", nowMs = 10)
        assertEquals("a", DesktopTrial.undoAnchor(state))
        state = DesktopTrial.afterUndo(state)
        assertNull(DesktopTrial.undoAnchor(state), "同一步不能撤销两次")
        assertEquals(DesktopTrial.UndoStep.Empty, state)
    }

    @Test
    fun no_previous_card_means_no_trial_undo_step() {
        val state = DesktopTrial.recordSwitch(DesktopTrial.UndoStep.Empty, previousCardId = null, nowMs = 0)
        assertNull(DesktopTrial.undoAnchor(state))
        assertFalse(state.pending)
    }

    @Test
    fun hover_preview_waits_the_full_400ms() {
        assertEquals(400L, DesktopTrial.HOVER_PREVIEW_DELAY_MS)
        assertFalse(DesktopTrial.hoverReady(hoveredAtMs = 1_000, nowMs = 1_000))
        assertFalse(DesktopTrial.hoverReady(hoveredAtMs = 1_000, nowMs = 1_399))
        assertTrue(DesktopTrial.hoverReady(hoveredAtMs = 1_000, nowMs = 1_400))
        assertFalse(DesktopTrial.hoverReady(hoveredAtMs = null, nowMs = 9_999), "没悬停就不预览")
    }

    @Test
    fun sample_photo_imports_only_when_the_project_is_empty() {
        val path = "C:/photos/sample.jpg"
        assertTrue(
            DesktopTrial.shouldImportSamplePhoto(
                flagEnabled = true, hasPhotos = false, alreadyAttempted = false, samplePath = path,
            ),
        )
        assertFalse(
            DesktopTrial.shouldImportSamplePhoto(
                flagEnabled = false, hasPhotos = false, alreadyAttempted = false, samplePath = path,
            ),
        )
        assertFalse(
            DesktopTrial.shouldImportSamplePhoto(
                flagEnabled = true, hasPhotos = true, alreadyAttempted = false, samplePath = path,
            ),
            "项目里已有照片（含上次导入）时不能再导入",
        )
        assertFalse(
            DesktopTrial.shouldImportSamplePhoto(
                flagEnabled = true, hasPhotos = false, alreadyAttempted = true, samplePath = path,
            ),
            "同一次启动只尝试一次",
        )
        assertFalse(
            DesktopTrial.shouldImportSamplePhoto(
                flagEnabled = true, hasPhotos = false, alreadyAttempted = false, samplePath = null,
            ),
            "平台给不出示例照片路径时什么都不做",
        )
    }
}
