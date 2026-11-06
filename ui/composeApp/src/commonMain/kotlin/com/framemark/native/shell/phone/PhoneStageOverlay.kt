package com.framemark.native.shell.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
 * Horizontal swipe on the photo switches borders (report §7.5: "在照片上左右滑动
 * 可以逐个换边框，顶部提示第几个"). A left swipe is "next", a right swipe "previous";
 * the order itself comes from WP-L's `traversal`, so it matches the sheet.
 *
 * The gesture never takes over a drag that another layer already consumed (the
 * watermark drag, a chip's click) and is disabled while the photo is zoomed.
 */
fun Modifier.phoneFrameSwipe(enabled: Boolean, onStep: (Int) -> Unit): Modifier =
    this.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        var total = 0f
        var aborted = false
        detectHorizontalDragGestures(
            onDragStart = {
                total = 0f
                aborted = false
            },
            onDragCancel = { total = 0f },
            onHorizontalDrag = { change, dragAmount: Float ->
                if (change.isConsumed) aborted = true else total += dragAmount
            },
            onDragEnd = {
                val threshold = SwipeThreshold.toPx()
                if (!aborted && abs(total) > threshold) onStep(if (total < 0f) 1 else -1)
                total = 0f
            },
        )
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
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(shape)
            .background(FmPalette.scrim)
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
            }
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

