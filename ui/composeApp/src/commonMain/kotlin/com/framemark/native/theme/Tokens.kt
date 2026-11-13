package com.framemark.native.theme

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * FrameMark design tokens (UI V2, theme-aware).
 *
 * Sources (read-only, never copied at runtime):
 *  - Light values: the V2 showcase `E:\FrameMark\design\ui-v2-showcase\src\styles.css`
 *    `:root` block (canvas/surface/ink/line/accent/stage intent). The shipped
 *    accent stays `--accent: #2F75E5` / `--accent-strong: #1D5FC6`.
 *  - Dark values: HANDOFF part 2 §3 table (the "深色（新）" column).
 *
 * U1F correction (kept verbatim from the light-only revision): earlier revisions
 * used `#0C668D`, the accent of the retired liquid-glass light block
 * (styles.css L3712). The shipped `index.html` selects `data-skin="hyperos"`,
 * whose block wins at equal specificity, so `#0C668D` is never painted. It must
 * not be described as "shipped"; `#2F75E5` / `#1D5FC6` are.
 *
 * Structure (WP-T):
 *  - [FmColors] is an immutable snapshot of one appearance (Light or Dark) and
 *    owns every derived colour, so a derived value can never mix the two themes.
 *  - [LocalFmColors] supplies the current snapshot; [FrameMarkTheme] provides it.
 *  - [FmPalette] keeps its historical member names but is now a thin
 *    `@Composable @ReadOnlyComposable` reader over [LocalFmColors]. Reading it
 *    from a non-composable scope (a `drawBehind {}` lambda, `remember {}`,
 *    a plain function) is a compile error by design: capture the value into a
 *    local first, or pass `FmColors` as a parameter.
 *
 * Every screen must read roles from these objects; screen code may not hard-code
 * colours, durations or spacing.
 */

/**
 * One immutable appearance snapshot.
 *
 * Use [Light] or [Dark] (or `LocalFmColors.current` inside a composable) instead
 * of constructing a third one. Derived roles ([navSurface], [primarySoft],
 * [ring], [indicator]) are computed from **this** snapshot's own base colours.
 */
class FmColors(
    val canvas: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val onSurface: Color,
    val onSurfaceSecondary: Color,
    val onSurfaceMuted: Color,
    val onSurfaceFaint: Color,
    val outline: Color,
    val outlineStrong: Color,
    val primary: Color,
    val primaryStrong: Color,
    val onPrimary: Color,
    val error: Color,
    val warning: Color,
    val success: Color,
    val scrim: Color,
    val hover: Color,
    val press: Color,
    val track: Color,
    val sliderIdle: Color,
    val stage: Color,
    val stageDark: Color,
    val stageMid: Color,
    val stageLight: Color,
    val stageLine: Color,
    val stageInk: Color,
    val stageMuted: Color,
    val shadowNavAmbient: Color,
    val shadowNavSpot: Color,
    val shadowCardAmbient: Color,
    val shadowCardSpot: Color,
    /** Primary opacity used by [primarySoft] (10 % light / 18 % dark). */
    val primarySoftAlpha: Float,
    /** Primary opacity used by [ring] (28 % light / 40 % dark). */
    val ringAlpha: Float,
) {
    /**
     * Floating nav capsule: surface at high alpha (D-012: no backdrop blur in v1).
     * 0.97 still let card text ghost through the unblurred capsule in the U1
     * screenshots, so the surface is raised to 0.985 — high alpha, no readable
     * bleed-through, still not fully opaque.
     */
    val navSurface: Color = surface.copy(alpha = 0.985f)

    /** Primary at [primarySoftAlpha] over [surface] — selected chip / soft highlight. */
    val primarySoft: Color = primary.copy(alpha = primarySoftAlpha).compositeOver(surface)

    /** Focus ring: primary at [ringAlpha]. Translucent on purpose, draw it over the surface. */
    val ring: Color = primary.copy(alpha = ringAlpha)

    /**
     * Selected pill in the bottom nav. The old app has no neutral indicator
     * (D-012 keeps one: surface at ~0.9 lightness). `#000` 10 % over surface
     * rendered hue-0 `#E5E5E5`, the only warm-neutral patch in a 214–218° hue
     * palette, so the pill collapses onto the palette's own line step. Equal to
     * `outline` by design, in both appearances.
     */
    val indicator: Color = outline

    /** Stage backdrop for a stage-tone choice. D1/P1 switch the stage with this. */
    fun stageColor(tone: StageTone): Color = when (tone) {
        StageTone.Dark -> stageDark
        StageTone.Mid -> stageMid
        StageTone.Light -> stageLight
    }

    companion object {
        /**
         * FrameMark accent (`--accent`, styles.css `:root`). Identical in both
         * appearances. Fill role only: solid buttons, rail tile, cursor,
         * hairlines. 4.38:1 on white, i.e. below AA for 11–13 sp text, so all
         * small ink uses [primaryStrong] (5.99:1 on white) — the old app's own
         * split. Single definition (the user may still switch it to `#2B6FD8`).
         */
        private val PRIMARY = Color(0xFF2F75E5)

        /** Single definition of the light slider idle grey (may become `#8A95A4`). */
        private val SLIDER_IDLE_LIGHT = Color(0xFF9AA4B1)

        /**
         * Light slider idle for the **dark** appearance.
         *
         * WCAG relative luminance of `#1D1E22` (dark surface): 0.01306.
         * `#6B7688` → L = 0.17863; contrast = (0.17863 + 0.05) / (0.01306 + 0.05)
         * = 0.22863 / 0.06306 = **3.63 : 1** (requirement: ≥ 3 : 1). The value
         * stays in the cool-grey family of the light token and sits 3.6× brighter
         * than the surface, so the untouched part of a slider still reads.
         */
        private val SLIDER_IDLE_DARK = Color(0xFF6B7688)

        private val STAGE_DARK = Color(0xFF2B2C2F)
        private val STAGE_MID = Color(0xFF5A5C61)
        private val STAGE_LIGHT = Color(0xFFD9DADD)

        /** Light appearance — the V2 showcase `:root` values. */
        val Light: FmColors = FmColors(
            canvas = Color(0xFFF0F2F5),
            surface = Color(0xFFFFFFFF),
            surfaceMuted = Color(0xFFF6F8FB),
            onSurface = Color(0xFF14181F),
            onSurfaceSecondary = Color(0xFF3E4753),
            onSurfaceMuted = Color(0xFF5C6673),
            onSurfaceFaint = Color(0xFF7F8A97),
            outline = Color(0xFFE3E7EC),
            outlineStrong = Color(0xFFD5DBE2),
            primary = PRIMARY,
            primaryStrong = Color(0xFF1D5FC6),
            onPrimary = Color(0xFFFFFFFF),
            error = Color(0xFFD2453B),
            warning = Color(0xFFC27A0E),
            success = Color(0xFF1E9E5A),
            scrim = Color(0x52101828),
            hover = Color(0x0B14181F),
            press = Color(0x1414181F),
            track = Color(0xFFDCE1E7),
            sliderIdle = SLIDER_IDLE_LIGHT,
            stage = STAGE_DARK,
            stageDark = STAGE_DARK,
            stageMid = STAGE_MID,
            stageLight = STAGE_LIGHT,
            stageLine = Color(0xFF3A3F47),
            stageInk = Color(0xFFD4DAE3),
            stageMuted = Color(0xFF969AA4),
            shadowNavAmbient = Color(0x0D101828),
            shadowNavSpot = Color(0x1A101828),
            shadowCardAmbient = Color(0x08101828),
            shadowCardSpot = Color(0x0F101828),
            primarySoftAlpha = 0.10f,
            ringAlpha = 0.28f,
        )

        /**
         * Dark appearance — HANDOFF part 2 §3 "深色（新）" column.
         *
         * The photo stage stays `#2B2C2F` in both appearances (hand-off decision
         * 2: the photo area is always dark), and the shadow colours move to
         * `#000` at a higher alpha because a `#101828` tint is invisible on
         * `#121316`.
         */
        val Dark: FmColors = FmColors(
            canvas = Color(0xFF121316),
            surface = Color(0xFF1D1E22),
            surfaceMuted = Color(0xFF26282D),
            onSurface = Color(0xFFEDEFF3),
            onSurfaceSecondary = Color(0xFFC3C8D0),
            onSurfaceMuted = Color(0xFF9AA1AC),
            onSurfaceFaint = Color(0xFF7D8490),
            outline = Color(0xFF2E3036),
            outlineStrong = Color(0xFF3A3D44),
            primary = PRIMARY,
            primaryStrong = Color(0xFF7FB0FF),
            onPrimary = Color(0xFFFFFFFF),
            error = Color(0xFFF06A5F),
            warning = Color(0xFFE0A23A),
            success = Color(0xFF3CC07A),
            // 60 % black: the light scrim's 32 % `#101828` only dims `#121316`
            // to `#0C0D0E`, which no longer separates a sheet from the canvas.
            scrim = Color(0x99000000),
            hover = Color(0x0FFFFFFF),
            press = Color(0x1AFFFFFF),
            track = Color(0xFF3A3D44),
            sliderIdle = SLIDER_IDLE_DARK,
            stage = STAGE_DARK,
            stageDark = STAGE_DARK,
            stageMid = STAGE_MID,
            stageLight = STAGE_LIGHT,
            stageLine = Color(0xFF3A3F47),
            stageInk = Color(0xFFD4DAE3),
            stageMuted = Color(0xFF969AA4),
            shadowNavAmbient = Color(0x1F000000),
            shadowNavSpot = Color(0x40000000),
            shadowCardAmbient = Color(0x14000000),
            shadowCardSpot = Color(0x24000000),
            primarySoftAlpha = 0.18f,
            ringAlpha = 0.40f,
        )
    }
}

/**
 * The appearance every `FmPalette` read resolves against. Provided by
 * [FrameMarkTheme]; defaults to [FmColors.Light] so a preview or a test that
 * never wraps its content still gets the light palette.
 */
val LocalFmColors = staticCompositionLocalOf { FmColors.Light }

/**
 * Whether the resolved appearance is dark. Provided by [FrameMarkTheme]; read it
 * when a component needs the appearance as a boolean (system-bar icons, shadow
 * vs. hairline), not just as colours.
 */
val LocalFmDark = staticCompositionLocalOf { false }

/**
 * Colour roles. Member names are frozen (758 references across 24 files when the
 * token layer was converted) — only the *source* changed, from static constants
 * to [LocalFmColors].
 *
 * Usage notes that are easy to get wrong (UX-RESEARCH A-11):
 *  - [onSurfaceFaint] (ink-4) is for **disabled state and placeholder text only**.
 *    It never carries real copy.
 *  - [sliderIdle] is only for the **unmodified** part of a slider. The modified
 *    part uses [primary]; do not reuse [sliderIdle] for a general track line
 *    (that is [track]).
 */
object FmPalette {
    // Surfaces -----------------------------------------------------------------
    /** App canvas behind cards (`--canvas`). */
    val canvas: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.canvas

    /** Card / sheet surface (`--surface`). */
    val surface: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.surface

    /** Inset surface (search fields, image wells, `--muted-bg`). */
    val surfaceMuted: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.surfaceMuted

    /** Floating nav capsule: surface at high alpha, see [FmColors.navSurface]. */
    val navSurface: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.navSurface

    // Ink ----------------------------------------------------------------------
    /** Primary text (`--ink`). */
    val onSurface: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.onSurface

    /** Secondary text / unselected nav ink (`--ink-2`). */
    val onSurfaceSecondary: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.onSurfaceSecondary

    /** Supporting copy (`--ink-3`). */
    val onSurfaceMuted: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.onSurfaceMuted

    /** Disabled / placeholder only (`--ink-4`), see the usage note above. */
    val onSurfaceFaint: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.onSurfaceFaint

    // Brand --------------------------------------------------------------------
    /** Shipped accent (`--accent`). Fill role only; small ink uses [primaryStrong]. */
    val primary: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.primary

    /** Accent ink (`--accent-strong`): every primary-coloured small label or glyph. */
    val primaryStrong: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.primaryStrong

    /** `--accent-ink`: label on [primary] fills. */
    val onPrimary: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.onPrimary

    /** 10 % primary over surface (18 % in dark) — selected chip / soft highlight. */
    val primarySoft: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.primarySoft

    // Lines --------------------------------------------------------------------
    /** Hairline (`--line`). */
    val outline: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.outline

    /** Stronger divider (`--line-2`), and the dark-mode elevation outline. */
    val outlineStrong: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.outlineStrong

    /** General track line behind a slider (`--track`). */
    val track: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.track

    // Interaction --------------------------------------------------------------
    /** Hover wash: ink 4.5 % (white 6 % in dark). */
    val hover: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.hover

    /** Press wash: ink 8 % (white 10 % in dark). */
    val press: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.press

    /** Focus ring: primary 28 % (40 % in dark). */
    val ring: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.ring

    // Nav indicator ------------------------------------------------------------
    /** Selected pill in the bottom nav. Equal to [outline] by design. */
    val indicator: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.indicator

    // Feedback -----------------------------------------------------------------
    val error: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.error

    val warning: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.warning

    /** Success / done accent (`--ok`). */
    val success: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.success

    val scrim: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.scrim

    // Stage (photo canvas) -----------------------------------------------------
    /**
     * Neutral dark stage behind the photo. `#2B2C2F` in **both** appearances:
     * the photo area stays dark (hand-off part 2 §3 decision 2). The switchable
     * tones live in [stageDark] / [stageMid] / [stageLight].
     */
    val stage: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.stage

    /** Stage tone: dark grey (default), identical in both appearances. */
    val stageDark: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.stageDark

    /** Stage tone: mid grey. */
    val stageMid: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.stageMid

    /** Stage tone: light grey. */
    val stageLight: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.stageLight

    /** Hairline on the stage: frame edges and tool separators. */
    val stageLine: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.stageLine

    /** Primary ink on the stage. */
    val stageInk: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.stageInk

    /** Muted ink on the stage (secondary labels, hints). */
    val stageMuted: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.stageMuted

    /**
     * Slider idle part only (unmodified state), see the usage note above.
     * Light `#9AA4B1`; dark `#6B7688` (3.63:1 on the dark surface).
     */
    val sliderIdle: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.sliderIdle
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

    // ---- 演示稿桌面字号（PARITY-SPEC §1 / `10-spec.png` "字体"卡）----------------
    //
    // 上面 22/17/15/13/12 是 V06 的手机端尺度，**保持原样**（手机端在用）。
    // 桌面区按演示稿补一套 16/15/14/12.5/12/11/10.5；数值文本另用 [FmNumeric]。
    // 13 sp 正文没有新增成员：现有 [bodyMedium] 就是 13 / Normal，行高 19 与演示稿
    // `13px/1.55`≈20 同档，重复造一个只会让两处慢慢分叉。

    /** 16 / 700：右栏检查器标题（`.ihead h3`，16px/700）。 */
    val desktopHeading = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp,
    )

    /** 15 / 700：顶栏词标「框印」（`.wordmark`，15px/700）。 */
    val desktopWordmark = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp,
    )

    /** 14 / 500：左栏页签（`.utabs button` 14px；选中态由调用点加 600）。 */
    val desktopTab = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    )

    /** 12.5 / 500：chip、段控按钮、次要按钮（`.chip` / `.seg button`）。 */
    val desktopChip = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    )

    /** 12 / 400：说明与标签（`.proj .saved`、`.strip .sinfo`、舞台上 meta 的中文）。 */
    val desktopCaption = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )

    /** 11 / 400：次级说明（页签计数后的说明、分组 note）。 */
    val desktopFootnote = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )

    /** 10.5 / 500：胶片条编号行（`.frame .fl`；数字本身用 [FmNumeric.frameNumber]）。 */
    val desktopFrameNumber = TextStyle(
        fontSize = 10.5.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    )
}

/**
 * Spacing ladder. AU4-18 (WP-TY1): the old `xxs = 2.dp` sat **off** the 4 grid the
 * showcase and `10-spec.png` use, so every call site was 2 dp tighter than the
 * ladder said. It is now 4 dp; the two places that genuinely needed 2 dp were
 * "a line, not a space" and moved to [FmRhythm.hairline] (1 dp) or an inline
 * [FmRhythm.inline] gap. Dashes and hairlines never use a spacing step.
 *
 * 档位语义见 [FmRhythm]：行内 2/4/6、行 8/10、分组 12/16、大节 24/32。
 */
object FmSpacing {
    /**
     * 最紧的一档。AU4-18 之前是 2 dp（越出 4 网格），现在是 4 dp —— 与 [xs] 同值，
     * 保留这个名字是因为 `inspector/BrandLogoLibrary.kt`、`inspector/FmControls.kt`、
     * `inspector/InspectorScreen.kt` 里还有 6 个调用点在用"最紧一档"这个语义
     * （那三个文件不在 WP-TY1 可改范围内），删掉名字会把语义丢掉。
     */
    val xxs = 4.dp
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
    /** Small status badge / tag inside a card. */
    val badge = 6.dp
    val chip = 8.dp
    val input = 10.dp
    val tile = 12.dp
    val card = 14.dp
    val panel = 18.dp
    val stage = 22.dp
    val sheet = 24.dp
    val pill = 999.dp
}

/**
 * Elevation. The two levels stay `Dp` (layout is theme independent); the shadow
 * colours moved into [FmColors] so a dark surface gets a deeper, black-based
 * shadow instead of an invisible `#101828` tint.
 *
 * New call sites should prefer [fmElevated] over `Modifier.shadow(...)` +
 * `FmElevation.*Ambient`: the modifier also draws the 1 dp `outlineStrong`
 * hairline that replaces the shadow in the dark appearance (hand-off part 2 §3
 * "阴影 → 1px line-2 描边"). Existing `Modifier.shadow` call sites keep working
 * and already get the deeper dark shadow.
 */
object FmElevation {
    /** Floating nav capsule (D-012: soft 4dp shadow, no blur). */
    val nav = 4.dp
    val card = 1.dp
    val navAmbient: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.shadowNavAmbient
    val navSpot: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.shadowNavSpot
    val cardAmbient: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.shadowCardAmbient
    val cardSpot: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.shadowCardSpot
}

/**
 * Theme-aware elevation for cards and the nav capsule.
 *
 * Light: the classic soft shadow (`ambientColor`/`spotColor` from [FmColors]).
 * Dark: the same shadow, deeper, **plus** a 1 dp [FmPalette.outlineStrong]
 * hairline — a shadow alone does not separate `#1D1E22` from `#121316`.
 *
 * @param level [FmElevation.card] (default) or [FmElevation.nav]; anything below
 *   [FmElevation.nav] uses the card shadow colours and anything at or above it
 *   uses the nav shadow colours.
 * @param shape the surface shape; the hairline follows it, so pass the same
 *   shape you clip with.
 */
@Composable
fun Modifier.fmElevated(
    level: Dp = FmElevation.card,
    shape: Shape = RectangleShape,
): Modifier {
    val dark = LocalFmDark.current
    val capsule = level >= FmElevation.nav
    val ambient = if (capsule) FmElevation.navAmbient else FmElevation.cardAmbient
    val spot = if (capsule) FmElevation.navSpot else FmElevation.cardSpot
    val shadowed = shadow(elevation = level, shape = shape, clip = false, ambientColor = ambient, spotColor = spot)
    return if (dark) shadowed.border(1.dp, FmPalette.outlineStrong, shape) else shadowed
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
    /** V2 single-line top bar (UI report §6): project · tabs · undo/redo · import · export. */
    val topBarHeight = 52.dp
    /** V2: the three columns are separated by hairlines, so the panes sit flush. */
    val edge = 0.dp
    val gap = 0.dp
    val panelRadius = 18.dp

    /** Default rail widths from V2 (UI report §6): border library 296 px, parameters 336 px. */
    val libraryWidth = 296.dp
    val libraryMin = 180.dp
    val libraryMax = 340.dp

    val inspectorWidth = 336.dp
    val inspectorMin = 240.dp
    val inspectorMax = 420.dp

    /** The stage never goes below this; below ~620 dp the right rail folds away. */
    val stageMin = 220.dp

    /** Drag strip between a rail and the stage, and the collapsed-rail affordance. */
    val handleWidth = 10.dp

    /** V2: the left rail folds down to a 52 px strip (UI report §6). */
    val collapsedWidth = 52.dp

    /** 胶片条高 96（`styles.css` 418 行 `.strip`，PARITY-SPEC §2D）。 */
    val filmstripHeight = 96.dp

    /** 胶片条帧缩略图高 56、圆角 6；「+ 添加」是 56×56（`.frame .fimg` / `.frame.add`）。 */
    val filmstripFrameHeight = 56.dp
    val filmstripAddSize = 56.dp

    // ---- 顶栏（`styles.css` 98-108 `.appbar`；PARITY-SPEC §2A）------------------

    /** `padding: 0 14px 0 16px`。 */
    val topBarPadStart = 16.dp
    val topBarPadEnd = 14.dp

    /** 中间 segmented 宽 220（PARITY-SPEC §2A 第 4 条）。 */
    val topBarSegWidth = 220.dp

    /**
     * AU4-02 紧凑态：`编辑 / 批量导出` 两段各 78 dp（4 个字 + 2 dp 槽边距），
     * 220 → 160 让出的 60 dp 留给「导出」的完整标签。演示稿只在宽窗出现，
     * 宽窗仍用 [topBarSegWidth]。
     */
    val topBarSegWidthCompact = 160.dp

    /** 词标 mark 26×26、圆角 7；竖线 1×20（`.mark` / `.vsep`）。 */
    val topBarMarkSize = 26.dp
    val topBarMarkRadius = 7.dp
    val topBarSeparatorHeight = 20.dp

    // ---- 舞台（`styles.css` 350-380；PARITY-SPEC §2C）--------------------------

    /** 照片区留白 `--pad: 56px 64px 84px`（`.stage-area`）。 */
    val stagePadTop = 56.dp
    val stagePadSide = 64.dp
    val stagePadBottom = 84.dp

    /** meta `left:16 top:14`；status 胶囊 `right:14 top:12`、高 26。 */
    val stageMetaStart = 16.dp
    val stageMetaTop = 14.dp
    val stageStatusEnd = 14.dp
    val stageStatusTop = 12.dp
    val stageStatusHeight = 26.dp

    /** 底部悬浮工具条：`bottom:18`、圆角 12、内边距 4、按钮 h30 / min-w30。 */
    val stageToolbarBottom = 18.dp
    val stageToolbarRadius = 12.dp
    val stageToolbarInset = 4.dp
    val stageToolbarHeight = 30.dp
    val stageToolbarMinWidth = 30.dp

    /** 缩放值读数宽 46（`.stage-tb .zl { min-width: 46px }`）。 */
    val stageZoomLabelWidth = 46.dp

    /** 工具条里的分隔线 1×18、灰阶圆点 16（`.stage-tb .sep` / `.tones button`）。 */
    val stageToolbarSeparatorHeight = 18.dp
    val stageToneDotSize = 16.dp
}

/**
 * 舞台悬浮层配色（`styles.css` 358-377；PARITY-SPEC §2C）。
 *
 * 演示稿把这三个叠层写成 `rgba(20,21,24,.55)`（status 胶囊）、
 * `rgba(24,25,28,.72)`（底部工具条）和几档 `rgba(255,255,255,.07/.10/.14/.16)`
 * （描边、hover、按下、分隔线）。它们都叠在 `--stage-dark #2B2C2F` 上，而舞台底色
 * 在浅色与深色外观下都是同一个值 —— 所以这里用 [FmColors.stageDark] /
 * [FmColors.stageInk] 作底、按比例压暗后给 alpha，既不引入新的品牌色，也不会
 * 因为切换外观而漂移（深色模式下工具条不该比浅色模式更暗）。
 */
object FmStageChrome {
    /** status 胶囊底：`#141518` 的 55 %。 */
    val pill: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.dimmedStage(0.47f).copy(alpha = 0.55f)

    /** 底部工具条底：`#18191C` 的 72 %（`.stage-tb`）。 */
    val bar: Color
        @Composable @ReadOnlyComposable get() = LocalFmColors.current.dimmedStage(0.56f).copy(alpha = 0.72f)

    /** 工具条 `box-shadow: 0 0 0 1px rgba(255,255,255,.07)`。 */
    val barOutline: Color
        @Composable @ReadOnlyComposable get() = FmPalette.stageInk.copy(alpha = 0.07f)

    /** `.tb:hover` → `rgba(255,255,255,.10)`。 */
    val barHover: Color
        @Composable @ReadOnlyComposable get() = FmPalette.stageInk.copy(alpha = 0.10f)

    /** `.tb.on, .tb:active` → `rgba(255,255,255,.16)`。 */
    val barPressed: Color
        @Composable @ReadOnlyComposable get() = FmPalette.stageInk.copy(alpha = 0.16f)

    /** `.stage-tb .sep` → `rgba(255,255,255,.14)`。 */
    val barSeparator: Color
        @Composable @ReadOnlyComposable get() = FmPalette.stageInk.copy(alpha = 0.14f)

    /** 灰阶圆点的内描边 `inset 0 0 0 1px rgba(255,255,255,.25)`。 */
    val toneOutline: Color
        @Composable @ReadOnlyComposable get() = FmPalette.stageInk.copy(alpha = 0.25f)

    /** 选中圆点的白环 `0 0 0 3.5px #fff`。 */
    val toneSelected: Color
        @Composable @ReadOnlyComposable get() = FmPalette.onPrimary

    /**
     * 「原图 · 松开恢复」提示：`background: rgba(255,255,255,.92); color: var(--ink)`
     * —— 白底深字。文字色取 [FmColors.stageDark] 而不是 `onSurface`：这块白片不管
     * 外观如何都画在舞台的深色底上，深色模式下 `onSurface` 是浅色，会在白片上消失。
     */
    val flagSurface: Color
        @Composable @ReadOnlyComposable get() = FmPalette.onPrimary.copy(alpha = 0.92f)

    val flagInk: Color
        @Composable @ReadOnlyComposable get() = FmPalette.stage
}

/** 舞台底色按比例压暗：`stageDark × factor`，alpha 交给调用点（叠在舞台上）。 */
private fun FmColors.dimmedStage(factor: Float): Color =
    Color(stageDark.red * factor, stageDark.green * factor, stageDark.blue * factor)

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

// Motion tokens used to live here (the U1a nav numbers). They moved to `Motion.kt`,
// which is now the single home for animation tokens; `FmMotion` is still the same
// `com.framemark.native.theme` symbol, so no call site or import changed.
