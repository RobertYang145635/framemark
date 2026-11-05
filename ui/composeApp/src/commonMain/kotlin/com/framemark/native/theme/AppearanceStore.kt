package com.framemark.native.theme

/**
 * Where [AppearanceSettings] live.
 *
 * Both JVM targets already have an app data directory — the desktop one from
 * `DesktopWorkflowPlatform.defaultDataDir()` (`FM_DATA_DIR`, else
 * `%LOCALAPPDATA%\FrameMarkNative`) and the Android one from
 * `WorkflowPlatform.dataDir` (`context.filesDir`), which is where `project.json`
 * and `presets.json` already sit. The appearance file goes into `settings/`
 * inside that same directory so it travels with the rest of the user's state and
 * needs no second path rule.
 *
 * Implementations must make [save] atomic (write `<path>.tmp`, then replace) and
 * must never throw on a missing or unreadable file: a broken appearance file
 * must not be able to stop the app from starting.
 */
interface AppearanceStore {
    /** Absolute path of the settings file, for logs and evidence. */
    val settingsPath: String

    /** Raw JSON text, or `null` when the file does not exist / cannot be read. */
    fun load(): String?

    /** True only when the whole document reached the file. */
    fun save(json: String): Boolean
}

/** [AppearanceStore] that keeps nothing (tests, previews, a read-only run). */
object NoPersistenceAppearanceStore : AppearanceStore {
    override val settingsPath: String = "<none>"

    override fun load(): String? = null

    override fun save(json: String): Boolean = true
}
