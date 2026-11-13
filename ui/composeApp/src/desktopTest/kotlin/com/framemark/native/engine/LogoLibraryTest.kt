package com.framemark.native.engine

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class LogoLibraryTest {
    @Test
    fun exact_catalog_cut_resolves_to_its_hashed_runtime_asset() = withRuntime { root, logos ->
        val key = "ver:fixture-cut"
        val alias = engineKey(key)
        val png = pngFixture()
        Files.write(logos.resolve("$alias.png"), png)
        writeMap(root, listOf(mapEntry(key, alias, sha256(png))))

        val library = LogoLibrary(logos.toFile())
        val resolved = assertNotNull(library.resolve(key, exactAssetKey = true))

        assertEquals(alias, resolved.name)
        assertEquals(2, resolved.width)
        assertEquals(1, resolved.height)
        assertEquals(alias, library.engineKey(key, exactAssetKey = true))
    }

    @Test
    fun corrupt_exact_asset_fails_without_falling_back_to_a_brand_logo() = withRuntime { root, logos ->
        val key = "ver:fixture-cut"
        val alias = engineKey(key)
        val validPng = pngFixture()
        Files.write(logos.resolve("$alias.png"), byteArrayOf(1, 2, 3, 4))
        Files.write(logos.resolve("fixture_black.png"), validPng)
        writeMap(root, listOf(mapEntry(key, alias, sha256(validPng))))

        val error = assertFailsWith<EngineConfigException> {
            LogoLibrary(logos.toFile()).resolve(key, exactAssetKey = true)
        }

        assertEquals("FM-E-LOGO-002", error.code)
        val keyError = assertFailsWith<EngineConfigException> {
            LogoLibrary(logos.toFile()).engineKey(key, exactAssetKey = true)
        }
        assertEquals("FM-E-LOGO-002", keyError.code)
    }

    @Test
    fun unknown_exact_asset_is_a_structured_error_but_legacy_brand_still_resolves() = withRuntime { root, logos ->
        val png = pngFixture()
        Files.write(logos.resolve("fixture_black.png"), png)
        writeMap(root, listOf(mapEntry("ver:other-cut", engineKey("ver:other-cut"), sha256(png))))

        val library = LogoLibrary(logos.toFile())
        val error = assertFailsWith<EngineConfigException> {
            library.resolve("ver:missing-cut", exactAssetKey = true)
        }
        assertEquals("FM-E-LOGO-001", error.code)
        assertEquals("fixture_black", assertNotNull(library.resolve("fixture")).name)
        assertEquals("fixture", library.engineKey("fixture"))
    }

    @Test
    fun brand_key_cut_falls_back_to_the_brand_baseline_when_the_version_map_is_stale() = withRuntime { root, logos ->
        val png = pngFixture()
        Files.write(logos.resolve("laika_black.png"), png)
        // A stale runtime map: the catalog knows the cut, the map does not.
        writeMap(root, listOf(mapEntry("ver:other-cut", engineKey("ver:other-cut"), sha256(png))))

        val library = LogoLibrary(logos.toFile())
        val resolved = assertNotNull(library.resolve("laika", exactAssetKey = true))

        assertEquals("laika_black", resolved.name)
        assertEquals("laika", library.engineKey("laika", exactAssetKey = true))
    }

    @Test
    fun brand_key_cut_without_any_runtime_bytes_is_a_structured_error() = withRuntime { root, logos ->
        writeMap(root, listOf(mapEntry("ver:other-cut", engineKey("ver:other-cut"), "00".repeat(32))))

        val resolveError = assertFailsWith<EngineConfigException> {
            LogoLibrary(logos.toFile()).resolve("laika", exactAssetKey = true)
        }
        assertEquals("FM-E-LOGO-002", resolveError.code)

        val keyError = assertFailsWith<EngineConfigException> {
            LogoLibrary(logos.toFile()).engineKey("laika", exactAssetKey = true)
        }
        assertEquals("FM-E-LOGO-002", keyError.code)
    }

    @Test
    fun malformed_version_map_fails_closed() = withRuntime { root, logos ->
        Files.writeString(root.resolve("data/registry/logo-versions.json"), "{\"schema\":\"wrong\",\"entries\":[]}")

        val error = assertFailsWith<EngineConfigException> {
            LogoLibrary(logos.toFile()).resolve("fixture")
        }

        assertEquals("FM-E-LOGO-003", error.code)
    }

    private fun withRuntime(block: (root: java.nio.file.Path, logos: java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("fm-logo-library-")
        val logos = root.resolve("data/logos")
        Files.createDirectories(logos)
        Files.createDirectories(root.resolve("data/registry"))
        try {
            block(root, logos)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun writeMap(root: java.nio.file.Path, entries: List<String>) {
        val json = """{"schema":"framemark-logo-version-map/v1","entries":[${entries.joinToString(",") }]}"""
        Files.writeString(root.resolve("data/registry/logo-versions.json"), json)
    }

    private fun mapEntry(assetKey: String, alias: String, pngSha256: String): String =
        """{"assetKey":"$assetKey","engineKey":"$alias","pngSha256":"$pngSha256"}"""

    private fun engineKey(assetKey: String): String = "fmlogo_${sha256(assetKey.toByteArray(Charsets.UTF_8))}"

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun pngFixture(): ByteArray {
        val image = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0x80ff0000.toInt())
        image.setRGB(1, 0, 0xff00ff00.toInt())
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }
}
