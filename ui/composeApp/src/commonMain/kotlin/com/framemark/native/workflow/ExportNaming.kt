package com.framemark.native.workflow

/**
 * Export file naming: `{photo}` = source photo stem, `{index}` = the 1-based
 * position of that photo in the project, `{name}` = card name, `{date}` / `{time}`.
 * The default pattern `{photo}-{index}` keeps every batch item on its own file.
 *
 * `{date}` / `{time}` are the photo's **own** capture time (`DateTimeOriginal`),
 * not the clock: a 2022-07-05 sample exported today used to be named
 * `portrait-320261121224134.jpg`, which files the shot under the wrong day.
 * Only a photo without any EXIF time falls back to the current clock, and the
 * export sheets say so (`ExportCopy.captureTimeNote`).
 */
object ExportNaming {
    fun exportPath(
        platform: WorkflowPlatform,
        pattern: String,
        cardName: String,
        photoName: String?,
        extension: String,
        index: Int = 1,
        outputDirectory: String? = null,
        capturedAt: String? = null,
    ): String = uniquePath(
        platform,
        resolveName(pattern, cardName, photoName, platform, index, capturedAt = capturedAt),
        extension,
        outputDirectory,
    )

    /** A deterministic collision candidate; final creation must still be no-clobber. */
    fun candidatePath(
        platform: WorkflowPlatform,
        pattern: String,
        cardName: String,
        photoName: String?,
        extension: String,
        index: Int = 1,
        collisionNumber: Int = 1,
        outputDirectory: String? = null,
        capturedAt: String? = null,
    ): String {
        val base = resolveName(pattern, cardName, photoName, platform, index, capturedAt = capturedAt)
        val collisionSuffix = if (collisionNumber <= 1) "" else "-$collisionNumber"
        val dir = (outputDirectory?.takeIf { it.isNotBlank() } ?: platform.exportDir).trimEnd('/', '\\')
        return "$dir/$base$collisionSuffix.$extension"
    }

    fun previewName(
        pattern: String,
        cardName: String,
        photoName: String? = null,
        index: Int = 1,
        capturedAt: String? = null,
    ): String = resolveName(pattern, cardName, photoName, WorkflowEnv.platform, index, capturedAt = capturedAt)

    /** Source photo stem (`/a/b/IMG_1.jpg` → `IMG_1`), null when there is no path. */
    fun photoStem(path: String?): String? {
        val name = path?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() } ?: return null
        return name.substringBeforeLast('.')
    }

    /** Fallback used when the pattern asks for a photo but the request has none. */
    fun photoNameFor(pattern: String, photoName: String?, fallback: String): String =
        if (photoName.isNullOrBlank() && pattern.contains("{photo}")) fallback else photoName.orEmpty()

    private fun resolveName(
        pattern: String,
        cardName: String,
        photoName: String?,
        platform: WorkflowPlatform?,
        index: Int,
        capturedAt: String? = null,
    ): String {
        // 拍摄时间优先；没有就用当前时钟（调用方会把这件事写在界面上）。
        val date = capturedAt?.let { com.framemark.native.product.ExifCaptureDate.dateToken(it) }
            ?: platform?.dateStamp() ?: "20260101"
        val time = capturedAt?.let { com.framemark.native.product.ExifCaptureDate.timeToken(it) }
            ?: platform?.timeStamp() ?: "000000"
        val filled = pattern
            .replace("{name}", cardName)
            .replace("{photo}", photoName.orEmpty())
            .replace("{date}", date)
            .replace("{time}", time)
            .replace("{index}", index.coerceAtLeast(1).toString())
        return sanitize(filled)
    }

    private fun sanitize(raw: String): String {
        val cleaned = buildString(raw.length) {
            raw.forEach { c ->
                when {
                    c in "\\/:*?\"<>|" -> append('-')
                    c.code < 32 -> Unit
                    else -> append(c)
                }
            }
        }.replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        return cleaned.take(80).ifBlank { "framemark" }
    }

    private fun uniquePath(platform: WorkflowPlatform, base: String, extension: String, outputDirectory: String?): String {
        val dir = (outputDirectory?.takeIf { it.isNotBlank() } ?: platform.exportDir).trimEnd('/', '\\')
        val first = "$dir/$base.$extension"
        if (!platform.exists(first)) return first
        for (index in 2..99) {
            val candidate = "$dir/$base-$index.$extension"
            if (!platform.exists(candidate)) return candidate
        }
        return "$dir/$base-${platform.nowMillis()}.$extension"
    }
}
