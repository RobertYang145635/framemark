package com.framemark.native

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.window.rememberWindowState
import com.framemark.native.engine.DesktopNativeRender
import com.framemark.native.shell.DESKTOP_WINDOW_DEFAULT_HEIGHT
import com.framemark.native.shell.DESKTOP_WINDOW_DEFAULT_WIDTH
import com.framemark.native.qa.QaSurface
import com.framemark.native.shell.MotionProbe
import com.framemark.native.shell.fitWindowToScreen
import com.framemark.native.theme.AppearanceController
import com.framemark.native.theme.AppearanceEnv
import com.framemark.native.theme.DesktopAppearanceEffects
import com.framemark.native.theme.DesktopAppearanceStore
import com.framemark.native.theme.FmColors
import com.framemark.native.theme.NoPersistenceAppearanceStore
import com.framemark.native.theme.ThemeMode
import com.framemark.native.theme.WindowsSystemTheme
import com.framemark.native.workflow.DesktopProjectController
import com.framemark.native.workflow.DesktopProjectRuntime
import com.framemark.native.workflow.DesktopWorkflowPlatform
import com.framemark.native.workflow.WorkflowEnv
import java.awt.GraphicsEnvironment

/**
 * Desktop entry point.
 *
 * Env hooks (evidence capture only, no effect on a normal launch):
 *  - FM_WINDOW=390x844  → initial window size in dp (still fitted to the screen)
 *  - FM_UNDECORATED=1   → no title bar, so the client area is exactly FM_WINDOW
 *  - FM_MOTION_LOG=1    → per-frame nav motion probe on stdout
 *  - FM_OPEN_CARD=<id>  → open this library card in the editor (U3/U4 capture)
 *  - FM_EDITOR_ITEM=<n> → scroll the editor LazyColumn to item n (U3 capture)
 *  - FM_DEMO_EDITS=<n>  → apply n control edits after the model loads (U4)
 *  - FM_UNDO=<n>        → undo n steps right after the demo edits (U4)
 *  - FM_AUTO_SAVE_PRESET=<name> → save a preset after the demo edits (U4)
 *  - FM_OPEN_PRESETS=1  → open the preset sheet (U4)
 *  - FM_OPEN_EXPORT=1   → open the export sheet (U4)
 *  - FM_RUN_EXPORT=1    → also generate the sample export (U4)
 *  - FM_OPEN_BATCH=1    → open the batch tab with a seeded demo queue (U4)
 *  - FM_APPEARANCE=system|light|dark → force the appearance for this run only
 *    (never written to `settings\appearance.json`)
 *  - FM_FAKE_SYSTEM_DARK_FILE=<path> → replace the Win32 registry read with a file
 *    (`1` = dark, `0` = light), polled every 5 s; sandbox QA only
 *  - FM_DATA_DIR / FM_EXPORT_DIR → redirect preset + sample files (evidence only)
 */
fun main() = application {
    fun startupElapsedMs(): Long =
        ProcessHandle.current().info().startInstant()
            .map { System.currentTimeMillis() - it.toEpochMilli() }
            .orElse(-1L)

    // V06: a window opened larger than the work area put the right inspector off
    // screen (external report). The initial size is fitted to the AWT work area
    // (taskbar excluded) instead of raising the three-column minimum width, so a
    // 200 % display with a small logical work area still opens fully visible.
    // Computed inside `remember`: the `application {}` body recomposes, and this
    // probe must log exactly once per launch.
    val windowSize = remember {
        val spec = QaSurface.windowSpec()
            ?.split("x")
            ?.mapNotNull { it.toFloatOrNull() }
        val desiredWidth = spec?.getOrNull(0) ?: DESKTOP_WINDOW_DEFAULT_WIDTH
        val desiredHeight = spec?.getOrNull(1) ?: DESKTOP_WINDOW_DEFAULT_HEIGHT
        val workArea = desktopWorkAreaDp()
        val fit = fitWindowToScreen(
            desiredWidth = desiredWidth,
            desiredHeight = desiredHeight,
            screenWidth = workArea?.first ?: 0f,
            screenHeight = workArea?.second ?: 0f,
        )
        println(
            "FM_WINDOW_FIT desired=${dpLabel(desiredWidth)}x${dpLabel(desiredHeight)} " +
                "screen=${workArea?.let { "${dpLabel(it.first)}x${dpLabel(it.second)}" } ?: "-"} " +
                "applied=${dpLabel(fit.width)}x${dpLabel(fit.height)}",
        )
        DpSize(fit.width.dp, fit.height.dp)
    }

    // The body of `application {}` is a composable lambda, so anything written here
    // re-runs on every recomposition. Loading the project reads observable state, so
    // a reload invalidates this very scope and the app spins: thousands of project
    // reloads and engine re-creations per minute. One-time wiring belongs in remember.
    remember {
        println("FM_START_MAIN elapsedMs=${startupElapsedMs()}")
        MotionProbe.enabled = QaSurface.motionLog()
        FeelProbe.enabled = QaSurface.g5Probe()

        WorkflowEnv.platform = DesktopWorkflowPlatform()
        // WP-D1 phase 2: install the desktop 最近使用 file store before the first
        // composition. Same seam as Android's MainActivity; the UI reads
        // `RecentFramesEnv.store` and falls back to an in-memory store.
        com.framemark.native.library.RecentFramesEnv.store =
            com.framemark.native.shell.desktop.DesktopRecentFramesStore()
        com.framemark.native.workflow.UserLogoEnv.repository =
            runCatching { com.framemark.native.userlogo.DesktopUserLogoRepository() }
                .onFailure { println("FM_USER_LOGO_UNAVAILABLE ${it.message}") }
                .getOrNull()
        com.framemark.native.workflow.UserLogoEnv.picker =
            if (com.framemark.native.workflow.UserLogoEnv.repository != null) {
                { com.framemark.native.userlogo.DesktopUserLogoPicker.open() }
            } else {
                null
            }
        com.framemark.native.inspector.RegistryPorts.provider = com.framemark.native.engine.DesktopRegistryProvider(
            com.framemark.native.engine.RegistrySource(com.framemark.native.engine.RegistrySource.devWorkspaceRoot()),
        )
        println("FM_START_PLATFORM elapsedMs=${startupElapsedMs()}")
        val projects = DesktopProjectController(WorkflowEnv.platform!!.dataDir).also { it.load() }
        WorkflowEnv.projectHolder = projects
        println("FM_START_PROJECT photos=${projects.photos.size} active=${projects.activePhotoId ?: "-"} error=${projects.loadError ?: "-"}")
        // X1: the real renderer. A missing manifest/binary leaves the placeholder
        // workflow in place and says so once on stdout.
        val desktopRender = runCatching { DesktopNativeRender() }
            .onFailure { println("FM_ENGINE_UNAVAILABLE ${it.message}") }
            .getOrNull()
        WorkflowEnv.nativeRender = desktopRender
        com.framemark.native.workflow.UserLogoSupport.probe =
            desktopRender?.let { render -> { id: String -> render.supportsUserLogo(id) } }
        println("FM_START_ENGINE elapsedMs=${startupElapsedMs()}")

        // WP-X3 §7: every `FM_*` launch hook moved to the QA-only surface
        // (`com.framemark.native.qaenv`, excluded from release builds). In a packaged
        // product this is a no-op: QaSurface.hooks is null because the class is absent.
        QaSurface.applyLaunchOptions()
        Unit
    }

    // WP-T (T-1): the system flag is read *here*, synchronously, before the
    // controller exists and before anything is composed. Seeding the controller
    // with it means "follow the system" is already correct on the first frame; a
    // controller created with the default `initialSystemDark = false` painted one
    // light frame and only flipped to dark when an effect ran afterwards. The
    // reader is the same WindowsSystemTheme the effect keeps polling, so
    // FM_FAKE_SYSTEM_DARK_FILE still overrides the registry.
    val systemTheme = remember { WindowsSystemTheme() }

    // WP-T: appearance is resolved before the first composition. FM_APPEARANCE
    // forces a mode for this run only and is never written to the settings file.
    val appearance = remember {
        val forcedMode = when (QaSurface.forcedAppearance()?.trim()?.lowercase()) {
            "system" -> ThemeMode.System
            "light" -> ThemeMode.Light
            "dark" -> ThemeMode.Dark
            else -> null
        }
        val store = runCatching { DesktopAppearanceStore() }
            .onFailure { println("FM_APPEARANCE_STORE_UNAVAILABLE ${it.message}") }
            .getOrElse { NoPersistenceAppearanceStore }
        val initialSystemDark = systemTheme.isDark()
        AppearanceController(
            store = store,
            forcedMode = forcedMode,
            initialSystemDark = initialSystemDark,
        ).also {
            AppearanceEnv.controller = it
            println("FM_APPEARANCE_FORCED ${forcedMode?.wire ?: "-"} file=${it.settingsPath}")
            // Evidence for T-1: this line is printed by the `application {}` body,
            // i.e. before the window content is composed and before the first frame.
            println(
                "FM_APPEARANCE_PRECOMPOSE systemDark=$initialSystemDark dark=${it.dark} " +
                    "mode=${it.settings.mode.wire} source=${systemTheme.lastSource}",
            )
        }
    }

    Window(
        onCloseRequest = {
            DesktopProjectRuntime.flush()
            exitApplication()
        },
        title = "FrameMark",
        state = rememberWindowState(
            size = windowSize,
            position = WindowPosition(Alignment.Center),
        ),
        undecorated = QaSurface.undecorated(),
    ) {
        // T-1: AWT paints the frame before Compose paints its first frame, and a
        // plain JFrame starts on the Metal content-pane grey. Paint the window with
        // the canvas the resolved appearance is about to render; this call happens
        // while the window content is composed, which is before Compose Desktop
        // makes the window visible.
        applyWindowBackground(window, dark = appearance.dark)
        DesktopAppearanceEffects(appearance, window, systemTheme)
        FrameMarkApp()
    }
}

/**
 * Paints the AWT frame (and its content pane) with the canvas of the resolved
 * appearance, so the gap between "window visible" and "first Compose frame"
 * cannot show the light `JFrame` default. It is a no-op when the colour already
 * matches, so a live theme switch only repaints once.
 */
private fun applyWindowBackground(window: ComposeWindow, dark: Boolean) {
    val canvas = if (dark) FmColors.Dark.canvas else FmColors.Light.canvas
    val awt = java.awt.Color(canvas.toArgb() and 0xFFFFFF)
    if (window.background == awt) return
    runCatching {
        window.background = awt
        window.contentPane?.background = awt
        window.repaint()
        println("FM_APPEARANCE_WINDOW_BG dark=$dark argb=#%06X".format(canvas.toArgb() and 0xFFFFFF))
    }.onFailure { println("FM_APPEARANCE_WINDOW_BG_FAIL ${it.message}") }
}

/**
 * AWT work area (the screen minus taskbar) in the same units Compose sizes the
 * window in.
 *
 * Compose Desktop sizes windows in AWT user-space coordinates and AWT already
 * reports user space — a 200 % display reports its logical half of the physical
 * pixels — so the rectangle is used as-is. Dividing by
 * `defaultTransform.scaleX` a second time would halve the budget on exactly the
 * high-DPI machines this fit targets and shrink the window instead of saving it.
 *
 * Returns null when the bounds cannot be read or are empty, and the caller then
 * keeps the desired size.
 */
private fun desktopWorkAreaDp(): Pair<Float, Float>? = runCatching {
    val bounds: java.awt.Rectangle? = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
        null
    } else {
        bounds.width.toFloat() to bounds.height.toFloat()
    }
}.getOrNull()

/** Log-friendly dp value: whole numbers stay whole. */
private fun dpLabel(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)
