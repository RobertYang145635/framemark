package com.framemark.native.product

/**
 * 最小 EXIF/TIFF 读取器：只按标签取 ASCII 值，不建索引、不猜。
 *
 * 存在的理由：导出命名的「拍摄日期 / 拍摄时间」必须是照片自己的
 * `DateTimeOriginal`，而界面侧唯一的历史做法是读当前系统时钟（`dateStamp()`），
 * 于是 2022-07-05 拍的样片被命名成当天日期。读 EXIF 的容器解析只有这一份：
 * [ExifMake]（照片品牌）与 [ExifCaptureDate]（拍摄时间）都用它，避免两套
 * JPEG/WebP/TIFF 头解析各自漂移。
 *
 * 只读文件头部就够：JPEG 的 APP1 在最前面，256 KB 覆盖任何相机写入的 EXIF。
 */
internal object ExifTiff {

    /** TIFF 头的一层 IFD；[base] 是 TIFF 起点（大端/小端的字节序从这里判定）。 */
    private class Tiff(val bytes: ByteArray, val base: Int, val little: Boolean) {
        fun ifdOffset(pointer: Int): Int? {
            if (pointer < 0 || pointer + 4 > bytes.size) return null
            return base + u32(pointer)
        }

        fun entries(ifd: Int): List<Int> {
            if (ifd < 0 || ifd + 2 > bytes.size) return emptyList()
            val count = u16(ifd)
            if (count <= 0 || count > 512) return emptyList()
            return (0 until count).map { ifd + 2 + it * 12 }.filter { it + 12 <= bytes.size }
        }

        fun tag(entry: Int): Int = u16(entry)

        fun type(entry: Int): Int = u16(entry + 2)

        fun count(entry: Int): Int = u32(entry + 4)

        fun valueStart(entry: Int, length: Int): Int? {
            if (length <= 0) return null
            val start = if (length <= 4) entry + 8 else base + u32(entry + 8)
            if (start < 0 || start >= bytes.size) return null
            return start
        }

        fun u32(offset: Int): Int = u32(bytes, offset, little)

        fun u16(offset: Int): Int = u16(bytes, offset, little)
    }

    private fun tiffAt(bytes: ByteArray): Tiff? {
        val base = when {
            isJpeg(bytes) -> jpegTiffStart(bytes)
            isTiff(bytes) -> 0
            isWebp(bytes) -> webpTiffStart(bytes)
            else -> null
        } ?: return null
        if (base + 8 > bytes.size) return null
        val little = when {
            u8(bytes, base) == 'I'.code && u8(bytes, base + 1) == 'I'.code -> true
            u8(bytes, base) == 'M'.code && u8(bytes, base + 1) == 'M'.code -> false
            else -> return null
        }
        return Tiff(bytes, base, little)
    }

    /**
     * 读一个 ASCII 标签（4 字节内内联，否则按偏移取）。[subIfdTag] 是要进入的
     * 子 IFD 指针标签（读 `DateTimeOriginal` 时是 0x8769）；null 表示只读 IFD0。
     */
    fun readAscii(bytes: ByteArray, tag: Int, subIfdTag: Int? = null): String? {
        val tiff = tiffAt(bytes) ?: return null
        val ifd0 = tiff.ifdOffset(tiff.base + 4) ?: return null
        val ifd = if (subIfdTag != null) {
            tiff.entries(ifd0)
                .firstOrNull { tiff.tag(it) == subIfdTag }
                ?.let { tiff.ifdOffset(it + 8) }
                ?: return null
        } else {
            ifd0
        }
        for (entry in tiff.entries(ifd)) {
            if (tiff.tag(entry) != tag) continue
            if (tiff.type(entry) != 2) return null
            val length = tiff.count(entry)
            if (length <= 0 || length > MAX_TEXT) return null
            val start = tiff.valueStart(entry, length) ?: return null
            val end = minOf(start + length, bytes.size)
            return bytes.copyOfRange(start, end).decodeToString()
        }
        return null
    }

    private const val MAX_TEXT = 4096

    // -- 容器 ------------------------------------------------------------------

    private fun isJpeg(b: ByteArray): Boolean =
        b.size >= 4 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8

    private fun isTiff(b: ByteArray): Boolean = b.size >= 8 &&
        ((u8(b, 0) == 'I'.code && u8(b, 1) == 'I'.code && u8(b, 2) == 42 && u8(b, 3) == 0) ||
            (u8(b, 0) == 'M'.code && u8(b, 1) == 'M'.code && u8(b, 2) == 0 && u8(b, 3) == 42))

    private fun isWebp(b: ByteArray): Boolean = b.size >= 12 &&
        ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP"

    /** JPEG 里第一个带 `Exif\0\0` 的 APP1，返回它后面 TIFF 头的起点。 */
    private fun jpegTiffStart(bytes: ByteArray): Int? {
        var i = 2
        while (i + 4 <= bytes.size) {
            if (u8(bytes, i) != 0xFF) return null
            val marker = u8(bytes, i + 1)
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                i += 2
                continue
            }
            if (marker == 0xD9 || marker == 0xDA) return null
            val length = (u8(bytes, i + 2) shl 8) or u8(bytes, i + 3)
            if (length < 2 || i + 2 + length > bytes.size) return null
            if (marker == 0xE1 && isExifPrefix(bytes, i + 4, i + 2 + length)) {
                return (i + 10).takeIf { it + 8 <= bytes.size }
            }
            i += 2 + length
        }
        return null
    }

    /** WebP 的 `EXIF` 块，返回它后面 TIFF 头的起点。 */
    private fun webpTiffStart(bytes: ByteArray): Int? {
        var i = 12
        while (i + 8 <= bytes.size) {
            val fourcc = ascii(bytes, i, 4)
            val size = u8(bytes, i + 4) or (u8(bytes, i + 5) shl 8) or
                (u8(bytes, i + 6) shl 16) or (u8(bytes, i + 7) shl 24)
            if (size < 0 || i + 8 + size > bytes.size) return null
            if (fourcc == "EXIF") {
                val start = i + 8
                val end = start + size
                val tiffStart = if (isExifPrefix(bytes, start, end)) start + 6 else start
                return tiffStart.takeIf { it + 8 <= bytes.size }
            }
            i += 8 + size + (size and 1)
        }
        return null
    }

    private fun isExifPrefix(bytes: ByteArray, start: Int, end: Int): Boolean =
        end - start >= 6 &&
            u8(bytes, start) == 'E'.code && u8(bytes, start + 1) == 'x'.code &&
            u8(bytes, start + 2) == 'i'.code && u8(bytes, start + 3) == 'f'.code &&
            u8(bytes, start + 4) == 0 && u8(bytes, start + 5) == 0

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || offset + length > bytes.size) return ""
        return bytes.copyOfRange(offset, offset + length).decodeToString()
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private fun u16(b: ByteArray, i: Int, little: Boolean): Int =
        if (little) u8(b, i) or (u8(b, i + 1) shl 8) else (u8(b, i) shl 8) or u8(b, i + 1)

    private fun u32(b: ByteArray, i: Int, little: Boolean): Int =
        if (little) {
            u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)
        } else {
            (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
        }
}
