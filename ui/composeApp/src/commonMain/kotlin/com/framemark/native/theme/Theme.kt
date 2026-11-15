package com.framemark.native.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

private val FmTypography = Typography(
    titleLarge = FmType.titleLarge,
    titleMedium = FmType.titleMedium,
    titleSmall = FmType.titleSmall,
    bodyLarge = FmType.bodyLarge,
    bodyMedium = FmType.bodyMedium,
    labelMedium = FmType.labelMedium,
    labelSmall = FmType.labelSmall,
)

/**
 * Material 3 colour scheme generated from one [FmColors] snapshot.
 *
 * No screen reads `MaterialTheme.colorScheme` today (they all go through
 * [FmPalette]); this exists so stock M3 components that the shells embed (ripples,
 * `Surface`, text fields) inherit the same appearance instead of falling back to
 * the M3 baseline purple.
 */
private fun fmColorScheme(colors: FmColors, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = colors.primary,
        onPrimary = colors.onPrimary,
        primaryContainer = colors.primarySoft,
        onPrimaryContainer = colors.primaryStrong,
        background = colors.canvas,
        onBackground = colors.onSurface,
        surface = colors.surface,
        onSurface = colors.onSurface,
        surfaceVariant = colors.surfaceMuted,
        onSurfaceVariant = colors.onSurfaceSecondary,
        surfaceContainer = colors.surfaceMuted,
        surfaceContainerHigh = colors.surfaceMuted,
        outline = colors.outline,
        outlineVariant = colors.outlineStrong,
        error = colors.error,
        onError = colors.onPrimary,
        scrim = colors.scrim,
    )
}

/**
 * Root theme.
 *
 * Provides [LocalFmColors] / [LocalFmDark] (which every [FmPalette] read resolves
 * against) and a matching Material 3 scheme plus the FrameMark typography, and
 * [LocalFmMotionScale] so interaction animation can be dialled or switched off.
 *
 * @param dark the resolved appearance. The default `false` keeps every older
 *   no-argument call site compiling and rendering exactly the default palette's
 *   light appearance; `App.kt` passes the value resolved from [AppearanceController]
 *   / [resolveDark].
 * @param motionScale how much interaction motion to run: `1f` (default) is the full
 *   motion spec, `0f` is the reduced-motion end state (every animation lands
 *   immediately). It exists so a host that can read the OS "reduce motion" setting has
 *   one place to put it; nothing in `theme/` invents a second switch.
 * @param palette which of the four interface palettes to render (配色方案 §6). It is
 *   independent of [dark]: every palette ships a light and a dark set. Defaults to
 *   [PaletteId.Default] (暖灰墨绿) so previews and older call sites keep a valid
 *   appearance without threading the value through.
 */
@Composable
fun FrameMarkTheme(
    dark: Boolean = false,
    motionScale: Float = FmMotionRules.fullScale,
    palette: PaletteId = PaletteId.Default,
    content: @Composable () -> Unit,
) {
    val colors = remember(palette, dark) { FmPalettes.of(palette, dark) }
    val scheme = remember(colors, dark) { fmColorScheme(colors, dark) }
    CompositionLocalProvider(
        LocalFmColors provides colors,
        LocalFmDark provides dark,
        LocalFmMotionScale provides motionScale,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = FmTypography,
            content = content,
        )
    }
}
