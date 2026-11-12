package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the fix for a real device failure: a design document containing an emoji
 * reached the native engine as JNI modified UTF-8, the Rust side rejected it, and
 * previews and exports stopped working until the text was cleared.
 */
class JsonTextTest {

    @Test
    fun non_bmp_text_is_escaped_to_ascii_and_still_decodes_identically () {
        val original = "😀 与 𠮷 混排 OK"
        val escaped = jsonWithSurrogatesEscaped("{\"text\":\"$original\"}")

        // The bridge breaks on characters outside the BMP, so no raw surrogate may
        // survive; BMP text (the Chinese here) is valid UTF-8 and stays readable.
        assertTrue(escaped.none { it.code in 0xD800..0xDFFF }, "raw surrogate left in: $escaped")
        assertTrue(escaped.contains("\\ud83d"), "surrogate pair must be written as two escapes: $escaped")
        assertTrue(
            jsonWithSurrogatesEscaped("{\"t\":\"😀\"}").all { it.code < 0x80 },
            "a payload that is only non-BMP text must become pure ASCII",
        )

        // The engine parses this JSON itself, so the escapes must round-trip.
        assertEquals(original, ProductJson.parseRoot(escaped)["text"])
    }

    @Test
    fun bmp_text_is_returned_untouched () {
        val json = "{\"text\":\"中文标题\"}"
        assertEquals(json, jsonWithSurrogatesEscaped(json), "Chinese is valid UTF-8 as-is")
    }

    @Test
    fun plain_ascii_is_returned_untouched () {
        val json = "{\"design\":{\"cardId\":\"simple-4\"}}"
        assertEquals(json, jsonWithSurrogatesEscaped(json))
    }

    @Test
    fun a_lone_surrogate_is_escaped_rather_than_leaking_invalid_utf8 () {
        val lone = "\uD83D"
        val escaped = jsonWithSurrogatesEscaped("{\"t\":\"$lone\"}")
        assertTrue(escaped.all { it.code < 0x80 })
        assertEquals(lone, ProductJson.parseRoot(escaped)["t"])
    }
}
