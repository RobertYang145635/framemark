package com.framemark.native.theme

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WP-T §5.2: `AppearanceSettingsCodec` round trip plus every documented fallback.
 *
 * The fallback contract (task brief §4.2.4): a missing file, broken JSON, an
 * unknown `version`, or an unknown `mode` / `stageTone` value all resolve to
 * `System` + `Dark` — and none of them throws.
 */
class AppearanceSettingsCodecTest {

    @Test
    fun encodeHasTheDocumentedShape() {
        assertEquals(
            "{\"version\":1,\"mode\":\"system\",\"stageTone\":\"dark\"}",
            AppearanceSettingsCodec.encode(AppearanceSettings()),
        )
    }

    @Test
    fun roundTripEveryModeAndTone() {
        for (mode in ThemeMode.entries) {
            for (tone in StageTone.entries) {
                val settings = AppearanceSettings(mode = mode, stageTone = tone)
                val decoded = AppearanceSettingsCodec.decode(AppearanceSettingsCodec.encode(settings))
                assertEquals(settings, decoded, "round trip failed for $mode/$tone")
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
        assertEquals(AppearanceSettings.DEFAULT, AppearanceSettingsCodec.decode("{\"version\":1,\"mode\":"))
        assertEquals(AppearanceSettings.DEFAULT, AppearanceSettingsCodec.decode("not json at all"))
        assertEquals(AppearanceSettings.DEFAULT, AppearanceSettingsCodec.decode("[]"))
        assertEquals(AppearanceSettings.DEFAULT, AppearanceSettingsCodec.decode("null"))
    }

    @Test
    fun unknownVersionIsDefault() {
        assertEquals(
            AppearanceSettings.DEFAULT,
            AppearanceSettingsCodec.decode("{\"version\":2,\"mode\":\"light\",\"stageTone\":\"mid\"}"),
        )
        assertEquals(
            AppearanceSettings.DEFAULT,
            AppearanceSettingsCodec.decode("{\"mode\":\"light\",\"stageTone\":\"mid\"}"),
        )
    }

    @Test
    fun unknownModeIsDefault() {
        assertEquals(
            AppearanceSettings.DEFAULT,
            AppearanceSettingsCodec.decode("{\"version\":1,\"mode\":\"sepia\",\"stageTone\":\"mid\"}"),
        )
        assertEquals(
            AppearanceSettings.DEFAULT,
            AppearanceSettingsCodec.decode("{\"version\":1,\"stageTone\":\"mid\"}"),
        )
    }

    @Test
    fun unknownStageToneIsDefault() {
        assertEquals(
            AppearanceSettings.DEFAULT,
            AppearanceSettingsCodec.decode("{\"version\":1,\"mode\":\"light\",\"stageTone\":\"prism\"}"),
        )
        assertEquals(
            AppearanceSettings.DEFAULT,
            AppearanceSettingsCodec.decode("{\"version\":1,\"mode\":\"light\"}"),
        )
    }

    @Test
    fun validDocumentKeepsBothFields() {
        val decoded = AppearanceSettingsCodec.decode(
            "{\"version\":1,\"mode\":\"light\",\"stageTone\":\"light\"}",
        )
        assertEquals(AppearanceSettings(mode = ThemeMode.Light, stageTone = StageTone.Light), decoded)
    }

    @Test
    fun wireVocabularyIsStable() {
        // The on-disk words are part of the file format; renaming an enum constant
        // must not silently change them.
        assertEquals(listOf("system", "light", "dark"), ThemeMode.entries.map { it.wire })
        assertEquals(listOf("dark", "mid", "light"), StageTone.entries.map { it.wire })
    }
}
