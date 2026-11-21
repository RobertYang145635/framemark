package com.framemark.native.workflow

import android.content.Context
import android.graphics.BitmapFactory
import com.framemark.native.engine.ExifProbe
import com.framemark.native.product.FmLog
import com.framemark.native.product.WatermarkAsset
import com.framemark.native.product.readFileBytesBounded
import com.framemark.native.product.ProductFileSystem
import com.framemark.native.shell.PhoneAssetIo
import java.io.File
import java.nio.channels.FileChannel
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

    /** Streams and stops at `limit + 1` bytes; never loads an oversized document. */
    override fun readBytesBounded(path: String, limitBytes: Int): ByteArray? =
        readFileBytesBounded(path, limitBytes)

    override fun writeBytes(path: String, bytes: ByteArray): Boolean = platform.writeBytes(path, bytes)

    override fun writeBytesIfAbsent(path: String, bytes: ByteArray): Boolean = runCatching {
        val file = File(path)
        file.parentFile?.mkdirs()
        Files.newOutputStream(
            file.toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { it.write(bytes) }
        if (isPresetLibraryBackup(file)) checkpointAfterPresetMutation("preset-after-backup")
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
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            // Durable only after the directory entry itself is flushed.
            fsyncParentDirectory(target)
            if (isPresetLibraryReplacement(source, target)) {
                checkpointAfterPresetMutation("preset-after-replace")
            }
            true
        }.getOrElse { atomic ->
            runCatching {
                // REPLACE_EXISTING is not atomic: keep the bytes being published and
                // re-read the target afterwards. A short write must not be reported as
                // a committed document.
                val expected = source.readBytes()
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                val landed = runCatching { target.readBytes() }.getOrNull()
                if (landed == null || !landed.contentEquals(expected)) {
                    FmLog.error("FS_REPLACE_MISMATCH") {
                        "to=$to expected=${expected.size} actual=${landed?.size ?: -1}"
                    }
                    return@runCatching false
                }
                fsyncParentDirectory(target)
                if (isPresetLibraryReplacement(source, target)) {
                    checkpointAfterPresetMutation("preset-after-replace")
                }
                true
            }.getOrElse {
                FmLog.warn("FM_FS_REPLACE_FAIL") {
                    "from=$from to=$to atomic=${atomic.message} fallback=${it.message}"
                }
                false
            }
        }
    }

    /**
     * Best-effort directory fsync: a rename can be lost on power loss until the
     * containing directory entry is flushed. Linux/Android allow opening a directory
     * read-only for this; Windows and some volumes refuse, so a failure is ignored —
     * the document bytes themselves were verified before the rename.
     */
    private fun fsyncParentDirectory(file: File) {
        val dir = file.parentFile ?: return
        runCatching {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    override fun delete(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)

    private fun isPresetLibraryBackup(file: File): Boolean = runCatching {
        file.canonicalFile.parentFile == File(dataDir).canonicalFile &&
            file.name.startsWith("presets.json.legacy-") && file.name.endsWith(".bak")
    }.getOrDefault(false)

    private fun isPresetLibraryReplacement(source: File, target: File): Boolean = runCatching {
        source.canonicalFile == File(dataDir, "presets.json.tmp").canonicalFile &&
            target.canonicalFile == File(dataDir, "presets.json").canonicalFile
    }.getOrDefault(false)

    /**
     * Debug QA checkpoint around real preset-library file operations. The backup
     * checkpoint may resume after the test changes the source file and removes the
     * marker; the replacement checkpoint is force-stopped immediately after the
     * actual rename. Both are inert unless MainActivity accepted debug QA extras.
     */
    private fun checkpointAfterPresetMutation(stage: String) {
        if (com.framemark.native.LaunchOptions.crashWindow != stage) return
        val markerDir = com.framemark.native.LaunchOptions.crashMarkerDir?.let(::File) ?: return
        if (!markerDir.isDirectory) return
        val marker = File(markerDir, "marker-$stage")
        marker.writeText("$stage time=${System.currentTimeMillis()} pid=${android.os.Process.myPid()}")
        FmLog.info("FM_CRASH_WINDOW") { "$stage marker=${marker.absolutePath}" }
        if (com.framemark.native.LaunchOptions.crashResumeOnMarkerRemoval) {
            while (marker.exists()) Thread.sleep(100)
        } else {
            while (true) Thread.sleep(200)
        }
    }
}

/**
 * Reads the assets the phone project embeds: watermark bytes and pixel size, and a
 * photo's display size after EXIF orientation. Decoding is bounds-only
 * (`inJustDecodeBounds`), so a 50 MP file never enters memory for metadata.
 */
class AndroidPhoneAssetIo : PhoneAssetIo {

    override fun readBytes(path: String): ByteArray? {
        // Watermark assets are embedded into the project: bound the read at the
        // product limit so an oversized pick throws a clear error instead of OOM.
        return readFileBytesBounded(path, WatermarkAsset.MAX_BYTES)
    }

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

    /**
     * EXIF `DateTimeOriginal`（`android.media.ExifInterface` 直接用系统解析器）。
     * 读不到（非 JPEG/TIFF、字段缺失、文件不可读）就是 null。
     */
    override fun capturedAt(path: String): String? = runCatching {
        val exif = android.media.ExifInterface(path)
        val original = exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL)
        val modified = exif.getAttribute(android.media.ExifInterface.TAG_DATETIME)
        com.framemark.native.product.ExifCaptureDate.normalize(original)
            ?: com.framemark.native.product.ExifCaptureDate.normalize(modified)
    }.getOrNull()

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
