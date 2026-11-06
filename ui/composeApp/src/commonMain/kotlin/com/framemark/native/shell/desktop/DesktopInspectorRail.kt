package com.framemark.native.shell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType

/**
 * 右栏外框（第一阶段）：标题区（边框名 / 类别 / 渲染方式）+ “⋯”菜单 + 页签 + 底栏，
 * 中间嵌入现有的 `EditorTabList`（由调用方作为 [content] 传入）。
 *
 * 第二阶段（WP-C 合并后）由 WP-C 的页签模型、页签条、页签内容替换 [tabs] 与 [content]，
 * 外框与底栏保留。
 */
@Composable
fun DesktopInspectorRail(
    cardName: String,
    categoryLine: String,
    renderMode: String,
    tags: List<String>,
    tabs: List<String>,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    adjustedCount: Int,
    onResetPage: () -> Unit,
    onSavePreset: () -> Unit,
    onApplyToOtherPhotos: () -> Unit,
    onResetAll: () -> Unit,
    onCollapse: () -> Unit,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = FmSpacing.lg, end = FmSpacing.sm, top = FmSpacing.md, bottom = FmSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("参数", style = FmType.titleMedium, color = FmPalette.onSurface)
                    Spacer(Modifier.size(FmSpacing.xs))
                    Text(
                        text = renderMode,
                        style = FmType.labelSmall.copy(fontSize = 10.sp),
                        color = FmPalette.primaryStrong,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(FmRadii.pill))
                            .background(FmPalette.primarySoft)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                Text(
                    text = cardName,
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (categoryLine.isNotBlank() || tags.isNotEmpty()) {
                    Text(
                        text = listOf(categoryLine).filter { it.isNotBlank() }.plus(tags).joinToString(" · "),
                        style = FmType.labelSmall.copy(fontSize = 10.sp),
                        color = FmPalette.onSurfaceFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .clickable { menuOpen = true }
                        .semantics {
                            contentDescription = "参数更多操作"
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⋯", style = FmType.titleMedium, color = FmPalette.onSurfaceMuted)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("应用到其他照片", style = FmType.bodyMedium) },
                        onClick = { menuOpen = false; onApplyToOtherPhotos() },
                    )
                    DropdownMenuItem(
                        text = { Text("存为预设…", style = FmType.bodyMedium) },
                        onClick = { menuOpen = false; onSavePreset() },
                    )
                    DropdownMenuItem(
                        text = { Text("全部恢复默认", style = FmType.bodyMedium) },
                        onClick = { menuOpen = false; onResetAll() },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .clickable(onClick = onCollapse)
                    .semantics { contentDescription = "折叠参数面板" },
                contentAlignment = Alignment.Center,
            ) {
                Text("›", style = FmType.titleMedium, color = FmPalette.onSurfaceMuted)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = FmSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            tabs.forEachIndexed { index, label ->
                val active = index == selectedTab
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .background(if (active) FmPalette.primarySoft else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onSelectTab(index) }
                        .semantics {
                            contentDescription = "参数分栏：$label"
                            role = Role.Tab
                        }
                        .padding(horizontal = FmSpacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = FmType.labelMedium,
                        color = if (active) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FmSpacing.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "已修改 $adjustedCount 项",
                style = FmType.labelSmall,
                color = if (adjustedCount > 0) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (adjustedCount > 0) {
                Text(
                    text = "恢复本页默认",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceSecondary,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .clickable(onClick = onResetPage)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.size(FmSpacing.xs))
            Text(
                text = "存为预设",
                style = FmType.labelSmall,
                color = FmPalette.primaryStrong,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .clickable(onClick = onSavePreset)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}
