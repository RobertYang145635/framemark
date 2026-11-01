package com.framemark.native.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FmColorScheme = lightColorScheme(
    primary = FmPalette.primary,
    onPrimary = FmPalette.onPrimary,
    primaryContainer = FmPalette.primarySoft,
    onPrimaryContainer = FmPalette.primaryStrong,
    background = FmPalette.canvas,
    onBackground = FmPalette.onSurface,
    surface = FmPalette.surface,
    onSurface = FmPalette.onSurface,
    surfaceVariant = FmPalette.surfaceMuted,
    onSurfaceVariant = FmPalette.onSurfaceSecondary,
    outline = FmPalette.outline,
    outlineVariant = FmPalette.outlineStrong,
    error = FmPalette.error,
    onError = Color.White,
    scrim = FmPalette.scrim,
)

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
 * Light-only theme for U1. Tokens live in [FmPalette]/[FmType]/[FmSpacing]/
 * [FmRadii]/[FmElevation]/[FmMotion]; MaterialTheme is wired to the same roles so
 * stock M3 components inherit them. Dark mode is out of scope for this slice.
 */
@Composable
fun FrameMarkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FmColorScheme,
        typography = FmTypography,
        content = content,
    )
}
