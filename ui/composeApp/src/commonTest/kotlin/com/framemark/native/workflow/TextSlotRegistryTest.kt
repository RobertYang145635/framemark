package com.framemark.native.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract of the shell-level holder the desktop stage feeds and the 文字 tab
 * reads. A snapshot belongs to one card/photo pair; these tests pin the two
 * behaviours that keep a stale pair from showing up as the current one.
 */
class TextSlotRegistryTest {

    private fun slot(
        id: String,
        key: String = "hand:test:$id",
        x: Double = 10.0,
        y: Double = 10.0,
    ) = TextSlot(
        id = id,
        text = "row $id",
        x = x,
        y = y,
        w = 120.0,
        h = 24.0,
        fontSize = 32.0,
        baseline = null,
        lines = 1,
        key = key,
        stable = true,
        located = true,
        raw = "row $id",
    )

    @Test
    fun updatePublishesGeometryAndRowsOfTheLatestReport() {
        val registry = TextSlotRegistry()
        assertTrue(registry.isEmpty, "a fresh registry holds nothing")
        assertEquals(0, registry.width)
        assertEquals(0, registry.height)

        val rows = listOf(slot("a"), slot("b", x = 760.0, y = 560.0))
        registry.update(900, 600, rows)

        assertEquals(900, registry.width)
        assertEquals(600, registry.height)
        assertEquals(rows, registry.slots)
        assertFalse(registry.isEmpty)
    }

    @Test
    fun aNewReportReplacesThePreviousOneInsteadOfMerging() {
        val registry = TextSlotRegistry()
        registry.update(900, 600, listOf(slot("old")))
        val next = listOf(slot("new"))
        registry.update(1200, 800, next)

        assertEquals(next, registry.slots, "a re-render can drop rows; nothing stale survives")
        assertEquals(1200, registry.width)
        assertEquals(800, registry.height)
    }

    @Test
    fun clearDropsTheSnapshotSoTheNextCardCannotInheritTheseRows() {
        val registry = TextSlotRegistry()
        registry.update(900, 600, listOf(slot("card-a")))
        // The shell clears on every card/photo switch...
        registry.clear()

        assertTrue(registry.isEmpty)
        assertEquals(0, registry.slots.size)
        assertEquals(0, registry.width)
        assertEquals(0, registry.height)

        // ...and the next card's report starts from scratch.
        registry.update(640, 480, listOf(slot("card-b")))
        assertEquals(listOf("card-b"), registry.slots.map { it.id })
        assertEquals(640, registry.width)
    }

    @Test
    fun aFailedRenderReportEmptiesTheSnapshot() {
        // NativeStagePreview reports (0, 0, emptyList()) when the engine is absent
        // or the render fails: the panel must fall back to "no rows" instead of
        // keeping the previous frame's coordinates.
        val registry = TextSlotRegistry()
        registry.update(900, 600, listOf(slot("a")))
        registry.update(0, 0, emptyList())

        assertTrue(registry.isEmpty)
        assertEquals(0, registry.width)
        assertEquals(0, registry.height)
    }

    @Test
    fun negativeGeometryIsClampedToZero() {
        val registry = TextSlotRegistry()
        registry.update(-1, -600, listOf(slot("a")))
        assertEquals(0, registry.width)
        assertEquals(0, registry.height)
    }

    @Test
    fun groupingReadsOnlyTheCurrentSnapshot() {
        val registry = TextSlotRegistry()
        registry.update(
            900,
            600,
            listOf(slot("tl", x = 10.0, y = 10.0), slot("br", x = 760.0, y = 560.0)),
        )
        assertEquals(
            listOf("左上", "右下"),
            groupTextSlots(registry.slots, registry.width, registry.height).map { it.label },
        )

        registry.clear()
        assertTrue(
            groupTextSlots(registry.slots, registry.width, registry.height).isEmpty(),
            "a cleared registry yields no groups, so the panel renders no row blocks",
        )
    }
}
