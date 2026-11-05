package com.framemark.native.inspector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** WP-C A-04 inspector rule: shrink to 80 % at most, then truncate with one ellipsis. */
class TextFitTest {

    @Test
    fun aShortLineIsReturnedUnchanged() {
        assertEquals("哈苏", FmTextFit.ellipsize("哈苏", 10))
        assertEquals("", FmTextFit.ellipsize("   ", 10))
    }

    @Test
    fun aLongLineEndsWithExactlyOneEllipsis() {
        val out = FmTextFit.ellipsize("一二三四五六七八九十", 5)
        assertEquals("一二三四…", out)
        assertEquals(1, out.count { it == '…' })
        assertEquals(5, out.length)
    }

    @Test
    fun lineBreaksNeverProduceAMultiLineRow() {
        val out = FmTextFit.ellipsize("上\n下", 10)
        assertFalse(out.contains('\n'))
        assertEquals("上 下", out)
    }

    @Test
    fun aSingleCharacterBudgetStillShowsTheEllipsis() {
        assertEquals("…", FmTextFit.ellipsize("abcdef", 1))
        assertEquals("…", FmTextFit.ellipsize("abcdef", 0))
    }

    @Test
    fun shrinkingIsCappedAtEightyPercent() {
        // Fits after a 20 % shrink.
        assertEquals(0.9f, FmTextFit.scaleFor(intrinsicWidth = 100f, availableWidth = 90f), 1e-4f)
        // Cannot shrink below 0.8 even when the width asks for it.
        assertEquals(0.8f, FmTextFit.scaleFor(intrinsicWidth = 200f, availableWidth = 100f), 1e-4f)
        // Degenerate measurements never scale.
        assertEquals(1f, FmTextFit.scaleFor(0f, 100f), 1e-4f)
        assertEquals(1f, FmTextFit.scaleFor(100f, 0f), 1e-4f)
    }

    @Test
    fun fitDetectionFollowsTheSameMinimumScale() {
        assertTrue(FmTextFit.fits(textWidth = 100f, availableWidth = 100f))
        assertTrue(FmTextFit.fits(textWidth = 100f, availableWidth = 82f))
        assertFalse(FmTextFit.fits(textWidth = 100f, availableWidth = 70f))
    }
}
