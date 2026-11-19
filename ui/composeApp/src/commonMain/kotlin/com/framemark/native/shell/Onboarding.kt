package com.framemark.native.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.framemark.native.LaunchOptions
import com.framemark.native.theme.FmDuration
import com.framemark.native.theme.FmEasing
import com.framemark.native.theme.FmMotionRules
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.theme.LocalFmMotionScale
import com.framemark.native.theme.fmElevated
import com.framemark.native.workflow.OnboardingState
import com.framemark.native.workflow.OnboardingStore

/**
 * 教程的日志标签。不带 `FM_` 前缀：它不是 QA 证据协议的一部分，只是一行
 * 「这次为什么弹 / 关掉了」的本地记录。
 */
internal const val ONBOARDING_TAG: String = "ONBOARDING"

/**
 * 新手教程的一页：标题 + 说明 + 示意图。
 *
 * [art] 是**画出来**的，不读任何图片资源：一张示意图 132 dp 高，用 Canvas 的几何
 * 图形拼（相框、边框网格、滑杆、导出单），随配色与明暗自动换色，也不往包里塞
 * 第二份截图。文案在 [OnboardingText] 里，测试不用起界面就能核对。
 */
data class OnboardingPage(
    val title: String,
    val body: String,
    val art: @Composable () -> Unit,
)

/**
 * 四页的顺序与配图。手机和桌面共用同一支 [OnboardingOverlay]，所以两端的步骤
 * 顺序、标题、说明一字不差 —— 复制一份到桌面端只会让两边慢慢分叉。
 */
fun onboardingPages(): List<OnboardingPage> {
    val arts: List<@Composable () -> Unit> = listOf(
        { ImportArt() },
        { BorderArt() },
        { ParamsArt() },
        { ExportArt() },
    )
    val copies = OnboardingText.PAGES
    require(arts.size == copies.size) {
        "新手教程：${arts.size} 张示意图对不上 ${copies.size} 段文案"
    }
    return copies.mapIndexed { index, copy -> OnboardingPage(copy.title, copy.body, arts[index]) }
}

/**
 * 首次启动是否要自动弹教程。
 *
 * 「首帧后判断」由调用点负责（`withFrameNanos`）；这里只回答「该不该弹」，并且
 * 让**证据/自动化启动**（带 `LaunchOptions` 钩子的那种）不弹：那些启动自带要打开的
 * 界面，教程会把它整屏盖住，而用户随时可以从设置或齿轮里手动打开。
 */
internal fun shouldAutoShowOnboarding(store: OnboardingStore): Boolean =
    OnboardingState.shouldShow(store) && !scriptedLaunch()

/** 带了任何一个证据钩子的启动都算脚本启动；正常启动这些值全是默认。 */
private fun scriptedLaunch(): Boolean =
    LaunchOptions.openCardId != null ||
        LaunchOptions.openBatch ||
        LaunchOptions.pairDemo ||
        LaunchOptions.runSampleExport ||
        LaunchOptions.openTab != null ||
        LaunchOptions.libraryQuery != null ||
        LaunchOptions.userPhotoPath != null

/**
 * 整屏遮罩 + 居中的卡片 + 底部入口（跳过 / 下一步 / 开始使用），手机与桌面共用。
 *
 * 状态是**提升**的：[page] 由调用点持有，所以「不破坏页面栈」是天然的 —— 手机上它是
 * 首页 / 编辑页之上的一层浮层，关掉就回到原来那一页，`PhonePage` 一个都没变。
 *
 * 交互约定：
 *  - 遮罩吞掉点击（点空白不关），必须选「跳过」或走完 —— 免得一次误触让教程默默消失；
 *  - 不是最后一页时主按钮是「下一步」，最后一页换成「开始使用」并回调 [onDone]；
 *  - 跳过与完成都由调用点写 `onboarding.done`（见 [OnboardingState.markDone]），
 *    本组件不碰存储，也不改任何页面状态。
 */
@Composable
fun OnboardingOverlay(
    page: Int,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onDone: () -> Unit,
) {
    val pages = remember { onboardingPages() }
    val index = page.coerceIn(0, pages.lastIndex)
    val last = pages.lastIndex
    val shape = RoundedCornerShape(FmRadii.panel)
    val motionScale = LocalFmMotionScale.current
    val slideMs = FmMotionRules.durationMs(FmDuration.pageSlide, motionScale)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(FmPalette.scrim)
            // 遮罩吞掉点击：不吃的话，手指落在卡片外面会穿透到下面的页面。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(FmSpacing.lg)
            .semantics { contentDescription = OnboardingText.TITLE },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = OnboardingText.CARD_MAX_WIDTH)
                .fillMaxWidth()
                // 横屏或大字号下卡片会超过屏高：卡片自己滚，按钮仍留在末尾。
                .heightIn(max = maxHeight)
                .clip(shape)
                .background(FmPalette.surface)
                .fmElevated(shape = shape)
                .verticalScroll(rememberScrollState())
                .padding(FmSpacing.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = OnboardingText.TITLE,
                    style = FmType.labelMedium,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = OnboardingText.counter(index, pages.size),
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(FmSpacing.md))
            AnimatedContent(
                targetState = index,
                modifier = Modifier.clipToBounds(),
                transitionSpec = { onboardingTransform(targetState > initialState, slideMs, motionScale) },
                label = "fm.onboarding.page",
            ) { shown ->
                val current = pages[shown.coerceIn(0, last)]
                Column(modifier = Modifier.fillMaxWidth()) {
                    current.art()
                    Spacer(Modifier.height(FmSpacing.lg))
                    Text(
                        text = current.title,
                        style = FmType.titleLarge,
                        color = FmPalette.onSurface,
                    )
                    Spacer(Modifier.height(FmSpacing.xs))
                    // 预留固定高度：换页时卡片不跳（四页说明都是一到两行）。
                    Text(
                        text = current.body,
                        style = FmType.bodyMedium,
                        color = FmPalette.onSurfaceSecondary,
                        modifier = Modifier.heightIn(min = OnboardingText.BODY_MIN_HEIGHT),
                    )
                }
            }
            Spacer(Modifier.height(FmSpacing.lg))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OnboardingAction(
                    label = OnboardingText.SKIP,
                    description = OnboardingText.SKIP_DESCRIPTION,
                    primary = false,
                    onClick = onSkip,
                )
                Spacer(Modifier.weight(1f))
                PageDots(count = pages.size, current = index)
                Spacer(Modifier.weight(1f))
                if (index < last) {
                    OnboardingAction(
                        label = OnboardingText.NEXT,
                        description = OnboardingText.NEXT_DESCRIPTION,
                        primary = true,
                        onClick = onNext,
                    )
                } else {
                    OnboardingAction(
                        label = OnboardingText.DONE,
                        description = OnboardingText.DONE_DESCRIPTION,
                        primary = true,
                        onClick = onDone,
                    )
                }
            }
        }
    }
}

/**
 * 换页的两段式横移：新页从手指前进的方向进来，旧页往反方向出去，两者共用同一个
 * `tween`（与参数页的滑动手感一致）。`motionScale == 0`（减少动态效果）时退化成
 * 直接换页，不做位移。
 */
private fun onboardingTransform(forward: Boolean, durationMs: Int, motionScale: Float): ContentTransform {
    if (motionScale <= 0f) return EnterTransition.None togetherWith ExitTransition.None
    val spec = tween<IntOffset>(durationMs, easing = FmEasing.standard)
    return slideInHorizontally(spec) { fullWidth -> if (forward) fullWidth else -fullWidth } togetherWith
        slideOutHorizontally(spec) { fullWidth -> if (forward) -fullWidth else fullWidth }
}

/** 「跳过」是文字按钮，「下一步 / 开始使用」是主色胶囊；两者都是 44 dp 高。 */
@Composable
private fun OnboardingAction(
    label: String,
    description: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .heightIn(min = OnboardingText.ACTION_MIN_HEIGHT)
            .clip(RoundedCornerShape(if (primary) FmRadii.pill else FmRadii.chip))
            .background(if (primary) FmPalette.primary else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = if (primary) FmSpacing.lg else FmSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = FmType.labelMedium,
            color = if (primary) FmPalette.onPrimary else FmPalette.onSurfaceMuted,
            maxLines = 1,
        )
    }
}

/** 四个小圆点：当前页是主色，其余是分隔线色。 */
@Composable
private fun PageDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.xs)) {
        repeat(count) { dot ->
            Box(
                modifier = Modifier
                    .size(OnboardingText.DOT_SIZE)
                    .clip(CircleShape)
                    .background(if (dot == current) FmPalette.primary else FmPalette.outline),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 四张示意图（纯绘制，无图片资源）
// ---------------------------------------------------------------------------

/** 画布里用的几个颜色：在可组合处取好，画法中只拿普通 Color。 */
private class ArtColors(
    val ink: Color,
    val faint: Color,
    val accent: Color,
    val line: Color,
    val surface: Color,
)

/** 示意图的统一底板：圆角卡片底 + 固定高度，四页高度一样，换页时卡片不跳。 */
@Composable
private fun ArtBoard(content: DrawScope.(ArtColors) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(OnboardingText.ART_HEIGHT)
            .clip(RoundedCornerShape(FmRadii.card))
            .background(FmPalette.surfaceMuted),
    ) {
        val colors = ArtColors(
            ink = FmPalette.onSurfaceMuted,
            faint = FmPalette.onSurfaceFaint,
            accent = FmPalette.primary,
            line = FmPalette.outline,
            surface = FmPalette.surface,
        )
        Canvas(modifier = Modifier.fillMaxSize()) { content(colors) }
    }
}

/** 一个（可虚线的）圆角矩形；`fill` / `stroke` 都在这里一次算好。 */
private fun DrawScope.roundedArea(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radius: Float,
    fill: Color? = null,
    stroke: Color? = null,
    strokeWidth: Float = 1.2f,
    dash: Boolean = false,
) {
    val width = right - left
    val height = bottom - top
    if (width <= 0f || height <= 0f) return
    val corner = CornerRadius(radius, radius)
    if (fill != null) {
        drawRoundRect(color = fill, topLeft = Offset(left, top), size = Size(width, height), cornerRadius = corner)
    }
    if (stroke != null) {
        drawRoundRect(
            color = stroke,
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = corner,
            style = Stroke(
                width = strokeWidth,
                pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f) else null,
            ),
        )
    }
}

/** 一个对勾：两点起点 + 拐点，粗细和端点在这里统一。 */
private fun DrawScope.artCheck(from: Offset, to: Offset, color: Color, strokeWidth: Float) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val path = Path().apply {
        moveTo(from.x, from.y)
        lineTo(to.x, to.y)
        lineTo(to.x + dx * 0.9f, to.y - dy * 1.6f)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** 第 1 页 · 导入照片：一张带边框的相片 + 虚线「+」入口 + 旁边两行读数。 */
@Composable
private fun ImportArt() = ArtBoard { c ->
    val w = size.width
    val h = size.height
    // 相片卡：画面在上半截，下半截是边框的文字区。
    roundedArea(
        left = w * 0.10f,
        top = h * 0.12f,
        right = w * 0.56f,
        bottom = h * 0.88f,
        radius = h * 0.08f,
        fill = c.surface,
        stroke = c.line,
    )
    val inner = Offset(w * 0.17f, h * 0.20f)
    val innerSize = Size(w * 0.32f, h * 0.40f)
    drawCircle(
        color = c.faint,
        radius = innerSize.height * 0.13f,
        center = Offset(inner.x + innerSize.width * 0.26f, inner.y + innerSize.height * 0.26f),
    )
    val hills = Path().apply {
        moveTo(inner.x, inner.y + innerSize.height * 0.88f)
        lineTo(inner.x + innerSize.width * 0.32f, inner.y + innerSize.height * 0.44f)
        lineTo(inner.x + innerSize.width * 0.56f, inner.y + innerSize.height * 0.72f)
        lineTo(inner.x + innerSize.width * 0.76f, inner.y + innerSize.height * 0.54f)
        lineTo(inner.x + innerSize.width, inner.y + innerSize.height * 0.88f)
    }
    drawPath(hills, c.faint, style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    // 边框文字区：两条短线（机型 / 光圈）。
    drawLine(c.line, Offset(inner.x, h * 0.72f), Offset(inner.x + innerSize.width * 0.86f, h * 0.72f), 2f, StrokeCap.Round)
    drawLine(c.line, Offset(inner.x, h * 0.80f), Offset(inner.x + innerSize.width * 0.60f, h * 0.80f), 2f, StrokeCap.Round)

    // 右侧：虚线「+」入口。
    roundedArea(
        left = w * 0.64f,
        top = h * 0.16f,
        right = w * 0.90f,
        bottom = h * 0.50f,
        radius = h * 0.10f,
        stroke = c.accent,
        strokeWidth = 1.6f,
        dash = true,
    )
    val plusX = w * 0.77f
    val plusY = h * 0.33f
    drawLine(c.accent, Offset(plusX - w * 0.05f, plusY), Offset(plusX + w * 0.05f, plusY), 2.4f, StrokeCap.Round)
    drawLine(c.accent, Offset(plusX, plusY - h * 0.09f), Offset(plusX, plusY + h * 0.09f), 2.4f, StrokeCap.Round)
    // 读到的 EXIF：三行。
    drawLine(c.line, Offset(w * 0.64f, h * 0.64f), Offset(w * 0.90f, h * 0.64f), 2f, StrokeCap.Round)
    drawLine(c.line, Offset(w * 0.64f, h * 0.74f), Offset(w * 0.82f, h * 0.74f), 2f, StrokeCap.Round)
    drawLine(c.line, Offset(w * 0.64f, h * 0.84f), Offset(w * 0.86f, h * 0.84f), 2f, StrokeCap.Round)
}

/** 第 2 页 · 套用边框：分类胶囊 + 两行边框格子，第一张已应用（主色 + 勾）。 */
@Composable
private fun BorderArt() = ArtBoard { c ->
    val w = size.width
    val h = size.height
    // 分类行：一格选中，两格未选中。
    var left = w * 0.08f
    listOf(true, false, false).forEach { active ->
        val width = if (active) w * 0.20f else w * 0.16f
        roundedArea(
            left = left,
            top = h * 0.10f,
            right = left + width,
            bottom = h * 0.24f,
            radius = h * 0.07f,
            fill = if (active) c.accent else null,
            stroke = if (active) null else c.line,
        )
        left += width + w * 0.04f
    }
    // 边框格子：第一张是「已应用」。
    val top = h * 0.34f
    val cell = w * 0.20f
    val rowGap = h * 0.16f
    repeat(3) { column ->
        repeat(2) { row ->
            val x = w * 0.08f + column * (cell + w * 0.05f)
            val y = top + row * rowGap
            val selected = column == 0 && row == 0
            roundedArea(
                left = x,
                top = y,
                right = x + cell,
                bottom = y + cell * 0.62f,
                radius = h * 0.05f,
                fill = if (selected) c.accent else c.surface,
                stroke = if (selected) null else c.line,
            )
        }
    }
    // 选中那张的勾。
    val checkX = w * 0.08f + cell * 0.5f
    val checkY = top + cell * 0.30f
    artCheck(
        from = Offset(checkX - cell * 0.16f, checkY),
        to = Offset(checkX, checkY + cell * 0.14f),
        color = c.surface,
        strokeWidth = 2.6f,
    )
}

/** 第 3 页 · 调参数：预览相框 + 三条滑杆 + 底部五格工具条（第一格选中）。 */
@Composable
private fun ParamsArt() = ArtBoard { c ->
    val w = size.width
    val h = size.height
    // 预览相框。
    roundedArea(
        left = w * 0.30f,
        top = h * 0.06f,
        right = w * 0.70f,
        bottom = h * 0.42f,
        radius = h * 0.07f,
        fill = c.surface,
        stroke = c.line,
    )
    roundedArea(
        left = w * 0.35f,
        top = h * 0.11f,
        right = w * 0.65f,
        bottom = h * 0.30f,
        radius = h * 0.04f,
        fill = c.faint,
    )
    drawLine(c.line, Offset(w * 0.38f, h * 0.35f), Offset(w * 0.55f, h * 0.35f), 2f, StrokeCap.Round)
    // 三条滑杆：左边是已改的部分，圆钮是当前值。
    listOf(0.42f, 0.62f, 0.30f).forEachIndexed { index, fraction ->
        val y = h * (0.54f + index * 0.10f)
        val start = w * 0.14f
        val end = w * 0.86f
        drawLine(c.line, Offset(start, y), Offset(end, y), 3f, StrokeCap.Round)
        drawLine(c.accent, Offset(start, y), Offset(start + (end - start) * fraction, y), 3f, StrokeCap.Round)
        val knob = Offset(start + (end - start) * fraction, y)
        drawCircle(c.surface, radius = h * 0.042f, center = knob)
        drawCircle(c.accent, radius = h * 0.042f, center = knob, style = Stroke(width = 2f))
    }
    // 工具条：五格，第一格选中。
    val barTop = h * 0.86f
    val barBottom = h * 0.97f
    roundedArea(
        left = w * 0.06f,
        top = barTop,
        right = w * 0.94f,
        bottom = barBottom,
        radius = h * 0.05f,
        fill = c.surface,
        stroke = c.line,
    )
    val gap = w * 0.176f
    val glyph = minOf(w * 0.05f, h * 0.08f)
    val centerY = (barTop + barBottom) / 2f
    repeat(5) { cell ->
        val x = w * 0.10f + cell * gap
        if (cell == 0) {
            roundedArea(
                left = x - w * 0.014f,
                top = barTop + h * 0.02f,
                right = x + glyph + w * 0.014f,
                bottom = barBottom - h * 0.02f,
                radius = h * 0.04f,
                fill = c.accent,
            )
        }
        roundedArea(
            left = x,
            top = centerY - glyph * 0.5f,
            right = x + glyph,
            bottom = centerY + glyph * 0.5f,
            radius = glyph * 0.25f,
            stroke = if (cell == 0) c.surface else c.ink,
            strokeWidth = 1.6f,
        )
    }
}

/** 第 4 页 · 导出：导出单（格式 / 长边两行选项）+ 出图箭头 + 完成勾。 */
@Composable
private fun ExportArt() = ArtBoard { c ->
    val w = size.width
    val h = size.height
    roundedArea(
        left = w * 0.08f,
        top = h * 0.10f,
        right = w * 0.62f,
        bottom = h * 0.90f,
        radius = h * 0.07f,
        fill = c.surface,
        stroke = c.line,
    )
    // 两行选项：每行三格，第一格选中。
    repeat(2) { row ->
        val y = h * (0.22f + row * 0.30f)
        repeat(3) { column ->
            val chipWidth = w * 0.12f
            val x = w * 0.12f + column * (chipWidth + w * 0.03f)
            val active = column == 0
            roundedArea(
                left = x,
                top = y,
                right = x + chipWidth,
                bottom = y + h * 0.16f,
                radius = h * 0.05f,
                fill = if (active) c.accent else c.surface,
                stroke = if (active) null else c.line,
            )
        }
    }
    // 出图箭头。
    val arrowY = h * 0.5f
    drawLine(c.ink, Offset(w * 0.66f, arrowY), Offset(w * 0.80f, arrowY), 2.4f, StrokeCap.Round)
    drawLine(c.ink, Offset(w * 0.755f, arrowY - h * 0.06f), Offset(w * 0.80f, arrowY), 2.4f, StrokeCap.Round)
    drawLine(c.ink, Offset(w * 0.755f, arrowY + h * 0.06f), Offset(w * 0.80f, arrowY), 2.4f, StrokeCap.Round)
    // 勾：导出完成。
    val center = Offset(w * 0.88f, arrowY)
    drawCircle(c.accent, radius = h * 0.13f, center = center)
    artCheck(
        from = Offset(center.x - h * 0.06f, center.y),
        to = Offset(center.x - h * 0.01f, center.y + h * 0.05f),
        color = c.surface,
        strokeWidth = 2.6f,
    )
}

/** 新手教程的全部文案；纯常量，测试可直接断言。 */
object OnboardingText {
    const val TITLE: String = "新手教程"
    const val SKIP: String = "跳过"
    const val NEXT: String = "下一步"
    const val DONE: String = "开始使用"

    /** 两个入口（手机设置页 / 关于页、桌面齿轮弹层）共用同一个名字。 */
    const val ENTRY: String = "新手教程"
    const val ENTRY_HINT: String = "四步上手：导入 · 边框 · 参数 · 导出"
    const val REOPEN_HINT: String = "随时可以重新看一遍"

    const val SKIP_DESCRIPTION: String = "跳过新手教程，以后不再自动出现"
    const val NEXT_DESCRIPTION: String = "新手教程下一页"
    const val DONE_DESCRIPTION: String = "结束新手教程，开始使用"

    /** 「第 n / 4 页」的读数。 */
    fun counter(page: Int, total: Int): String = "${page + 1} / $total"

    /** 示意图高度：四页同高，换页时卡片不跳。 */
    val ART_HEIGHT: Dp = 132.dp

    /** 说明文字至少两行高，同上。 */
    val BODY_MIN_HEIGHT: Dp = 38.dp

    /** 底部按钮的触摸高度。 */
    val ACTION_MIN_HEIGHT: Dp = 44.dp

    /** 页码圆点直径。 */
    val DOT_SIZE: Dp = 6.dp

    /** 弹层卡片的最大宽度；手机上让位给屏幕宽度。 */
    val CARD_MAX_WIDTH: Dp = 460.dp

    /**
     * 四页的顺序就是用户真实的操作顺序：导入 → 套边框 → 调参数 → 导出。
     * 每页两句，用产品里真实存在的按钮名（选照片 / 系列 / 导出 / 批量），
     * 不写「快来试试」这类空话。
     */
    val PAGES: List<OnboardingPageCopy> = listOf(
        OnboardingPageCopy(
            title = "导入照片",
            body = "从相册选一张，也可以一次多选。读入时会自动读取 EXIF，边框上的机型、光圈、快门都来自这张照片本身。",
        ),
        OnboardingPageCopy(
            title = "套用边框",
            body = "边框库按分类摆好，点一张就应用。「系列」能从品牌直接跳到那一组，也可以在搜索框里打名字。",
        ),
        OnboardingPageCopy(
            title = "调参数",
            body = "底部工具条依次是 边框 / 样式 / 文字 / Logo / 水印。拖动滑杆，上方的照片即时跟着变。",
        ),
        OnboardingPageCopy(
            title = "导出",
            body = "右上角「导出」选格式与长边，存进相册。多张照片一起出，去首页的「批量」。",
        ),
    )
}

/** 一页的标题与说明（配图在 [onboardingPages] 里，与文案分开，测试只钉文案）。 */
data class OnboardingPageCopy(val title: String, val body: String)
