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
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorLoadState
import com.framemark.native.inspector.InspectorPage
import com.framemark.native.inspector.WatermarkPane
import com.framemark.native.inspector.WatermarkZones
import com.framemark.native.inspector.applyDemoEdits
import com.framemark.native.inspector.inspectorTabsFor
import com.framemark.native.inspector.resetInspectorPage
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.InMemoryRecentFramesStore
import com.framemark.native.library.RecentFramesEnv
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.LibraryLoadState
import com.framemark.native.library.LibraryPreview
import com.framemark.native.library.LibraryPreviewLoader
import com.framemark.native.library.LibraryTraversal
import com.framemark.native.shell.phone.PhoneEditorTopBar
import com.framemark.native.shell.phone.PhoneHoldCompareChip
import com.framemark.native.shell.phone.PhoneLibrarySheet
import com.framemark.native.shell.phone.PhoneParameterPage
import com.framemark.native.shell.phone.PhoneSheetGrabber
import com.framemark.native.shell.phone.PhoneSheetHead
import com.framemark.native.shell.phone.PhoneStageChip
import com.framemark.native.shell.phone.PhoneSwipeFeedback
import com.framemark.native.shell.phone.PhoneSwipeHud
import com.framemark.native.shell.phone.PhoneToolBar
import com.framemark.native.shell.phone.PhoneUndoNotice
import com.framemark.native.shell.phone.phoneFrameSwipe
import com.framemark.native.shell.phone.phoneLibraryTraversal
import com.framemark.native.shell.phone.phoneLoadRecentFrames
import com.framemark.native.shell.phone.phoneRecordRecentFrame
import com.framemark.native.shell.phone.phoneSwipeStep
import com.framemark.native.shell.phone.phoneToolLabel
import com.framemark.native.shell.phone.phoneToolModified
import com.framemark.native.shell.phone.phoneToolPage
import com.framemark.native.shell.phone.phoneToolShowsDot
import com.framemark.native.shell.phone.phoneToolTab
import com.framemark.native.shell.phone.phoneToolsFor
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
 * V2 phone editor, photo first (report §7): a dark 52 dp top bar (返回 · 照片切换 ·
 * 撤销 / 重做 · 导出), the photo stage taking every remaining dp with the border
 * name and 按住看原图 floating over it, the two-level parameter sheet, and the
 * 62 dp five-tool bar (边框 / 样式 / 文字 / Logo / 水印, dots from WP-C's page model).
 *
 * The border tool is the WP-L driven sheet ([PhoneLibrarySheet]); the four page
 * tools render WP-C's pages ([PhoneParameterPage]). Design values still flow
 * through [session] → the shell's write-back: this file changes layout and routing
 * only, never persistence.
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
    /** Border ids currently used by any photo of the project (for 常用 → 本项目在用). */
    inUseCardIds: List<String> = emptyList(),
    /**
     * 返回 with nothing left to collapse: leaves the editor for 首页 (WP-P2 shell
     * page state; MID-APK-REPORT §6 item 1). Null keeps the old no-op behaviour for
     * any other caller.
     */
    onRequestHome: (() -> Unit)? = null,
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
    // V2 sheet: exactly two levels — 收起 (compact, the card strip) and 展开 (grid).
    var sheetExpanded by rememberSaveable { mutableStateOf(false) }
    var sheetDragPx by remember { mutableStateOf<Float?>(null) }
    var photosOpen by rememberSaveable { mutableStateOf(false) }
    var focusMode by rememberSaveable { mutableStateOf(false) }
    // §2.8: stable TextSlot key of the row being edited. UI only — never written
    // into the project, a preset, a cache key or an export request.
    var focusedTextSlotKey by remember { mutableStateOf<String?>(null) }
    var fullParametersDismissed by rememberSaveable { mutableStateOf(false) }
    var fullParametersManuallyOpen by rememberSaveable { mutableStateOf(false) }
    // Border sheet browsing state, kept across tool switches (showcase `ftab`).
    var borderCategory by rememberSaveable { mutableStateOf(LibraryIndex.CATEGORY_COMMON) }
    var borderQuery by rememberSaveable { mutableStateOf("") }
    var recentFrames by remember { mutableStateOf(emptyList<String>()) }
    var swipeFeedback by remember { mutableStateOf<PhoneSwipeFeedback?>(null) }
    // A-08: one short "已撤销" notice with its 重做 action after an undo.
    var undoNotice by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    val libraryIndex = (libraryState as? LibraryLoadState.Ready)?.let { LibraryIndex.of(it.snapshot) }
    // 最近使用 wiring: the platform entry installs the file-backed store in
    // `library/RecentFramesEnv`; tests/previews fall back to the in-memory codec.
    val recentFramesStore = remember { RecentFramesEnv.store ?: InMemoryRecentFramesStore() }

    // 最近使用: one read per catalog; the codec/fallback is WP-L's RecentFrames.
    LaunchedEffect(libraryIndex) {
        recentFrames = phoneLoadRecentFrames(recentFramesStore, libraryIndex)
    }

    // A highlighted row belongs to one photo/card pair and to the text tool only.
    LaunchedEffect(card?.id, tray.selectedId) { focusedTextSlotKey = null }
    LaunchedEffect(tool, focusMode) {
        if (tool != EditorTool.Text || focusMode) focusedTextSlotKey = null
    }
    // The swipe HUD says which card the user landed on, then gets out of the way.
    LaunchedEffect(swipeFeedback) {
        if (swipeFeedback != null) {
            delay(1400)
            swipeFeedback = null
        }
    }
    LaunchedEffect(undoNotice) {
        if (undoNotice) {
            delay(4000)
            undoNotice = false
        }
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

    // WP-C's page model is the single source of the four pages and their counts.
    // Not memoised: `isModified` reads snapshot state that must stay observed.
    val tabModel = inspectorTabsFor(
        model = model,
        session = session,
        hasTextSlots = textSlots.isNotEmpty(),
        watermarkModified = WatermarkZones.modifiedCount(watermark),
    )
    val tools = phoneToolsFor(tabModel)
    LaunchedEffect(tools) {
        if (tool !in tools) onToolChange(EditorTool.Border)
    }

    // Swipe order == the sheet's display order: both read WP-L's traversal for the
    // same view, so the two can never drift.
    val traversal = if (libraryIndex != null) {
        phoneLibraryTraversal(libraryIndex, borderCategory, null, borderQuery, inUseCardIds, recentFrames)
    } else {
        LibraryTraversal(emptyList())
    }

    fun pickBorder(picked: LibraryCard) {
        recentFrames = phoneRecordRecentFrame(recentFramesStore, libraryIndex, picked.id)
        onOpenCard(picked)
    }

    fun stepBorder(direction: Int) {
        val step = phoneSwipeStep(traversal, card?.id, direction)
        val target = step.targetId?.let { libraryIndex?.card(it) }
        swipeFeedback = PhoneSwipeFeedback(
            title = target?.name ?: step.message,
            detail = if (target != null) step.message else null,
            atEdge = step.atEdge,
        )
        if (target != null) pickBorder(target)
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
        // The expanded level of the two-level sheet (null when the window is too
        // short for a usable sheet, in which case the budget keeps the full page).
        val levels = phoneSheetLevels(maxHeight.value, tool, photosOpen, small, density.fontScale)
        val autoFullParameterPage = budget.fullPage
        LaunchedEffect(autoFullParameterPage) {
            if (!autoFullParameterPage) fullParametersDismissed = false
        }
        // The full parameter page is the constrained-window fallback only: V2 has
        // no separate "全部参数" entry because every page is a tool.
        val fullParameterPage = autoFullParameterPage && !fullParametersDismissed
        val heights = if (fullParameterPage) {
            budget
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
        // WP-P1-2: the 收起 sheet carries its own vertical stack (handle, title row,
        // category row, chip row, strip padding, name line). The budget that decided
        // the sheet height and the composables that draw it read the same numbers, so
        // "128 dp 卡片 + 一行名称 fits" and "舞台 ≥ 50 %" are one calculation. It is
        // keyed on the compact level, so dragging or expanding cannot resize the chrome
        // under the finger.
        val sheetMetrics = phoneCompactSheetMetrics(heights.sheet, density.fontScale)
        val fullHeaderH = (52f * density.fontScale.coerceIn(1f, 1.5f)).dp
        val keyboardController = LocalSoftwareKeyboardController.current
        fun closeFullParametersOrHideKeyboard() {
            if (imeVisible) {
                keyboardController?.hide()
            } else if (!autoFullParameterPage || canShowForcedPreview(maxHeight.value)) {
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
        BackHandler(enabled = sheetExpanded && !fullParameterPage) { sheetExpanded = false }

        // Two levels only: compact (heights.sheet from the budget) and expanded.
        val expandedLevel = levels?.expanded ?: heights.sheet
        val snapHeights = listOf(heights.sheet.dp, maxOf(heights.sheet, expandedLevel).dp)
        val draggedH = if (fullParameterPage) null else sheetDragPx?.let { with(density) { it.toDp() } }
        val sheetHeight = if (fullParameterPage) maxHeight else {
            draggedH ?: snapHeights[if (sheetExpanded) 1 else 0]
        }
        val stageEstimate = (maxHeight - topBarH - stripH - toolBarH - sheetHeight).coerceAtLeast(0.dp)
        LaunchedEffect(small, maxHeight, sheetExpanded, draggedH == null, tool, photosOpen, fullParameterPage) {
            val total = maxHeight.value
            fun pct(v: Float) = "%.1f".format(if (total > 0f) v / total * 100f else 0f)
            println(
                "FM_AREA full=$fullParameterPage small=$small content=${"%.1f".format(total)}dp " +
                    "top=${topBarH.value} stage=${"%.1f".format(stageEstimate.value)} " +
                    "strip=${stripH.value} panel=${"%.1f".format(sheetHeight.value)} bar=${toolBarH.value} " +
                    "tool=${tool.label} expanded=$sheetExpanded photosOpen=$photosOpen | " +
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
                            PhoneEditorTopBar(
                                height = topBarH,
                                fileName = tray.selected?.label ?: "未选择照片",
                                photoIndex = photoIndexOf(tray),
                                photoCount = tray.count.coerceAtLeast(1),
                                photosOpen = photosOpen,
                                canUndo = session.history.canUndo,
                                canRedo = session.history.canRedo,
                                onBack = {
                                    // Same layers the system back button walks.
                                    when {
                                        imeVisible -> keyboardController?.hide()
                                        fullParameterPage -> closeFullParametersOrHideKeyboard()
                                        focusMode -> focusMode = false
                                        sheetExpanded -> sheetExpanded = false
                                        else -> onRequestHome?.invoke()
                                    }
                                },
                                onTogglePhotos = { photosOpen = !photosOpen },
                                onUndo = {
                                    onUndo()
                                    // Only claim an undo when the history really moved.
                                    undoNotice = session.history.canRedo
                                },
                                onRedo = {
                                    onRedo()
                                    undoNotice = false
                                },
                                onExport = onOpenExport,
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
                            borderName = card?.name,
                            swipeFeedback = swipeFeedback,
                            undoNotice = undoNotice,
                            onRedo = {
                                onRedo()
                                undoNotice = false
                            },
                            swipeEnabled = tool == EditorTool.Border && zoom <= 1f,
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
                            onFrameStep = ::stepBorder,
                            onFocusToggle = { focusMode = !focusMode },
                            onOpenBorder = { onToolChange(EditorTool.Border) },
                            onOpenBatch = onOpenBatch,
                            onApplyAll = onApplyAll,
                            onOpenPhotoBatch = onOpenPhotoBatch,
                            onOpenSettings = onOpenSettings,
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
                        val toolPage = phoneToolPage(tool)
                        val pageModified = phoneToolModified(tool, tabModel)
                        if (!fullParameterPage) {
                            PhoneSheetGrabber(
                                height = sheetMetrics.grabber.dp,
                                onToggle = {
                                    sheetDragPx = null
                                    sheetExpanded = !sheetExpanded
                                },
                                onDragStart = {
                                    sheetDragPx = with(density) { sheetHeight.toPx() }
                                },
                                onDrag = { dy ->
                                    val lo = with(density) { snapHeights[0].toPx() }
                                    val hi = with(density) { snapHeights[1].toPx() }
                                    sheetDragPx = ((sheetDragPx ?: lo) - dy).coerceIn(lo, hi)
                                },
                                onDragEnd = {
                                    val current = sheetDragPx
                                    if (current != null) {
                                        val px = snapHeights.map { with(density) { it.toPx() } }
                                        sheetExpanded = (px.indices.minByOrNull { abs(px[it] - current) } ?: 0) == 1
                                        sheetDragPx = null
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
                                expanded = sheetExpanded,
                                height = sheetMetrics.head.dp,
                                onToggleExpanded = {
                                    sheetDragPx = null
                                    sheetExpanded = !sheetExpanded
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
                                            println("FM_WM_RESET")
                                        }
                                    }
                                } else {
                                    {
                                        val changed = resetInspectorPage(tabModel, toolPage, session)
                                        println("FM_PAGE_RESET page=${toolPage.name} changed=$changed")
                                    }
                                },
                                // 编辑中直接套用预设 (hand-off §3 decision 5): the phone keeps
                                // the entry on the border sheet's title.
                                onOpenPresets = if (tool == EditorTool.Border) onOpenPresets else null,
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
                                expanded = sheetExpanded || fullParameterPage,
                                metrics = sheetMetrics,
                                categoryKey = borderCategory,
                                onCategoryChange = { borderCategory = it },
                                query = borderQuery,
                                onQueryChange = { borderQuery = it },
                                inUse = inUseCardIds,
                                recent = recentFrames,
                                onRetry = onRetryLibrary,
                                onPick = ::pickBorder,
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
                                    println("FM_TEXT_FOCUS key=$key tool=$tool")
                                    focusedTextSlotKey = key
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
                                            onUpload = onPickWatermark,
                                            onClear = { onWatermarkChange(null) },
                                            dense = true,
                                        )
                                    }
                                } else {
                                    null
                                },
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                footer = if (tool == EditorTool.Params) {
                                    {
                                        val edge = session.export.longEdge
                                        val pw = previewGeo?.w ?: 0
                                        val ph = previewGeo?.h ?: 0
                                        val outW = if (pw > 0) edge else 0
                                        val outH = if (pw > 0 && ph > 0) {
                                            (edge.toFloat() * ph / pw).roundToInt()
                                        } else {
                                            0
                                        }
                                        Column(modifier = Modifier.padding(horizontal = FmSpacing.sm)) {
                                            ReferenceAndOutput(
                                                card = card,
                                                outputW = outW,
                                                outputH = outH,
                                                exportEdge = edge,
                                                fileName = tray.selected?.label ?: "示例照片",
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
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
        }
    }

}

/** 1-based index of the selected strip photo; 1 when the strip has no selection yet. */
private fun photoIndexOf(tray: PhotoTray): Int {
    val index = tray.items.indexOfFirst { it.id == tray.selectedId }
    return if (index >= 0) index + 1 else 1
}

/** Full parameter page title: the page is the current tool rendered full height. */
private fun panelTitle(tool: EditorTool): String = phoneToolLabel(tool)

/** Last native frame's pixel size plus the engine photo box (overlay anchor). */
private data class PreviewGeo(val w: Int, val h: Int, val photoRect: ImageRect?)

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
// Stage (photo fills the remaining height, report §7.2)
// ---------------------------------------------------------------------------

/** Neck of the stage: the V2 photo area is full-bleed, so no corner radius. */
private val StageEdge = 12.dp

/**
 * The dark photo stage. The photo takes every dp the chrome leaves (report §7.2);
 * the two V2 hints — the current border name (bottom-left) and 按住看原图
 * (bottom-right) — float over it, so no bottom band is reserved any more.
 *
 * Gestures: pinch / drag while zoomed / double-tap reset, the live watermark
 * overlay, and (border tool, unzoomed) the left/right swipe that switches frames
 * in WP-L's traversal order.
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
    borderName: String?,
    swipeFeedback: PhoneSwipeFeedback?,
    undoNotice: Boolean,
    swipeEnabled: Boolean,
    focusMode: Boolean,
    density: Density,
    onStageSize: (IntSize) -> Unit,
    onTransform: (Offset, Offset, Float) -> Unit,
    onDoubleTap: () -> Unit,
    onCompareChange: (Boolean) -> Unit,
    onRedo: () -> Unit,
    onFrameStep: (Int) -> Unit,
    onFocusToggle: () -> Unit,
    onOpenBorder: () -> Unit,
    onOpenBatch: () -> Unit,
    onApplyAll: (() -> Unit)?,
    onOpenPhotoBatch: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onRendered: (Int, Int, ImageRect?) -> Unit,
    onRenderStarted: () -> Unit,
    onFailed: (String) -> Unit,
    onTextSlots: ((Int, Int, List<TextSlot>) -> Unit)?,
    onWatermarkChange: (WatermarkSpec?) -> Unit,
) {
    Box(
        modifier = modifier.background(FmPalette.stage),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = StageEdge, end = StageEdge, top = 10.dp, bottom = StageEdge),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { onStageSize(it) }
                    .phoneFrameSwipe(
                        enabled = swipeEnabled && !compare && zoom <= 1f,
                        onStep = onFrameStep,
                    )
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
        // The two V2 stage hints float over the photo (report §7.2): the applied
        // border on the left, 按住看原图 on the right. Everything else moved into
        // the dark top bar, so the photo keeps the whole middle of the screen.
        PhoneSwipeHud(
            feedback = swipeFeedback,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp),
        )
        if (undoNotice) {
            PhoneUndoNotice(
                onRedo = onRedo,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 54.dp),
            )
        }
        if (!focusMode) {
            PhoneStageChip(
                text = borderName ?: "未选边框",
                description = "当前边框",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = StageEdge, bottom = StageEdge),
            )
            if (card != null) {
                PhoneHoldCompareChip(
                    comparing = compare,
                    onCompareChange = onCompareChange,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = StageEdge, bottom = StageEdge),
                )
            }
        }
    }
}

/**
 * The three V06 actions that the V2 top bar no longer carries (report §7.2 lists
 * only 返回/照片切换/撤销/重做/导出) stay reachable from a compact stage menu:
 * 应用到全部照片, 批量导出, 设置. Nothing is silently dropped.
 */
@Composable
private fun StageOverflowChip(
    onApplyAll: (() -> Unit)?,
    onOpenPhotoBatch: (() -> Unit)?,
    onOpenBatch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        PhoneStageChip(
            text = "⋯",
            description = "更多操作",
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (onOpenPhotoBatch != null) "批量导出 · 按照片" else "批量导出",
                        style = FmType.bodyMedium,
                    )
                },
                onClick = {
                    expanded = false
                    if (onOpenPhotoBatch != null) onOpenPhotoBatch() else onOpenBatch()
                },
            )
            if (onApplyAll != null) {
                DropdownMenuItem(
                    text = { Text("应用到全部照片", style = FmType.bodyMedium) },
                    onClick = {
                        expanded = false
                        onApplyAll()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("设置", style = FmType.bodyMedium) },
                onClick = {
                    expanded = false
                    onOpenSettings()
                },
            )
        }
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
            // WP-T report §1.6 item 9: the dashed "add photo" outline is a token now.
            .dashedBorder(FmPalette.onSurfaceFaint, 7.dp)
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

// ---------------------------------------------------------------------------
// Watermark tool
// ---------------------------------------------------------------------------

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
