package com.framemark.native.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import com.framemark.native.library.LibraryLoadState
import com.framemark.native.shell.phone.PhoneHomeGear
import com.framemark.native.shell.phone.PhoneHomeHero
import com.framemark.native.shell.phone.PhoneHomeIcon
import com.framemark.native.shell.phone.PhoneHomeRecentCard
import com.framemark.native.shell.phone.PhoneHomeTile
import com.framemark.native.shell.phone.PhoneRecentEdit
import com.framemark.native.resources.Res
import com.framemark.native.resources.youshe_biaoti
import org.jetbrains.compose.resources.Font
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.WorkflowEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 首页 (V2, report §7.1 / demo `03-phone-home.png`).
 *
 * One big card picks photos, 批量 and 预设 are tiles, 最近编辑 lists the project's
 * own photos, and the gear opens 设置 (hand-off part 2 §3 decision 3). The old
 * import-first copy is gone: the report replaced it with this layout, which is
 * why the photo actions now live on the hero card instead of three buttons.
 */
@Composable
fun PhoneHomeScreen(
    libraryState: LibraryLoadState,
    recent: List<PhoneRecentEdit>,
    photoCount: Int,
    onPickPhoto: () -> Unit,
    onPickMany: (() -> Unit)?,
    onUseSample: () -> Unit,
    onOpenSettings: () -> Unit,
    onBatch: () -> Unit,
    onPresets: () -> Unit,
    onOpenRecent: (PhoneRecentEdit) -> Unit,
) {
    val samplePath by produceState<String?>(initialValue = null) {
        value = withContext(Dispatchers.Default) {
            WorkflowEnv.platform?.samplePhotoPath()
        }
    }
    val heroPaths = remember(recent, samplePath) {
        val fromProject = recent.map { it.path }
        (fromProject + listOf(samplePath)).take(3)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FmPalette.canvas)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = FmSpacing.xxl, end = FmSpacing.md, top = FmSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            PhoneHomeGear(onClick = onOpenSettings)
        }
        BrandLockup(
            modifier = Modifier.padding(start = FmSpacing.xxl, end = FmSpacing.xxl, top = FmSpacing.xs),
            markSize = 34.dp,
            titleSize = 26.sp,
        )
        Text(
            text = "给照片加上相机边框与参数水印",
            style = FmType.bodyMedium.copy(fontSize = 13.sp, lineHeight = 20.sp),
            color = FmPalette.onSurfaceSecondary,
            modifier = Modifier.padding(start = FmSpacing.xxl, end = FmSpacing.xxl, top = FmSpacing.sm),
        )
        Spacer(Modifier.height(FmSpacing.xl))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FmSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(FmSpacing.md),
        ) {
            PhoneHomeHero(previewPaths = heroPaths, onPickPhoto = onPickPhoto)
            Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.md)) {
                PhoneHomeTile(
                    icon = PhoneHomeIcon.Grid,
                    title = "批量",
                    subtitle = if (photoCount > 0) "按项目里的照片一起导出" else "多张照片一起加边框",
                    onClick = onBatch,
                    modifier = Modifier.weight(1f),
                )
                PhoneHomeTile(
                    icon = PhoneHomeIcon.Star,
                    title = "预设",
                    subtitle = "把保存好的组合套到这张照片",
                    onClick = onPresets,
                    modifier = Modifier.weight(1f),
                )
            }
            if (recent.isNotEmpty()) {
                PhoneHomeRecentCard(
                    entries = recent,
                    total = photoCount,
                    onOpen = onOpenRecent,
                )
            }
            // 示例照片 + 文件导入 stay one tap away for an empty project; the demo
            // home only advertises the album card, so they sit below it.
            Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.md)) {
                HomeGhostButton(
                    text = "使用示例照片",
                    icon = HomeIcon.Sparkle,
                    onClick = onUseSample,
                    modifier = Modifier.weight(1f),
                )
                if (onPickMany != null) {
                    HomeGhostButton(
                        text = "从文件夹导入",
                        icon = HomeIcon.Folder,
                        onClick = onPickMany,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.height(FmSpacing.lg))
        HomeFooter(libraryState)
    }
}

/** 框印 / FrameMark lockup shared by the home header and the editor top bar. */
@Composable
fun BrandLockup(
    modifier: Modifier = Modifier,
    markSize: Dp = 32.dp,
    titleSize: TextUnit = 18.sp,
) {
    // Old `.brand-text strong` uses the bundled YouShe display face.
    val brandFont = FontFamily(Font(Res.font.youshe_biaoti))
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(markSize)
                .clip(RoundedCornerShape(markSize * 0.28f))
                .background(FmPalette.primarySoft),
            contentAlignment = Alignment.Center,
        ) {
            BrandFrameGlyph(
                tint = FmPalette.primary,
                modifier = Modifier.size(markSize * 0.58f),
            )
        }
        Spacer(Modifier.width(FmSpacing.sm))
        Column {
            Text(
                text = "框印",
                style = FmType.titleMedium.copy(
                    fontSize = titleSize,
                    fontWeight = FontWeight.Normal,
                    fontFamily = brandFont,
                ),
                color = FmPalette.onSurface,
            )
            Text(
                text = "FRAMEMARK",
                style = FmType.labelSmall.copy(
                    fontSize = (titleSize.value * 0.42f).sp,
                    letterSpacing = 2.6.sp,
                ),
                color = FmPalette.onSurfaceMuted,
            )
        }
    }
}

/** Corner-bracket frame mark (FrameMark's own glyph, not a vendor asset). */
@Composable
fun BrandFrameGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val strokeWidth = w * 0.075f
        val arm = w * 0.19f
        val edge = w * 0.13f
        // Four corner brackets pointing inward.
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(tint, Offset(x, y + dy * arm), Offset(x, y), strokeWidth, StrokeCap.Round)
            drawLine(tint, Offset(x, y), Offset(x + dx * arm, y), strokeWidth, StrokeCap.Round)
        }
        corner(edge, edge, 1f, 1f)
        corner(w - edge, edge, -1f, 1f)
        corner(edge, w - edge, 1f, -1f)
        corner(w - edge, w - edge, -1f, -1f)
        // Faint inner frame.
        val inner = w * 0.36f
        drawRoundRect(
            color = tint.copy(alpha = 0.5f),
            topLeft = Offset(inner, inner),
            size = Size(w - inner * 2f, w - inner * 2f),
            cornerRadius = CornerRadius(w * 0.06f),
            style = Stroke(width = w * 0.05f),
        )
    }
}

private enum class HomeIcon { Camera, Folder, Sparkle }

@Composable
private fun HomeGhostButton(
    text: String,
    icon: HomeIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // `.import-secondary-btn`: 48px tall, 12.5px label, 7px icon gap.
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outlineStrong, RoundedCornerShape(FmRadii.pill))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HomeGlyph(icon = icon, tint = FmPalette.onSurfaceSecondary)
            Spacer(Modifier.width(7.dp))
            Text(
                text = text,
                style = FmType.titleSmall.copy(fontSize = 12.5.sp),
                color = FmPalette.onSurfaceSecondary,
            )
        }
    }
}

@Composable
private fun HomeGlyph(icon: HomeIcon, tint: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val w = size.width
        val stroke = 1.6.dp.toPx()
        when (icon) {
            HomeIcon.Camera -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.08f, w * 0.28f),
                    size = Size(w * 0.84f, w * 0.5f),
                    cornerRadius = CornerRadius(w * 0.12f),
                    style = Stroke(width = stroke),
                )
                drawCircle(tint, radius = w * 0.16f, center = Offset(w * 0.5f, w * 0.54f), style = Stroke(stroke))
                drawLine(
                    tint,
                    Offset(w * 0.33f, w * 0.28f),
                    Offset(w * 0.33f, w * 0.2f),
                    stroke,
                    StrokeCap.Round,
                )
                drawLine(
                    tint,
                    Offset(w * 0.33f, w * 0.2f),
                    Offset(w * 0.62f, w * 0.2f),
                    stroke,
                    StrokeCap.Round,
                )
            }
            HomeIcon.Folder -> {
                val path = Path().apply {
                    moveTo(w * 0.1f, w * 0.3f)
                    lineTo(w * 0.4f, w * 0.3f)
                    lineTo(w * 0.48f, w * 0.4f)
                    lineTo(w * 0.9f, w * 0.4f)
                    lineTo(w * 0.9f, w * 0.8f)
                    lineTo(w * 0.1f, w * 0.8f)
                    close()
                }
                drawPath(path, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
            }
            HomeIcon.Sparkle -> {
                val path = Path().apply {
                    moveTo(w * 0.5f, w * 0.1f)
                    lineTo(w * 0.6f, w * 0.4f)
                    lineTo(w * 0.9f, w * 0.5f)
                    lineTo(w * 0.6f, w * 0.6f)
                    lineTo(w * 0.5f, w * 0.9f)
                    lineTo(w * 0.4f, w * 0.6f)
                    lineTo(w * 0.1f, w * 0.5f)
                    lineTo(w * 0.4f, w * 0.4f)
                    close()
                }
                drawPath(path, color = tint)
            }
        }
    }
}

@Composable
private fun HomeFooter(libraryState: LibraryLoadState) {
    val text = remember(libraryState) {
        when (libraryState) {
            is LibraryLoadState.Ready -> {
                val snapshot = libraryState.snapshot
                "${snapshot.categories.size} 类边框 · ${snapshot.cards.size} 套 · $DISPLAY_LOGO_BRANDS 个品牌"
            }
            LibraryLoadState.Loading -> "正在载入边框库…"
            is LibraryLoadState.Failed -> "边框库载入失败"
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FmSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = FmType.labelMedium.copy(fontSize = 11.sp, letterSpacing = 0.88.sp),
            color = FmPalette.onSurfaceMuted,
        )
    }
}

/**
 * Displayed logo brands (LUMO→OPPO and LUMIX→Panasonic folded). Same figure the
 * old import screen printed from `DISPLAY_LOGO_BRAND_COUNT`; the brand catalog is
 * not bundled into the Compose UI, so this is the shipped constant.
 */
private const val DISPLAY_LOGO_BRANDS = 61

/** Shared outline for the editor's small round affordances. */
@Composable
internal fun RoundChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(if (enabled) FmPalette.surface else Color.Transparent)
            .border(
                1.dp,
                if (enabled) FmPalette.outline else FmPalette.outline.copy(alpha = 0.5f),
                RoundedCornerShape(FmRadii.pill),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
