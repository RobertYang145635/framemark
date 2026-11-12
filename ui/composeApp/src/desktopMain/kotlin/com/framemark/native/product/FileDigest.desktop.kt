package com.framemark.native.product

import java.io.File
import java.security.MessageDigest

/**
 * Desktop streaming SHA-256: a fixed 64 KiB buffer, so hashing a 50 MiB document
 * never allocates a second 50 MiB array just to compare it with the bytes that were
 * written.
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
