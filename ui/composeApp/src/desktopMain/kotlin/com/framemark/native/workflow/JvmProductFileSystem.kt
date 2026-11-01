package com.framemark.native.workflow

import com.framemark.native.product.ProductFileSystem
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Desktop [ProductFileSystem] over `java.nio`.
 *
 * `File.renameTo` refuses to replace an existing file on Windows, so the atomic
 * replace uses `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)` and falls back to
 * `REPLACE_EXISTING` when the volume rejects ATOMIC_MOVE.
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

    override fun rename(from: String, to: String): Boolean = runCatching {
        val source = Path.of(from)
        val target = Path.of(to)
        target.parent?.let { Files.createDirectories(it) }
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Throwable) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
        true
    }.getOrDefault(false)

    override fun delete(path: String): Boolean = runCatching { Files.deleteIfExists(Path.of(path)) }.getOrDefault(false)
}
