package com.framemark.native.shell.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framemark.native.library.LibraryCard
import com.framemark.native.theme.LocalFmDark
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii

/** 卡片缩略图底板 `--plate-2`（浅 #EDF0F3 / 深 #26282D）。
 *  深色有同值令牌；浅色没有，用 `--line` 在 surface 上以 62% 混合得到 (238,240,243)，
 *  与演示稿的 (237,240,243) 只差 1/255（见报告“缺令牌”一节）。 */
@Composable
internal fun desktopCardPlate(): Color = if (LocalFmDark.current) {
    FmPalette.surfaceMuted
} else {
    FmPalette.outline.copy(alpha = 0.62f).compositeOver(FmPalette.surface)
}

/** 卡片选中环（styles.css `.card.on .th`：`0 0 0 2px accent, 0 0 0 5px ring`）。 */
private val SelectionRingWidth = 5.dp
private val SelectionAccentWidth = 2.dp

/**
 * 演示稿左栏的一张卡片：5:4 缩略图 + 左上徽标 + 右上勾选 + 名称 + 副行。
 *
 * 缩略图内容是**当前照片**套该边框（[DesktopLibraryThumb]）；悬停上报给面板做 0.4 s
 * 延迟预览，选中态由 [selected] 决定。
 */
@Composable
internal fun DesktopLibraryCard(
    card: LibraryCard,
    selected: Boolean,
    photoPath: String?,
    subtitle: String,
    onClick: () -> Unit,
    onHoverChange: (Boolean) -> Unit,
) {
    var hovered by remember(card.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(FmRadii.chip)
    val plate = desktopCardPlate()
    val badge = desktopCardBadge(card)
    val accentW = SelectionAccentWidth
    val ringW = SelectionRingWidth
    val ringColor = FmPalette.ring
    val accentColor = FmPalette.primary
    val hoverEdge = FmPalette.outlineStrong
    val imageScale by animateFloatAsState(if (hovered) 1.035f else 1f, label = "thumbScale")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .pointerInput(card.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> {
                                hovered = true
                                onHoverChange(true)
                            }
                            PointerEventType.Exit -> {
                                hovered = false
                                onHoverChange(false)
                            }
                        }
                    }
                }
            }
            .semantics {
                contentDescription = "边框：${card.name}"
                role = Role.Button
                this.selected = selected
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(5f / 4f)
                .drawBehind {
                    if (selected) {
                        val r = ringW.toPx()
                        drawRoundRect(
                            color = ringColor,
                            topLeft = Offset(-r / 2f, -r / 2f),
                            size = Size(size.width + r, size.height + r),
                            cornerRadius = CornerRadius(8.dp.toPx() + r / 2f),
                            style = Stroke(width = r),
                        )
                        val a = accentW.toPx()
                        drawRoundRect(
                            color = accentColor,
                            topLeft = Offset(-a / 2f, -a / 2f),
                            size = Size(size.width + a, size.height + a),
                            cornerRadius = CornerRadius(8.dp.toPx() + a / 2f),
                            style = Stroke(width = a),
                        )
                    } else if (hovered) {
                        val edge = 1.dp.toPx()
                        drawRoundRect(
                            color = hoverEdge,
                            topLeft = Offset(-edge / 2f, -edge / 2f),
                            size = Size(size.width + edge, size.height + edge),
                            cornerRadius = CornerRadius(8.dp.toPx() + edge / 2f),
                            style = Stroke(width = edge),
                        )
                    }
                }
                .clip(shape)
                .background(plate),
        ) {
            Box(Modifier.align(Alignment.Center).fillMaxSize()) {
                // 图 88% contain（styles.css `.card .th img`），四周留出底板色。
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    DesktopLibraryThumb(
                        card = card,
                        photoPath = photoPath,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(maxWidth * 0.88f)
                            .height(maxHeight * 0.88f)
                            .graphicsLayer {
                                scaleX = imageScale
                                scaleY = imageScale
                            }
                            .shadow(DesktopCardImageShadow, clip = false),
                    )
                }
            }
            if (badge != null) {
                DesktopCardBadge(text = badge, modifier = Modifier.align(Alignment.TopStart).offset(6.dp, 6.dp))
            }
            if (selected) {
                DesktopCardCheck(plate = plate, modifier = Modifier.align(Alignment.TopEnd).offset((-4).dp, (-4).dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = desktopCardName(card),
            style = DesktopLibraryType.cardName,
            color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            // V2 副行（`.card .ds`）：`类别 · 子类` / `品牌 · 系列` / `N 张照片在用`。
            text = subtitle,
            style = DesktopLibraryType.cardDesc,
            color = FmPalette.onSurfaceFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        )
    }
}

/**
 * 缩略图左上角徽标（`.card .bdg`）：h18、圆角 5、10.5/600。
 *
 * 热门 = 橙、原版/实测 = 青、官方规格/官方素材 = accent；演示稿用的是字面色
 * `#C2410C` / `#0F766E`，令牌里没有同值色位，这里用最接近的 warning / success
 * （差异已登记，等 `theme` 包补 badge 色位后换掉）。
 */
@Composable
private fun DesktopCardBadge(text: String, modifier: Modifier = Modifier) {
    val dark = LocalFmDark.current
    val ink = when (text) {
        "热门" -> FmPalette.warning
        "原版", "实测" -> FmPalette.success
        "官方规格", "官方素材" -> FmPalette.primaryStrong
        else -> FmPalette.onSurfaceSecondary
    }
    val background = if (dark) FmPalette.surfaceMuted.copy(alpha = 0.94f) else FmPalette.surface.copy(alpha = 0.92f)
    val edge = if (dark) FmPalette.outlineStrong else Color.Transparent
    Box(
        modifier = modifier
            .height(18.dp)
            .shadow(if (dark) 0.dp else 1.dp, RoundedCornerShape(5.dp), clip = false)
            .clip(RoundedCornerShape(5.dp))
            .background(background)
            .then(if (dark) Modifier.drawBehind {
                drawRoundRect(
                    color = edge,
                    cornerRadius = CornerRadius(5.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx()),
                )
            } else Modifier)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = DesktopLibraryType.badge,
            color = ink,
            maxLines = 1,
        )
    }
}

/** 选中勾选圆点（`.card .ck`）：20px accent 圆 + 2px 底板色描边 + 白勾。 */
@Composable
private fun DesktopCardCheck(plate: Color, modifier: Modifier = Modifier) {
    val accent = FmPalette.primary
    Canvas(modifier = modifier.size(24.dp)) {
        val r = 10.dp.toPx()
        drawCircle(color = plate, radius = r + 2.dp.toPx())
        drawCircle(color = accent, radius = r)
        val s = 1.3.dp.toPx()
        val path = Path().apply {
            moveTo(center.x - 5.dp.toPx(), center.y + 0.4.dp.toPx())
            lineTo(center.x - 1.2.dp.toPx(), center.y + 4.dp.toPx())
            lineTo(center.x + 5.2.dp.toPx(), center.y - 4.dp.toPx())
        }
        drawPath(
            path = path,
            color = Color.White,
            style = Stroke(width = s, cap = StrokeCap.Round),
        )
    }
}
