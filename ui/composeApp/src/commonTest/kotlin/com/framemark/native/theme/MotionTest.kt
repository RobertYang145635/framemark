package com.framemark.native.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Motion tokens + the pure half of the motion rules.
 *
 * These are the two properties the rest of the app is allowed to rely on:
 *
 *  1. the duration ladder is ordered, so "press is the quickest real motion, emphasised
 *     is the slowest" is a fact and not a comment, and
 *  2. **scale 0 means no motion** — every scaled duration collapses to 0, which is what
 *     makes the reduced-motion path and the tests deterministic.
 *
 * A pixel/timing test is deliberately absent: Compose's own animation core is the thing
 * under test there, and it is not ours to re-verify. What is ours is that we hand it
 * `snap()` when motion is off, which [FmMotionRules.durationMs] decides.
 */
class MotionTest {

    // ---- duration ladder ----------------------------------------------------

    @Test
    fun instantIsZero() {
        assertEquals(0, FmDuration.instant)
    }

    @Test
    fun durationLadderIsMonotonic() {
        // The semantic order: press < fast < standard < emphasized.
        assertTrue(
            FmDuration.press < FmDuration.fast,
            "press (${FmDuration.press}) must be quicker than fast (${FmDuration.fast})",
        )
        assertTrue(
            FmDuration.fast < FmDuration.standard,
            "fast (${FmDuration.fast}) must be quicker than standard (${FmDuration.standard})",
        )
        assertTrue(
            FmDuration.standard < FmDuration.emphasized,
            "standard (${FmDuration.standard}) must be quicker than " +
                "emphasized (${FmDuration.emphasized})",
        )
        assertTrue(FmDuration.press > FmDuration.instant, "a real motion must take some time")
    }

    @Test
    fun exitsAreShorterThanEntrances() {
        // M3 puts an exit at roughly half the enter duration, and never longer.
        assertTrue(
            FmDuration.exit < FmDuration.emphasized,
            "exit (${FmDuration.exit}) must be shorter than the emphasised enter " +
                "(${FmDuration.emphasized})",
        )
        assertTrue(
            FmDuration.exit > FmDuration.fast,
            "exit (${FmDuration.exit}) must still be visible, not a cut",
        )
    }

    @Test
    fun everyDurationIsNonNegative() {
        val all = listOf(
            FmDuration.instant,
            FmDuration.press,
            FmDuration.fast,
            FmDuration.standard,
            FmDuration.emphasized,
            FmDuration.exit,
        )
        assertTrue(all.all { it >= 0 }, "durations are a magnitude: $all")
    }

    // ---- scale --------------------------------------------------------------

    @Test
    fun fullScaleKeepsTheTokenValue() {
        assertEquals(
            FmDuration.emphasized,
            FmMotionRules.durationMs(FmDuration.emphasized, FmMotionRules.fullScale),
        )
    }

    @Test
    fun scaleZeroCollapsesEveryDuration() {
        // This is the reduced-motion contract: 0 means "be there already".
        for (base in listOf(
            FmDuration.press,
            FmDuration.fast,
            FmDuration.standard,
            FmDuration.emphasized,
            FmDuration.exit,
        )) {
            assertEquals(
                FmDuration.instant,
                FmMotionRules.durationMs(base, 0f),
                "base $base ms must become instant at scale 0",
            )
        }
    }

    @Test
    fun negativeScaleIsTreatedAsNoMotion() {
        // A caller should never pass this, but "no motion" is the safe interpretation.
        assertEquals(FmDuration.instant, FmMotionRules.durationMs(FmDuration.standard, -1f))
        assertFalse(FmMotionRules.motionEnabled(-1f))
    }

    @Test
    fun scaleHalvesTheDuration() {
        assertEquals(
            FmDuration.standard / 2,
            FmMotionRules.durationMs(FmDuration.standard, 0.5f),
        )
    }

    @Test
    fun scaledDurationNeverRoundsDownToZero() {
        // 140 ms × 0.001 rounds to 0: that would build a 0 ms tween that still occupies
        // a frame instead of short-circuiting. Clamp to 1 ms so the two paths stay
        // distinguishable.
        assertEquals(1, FmMotionRules.durationMs(FmDuration.fast, 0.001f))
    }

    @Test
    fun motionEnabledOnlyAboveZero() {
        assertTrue(FmMotionRules.motionEnabled(1f))
        assertTrue(FmMotionRules.motionEnabled(0.01f))
        assertFalse(FmMotionRules.motionEnabled(0f))
    }

    // ---- stagger ------------------------------------------------------------

    @Test
    fun revealDelayStartsAtZeroAndGrows() {
        assertEquals(0, FmMotionRules.revealDelayMs(0))
        assertEquals(FmMotionRules.revealStepMs, FmMotionRules.revealDelayMs(1))
        assertEquals(FmMotionRules.revealStepMs * 2, FmMotionRules.revealDelayMs(2))
    }

    @Test
    fun revealDelayIsCappedAndNeverDecreases() {
        var previous = 0
        for (index in 0 until 40) {
            val delay = FmMotionRules.revealDelayMs(index)
            assertTrue(delay >= previous, "stagger must not go backwards at $index")
            assertTrue(
                delay <= FmMotionRules.revealCapMs,
                "delay $delay at index $index exceeds cap ${FmMotionRules.revealCapMs}",
            )
            previous = delay
        }
        assertEquals(FmMotionRules.revealCapMs, FmMotionRules.revealDelayMs(40))
    }

    @Test
    fun revealIsSkippedPastTheItemCap() {
        // The cap is a skip, not a clamp: scrolling a long grid must not replay the
        // entrance for item 40.
        assertTrue(FmMotionRules.shouldReveal(0))
        assertTrue(FmMotionRules.shouldReveal(FmMotionRules.revealItems - 1))
        assertFalse(FmMotionRules.shouldReveal(FmMotionRules.revealItems))
        assertFalse(FmMotionRules.shouldReveal(500))
        assertFalse(FmMotionRules.shouldReveal(-1))
    }

    @Test
    fun cappedRevealCoversEveryRevealingItem() {
        // Every item that takes part must also fit inside the cap, otherwise the last
        // one waits longer than the reveal is allowed to last.
        val last = FmMotionRules.revealItems - 1
        assertEquals(FmMotionRules.revealCapMs, FmMotionRules.revealDelayMs(last))
    }

    // ---- sheet snap helper --------------------------------------------------

    @Test
    fun nearestStopPicksTheClosest() {
        val stops = listOf(120f, 360f, 700f)
        assertEquals(0, FmMotionRules.nearestStop(stops, 100f))
        assertEquals(1, FmMotionRules.nearestStop(stops, 400f))
        assertEquals(2, FmMotionRules.nearestStop(stops, 690f))
    }

    @Test
    fun nearestStopTiesGoToTheLowerStop() {
        // A release exactly between two stops settles towards the sheet being less open;
        // this is the behaviour the pre-extraction `minByOrNull` had.
        val stops = listOf(100f, 300f)
        assertEquals(0, FmMotionRules.nearestStop(stops, 200f))
    }

    @Test
    fun nearestStopHandlesOutOfRangeAndEmpty() {
        val stops = listOf(100f, 300f)
        assertEquals(0, FmMotionRules.nearestStop(stops, -5_000f))
        assertEquals(1, FmMotionRules.nearestStop(stops, 5_000f))
        assertEquals(0, FmMotionRules.nearestStop(emptyList(), 42f))
    }

    // ---- springs ------------------------------------------------------------

    @Test
    fun springPresetsAreDistinctAndUsable() {
        val presets = listOf(FmSpring.settle, FmSpring.press, FmSpring.gentle)
        assertTrue(presets.all { it.dampingRatio > 0f && it.stiffness > 0f })
        assertEquals(3, presets.distinct().size, "presets must not be aliases of each other")
        // A reveal should not overshoot; a panel may settle with a little travel.
        assertEquals(1f, FmSpring.gentle.dampingRatio)
        assertTrue(FmSpring.settle.dampingRatio < 1f)
    }

    @Test
    fun springRejectsImpossibleValues() {
        val failed = runCatching { FmSpringSpec(dampingRatio = 0f, stiffness = 100f) }.isFailure
        assertTrue(failed, "a zero damping ratio is not a spring")
    }

    // ---- legacy record survived the move ------------------------------------

    @Test
    fun legacyNavTokensKeptTheirValues() {
        // FmMotion moved from Tokens.kt to Motion.kt; these are the U1a measurements the
        // research notes quote, so the move must be value-preserving.
        assertEquals(265, FmMotion.navPositionMs)
        assertEquals(1.28f, FmMotion.navStretchPeak)
        assertEquals(520, FmMotion.navStretchEndMs)
        assertEquals(80, FmMotion.navInkMs)
        assertEquals(200, FmMotion.contentCrossfadeMs)
    }
}
