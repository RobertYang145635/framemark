package com.framemark.native.shell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorLoadState
import com.framemark.native.inspector.InspectorPage
import com.framemark.native.inspector.InspectorPageContent
import com.framemark.native.inspector.InspectorTabModel
import com.framemark.native.inspector.LogoBrandPanel
import com.framemark.native.inspector.WatermarkPane
import com.framemark.native.inspector.WatermarkZones
import com.framemark.native.inspector.resetInspectorPage
import com.framemark.native.library.LibraryCard
import com.framemark.native.product.FmLog
import com.framemark.native.shell.DesktopColumns
import com.framemark.native.shell.DesktopLayoutState
import com.framemark.native.shell.LIBRARY_PANE
import com.framemark.native.shell.PARAMS_PANE
import com.framemark.native.shell.STAGE_PANE
import com.framemark.native.shell.desktopInspectorMaxWidth
import com.framemark.native.shell.desktopInspectorMinWidth
import com.framemark.native.shell.desktopLibraryMaxWidth
import com.framemark.native.shell.desktopLibraryMinWidth
import com.framemark.native.shell.displayPixels
import com.framemark.native.theme.FmDesktop
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.ui.resizeCursor
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.NativeEngine
import com.framemark.native.workflow.ProjectHolder
import com.framemark.native.workflow.TextSlotRegistry
import kotlinx.coroutines.launch

/**
 * The three-column workspace and its single-pane fallback: the left library slot, the
 * centre stage slot, the right inspector slot, the collapsed-rail affordances and the
 * two drag-to-resize handles between them.
 *
 * Owns
 *  - [DesktopColumnLayout] — the layout decision handed in by `computeDesktopColumns`
 *    plus the compact (one-pane-at-a-time) branch driven by
 *    [DesktopLayoutState.narrowPane];
 *  - the inspector rail wiring (`DesktopInspectorRail` plus the four page bodies:
 *    style/text via `InspectorPageContent`, logo via `LogoBrandPanel`, watermark via
 *    `WatermarkPane`, and the empty/loading/retry copy);
 *  - [DesktopPanel] / [CollapsedRail] / [ResizeHandle] — the pane frame, the folded
 *    rail and the drag strip.
 *
 * Rail widths live in the hoisted [DesktopLayoutState]; this file never stores any.
 * The chrome of each rail keeps its own file ([DesktopInspectorRail],
 * `DesktopLibraryPanel`).
 */

@Composable
internal fun DesktopColumnLayout(
    modifier: Modifier,
    columns: DesktopColumns,
    availableWidthDp: Float,
    layout: DesktopLayoutState,
    libraryContent: @Composable () -> Unit,
    onCollapseLeft: () -> Unit,
    onExpandLeft: () -> Unit,
    onCollapseRight: () -> Unit,
    onExpandRight: () -> Unit,
    selectedCard: LibraryCard?,
    model: InspectorCardModel?,
    /** [model] plus the text rows the stage renderer reported; panel only. */
    panelModel: InspectorCardModel?,
    textSlots: TextSlotRegistry,
    /** 缺拍摄参数的行画不画虚线占位（进过「文字」页之后为 false）。 */
    showExifHints: Boolean = false,
    session: DesignSession,
    inspectorState: InspectorLoadState,
    onRetryInspector: () -> Unit,
    tabModel: InspectorTabModel,
    paramsPage: InspectorPage,
    onSelectPage: (InspectorPage) -> Unit,
    onTextSlotFocus: (String?) -> Unit,
    highlightedTextSlotKey: String?,
    projects: ProjectHolder,
    onAddPhoto: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    onSavePreset: (String) -> Unit,
    onResetDesign: () -> Unit,
    hoveredCard: LibraryCard?,
    holdOriginal: Boolean,
    onReuseDesign: (String) -> Unit,
    onPickBorder: () -> Unit,
    onApplyToOtherPhotos: () -> Unit,
    /** 胶片条「批量导出」链接（`styles.css` 430 行 `.sinfo` 里的 link）。 */
    onOpenBatch: () -> Unit,
) {
    val gap = FmDesktop.gap

    /**
     * 右栏外框（第二阶段）：WP-C 的四页签模型 / 页签条 / 页内容。水印页接
     * `WatermarkPane`（九宫格 + 按格边距），其余页接 `InspectorPageContent`。
     * 没选边框时只显示“从左侧选择一套边框”。
     */
    fun inspectorRail(collapse: () -> Unit): @Composable () -> Unit = {
        val wmScope = rememberCoroutineScope()
        val wm = desktopWorkflowWatermark(projects.watermark())
        val activePhoto = projects.activePhoto
        // FIX1: 照片的**显示**尺寸（EXIF 方向已应用），不再拿容器尺寸算比例 ——
        // 竖拍照片存的是 2000 × 2667、引擎按 2667 × 2000 出片，比例反了会让舞台
        // 画框和成片各说各话。
        val activePhotoDisplay = activePhoto?.displayPixels()
        val photoAspect = if (activePhotoDisplay != null && activePhotoDisplay.width > 0 && activePhotoDisplay.height > 0) {
            activePhotoDisplay.width.toFloat() / activePhotoDisplay.height
        } else {
            wm?.photoAspect ?: 1.5f
        }
        val pageTab = tabModel.tab(paramsPage)
        DesktopInspectorRail(
            cardName = selectedCard?.name ?: "未选择边框",
            categoryLine = listOfNotNull(selectedCard?.categoryName, selectedCard?.templateCategory)
                .filter { it.isNotBlank() }.joinToString(" · "),
            renderMode = when {
                selectedCard == null -> "等待选择"
                NativeEngine.supports(selectedCard.id) -> "实时渲染"
                else -> "示例图"
            },
            tags = listOfNotNull(selectedCard?.badge),
            tabModel = tabModel,
            selectedPage = paramsPage,
            onSelectPage = onSelectPage,
            totalModified = tabModel.totalModified,
            pageModified = pageTab?.modified ?: 0,
            onResetPage = {
                if (paramsPage == InspectorPage.Watermark) {
                    projects.updateWatermark(
                        anchor = WatermarkZones.DEFAULT_ANCHOR,
                        width = WatermarkZones.DEFAULT_WIDTH,
                        opacity = WatermarkZones.DEFAULT_OPACITY,
                        dx = 0f,
                        dy = 0f,
                        rotation = 0f,
                    )
                } else {
                    val changed = resetInspectorPage(tabModel, paramsPage, session)
                    FmLog.info("FM_INSPECTOR_RESET") { "page=${paramsPage.name} changed=$changed" }
                }
            },
            onSavePreset = { onSavePreset("${selectedCard?.name ?: "边框"} 预设") },
            onApplyToOtherPhotos = onApplyToOtherPhotos,
            onResetAll = onResetDesign,
            onCollapse = collapse,
            photoWidthPx = activePhotoDisplay?.width ?: 0,
            photoHeightPx = activePhotoDisplay?.height ?: 0,
            textSlots = textSlots.slots,
            textSlotWidthPx = textSlots.width,
            textSlotHeightPx = textSlots.height,
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.sm),
                ) {
                    when {
                        selectedCard == null -> Text(
                            text = "从左侧选择一套边框，这里会显示样式、文字、Logo 与水印。",
                            style = FmType.bodyMedium,
                            color = FmPalette.onSurfaceMuted,
                            modifier = Modifier.padding(vertical = FmSpacing.lg),
                        )
                        panelModel == null || pageTab == null -> Column {
                            Text(
                                text = if (inspectorState is InspectorLoadState.Failed) {
                                    "暂时无法加载可调参数。"
                                } else {
                                    "正在载入可调参数…"
                                },
                                style = FmType.bodyMedium,
                                color = FmPalette.onSurfaceMuted,
                                modifier = Modifier.padding(vertical = FmSpacing.lg),
                            )
                            if (inspectorState is InspectorLoadState.Failed) {
                                Text(
                                    text = "重试",
                                    style = FmType.labelMedium,
                                    color = FmPalette.primaryStrong,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(FmRadii.pill))
                                        .clickable(onClick = onRetryInspector)
                                        .padding(horizontal = FmSpacing.md, vertical = 4.dp),
                                )
                            }
                        }
                        paramsPage == InspectorPage.Logo && panelModel != null -> LogoBrandPanel(
                            // WP-C report §2.2 item 6: the Logo page is the shared
                            // LogoBrandPanel on desktop and phone, not raw controls.
                            model = panelModel,
                            session = session,
                            modifier = Modifier.fillMaxWidth(),
                            // 自动匹配要按照片品牌：slot 是异步到的，显式传参保证到货后重新解析。
                            textSlots = textSlots.slots,
                        )
                        paramsPage == InspectorPage.Watermark -> WatermarkPane(
                            spec = wm,
                            photoAspect = photoAspect,
                            onAnchor = { projects.updateWatermark(anchor = it) },
                            onWidth = { projects.updateWatermark(width = it) },
                            onOpacity = { projects.updateWatermark(opacity = it) },
                            onPosition = { dx, dy -> projects.updateWatermark(dx = dx, dy = dy) },
                            // AU2 A-11：rotation 全链路都在，以前只是缺入口。
                            onRotation = { degrees -> projects.updateWatermark(rotation = degrees) },
                            assetName = projects.watermark()?.asset?.name,
                            onUpload = {
                                val path = projects.pickWatermark()
                                if (path != null) {
                                    wmScope.launch { projects.importWatermark(path) }
                                }
                            },
                            onApplyAll = { projects.applyWatermarkToAll() },
                            onClear = { projects.clearWatermark() },
                            onReset = {
                                projects.updateWatermark(
                                    anchor = WatermarkZones.DEFAULT_ANCHOR,
                                    width = WatermarkZones.DEFAULT_WIDTH,
                                    opacity = WatermarkZones.DEFAULT_OPACITY,
                                    dx = 0f,
                                    dy = 0f,
                                    rotation = 0f,
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        else -> InspectorPageContent(
                            tab = pageTab,
                            session = session,
                            modifier = Modifier.fillMaxWidth(),
                            onTextSlotFocus = onTextSlotFocus,
                            // 文字页的选择状态以卡片 id 为键（切换边框时回到第一个）。
                            cardId = panelModel?.cardId,
                        )
                    }
                }
            },
        )
    }

    if (columns.compact) {
        // Not enough content width for three columns: one pane at a time, chosen
        // by the hoisted [DesktopLayoutState.narrowPane] so the top bar's
        // 「参数」 entry lands on the inspector instead of guessing.
        val pane = layout.narrowPane
        Column(modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
            ) {
                listOf("照片", "边框库", "参数").forEachIndexed { index, label ->
                    com.framemark.native.workflow.ChoiceChip(
                        label = label,
                        selected = pane == index,
                        onClick = { layout.narrowPane = index },
                    )
                }
            }
            Spacer(Modifier.height(FmSpacing.sm))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (pane) {
                    LIBRARY_PANE -> libraryContent()
                    PARAMS_PANE -> inspectorRail { layout.narrowPane = STAGE_PANE }()
                    else -> EditStage(
                        card = selectedCard, session = session, model = model, projects = projects,
                        textSlots = textSlots,
                        highlightedTextSlotKey = highlightedTextSlotKey,
                        onAddPhoto = onAddPhoto, onRemovePhoto = onRemovePhoto,
                        onOpenBatch = onOpenBatch,
                        hoveredCard = hoveredCard, holdOriginal = holdOriginal,
                        onReuseDesign = onReuseDesign, onPickBorder = onPickBorder,
                        inspectorPage = paramsPage,
                    )
                }
            }
        }
        return
    }

    val leftWidth = columns.left.dp
    val rightWidth = columns.right.dp
    val stageWidth = columns.stage.dp
    val handleWidth = FmDesktop.handleWidth
    // Centre the drag strip in the gap so it never covers a panel's edge.
    val handleInset = (gap - handleWidth) / 2

    Box(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            if (columns.showLeft) {
                DesktopPanel(width = leftWidth, edge = HairlineEdge.Right) {
                    libraryContent()
                }
            } else {
                CollapsedRail(side = "left", onExpand = onExpandLeft)
            }

            DesktopPanel(modifier = Modifier.weight(1f)) {
                EditStage(
                    card = selectedCard,
                    session = session,
                    model = model,
                    projects = projects,
                    textSlots = textSlots,
                    showExifHints = showExifHints,
                    highlightedTextSlotKey = highlightedTextSlotKey,
                    onAddPhoto = onAddPhoto,
                    onRemovePhoto = onRemovePhoto,
                    onOpenBatch = onOpenBatch,
                    hoveredCard = hoveredCard,
                    holdOriginal = holdOriginal,
                    onReuseDesign = onReuseDesign,
                    onPickBorder = onPickBorder,
                    inspectorPage = paramsPage,
                )
            }

            if (columns.showRight) {
                DesktopPanel(width = rightWidth, edge = HairlineEdge.Left) {
                    inspectorRail(onCollapseRight)()
                }
            } else {
                CollapsedRail(side = "right", onExpand = onExpandRight)
            }
        }

        if (columns.showLeft) {
            ResizeHandle(
                x = leftWidth + handleInset,
                width = handleWidth,
                onDrag = { delta ->
                    // The requested width is user intent; computeDesktopColumns
                    // clamps it to what the window can pay for on every frame.
                    layout.leftWidth = (leftWidth + delta)
                        .coerceIn(desktopLibraryMinWidth().dp, desktopLibraryMaxWidth().dp)
                },
            )
        }
        if (columns.showRight) {
            ResizeHandle(
                x = leftWidth + gap + stageWidth + handleInset,
                width = handleWidth,
                onDrag = { delta ->
                    layout.rightWidth = (rightWidth - delta)
                        .coerceIn(desktopInspectorMinWidth().dp, desktopInspectorMaxWidth().dp)
                },
            )
        }
    }
}

@Composable
private fun DesktopPanel(
    modifier: Modifier = Modifier,
    width: Dp? = null,
    edge: HairlineEdge = HairlineEdge.None,
    content: @Composable () -> Unit,
) {
    DesktopPane(
        modifier = modifier,
        width = width,
        background = FmPalette.surface,
        edge = edge,
    ) {
        content()
    }
}

@Composable
private fun CollapsedRail(side: String, onExpand: () -> Unit) {
    DesktopCollapsedRail(side = side, onExpand = onExpand)
}
/**
 * Drag strip between a rail and the stage. Width is `FmDesktop.handleWidth` and
 * the caller centres it in the 12 dp gap, so the strip never covers a panel
 * edge and the hit area is a token instead of the gap's leftover space.
 */
@Composable
private fun ResizeHandle(x: Dp, width: Dp, onDrag: (Dp) -> Unit) {
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(
        modifier = Modifier
            .offset(x = x)
            .width(width)
            .fillMaxHeight()
            .resizeCursor()
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    currentOnDrag(drag.x.toDp())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.outlineStrong),
        )
    }
}
