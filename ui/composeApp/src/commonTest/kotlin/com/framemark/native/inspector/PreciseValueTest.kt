package com.framemark.native.inspector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Slider precise-entry contract: strict parse, no rounding, no clamping. */
class PreciseValueTest {

    @Test
    fun trimsAndParsesInRangeValue() {
        assertEquals(0.5f, parsePreciseNumber(" 0.5 ", 0f, 1f))
        assertEquals(-12.25f, parsePreciseNumber("-12.25", -20f, 20f))
        assertEquals(1200f, parsePreciseNumber("1200", 0f, 4000f))
    }

    @Test
    fun rejectsEmptyAndWhitespaceOnly() {
        assertNull(parsePreciseNumber("", 0f, 1f))
        assertNull(parsePreciseNumber("   ", 0f, 1f))
        assertNull(parsePreciseNumber("\t\n", 0f, 1f))
    }

    @Test
    fun rejectsNonNumericText() {
        assertNull(parsePreciseNumber("abc", 0f, 1f))
        assertNull(parsePreciseNumber("12px", 0f, 100f))
        assertNull(parsePreciseNumber("1,5", 0f, 2f))
        assertNull(parsePreciseNumber("--1", -2f, 2f))
    }

    @Test
    fun rejectsNaNAndInfinity() {
        assertNull(parsePreciseNumber("NaN", 0f, 1f))
        assertNull(parsePreciseNumber("Infinity", 0f, Float.MAX_VALUE))
        assertNull(parsePreciseNumber("-Infinity", -Float.MAX_VALUE, 0f))
    }

    @Test
    fun rejectsOutOfRangeValues() {
        assertNull(parsePreciseNumber("1.0001", 0f, 1f))
        assertNull(parsePreciseNumber("-0.0001", 0f, 1f))
        assertNull(parsePreciseNumber("101", 0f, 100f))
    }

    @Test
    fun acceptsTheExactBounds() {
        assertEquals(0f, parsePreciseNumber("0", 0f, 1f))
        assertEquals(1f, parsePreciseNumber("1", 0f, 1f))
        assertEquals(-20f, parsePreciseNumber("-20", -20f, 20f))
        assertEquals(20f, parsePreciseNumber(" 20 ", -20f, 20f))
    }

    @Test
    fun doesNotRoundOrQuantise() {
        assertEquals(1.234567f, parsePreciseNumber("1.234567", 0f, 2f))
        assertEquals(0.333f, parsePreciseNumber("0.333", 0f, 1f))
    }

    @Test
    fun rejectsInvalidBounds() {
        assertNull(parsePreciseNumber("0.5", Float.NaN, 1f))
        assertNull(parsePreciseNumber("0.5", 0f, Float.POSITIVE_INFINITY))
        // An inverted range cannot describe a reachable slider value.
        assertNull(parsePreciseNumber("0.5", 1f, 0f))
    }
}
