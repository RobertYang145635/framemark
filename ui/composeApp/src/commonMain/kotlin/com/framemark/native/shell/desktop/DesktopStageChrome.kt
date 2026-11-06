package com.framemark.native.shell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.framemark.native.theme.FmColors
import com.framemark.native.theme.FmDesktop
import com.framemark.native.theme.FmNumeric
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmStageChrome
import com.framemark.native.theme.FmType
import com.framemark.native.theme.LocalAppearance
import com.framemark.native.theme.LocalFmColors
import com.framemark.native.theme.StageTone

/** 舞台底色：读 WP-T 的外观设置，三档（深灰 / 中灰 / 浅灰）。 */
@Composable
fun desktopStageBackdrop(): Color =
    LocalFmColors.current.stageColor(LocalAppearance.current.settings.stageTone)

private fun stageToneLabel(tone: StageTone): String = when (tone) {
    StageTone.Dark -> "深灰"
    StageTone.Mid -> "中灰"
    StageTone.Light -> "浅灰"
}

/**
 * 舞台左上角 meta（`styles.css` 358-361 `.stage .meta`）：`left:16 top:14`，gap 10。
 *
 * 三段：文件名（12 / 500 / `--font` / 白 86 %）、原尺寸（12 Bahnschrift / 白 62 %）、
 * 「→ 导出 W × H」（同字号）。数值一律 [FmNumeric.value]，所以换照片时数字不抖。
 */
@Composable
fun DesktopStageMeta(
    fileName: String,
    originalSize: String?,
    exportSize: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(start = FmDesktop.stageMetaStart, top = FmDesktop.stageMetaTop),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = fileName,
            style = FmType.desktopCaption.copy(fontWeight = FontWeight.Medium),
            color = FmPalette.stageInk,
            maxLines = 1,
        )
        if (originalSize != null) {
            Spacer(Modifier.width(10.dp))
            Text(text = originalSize, style = FmNumeric.value, color = FmPalette.stageMuted, maxLines = 1)
        }
        if (exportSize != null) {
            Spacer(Modifier.width(10.dp))
            Text(text = "→ 导出 $exportSize", style = FmNumeric.value, color = FmPalette.stageMuted, maxLines = 1)
        }
    }
}

/** 舞台右上角状态胶囊的三种点：实时（绿）/ 静态样张（琥珀）/ 忙（无点）。 */
enum class DesktopStageStatusKind { Live, Static, Busy }

/**
 * 舞台右上角状态胶囊（`styles.css` 362-366 `.status`）。
 *
 * `right:14 top:12`、高 26、圆角 999、底 `rgba(20,21,24,.55)`、12 px、7 px 圆点 +
 * 3 px 光晕。文案由调用点按状态给：实时预览 / 渲染中… / 原图 / 未选边框 / 引擎样张。
 */
@Composable
fun DesktopStageStatusTag(
    text: String,
    kind: DesktopStageStatusKind,
    modifier: Modifier = Modifier,
) {
    val dot = when (kind) {
        DesktopStageStatusKind.Live -> FmPalette.success
        DesktopStageStatusKind.Static -> FmPalette.warning
        DesktopStageStatusKind.Busy -> null
    }
    Row(
        modifier = modifier
            .padding(end = FmDesktop.stageStatusEnd, top = FmDesktop.stageStatusTop)
            .height(FmDesktop.stageStatusHeight)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmStageChrome.pill)
            .padding(horizontal = 10.dp)
            .semantics { contentDescription = "舞台状态：$text" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            // 7 px 实体 + 3 px 光晕（`.status i { box-shadow: 0 0 0 3px rgba(61,214,140,.18) }`）。
            Box(
                modifier = Modifier.size(13.dp).padding(3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            }
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = text,
            style = FmType.desktopCaption,
            color = FmPalette.stageInk,
            maxLines = 1,
        )
    }
}

/**
 * 「原图 · 松开恢复」提示（`styles.css` 378-379 `.compare-flag`）：顶部居中、
 * 白色 92 % 药丸、12 / 600、padding 4×10。只在按住对比时出现。
 */
@Composable
fun DesktopStageCompareFlag(modifier: Modifier = Modifier) {
    Text(
        text = "原图 · 松开恢复",
        style = FmType.desktopCaption.copy(fontWeight = FontWeight.SemiBold),
        color = FmStageChrome.flagInk,
        maxLines = 1,
        modifier = modifier
            .padding(top = 14.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmStageChrome.flagSurface)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * 舞台底部悬浮工具条（`styles.css` 368-377 `.stage-tb`）。
 *
 * 从左到右：`−`、缩放读数（`适合` 或 `NN%`，min-width 46，点它回适合窗口）、`+`、
 * 分隔线、`原图`（按住看原图）、分隔线、三个灰阶圆点。
 *
 * 演示稿 `desktop.js` 里的第四档「棋盘格」底色需要 `StageTone` 新增枚举值
 * （在 `theme\Appearance*` 里，不属本包），PARITY-SPEC §2C 也只写了三个灰阶点，
 * 所以这里就是三档。
 */
@Composable
fun DesktopStageToolbar(
    zoomLabel: String,
    zoomDescription: String,
    compareActive: Boolean,
    compareEnabled: Boolean,
    tone: StageTone,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onZoomIn: () -> Unit,
    onCompareHold: (Boolean) -> Unit,
    onSelectTone: (StageTone) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(FmDesktop.stageToolbarRadius)
    val ink = FmPalette.stageInk
    Row(
        modifier = modifier
            .padding(bottom = FmDesktop.stageToolbarBottom)
            .clip(shape)
            .background(FmStageChrome.bar)
            .border(1.dp, FmStageChrome.barOutline, shape)
            .padding(FmDesktop.stageToolbarInset),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DesktopStageToolButton(
            description = "缩小",
            ink = ink,
            onClick = onZoomOut,
        ) { DeskPlusMinusGlyph(plus = false, tint = ink) }
        DesktopStageZoomLabel(
            label = zoomLabel,
            description = zoomDescription,
            onClick = onFit,
        )
        DesktopStageToolButton(
            description = "放大",
            ink = ink,
            onClick = onZoomIn,
        ) { DeskPlusMinusGlyph(plus = true, tint = ink) }
        DesktopStageToolbarSeparator()
        DesktopStageCompareTool(
            active = compareActive,
            enabled = compareEnabled,
            onHoldChange = onCompareHold,
        )
        DesktopStageToolbarSeparator()
        StageToneDots(current = tone, onSelect = onSelectTone)
    }
}

/** 工具条按钮：h30 / min-w30 / 圆角 8 / padding 0 8，hover `白 10 %`，按下 `白 16 %`。 */
@Composable
private fun DesktopStageToolButton(
    description: String,
    ink: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val (source, hovered) = rememberDeskHover()
    Box(
        modifier = Modifier
            .height(FmDesktop.stageToolbarHeight)
            .widthIn(min = FmDesktop.stageToolbarMinWidth)
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(if (hovered) FmStageChrome.barHover else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 缩放读数：`500 12px var(--num)`、min-width 46、居中；点击回适合窗口。 */
@Composable
private fun DesktopStageZoomLabel(
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    val (source, hovered) = rememberDeskHover()
    Box(
        modifier = Modifier
            .height(FmDesktop.stageToolbarHeight)
            .width(FmDesktop.stageZoomLabelWidth)
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(if (hovered) FmStageChrome.barHover else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = FmNumeric.value.copy(fontWeight = FontWeight.Medium), color = FmPalette.stageInk, maxLines = 1)
    }
}

/** `.stage-tb .sep`：`1×18`、`白 14 %`、左右 margin 4（用 2 dp 的 gap + 4 dp 的 spacer 近似）。 */
@Composable
private fun DesktopStageToolbarSeparator() {
    Spacer(Modifier.width(2.dp))
    Box(
        Modifier
            .width(1.dp)
            .height(FmDesktop.stageToolbarSeparatorHeight)
            .background(FmStageChrome.barSeparator),
    )
    Spacer(Modifier.width(2.dp))
}

/** `原图` + 对比图标；按住显示原图，松开恢复（`.tb` 加 `ic('compare')`）。 */
@Composable
private fun DesktopStageCompareTool(
    active: Boolean,
    enabled: Boolean,
    onHoldChange: (Boolean) -> Unit,
) {
    val currentOnHoldChange by rememberUpdatedState(onHoldChange)
    val (source, hovered) = rememberDeskHover(enabled)
    val ink = if (enabled) FmPalette.stageInk else FmPalette.stageMuted
    Box(
        modifier = Modifier
            .height(FmDesktop.stageToolbarHeight)
            .widthIn(min = FmDesktop.stageToolbarMinWidth)
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(
                when {
                    active -> FmStageChrome.barPressed
                    hovered -> FmStageChrome.barHover
                    else -> Color.Transparent
                },
            )
            .hoverable(source, enabled)
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                currentOnHoldChange(true)
                                tryAwaitRelease()
                                currentOnHoldChange(false)
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .semantics {
                contentDescription = "按住查看原图"
                role = Role.Button
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeskCompareGlyph(tint = ink)
            Spacer(Modifier.width(6.dp))
            Text(text = "原图", style = FmType.desktopChip, color = ink, maxLines = 1)
        }
    }
}

/**
 * 三个灰阶圆点（`.tones button` 16 px，内描边 `白 25 %`；选中加
 * `0 0 0 2px 深底, 0 0 0 3.5px 白`）。圆点颜色就是 [FmColors.stageColor]，所以
 * 三个点和舞台实际会画出来的底色完全一致。
 */
@Composable
private fun StageToneDots(current: StageTone, onSelect: (StageTone) -> Unit) {
    val colors = LocalFmColors.current
    val ring = FmStageChrome.toneSelected
    val gap = FmStageChrome.bar
    Row(
        modifier = Modifier.padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StageTone.entries.forEach { tone ->
            val selected = tone == current
            val fill = colors.stageColor(tone)
            Box(
                modifier = Modifier
                    .size(FmDesktop.stageToneDotSize)
                    .drawBehind {
                        if (!selected) return@drawBehind
                        val radius = size.minDimension / 2f
                        drawCircle(gap, radius + 1.25.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                        drawCircle(ring, radius + 3.5.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                    }
                    .clip(CircleShape)
                    .background(fill)
                    .border(1.dp, FmStageChrome.toneOutline, CircleShape)
                    .clickable(role = Role.Button, onClick = { onSelect(tone) })
                    .semantics { contentDescription = "舞台底色 ${stageToneLabel(tone)}" },
            )
        }
    }
}
