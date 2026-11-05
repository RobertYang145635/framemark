package com.framemark.native.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Labels in on-screen order. Fixed copy — the design brief spells them out. */
private val APPEARANCE_SEGMENTS: List<Pair<ThemeMode, String>> = listOf(
    ThemeMode.System to "跟随系统",
    ThemeMode.Light to "浅色",
    ThemeMode.Dark to "深色",
)

/**
 * Three-way appearance switch: 跟随系统 / 浅色 / 深色.
 *
 * Reads and writes [LocalAppearance], so dropping it anywhere under
 * `FrameMarkApp` is enough — no parameters to thread through. WP-T deliberately
 * does **not** place an entry point: WP-D1 puts it behind the desktop top-bar
 * gear and WP-P2 behind the phone home gear.
 *
 * This is a local, token-only implementation so WP-T closes on its own. WP-C's
 * shared segmented control replaces the body later; the signature stays.
 *
 * @param modifier applied to the outer row; the caller owns placement and any
 *   label above the switch.
 * @param controller overridable for a preview or a test; defaults to the one
 *   published by `FrameMarkApp`.
 */
@Composable
fun AppearancePicker(
    modifier: Modifier = Modifier,
    controller: AppearanceController = LocalAppearance.current,
) {
    val selected = controller.settings.mode
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.surfaceMuted)
            .padding(FmSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        APPEARANCE_SEGMENTS.forEach { (mode, label) ->
            val active = mode == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .then(if (active) Modifier.background(FmPalette.surface) else Modifier)
                    .selectable(selected = active, role = Role.RadioButton) { controller.setMode(mode) }
                    .defaultMinSize(minWidth = 76.dp)
                    .padding(horizontal = FmSpacing.md, vertical = FmSpacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = FmType.labelMedium,
                    color = if (active) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                )
            }
        }
    }
}
