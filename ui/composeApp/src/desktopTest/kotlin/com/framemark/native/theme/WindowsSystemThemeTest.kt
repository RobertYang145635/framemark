package com.framemark.native.theme

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WP-T §5.4: the four registry outcomes plus the `FM_FAKE_SYSTEM_DARK_FILE`
 * parsing used by the sandboxed "follow the system" smoke run.
 *
 * The real registry is never read here: every case injects a
 * [WindowsThemeReader], and the fake-file cases pass their own temp file.
 */
class WindowsSystemThemeTest {

    private val root: File = Files.createTempDirectory("fm-winsys-").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun readerReturning(value: Int?) = WindowsThemeReader { value }

    private fun theme(reader: WindowsThemeReader, windows: Boolean = true) =
        WindowsSystemTheme(reader = reader, fakeSystemDarkFile = null, windows = windows)

    // Registry outcomes --------------------------------------------------------

    @Test
    fun registryZeroMeansDark() {
        val theme = theme(readerReturning(0))
        assertTrue(theme.isDark())
        assertEquals("registry:0", theme.lastSource)
    }

    @Test
    fun registryOneMeansLight() {
        val theme = theme(readerReturning(1))
        assertFalse(theme.isDark())
        assertEquals("registry:1", theme.lastSource)
    }

    @Test
    fun registryMissingMeansLight() {
        val theme = theme(readerReturning(null))
        assertFalse(theme.isDark(), "an unreadable value must fall back to light, never to dark")
        assertEquals("registry:none", theme.lastSource)
    }

    @Test
    fun registryFailureMeansLightAndSaysSoOnce() {
        val theme = theme(WindowsThemeReader { throw IllegalStateException("no jna") })
        assertFalse(theme.isDark())
        // A second read is still false and must not throw either.
        assertFalse(theme.isDark())
        assertEquals("registry:none", theme.lastSource)
    }

    @Test
    fun anUnexpectedRegistryValueMeansLight() {
        assertFalse(theme(readerReturning(7)).isDark())
        assertFalse(theme(readerReturning(-1)).isDark())
    }

    @Test
    fun nonWindowsNeverCallsTheReader() {
        var calls = 0
        val reader = WindowsThemeReader { calls++; 0 }
        val theme = WindowsSystemTheme(reader = reader, fakeSystemDarkFile = null, windows = false)
        assertFalse(theme.isDark())
        assertEquals(0, calls, "the registry must not be touched off Windows")
        assertEquals("non-windows", theme.lastSource)
    }

    // Fake system file ---------------------------------------------------------

    private fun fakeFile(content: String): File = File(root, "system-dark.txt").apply {
        parentFile.mkdirs()
        writeText(content)
    }

    @Test
    fun fakeFileOneIsDark() {
        val file = fakeFile("1")
        val theme = WindowsSystemTheme(readerReturning(1), file, windows = true)
        assertTrue(theme.isDark(), "the fake file must override the registry")
        assertEquals("fake:${file.name}=1", theme.lastSource)
    }

    @Test
    fun fakeFileZeroIsLight() {
        val file = fakeFile("0")
        val theme = WindowsSystemTheme(readerReturning(0), file, windows = true)
        assertFalse(theme.isDark(), "the fake file must override the registry")
        assertEquals("fake:${file.name}=0", theme.lastSource)
    }

    @Test
    fun fakeFileFollowsItsContentAtEachRead() {
        val file = fakeFile("0")
        val theme = WindowsSystemTheme(readerReturning(0), file, windows = true)
        assertFalse(theme.isDark())
        // The 5 s poll re-reads the same file, so a rewrite is visible next tick.
        file.writeText("1\n")
        assertTrue(theme.isDark())
        file.writeText("0")
        assertFalse(theme.isDark())
    }

    @Test
    fun fakeFileWithAnUnknownValueIsLight() {
        assertFalse(WindowsSystemTheme(readerReturning(0), fakeFile("2"), true).isDark())
        assertFalse(WindowsSystemTheme(readerReturning(0), fakeFile("dark"), true).isDark())
        assertFalse(WindowsSystemTheme(readerReturning(0), fakeFile(""), true).isDark())
    }

    @Test
    fun aMissingFakeFileIsLight() {
        val missing = File(root, "nope.txt")
        val theme = WindowsSystemTheme(readerReturning(0), missing, windows = true)
        assertFalse(theme.isDark(), "a missing fake file must not fall back to the registry")
        assertEquals("fake:invalid", theme.lastSource)
    }
}
