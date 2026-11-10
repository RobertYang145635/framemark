package com.framemark.native.product

/**
 * A file is larger than the caller's product limit. Thrown **before** the bytes are
 * materialised: the reader stops at `limit + 1` bytes, so a 500 MB logo is rejected
 * without entering memory.
 */
class FileTooLargeException(
    val path: String,
    val limitBytes: Int,
) : IllegalArgumentException("文件超过 ${limitBytes / 1024 / 1024} MB 上限")

/**
 * Reads [path] but never holds more than `limitBytes + 1` bytes.
 *
 * Returns null when the file does not exist. When the file is larger than the limit
 * the read aborts with [FileTooLargeException] — the bound is enforced while reading,
 * not after a full `readBytes()`, which is the difference between refusing a 500 MB
 * watermark and OOM-killing the app on it.
 *
 * Callers pass the product limit of the asset they are importing
 * ([UserLogoLibrary.MAX_BYTES] for user logos, [WatermarkAsset.MAX_BYTES] for
 * watermark/brand assets).
 */
internal expect fun readFileBytesBounded(path: String, limitBytes: Int): ByteArray?
