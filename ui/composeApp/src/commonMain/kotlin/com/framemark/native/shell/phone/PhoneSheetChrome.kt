package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.shell.PhoneCompactSheetMetrics
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import kotlin.math.abs

/** Title-row and chevron ceilings: the 收起 sheet may sit below both (WP-P1-2). */
private val SheetHeadMaxHeight = 48.dp
private val SheetHeadChevronSize = 40.dp

/**
 * Demo `.psheet .grab`: a band with a 36 × 4 dp handle. It owns one pointer
 * detector for both gestures: a tap toggles the two levels, a vertical drag
 * resizes between them. Keeping one detector means the click and the drag can
 * never steal each other's gesture.
 *
 * The band height comes from [PhoneCompactSheetMetrics.grabber] (WP-P1-2): the
 * 收起 sheet gives every dp it can spare to the 128 dp card and its name instead
 * of a fixed 18 dp band.
 */
@Composable
fun PhoneSheetGrabber(
    height: Dp,
    onToggle: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val toggle by rememberUpdatedState(onToggle)
    val dragStart by rememberUpdatedState(onDragStart)
    val dragMove by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragging = false
                    var total = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        // previousPosition is in this frame's node space, so the delta
                        // survives the sheet resizing under the finger.
                        val dy = change.position.y - change.previousPosition.y
                        total += dy
                        if (!dragging && abs(total) > viewConfiguration.touchSlop) {
                            dragging = true
                            dragStart()
                        }
                        if (dragging) {
                            change.consume()
                            dragMove(dy)
                        }
                    }
                    if (dragging) dragEnd() else toggle()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.outlineStrong),
        )
    }
}

/**
 * Sheet head (demo `.psh`: padding `0 12 6 18`, gap 8): 16/700 page name, the
 * 12.5 sp ink-3 subtitle (`当前：经典双行` on the border sheet, the card name on a
 * parameter page), then the accent actions — `恢复默认 N` (`.rst`, 32 dp, 13 sp)
 * and `调整样式 ›` (`.adj`, 32 dp, 13 sp, gap 2) — and the 36 dp expand chevron.
 *
 * 预设 stays as a fourth action (HANDOFF §3.5 keeps the phone's preset entry; the
 * showcase's phone screens have no preset surface, so it reuses the header's ghost
 * action slot and touches nothing else).
 */
@Composable
fun PhoneSheetHead(
    title: String,
    subtitle: String,
    modified: Int,
    expanded: Boolean,
    height: Dp,
    onToggleExpanded: () -> Unit,
    onReset: (() -> Unit)? = null,
    onOpenPresets: (() -> Unit)? = null,
    onAdjustStyle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val actionHeight = height.coerceAtMost(SheetHeadMaxHeight)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(start = 18.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = FmType.titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            color = FmPalette.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.width(FmSpacing.sm))
        Text(
            text = subtitle,
            style = FmType.labelSmall.copy(fontSize = 12.5.sp),
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onReset != null && modified > 0) {
            HeadAction(
                label = "恢复默认 $modified",
                description = "恢复本页默认，$modified 项",
                accent = true,
                height = actionHeight,
                onClick = onReset,
            )
        }
        if (onAdjustStyle != null) {
            HeadAction(
                label = "调整样式",
                description = "打开样式参数",
                accent = true,
                height = actionHeight,
                trailingChevron = true,
                onClick = onAdjustStyle,
            )
        }
        if (onOpenPresets != null) {
            HeadAction(
                label = "预设",
                description = "打开预设",
                height = actionHeight,
                onClick = onOpenPresets,
            )
        }
        Box(
            modifier = Modifier
                .size(height.coerceAtMost(SheetHeadChevronSize))
                .clip(RoundedCornerShape(FmRadii.pill))
                .semantics {
                    contentDescription = if (expanded) "收起参数" else "展开参数"
                    role = Role.Button
                }
                .clickable(onClick = onToggleExpanded),
            contentAlignment = Alignment.Center,
        ) {
            PhoneChevronGlyph(tint = FmPalette.onSurfaceSecondary, up = expanded)
        }
    }
}

@Composable
private fun HeadAction(
    label: String,
    description: String,
    height: Dp,
    onClick: () -> Unit,
    accent: Boolean = false,
    trailingChevron: Boolean = false,
) {
    Row(
        modifier = Modifier
            .heightIn(min = height)
            .clip(RoundedCornerShape(FmRadii.pill))
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = FmType.labelSmall.copy(fontSize = 13.sp),
            color = if (accent) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
            maxLines = 1,
        )
        if (trailingChevron) {
            Spacer(Modifier.width(2.dp))
            PhoneChevronGlyph(tint = FmPalette.primaryStrong, up = false, right = true, dim = 14.dp)
        }
    }
}
