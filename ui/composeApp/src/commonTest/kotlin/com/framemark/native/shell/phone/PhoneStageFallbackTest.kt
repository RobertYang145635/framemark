package com.framemark.native.shell.phone

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Defect 2: when the live render fails the stage shows the bundled static sample,
 * which read as "someone else's photo". The fix is draw-only — a 12 % wash and one
 * centred chip — and this test pins the two things that are logic rather than paint:
 * **when** the placeholder is drawn, and **where** it is allowed to sit.
 */
class PhoneStageFallbackTest {

    /** `PhoneStageFallbackChip`: 14 dp line box + 2 × 5 dp padding + 2 × 1 dp rim. */
    private val chipHeight = 26.dp

    private fun chipBottom(stageHeight: androidx.compose.ui.unit.Dp, focusMode: Boolean) =
        (stageHeight - PhoneStageBands.photoBottom(focusMode) + PhoneStageBands.PhotoTop) / 2 +
            chipHeight / 2

    @Test
    fun the_placeholder_is_drawn_when_the_render_reports_a_failure() {
        // The callback `NativeStagePreview` fires from its failure path is the same
        // signal the bottom-left 预览失败 · 显示静态图 hint uses: at that point the
        // rendered frame is dropped and the bundled sample is what the stage shows.
        assertTrue(
            PhoneStageFallback.showsPlaceholder(
                renderFailure = "no native renderer",
                compare = false,
            ),
        )
    }

    @Test
    fun no_failure_means_no_placeholder() {
        assertFalse(PhoneStageFallback.showsPlaceholder(null, compare = false))
        assertFalse(PhoneStageFallback.showsPlaceholder("", compare = false))
        assertFalse(PhoneStageFallback.showsPlaceholder("   ", compare = false))
    }

    @Test
    fun holding_the_original_photo_hides_the_placeholder() {
        // 按住看原图 shows the raw photo: it must never be dimmed or called a sample.
        assertFalse(
            PhoneStageFallback.showsPlaceholder(
                renderFailure = "render failed",
                compare = true,
            ),
        )
    }

    @Test
    fun the_placeholder_chip_cannot_reach_the_pill_band() {
        // The chip is centred in the photo band (Alignment.Center); the band ends
        // PhoneStageBands.PhotoBottom above the stage's bottom, i.e. above the pills.
        for (height in 100..1200 step 20) {
            val stage = height.dp
            val bottom = chipBottom(stage, focusMode = false)
            val pillTop = stage - PhoneStageBands.PillBand
            assertTrue(
                bottom < pillTop,
                "on a ${height}dp stage the chip ends at ${bottom.value}dp but the pill " +
                    "band starts at ${pillTop.value}dp",
            )
        }
    }

    @Test
    fun the_photo_band_reserves_the_pill_band() {
        // The pill band is the 44 dp 按住看原图 touch box plus its 12 dp inset; the
        // photo band ends 2 dp above it, so neither pill can cover the photo and the
        // placeholder cannot cover either pill.
        assertEquals(44.dp, PhoneTouchTarget)
        assertEquals(56.dp, PhoneStageBands.PillBand)
        assertEquals(58.dp, PhoneStageBands.PhotoBottom)
        assertEquals(2.dp, PhoneStageBands.PhotoBottom - PhoneStageBands.PillBand)
        // Focus mode hides both pills, so the photo may use the extra 46 dp.
        assertEquals(12.dp, PhoneStageBands.photoBottom(focusMode = true))
        assertTrue(PhoneStageBands.photoBottom(focusMode = true) < PhoneStageBands.PhotoBottom)
    }

    @Test
    fun the_chip_says_it_is_a_sample_and_keeps_the_existing_hint_copy() {
        val text = PhoneStageFallback.ChipText
        assertTrue(text.contains("示例图"), "the chip does not say 示例图: $text")
        assertTrue(text.contains("预览暂不可用"), "the chip does not explain the failure: $text")
        // The bottom-left hint keeps its own wording; the chip must not become a
        // second copy of it (two identical sentences on one screen read as a bug).
        assertFalse(text.contains("显示静态图"), "the chip duplicates the bottom-left hint: $text")
    }

    @Test
    fun the_placeholder_is_a_little_dimmer_and_uses_the_stage_pill_alpha() {
        assertTrue(
            PhoneStageFallback.DimAlpha in 0.10f..0.14f,
            "the wash is ${PhoneStageFallback.DimAlpha}, not the ~12 % the report asks for",
        )
        assertEquals(0.88f, PhoneStageFallback.ChipAlpha)
    }
}
