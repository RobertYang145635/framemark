package com.framemark.native.theme

import com.framemark.native.library.Json
import com.framemark.native.library.jsonInt
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString

/**
 * Appearance (UI V2, WP-T + 配色方案 2026-11-15).
 *
 * 界面配色 + 明暗模式 + 舞台色，持久化在 `<data dir>/settings/appearance.json`：
 *
 * ```json
 * {"version":2,"palette":"forest","mode":"system","stageTone":"dark"}
 * ```
 *
 * v1（`{"version":1,"mode":…,"stageTone":…}`）没有配色字段，读到时按
 * [PaletteId.Default] 迁移 —— 老用户第一次升级看到的就是默认的暖灰墨绿，明暗模式
 * 与舞台色原样保留。
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
    /** 界面配色（配色方案 §6）。默认暖灰墨绿。 */
    val palette: PaletteId = PaletteId.Default,
) {
    companion object {
        /** What a missing or unusable file resolves to: default palette, follow the system, dark stage. */
        val DEFAULT = AppearanceSettings()

        /** 当前写盘格式：v2 起带 `palette`。 */
        val SCHEMA_VERSION = 2

        /** 只带 mode / stageTone 的旧格式；读到时补默认配色。 */
        const val LEGACY_SCHEMA_VERSION = 1
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
 * `version`, or a `mode` / `stageTone` / `palette` value outside the enums. A
 * single unknown field invalidates the whole document on purpose: the file is a
 * user decision, not a partial record, and a half-applied appearance would be
 * worse than the default. The one exception is a **v1** document: it predates the
 * palette field, so it is migrated with [PaletteId.Default] instead of being
 * thrown away.
 */
object AppearanceSettingsCodec {
    const val MODE_KEY = "mode"
    const val STAGE_TONE_KEY = "stageTone"
    const val PALETTE_KEY = "palette"
    const val VERSION_KEY = "version"

    /** Exact on-disk shape: `{"version":2,"palette":"forest","mode":"system","stageTone":"dark"}`. */
    fun encode(settings: AppearanceSettings): String =
        "{\"$VERSION_KEY\":${AppearanceSettings.SCHEMA_VERSION}," +
            "\"$PALETTE_KEY\":\"${settings.palette.wire}\"," +
            "\"$MODE_KEY\":\"${settings.mode.wire}\"," +
            "\"$STAGE_TONE_KEY\":\"${settings.stageTone.wire}\"}"

    fun decode(text: CharSequence?): AppearanceSettings {
        if (text.isNullOrBlank()) return AppearanceSettings.DEFAULT
        val root = try {
            Json.parse(text.toString()).jsonMap()
        } catch (_: Throwable) {
            return AppearanceSettings.DEFAULT
        }
        val version = root.jsonInt(VERSION_KEY)
        val mode = ThemeMode.fromWire(root.jsonString(MODE_KEY)) ?: return AppearanceSettings.DEFAULT
        val tone = StageTone.fromWire(root.jsonString(STAGE_TONE_KEY)) ?: return AppearanceSettings.DEFAULT
        return when (version) {
            AppearanceSettings.LEGACY_SCHEMA_VERSION ->
                // v1: no palette field. Migrate to the default palette, keep the rest.
                AppearanceSettings(mode = mode, stageTone = tone, palette = PaletteId.Default)

            AppearanceSettings.SCHEMA_VERSION -> {
                val palette = PaletteId.fromWire(root.jsonString(PALETTE_KEY))
                    ?: return AppearanceSettings.DEFAULT
                AppearanceSettings(mode = mode, stageTone = tone, palette = palette)
            }

            else -> AppearanceSettings.DEFAULT
        }
    }
}
