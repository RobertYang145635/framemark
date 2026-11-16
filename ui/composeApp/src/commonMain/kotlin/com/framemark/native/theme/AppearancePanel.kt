package com.framemark.native.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 外观设置面板：界面配色（四张预览卡）、明暗模式、画布背景，外加「恢复默认外观」
 * 和保存失败提示（配色方案 §8）。
 *
 * 双端共用：手机设置页 2 列（411 dp 屏），桌面齿轮弹层也是 2 列。卡片比例、迷你界面
 * 结构和文案在两种宽度下完全一致，所以比较的是颜色而不是版式。
 *
 * **不用 `BoxWithConstraints` / Lazy 容器**：桌面把它放进 `DropdownMenu`，而
 * DropdownMenu 会用固有宽度测量内容；SubcomposeLayout 系（BoxWithConstraints、
 * LazyColumn、TabRow…）不支持固有测量，一打开弹层就抛
 * "Asking for intrinsic measurements of SubcomposeLayout layouts is not supported"。
 * 列数由调用方给（手机/桌面都传 2），需要一行四张时由更宽的设置页显式传 4。
 *
 * 这里也不依赖 `inspector/FmSegmented`：那套控件的选中态用的是 accent-soft 底 +
 * accent-strong 文字，和本卡片的「白药丸」不是一套；而 `theme/` 也不该反向依赖
 * `inspector/`。段控与设置页原来那支画法一致（36 dp 药丸、8 dp 圆角）。
 *
 * 选配色只改界面令牌，不碰照片、参数、文字、水印或 Logo（§7.2）；写入是即时的，
 * 没有「应用」按钮（§8.3）。
 */
private const val DEFAULT_PALETTE_COLUMNS = 2

@Composable
fun AppearancePanel(
    modifier: Modifier = Modifier,
    controller: AppearanceController = LocalAppearance.current,
    /** 每行几张卡；默认两列（手机与桌面弹层都用它）。 */
    columns: Int = DEFAULT_PALETTE_COLUMNS,
) {
    val dark = resolveDark(controller.settings.mode, controller.systemDark)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionLabel(AppearancePanelText.PALETTE_SECTION)
        PaletteGrid(
            selected = controller.settings.palette,
            dark = dark,
            columns = columns,
            onSelect = controller::setPalette,
        )

        SectionLabel(AppearancePanelText.MODE_SECTION)
        ModeSegment(selected = controller.settings.mode, onSelect = controller::setMode)
        Text(
            text = AppearancePanelText.MODE_NOTE,
            style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
            color = FmPalette.onSurfaceMuted,
        )

        SectionLabel(AppearancePanelText.STAGE_SECTION)
        StageToneRow(selected = controller.settings.stageTone, onSelect = controller::setStageTone)

        if (!controller.lastSaveOk) {
            Text(
                text = AppearancePanelText.SAVE_FAILED,
                style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
                color = FmPalette.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        ResetRow(
            enabled = controller.settings != AppearanceSettings.DEFAULT,
            onReset = controller::resetToDefault,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.08.sp),
        color = FmPalette.onSurfaceMuted,
    )
}

/** 四张预览卡；列数由调用方决定（默认两列）。 */
@Composable
private fun PaletteGrid(
    selected: PaletteId,
    dark: Boolean,
    columns: Int,
    onSelect: (PaletteId) -> Unit,
) {
    val ids = PaletteId.entries
    val perRow = columns.coerceIn(1, ids.size)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ids.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { id ->
                    PaletteCard(
                        id = id,
                        selected = id == selected,
                        dark = dark,
                        onClick = { onSelect(id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 最后一行不满时用占位撑住列宽，卡片不会被拉宽。
                repeat(perRow - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 一张预览卡。卡面用的是**这套配色自己的** surface / canvas / primary / primarySoft /
 * primaryStrong，所以四张卡并排就是四个真实界面，而不是四个色点（§8.2）。
 */
@Composable
private fun PaletteCard(
    id: PaletteId,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FmPalettes.of(id, dark)
    val shape = RoundedCornerShape(12.dp)
    // 选中态同时给描边、勾选和加粗名称：不只靠颜色判断（§8.2）。
    val borderColor = if (selected) colors.primary else colors.outline
    val borderWidth = if (selected) 2.dp else 1.dp
    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.surface)
            .border(borderWidth, borderColor, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(6.dp)
            .semantics {
                contentDescription = id.label
                role = Role.RadioButton
                stateDescription = if (selected) "已选" else "未选"
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PaletteMock(colors = colors, modifier = Modifier.fillMaxWidth().height(56.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = id.label,
                style = TextStyle(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = colors.onSurface,
                maxLines = 1,
            )
            if (selected) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = "✓",
                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold),
                    color = colors.primary,
                )
            }
        }
        Text(
            text = AppearancePanelText.tagline(id),
            style = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp),
            color = colors.onSurfaceMuted,
            maxLines = 1,
        )
    }
}

/**
 * 卡面上的迷你界面：画布底 + 顶栏 + 照片位 + 主按钮 + 柔和选中块。
 *
 * 只用布局块画，不用文字和图片：同一套结构在 76 dp 和 180 dp 宽下都成立，手机与桌面
 * 看到的是同一种「界面」，比较的只有颜色。
 */
@Composable
private fun PaletteMock(colors: FmColors, modifier: Modifier = Modifier) {
    Box(modifier = modifier.clip(RoundedCornerShape(8.dp)).background(colors.canvas)) {
        Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // 顶栏：surface 条 + 品牌色圆点 + 两截次要墨条。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.surface)
                    .padding(horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(Modifier.size(4.dp).clip(CircleShape).background(colors.primary))
                Box(
                    Modifier
                        .width(18.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.onSurfaceMuted),
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .width(14.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.primary),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(20.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 左侧「参数」两行。
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.9f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.onSurface),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(0.7f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.onSurfaceFaint),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(0.8f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.primarySoft),
                    )
                }
                // 右侧「照片」：舞台深灰，内层浅一档表示相纸。
                Box(
                    modifier = Modifier
                        .width(26.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.stage),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.72f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(colors.stageLight),
                    )
                }
            }
            // 底部行：实心主按钮 + 柔和选中块。
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.primary),
                )
                Box(
                    Modifier
                        .width(22.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.primarySoft),
                )
            }
        }
    }
}

/** 明暗三选一：跟随系统 / 浅色 / 深色。与设置页原段控同一画法。 */
@Composable
private fun ModeSegment(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val pill = RoundedCornerShape(FmRadii.chip)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.input))
            .background(FmPalette.outline)
            .padding(3.dp),
    ) {
        AppearancePanelText.MODE_ORDER.forEach { mode ->
            val active = mode == selected
            val label = AppearancePanelText.modeLabel(mode)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .then(if (active) Modifier.fmElevated(shape = pill) else Modifier)
                    .clip(pill)
                    .background(if (active) FmPalette.surface else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(role = Role.RadioButton) { if (!active) onSelect(mode) }
                    .semantics {
                        contentDescription = label
                        role = Role.RadioButton
                        stateDescription = if (active) "已选" else "未选"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = TextStyle(
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (active) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                )
            }
        }
    }
}

/** 画布背景三档：深灰 / 中灰 / 浅灰。圆点用真实的舞台色。 */
@Composable
private fun StageToneRow(selected: StageTone, onSelect: (StageTone) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppearancePanelText.TONE_ORDER.forEach { tone ->
            val active = tone == selected
            val swatch = LocalFmColors.current.stageColor(tone)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.RadioButton) { if (!active) onSelect(tone) }
                    .padding(vertical = 6.dp)
                    .semantics {
                        contentDescription = AppearancePanelText.toneLabel(tone)
                        role = Role.RadioButton
                        stateDescription = if (active) "已选" else "未选"
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) FmPalette.primary else FmPalette.outlineStrong,
                            shape = CircleShape,
                        ),
                )
                Text(
                    text = AppearancePanelText.toneLabel(tone),
                    style = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp),
                    color = if (active) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                )
            }
        }
    }
}

/** 「恢复默认外观」：只重置外观偏好，不动编辑会话（§8.3）。 */
@Composable
private fun ResetRow(enabled: Boolean, onReset: () -> Unit) {
    val shape = RoundedCornerShape(FmRadii.chip)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onReset)
            .semantics { contentDescription = AppearancePanelText.RESET },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = AppearancePanelText.RESET,
            style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
            color = if (enabled) FmPalette.primaryStrong else FmPalette.onSurfaceFaint,
        )
    }
}

/**
 * 面板文案。纯常量 + 纯函数，测试不用起模拟器就能核对「四套名称 / 三种模式 / 三档
 * 画布」的用词与顺序（配色方案 §8）。
 */
object AppearancePanelText {
    const val PALETTE_SECTION: String = "界面配色"
    const val MODE_SECTION: String = "明暗模式"
    const val STAGE_SECTION: String = "画布背景"
    const val RESET: String = "恢复默认外观"
    const val SAVE_FAILED: String = "外观已切换，但保存失败；重启后会恢复上一次的选择。"

    /** 跟随系统时立即跟随；手动选择后不再跟随。 */
    const val MODE_NOTE: String =
        "跟随系统时，系统切换到深色模式后立即跟着变；手动选择浅色或深色后不再跟随系统。"

    val MODE_ORDER: List<ThemeMode> = listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark)
    val TONE_ORDER: List<StageTone> = listOf(StageTone.Dark, StageTone.Mid, StageTone.Light)

    fun modeLabel(mode: ThemeMode): String = when (mode) {
        ThemeMode.System -> "跟随系统"
        ThemeMode.Light -> "浅色"
        ThemeMode.Dark -> "深色"
    }

    fun toneLabel(tone: StageTone): String = when (tone) {
        StageTone.Dark -> "深灰"
        StageTone.Mid -> "中灰"
        StageTone.Light -> "浅灰"
    }

    /** 副行：一句话说清这套配色的气质（§6 表格右列）。 */
    fun tagline(id: PaletteId): String = when (id) {
        PaletteId.Forest -> "自然安静"
        PaletteId.Graphite -> "沉稳精确"
        PaletteId.Indigo -> "清爽有序"
        PaletteId.Clay -> "温暖手作"
        PaletteId.Classic -> "经典直接"
    }
}
