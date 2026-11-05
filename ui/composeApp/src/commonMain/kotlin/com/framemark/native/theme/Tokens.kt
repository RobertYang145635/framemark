package com.framemark.native.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * FrameMark design tokens (U1, HyperOS-light).
 *
 * Sources (read-only, never copied at runtime):
 *  - `framemark/src/renderer/src/styles.css` → `html[data-skin='hyperos']` L5476+
 *    for canvas/surface/ink/outline/radius intent; the same block carries the
 *    shipped accent `--accent: #2F75E5` (L5498) and `--accent-strong: #1D5FC6`
 *    (L5499) — FrameMark's own colours, not OPCameraPro's palette.
 *  - `_research/native/ui-reference/REPORT.md` for nav geometry + motion
 *    relationships only (no reference colour value is copied: the indicator is
 *    the palette's own grey-92 step).
 *
 * U1F correction: earlier revisions of this file used `#0C668D`, the accent of
 * the retired liquid-glass light block (styles.css L3712). The shipped
 * `index.html` selects `data-skin="hyperos"`, whose block (L5480 > L3710) wins at
 * equal specificity, so `#0C668D` is never painted (0 px in all nine P3 states).
 * It must not be described as "shipped"; `#2F75E5` / `#1D5FC6` are.
 *
 * Every screen must read roles from these objects; screen code may not hard-code
 * colours, durations or spacing.
 */

object FmPalette {
    // Surfaces -----------------------------------------------------------------
    /** App canvas behind cards (`--bg` of the old HyperOS skin). */
    val canvas = Color(0xFFF0F2F5)

    /** Card / sheet surface. */
    val surface = Color(0xFFFFFFFF)

    /** Inset surface (search fields, image wells, `--panel-3`). */
    val surfaceMuted = Color(0xFFF6F8FB)

    /**
     * Floating nav capsule: surface at high alpha (D-012: no backdrop blur in v1).
     * 0.97 still let card text ghost through the unblurred capsule in the U1
     * screenshots, so the surface is raised to 0.985 — high alpha, no readable
     * bleed-through, still not fully opaque.
     */
    val navSurface = surface.copy(alpha = 0.985f)

    // Ink ----------------------------------------------------------------------
    /** Primary text (`--text`). */
    val onSurface = Color(0xFF14181F)

    /** Secondary text / unselected nav ink (`--text-2`). */
    val onSurfaceSecondary = Color(0xFF3E4753)

    /** Supporting copy (`--muted`; the hyperos skin overrides it at styles.css L6066). */
    val onSurfaceMuted = Color(0xFF5C6673)

    /** Disabled / tertiary (`--faint`; overridden at styles.css L6067). */
    val onSurfaceFaint = Color(0xFF7F8A97)

    // Brand --------------------------------------------------------------------
    /**
     * Shipped FrameMark accent (`--accent` of the hyperos skin, styles.css L5498;
     * 53,252 px in P3-06). Fill role only: solid buttons, rail tile, cursor,
     * hairlines. 4.38:1 on white, i.e. below AA for 11–13 sp text, so all small
     * ink uses [primaryStrong] (5.99:1 on white) — the old app's own split.
     */
    val primary = Color(0xFF2F75E5)

    /**
     * Accent ink (`--accent-strong`, styles.css L5499): the old app's active
     * tab / chip / segment text. Use this for every primary-coloured small
     * label or glyph; [primary] itself fails 4.5:1 at 11–13 sp.
     */
    val primaryStrong = Color(0xFF1D5FC6)

    /** `--accent-ink` (styles.css L5500): label on [primary] fills. */
    val onPrimary = Color(0xFFFFFFFF)

    /** 10 % primary over surface — selected chip / soft highlight. */
    val primarySoft = primary.copy(alpha = 0.10f).compositeOver(surface)

    // Lines --------------------------------------------------------------------
    /** Hairline (`--line`). */
    val outline = Color(0xFFE5E9EF)

    /** Stronger divider (`--line-2`). */
    val outlineStrong = Color(0xFFD6DDE6)

    // Nav indicator ------------------------------------------------------------
    /**
     * Selected pill in the bottom nav. The old app has no neutral indicator
     * (D-012 keeps one: surface at ~0.9 lightness). `#000` 10 % over surface
     * rendered hue-0 `#E5E5E5`, the only warm-neutral patch in a 214–218° hue
     * palette, so the pill collapses onto the palette's grey-92 step (`--line`,
     * hue 216, 9,332–22,639 px in P3). Equal to [outline] by design.
     */
    val indicator = Color(0xFFE5E9EF)

    // Feedback -----------------------------------------------------------------
    val error = Color(0xFFC2413B)
    val warning = Color(0xFFB26A00)
    val scrim = Color(0x52101828)

    // Stage (photo canvas) -----------------------------------------------------
    // V06 appends these four roles; no existing token above is changed. The photo
    // stage is a neutral dark canvas with no dot matrix / grid decoration, so the
    // marks and the floating view tools need their own ink levels.
    /** Neutral dark stage behind the photo. */
    val stage = Color(0xFF25282D)

    /** Hairline on the stage: frame edges and tool separators. */
    val stageLine = Color(0xFF3A3F47)

    /** Primary ink on the stage. */
    val stageInk = Color(0xFFD4DAE3)

    /** Muted ink on the stage (secondary labels, hints). */
    val stageMuted = Color(0xFF969AA4)
}

object FmType {
    /** Screen title in the content flow (no top app bar, U1a B8). */
    val titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    )

    /** Section / card-group title (old HyperOS `h2` = 17px). */
    val titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    )

    val titleSmall = TextStyle(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    )

    val bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )

    /** Old app body copy was 13px; kept for density. */
    val bodyMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )

    val labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    )

    /** Bottom-nav label: 11sp, exactly as measured on the reference (U1a). */
    val labelSmall = TextStyle(
        fontSize = FmNav.labelSize,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    )
}

object FmSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp

    /** Horizontal screen edge. */
    val screen = 24.dp

    /** Bottom breathing room under scroll content (clears the floating nav). */
    val contentBottom = 112.dp
}

object FmRadii {
    val chip = 8.dp
    val input = 10.dp
    val tile = 12.dp
    val card = 14.dp
    val panel = 18.dp
    val stage = 22.dp
    val sheet = 24.dp
    val pill = 999.dp
}

object FmElevation {
    /** Floating nav capsule (D-012: soft 4dp shadow, no blur). */
    val nav = 4.dp
    val card = 1.dp
    val navAmbient = Color(0x0D101828)
    val navSpot = Color(0x1A101828)
    val cardAmbient = Color(0x08101828)
    val cardSpot = Color(0x0F101828)
}

object FmBreakpoints {
    /** Below this width the phone shell (bottom nav) replaces the desktop scaffold. */
    val compact = 600.dp
}

/**
 * U5 desktop three-column geometry.
 *
 * Reference: the P3 Electron baseline at 1280×800 has a 248 dp library panel, a
 * 320 dp inspector panel, 12 dp canvas gaps and rounded white panels on the
 * `#F0F2F5` canvas. Those are layout/spacing references only — the panels are
 * native Compose, not a copy of the web DOM.
 */
object FmDesktop {
    val topBarHeight = 72.dp
    val edge = 12.dp
    val gap = 12.dp
    val panelRadius = 18.dp

    /** Default rail widths = P3 panel widths at 1280×800. */
    val libraryWidth = 248.dp
    val libraryMin = 180.dp
    val libraryMax = 340.dp

    val inspectorWidth = 320.dp
    val inspectorMin = 240.dp
    val inspectorMax = 420.dp

    /** The stage never goes below this; below ~620 dp the right rail folds away. */
    val stageMin = 220.dp

    /** Drag strip between a rail and the stage, and the collapsed-rail affordance. */
    val handleWidth = 10.dp
    val collapsedWidth = 24.dp

    val filmstripHeight = 104.dp
}

/**
 * Bottom-nav geometry from U1a (dp values transfer density-independently).
 * Item width is not fixed: 4 equal cells fill `screenWidth - 2 × sideMargin - 2 × padding`.
 */
object FmNav {
    val itemCount = 4
    val sideMargin = 24.dp
    val bottomMargin = 24.dp
    val height = 64.dp
    val padding = 4.dp
    val itemHeight = height - padding * 2 // 56dp
    val iconSlot = 24.dp
    val iconGlyph = 18.dp
    val iconLabelGap = 2.dp
    val labelSize = 11.sp
}

/**
 * Motion tokens. Targets come from U1a measurements (position 250–330 ms,
 * width +28 % mid-travel then ~250 ms relax, content crossfade ~200 ms, ink
 * 50–100 ms). Easings were *not* measured on the reference; the curves below are
 * FrameMark choices and are documented as such in `_research/native/U1.md`.
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
