package com.framemark.native.userlogo

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopUserLogoRepositoryTest {
    private fun png(width: Int = 120, height: Int = 40): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color(0, 120, 200, 200)
        g.fillRect(0, 0, width, height)
        g.dispose()
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }

    @Test
    fun import_list_load_delete_round_trip() = runBlocking {
        val dir = Files.createTempDirectory("fm-user-logos-").toFile()
        val repo = DesktopUserLogoRepository(dir)
        try {
            val entry = repo.import("我的 Logo.PNG", "image/png", png())
            assertEquals("image/png", entry.mime)
            assertEquals(120, entry.width)
            assertEquals(40, entry.height)
            assertEquals(1, repo.list().size)
            val loaded = repo.load(entry.id)
            assertNotNull(loaded)
            assertEquals(entry.sha256, loaded.logo.sha256)
            repo.delete(entry.id)
            assertEquals(0, repo.list().size)
            assertNull(repo.load(entry.id))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun concurrent_imports_do_not_drop_index_entries() = runBlocking {
        val dir = Files.createTempDirectory("fm-user-logos-concurrent-").toFile()
        val repo = DesktopUserLogoRepository(dir)
        try {
            val entries = coroutineScope {
                (0 until 8).map { index ->
                    async(Dispatchers.Default) {
                        repo.import("logo-$index.png", "image/png", png(120 + index, 40))
                    }
                }.awaitAll()
            }
            assertEquals(8, entries.map { it.id }.distinct().size)
            assertEquals(8, repo.list().size)
            entries.forEach { assertNotNull(repo.load(it.id)) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corrupt_index_is_preserved_and_import_is_refused() = runBlocking {
        val dir = Files.createTempDirectory("fm-user-logos-").toFile()
        val repo = DesktopUserLogoRepository(dir)
        try {
            dir.mkdirs()
            val corrupt = java.io.File(dir, "index.properties")
            corrupt.writeText("ids=ul-broken\nul-broken.name=\\uZZZZ\n")
            val error = runCatching { repo.import("x.png", "image/png", png()) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(corrupt.readText().contains("ul-broken"), "corrupt index must not be overwritten")
            assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("index.corrupt-") })
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".png") }, "must not write an orphan image")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun partially_valid_index_is_treated_as_corrupt() = runBlocking {
        val dir = Files.createTempDirectory("fm-user-logos-").toFile()
        val repo = DesktopUserLogoRepository(dir)
        try {
            dir.mkdirs()
            val corrupt = java.io.File(dir, "index.properties")
            corrupt.writeText("ids=ul-broken\nul-broken.name=missing-fields\n")
            val error = runCatching { repo.import("x.png", "image/png", png()) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(corrupt.readText().contains("ul-broken"))
            assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("index.corrupt-") })
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".png") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun bad_format_is_rejected() = runBlocking {
        val dir = Files.createTempDirectory("fm-user-logos-").toFile()
        val repo = DesktopUserLogoRepository(dir)
        try {
            val error = runCatching { repo.import("x.jpg", "image/jpeg", byteArrayOf(1, 2, 3)) }.exceptionOrNull()
            assertNotNull(error)
            assertEquals(0, repo.list().size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
