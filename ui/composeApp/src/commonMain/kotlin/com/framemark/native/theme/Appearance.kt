package com.framemark.native.theme

import com.framemark.native.library.Json
import com.framemark.native.library.jsonInt
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString

/**
 * Appearance (UI V2, WP-T).
 *
 * Three appearance modes plus a photo-stage tone, persisted at
 * `<data dir>/settings/appearance.json`:
 *
 * ```json
 * {"version":1,"mode":"system","stageTone":"dark"}
 * ```
 *
 * The file is small enough to rewrite on every change, so it is written
 * atomically (temp file + atomic replace) and never merged. A missing, corrupt,
 * unreadable or unknown-version file falls back to [AppearanceSettings.DEFAULT]
 * and **is left on disk untouched** — the next user change overwrites it, so a
 * failed read never destroys whatever the user had.
 */

/** How the appearance follows the platform. */
enum class ThemeMode(val wire: String) {
    /** Follow the OS light/dark setting. The default. */
    System("system"),
    Light("light"),
    Dark("dark"),
    ;

    companion object {
        fun fromWire(value: String?): ThemeMode? = entries.firstOrNull { it.wire == value }
    }
}

/** Backdrop tone of the photo stage. The photo area itself is always dark. */
enum class StageTone(val wire: String) {
    Dark("dark"),
    Mid("mid"),
    Light("light"),
    ;

    companion object {
        fun fromWire(value: String?): StageTone? = entries.firstOrNull { it.wire == value }
    }
}

/** One persisted appearance decision. */
data class AppearanceSettings(
    val mode: ThemeMode = ThemeMode.System,
    val stageTone: StageTone = StageTone.Dark,
) {
    companion object {
        /** What a missing or unusable file resolves to: follow the system, dark stage. */
        val DEFAULT = AppearanceSettings()

        val SCHEMA_VERSION = 1
    }
}

/**
 * Turns a stored mode plus the platform's own dark flag into the boolean the
 * theme needs. Pure so it can be tested without a composition.
 */
fun resolveDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.System -> systemDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/**
 * Serialises/deserialises [AppearanceSettings].
 *
 * Never throws: [decode] answers [AppearanceSettings.DEFAULT] for anything it
 * cannot fully trust — missing text, malformed JSON, a missing or unknown
 * `version`, or a `mode` / `stageTone` value outside the enums. A single unknown
 * field invalidates the whole document on purpose: the file is a user decision,
 * not a partial record, and a half-applied appearance would be worse than the
 * default.
 */
object AppearanceSettingsCodec {
    const val MODE_KEY = "mode"
    const val STAGE_TONE_KEY = "stageTone"
    const val VERSION_KEY = "version"

    /** Exact on-disk shape: `{"version":1,"mode":"system","stageTone":"dark"}`. */
    fun encode(settings: AppearanceSettings): String =
        "{\"$VERSION_KEY\":${AppearanceSettings.SCHEMA_VERSION}," +
            "\"$MODE_KEY\":\"${settings.mode.wire}\"," +
            "\"$STAGE_TONE_KEY\":\"${settings.stageTone.wire}\"}"

    fun decode(text: CharSequence?): AppearanceSettings {
        if (text.isNullOrBlank()) return AppearanceSettings.DEFAULT
        val root = try {
            Json.parse(text.toString()).jsonMap()
        } catch (_: Throwable) {
            return AppearanceSettings.DEFAULT
        }
        if (root.jsonInt(VERSION_KEY) != AppearanceSettings.SCHEMA_VERSION) {
            return AppearanceSettings.DEFAULT
        }
        val mode = ThemeMode.fromWire(root.jsonString(MODE_KEY)) ?: return AppearanceSettings.DEFAULT
        val tone = StageTone.fromWire(root.jsonString(STAGE_TONE_KEY)) ?: return AppearanceSettings.DEFAULT
        return AppearanceSettings(mode = mode, stageTone = tone)
    }
}
