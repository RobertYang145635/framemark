package com.framemark.native.shell.phone

import android.content.Context
import com.framemark.native.library.RecentFrames
import com.framemark.native.library.RecentFramesStore
import com.framemark.native.workflow.AndroidWorkflowPlatform
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Android 最近使用 file: `<app data dir>/settings/recent-frames.json`.
 *
 * Data directory and atomic write follow WP-T's `theme/AndroidAppearanceStore.kt`
 * exactly (hand-off part 2 §4 WP-P1, WP-T report §2.2): the directory is the app's
 * existing private files dir (`AndroidWorkflowPlatform.dataDir` ==
 * `context.filesDir`), where `project.json`, `presets.json` and
 * `settings/appearance.json` already live, so nothing new is exposed on shared
 * storage and uninstalling the app removes it.
 *
 * The **format** is never re-implemented here: [load] / [save] delegate to
 * [RecentFrames.decode] / [RecentFrames.encode], so the corrupt-file fallback, the
 * 8-item cap and the "move to front" rule have exactly one implementation (WP-L).
 * Writes are atomic: `<name>.tmp` then
 * `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)`, with a plain `REPLACE_EXISTING`
 * retry for a filesystem that refuses the atomic move.
 */
class AndroidRecentFramesStore(dataDir: String) : RecentFramesStore {

    private val settingsFile = File(File(dataDir, "settings"), RecentFrames.FILE_NAME)

    /** Absolute path of the document, for reports and QA evidence. */
    val settingsPath: String get() = settingsFile.absolutePath

    override fun load(): List<String> {
        val text = if (settingsFile.isFile) {
            runCatching { settingsFile.readText(Charsets.UTF_8) }.getOrNull()
        } else {
            null
        }
        return RecentFrames.decode(text)
    }

    override fun save(ids: List<String>) {
        val json = RecentFrames.encode(ids)
        val directory = settingsFile.absoluteFile.parentFile ?: return
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return
        val temp = File(directory, settingsFile.name + TEMP_SUFFIX)
        try {
            val bytes = json.encodeToByteArray()
            temp.writeBytes(bytes)
            if (temp.length() != bytes.size.toLong()) {
                runCatching { temp.delete() }
                return
            }
            val source = temp.toPath()
            val target = settingsFile.toPath()
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Throwable) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Throwable) {
            runCatching { temp.delete() }
        }
    }

    companion object {
        const val TEMP_SUFFIX = ".tmp"

        /** Same directory rule as the other Android documents. */
        fun forContext(context: Context): AndroidRecentFramesStore =
            AndroidRecentFramesStore(AndroidWorkflowPlatform(context).dataDir)
    }
}
