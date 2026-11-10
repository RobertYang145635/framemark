package com.framemark.native.workflow

import com.framemark.native.product.FmLog
import com.framemark.native.product.ProductFileSystem
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Desktop [ProductFileSystem] over `java.nio`.
 *
 * `File.renameTo` refuses to replace an existing file on Windows, so the atomic
 * replace uses `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)` and falls back to
 * `REPLACE_EXISTING` when the volume rejects ATOMIC_MOVE. The fallback is not
 * atomic, so the target is re-read and compared with the bytes that were meant to
 * be published before the replace reports success.
 */
class JvmProductFileSystem(override val dataDir: String) : ProductFileSystem {
    override fun exists(path: String): Boolean = File(path).isFile

    override fun readText(path: String): String? = runCatching {
        File(path).takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }.getOrNull()

    override fun readBytes(path: String): ByteArray? = runCatching {
        File(path).takeIf { it.isFile }?.readBytes()
    }.getOrNull()

    override fun writeBytes(path: String, bytes: ByteArray): Boolean = runCatching {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
    }.getOrDefault(false)

    override fun writeBytesIfAbsent(path: String, bytes: ByteArray): Boolean = runCatching {
        val file = File(path)
        file.parentFile?.mkdirs()
        Files.newOutputStream(Path.of(path), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            it.write(bytes)
        }
        true
    }.getOrDefault(false)

    override fun fileSize(path: String): Long = runCatching { File(path).length() }.getOrDefault(0L)

    override fun rename(from: String, to: String): Boolean {
        val source = Path.of(from)
        val target = Path.of(to)
        if (!Files.isRegularFile(source)) return false
        target.parent?.let { runCatching { Files.createDirectories(it) } }
        val moved = try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (atomic: Throwable) {
            runCatching {
                // Not atomic: verify every byte landed before claiming success.
                val expected = Files.readAllBytes(source)
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                val landed = runCatching { Files.readAllBytes(target) }.getOrNull()
                if (landed == null || !landed.contentEquals(expected)) {
                    FmLog.error("FS_REPLACE_MISMATCH") {
                        "to=$to expected=${expected.size} actual=${landed?.size ?: -1}"
                    }
                    return@runCatching false
                }
                true
            }.getOrElse {
                FmLog.warn("FS_REPLACE_FAIL") {
                    "from=$from to=$to atomic=${atomic.message} fallback=${it.message}"
                }
                false
            }
        }
        if (moved) fsyncParentDirectory(target)
        return moved
    }

    /**
     * Best-effort directory fsync after a replace. Windows refuses to open a
     * directory this way, so the failure is ignored: the document bytes were
     * verified, only the directory-entry flush is platform-dependent.
     */
    private fun fsyncParentDirectory(target: Path) {
        val dir = target.parent ?: return
        runCatching {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    override fun delete(path: String): Boolean = runCatching { Files.deleteIfExists(Path.of(path)) }.getOrDefault(false)
}
