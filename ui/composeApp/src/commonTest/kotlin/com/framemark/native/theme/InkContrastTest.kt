package com.framemark.native.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * AU4-04: the ink ladder, pinned by numbers.
 *
 * `Tokens.kt` says `onSurfaceFaint` (ink-4) is for **disabled state and
 * placeholder text only**, but the rule was not enforced anywhere, so 11–12 px
 * body-level labels were painted with it: `#7F8A97 on #FFFFFF = 3.51:1`, below
 * the WCAG 2.1 AA 4.5:1 floor for normal text (AU4-04, 1247 pixels measured on
 * the light workbench capture).
 *
 * This test makes the ladder a contract:
 *  - [FmColors.onSurface], [FmColors.onSurfaceSecondary] and
 *    [FmColors.onSurfaceMuted] are body inks and must clear 4.5:1 on every
 *    surface they can be painted on (canvas / surface / inset surface);
 *  - [FmColors.onSurfaceFaint] is *deliberately* below that floor, which is why
 *    it may only be used where AA does not apply (disabled controls, input
 *    placeholders, hairline decoration). If a future change raises it above
 *    4.5:1 the last test says so, so the usage rule can be re-decided on purpose
 *    instead of by accident.
 */
class InkContrastTest {

    private val light = FmColors.Light
    private val dark = FmColors.Dark

    @Test
    fun mutedInkPassesAaOnEveryLightSurface() {
        val surfaces = mapOf(
            "canvas" to light.canvas,
            "surface" to light.surface,
            "surfaceMuted" to light.surfaceMuted,
        )
        surfaces.forEach { (name, background) ->
            val ratio = contrastRatio(light.onSurfaceMuted, background)
            assertTrue(
                ratio >= 4.5,
                "light onSurfaceMuted ${hex(light.onSurfaceMuted)} on $name ${hex(background)} is $ratio:1 (need >= 4.5:1)",
            )
        }
    }

    @Test
    fun mutedInkPassesAaOnEveryDarkSurface() {
        val surfaces = mapOf(
            "canvas" to dark.canvas,
            "surface" to dark.surface,
            "surfaceMuted" to dark.surfaceMuted,
        )
        surfaces.forEach { (name, background) ->
            val ratio = contrastRatio(dark.onSurfaceMuted, background)
            assertTrue(
                ratio >= 4.5,
                "dark onSurfaceMuted ${hex(dark.onSurfaceMuted)} on $name ${hex(background)} is $ratio:1 (need >= 4.5:1)",
            )
        }
    }

    @Test
    fun theStrongInksPassAaWithRoomToSpare() {
        listOf(
            "light onSurface" to (light.onSurface to light.surface),
            "light onSurfaceSecondary" to (light.onSurfaceSecondary to light.surface),
            "dark onSurface" to (dark.onSurface to dark.surface),
            "dark onSurfaceSecondary" to (dark.onSurfaceSecondary to dark.surface),
        ).forEach { (label, pair) ->
            val ratio = contrastRatio(pair.first, pair.second)
            assertTrue(ratio >= 7.0, "$label is $ratio:1, expected >= 7:1 (AAA body text)")
        }
    }

    @Test
    fun faintInkIsBelowAaSoItStaysDisabledAndPlaceholderOnly() {
        val lightRatio = contrastRatio(light.onSurfaceFaint, light.surface)
        val lightInset = contrastRatio(light.onSurfaceFaint, light.canvas)
        val darkRatio = contrastRatio(dark.onSurfaceFaint, dark.surface)
        assertTrue(
            lightRatio < 4.5,
            "light onSurfaceFaint ${hex(light.onSurfaceFaint)} on surface is $lightRatio:1 - " +
                "if this now clears AA, re-read the Tokens.kt usage rule before using it for body text",
        )
        assertTrue(lightInset < 4.5, "light onSurfaceFaint on canvas is $lightInset:1")
        assertTrue(darkRatio < 4.5, "dark onSurfaceFaint on surface is $darkRatio:1")
        // The gap between the two inks is the reason AU4-04 exists: muted is the
        // body-safe step, faint is the disabled step.
        assertTrue(lightRatio > 2.5, "faint ink must still be readable enough to say 'disabled'")
    }

    // Helpers ------------------------------------------------------------------

    private fun hex(color: Color): String =
        "#%02X%02X%02X".format(
            (color.red * 255f).toInt(),
            (color.green * 255f).toInt(),
            (color.blue * 255f).toInt(),
        )

    private fun linear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
