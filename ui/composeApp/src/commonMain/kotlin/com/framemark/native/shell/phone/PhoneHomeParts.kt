package com.framemark.native.shell.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmElevation
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.fmElevated
import com.framemark.native.workflow.PhotoThumbnail

/**
 * 首页的零件，尺寸逐条照 `styles.css` 的 `.home` 段与 `phone.js buildHome()`
 * （CSS px → dp 1:1；演示稿的 `.screen` 是 394 px 宽，本机 411 dp）。
 *
 * 颜色一律取 [FmPalette] 令牌，深浅两套自动跟着走；卡片圆角 20 与 `.hcard`
 * 一致（[FmRadii] 里没有 20，见报告「令牌缺口」）。
 */
private val HomeCardRadius = 20.dp
private val HomeCardShape = RoundedCornerShape(HomeCardRadius)
private val PrintRadius = 4.dp
private val PrintShape = RoundedCornerShape(PrintRadius)

/** 没有尺寸信息时的兜底长宽比。 */
private const val FOUR_THREE = 4f / 3f

/** 成片托：156 宽 / 125 高（演示稿 `fitPrint(…, 156, 140)` 里成片实测约 1.25）。 */
private const val PRINT_ASPECT = 156f / 125f

/** 扇形里的一张照片：路径 + 长宽比（普通照片用 4:3 兜底）。 */
internal data class PhoneHeroPreview(
    val path: String?,
    val aspect: Float = FOUR_THREE,
)

/**
 * 演示稿的字号（`10-spec` 页：18/700 hero 标题、15/700 卡片标题、14/500 行标题、
 * 13 正文、12.5 副文案、12 元信息）。[com.framemark.native.theme.FmType] 里没有
 * 14/500 与 12.5 这两档，所以这里按 PARITY-SPEC §1 的数值就地定义。
 */
private val TextStyleHeroTitle = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
private val TextStyleHeroSub = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)
private val TextStyleHeroAction = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
private val TextStyle15Bold = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
private val TextStyle14Medium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
private val TextStyle12Half = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp)
private val TextStyle12 = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)

/** 首页右上角的齿轮：图标 22，命中区 48（UI 评审 P0-3；视觉仍按演示稿大小）。 */
@Composable
fun PhoneHomeGear(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .semantics {
                contentDescription = PhoneHomeText.SETTINGS_LABEL
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Read the token outside the draw scope: FmPalette members are composable.
        val ink = FmPalette.onSurfaceSecondary
        Canvas(modifier = Modifier.size(22.dp)) {
            val w = size.width
            val tint = ink
            // 齿比辐条粗，读起来才像齿轮而不是太阳（演示稿 `ic('gear', 22)`）。
            val tooth = w * 0.13f
            val teeth = 8
            val outer = w * 0.46f
            val ring = w * 0.27f
            repeat(teeth) { index ->
                val angle = (index * (360f / teeth)) * (kotlin.math.PI.toFloat() / 180f)
                val dx = kotlin.math.cos(angle)
                val dy = kotlin.math.sin(angle)
                drawLine(
                    color = tint,
                    start = Offset(w / 2f + dx * ring, w / 2f + dy * ring),
                    end = Offset(w / 2f + dx * outer, w / 2f + dy * outer),
                    strokeWidth = tooth,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(
                color = tint,
                radius = ring,
                center = Offset(w / 2f, w / 2f),
                style = Stroke(width = w * 0.115f),
            )
        }
    }
}

/**
 * 首页用到的三个字形，形状照演示稿 `ic('batch')` / `ic('star')` / `ic('album')`：
 * 批量是 2×2 圆角方块、预设是描边五角星、选照片是相册（圆角框 + 太阳 + 山）。
 */
internal enum class PhoneHomeIcon { Grid, Star, Album }

@Composable
internal fun PhoneHomeGlyph(icon: PhoneHomeIcon, tint: Color, size: Dp = 20.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = size.toPx()
        val stroke = w * 0.08f
        when (icon) {
            PhoneHomeIcon.Grid -> {
                val cell = w * 0.32f
                val gap = w * 0.14f
                listOf(w * 0.11f, w * 0.11f + cell + gap).forEach { y ->
                    listOf(w * 0.11f, w * 0.11f + cell + gap).forEach { x ->
                        drawRoundRect(
                            color = tint,
                            topLeft = Offset(x, y),
                            size = Size(cell, cell),
                            cornerRadius = CornerRadius(w * 0.08f),
                            style = Stroke(width = stroke),
                        )
                    }
                }
            }
            PhoneHomeIcon.Star -> {
                val path = Path()
                val cx = w / 2f
                val cy = w * 0.52f
                val outer = w * 0.42f
                val innerR = w * 0.17f
                repeat(10) { index ->
                    val radius = if (index % 2 == 0) outer else innerR
                    val angle = (-90f + index * 36f) * (kotlin.math.PI.toFloat() / 180f)
                    val px = cx + kotlin.math.cos(angle) * radius
                    val py = cy + kotlin.math.sin(angle) * radius
                    if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                drawPath(path, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
            }
            PhoneHomeIcon.Album -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.1f, w * 0.16f),
                    size = Size(w * 0.8f, w * 0.68f),
                    cornerRadius = CornerRadius(w * 0.12f),
                    style = Stroke(width = stroke),
                )
                drawCircle(tint, radius = w * 0.075f, center = Offset(w * 0.33f, w * 0.36f))
                val slope = Path().apply {
                    moveTo(w * 0.16f, w * 0.74f)
                    lineTo(w * 0.4f, w * 0.48f)
                    lineTo(w * 0.56f, w * 0.64f)
                    lineTo(w * 0.68f, w * 0.55f)
                    lineTo(w * 0.84f, w * 0.74f)
                }
                drawPath(slope, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
            }
        }
    }
}

/**
 * 首页的一张功能卡（演示稿 `.htile`）：34×34 的圆角图标块 + 15px 标题 + 12.5px 副文案。
 * 两张卡由调用方用 `IntrinsicSize.Max` 拉齐高度。
 */
@Composable
internal fun PhoneHomeTile(
    icon: PhoneHomeIcon,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(HomeCardShape)
            .background(FmPalette.surface)
            .semantics {
                contentDescription = "$title，$subtitle"
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(FmRadii.tile))
                .background(FmPalette.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            PhoneHomeGlyph(icon = icon, tint = FmPalette.onSurfaceSecondary, size = 20.dp)
        }
        Text(
            text = title,
            style = TextStyle15Bold,
            color = FmPalette.onSurface,
        )
        Text(
            text = subtitle,
            style = TextStyle12Half,
            color = FmPalette.onSurfaceMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 最近编辑卡（演示稿 `.hcard.hlist`）：标题 + 右上「全部」，下面是每张照片一行
 * （56×44 缩略图 / 文件名 / 边框·主题 / 序号）。
 */
@Composable
internal fun PhoneHomeRecentCard(
    entries: List<PhoneRecentEdit>,
    onOpen: (PhoneRecentEdit) -> Unit,
    onOpenAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(HomeCardShape)
            .background(FmPalette.surface)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = PhoneHomeText.RECENT_TITLE,
                style = TextStyle15Bold,
                color = FmPalette.onSurface,
            )
            Spacer(Modifier.weight(1f))
            // 文案写明「继续最近编辑」：这个链接只打开最近一张，不做全量列表。
            // 命中区撑到 44 dp，文字本身不用变大。
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(FmRadii.badge))
                    .clickable(role = Role.Button, onClick = onOpenAll)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = PhoneHomeText.RECENT_ALL,
                    style = TextStyle12Half.copy(fontWeight = FontWeight.Medium),
                    color = FmPalette.primary,
                )
            }
        }
        entries.forEachIndexed { index, entry ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(FmPalette.outline),
                )
            }
            PhoneHomeRecentRow(entry = entry, onClick = { onOpen(entry) })
        }
    }
}

@Composable
private fun PhoneHomeRecentRow(entry: PhoneRecentEdit, onClick: () -> Unit) {
    val meta = PhoneHomeText.recentPosition(entry)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "打开 ${entry.displayName}"
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 44.dp)
                .clip(RoundedCornerShape(FmRadii.chip))
                .background(FmPalette.surfaceMuted),
        ) {
            PhotoThumbnail(
                path = entry.path,
                maxEdge = 160,
                cornerRadius = FmRadii.chip,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = TextStyle14Medium,
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = PhoneHomeText.recentSubtitle(entry),
                style = TextStyle12Half,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (meta.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = meta,
                style = TextStyle12,
                color = FmPalette.onSurfaceMuted,
            )
        }
    }
}

/**
 * 选照片 hero 卡（演示稿 `.hcard.hero`）：150 高的扇形叠放照片、18/700 标题、
 * 13 的说明，右边是 40 高的蓝色胶囊「选照片」。
 *
 * [previews] 是项目里最新的照片（最多三张，带各自长宽比）；空缺的位置由调用方用
 * 内置样张补齐，所以空项目也能画出演示稿那样的三张叠放。
 */
@Composable
internal fun PhoneHomeHero(
    previews: List<PhoneHeroPreview>,
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val slots = List(3) { index -> previews.getOrNull(index) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(HomeCardShape)
            .background(FmPalette.surface)
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 后两张往两侧歪出去，前面一张正着放，和演示稿的 transform 一致。
            HeroFanPhoto(slot = slots[1], width = 96.dp, rotation = -10f, offsetX = (-70).dp)
            HeroFanPhoto(slot = slots[2], width = 92.dp, rotation = 9f, offsetX = 68.dp)
            HeroPrintPhoto(path = slots[0]?.path, width = 156.dp)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = PhoneHomeText.HERO_TITLE,
                    style = TextStyleHeroTitle,
                    color = FmPalette.onSurface,
                )
                Text(
                    text = PhoneHomeText.HERO_SUBTITLE,
                    style = TextStyleHeroSub,
                    color = FmPalette.onSurfaceMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.primary)
                    .semantics {
                        contentDescription = PhoneHomeText.HERO_ACTION
                        role = Role.Button
                    }
                    .clickable { onPickPhoto() }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhoneHomeGlyph(
                    icon = PhoneHomeIcon.Album,
                    tint = FmPalette.onPrimary,
                    size = 18.dp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = PhoneHomeText.HERO_ACTION,
                    style = TextStyleHeroAction,
                    color = FmPalette.onPrimary,
                )
            }
        }
    }
}

/** 扇形里后面的一张：白底照片卡，按照片自己的长宽比歪着放。 */
@Composable
private fun HeroFanPhoto(slot: PhoneHeroPreview?, width: Dp, rotation: Float, offsetX: Dp) {
    Box(
        modifier = Modifier
            .offset(x = offsetX)
            .rotate(rotation)
            .width(width)
            .height(width / (slot?.aspect ?: FOUR_THREE))
            .fmElevated(level = FmElevation.nav, shape = PrintShape)
            .clip(PrintShape)
            .background(FmPalette.surface),
    ) {
        PhotoThumbnail(
            path = slot?.path,
            modifier = Modifier.fillMaxSize(),
            maxEdge = 256,
            cornerRadius = PrintRadius,
        )
    }
}

/**
 * 扇形最前面的那张：白卡"照片托"。演示稿这里是真的成片（照片 + 边框 + 参数行，
 * 156 宽、约 125 高，底部一条说明带）；原生首页不跑引擎，所以用白托和留白
 * 占住同一块版面。
 */
@Composable
private fun HeroPrintPhoto(path: String?, width: Dp) {
    Column(
        modifier = Modifier
            .width(width)
            .height(width / PRINT_ASPECT)
            .fmElevated(level = FmElevation.nav, shape = PrintShape)
            .clip(PrintShape)
            .background(FmPalette.surface)
            .padding(PrintRadius),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(2.dp)),
        ) {
            PhotoThumbnail(
                path = path,
                modifier = Modifier.fillMaxSize(),
                maxEdge = 480,
                cornerRadius = 2.dp,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}
