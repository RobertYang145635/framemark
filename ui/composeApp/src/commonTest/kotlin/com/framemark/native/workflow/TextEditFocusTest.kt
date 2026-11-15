package com.framemark.native.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W-01: the registry the shell's key handler consults.
 *
 * Only "is a text field focused" matters to the handler, but the bookkeeping has
 * two failure modes that show up as a dead keyboard later: a field removed from
 * the composition while focused (the shell would yield shortcuts forever) and two
 * fields reporting focus in either order (the count must not go negative).
 */
class TextEditFocusTest {

    @Test
    fun nothing_is_editing_until_a_field_joins() {
        val focus = TextEditFocus()
        assertFalse(focus.isEditing)
        assertEquals(0, focus.focusedCount)

        val field = Any()
        focus.setFocused(field, true)
        assertTrue(focus.isEditing)
        assertEquals(1, focus.focusedCount)

        focus.setFocused(field, false)
        assertFalse(focus.isEditing)
        assertEquals(0, focus.focusedCount)
    }

    @Test
    fun a_field_disposed_while_focused_is_released() {
        val focus = TextEditFocus()
        val field = Any()
        focus.setFocused(field, true)
        focus.release(field)
        assertFalse(focus.isEditing, "a disposed field must not keep the shell yielding keys")
    }

    @Test
    fun two_fields_cannot_leave_the_registry_stuck() {
        val focus = TextEditFocus()
        val first = Any()
        val second = Any()
        // Focus moving from one field to another may report either order.
        focus.setFocused(first, true)
        focus.setFocused(second, true)
        focus.setFocused(first, false)
        assertTrue(focus.isEditing, "the second field is still focused")
        focus.setFocused(second, false)
        assertFalse(focus.isEditing)

        // A release of something that never joined does not change the state.
        focus.setFocused(first, true)
        focus.release(Any())
        assertTrue(focus.isEditing)
    }

    @Test
    fun repeated_focus_callbacks_are_idempotent() {
        val focus = TextEditFocus()
        val field = Any()
        focus.setFocused(field, true)
        focus.setFocused(field, true)
        assertEquals(1, focus.focusedCount)
        focus.setFocused(field, false)
        focus.setFocused(field, false)
        assertEquals(0, focus.focusedCount)
        assertFalse(focus.isEditing)
    }
}
