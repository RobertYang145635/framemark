package com.framemark.native.product

/**
 * 照片的**拍摄时间**（EXIF `DateTimeOriginal`），导出命名用。
 *
 * 过去 `{date}` / `{time}` 取的是当前系统时钟：样例照片拍于 2022-07-05 14:13:37，
 * 单张导出却叫 `portrait-320261121224134.jpg`，按拍摄日归档会分错日期。
 *
 * 读取顺序：Exif SubIFD 的 `DateTimeOriginal`（0x9003，拍下的那一刻）→ IFD0 的
 * `DateTime`（0x0132，修改时间，重存过的照片只有它）。两者都是
 * `YYYY:MM:DD HH:MM:SS`，统一规范成 `yyyy-MM-dd HH:mm:ss`；读不到返回 null，
 * 调用方必须自己说清楚用了什么替代值，不能默默用系统时间。
 */
object ExifCaptureDate {

    private const val TAG_DATETIME = 0x0132
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_DATETIME_ORIGINAL = 0x9003

    /** `[bytes]`（文件头部即可）里的拍摄时间，规范成 `yyyy-MM-dd HH:mm:ss`。 */
    fun read(bytes: ByteArray): String? {
        val original = ExifTiff.readAscii(bytes, TAG_DATETIME_ORIGINAL, subIfdTag = TAG_EXIF_IFD)
        val modified = ExifTiff.readAscii(bytes, TAG_DATETIME)
        return normalize(original) ?: normalize(modified)
    }

    /**
     * `YYYY:MM:DD HH:MM:SS`（相机写法）→ `yyyy-MM-dd HH:mm:ss`。
     * 全零（`0000:00:00 00:00:00` 很常见）或任何一段越界都返回 null。
     */
    fun normalize(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.length >= 19 } ?: return null
        val date = text.substring(0, 10)
        val time = text.substring(11, 19)
        val parts = date.split(':', '-', '/')
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (year < 1800 || year > 2200) return null
        if (month !in 1..12 || day !in 1..31) return null
        val clock = time.split(':')
        if (clock.size != 3) return null
        val hour = clock[0].toIntOrNull() ?: return null
        val minute = clock[1].toIntOrNull() ?: return null
        val second = clock[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        return buildString {
            append(parts[0].padStart(4, '0')).append('-')
            append(parts[1].padStart(2, '0')).append('-')
            append(parts[2].padStart(2, '0')).append(' ')
            append(clock[0].padStart(2, '0')).append(':')
            append(clock[1].padStart(2, '0')).append(':')
            append(clock[2].padStart(2, '0'))
        }
    }

    /** `yyyy-MM-dd HH:mm:ss` → `yyyyMMdd`（命名里的 `{date}`）。 */
    fun dateToken(capturedAt: String): String? =
        normalize(capturedAt)?.take(10)?.replace("-", "")

    /** `yyyy-MM-dd HH:mm:ss` → `HHmmss`（命名里的 `{time}`）。 */
    fun timeToken(capturedAt: String): String? =
        normalize(capturedAt)?.substring(11, 19)?.replace(":", "")
}
