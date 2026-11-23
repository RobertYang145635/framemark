package com.framemark.native.shell

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.framemark.native.inspector.ExifGapNotice
import com.framemark.native.theme.FmPalette
import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.slotHighlightRect

/**
 * 这层淡灰示例该不该画：这套照片/边框没被标记「看过」，且确实有「有位置但没字」的行。
 * 单独抽出来方便测试，也把「看过就不画」这条边界放在一处。
 */
internal fun shouldDrawExifHints(slots: List<TextSlot>, cardSeen: Boolean): Boolean =
    !cardSeen && ExifGapNotice.missingFields(slots).isNotEmpty()

/**
 * 舞台上给**缺拍摄参数的行**画淡灰的「标准参数档」示例（2026-11-21 用户口径）。
 *
 * 为什么：照片没有 EXIF 时，边框上那一行照实留空，用户看不出这里本来写什么，也不知道这张
 * 照片能填什么。这里把该行的示例（`23mm F1.8 1/125s ISO250`、机型、日期…）用淡灰画在
 * **这一行真实的位置**上：看一眼就知道「这里本来有字」。
 *
 * 三条边界（与 [ExifGapNotice] 一致）：
 *  1. 只在编辑器里画：Compose 覆盖层，引擎输出与导出都不含它；
 *  2. 只画「有位置但没字」的行，已经填过的不画；
 *  3. 进过一次「文字」页就撤掉（`exifHintsAcknowledged`），换照片或换边框重新出现。
 *
 * 位置来自渲染报告；报告没有矩形时由 `SlotGeometryProbe` 用量出来的矩形补上
 * （[slotHighlightRect] 也接受零宽矩形，按字高给最小框）。
 */
@Composable
internal fun ExifSlotHintLayer(
    slots: List<TextSlot>,
    bitmapWidth: Int,
    bitmapHeight: Int,
    boxSize: IntSize,
    samples: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val missing = ExifGapNotice.missingFields(slots)
    if (missing.isEmpty() || bitmapWidth <= 0 || bitmapHeight <= 0 || samples.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val ink = FmPalette.stageInk
    Canvas(modifier = modifier) {
        for (slot in missing) {
            // 优先用引擎给的「这一格本来会写什么」（`@exif=@{focal}mm` → `23mm`），
            // 引擎没给的（手写模板）才退回按角色取的示例。
            val text = slot.key?.let(samples::get)?.takeIf { it.isNotBlank() } ?: continue
            val rect = slotHighlightRect(
                slot = slot,
                bitmapW = bitmapWidth,
                bitmapH = bitmapHeight,
                boxW = boxSize.width.toFloat(),
                boxH = boxSize.height.toFloat(),
            ) ?: continue
            // 字号按**屏幕上**这一行的高度换算：报告里的 fontSize 是画布像素，
            // 直接当 sp 用会放大好几倍（第一版就出了这个错）。
            val fontPx = (rect.h * 0.82f).coerceAtLeast(8f)
            val style = TextStyle(fontSize = fontPx.toSp())
            drawGhost(measurer, text, rect.x, rect.y, rect.h, rect.w, ink, style)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGhost(
    measurer: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    height: Float,
    width: Float,
    ink: Color,
    style: TextStyle,
) {
    val measured = measurer.measure(
        text = text,
        style = style.copy(color = ink.copy(alpha = 0.42f)),
        maxLines = 1,
        overflow = TextOverflow.Clip,
        constraints = Constraints(maxWidth = width.toInt().coerceAtLeast(1)),
    )
    // 行内垂直居中，并按这一行的宽度裁掉超出部分（示例比真实文字长时不能溢出边框）。
    val top = y + ((height - measured.size.height) / 2f).coerceAtLeast(0f)
    clipRect(x, y, x + width, y + height) {
        drawText(textLayoutResult = measured, topLeft = Offset(x, top))
    }
}
