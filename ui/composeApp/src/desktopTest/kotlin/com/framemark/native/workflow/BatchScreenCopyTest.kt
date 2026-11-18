package com.framemark.native.workflow

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI copy audit 2026-11-13, leak 4: the batch sheet's closing hint read
 * 「导出走原生引擎（E5，JPEG q100 4:4:4）…」. `E5` is the internal work-item id of the
 * export encoder (the docs call it "IO/导出 E5", the desktop worker is `E5Worker`), so
 * the user was reading a milestone tag; `q100` was the internal shorthand for the
 * quality setting. The card is user copy and may state the export properties only.
 *
 * The test reads the production constant, so a reworded hint is checked too.
 */
class BatchScreenCopyTest {

    @Test
    fun theBatchHintCarriesNoWorkItemId() {
        val hint = BATCH_EXPORT_HINT
        assertFalse(Regex("\\bE\\d+\\b").containsMatchIn(hint), "hint names a work item: $hint")
        assertFalse(hint.contains("q100"), "hint uses the internal quality shorthand: $hint")
    }

    @Test
    fun theBatchHintStillStatesTheRealExportProperties() {
        // This is a rewrite, not a deletion: the facts the user needs stay.
        val hint = BATCH_EXPORT_HINT
        assertTrue(hint.contains("JPEG 质量 100"), hint)
        assertTrue(hint.contains("4:4:4"), hint)
        assertTrue(hint.contains("导出目录"), hint)
    }

    @Test
    fun theQueueHintSaysWhatGetsFrozenWhenATaskIsAdded() {
        // 计划 §6：不看文档也要能说出「加入批量后会固定什么、之后再改会不会影响队列」。
        val hint = BATCH_QUEUE_HINT
        assertTrue(hint.contains("固定"), hint)
        assertTrue(hint.contains("边框"), hint)
        assertTrue(hint.contains("不影响已加入的任务"), hint)
        assertFalse(Regex("\\bE\\d+\\b").containsMatchIn(hint), "queue hint names a work item: $hint")
    }
}
