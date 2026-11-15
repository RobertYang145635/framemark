package com.framemark.native.workflow

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W-02: the window may close only after a write that made the project durable.
 *
 * These are the two halves of that rule: [decideExit] is the pure decision the
 * close handler makes, [AppExitGate] is the state the dialog reacts to. Both are
 * exercised here with an injected exit lambda, so the behaviour is pinned
 * without a window — the audit's requirement was "test the exit decision, not
 * only the saveError field".
 */
class AppExitTest {

    @AfterTest
    fun resetGate() {
        AppExitGate.dismiss()
        AppExitGate.exit = null
    }

    @Test
    fun a_durable_write_leaves_the_window() {
        assertEquals(ExitDecision.Exit, decideExit(saved = true, saveError = null, writesBlocked = false))
        // A stale error from an earlier failure must not block a successful close.
        assertEquals(ExitDecision.Exit, decideExit(saved = true, saveError = "FM-E-IO-003 旧错误", writesBlocked = false))
    }

    @Test
    fun a_failed_write_keeps_the_window_with_the_storage_reason() {
        val decision = decideExit(
            saved = false,
            saveError = "FM-E-IO-003 工程没有写入：磁盘已满",
            writesBlocked = false,
        )
        val stay = decision as? ExitDecision.Stay ?: error("must stay open")
        assertEquals("FM-E-IO-003 工程没有写入：磁盘已满", stay.block.reason)
        assertNull(stay.block.copyPath)
        assertNull(stay.block.copyError)
    }

    @Test
    fun a_failed_write_without_an_error_still_explains_itself() {
        val readFailure = decideExit(saved = false, saveError = null, writesBlocked = true)
        assertTrue((readFailure as ExitDecision.Stay).block.reason.contains("读取"))
        val plain = decideExit(saved = false, saveError = "   ", writesBlocked = false)
        assertTrue((plain as ExitDecision.Stay).block.reason.contains("写入"))
    }

    @Test
    fun a_refused_close_shows_the_dialog_and_exit_is_not_called() {
        var exited = 0
        AppExitGate.exit = { exited++ }
        AppExitGate.requestClose(decideExit(saved = false, saveError = "boom", writesBlocked = false))
        assertEquals(0, exited, "a failed save must never call exitApplication")
        assertEquals("boom", assertNotNull(AppExitGate.block).reason)
    }

    @Test
    fun retry_closes_the_window_once_the_write_succeeds() {
        var exited = 0
        AppExitGate.exit = { exited++ }
        AppExitGate.requestClose(decideExit(saved = false, saveError = "boom", writesBlocked = false))

        AppExitGate.retry(saved = true, saveError = null)
        assertEquals(1, exited)
        assertNull(AppExitGate.block, "a successful retry clears the dialog before exiting")
    }

    @Test
    fun a_failed_retry_updates_the_reason_and_stays_open() {
        var exited = 0
        AppExitGate.exit = { exited++ }
        AppExitGate.requestClose(decideExit(saved = false, saveError = "first", writesBlocked = false))

        AppExitGate.retry(saved = false, saveError = "still full")
        assertEquals(0, exited)
        assertEquals("still full", assertNotNull(AppExitGate.block).reason)

        // A retry that fails without a fresh message must not display a blank reason.
        AppExitGate.retry(saved = false, saveError = null)
        assertFalse(assertNotNull(AppExitGate.block).reason.isBlank())
    }

    @Test
    fun a_saved_copy_is_reported_without_closing_the_window() {
        var exited = 0
        AppExitGate.exit = { exited++ }
        AppExitGate.requestClose(decideExit(saved = false, saveError = "locked", writesBlocked = false))

        AppExitGate.copySaved("D:/backup/project-copy.json")
        assertEquals(0, exited, "a copy is not the document; the window stays open")
        val block = assertNotNull(AppExitGate.block)
        assertEquals("D:/backup/project-copy.json", block.copyPath)
        assertNull(block.copyError)

        AppExitGate.copyFailed("目标文件夹不可写")
        assertEquals("目标文件夹不可写", assertNotNull(AppExitGate.block).copyError)
        assertEquals("D:/backup/project-copy.json", assertNotNull(AppExitGate.block).copyPath)
    }

    @Test
    fun dismissing_keeps_the_window_and_leaves_no_dialog_state() {
        AppExitGate.requestClose(decideExit(saved = false, saveError = "boom", writesBlocked = false))
        AppExitGate.dismiss()
        assertNull(AppExitGate.block)
    }

    @Test
    fun discarding_the_edits_is_the_only_way_to_leave_without_a_write() {
        var exited = 0
        AppExitGate.exit = { exited++ }
        AppExitGate.requestClose(decideExit(saved = false, saveError = "boom", writesBlocked = false))

        AppExitGate.discardAndExit()
        assertEquals(1, exited)
        assertNull(AppExitGate.block)
    }

    @Test
    fun a_gate_without_an_installed_exit_still_clears_its_state() {
        AppExitGate.exit = null
        AppExitGate.requestClose(decideExit(saved = false, saveError = "boom", writesBlocked = false))
        AppExitGate.discardAndExit()
        assertNull(AppExitGate.block, "hosts without a window must not get stuck with a dialog")
    }
}
