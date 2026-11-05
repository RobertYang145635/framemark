package com.framemark.native.theme

import android.content.Context
import com.framemark.native.workflow.AndroidWorkflowPlatform
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Android appearance file: `<data dir>/settings/appearance.json`.
 *
 * The data directory is the app's existing private files dir, i.e. exactly
 * `AndroidWorkflowPlatform.dataDir` == `context.filesDir.absolutePath`, which
 * already holds `project.json` and `presets.json`. The appearance file sits next
 * to them in `settings/` — no second path rule, and it travels with the app's
 * private data (uninstalled with it, never on shared storage).
 *
 * Writes are atomic: `<name>.tmp` then `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)`
 * (`java.nio.file` is API 26, the module's `minSdk` is 26), with a plain
 * `REPLACE_EXISTING` retry for a filesystem that refuses the atomic move.
 */
class AndroidAppearanceStore(dataDir: String) : AppearanceStore {

    private val settingsFile = File(File(dataDir, "settings"), "appearance.json")

    override val settingsPath: String get() = settingsFile.absolutePath

    override fun load(): String? {
        if (!settingsFile.isFile) return null
        return runCatching { settingsFile.readText(Charsets.UTF_8) }.getOrNull()
    }

    override fun save(json: String): Boolean {
        val directory = settingsFile.absoluteFile.parentFile ?: return false
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return false
        val temp = File(directory, settingsFile.name + TEMP_SUFFIX)
        return try {
            val bytes = json.encodeToByteArray()
            temp.writeBytes(bytes)
            if (temp.length() != bytes.size.toLong()) {
                runCatching { temp.delete() }
                return false
            }
            val source = temp.toPath()
            val target = settingsFile.toPath()
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Throwable) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (_: Throwable) {
            runCatching { temp.delete() }
            false
        }
    }

    companion object {
        const val TEMP_SUFFIX = ".tmp"

        /** Same directory rule as the other Android documents. */
        fun forContext(context: Context): AndroidAppearanceStore =
            AndroidAppearanceStore(AndroidWorkflowPlatform(context).dataDir)
    }
}
