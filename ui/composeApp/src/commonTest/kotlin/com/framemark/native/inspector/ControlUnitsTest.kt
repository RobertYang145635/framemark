package com.framemark.native.inspector

import com.framemark.native.product.ControlLabels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP-C §4.1 (P0): units reach the UI only through [FmUnits].
 *
 * Every rule here comes from report §9 "单位换算" plus the WP-C row in HANDOFF
 * part 2 §4: `pct` → %, `multiplier` → ×, `alpha` → % only when max ≤ 1.5,
 * `count` → 个, `unitless` → % only for ratio fields (`logoCenterX`).
 */
class ControlUnitsTest {

    @Test
    fun percentAndMultiplierAndCountMapToHumanSuffixes() {
        assertEquals("%", FmUnits.suffixFor("pct", 100f))
        assertEquals("%", FmUnits.suffixFor("percent", 100f))
        assertEquals("%", FmUnits.suffixFor("%", 100f))
        assertEquals("×", FmUnits.suffixFor("multiplier", 10f))
        assertEquals("个", FmUnits.suffixFor("count", 6f))
        assertEquals("% 长边", FmUnits.suffixFor("pctOfLongEdge", 40f))
    }

    @Test
    fun alphaBecomesPercentOnlyWhenTheDeclaredMaximumIsAtMostOnePointFive() {
        assertEquals("%", FmUnits.suffixFor("alpha", 1f))
        assertEquals(100f, FmUnits.scaleFor("alpha", 1f))
        // Above 1.5 the value is not a 0..1 opacity and must not be multiplied.
        assertNull(FmUnits.suffixFor("alpha", 2f))
        assertEquals(1f, FmUnits.scaleFor("alpha", 2f))
        // A missing max is treated as an opacity (all shipped alphas are 0..1).
        assertEquals("%", FmUnits.suffixFor("alpha", null))
    }

    @Test
    fun unitlessRatioFieldsShowAPercentAndOtherUnitlessFieldsShowNothing() {
        assertEquals("%", FmUnits.suffixFor("unitless", 2f, "params.logoCenterX"))
        assertEquals(100f, FmUnits.scaleFor("unitless", 2f, "params.logoCenterX"))
        assertEquals("%", FmUnits.suffixFor("unitless", 1f, "params.cardCx"))
        // Semantics not documented in the frozen sources: no unit, no guessing.
        assertNull(FmUnits.suffixFor("unitless", 2f, "params.brandY"))
        assertNull(FmUnits.suffixFor("unitless", 40f, "params.blur"))
        assertNull(FmUnits.suffixFor("unitless", 10f, "params.shadow"))
        assertEquals(1f, FmUnits.scaleFor("unitless", 40f, "params.blur"))
    }

    @Test
    fun unknownUnitsAreNeverEchoed() {
        // The bug this fixes: `0.594unitless`, `5multiplier`. A raw unit may map to
        // human copy, but the raw spelling itself must never be the suffix.
        val allowed = setOf("%", "×", "个", "% 长边")
        for (raw in FmUnits.RAW_UNITS) {
            val suffix = FmUnits.suffixFor(raw, 1f, "params.footerPct")
            if (suffix != null) {
                assertTrue(suffix in allowed, "raw token $raw produced '$suffix'")
                assertFalse(suffix == raw, "raw token $raw passed through")
            }
        }
        assertNull(FmUnits.suffixFor("furlong", 1f))
        assertNull(FmUnits.suffixFor(null, 1f))
        assertNull(FmUnits.suffixFor("", 1f))
    }

    @Test
    fun displayTextScalesAndAppendsTheSuffix() {
        assertEquals("59.4%", FmUnits.displayText(0.594f, "%", 100f))
        assertEquals("5×", FmUnits.displayText(5f, "×", 1f))
        assertEquals("3个", FmUnits.displayText(3f, "个", 1f))
        assertEquals("40%", FmUnits.displayText(0.4f, "%", 100f))
        // No unit: a bare number, never a token.
        assertEquals("0.594", FmUnits.displayText(0.594f, null, 1f))
    }

    @Test
    fun displayNumbersKeepThreeDecimalsAndDropTrailingZeros() {
        assertEquals("1", FmUnits.formatNumber(1f))
        assertEquals("1.5", FmUnits.formatNumber(1.5f))
        assertEquals("0.594", FmUnits.formatNumber(0.594f))
        assertEquals("59.4", FmUnits.formatNumber(59.4000015f))
    }

    @Test
    fun everyRawUnitTokenIsInTheForbiddenList() {
        val forbidden = listOf("pct", "multiplier", "unitless", "alpha", "count")
        for (token in forbidden) {
            assertTrue(FmUnits.RAW_UNITS.contains(token), "forbidden list must contain $token")
        }
        // `pctOfLongEdge` maps to `% 长边`, so its raw spelling must not survive.
        assertEquals("% 长边", FmUnits.suffixFor("pctOfLongEdge", 10f))
        assertFalse(FmUnits.RAW_UNITS.isEmpty())
    }

    @Test
    fun labelTableAnswersWithOneNamePerField() {
        // P0 #3: the field key wins, so the two old names collapse.
        assertEquals("照片留白", ControlLabels.forControl("外边距", "x", "params.marginPct"))
        assertEquals("照片留白", ControlLabels.forControl("marginPct", "x", "params.marginPct"))
        assertEquals("底色", ControlLabels.forControl("底色主题", "x", "theme"))
        assertEquals("版式", ControlLabels.forControl("页脚版式", "x", "params.footerMode"))
        assertEquals("显示 Logo", ControlLabels.forControl("显示标志", "x", "text.showLogo"))
        assertEquals("位置", ControlLabels.forControl("标志位置", "x", "params.logoPos"))
        assertEquals("Logo 大小", ControlLabels.forControl("字标缩放", "x", "params.logoScale"))
        assertEquals("水平位置", ControlLabels.forControl("字标水平中心", "x", "params.logoCenterX"))
        assertEquals("Logo", ControlLabels.forControl("品牌标志", "x", "design.logo"))
        assertEquals("整体文字大小", ControlLabels.forControl("整卡字号缩放", "x", "text.fontScale"))
        assertEquals("模糊强度", ControlLabels.forControl("背景模糊", "x", "params.blur"))
    }

    @Test
    fun internalFieldsAreNeverGivenAHumanLabel() {
        // Report §9: `slots` ("页脚行选择") must not appear in the UI.
        assertNull(ControlLabels.forControl("页脚行选择", "x", "params.slots"))
        assertNull(ControlLabels.forControl("slots", "x", "params.slots"))
        assertTrue(ControlLabels.isInternal("params.slots"))
        assertFalse(ControlLabels.isInternal("params.footerPct"))
    }
}
