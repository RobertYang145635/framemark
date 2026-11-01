package com.framemark.native.ui

import androidx.compose.ui.Modifier

/**
 * Horizontal-resize cursor for the desktop rail drag strips. Android has no
 * pointer cursor, so its actual is the identity modifier.
 */
expect fun Modifier.resizeCursor(): Modifier
