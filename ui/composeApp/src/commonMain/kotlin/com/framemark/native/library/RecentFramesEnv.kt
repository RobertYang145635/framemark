package com.framemark.native.library

/**
 * Process-wide 最近使用 store seam (WP-D1 phase 2, supervisor 2026-11-06).
 *
 * WP-L deliberately does not decide where `recent-frames.json` lives. Each platform
 * entry point installs its file-backed [RecentFramesStore] here **before** the first
 * composition:
 *  - desktop: `DesktopRecentFramesStore` (`<data dir>/settings/recent-frames.json`);
 *  - Android: `AndroidRecentFramesStore` (same file under `context.filesDir`).
 *
 * Shell/UI code reads `RecentFramesEnv.store` and falls back to WP-L's in-memory
 * implementation when no entry point installed one (tests, previews, phone widgets
 * used outside the app entry). Keeping the install in the platform entry means no
 * commonMain code needs `java.nio`, and both platforms share one file format.
 */
object RecentFramesEnv {
    var store: RecentFramesStore? = null
}
