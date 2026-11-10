package com.framemark.native.shell.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import kotlin.math.abs

/** Swipe distance that counts as "switch to the next border" (showcase: 48 px). */
private val SwipeThreshold = 48.dp

/** What the swipe HUD shows: the new card's name plus "第 N / M 个". */
data class PhoneSwipeFeedback(
    val title: String,
    val detail: String?,
    val atEdge: Boolean,
)

/**
 * The whole stage gesture on one pointer-input node: 左右滑动换边框 + 缩放/平移.
 *
 * WP-F2 D4 / WP-X3 §4: the stage used to hang two detectors on the same box —
 * [phoneFrameSwipe] (outer) and `detectTransformGestures` (inner). The inner
 * detector runs first in the Main pass, so it consumed every single-finger drag,
 * `change.isConsumed` became true and the frame swipe aborted 100 % of the time:
 * the pill advertised `· 左右滑动换边框` and nothing ever changed.
 *
 * Two detectors cannot be told apart by ordering (whichever runs first wins both
 * gestures), so the decision is made here instead, from the pointer stream:
 *
 *  - two pointers or a pinch → zoom/pan, always (the transform is never lost);
 *  - one pointer while [swipeEnabled] (border tool, photo not zoomed) →
 *    accumulates horizontally. Past the touch slop a mostly-horizontal drag is
 *    claimed as a frame swipe and consumed; a mostly-vertical drag is left to the
 *    pan branch so the photo can still be moved;
 *  - one pointer while [swipeEnabled] is false (zoomed in, comparing, other
 *    tools) → pan, exactly like before.
 *
 * A tap never crosses the slop, so the double-tap detector on the same box still
 * sees its events (this modifier consumes nothing until the slop is passed).
 */
fun Modifier.phoneStageGestures(
    swipeEnabled: Boolean,
    onStep: (Int) -> Unit,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
): Modifier = this.pointerInput(swipeEnabled) {
    val slop = (SwipeThreshold / 2f).toPx()
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var totalX = 0f
        var totalY = 0f
        var claimed = false     // the swipe owns this gesture
        var multiTouch = false
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            if (pressed > 1 || zoomChange != 1f) multiTouch = true
            if (multiTouch) {
                if (!claimed && (panChange != Offset.Zero || zoomChange != 1f)) {
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (centroid.isSpecified) onTransform(centroid, panChange, zoomChange)
                    event.changes.forEach { it.consume() }
                }
            } else {
                val delta = event.changes.firstOrNull()?.positionChange() ?: Offset.Zero
                if (claimed) {
                    totalX += delta.x
                    event.changes.forEach { it.consume() }
                } else if (swipeEnabled) {
                    totalX += delta.x
                    totalY += delta.y
                    if (abs(totalX) > slop && abs(totalX) >= abs(totalY)) {
                        claimed = true
                        event.changes.forEach { it.consume() }
                    } else if (abs(totalY) > slop) {
                        // A vertical drag is not a frame swipe: pass it to the pan.
                        onTransform(Offset.Unspecified, delta, 1f)
                    }
                } else if (delta != Offset.Zero) {
                    onTransform(Offset.Unspecified, delta, 1f)
                }
            }
        } while (event.changes.any { it.pressed })
        if (claimed && abs(totalX) > SwipeThreshold.toPx()) {
            onStep(if (totalX < 0f) 1 else -1)
        }
    }
}

/**
 * The border-name pill on the dark stage (showcase `.pchip`: h 26, radius 13,
 * padding `0 10`, 12 px, 55 % scrim) with the optional `· 左右滑动换边框` hint
 * (`.pc-hint`, 11.5 px at 55 % white).
 */
@Composable
fun PhoneStageChip(
    text: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    hint: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(FmRadii.pill)
    Row(
        modifier = modifier
            .height(26.dp)
            .clip(shape)
            .background(FmPalette.scrim)
            .then(
                if (onClick != null) {
                    Modifier
                        .semantics {
                            contentDescription = description ?: text
                            role = Role.Button
                        }
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = FmType.labelSmall.copy(fontSize = 12.sp),
            color = FmPalette.stageInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "· $hint",
                style = FmType.labelSmall.copy(fontSize = 11.5.sp),
                color = FmPalette.stageMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * 按住看原图 (report §7.2): press shows the raw photo, release returns to the
 * design. It is a press-and-hold, not a toggle, so it can never get stuck.
 */
@Composable
fun PhoneHoldCompareChip(
    comparing: Boolean,
    onCompareChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(FmRadii.pill)
    // AU4-05: the demo pill is 34 dp, which is below the 44 dp phone touch-target
    // line. The *touch* box grows to 44 dp (transparent: the scrim pill is drawn at
    // 34 inside it), so the visual size on the dark stage is unchanged while the
    // thumb gets a target it can hit. The stage overlay is outside the sheet
    // budget, so nothing else has to move.
    Box(
        modifier = modifier
            .heightIn(min = PhoneTouchTarget)
            .semantics(mergeDescendants = true) {
                contentDescription = "按住查看原图"
                role = Role.Button
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onCompareChange(true)
                        tryAwaitRelease()
                        onCompareChange(false)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(34.dp)
                .clip(shape)
                .background(FmPalette.scrim)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhoneEyeGlyph(tint = FmPalette.stageInk)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "按住看原图",
                    style = FmType.labelSmall.copy(fontSize = 12.5.sp),
                    color = if (comparing) FmPalette.primaryStrong else FmPalette.stageInk,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The failed-preview placeholder ([PhoneStageFallback]): one centred pill over
 * the bundled static sample, so the fallback reads as "示例图" instead of as the
 * user's photo. Styled like the other stage pills — `FmPalette.stage` at 88 %, a
 * 1 dp `stageLine` rim, `FmType.labelSmall` in `stageInk` — and, like the
 * bottom-left border pill, purely decorative: it has no click and no pointer input,
 * so every touch still reaches the stage gestures.
 *
 * The caller draws it inside the photo band ([PhoneStageBands.photoBottom]), which
 * ends 2 dp above the pill band: the chip physically cannot reach the border-name
 * pill or 按住看原图.
 */
@Composable
fun PhoneStageFallbackChip(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(FmRadii.pill)
    Box(
        modifier = modifier
            .clip(shape)
            .background(FmPalette.stage.copy(alpha = PhoneStageFallback.ChipAlpha))
            .border(1.dp, FmPalette.stageLine, shape)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = PhoneStageFallback.ChipText,
            style = FmType.labelSmall,
            color = FmPalette.stageInk,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The whole placeholder layer: a [PhoneStageFallback.DimAlpha] stage wash over the
 * static sample and the centred [PhoneStageFallbackChip].
 *
 * It covers the **photo band only** — the same rectangle as the preview, inset by
 * [PhoneStageBands] — so the two stage pills in the band under it are never dimmed
 * or covered, in either normal or focus mode. Draw-only: no click, no pointer input.
 */
@Composable
fun PhoneStageFallbackOverlay(
    focusMode: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(
            start = PhoneStageBands.PhotoSide,
            end = PhoneStageBands.PhotoSide,
            top = PhoneStageBands.PhotoTop,
            bottom = PhoneStageBands.photoBottom(focusMode),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FmPalette.stage.copy(alpha = PhoneStageFallback.DimAlpha)),
        )
        PhoneStageFallbackChip()
    }
}

/** A-08: after an undo, a short notice that also offers the way back. */
@Composable
fun PhoneUndoNotice(onRedo: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.scrim)
            .padding(start = FmSpacing.md, end = FmSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
    ) {
        Text(
            text = "已撤销",
            style = FmType.labelSmall.copy(fontSize = 12.sp),
            color = FmPalette.stageInk,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .heightIn(min = 34.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .semantics {
                    contentDescription = "重做"
                    role = Role.Button
                }
                .clickable(onClick = onRedo)
                .padding(horizontal = FmSpacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "重做",
                style = FmType.labelSmall.copy(fontSize = 12.sp),
                color = FmPalette.primaryStrong,
                maxLines = 1,
            )
        }
    }
}

/** The "第 N / M 个" HUD shown for a moment after a swipe (showcase `.step-hud`). */
@Composable
fun PhoneSwipeHud(feedback: PhoneSwipeFeedback?, modifier: Modifier = Modifier) {
    val current = feedback ?: return
    Row(
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.scrim)
            .padding(horizontal = FmSpacing.md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FmSpacing.xs),
    ) {
        if (!current.atEdge) {
            SwipeArrow(right = false, tint = FmPalette.stageMuted)
            Spacer(Modifier.width(2.dp))
        }
        Text(
            text = current.title,
            style = FmType.labelSmall.copy(fontSize = 12.sp),
            color = FmPalette.stageInk,
            maxLines = 1,
        )
        current.detail?.let {
            Spacer(Modifier.width(5.dp))
            Text(
                text = it,
                style = FmType.labelSmall.copy(fontSize = 11.sp),
                color = FmPalette.stageMuted,
                maxLines = 1,
            )
        }
        if (!current.atEdge) {
            Spacer(Modifier.width(2.dp))
            SwipeArrow(right = true, tint = FmPalette.stageMuted)
        }
    }
}

@Composable
private fun SwipeArrow(right: Boolean, tint: androidx.compose.ui.graphics.Color) {
    Canvas(modifier = Modifier.size(10.dp)) {
        val w = size.width
        val stroke = 1.4.dp.toPx()
        val tip = if (right) w * 0.82f else w * 0.18f
        val back = if (right) w * 0.3f else w * 0.7f
        drawLine(tint, Offset(tip, w * 0.2f), Offset(tip, w * 0.8f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(back, w * 0.2f), Offset(tip, w * 0.5f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(back, w * 0.8f), Offset(tip, w * 0.5f), stroke, StrokeCap.Round)
    }
}

