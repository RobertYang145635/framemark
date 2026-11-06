package com.framemark.native.shell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmDesktop
import com.framemark.native.theme.FmElevation
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmType
import com.framemark.native.theme.FmNumeric

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

// ---------------------------------------------------------------------------
// 桌面控件工具箱
//
// 演示稿的按钮/图标按钮/段控都是同一套 `.btn` / `.ibtn` / `.seg`（styles.css
// 110-145）：高度 32、圆角 8、13px、hover 150 ms。顶栏、舞台、胶片条三处都要用，
// 放在这里保证三处的 hover / 禁用 / 主次按钮表现完全一致。
// ---------------------------------------------------------------------------

/** `transition: background .15s` 的 hover 状态；返回交互源与"是否悬停"。 */
@Composable
fun rememberDeskHover(enabled: Boolean = true): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    return source to (hovered && enabled)
}

/**
 * `.ibtn`：32×32、圆角 8、`ink-2` 图标；hover 上浮到 `hover` 底 + `ink`；
 * 禁用 `ink-4` 55 %（`styles.css` 124-126）。
 */
@Composable
fun DeskIconButton(
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    size: Dp = 32.dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val (source, hovered) = rememberDeskHover(enabled)
    val background = when {
        !enabled -> Color.Transparent
        active -> FmPalette.surfaceMuted
        hovered -> FmPalette.hover
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(background)
            .hoverable(source, enabled)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** `.btn` 的三种样式（`styles.css` 111-120）。 */
enum class DeskButtonStyle { Ghost, Line, Primary }

/**
 * `.btn`：高 32、圆角 8、padding 0 14、13/500，图标与文字 gap 6。
 *
 * [kbd] 是「导出 Ctrl E」里那块浅色快捷键提示（`.btn.primary kbd`）。
 */
@Composable
fun DeskButton(
    label: String,
    modifier: Modifier = Modifier,
    style: DeskButtonStyle = DeskButtonStyle.Ghost,
    enabled: Boolean = true,
    height: Dp = 32.dp,
    kbd: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val (source, hovered) = rememberDeskHover(enabled)
    val shape = RoundedCornerShape(FmRadii.chip)
    val background = when (style) {
        DeskButtonStyle.Primary -> if (hovered) FmPalette.primaryStrong else FmPalette.primary
        DeskButtonStyle.Line -> if (hovered) FmPalette.surfaceMuted else FmPalette.surface
        DeskButtonStyle.Ghost -> if (hovered) FmPalette.hover else Color.Transparent
    }
    val ink = when (style) {
        DeskButtonStyle.Primary -> FmPalette.onPrimary
        DeskButtonStyle.Line -> FmPalette.onSurface
        DeskButtonStyle.Ghost -> if (hovered) FmPalette.onSurface else FmPalette.onSurfaceSecondary
    }
    Row(
        modifier = modifier
            .height(height)
            .alpha(if (enabled) 1f else DisabledAlpha)
            .clip(shape)
            .background(background)
            .then(
                if (style == DeskButtonStyle.Line) Modifier.border(1.dp, FmPalette.outlineStrong, shape) else Modifier,
            )
            .hoverable(source, enabled)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        Text(text = label, style = FmType.bodyMedium, color = ink, maxLines = 1)
        if (kbd != null) {
            Spacer(Modifier.width(6.dp))
            DeskKbd(text = kbd, onPrimary = style == DeskButtonStyle.Primary)
        }
    }
}

/** 按钮右侧的快捷键提示；主按钮上是白色半透明片，其余是描边片。 */
@Composable
fun DeskKbd(text: String, onPrimary: Boolean) {
    val background = if (onPrimary) FmPalette.onPrimary.copy(alpha = 0.16f) else FmPalette.surfaceMuted
    val ink = if (onPrimary) FmPalette.onPrimary.copy(alpha = 0.85f) else FmPalette.onSurfaceMuted
    Text(
        text = text,
        style = FmNumeric.frameNumber,
        color = ink,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** `.btn[disabled] { opacity: .45 }` / `.ibtn[disabled] { opacity: .55 }`。 */
const val DisabledAlpha = 0.45f
const val DisabledIconAlpha = 0.55f

/**
 * `.seg`：槽 `--seg`、padding 2、圆角 8；按钮 h28、12.5px，选中滑块白底 + 阴影
 * （`styles.css` 134-140）。宽度由调用点给（顶栏 220）。
 */
@Composable
fun DeskSegmented(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(FmPalette.indicator)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val (source, hovered) = rememberDeskHover()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .then(
                        if (selected) {
                            Modifier
                                .background(FmPalette.surface)
                                .shadow(
                                    elevation = 1.dp,
                                    shape = RoundedCornerShape(6.dp),
                                    clip = false,
                                    ambientColor = FmElevation.cardAmbient,
                                    spotColor = FmElevation.cardSpot,
                                )
                        } else {
                            Modifier
                        },
                    )
                    .hoverable(source)
                    .clickable(
                        interactionSource = source,
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = FmType.desktopChip.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = when {
                        selected -> FmPalette.onSurface
                        hovered -> FmPalette.onSurface
                        else -> FmPalette.onSurfaceMuted
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** 竖线 `1×20`（`.vsep`，`styles.css` 103）。 */
@Composable
fun DeskVsep(height: Dp = FmDesktop.topBarSeparatorHeight) {
    Box(Modifier.width(1.dp).height(height).background(FmPalette.outline))
}

/**
 * 品牌 mark：26×26、圆角 7，深色底 + 浅色"窗口" + 底部一道浅蓝（`styles.css` 50-52）。
 *
 * 深色外观下演示稿把底换成 `#2B2C2F`、窗口换成 `#EDEFF3`（`theme-dark.css` 42-43）；
 * 这里底一律用 [FmPalette.stage]（两套外观都是 `#2B2C2F`），窗口用白色
 * （`onPrimary`），所以浅色外观里底比演示稿的 `#14181F` 浅一点点，深色外观完全一致。
 */
@Composable
fun DeskBrandMark(size: Dp = FmDesktop.topBarMarkSize) {
    val radius = size * (FmDesktop.topBarMarkRadius.value / FmDesktop.topBarMarkSize.value)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(FmPalette.stage),
    ) {
        // .mark::before — left/right 6、top 6、bottom 9，圆角 2（按 26 的基准缩放）
        val unit = size.value / FmDesktop.topBarMarkSize.value
        Box(
            modifier = Modifier
                .padding(start = (6 * unit).dp, end = (6 * unit).dp, top = (6 * unit).dp, bottom = (9 * unit).dp)
                .fillMaxHeight()
                .width(size - (12 * unit).dp)
                .clip(RoundedCornerShape((2 * unit).dp))
                .background(FmPalette.onPrimary),
        )
        // .mark::after — left 9、bottom 5.5、8×1.5、圆角 1
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = (9 * unit).dp, bottom = (5.5f * unit).dp)
                .width((8 * unit).dp)
                .height((1.5f * unit).dp)
                .clip(RoundedCornerShape(1.dp))
                .background(FmPalette.primaryStrong),
        )
    }
}

/** `.stage-tb .tb` 里的 `−` / `+`；1.6 dp 细线，比字体减号更像图标。 */
@Composable
fun DeskPlusMinusGlyph(plus: Boolean, tint: Color, size: Dp = 16.dp) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val stroke = 1.6.dp.toPx()
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val arm = this.size.width * 0.32f
        drawLine(tint, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), stroke, StrokeCap.Round)
        if (plus) drawLine(tint, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), stroke, StrokeCap.Round)
    }
}

/** 顶栏撤销/重做：一段圆弧 + 箭头（演示稿的 curved-arrow 图标）。 */
@Composable
fun DeskHistoryGlyph(redo: Boolean, tint: Color, size: Dp = 18.dp) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        fun x(v: Float): Float = if (redo) w - v * w else v * w
        val strokeWidth = 1.6.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(x(0.20f), h * 0.60f)
            cubicTo(x(0.26f), h * 0.16f, x(0.80f), h * 0.16f, x(0.84f), h * 0.56f)
        }
        drawPath(path, color = tint, style = stroke)
        // 箭头：端点处两条短线（上、右），比一个 L 形折角更接近演示稿。
        drawLine(tint, Offset(x(0.20f), h * 0.60f), Offset(x(0.20f), h * 0.30f), strokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(x(0.20f), h * 0.60f), Offset(x(0.44f), h * 0.60f), strokeWidth, StrokeCap.Round)
    }
}

/** 「导入照片」/「导出」的箭头 + 托盘图标（`ic('import'|'export', 16)`）。 */
@Composable
fun DeskTransferGlyph(export: Boolean, tint: Color, size: Dp = 16.dp) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 1.5.dp.toPx()
        val shaftTop = if (export) h * 0.62f else h * 0.18f
        val shaftBottom = if (export) h * 0.18f else h * 0.62f
        val cx = w * 0.5f
        drawLine(tint, Offset(cx, shaftTop), Offset(cx, shaftBottom), stroke, StrokeCap.Round)
        // 箭头
        val tip = if (export) h * 0.18f else h * 0.62f
        val wing = if (export) h * 0.30f else h * 0.50f
        drawLine(tint, Offset(cx, tip), Offset(cx - w * 0.16f, wing), stroke, StrokeCap.Round)
        drawLine(tint, Offset(cx, tip), Offset(cx + w * 0.16f, wing), stroke, StrokeCap.Round)
        // 托盘
        drawLine(tint, Offset(w * 0.18f, h * 0.72f), Offset(w * 0.18f, h * 0.86f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.82f, h * 0.72f), Offset(w * 0.82f, h * 0.86f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.18f, h * 0.86f), Offset(w * 0.82f, h * 0.86f), stroke, StrokeCap.Round)
    }
}

/** 「原图」对比图标：一个画框，右半边用竖线切成"另一半"（`ic('compare', 16)`）。 */
@Composable
fun DeskCompareGlyph(tint: Color, size: Dp = 16.dp) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 1.5.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.12f, h * 0.16f),
            size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.68f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5.dp.toPx()),
            style = Stroke(width = stroke),
        )
        drawLine(tint, Offset(w * 0.5f, h * 0.16f), Offset(w * 0.5f, h * 0.84f), stroke, StrokeCap.Round)
    }
}

/** 齿轮（顶栏外观入口）；演示稿是 18 dp 描边齿轮：一圈环 + 八颗短齿。 */
@Composable
fun DeskGearGlyph(tint: Color, size: Dp = 18.dp) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 2.dp.toPx()
        val ring = w * 0.24f
        val center = Offset(w / 2f, h / 2f)
        drawCircle(tint, ring, center, style = Stroke(width = stroke))
        repeat(8) { index ->
            val angle = Math.toRadians((index * 45).toDouble()).toFloat()
            val inner = ring + stroke * 0.4f
            val outer = ring + stroke * 1.5f
            drawLine(
                tint,
                Offset(center.x + kotlin.math.cos(angle) * inner, center.y + kotlin.math.sin(angle) * inner),
                Offset(center.x + kotlin.math.cos(angle) * outer, center.y + kotlin.math.sin(angle) * outer),
                stroke,
                StrokeCap.Round,
            )
        }
    }
}


