package com.framemark.native.product

/**
 * 照片 EXIF 的 `Make`（IFD0 tag 0x010F）读取器 —— **两个平台共用这一份解析**。
 *
 * 为什么要有它：字标「跟随照片品牌」在引擎里从来不是自动的。手写卡的设计里烤着
 * 样片字标，`design.logo` 缺席时引擎就画那一枚（OPPO 照片仍显示 Panasonic）；
 * 平台侧能拿到「照片是什么品牌」的唯一来源是文件自己的 EXIF Make。
 *
 * 只做容器 → TIFF → IFD0 的定位，不解释别的 tag、不读像素：
 *  - JPEG：APP1 里 `Exif\0\0` 之后的 TIFF 头（最容易找到的那一个，与 Orientation 同一条路径）；
 *  - TIFF：文件本身就是 TIFF 头；
 *  - WebP：RIFF 里的 `EXIF` 块（有的写入器会再带一遍 `Exif\0\0` 前缀，两种都认）。
 *
 * HEIC/HEIF 的容器解析不在这里：安卓导入时会把 Exif 项写成侧车 JPEG
 * （`files/exif/<photo>.jpg`，见 `Heif.exifBlock` / `MainActivity` 的导入分支），
 * 调用方对像素文件读不到时再读那张侧车。
 *
 * 全程不抛异常：读不懂就是「这张照片没有品牌」，调用方退回引擎的默认字标。
 */
object ExifMake {

    /** IFD0 `Make`。 */
    private const val TAG_MAKE = 0x010F

    /** TIFF 的字段类型：2 = ASCII。别的类型出现在 Make 上说明数据坏了，不猜。 */
    private const val TYPE_ASCII = 2

    /** Make 的合理上限：厂商名不会比这长，超过就是偏移读歪了。 */
    private const val MAX_LENGTH = 4096

    /**
     * 一张照片头部的 EXIF `Make`，规范化后返回；没有 / 读不懂 → null。
     *
     * [bytes] 只需要文件头部（JPEG APP1 最多 64 KB，256 KB 足够覆盖），
     * 不需要整张照片。
     */
    fun read(bytes: ByteArray): String? = runCatching {
        when {
            isJpeg(bytes) -> jpegTiffStart(bytes)?.let { tiffString(bytes, it) }
            isTiff(bytes) -> tiffString(bytes, 0)
            isWebp(bytes) -> webpTiffStart(bytes)?.let { tiffString(bytes, it) }
            else -> null
        }
    }.getOrNull()?.let { clean(it) }

    /**
     * 规范化厂商名：去掉 NUL / 控制字符 / 替换符，压掉多余空白。
     * 全空（只有 NUL 的 Make 很常见）就是没有品牌，返回 null。
     */
    fun clean(raw: String?): String? {
        val text = raw.orEmpty()
            .map { char -> if (char.isISOControl() || char == '\uFFFD') ' ' else char }
            .joinToString("")
            .trim()
            .replace(Whitespace, " ")
        return text.ifEmpty { null }
    }

    private val Whitespace = Regex("\\s+")

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

    // -- TIFF IFD0 -------------------------------------------------------------

    /** 从 [base] 处的 TIFF 头读 IFD0 的 Make（ASCII，4 字节内内联，否则按偏移取）。 */
    private fun tiffString(bytes: ByteArray, base: Int): String? {
        if (base < 0 || base + 8 > bytes.size) return null
        val little = when {
            u8(bytes, base) == 'I'.code && u8(bytes, base + 1) == 'I'.code -> true
            u8(bytes, base) == 'M'.code && u8(bytes, base + 1) == 'M'.code -> false
            else -> return null
        }
        val ifd = base + u32(bytes, base + 4, little)
        if (ifd < 0 || ifd + 2 > bytes.size) return null
        val count = u16(bytes, ifd, little)
        if (count <= 0 || count > 512) return null
        for (index in 0 until count) {
            val entry = ifd + 2 + index * 12
            if (entry + 12 > bytes.size) return null
            if (u16(bytes, entry, little) != TAG_MAKE) continue
            if (u16(bytes, entry + 2, little) != TYPE_ASCII) return null
            val length = u32(bytes, entry + 4, little)
            if (length <= 0 || length > MAX_LENGTH) return null
            val start = if (length <= 4) entry + 8 else base + u32(bytes, entry + 8, little)
            if (start < 0 || start >= bytes.size) return null
            val end = minOf(start + length, bytes.size)
            return bytes.copyOfRange(start, end).decodeToString()
        }
        return null
    }

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
