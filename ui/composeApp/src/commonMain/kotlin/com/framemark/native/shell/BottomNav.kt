package com.framemark.native.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.shadow
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
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType

/** The editor's two tools (old WebView `.mobile-editor-bar`). */
enum class EditorTool(val label: String) {
    Border("边框"),
    Watermark("水印"),
}

/**
 * Bottom segment bar of the editor: exactly 边框 / 水印. Tapping 边框 while it is
 * already active opens the border library (same double duty as the old app);
 * the caller decides that, this bar only reports the tap.
 */
@Composable
fun FmEditorToolBar(
    height: Dp,
    tool: EditorTool,
    onSelectBorder: () -> Unit,
    onSelectWatermark: () -> Unit,
) {
    // Area budget block (62dp / 58dp compact) holding the old floating pill.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(FmPalette.surface)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .shadow(12.dp, RoundedCornerShape(FmRadii.pill))
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.surface)
                .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.pill))
                .selectableGroup()
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolCell(
                tool = EditorTool.Border,
                selected = tool == EditorTool.Border,
                onClick = onSelectBorder,
                modifier = Modifier.weight(1f),
            )
            ToolCell(
                tool = EditorTool.Watermark,
                selected = tool == EditorTool.Watermark,
                onClick = onSelectWatermark,
                modifier = Modifier.weight(1f),
            )
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
            .semantics(mergeDescendants = true) {
                contentDescription = tool.label
                stateDescription = if (selected) "已选中" else "未选中"
            }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ToolGlyph(tool = tool, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        Text(
            tool.label,
            style = FmType.labelMedium.copy(fontSize = 12.sp),
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
