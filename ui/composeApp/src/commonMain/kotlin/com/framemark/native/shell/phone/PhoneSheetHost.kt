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
                                    { onToolChange(EditorTool.Params) }
                                } else {
                                    null
                                },
                            )
                        }
                        if (card == null && tool != EditorTool.Border) {
                            PanelRequiresBorder(
                                onOpenBorder = { onToolChange(EditorTool.Border) },
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            )
                        } else if (tool == EditorTool.Border) {
                            PhoneLibrarySheet(
                                index = libraryIndex,
                                selectedCardId = card?.id,
                                expanded = sheetLevel > 0 || fullParameterPage,
                                metrics = sheetMetrics,
                                categoryKey = borderCategory,
                                onCategoryChange = { onBorderCategoryChange(it) },
                                query = borderQuery,
                                onQueryChange = { onBorderQueryChange(it) },
                                inUse = inUseCardIds,
                                recent = recentFrames,
                                onRetry = onRetryLibrary,
                                onPick = pickBorder,
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            )
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
                        onSelect = onToolChange,
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

/** Old mobile `.inspector-panel { border-radius: 22px 22px 0 0 }`. */
internal val SheetRadius = 22.dp
