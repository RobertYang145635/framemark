package com.framemark.native.shell

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneEditorLayoutPolicyTest {
    @Test
    fun compact_height_uses_the_full_parameter_page() {
        assertTrue(useFullPhoneParameterPage(479f, 1f, imeVisible = false))
        assertFalse(useFullPhoneParameterPage(480f, 1f, imeVisible = false))
    }

    @Test
    fun large_font_or_visible_keyboard_uses_the_full_parameter_page() {
        assertTrue(useFullPhoneParameterPage(700f, 1.5f, imeVisible = false))
        assertTrue(useFullPhoneParameterPage(700f, 1f, imeVisible = true))
        assertFalse(useFullPhoneParameterPage(700f, 1.49f, imeVisible = false))
    }
}
