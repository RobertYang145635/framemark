package com.framemark.native.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.FeelProbe
import com.framemark.native.LaunchOptions
import com.framemark.native.inspector.BorderSection
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorDataSource
import com.framemark.native.inspector.InspectorLoadState
import com.framemark.native.inspector.RegistryPorts
import com.framemark.native.workflow.PresetStore
import com.framemark.native.inspector.InspectorModelFactory
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.SectionCard
import com.framemark.native.inspector.SectionCardShell
import com.framemark.native.inspector.WatermarkSection
import com.framemark.native.inspector.applyDemoEdits
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryDataSource
import com.framemark.native.library.LibraryFilterState
import com.framemark.native.library.LibraryLoadState
import com.framemark.native.library.LibraryPreview
import com.framemark.native.library.LibraryScreen
import com.framemark.native.theme.FmDesktop
import com.framemark.native.theme.FmElevation
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.ui.resizeCursor
import com.framemark.native.product.BatchJobStatus
import com.framemark.native.product.PhotoRef
import com.framemark.native.product.toDesignSnapshot
import com.framemark.native.workflow.ProjectBatchPanel
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.DesktopWatermarkPanel
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.ExportSheet
import com.framemark.native.workflow.InMemoryProjectHolder
import com.framemark.native.workflow.NativeEngine
import com.framemark.native.workflow.NativeStagePreview
import com.framemark.native.workflow.ProjectHolder
import com.framemark.native.workflow.ProjectPhotoStage
import com.framemark.native.workflow.ProjectPhotoStrip
import com.framemark.native.workflow.ProjectStatusBanner
import com.framemark.native.workflow.ProjectPresetSheet
import com.framemark.native.workflow.PrimaryButton
import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.WorkflowEnv
import kotlinx.coroutines.launch

/**
 * Desktop layout state, hoisted above the breakpoint by [FrameMarkApp] so a
 * live resize to phone width and back keeps the rail widths and the collapsed
 * side. Widths are remembered for the app run (not persisted to disk yet).
 */
class DesktopLayoutState {
    var leftWidth by mutableStateOf(FmDesktop.libraryWidth)
    var rightWidth by mutableStateOf(FmDesktop.inspectorWidth)
    var leftCollapsed by mutableStateOf(false)
    var rightCollapsed by mutableStateOf(false)
}

private enum class StageMode { Edit, Batch }

private enum class DesktopTab(val label: String, val section: InspectorSection?) {
    Border("边框", InspectorSection.Border),
    Logo("Logo", InspectorSection.Logo),
    Text("文字", InspectorSection.Text),
    Layout("布局", InspectorSection.Layout),
    Theme("主题", InspectorSection.Theme),
    Watermark("水印", null),
}

/**
 * U5 desktop three-column workspace (>= 600 dp).
 *
 *  - left library rail: the U2 library in rail mode (categories / vendor /
 *    search / 175 cards), collapsible and horizontally resizable;
 *  - centre stage: selected border on a built-in sample photo, with a filmstrip
 *    (built-in placeholders only) or the import placeholder;
 *  - right inspector: the U3b data-driven controls as section tabs, collapsible
 *    and resizable.
 *
 * The stage is a static border preview with honest labels: control edits are
 * local, export is the U4a placeholder, batch progress is simulated.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DesktopShell(layout: DesktopLayoutState) {
    val projects = WorkflowEnv.projectHolder ?: remember { InMemoryProjectHolder() }
    DesktopShell(layout, projects)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DesktopShell(layout: DesktopLayoutState, projects: ProjectHolder) {
    val filters = remember { LibraryFilterState() }
    val libraryGrid = remember { LazyGridState() }
    val scope = rememberCoroutineScope()
    val session = remember {
        DesignSession().apply {
            LaunchOptions.exportFormat?.let { raw ->
                val fmt = SampleFormat.entries.firstOrNull {
                    it.extension.equals(raw, ignoreCase = true) || it.label.equals(raw, ignoreCase = true)
                }
                if (fmt != null) export = export.copy(format = fmt)
            }
            LaunchOptions.exportLongEdge?.let { export = export.copy(longEdge = it) }
            export = export.copy(webpLossless = LaunchOptions.exportWebpLossless)
        }
    }
    val focusRequester = remember { FocusRequester() }

    var selectedCard by remember { mutableStateOf<LibraryCard?>(null) }
    var removeTarget by remember { mutableStateOf<PhotoRef?>(null) }
    var importNotice by remember { mutableStateOf<String?>(null) }
    var libraryState by remember { mutableStateOf<LibraryLoadState>(LibraryLoadState.Loading) }
    var libraryReload by remember { mutableIntStateOf(0) }
    var inspectorState by remember { mutableStateOf<InspectorLoadState>(InspectorLoadState.Idle) }
    var inspectorReload by remember { mutableIntStateOf(0) }
    var autoOpened by remember { mutableStateOf(false) }
    var stageMode by remember { mutableStateOf(StageMode.Edit) }
    var tab by remember { mutableStateOf(DesktopTab.Border) }
    var presetSheetOpen by remember { mutableStateOf(false) }
    var exportSheetOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }

    fun requestExport(): Boolean {
        val photo = projects.activePhoto
        val design = projects.activeDesign
        if (photo != null && design == null) {
            importNotice = "当前照片尚未应用边框，请先从左侧选择一款边框。"
            return false
        }
        if (photo != null && design?.cardId != selectedCard?.id) {
            val card = (libraryState as? LibraryLoadState.Ready)?.snapshot?.cards
                ?.firstOrNull { it.id == design?.cardId }
            if (card == null) {
                importNotice = "当前照片的边框暂时不可用，请重新选择边框后再导出。"
                return false
            }
            selectedCard = card
        }
        exportSheetOpen = true
        return true
    }

    LaunchedEffect(Unit) {
        projects.load()
        projects.loadPresets()
        projects.loadBatch()
        LaunchOptions.libraryQuery?.let { filters.query = it }
        // The window may not own focus yet on the first frame; retry briefly so
        // the Ctrl/Cmd shortcuts work without clicking first.
        repeat(6) {
            runCatching { focusRequester.requestFocus() }
            kotlinx.coroutines.delay(250)
        }
    }

    LaunchedEffect(selectedCard?.id) { session.onCardChanged() }

    // Load the frozen E1a bundle once; retry only re-runs this effect.
    LaunchedEffect(libraryReload) {
        val platform = WorkflowEnv.platform
        fun elapsed(): Long = platform?.let { p ->
            val start = p.processStartMillis()
            if (start == null) -1L else p.nowMillis() - start
        } ?: -1L
        println("FM_LIB_START elapsedMs=${elapsed()}")
        val loadMark = kotlin.time.TimeSource.Monotonic.markNow()
        libraryState = LibraryLoadState.Loading
        val result = LibraryDataSource.load()
        libraryState = if (result.error == null) {
            val snapshot = result.snapshot
            println(
                "FM_LIB_READY cards=${snapshot.cards.size} categories=${snapshot.categories.size} " +
                    "previews=${snapshot.mappedPreviews} unmapped=${snapshot.unmappedPreviewIds.size} " +
                    "generatedAt=${snapshot.generatedAt} elapsedMs=${elapsed()} " +
                    "loadMs=${loadMark.elapsedNow().inWholeMilliseconds}",
            )
            LibraryLoadState.Ready(snapshot)
        } else {
            LibraryLoadState.Failed(result.error)
        }
    }

    // P3f: the desktop inspector is driven by product.ControlRegistry plus the IR
    // slot binding — one vocabulary. There is deliberately no fallback to the old
    // InspectorData word list: if the registry cannot be read, the panel says so.
    LaunchedEffect(inspectorReload) {
        inspectorState = InspectorLoadState.Loading
        val provider = RegistryPorts.provider
        val load = provider?.load()
        inspectorState = if (load != null) {
            InspectorLoadState.Ready(registry = load)
        } else {
            InspectorLoadState.Failed(
                "控件注册表数据缺失：" + (provider?.missing()?.joinToString("、") ?: "本平台没有注册表"),
            )
        }
    }

    val registryState = (inspectorState as? InspectorLoadState.Ready)?.registry
    val model = remember(selectedCard?.id, registryState) {
        val card = selectedCard ?: return@remember null
        val state = registryState ?: return@remember null
        state.modelFor(card.id, card.name, card.category, card.categoryName)
    }

    // P2a: project is the source of truth; the DesignSession is a hydrated view
    // that writes every change back through the controller.
    val currentModel by rememberUpdatedState(model)
    LaunchedEffect(Unit) {
        session.onValuesChanged = { _, overrides ->
            currentModel?.let { projects.applyControlOverrides(it, overrides) }
        }
        session.onExportChanged = { projects.setExportSettings(it) }
    }
    LaunchedEffect(model?.cardId, projects.activePhotoId, projects.hydrationVersion) {
        val built = model ?: return@LaunchedEffect
        session.seed(built.controls)
        session.replaceValues(projects.designValues(built))
        session.setExportSilently(projects.exportSettingsForUi())
    }
    LaunchedEffect(libraryState) {
        if (LaunchOptions.openTab != "watermark") return@LaunchedEffect
        if (libraryState !is LibraryLoadState.Ready) return@LaunchedEffect
        kotlinx.coroutines.delay(1200)
        tab = DesktopTab.Watermark
    }
    LaunchedEffect(importNotice) {
        if (importNotice == null) return@LaunchedEffect
        kotlinx.coroutines.delay(6000)
        importNotice = null
    }
    // Selecting a photo (or restoring a project) points the library at the card
    // that photo was last designed with.
    LaunchedEffect(projects.activePhotoId, projects.activeDesign?.cardId, libraryState) {
        if (projects.activePhoto == null) return@LaunchedEffect
        val ready = libraryState as? LibraryLoadState.Ready ?: return@LaunchedEffect
        val design = projects.activeDesign
        if (design == null) {
            selectedCard = null
            return@LaunchedEffect
        }
        val card = ready.snapshot.cards.firstOrNull { it.id == design.cardId }
        if (card == null) {
            selectedCard = null
            return@LaunchedEffect
        }
        if (selectedCard?.id != card.id) selectedCard = card
    }

    // Legacy presets (pre-SessionStore `presets.json`, no watermark, session-keyed
    // values) fold into the product document once the inspector data can map their
    // control keys. Same shape as the phone's migration.
    LaunchedEffect(inspectorState, libraryState, projects.loaded) {
        if (inspectorState !is InspectorLoadState.Ready || !projects.loaded) return@LaunchedEffect
        val registry = (inspectorState as? InspectorLoadState.Ready)?.registry ?: return@LaunchedEffect
        if (projects.legacyBatchPending) projects.migrateLegacyBatch()
        PresetStore.load()
        val legacy = PresetStore.items
        if (legacy.isEmpty()) return@LaunchedEffect
        val converted = legacy.mapNotNull { item ->
            val card = (libraryState as? LibraryLoadState.Ready)?.snapshot?.cards
                ?.firstOrNull { it.id == item.cardId } ?: return@mapNotNull null
            registry.modelFor(card.id, card.name, card.category, card.categoryName)
                ?.let { legacyToPreset(item, card.id, card.systemStyle, it) }
        }
        val migrated = projects.importLegacyPresets(converted)
        if (migrated > 0) println("FM_DESKTOP_PRESET_MIGRATED count=$migrated legacy_kept=${legacy.size}")
    }

    val quickSavePreset: (String) -> Unit = { name ->
        if (selectedCard != null) projects.savePreset(name)
    }

    // A border card usually has no Border-section rows (the card *is* the
    // border). Land on the first section that actually has controls instead of
    // showing an empty summary-only tab. The user can still pick any tab after.
    LaunchedEffect(model?.cardId) {
        val built = model ?: return@LaunchedEffect
        val borderControls = built.sections
            .firstOrNull { it.section == InspectorSection.Border }
            ?.controls
            .orEmpty()
        if (tab == DesktopTab.Border && borderControls.isEmpty()) {
            val first = DesktopTab.entries.firstOrNull { candidate ->
                val section = candidate.section ?: return@firstOrNull false
                section != InspectorSection.Border &&
                    built.sections.firstOrNull { it.section == section }?.controls?.isNotEmpty() == true
            }
            if (first != null) tab = first
        }
    }

    // Seed values + evidence hooks once the selected card's model exists.
    LaunchedEffect(model?.cardId) {
        val built = model ?: return@LaunchedEffect
        session.seed(built.controls)
        if (!LaunchOptions.demoApplied && LaunchOptions.demoEdits > 0) {
            LaunchOptions.demoApplied = true
            val applied = applyDemoEdits(session, built.controls, LaunchOptions.demoEdits)
            println("FM_DEMO_EDITS applied=$applied card=${selectedCard?.id}")
            repeat(LaunchOptions.undoSteps) { session.undo() }
            if (LaunchOptions.undoSteps > 0) println("FM_DEMO_UNDO steps=${LaunchOptions.undoSteps}")
        }
        val presetName = LaunchOptions.autoSavePreset
        if (presetName != null && !LaunchOptions.autoSaveApplied) {
            LaunchOptions.autoSaveApplied = true
            quickSavePreset(presetName)
        }
    }

    // Evidence-only: select a known card once the library is ready.
    LaunchedEffect(libraryState) {
        val id = LaunchOptions.openCardId ?: return@LaunchedEffect
        val ready = libraryState as? LibraryLoadState.Ready ?: return@LaunchedEffect
        if (autoOpened) return@LaunchedEffect
        val card = ready.snapshot.cards.firstOrNull { it.id == id }
        if (card != null) {
            selectedCard = card
            if (!LaunchOptions.openBatch) stageMode = StageMode.Edit
            autoOpened = true
            println("FM_OPEN_CARD ${card.id} name=${card.name}")
        } else {
            println("FM_OPEN_CARD_MISSING $id")
            autoOpened = true
        }
    }

    // Evidence-only: open the product batch stage once the library is ready.
    // Re-enqueueing an unchanged photo+design is a duplicate by idempotency key,
    // so a restart that runs this again cannot create a second copy of a job.
    LaunchedEffect(LaunchOptions.openBatch, libraryState) {
        if (!LaunchOptions.openBatch || LaunchOptions.batchSeeded) return@LaunchedEffect
        if (libraryState !is LibraryLoadState.Ready) return@LaunchedEffect
        LaunchOptions.batchSeeded = true
        stageMode = StageMode.Batch
        if (LaunchOptions.batchReset) {
            projects.batchJobs.filter { it.status != BatchJobStatus.Running }.forEach { projects.removeBatchJob(it.id) }
        }
        val enqueued = if (LaunchOptions.batchCount > 0) {
            projects.enqueueBatch(projects.photos.take(LaunchOptions.batchCount).map { it.id })
        } else {
            0
        }
        println(
            "FM_PRODUCT_BATCH_SEED enqueued=$enqueued total=${projects.batchJobs.size} " +
                "pending=${projects.batchJobs.count { it.status == BatchJobStatus.Queued }}",
        )
        if (WorkflowEnv.platform?.batchAutoStart() == true) projects.startBatch()
    }

    // Evidence-only: open a workflow surface once the inspector is ready.
    LaunchedEffect(selectedCard?.id, inspectorState) {
        if (LaunchOptions.sheetsApplied) return@LaunchedEffect
        val card = selectedCard ?: return@LaunchedEffect
        if (inspectorState !is InspectorLoadState.Ready) return@LaunchedEffect
        when {
            LaunchOptions.openPresetSheet -> {
                presetSheetOpen = true
                LaunchOptions.sheetsApplied = true
                println("FM_OPEN_SHEET presets card=${card.id}")
            }
            LaunchOptions.openExportSheet -> {
                if (requestExport()) {
                    LaunchOptions.sheetsApplied = true
                    println("FM_OPEN_SHEET export card=${card.id}")
                }
            }
        }
    }

    BackHandler(enabled = presetSheetOpen || exportSheetOpen || helpOpen) {
        when {
            helpOpen -> helpOpen = false
            exportSheetOpen -> exportSheetOpen = false
            presetSheetOpen -> presetSheetOpen = false
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(FmPalette.canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val windowWidth = maxWidth
        val narrow = windowWidth < 688.dp
        val rightVisible = !layout.rightCollapsed && (!narrow || layout.leftCollapsed)

        fun collapseLeft() {
            layout.leftCollapsed = true
            if (narrow) layout.rightCollapsed = true
        }

        fun expandLeft() {
            layout.leftCollapsed = false
            if (narrow) layout.rightCollapsed = true
        }

        fun collapseRight() {
            layout.rightCollapsed = true
        }

        fun expandRight() {
            layout.rightCollapsed = false
            if (narrow) layout.leftCollapsed = true
        }

        fun toggleLeft() = if (layout.leftCollapsed) expandLeft() else collapseLeft()
        fun toggleRight() = if (rightVisible) collapseRight() else expandRight()

        val openCard: (LibraryCard) -> Unit = { card ->
            FeelProbe.begin("card", card.id)
            selectedCard = card
            projects.applyCard(card.id, card.systemStyle)
            stageMode = StageMode.Edit
            tab = DesktopTab.Border
        }

        val addPhoto: () -> Unit = {
            val paths = projects.pickPhotos()
            if (paths.isNotEmpty()) {
                scope.launch {
                    val report = projects.importPhotos(paths)
                    println("FM_IMPORT added=${report.added} failed=${report.failures.size}")
                    importNotice = "已导入 ${report.added} 张" + if (report.failures.isEmpty()) "" else "，失败 ${report.failures.size} 张"
                    if (report.added > 0) stageMode = StageMode.Edit
                }
            }
        }

        FeelProbe.Host()
        FeelProbe.ColdStart(libraryState is LibraryLoadState.Ready)
        StartupFrameProbe()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val sheetOpen = presetSheetOpen || exportSheetOpen || helpOpen
                    if (event.key == Key.Escape) {
                        when {
                            helpOpen -> helpOpen = false
                            exportSheetOpen -> exportSheetOpen = false
                            presetSheetOpen -> presetSheetOpen = false
                            else -> return@onPreviewKeyEvent false
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (sheetOpen) return@onPreviewKeyEvent false

                    if (event.key == Key.F1) {
                        helpOpen = true
                        println("FM_SHORTCUT help")
                        return@onPreviewKeyEvent true
                    }
                    if (!event.isCtrlPressed) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Z && event.isShiftPressed -> {
                            val handled = session.redo()
                            if (handled) println("FM_SHORTCUT redo")
                            handled
                        }
                        event.key == Key.Z -> {
                            val handled = session.undo()
                            if (handled) println("FM_SHORTCUT undo")
                            handled
                        }
                        event.key == Key.Y -> {
                            val handled = session.redo()
                            if (handled) println("FM_SHORTCUT redo")
                            handled
                        }
                        event.key == Key.E -> {
                            if (!requestExport()) return@onPreviewKeyEvent true
                            println("FM_SHORTCUT export")
                            true
                        }
                        event.key == Key.P && event.isShiftPressed -> {
                            FeelProbe.begin("preset", "shortcut")
                            presetSheetOpen = true
                            println("FM_SHORTCUT presets")
                            true
                        }
                        event.key == Key.B && event.isShiftPressed -> {
                            toggleRight()
                            println("FM_SHORTCUT rail-right collapsed=${layout.rightCollapsed}")
                            true
                        }
                        event.key == Key.B -> {
                            toggleLeft()
                            println("FM_SHORTCUT rail-left collapsed=${layout.leftCollapsed}")
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = FmDesktop.edge, vertical = FmDesktop.edge),
            ) {
                DesktopTopBar(
                    card = selectedCard,
                    mode = stageMode,
                    photoCount = projects.photos.size,
                    onAddPhoto = addPhoto,
                    onToggleBatch = {
                        stageMode = if (stageMode == StageMode.Batch) StageMode.Edit else StageMode.Batch
                    },
                    onOpenPresets = {
                        FeelProbe.begin("preset", "topbar")
                        presetSheetOpen = true
                    },
                    onOpenExport = { requestExport() },
                    onOpenHelp = { helpOpen = true },
                )
                ProjectStatusBanner(
                    projects = projects,
                    notice = importNotice,
                    onRetryImports = {
                        val failed = projects.importFailures.map { it.path }
                        if (failed.isNotEmpty()) {
                            scope.launch { projects.importPhotos(failed) }
                        }
                    },
                )
                Spacer(Modifier.height(FmDesktop.gap))

                DesktopColumns(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    layout = layout,
                    filters = filters,
                    libraryState = libraryState,
                    libraryGrid = libraryGrid,
                    onRetryLibrary = { libraryReload++ },
                    onOpenCard = openCard,
                    onCollapseLeft = ::collapseLeft,
                    onExpandLeft = ::expandLeft,
                    onCollapseRight = ::collapseRight,
                    onExpandRight = ::expandRight,
                    selectedCard = selectedCard,
                    model = model,
                    session = session,
                    inspectorState = inspectorState,
                    onRetryInspector = { inspectorReload++ },
                    tab = tab,
                    onSelectTab = { tab = it },
                    onUndo = { session.undo() },
                    onRedo = { session.redo() },
                    stageMode = stageMode,
                    projects = projects,
                    onAddPhoto = addPhoto,
                    onRemovePhoto = { id -> removeTarget = projects.photos.firstOrNull { it.id == id } },
                )
            }

            if (helpOpen) {
                HelpOverlay(onDismiss = { helpOpen = false })
            }
            if (presetSheetOpen) {
                ProjectPresetSheet(
                    projects = projects,
                    cardLookup = { id ->
                        (libraryState as? LibraryLoadState.Ready)?.snapshot?.cards?.firstOrNull { it.id == id }
                    },
                    onDismiss = { presetSheetOpen = false },
                )
            }
            if (exportSheetOpen) {
                val activeDesign = projects.activeDesign
                val activePhoto = projects.activePhoto
                val exportCard = if (activePhoto != null) {
                    (libraryState as? LibraryLoadState.Ready)?.snapshot?.cards
                        ?.firstOrNull { it.id == activeDesign?.cardId }
                } else {
                    selectedCard
                }
                ExportSheet(
                    session = session,
                    card = exportCard,
                    autoGenerate = LaunchOptions.runSampleExport,
                    design = if (activePhoto != null) {
                        activeDesign?.takeIf { it.cardId == exportCard?.id }?.toDesignSnapshot()
                    } else {
                        exportCard?.let { NativeEngine.designOf(it, model, session) }
                    },
                    photoPath = activePhoto?.path,
                    photoDisplayName = activePhoto?.displayName,
                    photoIndex = projects.photoIndex(projects.activePhotoId),
                    overlay = { projects.overlaySpec() },
                    onDismiss = { exportSheetOpen = false },
                )
            }

            removeTarget?.let { target ->
                AlertDialog(
                    onDismissRequest = { removeTarget = null },
                    title = { Text("移除照片") },
                    text = {
                        Text("从工程中移除「${target.displayName}」？对应设计和水印会一起删除，不会删除原文件。")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            projects.removePhoto(target.id)
                            removeTarget = null
                        }) { Text("移除") }
                    },
                    dismissButton = {
                        TextButton(onClick = { removeTarget = null }) { Text("取消") }
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

@Composable
private fun DesktopTopBar(
    card: LibraryCard?,
    mode: StageMode,
    photoCount: Int,
    onAddPhoto: () -> Unit,
    onToggleBatch: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 688.dp) {
            Column(Modifier.fillMaxWidth().height(108.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandBlock(compact = true)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
                ) {
                    BarAction("添加", onAddPhoto)
                    BarAction(if (mode == StageMode.Batch) "编辑" else "批量", onToggleBatch)
                    BarAction("预设", onOpenPresets)
                    BarAction("导出", onOpenExport, primary = true)
                    BarAction("帮助", onOpenHelp)
                }
            }
            return@BoxWithConstraints
        }
        val compact = maxWidth < 860.dp
        Row(
            modifier = Modifier.fillMaxWidth().height(FmDesktop.topBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandBlock(compact = compact)
            Spacer(Modifier.width(FmSpacing.md))
            if (!compact) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 460.dp)
                            .fillMaxWidth(0.92f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(FmRadii.pill))
                            .background(FmPalette.surface)
                            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.pill))
                            .padding(horizontal = FmSpacing.lg),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when {
                                    mode == StageMode.Batch -> "批量队列 · 原生导出"
                                    card != null -> "当前边框 · ${card.name}"
                                    else -> "尚未选择边框"
                                },
                                style = FmType.labelMedium,
                                color = if (card != null) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (photoCount > 0) {
                                Spacer(Modifier.width(FmSpacing.sm))
                                Text(
                                    text = "$photoCount 张照片",
                                    style = FmType.labelSmall,
                                    color = FmPalette.onSurfaceFaint,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarAction("添加照片", onAddPhoto)
                BarAction(if (mode == StageMode.Batch) "返回编辑" else "批量", onToggleBatch)
                BarAction("预设", onOpenPresets)
                BarAction("导出", onOpenExport, primary = true)
                BarAction("帮助", onOpenHelp)
            }
        }
    }
}

@Composable
private fun BrandBlock(compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(FmPalette.surface)
                .border(1.dp, FmPalette.outline, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(22.dp)) {
                val stroke = Stroke(width = 1.8.dp.toPx(), join = StrokeJoin.Round)
                drawRoundRect(
                    color = FmPalette.primary,
                    topLeft = Offset(size.width * 0.08f, size.height * 0.08f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.84f, size.height * 0.84f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.20f),
                    style = stroke,
                )
                drawRoundRect(
                    color = FmPalette.primaryStrong,
                    topLeft = Offset(size.width * 0.32f, size.height * 0.32f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.36f, size.height * 0.36f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.07f),
                    style = stroke,
                )
            }
        }
        Spacer(Modifier.width(FmSpacing.sm))
        Column {
            Text("FrameMark", style = FmType.titleSmall, color = FmPalette.onSurface)
            if (!compact) {
                Text(
                    "桌面版 · ${WorkflowEnv.platform?.buildLabel()?.takeIf { it.isNotBlank() } ?: "开发构建"}",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceFaint,
                )
            }
        }
    }
}

@Composable
private fun BarAction(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(if (primary) FmPalette.primary else FmPalette.surface)
            .border(
                width = 1.dp,
                color = if (primary) Color.Transparent else FmPalette.outline,
                shape = RoundedCornerShape(FmRadii.pill),
            )
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

// ---------------------------------------------------------------------------
// Three columns
// ---------------------------------------------------------------------------

@Composable
private fun DesktopColumns(
    modifier: Modifier,
    layout: DesktopLayoutState,
    filters: LibraryFilterState,
    libraryState: LibraryLoadState,
    libraryGrid: LazyGridState,
    onRetryLibrary: () -> Unit,
    onOpenCard: (LibraryCard) -> Unit,
    onCollapseLeft: () -> Unit,
    onExpandLeft: () -> Unit,
    onCollapseRight: () -> Unit,
    onExpandRight: () -> Unit,
    selectedCard: LibraryCard?,
    model: InspectorCardModel?,
    session: DesignSession,
    inspectorState: InspectorLoadState,
    onRetryInspector: () -> Unit,
    tab: DesktopTab,
    onSelectTab: (DesktopTab) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    stageMode: StageMode,
    projects: ProjectHolder,
    onAddPhoto: () -> Unit,
    onRemovePhoto: (String) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        if (maxWidth < 688.dp) {
            var pane by remember { mutableIntStateOf(0) }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("照片", "边框库", "参数").forEachIndexed { index, label ->
                        com.framemark.native.workflow.ChoiceChip(label, pane == index, { pane = index })
                    }
                }
                Spacer(Modifier.height(FmSpacing.sm))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (pane) {
                        1 -> LibraryScreen(
                            loadState = libraryState, filters = filters, gridState = libraryGrid,
                            onRetry = onRetryLibrary, onOpenCard = { onOpenCard(it); pane = 0 },
                            rail = true, selectedCardId = selectedCard?.id,
                        )
                        2 -> DesktopInspector(
                            card = selectedCard, model = model, session = session, projects = projects,
                            inspectorState = inspectorState, onRetry = onRetryInspector, tab = tab,
                            onSelectTab = onSelectTab, onUndo = onUndo, onRedo = onRedo,
                            onCollapse = { pane = 0 },
                        )
                        else -> if (stageMode == StageMode.Batch) ProjectBatchPanel(projects) else EditStage(
                            card = selectedCard, session = session, model = model, projects = projects,
                            onAddPhoto = onAddPhoto, onRemovePhoto = onRemovePhoto,
                        )
                    }
                }
            }
            return@BoxWithConstraints
        }
        val gap = FmDesktop.gap
        val collapsedWidth = FmDesktop.collapsedWidth
        val bothFit = maxWidth >= FmDesktop.libraryMin + FmDesktop.inspectorMin + FmDesktop.stageMin + gap * 2
        val showLeft = !layout.leftCollapsed
        val showRight = !layout.rightCollapsed && (bothFit || layout.leftCollapsed)
        val rightReserve = if (showRight) layout.rightWidth else collapsedWidth
        val maxLeft = (maxWidth - rightReserve - gap * 2 - FmDesktop.stageMin)
            .coerceIn(FmDesktop.libraryMin, FmDesktop.libraryMax)
        val leftWidth = if (showLeft) {
            layout.leftWidth.coerceIn(FmDesktop.libraryMin, maxLeft)
        } else {
            collapsedWidth
        }
        val maxRight = (maxWidth - leftWidth - gap * 2 - FmDesktop.stageMin)
            .coerceIn(FmDesktop.inspectorMin, FmDesktop.inspectorMax)
        val rightWidth = if (showRight) {
            layout.rightWidth.coerceIn(FmDesktop.inspectorMin, maxRight)
        } else {
            collapsedWidth
        }
        val stageWidth = maxWidth - leftWidth - rightWidth - gap * 2

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            if (showLeft) {
                DesktopPanel(width = leftWidth) {
                    LibraryScreen(
                        loadState = libraryState,
                        filters = filters,
                        gridState = libraryGrid,
                        onRetry = onRetryLibrary,
                        onOpenCard = onOpenCard,
                        rail = true,
                        headerTrailing = {
                            ChevronButton(
                                glyph = "‹",
                                description = "折叠边框库",
                                onClick = onCollapseLeft,
                            )
                        },
                        selectedCardId = selectedCard?.id,
                    )
                }
            } else {
                CollapsedRail(side = "left", onExpand = onExpandLeft)
            }

            DesktopPanel(modifier = Modifier.weight(1f)) {
                if (stageMode == StageMode.Batch) {
                    ProjectBatchPanel(projects)
                } else {
                    EditStage(
                        card = selectedCard,
                        session = session,
                        model = model,
                        projects = projects,
                        onAddPhoto = onAddPhoto,
                        onRemovePhoto = onRemovePhoto,
                    )
                }
            }

            if (showRight) {
                DesktopPanel(width = rightWidth) {
                    DesktopInspector(
                        card = selectedCard,
                        model = model,
                        session = session,
                        projects = projects,
                        inspectorState = inspectorState,
                        onRetry = onRetryInspector,
                        tab = tab,
                        onSelectTab = onSelectTab,
                        onUndo = onUndo,
                        onRedo = onRedo,
                        onCollapse = onCollapseRight,
                    )
                }
            } else {
                CollapsedRail(side = "right", onExpand = onExpandRight)
            }
        }

        if (showLeft) {
            ResizeHandle(
                x = leftWidth,
                onDrag = { delta ->
                    val limit = (maxWidth - rightWidth - gap * 2 - FmDesktop.stageMin)
                        .coerceIn(FmDesktop.libraryMin, FmDesktop.libraryMax)
                    layout.leftWidth = (leftWidth + delta).coerceIn(FmDesktop.libraryMin, limit)
                },
            )
        }
        if (showRight) {
            ResizeHandle(
                x = leftWidth + gap + stageWidth,
                onDrag = { delta ->
                    val limit = (maxWidth - leftWidth - gap * 2 - FmDesktop.stageMin)
                        .coerceIn(FmDesktop.inspectorMin, FmDesktop.inspectorMax)
                    layout.rightWidth = (rightWidth - delta).coerceIn(FmDesktop.inspectorMin, limit)
                },
            )
        }
    }
}

@Composable
private fun DesktopPanel(
    modifier: Modifier = Modifier,
    width: Dp? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(FmDesktop.panelRadius)
    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .fillMaxHeight()
            .shadow(
                elevation = FmElevation.card,
                shape = shape,
                ambientColor = FmElevation.cardAmbient,
                spotColor = FmElevation.cardSpot,
            )
            .clip(shape)
            .background(FmPalette.surface),
    ) {
        content()
    }
}

@Composable
private fun CollapsedRail(side: String, onExpand: () -> Unit) {
    val shape = RoundedCornerShape(FmDesktop.panelRadius)
    Box(
        modifier = Modifier
            .width(FmDesktop.collapsedWidth)
            .fillMaxHeight()
            .shadow(
                elevation = FmElevation.card,
                shape = shape,
                ambientColor = FmElevation.cardAmbient,
                spotColor = FmElevation.cardSpot,
            )
            .clip(shape)
            .background(FmPalette.surface)
            .clickable(onClick = onExpand)
            .semantics {
                contentDescription = if (side == "left") "展开边框库" else "展开参数面板"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (side == "left") "›" else "‹",
            style = FmType.titleMedium,
            color = FmPalette.primaryStrong,
        )
    }
}

@Composable
private fun ChevronButton(glyph: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = FmType.titleMedium, color = FmPalette.onSurfaceMuted)
    }
}

@Composable
private fun ResizeHandle(x: Dp, onDrag: (Dp) -> Unit) {
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(
        modifier = Modifier
            .offset(x = x)
            .width(FmDesktop.gap)
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

// ---------------------------------------------------------------------------
// Centre stage
// ---------------------------------------------------------------------------

@Composable
private fun EditStage(
    card: LibraryCard?,
    session: DesignSession,
    model: InspectorCardModel?,
    projects: ProjectHolder,
    onAddPhoto: () -> Unit,
    onRemovePhoto: (String) -> Unit,
) {
    val native = card != null && NativeEngine.supports(card.id)
    val activeDesign = projects.activeDesign
    val design = when {
        projects.activePhoto != null -> activeDesign?.takeIf { it.cardId == card?.id }?.toDesignSnapshot()
        activeDesign != null && activeDesign.cardId == card?.id -> activeDesign.toDesignSnapshot()
        else -> card?.let { NativeEngine.designOf(it, model, session) }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = projects.activePhoto?.displayName ?: card?.name ?: "尚未添加照片",
                    style = FmType.titleMedium,
                    color = FmPalette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = when {
                        projects.activePhoto != null && activeDesign == null -> "未应用边框 · 选择边框开始"
                        native -> "原生引擎渲染 · 参数实时"
                        card != null -> "静态边框预览 · 参数未渲染"
                        projects.activePhoto != null -> "自己的照片 · 尚未选择边框"
                        else -> "尚未添加照片"
                    },
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(FmSpacing.sm))
            Tag(
                text = when {
                    projects.activePhoto != null && activeDesign == null -> "未应用边框"
                    native -> "原生渲染"
                    card != null -> "静态预览"
                    else -> "占位"
                },
                onSurface = false,
            )
        }

        var zoom by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        var stagePx by remember { mutableStateOf(IntSize.Zero) }

        fun clampPan(next: Offset, z: Float = zoom): Offset {
            if (z <= 1f) return Offset.Zero
            val maxX = stagePx.width * (z - 1f) / 2f
            val maxY = stagePx.height * (z - 1f) / 2f
            return Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
        }

        /** 25%..400% with a 100% detent; zoom steps are multiplicative. */
        fun wheelZoom(anchor: Offset, dy: Float) {
            val factor = if (dy < 0f) 1.1f else 1f / 1.1f
            val raw = (zoom * factor).coerceIn(0.25f, 4f)
            val next = if (raw in 0.94f..1.06f) 1f else raw
            if (next == zoom) return
            if (next <= 1f) {
                zoom = next
                pan = Offset.Zero
            } else {
                val center = Offset(stagePx.width / 2f, stagePx.height / 2f)
                val proposed = anchor - center - (anchor - center - pan) * (next / zoom)
                zoom = next
                pan = clampPan(proposed, next)
            }
            println(
                "FM_WHEEL dy=${"%.0f".format(dy)} anchor=${"%.0f".format(anchor.x)},${"%.0f".format(anchor.y)} " +
                    "zoom=${"%.3f".format(zoom)} pan=${"%.1f".format(pan.x)},${"%.1f".format(pan.y)}",
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = FmSpacing.lg, end = FmSpacing.lg, bottom = FmSpacing.md)
                .clip(RoundedCornerShape(FmRadii.card))
                .background(FmPalette.surfaceMuted)
                .onSizeChanged { stagePx = it }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val dy = change.scrollDelta.y
                            if (dy == 0f) continue
                            // Consume so the wheel can never scroll the shell.
                            change.consume()
                            wheelZoom(change.position, dy)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        if (zoom <= 1f) return@detectDragGestures
                        change.consume()
                        pan = clampPan(pan + drag)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            zoom = 1f
                            pan = Offset.Zero
                            println("FM_WHEEL reset zoom=1.000")
                        },
                    )
                },
        ) {
            if (projects.photos.isEmpty()) {
                ImportPlaceholder(onAddPhoto = onAddPhoto)
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = pan.x
                            translationY = pan.y
                        },
                ) {
                    ProjectPhotoStage(projects = projects, card = card, design = design)
                }
            }
        }

        if (projects.photos.isNotEmpty()) {
            ProjectPhotoStrip(
                projects = projects,
                onAddPhoto = onAddPhoto,
                onRemovePhoto = onRemovePhoto,
            )
        }
    }
}

@Composable
private fun StagePhoto(card: LibraryCard?, photoIndex: Int, design: DesignSnapshot?, native: Boolean) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(FmSpacing.lg)) {
        val width = minOf(maxWidth * 0.92f, maxHeight * 0.92f * 4f / 3f)
        Box(
            modifier = Modifier
                .width(width)
                .aspectRatio(4f / 3f)
                .align(Alignment.Center)
                .shadow(2.dp, RoundedCornerShape(FmRadii.tile))
                .clip(RoundedCornerShape(FmRadii.tile))
                .background(FmPalette.surface),
        ) {
            if (card != null) {
                if (native && design != null) {
                    NativeStagePreview(card = card, design = design, modifier = Modifier.fillMaxSize())
                } else {
                    LibraryPreview(card = card, modifier = Modifier.fillMaxSize())
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(photoBrush(photoIndex))
                        .padding(FmSpacing.xxl),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("从左侧选择边框", style = FmType.titleMedium, color = FmPalette.onSurface)
                    Spacer(Modifier.height(FmSpacing.xs))
                    Text(
                        "175 套边框都可以在这里预览；参数在右侧调整。",
                        style = FmType.bodyMedium,
                        color = FmPalette.onSurfaceMuted,
                    )
                }
            }
        }
        Tag(
            text = when {
                native -> "原生渲染"
                card != null -> "静态预览 · 未套用参数"
                else -> "示例照片 · 占位"
            },
            modifier = Modifier.align(Alignment.BottomStart),
            onSurface = true,
        )
    }
}

@Composable
private fun ImportPlaceholder(onAddPhoto: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(FmSpacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(FmRadii.panel))
                .background(FmPalette.surface)
                .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.panel))
                .padding(FmSpacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("导入照片", style = FmType.titleLarge, color = FmPalette.onSurface)
            Spacer(Modifier.height(FmSpacing.xs))
            Text(
                "为照片加上边框、文字与水印",
                style = FmType.bodyMedium,
                color = FmPalette.onSurfaceMuted,
            )
            Spacer(Modifier.height(FmSpacing.xl))
            PrimaryButton(
                text = "选择照片",
                onClick = onAddPhoto,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(FmSpacing.md))
            Text(
                "支持 JPEG / PNG / WebP / TIFF，可多选；失败的图片会单独列出。",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceFaint,
            )
        }
    }
}

@Composable
private fun Filmstrip(
    photoCount: Int,
    activePhoto: Int,
    onSelectPhoto: (Int) -> Unit,
    onAddPhoto: () -> Unit,
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(FmPalette.outline),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("照片", style = FmType.labelMedium, color = FmPalette.onSurface)
            Spacer(Modifier.width(FmSpacing.sm))
            Text(
                "$photoCount 张 · 内置示例",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "添加照片",
                style = FmType.labelSmall,
                color = FmPalette.primaryStrong,
                modifier = Modifier
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .clickable(onClick = onAddPhoto)
                    .padding(horizontal = FmSpacing.sm, vertical = 2.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
        ) {
            for (index in 0 until photoCount) {
                val active = index == activePhoto
                Box(
                    modifier = Modifier
                        .width(68.dp)
                        .height(52.dp)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .background(photoBrush(index))
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) FmPalette.primary else FmPalette.outline,
                            shape = RoundedCornerShape(FmRadii.chip),
                        )
                        .clickable { onSelectPhoto(index) },
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Text(
                        text = (index + 1).toString().padStart(2, '0'),
                        style = FmType.labelSmall.copy(fontSize = 10.sp),
                        color = FmPalette.onSurfaceSecondary,
                        modifier = Modifier
                            .padding(4.dp)
                            .clip(RoundedCornerShape(FmRadii.pill))
                            .background(FmPalette.surface.copy(alpha = 0.9f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            if (photoCount < StagePhotoColors.size) {
                Box(
                    modifier = Modifier
                        .width(52.dp)
                        .height(52.dp)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .background(FmPalette.surfaceMuted)
                        .border(1.dp, FmPalette.outlineStrong, RoundedCornerShape(FmRadii.chip))
                        .clickable(onClick = onAddPhoto),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("＋", style = FmType.titleMedium, color = FmPalette.onSurfaceMuted)
                }
            }
        }
        Spacer(Modifier.height(FmSpacing.xs))
    }
}

// ---------------------------------------------------------------------------
// Right inspector
// ---------------------------------------------------------------------------

@Composable
private fun DesktopInspector(
    card: LibraryCard?,
    model: InspectorCardModel?,
    session: DesignSession,
    projects: ProjectHolder,
    inspectorState: InspectorLoadState,
    onRetry: () -> Unit,
    tab: DesktopTab,
    onSelectTab: (DesktopTab) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCollapse: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = FmSpacing.lg, end = FmSpacing.sm, top = FmSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("参数", style = FmType.titleMedium, color = FmPalette.onSurface)
                Text(
                    text = card?.name ?: "未选择边框",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HistoryButton(
                label = "撤销",
                redo = false,
                enabled = session.history.canUndo,
                onClick = onUndo,
            )
            Spacer(Modifier.width(2.dp))
            HistoryButton(
                label = "重做",
                redo = true,
                enabled = session.history.canRedo,
                onClick = onRedo,
            )
            Spacer(Modifier.width(2.dp))
            ChevronButton(glyph = "›", description = "折叠参数面板", onClick = onCollapse)
        }
        Spacer(Modifier.height(FmSpacing.sm))
        TabStrip(selected = tab, onSelect = onSelectTab)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(FmPalette.outline),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(FmSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FmSpacing.md),
        ) {
            if (card == null) {
                SectionCardShell(title = "可调参数") {
                    Text(
                        "从左侧选择一套边框，这里会显示可调参数。",
                        style = FmType.bodyMedium,
                        color = FmPalette.onSurfaceMuted,
                    )
                }
            } else {
                when (tab) {
                    DesktopTab.Border -> BorderSection(
                        card = card,
                        model = model,
                        session = session,
                        loadState = inspectorState,
                        onRetry = onRetry,
                    )
                    DesktopTab.Watermark -> DesktopWatermarkPanel(projects, modifier = Modifier.fillMaxWidth())
                    else -> {
                        val section = model?.sections?.firstOrNull { it.section == tab.section }
                        if (section != null && section.controls.isNotEmpty()) {
                            SectionCard(section = section, session = session)
                        } else {
                            SectionCardShell(title = tab.label) {
                                Text(
                                    text = if (inspectorState is InspectorLoadState.Ready) {
                                        "该边框暂无此段参数"
                                    } else {
                                        "正在载入可调参数…"
                                    },
                                    style = FmType.bodyMedium,
                                    color = FmPalette.onSurfaceMuted,
                                )
                                if (inspectorState is InspectorLoadState.Failed) {
                                    Text(
                                        "暂时无法加载参数。",
                                        style = FmType.bodyMedium,
                                        color = FmPalette.onSurfaceMuted,
                                    )
                                    Text(
                                        "重试",
                                        style = FmType.labelMedium,
                                        color = FmPalette.primaryStrong,
                                        modifier = Modifier
                                            .padding(top = FmSpacing.sm)
                                            .clip(RoundedCornerShape(FmRadii.pill))
                                            .clickable(onClick = onRetry)
                                            .padding(horizontal = FmSpacing.md, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Text(
                if (card != null && NativeEngine.supports(card.id)) {
                    "预览由原生引擎渲染；参数实时套用。"
                } else {
                    "预览为静态边框图；参数只改本地值。"
                },
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceFaint,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FmSpacing.lg, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Ctrl+Z 撤销 · Ctrl+E 导出",
                style = FmType.labelSmall.copy(fontSize = 10.sp),
                color = FmPalette.onSurfaceFaint,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "F1 帮助",
                style = FmType.labelSmall.copy(fontSize = 10.sp),
                color = FmPalette.onSurfaceFaint,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TabStrip(selected: DesktopTab, onSelect: (DesktopTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = FmSpacing.md, vertical = FmSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DesktopTab.entries.forEach { item ->
            val active = item == selected
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(FmRadii.chip))
                    .background(if (active) FmPalette.primarySoft else Color.Transparent)
                    .clickable { onSelect(item) }
                    .padding(horizontal = FmSpacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.label,
                    style = FmType.labelMedium,
                    color = if (active) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HistoryButton(
    label: String,
    redo: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) FmPalette.primaryStrong else FmPalette.onSurfaceFaint.copy(alpha = 0.7f)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(FmRadii.input))
            .background(if (enabled) FmPalette.surfaceMuted else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val w = size.width
            val h = size.height
            fun x(v: Float): Float = if (redo) w - v * w else v * w
            val stroke = Stroke(
                width = 1.7.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            val path = Path().apply {
                moveTo(x(0.18f), h * 0.62f)
                cubicTo(x(0.30f), h * 0.20f, x(0.80f), h * 0.20f, x(0.84f), h * 0.56f)
            }
            drawPath(path, color = tint, style = stroke)
            drawLine(
                color = tint,
                start = Offset(x(0.18f), h * 0.62f),
                end = Offset(x(0.18f), h * 0.34f),
                strokeWidth = 1.7.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = tint,
                start = Offset(x(0.18f), h * 0.62f),
                end = Offset(x(0.44f), h * 0.62f),
                strokeWidth = 1.7.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Help (plain language)
// ---------------------------------------------------------------------------

@Composable
private fun HelpOverlay(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FmPalette.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 540.dp)
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(FmRadii.panel))
                .background(FmPalette.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(FmSpacing.xxl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("快捷键", style = FmType.titleLarge, color = FmPalette.onSurface)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = FmSpacing.sm, vertical = 4.dp),
                ) {
                    Text("关闭", style = FmType.labelMedium, color = FmPalette.primaryStrong)
                }
            }
            Spacer(Modifier.height(FmSpacing.xs))
            Text(
                "桌面版常用操作。手机尺寸下没有键盘快捷键。",
                style = FmType.bodyMedium,
                color = FmPalette.onSurfaceMuted,
            )
            Spacer(Modifier.height(FmSpacing.lg))
            HelpSection("编辑")
            HelpRow("Ctrl + Z", "撤销上一步")
            HelpRow("Ctrl + Shift + Z / Ctrl + Y", "重做")
            HelpSection("工作流")
            HelpRow("Ctrl + E", "打开导出（示例占位图）")
            HelpRow("Ctrl + Shift + P", "打开预设")
            HelpSection("布局")
            HelpRow("Ctrl + B", "折叠或展开左侧边框库")
            HelpRow("Ctrl + Shift + B", "折叠或展开右侧参数面板")
            HelpSection("帮助")
            HelpRow("F1", "打开这份快捷键说明")
            HelpRow("Esc", "关闭弹层")
            Spacer(Modifier.height(FmSpacing.lg))
            Text(
                "预览仍是静态边框图，参数只改本地值；导出与批量导出均走原生引擎，文件写入导出目录。",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun HelpSection(title: String) {
    Text(
        text = title,
        style = FmType.labelSmall,
        color = FmPalette.onSurfaceFaint,
        modifier = Modifier.padding(top = FmSpacing.md, bottom = FmSpacing.xxs),
    )
}

@Composable
private fun HelpRow(keys: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = keys,
            style = FmType.labelMedium,
            color = FmPalette.onSurface,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 150.dp, max = 210.dp),
        )
        Spacer(Modifier.width(FmSpacing.md))
        Text(
            text = description,
            style = FmType.bodyMedium,
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// Built-in sample photo placeholders (no reference assets)
// ---------------------------------------------------------------------------

private val StagePhotoColors = listOf(
    Color(0xFFC9D8E4) to Color(0xFFEDE5D7),
    Color(0xFFD8CFC2) to Color(0xFFE7EBEF),
    Color(0xFFCBDCD2) to Color(0xFFEAE3D3),
    Color(0xFFDECBD4) to Color(0xFFEDE3DA),
    Color(0xFFC8D2DE) to Color(0xFFE0E8F0),
    Color(0xFFDACFBA) to Color(0xFFEFE8D9),
)

private fun photoBrush(index: Int): Brush {
    val pair = StagePhotoColors[index.coerceIn(0, StagePhotoColors.lastIndex)]
    return Brush.linearGradient(listOf(pair.first, pair.second))
}

/** G5 cold-start instrumentation: first Compose frame of the desktop shell. */
@Composable
private fun StartupFrameProbe() {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        val platform = WorkflowEnv.platform
        val start = platform?.processStartMillis()
        val now = platform?.nowMillis()
        if (start != null && now != null) {
            println("FM_FIRST_FRAME elapsedMs=${now - start}")
        }
    }
}
