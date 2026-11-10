package com.framemark.native.product

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The import limits (`UserLogoLibrary.MAX_BYTES` 4 MB, `WatermarkAsset.MAX_BYTES`
 * 32 MB) used to be checked **after** `File.readBytes()`, so a 500 MB pick was fully
 * loaded before being refused. [readFileBytesBounded] enforces the bound while
 * reading; these tests pin the boundary (exactly the limit / one byte past it),
 * which is the part an off-by-one would quietly get wrong. A real 500 MB fixture is
 * not needed: the helper refuses on size before allocating, so a 17-byte file over a
 * 16-byte limit exercises the same branch.
 */
class BoundedReadTest {

    private fun file(bytes: Int, name: String): String {
        val dir = Files.createTempDirectory("fm-bounded-")
        val path = dir.resolve(name)
        Files.write(path, ByteArray(bytes) { (it % 251).toByte() })
        return path.toString()
    }

    @Test
    fun exactly_the_limit_is_accepted() {
        val path = file(bytes = 16, name = "exact.bin")
        val bytes = readFileBytesBounded(path, 16)
        assertContentEquals(ByteArray(16) { (it % 251).toByte() }, bytes)
    }

    @Test
    fun one_byte_over_the_limit_is_refused() {
        val path = file(bytes = 17, name = "over.bin")
        val error = assertFailsWith<FileTooLargeException> { readFileBytesBounded(path, 16) }
        assertEquals(16, error.limitBytes)
        assertTrue(error.message!!.isNotBlank(), "拒绝时必须带可读的原因")
    }

    @Test
    fun a_far_larger_file_is_refused_by_the_cheap_size_check() {
        // 8 MB against the 4 MB user-logo limit: refused without reading the payload.
        val path = file(bytes = 8 * 1024 * 1024, name = "huge.bin")
        assertFailsWith<FileTooLargeException> {
            readFileBytesBounded(path, 4 * 1024 * 1024)
        }
    }

    @Test
    fun a_missing_file_is_null_not_failure() {
        assertNull(readFileBytesBounded("/definitely/not/here/logo.png", 1024))
    }

    @Test
    fun an_empty_file_reads_as_zero_bytes() {
        val path = file(bytes = 0, name = "empty.bin")
        assertContentEquals(ByteArray(0), readFileBytesBounded(path, 16))
    }
}
