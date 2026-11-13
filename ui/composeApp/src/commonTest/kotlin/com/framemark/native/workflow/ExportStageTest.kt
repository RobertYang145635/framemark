package com.framemark.native.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The [ExportStage] ladder the phone export sheet drives its determinate bar from
 * (motion batch 2, 2026-11-14).
 *
 * The bar is only as honest as this table: the fraction is "stages completed / 4",
 * the engine reports no byte-level progress, and the sheet must never interpolate a
 * fake percentage between these stops. These tests pin the two properties that make
 * that true — the ladder starts at 0, ends at 1, and never goes backwards.
 */
class ExportStageTest {

    @Test
    fun theLadderStartsAtZeroAndEndsAtOne() {
        assertEquals(0f, ExportStage.Started.fraction)
        assertEquals(1f, ExportStage.Published.fraction)
    }

    @Test
    fun everyStopIsMonotonic() {
        var previous = -1f
        for (stage in ExportStage.entries) {
            assertTrue(
                stage.fraction > previous,
                "${stage.name} fraction ${stage.fraction} must be greater than $previous",
            )
            previous = stage.fraction
        }
    }

    @Test
    fun everyStopStaysInsideTheBar() {
        for (stage in ExportStage.entries) {
            assertTrue(stage.fraction in 0f..1f, "${stage.name} is outside the bar: ${stage.fraction}")
        }
    }

    @Test
    fun theDeclarationOrderIsThePipelineOrder() {
        // The UI consumes the enum in declaration order (Started → Published); a stage
        // inserted at the wrong end would silently move the bar backwards.
        assertEquals(
            listOf(
                ExportStage.Started,
                ExportStage.Rendered,
                ExportStage.Prepared,
                ExportStage.Committed,
                ExportStage.Published,
            ),
            ExportStage.entries,
        )
    }
}
