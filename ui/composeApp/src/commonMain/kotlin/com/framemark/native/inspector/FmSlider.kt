package com.framemark.native.inspector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmType
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The one slider used by both inspectors, the watermark panel and the touch
 * parameter sheet.
 *
 * WP-C §4.2 (report §9):
 *  - desktop: 16 dp thumb, 4 dp track; touch: 24 dp thumb, 6 dp track and the
 *    single [FM_TOUCH_SLIDER_HEIGHT_DP] hit row;
 *  - idle part `FmPalette.sliderIdle`, modified part `FmPalette.primary`
 *    (UX-RESEARCH A-11: `sliderIdle` is for the unmodified state only);
 *  - a default tick is always visible, so "what is default?" is answerable;
 *  - double tap resets, ←/→ move one step (Shift ×10), PageUp/PageDown 10 %,
 *    Home/End bounds, Delete restores the default; ±1.5 % snaps to the default.
 *
 * The first five parameters keep the historical signature so existing callers
 * (`workflow/ProjectPanels.kt`, `shell/PhoneEditor.kt`) compile unchanged.
 */
@Composable
fun FmSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** True colours the filled part with `primary`; false keeps it `sliderIdle`. */
    modified: Boolean = false,
    /** Declared default: drives the tick, the ±1.5 % snap and double-tap reset. */
    defaultValue: Float? = null,
    /** Grid step; null or ≤ 0 keeps the value continuous. */
    step: Float? = null,
    /** Called by double tap / Delete when a default exists. */
    onReset: (() -> Unit)? = null,
    /**
     * 松手（或手势被取消）时回调一次。拖动气泡用它补写最后一笔：拖动期间
     * [onValueChange] 仍然每个样本都回调，要不要合并节奏由调用方决定
     * （`PhoneSliderParamRow` 走 `rememberDragCommit`）。
     */
    onValueChangeFinished: (() -> Unit)? = null,
    /** Touch density (24 dp thumb, 40 dp row) instead of the desktop one. */
    touch: Boolean = false,
    /** Number formatting for the semantics description. */
    valueText: (Float) -> String = { FmUnits.formatNumber(it) },
    /** 参数名：读屏先读它、再读当前值（UI 评审 P0-2）。为空时退化为「滑块」。 */
    label: String? = null,
) {
    val min = valueRange.start
    val max = valueRange.endInclusive
    val span = (max - min).takeIf { it > 0f } ?: 1f
    val fraction = ((value - min) / span).coerceIn(0f, 1f)
    val defaultFraction = defaultValue
        ?.let { ((it - min) / span).coerceIn(0f, 1f) }
    var trackWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    // WP-T: colours are read during composition; the Canvas draw scope is not
    // composable, so every FmPalette read stays outside `Canvas { }`.
    val idleTrack = FmPalette.track
    val fillTrack = if (modified) FmPalette.primary else FmPalette.sliderIdle
    val tickColor = FmPalette.onSurfaceMuted
    val thumbFill = FmPalette.surface
    val thumbEdge = FmPalette.outlineStrong
    // Shadow comes from the elevation token, never a literal black.
    val thumbShadow = com.framemark.native.theme.FmElevation.cardAmbient
    val rowHeight = if (touch) FmTouchSliderHeight else 36.dp
    val thumb = if (touch) FmTouchThumbSize else FmDesktopThumbSize
    val trackThickness = if (touch) FmTouchTrackThickness else FmDesktopTrackThickness

    fun valueAt(x: Float): Float {
        if (trackWidthPx <= 0) return value
        val thumbRadiusPx = with(density) { (thumb / 2).toPx() }
        val usable = (trackWidthPx - thumbRadiusPx * 2).coerceAtLeast(1f)
        val rawFraction = ((x - thumbRadiusPx) / usable).coerceIn(0f, 1f)
        return min + rawFraction * span
    }

    fun commit(raw: Float) {
        onValueChange(FmSliderMath.dragValue(raw, min, max, step, defaultValue))
    }

    val focusRequester = remember { FocusRequester() }
    var focusMark by remember { mutableStateOf<TimeMark?>(null) }
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
            .focusRequester(focusRequester)
            .focusable(enabled = enabled)
            .onPreviewKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                val key = when (event.key) {
                    Key.DirectionLeft -> FmSliderKey.Left
                    Key.DirectionRight -> FmSliderKey.Right
                    Key.DirectionUp -> FmSliderKey.Up
                    Key.DirectionDown -> FmSliderKey.Down
                    Key.PageUp -> FmSliderKey.PageUp
                    Key.PageDown -> FmSliderKey.PageDown
                    Key.MoveHome -> FmSliderKey.Home
                    Key.MoveEnd -> FmSliderKey.End
                    Key.Delete, Key.Backspace -> FmSliderKey.Delete
                    else -> null
                } ?: return@onPreviewKeyEvent false
                val next = FmSliderMath.applyKey(
                    key = key,
                    value = value,
                    min = min,
                    max = max,
                    step = step,
                    default = defaultValue,
                    shift = event.isShiftPressed,
                ) ?: return@onPreviewKeyEvent false
                onValueChange(next)
                true
            }
            .pointerInput(enabled, min, max, step, defaultValue) {
                if (!enabled) return@pointerInput
                var lastTap: TimeMark? = null
                awaitEachGesture {
                    focusRequester.requestFocus()
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragged = false
                    commit(valueAt(down.position.x))
                    var finished = false
                    while (!finished) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            finished = true
                        } else {
                            if (change.positionChanged()) {
                                dragged = true
                                commit(valueAt(change.position.x))
                                change.consume()
                            }
                            if (change.changedToUpIgnoreConsumed()) finished = true
                        }
                    }
                    // 松手 / 手势丢失都算一次拖动结束：拖动气泡在这里补写最后一笔，
                    // 所以「松手才真正落值」和「拖动中被合并的中间样本」都不丢。
                    latestOnValueChangeFinished?.invoke()
                    if (!dragged) {
                        val now = TimeSource.Monotonic.markNow()
                        val previous = lastTap
                        if (previous != null && previous.elapsedNow().inWholeMilliseconds < 300) {
                            lastTap = null
                            if (onReset != null) onReset() else defaultValue?.let(onValueChange)
                        } else {
                            lastTap = now
                        }
                    }
                }
            }
            .onSizeChanged { trackWidthPx = it.width }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.coerceIn(min, max),
                    range = min..max,
                    steps = step?.takeIf { it > 0f }
                        ?.let { ((max - min) / it).toInt().coerceAtLeast(0) }
                        ?: 0,
                )
                contentDescription = if (label.isNullOrBlank()) {
                    "滑块 ${valueText(value)}"
                } else {
                    "$label，${valueText(value)}"
                }
                if (!enabled) disabled()
                if (enabled) {
                    setProgress { target ->
                        onValueChange(target.coerceIn(min, max))
                        true
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val thumbSizePx = (if (touch) FmTouchThumbSize else FmDesktopThumbSize).toPx()
            val thumbRadius = thumbSizePx / 2f
            val usable = (size.width - thumbRadius * 2).coerceAtLeast(1f)
            val centerY = size.height / 2f
            val trackHeight = (if (touch) FmTouchTrackThickness else FmDesktopTrackThickness).toPx()
            val left = thumbRadius
            val radius = CornerRadius(trackHeight / 2f)
            // Idle track, then the filled part (grey = default, blue = edited).
            drawRoundRect(
                color = idleTrack,
                topLeft = Offset(left, centerY - trackHeight / 2f),
                size = Size(usable, trackHeight),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = fillTrack,
                topLeft = Offset(left, centerY - trackHeight / 2f),
                size = Size((usable * fraction).coerceAtLeast(0f), trackHeight),
                cornerRadius = radius,
            )
            // Default tick: always visible, even when the value is at the default.
            // PARITY-SPEC §3: 12 × 2 dp notch.
            defaultFraction?.let { f ->
                val x = left + usable * f
                val tickHeight = (if (touch) 14f else 12f) * density.density
                drawLine(
                    color = tickColor,
                    start = Offset(x, centerY - tickHeight / 2f),
                    end = Offset(x, centerY + tickHeight / 2f),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            // Thumb: white disc with a hairline edge, matching the reference.
            val thumbCenter = Offset(sliderThumbCenterPx(size.width, thumbSizePx, fraction), centerY)
            drawCircle(color = thumbShadow, radius = thumbRadius + 1.dp.toPx(), center = thumbCenter)
            drawCircle(color = thumbFill, radius = thumbRadius, center = thumbCenter)
            drawCircle(
                color = thumbEdge,
                radius = thumbRadius,
                center = thumbCenter,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

/** Kept for one caller that measured the old Material slider height. */
internal val FmSliderRowHeight = 44.dp

/**
 * Horizontal centre of the thumb for [fraction] on a [trackWidthPx] track.
 *
 * Pure on purpose: the drag bubble ([FmSliderDragBubble]) has to land on exactly the
 * thumb the painter drew, and the value must not be re-derived in two places with two
 * roundings. The thumb keeps its radius of padding at both ends, so the usable track is
 * `width - 2r` and the centre starts at `r` — the same geometry the painter uses.
 */
internal fun sliderThumbCenterPx(
    trackWidthPx: Float,
    thumbSizePx: Float,
    fraction: Float,
): Float {
    val radius = thumbSizePx / 2f
    val usable = (trackWidthPx - radius * 2f).coerceAtLeast(1f)
    return radius + usable * fraction.coerceIn(0f, 1f)
}

/**
 * Left edge of a [bubbleWidthPx] pill centred on the thumb, clamped into the track.
 *
 * The bubble is a **direct read** of the current value: no clock, no easing, one
 * calculation per frame (motion spec §3.1 #6). Clamping means a bubble near either end
 * stays inside the row instead of hanging off it.
 */
internal fun sliderBubbleLeftPx(
    trackWidthPx: Float,
    thumbSizePx: Float,
    bubbleWidthPx: Float,
    fraction: Float,
): Float {
    if (trackWidthPx <= 0f) return 0f
    val centered = sliderThumbCenterPx(trackWidthPx, thumbSizePx, fraction) - bubbleWidthPx / 2f
    val maxLeft = (trackWidthPx - bubbleWidthPx).coerceAtLeast(0f)
    return centered.coerceIn(0f, maxLeft)
}

/**
 * The drag bubble that rides above the thumb while a touch slider row is being
 * dragged (motion spec §3.1 #6, implemented in batch 2).
 *
 * The rule that shapes this composable is the drag-safety one, and it is structural:
 *
 *  - **only opacity is animated.** [alpha] is a lambda because an animated float must
 *    never be a composition input: the caller passes `{ anim.value }`, and the value is
 *    read inside `graphicsLayer`, i.e. in the draw phase.
 *  - **the position is a direct read.** The pill is placed from the same [fraction] the
 *    painter used ([sliderBubbleLeftPx] on top of [sliderThumbCenterPx]); there is no
 *    animated offset anywhere on this path, so the bubble can never lag the thumb.
 *  - **the number never animates.** `text` is plain `Text`; a counting-up readout is a
 *    number the user cannot read mid-drag (spec §4.3).
 *
 * The bubble is decoration for the eye only: it is cleared from semantics, because the
 * row's value text and the slider's own `contentDescription` already carry the value.
 */
@Composable
internal fun FmSliderDragBubble(
    text: String,
    fraction: Float,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    val ink = FmPalette.stageInk
    val fill = FmPalette.stage.copy(alpha = 0.92f)
    val shape = RoundedCornerShape(FmRadii.chip)
    Layout(
        content = {
            Box(
                modifier = Modifier
                    .graphicsLayer { this.alpha = alpha().coerceIn(0f, 1f) }
                    .height(FmTouchBubbleHeight)
                    .clip(shape)
                    .background(fill)
                    .padding(horizontal = FmTouchBubblePadX)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Text(text = text, style = FmType.labelSmall, color = ink, maxLines = 1)
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(Constraints(maxWidth = constraints.maxWidth))
        layout(constraints.maxWidth, placeable.height) {
            placeable.place(
                sliderBubbleLeftPx(
                    trackWidthPx = constraints.maxWidth.toFloat(),
                    thumbSizePx = FmTouchThumbSize.toPx(),
                    bubbleWidthPx = placeable.width.toFloat(),
                    fraction = fraction,
                ).roundToInt(),
                0,
            )
        }
    }
}

/** Rounds a value onto the 0.001 grid the display uses. */
internal fun roundForDisplay(value: Float): Float = (value * 1000f).roundToInt() / 1000f

/** Absolute distance helper used by the "default tick" placement. */
internal fun fractionOf(value: Float, min: Float, max: Float): Float {
    val span = max - min
    if (abs(span) < 1e-6f) return 0f
    return ((value - min) / span).coerceIn(0f, 1f)
}
