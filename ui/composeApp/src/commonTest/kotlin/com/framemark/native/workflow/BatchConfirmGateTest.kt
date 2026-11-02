package com.framemark.native.workflow

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P1-01: the desktop 「按照片批量」 sheet keeps its pre-flight confirmation in composable
 * state. Clearing or changing the selection — or a design binding landing behind the open
 * panel — must not leave that confirmation able to enqueue the set the user no longer chose.
 *
 * The decision lives in [batchConfirmGate] so it is reachable without a UI test harness.
 * Note on evidence: this is a green-only unit proof. The defect itself was established by
 * the reachable source path (selection mutators never cleared `pendingBatch`, and the
 * confirm button submitted the originally computed `preflight.designedPhotoIds`); the
 * click-through still needs a real Windows UI run and is not claimed here.
 */
class BatchConfirmGateTest {
    private val pendingIds = listOf("a", "b")
    private val mixed = BatchPreflight(listOf("a"), listOf(UndesignedPhoto("b", "b.jpg", 2)))

    @Test
    fun unchanged_selection_and_bindings_may_submit() {
        assertEquals(
            BatchConfirmGate.Submit,
            batchConfirmGate(pendingIds, setOf("a", "b"), mixed, mixed),
        )
    }

    @Test
    fun cleared_selection_is_refused() {
        assertEquals(
            BatchConfirmGate.SelectionChanged,
            batchConfirmGate(pendingIds, emptySet(), mixed, mixed),
            "清空选择后旧确认必须不再提交",
        )
    }

    @Test
    fun re_selection_is_refused() {
        assertEquals(
            BatchConfirmGate.SelectionChanged,
            batchConfirmGate(pendingIds, setOf("a"), mixed, mixed),
            "改选成只剩 a 时，旧确认不得替用户决定",
        )
    }

    @Test
    fun swapped_selection_of_the_same_size_is_refused() {
        assertEquals(
            BatchConfirmGate.SelectionChanged,
            batchConfirmGate(pendingIds, setOf("a", "c"), mixed, mixed),
        )
    }

    @Test
    fun bindings_that_changed_behind_the_panel_are_refused() {
        // Same selection, but b gained a border while the panel was open: the user must
        // re-run the check rather than confirm a prompt that no longer describes reality.
        val nowAllDesigned = BatchPreflight(listOf("a", "b"), emptyList())
        assertEquals(
            BatchConfirmGate.DesignChanged,
            batchConfirmGate(pendingIds, setOf("a", "b"), mixed, nowAllDesigned),
        )
    }

    @Test
    fun designed_set_shrinking_behind_the_panel_is_refused() {
        val nowOnlyB = BatchPreflight(listOf("b"), listOf(UndesignedPhoto("a", "a.jpg", 1)))
        assertEquals(
            BatchConfirmGate.DesignChanged,
            batchConfirmGate(pendingIds, setOf("a", "b"), mixed, nowOnlyB),
        )
    }
}
