package com.framemark.native.qaenv

import com.framemark.native.LaunchOptions
import com.framemark.native.qa.QaSurfaceHooks

/**
 * Environment-backed QA surface — **not part of the packaged product**.
 *
 * Every `FM_*` launch hook that used to sit in `main.kt` lives here now, unchanged
 * in behaviour: the dev/QA run (`tools\run-desktop.ps1`) sets the variables and the
 * hooks behave exactly as before. A release package is built with
 * `-PfmQaSurface=false`, which excludes the `com/framemark/native/qaenv` and
 * `com/framemark/native/verify` packages from the compiled output, so the packaged
 * jar contains neither this class nor any of these literals, and `QaSurface.hooks`
 * resolves to null (see `qa/QaSurface.kt`).
 *
 * To prove it: `unzip -l <app jar> | grep -c qaenv` is 0 in the release jar and 1 in
 * the QA jar; `evidence\X3\release-surface\` holds both listings.
 */
object EnvQaSurface : QaSurfaceHooks {

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun flag(name: String): Boolean = System.getenv(name) == "1"

    override val windowSpec: String? get() = env("FM_WINDOW")

    override val undecorated: Boolean get() = flag("FM_UNDECORATED")

    override val motionLog: Boolean get() = flag("FM_MOTION_LOG")

    override val g5Probe: Boolean get() = flag("FM_G5_PROBE")

    override val forcedAppearance: String? get() = env("FM_APPEARANCE")

    override val fakeSystemDarkFile: String? get() = env("FM_FAKE_SYSTEM_DARK_FILE")

    override fun applyLaunchOptions() {
        LaunchOptions.openCardId = env("FM_OPEN_CARD")
        LaunchOptions.editorItem = env("FM_EDITOR_ITEM")?.toIntOrNull()
        LaunchOptions.openPresetSheet = flag("FM_OPEN_PRESETS")
        LaunchOptions.openExportSheet = flag("FM_OPEN_EXPORT")
        LaunchOptions.logoPage = env("FM_LOGO_PAGE")
        LaunchOptions.logoBrowseBrand = env("FM_LOGO_BROWSE")
        LaunchOptions.logoPickVersion = env("FM_LOGO_PICK")
        LaunchOptions.logoAfterBack = flag("FM_LOGO_AFTER_BACK")
        LaunchOptions.runSampleExport = flag("FM_RUN_EXPORT")
        LaunchOptions.openBatch = flag("FM_OPEN_BATCH")
        LaunchOptions.batchCount = env("FM_BATCH_COUNT")?.toIntOrNull() ?: LaunchOptions.batchCount
        LaunchOptions.batchReset = flag("FM_BATCH_RESET")
        LaunchOptions.samplePhoto = flag("FM_SAMPLE_PHOTO")
        LaunchOptions.libraryQuery = env("FM_LIB_QUERY")
        LaunchOptions.openTab = env("FM_OPEN_TAB")
        LaunchOptions.demoEdits = env("FM_DEMO_EDITS")?.toIntOrNull() ?: 0
        LaunchOptions.undoSteps = env("FM_UNDO")?.toIntOrNull() ?: 0
        LaunchOptions.autoSavePreset = env("FM_AUTO_SAVE_PRESET")
        LaunchOptions.exportFormat = env("FM_EXPORT_FORMAT")
        LaunchOptions.exportWebpLossless = System.getenv("FM_EXPORT_WEBP_LOSSLESS") != "0"
        LaunchOptions.exportLongEdge = env("FM_EXPORT_EDGE")?.toIntOrNull()
        LaunchOptions.controlProbe = flag("FM_CONTROL_PROBE")
    }
}
