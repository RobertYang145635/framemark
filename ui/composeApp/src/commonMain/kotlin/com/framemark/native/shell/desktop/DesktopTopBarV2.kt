package com.framemark.native.shell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.AppearancePanel
import com.framemark.native.appVersionLabel
import com.framemark.native.product.FmLog
import com.framemark.native.shell.AboutContent
import com.framemark.native.shell.AboutText
import com.framemark.native.theme.FmDesktop
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmType

/**
 * 顶栏（`styles.css` 98-108 `.appbar`；PARITY-SPEC §2A）。
 *
 * 结构照演示稿从左到右：
 * `mark + 框印` │ `项目名 ▾ ● 已自动保存` ⟷ `编辑 / 批量导出` ⟷ `撤销 重做 │ 导入照片 导出[Ctrl E] ⚙`。
 *
 * 三列是 `minmax(0,1fr) auto minmax(0,1fr)`：两侧各吃一半剩余空间，中间的段控
 * 因此永远在窗口正中，与两侧按钮数量无关（演示稿正是靠这个 grid 把 220 px 的
 * 模式段控钉在中间的）。
 *
 * 齿轮弹层里放配色方案 §8 的 [AppearancePanel]（与手机设置页共用同一支面板）；
 * 项目卡的下拉是演示稿 `.proj` 上那个 chevron 的真实去处 —— 保存预设、恢复当前设计、
 * 帮助与两侧栏开关都收在里面，所以顶栏本身不再有演示稿没有的 `⋯` 按钮。
 */
@Composable
fun DesktopTopBarV2(
    /**
     * AU4-02: true below [com.framemark.native.shell.desktopTopBarCompact]. The
     * full bar needs ~980 dp of content width; narrower windows keep every action
     * but drop the decoder-only pieces (词标、Ctrl E 提示、「导入照片」的文字), so
     * 「导出」「⚙」「● 已自动保存」 stay fully visible instead of being clipped.
     */
    compact: Boolean = false,
    /** 项目名；模型里 `Project` 没有名称字段，调用点给「未命名项目」。 */
    projectName: String,
    /** 自动保存徽标（决定圆点颜色与文案）。 */
    saveState: DesktopSaveState,
    batchMode: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onSelectEdit: () -> Unit,
    onSelectBatch: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddPhoto: () -> Unit,
    onOpenExport: () -> Unit,
    onSavePreset: () -> Unit,
    onResetDesign: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onToggleLeft: () -> Unit,
    onToggleRight: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(FmDesktop.topBarHeight)
            .background(FmPalette.surface)
            .padding(start = FmDesktop.topBarPadStart, end = FmDesktop.topBarPadEnd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左组：词标 │ 项目卡（1fr，长名字省略成 …）
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 紧凑态去掉词标：它只是品牌装饰，不是操作，是最先该让位的东西。
            if (!compact) {
                Wordmark()
                Spacer(Modifier.width(6.dp))
                DeskVsep()
                Spacer(Modifier.width(6.dp))
            }
            ProjectChip(
                projectName = projectName,
                saveState = saveState,
                // 紧凑态把项目名压成可收缩的一格（ellipsis），项目卡本身仍是可点的下拉；
                // 「● 已自动保存」不参与收缩，必须完整可见。
                modifier = if (compact) Modifier.weight(1f, fill = false) else Modifier,
                nameMaxWidth = if (compact) 96.dp else 260.dp,
                onSavePreset = onSavePreset,
                onResetDesign = onResetDesign,
                onOpenHelp = onOpenHelp,
                onToggleLeft = onToggleLeft,
                onToggleRight = onToggleRight,
            )
        }
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        DeskSegmented(
            options = listOf("编辑", "批量导出"),
            selectedIndex = if (batchMode) 1 else 0,
            modifier = Modifier.width(if (compact) FmDesktop.topBarSegWidthCompact else FmDesktop.topBarSegWidth),
            onSelect = { if (it == 0) onSelectEdit() else onSelectBatch() },
        )
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        // 右组：撤销 重做 │ 导入照片 导出 ⚙（右对齐）
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeskIconButton(
                description = "撤销",
                enabled = canUndo,
                onClick = onUndo,
            ) { DeskHistoryGlyph(redo = false, tint = if (canUndo) FmPalette.onSurfaceSecondary else FmPalette.onSurfaceFaint) }
            DeskIconButton(
                description = "重做",
                enabled = canRedo,
                onClick = onRedo,
            ) { DeskHistoryGlyph(redo = true, tint = if (canRedo) FmPalette.onSurfaceSecondary else FmPalette.onSurfaceFaint) }
            Spacer(Modifier.width(2.dp))
            DeskVsep()
            Spacer(Modifier.width(2.dp))
            if (compact) {
                // 紧凑态「导入照片」去掉文字只留图标：动作还在（可读标签照旧），
                // 让出 ~70 dp 给「导出」的完整标签。
                DeskIconButton(description = "导入照片", onClick = onAddPhoto) {
                    DeskTransferGlyph(export = false, tint = FmPalette.onSurfaceSecondary)
                }
            } else {
                DeskButton(
                    label = "导入照片",
                    leading = { DeskTransferGlyph(export = false, tint = FmPalette.onSurfaceSecondary) },
                    onClick = onAddPhoto,
                )
            }
            DeskButton(
                label = if (batchMode) "开始导出" else "导出",
                style = DeskButtonStyle.Primary,
                leading = { DeskTransferGlyph(export = true, tint = FmPalette.onPrimary) },
                // 快捷键提示是"学会之后才有用"的信息，窄窗先让位；Ctrl E 依旧生效。
                kbd = if (compact) null else "Ctrl E",
                onClick = onOpenExport,
            )
            AppearanceGear(onOpenAbout = onOpenAbout)
        }
    }
}

/** 顶栏的自动保存状态：绿点=已保存，警告点=没存上/只读。 */
enum class DesktopSaveState { Saved, Pending, Failed }

@Composable
private fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DeskBrandMark()
        Spacer(Modifier.width(9.dp))
        Text(
            text = "框印",
            style = FmType.desktopWordmark,
            color = FmPalette.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * `.proj`：项目名 600 + chevron + `● 已自动保存`，padding 5×8、圆角 8、hover 上浮。
 * 下拉里是设计/项目级动作，对应演示稿 `.proj` 上那个 chevron。
 */
@Composable
private fun ProjectChip(
    projectName: String,
    saveState: DesktopSaveState,
    onSavePreset: () -> Unit,
    onResetDesign: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onToggleLeft: () -> Unit,
    onToggleRight: () -> Unit,
    modifier: Modifier = Modifier,
    nameMaxWidth: androidx.compose.ui.unit.Dp = 260.dp,
) {
    var open by remember { mutableStateOf(false) }
    val (source, hovered) = rememberDeskHover()
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(FmRadii.chip))
                .background(if (hovered || open) FmPalette.hover else androidx.compose.ui.graphics.Color.Transparent)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, onClick = { open = true })
                .semantics {
                    contentDescription = "项目：$projectName"
                    role = Role.Button
                }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = projectName,
                // `.proj b`：跟随 `.appbar` 的 14 px，权重 600。
                style = FmType.desktopTab.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = nameMaxWidth),
            )
            Spacer(Modifier.width(4.dp))
            ChevronDown(tint = FmPalette.onSurfaceSecondary)
            Spacer(Modifier.width(4.dp))
            SavedBadge(state = saveState)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("保存预设…", style = FmType.bodyMedium) },
                onClick = { open = false; onSavePreset() },
            )
            DropdownMenuItem(
                text = { Text("恢复当前设计…", style = FmType.bodyMedium) },
                onClick = { open = false; onResetDesign() },
            )
            DropdownMenuItem(
                text = { Text("帮助与快捷键（F1）", style = FmType.bodyMedium) },
                onClick = { open = false; onOpenHelp() },
            )
            DropdownMenuItem(
                text = { Text("显示/折叠边框库（Ctrl+B）", style = FmType.bodyMedium) },
                onClick = { open = false; onToggleLeft() },
            )
            DropdownMenuItem(
                text = { Text("显示/折叠参数面板（Ctrl+Shift+B）", style = FmType.bodyMedium) },
                onClick = { open = false; onToggleRight() },
            )
        }
    }
}

/** `● 已自动保存`：6 px 圆点 + 12 px `ink-4` 文案（`.proj .saved::before`）。 */
@Composable
private fun SavedBadge(state: DesktopSaveState) {
    val dot = when (state) {
        DesktopSaveState.Saved -> FmPalette.success
        DesktopSaveState.Pending -> FmPalette.warning
        DesktopSaveState.Failed -> FmPalette.error
    }
    val label = when (state) {
        DesktopSaveState.Saved -> "已自动保存"
        DesktopSaveState.Pending -> "保存中…"
        DesktopSaveState.Failed -> "保存失败"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(FmRadii.pill)).background(dot))
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = FmType.desktopCaption,
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
        )
    }
}

/** `.proj` 后面的下拉 chevron（14 px）。 */
@Composable
private fun ChevronDown(tint: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(14.dp)) {
        val stroke = 1.5.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.24f, h * 0.42f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.64f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.64f), androidx.compose.ui.geometry.Offset(w * 0.76f, h * 0.42f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** 顶栏右端的齿轮：弹层里是配色方案 §8 的外观面板（配色 / 明暗 / 画布）。 */
@Composable
private fun AppearanceGear(onOpenAbout: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        DeskIconButton(
            description = "外观设置",
            active = open,
            onClick = { open = true },
        ) { DeskGearGlyph(tint = FmPalette.onSurfaceSecondary) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // 手机设置页与这里共用同一支 `AppearancePanel`：四张预览卡 + 明暗段控 +
            // 画布三档 + 恢复默认。弹层不因选择而关闭，改配色时能立即对照（§8.3）。
            Column(Modifier.width(360.dp).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "外观",
                        style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
                        color = FmPalette.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    // 关于放在标题行而不是菜单底部：底部那一行会被 DropdownMenu 的
                    // 高度/滚动区域吃掉点击（2026-11-15 在打包后的桌面 App 上复现），
                    // 标题行与配色卡同一片区域，点击可靠。
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(FmRadii.pill))
                            .clickable(role = Role.Button) {
                                open = false
                                onOpenAbout()
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "关于 FrameMark",
                            style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                            color = FmPalette.primaryStrong,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                AppearancePanel()
            }
        }
    }
}
