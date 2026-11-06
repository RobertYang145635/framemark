package com.framemark.native.shell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmType

/** Side of a pane that carries the 1 dp hairline separator (V2 §6: 发丝线分区). */
enum class HairlineEdge { Left, Right, None }

/**
 * One V2 desktop column.
 *
 * V2 replaced the rounded, shadowed "card inside a card" panels with a flat
 * surface separated by a single hairline (UI report §3.3 / §6 / §8). The
 * hairline is drawn *inside* the pane's own width so 296 + stage + 336 still
 * equals the window content width exactly — no extra separator column.
 */
@Composable
fun DesktopPane(
    modifier: Modifier = Modifier,
    width: Dp? = null,
    background: Color = FmPalette.surface,
    edge: HairlineEdge = HairlineEdge.None,
    content: @Composable () -> Unit,
) {
    val line = FmPalette.outline
    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .fillMaxHeight()
            .background(background)
            .then(
                if (edge == HairlineEdge.None) {
                    Modifier
                } else {
                    Modifier.drawWithContent {
                        drawContent()
                        val stroke = 1.dp.toPx()
                        val x = if (edge == HairlineEdge.Right) size.width - stroke / 2f else stroke / 2f
                        drawLine(
                            color = line,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = stroke,
                        )
                    }
                },
            ),
    ) {
        content()
    }
}

/**
 * Folded rail: the V2 library collapses to a 52 px strip (UI report §6), not a
 * 24 px arrow. The label is rotated by stacking characters so the strip still
 * says what it opens.
 */
@Composable
fun DesktopCollapsedRail(
    side: String,
    onExpand: () -> Unit,
) {
    val label = if (side == "left") "边框库" else "参数"
    val arrow = if (side == "left") "›" else "‹"
    Box(
        modifier = Modifier
            .width(52.dp)
            .fillMaxHeight()
            .background(FmPalette.surfaceMuted)
            .clickable(onClick = onExpand)
            .semantics {
                contentDescription = if (side == "left") "展开边框库" else "展开参数面板"
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = arrow,
                style = FmType.titleMedium,
                color = FmPalette.primaryStrong,
                modifier = Modifier.clip(RoundedCornerShape(FmRadii.chip)),
            )
            label.forEach { ch ->
                Text(
                    text = ch.toString(),
                    style = FmType.labelSmall.copy(fontSize = 11.sp),
                    color = FmPalette.onSurfaceMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
