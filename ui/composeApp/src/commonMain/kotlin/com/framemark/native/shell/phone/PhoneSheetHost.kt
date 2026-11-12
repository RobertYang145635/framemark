package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorLoadState
import com.framemark.native.inspector.InspectorPage
import com.framemark.native.inspector.InspectorTabModel
import com.framemark.native.inspector.WatermarkPane
import com.framemark.native.inspector.WatermarkZones
import com.framemark.native.inspector.resetInspectorPage
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryIndex
import com.framemark.native.product.FmLog
import com.framemark.native.shell.EditorTool
import com.framemark.native.shell.PhoneCompactSheetMetrics
import com.framemark.native.shell.phone.PhoneLibrarySheet
import com.framemark.native.shell.phone.PhoneParameterPage
import com.framemark.native.shell.phone.PhoneSheetGrabber
import com.framemark.native.shell.phone.PhoneSheetHead
import com.framemark.native.shell.phone.PhoneToolBar
import com.framemark.native.shell.phone.phoneToolLabel
import com.framemark.native.shell.phone.phoneToolModified
import com.framemark.native.shell.phone.phoneToolPage
import com.framemark.native.shell.phone.phoneToolShowsDot
import com.framemark.native.shell.phone.phoneToolTab
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.PhotoTray
import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.WatermarkSpec
import kotlin.math.abs

/**
 * The phone editor's parameter host: the draggable bottom sheet (grabber, title
 * row, the WP-L border sheet or the four WP-C parameter pages — including the
 * watermark pane and the full-page fallback for a window too short for a usable
 * sheet) plus the five-tool bar that switches pages.
 *
 * Every value stays owned by [PhoneEditorScreen]: this is a pure move of the panel
 * block that used to be inline there, with each state read passed down as a
 * parameter and each state write passed down as a callback.
 */
@Composable
internal fun ColumnScope.PhoneSheetHost(
    fullParameterPage: Boolean,
    sheetHeight: Dp,
    sheetMetrics: PhoneCompactSheetMetrics,
    snapHeights: List<Dp>,
    sheetLevel: Int,
    onSheetLevelChange: (Int) -> Unit,
    sheetDragPx: Float?,
    onSheetDragPx: (Float?) -> Unit,
    density: Density,
    tool: EditorTool,
    tabModel: InspectorTabModel,
    card: LibraryCard?,
    libraryIndex: LibraryIndex?,
    borderCategory: String,
    onBorderCategoryChange: (String) -> Unit,
    borderQuery: String,
    onBorderQueryChange: (String) -> Unit,
    inUseCardIds: List<String>,
    recentFrames: List<String>,
    onRetryLibrary: () -> Unit,
    pickBorder: (LibraryCard) -> Unit,
    onOpenPresets: () -> Unit,
    /**
     * Logo 页的 选择品牌：选择器是外壳画在整个编辑器之上的覆盖层（舞台仍在），
     * 底板只负责把请求递上去。null 时 Logo 页走内联品牌页（桌面右栏）。
     */
    onOpenBrandPicker: (() -> Unit)? = null,
    onToolChange: (EditorTool) -> Unit,
    model: InspectorCardModel?,
    session: DesignSession,
    inspectorState: InspectorLoadState,
    onRetryInspector: () -> Unit,
    textSlots: List<TextSlot>,
    textOutputWidth: Int,
    textOutputHeight: Int,
    onFocusedTextSlotChange: (String?) -> Unit,
    watermark: WatermarkSpec?,
    onWatermarkChange: (WatermarkSpec?) -> Unit,
    onPickWatermark: () -> Unit,
    photoWidthPx: Int,
    photoHeightPx: Int,
    previewGeo: PreviewGeo?,
    tools: List<EditorTool>,
    toolBarH: Dp,
) {
                // 整页挑边框的开关：只有**用户自己点**才打开（工具条上的 边框、底板里的
                // 「换一个边框」、无边框时的「去选边框」）。工具被改成 边框 不算点——
                // 全高回退页的「返回」会把工具还原成进入前的 边框，那一刻再开一次边框库页
                // 就是 rc14 P3-1：一次「返回预览」回不到舞台。规则见 PhoneSheetPagePolicy。
                // 开关只是本地 UI 状态：不写设计、不移动底板档位。
                var borderPageOpen by remember { mutableStateOf(false) }
                // 用户切换工具的唯一入口：整页边框库跟着开关。
                fun pickTool(next: EditorTool) {
                    borderPageOpen = PhoneSheetPagePolicy.pageAfterToolPick(tool, next, borderPageOpen)
                    onToolChange(next)
                }
                // 整页参数页（矮窗口 / 大字号的回退页；Logo 不在其列）接管底板时，
                // 底板里不留在背后的页面；它把屏幕交还底板时，底板仍从自己的默认页开始。
                LaunchedEffect(fullParameterPage) {
                    borderPageOpen = if (fullParameterPage) {
                        PhoneSheetPagePolicy.pageUnderFullHeightPage()
                    } else {
                        PhoneSheetPagePolicy.pageAfterFullHeightPageReturn()
                    }
                }
                Box(
                    modifier = if (fullParameterPage) {
                        Modifier.fillMaxWidth().weight(1f)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .height(sheetHeight)
                            .clip(RoundedCornerShape(topStart = SheetRadius, topEnd = SheetRadius))
                            .background(FmPalette.surface)
                            .border(
                                1.dp,
                                FmPalette.outline,
                                RoundedCornerShape(topStart = SheetRadius, topEnd = SheetRadius),
                            )
                    },
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        val toolPage = phoneToolPage(tool)
                        val pageModified = phoneToolModified(tool, tabModel)
                        if (!fullParameterPage) {
                            PhoneSheetGrabber(
                                height = sheetMetrics.grabber.dp,
                                onToggle = {
                                    // Grabber tap cycles through the three stops: 收起 →
                                    // 半开 → 展开 → 收起.
                                    onSheetDragPx(null)
                                    onSheetLevelChange((sheetLevel + 1) % snapHeights.size)
                                },
                                onDragStart = {
                                    onSheetDragPx(with(density) { sheetHeight.toPx() })
                                },
                                onDrag = { dy ->
                                    val lo = with(density) { snapHeights.first().toPx() }
                                    val hi = with(density) { snapHeights.last().toPx() }
                                    onSheetDragPx(((sheetDragPx ?: lo) - dy).coerceIn(lo, hi))
                                },
                                onDragEnd = {
                                    val current = sheetDragPx
                                    if (current != null) {
                                        val px = snapHeights.map { with(density) { it.toPx() } }
                                        onSheetLevelChange(px.indices.minByOrNull { abs(px[it] - current) } ?: 0)
                                        onSheetDragPx(null)
                                    }
                                },
                            )
                            PhoneSheetHead(
                                title = phoneToolLabel(tool),
                                subtitle = if (tool == EditorTool.Border) {
                                    "当前：${card?.name ?: "未选择"}"
                                } else {
                                    card?.name.orEmpty()
                                },
                                modified = pageModified,
                                expanded = sheetLevel == snapHeights.lastIndex,
                                height = sheetMetrics.head.dp,
                                onToggleExpanded = {
                                    onSheetDragPx(null)
                                    onSheetLevelChange((sheetLevel + 1) % snapHeights.size)
                                },
                                onReset = if (toolPage == null) {
                                    null
                                } else if (toolPage == InspectorPage.Watermark) {
                                    {
                                        watermark?.let { spec ->
                                            onWatermarkChange(
                                                spec.copy(
                                                    anchor = WatermarkZones.DEFAULT_ANCHOR,
                                                    width = WatermarkZones.DEFAULT_WIDTH,
                                                    opacity = WatermarkZones.DEFAULT_OPACITY,
                                                    dx = 0f,
                                                    dy = 0f,
                                                ),
                                            )
                                            FmLog.info("FM_WM_RESET")
                                        }
                                    }
                                } else {
                                    {
                                        val changed = resetInspectorPage(tabModel, toolPage, session)
                                        FmLog.info("FM_PAGE_RESET") { "page=${toolPage.name} changed=$changed" }
                                    }
                                },
                                // 编辑中直接套用预设 (hand-off §3 decision 5): the phone keeps
                                // the entry on the border sheet's title.
                                onOpenPresets = if (tool == EditorTool.Border) onOpenPresets else null,
                                // Demo `.psh .adj`: 调整样式 › jumps from the border sheet to
                                // the 样式 page of the applied card.
                                onAdjustStyle = if (tool == EditorTool.Border && card != null) {
                                    { pickTool(EditorTool.Params) }
                                } else {
                                    null
                                },
                            )
                        }
                        if (card == null && tool != EditorTool.Border) {
                            PanelRequiresBorder(
                                onOpenBorder = { pickTool(EditorTool.Border) },
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            )
                        } else if (tool == EditorTool.Border) {
                            PhoneLibrarySheet(
                                index = libraryIndex,
                                card = card,
                                onRetry = onRetryLibrary,
                                onOpenPage = { borderPageOpen = true },
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            )
                            // 整页挑边框：盖在编辑器上面的一层，不移动底板、不改工具，
                            // 所以关掉它就精确回到进来之前的底板档位与工具。
                            val pageIndex = libraryIndex
                            if (borderPageOpen && pageIndex != null) {
                                PhoneBorderPageDialog(
                                    index = pageIndex,
                                    appliedCard = card,
                                    categoryKey = borderCategory,
                                    onCategoryChange = { onBorderCategoryChange(it) },
                                    query = borderQuery,
                                    onQueryChange = { onBorderQueryChange(it) },
                                    inUse = inUseCardIds,
                                    recent = recentFrames,
                                    onApply = pickBorder,
                                    onDismiss = { borderPageOpen = false },
                                )
                            }
                        } else {
                            val photoAspect = previewGeo?.photoRect
                                ?.takeIf { it.h > 0 }
                                ?.let { it.w.toFloat() / it.h }
                                ?: watermark?.photoAspect
                                ?: 1.5f
                            PhoneParameterPage(
                                tab = phoneToolTab(tool, tabModel),
                                page = toolPage ?: InspectorPage.Style,
                                model = model,
                                session = session,
                                inspectorState = inspectorState,
                                onRetry = onRetryInspector,
                                textSlots = textSlots,
                                textOutputWidth = textOutputWidth,
                                textOutputHeight = textOutputHeight,
                                onTextSlotFocus = { key ->
                                    FmLog.info("FM_TEXT_FOCUS") { "key=$key tool=$tool" }
                                    onFocusedTextSlotChange(key)
                                },
                                onOpenBrandPicker = onOpenBrandPicker,
                                watermarkPane = if (toolPage == InspectorPage.Watermark) {
                                    {
                                        WatermarkPane(
                                            spec = watermark,
                                            photoAspect = photoAspect,
                                            onAnchor = { anchor ->
                                                watermark?.let { onWatermarkChange(it.copy(anchor = anchor)) }
                                            },
                                            onWidth = { width ->
                                                watermark?.let { onWatermarkChange(it.copy(width = width)) }
                                            },
                                            onOpacity = { opacity ->
                                                watermark?.let { onWatermarkChange(it.copy(opacity = opacity)) }
                                            },
                                            onPosition = { dx, dy ->
                                                watermark?.let { onWatermarkChange(it.copy(dx = dx, dy = dy)) }
                                            },
                                            // AU2 A-11：手机与桌面共用同一个入口。
                                            onRotation = { degrees ->
                                                watermark?.let { onWatermarkChange(it.copy(rotation = degrees)) }
                                            },
                                            onUpload = onPickWatermark,
                                            onClear = { onWatermarkChange(null) },
                                            dense = true,
                                        )
                                    }
                                } else {
                                    null
                                },
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                // 样式's own 导出尺寸 row (showcase `paneClassicStyle`'s
                                // `.outsz`): 由照片像素 + 版式留白算出，和桌面 rail
                                // 同一个 `curatedOutputSize`；不再用导出长边顶替尺寸。
                                card = card,
                                photoWidthPx = photoWidthPx,
                                photoHeightPx = photoHeightPx,
                            )
                        }
                    }
                }
                if (!fullParameterPage) {
                    PhoneToolBar(
                        height = toolBarH,
                        tool = tool,
                        availableTools = tools,
                        modifiedOf = { entry -> phoneToolShowsDot(entry, tabModel) },
                        onSelect = { pickTool(it) },
                    )
                }
}

/** 1-based index of the selected strip photo; 1 when the strip has no selection yet. */
internal fun photoIndexOf(tray: PhotoTray): Int {
    val index = tray.items.indexOfFirst { it.id == tray.selectedId }
    return if (index >= 0) index + 1 else 1
}

/** Full parameter page title: the page is the current tool rendered full height. */
internal fun panelTitle(tool: EditorTool): String = phoneToolLabel(tool)

// ---------------------------------------------------------------------------
// Panel
// ---------------------------------------------------------------------------

/** A non-border tool with no applied border yet: a real explanation, not empty rows. */
@Composable
private fun PanelRequiresBorder(onOpenBorder: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(FmSpacing.md),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("先选择边框", style = FmType.titleSmall, color = FmPalette.onSurface)
        Spacer(Modifier.height(FmSpacing.xs))
        Text(
            text = "这张照片还没有边框。选择边框后才能调整参数、文字、Logo 和水印位置。",
            style = FmType.bodyMedium,
            color = FmPalette.onSurfaceMuted,
        )
        Spacer(Modifier.height(FmSpacing.md))
        Text(
            text = "去选边框",
            style = FmType.labelMedium,
            color = FmPalette.onPrimary,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.primary)
                .semantics {
                    contentDescription = "去选择边框"
                    role = Role.Button
                }
                .clickable(onClick = onOpenBorder)
                .padding(horizontal = FmSpacing.xl, vertical = FmSpacing.md),
        )
    }
}

@Composable
internal fun ParameterPageHeader(
    height: Dp,
    title: String,
    closeLabel: String,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline)
            .padding(horizontal = FmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "参数 · $title",
            style = FmType.titleSmall,
            color = FmPalette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = closeLabel,
            style = FmType.labelMedium,
            color = FmPalette.primaryStrong,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .clickable(onClick = onClose)
                .padding(horizontal = FmSpacing.md, vertical = FmSpacing.md),
        )
    }
}

/**
 * Old mobile `.inspector-panel { border-radius: 22px 22px 0 0 }`. 22 dp is the
 * stage token, not the sheet role, so the sheet now uses [FmRadii.sheet] — the
 * same radius as every other sheet surface (`FmSheetSurface`, dialogs).
 */
internal val SheetRadius = FmRadii.sheet
