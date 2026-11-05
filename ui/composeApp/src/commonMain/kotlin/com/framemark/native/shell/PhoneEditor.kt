package com.framemark.native.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.framemark.native.LaunchOptions
import com.framemark.native.inspector.EditorTabList
import com.framemark.native.inspector.FmSlider
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorLoadState
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.applyDemoEdits
import com.framemark.native.inspector.parsePreciseNumber
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryLoadState
import com.framemark.native.library.LibraryPreview
import com.framemark.native.library.LibraryPreviewLoader
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.ImageRect
import com.framemark.native.workflow.NativeEngine
import com.framemark.native.workflow.NativeStagePreview
import com.framemark.native.workflow.PhotoThumbnail
import com.framemark.native.workflow.PhotoTray
import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.TrayPhoto
import com.framemark.native.workflow.WatermarkSpec
import com.framemark.native.workflow.WorkflowEnv
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * V06 phone editor, preview first: one 48 dp top bar (file name, photo counter,
 * 预设, 导出, 更多), the neutral dark stage with its lightweight tool group, an
 * on-demand 64 dp photo strip, the parameter panel and the five-tool bottom bar.
 *
 * The border library lives inside the panel ([PhoneBorderBrowser]); the other
 * tools reuse the shared inspector with phone density. Design values still flow
 * through [session] → the shell's write-back: this file changes layout and
 * routing only, never persistence.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PhoneEditorScreen(
    card: LibraryCard?,
    model: InspectorCardModel?,
    /** Stored design of the active photo; null when there is no project design yet. */
    activeDesign: DesignSnapshot? = null,
    inspectorState: InspectorLoadState,
    libraryState: LibraryLoadState,
    session: DesignSession,
    tray: PhotoTray,
    tool: EditorTool,
    onToolChange: (EditorTool) -> Unit,
    onPickPhoto: () -> Unit,
    onSelectPhoto: (TrayPhoto) -> Unit,
    onRemovePhoto: (TrayPhoto) -> Unit,
    /** Border browser tap: applies the card and keeps the browser open. */
    onOpenCard: (LibraryCard) -> Unit,
    onRetryLibrary: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenBatch: () -> Unit,
    onOpenSettings: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRetryInspector: () -> Unit,
    onQuickSavePreset: (String) -> Unit,
    panelState: LazyListState,
    watermark: WatermarkSpec? = null,
    onWatermarkChange: (WatermarkSpec?) -> Unit = {},
    onPickWatermark: () -> Unit = {},
    customizedPhotoIds: Set<Int> = emptySet(),
    onApplyAll: (() -> Unit)? = null,
    onOpenPhotoBatch: (() -> Unit)? = null,
    textSlots: List<TextSlot> = emptyList(),
    textOutputWidth: Int = 0,
    textOutputHeight: Int = 0,
    onTextSlots: ((Int, Int, List<TextSlot>) -> Unit)? = null,
) {
    // Seed declared values once per card; a preset applied beforehand wins.
    // Evidence hooks (FM_DEMO_EDITS / FM_UNDO / FM_AUTO_SAVE_PRESET) run here too.
    LaunchedEffect(model?.cardId) {
        val built = model ?: return@LaunchedEffect
        session.seed(built.controls)
        if (!LaunchOptions.demoApplied && LaunchOptions.demoEdits > 0) {
            LaunchOptions.demoApplied = true
            val applied = applyDemoEdits(session, built.controls, LaunchOptions.demoEdits)
            println("FM_DEMO_EDITS applied=$applied card=${card?.id}")
            repeat(LaunchOptions.undoSteps) { session.undo() }
            if (LaunchOptions.undoSteps > 0) println("FM_DEMO_UNDO steps=${LaunchOptions.undoSteps}")
        }
        val presetName = LaunchOptions.autoSavePreset
        if (presetName != null && !LaunchOptions.autoSaveApplied) {
            LaunchOptions.autoSaveApplied = true
            onQuickSavePreset(presetName)
        }
    }

    var zoom by remember { mutableFloatStateOf(1f) }
    var compare by remember { mutableStateOf(false) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    var renderFailure by remember(card?.id) { mutableStateOf<String?>(null) }
    var previewGeo by remember(card?.id) { mutableStateOf<PreviewGeo?>(null) }
    var confirmReset by remember(card?.id) { mutableStateOf(false) }
    // Parameter sheet: 0 collapsed (44dp) / 1 default / 2 tall.
    var sheetSnap by remember { mutableIntStateOf(1) }
    var sheetDragPx by remember { mutableStateOf<Float?>(null) }
    var photosOpen by rememberSaveable { mutableStateOf(false) }
    var focusMode by rememberSaveable { mutableStateOf(false) }
    // §2.8: stable TextSlot key of the row being edited. UI only — never written
    // into the project, a preset, a cache key or an export request.
    var focusedTextSlotKey by remember { mutableStateOf<String?>(null) }
    var fullParametersDismissed by rememberSaveable { mutableStateOf(false) }
    var fullParametersManuallyOpen by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current

    // A highlighted row belongs to one photo/card pair and to the text tool only.
    LaunchedEffect(card?.id, tray.selectedId) { focusedTextSlotKey = null }
    LaunchedEffect(tool, focusMode) {
        if (tool != EditorTool.Text || focusMode) focusedTextSlotKey = null
    }

    /** Keeps the content point under [anchor] fixed while [zoom] changes. */
    fun zoomAround(anchor: Offset, target: Float) {
        val next = target.coerceIn(0.5f, 4f)
        if (next <= 1f) {
            zoom = next
            pan = Offset.Zero
            return
        }
        val center = Offset(stageSize.width / 2f, stageSize.height / 2f)
        val scale = next / zoom
        val proposed = anchor - center - (anchor - center - pan) * scale
        val maxX = stageSize.width * (next - 1f) / 2f
        val maxY = stageSize.height * (next - 1f) / 2f
        zoom = next
        pan = Offset(proposed.x.coerceIn(-maxX, maxX), proposed.y.coerceIn(-maxY, maxY))
    }

    fun setZoom(next: Float) = zoomAround(
        Offset(stageSize.width / 2f, stageSize.height / 2f),
        next,
    )

    fun panBy(dx: Float, dy: Float) {
        if (zoom <= 1f) return
        val maxX = stageSize.width * (zoom - 1f) / 2f
        val maxY = stageSize.height * (zoom - 1f) / 2f
        pan = Offset(
            (pan.x + dx).coerceIn(-maxX, maxX),
            (pan.y + dy).coerceIn(-maxY, maxY),
        )
    }

    val tools = phoneEditorTools(phoneEditorHasLogoCapability(model))
    LaunchedEffect(tools) {
        if (tool !in tools) onToolChange(EditorTool.Border)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        val small = maxWidth < 380.dp
        val budget = computePhoneEditorHeights(
            availableHeightDp = maxHeight.value,
            fontScale = density.fontScale,
            imeVisible = imeVisible,
            tool = tool,
            photosOpen = photosOpen,
            small = small,
        )
        val autoFullParameterPage = budget.fullPage
        LaunchedEffect(autoFullParameterPage) {
            if (!autoFullParameterPage) fullParametersDismissed = false
        }
        val fullParameterPage = fullParametersManuallyOpen ||
            (autoFullParameterPage && !fullParametersDismissed)
        // A manually opened 全部参数 page uses the whole window even when the window
        // itself is tall enough for the preview layout.
        val heights = if (fullParameterPage) {
            if (budget.fullPage) {
                budget
            } else {
                PhoneEditorHeights(0f, 0f, 0f, maxHeight.value, 0f, fullPage = true)
            }
        } else if (budget.fullPage && canShowForcedPreview(maxHeight.value)) {
            // Leaving the constrained full page must not collapse the chrome to
            // zero height: keep a real top bar/tool bar and a small stage, and
            // let the sheet scroll.
            forcedPreviewHeights(maxHeight.value)
        } else {
            budget
        }
        val topBarH = heights.topBar.dp
        val stripH = heights.strip.dp
        val toolBarH = heights.toolBar.dp
        val fullHeaderH = (52f * density.fontScale.coerceIn(1f, 1.5f)).dp
        val keyboardController = LocalSoftwareKeyboardController.current
        fun closeFullParametersOrHideKeyboard() {
            if (imeVisible) {
                keyboardController?.hide()
            } else if (!autoFullParameterPage || canShowForcedPreview(maxHeight.value)) {
                fullParametersManuallyOpen = false
                fullParametersDismissed = autoFullParameterPage
            }
            // A window too short for any valid preview (below FORCED_PREVIEW_MIN_DP)
            // keeps the full page: dismissing it would render a zero-height shell.
        }
        BackHandler(enabled = fullParameterPage) {
            closeFullParametersOrHideKeyboard()
        }
        // Focus viewing hides every chrome layer; back leaves it before anything else.
        BackHandler(enabled = focusMode) { focusMode = false }

        val collapsedH = 44.dp
        val defaultSheetH = heights.sheet.dp
        // A short window can leave less than the collapsed sheet's 44dp budget
        // above the 26% stage reserve. Keep coerceIn's bounds ordered; the tall
        // snap then collapses to the default height instead of throwing.
        val tallSheetUpperH = if (fullParameterPage) {
            defaultSheetH
        } else {
            (maxHeight - topBarH - stripH - toolBarH - maxHeight * 0.26f)
                .coerceAtLeast(defaultSheetH)
        }
        val tallSheetH = if (fullParameterPage) defaultSheetH else {
            (maxHeight * 0.55f).coerceIn(defaultSheetH, tallSheetUpperH)
        }
        val snapHeights = listOf(collapsedH, defaultSheetH, tallSheetH)
        val draggedH = if (fullParameterPage) null else sheetDragPx?.let { with(density) { it.toDp() } }
        val sheetHeight = if (fullParameterPage) maxHeight else {
            draggedH ?: snapHeights[sheetSnap.coerceIn(0, 2)]
        }
        val collapsed = !fullParameterPage && sheetHeight <= collapsedH + 1.dp
        val stageEstimate = (maxHeight - topBarH - stripH - toolBarH - sheetHeight).coerceAtLeast(0.dp)
        LaunchedEffect(small, maxHeight, sheetSnap, draggedH == null, tool, photosOpen, fullParameterPage) {
            val total = maxHeight.value
            fun pct(v: Float) = "%.1f".format(if (total > 0f) v / total * 100f else 0f)
            println(
                "FM_AREA full=$fullParameterPage small=$small content=${"%.1f".format(total)}dp " +
                    "top=${topBarH.value} stage=${"%.1f".format(stageEstimate.value)} " +
                    "strip=${stripH.value} panel=${"%.1f".format(sheetHeight.value)} bar=${toolBarH.value} " +
                    "tool=${tool.label} photosOpen=$photosOpen | " +
                    "stage%=${pct(stageEstimate.value)} panel%=${pct(sheetHeight.value)}",
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Slot A: preview chrome, or the header of a page that replaces the
            // preview. Keeping it the first child means the panel below is the
            // same composition slot in both modes.
            Box(
                modifier = if (fullParameterPage) {
                    Modifier.fillMaxWidth().height(fullHeaderH)
                } else {
                    Modifier.fillMaxWidth().weight(1f)
                },
            ) {
                if (fullParameterPage) {
                    ParameterPageHeader(
                        height = fullHeaderH,
                        title = panelTitle(tool),
                        closeLabel = if (imeVisible) "收起键盘" else "返回预览",
                        onClose = ::closeFullParametersOrHideKeyboard,
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!focusMode) {
                            EditorTopBar(
                                height = topBarH,
                                fileName = tray.selected?.label ?: "未选择照片",
                                photoIndex = photoIndexOf(tray),
                                photoCount = tray.count.coerceAtLeast(1),
                                photosOpen = photosOpen,
                                onTogglePhotos = { photosOpen = !photosOpen },
                                onOpenPresets = onOpenPresets,
                                onOpenExport = onOpenExport,
                                onOpenBatch = onOpenBatch,
                                onOpenFullParameters = {
                                    fullParametersManuallyOpen = true
                                    onToolChange(EditorTool.Params)
                                },
                                onApplyAll = onApplyAll,
                                onOpenPhotoBatch = onOpenPhotoBatch,
                                onOpenSettings = onOpenSettings,
                            )
                        }
                        StageArea(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                // Focus viewing fills the window: no side inset, no chrome.
                                .then(if (focusMode) Modifier else Modifier.padding(horizontal = 4.dp)),
                            card = card,
                            model = model,
                            activeDesign = activeDesign,
                            session = session,
                            tray = tray,
                            compare = compare,
                            zoom = zoom,
                            pan = pan,
                            previewGeo = previewGeo,
                            stageSize = stageSize,
                            watermark = watermark,
                            focusedTextSlotKey = focusedTextSlotKey,
                            renderFailure = renderFailure,
                            canUndo = session.history.canUndo,
                            canRedo = session.history.canRedo,
                            focusMode = focusMode,
                            density = density,
                            onStageSize = { stageSize = it },
                            onTransform = { centroid, panChange, zoomChange ->
                                if (zoomChange != 1f) {
                                    zoomAround(centroid, zoom * zoomChange)
                                } else if (panChange != Offset.Zero) {
                                    panBy(panChange.x, panChange.y)
                                }
                            },
                            onDoubleTap = { setZoom(1f) },
                            onCompareChange = { compare = it },
                            onUndo = onUndo,
                            onRedo = onRedo,
                            onFocusToggle = { focusMode = !focusMode },
                            onOpenBorder = { onToolChange(EditorTool.Border) },
                            onRendered = { w, h, rect ->
                                previewGeo = PreviewGeo(w, h, rect)
                                renderFailure = null
                            },
                            onRenderStarted = { previewGeo = null },
                            onFailed = {
                                previewGeo = null
                                renderFailure = it
                            },
                            onTextSlots = onTextSlots,
                            onWatermarkChange = onWatermarkChange,
                        )
                        if (!focusMode && photosOpen) {
                            PhotoStrip(
                                tray = tray,
                                onSelect = onSelectPhoto,
                                onRemove = onRemovePhoto,
                                onAdd = onPickPhoto,
                                customIds = customizedPhotoIds,
                                modifier = Modifier.fillMaxWidth().height(stripH),
                            )
                        }
                    }
                }
            }
            // Slot B: the parameter panel. In preview mode it is the draggable
            // sheet; on the full parameter page it takes the rest of the window.
            // One call site either way, so text focus and scroll position survive
            // the preview/full-page switch (which the IME triggers on every focus).
            if (!focusMode) {
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
                        if (!fullParameterPage) {
                            SheetGrabber(
                                collapsed = collapsed,
                                onToggle = {
                                    sheetDragPx = null
                                    sheetSnap = if (collapsed) 1 else 0
                                },
                                onDragStart = {
                                    sheetDragPx = with(density) { sheetHeight.toPx() }
                                },
                                onDrag = { dy ->
                                    val lo = with(density) { collapsedH.toPx() }
                                    val hi = with(density) { snapHeights[2].toPx() }
                                    sheetDragPx = ((sheetDragPx ?: lo) - dy).coerceIn(lo, hi)
                                },
                                onDragEnd = {
                                    val current = sheetDragPx
                                    if (current != null) {
                                        val px = snapHeights.map { with(density) { it.toPx() } }
                                        sheetSnap = px.indices.minByOrNull { abs(px[it] - current) } ?: 1
                                        sheetDragPx = null
                                    }
                                },
                            )
                        }
                        if (collapsed) {
                            CollapsedPanelRow(
                                label = collapsedPanelLabel(tool, card),
                                onExpand = {
                                    sheetDragPx = null
                                    sheetSnap = 1
                                },
                            )
                        } else {
                            EditorPanel(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                tool = tool,
                                card = card,
                                model = model,
                                inspectorState = inspectorState,
                                libraryState = libraryState,
                                session = session,
                                tray = tray,
                                panelState = panelState,
                                textSlots = textSlots,
                                textOutputWidth = textOutputWidth,
                                textOutputHeight = textOutputHeight,
                                previewGeo = previewGeo,
                                watermark = watermark,
                                onRetryInspector = onRetryInspector,
                                onTextSlotFocus = { key ->
                                    println("FM_TEXT_FOCUS key=$key tool=$tool")
                                    focusedTextSlotKey = key
                                },
                                onWatermarkChange = onWatermarkChange,
                                onPickWatermark = onPickWatermark,
                                onOpenBorder = { onToolChange(EditorTool.Border) },
                                onOpenCard = onOpenCard,
                                onRetryLibrary = onRetryLibrary,
                                onRequestReset = { confirmReset = true },
                            )
                        }
                    }
                }
                if (!fullParameterPage) {
                    FmEditorToolBar(
                        height = toolBarH,
                        tool = tool,
                        availableTools = tools,
                        onSelect = onToolChange,
                    )
                }
            }
        }
    }

    if (confirmReset && card != null) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("重置这套边框的调整？") },
            text = { Text("只清空当前边框的参数修改；照片和水印不变。可以撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    session.resetCard(card.id)
                    println("FM_CARD_RESET ${card.id}")
                }) { Text("重置") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("取消") }
            },
        )
    }
}

/** 1-based index of the selected strip photo; 1 when the strip has no selection yet. */
private fun photoIndexOf(tray: PhotoTray): Int {
    val index = tray.items.indexOfFirst { it.id == tray.selectedId }
    return if (index >= 0) index + 1 else 1
}

/** Full parameter page title: the page is the current tool rendered full height. */
private fun panelTitle(tool: EditorTool): String = when (tool) {
    EditorTool.Border -> "边框"
    EditorTool.Params -> "全部参数"
    EditorTool.Text -> "文字"
    EditorTool.Logo -> "Logo"
    EditorTool.Watermark -> "图片水印"
}

private fun collapsedPanelLabel(tool: EditorTool, card: LibraryCard?): String = when (tool) {
    EditorTool.Border -> "边框 · ${card?.name ?: "未选择"}"
    EditorTool.Watermark -> "图片水印"
    else -> tool.label
}

/** Last native frame's pixel size plus the engine photo box (overlay anchor). */
private data class PreviewGeo(val w: Int, val h: Int, val photoRect: ImageRect?)

// ---------------------------------------------------------------------------
// Panel
// ---------------------------------------------------------------------------

/**
 * Panel content for the active tool. Border → the in-panel browser; 调整 → the
 * card head + Border/Layout/Theme sections + reference/output footer; 文字 →
 * renderer-backed rows with the focus callback; Logo → the N1 LogoBrandPanel via
 * `EditorTabList(tab = Logo)`; 图片水印 → the watermark panel.
 */
@Composable
private fun EditorPanel(
    modifier: Modifier,
    tool: EditorTool,
    card: LibraryCard?,
    model: InspectorCardModel?,
    inspectorState: InspectorLoadState,
    libraryState: LibraryLoadState,
    session: DesignSession,
    tray: PhotoTray,
    panelState: LazyListState,
    textSlots: List<TextSlot>,
    textOutputWidth: Int,
    textOutputHeight: Int,
    previewGeo: PreviewGeo?,
    watermark: WatermarkSpec?,
    onRetryInspector: () -> Unit,
    onTextSlotFocus: (String?) -> Unit,
    onWatermarkChange: (WatermarkSpec?) -> Unit,
    onPickWatermark: () -> Unit,
    onOpenBorder: () -> Unit,
    onOpenCard: (LibraryCard) -> Unit,
    onRetryLibrary: () -> Unit,
    onRequestReset: () -> Unit,
) {
    if (card == null && tool != EditorTool.Border) {
        PanelRequiresBorder(onOpenBorder = onOpenBorder, modifier = modifier)
        return
    }
    when (tool) {
        EditorTool.Border -> PhoneBorderBrowser(
            loadState = libraryState,
            selectedCardId = card?.id,
            onOpenCard = onOpenCard,
            onRetry = onRetryLibrary,
            modifier = modifier,
        )
        EditorTool.Params -> EditorTabList(
            state = panelState,
            tab = InspectorSection.Layout,
            model = model,
            loadState = inspectorState,
            session = session,
            onRetry = onRetryInspector,
            initialItem = LaunchOptions.editorItem,
            modifier = modifier,
            header = {
                CardHead(
                    card = card,
                    canReset = card != null && session.hasOverrides(card.id),
                    onReset = onRequestReset,
                )
            },
            pinnedTabs = null,
            footer = {
                // Export size = current 导出长边 × the preview aspect (ratio changes
                // already show up in the preview).
                val edge = session.export.longEdge
                val pw = previewGeo?.w ?: 0
                val ph = previewGeo?.h ?: 0
                val outW = if (pw > 0) edge else 0
                val outH = if (pw > 0 && ph > 0) {
                    (edge.toFloat() * ph / pw).roundToInt()
                } else {
                    0
                }
                ReferenceAndOutput(
                    card = card,
                    outputW = outW,
                    outputH = outH,
                    exportEdge = edge,
                    fileName = tray.selected?.label ?: "示例照片",
                )
            },
            extraSections = listOf(InspectorSection.Theme),
            dense = true,
        )
        EditorTool.Text -> EditorTabList(
            state = panelState,
            tab = InspectorSection.Text,
            model = model,
            loadState = inspectorState,
            session = session,
            onRetry = onRetryInspector,
            modifier = modifier,
            pinnedTabs = null,
            textSlots = textSlots,
            outputWidth = textOutputWidth,
            outputHeight = textOutputHeight,
            onTextSlotFocus = onTextSlotFocus,
            dense = true,
        )
        EditorTool.Logo -> EditorTabList(
            state = panelState,
            tab = InspectorSection.Logo,
            model = model,
            loadState = inspectorState,
            session = session,
            onRetry = onRetryInspector,
            modifier = modifier,
            pinnedTabs = null,
            dense = true,
        )
        EditorTool.Watermark -> WatermarkPanel(
            spec = watermark,
            onChange = onWatermarkChange,
            onPick = onPickWatermark,
            modifier = modifier,
        )
    }
}

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

/** The 44 dp collapsed sheet still names what a tap will expand. */
@Composable
private fun CollapsedPanelRow(label: String, onExpand: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 26.dp)
            .clickable(onClick = onExpand)
            .padding(horizontal = FmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = FmType.labelMedium,
            color = FmPalette.onSurfaceSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "展开",
            style = FmType.labelSmall,
            color = FmPalette.primaryStrong,
        )
    }
}

/** Panel card head: eyebrow, name, description, 重置. */
@Composable
private fun CardHead(card: LibraryCard?, canReset: Boolean, onReset: () -> Unit) {
    val eyebrow = when {
        card == null -> "边框"
        card.vendor != null || card.brand != null -> "系统相机"
        else -> card.categoryName.ifBlank { "边框" }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = eyebrow,
                style = FmType.labelSmall.copy(fontSize = 11.sp),
                color = FmPalette.primaryStrong,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "重置",
                style = FmType.labelSmall,
                color = if (canReset) FmPalette.onSurfaceSecondary else FmPalette.onSurfaceFaint,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.surfaceMuted)
                    .clickable(enabled = canReset, onClick = onReset)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = card?.name ?: "未选择边框",
            style = FmType.titleMedium,
            color = FmPalette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "按厂商相机/相册的原始水印配置渲染，保留真实版式与素材。",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
        )
        Spacer(Modifier.height(FmSpacing.md))
    }
}

/** Panel footer: original reference thumb + output size/filename. */
@Composable
private fun ReferenceAndOutput(
    card: LibraryCard?,
    outputW: Int,
    outputH: Int,
    exportEdge: Int,
    fileName: String,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = FmSpacing.md)) {
        Text("原版参考", style = FmType.labelMedium, color = FmPalette.onSurface)
        Text(
            text = "供应商原版水印，仅作对照；成片以画布为准",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
        )
        Spacer(Modifier.height(FmSpacing.xs))
        if (card != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(FmRadii.tile))
                    .background(FmPalette.surfaceMuted)
                    .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.tile)),
            ) {
                LibraryPreview(card = card, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(FmSpacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (outputW > 0 && outputH > 0) {
                    "输出 $outputW × $outputH · 长边 $exportEdge"
                } else {
                    "输出 —"
                },
                style = FmType.labelMedium,
                color = FmPalette.onSurfaceSecondary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = fileName,
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Constrained phone windows use a dedicated parameter page with the same editor state. */
/**
 * A short window or a large font uses the full parameter page; the keyboard does
 * **not** any more. The shell already applies `WindowInsets.safeDrawing` (which
 * includes the IME), so when the keyboard opens the measured height shrinks and
 * the preview stays visible above it — the user must be able to see the photo
 * while typing. `imeVisible` is kept in the signature for the existing
 * callers/tests and for future policies.
 */
internal fun useFullPhoneParameterPage(
    availableHeightDp: Float,
    fontScale: Float,
    imeVisible: Boolean,
): Boolean = availableHeightDp < 480f || fontScale >= 1.5f

@Composable
private fun ParameterPageHeader(
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
private val SheetRadius = 22.dp

// ---------------------------------------------------------------------------
// Top bar (one 48 dp layer)
// ---------------------------------------------------------------------------

@Composable
private fun EditorTopBar(
    height: Dp,
    fileName: String,
    photoIndex: Int,
    photoCount: Int,
    photosOpen: Boolean,
    onTogglePhotos: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenBatch: () -> Unit,
    onOpenFullParameters: () -> Unit,
    onApplyAll: (() -> Unit)? = null,
    onOpenPhotoBatch: (() -> Unit)? = null,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(FmPalette.surface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = fileName,
            style = FmType.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = FmPalette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        TopBarAction(
            description = if (photosOpen) "收起照片条" else "展开照片条",
            text = "$photoIndex/$photoCount ▾",
            selected = photosOpen,
            onClick = onTogglePhotos,
        )
        // 预设 is a first-class action: never buried in the overflow menu.
        TopBarAction(description = "预设", text = "预设", onClick = onOpenPresets)
        TopBarAction(description = "导出当前照片", text = "导出", primary = true, onClick = onOpenExport)
        EditorOverflowMenu(
            onOpenBatch = onOpenBatch,
            onOpenFullParameters = onOpenFullParameters,
            onApplyAll = onApplyAll,
            onOpenPhotoBatch = onOpenPhotoBatch,
            onOpenSettings = onOpenSettings,
        )
    }
}

/**
 * A 48 dp minimum hit target with a 36 dp visible pill, so 导出/更多 keep the V06
 * density without giving up the touch size. The pill grows with the label when the
 * system font is enlarged; a fixed 48 dp pill clipped 预设/导出 at fontScale 1.5.
 */
@Composable
private fun TopBarAction(
    description: String,
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    selected: Boolean = false,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .widthIn(min = 48.dp)
            .height(48.dp)
            .semantics {
                contentDescription = description
                role = Role.Button
                if (selected) stateDescription = "已展开"
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                // The pill wraps its label (min 48 dp outer target) instead of
                // forcing the 12 sp label into a fixed 48 dp box.
                .height(36.dp)
                .clip(shape)
                .background(
                    when {
                        primary -> FmPalette.primary
                        selected -> FmPalette.primarySoft
                        else -> FmPalette.surface
                    },
                )
                .border(
                    width = 1.dp,
                    color = if (primary) Color(0x29FFFFFF) else FmPalette.outline,
                    shape = shape,
                )
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = FmType.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                color = if (primary) FmPalette.onPrimary else FmPalette.onSurfaceSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EditorOverflowMenu(
    onOpenBatch: () -> Unit,
    onOpenFullParameters: () -> Unit,
    onApplyAll: (() -> Unit)? = null,
    onOpenPhotoBatch: (() -> Unit)? = null,
    onOpenSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TopBarAction(
            description = "更多操作",
            text = "⋯",
            selected = expanded,
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(184.dp)
                .background(FmPalette.surface),
        ) {
            // 撤销/重做 live in the stage tool group; no duplicate here.
            OverflowItem(text = if (onOpenPhotoBatch != null) "批量导出 · 按照片" else "批量导出") {
                expanded = false
                if (onOpenPhotoBatch != null) onOpenPhotoBatch() else onOpenBatch()
            }
            OverflowItem(text = "全部参数") {
                expanded = false
                onOpenFullParameters()
            }
            if (onApplyAll != null) {
                OverflowItem(text = "应用到全部照片") {
                    expanded = false
                    onApplyAll()
                }
            }
            OverflowDivider()
            OverflowItem(text = "设置") {
                expanded = false
                onOpenSettings()
            }
        }
    }
}

@Composable
private fun OverflowItem(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                style = FmType.bodyMedium,
                color = if (enabled) FmPalette.onSurface else FmPalette.onSurfaceFaint,
            )
        },
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun OverflowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(FmPalette.outline),
    )
}

// ---------------------------------------------------------------------------
// Stage
// ---------------------------------------------------------------------------

/** Neutral dark stage radius (the old `.canvas-viewport` was 12; V06 wants 3–8). */
private val StageRadius = 8.dp

/** Bottom band reserved for the stage tools; the photo never enters it. */
private val StageToolsH = 48.dp

/**
 * The dark photo stage: gestures (pinch / drag while zoomed / double-tap reset),
 * the live watermark overlay, the compare-on-hold view, the render-failure badge
 * and the lightweight undo / redo / 原图按住 / 专注 tool group.
 */
@Composable
private fun StageArea(
    modifier: Modifier,
    card: LibraryCard?,
    model: InspectorCardModel?,
    activeDesign: DesignSnapshot?,
    session: DesignSession,
    tray: PhotoTray,
    compare: Boolean,
    zoom: Float,
    pan: Offset,
    previewGeo: PreviewGeo?,
    stageSize: IntSize,
    watermark: WatermarkSpec?,
    focusedTextSlotKey: String?,
    renderFailure: String?,
    canUndo: Boolean,
    canRedo: Boolean,
    focusMode: Boolean,
    density: Density,
    onStageSize: (IntSize) -> Unit,
    onTransform: (Offset, Offset, Float) -> Unit,
    onDoubleTap: () -> Unit,
    onCompareChange: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFocusToggle: () -> Unit,
    onOpenBorder: () -> Unit,
    onRendered: (Int, Int, ImageRect?) -> Unit,
    onRenderStarted: () -> Unit,
    onFailed: (String) -> Unit,
    onTextSlots: ((Int, Int, List<TextSlot>) -> Unit)?,
    onWatermarkChange: (WatermarkSpec?) -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(StageRadius))
            .background(FmPalette.stage),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 10 top / 12 sides / 48 bottom: the tool band is reserved, so the
                // photo and the buttons can never overlap.
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = StageToolsH),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { onStageSize(it) }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, panChange, zoomChange, _ ->
                            onTransform(centroid, panChange, zoomChange)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onDoubleTap() })
                    }
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
                contentAlignment = Alignment.Center,
            ) {
                val comparePath = tray.selected?.path ?: WorkflowEnv.platform?.samplePhotoPath()
                if (compare && comparePath != null) {
                    // Hold-to-compare: the raw photo, no design frame, no fake overlay.
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        PhotoThumbnail(
                            path = comparePath,
                            maxEdge = 2048,
                            cornerRadius = 0.dp,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Text(
                            text = "原图",
                            style = FmType.labelSmall,
                            color = FmPalette.stageInk,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(FmSpacing.sm)
                                .clip(RoundedCornerShape(FmRadii.pill))
                                .background(FmPalette.stageLine)
                                .padding(horizontal = FmSpacing.sm, vertical = 3.dp),
                        )
                    }
                } else when {
                    card == null -> StagePlaceholder(tray = tray, onOpenBorder = onOpenBorder)
                    NativeEngine.supports(card.id) -> NativeStagePreview(
                        // The phone stage deliberately omits `overlay`: WatermarkOverlay
                        // is the single live watermark layer. Rendering it here as well
                        // would make the throttled native frame show a second watermark.
                        card = card,
                        // The project design is the source of truth once this photo
                        // has one: it carries the flat slot overrides the text panel
                        // writes, so a restart renders the stored rows instead of a
                        // stale cached frame.
                        design = activeDesign?.takeIf { it.cardId == card.id }
                            ?: NativeEngine.designOf(card, model, session),
                        modifier = Modifier.fillMaxSize(),
                        onRendered = onRendered,
                        onRenderStarted = onRenderStarted,
                        onFailed = onFailed,
                        onTextSlots = onTextSlots,
                        highlightedTextSlotKey = focusedTextSlotKey,
                    )
                    else -> Box(modifier = Modifier.fillMaxSize()) {
                        LibraryPreview(card = card, modifier = Modifier.fillMaxSize())
                        Text(
                            text = "静态预览",
                            style = FmType.labelSmall,
                            color = FmPalette.stageInk,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(FmSpacing.md)
                                .clip(RoundedCornerShape(FmRadii.pill))
                                .background(FmPalette.stageLine)
                                .padding(horizontal = FmSpacing.sm, vertical = 3.dp),
                        )
                    }
                }
                if (!compare) {
                    watermark?.let { spec ->
                        previewGeo?.let { geo ->
                            WatermarkOverlay(
                                spec = spec,
                                geo = geo,
                                stage = stageSize,
                                density = density,
                                onChange = onWatermarkChange,
                            )
                        }
                    }
                }
            }
            if (renderFailure != null) {
                Text(
                    text = "预览失败 · 显示静态图",
                    style = FmType.labelSmall,
                    color = FmPalette.stageInk,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(FmSpacing.md)
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.stageLine)
                        .padding(horizontal = FmSpacing.sm, vertical = 3.dp),
                )
            }
        }
        StageToolRow(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(StageToolsH),
            canUndo = canUndo,
            canRedo = canRedo,
            comparing = compare,
            focusMode = focusMode,
            onUndo = onUndo,
            onRedo = onRedo,
            onCompareChange = onCompareChange,
            onFocusToggle = onFocusToggle,
        )
        if (focusMode) {
            ExitFocusChip(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(FmSpacing.sm),
                onClick = onFocusToggle,
            )
        }
    }
}

@Composable
private fun StageToolRow(
    modifier: Modifier,
    canUndo: Boolean,
    canRedo: Boolean,
    comparing: Boolean,
    focusMode: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCompareChange: (Boolean) -> Unit,
    onFocusToggle: () -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StageToolButton(
            label = "撤销",
            description = "撤销",
            enabled = canUndo,
            stateText = if (canUndo) null else "没有可撤销的修改",
            onClick = onUndo,
        ) { tint -> UndoGlyph(tint = tint, redo = false) }
        StageToolButton(
            label = "重做",
            description = "重做",
            enabled = canRedo,
            stateText = if (canRedo) null else "没有可重做的修改",
            onClick = onRedo,
        ) { tint -> UndoGlyph(tint = tint, redo = true) }
        // 原图 is press-and-hold: press shows the raw photo, release returns.
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = StageToolsH)
                .clip(RoundedCornerShape(FmRadii.input))
                .semantics(mergeDescendants = true) {
                    contentDescription = "按住查看原图"
                    role = Role.Button
                    stateDescription = if (comparing) "正在显示原图" else "松开回到设计"
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onCompareChange(true)
                            tryAwaitRelease()
                            onCompareChange(false)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EyeGlyph(tint = if (comparing) FmPalette.primaryStrong else FmPalette.stageInk)
                Text(
                    text = "原图",
                    style = FmType.labelSmall.copy(fontSize = 11.sp),
                    color = if (comparing) FmPalette.primaryStrong else FmPalette.stageInk,
                    maxLines = 1,
                )
            }
        }
        StageToolButton(
            label = if (focusMode) "退出" else "专注",
            description = if (focusMode) "退出专注" else "专注查看",
            enabled = true,
            stateText = null,
            onClick = onFocusToggle,
        ) { tint -> FocusGlyph(tint) }
    }
}

/**
 * One stage tool: the glyph+label sit in a 30 dp block inside a 48 dp tall tap
 * band. Disabled tools keep the band and explain why they cannot be pressed.
 */
@Composable
private fun StageToolButton(
    label: String,
    description: String,
    enabled: Boolean,
    stateText: String?,
    onClick: () -> Unit,
    glyph: @Composable (Color) -> Unit,
) {
    val tint = if (enabled) FmPalette.stageInk else FmPalette.stageMuted.copy(alpha = 0.55f)
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = StageToolsH)
            .clip(RoundedCornerShape(FmRadii.input))
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                if (stateText != null) stateDescription = stateText
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            glyph(tint)
            Text(
                text = label,
                style = FmType.labelSmall.copy(fontSize = 11.sp),
                color = tint,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ExitFocusChip(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.stageLine)
            .border(1.dp, FmPalette.stageMuted.copy(alpha = 0.5f), RoundedCornerShape(FmRadii.pill))
            .semantics {
                contentDescription = "退出专注"
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .padding(horizontal = FmSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text("退出专注", style = FmType.labelMedium, color = FmPalette.stageInk, maxLines = 1)
    }
}

/** Curved undo / redo arrow. */
@Composable
private fun UndoGlyph(tint: Color, redo: Boolean) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val stroke = 1.6.dp.toPx()
        val startX = if (redo) w * 0.84f else w * 0.16f
        val endX = if (redo) w * 0.16f else w * 0.84f
        val path = Path().apply {
            moveTo(startX, w * 0.42f)
            cubicTo(w * 0.2f, w * 0.1f, w * 0.8f, w * 0.1f, endX, w * 0.42f)
        }
        drawPath(path, tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawLine(tint, Offset(endX, w * 0.42f), Offset(endX, w * 0.72f), stroke, StrokeCap.Round)
        val tip = w * 0.74f
        drawLine(
            tint,
            Offset(endX, tip),
            Offset(endX - (if (redo) -1f else 1f) * w * 0.16f, w * 0.6f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            tint,
            Offset(endX, tip),
            Offset(endX + (if (redo) -1f else 1f) * w * 0.16f, w * 0.6f),
            stroke,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun EyeGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(15.dp)) {
        val w = size.width
        val stroke = 1.3.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.06f, w * 0.5f)
            cubicTo(w * 0.3f, w * 0.18f, w * 0.7f, w * 0.18f, w * 0.94f, w * 0.5f)
            cubicTo(w * 0.7f, w * 0.82f, w * 0.3f, w * 0.82f, w * 0.06f, w * 0.5f)
            close()
        }
        drawPath(path, tint, style = Stroke(width = stroke))
        drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.5f, w * 0.5f))
    }
}

/** Four corner arms: fit-to-window / focus viewing. */
@Composable
private fun FocusGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(15.dp)) {
        val w = size.width
        val stroke = 1.5.dp.toPx()
        val arm = w * 0.34f
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(tint, Offset(x, y), Offset(x + dx * arm, y), stroke, StrokeCap.Round)
            drawLine(tint, Offset(x, y), Offset(x, y + dy * arm), stroke, StrokeCap.Round)
        }
        corner(w * 0.1f, w * 0.1f, 1f, 1f)
        corner(w * 0.9f, w * 0.1f, -1f, 1f)
        corner(w * 0.1f, w * 0.9f, 1f, -1f)
        corner(w * 0.9f, w * 0.9f, -1f, -1f)
    }
}

@Composable
private fun StagePlaceholder(tray: PhotoTray, onOpenBorder: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PhotoThumbnail(
            path = tray.selected?.path,
            maxEdge = 1080,
            cornerRadius = 0.dp,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = "选择边框",
            style = FmType.labelMedium,
            color = FmPalette.onPrimary,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.primary)
                .semantics {
                    contentDescription = "选择边框"
                    role = Role.Button
                }
                .clickable(onClick = onOpenBorder)
                .padding(horizontal = FmSpacing.xl, vertical = FmSpacing.md),
        )
    }
}

/**
 * Live watermark on the transformed stage: Compose draws it while the user drags /
 * slides, so the feedback tracks pinch/pan instantly; the engine gets the same
 * rect as an `OverlaySpec` for export.
 */
@Composable
private fun BoxScope.WatermarkOverlay(
    spec: WatermarkSpec,
    geo: PreviewGeo,
    stage: IntSize,
    density: Density,
    onChange: (WatermarkSpec?) -> Unit,
) {
    if (geo.w <= 0 || geo.h <= 0 || stage.width <= 0 || stage.height <= 0) return
    // The overlay lives inside the stage's photo box, which has no extra padding:
    // the draw box is exactly the box the preview fills with ContentScale.Fit.
    val availW = stage.width.toFloat()
    val availH = stage.height.toFloat()
    if (availW <= 1f || availH <= 1f) return
    val scale = minOf(availW / geo.w, availH / geo.h)
    val left = (availW - geo.w * scale) / 2f
    val top = (availH - geo.h * scale) / 2f
    val pr = geo.photoRect ?: run {
        // Never fall back to the full canvas: that would place the watermark on
        // the border/footer when the engine has not reported the photo box yet.
        return
    }
    val photoW = pr.w * scale
    val photoH = pr.h * scale
    if (photoW <= 1f || photoH <= 1f) return
    val aspect = photoW / photoH

    // The Compose layer is the only phone watermark layer. Draw every gesture
    // sample locally, but publish the project value on a fixed 10 Hz cadence so
    // a drag cannot force the whole editor/project to recompose per touch sample.
    var liveSpec by remember(spec.path) { mutableStateOf(spec) }
    var dragging by remember(spec.path) { mutableStateOf(false) }
    val latestOnChange by rememberUpdatedState(onChange)
    val commitSignal = remember(spec.path) { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(spec) {
        if (!dragging && liveSpec != spec) liveSpec = spec
    }

    LaunchedEffect(commitSignal) {
        val intervalMs = 100L
        var lastCommitAt: TimeMark? = null
        while (true) {
            commitSignal.receive()
            val remaining = lastCommitAt?.let {
                intervalMs - it.elapsedNow().inWholeMilliseconds
            } ?: 0L
            if (remaining > 0L) delay(remaining)
            latestOnChange(liveSpec)
            lastCommitAt = TimeSource.Monotonic.markNow()
            // Every wake-up is level-triggered by liveSpec; intermediate samples
            // collapse instead of producing one project commit each.
            while (commitSignal.tryReceive().isSuccess) {
                // Coalesced into the next fixed-interval commit.
            }
        }
    }

    val r = liveSpec.rect(aspect)
    val box = liveSpec.aabb(aspect)

    // The rotated image is clipped to its AABB, not to the unrotated draw rect;
    // otherwise the four corners would be cut off at 45°/90°.
    val ow = r[2] * photoW
    val oh = r[3] * photoH
    val ox = (r[0] - box[0]) * photoW
    val oy = (r[1] - box[1]) * photoH
    val aw = box[2] * photoW
    val ah = box[3] * photoH

    LaunchedEffect(aspect, liveSpec.photoAspect) {
        if (abs(aspect - liveSpec.photoAspect) > 0.01f) {
            liveSpec = liveSpec.copy(photoAspect = aspect)
            commitSignal.trySend(Unit)
        }
    }

    Box(
        modifier = Modifier
            // The stage Box centers its children; without this the photo-box
            // offset would be added to the centered position and the watermark
            // would drift down onto the border/footer.
            .align(Alignment.TopStart)
            .offset {
                IntOffset(
                    (left + (pr.x + box[0] * pr.w) * scale).roundToInt(),
                    (top + (pr.y + box[1] * pr.h) * scale).roundToInt(),
                )
            }
            .size(with(density) { aw.toDp() }, with(density) { ah.toDp() })
            .clipToBounds(),
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(ox.roundToInt(), oy.roundToInt()) }
                .size(with(density) { ow.toDp() }, with(density) { oh.toDp() })
                .graphicsLayer { rotationZ = liveSpec.rotation }
                .pointerInput(liveSpec.path, aspect) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            commitSignal.trySend(Unit)
                        },
                        onDragCancel = {
                            dragging = false
                            commitSignal.trySend(Unit)
                        },
                    ) { change, drag ->
                        change.consume()
                        liveSpec = liveSpec.withRectNudge(
                            drag.x / photoW,
                            drag.y / photoH,
                            aspect,
                        )
                        commitSignal.trySend(Unit)
                    }
                },
        ) {
            // The tray thumbnail path re-encodes to JPEG (no alpha); the overlay
            // must keep the PNG alpha so it reads the same as the exported file.
            val wmBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                initialValue = null,
                key1 = liveSpec.path,
            ) {
                value = withContext(Dispatchers.Default) {
                    runCatching {
                        WorkflowEnv.platform?.readBytes(liveSpec.path)?.let {
                            LibraryPreviewLoader.decode(it)
                        }
                    }.getOrNull()
                }
            }
            val ready = wmBitmap
            if (ready != null) {
                Image(
                    bitmap = ready,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().alpha(liveSpec.opacity),
                )
            }
        }
    }
}
// ---------------------------------------------------------------------------
// Photo strip
// ---------------------------------------------------------------------------

@Composable
private fun PhotoStrip(
    tray: PhotoTray,
    onSelect: (TrayPhoto) -> Unit,
    onRemove: (TrayPhoto) -> Unit,
    onAdd: () -> Unit,
    customIds: Set<Int> = emptySet(),
    modifier: Modifier = Modifier,
) {
    // Collapsed by default: the caller only composes this when the strip is open.
    Box(
        modifier = modifier
            .background(FmPalette.surface)
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tray.items.forEachIndexed { index, photo ->
                PhotoTile(
                    photo = photo,
                    index = index + 1,
                    selected = photo.id == tray.selected?.id,
                    customized = photo.id in customIds,
                    onSelect = { onSelect(photo) },
                    onRemove = { onRemove(photo) },
                )
            }
            AddPhotoTile(onAdd = onAdd)
        }
    }
}

@Composable
private fun PhotoTile(
    photo: TrayPhoto,
    index: Int,
    selected: Boolean,
    customized: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    // Keep selection and removal in separate gestures. An embedded remove badge
    // receives an expanded touch target that could swallow the tile centre.
    val isSelected = selected
    var showActions by remember(photo.id) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 48.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(FmPalette.surfaceMuted)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) FmPalette.primary else FmPalette.outline,
                shape = RoundedCornerShape(7.dp),
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "${photo.label}，第${index}张，长按管理照片"
                role = Role.RadioButton
                this.selected = isSelected
                stateDescription = if (isSelected) "当前照片" else "未选中"
            }
            .combinedClickable(
                onClick = onSelect,
                onLongClickLabel = "管理照片",
                onLongClick = { showActions = true },
            ),
    ) {
        PhotoThumbnail(
            path = photo.path ?: WorkflowEnv.platform?.samplePhotoPath(),
            maxEdge = 192,
            cornerRadius = 6.dp,
            modifier = Modifier.fillMaxSize(),
        )
        if (customized) {
            Text(
                text = "改",
                style = FmType.labelSmall.copy(fontSize = 10.sp),
                color = FmPalette.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(FmPalette.primaryStrong)
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
        Text(
            text = index.toString().padStart(2, '0'),
            style = FmType.labelSmall.copy(fontSize = 10.sp),
            color = if (isSelected) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(3.dp),
        )
        DropdownMenu(
            expanded = showActions,
            onDismissRequest = { showActions = false },
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("移出项目")
                        Text("保留原文件", style = FmType.labelSmall)
                    }
                },
                onClick = { showActions = false; onRemove() },
            )
            DropdownMenuItem(
                text = { Text("取消") },
                onClick = { showActions = false },
            )
        }
    }
}

private fun Modifier.dashedBorder(color: Color, radius: androidx.compose.ui.unit.Dp): Modifier =
    drawBehind {
        val width = 1.5.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(width / 2f, width / 2f),
            size = Size(size.width - width, size.height - width),
            cornerRadius = CornerRadius(radius.toPx()),
            style = Stroke(
                width = width,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
            ),
        )
    }

@Composable
private fun AddPhotoTile(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .size(width = 52.dp, height = 48.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(FmPalette.surface)
            .dashedBorder(Color(0xFF8A94A3), 7.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "添加照片"
                role = Role.Button
            }
            .clickable(onClick = onAdd),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "＋",
            style = FmType.titleSmall.copy(fontSize = 14.sp),
            color = FmPalette.onSurfaceMuted,
        )
        Text(
            "添加",
            style = FmType.labelSmall.copy(fontSize = 10.sp),
            color = FmPalette.onSurfaceMuted,
        )
    }
}

// ---------------------------------------------------------------------------
// Sheet grabber
// ---------------------------------------------------------------------------

/** Old `.sheet-grabber`: tap collapses / expands the parameter sheet. */
@Composable
private fun SheetGrabber(
    collapsed: Boolean,
    onToggle: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    // Keying the detector on `collapsed` would cancel it the moment a drag leaves
    // the collapsed height; keep it stable and read the latest callbacks instead.
    val toggle by rememberUpdatedState(onToggle)
    val dragStart by rememberUpdatedState(onDragStart)
    val dragMove by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            // One detector for tap (toggle) and vertical drag (snap), so the click
            // and the drag can never steal each other's gestures.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragging = false
                    var total = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        // previousPosition is in this frame's node space, so the
                        // delta survives the sheet resizing under the finger.
                        val dy = change.position.y - change.previousPosition.y
                        total += dy
                        if (!dragging && abs(total) > viewConfiguration.touchSlop) {
                            dragging = true
                            dragStart()
                        }
                        if (dragging) {
                            change.consume()
                            dragMove(dy)
                        }
                    }
                    if (dragging) dragEnd() else toggle()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(Color(0xFF3B3B42)),
        )
    }
}

// ---------------------------------------------------------------------------
// Watermark tool
// ---------------------------------------------------------------------------

@Composable
private fun WatermarkPanel(
    spec: WatermarkSpec?,
    onChange: (WatermarkSpec?) -> Unit,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(FmSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("水印图层", style = FmType.titleSmall, color = FmPalette.onSurface)
            Spacer(Modifier.width(FmSpacing.sm))
            Text(
                text = "每张照片独立",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.surfaceMuted)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(FmSpacing.xs))
        Text(
            text = "透明 PNG/WebP，压在照片区域内、边框文字之下。",
            style = FmType.bodyMedium,
            color = FmPalette.onSurfaceMuted,
        )
        Spacer(Modifier.height(FmSpacing.md))

        if (spec == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(FmRadii.input))
                    .background(FmPalette.surfaceMuted)
                    .border(1.dp, FmPalette.outlineStrong, RoundedCornerShape(FmRadii.input))
                    .semantics {
                        contentDescription = "上传水印素材"
                        role = Role.Button
                    }
                    .clickable(onClick = onPick)
                    .padding(FmSpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("＋", style = FmType.titleMedium, color = FmPalette.onSurfaceMuted)
                Spacer(Modifier.width(FmSpacing.sm))
                Column {
                    Text("上传水印", style = FmType.bodyMedium, color = FmPalette.onSurfaceSecondary)
                    Text(
                        "只在当前这张照片上显示。",
                        style = FmType.labelSmall,
                        color = FmPalette.onSurfaceMuted,
                    )
                }
            }
            Spacer(Modifier.height(FmSpacing.md))
            return@Column
        }

        val aspect = spec.photoAspect
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(FmRadii.tile))
                    .background(FmPalette.surfaceMuted)
                    .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.tile)),
            ) {
                PhotoThumbnail(
                    path = spec.path,
                    maxEdge = 192,
                    cornerRadius = 0.dp,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                )
            }
            Spacer(Modifier.width(FmSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "水印素材",
                    style = FmType.bodyMedium,
                    color = FmPalette.onSurface,
                )
                Text(
                    text = "宽 ${(spec.width * 100).roundToInt()}% · " +
                        "不透明度 ${(spec.opacity * 100).roundToInt()}%",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
            }
            SmallAction("替换", onClick = onPick)
            Spacer(Modifier.width(FmSpacing.xs))
            SmallAction("删除", onClick = { onChange(null) })
        }

        Spacer(Modifier.height(FmSpacing.md))
        PanelLabel("位置")
        Spacer(Modifier.height(FmSpacing.xs))
        AnchorGrid(selected = spec.anchor) { slot ->
            onChange(spec.copy(anchor = slot, dx = 0f, dy = 0f))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "九宫格定锚点，下面微调或直接拖动预览。",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
        )

        Spacer(Modifier.height(FmSpacing.md))
        PanelLabel("微调")
        Spacer(Modifier.height(FmSpacing.xs))
        WatermarkNudge(
            spec = spec,
            aspect = aspect,
            onChange = onChange,
        )

        Spacer(Modifier.height(FmSpacing.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelLabel("宽度")
            Spacer(Modifier.weight(1f))
            Text(
                text = "${(spec.width * 100).roundToInt()}%",
                style = FmType.labelMedium,
                color = FmPalette.onSurfaceSecondary,
            )
        }
        FmSlider(
            value = spec.width,
            onValueChange = {
                onChange(spec.copy(width = it.coerceIn(WatermarkSpec.MIN_WIDTH, WatermarkSpec.MAX_WIDTH)))
            },
            valueRange = WatermarkSpec.MIN_WIDTH..WatermarkSpec.MAX_WIDTH,
        )

        Spacer(Modifier.height(FmSpacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelLabel("不透明度")
            Spacer(Modifier.weight(1f))
            Text(
                text = "${(spec.opacity * 100).roundToInt()}%",
                style = FmType.labelMedium,
                color = FmPalette.onSurfaceSecondary,
            )
        }
        FmSlider(
            value = spec.opacity,
            onValueChange = {
                onChange(spec.copy(opacity = it.coerceIn(WatermarkSpec.MIN_OPACITY, 1f)))
            },
            valueRange = WatermarkSpec.MIN_OPACITY..1f,
        )
        Spacer(Modifier.height(FmSpacing.md))
        WatermarkRotationRow(spec = spec, onChange = onChange)
        Spacer(Modifier.height(FmSpacing.md))
    }
}

/**
 * Nudge cross + precise dx/dy. Both write through `WatermarkSpec.withRectNudge`,
 * so the normalised offsets are relative to the photo draw box (the aspect the
 * engine reported), never to the canvas including the border.
 */
@Composable
private fun WatermarkNudge(
    spec: WatermarkSpec,
    aspect: Float,
    onChange: (WatermarkSpec?) -> Unit,
) {
    var fine by remember { mutableStateOf(false) }
    val step = if (fine) 0.001f else 0.01f
    Row(verticalAlignment = Alignment.Top) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                NudgeSpacer()
                NudgeButton(label = "↑", description = "水印上移") {
                    onChange(spec.withRectNudge(0f, -step, aspect))
                }
                NudgeSpacer()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                NudgeButton(label = "←", description = "水印左移") {
                    onChange(spec.withRectNudge(-step, 0f, aspect))
                }
                NudgeButton(label = "◎", description = "水印偏移归零") {
                    onChange(spec.withRectNudge(-spec.dx, -spec.dy, aspect))
                }
                NudgeButton(label = "→", description = "水印右移") {
                    onChange(spec.withRectNudge(step, 0f, aspect))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                NudgeSpacer()
                NudgeButton(label = "↓", description = "水印下移") {
                    onChange(spec.withRectNudge(0f, step, aspect))
                }
                NudgeSpacer()
            }
        }
        Spacer(Modifier.width(FmSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "步长",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
                Spacer(Modifier.width(FmSpacing.xs))
                StepChip(label = "1%", active = !fine) { fine = false }
                Spacer(Modifier.width(FmSpacing.xs))
                StepChip(label = "0.1%", active = fine) { fine = true }
            }
            Spacer(Modifier.height(FmSpacing.xs))
            PrecisePercentField(
                label = "水平 %",
                fraction = spec.dx,
                onFraction = { value -> onChange(spec.withRectNudge(value - spec.dx, 0f, aspect)) },
            )
            PrecisePercentField(
                label = "垂直 %",
                fraction = spec.dy,
                onFraction = { value -> onChange(spec.withRectNudge(0f, value - spec.dy, aspect)) },
            )
            Text(
                text = "以照片绘制区归一化，范围 −100…100%。",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun NudgeSpacer() {
    Box(modifier = Modifier.size(48.dp))
}

@Composable
private fun NudgeButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(FmRadii.input))
            .background(FmPalette.surfaceMuted)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input))
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = FmType.titleSmall, color = FmPalette.onSurfaceSecondary)
    }
}

@Composable
private fun StepChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = FmType.labelSmall,
        color = if (active) FmPalette.onPrimary else FmPalette.onSurfaceSecondary,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(if (active) FmPalette.primaryStrong else FmPalette.surfaceMuted)
            .semantics {
                contentDescription = "微调步长 $label"
                role = Role.RadioButton
                this.selected = active
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    )
}

/** One-decimal percent text with a '.' separator (parsePreciseNumber reads '.'). */
private fun percentText(fraction: Float): String {
    val tenths = (fraction * 1000f).roundToInt()
    val sign = if (tenths < 0) "-" else ""
    val magnitude = abs(tenths)
    return "$sign${magnitude / 10}.${magnitude % 10}"
}

@Composable
private fun PrecisePercentField(
    label: String,
    fraction: Float,
    onFraction: (Float) -> Unit,
) {
    var text by remember { mutableStateOf(percentText(fraction)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(fraction, focused) {
        if (!focused) text = percentText(fraction)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = FmType.labelMedium,
            color = FmPalette.onSurfaceSecondary,
            modifier = Modifier.width(60.dp),
        )
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                val percent = parsePreciseNumber(raw, -100f, 100f)
                if (percent != null) onFraction(percent / 100f)
            },
            singleLine = true,
            textStyle = FmType.bodyMedium.copy(color = FmPalette.onSurface),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(FmRadii.input))
                .background(FmPalette.surfaceMuted)
                .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input))
                .onFocusChanged { focused = it.isFocused }
                .padding(horizontal = 10.dp, vertical = 12.dp),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isBlank()) {
                        Text(
                            text = "0.0",
                            style = FmType.bodyMedium,
                            color = FmPalette.onSurfaceFaint,
                        )
                    }
                    innerTextField()
                }
            },
        )
        Spacer(Modifier.width(4.dp))
        Text("%", style = FmType.labelMedium, color = FmPalette.onSurfaceMuted)
    }
}

/**
 * Real rotation, written through the same `WatermarkSpec.rotation` that feeds
 * the engine overlay; the live Compose layer only previews the same angle.
 */
@Composable
private fun WatermarkRotationRow(spec: WatermarkSpec, onChange: (WatermarkSpec?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("旋转", style = FmType.bodyMedium, color = FmPalette.onSurface)
        Spacer(Modifier.weight(1f))
        Text(
            text = "${spec.rotation.roundToInt()}°",
            style = FmType.labelMedium,
            color = FmPalette.onSurfaceSecondary,
        )
        Spacer(Modifier.width(FmSpacing.xs))
        SmallAction("归零") { onChange(spec.copy(rotation = 0f)) }
    }
    FmSlider(
        value = spec.rotation,
        onValueChange = { raw ->
            onChange(spec.copy(rotation = raw.coerceIn(-180f, 180f).roundToInt().toFloat()))
        },
        valueRange = -180f..180f,
        modifier = Modifier.semantics {
            contentDescription = "水印旋转角度，${spec.rotation.roundToInt()} 度"
        },
    )
}

@Composable
private fun PanelLabel(text: String) {
    Text(text, style = FmType.labelMedium, color = FmPalette.onSurfaceSecondary)
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = FmType.labelMedium,
        color = FmPalette.onSurfaceSecondary,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.surfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** 3x3 quick anchors; index 0 = top-left, 8 = bottom-right. */
@Composable
private fun AnchorGrid(selected: Int, onSelect: (Int) -> Unit) {
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 3) {
                    val slot = row * 3 + col
                    val active = slot == selected
                    val position = when (slot) {
                        0 -> "左上"
                        1 -> "上中"
                        2 -> "右上"
                        3 -> "左中"
                        4 -> "中心"
                        5 -> "右中"
                        6 -> "左下"
                        7 -> "下中"
                        else -> "右下"
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "水印位置：$position"
                                role = Role.RadioButton
                                this.selected = active
                                stateDescription = if (active) "已选中" else "未选中"
                            }
                            .clickable { onSelect(slot) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (active) FmPalette.primary else FmPalette.surfaceMuted)
                                .border(
                                    1.dp,
                                    if (active) FmPalette.primary else FmPalette.outline,
                                    RoundedCornerShape(7.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (active) FmPalette.onPrimary else FmPalette.onSurfaceMuted,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Overlay sheets (batch / settings)
// ---------------------------------------------------------------------------

/** Full-screen phone overlay: white canvas, its own scroll, back closes it. */
@Composable
fun PhoneOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .background(FmPalette.canvas),
        ) {
            content()
        }
    }
}

@Composable
fun OverlayHeader(title: String, subtitle: String? = null, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = FmSpacing.xxl, end = FmSpacing.lg, top = FmSpacing.md, bottom = FmSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = FmType.titleLarge,
                color = FmPalette.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = FmType.bodyMedium, color = FmPalette.onSurfaceMuted)
            }
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(FmPalette.surface)
                .border(1.dp, FmPalette.outline, CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", style = FmType.labelMedium, color = FmPalette.onSurfaceSecondary)
        }
    }
}
