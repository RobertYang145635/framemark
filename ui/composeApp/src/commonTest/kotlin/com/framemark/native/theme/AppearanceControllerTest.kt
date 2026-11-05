package com.framemark.native.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WP-T §4.2.5: the controller holds the observable settings and writes through on
 * every change — and an `FM_APPEARANCE` override is a per-run decision that must
 * never reach the file.
 */
class AppearanceControllerTest {

    private class FakeStore(var text: String? = null) : AppearanceStore {
        override val settingsPath: String = "<fake>/settings/appearance.json"
        var saves: Int = 0
        var lastWritten: String? = null

        override fun load(): String? = text

        override fun save(json: String): Boolean {
            saves++
            lastWritten = json
            text = json
            return true
        }
    }

    @Test
    fun missingStoreMeansSystemAndDarkStage() {
        val controller = AppearanceController(FakeStore())
        assertEquals(AppearanceSettings.DEFAULT, controller.settings)
        assertEquals(ThemeMode.System, controller.settings.mode)
        assertEquals(StageTone.Dark, controller.settings.stageTone)
        assertEquals("missing", controller.loadStatus)
    }

    @Test
    fun storedSettingsAreLoaded() {
        val store = FakeStore(
            AppearanceSettingsCodec.encode(AppearanceSettings(ThemeMode.Dark, StageTone.Mid)),
        )
        val controller = AppearanceController(store)
        assertEquals(ThemeMode.Dark, controller.settings.mode)
        assertEquals(StageTone.Mid, controller.settings.stageTone)
        assertEquals("loaded", controller.loadStatus)
        assertEquals(0, store.saves, "loading must not write")
    }

    @Test
    fun corruptFileFallsBackAndIsLeftAlone() {
        val corrupt = "{ this is not json"
        val store = FakeStore(corrupt)
        val controller = AppearanceController(store)
        assertEquals(AppearanceSettings.DEFAULT, controller.settings)
        assertEquals(0, store.saves, "a corrupt file must not be overwritten on load")
        assertEquals(corrupt, store.text, "a corrupt file must survive the failed read")
    }

    @Test
    fun setModeWritesThrough() {
        val store = FakeStore()
        val controller = AppearanceController(store)
        controller.setMode(ThemeMode.Light)
        assertEquals(ThemeMode.Light, controller.settings.mode)
        assertEquals(1, store.saves)
        assertEquals(
            "{\"version\":1,\"mode\":\"light\",\"stageTone\":\"dark\"}",
            store.lastWritten,
        )
        // Repeating the same mode is not a change and must not touch the disk.
        controller.setMode(ThemeMode.Light)
        assertEquals(1, store.saves)
    }

    @Test
    fun setStageToneWritesThrough() {
        val store = FakeStore()
        val controller = AppearanceController(store)
        controller.setStageTone(StageTone.Light)
        assertEquals(StageTone.Light, controller.settings.stageTone)
        assertEquals(1, store.saves)
        assertTrue(controller.settings.mode == ThemeMode.System, "tone change must not alter the mode")
    }

    @Test
    fun systemDarkTracksThePlatformWithoutPersisting() {
        val store = FakeStore()
        val controller = AppearanceController(store, initialSystemDark = false)
        assertFalse(controller.dark)
        controller.updateSystemDark(true)
        assertTrue(controller.dark, "System mode must follow the platform")
        assertEquals(0, store.saves, "the platform flag is never persisted")
        controller.setMode(ThemeMode.Light)
        assertFalse(controller.dark, "an explicit light mode ignores a dark platform")
    }

    @Test
    fun forcedModeWinsForThisRunAndNeverReachesTheFile() {
        val store = FakeStore(
            AppearanceSettingsCodec.encode(AppearanceSettings(ThemeMode.System, StageTone.Dark)),
        )
        val controller = AppearanceController(store, forcedMode = ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, controller.settings.mode)
        assertTrue(controller.isForced)
        assertTrue(controller.dark, "forced dark must resolve dark even on a light platform")
        assertEquals(0, store.saves, "the forced mode is not written on load")

        // Changing the stage tone during a forced run must persist the user's own
        // mode (system), not the override.
        controller.setStageTone(StageTone.Mid)
        assertEquals(1, store.saves)
        assertEquals(
            "{\"version\":1,\"mode\":\"system\",\"stageTone\":\"mid\"}",
            store.lastWritten,
        )
        assertEquals(ThemeMode.Dark, controller.settings.mode, "the override still wins for the UI")
    }

    @Test
    fun aFailingSaveDoesNotThrow() {
        val store = object : AppearanceStore {
            override val settingsPath: String = "<failing>"
            override fun load(): String? = null
            override fun save(json: String): Boolean = false
        }
        val controller = AppearanceController(store)
        controller.setMode(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, controller.settings.mode, "the in-memory choice survives a failed write")
        assertFalse(controller.lastSaveOk)
    }

    @Test
    fun aThrowingStoreDoesNotPreventStartup() {
        val store = object : AppearanceStore {
            override val settingsPath: String = "<throwing>"
            override fun load(): String? = throw IllegalStateException("boom")
            override fun save(json: String): Boolean = throw IllegalStateException("boom")
        }
        val controller = AppearanceController(store)
        assertEquals(AppearanceSettings.DEFAULT, controller.settings)
        assertEquals("error", controller.loadStatus)
        controller.setMode(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, controller.settings.mode)
        assertFalse(controller.lastSaveOk)
    }
}
