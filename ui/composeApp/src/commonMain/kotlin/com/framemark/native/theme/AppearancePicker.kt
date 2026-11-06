package com.framemark.native.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.framemark.native.inspector.FmSegmented

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
 * WP-C §3: the body is now the shared V2 segmented control ([FmSegmented]); the
 * signature and the copy are unchanged, so existing callers keep compiling.
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
    FmSegmented(
        options = APPEARANCE_SEGMENTS.map { it.first.wire },
        selected = selected.wire,
        onSelect = { wire ->
            ThemeMode.entries.firstOrNull { it.wire == wire }?.let(controller::setMode)
        },
        labels = APPEARANCE_SEGMENTS.associate { (mode, label) -> mode.wire to label },
        modifier = modifier,
    )
}
