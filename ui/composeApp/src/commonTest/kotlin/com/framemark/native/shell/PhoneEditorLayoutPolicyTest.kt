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
    fun large_font_or_short_window_uses_the_full_parameter_page() {
        assertTrue(useFullPhoneParameterPage(700f, 1.5f, imeVisible = false))
        // V06 UX feedback: the IME alone must keep the imePadding()-measured
        // preview visible, not switch to a preview-less full page.
        assertFalse(useFullPhoneParameterPage(700f, 1f, imeVisible = true))
        // If the space left above the keyboard is genuinely short, the full page
        // is still the safe layout.
        assertTrue(useFullPhoneParameterPage(400f, 1f, imeVisible = true))
        assertFalse(useFullPhoneParameterPage(700f, 1.49f, imeVisible = false))
    }
}
