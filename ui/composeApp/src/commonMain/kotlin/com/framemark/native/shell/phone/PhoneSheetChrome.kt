package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.framemark.native.theme.FmRhythm
import com.framemark.native.theme.FmType
import kotlin.math.abs
import com.framemark.native.product.FmLog

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
 * **AU4 §A2 / WP-TY1：页头固定 4 个元素，与演示稿一致。**
 *
 * 改前小米底板页头一行塞 5 个：`边框  当前：经典双行  调整样式 ›  预设  ⌄`，
 * 演示稿只有 4 个（`边框  当前：经典双行  调整样式 ›  ⌄`）。多出来的「预设」把
 * `当前：…` 挤成省略号，13 sp 的两个动作贴在一起 —— 这是"很密"那一类问题的根因。
 * 现在「预设」收进**折叠箭头菜单**：点箭头照旧一步展开/收起（grabber 也能点），
 * 长按/再点箭头打开菜单拿「预设」。于是无改动时页头恒为 4 个元素，
 * 有改动时是 5 个（`恢复默认 N` 与演示稿 `.psh .rst` 一样内联出现）。
 *
 * 预设入口仍在（HANDOFF §3.5 要求手机保留），只是从页头降级到箭头菜单，
 * 这也是任务书给的两种做法之一（"收进 ⋯ 或折叠箭头菜单"）。
 *
 * 验收人反馈「保存预设和使用预设我没看到」，所以**主入口**改成工具条尾部的常驻
 * 「预设」动作（[PhoneToolBar]）；这个长按菜单入口保留，但不再是唯一入口。
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
    var menuOpen by remember { mutableStateOf(false) }
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
        // `.psh { gap: 8px }` —— 标题与副题之间是演示稿的 8 dp（= FmRhythm.row），
        // 不要为了"看起来更紧"改成 6：那会离基线 2 dp，而这里正是最挤的一行。
        Spacer(Modifier.width(FmRhythm.row))
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
        // AU4-05 (WP-X3 follow-up): 展开参数 keeps the band's height. Growing its hit
        // box past the 28–30 dp band was measured NOT to work — the band's own bounds
        // gate the pointer, so the extra dp are dead (a tap 6 dp above the box reached
        // the sheet grabber instead, and 6 dp above 预设 / 5 dp above 热门 did nothing).
        // See reports\WP-X3-REPORT.md §4.4: the real fix needs either a taller band
        // (sheet budget → product decision) or a touch delegate on a taller ancestor.
        //
        // WP-TY1：箭头**单击仍是展开/收起**（一步，不退化），但长按会打开一个小菜单，
        // 「预设」就收在那里；没有预设可给时（参数页）长按不起作用，行为与改前一致。
        Box {
            Box(
                modifier = Modifier
                    .size(height.coerceAtMost(SheetHeadChevronSize))
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .semantics {
                        contentDescription = if (expanded) "收起参数" else "展开参数"
                        role = Role.Button
                    }
                    .combinedClickable(
                        onClick = onToggleExpanded,
                        onLongClick = if (onOpenPresets != null) {
                            {
                                menuOpen = true
                                FmLog.info("FM_PHONE_SHEET_HEAD_MENU") { "presets=1" }
                            }
                        } else {
                            null
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                PhoneChevronGlyph(tint = FmPalette.onSurfaceSecondary, up = expanded)
            }
            if (onOpenPresets != null) {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (expanded) "收起参数" else "展开参数", style = FmType.bodyMedium) },
                        onClick = {
                            menuOpen = false
                            onToggleExpanded()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("预设", style = FmType.bodyMedium) },
                        onClick = {
                            menuOpen = false
                            onOpenPresets()
                        },
                    )
                }
            }
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
    // AU4-05 (WP-X3 follow-up): same platform limit as the chevron above — the 28–30 dp
    // head band gates the pointer, so the pill keeps its height and the band keeps its
    // layout. Not widened on purpose: a 44 dp hit box would only lie to a11y.
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
