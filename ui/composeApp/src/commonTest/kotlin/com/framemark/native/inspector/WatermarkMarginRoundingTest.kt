package com.framemark.native.inspector

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D1-3: dragged dx/dy goes through the same 0.1 % grid as the margin sliders, and
 * the margin value reads with one fixed decimal afterwards.
 */
class WatermarkMarginRoundingTest {

    @Test
    fun drag_offsets_are_quantized_to_the_point_one_percent_grid() {
        val rawDx = 0.0595837f
        val qx = FmSliderMath.quantize(rawDx, -4f, 4f, 0.001f)
        assertTrue(abs(qx - 0.060f) < 1e-6f, "0.1 % grid: $qx")
        val rawDy = -0.009275975f
        val qy = FmSliderMath.quantize(rawDy, -4f, 4f, 0.001f)
        assertTrue(abs(qy - (-0.009f)) < 1e-6f, "0.1 % grid: $qy")
    }

    @Test
    fun margin_values_read_with_one_fixed_decimal() {
        assertEquals("6.0", FmUnits.formatNumber(6.0f, 1))
        assertEquals("5.9", FmUnits.formatNumber(5.94f, 1))
        assertEquals("3.8", FmUnits.formatNumber(3.75f, 1))
        // The default 3-decimal rule is unchanged for every other control.
        assertEquals("59.4", FmUnits.formatNumber(59.4f))
        assertEquals("6", FmUnits.formatNumber(6.0f))
    }
}
