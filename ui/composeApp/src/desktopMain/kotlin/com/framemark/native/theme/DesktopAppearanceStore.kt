package com.framemark.native.theme

import com.framemark.native.workflow.DesktopWorkflowPlatform
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop appearance file: `<data dir>/settings/appearance.json`.
 *
 * The data directory is exactly the one the rest of the desktop app already uses
 * — `DesktopWorkflowPlatform.defaultDataDir()`, i.e. `FM_DATA_DIR`, else
 * `%LOCALAPPDATA%\FrameMarkNative`, else `%USERPROFILE%\.framemark-native` — so
 * `project.json`, `presets.json` and the appearance file never diverge. That
 * lookup is only read here; nothing in `workflow\` changes.
 *
 * Writes mirror `JvmProductFileSystem.rename`: full text into `<name>.tmp`, a byte
 * count check, then `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)` with a plain
 * `REPLACE_EXISTING` retry for a volume that refuses the atomic move. `File.renameTo`
 * would leave the old file in place on Windows.
 */
class DesktopAppearanceStore(
    private val settingsFile: File = defaultSettingsFile(),
) : AppearanceStore {

    override val settingsPath: String get() = settingsFile.absolutePath

    /** Transfer-encoding free: the app only ever writes UTF-8. */
    private val charset = Charsets.UTF_8

    override fun load(): String? {
        if (!settingsFile.isFile) return null
        return runCatching { settingsFile.readText(charset) }.getOrNull()
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
        /** Sibling temp name; a leftover is proof a save was interrupted. */
        const val TEMP_SUFFIX = ".tmp"

        /** `settings` under the app data directory — the same rule as the other documents. */
        fun defaultSettingsFile(): File =
            File(File(DesktopWorkflowPlatform.defaultDataDir(), "settings"), "appearance.json")
    }
}
