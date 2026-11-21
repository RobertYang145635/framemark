package com.framemark.native.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设置齿轮：手机首页与桌面顶栏**同一枚字形**。
 *
 * 2026-11 用户反馈两端图标不一样：桌面那枚是细环 + 八根短射线（读起来像太阳），
 * 手机这枚是粗齿 + 实心环 + 中心孔。现在只有这一份绘制，两端的差别只剩尺寸
 * （手机 22 dp、桌面 18 dp）；比例全部按宽度取，所以任何尺寸都同一个形状。
 */
@Composable
fun FmGearGlyph(tint: Color, size: Dp = 20.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val c = Offset(w / 2f, w / 2f)
        val body = w * 0.27f
        val toothIn = body - w * 0.03f
        val toothOut = w * 0.47f
        repeat(8) { i ->
            val a = (i * 45f) * (kotlin.math.PI.toFloat() / 180f)
            val dx = kotlin.math.cos(a)
            val dy = kotlin.math.sin(a)
            drawLine(
                color = tint,
                start = Offset(c.x + dx * toothIn, c.y + dy * toothIn),
                end = Offset(c.x + dx * toothOut, c.y + dy * toothOut),
                strokeWidth = w * 0.19f,
                cap = StrokeCap.Butt,
            )
        }
        drawCircle(
            color = tint,
            radius = body,
            center = c,
            style = Stroke(width = w * 0.17f),
        )
    }
}
