package com.framemark.native.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Android actual of [SystemBarsOnDarkSurface].
 *
 * Applies on entry, re-applies when [dark] flips, and restores the theme default
 * on leave so the next screen does not inherit an override. The default is
 * `LocalFmDark` — the appearance the theme resolved to — not the raw OS night
 * flag, because the user may have chosen 浅色 on a dark device.
 */
@Composable
actual fun SystemBarsOnDarkSurface(dark: Boolean) {
    val activity = LocalContext.current.findComponentActivity()
    val themeDark = LocalFmDark.current
    DisposableEffect(activity, dark) {
        activity?.applySystemBarInk(dark)
        onDispose { activity?.applySystemBarInk(themeDark) }
    }
}

/**
 * Re-applies the edge-to-edge bar styles for [dark] (true ⇒ light icons on a dark
 * surface). Called from `MainActivity` every time the resolved appearance
 * changes, per UX-RESEARCH A-10.
 */
internal fun Activity.applySystemBarInk(dark: Boolean) {
    val activity = this as? ComponentActivity ?: return
    val onDark = SystemBarStyle.dark(Color.TRANSPARENT)
    val onLight = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    val style = if (dark) onDark else onLight
    activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}

/** Unwraps the Compose `LocalContext` down to the hosting activity. */
internal tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
