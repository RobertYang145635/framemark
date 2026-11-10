package com.framemark.native.product

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * JVM bounded read. The size is checked before opening, and the stream is also
 * capped at `limit + 1` bytes so a file that grows between the check and the read
 * still fails instead of being loaded.
 */
internal actual fun readFileBytesBounded(path: String, limitBytes: Int): ByteArray? {
    val file = File(path)
    if (!file.isFile) return null
    require(limitBytes > 0) { "limitBytes 必须为正数" }
    if (file.length() > limitBytes) throw FileTooLargeException(path, limitBytes)
    FileInputStream(file).use { stream ->
        val out = ByteArrayOutputStream(minOf(file.length(), limitBytes.toLong()).toInt().coerceAtLeast(1))
        val buffer = ByteArray(minOf(64 * 1024, limitBytes + 1))
        var total = 0
        while (true) {
            val read = stream.read(buffer, 0, minOf(buffer.size, limitBytes + 1 - total))
            if (read <= 0) break
            total += read
            if (total > limitBytes) throw FileTooLargeException(path, limitBytes)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
