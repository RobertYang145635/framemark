package com.framemark.native.product

import java.io.File
import java.security.MessageDigest

/**
 * Android streaming SHA-256: same contract as the desktop actual (fixed 64 KiB
 * buffer, null when the file is absent or unreadable). The project document can
 * embed a 32 MiB watermark, so the staged-file check must not read it back in full.
 */
internal actual fun sha256OfFile(path: String): String? = runCatching {
    val file = File(path)
    if (!file.isFile) return@runCatching null
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}.getOrNull()
