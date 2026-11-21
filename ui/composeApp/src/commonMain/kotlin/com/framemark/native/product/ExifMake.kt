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

    /**
     * 一张照片头部的 EXIF `Make`，规范化后返回；没有 / 读不懂 → null。
     *
     * [bytes] 只需要文件头部（JPEG APP1 最多 64 KB，256 KB 足够覆盖），
     * 不需要整张照片。
     */
    fun read(bytes: ByteArray): String? = runCatching {
        ExifTiff.readAscii(bytes, TAG_MAKE)
    }.getOrNull()?.let { clean(it) }

    /**
     * 规范化厂商名：去掉 NUL / 控制字符 / 替换符，压掉多余空白。
     * 全空（只有 NUL 的 Make 很常见）就是没有品牌，返回 null。
     */
    fun clean(raw: String?): String? {
        val text = raw.orEmpty()
            .map { char -> if (char.isISOControl() || char == '�') ' ' else char }
            .joinToString("")
            .trim()
            .replace(Whitespace, " ")
        return text.ifEmpty { null }
    }

    private val Whitespace = Regex("\\s+")
}
