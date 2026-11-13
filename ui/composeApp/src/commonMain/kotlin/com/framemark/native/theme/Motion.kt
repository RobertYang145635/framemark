package com.framemark.native.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * FrameMark motion tokens — the one home for interaction animation (UI V2, batch 1).
 *
 * ## Why a scale instead of literals at the call site
 *
 * The rest of `theme/` already forbids screen code from writing colours or spacing
 * by hand; motion follows the same rule. A call site names an **intent**
 * (`FmDuration.emphasized`, `FmEasing.enter`) and this file owns the number, so
 * retuning the app's feel is one edit and nothing has to be grepped.
 *
 * ## Reduced motion
 *
 * Every animation in this app has to be able to finish *immediately*. Compose's own
 * animation core reads `androidx.compose.ui.MotionDurationScale` from the coroutine
 * context, but Compose Multiplatform **1.9.0 does not ship `LocalMotionDurationScale`**
 * (checked against the resolved `ui-desktop` artifact — only the `MotionDurationScale`
 * interface and its `Key` exist), so there is no platform value to read from a
 * composable. FrameMark therefore supplies [LocalFmMotionScale] with the same meaning
 * and the same 0..1 domain as `MotionDurationScale.scaleFactor`:
 *
 *  - `1f` (default) — full motion;
 *  - between — a slower/faster taste, e.g. `0.5f`;
 *  - `0f` — **no motion**: every helper here collapses to `snap()` /
 *    `EnterTransition.None`, i.e. straight to the end state.
 *
 * Tests drive that by wrapping a scene in
 * `CompositionLocalProvider(LocalFmMotionScale provides 0f)`. Screen code never
 * branches on the scale itself; it calls [fmTweenSpec] / [fmSpringSpec] /
 * [fmOverlayEnter] / [fmOverlayExit], which do.
 *
 * The pure half of the rules ([FmMotionRules]) is deliberately Compose-free so it can
 * be unit-tested in `commonTest` without a UI harness.
 *
 * ## What this file does *not* buy you
 *
 * Tokens give consistency, not judgement. Two rules travel with them (see the motion
 * spec in `docs/motion-spec.md`):
 *
 *  1. **Never animate a value the user is dragging.** A finger on the screen already
 *     defines the frame; an animation on the same property turns tracking into lag.
 *     Animate only the *commit* after release.
 *  2. **Never animate canvas pixels.** The photo, the frame render and any preview
 *     bitmap are the product; transitions belong to chrome around them.
 */
object FmDuration {
    /** No motion. Use only for the scale-0 end state, never as a taste value. */
    const val instant = 0

    /** Pointer-down / release feedback on a control that is already on screen. */
    const val press = 90

    /** Ink, ring, dot and other "state flip" changes. */
    const val fast = 140

    /** Content crossfade, a row settling in, a card revealing. */
    const val standard = 200

    /** A panel travelling most of the screen: sheet snap, overlay slide. */
    const val emphasized = 320

    /**
     * Exits are shorter than enters (Material 3: enter ~400 ms emphasised-decelerate,
     * exit ~200 ms emphasised-accelerate). Leaving must feel like the app got out of
     * the way, not like a second entrance.
     */
    const val exit = 180
}

/**
 * Easing curves. Values are the Material 3 sets, written as the four control points
 * `CubicBezierEasing(x1, y1, x2, y2)`; the doc comments name the M3 token each one is
 * so a future designer can check them against m3.material.io without reading code.
 */
object FmEasing {
    /** M3 `standard` — `cubic-bezier(0.2, 0, 0, 1)`. The default for UI transitions. */
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** M3 `standard-decelerate` — an element arriving and coming to rest. */
    val enter = CubicBezierEasing(0f, 0f, 0f, 1f)

    /** M3 `standard-accelerate` — an element leaving the screen. */
    val exit = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /** M3 `emphasized-decelerate` — `Cubic(0.05, 0.7, 0.1, 1.0)`. Big arrivals. */
    val emphasizedEnter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** M3 `emphasized-accelerate` — `Cubic(0.3, 0.0, 0.8, 0.15)`. Big departures. */
    val emphasizedExit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** For determinate progress only; never for a state change. */
    val linear = LinearEasing

    /** Kept for the U1a nav measurements ([FmMotion]); not for new work. */
    val legacyNav = FastOutSlowInEasing
}

/**
 * A spring as data, so one preset can be applied to `Float`, `Dp`, `Color` … without
 * storing a `SpringSpec<T>` per type.
 *
 * Springs are only for motion that is *interrupted* (a sheet the user released, a card
 * they pressed): they carry velocity, which a fixed tween cannot. Fire-and-forget
 * transitions use [FmDuration] + [FmEasing] instead — a spring there just adds a tail
 * nobody asked for.
 */
class FmSpringSpec(val dampingRatio: Float, val stiffness: Float) {
    init {
        require(dampingRatio > 0f && stiffness > 0f) {
            "spring presets need a positive damping ratio and stiffness"
        }
    }

    fun <T> spec(): SpringSpec<T> = spring(dampingRatio = dampingRatio, stiffness = stiffness)
}

object FmSpring {
    /**
     * A panel travelling to its stop. Slightly under-damped on purpose: a sheet that
     * lands dead-stop reads as a cut, not as "it went where I put it". `0.85` keeps
     * the overshoot well under a pixel on a phone-sized travel.
     */
    val settle = FmSpringSpec(dampingRatio = 0.85f, stiffness = 380f)

    /** Press feedback on an already-visible control: fast, small, barely a bounce. */
    val press = FmSpringSpec(dampingRatio = 0.55f, stiffness = 1200f)

    /** Reveal of a list/grid item: critically damped, no overshoot to catch the eye. */
    val gentle = FmSpringSpec(dampingRatio = 1f, stiffness = 200f)
}

/**
 * Motion amount, mirroring `MotionDurationScale.scaleFactor`: `1f` full motion,
 * `0f` none. Provided by [FrameMarkTheme] (as `1f`); a host that knows the OS
 * "reduce motion" setting overrides it.
 */
val LocalFmMotionScale = staticCompositionLocalOf<Float> { FmMotionRules.fullScale }

/**
 * Pure motion rules — the part worth unit-testing.
 *
 * Everything here is a function of numbers and has no Compose dependency, so it runs in
 * `commonTest`/`desktopTest` without a composition.
 */
object FmMotionRules {
    /** Full-motion value of [LocalFmMotionScale]. */
    const val fullScale = 1f

    /** Delay added per revealed item. */
    const val revealStepMs = 28

    /** Stagger is capped: the 13th item does not wait 13 × 28 ms. */
    const val revealCapMs = 168

    /**
     * Only this many items animate in at all. A grid scrolls; re-running an entrance
     * for item 40 when it is scrolled into view is a twitch, not a reveal.
     */
    const val revealItems = 12

    /**
     * Does this grid/list index take part in the first-load reveal?
     *
     * The cap is the whole point: past [revealItems] the entrance is skipped, not
     * merely shortened, so scrolling a long grid never replays it.
     */
    fun shouldReveal(index: Int): Boolean = index >= 0 && index < revealItems

    /**
     * Stagger delay for [index], capped at [capMs]. Negative indices are clamped to 0.
     */
    fun revealDelayMs(
        index: Int,
        stepMs: Int = revealStepMs,
        capMs: Int = revealCapMs,
    ): Int {
        if (index <= 0) return 0
        val raw = index * stepMs
        return if (raw > capMs) capMs else raw
    }

    /**
     * Effective duration for a base duration at [scale]. Returns `0` when motion is off
     * — the caller's spec becomes `snap()`, i.e. the end state — and never returns a
     * positive sub-millisecond duration, which would round to a 0 ms `tween` that still
     * occupies a frame.
     */
    fun durationMs(baseMs: Int, scale: Float): Int =
        if (scale <= 0f || baseMs <= 0) FmDuration.instant else (baseMs * scale).roundToInt().coerceAtLeast(1)

    /** Convenience predicate so call sites and tests spell "is motion allowed" one way. */
    fun motionEnabled(scale: Float): Boolean = scale > 0f

    /**
     * Map the OS animation settings onto [LocalFmMotionScale]'s `0..1` domain.
     *
     * The platform reads two independent switches and hands them in nullable, because
     * "unknown" is a real state (desktop has neither; Android's public SDK has no
     * reduced-motion query, so it passes `null` for it and its animation scale alone
     * decides):
     *
     * | system state | [animatorDurationScale] | [reduceMotion] | result |
     * |---|---|---|---|
     * | animations off | `0` | any | `0` (no motion) |
     * | reduced motion on | any | `true` | `0` |
     * | slower (0.5×, …) | `0 < s < 1` | `false`/null | `s` |
     * | normal | `1` | `false`/null | `1` |
     * | faster than normal | `> 1` | any | `1` — never amplify |
     * | not reported | `null` / negative / NaN | `null` | `1` |
     *
     * A value above 1 is *not* "more motion": Material's `scaleFactor` domain ends at
     * 1, and a system-wide 10× animation speed must not turn a press into a slideshow
     * in an app that only ever reduces. Android's unset `ANIMATOR_DURATION_SCALE`
     * arrives as a negative sentinel from the platform reader; it maps to "unknown",
     * not to "no motion".
     */
    fun scaleFromSystem(animatorDurationScale: Float?, reduceMotion: Boolean?): Float {
        if (reduceMotion == true) return FmDuration.instant.toFloat()
        val reported = animatorDurationScale ?: return fullScale
        if (!reported.isFinite() || reported < 0f) return fullScale
        if (reported == 0f) return 0f
        if (reported >= fullScale) return fullScale
        return reported
    }

    /**
     * Clamp a value into the `0..1` domain before it becomes [LocalFmMotionScale].
     * Non-finite ("unknown") is full motion, the safe default: a broken read must not
     * silently disable the app's motion.
     */
    fun clampScale(value: Float): Float =
        if (!value.isFinite()) fullScale else value.coerceIn(0f, fullScale)

    /**
     * Index of the stop nearest to [value]. Ties resolve to the **lower** index, which
     * is what `List.minByOrNull` did before this was extracted — a release halfway
     * between two stops settles towards the sheet being less open.
     */
    fun nearestStop(stops: List<Float>, value: Float): Int {
        if (stops.isEmpty()) return 0
        var best = 0
        var bestDistance = abs(stops[0] - value)
        for (i in 1 until stops.size) {
            val distance = abs(stops[i] - value)
            if (distance < bestDistance) {
                best = i
                bestDistance = distance
            }
        }
        return best
    }
}

/**
 * A tween that becomes `snap()` when motion is off.
 *
 * Use this (or [fmSpringSpec]) instead of calling `tween(...)` directly anywhere the
 * scale must be honoured.
 */
@Composable
fun <T> fmTweenSpec(
    baseMs: Int,
    easing: Easing = FmEasing.standard,
): FiniteAnimationSpec<T> {
    val scale = LocalFmMotionScale.current
    return if (FmMotionRules.motionEnabled(scale)) {
        tween(FmMotionRules.durationMs(baseMs, scale), easing = easing)
    } else {
        snap()
    }
}

/** A spring preset that becomes `snap()` when motion is off. */
@Composable
fun <T> fmSpringSpec(spec: FmSpringSpec): FiniteAnimationSpec<T> {
    val scale = LocalFmMotionScale.current
    return if (FmMotionRules.motionEnabled(scale)) spec.spec() else snap()
}

/**
 * Enter transition for a full-screen layer that rises over the editor (brand picker,
 * border library): the scrim fades, the layer lifts by about a twelfth of its height.
 *
 * The lift is deliberately small. A full-height slide would drag the scrim with it and
 * look like a page push; a fifth of the height already reads as "this came from the
 * bottom" without implying navigation.
 */
@Composable
fun fmOverlayEnter(): EnterTransition {
    val scale = LocalFmMotionScale.current
    if (!FmMotionRules.motionEnabled(scale)) return EnterTransition.None
    return fadeIn(
        tween(FmMotionRules.durationMs(FmDuration.standard, scale), easing = FmEasing.enter),
    ) + slideInVertically(
        tween(FmMotionRules.durationMs(FmDuration.emphasized, scale), easing = FmEasing.emphasizedEnter),
        initialOffsetY = { it / 12 },
    )
}

/** Exit for an overlay: shorter than [fmOverlayEnter], no rebound. */
@Composable
fun fmOverlayExit(): ExitTransition {
    val scale = LocalFmMotionScale.current
    if (!FmMotionRules.motionEnabled(scale)) return ExitTransition.None
    val ms = FmMotionRules.durationMs(FmDuration.exit, scale)
    return fadeOut(tween(ms, easing = FmEasing.exit)) + slideOutVertically(
        tween(ms, easing = FmEasing.emphasizedExit),
        targetOffsetY = { it / 12 },
    )
}

/**
 * Enter for content that swaps **in place** — a sheet page, a tab's rows. A short fade
 * with the smallest perceptible rise.
 *
 * The travel is a fortieth of the height, not a screenful: the container does not move,
 * so a long slide would read as a page arriving from somewhere it did not come from.
 * Nothing here animates a value inside the content; the page's own sliders and text are
 * untouched.
 */
@Composable
fun fmContentEnter(): EnterTransition {
    val scale = LocalFmMotionScale.current
    if (!FmMotionRules.motionEnabled(scale)) return EnterTransition.None
    val ms = FmMotionRules.durationMs(FmDuration.standard, scale)
    return fadeIn(tween(ms, easing = FmEasing.enter)) +
        slideInVertically(tween(ms, easing = FmEasing.emphasizedEnter)) { it / 40 }
}

/** Exit for in-place swapped content: shorter than the entrance, fade only. */
@Composable
fun fmContentExit(): ExitTransition {
    val scale = LocalFmMotionScale.current
    if (!FmMotionRules.motionEnabled(scale)) return ExitTransition.None
    return fadeOut(tween(FmMotionRules.durationMs(FmDuration.exit, scale), easing = FmEasing.exit))
}

/**
 * First-load reveal for a list/grid item: fade in and drift up [distance], staggered by
 * [index] and capped by [FmMotionRules].
 *
 * Two boundaries matter:
 *  - index ≥ [FmMotionRules.revealItems] does **not** animate at all, so a long grid
 *    scrolled into view never twitches;
 *  - scale 0 renders the item at its final state from the first frame.
 *
 * This animates alpha and translation only, never size: a grid that grows into place
 * reflows its neighbours on every frame.
 */
@Composable
fun Modifier.fmRevealOnEnter(index: Int, distance: Dp = 6.dp): Modifier {
    val scale = LocalFmMotionScale.current
    val animate = FmMotionRules.shouldReveal(index) && FmMotionRules.motionEnabled(scale)
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        val wait = FmMotionRules.revealDelayMs(index)
        if (wait > 0) delay(wait.toLong())
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                FmMotionRules.durationMs(FmDuration.standard, scale),
                easing = FmEasing.enter,
            ),
        )
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * distance.toPx()
    }
}

/**
 * Motion tokens from the U1a bottom-nav measurement — **legacy record, not a palette**.
 *
 * The four-tab capsule these describe has been deleted (the phone IA is the old editor
 * bar again), and nothing in the app reads them any more. They stay because the U1
 * motion table quotes these exact numbers and a future nav rewrite should start from the
 * measurement rather than re-derive it. New work uses [FmDuration] / [FmEasing] /
 * [FmSpring] above; do not extend this block.
 *
 * Targets: position 250–330 ms, width +28 % mid-travel then ~250 ms relax, content
 * crossfade ~200 ms, ink 50–100 ms. Easings were *not* measured on the reference; the
 * curves below are FrameMark choices, documented as such in `_research/native/U1.md`.
 */
object FmMotion {
    const val navPositionMs = 265
    const val navPositionEasingX = 0.2f
    const val navPositionEasingY = 0.0f
    const val navPositionEasingZ = 0.0f
    const val navPositionEasingW = 1.0f

    val navPositionEasing = CubicBezierEasing(
        navPositionEasingX,
        navPositionEasingY,
        navPositionEasingZ,
        navPositionEasingW,
    )

    /** Peak width multiplier mid-travel (+28 %). */
    const val navStretchPeak = 1.28f

    /** Peak sample time inside the 520 ms stretch envelope. */
    const val navStretchPeakAtMs = 130

    /** Width dips slightly past nominal when the position lands (U1a: 237 vs 247). */
    const val navStretchDip = 0.96f
    const val navStretchDipAtMs = navPositionMs

    /** Width envelope total: 265 ms travel + ~250 ms relax ≈ 515 ms. */
    const val navStretchEndMs = 520

    /** Ink swap (50–100 ms target). */
    const val navInkMs = 80
    val navInkEasing = FastOutSlowInEasing

    /** Content crossfade (~200 ms target). */
    const val contentCrossfadeMs = 200
    val contentEasing = FastOutSlowInEasing

    /** Stretch envelope segment easings (chosen, not measured). */
    val stretchAttack = FastOutSlowInEasing
    val stretchDip = LinearOutSlowInEasing
    val stretchRelax = LinearEasing
}
