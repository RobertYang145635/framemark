package com.framemark.native.workflow

import android.content.Context
import android.graphics.BitmapFactory
import com.framemark.native.engine.ExifProbe
import com.framemark.native.product.ProductFileSystem
import com.framemark.native.shell.PhoneAssetIo
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Android's [ProductFileSystem]: the same file hooks the workflow already uses,
 * plus the two operations they do not expose — an atomic replace and a delete.
 *
 * `File.renameTo` refuses to replace an existing target, so the staged temp file is
 * moved with `ATOMIC_MOVE` (java.nio.file is available from minSdk 26). If the
 * filesystem rejects an atomic move the replace is done by hand and the caller
 * still verifies the byte count before it reports success.
 */
class AndroidProductFileSystem(private val context: Context) : ProductFileSystem {
    private val platform = AndroidWorkflowPlatform(context)

    override val dataDir: String get() = platform.dataDir

    override fun exists(path: String): Boolean = File(path).isFile

    override fun readText(path: String): String? = platform.readTextFile(path)

    override fun readBytes(path: String): ByteArray? = platform.readBytes(path)

    override fun writeBytes(path: String, bytes: ByteArray): Boolean = platform.writeBytes(path, bytes)

    override fun writeBytesIfAbsent(path: String, bytes: ByteArray): Boolean = runCatching {
        val file = File(path)
        file.parentFile?.mkdirs()
        Files.newOutputStream(
            file.toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { it.write(bytes) }
        true
    }.getOrDefault(false)

    override fun fileSize(path: String): Long = platform.fileSize(path)

    override fun rename(from: String, to: String): Boolean {
        val source = File(from)
        val target = File(to)
        if (!source.isFile) return false
        target.parentFile?.mkdirs()
        return runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        }.getOrElse { atomic ->
            runCatching {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                true
            }.getOrElse {
                println("FM_FS_REPLACE_FAIL from=$from to=$to atomic=${atomic.message} fallback=${it.message}")
                false
            }
        }
    }

    override fun delete(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)
}

/**
 * Reads the assets the phone project embeds: watermark bytes and pixel size, and a
 * photo's display size after EXIF orientation. Decoding is bounds-only
 * (`inJustDecodeBounds`), so a 50 MP file never enters memory for metadata.
 */
class AndroidPhoneAssetIo : PhoneAssetIo {

    override fun readBytes(path: String): ByteArray? = runCatching {
        val file = File(path)
        if (!file.isFile) return null
        file.readBytes()
    }.getOrNull()

    override fun imageBounds(path: String): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return intArrayOf(bounds.outWidth, bounds.outHeight)
    }

    override fun photoMeta(path: String): IntArray? {
        val raw = imageBounds(path) ?: return null
        val file = File(path)
        val orientation = runCatching { ExifProbe.orientation(file) }.getOrDefault(1)
        val swapped = orientation in 5..8
        val width = if (swapped) raw[1] else raw[0]
        val height = if (swapped) raw[0] else raw[1]
        return intArrayOf(width, height, orientation)
    }

    override fun fingerprint(path: String): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
