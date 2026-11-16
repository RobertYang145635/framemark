package com.framemark.native.shell

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 桌面方向键归属（UI 评审 P0-1）。
 *
 * 根节点只在「没有子控件先消费」且处于编辑舞台时把方向键当成切换对象；文本编辑、
 * 弹层、批量模式、带修饰键一律不接管。参数滑杆自己的 preview 会先吃掉方向键，
 * 所以「焦点在滑杆上按 ←/→」不会再切卡片。
 */
class DesktopArrowKeysTest {

    private fun nav(
        key: Key,
        ctrl: Boolean = false,
        shift: Boolean = false,
        editing: Boolean = false,
        sheet: Boolean = false,
        editStage: Boolean = true,
    ) = DesktopArrowKeys.navigate(
        key = key,
        ctrl = ctrl,
        shift = shift,
        textEditing = editing,
        sheetOpen = sheet,
        editStage = editStage,
    )

    @Test
    fun arrowsStepCardsOnlyOnTheEditStage() {
        assertEquals(-1, nav(Key.DirectionLeft))
        assertEquals(1, nav(Key.DirectionRight))
        assertEquals(-1, nav(Key.DirectionUp))
        assertEquals(1, nav(Key.DirectionDown))
        assertEquals(0, nav(Key.DirectionLeft, editStage = false))
        assertEquals(0, nav(Key.DirectionRight, editStage = false))
    }

    @Test
    fun textFieldsSheetsAndModifiersKeepTheArrows() {
        // 文本编辑：方向键归输入框（光标/选区），根节点不换对象。
        assertEquals(0, nav(Key.DirectionLeft, editing = true))
        assertEquals(0, nav(Key.DirectionRight, editing = true))
        // 弹层打开时不切卡片。
        assertEquals(0, nav(Key.DirectionLeft, sheet = true))
        // 带修饰键的方向键留给其它快捷键。
        assertEquals(0, nav(Key.DirectionLeft, ctrl = true))
        assertEquals(0, nav(Key.DirectionRight, shift = true))
    }

    @Test
    fun otherKeysAreNeverNavigation() {
        assertEquals(0, nav(Key.A))
        assertEquals(0, nav(Key.Spacebar))
        assertEquals(0, nav(Key.Escape))
        assertEquals(0, nav(Key.F1))
    }
}
