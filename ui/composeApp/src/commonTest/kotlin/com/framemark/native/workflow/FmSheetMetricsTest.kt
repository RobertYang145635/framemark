package com.framemark.native.workflow

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The phone sheet's height policy, without a composition.
 *
 * Defect 1 ("有的地方太稀疏"): the sheet was fixed at 90 % of the window, so the
 * export form ended around 45 % and the rest was blank. The fix keeps the same
 * ceiling — a sheet may never grow past 90 % of the window — but a content-wrapped
 * sheet stops at its content. [FmSheetMetrics.wrapCap] is the ceiling half of that
 * contract; the wrapping itself is Compose's `heightIn(max = …)` + `weight(fill =
 * false)`, and `PhoneExportSheetRenderTest` measures it from a render.
 */
class FmSheetMetricsTest {

    private fun Dp.isCloseTo(expected: Dp, tolerance: Float = 0.01f): Boolean =
        abs(value - expected.value) <= tolerance

    @Test
    fun the_wrap_cap_is_the_old_fixed_fraction_of_the_window() {
        for (height in listOf(320, 430, 640, 905, 1200)) {
            val window = height.dp
            val cap = FmSheetMetrics.wrapCap(window)
            assertTrue(
                cap.isCloseTo(window * FmSheetMetrics.FillFraction),
                "cap ${cap.value}dp of a ${height}dp window is not the historical " +
                    "fill fraction ${FmSheetMetrics.FillFraction}",
            )
        }
    }

    @Test
    fun the_cap_never_exceeds_ninety_percent_of_the_window() {
        for (height in 240..1600 step 40) {
            val window = height.dp
            val cap = FmSheetMetrics.wrapCap(window)
            assertTrue(
                cap <= window * 0.9f + 0.01f.dp,
                "cap ${cap.value}dp is taller than 90 % of a ${height}dp window",
            )
            assertTrue(cap > 0.dp, "cap ${cap.value}dp is not positive")
        }
    }

    @Test
    fun a_taller_window_gets_a_taller_cap() {
        val small = FmSheetMetrics.wrapCap(430.dp)
        val phone = FmSheetMetrics.wrapCap(905.dp)
        assertTrue(
            small < phone,
            "the cap shrank when the window grew (${small.value}dp vs ${phone.value}dp)",
        )
    }
}
