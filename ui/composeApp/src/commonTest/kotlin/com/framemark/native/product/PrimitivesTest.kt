package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PrimitivesTest {
    @Test
    fun sha256_matches_published_vectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc".encodeToByteArray()),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            Sha256.hex(ByteArray(1_000_000) { 'a'.code.toByte() }),
        )
    }

    @Test
    fun base64_matches_rfc4648_vectors() {
        assertEquals("", Base64Codec.encode(ByteArray(0)))
        assertEquals("Zg==", Base64Codec.encode("f".encodeToByteArray()))
        assertEquals("Zm8=", Base64Codec.encode("fo".encodeToByteArray()))
        assertEquals("Zm9v", Base64Codec.encode("foo".encodeToByteArray()))
        assertEquals("Zm9vYg==", Base64Codec.encode("foob".encodeToByteArray()))
        assertEquals("Zm9vYmE=", Base64Codec.encode("fooba".encodeToByteArray()))
        assertEquals("Zm9vYmFy", Base64Codec.encode("foobar".encodeToByteArray()))
        assertEquals("foobar", Base64Codec.decode("Zm9vYmFy").decodeToString())
    }

    @Test
    fun base64_round_trips_binary_and_one_megabyte() {
        val binary = ByteArray(256) { it.toByte() }
        assertContentEquals(binary, Base64Codec.decode(Base64Codec.encode(binary)))

        val megabyte = ByteArray(1024 * 1024) { ((it * 31 + 7) and 0xff).toByte() }
        val encoded = Base64Codec.encode(megabyte)
        assertTrue(encoded.length >= 1_398_100, "1MiB base64 长度异常：${encoded.length}")
        assertContentEquals(megabyte, Base64Codec.decode(encoded))
    }

    @Test
    fun base64_rejects_malformed_input() {
        assertFailsWith<IllegalArgumentException> { Base64Codec.decode("Zm9") }
        assertFailsWith<IllegalArgumentException> { Base64Codec.decode("Zm9v!") }
        assertFailsWith<IllegalArgumentException> { Base64Codec.decode("Z===") }
        assertFailsWith<IllegalArgumentException> { Base64Codec.decode("ZQ=A") }
    }

    @Test
    fun design_values_normalize_numbers_and_reject_junk() {
        val normalized = DesignValues.normalize(
            mapOf(
                "inset" to 0.1f,
                "bold" to true,
                "model" to "X-T5",
                "cleared" to null,
                "size" to 24,
            ),
            "params",
        )
        assertEquals(0.1, normalized["inset"])
        assertEquals(0.1f.toString().toDouble(), normalized["inset"])
        assertEquals(true, normalized["bold"])
        assertEquals("X-T5", normalized["model"])
        assertEquals(24.0, normalized["size"])
        assertTrue("cleared" !in normalized)
        assertFailsWith<IllegalArgumentException> { DesignValues.normalize(mapOf("bad" to Double.NaN), "params") }
        assertFailsWith<IllegalArgumentException> { DesignValues.normalize(mapOf("bad" to listOf(1)), "params") }
    }

    @Test
    fun json_encoder_is_deterministic_and_rejects_non_finite() {
        val values = mapOf("b" to 0.5, "a" to true, "c" to "文字")
        assertEquals("{\"a\":true,\"b\":0.5,\"c\":\"文字\"}", ProductJson.encodeValues(values))
        assertEquals("{\"a\":true,\"b\":0.5,\"c\":\"文字\"}", ProductJson.encodeValues(values.toList().reversed().toMap()))
        assertFailsWith<IllegalArgumentException> { ProductJson.encodeValue(Double.POSITIVE_INFINITY) }
        assertEquals("0.8", ProductJson.number(0.8f))
    }
}
