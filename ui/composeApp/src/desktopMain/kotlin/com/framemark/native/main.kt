package com.framemark.native

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.runtime.remember
import androidx.compose.ui.window.rememberWindowState
import com.framemark.native.engine.DesktopNativeRender
import com.framemark.native.shell.MotionProbe
import com.framemark.native.workflow.DesktopProjectController
import com.framemark.native.workflow.DesktopProjectRuntime
import com.framemark.native.workflow.DesktopWorkflowPlatform
import com.framemark.native.workflow.WorkflowEnv

/**
 * Desktop entry point.
 *
 * Env hooks (evidence capture only, no effect on a normal launch):
 *  - FM_WINDOW=390x844  → initial window size in dp
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
 *  - FM_DATA_DIR / FM_EXPORT_DIR → redirect preset + sample files (evidence only)
 */
fun main() = application {
    fun startupElapsedMs(): Long =
        ProcessHandle.current().info().startInstant()
            .map { System.currentTimeMillis() - it.toEpochMilli() }
            .orElse(-1L)

    val spec = System.getenv("FM_WINDOW")
        ?.split("x")
        ?.mapNotNull { it.toFloatOrNull() }
    val windowSize = if (spec != null && spec.size == 2) {
        DpSize(spec[0].dp, spec[1].dp)
    } else {
        DpSize(1280.dp, 800.dp)
    }

    // The body of `application {}` is a composable lambda, so anything written here
    // re-runs on every recomposition. Loading the project reads observable state, so
    // a reload invalidates this very scope and the app spins: thousands of project
    // reloads and engine re-creations per minute. One-time wiring belongs in remember.
    remember {
        println("FM_START_MAIN elapsedMs=${startupElapsedMs()}")
        MotionProbe.enabled = System.getenv("FM_MOTION_LOG") == "1"
        FeelProbe.enabled = System.getenv("FM_G5_PROBE") == "1"

        WorkflowEnv.platform = DesktopWorkflowPlatform()
        com.framemark.native.inspector.RegistryPorts.provider = com.framemark.native.engine.DesktopRegistryProvider(
            com.framemark.native.engine.RegistrySource(com.framemark.native.engine.RegistrySource.devWorkspaceRoot()),
        )
        println("FM_START_PLATFORM elapsedMs=${startupElapsedMs()}")
        val projects = DesktopProjectController(WorkflowEnv.platform!!.dataDir).also { it.load() }
        WorkflowEnv.projectHolder = projects
        println("FM_START_PROJECT photos=${projects.photos.size} active=${projects.activePhotoId ?: "-"} error=${projects.loadError ?: "-"}")
        // X1: the real renderer. A missing manifest/binary leaves the placeholder
        // workflow in place and says so once on stdout.
        WorkflowEnv.nativeRender = runCatching { DesktopNativeRender() }
            .onFailure { println("FM_ENGINE_UNAVAILABLE ${it.message}") }
            .getOrNull()
        println("FM_START_ENGINE elapsedMs=${startupElapsedMs()}")

        LaunchOptions.openCardId = System.getenv("FM_OPEN_CARD")?.takeIf { it.isNotBlank() }
        LaunchOptions.editorItem = System.getenv("FM_EDITOR_ITEM")?.toIntOrNull()
        LaunchOptions.openPresetSheet = System.getenv("FM_OPEN_PRESETS") == "1"
        LaunchOptions.openExportSheet = System.getenv("FM_OPEN_EXPORT") == "1"
        LaunchOptions.runSampleExport = System.getenv("FM_RUN_EXPORT") == "1"
        LaunchOptions.openBatch = System.getenv("FM_OPEN_BATCH") == "1"
        LaunchOptions.batchCount = System.getenv("FM_BATCH_COUNT")?.toIntOrNull() ?: LaunchOptions.batchCount
        LaunchOptions.batchReset = System.getenv("FM_BATCH_RESET") == "1"
        LaunchOptions.samplePhoto = System.getenv("FM_SAMPLE_PHOTO") == "1"
        LaunchOptions.libraryQuery = System.getenv("FM_LIB_QUERY")?.takeIf { it.isNotBlank() }
        LaunchOptions.openTab = System.getenv("FM_OPEN_TAB")?.takeIf { it.isNotBlank() }
        LaunchOptions.demoEdits = System.getenv("FM_DEMO_EDITS")?.toIntOrNull() ?: 0
        LaunchOptions.undoSteps = System.getenv("FM_UNDO")?.toIntOrNull() ?: 0
        LaunchOptions.autoSavePreset = System.getenv("FM_AUTO_SAVE_PRESET")?.takeIf { it.isNotBlank() }
        LaunchOptions.exportFormat = System.getenv("FM_EXPORT_FORMAT")?.takeIf { it.isNotBlank() }
        LaunchOptions.exportWebpLossless = System.getenv("FM_EXPORT_WEBP_LOSSLESS") != "0"
        LaunchOptions.exportLongEdge = System.getenv("FM_EXPORT_EDGE")?.toIntOrNull()
        LaunchOptions.controlProbe = System.getenv("FM_CONTROL_PROBE") == "1"
        Unit
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
        undecorated = System.getenv("FM_UNDECORATED") == "1",
    ) {
        FrameMarkApp()
    }
}
