package com.framemark.native.engine

import android.graphics.Bitmap
import com.framemark.native.product.ExifMake
import com.framemark.native.product.orientedImage
import java.io.File
import java.io.FileInputStream

/**
 * Minimal EXIF reader for user photos. Skia handles orientation when rendering,
 * but Android's BitmapFactory (photo strip thumbnails, import conversion) does
 * not, so the UI-side consumers read the tag here.
 *
 * Supports JPEG (APP1 "Exif\0\0"), TIFF (IFD0) and WebP (RIFF "EXIF" chunk).
 */
object ExifProbe {

    fun isJpeg(file: File): Boolean = head(file, 4).let { it.size >= 3 && u8(it, 0) == 0xFF && u8(it, 1) == 0xD8 && u8(it, 2) == 0xFF }

    fun isTiff(file: File): Boolean = head(file, 4).let { it.size >= 4 && isTiffBytes(it) }

    fun isWebp(file: File): Boolean = head(file, 12).let {
        it.size >= 12 && it.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            it.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
    }

    /** True when a JPEG carries an APP1 with the "Exif\0\0" prefix. */
    fun hasApp1(file: File): Boolean = runCatching {
        val bytes = head(file, 256 * 1024)
        if (!(bytes.size >= 4 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8)) return false
        var i = 2
        while (i + 4 <= bytes.size) {
            if (u8(bytes, i) != 0xFF) return false
            val marker = u8(bytes, i + 1)
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                i += 2
                continue
            }
            if (marker == 0xD9 || marker == 0xDA) return false
            val len = (u8(bytes, i + 2) shl 8) or u8(bytes, i + 3)
            if (len < 2 || i + 2 + len > bytes.size) return false
            if (marker == 0xE1 && len >= 8 && isExifPrefix(bytes, i + 4, i + 2 + len)) return true
            i += 2 + len
        }
        false
    }.getOrDefault(false)

    /**
     * IFD0 `Make`（0x010F）：照片的品牌原文，字标自动匹配就按它解析。
     *
     * 容器解析交给共用的 [ExifMake]（JPEG APP1 / TIFF / WebP 一份逻辑，与桌面同源），
     * 这里只负责读文件头。HEIC 导入后的像素是 PNG，Exif 在侧车 JPEG 里，由调用方
     * （`AndroidNativeRender` 的 `files/exif/<name>.jpg` 约定）补一次读取。
     */
    fun make(file: File): String? = runCatching {
        ExifMake.read(head(file, 256 * 1024))
    }.getOrNull()

    /** IFD0 Orientation (1 when absent/unreadable). */
    fun orientation(file: File): Int = runCatching {
        val bytes = head(file, 256 * 1024)
        when {
            bytes.size >= 4 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 -> jpegOrientation(bytes)
            isTiffBytes(bytes) -> tiffOrientation(bytes, 0)
            bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" ->
                webpOrientation(bytes)
            else -> 1
        }
    }.getOrDefault(1)

    /**
     * Rotate/mirror a decoded bitmap so it shows with the EXIF orientation applied.
     *
     * Delegates to `product.orientedImage`, the single transform table shared with
     * the export's gain-map composition (`PhotoOrientation.rowSource`); keeping a
     * second Matrix table here is what let the thumbnail and the export disagree.
     */
    fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        if (orientation <= 1 || orientation > 8) return bitmap
        val rotated = bitmap.orientedImage(orientation)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun jpegOrientation(bytes: ByteArray): Int {
        var i = 2
        while (i + 4 <= bytes.size) {
            if (u8(bytes, i) != 0xFF) return 1
            val marker = u8(bytes, i + 1)
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                i += 2
                continue
            }
            if (marker == 0xD9 || marker == 0xDA) return 1
            val len = (u8(bytes, i + 2) shl 8) or u8(bytes, i + 3)
            if (len < 2 || i + 2 + len > bytes.size) return 1
            if (marker == 0xE1 && len >= 8 && isExifPrefix(bytes, i + 4, i + 2 + len)) {
                return tiffOrientation(bytes, i + 10)
            }
            i += 2 + len
        }
        return 1
    }

    private fun webpOrientation(bytes: ByteArray): Int {
        var i = 12
        while (i + 8 <= bytes.size) {
            val fourcc = bytes.copyOfRange(i, i + 4).toString(Charsets.US_ASCII)
            val size = u8(bytes, i + 4) or (u8(bytes, i + 5) shl 8) or
                (u8(bytes, i + 6) shl 16) or (u8(bytes, i + 7) shl 24)
            if (size < 0 || i + 8 + size > bytes.size) return 1
            if (fourcc == "EXIF") {
                val start = i + 8
                val end = start + size
                val tiffStart = if (size >= 6 && isExifPrefix(bytes, start, end)) start + 6 else start
                return tiffOrientation(bytes, tiffStart)
            }
            i += 8 + size + (size and 1)
        }
        return 1
    }

    private fun tiffOrientation(bytes: ByteArray, base: Int): Int {
        if (base + 8 > bytes.size) return 1
        val le = when {
            bytes[base] == 'I'.code.toByte() && bytes[base + 1] == 'I'.code.toByte() -> true
            bytes[base] == 'M'.code.toByte() && bytes[base + 1] == 'M'.code.toByte() -> false
            else -> return 1
        }
        val ifd = base + u32(bytes, base + 4, le)
        if (ifd + 2 > bytes.size) return 1
        val count = u16(bytes, ifd, le)
        for (n in 0 until count) {
            val entry = ifd + 2 + n * 12
            if (entry + 12 > bytes.size) return 1
            if (u16(bytes, entry, le) == 0x0112) {
                return u16(bytes, entry + 8, le)
            }
        }
        return 1
    }

    private fun isExifPrefix(bytes: ByteArray, start: Int, end: Int): Boolean =
        end - start >= 6 &&
            bytes[start] == 'E'.code.toByte() && bytes[start + 1] == 'x'.code.toByte() &&
            bytes[start + 2] == 'i'.code.toByte() && bytes[start + 3] == 'f'.code.toByte() &&
            bytes[start + 4] == 0.toByte() && bytes[start + 5] == 0.toByte()

    private fun isTiffBytes(bytes: ByteArray): Boolean = bytes.size >= 4 &&
        ((bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 42.toByte() && bytes[3] == 0.toByte()) ||
            (bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() && bytes[2] == 0.toByte() && bytes[3] == 42.toByte()))

    private fun head(file: File, max: Int): ByteArray {
        if (!file.isFile) return ByteArray(0)
        val size = minOf(file.length(), max.toLong()).toInt()
        val buf = ByteArray(size)
        FileInputStream(file).use { input ->
            var read = 0
            while (read < size) {
                val n = input.read(buf, read, size - read)
                if (n <= 0) break
                read += n
            }
            if (read == size) return buf
            return buf.copyOf(read)
        }
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private fun u16(b: ByteArray, i: Int, le: Boolean): Int =
        if (le) u8(b, i) or (u8(b, i + 1) shl 8) else (u8(b, i) shl 8) or u8(b, i + 1)

    private fun u32(b: ByteArray, i: Int, le: Boolean): Int =
        if (le) u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)
        else (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
}
