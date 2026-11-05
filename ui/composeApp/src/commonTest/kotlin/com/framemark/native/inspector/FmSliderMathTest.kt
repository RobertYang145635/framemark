package com.framemark.native.inspector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WP-C §4.2 slider behaviour, tested as pure maths (no Compose):
 * quantisation, ±1.5 % snapping, arrow/Shift/PageUp/Home/End, typed clamping.
 */
class FmSliderMathTest {

    private val min = 0f
    private val max = 100f
    private val step = 1f
    private val default = 50f

    @Test
    fun quantizeStartsAtMinAndClampsToTheRange() {
        assertEquals(0f, FmSliderMath.quantize(-4f, min, max, step))
        assertEquals(100f, FmSliderMath.quantize(120f, min, max, step))
        assertEquals(12f, FmSliderMath.quantize(12.4f, min, max, step))
        assertEquals(13f, FmSliderMath.quantize(12.6f, min, max, step))
        // A 0.5 grid starting at 5, like footerPct.
        assertEquals(5.5f, FmSliderMath.quantize(5.7f, 5f, 25f, 0.5f))
    }

    @Test
    fun aDefaultOffTheGridStaysExact() {
        // 7.3 is not on the 0.5 grid from 5; it must survive a "no move" drag.
        val offGridDefault = 7.3f
        assertEquals(offGridDefault, FmSliderMath.quantize(7.3f, 5f, 25f, 0.5f, offGridDefault))
        assertEquals(offGridDefault, FmSliderMath.dragValue(7.31f, 5f, 25f, 0.5f, offGridDefault))
    }

    @Test
    fun valuesWithinOnePointFivePercentOfTheDefaultSnapToIt() {
        // 1.5 % of a 100-wide range = 1.5.
        assertEquals(50f, FmSliderMath.snapToDefault(48.6f, default, min, max))
        assertEquals(48.4f, FmSliderMath.snapToDefault(48.4f, default, min, max))
        assertEquals(50f, FmSliderMath.dragValue(51.4f, min, max, step, default))
        assertFalse(FmSliderMath.dragValue(53f, min, max, step, default) == 50f)
        // Without a default nothing snaps.
        assertEquals(48.6f, FmSliderMath.snapToDefault(48.6f, null, min, max))
    }

    @Test
    fun arrowKeysMoveOneStepAndShiftMovesTen() {
        assertEquals(51f, FmSliderMath.applyKey(FmSliderKey.Right, 50f, min, max, step, default))
        assertEquals(49f, FmSliderMath.applyKey(FmSliderKey.Left, 50f, min, max, step, default))
        assertEquals(60f, FmSliderMath.applyKey(FmSliderKey.Right, 50f, min, max, step, default, shift = true))
        assertEquals(40f, FmSliderMath.applyKey(FmSliderKey.Left, 50f, min, max, step, default, shift = true))
        assertEquals(51f, FmSliderMath.applyKey(FmSliderKey.Up, 50f, min, max, step, default))
        assertEquals(49f, FmSliderMath.applyKey(FmSliderKey.Down, 50f, min, max, step, default))
        // Bounds are respected.
        assertEquals(100f, FmSliderMath.applyKey(FmSliderKey.Right, 100f, min, max, step, default))
        assertEquals(0f, FmSliderMath.applyKey(FmSliderKey.Left, 0f, min, max, step, default))
    }

    @Test
    fun pageKeysMoveTenPercentAndHomeEndJumpToBounds() {
        assertEquals(60f, FmSliderMath.applyKey(FmSliderKey.PageUp, 50f, min, max, step, default))
        assertEquals(40f, FmSliderMath.applyKey(FmSliderKey.PageDown, 50f, min, max, step, default))
        assertEquals(0f, FmSliderMath.applyKey(FmSliderKey.Home, 73f, min, max, step, default))
        assertEquals(100f, FmSliderMath.applyKey(FmSliderKey.End, 12f, min, max, step, default))
        assertEquals(50f, FmSliderMath.applyKey(FmSliderKey.Delete, 12f, min, max, step, default))
        assertEquals(10f, FmSliderMath.pageStep(min, max))
    }

    @Test
    fun typedValuesOutsideTheRangeAreClampedBack() {
        assertEquals(100f, FmSliderMath.parseTyped("150", 50f, min, max))
        assertEquals(0f, FmSliderMath.parseTyped("-5", 50f, min, max))
        assertEquals(12.5f, FmSliderMath.parseTyped("12.5", 50f, min, max))
        // A decimal comma is accepted like a decimal point.
        assertEquals(1.5f, FmSliderMath.parseTyped("1,5", 50f, 0f, 10f))
        // Non-numeric text keeps the current value instead of writing a guess.
        assertEquals(50f, FmSliderMath.parseTyped("abc", 50f, min, max))
        assertEquals(50f, FmSliderMath.parseTyped("", 50f, min, max))
    }

    @Test
    fun typedValuesRespectTheDisplayScale() {
        // 0.594 alpha is shown as 59.4 %; typing 59.4 stores 0.594 again.
        assertEquals(0.594f, FmSliderMath.parseTyped("59.4", 0.8f, 0f, 1f, scale = 100f), 1e-5f)
        assertEquals(1f, FmSliderMath.parseTyped("100", 0.8f, 0f, 1f, scale = 100f))
        assertEquals(0f, FmSliderMath.parseTyped("-2", 0.8f, 0f, 1f, scale = 100f))
    }

    @Test
    fun fineNudgeUsesZeroPointOnePercentOfTheRange() {
        // 0.1 % of 100 = 0.1.
        assertEquals(50.1f, FmSliderMath.nudge(50f, +1, min, max, default), 1e-4f)
        assertEquals(49.9f, FmSliderMath.nudge(50f, -1, min, max, default), 1e-4f)
        assertEquals(0.1f, FmSliderMath.fineStep(min, max))
        // 0.1 % taps next to the default still move: no snap in fine nudge.
        assertEquals(50.05f, FmSliderMath.nudge(49.95f, +1, min, max, default), 1e-4f)
        assertEquals(49.9f, FmSliderMath.nudge(50f, -1, min, max, default))
    }

    @Test
    fun anUnchangedValueIsNotCountedAsModified() {
        assertFalse(FmSliderMath.isModified(50f, 50f))
        assertTrue(FmSliderMath.isModified(50.01f, 50f))
        assertFalse(FmSliderMath.isModified(50f, null))
    }

    @Test
    fun touchHeightIsOneConstant() {
        // U-01: 40 dp until the user decides; this is the only literal.
        assertEquals(40, FM_TOUCH_SLIDER_HEIGHT_DP)
    }
}
