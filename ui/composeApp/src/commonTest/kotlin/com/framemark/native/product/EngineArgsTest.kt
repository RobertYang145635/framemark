package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP-X1 / AU1-05: `theme` is an enum at every boundary.
 *
 * The injection the review proved: the OS splits one argv element on quotes, so
 * `theme = x" --out C:\evil.png --theme` became extra engine arguments.
 */
class EngineArgsTest {
    private val injected = "x\" --out C:\\evil.png --theme"

    @Test
    fun onlyRegisteredThemesAreAccepted() {
        assertEquals("white", EngineArgs.knownTheme("white"))
        assertEquals("black", EngineArgs.knownTheme("black"))
        assertNull(EngineArgs.knownTheme("plaid"))
        assertNull(EngineArgs.knownTheme(""))
        assertNull(EngineArgs.knownTheme(null))
        assertNull(EngineArgs.knownTheme(injected))
    }

    @Test
    fun unSafeThemeIsDroppedInsteadOfPassingThrough() {
        var rejected: String? = null
        val value = EngineArgs.themeForArgv(injected) { rejected = it }
        assertNull(value, "a splittable theme must not reach argv at all")
        assertEquals(injected, rejected, "the rejected value must be reportable")
        assertNull(EngineArgs.themeForArgv(""))
        assertNull(EngineArgs.themeForArgv(null))
        // IR styles legitimately use their own swatch ids; the hand enum is a
        // subset, not the whole set (regression: rejecting `glass` broke preset carry).
        assertEquals("glass", EngineArgs.themeForArgv("glass"))
        assertEquals("white", EngineArgs.themeForArgv("white"))
        assertNull(EngineArgs.handTheme("glass"))
        assertEquals("white", EngineArgs.handTheme("white"))
    }

    @Test
    fun argvSafeLiteralRejectsQuotesSpacesAndOptionPrefixes() {
        assertTrue(EngineArgs.isArgvSafeLiteral("white"))
        assertTrue(EngineArgs.isArgvSafeLiteral("wm-classic"))
        // A bare option-looking literal is still one argv element — it cannot split.
        assertTrue(EngineArgs.isArgvSafeLiteral("--out"))
        // What must never pass is anything the OS would re-split or that carries
        // control characters.
        assertFalse(EngineArgs.isArgvSafeLiteral(injected))
        assertFalse(EngineArgs.isArgvSafeLiteral("with space"))
        assertFalse(EngineArgs.isArgvSafeLiteral("x\ny"))
        assertFalse(EngineArgs.isArgvSafeLiteral(""))
    }

    /** The preset import path rejects the document instead of sanitising silently. */
    @Test
    fun presetWithInjectedThemeIsRejectedAtDecode() {
        val raw = mapOf(
            "cardId" to "wm-classic",
            "theme" to injected,
            "params" to emptyMap<String, Any?>(),
            "text" to emptyMap<String, Any?>(),
        )
        val failure = assertFailsWith<ProductDecodeException> { decodeDesign(raw, "preset.design") }
        assertTrue(failure.message.orEmpty().contains("theme"), "message: ${failure.message}")
        assertTrue(failure.message.orEmpty().contains("无法安全传给引擎"), "message: ${failure.message}")
    }

    /** An IR swatch id is a legitimate theme and must still decode. */
    @Test
    fun irSwatchIdStillDecodes() {
        val raw = mapOf(
            "cardId" to "simple-1",
            "theme" to "glass",
            "params" to emptyMap<String, Any?>(),
            "text" to emptyMap<String, Any?>(),
        )
        assertEquals("glass", decodeDesign(raw, "preset.design").theme)
    }

    @Test
    fun registeredThemeStillDecodes() {
        val raw = mapOf(
            "cardId" to "wm-classic",
            "theme" to "ink",
            "params" to emptyMap<String, Any?>(),
            "text" to emptyMap<String, Any?>(),
        )
        assertEquals("ink", decodeDesign(raw, "preset.design").theme)
    }
}
