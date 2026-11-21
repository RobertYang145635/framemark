package com.framemark.native.shell.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 自建相册的多选规则（2026-11-21）：勾选角标是**点选顺序**，到上限不能再加，
 * 取消永远可以。这三条决定了「按什么顺序导入」和「多选上限」的用户可见行为。
 */
class GallerySelectionTest {

    @Test
    fun selectionKeepsTheTapOrder() {
        val selection = GallerySelection(max = 9)
        selection.toggle("c")
        selection.toggle("a")
        selection.toggle("b")
        assertEquals(listOf("c", "a", "b"), selection.ordered())
        assertEquals(1, selection.positionOf("c"))
        assertEquals(2, selection.positionOf("a"))
        assertEquals(3, selection.positionOf("b"))
    }

    @Test
    fun tappingAgainDeselects() {
        val selection = GallerySelection(max = 3)
        selection.toggle("a")
        selection.toggle("b")
        assertTrue(selection.toggle("a"))
        assertEquals(listOf("b"), selection.ordered())
        assertEquals(0, selection.positionOf("a"))
    }

    @Test
    fun theLimitStopsNewPicksButNeverBlocksRemoval() {
        val selection = GallerySelection(max = 2)
        assertTrue(selection.toggle("a"))
        assertTrue(selection.toggle("b"))
        assertTrue(selection.isFull)
        assertFalse(selection.toggle("c"), "到上限后点新照片不加入")
        assertEquals(2, selection.count())
        assertTrue(selection.toggle("a"), "取消已选永远可以")
        assertFalse(selection.isFull)
    }

    @Test
    fun switchingAlbumsKeepsOnlyVisibleSelections() {
        val selection = GallerySelection(max = 9)
        selection.toggle("a")
        selection.toggle("b")
        selection.retainOnly(setOf("b"))
        assertEquals(listOf("b"), selection.ordered())
    }

    @Test
    fun blankIdsAreIgnored() {
        val selection = GallerySelection(max = 9)
        assertFalse(selection.toggle(""))
        assertEquals(0, selection.size)
    }
}
