package com.framemark.native.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmType

/**
 * The editor's five phone tools (V06). The names are UI routing only: no design
 * state, project field or engine binding is keyed on them.
 */
enum class EditorTool(val label: String) {
    Border("边框"),
    Params("调整"),
    Text("文字"),
    Logo("Logo"),
    Watermark("图片水印"),
}

/** 1 dp hairline + 51 dp of cells, so every cell clears the 48 dp touch target. */
private val ToolBarDivider = 1.dp

/**
 * Bottom tool bar: equal-width cells for [availableTools] (Logo only when the
 * card has a Logo capability, so 4 or 5 cells). The bar only reports the tap;
 * the caller decides which panel a tool opens.
 */
@Composable
fun FmEditorToolBar(
    height: Dp,
    tool: EditorTool,
    availableTools: List<EditorTool>,
    onSelect: (EditorTool) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(FmPalette.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ToolBarDivider)
                .background(FmPalette.outline),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .selectableGroup()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            availableTools.forEach { entry ->
                ToolCell(
                    tool = entry,
                    selected = entry == tool,
                    onClick = { onSelect(entry) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ToolCell(
    tool: EditorTool,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceMuted
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(FmRadii.tile))
            .background(if (selected) FmPalette.primarySoft else Color.Transparent)
            .semantics(mergeDescendants = true) {
                contentDescription = tool.label
                stateDescription = if (selected) "已选中" else "未选中"
            }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ToolGlyph(tool = tool, tint = tint, modifier = Modifier.size(20.dp))
        Box(modifier = Modifier.height(2.dp))
        Text(
            text = tool.label,
            style = FmType.labelSmall.copy(fontSize = 11.sp),
            color = tint,
            maxLines = 1,
        )
    }
}

@Composable
private fun ToolGlyph(tool: EditorTool, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
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
                // Three sliders with their knobs at different stops.
                val rows = listOf(0.24f, 0.5f, 0.76f)
                val knobs = listOf(0.68f, 0.36f, 0.58f)
                rows.forEachIndexed { index, y ->
                    drawLine(
                        tint,
                        Offset(w * 0.14f, w * y),
                        Offset(w * 0.86f, w * y),
                        stroke.width,
                        StrokeCap.Round,
                    )
                    drawCircle(tint, radius = w * 0.08f, center = Offset(w * knobs[index], w * y))
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
