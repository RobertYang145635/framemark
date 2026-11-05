package com.framemark.native.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A user-supplied logo kept in the app-global library. [id] is the stable part
 * after `user:` in a design; the file format is PNG or WebP and the original
 * bytes are preserved so both renderers can decode them.
 */
data class UserLogo(
    val id: String,
    val name: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Int,
    val sha256: String,
    val addedAt: Long,
) {
    val designValue: String get() = "${UserLogoLibrary.PREFIX}$id"
    val extension: String get() = if (mime == UserLogoLibrary.MIME_WEBP) "webp" else "png"
}

data class UserLogoAsset(val logo: UserLogo, val bytes: ByteArray)

/**
 * Global, cross-project storage. Implementations own their directory and index;
 * [revision] is incremented after every successful import/delete so Compose can
 * refresh the user tab without polling.
 */
interface UserLogoRepository {
    val revision: Int
    suspend fun list(): List<UserLogo>
    suspend fun load(id: String): UserLogoAsset?
    suspend fun import(name: String, mime: String, bytes: ByteArray): UserLogo
    suspend fun delete(id: String)
}

/** App-global identity used in `PhotoDesign.logo` and every render request. */
object UserLogoLibrary {
    const val PREFIX = "user:"
    const val MIME_PNG = "image/png"
    const val MIME_WEBP = "image/webp"
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_EDGE = 4096

    var revision by mutableIntStateOf(0)
        private set

    fun changed() {
        revision++
    }

    fun isUserLogo(value: String?): Boolean = value?.startsWith(PREFIX) == true

    fun idOf(value: String?): String? =
        value?.takeIf(::isUserLogo)?.removePrefix(PREFIX)?.takeIf { it.isNotBlank() }

    /** True for PNG/WebP by declared MIME or by file extension. */
    fun normalizeMime(mime: String?, name: String): String? {
        val lower = mime?.lowercase()?.substringBefore(';')?.trim()
        if (lower == MIME_PNG || lower == MIME_WEBP) return lower
        return when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> MIME_PNG
            "webp" -> MIME_WEBP
            else -> null
        }
    }

    fun validate(width: Int, height: Int, sizeBytes: Int, mime: String?): String? {
        if (mime != MIME_PNG && mime != MIME_WEBP) return "只支持 PNG 或 WebP"
        if (sizeBytes <= 0) return "图片为空"
        if (sizeBytes > MAX_BYTES) return "图片不能超过 4 MB"
        if (width <= 0 || height <= 0) return "图片尺寸无效"
        if (width > MAX_EDGE || height > MAX_EDGE) return "图片最长边不能超过 4096"
        return null
    }

    fun safeName(raw: String, mime: String): String {
        val base = raw.substringBeforeLast('.').ifBlank { "用户 Logo" }
        val clean = base.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' }
            .trim()
            .take(60)
        val ext = if (mime == MIME_WEBP) "webp" else "png"
        return "${clean.ifBlank { "用户 Logo" }}.$ext"
    }
}

/** Last import failure for the user tab; cleared when the picker is opened. */
object UserLogoImportStatus {
    var error by mutableStateOf<String?>(null)
        private set

    fun clear() {
        error = null
    }

    fun fail(message: String) {
        error = message
    }
}

/** Installed by Android/desktop entry points; null keeps the user tab hidden. */
object UserLogoEnv {
    var repository: UserLogoRepository? = null

    /** Opens the platform image picker; the picker imports into [repository]. */
    var picker: (() -> Unit)? = null
}

/**
 * Backend capability probe. User logos are rendered only by the hand engine;
 * IR brand slots still resolve packaged assets, so the UI must not offer the
 * user library for an IR card.
 */
object UserLogoSupport {
    var probe: ((String) -> Boolean)? = null

    fun supports(cardId: String): Boolean = probe?.invoke(cardId) == true
}
