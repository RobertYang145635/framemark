package com.framemark.native.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WP-T §5.5: every token in both appearances, plus the `sliderIdle` contrast
 * requirement.
 *
 * The expected literals are the two tables in task brief §4.1 (light = the V2
 * showcase `:root`, dark = the HANDOFF "深色（新）" column). Changing a token
 * without changing this table is supposed to fail here.
 */
class FmColorsTest {

    private val light = FmColors.Light
    private val dark = FmColors.Dark

    // §4.1.2 — surfaces, ink, lines, brand, feedback, stage ---------------------

    @Test
    fun lightSurfacesAndInk() {
        assertEquals(Color(0xFFF0F2F5), light.canvas)
        assertEquals(Color(0xFFFFFFFF), light.surface)
        assertEquals(Color(0xFFF6F8FB), light.surfaceMuted)
        assertEquals(Color(0xFF14181F), light.onSurface)
        assertEquals(Color(0xFF3E4753), light.onSurfaceSecondary)
        assertEquals(Color(0xFF5C6673), light.onSurfaceMuted)
        assertEquals(Color(0xFF7F8A97), light.onSurfaceFaint)
    }

    @Test
    fun darkSurfacesAndInk() {
        assertEquals(Color(0xFF121316), dark.canvas)
        assertEquals(Color(0xFF1D1E22), dark.surface)
        assertEquals(Color(0xFF26282D), dark.surfaceMuted)
        assertEquals(Color(0xFFEDEFF3), dark.onSurface)
        assertEquals(Color(0xFFC3C8D0), dark.onSurfaceSecondary)
        assertEquals(Color(0xFF9AA1AC), dark.onSurfaceMuted)
        assertEquals(Color(0xFF7D8490), dark.onSurfaceFaint)
    }

    @Test
    fun lightLinesAndFeedback() {
        assertEquals(Color(0xFFE3E7EC), light.outline)
        assertEquals(Color(0xFFD5DBE2), light.outlineStrong)
        assertEquals(light.outline, light.indicator)
        assertEquals(Color(0xFFD2453B), light.error)
        assertEquals(Color(0xFFC27A0E), light.warning)
        assertEquals(Color(0xFF1E9E5A), light.success)
        assertEquals(Color(0xFFDCE1E7), light.track)
    }

    @Test
    fun darkLinesAndFeedback() {
        assertEquals(Color(0xFF2E3036), dark.outline)
        assertEquals(Color(0xFF3A3D44), dark.outlineStrong)
        assertEquals(dark.outline, dark.indicator)
        assertEquals(Color(0xFFF06A5F), dark.error)
        assertEquals(Color(0xFFE0A23A), dark.warning)
        assertEquals(Color(0xFF3CC07A), dark.success)
        assertEquals(Color(0xFF3A3D44), dark.track)
    }

    @Test
    fun brandIsSharedExceptAccentInkAndMixes() {
        assertEquals(Color(0xFF2F75E5), light.primary)
        assertEquals(Color(0xFF2F75E5), dark.primary)
        assertEquals(Color(0xFFFFFFFF), light.onPrimary)
        assertEquals(Color(0xFFFFFFFF), dark.onPrimary)
        assertEquals(Color(0xFF1D5FC6), light.primaryStrong)
        assertEquals(Color(0xFF7FB0FF), dark.primaryStrong)
    }

    @Test
    fun stageIsDarkInBothAppearances() {
        assertEquals(Color(0xFF2B2C2F), light.stage)
        assertEquals(Color(0xFF2B2C2F), dark.stage)
        assertEquals(Color(0xFF2B2C2F), light.stageDark)
        assertEquals(Color(0xFF2B2C2F), dark.stageDark)
        assertEquals(Color(0xFF5A5C61), light.stageMid)
        assertEquals(Color(0xFF5A5C61), dark.stageMid)
        assertEquals(Color(0xFFD9DADD), light.stageLight)
        assertEquals(Color(0xFFD9DADD), dark.stageLight)
        assertEquals(Color(0xFF3A3F47), light.stageLine)
        assertEquals(Color(0xFFD4DAE3), light.stageInk)
        assertEquals(Color(0xFF969AA4), light.stageMuted)
    }

    @Test
    fun stageColorMapsEveryTone() {
        assertEquals(light.stageDark, light.stageColor(StageTone.Dark))
        assertEquals(light.stageMid, light.stageColor(StageTone.Mid))
        assertEquals(light.stageLight, light.stageColor(StageTone.Light))
    }

    // §4.1.3 — new interaction tokens ------------------------------------------

    @Test
    fun lightInteractionTokens() {
        assertEquals(Color(0x0B14181F), light.hover)
        assertEquals(Color(0x1414181F), light.press)
        assertEquals(Color(0xFF9AA4B1), light.sliderIdle)
        assertEquals(0.10f, light.primarySoftAlpha, "primarySoft must be 10 % in light")
        assertEquals(0.28f, light.ringAlpha, "ring must be 28 % in light")
    }

    @Test
    fun darkInteractionTokens() {
        assertEquals(Color(0x0FFFFFFF), dark.hover)
        assertEquals(Color(0x1AFFFFFF), dark.press)
        assertEquals(Color(0xFF6B7688), dark.sliderIdle)
        assertEquals(0.18f, dark.primarySoftAlpha, "primarySoft must be 18 % in dark")
        assertEquals(0.40f, dark.ringAlpha, "ring must be 40 % in dark")
    }

    // Derived colours ----------------------------------------------------------

    @Test
    fun derivedColorsUseTheirOwnSnapshot() {
        // primarySoft / ring must composite over the snapshot's own surface; using
        // the light surface in the dark snapshot is the classic bug this pins.
        for (colors in listOf(light, dark)) {
            assertEquals(
                colors.primary.copy(alpha = colors.primarySoftAlpha).compositeOver(colors.surface),
                colors.primarySoft,
            )
            assertEquals(
                colors.primary.copy(alpha = colors.ringAlpha),
                colors.ring,
            )
            assertEquals(colors.outline, colors.indicator)
        }
    }

    @Test
    fun softAndRingAreMoreVisibleInDark() {
        assertTrue(dark.primarySoft != light.primarySoft)
        assertTrue(dark.ring.alpha > light.ring.alpha)
    }

    @Test
    fun navSurfaceIsTheSurfaceAtPointNineEightFive() {
        // Compose packs an sRGB Color into 0xAARRGGBB, so 0.985f quantises to
        // 251/255 = 0.98431373 — the value the light-only revision already shipped.
        val quantised = 251f / 255f
        assertEquals(quantised, light.navSurface.alpha)
        assertEquals(quantised, dark.navSurface.alpha)
        assertEquals(light.surface.red, light.navSurface.red)
        assertEquals(light.surface.green, light.navSurface.green)
        assertEquals(light.surface.blue, light.navSurface.blue)
        assertEquals(dark.surface.red, dark.navSurface.red)
        assertEquals(dark.surface.green, dark.navSurface.green)
        assertEquals(dark.surface.blue, dark.navSurface.blue)
    }

    @Test
    fun shadowsAreDeeperInDark() {
        assertTrue(dark.shadowNavAmbient.alpha > light.shadowNavAmbient.alpha)
        assertTrue(dark.shadowNavSpot.alpha > light.shadowNavSpot.alpha)
        assertTrue(dark.shadowCardAmbient.alpha > light.shadowCardAmbient.alpha)
        assertTrue(dark.shadowCardSpot.alpha > light.shadowCardSpot.alpha)
    }

    // The contrast requirement -------------------------------------------------

    @Test
    fun darkSliderIdleContrastAgainstSurfaceIsAtLeastThree() {
        val ratio = contrastRatio(dark.sliderIdle, dark.surface)
        assertTrue(
            ratio >= 3.0,
            "dark sliderIdle ${dark.sliderIdle} on ${dark.surface} is only $ratio:1 (need >= 3:1)",
        )
    }

    @Test
    fun contrastFormulaMatchesKnownAnchors() {
        // Sanity-check the test's own WCAG maths before it is trusted above.
        assertEquals(21.0, contrastRatio(Color(0xFFFFFFFF), Color(0xFF000000)), 0.01)
        assertEquals(1.0, contrastRatio(Color(0xFFFFFFFF), Color(0xFFFFFFFF)), 0.01)
    }

    // Helpers ------------------------------------------------------------------

    /** WCAG 2.x relative luminance of one channel. */
    private fun linear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
