package com.framemark.native.theme

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WP-T §5.1: three modes × two platform states.
 *
 * `resolveDark` is the single decision point between the stored mode and the
 * boolean the theme renders, so every one of the six combinations is pinned here.
 */
class ThemeModeResolutionTest {

    @Test
    fun systemFollowsLightPlatform() {
        assertFalse(resolveDark(ThemeMode.System, systemDark = false))
    }

    @Test
    fun systemFollowsDarkPlatform() {
        assertTrue(resolveDark(ThemeMode.System, systemDark = true))
    }

    @Test
    fun lightIgnoresLightPlatform() {
        assertFalse(resolveDark(ThemeMode.Light, systemDark = false))
    }

    @Test
    fun lightIgnoresDarkPlatform() {
        // The point of an explicit 浅色 choice: a dark device must still render light.
        assertFalse(resolveDark(ThemeMode.Light, systemDark = true))
    }

    @Test
    fun darkIgnoresLightPlatform() {
        assertTrue(resolveDark(ThemeMode.Dark, systemDark = false))
    }

    @Test
    fun darkIgnoresDarkPlatform() {
        assertTrue(resolveDark(ThemeMode.Dark, systemDark = true))
    }
}
