package com.framemark.native.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import com.framemark.native.library.LibraryPreviewLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Slider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.framemark.native.LaunchOptions
import com.framemark.native.inspector.EditorTabList
import com.framemark.native.inspector.InspectorLoadState
import com.framemark.native.inspector.InspectorModelFactory
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.applyDemoEdits
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryPreview
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.NativeEngine
import com.framemark.native.workflow.NativeStagePreview
import com.framemark.native.workflow.ImageRect
import com.framemark.native.workflow.PhotoThumbnail
import com.framemark.native.workflow.PhotoTray
import com.framemark.native.workflow.TrayPhoto
import com.framemark.native.workflow.WatermarkSpec
import com.framemark.native.workflow.WorkflowEnv
import kotlin.math.roundToInt

/**
 * Preview-first editor (old WebView IA): brand + 添加 / 预设 / 导出当前 in the top
 * bar, stage title with zoom, the native stage, the photo strip, the four
 * parameter tabs and the 边框 / 水印 segment bar. Choosing a border lives in the
 * library sheet the 边框 segment opens.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PhoneEditorScreen(
    card: LibraryCard?,
    inspectorState: InspectorLoadState,
    session: DesignSession,
    tray: PhotoTray,
    tool: EditorTool,
    onToolChange: (EditorTool) -> Unit,
    tab: InspectorSection,
    onTabChange: (InspectorSection) -> Unit,
    onPickPhoto: () -> Unit,
    onSelectPhoto: (TrayPhoto) -> Unit,
    onRemovePhoto: (TrayPhoto) -> Unit,
    onOpenLibrary: () -> Unit,
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
) {
    val snapshot = (inspectorState as? InspectorLoadState.Ready)?.snapshot
    val model = remember(card?.id, snapshot) {
        if (card != null && snapshot != null) InspectorModelFactory.build(card, snapshot) else null
    }

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
    var fullParametersDismissed by rememberSaveable { mutableStateOf(false) }
    var fullParametersManuallyOpen by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current

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

    BoxWithConstraints(modifier = Modifier.fillMaxSize().imePadding()) {
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        val autoFullParameterPage = useFullPhoneParameterPage(
            availableHeightDp = maxHeight.value,
            fontScale = density.fontScale,
            imeVisible = imeVisible,
        )
        LaunchedEffect(autoFullParameterPage) {
            if (!autoFullParameterPage) fullParametersDismissed = false
        }
        val fullParameterPage = fullParametersManuallyOpen ||
            (autoFullParameterPage && !fullParametersDismissed)
        val keyboardController = LocalSoftwareKeyboardController.current
        fun closeFullParametersOrHideKeyboard() {
            if (imeVisible) {
                keyboardController?.hide()
            } else {
                fullParametersManuallyOpen = false
                fullParametersDismissed = autoFullParameterPage
            }
        }
        BackHandler(enabled = fullParameterPage) {
            closeFullParametersOrHideKeyboard()
        }
        // Area budget (insets excluded): top bar 56 / title+zoom 40 (36) /
        // flexible stage (>=38% of the content height) / strip 72 (64) /
        // sheet 34% (30%) / tool bar 62 (58).
        val small = maxWidth < 380.dp
        val fullHeaderH = (56f * density.fontScale.coerceIn(1f, 1.5f)).dp
        val topBarH = if (fullParameterPage) 0.dp else 56.dp
        val titleH = if (fullParameterPage) 0.dp else if (small) 36.dp else 40.dp
        val stripH = if (fullParameterPage) 0.dp else if (small) 64.dp else 72.dp
        val toolBarH = if (fullParameterPage) 0.dp else if (small) 58.dp else 62.dp
        val collapsedH = 44.dp
        val stageMinH = if (fullParameterPage) 0.dp else maxHeight * 0.38f
        val maxSheetH = if (fullParameterPage) {
            (maxHeight - fullHeaderH).coerceAtLeast(0.dp)
        } else {
            (maxHeight - topBarH - titleH - stripH - toolBarH - stageMinH).coerceAtLeast(collapsedH)
        }
        val defaultSheetH = if (fullParameterPage) maxSheetH else {
            (maxHeight * (if (small) 0.30f else 0.34f)).coerceIn(collapsedH, maxSheetH)
        }
        // A short landscape window can leave less than the collapsed sheet's 44dp
        // budget above the 26% stage reserve. Keep coerceIn's bounds ordered; in
        // that case the tall snap collapses to the default height.
        val tallSheetUpperH = if (fullParameterPage) maxSheetH else {
            (maxHeight - topBarH - titleH - stripH - toolBarH - maxHeight * 0.26f)
                .coerceAtLeast(defaultSheetH)
        }
        val tallSheetH = if (fullParameterPage) maxSheetH else {
            (maxHeight * 0.55f).coerceIn(defaultSheetH, tallSheetUpperH)
        }
        val snapHeights = listOf(collapsedH, defaultSheetH, tallSheetH)
        val draggedH = if (fullParameterPage) null else sheetDragPx?.let { with(density) { it.toDp() } }
        val sheetHeight = if (fullParameterPage) maxSheetH else {
            draggedH ?: snapHeights[sheetSnap.coerceIn(0, 2)]
        }
        val collapsed = !fullParameterPage && sheetHeight <= collapsedH + 1.dp
        val stageHeight = (maxHeight - topBarH - titleH - stripH - toolBarH - sheetHeight)
            .coerceAtLeast(0.dp)
        LaunchedEffect(small, maxHeight, sheetSnap, draggedH == null) {
            val total = maxHeight.value
            fun pct(v: Dp) = "%.1f".format(v.value / total * 100f)
            println(
                "FM_AREA small=$small content=${"%.1f".format(total)}dp " +
                    "top=${topBarH.value} title=${titleH.value} stage=${"%.1f".format(stageHeight.value)} " +
                    "strip=${stripH.value} panel=${"%.1f".format(sheetHeight.value)} bar=${toolBarH.value} | " +
                    "stage%=${pct(stageHeight)} panel%=${pct(sheetHeight)} " +
                    "top%=${pct(topBarH)} title%=${pct(titleH)} strip%=${pct(stripH)} bar%=${pct(toolBarH)}",
            )
        }
        Column(modifier = Modifier.fillMaxSize()) {
            if (fullParameterPage) {
                ParameterPageHeader(
                    height = fullHeaderH,
                    title = card?.name ?: "全部参数",
                    closeLabel = if (imeVisible) "收起键盘" else "返回预览",
                    onClose = ::closeFullParametersOrHideKeyboard,
                )
            } else {
                EditorTopBar(
                height = topBarH,
                canUndo = session.history.canUndo,
                canRedo = session.history.canRedo,
                onPickPhoto = onPickPhoto,
                onOpenPresets = onOpenPresets,
                onOpenExport = onOpenExport,
                onOpenBatch = onOpenBatch,
                onOpenFullParameters = { fullParametersManuallyOpen = true },
                onApplyAll = onApplyAll,
                onOpenPhotoBatch = onOpenPhotoBatch,
                onOpenSettings = onOpenSettings,
                onUndo = onUndo,
                onRedo = onRedo,
            )

            // Old `.stage-topline` + `.stage-meta { display: none }` on mobile:
            // one 13px title line, then the eye / zoom tools on the right.
            EditorTitleRow(
                height = titleH,
                title = card?.name ?: "选择边框",
                onOpenLibrary = onOpenLibrary,
                zoom = zoom,
                comparing = compare,
                onCompareChange = { compare = it },
                onZoomOut = { setZoom(zoom / 1.25f) },
                onZoomIn = { setZoom(zoom * 1.25f) },
                onZoomReset = { setZoom(1f) },
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(StageRadius))
                        .background(Color(0xFFF7F8FA))
                        .border(1.dp, FmPalette.outline, RoundedCornerShape(StageRadius))
                        .onSizeChanged { stageSize = it }
                        // One detector for pinch + drag: 2 fingers zoom around
                        // their centroid, 1 finger pans (only while zoomed).
                        .pointerInput(Unit) {
                            detectTransformGestures { centroid, panChange, zoomChange, _ ->
                                if (zoomChange != 1f) {
                                    zoomAround(centroid, zoom * zoomChange)
                                } else if (panChange != Offset.Zero) {
                                    panBy(panChange.x, panChange.y)
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { setZoom(1f) })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    StageBackdrop()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // Old `.canvas-frame { padding: 6px }` around the canvas.
                            .padding(6.dp)
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = pan.x
                                translationY = pan.y
                            },
                    ) {
                        val comparePath =
                            tray.selected?.path ?: WorkflowEnv.platform?.samplePhotoPath()
                        if (compare && comparePath != null) {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(6.dp),
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
                                    color = FmPalette.onSurfaceSecondary,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(FmSpacing.sm)
                                        .clip(RoundedCornerShape(FmRadii.pill))
                                        .background(FmPalette.surface.copy(alpha = 0.92f))
                                        .padding(horizontal = FmSpacing.sm, vertical = 3.dp),
                                )
                            }
                        } else when {
                            card == null -> StagePlaceholder(
                                tray = tray,
                                onOpenLibrary = onOpenLibrary,
                            )
                            NativeEngine.supports(card.id) -> NativeStagePreview(
                                card = card,
                                design = NativeEngine.designOf(card, model, session),
                                modifier = Modifier.fillMaxSize(),
                                onRendered = { w, h, rect ->
                                    previewGeo = PreviewGeo(w, h, rect)
                                    renderFailure = null
                                },
                                onFailed = { renderFailure = it },
                            )
                            else -> Box(modifier = Modifier.fillMaxSize()) {
                                LibraryPreview(card = card, modifier = Modifier.fillMaxSize())
                                Text(
                                    text = "静态预览",
                                    style = FmType.labelSmall,
                                    color = FmPalette.onSurfaceSecondary,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(FmSpacing.md)
                                        .clip(RoundedCornerShape(FmRadii.pill))
                                        .background(FmPalette.surface.copy(alpha = 0.9f))
                                        .padding(horizontal = FmSpacing.sm, vertical = 3.dp),
                                )
                            }
                        }
                        watermark?.let { spec ->
                            val geo = previewGeo
                            if (!compare && geo != null) {
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
                    if (renderFailure != null) {
                        Text(
                            text = "预览失败 · 显示静态图",
                            style = FmType.labelSmall,
                            color = FmPalette.onSurfaceSecondary,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(FmSpacing.md)
                                .clip(RoundedCornerShape(FmRadii.pill))
                                .background(FmPalette.surface.copy(alpha = 0.92f))
                                .padding(horizontal = FmSpacing.sm, vertical = 3.dp),
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(stripH), contentAlignment = Alignment.Center) {
                PhotoStrip(
                    tray = tray,
                    onSelect = onSelectPhoto,
                    onRemove = onRemovePhoto,
                    onAdd = onPickPhoto,
                    customIds = customizedPhotoIds,
                )
            }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sheetHeight)
                    .clip(RoundedCornerShape(topStart = SheetRadius, topEnd = SheetRadius))
                    .background(FmPalette.surface)
                    .border(
                        1.dp,
                        FmPalette.outline,
                        RoundedCornerShape(topStart = SheetRadius, topEnd = SheetRadius),
                    ),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (!fullParameterPage) SheetGrabber(
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
                                sheetSnap = px.indices.minByOrNull { kotlin.math.abs(px[it] - current) } ?: 1
                                sheetDragPx = null
                            }
                        },
                    )
                    if (fullParameterPage || !collapsed) {
                        if (tool == EditorTool.Border) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(FmPalette.surface),
                            ) {
                                EditorTabList(
                                    state = panelState,
                                    tab = tab,
                                    model = model,
                                    loadState = inspectorState,
                                    session = session,
                                    onRetry = onRetryInspector,
                                    initialItem = LaunchOptions.editorItem,
                                    modifier = Modifier.fillMaxSize(),
                                    header = {
                                        CardHead(
                                            card = card,
                                            canReset = card != null && session.hasOverrides(card.id),
                                            onReset = { confirmReset = true },
                                        )
                                    },
                                    pinnedTabs = {
                                        EditorTabRow(selected = tab, onSelect = onTabChange)
                                    },
                                    footer = {
                                        // Export size = current 导出长边 × the preview aspect
                                        // (ratio changes show up in the preview already).
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
                                )
                            }
                        } else {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                WatermarkPanel(spec = watermark, onChange = onWatermarkChange, onPick = onPickWatermark)
                            }
                        }
                    } else if (tool == EditorTool.Border) {
                        // Collapsed = 18dp grabber + 26dp tabs = the 44dp budget.
                        EditorTabRow(
                            selected = tab,
                            onSelect = onTabChange,
                            rowHeight = 26.dp,
                        )
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

            if (!fullParameterPage) FmEditorToolBar(
                height = toolBarH,
                tool = tool,
                onSelectBorder = {
                    // Discoverability first: one tap always lands in the border library.
                    onToolChange(EditorTool.Border)
                    onOpenLibrary()
                },
                onSelectWatermark = { onToolChange(EditorTool.Watermark) },
            )
        }
    }
}

/** Last native frame's pixel size plus the engine photo box (overlay anchor). */
private data class PreviewGeo(val w: Int, val h: Int, val photoRect: ImageRect?)

/**
 * Live watermark on the transformed stage: Compose draws it while the user
 * drags / slides, so the feedback tracks pinch/pan instantly; the engine gets the
 * same rect as an `OverlaySpec` for export.
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
    val pad = with(density) { 6.dp.toPx() }
    val availW = stage.width - pad * 2f
    val availH = stage.height - pad * 2f
    if (availW <= 1f || availH <= 1f) return
    val scale = minOf(availW / geo.w, availH / geo.h)
    val left = pad + (availW - geo.w * scale) / 2f
    val top = pad + (availH - geo.h * scale) / 2f
    val pr = geo.photoRect ?: ImageRect(0f, 0f, geo.w.toFloat(), geo.h.toFloat())
    val photoW = pr.w * scale
    val photoH = pr.h * scale
    if (photoW <= 1f || photoH <= 1f) return
    val aspect = photoW / photoH
    val r = spec.rect(aspect)
    // Same clamp the engine gets: the live overlay is clipped to the photo box.
    val ow = r[2] * photoW
    val oh = r[3] * photoH
    val ox = r[0] * photoW
    val oy = r[1] * photoH

    LaunchedEffect(aspect, spec.photoAspect) {
        if (kotlin.math.abs(aspect - spec.photoAspect) > 0.01f) {
            onChange(spec.copy(photoAspect = aspect))
        }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset((left + pr.x * scale).roundToInt(), (top + pr.y * scale).roundToInt()) }
            .size(with(density) { photoW.toDp() }, with(density) { photoH.toDp() })
            .clipToBounds(),
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(ox.roundToInt(), oy.roundToInt()) }
                .size(with(density) { ow.toDp() }, with(density) { oh.toDp() })
                .pointerInput(spec.path, aspect) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onChange(spec.withRectNudge(drag.x / photoW, drag.y / photoH, aspect))
                    }
                },
        ) {
            // The tray thumbnail path re-encodes to JPEG (no alpha); the overlay
            // must keep the PNG alpha so it reads the same as the exported file.
            val wmBitmap by produceState<ImageBitmap?>(initialValue = null, spec.path) {
                value = withContext(Dispatchers.Default) {
                    runCatching {
                        WorkflowEnv.platform?.readBytes(spec.path)?.let { LibraryPreviewLoader.decode(it) }
                    }.getOrNull()
                }
            }
            val ready = wmBitmap
            if (ready != null) {
                Image(
                    bitmap = ready,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().alpha(spec.opacity),
                )
            }
        }
    }
}

/** Desktop right-rail card head: eyebrow, name, description, 重置. */
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
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.surfaceMuted)
                    .clickable(enabled = canReset, onClick = onReset)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
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

/** Desktop right-rail bottom: original reference thumb + output size/filename. */
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

/** Old mobile `.canvas-viewport { border-radius: 12px }`. */
private val StageRadius = 12.dp

/** Constrained phone windows use a dedicated parameter page with the same editor state. */
internal fun useFullPhoneParameterPage(
    availableHeightDp: Float,
    fontScale: Float,
    imeVisible: Boolean,
): Boolean = availableHeightDp < 480f || fontScale >= 1.5f || imeVisible

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
        TextButton(onClick = onClose) { Text(closeLabel) }
    }
}

/** Old mobile `.inspector-panel { border-radius: 22px 22px 0 0 }`. */
private val SheetRadius = 22.dp

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

private val StageDot = Color(0x14141E2D)

/**
 * Old `.canvas-viewport` on mobile: #f7f8fa, an 18px dot grid and the inset
 * `0 0 80px rgba(0,0,0,.35)` shadow that reads as an edge vignette. Compose has
 * no inset box-shadow, so one linear gradient per edge approximates it.
 */
@Composable
private fun StageBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // 80px blur reaches ~2x its radius into the stage, which is why the old
        // stage reads grey overall (centre ~#E4E4E6) instead of the #f7f8fa base.
        val edge = 160.dp.toPx()
        val shade = Color.Black.copy(alpha = 0.15f)
        val clear = Color.Transparent
        drawRect(Brush.verticalGradient(listOf(shade, clear), startY = 0f, endY = edge))
        drawRect(
            Brush.verticalGradient(
                listOf(clear, shade),
                startY = size.height - edge,
                endY = size.height,
            ),
        )
        drawRect(Brush.horizontalGradient(listOf(shade, clear), startX = 0f, endX = edge))
        drawRect(
            Brush.horizontalGradient(
                listOf(clear, shade),
                startX = size.width - edge,
                endX = size.width,
            ),
        )
        val step = 18.dp.toPx()
        val radius = 0.7.dp.toPx()
        var y = step / 2f
        while (y < size.height) {
            var x = step / 2f
            while (x < size.width) {
                drawCircle(StageDot, radius, Offset(x, y))
                x += step
            }
            y += step
        }
    }
}

@Composable
private fun EditorTopBar(
    height: Dp,
    canUndo: Boolean,
    canRedo: Boolean,
    onPickPhoto: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenBatch: () -> Unit,
    onOpenFullParameters: () -> Unit,
    onApplyAll: (() -> Unit)? = null,
    onOpenPhotoBatch: (() -> Unit)? = null,
    onOpenSettings: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    // Area budget: 56dp block (old `.topbar` capsule is 54 inside it), 9px sides.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 9.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(20.dp))
            .padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(31.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(FmPalette.primarySoft),
            contentAlignment = Alignment.Center,
        ) {
            BrandFrameGlyph(tint = FmPalette.primary, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = "框印",
            style = FmType.titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            color = FmPalette.onSurface,
        )
        Spacer(Modifier.weight(1f))
        TopBarPill(text = "添加", onClick = onPickPhoto)
        Spacer(Modifier.width(4.dp))
        TopBarPill(text = "预设", onClick = onOpenPresets)
        Spacer(Modifier.width(4.dp))
        EditorOverflowMenu(
            canUndo = canUndo,
            canRedo = canRedo,
            onUndo = onUndo,
            onRedo = onRedo,
            onOpenBatch = onOpenBatch,
            onOpenFullParameters = onOpenFullParameters,
            onApplyAll = onApplyAll,
            onOpenPhotoBatch = onOpenPhotoBatch,
            onOpenSettings = onOpenSettings,
        )
        Spacer(Modifier.width(4.dp))
        TopBarPill(text = "导出当前", primary = true, onClick = onOpenExport)
    }
}

@Composable
private fun TopBarPill(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(if (primary) 10.dp else 9.dp)
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(shape)
            .background(if (primary) FmPalette.primary else FmPalette.surface)
            .border(
                width = 1.dp,
                color = if (primary) Color(0x29FFFFFF) else FmPalette.outline,
                shape = shape,
            )
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                }
            .clickable(onClick = onClick)
            .padding(horizontal = if (primary) 12.dp else 8.dp),
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

@Composable
private fun EditorOverflowMenu(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenBatch: () -> Unit,
    onOpenFullParameters: () -> Unit,
    onApplyAll: (() -> Unit)? = null,
    onOpenPhotoBatch: (() -> Unit)? = null,
    onOpenSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 36.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(FmPalette.surface)
                .border(1.dp, FmPalette.outline, RoundedCornerShape(9.dp))
                .semantics {
                    contentDescription = "更多操作"
                    role = Role.Button
                }
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "⋯",
                style = FmType.titleSmall.copy(fontSize = 14.sp),
                color = FmPalette.onSurfaceSecondary,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(184.dp)
                .background(FmPalette.surface),
        ) {
            OverflowItem(text = "撤销", enabled = canUndo) {
                expanded = false
                onUndo()
            }
            OverflowItem(text = "重做", enabled = canRedo) {
                expanded = false
                onRedo()
            }
            OverflowDivider()
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
// Stage title + zoom
// ---------------------------------------------------------------------------

@Composable
private fun EditorTitleRow(
    height: Dp,
    title: String,
    onOpenLibrary: () -> Unit = {},
    zoom: Float,
    comparing: Boolean,
    onCompareChange: (Boolean) -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomReset: () -> Unit,
) {
    // Old `.stage-topline` (padding 0 2 4) inside `.preview-stage` (0 8) on mobile:
    // 13px title, then a 32px eye button and the 26px zoom cluster.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = FmType.titleSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = FmPalette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        // MOBILE-PORT-PLAN §3: the border entry lives in the title row, not a
        // hidden second tap on the bottom bar.
        Text(
            text = "选择边框 ›",
            style = FmType.labelSmall.copy(fontSize = 11.sp),
            color = FmPalette.primaryStrong,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.primarySoft)
                .semantics {
                    contentDescription = "选择边框"
                    role = Role.Button
                }
                .clickable(onClick = onOpenLibrary)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (comparing) FmPalette.surfaceMuted else FmPalette.surface)
                .border(1.dp, FmPalette.outline, RoundedCornerShape(9.dp))
                    .semantics {
                        contentDescription = "按住查看原图"
                        role = Role.Button
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
            EyeGlyph(tint = FmPalette.onSurfaceSecondary)
        }
        Spacer(Modifier.width(4.dp))
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(FmPalette.surface)
                .border(1.dp, FmPalette.outline, RoundedCornerShape(9.dp))
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            ZoomButton(label = "−", description = "缩小预览", onClick = onZoomOut)
            Text(
                text = "${(zoom * 100).roundToInt()}%",
                style = FmType.labelSmall.copy(fontSize = 11.sp),
                color = FmPalette.onSurfaceSecondary,
                modifier = Modifier
                    .width(44.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .semantics {
                        contentDescription = "重置缩放到 ${(zoom * 100).roundToInt()}%"
                        role = Role.Button
                    }
                    .clickable(onClick = onZoomReset)
                    .padding(top = 5.dp),
                textAlign = TextAlign.Center,
            )
            ZoomButton(label = "+", description = "放大预览", onClick = onZoomIn)
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .semantics {
                        contentDescription = "适合窗口显示"
                        role = Role.Button
                    }
                    .clickable(enabled = zoom > 1f, onClick = onZoomReset),
                contentAlignment = Alignment.Center,
            ) {
                FitGlyph(
                    tint = if (zoom > 1f) {
                        FmPalette.onSurfaceSecondary
                    } else {
                        FmPalette.onSurfaceFaint.copy(alpha = 0.45f)
                    },
                )
            }
        }
    }
}

@Composable
private fun ZoomButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = FmType.titleSmall.copy(fontSize = 13.sp),
            color = FmPalette.onSurfaceSecondary,
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

@Composable
private fun FitGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val w = size.width
        val stroke = 1.4.dp.toPx()
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
private fun StagePlaceholder(tray: PhotoTray, onOpenLibrary: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PhotoThumbnail(
            path = tray.selected?.path,
            maxEdge = 1080,
            cornerRadius = 0.dp,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.primary)
                .clickable(onClick = onOpenLibrary)
                .padding(horizontal = FmSpacing.xl, vertical = FmSpacing.sm),
        ) {
            Text("选择边框", style = FmType.labelMedium, color = FmPalette.onPrimary)
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
) {
    // Old `.filmstrip` on mobile: 5/10/7 padding around a white 20px-radius bar,
    // 57x46 thumbs with a 6px gap.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 7.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(20.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 2.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
    // Keep selection and removal in separate gestures. An embedded 19dp remove
    // badge receives an expanded touch target that can swallow the tile centre.
    val isSelected = selected
    var showActions by remember(photo.id) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(width = 57.dp, height = 48.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(FmPalette.surfaceMuted)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) FmPalette.primary else FmPalette.outline,
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
                style = FmType.labelSmall.copy(fontSize = 8.sp),
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
            style = FmType.labelSmall.copy(fontSize = 9.sp),
            color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
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
            .size(width = 57.dp, height = 46.dp)
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
            style = FmType.labelSmall.copy(fontSize = 9.sp),
            color = FmPalette.onSurfaceMuted,
        )
    }
}

// ---------------------------------------------------------------------------
// Parameter tabs
// ---------------------------------------------------------------------------

private val EDITOR_TABS = listOf(
    InspectorSection.Logo,
    InspectorSection.Text,
    InspectorSection.Layout,
    InspectorSection.Theme,
)

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
                        if (!dragging && kotlin.math.abs(total) > viewConfiguration.touchSlop) {
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

@Composable
private fun EditorTabRow(
    selected: InspectorSection,
    onSelect: (InspectorSection) -> Unit,
    rowHeight: Dp = 36.dp,
) {
    // Old `.editor-tabs` on mobile: 2 8 8 padding, 36px buttons, 13px labels,
    // 18px icons and a 22x2 underline sitting 2px above the button's bottom.
    val compact = rowHeight < 36.dp
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .padding(
                    start = 8.dp,
                    end = 8.dp,
                    top = if (compact) 0.dp else 2.dp,
                    bottom = if (compact) 0.dp else 8.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            EDITOR_TABS.forEach { section ->
                val active = section == selected
                val tint = if (active) FmPalette.primaryStrong else FmPalette.onSurfaceMuted
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(rowHeight)
                        .clip(RoundedCornerShape(10.dp))
                        .semantics(mergeDescendants = true) {
                            contentDescription = section.title
                            role = Role.Tab
                            this.selected = active
                            stateDescription = if (active) "已选中" else "未选中"
                        }
                        .clickable { onSelect(section) },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TabGlyph(section = section, tint = tint)
                        Text(
                            text = section.title,
                            style = FmType.labelMedium.copy(fontSize = 13.sp),
                            color = tint,
                            maxLines = 1,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 2.dp)
                            .width(if (active) 22.dp else 0.dp)
                            .height(2.dp)
                            .background(if (active) FmPalette.primary else Color.Transparent),
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
    }
}

@Composable
private fun TabGlyph(section: InspectorSection, tint: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val w = size.width
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        when (section) {
            InspectorSection.Logo -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.1f, w * 0.15f),
                    size = Size(w * 0.8f, w * 0.7f),
                    cornerRadius = CornerRadius(w * 0.14f),
                    style = stroke,
                )
                drawCircle(tint, radius = w * 0.08f, center = Offset(w * 0.33f, w * 0.37f))
                drawLine(
                    tint,
                    Offset(w * 0.18f, w * 0.74f),
                    Offset(w * 0.44f, w * 0.48f),
                    stroke.width,
                    StrokeCap.Round,
                )
                drawLine(
                    tint,
                    Offset(w * 0.44f, w * 0.48f),
                    Offset(w * 0.8f, w * 0.74f),
                    stroke.width,
                    StrokeCap.Round,
                )
            }
            InspectorSection.Text -> {
                drawLine(tint, Offset(w * 0.2f, w * 0.22f), Offset(w * 0.8f, w * 0.22f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.5f, w * 0.22f), Offset(w * 0.5f, w * 0.82f), stroke.width, StrokeCap.Round)
            }
            InspectorSection.Layout -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.12f, w * 0.12f),
                    size = Size(w * 0.34f, w * 0.34f),
                    cornerRadius = CornerRadius(w * 0.08f),
                    style = stroke,
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.54f, w * 0.12f),
                    size = Size(w * 0.34f, w * 0.34f),
                    cornerRadius = CornerRadius(w * 0.08f),
                    style = stroke,
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.12f, w * 0.54f),
                    size = Size(w * 0.34f, w * 0.34f),
                    cornerRadius = CornerRadius(w * 0.08f),
                    style = stroke,
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.54f, w * 0.54f),
                    size = Size(w * 0.34f, w * 0.34f),
                    cornerRadius = CornerRadius(w * 0.08f),
                    style = stroke,
                )
            }
            InspectorSection.Theme -> {
                drawCircle(tint, radius = w * 0.34f, center = Offset(w * 0.5f, w * 0.5f), style = stroke)
                drawCircle(tint, radius = w * 0.14f, center = Offset(w * 0.5f, w * 0.5f))
            }
            InspectorSection.Border -> Unit
        }
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
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(FmSpacing.lg),
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
            return@Column
        }

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
                    text = "宽 ${(spec.width * 100).roundToInt()}% · 不透明度 ${(spec.opacity * 100).roundToInt()}%",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
            }
            SmallAction("替换", onClick = onPick)
            Spacer(Modifier.width(FmSpacing.xs))
            SmallAction("删除", onClick = { onChange(null) })
        }

        Spacer(Modifier.height(FmSpacing.lg))
        PanelLabel("位置")
        Spacer(Modifier.height(FmSpacing.xs))
        AnchorGrid(selected = spec.anchor) { slot ->
            onChange(spec.copy(anchor = slot, dx = 0f, dy = 0f))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "也可以直接在预览上拖动微调。",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
        )

        Spacer(Modifier.height(FmSpacing.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelLabel("宽度")
            Spacer(Modifier.weight(1f))
            Text(
                text = "${(spec.width * 100).roundToInt()}%",
                style = FmType.labelMedium,
                color = FmPalette.onSurfaceSecondary,
            )
        }
        Slider(
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
        Slider(
            value = spec.opacity,
            onValueChange = {
                onChange(spec.copy(opacity = it.coerceIn(WatermarkSpec.MIN_OPACITY, 1f)))
            },
            valueRange = WatermarkSpec.MIN_OPACITY..1f,
        )
        Spacer(Modifier.height(FmSpacing.md))
    }
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
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.surfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
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
// Overlay sheets (library / batch / settings)
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
