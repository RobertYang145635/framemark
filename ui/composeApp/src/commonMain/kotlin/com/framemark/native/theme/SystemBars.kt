package com.framemark.native.theme

import androidx.compose.runtime.Composable

/**
 * Tells the platform that the surface **behind the system bars** is dark, so the
 * status / navigation bar icons switch to light ink (and back).
 *
 * Call it from a screen whose top edge is a dark surface — the phone editor's
 * dark top bar is the case this was added for (WP-P1). A screen that never calls
 * it keeps the theme default: `MainActivity` re-applies the edge-to-edge styles
 * whenever the resolved appearance changes, which is exactly UX-RESEARCH A-10
 * ("do not wait for the Activity to be recreated").
 *
 * Desktop has no system bars; the desktop actual is a no-op.
 *
 * @param dark true when the content behind the bars is dark (light icons).
 */
@Composable
expect fun SystemBarsOnDarkSurface(dark: Boolean)
