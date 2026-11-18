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

/**
 * Stage gesture: pinch to zoom, one finger to pan. The old left/right
 * swipe-to-change-border gesture was removed (it only walked the current sheet
 * category, did not wrap, and dead-ended after a few cards). Changing a border
 * is now exclusively the sheet / list action.
 */
fun Modifier.phoneStageGestures(
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            if (panChange != Offset.Zero || zoomChange != 1f) {
                onTransform(event.calculateCentroid(useCurrent = false), panChange, zoomChange)
                event.changes.forEach { it.consume() }
            }
        } while (true)
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
