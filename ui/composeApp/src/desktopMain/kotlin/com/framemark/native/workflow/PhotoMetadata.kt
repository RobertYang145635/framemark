package com.framemark.native.workflow

import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Photo header reader: intrinsic size + EXIF orientation without decoding the
 * raster. JPEG/PNG/TIFF use the JDK ImageIO header; WebP is parsed from its RIFF
 * header (ImageIO has no WebP reader). Orientation comes from EXIF in JPEG and
 * TIFF; other formats default to 1.
 */
object PhotoMetadata {
    data class Meta(val width: Int, val height: Int, val orientation: Int)

    private val headerBytes = 512 * 1024

    fun read(file: File): Meta? {
        if (!file.isFile) return null
        val dims = imageSize(file) ?: return null
        if (dims.first <= 0 || dims.second <= 0) return null
        return Meta(dims.first, dims.second, orientation(file))
    }

    fun fingerprint(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun imageSize(file: File): Pair<Int, Int>? {
        runCatching {
            ImageIO.createImageInputStream(file).use { input ->
                if (input == null) return@use
                val readers = ImageIO.getImageReaders(input)
                if (readers.hasNext()) {
                    val reader = readers.next()
                    try {
                        reader.input = input
                        val width = reader.getWidth(0)
                        val height = reader.getHeight(0)
                        if (width > 0 && height > 0) return width to height
                    } finally {
                        reader.dispose()
                    }
                }
            }
        }
        return webpSize(file)
    }

    private fun webpSize(file: File): Pair<Int, Int>? = runCatching {
        val head = file.inputStream().use { it.readNBytes(64) }
        if (head.size < 30) return@runCatching null
        if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF") return@runCatching null
        if (String(head, 8, 4, Charsets.US_ASCII) != "WEBP") return@runCatching null
        val fourcc = String(head, 12, 4, Charsets.US_ASCII)
        val payload = 20
        when (fourcc) {
            "VP8X" -> {
                val width = 1 + u24(head, payload + 4)
                val height = 1 + u24(head, payload + 7)
                width to height
            }
            "VP8 " -> {
                if ((head[payload + 3].toInt() and 0xff) != 0x9d) return@runCatching null
                val width = u16(head, payload + 6) and 0x3fff
                val height = u16(head, payload + 8) and 0x3fff
                width to height
            }
            "VP8L" -> {
                if ((head[payload].toInt() and 0xff) != 0x2f) return@runCatching null
                val b = ByteArray(4) { head[payload + 1 + it] }
                val bits = (b[0].toInt() and 0xff) or
                    ((b[1].toInt() and 0xff) shl 8) or
                    ((b[2].toInt() and 0xff) shl 16) or
                    ((b[3].toInt() and 0xff) shl 24)
                val width = (bits and 0x3fff) + 1
                val height = ((bits ushr 14) and 0x3fff) + 1
                width to height
            }
            else -> null
        }
    }.getOrNull()

    fun orientation(file: File): Int {
        val name = file.name.lowercase()
        val head = runCatching { file.inputStream().use { it.readNBytes(headerBytes) } }.getOrNull() ?: return 1
        return when {
            head.size > 4 && (head[0].toInt() and 0xff) == 0xff && (head[1].toInt() and 0xff) == 0xd8 ->
                jpegOrientation(head)
            String(head, 0, minOf(4, head.size), Charsets.US_ASCII).let { it == "II*\u0000" || it == "MM\u0000*" } ->
                tiffOrientation(head, 0)
            name.endsWith(".tif") || name.endsWith(".tiff") -> tiffOrientation(head, 0)
            else -> 1
        }
    }

    private fun jpegOrientation(bytes: ByteArray): Int {
        var i = 2
        while (i + 4 <= bytes.size) {
            if ((bytes[i].toInt() and 0xff) != 0xff) return 1
            val marker = bytes[i + 1].toInt() and 0xff
            if (marker == 0xd8 || marker == 0x01 || marker in 0xd0..0xd7) {
                i += 2
                continue
            }
            if (marker == 0xd9 || marker == 0xda) return 1
            val length = u16(bytes, i + 2)
            if (length < 2 || i + 2 + length > bytes.size) return 1
            val payload = i + 4
            if (marker == 0xe1 && length >= 8 &&
                bytes[payload] == 'E'.code.toByte() && bytes[payload + 1] == 'x'.code.toByte() &&
                bytes[payload + 2] == 'i'.code.toByte() && bytes[payload + 3] == 'f'.code.toByte() &&
                bytes[payload + 4] == 0.toByte() && bytes[payload + 5] == 0.toByte()
            ) {
                return tiffOrientation(bytes, payload + 6)
            }
            i += 2 + length
        }
        return 1
    }

    /** Reads IFD0's 0x0112 (Orientation) from a TIFF block starting at [base]. */
    private fun tiffOrientation(bytes: ByteArray, base: Int): Int {
        if (base + 8 > bytes.size) return 1
        val little = bytes[base] == 'I'.code.toByte() && bytes[base + 1] == 'I'.code.toByte()
        val big = bytes[base] == 'M'.code.toByte() && bytes[base + 1] == 'M'.code.toByte()
        if (!little && !big) return 1
        fun u16(offset: Int): Int {
            if (offset + 2 > bytes.size) return 0
            val a = bytes[offset].toInt() and 0xff
            val b = bytes[offset + 1].toInt() and 0xff
            return if (little) a or (b shl 8) else (a shl 8) or b
        }
        fun u32(offset: Int): Int {
            if (offset + 4 > bytes.size) return 0
            val a = bytes[offset].toInt() and 0xff
            val b = bytes[offset + 1].toInt() and 0xff
            val c = bytes[offset + 2].toInt() and 0xff
            val d = bytes[offset + 3].toInt() and 0xff
            return if (little) a or (b shl 8) or (c shl 16) or (d shl 24)
            else (a shl 24) or (b shl 16) or (c shl 8) or d
        }
        val ifd = base + u32(base + 4)
        if (ifd + 2 > bytes.size) return 1
        val count = u16(ifd)
        if (count > 512) return 1
        for (index in 0 until count) {
            val entry = ifd + 2 + index * 12
            if (entry + 12 > bytes.size) break
            if (u16(entry) == 0x0112) {
                val value = u16(entry + 8)
                return if (value in 1..8) value else 1
            }
        }
        return 1
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset + 2 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }

    private fun u24(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16)
    }
}
