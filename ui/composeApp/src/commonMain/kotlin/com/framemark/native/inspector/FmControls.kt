package com.framemark.native.inspector

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmElevation
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmRhythm
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.theme.fmElevated
import com.framemark.native.workflow.ControlProbe
import com.framemark.native.workflow.probeBounds
import com.framemark.native.workflow.DesignSession
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * WP-C §4.2 shared controls: slider maths, the desktop/touch slider rows, the
 * segmented control, the colour swatch and the text row.
 *
 * Spec sources: report §9 "FmSlider", §5 ("触控目标 ≥ 48dp; 桌面行高 32–40px"),
 * UX-RESEARCH A-11 (colour roles) and U-01 (touch height decision).
 */

/**
 * Phone touch height for one slider row.
 *
 * UX-RESEARCH U-01: the report says 40 dp, the Android guidance says 48 dp; the
 * user has not decided yet. This is the **single** constant to change — the
 * rows and the hit padding below are all derived from it.
 */
internal const val FM_TOUCH_SLIDER_HEIGHT_DP = 40
internal val FmTouchSliderHeight: Dp = FM_TOUCH_SLIDER_HEIGHT_DP.dp

/** Touch thumb / track per report §9; desktop thumb / track per the same table. */
internal val FmTouchThumbSize = 24.dp
internal val FmTouchTrackThickness = 6.dp
internal val FmDesktopThumbSize = 16.dp
internal val FmDesktopTrackThickness = 4.dp

/** Minimum hit row of the −/+ buttons and of a desktop row. */
internal val FmTouchButtonSize = 32.dp

/**
 * Slider maths, extracted so it can be unit-tested without Compose.
 *
 * Contract (report §9):
 *  - quantise from `min` by `step`;
 *  - the declared default stays exact even when it is off the grid;
 *  - snap to the default within ±1.5 % of the range;
 *  - ←/→ = one step, Shift = ten, PageUp/PageDown = 10 %, Home/End = bounds,
 *    Delete = default;
 *  - typed values outside the range are clamped back into it.
 */
internal object FmSliderMath {

    /** ±1.5 % of the range snaps back to the declared default. */
    const val SNAP_BAND = 0.015f

    /** 0.1 % of the range: the −/+ fine step (watermark margins). */
    const val FINE_STEP_BAND = 0.001f

    /** 10 % of the range: PageUp / PageDown. */
    const val PAGE_STEP_BAND = 0.1f

    fun span(min: Float, max: Float): Float = (max - min).coerceAtLeast(0f)

    fun coerce(value: Float, min: Float, max: Float): Float =
        if (max < min) min else value.coerceIn(min, max)

    /** Grid step from `min`; a default value off the grid stays exact. */
    fun quantize(value: Float, min: Float, max: Float, step: Float?, default: Float? = null): Float {
        val clamped = coerce(value, min, max)
        if (default != null && abs(clamped - default) <= 1e-6f) return default
        if (step == null || step <= 0f) return clamped
        val steps = ((clamped - min) / step).roundToInt()
        return coerce(min + steps * step, min, max)
    }

    /** Snap to the declared default when within [SNAP_BAND] of the span. */
    fun snapToDefault(value: Float, default: Float?, min: Float, max: Float): Float {
        if (default == null) return value
        val band = span(min, max) * SNAP_BAND
        return if (abs(value - default) <= band) default else value
    }

    /** Drag pipeline: clamp → quantise → snap. */
    fun dragValue(raw: Float, min: Float, max: Float, step: Float?, default: Float?): Float =
        snapToDefault(quantize(raw, min, max, step, default), default, min, max)

    /** One arrow-key step (Shift = ×10). */
    fun stepValue(
        value: Float,
        direction: Int,
        min: Float,
        max: Float,
        step: Float?,
        default: Float?,
        shift: Boolean = false,
    ): Float {
        val unit = step?.takeIf { it > 0f } ?: (span(min, max) / 100f)
        val delta = unit * (if (shift) 10f else 1f) * direction
        return quantize(value + delta, min, max, step, default)
    }

    fun pageStep(min: Float, max: Float): Float = span(min, max) * PAGE_STEP_BAND

    fun fineStep(min: Float, max: Float): Float = span(min, max) * FINE_STEP_BAND

    /** The −/+ buttons around the number box: 0.1 % of the range per tap. */
    fun nudge(value: Float, direction: Int, min: Float, max: Float, default: Float?): Float {
        val step = fineStep(min, max).takeIf { it > 0f } ?: span(min, max) / 1000f
        // No default snapping here: 0.1 % taps next to the default must still move,
        // otherwise the fine precision the buttons exist for is unusable.
        return coerce(value + step * direction, min, max)
    }

    /**
     * Typed input in **display units**. Non-numeric text keeps [current];
     * out-of-range numbers are clamped back into the range (brief §4.2).
     */
    fun parseTyped(text: String, current: Float, min: Float, max: Float, scale: Float = 1f): Float {
        val number = text.trim().replace(',', '.').toFloatOrNull() ?: return current
        // `scale` is the display multiplier (alpha ×100), so the engine value is
        // the typed number divided by it.
        val stored = number / (if (scale > 0f) scale else 1f)
        return coerce(stored, min, max)
    }

    /** Whether the value differs from the declared default (page "已改" counts). */
    fun isModified(value: Float, default: Float?): Boolean =
        default != null && abs(value - default) > 1e-4f
}

/** Arrow / page keys the slider handles, kept platform-agnostic for tests. */
internal enum class FmSliderKey { Left, Right, Up, Down, PageUp, PageDown, Home, End, Delete }

/**
 * Target value for one key press, or null when the key is not a slider key.
 * `Delete` restores the default exactly (report §9).
 */
internal fun FmSliderMath.applyKey(
    key: FmSliderKey,
    value: Float,
    min: Float,
    max: Float,
    step: Float?,
    default: Float?,
    shift: Boolean = false,
): Float? = when (key) {
    FmSliderKey.Left, FmSliderKey.Down -> stepValue(value, -1, min, max, step, default, shift)
    FmSliderKey.Right, FmSliderKey.Up -> stepValue(value, +1, min, max, step, default, shift)
    FmSliderKey.PageDown -> quantize(value - pageStep(min, max), min, max, step, default)
    FmSliderKey.PageUp -> quantize(value + pageStep(min, max), min, max, step, default)
    FmSliderKey.Home -> min
    FmSliderKey.End -> max
    FmSliderKey.Delete -> default
}

/**
 * Desktop slider row: label · (default tick via the slider) · value box · reset
 * (only when modified) · track. The value box accepts a number and clamps.
 */
@Composable
internal fun FmDesktopSliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float?,
    default: Float?,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    unit: String? = null,
    scale: Float = 1f,
    enabled: Boolean = true,
    disabledReason: String? = null,
    onReset: (() -> Unit)? = null,
    /** D1-3: fixed decimals for the value box (watermark margins use 1). */
    decimalPlaces: Int = 3,
    /** `.fs .lab .hint` — 11.5 `ink-4`, e.g. 「占照片宽度」. */
    hint: String? = null,
) {
    val modified = FmSliderMath.isModified(value, default)
    Column(modifier = modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (modified) {
                Box(Modifier.size(4.dp).clip(CircleShape).background(FmPalette.primary))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = CuratedSliderLabel,
                color = if (enabled) FmPalette.onSurfaceSecondary else FmPalette.onSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!hint.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = hint,
                    style = CuratedSliderHint,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            if (modified && onReset != null) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button, onClick = onReset)
                        .semantics { contentDescription = "$label 复位" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "↺",
                        style = CuratedSliderHint.copy(fontSize = 13.sp),
                        color = FmPalette.onSurfaceMuted,
                    )
                }
            }
            FmSliderValueBox(
                value = value,
                min = min,
                max = max,
                unit = unit,
                label = label,
                scale = scale,
                decimalPlaces = decimalPlaces,
                onCommit = onValueChange,
            )
        }
        FmSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = min..max,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { stateDescription = if (modified) "已修改" else "默认值" },
            enabled = enabled,
            modified = modified,
            defaultValue = default,
            step = step,
            onReset = onReset,
            valueText = { FmUnits.displayText(it, unit, scale) },
        )
        if (!enabled) {
            Text(
                text = disabledReason ?: "暂不可调",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Touch slider row: 40 dp (see [FM_TOUCH_SLIDER_HEIGHT_DP]) hit row, larger
 * thumb, and −/+ buttons around the number box for 0.1 % precision.
 */
@Composable
internal fun FmTouchSliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float?,
    default: Float?,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    unit: String? = null,
    scale: Float = 1f,
    enabled: Boolean = true,
    disabledReason: String? = null,
    onReset: (() -> Unit)? = null,
    /** D1-3: fixed decimals in the drag bubble (watermark margins use 1). */
    decimalPlaces: Int = 3,
) {
    val modified = FmSliderMath.isModified(value, default)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = FmType.bodyMedium,
                color = if (enabled) FmPalette.onSurface else FmPalette.onSurfaceFaint,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (modified) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(FmPalette.primary))
                Spacer(Modifier.width(6.dp))
            }
            if (modified && onReset != null) {
                Text(
                    text = "复位",
                    style = FmType.labelSmall,
                    color = FmPalette.primaryStrong,
                    modifier = Modifier
                        .heightIn(min = 28.dp)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .clickable(role = Role.Button, onClick = onReset)
                        .padding(horizontal = 6.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FmNudgeButton(
                text = "−",
                enabled = enabled && value > min,
                description = "$label 减少 ${FmUnits.formatNumber(FmSliderMath.fineStep(min, max) * scale)}${unit ?: ""}",
                onClick = { onValueChange(FmSliderMath.nudge(value, -1, min, max, default)) },
            )
            Box(modifier = Modifier.weight(1f).padding(horizontal = FmSpacing.xs)) {
                FmSlider(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = min..max,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    modified = modified,
                    defaultValue = default,
                    step = step,
                    onReset = onReset,
                    touch = true,
                    valueText = { FmUnits.formatNumber(it * scale, decimalPlaces) + (unit ?: "") },
                )
            }
            FmNudgeButton(
                text = "+",
                enabled = enabled && value < max,
                description = "$label 增加 ${FmUnits.formatNumber(FmSliderMath.fineStep(min, max) * scale)}${unit ?: ""}",
                onClick = { onValueChange(FmSliderMath.nudge(value, +1, min, max, default)) },
            )
        }
        if (!enabled) {
            Text(
                text = disabledReason ?: "暂不可调",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun FmNudgeButton(
    text: String,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .heightIn(min = FmTouchButtonSize)
            .width(40.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(FmPalette.surfaceMuted)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.chip))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = FmType.titleSmall,
            color = if (enabled) FmPalette.onSurfaceSecondary else FmPalette.onSurfaceFaint,
        )
    }
}

/** Editable number box shared by both slider rows: clamps typed input. */
@Composable
internal fun FmNumberBox(
    value: Float,
    min: Float,
    max: Float,
    scale: Float,
    unit: String?,
    label: String,
    enabled: Boolean,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** D1-3: fixed decimals (1/2); 3 keeps the historical rule. */
    decimalPlaces: Int = 3,
) {
    val display = FmUnits.formatNumber(value * scale, decimalPlaces)
    var text by remember(value) { mutableStateOf(display) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        val next = FmSliderMath.parseTyped(text, value, min, max, scale)
        onCommit(next)
        text = FmUnits.formatNumber(next * scale, decimalPlaces)
    }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        enabled = enabled,
        singleLine = true,
        textStyle = FmType.labelMedium.copy(color = FmPalette.onSurface),
        cursorBrush = SolidColor(FmPalette.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier
            .heightIn(min = 30.dp)
            .clip(RoundedCornerShape(FmRadii.input))
            .background(FmPalette.surfaceMuted)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input))
            .onFocusChanged { state ->
                if (focused && !state.isFocused) commit()
                focused = state.isFocused
            }
            .semantics { contentDescription = "$label 数值" }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    if (text.isEmpty()) {
                        Text(
                            text = FmUnits.formatNumber(value * scale, decimalPlaces),
                            style = FmType.labelMedium,
                            color = FmPalette.onSurfaceFaint,
                        )
                    }
                    inner()
                }
                if (!unit.isNullOrEmpty()) {
                    Text(unit, style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
                }
            }
        },
    )
}

/**
 * Segmented control (§4.2, report §9 "其他组件"): one pill row, the selected
 * block uses `primarySoft` + `primaryStrong`. Content-sized, no animation
 * dependency so it works inside lazy rows.
 */
@Composable
internal fun FmSegmented(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    labels: Map<String, String> = emptyMap(),
    enabled: Boolean = true,
    touch: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(FmPalette.surfaceMuted)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = if (touch) 44.dp else 30.dp)
                    .clip(RoundedCornerShape(FmRadii.chip))
                    .background(if (active) FmPalette.primarySoft else Color.Transparent)
                    .clickable(enabled = enabled, role = Role.Tab) { onSelect(option) }
                    .semantics {
                        contentDescription = labels[option] ?: option
                        stateDescription = if (active) "已选" else "未选"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = labels[option] ?: option,
                    style = FmType.labelMedium,
                    color = if (active) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Colour swatch (底色色块): a rounded tile with a selected ring. */
@Composable
internal fun FmColorSwatch(
    color: Color,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(color)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) FmPalette.primary else FmPalette.outlineStrong,
                shape = RoundedCornerShape(FmRadii.chip),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                stateDescription = if (selected) "已选" else "未选"
            },
    )
}

/**
 * Text row (report §6 文字页): a small position diagram + editable text +
 * "已改 / 恢复原文" + visibility eye. The caller owns ordering.
 */
@Composable
internal fun FmTextRow(
    label: String,
    value: String,
    placeholder: String,
    modified: Boolean,
    visible: Boolean,
    onValueChange: (String) -> Unit,
    onReset: () -> Unit,
    onToggleVisible: () -> Unit,
    modifier: Modifier = Modifier,
    touch: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = FmSpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier.weight(1f),
            )
            if (modified) {
                Text(
                    text = "已改",
                    style = FmType.labelSmall,
                    color = FmPalette.primaryStrong,
                )
                Spacer(Modifier.width(FmSpacing.xs))
                Text(
                    text = "恢复原文",
                    style = FmType.labelSmall,
                    color = FmPalette.primaryStrong,
                    modifier = Modifier
                        .heightIn(min = 28.dp)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .clickable(role = Role.Button, onClick = onReset)
                        .padding(horizontal = 6.dp),
                )
            }
            val eye = if (visible) "隐藏" else "显示"
            Text(
                text = eye,
                style = FmType.labelSmall,
                color = if (visible) FmPalette.onSurfaceSecondary else FmPalette.onSurfaceFaint,
                modifier = Modifier
                    .heightIn(min = 28.dp)
                    .clip(RoundedCornerShape(FmRadii.chip))
                    .clickable(role = Role.Switch, onClick = onToggleVisible)
                    .semantics { contentDescription = "$label $eye" }
                    .padding(horizontal = 6.dp),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = visible,
            textStyle = FmType.bodyMedium.copy(color = FmPalette.onSurface),
            cursorBrush = SolidColor(FmPalette.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (touch) 44.dp else 34.dp)
                .clip(RoundedCornerShape(FmRadii.input))
                .background(FmPalette.surfaceMuted)
                .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input))
                .semantics { contentDescription = label }
                .padding(horizontal = FmSpacing.sm, vertical = 8.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(text = placeholder, style = FmType.bodyMedium, color = FmPalette.onSurfaceFaint)
                    }
                    inner()
                }
            },
        )
    }
}

/** Stable colour for a swatch id the data describes instead of a hex string. */
internal fun swatchColor(themeId: String): Color = when {
    themeId.contains("black", ignoreCase = true) -> Color(0xFF08090A)
    themeId.contains("ink", ignoreCase = true) -> Color(0xFF111214)
    themeId.contains("cream", ignoreCase = true) -> Color(0xFFEEE6D7)
    themeId.contains("stone", ignoreCase = true) -> Color(0xFFD9D4C9)
    themeId.contains("paper", ignoreCase = true) -> Color(0xFFF6F3EC)
    themeId.contains("white", ignoreCase = true) -> Color(0xFFFFFFFF)
    themeId.contains("glass", ignoreCase = true) -> Color(0x33FFFFFF)
    else -> Color(0xFFE3E7EC)
}

/**
 * Foreground (ink) colour of a swatch id — the 12 × 2 bar drawn inside the disc
 * (showcase `theme(k).fg`). Data colours, like [swatchColor]: the swatch *is* a
 * preview of the border's own plate, so it cannot come from the app palette.
 */
internal fun swatchForeground(themeId: String): Color = when {
    themeId.contains("black", ignoreCase = true) || themeId.contains("ink", ignoreCase = true) -> Color(0xFFF5F2EA)
    themeId.contains("paper", ignoreCase = true) -> Color(0xFF151515)
    themeId.contains("cream", ignoreCase = true) -> Color(0xFF1D1811)
    themeId.contains("stone", ignoreCase = true) -> Color(0xFF191714)
    else -> Color(0xFF0C0C0D)
}

// ---------------------------------------------------------------------------
// WP-V3 spec controls (PARITY-SPEC §3, 10-spec.png "FmSlider 结构与状态")
// ---------------------------------------------------------------------------

/**
 * `.fs` — one FmSlider row: label (+ changed dot) · value box (+ ↺ when changed)
 * · 4 dp track with the default notch. The value box is editable and clamps.
 */
@Composable
internal fun CuratedSliderRow(
    control: InspectorControl,
    session: DesignSession,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    unit: String? = control.unit,
    decimalPlaces: Int = 3,
    /**
     * AU2 A-03/A-04/A-07：该参数在当前状态下是否真的生效。false 时滑杆置灰不可拖，
     * 并在下面写一句原因 —— 不让用户「拖了没反应」。
     */
    enabled: Boolean = true,
    disabledReason: String? = null,
    /**
     * 生效下限（引擎侧的等价区间下限）。例如底部留白会被引擎按
     * `max(pw·pct/100, s·0.085)` 抬到地板，低于地板的拖动在成片上没有任何变化，
     * 所以滑杆下限直接抬到地板，不再留一段死区。
     */
    effectiveMin: Float? = null,
) {
    val declaredMin = control.min ?: 0f
    val max = control.max ?: 1f
    val min = effectiveMin?.takeIf { it > declaredMin }?.coerceAtMost(max) ?: declaredMin
    val rawValue = (session.value(control) as? Number)?.toFloat()
        ?: control.min ?: 0f
    val value = rawValue.coerceIn(min, max)
    val default = control.declaredDefault?.toFloatOrNull()
    val scale = if (control.unitScale > 0f) control.unitScale else 1f
    val modified = FmSliderMath.isModified(rawValue, default)
    // WP-X3 §6: the curated slider is the desktop's control row, so it publishes the
    // same `FM_CONTROL_BOUNDS` / `FM_CONTROL_STATE` lines the old ControlItem did.
    curatedProbeState(control, value)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .probeBounds("control:${control.key}")
            .padding(top = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (modified) {
                Box(Modifier.size(4.dp).clip(CircleShape).background(FmPalette.primary))
                Spacer(Modifier.width(FmRhythm.inlineLoose))
            }
            // AU4-16：单位/说明**贴着标签**（`底部留白 · 占照片宽度`），不要挂到值框
            // 左边；标签自己可省略号收缩，值框永远靠右。
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = CuratedSliderLabel,
                    color = if (enabled) FmPalette.onSurfaceSecondary else FmPalette.onSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!hint.isNullOrBlank()) {
                    Spacer(Modifier.width(FmRhythm.inline))
                    Text(
                        text = "· $hint",
                        style = CuratedSliderHint,
                        color = FmPalette.onSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (modified && enabled) {
                Spacer(Modifier.width(FmRhythm.inlineLoose))
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button) { session.resetControl(control) }
                        .semantics { contentDescription = "$label 复位" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "↺",
                        style = CuratedSliderHint.copy(fontSize = 13.sp),
                        color = FmPalette.onSurfaceMuted,
                    )
                }
            }
            Spacer(Modifier.width(FmRhythm.inlineLoose))
            FmSliderValueBox(
                value = value,
                min = min,
                max = max,
                unit = unit,
                label = label,
                decimalPlaces = decimalPlaces,
                scale = scale,
                onCommit = { if (enabled) session.set(control, it) },
            )
        }
        FmSlider(
            value = value,
            onValueChange = { session.set(control, it) },
            valueRange = min..max,
            step = control.step,
            defaultValue = default,
            onReset = if (default != null) ({ session.resetControl(control) }) else null,
            modified = modified,
            enabled = enabled,
            valueText = { FmUnits.formatNumber(it * scale, decimalPlaces) + (unit ?: "") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!enabled && !disabledReason.isNullOrBlank()) {
            Text(
                text = disabledReason,
                style = CuratedSliderHint,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** `.fs .lab` — 12.5 `ink-2`. */
internal val CuratedSliderLabel = TextStyle(
    fontSize = 12.5.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Normal,
)

/** `.fs .lab .hint` — 11.5 `ink-4`. */
internal val CuratedSliderHint = TextStyle(
    fontSize = 11.5.sp,
    lineHeight = 15.sp,
    fontWeight = FontWeight.Normal,
)

/** `.fs .box` — h26, radius 6, `muted-bg`, right-aligned value + unit. */
internal val ValueBoxWidth = 76.dp

/**
 * `.fs .box` — h26, radius 6, `muted-bg`, right-aligned value + unit.
 *
 * WP-V4 B: 数值与单位都走 `--num` 字族（[withNumericDigits]），字号层级不变。
 */
@Composable
internal fun FmSliderValueBox(
    value: Float,
    min: Float,
    max: Float,
    unit: String?,
    label: String,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    decimalPlaces: Int = 3,
    /** Display multiplier (alpha / logo centre ×100); the stored value stays raw. */
    scale: Float = 1f,
) {
    val display = FmUnits.formatNumber(value * scale, decimalPlaces)
    var text by remember(value) { mutableStateOf(display) }
    fun commit() {
        val next = FmSliderMath.parseTyped(text, value, min, max, scale)
        onCommit(next)
        text = FmUnits.formatNumber(next * scale, decimalPlaces)
    }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 11.5.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            color = FmPalette.onSurface,
            textAlign = TextAlign.End,
        ).withNumericDigits(),
        cursorBrush = SolidColor(FmPalette.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier
            .height(26.dp)
            .width(ValueBoxWidth)
            .clip(RoundedCornerShape(6.dp))
            .background(FmPalette.surfaceMuted)
            .border(1.dp, FmPalette.outlineStrong, RoundedCornerShape(6.dp))
            .onFocusChanged { state -> if (!state.isFocused) commit() }
            .semantics { contentDescription = "$label 数值" }
            .padding(horizontal = 8.dp),
        decorationBox = { inner ->
            Row(
                modifier = Modifier.fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                // `.weight(1f, fill = false)`: a single-line `BasicTextField` fills the
                // width it is offered, so on its own it takes the box's whole 60 dp
                // content area and the unit that follows starts *past* the box's right
                // edge — where `.clip(RoundedCornerShape(6.dp))` cut it in half
                // (`5.0×`, `59.4%` on the device). Measuring the value against the space
                // the unit leaves keeps the whole unit inside the same 76 dp box.
                Box(Modifier.weight(1f, fill = false)) {
                    if (text.isEmpty()) {
                        Text(display, style = CuratedSliderHint, color = FmPalette.onSurfaceFaint)
                    }
                    inner()
                }
                if (!unit.isNullOrEmpty()) {
                    Text(
                        text = unit,
                        style = TextStyle(fontSize = 11.sp, lineHeight = 13.sp).withNumericDigits(),
                        color = FmPalette.onSurfaceMuted,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        },
    )
}

/**
 * `.seg` — `--seg` slot, 2 dp padding, radius 8; buttons h28 / 12.5 px; the
 * selected thumb is a white surface with a soft shadow that slides (300 ms
 * spring). Content-sized, so it also works inside a lazy row.
 */
@Composable
internal fun CuratedSegmented(
    options: List<ControlOption>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    touch: Boolean = false,
) {
    var widthPx by remember { mutableStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val cellPx = if (options.isEmpty()) 0f else (widthPx - 4f * density) / options.size
    val index = options.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    val thumbX by androidx.compose.animation.core.animateDpAsState(
        targetValue = ((2f + cellPx * index) / density).dp,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 700f),
        label = "segThumbX",
    )
    val thumbW = ((cellPx) / density).dp
    val surface = FmPalette.surface
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(FmPalette.surfaceMuted)
            .padding(2.dp)
            .onGloballyPositioned { widthPx = it.size.width.toFloat() },
    ) {
        if (thumbW > 0.dp) {
            Box(
                Modifier
                    .padding(top = 2.dp, bottom = 2.dp)
                    .offset(x = thumbX - 2.dp, y = 0.dp)
                    .width(thumbW)
                    .height((if (touch) 40 else 28).dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(surface)
                    .fmElevated(FmElevation.card, RoundedCornerShape(6.dp)),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            options.forEach { option ->
                val active = option.value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((if (touch) 40 else 28).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Tab) { if (!active) onSelect(option.value) }
                        .semantics {
                            contentDescription = option.display
                            stateDescription = if (active) "已选" else "未选"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option.display,
                        style = TextStyle(
                            fontSize = 12.5.sp,
                            lineHeight = 16.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (active) FmPalette.onSurface else FmPalette.onSurfaceSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

