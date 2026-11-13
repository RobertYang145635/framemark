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
}
