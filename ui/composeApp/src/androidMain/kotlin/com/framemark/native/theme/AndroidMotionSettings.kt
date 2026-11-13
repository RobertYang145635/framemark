package com.framemark.native.theme

import android.content.Context
import android.provider.Settings

/**
 * Reads the Android "reduce motion" input and maps it onto FrameMark's `0..1` motion
 * scale with the pure [FmMotionRules.scaleFromSystem] rule (2026-11-14).
 *
 * **What Android actually exposes.** `Settings.Global.ANIMATOR_DURATION_SCALE` is the
 * developer-option animation scale (and the value the accessibility
 * *Remove animations* toggle writes): `0` means animations are off, `1` is normal,
 * anything between is a proportional speed (`0.5` is the common "animations 0.5×"
 * setting). The read passes a negative sentinel when the setting has never been
 * written, which [FmMotionRules.scaleFromSystem] treats as "unknown" → full motion.
 *
 * There is deliberately **no `AccessibilityManager` call** here: the public SDK
 * (`compileSdk` 36) exposes no reduced-motion query — `AccessibilityManager` has
 * `isTouchExplorationEnabled`, `isHighContrastTextEnabled`, `isAudioDescriptionRequested`
 * and friends, but nothing about motion. Android's accessibility "remove animations"
 * path *is* the animation scale being 0, which this reader already covers; a future
 * platform (or a host that finds a real query) plugs its boolean into the second
 * argument of [FmMotionRules.scaleFromSystem] without touching the mapping.
 *
 * The reader is deliberately a plain object with no observers: `MainActivity` calls it
 * before the first composition and again from `onResume`, which is exactly when a user
 * who went to Settings (or developer options) and changed the value comes back.
 */
object AndroidMotionSettings {
    /** What Android reports when the global setting has never been written. */
    private const val UNKNOWN_ANIMATOR_SCALE = -1f

    /** One read → one scale, in [LocalFmMotionScale]'s domain. */
    fun read(context: Context): Float = FmMotionRules.scaleFromSystem(
        animatorDurationScale = animatorDurationScale(context),
        // No public Android API answers "reduce motion"; the accessibility toggle is
        // the same setting read above. Kept explicit so the mapping stays two-input.
        reduceMotion = null,
    )

    /** `ANIMATOR_DURATION_SCALE`, or null when it cannot be read. */
    internal fun animatorDurationScale(context: Context): Float? = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            UNKNOWN_ANIMATOR_SCALE,
        )
    }.getOrNull()
}
