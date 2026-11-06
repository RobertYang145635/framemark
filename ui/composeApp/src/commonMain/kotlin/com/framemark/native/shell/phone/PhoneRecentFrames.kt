package com.framemark.native.shell.phone

import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.RecentFrames
import com.framemark.native.library.RecentFramesStore

/**
 * 最近使用 wiring for the phone sheet (report §7.5, WP-L `RecentFrames`).
 *
 * `RecentFrames` owns the format (`{"version":1,"cards":[…]}`), the 8-item cap,
 * the move-to-front rule and the corrupt-file fallback; this file only chooses
 * where the file lives and which catalog is used to drop stale ids.
 *
 * **Wiring point (handed to the supervisor after the merge):** commonMain has no
 * platform seam yet, so the phone sheet currently records into an
 * `InMemoryRecentFramesStore` (see `PhoneEditorScreen`). Once D1's
 * `library/RecentFramesEnv.kt` (`object RecentFramesEnv { var store … }`) lands,
 * `MainActivity` installs `AndroidRecentFramesStore.forContext(applicationContext)`
 * there and the editor reads `RecentFramesEnv.store` instead of the in-memory
 * instance. Nothing else in this file changes.
 */

/**
 * The file the phone writes: `<app data dir>/settings/recent-frames.json`, the
 * same `settings/` directory and naming rule as WP-T's `appearance.json`.
 * The separator of [dataDir] is kept, so a Windows path never comes back mixed.
 */
fun phoneRecentFramesPath(dataDir: String): String {
    val trimmed = dataDir.trimEnd('/', '\\')
    val separator = if (trimmed.contains('\\') && !trimmed.contains('/')) "\\" else "/"
    return trimmed + separator + "settings" + separator + RecentFrames.FILE_NAME
}

/** Reads the list once; a missing or damaged file yields an empty list. */
fun phoneLoadRecentFrames(store: RecentFramesStore?, index: LibraryIndex?): List<String> {
    if (store == null) return emptyList()
    return if (index != null) {
        RecentFrames.load(store, index)
    } else {
        RecentFrames.load(store, known = null)
    }
}

/**
 * Records one border pick (report §7.5 "每次选卡都调用 RecentFrames.record") and
 * returns the new list so the 常用 group updates without a reload.
 */
fun phoneRecordRecentFrame(
    store: RecentFramesStore?,
    index: LibraryIndex?,
    cardId: String,
): List<String> {
    if (store == null || cardId.isBlank()) return emptyList()
    return if (index != null) {
        RecentFrames.record(store, cardId, index)
    } else {
        RecentFrames.record(store, cardId, known = null)
    }
}
