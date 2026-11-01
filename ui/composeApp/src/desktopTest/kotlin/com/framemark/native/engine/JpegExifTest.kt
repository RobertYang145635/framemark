package com.framemark.native.engine

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JpegExifTest {
    @Test
    fun removes_gps_values_and_updates_dimensions_in_both_tiff_byte_orders() {
        for (littleEndian in listOf(true, false)) {
            withJpeg(exifJpeg(makeTiff(littleEndian))) { jpeg ->
                val sanitized = assertNotNull(JpegExif.tiffBlock(jpeg, width = 1280, height = 720))
                val parsed = ParsedTiff(sanitized)

                assertEquals(1280L, parsed.scalar(parsed.root, TAG_IMAGE_WIDTH))
                assertEquals(720L, parsed.scalar(parsed.root, TAG_IMAGE_HEIGHT))
                assertEquals(1L, parsed.scalar(parsed.root, TAG_ORIENTATION))
                assertEquals("CAMERA-X", parsed.ascii(parsed.root, TAG_MAKE))

                val exif = parsed.directory(parsed.scalar(parsed.root, TAG_EXIF_IFD)!!)
                assertEquals(1280L, parsed.scalar(exif, TAG_PIXEL_X_DIMENSION))
                assertEquals(720L, parsed.scalar(exif, TAG_PIXEL_Y_DIMENSION))
                assertEquals("2025:03:04 05:06:07", parsed.ascii(exif, TAG_DATE_TIME_ORIGINAL))
                assertNull(parsed.find(parsed.root, TAG_GPS_IFD))

                assertFalse(contains(sanitized, GPS_SECRET.toByteArray(Charsets.US_ASCII)))
                assertFalse(contains(sanitized, gpsCoordinateBytes(littleEndian)))
            }
        }
    }

    @Test
    fun piexifjs_gps_sub_ifd_without_next_pointer_is_sanitized() {
        // Real MM-order fixture: IFD0 at 8 points to a four-entry GPS IFD at 26.
        // That sub-IFD ends at byte 76; GPS rational payload starts there, with no
        // four-byte next-IFD field between the directory and its values.
        val tiff = bytesFromHex(
            """
            4D 4D 00 2A 00 00 00 08 00 01 88 25 00 04 00 00 00 01 00 00 00 1A
            00 00 00 00 00 04 00 01 00 02 00 00 00 02 4E 00 00 00 00 02 00 05
            00 00 00 03 00 00 00 4C 00 03 00 02 00 00 00 02 45 00 00 00 00 04
            00 05 00 00 00 03 00 00 00 64 00 00 00 1E 00 00 00 01 00 00 00 00
            00 00 00 01 00 00 00 00 00 00 00 01 00 00 00 78 00 00 00 01 00 00
            00 00 00 00 00 01 00 00 00 00 00 00 00 01
            """.trimIndent(),
        )

        withJpeg(exifJpeg(tiff)) { jpeg ->
            val sanitized = assertNotNull(JpegExif.tiffBlock(jpeg))
            val parsed = ParsedTiff(sanitized)
            assertNull(parsed.find(parsed.root, TAG_GPS_IFD))
            assertFalse(contains(sanitized, tiff.copyOfRange(76, tiff.size)))
        }
    }

    @Test
    fun malformed_ifd_and_out_of_bounds_value_data_are_omitted() {
        val valid = makeTiff(littleEndian = true)
        val invalidRootOffset = valid.copyOf().also { putU32(it, 4, it.size + 20L, true) }
        val invalidValueOffset = valid.copyOf().also {
            // IFD0's Make entry points beyond the APP1 TIFF payload.
            putU32(it, 8 + 2 + 2 * ENTRY_SIZE + 8, it.size + 20L, true)
        }

        for (tiff in listOf(invalidRootOffset, invalidValueOffset, valid.copyOfRange(0, valid.size - 3))) {
            withJpeg(exifJpeg(tiff)) { jpeg ->
                assertNull(JpegExif.tiffBlock(jpeg))
            }
        }
    }

    @Test
    fun invalid_output_dimensions_are_rejected() {
        withJpeg(exifJpeg(makeTiff(littleEndian = false))) { jpeg ->
            assertNull(JpegExif.tiffBlock(jpeg, width = 0, height = 720))
            assertNull(JpegExif.tiffBlock(jpeg, width = 1280, height = -1))
        }
    }

    @Test
    fun embedded_thumbnail_is_preserved_with_rebased_offset_when_it_has_no_exif() {
        val thumbnail = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x31, 0x32, 0xFF.toByte(), 0xD9.toByte())
        withJpeg(exifJpeg(thumbnailTiff(thumbnail))) { jpeg ->
            val sanitized = assertNotNull(JpegExif.tiffBlock(jpeg))
            val parsed = ParsedTiff(sanitized)
            val ifd1 = parsed.nextDirectory(parsed.root)
            assertTrue(ifd1 > 0)
            val thumbnailOffset = parsed.scalar(ifd1, TAG_THUMBNAIL_OFFSET)!!.toInt()
            assertEquals(thumbnail.size.toLong(), parsed.scalar(ifd1, TAG_THUMBNAIL_LENGTH))
            assertTrue(thumbnailOffset >= 8)
            assertTrue(contains(sanitized, thumbnail))
        }
    }

    private inline fun withJpeg(bytes: ByteArray, block: (java.io.File) -> Unit) {
        val path = Files.createTempFile("framemark-exif-", ".jpg")
        try {
            Files.write(path, bytes)
            block(path.toFile())
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun exifJpeg(tiff: ByteArray): ByteArray {
        val payload = EXIF_PREFIX + tiff
        require(payload.size + 2 <= 0xFFFF)
        val segmentLength = payload.size + 2
        return byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(),
            (segmentLength ushr 8).toByte(), segmentLength.toByte(),
        ) + payload + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    /** TIFF with IFD0 + EXIF + GPS IFDs, including coordinates and safe camera tags. */
    private fun makeTiff(littleEndian: Boolean): ByteArray {
        val rootOffset = 8
        val rootEntryCount = 6
        val exifOffset = rootOffset + 2 + rootEntryCount * ENTRY_SIZE + 4
        val exifEntryCount = 3
        val gpsOffset = exifOffset + 2 + exifEntryCount * ENTRY_SIZE + 4
        val gpsEntryCount = 5
        val makeOffset = gpsOffset + 2 + gpsEntryCount * ENTRY_SIZE + 4
        val make = "CAMERA-X\u0000".toByteArray(Charsets.US_ASCII)
        val dateOffset = makeOffset + make.size
        val date = "2025:03:04 05:06:07\u0000".toByteArray(Charsets.US_ASCII)
        val secretOffset = dateOffset + date.size
        val secret = GPS_SECRET.toByteArray(Charsets.US_ASCII)
        val coordinateOffset = secretOffset + secret.size
        val out = ByteArray(coordinateOffset + 48)

        out[0] = if (littleEndian) 'I'.code.toByte() else 'M'.code.toByte()
        out[1] = out[0]
        putU16(out, 2, 42, littleEndian)
        putU32(out, 4, rootOffset.toLong(), littleEndian)

        var p = rootOffset
        putU16(out, p, rootEntryCount, littleEndian)
        p += 2
        p = entry(out, p, TAG_IMAGE_WIDTH, TYPE_LONG, 1, 4032, littleEndian)
        p = entry(out, p, TAG_IMAGE_HEIGHT, TYPE_LONG, 1, 3024, littleEndian)
        p = entry(out, p, TAG_MAKE, TYPE_ASCII, make.size, makeOffset.toLong(), littleEndian)
        p = entry(out, p, TAG_ORIENTATION, TYPE_SHORT, 1, 6, littleEndian)
        p = entry(out, p, TAG_EXIF_IFD, TYPE_LONG, 1, exifOffset.toLong(), littleEndian)
        p = entry(out, p, TAG_GPS_IFD, TYPE_LONG, 1, gpsOffset.toLong(), littleEndian)
        putU32(out, p, 0, littleEndian)

        p = exifOffset
        putU16(out, p, exifEntryCount, littleEndian)
        p += 2
        p = entry(out, p, TAG_DATE_TIME_ORIGINAL, TYPE_ASCII, date.size, dateOffset.toLong(), littleEndian)
        p = entry(out, p, TAG_PIXEL_X_DIMENSION, TYPE_LONG, 1, 4032, littleEndian)
        p = entry(out, p, TAG_PIXEL_Y_DIMENSION, TYPE_LONG, 1, 3024, littleEndian)
        putU32(out, p, 0, littleEndian)

        p = gpsOffset
        putU16(out, p, gpsEntryCount, littleEndian)
        p += 2
        p = entry(out, p, 0x0001, TYPE_ASCII, 2, "N\u0000".toByteArray(Charsets.US_ASCII), littleEndian)
        p = entry(out, p, 0x0002, TYPE_RATIONAL, 3, coordinateOffset.toLong(), littleEndian)
        p = entry(out, p, 0x0003, TYPE_ASCII, 2, "E\u0000".toByteArray(Charsets.US_ASCII), littleEndian)
        p = entry(out, p, 0x0004, TYPE_RATIONAL, 3, (coordinateOffset + 24).toLong(), littleEndian)
        p = entry(out, p, 0x001C, TYPE_UNDEFINED, secret.size, secretOffset.toLong(), littleEndian)
        putU32(out, p, 0, littleEndian)

        make.copyInto(out, makeOffset)
        date.copyInto(out, dateOffset)
        secret.copyInto(out, secretOffset)
        // 37° 46' 12.34" N, 122° 25' 9.87" W as two distinct GPS rationals.
        val coordinates = intArrayOf(37, 1, 46, 1, 1234, 100, 122, 1, 25, 1, 987, 100)
        coordinates.forEachIndexed { index, value -> putU32(out, coordinateOffset + index * 4, value.toLong(), littleEndian) }
        return out
    }

    private fun gpsCoordinateBytes(littleEndian: Boolean): ByteArray = ByteArray(48).also { out ->
        val values = intArrayOf(37, 1, 46, 1, 1234, 100, 122, 1, 25, 1, 987, 100)
        values.forEachIndexed { index, value -> putU32(out, index * 4, value.toLong(), littleEndian) }
    }

    private fun thumbnailTiff(thumbnail: ByteArray): ByteArray {
        val rootOffset = 8
        val ifd1Offset = rootOffset + 6 // empty IFD0: count + next pointer
        val thumbnailOffset = ifd1Offset + 2 + 2 * ENTRY_SIZE + 4
        val out = ByteArray(thumbnailOffset + thumbnail.size)
        out[0] = 'I'.code.toByte()
        out[1] = 'I'.code.toByte()
        putU16(out, 2, 42, littleEndian = true)
        putU32(out, 4, rootOffset.toLong(), littleEndian = true)
        putU16(out, rootOffset, 0, littleEndian = true)
        putU32(out, rootOffset + 2, ifd1Offset.toLong(), littleEndian = true)

        var p = ifd1Offset
        putU16(out, p, 2, littleEndian = true)
        p += 2
        p = entry(out, p, TAG_THUMBNAIL_OFFSET, TYPE_LONG, 1, thumbnailOffset.toLong(), littleEndian = true)
        p = entry(out, p, TAG_THUMBNAIL_LENGTH, TYPE_LONG, 1, thumbnail.size.toLong(), littleEndian = true)
        putU32(out, p, 0, littleEndian = true)
        thumbnail.copyInto(out, thumbnailOffset)
        return out
    }

    private fun entry(out: ByteArray, offset: Int, tag: Int, type: Int, count: Int, value: Long, littleEndian: Boolean): Int {
        putU16(out, offset, tag, littleEndian)
        putU16(out, offset + 2, type, littleEndian)
        putU32(out, offset + 4, count.toLong(), littleEndian)
        putU32(out, offset + 8, value, littleEndian)
        return offset + ENTRY_SIZE
    }

    private fun entry(out: ByteArray, offset: Int, tag: Int, type: Int, count: Int, inline: ByteArray, littleEndian: Boolean): Int {
        putU16(out, offset, tag, littleEndian)
        putU16(out, offset + 2, type, littleEndian)
        putU32(out, offset + 4, count.toLong(), littleEndian)
        inline.copyInto(out, offset + 8)
        return offset + ENTRY_SIZE
    }

    private fun putU16(out: ByteArray, offset: Int, value: Int, littleEndian: Boolean) {
        if (littleEndian) {
            out[offset] = value.toByte()
            out[offset + 1] = (value ushr 8).toByte()
        } else {
            out[offset] = (value ushr 8).toByte()
            out[offset + 1] = value.toByte()
        }
    }

    private fun putU32(out: ByteArray, offset: Int, value: Long, littleEndian: Boolean) {
        if (littleEndian) {
            for (i in 0..3) out[offset + i] = (value ushr (i * 8)).toByte()
        } else {
            for (i in 0..3) out[offset + i] = (value ushr ((3 - i) * 8)).toByte()
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        haystack.indices.any { start -> start + needle.size <= haystack.size && needle.indices.all { haystack[start + it] == needle[it] } }

    private fun bytesFromHex(value: String): ByteArray =
        value.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private class ParsedTiff(private val bytes: ByteArray) {
        private val littleEndian = bytes[0] == 'I'.code.toByte()
        val root: Int = u32(4).toInt()

        fun find(directory: Int, tag: Int): Pair<Int, Int>? {
            val count = u16(directory)
            repeat(count) { index ->
                val p = directory + 2 + index * ENTRY_SIZE
                if (u16(p) == tag) return p to u16(p + 2)
            }
            return null
        }

        fun scalar(directory: Int, tag: Int): Long? {
            val (entry, type) = find(directory, tag) ?: return null
            return when (type) {
                TYPE_SHORT -> u16(entry + 8).toLong()
                TYPE_LONG -> u32(entry + 8)
                else -> null
            }
        }

        fun directory(offset: Long): Int = offset.toInt()

        fun nextDirectory(directory: Int): Int {
            val count = u16(directory)
            return u32(directory + 2 + count * ENTRY_SIZE).toInt()
        }

        fun ascii(directory: Int, tag: Int): String? {
            val (entry, type) = find(directory, tag) ?: return null
            if (type != TYPE_ASCII) return null
            val count = u32(entry + 4).toInt()
            val value = if (count <= 4) entry + 8 else u32(entry + 8).toInt()
            return bytes.copyOfRange(value, value + count).toString(Charsets.US_ASCII).trimEnd('\u0000')
        }

        private fun u16(offset: Int): Int {
            val a = bytes[offset].toInt() and 0xFF
            val b = bytes[offset + 1].toInt() and 0xFF
            return if (littleEndian) a or (b shl 8) else (a shl 8) or b
        }

        private fun u32(offset: Int): Long {
            val a = bytes[offset].toLong() and 0xFF
            val b = bytes[offset + 1].toLong() and 0xFF
            val c = bytes[offset + 2].toLong() and 0xFF
            val d = bytes[offset + 3].toLong() and 0xFF
            return if (littleEndian) a or (b shl 8) or (c shl 16) or (d shl 24)
            else (a shl 24) or (b shl 16) or (c shl 8) or d
        }
    }

    companion object {
        private const val ENTRY_SIZE = 12
        private const val TYPE_ASCII = 2
        private const val TYPE_SHORT = 3
        private const val TYPE_LONG = 4
        private const val TYPE_RATIONAL = 5
        private const val TYPE_UNDEFINED = 7

        private const val TAG_IMAGE_WIDTH = 0x0100
        private const val TAG_IMAGE_HEIGHT = 0x0101
        private const val TAG_MAKE = 0x010F
        private const val TAG_ORIENTATION = 0x0112
        private const val TAG_THUMBNAIL_OFFSET = 0x0201
        private const val TAG_THUMBNAIL_LENGTH = 0x0202
        private const val TAG_EXIF_IFD = 0x8769
        private const val TAG_GPS_IFD = 0x8825
        private const val TAG_DATE_TIME_ORIGINAL = 0x9003
        private const val TAG_PIXEL_X_DIMENSION = 0xA002
        private const val TAG_PIXEL_Y_DIMENSION = 0xA003
        private const val GPS_SECRET = "RAW_GPS_SECRET"
        private val EXIF_PREFIX = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
    }
}
