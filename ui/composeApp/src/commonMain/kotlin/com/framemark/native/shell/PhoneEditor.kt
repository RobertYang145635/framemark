package com.framemark.native.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.framemark.native.LaunchOptions
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorLoadState
import com.framemark.native.inspector.WatermarkZones
import com.framemark.native.inspector.applyDemoEdits
import com.framemark.native.inspector.hasTextPage
import com.framemark.native.inspector.inspectorTabsFor
import com.framemark.native.inspector.textSlotNavEntries
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.InMemoryRecentFramesStore
import com.framemark.native.library.RecentFramesEnv
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.LibraryLoadState
import com.framemark.native.library.LibraryPreview
import com.framemark.native.library.LibraryTraversal
import com.framemark.native.shell.phone.PhoneEditorTopBar
import com.framemark.native.shell.phone.PhoneLibrarySheet
import com.framemark.native.shell.phone.PhoneParameterPage
import com.framemark.native.shell.phone.PhoneSwipeFeedback
import com.framemark.native.shell.phone.phoneLibraryTraversal
import com.framemark.native.shell.phone.phoneLoadRecentFrames
import com.framemark.native.shell.phone.phoneRecordRecentFrame
import com.framemark.native.shell.phone.phoneSwipeStep
import com.framemark.native.shell.phone.phoneToolsFor
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.PhotoTray
import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.TrayPhoto
import com.framemark.native.workflow.WatermarkSpec
import kotlinx.coroutines.delay
import com.framemark.native.product.FmLog
import com.framemark.native.shell.phone.photoIndexOf
import com.framemark.native.shell.phone.panelTitle
import com.framemark.native.shell.phone.StageArea
import com.framemark.native.shell.phone.PreviewGeo
import com.framemark.native.shell.phone.PhotoStrip
import com.framemark.native.shell.phone.PhoneSheetHost
import com.framemark.native.shell.phone.ParameterPageHeader

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
    watermark: WatermarkSpec? = null,
    onWatermarkChange: (WatermarkSpec?) -> Unit = {},
    onPickWatermark: () -> Unit = {},
    customizedPhotoIds: Set<Int> = emptySet(),
    onApplyAll: (() -> Unit)? = null,
    onOpenPhotoBatch: (() -> Unit)? = null,
    textSlots: List<TextSlot> = emptyList(),
    textOutputWidth: Int = 0,
    textOutputHeight: Int = 0,
    /** 舞台当前照片的像素尺寸；样式页「导出尺寸」用它算真实成片（不再用导出长边顶替）。 */
    photoWidthPx: Int = 0,
    photoHeightPx: Int = 0,
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
            FmLog.info("FM_DEMO_EDITS") { "applied=$applied card=${card?.id}" }
            repeat(LaunchOptions.undoSteps) { session.undo() }
            if (LaunchOptions.undoSteps > 0) FmLog.info("FM_DEMO_UNDO") { "steps=${LaunchOptions.undoSteps}" }
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
    // V2 sheet: three stops — 收起 (0, the card strip), 半开 (1) and 展开 (2, the grid).
    // WP-X3 §5: the demo's `.psheet` is a freely dragged `height: var(--sh)`, so the
    // stop count is ours to pick; the task book asks for three (WP-F2 D3 measured
    // only two stable heights). Index instead of a Boolean so the drag snap and the
    // chevron cannot disagree about "which of the three am I on".
    var sheetLevel by rememberSaveable { mutableStateOf(0) }
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
    // §3.3: the stage's chip names the highlighted box (`左上 · 品牌`). The 文字 page
    // already derives exactly that name from `textSlotNavEntries`; the shell forwards
    // the finished string instead of teaching `workflow/` a second naming scheme. A
    // readonly row (no writable key) is found by its report id, same as the stage.
    val focusedTextSlotLabel = remember(focusedTextSlotKey, textSlots, textOutputWidth, textOutputHeight) {
        textSlotNavEntries(textSlots, textOutputWidth, textOutputHeight)
            .firstOrNull { it.navKey == focusedTextSlotKey }
            ?.let { entry ->
                entry.role?.takeIf { it.isNotBlank() }?.let { "${entry.positionLabel} · $it" }
                    ?: entry.positionLabel
            }
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
        hasTextSlots = hasTextPage(textSlots.size, textOutputWidth),
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
        BackHandler(enabled = sheetLevel > 0 && !fullParameterPage) { sheetLevel = 0 }

        // Three levels: compact (heights.sheet from the budget), 半开, expanded.
        val compactLevel = heights.sheet.dp
        val expandedLevel = maxOf(heights.sheet, levels?.expanded ?: heights.sheet).dp
        val halfLevel = maxOf(heights.sheet, levels?.half ?: heights.sheet).dp
        val snapHeights = listOf(compactLevel, halfLevel, expandedLevel)
        val draggedH = if (fullParameterPage) null else sheetDragPx?.let { with(density) { it.toDp() } }
        val sheetHeight = if (fullParameterPage) maxHeight else {
            draggedH ?: snapHeights[sheetLevel.coerceIn(0, snapHeights.lastIndex)]
        }
        val stageEstimate = (maxHeight - topBarH - stripH - toolBarH - sheetHeight).coerceAtLeast(0.dp)
        LaunchedEffect(small, maxHeight, sheetLevel, draggedH == null, tool, photosOpen, fullParameterPage) {
            val total = maxHeight.value
            fun pct(v: Float) = "%.1f".format(if (total > 0f) v / total * 100f else 0f)
            FmLog.info("FM_AREA") {
                "full=$fullParameterPage small=$small content=${"%.1f".format(total)}dp " +
                    "top=${topBarH.value} stage=${"%.1f".format(stageEstimate.value)} " +
                    "strip=${stripH.value} panel=${"%.1f".format(sheetHeight.value)} bar=${toolBarH.value} " +
                    "tool=${tool.label} level=$sheetLevel photosOpen=$photosOpen | " +
                    "stage%=${pct(stageEstimate.value)} panel%=${pct(sheetHeight.value)}"
            }
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
                                        sheetLevel > 0 -> sheetLevel = 0
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
                                .weight(1f),
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
                            focusedTextSlotLabel = focusedTextSlotLabel,
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
                PhoneSheetHost(
                    fullParameterPage = fullParameterPage,
                    sheetHeight = sheetHeight,
                    sheetMetrics = sheetMetrics,
                    snapHeights = snapHeights,
                    sheetLevel = sheetLevel,
                    onSheetLevelChange = { sheetLevel = it },
                    sheetDragPx = sheetDragPx,
                    onSheetDragPx = { sheetDragPx = it },
                    density = density,
                    tool = tool,
                    tabModel = tabModel,
                    card = card,
                    libraryIndex = libraryIndex,
                    borderCategory = borderCategory,
                    onBorderCategoryChange = { borderCategory = it },
                    borderQuery = borderQuery,
                    onBorderQueryChange = { borderQuery = it },
                    inUseCardIds = inUseCardIds,
                    recentFrames = recentFrames,
                    onRetryLibrary = onRetryLibrary,
                    pickBorder = ::pickBorder,
                    onOpenPresets = onOpenPresets,
                    onToolChange = onToolChange,
                    model = model,
                    session = session,
                    inspectorState = inspectorState,
                    onRetryInspector = onRetryInspector,
                    textSlots = textSlots,
                    textOutputWidth = textOutputWidth,
                    textOutputHeight = textOutputHeight,
                    onFocusedTextSlotChange = { focusedTextSlotKey = it },
                    watermark = watermark,
                    onWatermarkChange = onWatermarkChange,
                    onPickWatermark = onPickWatermark,
                    photoWidthPx = photoWidthPx,
                    photoHeightPx = photoHeightPx,
                    previewGeo = previewGeo,
                    tools = tools,
                    toolBarH = toolBarH,
                )
            }
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
