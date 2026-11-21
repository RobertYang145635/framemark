package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 导出命名的「拍摄日期 / 拍摄时间」必须来自照片自己的 `DateTimeOriginal`
 * （WP-Q2④：2022-07-05 的样片被命名成当天日期，按拍摄日归档会分错日子）。
 */
class ExifCaptureDateTest {

    @Test
    fun the_camera_writing_is_normalised_into_the_product_format() {
        assertEquals("2022-07-05 14:13:37", ExifCaptureDate.normalize("2022:07:05 14:13:37"))
        // 尾部 NUL（EXIF ASCII 常见）被忽略。
        assertEquals("2022-07-05 14:13:37", ExifCaptureDate.normalize("2022:07:05 14:13:37\u0000"))
        assertEquals("20220705", ExifCaptureDate.dateToken("2022:07:05 14:13:37"))
        assertEquals("141337", ExifCaptureDate.timeToken("2022:07:05 14:13:37"))
    }

    @Test
    fun nonsense_is_not_a_capture_time() {
        assertNull(ExifCaptureDate.normalize(null))
        assertNull(ExifCaptureDate.normalize(""))
        // 相机没写时间时的占位写法。
        assertNull(ExifCaptureDate.normalize("0000:00:00 00:00:00"))
        assertNull(ExifCaptureDate.normalize("2022:13:05 14:13:37"))
        assertNull(ExifCaptureDate.normalize("2022:07:05 24:13:37"))
        assertNull(ExifCaptureDate.normalize("看不懂"))
        assertNull(ExifCaptureDate.dateToken("2022:13:05 14:13:37"))
    }

    /** 真实的 JPEG/APP1/TIFF 三层结构：IFD0 → Exif SubIFD(0x8769) → 0x9003。 */
    private fun exifJpeg(capture: String): ByteArray {
        val text = capture.toByteArray(Charsets.US_ASCII) + 0
        // TIFF（大端 "MM\0*"）：头 8 字节，IFD0 在 8，subIFD 在 8 + 2 + 12 + 4 = 26，
        // 值区在 26 + 2 + 12 + 4 = 44。
        val subIfd = 26
        val valueAt = 44
        val tiff = ByteArray(valueAt + text.size)
        fun put(offset: Int, value: Int) {
            tiff[offset] = (value ushr 24).toByte()
            tiff[offset + 1] = (value ushr 16).toByte()
            tiff[offset + 2] = (value ushr 8).toByte()
            tiff[offset + 3] = value.toByte()
        }
        fun putShort(offset: Int, value: Int) {
            tiff[offset] = (value ushr 8).toByte()
            tiff[offset + 1] = value.toByte()
        }
        tiff[0] = 'M'.code.toByte()
        tiff[1] = 'M'.code.toByte()
        putShort(2, 42)
        put(4, 8)
        // IFD0：一个条目（Exif SubIFD 指针）
        putShort(8, 1)
        putShort(10, 0x8769)
        putShort(12, 4) // LONG
        put(14, 1)
        put(18, subIfd)
        put(22, 0) // next IFD
        // SubIFD：一个条目（DateTimeOriginal）
        putShort(subIfd, 1)
        putShort(subIfd + 2, 0x9003)
        putShort(subIfd + 4, 2) // ASCII
        put(subIfd + 6, text.size)
        put(subIfd + 10, valueAt)
        put(subIfd + 14, 0)
        text.copyInto(tiff, valueAt)
        // 包进 EXIF APP1
        val payload = "Exif".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0) + tiff
        val segmentLength = payload.size + 2
        val app1 = ByteArray(4) { 0 } + payload
        app1[0] = 0xFF.toByte()
        app1[1] = 0xE1.toByte()
        app1[2] = (segmentLength ushr 8).toByte()
        app1[3] = segmentLength.toByte()
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1 + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    @Test
    fun the_reader_finds_datetime_original_in_the_exif_sub_ifd() {
        assertEquals(
            "2022-07-05 14:13:37",
            ExifCaptureDate.read(exifJpeg("2022:07:05 14:13:37")),
        )
        // 不是 EXIF 的字节不会抛异常，只是没有拍摄时间。
        assertNull(ExifCaptureDate.read(byteArrayOf(1, 2, 3, 4)))
    }
}
