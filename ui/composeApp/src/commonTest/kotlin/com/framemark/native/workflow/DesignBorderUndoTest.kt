package com.framemark.native.workflow

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P0-2（UX 审查）：换边框以前 `history.clear()` —— 换错一次既回不去，还把之前所有
 * 编辑的撤销历史一起扔了。
 *
 * 现在换边框是历史里的一步：每次切换记一条「旧边框 → 新边框」，撤销回到上一张，
 * 重做再往前走；而滑杆仍是一次拖动一条（同一控件 700 ms 内合并）。
 */
class DesignBorderUndoTest {

    private fun slider(key: String = "card-a::borderPct") = InspectorControl(
        key = key,
        controlId = "borderPct",
        param = "params.borderPct",
        label = "边框",
        kind = "slider",
        section = InspectorSection.Layout,
        type = ControlType.Slider,
        initial = ControlValue.Num(10f),
        min = 0f,
        max = 100f,
        step = 1f,
        options = emptyList(),
        multiline = false,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )

    @Test
    fun three_switches_undo_back_one_border_at_a_time() {
        val session = DesignSession()
        session.onCardChanged("stock-a")
        session.onCardChanged("stock-b")
        session.onCardChanged("stock-c")

        assertEquals(3, session.history.undoCount, "每次换边框只记一条")

        assertTrue(session.undo())
        assertEquals("stock-b", session.cardId, "撤销一次回到上一张边框")
        assertTrue(session.undo())
        assertEquals("stock-a", session.cardId)
        assertTrue(session.undo())
        assertNull(session.cardId, "第一张之前是未选边框")
        assertFalse(session.undo())
    }

    @Test
    fun redo_walks_the_same_borders_forward_again() {
        val session = DesignSession()
        session.onCardChanged("stock-a")
        session.onCardChanged("stock-b")
        session.undo()
        assertEquals("stock-a", session.cardId)

        assertTrue(session.redo())
        assertEquals("stock-b", session.cardId)
        assertFalse(session.redo())
    }

    @Test
    fun undo_of_a_switch_hands_the_old_border_back_to_the_shell() {
        val session = DesignSession()
        val restored = mutableListOf<String?>()
        session.onCardRestored = { restored += it }

        session.onCardChanged("stock-a")
        session.onCardChanged("stock-b")
        restored.clear() // the switch itself does not report anything

        session.undo()
        assertEquals(listOf<String?>("stock-a"), restored, "撤销跨了边框，外壳必须知道回到哪一张")

        session.redo()
        assertEquals(listOf<String?>("stock-a", "stock-b"), restored)
    }

    @Test
    fun a_switch_keeps_the_earlier_value_edits_in_the_stack() {
        val session = DesignSession()
        val control = slider()
        session.seed(listOf(control))

        session.onCardChanged("stock-a")
        session.set(control, 40f)
        session.onCardChanged("stock-b")
        assertEquals(3, session.history.undoCount, "换边框不再是 clear()，而是各多一步")

        // 最后一次是换边框：撤销先回到上一张边框，值编辑的步骤还在。
        assertTrue(session.undo())
        assertEquals("stock-a", session.cardId)
        assertEquals(40f, session.value(control) as Float, "换边框不会顺手改掉之前的编辑")

        // 再撤一次才是那一下滑杆。
        assertTrue(session.undo())
        assertEquals("stock-a", session.cardId)
        assertEquals(10f, session.value(control) as Float, "之前的滑杆编辑仍可撤销")
    }

    @Test
    fun a_switch_breaks_the_merge_window_so_the_next_edit_is_its_own_step() {
        val session = DesignSession()
        val control = slider()
        session.seed(listOf(control))

        session.set(control, 20f)
        session.set(control, 30f)
        assertEquals(1, session.history.undoCount, "同一次拖动仍合并成一条")

        session.onCardChanged("stock-a")
        assertEquals(2, session.history.undoCount)

        session.set(control, 40f)
        assertEquals(3, session.history.undoCount, "换边框之后的第一下编辑必须是新的一步")
    }

    @Test
    fun the_same_border_announced_twice_records_nothing() {
        val session = DesignSession()
        session.onCardChanged("stock-a")
        session.onCardChanged("stock-a")
        assertEquals(1, session.history.undoCount)
    }

    @Test
    fun losing_the_border_records_no_step() {
        // 照片被删光会走到 cardId = null：那不是用户「换边框」，不该留下一步。
        val session = DesignSession()
        session.onCardChanged("stock-a")
        session.onCardChanged(null)
        assertEquals(1, session.history.undoCount)
        assertNull(session.cardId)
    }
}
