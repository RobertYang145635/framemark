package com.framemark.native.shell.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Line glyphs of the V2 phone editor chrome. They are drawn, not icon-font based,
 * so they follow the ink colour of the surface they sit on (dark top bar, dark
 * stage, light sheet) without a second asset set.
 */

/** 返回: one chevron pointing left. */
@Composable
internal fun PhoneBackGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val stroke = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.62f, w * 0.16f)
            lineTo(w * 0.30f, w * 0.50f)
            lineTo(w * 0.62f, w * 0.84f)
        }
        drawPath(path, tint, style = stroke)
    }
}

/**
 * 撤销 / 重做: the V06 curved arrow.
 *
 * WP-F2 D5 / WP-X3 §5: the arrow head decides which way the glyph reads. Undo
 * must point back (**left**), redo forward (**right**) — that is what the desktop
 * top bar draws (`DesktopLayout.kt` `DeskHistoryGlyph` puts the head at `x(0.20)`
 * for undo) and what every platform convention expects. This glyph used to draw
 * the head at `endX`, which the `startX/endX` split flipped: undo's head landed
 * on the right and redo's on the left, i.e. the two arrows were swapped on the
 * phone only. Both ends are now mirrored through one `x()` helper, so the head
 * is on the left for undo and on the right for redo.
 */
@Composable
internal fun PhoneHistoryGlyph(tint: Color, redo: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.7.dp.toPx()
        // Same mirror trick as the desktop glyph: undo uses the plain coordinates,
        // redo the mirrored ones, so a single geometry serves both directions.
        fun x(v: Float): Float = if (redo) w - v * w else v * w
        val path = Path().apply {
            moveTo(x(0.84f), h * 0.42f)
            cubicTo(x(0.80f), h * 0.10f, x(0.20f), h * 0.10f, x(0.16f), h * 0.42f)
        }
        drawPath(path, tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
        // The head sits at x(0.16): left for undo, right for redo.
        val headX = x(0.16f)
        val tipY = h * 0.74f
        drawLine(tint, Offset(headX, h * 0.42f), Offset(headX, h * 0.72f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(headX, tipY), Offset(headX - w * 0.16f, h * 0.60f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(headX, tipY), Offset(headX + w * 0.16f, h * 0.60f), stroke, StrokeCap.Round)
    }
}

/** 按住看原图: an eye. */
@Composable
internal fun PhoneEyeGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(15.dp)) {
        val w = size.width
        val stroke = 1.3.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.06f, w * 0.5f)
            cubicTo(w * 0.3f, w * 0.18f, w * 0.7f, w * 0.18f, w * 0.94f, w * 0.5f)
            cubicTo(w * 0.7f, w * 0.82f, w * 0.3f, w * 0.82f, w * 0.06f, w * 0.5f)
            close()
        }
        drawPath(path, tint, style = Stroke(width = stroke))
        drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.5f, w * 0.5f))
    }
}

/** 专注查看: four corner arms. */
@Composable
internal fun PhoneFocusGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(15.dp)) {
        val w = size.width
        val stroke = 1.5.dp.toPx()
        val arm = w * 0.34f
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(tint, Offset(x, y), Offset(x + dx * arm, y), stroke, StrokeCap.Round)
            drawLine(tint, Offset(x, y), Offset(x, y + dy * arm), stroke, StrokeCap.Round)
        }
        corner(w * 0.1f, w * 0.1f, 1f, 1f)
        corner(w * 0.9f, w * 0.1f, -1f, 1f)
        corner(w * 0.1f, w * 0.9f, 1f, -1f)
        corner(w * 0.9f, w * 0.9f, -1f, -1f)
    }
}

/** 「更多」: three dots. */
@Composable
internal fun PhoneMoreGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(16.dp)) {
        val w = size.width
        val r = w * 0.085f
        listOf(0.24f, 0.5f, 0.76f).forEach { x ->
            drawCircle(tint, radius = r, center = Offset(w * x, w * 0.5f))
        }
    }
}

/** 已选: the check inside the card's selection dot, 12 dp. */
@Composable
internal fun PhoneCheckGlyph(tint: Color, modifier: Modifier = Modifier, dim: androidx.compose.ui.unit.Dp = 12.dp) {
    Canvas(modifier = modifier.size(dim)) {
        val w = dim.toPx()
        val path = Path().apply {
            moveTo(w * 0.22f, w * 0.52f)
            lineTo(w * 0.43f, w * 0.72f)
            lineTo(w * 0.78f, w * 0.3f)
        }
        drawPath(path, tint, style = Stroke(width = w * 0.15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 关闭: the export sheet's cross, 18 dp. */
@Composable
internal fun PhoneCloseGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val w = size.width
        val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.22f, w * 0.22f), Offset(w * 0.78f, w * 0.78f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.78f, w * 0.22f), Offset(w * 0.22f, w * 0.78f), stroke.width, StrokeCap.Round)
    }
}

/** 恢复默认: the showcase `.fs .reset` arrow, 15 dp. */
@Composable
internal fun PhoneResetGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(15.dp)) {
        val w = size.width
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawArc(
            color = tint,
            startAngle = 40f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(w * 0.16f, w * 0.16f),
            size = androidx.compose.ui.geometry.Size(w * 0.68f, w * 0.68f),
            style = stroke,
        )
        drawLine(tint, Offset(w * 0.52f, w * 0.06f), Offset(w * 0.86f, w * 0.22f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.86f, w * 0.22f), Offset(w * 0.60f, w * 0.34f), stroke.width, StrokeCap.Round)
    }
}

/** 搜索边框: a magnifier, 16 dp. */
@Composable
internal fun PhoneSearchGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(16.dp)) {
        val w = size.width
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(
            color = tint,
            radius = w * 0.29f,
            center = Offset(w * 0.42f, w * 0.42f),
            style = stroke,
        )
        drawLine(tint, Offset(w * 0.64f, w * 0.64f), Offset(w * 0.88f, w * 0.88f), stroke.width, StrokeCap.Round)
    }
}

/** Sheet expand / collapse arrow; [right] draws the `›` used by 调整样式. */
@Composable
internal fun PhoneChevronGlyph(
    tint: Color,
    up: Boolean,
    modifier: Modifier = Modifier,
    right: Boolean = false,
    dim: androidx.compose.ui.unit.Dp = 18.dp,
) {
    Canvas(modifier = modifier.size(dim)) {
        val w = dim.toPx()
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            when {
                right -> {
                    moveTo(w * 0.38f, w * 0.22f)
                    lineTo(w * 0.66f, w * 0.5f)
                    lineTo(w * 0.38f, w * 0.78f)
                }
                up -> {
                    moveTo(w * 0.22f, w * 0.62f)
                    lineTo(w * 0.5f, w * 0.34f)
                    lineTo(w * 0.78f, w * 0.62f)
                }
                else -> {
                    moveTo(w * 0.22f, w * 0.38f)
                    lineTo(w * 0.5f, w * 0.66f)
                    lineTo(w * 0.78f, w * 0.38f)
                }
            }
        }
        drawPath(path, tint, style = stroke)
    }
}

