package com.framemark.native.theme

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WP-T §5.2 + 配色方案 §8: `AppearanceSettingsCodec` round trip, the v1 → v2
 * migration, and every documented fallback.
 *
 * The fallback contract (task brief §4.2.4): a missing file, broken JSON, an
 * unknown `version`, or an unknown `mode` / `stageTone` / `palette` value all
 * resolve to the default appearance — and none of them throws. The one deliberate
 * exception is a **v1** document (no `palette` field): it is migrated with the
 * default palette instead of being discarded.
 */
class AppearanceSettingsCodecTest {

    @Test
    fun encodeHasTheDocumentedShape() {
        assertEquals(
            "{\"version\":2,\"palette\":\"forest\",\"mode\":\"system\",\"stageTone\":\"dark\"}",
            AppearanceSettingsCodec.encode(AppearanceSettings()),
        )
    }

    @Test
    fun roundTripEveryModeToneAndPalette() {
        for (mode in ThemeMode.entries) {
            for (tone in StageTone.entries) {
                for (palette in PaletteId.entries) {
                    val settings = AppearanceSettings(mode = mode, stageTone = tone, palette = palette)
                    val decoded = AppearanceSettingsCodec.decode(AppearanceSettingsCodec.encode(settings))
                    assertEquals(settings, decoded, "round trip failed for $mode/$tone/$palette")
                }
            }
        }
    }

    @Test
    fun missingFileIsDefault() {
        assertEquals(AppearanceSettings.DEFAULT, AppearanceSettingsCodec.decode(null))
        assertEquals(AppearanceSettings.DEFAULT, AppearanceSettingsCodec.decode(""))
        assertEquals(AppearanceSettings.DEFAULT, AppearanceSettingsCodec.decode("   "))
    }

    @Test
    fun corruptJsonIsDefault() {
        assertEquals(AppearanceSettings.DEFAULT, AppearanceSettingsCodec.decode("{\"version\":2,\"mode\":"))
        assertEquals(AppearanceSettings.DEFAULT, AppearanceSettingsCodec.decode("not json at all"))
        assertEquals(AppearanceSettings.DEFAULT, AppearanceSettingsCodec.decode("[]"))
        assertEquals(AppearanceSettings.DEFAULT, AppearanceSettingsCodec.decode("null"))
    }

    @Test
    fun unknownVersionIsDefault() {
        assertEquals(
            AppearanceSettings.DEFAULT,
            AppearanceSettingsCodec.decode(
                "{\"version\":3,\"palette\":\"forest\",\"mode\":\"light\",\"stageTone\":\"mid\"}",
            ),
        )
        assertEquals(
            AppearanceSettings.DEFAULT,
            AppearanceSettingsCodec.decode("{\"mode\":\"light\",\"stageTone\":\"mid\"}"),
        )
    }

    @Test
    fun v1DocumentsAreMigratedToTheDefaultPalette() {
        // 老文件没有 palette 字段：明暗模式与舞台色原样保留，配色补默认值。
        assertEquals(
            AppearanceSettings(mode = ThemeMode.Dark, stageTone = StageTone.Mid, palette = PaletteId.Forest),
            AppearanceSettingsCodec.decode("{\"version\":1,\"mode\":\"dark\",\"stageTone\":\"mid\"}"),
        )
        assertEquals(
            AppearanceSettings(mode = ThemeMode.Light, stageTone = StageTone.Light, palette = PaletteId.Forest),
            AppearanceSettingsCodec.decode("{\"version\":1,\"mode\":\"light\",\"stageTone\":\"light\"}"),
        )
    }

    @Test
    fun unknownPaletteIsDefault() {
        assertEquals(
            AppearanceSettings.DEFAULT,
            AppearanceSettingsCodec.decode(
                "{\"version\":2,\"palette\":\"sepia\",\"mode\":\"light\",\"stageTone\":\"mid\"}",
            ),
        )
        assertEquals(
            AppearanceSettings.DEFAULT,
            AppearanceSettingsCodec.decode("{\"version\":2,\"mode\":\"light\",\"stageTone\":\"mid\"}"),
        )
    }

    @Test
    fun unknownModeIsDefault() {
        assertEquals(
            AppearanceSettings.DEFAULT,
            AppearanceSettingsCodec.decode(
                "{\"version\":2,\"palette\":\"forest\",\"mode\":\"sepia\",\"stageTone\":\"mid\"}",
            ),
        )
        assertEquals(
            AppearanceSettings.DEFAULT,
            AppearanceSettingsCodec.decode("{\"version\":2,\"palette\":\"forest\",\"stageTone\":\"mid\"}"),
        )
        assertEquals(
            AppearanceSettings.DEFAULT,
            AppearanceSettingsCodec.decode("{\"version\":1,\"mode\":\"sepia\",\"stageTone\":\"mid\"}"),
        )
    }

    @Test
    fun unknownStageToneIsDefault() {
        assertEquals(
            AppearanceSettings.DEFAULT,
            AppearanceSettingsCodec.decode(
                "{\"version\":2,\"palette\":\"forest\",\"mode\":\"light\",\"stageTone\":\"prism\"}",
            ),
        )
        assertEquals(
            AppearanceSettings.DEFAULT,
            AppearanceSettingsCodec.decode("{\"version\":2,\"palette\":\"forest\",\"mode\":\"light\"}"),
        )
        assertEquals(
            AppearanceSettings.DEFAULT,
            AppearanceSettingsCodec.decode("{\"version\":1,\"mode\":\"light\"}"),
        )
    }

    @Test
    fun validDocumentKeepsAllThreeFields() {
        val decoded = AppearanceSettingsCodec.decode(
            "{\"version\":2,\"palette\":\"clay\",\"mode\":\"light\",\"stageTone\":\"light\"}",
        )
        assertEquals(
            AppearanceSettings(mode = ThemeMode.Light, stageTone = StageTone.Light, palette = PaletteId.Clay),
            decoded,
        )
    }

    @Test
    fun wireVocabularyIsStable() {
        // The on-disk words are part of the file format; renaming an enum constant
        // must not silently change them.
        assertEquals(listOf("system", "light", "dark"), ThemeMode.entries.map { it.wire })
        assertEquals(listOf("dark", "mid", "light"), StageTone.entries.map { it.wire })
        assertEquals(listOf("forest", "graphite", "indigo", "clay", "classic"), PaletteId.entries.map { it.wire })
    }
}
