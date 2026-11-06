package com.framemark.native.shell.phone

import com.framemark.native.theme.AppearanceController
import com.framemark.native.theme.AppearanceSettingsCodec
import com.framemark.native.theme.AppearanceStore
import com.framemark.native.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WP-P2 §4.2 设置页外观往返.
 *
 * The settings page prints [PhoneSettingsText.themeLabel] of the controller the
 * `AppearancePicker` writes to, so the round-trip is: pick a mode (the same call
 * the picker makes) → the row follows → a fresh controller over the same file
 * still reports the choice.
 */
class PhoneSettingsTextTest {

    private class FakeStore(var text: String? = null) : AppearanceStore {
        override val settingsPath: String = "<fake>/settings/appearance.json"
        var writes: Int = 0

        override fun load(): String? = text

        override fun save(json: String): Boolean {
            writes++
            text = json
            return true
        }
    }

    @Test
    fun pickingAModeUpdatesTheRowAndSurvivesAReopen() {
        val store = FakeStore()
        val first = AppearanceController(store)
        assertEquals("跟随系统", PhoneSettingsText.themeLabel(first.settings.mode))

        // What `AppearancePicker`'s onSelect does for 深色.
        first.setMode(ThemeMode.Dark)
        assertEquals("深色", PhoneSettingsText.themeLabel(first.settings.mode))
        assertEquals(1, store.writes, "a change is written through exactly once")

        // Reopening 设置 (a new controller over the same file) still shows 深色.
        val reopened = AppearanceController(store)
        assertEquals(ThemeMode.Dark, reopened.settings.mode)
        assertEquals("深色", PhoneSettingsText.themeLabel(reopened.settings.mode))
        assertEquals(1, store.writes, "reading the file must not write it")

        // And the choice is a full round-trip through the on-disk format.
        val decoded = AppearanceSettingsCodec.decode(store.load())
        assertEquals(ThemeMode.Dark, decoded.mode)
    }

    @Test
    fun theThreeLabelsAreTheDemoCopyInOrder() {
        assertEquals(
            listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark),
            PhoneSettingsText.MODE_ORDER,
        )
        assertEquals(
            listOf("跟随系统", "浅色", "深色"),
            PhoneSettingsText.MODE_ORDER.map { PhoneSettingsText.themeLabel(it) },
        )
    }

    @Test
    fun versionNeverPrintsAnEmptyRow() {
        assertEquals("dev", PhoneSettingsText.versionLabel(null))
        assertEquals("dev", PhoneSettingsText.versionLabel(""))
        assertEquals("dev", PhoneSettingsText.versionLabel("   "))
        assertEquals("dev", PhoneSettingsText.versionLabel("null"))
        assertEquals("0.9.0-rc3", PhoneSettingsText.versionLabel(" 0.9.0-rc3 "))
    }
}
