package com.framemark.native

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.framemark.native.shell.DesktopLayoutState
import com.framemark.native.shell.DesktopShell
import com.framemark.native.shell.PhoneShell
import com.framemark.native.workflow.WorkflowEnv
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FrameMarkTheme

/**
 * Shared root composable for desktop and Android.
 *
 * Platform wiring owns the project for the lifetime of the app. A window resize
 * must never swap a disk-backed desktop project for the phone's fallback store.
 * DesktopShell adapts its layout; Android retains its real PhoneProjectStore.
 */
@Composable
fun FrameMarkApp() {
    // Hoisted above the breakpoint so rail widths / collapsed state survive a
    // live resize to phone width and back (U5).
    val desktopLayout = remember { DesktopLayoutState() }
    FrameMarkTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = FmPalette.canvas) {
            if (WorkflowEnv.projectHolder != null) {
                DesktopShell(layout = desktopLayout)
            } else {
                PhoneShell()
            }
        }
    }
}
