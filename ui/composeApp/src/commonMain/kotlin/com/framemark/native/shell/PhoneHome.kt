package com.framemark.native.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.resources.Res
import com.framemark.native.resources.youshe_biaoti
import com.framemark.native.shell.phone.PhoneHomeGear
import com.framemark.native.shell.phone.PhoneHomeHero
import com.framemark.native.shell.phone.PhoneHomeIcon
import com.framemark.native.shell.phone.PhoneHomeRecentCard
import com.framemark.native.shell.phone.PhoneHomeText
import com.framemark.native.shell.phone.PhoneHomeTile
import com.framemark.native.shell.phone.PhoneHeroPreview
import com.framemark.native.shell.phone.PhoneRecentEdit
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.WorkflowEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.Font

/**
 * 首页，逐条对齐演示稿 `phone.js buildHome()`（25-52 行）与 `03-phone-home.png`。
 *
 * 版面：浅灰画布，右上齿轮；「框印」32/700 词标 + 一行说明；白色 hero 卡
 * （扇形叠放照片 + 蓝色「选照片」胶囊）；两张功能卡（批量 / 预设）；「最近编辑」
 * 卡（右上「全部」+ 每张照片一行）。V06 的「导入照片」大图和三颗大按钮已经去掉：
 * 选照片回到 hero 卡上，示例照片与文件夹导入不再是首页入口（见报告 §3 差异登记）。
 */
@Composable
fun PhoneHomeScreen(
    recent: List<PhoneRecentEdit>,
    presetCount: Int,
    onPickPhoto: () -> Unit,
    onOpenSettings: () -> Unit,
    onBatch: () -> Unit,
    onPresets: () -> Unit,
    onOpenRecent: (PhoneRecentEdit) -> Unit,
    onOpenAll: () -> Unit,
) {
    val samplePath by produceState<String?>(initialValue = null) {
        value = withContext(Dispatchers.Default) {
            WorkflowEnv.platform?.samplePhotoPath()
        }
    }
    val heroPreviews = remember(recent, samplePath) {
        val fromProject = recent.map { PhoneHeroPreview(path = it.path, aspect = it.aspectRatio()) }
        // 空位用内置样张补满三张：演示稿的扇形永远是三张照片，留空会变成灰块。
        val fallback = PhoneHeroPreview(path = samplePath)
        List(3) { index -> fromProject.getOrNull(index) ?: fallback }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FmPalette.canvas)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // `.htop`：44 高、右对齐；齿轮自己 32×32，左右再让 8。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhoneHomeGear(onClick = onOpenSettings)
        }
        // `.home h1`：32/700，外边距 6 4 2。
        Text(
            text = PhoneHomeText.WORDMARK,
            style = TextStyle(
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = FmPalette.onSurface,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        )
        // `.hsub`：14 / ink-3，外边距 0 4 10。
        Text(
            text = PhoneHomeText.SUBTITLE,
            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            color = FmPalette.onSurfaceMuted,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        )
        PhoneHomeHero(previews = heroPreviews, onPickPhoto = onPickPhoto)
        // `.htiles`：两列等宽、间距 12，两张卡拉齐高度。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PhoneHomeTile(
                icon = PhoneHomeIcon.Grid,
                title = PhoneHomeText.BATCH_TITLE,
                subtitle = PhoneHomeText.BATCH_SUBTITLE,
                onClick = onBatch,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            PhoneHomeTile(
                icon = PhoneHomeIcon.Star,
                title = PhoneHomeText.PRESETS_TITLE,
                subtitle = PhoneHomeText.presetSubtitle(presetCount),
                onClick = onPresets,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
        if (recent.isNotEmpty()) {
            PhoneHomeRecentCard(
                entries = recent,
                onOpen = onOpenRecent,
                onOpenAll = onOpenAll,
            )
        }
    }
}

/** 框印 / FrameMark lockup shared by the editor top bar (the home uses the plain 词标). */
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
                text = PhoneHomeText.WORDMARK,
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
