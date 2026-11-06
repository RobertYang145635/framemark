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

/** 撤销 / 重做: the V06 curved arrow, kept identical so the gesture reads the same. */
@Composable
internal fun PhoneHistoryGlyph(tint: Color, redo: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val w = size.width
        val stroke = 1.7.dp.toPx()
        val startX = if (redo) w * 0.84f else w * 0.16f
        val endX = if (redo) w * 0.16f else w * 0.84f
        val path = Path().apply {
            moveTo(startX, w * 0.42f)
            cubicTo(w * 0.2f, w * 0.1f, w * 0.8f, w * 0.1f, endX, w * 0.42f)
        }
        drawPath(path, tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawLine(tint, Offset(endX, w * 0.42f), Offset(endX, w * 0.72f), stroke, StrokeCap.Round)
        val tip = w * 0.74f
        drawLine(
            tint,
            Offset(endX, tip),
            Offset(endX - (if (redo) -1f else 1f) * w * 0.16f, w * 0.6f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            tint,
            Offset(endX, tip),
            Offset(endX + (if (redo) -1f else 1f) * w * 0.16f, w * 0.6f),
            stroke,
            StrokeCap.Round,
        )
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

/** Sheet expand / collapse arrow. */
@Composable
internal fun PhoneChevronGlyph(tint: Color, up: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val w = size.width
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            if (up) {
                moveTo(w * 0.22f, w * 0.62f)
                lineTo(w * 0.5f, w * 0.34f)
                lineTo(w * 0.78f, w * 0.62f)
            } else {
                moveTo(w * 0.22f, w * 0.38f)
                lineTo(w * 0.5f, w * 0.66f)
                lineTo(w * 0.78f, w * 0.38f)
            }
        }
        drawPath(path, tint, style = stroke)
    }
}
