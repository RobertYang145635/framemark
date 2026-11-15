package com.framemark.native.shell

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W-01: exactly which keys a focused text field gets instead of the shell.
 *
 * The root handler runs in the preview phase, so every key it claims never
 * reaches the field. The three families below used to be claimed unconditionally:
 * `\` typed nothing (it toggled 按住看原图), the arrows stepped to another border
 * and reset the inspector to 样式, Ctrl+Z undid the design while the caret was in
 * a text row.
 */
class TextEditKeysTest {

    @Test
    fun backslash_belongs_to_the_editor_on_both_edges() {
        assertTrue(TextEditKeys.belongsToTextEditor(Key.Backslash, ctrl = false, keyDown = true))
        assertTrue(TextEditKeys.belongsToTextEditor(Key.Backslash, ctrl = false, keyDown = false))
    }

    @Test
    fun the_arrow_keys_move_the_caret_only_while_the_key_is_down() {
        for (key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)) {
            assertTrue(TextEditKeys.belongsToTextEditor(key, ctrl = false, keyDown = true), "$key down")
            assertFalse(TextEditKeys.belongsToTextEditor(key, ctrl = false, keyDown = false), "$key up")
        }
    }

    @Test
    fun undo_and_redo_belong_to_the_editor_when_it_is_focused() {
        assertTrue(TextEditKeys.belongsToTextEditor(Key.Z, ctrl = true, keyDown = true))
        assertTrue(TextEditKeys.belongsToTextEditor(Key.Z, ctrl = true, keyDown = true))
        assertTrue(TextEditKeys.belongsToTextEditor(Key.Y, ctrl = true, keyDown = true))
        // A plain Z is a character, not a shortcut; the design undo is Ctrl+Z only.
        assertFalse(TextEditKeys.belongsToTextEditor(Key.Z, ctrl = false, keyDown = true))
        assertFalse(TextEditKeys.belongsToTextEditor(Key.Y, ctrl = false, keyDown = true))
    }

    @Test
    fun global_shortcuts_do_not_yield_to_a_text_field() {
        assertFalse(TextEditKeys.belongsToTextEditor(Key.F1, ctrl = false, keyDown = true))
        assertFalse(TextEditKeys.belongsToTextEditor(Key.Escape, ctrl = false, keyDown = true))
        assertFalse(TextEditKeys.belongsToTextEditor(Key.E, ctrl = true, keyDown = true))
        assertFalse(TextEditKeys.belongsToTextEditor(Key.B, ctrl = true, keyDown = true))
        assertFalse(TextEditKeys.belongsToTextEditor(Key.P, ctrl = true, keyDown = true))
        assertFalse(TextEditKeys.belongsToTextEditor(Key.A, ctrl = true, keyDown = true))
    }
}
