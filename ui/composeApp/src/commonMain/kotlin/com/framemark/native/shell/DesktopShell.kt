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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.FeelProbe
import com.framemark.native.LaunchOptions
import com.framemark.native.inspector.BorderSection
import com.framemark.native.inspector.EditorTabList
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorPage
import com.framemark.native.inspector.InspectorPageContent
import com.framemark.native.inspector.InspectorTabModel
import com.framemark.native.inspector.WatermarkPane
import com.framemark.native.inspector.WatermarkZones
import com.framemark.native.inspector.inspectorTabsFor
import com.framemark.native.inspector.resetInspectorPage
import com.framemark.native.inspector.InspectorDataSource
import com.framemark.native.inspector.InspectorLoadState
import com.framemark.native.inspector.RegistryPorts
import com.framemark.native.workflow.PresetStore
import com.framemark.native.inspector.InspectorModelFactory
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.LogoBrandPanel
import com.framemark.native.inspector.NativeTextSlotControls
import com.framemark.native.inspector.SectionCard
import com.framemark.native.inspector.SectionCardShell
import com.framemark.native.inspector.WatermarkSection
import com.framemark.native.inspector.applyDemoEdits
import com.framemark.native.library.InMemoryRecentFramesStore
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryDataSource
import com.framemark.native.library.LibraryFilterState
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.LibraryLoadState
import com.framemark.native.library.LibraryPreview
import com.framemark.native.library.LibraryScreen
import com.framemark.native.library.LibraryTraversal
import com.framemark.native.library.RecentFrames
import com.framemark.native.library.RecentFramesEnv
import com.framemark.native.shell.desktop.DesktopLibraryFilters
import com.framemark.native.shell.desktop.DesktopLibraryPanel
import com.framemark.native.shell.desktop.DesktopSaveState
import com.framemark.native.shell.desktop.DesktopStageCompareFlag
import com.framemark.native.shell.desktop.DesktopStageMeta
import com.framemark.native.shell.desktop.DesktopStageStatusKind
import com.framemark.native.shell.desktop.DesktopStageStatusTag
import com.framemark.native.shell.desktop.DesktopStageToolbar
import com.framemark.native.shell.desktop.DesktopTopBarV2
import com.framemark.native.shell.desktop.DesktopTrial
import com.framemark.native.shell.desktop.OtherPhotoDesign
import com.framemark.native.shell.desktop.desktopPageForLaunchTab
import com.framemark.native.shell.desktop.desktopStageBackdrop
import com.framemark.native.shell.desktop.desktopWorkflowWatermark
import com.framemark.native.shell.desktop.DesktopApplyDesignDialog
import com.framemark.native.shell.desktop.DesktopCollapsedRail
import com.framemark.native.shell.desktop.DesktopFilmstrip
import com.framemark.native.shell.desktop.DesktopInspectorRail
import com.framemark.native.shell.desktop.DesktopPane
import com.framemark.native.shell.desktop.DesktopStageEmptyState
import com.framemark.native.shell.desktop.HairlineEdge
import com.framemark.native.theme.FmDesktop
import com.framemark.native.theme.FmElevation
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.theme.LocalAppearance
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
import com.framemark.native.workflow.ProjectReadRecoveryBlock
import com.framemark.native.workflow.ProjectStatusBanner
import com.framemark.native.workflow.ProjectPresetSheet
import com.framemark.native.workflow.PrimaryButton
import com.framemark.native.workflow.PhotoThumbnail
import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.TextSlotRegistry
import com.framemark.native.workflow.WorkflowEnv
import com.framemark.native.workflow.groupTextSlots
import com.framemark.native.workflow.textFallbackControls
import com.framemark.native.workflow.withNativeTextSlots
import androidx.compose.ui.graphics.RectangleShape
import kotlin.math.abs
import kotlin.math.roundToInt
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

    /**
     * Which pane a narrow window shows: 0 = photo stage, 1 = border library,
     * 2 = parameters. Hoisted here (not local to the column layout) so the top
     * bar's 「参数」 entry can bring the inspector up from anywhere, and so the
     * choice survives a resize that folds the three columns into one pane.
     */
    var narrowPane by mutableIntStateOf(0)
}

private enum class StageMode { Edit, Batch }

/** Narrow-window pane indices, `DesktopLayoutState.narrowPane`. */
private const val STAGE_PANE = 0
private const val LIBRARY_PANE = 1
private const val PARAMS_PANE = 2

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

    // V2 左栏：筛选状态、最近使用、悬停预览与逐个试的撤销合并。
    val desktopFilters = remember { DesktopLibraryFilters() }
    val recentStore = remember { RecentFramesEnv.store ?: InMemoryRecentFramesStore() }
    var recentIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var hoverCard by remember { mutableStateOf<LibraryCard?>(null) }
    var trialState by remember { mutableStateOf(DesktopTrial.UndoStep.Empty) }
    var trialNotice by remember { mutableStateOf<String?>(null) }
    var holdOriginal by remember { mutableStateOf(false) }
    var librarySearchFocused by remember { mutableStateOf(false) }
    var sampleImportAttempted by remember { mutableStateOf(false) }

    // §4.6：FM_SAMPLE_PHOTO=1 且项目里还没有照片时，导入平台自带的示例照片。
    // 项目文件会持久化，所以下一次启动 photos 非空，不会重复导入。
    LaunchedEffect(projects.loaded) {
        if (!projects.loaded) return@LaunchedEffect
        val platform = WorkflowEnv.platform ?: return@LaunchedEffect
        val path = platform.samplePhotoPath()
        if (DesktopTrial.shouldImportSamplePhoto(
                flagEnabled = LaunchOptions.samplePhoto,
                hasPhotos = projects.photos.isNotEmpty(),
                alreadyAttempted = sampleImportAttempted,
                samplePath = path,
            )
        ) {
            sampleImportAttempted = true
            val report = projects.importPhotos(listOf(path!!))
            println("FM_SAMPLE_PHOTO imported=${report.added} failed=${report.failures.size} path=$path")
        }
    }
    LaunchedEffect(trialNotice) {
        if (trialNotice == null) return@LaunchedEffect
        kotlinx.coroutines.delay(4000)
        trialNotice = null
    }

    // Renderer-backed text rows belong to one card/photo pair. Replacing the
    // registry with the selection keeps an old report out of the next frame
    // synchronously; an in-flight render can only finish into its old instance.
    val textSlots = remember(selectedCard?.id, projects.activePhotoId) { TextSlotRegistry() }
    var removeTarget by remember { mutableStateOf<PhotoRef?>(null) }
    var importNotice by remember { mutableStateOf<String?>(null) }
    var libraryState by remember { mutableStateOf<LibraryLoadState>(LibraryLoadState.Loading) }
    var libraryReload by remember { mutableIntStateOf(0) }
    var inspectorState by remember { mutableStateOf<InspectorLoadState>(InspectorLoadState.Idle) }
    var inspectorReload by remember { mutableIntStateOf(0) }
    var autoOpened by remember { mutableStateOf(false) }
    var stageMode by remember { mutableStateOf(StageMode.Edit) }
    var paramsPage by remember { mutableStateOf(InspectorPage.Style) }
    var presetSheetOpen by remember { mutableStateOf(false) }
    var exportSheetOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    var applyDesignOpen by remember { mutableStateOf(false) }

    val libraryIndex = remember(libraryState) {
        (libraryState as? LibraryLoadState.Ready)?.snapshot?.let { LibraryIndex.of(it) }
    }
    val inUseCardIds = projects.project.designs.values.mapNotNull { it.cardId }.distinct()
    val trialView = desktopFilters.view(inUseCardIds, recentIds)
    val trialTraversal = remember(libraryIndex, trialView) {
        libraryIndex?.traversal(trialView) ?: LibraryTraversal(emptyList())
    }

    LaunchedEffect(libraryIndex) {
        val index = libraryIndex ?: return@LaunchedEffect
        recentIds = RecentFrames.load(recentStore, index)
    }

    // §3.1/§3.3: stable key of the text row whose input has focus. The 文字 tab
    // reports it, the stage draws its frame. Every path that swaps what is on
    // screen — photo, card, inspector tab, folded inspector — clears it, so a
    // highlight can never outlive the frame it described.
    var focusedTextSlotKey by remember { mutableStateOf<String?>(null) }

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

    // The registry itself is replaced on a pair switch. Clear the focus marker
    // too so a highlight from the previous frame cannot survive that switch.
    LaunchedEffect(selectedCard?.id, projects.activePhotoId) {
        focusedTextSlotKey = null
    }

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
    // The 文字 tab lists the rows the renderer actually drew, so the panel model
    // carries their per-row controls. `model` itself stays the declared model the
    // export/design paths read; only the inspector gets the derived copy.
    val panelModel = remember(model, textSlots.slots) { model?.withNativeTextSlots(textSlots.slots) }

    // WP-C's four-page model: which pages exist, and each page's changed-item count.
    // Not memoised: `isModified` reads snapshot state that must stay observed.
    val watermarkSpec = desktopWorkflowWatermark(projects.watermark())
    val tabModel = inspectorTabsFor(
        model = panelModel,
        session = session,
        hasTextSlots = textSlots.slots.isNotEmpty(),
        watermarkModified = WatermarkZones.modifiedCount(watermarkSpec),
    )

    // P2a: project is the source of truth; the DesignSession is a hydrated view
    // that writes every change back through the controller.
    //
    // The panel model is the write path: only it carries the renderer-backed text
    // rows, so a `slotText:`/`slotOff:`/`slotScale:` edit is owned (and therefore
    // persisted and re-applied) instead of being dropped as an unknown consumer.
    val currentPanelModel by rememberUpdatedState(panelModel)
    LaunchedEffect(Unit) {
        session.onValuesChanged = { _, overrides ->
            currentPanelModel?.let { projects.applyControlOverrides(it, overrides) }
        }
        session.onExportChanged = { projects.setExportSettings(it) }
    }
    // Re-hydrate when the *control set* changes (rows arriving or disappearing),
    // not on every render: typing must not be interrupted, and re-reading the
    // design here is what keeps a stored slot override from being swept away by
    // the next unrelated edit.
    LaunchedEffect(
        panelModel?.cardId,
        panelModel?.controls?.size,
        projects.activePhotoId,
        projects.hydrationVersion,
    ) {
        val built = currentPanelModel ?: return@LaunchedEffect
        session.seed(built.controls)
        session.replaceValues(projects.designValues(built))
        session.setExportSilently(projects.exportSettingsForUi())
    }
    LaunchedEffect(libraryState) {
        val requested = desktopPageForLaunchTab(LaunchOptions.openTab) ?: return@LaunchedEffect
        if (libraryState !is LibraryLoadState.Ready) return@LaunchedEffect
        kotlinx.coroutines.delay(1200)
        paramsPage = requested
        println("FM_OPEN_TAB page=${requested.name} raw=${LaunchOptions.openTab}")
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

    // Right rail: if the selected page disappears with the card (e.g. an IR card has
    // no Logo page), fall back to the first page the model still offers.
    LaunchedEffect(tabModel.tabs.map { it.page }) {
        if (tabModel.tabs.isNotEmpty() && tabModel.tab(paramsPage) == null) {
            paramsPage = tabModel.tabs.first().page
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
            projects.applyCard(card.id, card.systemStyle)
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
        // The content box the top bar and the columns share. Both compare it
        // against the same derived three-column floor (desktopColumnMinWidth),
        // so the bar can never call a window narrow while the columns still show
        // both rails — the old 688-vs-664 pair did exactly that.
        val contentWidth = (maxWidth - FmDesktop.edge * 2).coerceAtLeast(0.dp)
        val columns = computeDesktopColumns(
            availableWidthDp = contentWidth.value,
            requestedLeft = layout.leftWidth.value,
            requestedRight = layout.rightWidth.value,
            leftCollapsed = layout.leftCollapsed,
            rightCollapsed = layout.rightCollapsed,
        )
        val rightVisible = columns.showRight

        fun collapseLeft() {
            layout.leftCollapsed = true
        }

        fun expandLeft() {
            layout.leftCollapsed = false
        }

        fun collapseRight() {
            layout.rightCollapsed = true
        }

        fun expandRight() {
            layout.rightCollapsed = false
        }

        fun toggleLeft() = if (layout.leftCollapsed) expandLeft() else collapseLeft()
        fun toggleRight() = if (layout.rightCollapsed) expandRight() else collapseRight()

        /** 顶栏「参数」: narrow windows switch pane, wide ones un-fold the inspector. */
        fun openParams() {
            layout.narrowPane = PARAMS_PANE
            layout.rightCollapsed = false
        }

        LaunchedEffect(columns.left, columns.right, columns.stage, columns.compact, columns.showRight, contentWidth) {
            val line = "FM_DESKTOP_COLUMNS content=%.0f left=%.0f right=%.0f stage=%.0f " +
                "compact=%s showL=%s showR=%s"
            println(
                line.format(
                    contentWidth.value,
                    columns.left,
                    columns.right,
                    columns.stage,
                    columns.compact,
                    columns.showLeft,
                    columns.showRight,
                ),
            )
        }

        // Focus belongs to the 文字 tab: switching tab, folding the inspector or
        // collapsing into the single-pane layout drops it, so the stage never
        // frames a row whose editor is no longer on screen.
        LaunchedEffect(paramsPage, rightVisible) {
            if (paramsPage != InspectorPage.Text || !rightVisible) focusedTextSlotKey = null
        }

        fun applyCardSelection(card: LibraryCard, recordTrial: Boolean) {
            if (recordTrial) {
                val previous = selectedCard?.id
                val now = WorkflowEnv.platform?.nowMillis() ?: 0L
                trialState = DesktopTrial.recordSwitch(trialState, previous, now)
                val position = trialTraversal.positionOf(card.id)
                trialNotice = if (position > 0) {
                    "${card.name} · 第 $position / ${trialTraversal.size}"
                } else {
                    card.name
                }
            }
            selectedCard = card
            projects.applyCard(card.id, card.systemStyle)
            stageMode = StageMode.Edit
            paramsPage = InspectorPage.Style
            focusedTextSlotKey = null
            val index = libraryIndex
            if (recordTrial && index != null) {
                recentIds = RecentFrames.record(recentStore, card.id, index)
            }
        }

        val openCard: (LibraryCard) -> Unit = { card ->
            FeelProbe.begin("card", card.id)
            applyCardSelection(card, recordTrial = true)
        }

        /** ← → 逐个试：按 LibraryIndex 的显示顺序前后各一张。 */
        fun stepCard(forward: Boolean) {
            val index = libraryIndex ?: return
            val nextId = if (forward) {
                trialTraversal.next(selectedCard?.id)
            } else {
                trialTraversal.prev(selectedCard?.id)
            } ?: return
            val card = index.card(nextId) ?: return
            applyCardSelection(card, recordTrial = true)
            println("FM_TRIAL step=${if (forward) "next" else "prev"} card=${card.id}")
        }

        /**
         * 左栏内容：边框数据就绪后走 V2 面板（LibraryIndex + 两页签 + 固定三行筛选），
         * 加载 / 失败时沿用现有 LibraryScreen 的加载与重试界面。
         */
        val libraryContent: @Composable () -> Unit = {
            val index = libraryIndex
            if (index == null) {
                LibraryScreen(
                    loadState = libraryState,
                    filters = filters,
                    gridState = libraryGrid,
                    onRetry = { libraryReload++ },
                    onOpenCard = { card -> openCard(card); layout.narrowPane = STAGE_PANE },
                    rail = true,
                    selectedCardId = selectedCard?.id,
                )
            } else {
                DesktopLibraryPanel(
                    index = index,
                    filters = desktopFilters,
                    inUseIds = inUseCardIds,
                    recentIds = recentIds,
                    selectedCardId = selectedCard?.id,
                    presets = projects.presets,
                    onOpenCard = { card -> openCard(card); layout.narrowPane = STAGE_PANE },
                    onHoverCard = { hoverCard = it },
                    onApplyPreset = { id ->
                        if (projects.applyPreset(id)) {
                            val design = projects.activeDesign
                            val card = design?.cardId?.let { index.card(it) }
                            if (card != null) selectedCard = card
                            importNotice = "已应用预设"
                        } else {
                            importNotice = projects.presetError ?: "应用预设失败"
                        }
                    },
                    onOpenPresetSheet = { presetSheetOpen = true },
                    onCollapse = ::collapseLeft,
                    onSearchFocusChanged = { librarySearchFocused = it },
                )
            }
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
                    if (projects.projectWritesBlocked) return@onPreviewKeyEvent true
                    if (event.key == Key.Backslash) {
                        // 按住 `\` 看原图（报告 §6）：按下显示原图，松开恢复。
                        holdOriginal = event.type == KeyEventType.KeyDown
                        return@onPreviewKeyEvent true
                    }
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

                    // ← → 逐个试（报告 §6）：顺序来自 LibraryIndex.traversal。
                    // 搜索框有焦点时把方向键让给文字编辑。
                    if (!librarySearchFocused && !event.isCtrlPressed && !event.isShiftPressed &&
                        stageMode == StageMode.Edit
                    ) {
                        when (event.key) {
                            Key.DirectionLeft -> {
                                stepCard(forward = false)
                                return@onPreviewKeyEvent true
                            }
                            Key.DirectionRight -> {
                                stepCard(forward = true)
                                return@onPreviewKeyEvent true
                            }
                            Key.DirectionUp -> {
                                stepCard(forward = false)
                                return@onPreviewKeyEvent true
                            }
                            Key.DirectionDown -> {
                                stepCard(forward = true)
                                return@onPreviewKeyEvent true
                            }
                            else -> Unit
                        }
                    }

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
                            // 连续切卡停下 0.7 秒算一步撤销；有挂起的切换时先回到那段
                            // 切换开始前的那张卡（A-08），否则退回参数撤销。
                            val anchorId = DesktopTrial.undoAnchor(trialState)
                            val anchor = anchorId?.let { libraryIndex?.card(it) }
                            if (anchor != null) {
                                val left = selectedCard
                                applyCardSelection(anchor, recordTrial = false)
                                trialState = DesktopTrial.afterUndo(trialState)
                                trialNotice = "已撤销：切换到 ${anchor.name}" +
                                    if (left != null && left.id != anchor.id) "（原：${left.name}）" else ""
                                println("FM_TRIAL undo card=${anchor.id}")
                                true
                            } else {
                                val handled = session.undo()
                                if (handled) println("FM_SHORTCUT undo")
                                handled
                            }
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
            // 设计上下文（保存预设 / 恢复当前设计）挂在项目卡的下拉里：演示稿的
            // `.proj` 就带一个 chevron，而顶栏与舞台上不该有演示稿没有的 `⋯`。
            var savePresetOpen by remember { mutableStateOf(false) }
            var savePresetName by remember { mutableStateOf("") }
            var resetConfirmOpen by remember { mutableStateOf(false) }
            fun openSavePreset() {
                savePresetName = selectedCard?.name?.let { "$it 预设" }
                    ?: projects.activePhoto?.displayName?.substringBeforeLast('.')
                    ?: "我的预设"
                savePresetOpen = true
            }
            val saveDesignPreset: (String) -> Unit = { name ->
                val saved = projects.savePreset(name)
                importNotice = if (saved != null) {
                    "已保存预设「${saved.name}」"
                } else {
                    projects.presetError ?: "保存预设失败"
                }
                println("FM_DESIGN_PRESET_SAVE name=$name ok=${saved != null}")
            }
            val resetCurrentDesign: () -> Unit = {
                val current = selectedCard
                if (current != null) {
                    session.resetCard(current.id)
                    println("FM_DESIGN_RESET ${current.id}")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = FmDesktop.edge, vertical = FmDesktop.edge),
            ) {
                DesktopTopBarV2(
                    // 演示稿 `.proj` 第一行是项目名；`Project` 没有名称字段，模型不许改，
                    // 所以这里如实显示「未命名项目」，等模型长出 name 再换真名。
                    projectName = "未命名项目",
                    saveState = when {
                        projects.saveError != null -> DesktopSaveState.Failed
                        !projects.loaded || projects.projectWritesBlocked -> DesktopSaveState.Pending
                        else -> DesktopSaveState.Saved
                    },
                    batchMode = stageMode == StageMode.Batch,
                    canUndo = session.history.canUndo,
                    canRedo = session.history.canRedo,
                    onSelectEdit = { stageMode = StageMode.Edit },
                    onSelectBatch = { stageMode = StageMode.Batch },
                    onUndo = { session.undo() },
                    onRedo = { session.redo() },
                    onAddPhoto = addPhoto,
                    onOpenExport = { requestExport() },
                    onSavePreset = ::openSavePreset,
                    onResetDesign = { resetConfirmOpen = true },
                    onOpenHelp = { helpOpen = true },
                    onToggleLeft = ::toggleLeft,
                    // 宽窗口是"折叠/展开右栏"，窄窗口（compact）下退化成"切到参数面板"，
                    // 这样去掉顶栏那个演示稿没有的「参数」按钮之后，窄窗口仍然进得去。
                    onToggleRight = {
                        if (layout.rightCollapsed || columns.compact) openParams() else collapseRight()
                    },
                )
                // V2: the 52 dp bar is separated from the workspace by one hairline.
                Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
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

                DesktopColumnLayout(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    columns = columns,
                    availableWidthDp = contentWidth.value,
                    layout = layout,
                    libraryContent = libraryContent,
                    onCollapseLeft = ::collapseLeft,
                    onExpandLeft = ::expandLeft,
                    onCollapseRight = ::collapseRight,
                    onExpandRight = ::expandRight,
                    selectedCard = selectedCard,
                    model = model,
                    panelModel = panelModel,
                    textSlots = textSlots,
                    session = session,
                    inspectorState = inspectorState,
                    onRetryInspector = { inspectorReload++ },
                    tabModel = tabModel,
                    paramsPage = paramsPage,
                    onSelectPage = { paramsPage = it },
                    onTextSlotFocus = { key ->
                        focusedTextSlotKey = key
                        println("FM_TEXT_FOCUS key=${key ?: "-"} page=${paramsPage.name}")
                    },
                    highlightedTextSlotKey = focusedTextSlotKey,
                    stageMode = stageMode,
                    projects = projects,
                    onAddPhoto = addPhoto,
                    onRemovePhoto = { id -> removeTarget = projects.photos.firstOrNull { it.id == id } },
                    hoveredCard = hoverCard,
                    holdOriginal = holdOriginal,
                    onReuseDesign = { photoId ->
                        val changed = projects.applyDesignFromPhoto(
                            sourcePhotoId = photoId,
                            targetPhotoIds = listOfNotNull(projects.activePhotoId),
                        )
                        importNotice = when {
                            changed < 0 -> "当前平台不支持跨照片套用设计"
                            changed == 0 -> "没有可套用的变化"
                            else -> "已沿用其他照片的设计"
                        }
                        projects.activeDesign?.cardId?.let { cardId ->
                            libraryIndex?.card(cardId)?.let { selectedCard = it }
                        }
                    },
                    onPickBorder = { layout.leftCollapsed = false },
                    onApplyToOtherPhotos = { applyDesignOpen = true },
                    onSavePreset = saveDesignPreset,
                    onResetDesign = resetCurrentDesign,
                    onOpenBatch = { stageMode = StageMode.Batch },
                )
            }

            trialNotice?.let { notice ->
                // A-08：撤销合并的那一刻说明撤销了什么；4 秒后自动消失。
                Text(
                    text = notice,
                    style = FmType.labelMedium,
                    color = FmPalette.onSurface,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 96.dp)
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.surface)
                        .border(1.dp, FmPalette.outlineStrong, RoundedCornerShape(FmRadii.pill))
                        .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.sm),
                )
            }

            if (helpOpen) {
                HelpOverlay(onDismiss = { helpOpen = false })
            }
            if (savePresetOpen) {
                AlertDialog(
                    onDismissRequest = { savePresetOpen = false },
                    title = { Text("保存预设") },
                    text = {
                        Column {
                            Text(
                                "把当前边框的参数与文字保存为预设，之后可以一键套用到其他照片。",
                                style = FmType.bodyMedium,
                                color = FmPalette.onSurfaceMuted,
                            )
                            Spacer(Modifier.height(FmSpacing.md))
                            OutlinedTextField(
                                value = savePresetName,
                                onValueChange = { savePresetName = it },
                                singleLine = true,
                                label = { Text("预设名称") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = savePresetName.isNotBlank(),
                            onClick = {
                                val clean = savePresetName.trim()
                                savePresetOpen = false
                                if (clean.isNotEmpty()) saveDesignPreset(clean)
                            },
                        ) { Text("保存") }
                    },
                    dismissButton = {
                        TextButton(onClick = { savePresetOpen = false }) { Text("取消") }
                    },
                )
            }
            if (resetConfirmOpen) {
                AlertDialog(
                    onDismissRequest = { resetConfirmOpen = false },
                    title = { Text("恢复当前设计？") },
                    text = {
                        Text("只清空当前边框的参数修改，回到刚应用的设计；照片和水印保留，可以撤销。")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            resetConfirmOpen = false
                            resetCurrentDesign()
                        }) { Text("恢复") }
                    },
                    dismissButton = {
                        TextButton(onClick = { resetConfirmOpen = false }) { Text("取消") }
                    },
                )
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
                        exportCard?.let { NativeEngine.designOf(it, panelModel ?: model, session) }
                    },
                    photoPath = activePhoto?.path,
                    photoDisplayName = activePhoto?.displayName,
                    photoIndex = projects.photoIndex(projects.activePhotoId),
                    overlay = { projects.overlaySpec() },
                    onDismiss = { exportSheetOpen = false },
                )
            }

            if (applyDesignOpen) {
                DesktopApplyDesignDialog(
                    source = projects.activePhoto,
                    photos = projects.photos,
                    onDismiss = { applyDesignOpen = false },
                    onApply = { targets ->
                        val changed = projects.applyDesignFromPhoto(
                            sourcePhotoId = projects.activePhotoId.orEmpty(),
                            targetPhotoIds = targets,
                        )
                        importNotice = when {
                            changed < 0 -> "当前平台不支持跨照片套用设计"
                            changed == 0 -> "没有可套用的变化"
                            else -> "已应用到 $changed 张照片"
                        }
                        applyDesignOpen = false
                    },
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
            if (projects.projectWritesBlocked) {
                ProjectReadRecoveryBlock(
                    loading = !projects.loaded,
                    error = projects.loadError ?: projects.saveError,
                    onRetry = {
                        println("FM_PROJECT_RETRY_REQUESTED surface=desktop")
                        val recovered = projects.retryProjectLoad()
                        println("FM_PROJECT_RETRY_RESULT recovered=$recovered error=${projects.loadError ?: "-"}")
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

/**
 * V06 top bar: brand, the 工作台 / 预设 / 任务 first-level navigation, and the
 * actions that must stay reachable (undo, redo, import, export). Secondary
 * actions (help, rail visibility) live in the ⋯ overflow so the bar never turns
 * into a wall of buttons; below the derived three-column minimum it wraps into
 * two rows and the action row scrolls instead of dropping anything.
 */
@Composable
private fun DesktopTopBar(
    card: LibraryCard?,
    mode: StageMode,
    photoCount: Int,
    narrow: Boolean,
    inspectorVisible: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddPhoto: () -> Unit,
    onSelectEdit: () -> Unit,
    onSelectBatch: () -> Unit,
    presetSheetOpen: Boolean,
    onOpenPresets: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenHelp: () -> Unit,
    onShowParams: () -> Unit,
    onToggleLeft: () -> Unit,
    onToggleRight: () -> Unit,
) {
    if (narrow) {
        Column(Modifier.fillMaxWidth().height(108.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BrandBlock(compact = true)
                Spacer(Modifier.width(FmSpacing.sm))
                StatusPill(card = card, mode = mode, photoCount = photoCount, compact = true)
                Spacer(Modifier.weight(1f))
                TopBarOverflow(
                    inspectorVisible = inspectorVisible,
                    onOpenHelp = onOpenHelp,
                    onShowParams = onShowParams,
                    onToggleLeft = onToggleLeft,
                    onToggleRight = onToggleRight,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavTabs(
                    mode = mode,
                    presetSheetOpen = presetSheetOpen,
                    onSelectEdit = onSelectEdit,
                    onSelectBatch = onSelectBatch,
                    onOpenPresets = onOpenPresets,
                )
                Spacer(Modifier.width(FmSpacing.xs))
                BarAction("导出", onOpenExport, primary = true)
                if (!inspectorVisible) BarAction("参数", onShowParams)
                BarAction("导入照片", onAddPhoto)
                HistoryButton(label = "撤销", redo = false, enabled = canUndo, onClick = onUndo)
                HistoryButton(label = "重做", redo = true, enabled = canRedo, onClick = onRedo)
            }
        }
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val roomy = maxWidth >= 860.dp
        Row(
            modifier = Modifier.fillMaxWidth().height(FmDesktop.topBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandBlock(compact = !roomy)
            Spacer(Modifier.width(FmSpacing.md))
            NavTabs(
                mode = mode,
                presetSheetOpen = presetSheetOpen,
                onSelectEdit = onSelectEdit,
                onSelectBatch = onSelectBatch,
                onOpenPresets = onOpenPresets,
            )
            if (roomy) {
                Spacer(Modifier.weight(1f))
                StatusPill(card = card, mode = mode, photoCount = photoCount, compact = false)
                Spacer(Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarAction("导出", onOpenExport, primary = true)
                if (!inspectorVisible) BarAction("参数", onShowParams)
                BarAction("导入照片", onAddPhoto)
                HistoryButton(label = "撤销", redo = false, enabled = canUndo, onClick = onUndo)
                HistoryButton(label = "重做", redo = true, enabled = canRedo, onClick = onRedo)
                TopBarOverflow(
                    inspectorVisible = inspectorVisible,
                    onOpenHelp = onOpenHelp,
                    onShowParams = onShowParams,
                    onToggleLeft = onToggleLeft,
                    onToggleRight = onToggleRight,
                )
            }
        }
    }
}

/** 工作台 / 预设 / 任务. 预设 is the existing sheet, not a fourth mode. */
@Composable
private fun NavTabs(
    mode: StageMode,
    presetSheetOpen: Boolean,
    onSelectEdit: () -> Unit,
    onSelectBatch: () -> Unit,
    onOpenPresets: () -> Unit,
) {
    Row(
        modifier = Modifier
            .selectableGroup()
            .height(36.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.surfaceMuted)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.pill))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavTab(label = "工作台", selected = mode == StageMode.Edit, onClick = onSelectEdit)
        NavTab(label = "预设", selected = presetSheetOpen, onClick = onOpenPresets)
        NavTab(label = "任务", selected = mode == StageMode.Batch, onClick = onSelectBatch)
    }
}

@Composable
private fun NavTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(if (selected) FmPalette.surface else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = FmSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = FmType.labelMedium,
            color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
            maxLines = 1,
        )
    }
}

/** Photo count / current border capsule; the photo count survives the narrow bar. */
@Composable
private fun StatusPill(card: LibraryCard?, mode: StageMode, photoCount: Int, compact: Boolean) {
    Row(
        modifier = Modifier
            .height(if (compact) 28.dp else 38.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.pill))
            .padding(horizontal = if (compact) FmSpacing.sm else FmSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                compact && photoCount > 0 -> "$photoCount 张"
                compact -> "未选边框"
                mode == StageMode.Batch -> "批量队列 · 原生导出"
                card != null -> "当前边框 · ${card.name}"
                else -> "尚未选择边框"
            },
            style = FmType.labelMedium,
            color = if (card != null || photoCount > 0) FmPalette.onSurface else FmPalette.onSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!compact && photoCount > 0) {
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

/**
 * ⋯ overflow: everything secondary. The rail toggles live here because a folded
 * rail's own affordance can be small, and the 「参数」 entry repeats inside the
 * menu whenever the inspector is not on screen.
 */
@Composable
private fun TopBarOverflow(
    inspectorVisible: Boolean,
    onOpenHelp: () -> Unit,
    onShowParams: () -> Unit,
    onToggleLeft: () -> Unit,
    onToggleRight: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(FmRadii.input))
                .background(FmPalette.surface)
                .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input))
                .clickable(onClick = { open = true })
                .semantics {
                    contentDescription = "更多"
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("⋯", style = FmType.titleMedium, color = FmPalette.onSurfaceSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("帮助与快捷键（F1）", style = FmType.bodyMedium) },
                onClick = { open = false; onOpenHelp() },
            )
            if (!inspectorVisible) {
                DropdownMenuItem(
                    text = { Text("打开参数面板", style = FmType.bodyMedium) },
                    onClick = { open = false; onShowParams() },
                )
            }
            DropdownMenuItem(
                text = { Text("显示/折叠边框库（Ctrl+B）", style = FmType.bodyMedium) },
                onClick = { open = false; onToggleLeft() },
            )
            DropdownMenuItem(
                text = { Text("显示/折叠参数面板（Ctrl+Shift+B）", style = FmType.bodyMedium) },
                onClick = { open = false; onToggleRight() },
            )
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
            // WP-T: read the ink during composition; the Canvas draw scope is not composable.
            val markOuter = FmPalette.primary
            val markInner = FmPalette.primaryStrong
            Canvas(modifier = Modifier.size(22.dp)) {
                val stroke = Stroke(width = 1.8.dp.toPx(), join = StrokeJoin.Round)
                drawRoundRect(
                    color = markOuter,
                    topLeft = Offset(size.width * 0.08f, size.height * 0.08f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.84f, size.height * 0.84f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.20f),
                    style = stroke,
                )
                drawRoundRect(
                    color = markInner,
                    topLeft = Offset(size.width * 0.32f, size.height * 0.32f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.36f, size.height * 0.36f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.07f),
                    style = stroke,
                )
            }
        }
        Spacer(Modifier.width(FmSpacing.sm))
        Text("FrameMark", style = FmType.titleSmall, color = FmPalette.onSurface)
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
private fun DesktopColumnLayout(
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
    session: DesignSession,
    inspectorState: InspectorLoadState,
    onRetryInspector: () -> Unit,
    tabModel: InspectorTabModel,
    paramsPage: InspectorPage,
    onSelectPage: (InspectorPage) -> Unit,
    onTextSlotFocus: (String?) -> Unit,
    highlightedTextSlotKey: String?,
    stageMode: StageMode,
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
        val photoAspect = if (activePhoto != null && activePhoto.widthPx > 0 && activePhoto.heightPx > 0) {
            activePhoto.widthPx.toFloat() / activePhoto.heightPx
        } else {
            wm?.photoAspect ?: 1.5f
        }
        val pageTab = tabModel.tab(paramsPage)
        DesktopInspectorRail(
            cardName = selectedCard?.name ?: "未选择边框",
            categoryLine = selectedCard?.categoryName.orEmpty(),
            renderMode = when {
                selectedCard == null -> "等待选择"
                NativeEngine.supports(selectedCard.id) -> "实时渲染"
                else -> "引擎样张"
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
                    println("FM_INSPECTOR_RESET page=${paramsPage.name} changed=$changed")
                }
            },
            onSavePreset = { onSavePreset("${selectedCard?.name ?: "边框"} 预设") },
            onApplyToOtherPhotos = onApplyToOtherPhotos,
            onResetAll = onResetDesign,
            onCollapse = collapse,
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
                        )
                        paramsPage == InspectorPage.Watermark -> WatermarkPane(
                            spec = wm,
                            photoAspect = photoAspect,
                            onAnchor = { projects.updateWatermark(anchor = it) },
                            onWidth = { projects.updateWatermark(width = it) },
                            onOpacity = { projects.updateWatermark(opacity = it) },
                            onPosition = { dx, dy -> projects.updateWatermark(dx = dx, dy = dy) },
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
                    else -> if (stageMode == StageMode.Batch) {
                        ProjectBatchPanel(projects)
                    } else {
                        EditStage(
                            card = selectedCard, session = session, model = model, projects = projects,
                            textSlots = textSlots,
                            highlightedTextSlotKey = highlightedTextSlotKey,
                            onAddPhoto = onAddPhoto, onRemovePhoto = onRemovePhoto,
                            onOpenBatch = onOpenBatch,
                            hoveredCard = hoveredCard, holdOriginal = holdOriginal,
                            onReuseDesign = onReuseDesign, onPickBorder = onPickBorder,
                        )
                    }
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
                if (stageMode == StageMode.Batch) {
                    ProjectBatchPanel(projects)
                } else {
                    EditStage(
                        card = selectedCard,
                        session = session,
                        model = model,
                        projects = projects,
                        textSlots = textSlots,
                        highlightedTextSlotKey = highlightedTextSlotKey,
                        onAddPhoto = onAddPhoto,
                        onRemovePhoto = onRemovePhoto,
                        onOpenBatch = onOpenBatch,
                        hoveredCard = hoveredCard,
                        holdOriginal = holdOriginal,
                        onReuseDesign = onReuseDesign,
                        onPickBorder = onPickBorder,
                    )
                }
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

// ---------------------------------------------------------------------------
// Centre stage
// ---------------------------------------------------------------------------

@Composable
private fun EditStage(
    card: LibraryCard?,
    session: DesignSession,
    model: InspectorCardModel?,
    projects: ProjectHolder,
    /** Filled by the stage render; read by the 文字 tab. */
    textSlots: TextSlotRegistry,
    /** §3.3: row being edited in the 文字 tab; null draws no frame. */
    highlightedTextSlotKey: String?,
    onAddPhoto: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    /** 胶片条右侧「批量导出」链接（`styles.css` 430 行 `.sinfo` 里的 link）。 */
    onOpenBatch: () -> Unit,
    hoveredCard: LibraryCard? = null,
    holdOriginal: Boolean = false,
    onReuseDesign: (String) -> Unit = {},
    onPickBorder: () -> Unit = {},
) {
    val native = card != null && NativeEngine.supports(card.id)
    val activeDesign = projects.activeDesign
    // The sample preview (no imported photo) is rendered straight from the
    // session, so it has to see the renderer-backed text rows as well.
    val panelModel = remember(model, textSlots.slots) { model?.withNativeTextSlots(textSlots.slots) }
    val design = when {
        projects.activePhoto != null -> activeDesign?.takeIf { it.cardId == card?.id }?.toDesignSnapshot()
        activeDesign != null && activeDesign.cardId == card?.id -> activeDesign.toDesignSnapshot()
        else -> card?.let { NativeEngine.designOf(it, panelModel ?: model, session) }
    }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var stagePx by remember { mutableStateOf(IntSize.Zero) }
    // 渲染帧的像素尺寸。演示稿 meta 要「→ 导出 W × H」，而导出画布与渲染帧是同一个
    // 设计、宽高比一致，所以这个尺寸就是那三个数字的来源（见 exportSizeLabel）。
    var renderedPx by remember { mutableStateOf(IntSize.Zero) }
    var comparing by remember { mutableStateOf(false) }

    LaunchedEffect(projects.activePhotoId) {
        // A hold-compare cannot survive the photo it was comparing.
        comparing = false
    }

    fun fitToWindow() {
        zoom = 1f
        pan = Offset.Zero
        println("FM_WHEEL fit zoom=1.000")
    }

    fun clampPan(next: Offset, z: Float = zoom): Offset {
        if (z <= 1f) return Offset.Zero
        val maxX = stagePx.width * (z - 1f) / 2f
        val maxY = stagePx.height * (z - 1f) / 2f
        return Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
    }

    /** 工具条 `−` / `+`：与滚轮同一套倍率与 100 % 吸附。 */
    fun stepZoom(direction: Int) {
        val factor = if (direction > 0) 1.25f else 0.8f
        val raw = (zoom * factor).coerceIn(0.25f, 4f)
        val next = if (raw in 0.94f..1.06f) 1f else raw
        if (next == zoom) return
        if (next <= 1f) {
            zoom = next
            pan = Offset.Zero
        } else {
            zoom = next
            pan = clampPan(pan, next)
        }
        println("FM_WHEEL step=${if (direction > 0) "+" else "-"} zoom=${"%.3f".format(zoom)}")
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

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(desktopStageBackdrop())
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
            val previewed = hoveredCard
            // `.stage-area { position:absolute; inset:0; padding: 56px 64px 84px }`
            // —— 底部 84 已经容下悬浮工具条，不再单独扣一条预留高度。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = FmDesktop.stagePadSide,
                        end = FmDesktop.stagePadSide,
                        top = FmDesktop.stagePadTop,
                        bottom = FmDesktop.stagePadBottom,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (previewed != null) {
                    // 悬停 0.4 秒后的预览：直接显示这张卡的打包预览图，不切换选中项。
                    LibraryPreview(
                        card = previewed,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (projects.photos.isEmpty()) {
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
                    val activePhoto = projects.activePhoto
                    // The shared wrapper (workflow.ProjectPhotoStage) owns the
                    // native branch but exposes no `onTextSlots`, so the desktop
                    // stage mirrors that exact branch to receive the renderer's
                    // text rows. Everything else (watermark-only photo, static
                    // card, placeholder) still goes through the shared wrapper.
                    when {
                        // Hold-to-compare shows the imported photo itself, never a
                        // rendered frame and never a highlight.
                        (comparing || holdOriginal) && activePhoto != null -> PhotoThumbnail(
                            path = activePhoto.path,
                            modifier = Modifier.fillMaxSize(),
                            maxEdge = 1600,
                            cornerRadius = FmRadii.stage,
                            contentScale = ContentScale.Fit,
                        )
                        card != null && design != null && NativeEngine.supports(card.id) -> DesktopNativeStage(
                            card = card,
                            design = design,
                            projects = projects,
                            textSlots = textSlots,
                            highlightedTextSlotKey = highlightedTextSlotKey,
                            // 渲染帧与导出画布是同一个设计，宽高比一致；有了它 `.print`
                            // 的投影才能贴着成品，而不是围着舞台上一块空白。
                            printAspect = if (renderedPx.width > 0 && renderedPx.height > 0) {
                                renderedPx.width.toFloat() / renderedPx.height
                            } else {
                                null
                            },
                            onRenderedPx = { w, h -> renderedPx = IntSize(w, h) },
                        )
                        else -> ProjectPhotoStage(projects = projects, card = card, design = design)
                    }
                }
            }
            }

            // ---- 舞台悬浮层（PARITY-SPEC §2C）：meta / status / 对比提示 / 工具条 ----
            if (previewed == null) {
                val activePhoto = projects.activePhoto
                if (activePhoto != null && projects.activeDesign == null) {
                    val candidates = projects.photos
                        .filter { it.id != activePhoto.id }
                        .mapNotNull { p ->
                            projects.project.designs[p.id]?.let { OtherPhotoDesign(p.id, p.displayName) }
                        }
                    DesktopStageEmptyState(
                        candidates = candidates,
                        onReuse = onReuseDesign,
                        onPickBorder = onPickBorder,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            val metaPhoto = projects.activePhoto
            DesktopStageMeta(
                fileName = metaPhoto?.displayName ?: card?.name ?: "尚未添加照片",
                originalSize = metaPhoto
                    ?.takeIf { it.widthPx > 0 && it.heightPx > 0 }
                    ?.let { "${it.widthPx} × ${it.heightPx}" },
                exportSize = exportSizeLabel(
                    photo = metaPhoto,
                    rendered = if (previewed != null) IntSize.Zero else renderedPx,
                    longEdge = projects.exportSettingsForUi().effectiveLongEdge,
                ),
                modifier = Modifier.align(Alignment.TopStart),
            )
            DesktopStageStatusTag(
                text = when {
                    comparing || holdOriginal -> "原图"
                    previewed != null -> "悬停预览 · ${previewed.name}"
                    projects.activePhoto != null && activeDesign == null -> "未选边框"
                    native -> "实时预览"
                    card != null -> "引擎样张"
                    else -> "未选边框"
                },
                kind = when {
                    native && previewed == null && !comparing && !holdOriginal &&
                        !(projects.activePhoto != null && activeDesign == null) -> DesktopStageStatusKind.Live
                    else -> DesktopStageStatusKind.Static
                },
                modifier = Modifier.align(Alignment.TopEnd),
            )
            if (comparing || holdOriginal) {
                DesktopStageCompareFlag(modifier = Modifier.align(Alignment.TopCenter))
            }
            val appearance = LocalAppearance.current
            DesktopStageToolbar(
                zoomLabel = if (zoom <= 1f) "适合" else "${(zoom * 100).roundToInt()}%",
                zoomDescription = "当前 ${(zoom * 100).roundToInt()}%，点击适合窗口",
                compareActive = comparing || holdOriginal,
                compareEnabled = projects.activePhoto != null,
                tone = appearance.settings.stageTone,
                onZoomOut = { stepZoom(-1) },
                onFit = ::fitToWindow,
                onZoomIn = { stepZoom(1) },
                onCompareHold = { comparing = it },
                onSelectTone = { appearance.setStageTone(it) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (projects.photos.isNotEmpty()) {
            DesktopFilmstrip(
                projects = projects,
                onAddPhoto = onAddPhoto,
                onRemovePhoto = onRemovePhoto,
                onOpenBatch = onOpenBatch,
            )
        }
    }

    // 设计上下文（保存预设 / 恢复当前设计）已经上移到项目卡下拉，舞台本身不再有
    // 演示稿之外的 `⋯` 菜单。
}

/**
 * Desktop stage for a card the native engine renders.
 *
 * The composition (padding, centred fill, overlay re-anchor after the first
 * frame) is the native branch of the shared `workflow.ProjectPhotoStage`, plus
 * the two things that wrapper cannot pass through:
 * `NativeStagePreview(onTextSlots = …)`, which is how the 文字 tab learns the
 * geometry of the rows the engine actually drew, and
 * `NativeStagePreview(highlightedTextSlotKey = …)`, which frames the row being
 * edited. Keep the two in sync if the wrapper's native branch ever changes.
 */
@Composable
private fun DesktopNativeStage(
    card: LibraryCard,
    design: DesignSnapshot,
    projects: ProjectHolder,
    textSlots: TextSlotRegistry,
    highlightedTextSlotKey: String?,
    /** 渲染帧宽高比：`.print` 的投影要贴着成品，不能围着整块舞台。 */
    printAspect: Float?,
    onRenderedPx: (Int, Int) -> Unit,
) {
    // The watermark anchor needs the render's photo rect, which only exists after
    // the first frame; bumping the version re-keys the overlay so the mark lands
    // on the rendered image instead of the placeholder.
    var geometryVersion by remember { mutableIntStateOf(0) }
    val overlay = remember(projects.activePhotoId, projects.activeDesign?.overlay, geometryVersion) {
        projects.overlaySpec()
    }
    val shadowColor = FmPalette.scrim
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (printAspect != null && printAspect > 0f) {
                        Modifier.aspectRatio(printAspect)
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
                // `.print` 投影：`0 1px 2px, 0 18px 40px -12px, 0 40px 80px -30px`
                // —— Compose 只有一层 shadow，取最大那层，颜色用遮罩令牌。
                .shadow(
                    elevation = 28.dp,
                    shape = RectangleShape,
                    clip = false,
                    ambientColor = shadowColor,
                    spotColor = shadowColor,
                ),
        ) {
            NativeStagePreview(
                card = card,
                design = design,
                modifier = Modifier.fillMaxSize(),
                overlay = overlay,
                photoPath = projects.activePhoto?.path,
                onRendered = { w, h, _ ->
                    geometryVersion++
                    onRenderedPx(w, h)
                },
                onTextSlots = textSlots::update,
                highlightedTextSlotKey = highlightedTextSlotKey,
                // WP-C §4.5: the watermark can be dragged directly on the photo; the
                // dashed frame shows the cell it may move in, and the write-back is the
                // same absolute dx/dy the 水印 page sliders use.
                watermark = desktopWorkflowWatermark(projects.watermark()),
                onWatermarkNudge = { dx, dy -> projects.updateWatermark(dx = dx, dy = dy) },
            )
        }
    }
}

/**
 * 「→ 导出 W × H」。
 *
 * 导出画布与舞台上的渲染帧是同一个设计（同一套边距与文字带），宽高比一致；所以
 * 导出高度 = 照片原宽 × 帧比例，与演示稿 `outSize()` 同源。指定长边时按长边归一。
 * 取不到渲染帧（首帧之前）或照片没有尺寸时返回 null —— 宁可不写，也不写一个猜的
 * 数字。
 */
private fun exportSizeLabel(photo: PhotoRef?, rendered: IntSize, longEdge: Int?): String? {
    if (photo == null || photo.widthPx <= 0) return null
    if (rendered.width <= 0 || rendered.height <= 0) return null
    val frameAspect = rendered.height.toFloat() / rendered.width // 高 / 宽
    var width = photo.widthPx
    var height = Math.round(width * frameAspect)
    if (longEdge != null && longEdge > 0) {
        val longest = maxOf(width, height)
        if (longest > 0 && longest != longEdge) {
            val scale = longEdge.toFloat() / longest
            width = Math.round(width * scale)
            height = Math.round(height * scale)
        }
    }
    return "$width × $height"
}

/** The visible ~30 dp stage pill; callers wrap it in a ≥40 dp hit box. */
@Composable
private fun StagePill(label: String, active: Boolean, enabled: Boolean) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(if (active) FmPalette.primary else FmPalette.stageLine)
            .padding(horizontal = FmSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = FmType.labelSmall,
            color = if (active) FmPalette.onPrimary else if (enabled) FmPalette.stageInk else FmPalette.stageMuted,
            maxLines = 1,
        )
    }
}

/** Tap tool on the stage: 30 dp visible, 40 dp target (V06 §5). */
@Composable
private fun StageClickTool(
    label: String,
    description: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        StagePill(label = label, active = active, enabled = enabled)
    }
}

/** Hold-to-compare tool: the original photo is visible only while pressed. */
@Composable
private fun StageHoldTool(
    label: String,
    description: String,
    active: Boolean,
    enabled: Boolean,
    onHoldChange: (Boolean) -> Unit,
) {
    val currentOnHoldChange by rememberUpdatedState(onHoldChange)
    Box(
        modifier = Modifier
            .height(40.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                currentOnHoldChange(true)
                                tryAwaitRelease()
                                currentOnHoldChange(false)
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        StagePill(label = label, active = active, enabled = enabled)
    }
}

/**
 * 设计上下文 menu on the stage title row: 保存预设 / 恢复当前设计. Both act on the
 * design, so they sit with the design instead of a tool strip under the stage;
 * the reset is confirmed and the watermark is deliberately left untouched.
 */
@Composable
private fun DesignMenuButton(
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    canSavePreset: Boolean,
    onSavePreset: () -> Unit,
    onResetDesign: () -> Unit,
) {
    Box {
        Box(
            modifier = Modifier
                .height(40.dp)
                .widthIn(min = 44.dp)
                .clip(RoundedCornerShape(FmRadii.chip))
                .clickable(enabled = enabled, role = Role.Button, onClick = { onExpandedChange(true) })
                .semantics { contentDescription = "设计菜单：保存预设、恢复当前设计" },
            contentAlignment = Alignment.Center,
        ) {
            StagePill(label = "⋯", active = expanded, enabled = enabled)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text("保存预设…", style = FmType.bodyMedium) },
                enabled = canSavePreset,
                onClick = {
                    onExpandedChange(false)
                    onSavePreset()
                },
            )
            DropdownMenuItem(
                text = { Text("恢复当前设计…", style = FmType.bodyMedium) },
                enabled = enabled,
                onClick = {
                    onExpandedChange(false)
                    onResetDesign()
                },
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
            if (photoCount < 8) {
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
            HelpRow("Ctrl + Z", "撤销（连续切边框只算一步）")
            HelpRow("Ctrl + Shift + Z / Ctrl + Y", "重做")
            HelpSection("边框库")
            HelpRow("← / →", "按库里的顺序逐个试上一个 / 下一个")
            HelpRow("鼠标悬停 0.4 秒", "在舞台预览这张边框")
            HelpRow("Ctrl + B", "折叠或展开左侧边框库（52 px 窄条）")
            HelpRow("Ctrl + Shift + B", "折叠或展开右侧参数面板")
            HelpSection("舞台")
            HelpRow("按住 \\", "查看原图，松开恢复")
            HelpRow("Ctrl + 滚轮", "缩放（25%–400%）")
            HelpRow("双击", "适合窗口")
            HelpSection("工作流")
            HelpRow("Ctrl + E", "打开导出")
            HelpRow("Ctrl + Shift + P", "打开预设")
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

/** WP-D1 §4.9: placeholder washes are tokens, not six hard-coded pastel pairs. */
@Composable
private fun photoBrush(index: Int): Brush {
    val muted = FmPalette.surfaceMuted
    val track = FmPalette.track
    return Brush.linearGradient(listOf(muted, track))
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
