package com.framemark.native.theme

import androidx.compose.runtime.Composable

/**
 * Desktop actual of [SystemBarsOnDarkSurface]: a desktop window has no system
 * bars to tint, so this is intentionally a no-op. It exists so common code (the
 * phone editor) can declare the intent without a platform check.
 */
@Composable
actual fun SystemBarsOnDarkSurface(dark: Boolean) {
    // Nothing to do: the Windows caption is handled by WindowsTitleBar.
}
