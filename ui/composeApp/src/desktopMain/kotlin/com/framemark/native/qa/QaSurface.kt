package com.framemark.native.qa

/**
 * The QA/evidence surface of the desktop app: every `FM_*` launch hook, probe
 * toggle and sandbox override that exists only so a scripted harness can drive the
 * UI (`FM_WINDOW`, `FM_OPEN_CARD`, `FM_CONTROL_PROBE`, `FM_DEMO_EDITS`, …).
 *
 * WP-X3 §7: the packaged desktop product must not ship those hooks. The
 * implementation lives in `com.framemark.native.qaenv` (source set `desktopMain`,
 * filtered out by the `-PfmQaSurface=false` build parameter — see
 * `ui/composeApp/build.gradle.kts`), and this object is the only thing the rest of
 * the app knows about it. When the implementation class is not on the classpath:
 *
 *  - [installed] is false,
 *  - every accessor returns "not requested",
 *  - and `FM_WINDOW=...` / `-DFM_...` on a command line has no effect at all.
 *
 * The indirection is a `Class.forName` lookup on purpose: a direct reference would
 * make the release build fail to compile once the QA source is excluded, which is
 * exactly the coupling this is meant to remove.
 */
interface QaSurfaceHooks {
    /** `FM_WINDOW=1440x900`, or null. */
    val windowSpec: String?

    /** `FM_UNDECORATED=1`. */
    val undecorated: Boolean

    /** `FM_MOTION_LOG=1`: per-frame navigation motion probe. */
    val motionLog: Boolean

    /** `FM_G5_PROBE=1`: feel/timing probe. */
    val g5Probe: Boolean

    /** `FM_APPEARANCE=system|light|dark`: force the appearance for this run only. */
    val forcedAppearance: String?

    /** `FM_FAKE_SYSTEM_DARK_FILE=<path>`: replace the registry read with a file. */
    val fakeSystemDarkFile: String?

    /** Copies every remaining `FM_*` launch hook into [com.framemark.native.LaunchOptions]. */
    fun applyLaunchOptions()
}

object QaSurface {
    /** The implementation, or null in a build that excludes the QA source. */
    val hooks: QaSurfaceHooks? = runCatching {
        Class.forName("com.framemark.native.qaenv.EnvQaSurface")
            .getDeclaredField("INSTANCE")
            .get(null) as QaSurfaceHooks
    }.getOrNull()

    /** True when the QA hooks are compiled in (dev / QA builds). */
    val installed: Boolean get() = hooks != null

    fun windowSpec(): String? = hooks?.windowSpec

    fun undecorated(): Boolean = hooks?.undecorated == true

    fun motionLog(): Boolean = hooks?.motionLog == true

    fun g5Probe(): Boolean = hooks?.g5Probe == true

    fun forcedAppearance(): String? = hooks?.forcedAppearance

    fun fakeSystemDarkFile(): String? = hooks?.fakeSystemDarkFile

    fun applyLaunchOptions() {
        hooks?.applyLaunchOptions()
    }
}
