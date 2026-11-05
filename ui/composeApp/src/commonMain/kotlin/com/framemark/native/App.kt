package com.framemark.native

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.framemark.native.shell.DesktopLayoutState
import com.framemark.native.shell.DesktopShell
import com.framemark.native.shell.PhoneShell
import com.framemark.native.theme.AppearanceController
import com.framemark.native.theme.AppearanceEnv
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FrameMarkTheme
import com.framemark.native.theme.LocalAppearance
import com.framemark.native.theme.resolveDark
import com.framemark.native.workflow.WorkflowEnv

/**
 * Shared root composable for desktop and Android.
 *
 * Platform wiring owns the project for the lifetime of the app. A window resize
 * must never swap a disk-backed desktop project for the phone's fallback store.
 * DesktopShell adapts its layout; Android retains its real PhoneProjectStore.
 *
 * WP-T: the appearance comes from [AppearanceEnv], installed by the platform entry
 * point before the first composition (`main.kt` on desktop, `MainActivity` on
 * Android). The resolved `dark` boolean is handed to [FrameMarkTheme]; the
 * controller itself is published through [LocalAppearance] so `AppearancePicker`
 * needs no parameters. A target that forgets to install one gets a non-persisting
 * controller instead of a crash.
 */
@Composable
fun FrameMarkApp() {
    // Hoisted above the breakpoint so rail widths / collapsed state survive a
    // live resize to phone width and back (U5).
    val desktopLayout = remember { DesktopLayoutState() }
    val appearance = remember {
        AppearanceEnv.controller ?: AppearanceController().also { AppearanceEnv.controller = it }
    }
    val dark = resolveDark(appearance.settings.mode, appearance.systemDark)
    CompositionLocalProvider(LocalAppearance provides appearance) {
        FrameMarkTheme(dark = dark) {
            Surface(modifier = Modifier.fillMaxSize(), color = FmPalette.canvas) {
                if (WorkflowEnv.projectHolder != null) {
                    DesktopShell(layout = desktopLayout)
                } else {
                    PhoneShell()
                }
            }
        }
    }
}
