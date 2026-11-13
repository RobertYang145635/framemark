package com.framemark.native.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure half of the platform "reduce motion" wiring (motion batch 2, 2026-11-14).
 *
 * `FmMotionRules.scaleFromSystem` is the whole decision: Android hands it the
 * `ANIMATOR_DURATION_SCALE` it read and the `AccessibilityManager.isReduceMotionEnabled`
 * answer (null below API 33), desktop hands it two nulls. The mapping table in the
 * function's KDoc is asserted here row by row, including the two states a naive
 * implementation gets wrong: an unset Android setting (negative sentinel) must mean
 * *full* motion, not none, and a scale above 1 must never amplify the app's motion.
 */
class MotionPlatformTest {

    @Test
    fun animationsOffIsNoMotion() {
        assertEquals(0f, FmMotionRules.scaleFromSystem(animatorDurationScale = 0f, reduceMotion = null))
        assertEquals(0f, FmMotionRules.scaleFromSystem(animatorDurationScale = 0f, reduceMotion = false))
    }

    @Test
    fun accessibilityReduceMotionWinsOverAnyScale() {
        // The accessibility toggle is a user's explicit "no motion": a normal or even
        // slowed-down animation scale must not override it.
        assertEquals(0f, FmMotionRules.scaleFromSystem(animatorDurationScale = 1f, reduceMotion = true))
        assertEquals(0f, FmMotionRules.scaleFromSystem(animatorDurationScale = 0.75f, reduceMotion = true))
        assertEquals(0f, FmMotionRules.scaleFromSystem(animatorDurationScale = null, reduceMotion = true))
    }

    @Test
    fun aReducedAnimationScaleIsProportional() {
        assertEquals(0.5f, FmMotionRules.scaleFromSystem(animatorDurationScale = 0.5f, reduceMotion = false))
        assertEquals(0.25f, FmMotionRules.scaleFromSystem(animatorDurationScale = 0.25f, reduceMotion = null))
        // The values Map to the same 0..1 domain the animation helpers already scale by.
        assertEquals(
            FmDuration.standard / 2,
            FmMotionRules.durationMs(
                FmDuration.standard,
                FmMotionRules.scaleFromSystem(0.5f, null),
            ),
        )
    }

    @Test
    fun normalScaleKeepsFullMotion() {
        assertEquals(1f, FmMotionRules.scaleFromSystem(animatorDurationScale = 1f, reduceMotion = false))
        assertEquals(1f, FmMotionRules.scaleFromSystem(animatorDurationScale = 1f, reduceMotion = null))
    }

    @Test
    fun desktopHasNoHookAndThereforeFullMotion() {
        // Both inputs unknown is exactly the desktop wiring: no OS hook, no guess.
        assertEquals(FmMotionRules.fullScale, FmMotionRules.scaleFromSystem(null, null))
    }

    @Test
    fun unknownIsFullMotionNotNoMotion() {
        // Android's "setting never written" sentinel is negative; NaN comes from a
        // corrupted read. Neither may silently disable motion.
        assertEquals(1f, FmMotionRules.scaleFromSystem(animatorDurationScale = -1f, reduceMotion = null))
        assertEquals(1f, FmMotionRules.scaleFromSystem(animatorDurationScale = Float.NaN, reduceMotion = null))
        assertEquals(
            1f,
            FmMotionRules.scaleFromSystem(animatorDurationScale = Float.NEGATIVE_INFINITY, reduceMotion = null),
        )
    }

    @Test
    fun aScaleAboveOneDoesNotAmplify() {
        // Material's scaleFactor domain ends at 1: a system 10x setting must not turn a
        // 200 ms transition into a two-second one.
        assertEquals(1f, FmMotionRules.scaleFromSystem(animatorDurationScale = 10f, reduceMotion = null))
        assertEquals(1f, FmMotionRules.scaleFromSystem(animatorDurationScale = 1.5f, reduceMotion = false))
    }

    @Test
    fun clampScaleKeepsTheDomainAndTreatsNaNAsFull() {
        assertEquals(1f, FmMotionRules.clampScale(3f))
        assertEquals(0f, FmMotionRules.clampScale(-3f))
        assertEquals(0.5f, FmMotionRules.clampScale(0.5f))
        assertEquals(1f, FmMotionRules.clampScale(Float.NaN))
    }

    @Test
    fun scaleZeroStillShortCircuitsEveryDuration() {
        // The reduce-motion contract has to survive the new platform path: a system
        // scale of 0 lands on the same 0 ms end state every helper already tests.
        val scale = FmMotionRules.scaleFromSystem(animatorDurationScale = 0f, reduceMotion = null)
        for (base in listOf(
            FmDuration.press,
            FmDuration.fast,
            FmDuration.standard,
            FmDuration.emphasized,
            FmDuration.exit,
        )) {
            assertEquals(FmDuration.instant, FmMotionRules.durationMs(base, scale))
        }
        assertTrue(!FmMotionRules.motionEnabled(scale))
    }
}
