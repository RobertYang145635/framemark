package com.framemark.native.shell.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.shell.EditorTool
import com.framemark.native.shell.FmNavBackdrop
import com.framemark.native.shell.FmNavCapsule
import com.framemark.native.shell.FmNavGlassCapsule
import com.framemark.native.shell.FmNavSelectedPill
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType

/** The trailing 预设 cell: a narrower operator cell, not a sixth tab. */
private val PresetCellWidth = 58.dp

/** Separator between the tool cells and the 预设 action. */
private val PresetSeparatorWidth = 1.dp

/** Width the 预设 block takes from the cell track, so the pill stays under its tool. */
internal val PresetTrailingDp = PresetSeparatorWidth + FmSpacing.xs + PresetCellWidth

/**
 * V2 five-tool bar: 边框 · 样式 · 文字 · Logo · 水印. The bar is the **floating glass
 * capsule** from `BottomNav.kt` (`FmNavGlassCapsule`): it keeps the caller's
 * 58 / 62 dp slot, but the capsule inside it is inset 8 dp from each side and 6 dp
 * from the bottom, fully rounded, with a soft shadow and a pill behind the selected
 * cell. The slot height is unchanged on purpose — `PhoneEditor`'s area budget and
 * `PhoneEditorHeightsTest` keep reading the same numbers.
 *
 * The selected cell turns blue (report §7.3 "选中项为蓝色"), and a cell whose page
 * has changed controls carries the small dot — its data is
 * [com.framemark.native.inspector.InspectorTabInfo.modified], i.e. WP-C's "已改项数",
 * never a locally recomputed count.
 *
 * The bar only reports the tap; the caller decides which page a tool opens
 * ([phoneToolPage]).
 *
 * **Trailing 预设 action** ([onOpenPresets]): the preset page used to be reachable
 * only from the border sheet's head menu (long-press the expand chevron), so the
 * owner of this build could not find save/apply at all. It is an action, not a
 * page — it never joins the tab group — and it stays visible on every tool, so
 * "save what I just made" does not require switching back to 边框 first. It opens
 * the same [com.framemark.native.shell.PhonePresetSheet]; nothing here saves.
 *
 * @param backdrop what the glass should blur. Pass a [FmNavBackdrop] captured from
 *   the content behind the bar to get real backdrop blur; without it (or on a
 *   platform without `RenderEffect`) the capsule draws its translucent fallback.
 * @param height the slot the caller budgets for this bar (58 / 62 dp on a phone).
 */
@Composable
fun PhoneToolBar(
    height: Dp,
    tool: EditorTool,
    availableTools: List<EditorTool>,
    modifiedOf: (EditorTool) -> Boolean,
    onSelect: (EditorTool) -> Unit,
    onOpenPresets: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    backdrop: FmNavBackdrop? = null,
) {
    FmNavGlassCapsule(slotHeight = height, modifier = modifier, backdrop = backdrop) {
        val openPresets = onOpenPresets
        val trailing = if (openPresets != null) PresetTrailingDp else 0.dp
        FmNavSelectedPill(
            index = FmNavCapsule.selectedIndex(availableTools, tool),
            capsuleWidth = maxWidth,
            capsuleHeight = maxHeight,
            count = availableTools.size,
            trailing = trailing,
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = FmNavCapsule.innerPad,
                    vertical = FmNavCapsule.pillInsetV,
                )
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            availableTools.forEach { entry ->
                ToolCell(
                    tool = entry,
                    selected = entry == tool,
                    marked = modifiedOf(entry),
                    onClick = { onSelect(entry) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (openPresets != null) {
                Box(
                    modifier = Modifier
                        .width(PresetSeparatorWidth)
                        .height(FmNavCapsule.iconSize)
                        .background(FmPalette.outline),
                )
                Box(modifier = Modifier.width(FmSpacing.xs))
                PresetActionCell(
                    onClick = openPresets,
                    modifier = Modifier.width(PresetCellWidth),
                )
            }
        }
    }
}

/**
 * The always-visible 预设 action: tinted like a call to action (not like the
 * selected tab), one tap, full cell height — so its touch target is the whole
 * cell instead of the 30 dp pill AU4-05 measured inside the sheet head.
 */
@Composable
private fun PresetActionCell(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = FmSpacing.xxs)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.primarySoft)
            .semantics(mergeDescendants = true) {
                contentDescription = "预设，保存当前设计或套用已保存的预设"
                role = Role.Button
            }
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PhonePresetGlyph(tint = FmPalette.primaryStrong, modifier = Modifier.size(FmNavCapsule.iconSize))
        Box(modifier = Modifier.height(FmNavCapsule.iconLabelGap))
        Text(
            text = PhonePresetEntryPolicy.ACTION_LABEL,
            style = FmType.labelSmall.copy(
                fontSize = FmNavCapsule.labelSize,
                lineHeight = FmNavCapsule.labelLineHeight,
                fontWeight = FontWeight.SemiBold,
            ),
            color = FmPalette.primaryStrong,
            maxLines = 1,
        )
    }
}

/** A bookmark: "this design is saved". Stroke-based so it follows [tint]. */
@Composable
private fun PhonePresetGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.26f, w * 0.16f)
            lineTo(w * 0.74f, w * 0.16f)
            lineTo(w * 0.74f, w * 0.84f)
            lineTo(w * 0.5f, w * 0.64f)
            lineTo(w * 0.26f, w * 0.84f)
            close()
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}

@Composable
private fun ToolCell(
    tool: EditorTool,
    selected: Boolean,
    marked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = phoneToolLabel(tool)
    val tint = if (selected) FmPalette.primaryStrong else FmPalette.onSurface
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(FmRadii.pill))
            .semantics(mergeDescendants = true) {
                contentDescription = if (marked) "$label，有改动" else label
                stateDescription = if (selected) "已选中" else "未选中"
            }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            PhoneToolGlyph(tool = tool, tint = tint, modifier = Modifier.size(FmNavCapsule.iconSize))
            if (marked) {
                Box(
                    modifier = Modifier
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(ToolDotSize)
                        .clip(CircleShape)
                        .background(FmPalette.primary),
                )
            }
        }
        Box(modifier = Modifier.height(FmNavCapsule.iconLabelGap))
        Text(
            text = label,
            style = FmType.labelSmall.copy(
                fontSize = FmNavCapsule.labelSize,
                lineHeight = FmNavCapsule.labelLineHeight,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = tint,
            maxLines = 1,
        )
    }
}

/** Status dot on a cell whose page has changed controls. */
private val ToolDotSize = 7.dp

/** The five tool glyphs, sized by the capsule, stroke-based so they follow [tint]. */
@Composable
private fun PhoneToolGlyph(tool: EditorTool, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (tool) {
            EditorTool.Border -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.1f, w * 0.1f),
                    size = Size(w * 0.8f, w * 0.8f),
                    cornerRadius = CornerRadius(w * 0.18f),
                    style = stroke,
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.32f, w * 0.32f),
                    size = Size(w * 0.36f, w * 0.36f),
                    cornerRadius = CornerRadius(w * 0.07f),
                    style = stroke,
                )
            }
            EditorTool.Params -> {
                listOf(0.24f, 0.5f, 0.76f).forEachIndexed { index, y ->
                    drawLine(
                        tint,
                        Offset(w * 0.14f, w * y),
                        Offset(w * 0.86f, w * y),
                        stroke.width,
                        StrokeCap.Round,
                    )
                    val knob = listOf(0.68f, 0.36f, 0.58f)[index]
                    drawCircle(tint, radius = w * 0.08f, center = Offset(w * knob, w * y))
                }
            }
            EditorTool.Text -> {
                drawLine(tint, Offset(w * 0.18f, w * 0.24f), Offset(w * 0.82f, w * 0.24f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.5f, w * 0.24f), Offset(w * 0.5f, w * 0.84f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.34f, w * 0.84f), Offset(w * 0.66f, w * 0.84f), stroke.width, StrokeCap.Round)
            }
            EditorTool.Logo -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.1f, w * 0.2f),
                    size = Size(w * 0.8f, w * 0.6f),
                    cornerRadius = CornerRadius(w * 0.16f),
                    style = stroke,
                )
                drawCircle(tint, radius = w * 0.12f, center = Offset(w * 0.5f, w * 0.5f), style = stroke)
                drawLine(tint, Offset(w * 0.28f, w * 0.5f), Offset(w * 0.4f, w * 0.5f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.6f, w * 0.5f), Offset(w * 0.72f, w * 0.5f), stroke.width, StrokeCap.Round)
            }
            EditorTool.Watermark -> {
                val path = Path().apply {
                    moveTo(w * 0.5f, w * 0.12f)
                    cubicTo(w * 0.82f, w * 0.5f, w * 0.82f, w * 0.62f, w * 0.82f, w * 0.68f)
                    cubicTo(w * 0.82f, w * 0.86f, w * 0.68f, w * 0.9f, w * 0.5f, w * 0.9f)
                    cubicTo(w * 0.32f, w * 0.9f, w * 0.18f, w * 0.86f, w * 0.18f, w * 0.68f)
                    cubicTo(w * 0.18f, w * 0.62f, w * 0.18f, w * 0.5f, w * 0.5f, w * 0.12f)
                    close()
                }
                drawPath(path = path, color = tint, style = stroke)
            }
        }
    }
}
