package com.framemark.native.theme

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP-T §5.3: the desktop store writes, re-reads, replaces atomically and leaves a
 * corrupt file alone.
 *
 * Everything runs in a fresh temp directory; the real
 * `%LOCALAPPDATA%\FrameMarkNative` is never touched.
 */
class DesktopAppearanceStoreTest {

    private val root: File = Files.createTempDirectory("fm-appearance-").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun store() = DesktopAppearanceStore(File(File(root, "settings"), "appearance.json"))

    @Test
    fun missingFileReadsAsNull() {
        val store = store()
        assertNull(store.load())
        assertTrue(store.settingsPath.endsWith("settings\\appearance.json") || store.settingsPath.endsWith("settings/appearance.json"))
    }

    @Test
    fun writeThenReadRoundTrips() {
        val store = store()
        val settings = AppearanceSettings(ThemeMode.Dark, StageTone.Light)
        assertTrue(store.save(AppearanceSettingsCodec.encode(settings)))
        assertEquals(settings, AppearanceSettingsCodec.decode(store.load()))
        assertEquals(
            "{\"version\":2,\"palette\":\"forest\",\"mode\":\"dark\",\"stageTone\":\"light\"}",
            store.load(),
        )
    }

    @Test
    fun creatingTheSettingsDirectoryHappensOnFirstSave() {
        val store = store()
        assertTrue(!File(root, "settings").exists(), "the directory must not exist yet")
        assertTrue(store.save(AppearanceSettingsCodec.encode(AppearanceSettings())))
        assertTrue(File(root, "settings").isDirectory)
    }

    @Test
    fun saveIsAtomicAndLeavesNoTempFile() {
        val store = store()
        assertTrue(store.save(AppearanceSettingsCodec.encode(AppearanceSettings(ThemeMode.Light, StageTone.Dark))))
        assertTrue(store.save(AppearanceSettingsCodec.encode(AppearanceSettings(ThemeMode.Dark, StageTone.Mid))))

        val siblings = File(root, "settings").listFiles()!!.map { it.name }.sorted()
        assertEquals(
            listOf("appearance.json"),
            siblings,
            "the temp file must be replaced, never left behind: $siblings",
        )
        // The second write replaced the first instead of appending to it.
        assertEquals(
            "{\"version\":2,\"palette\":\"forest\",\"mode\":\"dark\",\"stageTone\":\"mid\"}",
            store.load(),
        )
    }

    @Test
    fun corruptFileFallsBackAndIsNotOverwritten() {
        val store = store()
        val file = File(File(root, "settings"), "appearance.json")
        file.parentFile.mkdirs()
        val corrupt = "{ \"version\": 1, \"mode\": "
        file.writeText(corrupt)

        val loaded = AppearanceSettingsCodec.decode(store.load())
        assertEquals(AppearanceSettings.DEFAULT, loaded)
        assertEquals(corrupt, file.readText(), "a corrupt file must survive until the next user change")

        // ... and the next real change replaces it.
        assertTrue(store.save(AppearanceSettingsCodec.encode(AppearanceSettings(ThemeMode.Light))))
        assertEquals(
            "{\"version\":2,\"palette\":\"forest\",\"mode\":\"light\",\"stageTone\":\"dark\"}",
            file.readText(),
        )
    }

    @Test
    fun anUnreadablePathIsReportedAsNullNotAThrow() {
        // A directory where the file should be: reading must not throw.
        val file = File(File(root, "settings"), "appearance.json")
        file.mkdirs()
        val store = DesktopAppearanceStore(file)
        assertNull(store.load())
    }
}
