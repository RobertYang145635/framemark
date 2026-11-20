package com.framemark.native.shell

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntSize
import com.framemark.native.inspector.ExifGapNotice
import com.framemark.native.theme.FmPalette
import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.slotHighlightRect

/**
 * 舞台上给**缺拍摄参数的文字行**画一圈极淡的虚线框（2026-11-20 用户定的方案）。
 *
 * 为什么要有：照片没有 EXIF 时，边框上的参数行照实留空 —— 用户看不出「这里本来能填」，
 * 也不知道几张只差参数行的边框有什么区别。库里的缩略图已经改成随包样张（能看到完整
 * 设计），舞台上再给这几行一个可见的占位，用户一眼就知道缺口在哪。
 *
 * 三条边界（[ExifGapNotice] + 两端的 `exifHintsAcknowledged`）：
 *  1. **只在编辑器里画**：这是 Compose 覆盖层，引擎输出不含它，导出一定干净；
 *  2. 只画「有位置但没字」的行（[ExifGapNotice.isMissing]），已经填过的行不画；
 *  3. 用户**进过「文字」页**就撤掉，但这条「看过了」是**按照片**记、并且**换照片或
 *     换边框就重置**（用户 2026-11-20 定）：同一张照片换到别的边框再换回来，占位仍然
 *     出现 —— 那时面对的是一套新排版，缺口得重新看见。
 */
/**
 * 该不该画这层占位：这套边框没被标记「看过」，且确实有「有位置但没字」的行。
 *
 * 单独抽出来给两端共用（也用来钉边界条件）：看过 → 不画；没有缺行 → 不画。
 */
internal fun shouldDrawExifHints(slots: List<TextSlot>, cardSeen: Boolean): Boolean =
    !cardSeen && ExifGapNotice.missingFields(slots).isNotEmpty()

@Composable
internal fun ExifSlotHintLayer(
    slots: List<TextSlot>,
    bitmapWidth: Int,
    bitmapHeight: Int,
    boxSize: IntSize,
    modifier: Modifier = Modifier,
    tint: Color = FmPalette.stageInk,
) {
    val missing = ExifGapNotice.missingFields(slots)
    if (missing.isEmpty() || bitmapWidth <= 0 || bitmapHeight <= 0) return
    Canvas(modifier = modifier) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
        val stroke = Stroke(width = 1.5f, pathEffect = dash)
        for (slot in missing) {
            val rect = slotHighlightRect(
                slot = slot,
                bitmapW = bitmapWidth,
                bitmapH = bitmapHeight,
                boxW = boxSize.width.toFloat(),
                boxH = boxSize.height.toFloat(),
            ) ?: continue
            drawRoundRect(
                color = tint.copy(alpha = 0.28f),
                topLeft = Offset(rect.x, rect.y),
                size = Size(rect.w, rect.h),
                cornerRadius = CornerRadius(4f, 4f),
                style = stroke,
            )
        }
    }
}
