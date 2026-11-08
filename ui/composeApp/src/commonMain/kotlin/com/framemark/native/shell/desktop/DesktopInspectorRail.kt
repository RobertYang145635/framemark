package com.framemark.native.shell.desktop

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.inspector.CuratedPhotoSize
import com.framemark.native.inspector.CuratedTextSlots
import com.framemark.native.inspector.InspectorPage
import com.framemark.native.inspector.InspectorTabModel
import com.framemark.native.inspector.LocalCuratedPhotoSize
import com.framemark.native.inspector.LocalCuratedTextSlots
import com.framemark.native.theme.FmNumeric
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.TextSlot

/**
 * 右栏外框（WP-V3）：标题 · 分类行 · 分页条 · 底栏。
 *
 * 结构与数值照 `styles.css:434-451`（`.insp / .ihead / .itabs / .ibody / .ifoot`）：
 *  - 标题是**所选边框名** 16/700（不是「参数」），下一行 12px 分类 + 分类/徽标 pill +
 *    「实时渲染」绿点；右上「⋯」菜单；
 *  - 页签条 h40、13px，选中 600 + 2px `--ink` 滑动指示条；本页有改动时显示数字徽标；
 *  - 底栏「全部为默认值 / 已修改 N 项」+ 「恢复本页默认」ghost + 「存为预设」。
 *
 * 页内容由调用方作为 [content] 传入（外壳仍是 `DesktopShell` 装配的
 * `CuratedPage` / `WatermarkPane`），本文件只负责外框。
 */
@Composable
fun DesktopInspectorRail(
    cardName: String,
    categoryLine: String,
    renderMode: String,
    tags: List<String>,
    tabModel: InspectorTabModel,
    selectedPage: InspectorPage,
    onSelectPage: (InspectorPage) -> Unit,
    totalModified: Int,
    pageModified: Int,
    onResetPage: () -> Unit,
    onSavePreset: () -> Unit,
    onApplyToOtherPhotos: () -> Unit,
    onResetAll: () -> Unit,
    onCollapse: () -> Unit,
    /** 舞台当前照片的像素尺寸，用于样式页的「导出尺寸」；未传则不显示该行。 */
    photoWidthPx: Int = 0,
    photoHeightPx: Int = 0,
    /**
     * 舞台最近一次渲染报告的文本 slot（`TextSlotRegistry`），用于文字页的四行：
     * 品牌 / 机型 / 拍摄参数 / 日期。空列表时文字页保持原控件列表。
     */
    textSlots: List<TextSlot> = emptyList(),
    /** slot 坐标所在的成片像素画布（位置示意图的归一化基准）。 */
    textSlotWidthPx: Int = 0,
    textSlotHeightPx: Int = 0,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val photoSize = if (photoWidthPx > 0 && photoHeightPx > 0) {
        CuratedPhotoSize(photoWidthPx, photoHeightPx)
    } else {
        null
    }
    // 报告到了就提供画布尺寸，即使一个文本框都没有：文字页要能区分「这个边框没有
    // 可编辑文字」（空态）和「还没渲染完」（保留声明出来的文字行）。
    val textSlotInfo = if (textSlotWidthPx > 0) {
        CuratedTextSlots(textSlots, textSlotWidthPx, textSlotHeightPx)
    } else {
        null
    }
    CompositionLocalProvider(
        LocalCuratedPhotoSize provides photoSize,
        LocalCuratedTextSlots provides textSlotInfo,
    ) {
    Column(modifier = Modifier.fillMaxSize().background(FmPalette.surface)) {
        // ---------------------------------------------------------------- ihead
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = cardName,
                    style = InspectorHeadTitle,
                    color = FmPalette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 演示稿把分类拆成 pill（「文字边框」「水印边框」各一枚），不是一行文字。
                    categoryLine.split(" · ").filter { it.isNotBlank() }.forEach { part ->
                        Text(
                            text = part,
                            style = TextStyle(
                                fontSize = 11.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.Normal,
                            ),
                            color = FmPalette.onSurfaceMuted,
                            maxLines = 1,
                            modifier = Modifier
                                .heightIn(min = 18.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(FmPalette.surfaceMuted)
                                .padding(horizontal = 6.dp, vertical = 0.dp),
                        )
                    }
                    tags.filter { it.isNotBlank() }.forEach { tag ->
                        Text(
                            text = tag,
                            style = TextStyle(
                                fontSize = 11.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.Normal,
                            ),
                            color = FmPalette.onSurfaceMuted,
                            maxLines = 1,
                            modifier = Modifier
                                .heightIn(min = 18.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(FmPalette.surfaceMuted)
                                .padding(horizontal = 6.dp, vertical = 0.dp),
                        )
                    }
                    if (renderMode.isNotBlank()) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(FmPalette.success))
                        Text(
                            text = renderMode,
                            style = TextStyle(
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.Normal,
                            ),
                            color = FmPalette.success,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.width(FmSpacing.sm))
            Box {
                InspectorIconButton(
                    glyph = "⋯",
                    description = "参数更多操作",
                    onClick = { menuOpen = true },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("应用到其他照片…", style = FmType.bodyMedium) },
                        onClick = { menuOpen = false; onApplyToOtherPhotos() },
                    )
                    DropdownMenuItem(
                        text = { Text("存为预设", style = FmType.bodyMedium) },
                        onClick = { menuOpen = false; onSavePreset() },
                    )
                    DropdownMenuItem(
                        text = { Text("全部恢复默认", style = FmType.bodyMedium) },
                        onClick = { menuOpen = false; onResetAll() },
                    )
                    // 折叠是原生窄栏需要的操作，但演示稿的 ihead 只有「⋯」一个按钮，
                    // 所以收进菜单，不再占标题行的第二个图标位（WP-Q D2）。
                    DropdownMenuItem(
                        text = { Text("折叠参数面板", style = FmType.bodyMedium) },
                        onClick = { menuOpen = false; onCollapse() },
                    )
                }
            }
        }

        // ---------------------------------------------------------------- itabs
        if (tabModel.tabs.isNotEmpty()) {
            InspectorPageTabs(
                model = tabModel,
                selected = selectedPage,
                onSelect = onSelectPage,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
        }

        // ---------------------------------------------------------------- ibody
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }

        // ---------------------------------------------------------------- ifoot
        Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (totalModified == 0) {
                    Text(
                        text = "全部为默认值",
                        style = FmType.labelMedium.copy(fontSize = 12.sp),
                        color = FmPalette.onSurfaceMuted,
                        maxLines = 1,
                    )
                } else {
                    Text(
                        text = "已修改 ",
                        style = FmType.labelMedium.copy(fontSize = 12.sp),
                        color = FmPalette.primaryStrong,
                        maxLines = 1,
                    )
                    // 演示稿 `.ifoot .n`：数字是 accent + `--num`，中文仍是 `--font`。
                    Text(
                        text = totalModified.toString(),
                        style = FmNumeric.value.copy(fontWeight = FontWeight.SemiBold),
                        color = FmPalette.primaryStrong,
                        maxLines = 1,
                    )
                    Text(
                        text = " 项",
                        style = FmType.labelMedium.copy(fontSize = 12.sp),
                        color = FmPalette.primaryStrong,
                        maxLines = 1,
                    )
                }
            }
            InspectorFootButton(
                text = "恢复本页默认",
                leading = "↺",
                enabled = pageModified > 0,
                onClick = onResetPage,
            )
            InspectorFootButton(
                text = "存为预设",
                leading = null,
                enabled = true,
                onClick = onSavePreset,
            )
        }
    }
    }
}

/** `h3` in the demo: 16 / 700, one line, ellipsis. */
private val InspectorHeadTitle = TextStyle(
    fontSize = 16.sp,
    lineHeight = 21.sp,
    fontWeight = FontWeight.Bold,
)

/** `.ihead button.ibtn` — 28 dp square, ink-3 glyph that darkens on hover. */
@Composable
private fun InspectorIconButton(
    glyph: String,
    description: String,
    onClick: () -> Unit,
    size: Dp = 28.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(FmRadii.chip))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = TextStyle(fontSize = 15.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
            color = FmPalette.onSurfaceMuted,
        )
    }
}

/**
 * `.itabs` — four equal cells, h40, 13 px; the selected label is 600 with a
 * 2 dp `--ink` bar that slides under the **label** (`width = label + 12`,
 * §2E). A page with unsaved changes carries a numeric badge (10 px, `accent-soft`
 * plate, radius 8).
 */
@Composable
private fun InspectorPageTabs(
    model: InspectorTabModel,
    selected: InspectorPage,
    onSelect: (InspectorPage) -> Unit,
) {
    val tabs = InspectorPage.entries.mapNotNull { model.tab(it) }
    var stripWidth by remember { mutableStateOf(0f) }
    // State map: the first measure of a label must trigger one recompose.
    val labelWidths = remember { mutableStateMapOf<InspectorPage, Float>() }
    val density = LocalDensity.current.density
    val cellWidth = if (tabs.isEmpty()) 0f else stripWidth / tabs.size
    val index = tabs.indexOfFirst { it.page == selected }.coerceAtLeast(0)
    val labelWidth = if (labelWidths.containsKey(selected)) labelWidths.getValue(selected) else 0f
    val barWidth = if (labelWidth > 0f) labelWidth + 12.dp.value * density else 0f
    val targetX = if (cellWidth > 0f && labelWidth > 0f) {
        index * cellWidth + (cellWidth - labelWidth) / 2f - 6.dp.value * density
    } else {
        0f
    }
    val animatedX by animateDpAsState(
        targetValue = targetX.dp,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 700f),
        label = "tabIndicatorX",
    )
    val animatedWidth by animateDpAsState(
        targetValue = barWidth.dp,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 700f),
        label = "tabIndicatorW",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .onGloballyPositioned { stripWidth = it.size.width.toFloat() },
    ) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            tabs.forEach { tab ->
                val active = tab.page == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(role = Role.Tab) { onSelect(tab.page) }
                        .semantics {
                            contentDescription = if (tab.modified > 0) {
                                "${tab.label}，已改 ${tab.modified} 项"
                            } else {
                                tab.label
                            }
                            stateDescription = if (active) "已选" else "未选"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = tab.label,
                            style = TextStyle(
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            color = if (active) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.onGloballyPositioned {
                                labelWidths[tab.page] = it.size.width.toFloat()
                            },
                        )
                        if (tab.modified > 0) {
                            Spacer(Modifier.width(5.dp))
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 16.dp)
                                    .widthIn(min = 16.dp)
                                    .clip(RoundedCornerShape(FmRadii.chip))
                                    .background(FmPalette.primarySoft)
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = tab.modified.toString(),
                                    style = FmNumeric.frameNumber.copy(
                                        fontSize = 10.sp,
                                        lineHeight = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = FmPalette.primaryStrong,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (animatedWidth > 0.dp) {
            Box(
                modifier = Modifier
                    .offset(x = animatedX + 10.dp, y = 37.dp)
                    .width(animatedWidth)
                    .height(2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(FmPalette.onSurface),
            )
        }
    }
}

/** `.ifoot .btn` — h32, radius 8; ghost vs. line follows the demo pair. */@Composable
private fun InspectorFootButton(
    text: String,
    leading: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(FmRadii.chip)
    val ink = if (enabled) FmPalette.onSurfaceSecondary else FmPalette.onSurfaceFaint
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(shape)
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outlineStrong, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(PaddingValues(horizontal = 10.dp))
            .semantics {
                contentDescription = text
                stateDescription = if (enabled) "可用" else "不可用"
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Text(
                    text = leading,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 14.sp),
                    color = ink,
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = text,
                style = FmType.labelMedium.copy(fontSize = 12.sp),
                color = ink,
                maxLines = 1,
            )
        }
    }
}

/** Local dp→px for the indicator math (the strip is measured in pixels). */
