package com.framemark.native.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * The platform "how much motion" input, expressed in [LocalFmMotionScale]'s `0..1`
 * domain (2026-11-14, motion batch 2).
 *
 * Compose Multiplatform 1.9.0 ships no `LocalMotionDurationScale` (see
 * [LocalFmMotionScale]), and an OS setting is not a composable value anyway: the
 * platform entry point reads the system state once, **before the first composition**,
 * maps it with the pure [FmMotionRules.scaleFromSystem], and installs it here.
 * [com.framemark.native.FrameMarkApp] hands [scale] to `FrameMarkTheme(motionScale = …)`,
 * which stays the single switch the animation helpers read.
 *
 * Platform values:
 *
 *  - **Android** — `Settings.Global.ANIMATOR_DURATION_SCALE` (`0` = animations off —
 *    this is also what the accessibility *Remove animations* toggle writes; `1` =
 *    normal; between = proportional). The public Android SDK has no reduced-motion
 *    query (`AccessibilityManager` exposes none), so that single scale is the input;
 *    `MainActivity` installs the mapped value before `setContent` and refreshes it in
 *    `onResume`, so leaving the app to change the setting takes effect on return.
 *    The reader lives in `AndroidMotionSettings` (androidMain).
 *  - **Desktop** — there is **no OS hook wired here**, so the value keeps the default
 *    [FmMotionRules.fullScale] (`1`, full motion). Windows' "show animations" /
 *    `SPI_GETCLIENTAREAANIMATION` and the macOS `NSWorkspace.accessibilityDisplayOptions`
 *    equivalent are filed as a follow-up in `docs/motion-spec.md` §4.1; inventing a
 *    value from e.g. the theme would be a guess, not the setting.
 *
 * A host that cannot read the setting installs nothing; the default is the full-motion
 * value, never a silent "reduce".
 */
object FmPlatformMotionScale {
    /**
     * The mapped scale. Compose state on purpose: the Android entry point refreshes it
     * on resume, and the theme must recompose when that happens.
     */
    var scale: Float by mutableFloatStateOf(FmMotionRules.fullScale)
        private set

    /** Install a mapped scale; anything outside `0..1` is clamped by [FmMotionRules]. */
    fun install(value: Float) {
        scale = FmMotionRules.clampScale(value)
    }
}
