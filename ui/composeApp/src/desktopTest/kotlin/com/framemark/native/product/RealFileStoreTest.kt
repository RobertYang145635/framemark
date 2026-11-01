package com.framemark.native.product

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-disk proof of the atomic write protocol.
 *
 * `File.renameTo` refuses to replace an existing file on Windows, so the platform
 * adapter has to use `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`. This test
 * runs the same store against that implementation on a Chinese/space path.
 */
class RealFileStoreTest {
    private class NioFileSystem(override val dataDir: String) : ProductFileSystem {
        override fun exists(path: String): Boolean = Files.exists(Path.of(path))

        override fun readText(path: String): String? = try {
            Files.readString(Path.of(path))
        } catch (t: Throwable) {
            null
        }

        override fun writeBytes(path: String, bytes: ByteArray): Boolean = try {
            Path.of(path).parent?.let { Files.createDirectories(it) }
            Files.write(Path.of(path), bytes)
            true
        } catch (t: Throwable) {
            false
        }

        override fun fileSize(path: String): Long = try {
            Files.size(Path.of(path))
        } catch (t: Throwable) {
            0L
        }

        override fun rename(from: String, to: String): Boolean = try {
            Files.move(
                Path.of(from),
                Path.of(to),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        } catch (t: Throwable) {
            false
        }

        override fun delete(path: String): Boolean = try {
            Files.deleteIfExists(Path.of(path))
        } catch (t: Throwable) {
            false
        }
    }

    @Test
    fun store_replaces_documents_atomically_on_a_real_disk() {
        val root = Files.createTempDirectory("fm-product-中文 测试-")
        try {
            val dataDir = Files.createDirectories(root.resolve("data 数据")).toString()
            val store = SessionStore(NioFileSystem(dataDir))
            val project = Project(
                id = "real",
                photos = listOf(PhotoRef("a", "C:/照片/a.jpg", "a.jpg")),
                activePhotoId = "a",
                designs = mapOf("a" to PhotoDesign.base("simple-4")),
                selectedCardId = "simple-4",
            )

            assertEquals(StoreResult.Ok(Unit), store.saveProject(project))
            assertEquals(project, store.loadProject().ok())

            assertEquals(StoreResult.Ok(Unit), store.saveProject(project.copy(updatedAt = 42)))
            assertEquals(42L, store.loadProject().ok().updatedAt)

            val document = Path.of(store.documentPath(DocumentKind.Project))
            assertTrue(Files.readString(document).contains("\"updatedAt\":42"))
            val leftovers = Files.list(Path.of(dataDir)).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".tmp") }.count()
            }
            assertEquals(0L, leftovers, "真实磁盘上残留了临时文件")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun missing_document_is_reported_as_missing_on_a_real_disk() {
        val root = Files.createTempDirectory("fm-product-missing-")
        try {
            assertEquals(StoreResult.Missing, SessionStore(NioFileSystem(root.toString())).loadProject())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_failed_replace_on_a_real_disk_keeps_the_old_document() {
        val root = Files.createTempDirectory("fm-product-fallback-")
        try {
            val dataDir = Files.createDirectories(root.resolve("data")).toString()
            val project = Project(id = "real", photos = listOf(PhotoRef("a", "C:/a.jpg", "a.jpg")), activePhotoId = "a")
            assertEquals(StoreResult.Ok(Unit), SessionStore(NioFileSystem(dataDir)).saveProject(project))

            val failing = object : ProductFileSystem by NioFileSystem(dataDir) {
                override fun rename(from: String, to: String): Boolean = false
            }
            val store = SessionStore(failing)
            val error = store.saveProject(project.copy(updatedAt = 7)).errorOrNull()
            assertEquals(StoreCodes.IO_WRITE, error?.code)
            assertEquals(project, store.loadProject().ok())
            assertEquals(0L, Files.list(Path.of(dataDir)).use { it.filter { p -> p.fileName.toString().endsWith(".tmp") }.count() })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
