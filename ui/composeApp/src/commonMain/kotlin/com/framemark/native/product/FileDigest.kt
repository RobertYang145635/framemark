package com.framemark.native.product

/**
 * SHA-256 of a file, computed by streaming its bytes.
 *
 * The store verifies a staged document by hashing the temporary file after the
 * write instead of reading it back in full, so the comparison must not materialise
 * a second copy of a document that can embed a 32 MiB watermark as base64. Both
 * shipped targets implement this with `MessageDigest` over a fixed 64 KiB buffer;
 * in-memory adapters ([ProductFileSystem.sha256Of]) override the interface method
 * instead, because a fake file already lives in memory and cannot stream.
 *
 * Returns null when the file is absent or unreadable, exactly like the other read
 * hooks: the caller reports a structured [StoreError] instead of an exception.
 */
internal expect fun sha256OfFile(path: String): String?
