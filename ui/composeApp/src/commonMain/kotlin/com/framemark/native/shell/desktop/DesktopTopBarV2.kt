package com.framemark.native.shell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framemark.native.theme.AppearancePicker
import com.framemark.native.theme.FmDesktop
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType

/**
 * V2 顶栏：52 dp 单行，左端项目，中间 编辑 / 批量导出，右端撤销重做 / 导入 / 导出 / 齿轮。
 *
 * 齿轮弹层里放 WP-T 的 [AppearancePicker]（WP-C 合并后会替换它内部实现，签名不变）。
 * 预设入口移到左栏“预设”页签（报告 §5 决定 5），所以这里不再有第三段导航。
 */
@Composable
fun DesktopTopBarV2(
    cardName: String?,
    photoCount: Int,
    batchMode: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    inspectorVisible: Boolean,
    onSelectEdit: () -> Unit,
    onSelectBatch: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddPhoto: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenHelp: () -> Unit,
    onShowParams: () -> Unit,
    onToggleLeft: () -> Unit,
    onToggleRight: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(FmDesktop.topBarHeight)
            .padding(horizontal = FmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("FrameMark", style = FmType.titleSmall, color = FmPalette.onSurface, maxLines = 1)
        Spacer(Modifier.width(FmSpacing.sm))
        Box(Modifier.width(1.dp).height(18.dp).background(FmPalette.outline))
        Spacer(Modifier.width(FmSpacing.sm))
        Text(
            text = if (photoCount > 0) "$photoCount 张照片 · ${cardName ?: "未选边框"}" else "未导入照片",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        Spacer(Modifier.width(FmSpacing.lg))
        SegmentedNav(batchMode = batchMode, onSelectEdit = onSelectEdit, onSelectBatch = onSelectBatch)
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopHistoryButton(label = "↶", description = "撤销", enabled = canUndo, onClick = onUndo)
            TopHistoryButton(label = "↷", description = "重做", enabled = canRedo, onClick = onRedo)
            TopAction(label = "导入", onClick = onAddPhoto)
            if (!inspectorVisible) TopAction(label = "参数", onClick = onShowParams)
            TopAction(label = "导出", primary = true, onClick = onOpenExport)
            TopOverflowMenu(
                onOpenHelp = onOpenHelp,
                onToggleLeft = onToggleLeft,
                onToggleRight = onToggleRight,
            )
            AppearanceGear()
        }
    }
}

@Composable
private fun SegmentedNav(
    batchMode: Boolean,
    onSelectEdit: () -> Unit,
    onSelectBatch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.surfaceMuted)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavSegment("编辑", selected = !batchMode, onClick = onSelectEdit)
        NavSegment("批量导出", selected = batchMode, onClick = onSelectBatch)
    }
}

@Composable
private fun NavSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(if (selected) FmPalette.surface else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = FmSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = FmType.labelMedium,
            color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
            maxLines = 1,
        )
    }
}
@Composable
private fun TopAction(label: String, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FmRadii.pill)
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .background(if (primary) FmPalette.primary else FmPalette.surface)
            .border(1.dp, if (primary) androidx.compose.ui.graphics.Color.Transparent else FmPalette.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = FmSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = FmType.labelMedium,
            color = if (primary) FmPalette.onPrimary else FmPalette.onSurfaceSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun TopHistoryButton(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(FmRadii.input))
            .background(if (enabled) FmPalette.surfaceMuted else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = FmType.titleSmall,
            color = if (enabled) FmPalette.onSurfaceSecondary else FmPalette.onSurfaceFaint,
        )
    }
}

/** ⋯：帮助与两侧栏的显示/折叠；主操作不藏进来。 */
@Composable
private fun TopOverflowMenu(
    onOpenHelp: () -> Unit,
    onToggleLeft: () -> Unit,
    onToggleRight: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(FmRadii.input))
                .clickable { open = true }
                .semantics {
                    contentDescription = "更多"
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("⋯", style = FmType.titleMedium, color = FmPalette.onSurfaceMuted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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

/** 顶栏右端的齿轮：弹层里是外观三选一（演示稿 `08-desktop-gear.png`）。 */
@Composable
private fun AppearanceGear() {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(FmRadii.input))
                .background(FmPalette.surfaceMuted)
                .clickable { open = true }
                .semantics {
                    contentDescription = "外观设置"
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("⚙", style = FmType.titleMedium, color = FmPalette.onSurfaceSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(260.dp).padding(horizontal = FmSpacing.md, vertical = FmSpacing.sm)) {
                Text("外观", style = FmType.titleSmall, color = FmPalette.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(
                    "跟随系统，或固定浅色 / 深色。照片区始终保持深色。",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
                Spacer(Modifier.height(FmSpacing.sm))
                AppearancePicker(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

