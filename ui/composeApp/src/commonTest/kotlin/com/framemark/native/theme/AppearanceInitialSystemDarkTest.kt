package com.framemark.native.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Repair T-1: the platform's dark flag has to be known **before the first
 * composition**, not filled in by an effect afterwards.
 *
 * A controller built with `initialSystemDark = true` must already resolve dark on
 * its very first read, with no `updateSystemDark(...)` call and no composition.
 * The regression this pins: `initialSystemDark` defaulting to `false` made
 * "follow the system" paint one light frame on a dark machine.
 */
class AppearanceInitialSystemDarkTest {

    private class FakeStore(var text: String? = null) : AppearanceStore {
        override val settingsPath: String = "<fake>/settings/appearance.json"
        var saves: Int = 0

        override fun load(): String? = text

        override fun save(json: String): Boolean {
            saves++
            text = json
            return true
        }
    }

    @Test
    fun systemModeWithASeededDarkPlatformIsDarkImmediately() {
        val controller = AppearanceController(FakeStore(), initialSystemDark = true)
        assertEquals(ThemeMode.System, controller.settings.mode)
        assertTrue(controller.systemDark, "the seeded platform flag must be visible without any update call")
        assertTrue(controller.dark, "System + a dark platform must resolve dark on the first read")
    }

    @Test
    fun systemModeWithASeededLightPlatformIsLightImmediately() {
        val controller = AppearanceController(FakeStore(), initialSystemDark = false)
        assertFalse(controller.dark)
    }

    @Test
    fun aSeededDarkPlatformStillLosesToAnExplicitLightChoice() {
        val controller = AppearanceController(FakeStore(), initialSystemDark = true)
        controller.setMode(ThemeMode.Light)
        assertTrue(controller.systemDark, "the platform flag itself must not change")
        assertFalse(controller.dark, "an explicit light choice ignores a dark platform")
    }

    @Test
    fun aSeededLightPlatformStillLosesToAnExplicitDarkChoice() {
        val controller = AppearanceController(FakeStore(), initialSystemDark = false)
        controller.setMode(ThemeMode.Dark)
        assertTrue(controller.dark)
    }

    @Test
    fun aStoredModeIsAppliedOnTopOfTheSeededFlag() {
        val store = FakeStore(AppearanceSettingsCodec.encode(AppearanceSettings(ThemeMode.Dark, StageTone.Mid)))
        val controller = AppearanceController(store, initialSystemDark = false)
        assertEquals(ThemeMode.Dark, controller.settings.mode)
        assertTrue(controller.dark, "the stored dark mode wins even though the seeded platform flag is light")
        assertEquals(0, store.saves, "seeding must not write the settings file")
    }

    @Test
    fun seedingDoesNotChangeAnyOtherSettingOrTheLoadStatus() {
        val controller = AppearanceController(FakeStore(), initialSystemDark = true)
        assertEquals(StageTone.Dark, controller.settings.stageTone)
        assertEquals("missing", controller.loadStatus)
        assertEquals(resolveDark(ThemeMode.System, true), controller.dark)
    }

    @Test
    fun theDarkReadSolelyDependsOnTheSeededFlag() {
        // The same controller instance read twice before any effect runs: the answer
        // must be stable, i.e. the first frame and the first recomposition agree.
        val controller = AppearanceController(FakeStore(), initialSystemDark = true)
        val first = controller.dark
        val second = controller.dark
        assertTrue(first)
        assertEquals(first, second)
    }
}
