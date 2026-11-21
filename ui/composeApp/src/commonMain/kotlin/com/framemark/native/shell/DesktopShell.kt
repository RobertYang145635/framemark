package com.framemark.native.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.framemark.native.FeelProbe
import com.framemark.native.LaunchOptions
import com.framemark.native.inspector.InspectorPage
import com.framemark.native.inspector.WatermarkZones
import com.framemark.native.inspector.hasTextPage
import com.framemark.native.inspector.inspectorTabsFor
import com.framemark.native.inspector.InspectorLoadState
import com.framemark.native.inspector.RegistryPorts
import com.framemark.native.workflow.PresetStore
import com.framemark.native.inspector.applyDemoEdits
import com.framemark.native.library.InMemoryRecentFramesStore
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryDataSource
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.LibraryLoadState
import com.framemark.native.library.LibraryScreen
import com.framemark.native.library.LibraryTraversal
import com.framemark.native.library.RecentFrames
import com.framemark.native.library.RecentFramesEnv
import com.framemark.native.shell.desktop.DesktopLibraryFilters
import com.framemark.native.shell.desktop.DesktopLibraryPanel
import com.framemark.native.shell.desktop.DesktopSaveState
import com.framemark.native.shell.desktop.DesktopTopBarV2
import com.framemark.native.shell.desktop.DesktopTrial
import com.framemark.native.shell.desktop.desktopPageForLaunchTab
import com.framemark.native.shell.desktop.desktopWorkflowWatermark
import com.framemark.native.shell.desktop.BatchWorkspace
import com.framemark.native.shell.desktop.DesktopAboutOverlay
import com.framemark.native.shell.desktop.DesktopApplyDesignDialog
import com.framemark.native.shell.desktop.DesktopColumnLayout
import com.framemark.native.shell.desktop.DesktopExportSheet
import com.framemark.native.shell.desktop.DesktopHelpOverlay
import com.framemark.native.shell.desktop.DesktopRemovePhotoDialog
import com.framemark.native.shell.desktop.DesktopResetDesignDialog
import com.framemark.native.shell.desktop.DesktopSavePresetDialog
import com.framemark.native.shell.desktop.DesktopExitBlockedDialog
import com.framemark.native.theme.FmDesktop
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.product.BatchJobStatus
import com.framemark.native.product.PhotoRef
import com.framemark.native.workflow.AppExitGate
import com.framemark.native.workflow.DesignSession
import com.framemark.native.product.ExportFormat
import com.framemark.native.workflow.InMemoryProjectHolder
import com.framemark.native.workflow.OnboardingState
import com.framemark.native.workflow.ProjectHolder
import com.framemark.native.workflow.ProjectReadRecoveryBlock
import com.framemark.native.workflow.ProjectStatusBanner
import com.framemark.native.workflow.ProjectPresetSheet
import com.framemark.native.workflow.LocalTextEditFocus
import com.framemark.native.workflow.TextEditFocus
import com.framemark.native.workflow.TextSlotRegistry
import com.framemark.native.workflow.WorkflowEnv
import com.framemark.native.workflow.WorkflowOnboardingStore
import com.framemark.native.workflow.withNativeTextSlots
import kotlinx.coroutines.launch
import com.framemark.native.product.FmLog
import com.framemark.native.product.NoticeText

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

/**
 * Narrow-window pane indices, `DesktopLayoutState.narrowPane`.
 *
 * `internal` because the compact branch that reads them moved to
 * `shell/desktop/DesktopColumnLayout.kt`; the values and their meaning are unchanged.
 */
internal const val STAGE_PANE = 0
internal const val LIBRARY_PANE = 1
internal const val PARAMS_PANE = 2

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
    val scope = rememberCoroutineScope()
    val session = remember {
        DesignSession().apply {
            LaunchOptions.exportFormat?.let { raw ->
                val fmt = ExportFormat.entries.firstOrNull {
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
    // W-01: every editable field joins this registry while focused, so the root
    // key handler can yield the keys that belong to text editing.
    val textEditFocus = remember { TextEditFocus() }
    // A 按住看原图 hold that started before the user clicked into a field must not
    // stay latched: once editing begins, the Backslash KeyUp is yielded to the field.
    LaunchedEffect(textEditFocus.isEditing) {
        if (textEditFocus.isEditing) holdOriginal = false
    }
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
            FmLog.info("FM_SAMPLE_PHOTO") { "imported=${report.added} failed=${report.failures.size} path=$path" }
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
    var aboutOpen by remember { mutableStateOf(false) }
    var applyDesignOpen by remember { mutableStateOf(false) }
    // 新手教程：一层整窗覆盖，不是第四个 StageMode，也不动顶栏的 编辑 / 批量导出。
    // 首次启动在**首帧之后**才判定（`withFrameNanos`），免得工作区先闪一下再被盖住。
    val onboardingStore = remember { WorkflowOnboardingStore() }
    var onboardingOpen by remember { mutableStateOf(false) }
    var onboardingPage by remember { mutableIntStateOf(0) }
    // 缺拍摄参数的行：舞台给一圈虚线占位。**按照片**记「看过了」：进过一次「文字」页
    // 就撤掉；换照片或换边框都重置，所以同一张照片换到别的边框再换回来，占位照样出现。
    var exifHintsAcknowledged by remember(projects.activePhotoId, selectedCard?.id) {
        mutableStateOf(false)
    }
    // 启动自动检查更新（与手机端同一条逻辑）：有新版本弹一次，跳过的版本不再打扰。
    var pendingUpdate by remember { mutableStateOf<com.framemark.native.product.update.UpdateManifest?>(null) }
    LaunchedEffect(Unit) {
        val current = com.framemark.native.appVersionLabel()
        pendingUpdate = com.framemark.native.product.update.UpdateLaunch.check(
            currentVersion = current,
            storage = com.framemark.native.shell.PlatformUpdateSkipStorage(),
        )
        FmLog.info("UPDATE_ON_LAUNCH") { "current=$current remote=${pendingUpdate?.versionName ?: "<none>"}" }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        onboardingOpen = shouldAutoShowOnboarding(onboardingStore)
        if (onboardingOpen) FmLog.info(ONBOARDING_TAG) { "auto open page=1 / ${OnboardingText.PAGES.size}" }
    }

    /** 跳过与完成走同一条路：写 done、关掉覆盖层。 */
    fun closeOnboarding(skipped: Boolean) {
        OnboardingState.markDone(onboardingStore)
        onboardingOpen = false
        onboardingPage = 0
        FmLog.info(ONBOARDING_TAG) { "closed skipped=$skipped path=${onboardingStore.settingsPath}" }
    }

    /** 从齿轮弹层手动打开：永远从第一页开始，且不改 `onboarding.done`。 */
    fun openOnboarding() {
        onboardingPage = 0
        onboardingOpen = true
        FmLog.info(ONBOARDING_TAG) { "open from=gear" }
    }

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
        // RUNBOOK §5.5：QA 启动参数预填边框库搜索。env 的读取点在
        // desktopMain/qaenv/EnvQaSurface.kt（qaenv 层，产品源码不出现 hook 名，
        // 见 ARCH1 的 evidence-hook-literal 规则）。V2 左栏的搜索框绑的是
        // desktopFilters.query（shell\desktop\DesktopLibraryPanel.kt:375），旧
        // LibraryScreen 的筛选状态已随不可达的 Ready 分支删除（WP-FIX2）。
        LaunchOptions.libraryQuery?.let { desktopFilters.query = it }
        // The window may not own focus yet on the first frame; retry briefly so
        // the Ctrl/Cmd shortcuts work without clicking first.
        repeat(6) {
            runCatching { focusRequester.requestFocus() }
            kotlinx.coroutines.delay(250)
        }
    }

    // 换边框 = 一条可撤销的历史（P0-2），手机与桌面同一条规则：不清栈，记一步。
    LaunchedEffect(selectedCard?.id) { session.onCardChanged(selectedCard?.id) }

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
        FmLog.info("FM_LIB_START") { "elapsedMs=${elapsed()}" }
        val loadMark = kotlin.time.TimeSource.Monotonic.markNow()
        libraryState = LibraryLoadState.Loading
        val result = LibraryDataSource.load()
        libraryState = if (result.error == null) {
            val snapshot = result.snapshot
            FmLog.info("FM_LIB_READY") {
                "cards=${snapshot.cards.size} categories=${snapshot.categories.size} " +
                    "previews=${snapshot.mappedPreviews} unmapped=${snapshot.unmappedPreviewIds.size} " +
                    "generatedAt=${snapshot.generatedAt} elapsedMs=${elapsed()} " +
                    "loadMs=${loadMark.elapsedNow().inWholeMilliseconds}"
            }
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
        hasTextSlots = hasTextPage(textSlots.slots.size, textSlots.width),
        watermarkModified = WatermarkZones.modifiedCount(watermarkSpec),
    )

    // P2a: project is the source of truth; the DesignSession is a hydrated view
    // that writes every change back through the controller.
    //
    // The panel model is the write path: only it carries the renderer-backed text
    // rows, so a `slotText:`/`slotOff:`/`slotScale:` edit is owned (and therefore
    // persisted and re-applied) instead of being dropped as an unknown consumer.
    val currentPanelModel by rememberUpdatedState(panelModel)
    // 与手机端同一个坑：`LaunchedEffect(Unit)` 捕获的是首次组合的 `libraryIndex`
    // （那时还是 null），撤销跨边框就永远指不回上一张卡 —— 历史被消耗掉，画面不动。
    // 见 [UndoCardTarget]。
    val undoCardTarget = remember { UndoCardTarget { libraryIndex?.cards.orEmpty() } }
    // 当前会话里的值是从哪张照片读出来的：同照片的重新水合 = 换了边框，它不能清撤销栈
    // （换边框这一步就是用户要回去的那一步，P0-2）；换照片才清。
    var hydratedPhotoId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        session.onValuesChanged = { _, overrides ->
            currentPanelModel?.let { projects.applyControlOverrides(it, overrides) }
        }
        session.onExportChanged = { projects.setExportSettings(it) }
        session.onCardRestored = { cardId ->
            // 撤销 / 重做跨了边框：把选中边框指回去，写入仍由下面那个水合 effect 做。
            // （手机端同一个回调；这里不另打证据标签，`FM_*` 字面量在 shell 层是被
            // ARCH1 记在允许清单里的债，只减不增。）
            if (cardId == null) {
                selectedCard = null
            } else {
                undoCardTarget.resolve(cardId)?.let { restored -> selectedCard = restored }
            }
        }
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
        val photoId = projects.activePhotoId
        val photoChanged = hydratedPhotoId != photoId
        hydratedPhotoId = photoId
        session.seed(built.controls)
        session.replaceValues(projects.designValues(built), clearHistory = photoChanged)
        session.setExportSilently(projects.exportSettingsForUi())
    }
    LaunchedEffect(libraryState) {
        val requested = desktopPageForLaunchTab(LaunchOptions.openTab) ?: return@LaunchedEffect
        if (libraryState !is LibraryLoadState.Ready) return@LaunchedEffect
        kotlinx.coroutines.delay(1200)
        paramsPage = requested
        FmLog.info("FM_OPEN_TAB") { "page=${requested.name} raw=${LaunchOptions.openTab}" }
    }
    LaunchedEffect(importNotice) {
        if (importNotice == null) return@LaunchedEffect
        kotlinx.coroutines.delay(6000)
        importNotice = null
    }
    // Cold start shows the library, not last session's editor. Auto-selection of a
    // photo's design is armed only after the user picks a card or a photo; a restored
    // project primes the "本项目在用" card instead of opening it.
    var autoSelectCard by remember { mutableStateOf(false) }
    // Selecting a photo (after the user acts) points the library at its card.
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
        if (!autoSelectCard) return@LaunchedEffect
        if (selectedCard?.id != card.id) selectedCard = card
    }
    // 恢复已保存的工程：当前照片本来就带着边框，右栏不能写「未选择边框」。
    // 冷启动仍然先看边框库（`stageMode` 不动），只把这张卡点亮，参数面板按
    // 工程里的设计恢复 —— 与底部胶片条的「已选边框」计数保持一致。
    var restoredSelection by remember { mutableStateOf(false) }
    LaunchedEffect(projects.loaded, projects.activeDesign?.cardId, libraryState) {
        if (restoredSelection) return@LaunchedEffect
        if (!projects.loaded) return@LaunchedEffect
        if (autoSelectCard || selectedCard != null) {
            restoredSelection = true
            return@LaunchedEffect
        }
        val ready = libraryState as? LibraryLoadState.Ready ?: return@LaunchedEffect
        val card = restoredCardOf(projects.activeDesign?.cardId, ready.snapshot.cards)
            ?: return@LaunchedEffect
        selectedCard = card
        restoredSelection = true
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
        if (migrated > 0) FmLog.info("FM_DESKTOP_PRESET_MIGRATED") { "count=$migrated legacy_kept=${legacy.size}" }
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
            FmLog.info("FM_DEMO_EDITS") { "applied=$applied card=${selectedCard?.id}" }
            repeat(LaunchOptions.undoSteps) { session.undo() }
            if (LaunchOptions.undoSteps > 0) FmLog.info("FM_DEMO_UNDO") { "steps=${LaunchOptions.undoSteps}" }
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
            FmLog.info("FM_OPEN_CARD") { "${card.id} name=${card.name}" }
        } else {
            FmLog.info("FM_OPEN_CARD_MISSING") { "$id" }
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
        FmLog.info("FM_PRODUCT_BATCH_SEED") {
            "enqueued=$enqueued total=${projects.batchJobs.size} " +
                "pending=${projects.batchJobs.count { it.status == BatchJobStatus.Queued }}"
        }
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
                FmLog.info("FM_OPEN_SHEET") { "presets card=${card.id}" }
            }
            LaunchOptions.openExportSheet -> {
                if (requestExport()) {
                    LaunchOptions.sheetsApplied = true
                    FmLog.info("FM_OPEN_SHEET") { "export card=${card.id}" }
                }
            }
        }
    }

    BackHandler(enabled = presetSheetOpen || exportSheetOpen || helpOpen || aboutOpen || onboardingOpen) {
        when {
            // 返回/关闭等于「跳过」：显式关掉了就别再每次启动都弹（与跳过按钮同一条路）。
            onboardingOpen -> closeOnboarding(skipped = true)
            aboutOpen -> aboutOpen = false
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
            val line = "content=%.0f left=%.0f right=%.0f stage=%.0f " +
                "compact=%s showL=%s showR=%s"
            FmLog.info("FM_DESKTOP_COLUMNS") {
                line.format(
                    contentWidth.value,
                    columns.left,
                    columns.right,
                    columns.stage,
                    columns.compact,
                    columns.showLeft,
                    columns.showRight,
                )
            }
        }

        // Focus belongs to the 文字 tab: switching tab, folding the inspector or
        // collapsing into the single-pane layout drops it, so the stage never
        // frames a row whose editor is no longer on screen.
        LaunchedEffect(paramsPage, rightVisible) {
            // 打开过文字页 = 用户已经知道这几行能改：撤掉这一拍舞台上的占位。
            if (paramsPage == InspectorPage.Text) exifHintsAcknowledged = true
            if (paramsPage != InspectorPage.Text || !rightVisible) focusedTextSlotKey = null
        }

        fun applyCardSelection(card: LibraryCard, recordTrial: Boolean) {
            autoSelectCard = true
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
            FmLog.info("FM_TRIAL") { "step=${if (forward) "next" else "prev"} card=${card.id}" }
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
                    onRetry = { libraryReload++ },
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
                )
            }
        }

        val addPhoto: () -> Unit = {
            val paths = projects.pickPhotos()
            if (paths.isNotEmpty()) {
                scope.launch {
                    val report = projects.importPhotos(paths)
                    FmLog.info("FM_IMPORT") { "added=${report.added} failed=${report.failures.size}" }
                    importNotice = "已导入 ${report.added} 张" + if (report.failures.isEmpty()) "" else "，失败 ${report.failures.size} 张"
                    if (report.added > 0) stageMode = StageMode.Edit
                }
            }
        }

        FeelProbe.Host()
        FeelProbe.ColdStart(libraryState is LibraryLoadState.Ready)
        StartupFrameProbe()

        CompositionLocalProvider(LocalTextEditFocus provides textEditFocus) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                // 方向键切换对象只在**没有子控件先消费**时才发生（UI 评审 P0-1）：
                // `onKeyEvent` 从焦点节点向父级冒泡，参数滑杆会在自己的 preview 里先
                // 吃掉 ←/→/↑/↓；焦点在舞台上时才会落到这里。快捷键其余部分仍用
                // preview（要抢在文本框之前）。
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val direction = DesktopArrowKeys.navigate(
                        key = event.key,
                        ctrl = event.isCtrlPressed,
                        shift = event.isShiftPressed,
                        textEditing = textEditFocus.isEditing,
                        sheetOpen = presetSheetOpen || exportSheetOpen || helpOpen || aboutOpen || onboardingOpen,
                        editStage = stageMode == StageMode.Edit,
                    )
                    if (direction == 0) return@onKeyEvent false
                    stepCard(forward = direction > 0)
                    true
                }
                .onPreviewKeyEvent { event ->
                    // W-01: the root receives preview keys before the focused text field.
                    // Yield the three key families an editor owns (backslash, arrows,
                    // undo/redo); continue consuming everything else as before.
                    if (textEditFocus.isEditing &&
                        TextEditKeys.belongsToTextEditor(
                            key = event.key,
                            ctrl = event.isCtrlPressed,
                            keyDown = event.type == KeyEventType.KeyDown,
                        )
                    ) {
                        return@onPreviewKeyEvent false
                    }
                    if (projects.projectWritesBlocked) return@onPreviewKeyEvent true
                    if (event.key == Key.Backslash) {
                        // 按住 `\` 看原图（报告 §6）：按下显示原图，松开恢复。
                        holdOriginal = event.type == KeyEventType.KeyDown
                        return@onPreviewKeyEvent true
                    }
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val sheetOpen = presetSheetOpen || exportSheetOpen || helpOpen || aboutOpen || onboardingOpen
                    if (event.key == Key.Escape) {
                        when {
                            onboardingOpen -> closeOnboarding(skipped = true)
                            aboutOpen -> aboutOpen = false
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
                        FmLog.info("FM_SHORTCUT") { "help" }
                        return@onPreviewKeyEvent true
                    }
                    if (!event.isCtrlPressed) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Z && event.isShiftPressed -> {
                            val handled = session.redo()
                            if (handled) FmLog.info("FM_SHORTCUT") { "redo" }
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
                                FmLog.info("FM_TRIAL") { "undo card=${anchor.id}" }
                                true
                            } else {
                                val handled = session.undo()
                                if (handled) FmLog.info("FM_SHORTCUT") { "undo" }
                                handled
                            }
                        }
                        event.key == Key.Y -> {
                            val handled = session.redo()
                            if (handled) FmLog.info("FM_SHORTCUT") { "redo" }
                            handled
                        }
                        event.key == Key.E -> {
                            if (!requestExport()) return@onPreviewKeyEvent true
                            FmLog.info("FM_SHORTCUT") { "export" }
                            true
                        }
                        event.key == Key.P && event.isShiftPressed -> {
                            FeelProbe.begin("preset", "shortcut")
                            presetSheetOpen = true
                            FmLog.info("FM_SHORTCUT") { "presets" }
                            true
                        }
                        event.key == Key.B && event.isShiftPressed -> {
                            toggleRight()
                            FmLog.info("FM_SHORTCUT") { "rail-right collapsed=${layout.rightCollapsed}" }
                            true
                        }
                        event.key == Key.B -> {
                            toggleLeft()
                            FmLog.info("FM_SHORTCUT") { "rail-left collapsed=${layout.leftCollapsed}" }
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
                    NoticeText.human(projects.presetError) ?: "保存预设失败"
                }
                FmLog.info("FM_DESIGN_PRESET_SAVE") { "name=$name ok=${saved != null}" }
            }
            val resetCurrentDesign: () -> Unit = {
                val current = selectedCard
                if (current != null) {
                    session.resetCard(current.id)
                    FmLog.info("FM_DESIGN_RESET") { "${current.id}" }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = FmDesktop.edge, vertical = FmDesktop.edge),
            ) {
                DesktopTopBarV2(
                    // AU4-02：顶栏与三栏共用同一个内容宽（V2 三栏贴边，内容宽 = 窗宽），
                    // 窄窗切紧凑态，保证「导出」「⚙」「● 已自动保存」完整可见。
                    compact = desktopTopBarCompact(contentWidth.value),
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
                    onOpenAbout = { aboutOpen = true },
                    onOpenOnboarding = { openOnboarding() },
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

                if (stageMode == StageMode.Batch) {
                    // WP-D2: 顶栏切到「批量导出」时整个工作区换成 V2 批量工作区
                    // （左勾选 · 中联系表 · 右设置摘要 + 队列）。
                    BatchWorkspace(
                        projects = projects,
                        cardName = { id ->
                            (libraryState as? LibraryLoadState.Ready)?.snapshot?.cards
                                ?.firstOrNull { it.id == id }?.name
                        },
                        // 批量与单张共用同一份导出设置：这里直接开同一个对话框，
                        // 关掉后摘要按 project.exportSettings 重新算。
                        onOpenExportSettings = { requestExport() },
                        onBackToEdit = { photoId ->
                            autoSelectCard = true
                            projects.selectPhoto(photoId)
                            stageMode = StageMode.Edit
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                } else {
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
                    showExifHints = !exifHintsAcknowledged,
                    session = session,
                    inspectorState = inspectorState,
                    onRetryInspector = { inspectorReload++ },
                    tabModel = tabModel,
                    paramsPage = paramsPage,
                    onSelectPage = { paramsPage = it },
                    onTextSlotFocus = { key ->
                        focusedTextSlotKey = key
                        FmLog.info("FM_TEXT_FOCUS") { "key=${key ?: "-"} page=${paramsPage.name}" }
                    },
                    highlightedTextSlotKey = focusedTextSlotKey,
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

            pendingUpdate?.let { manifest ->
                UpdatePromptDialog(
                    manifest = manifest,
                    asset = manifest.assetFor(com.framemark.native.product.update.updatePlatform()),
                    onDismiss = {
                        pendingUpdate = null
                        FmLog.info("UPDATE_PROMPT") { "action=later version=${manifest.versionName}" }
                    },
                    onSkipVersion = {
                        com.framemark.native.product.update.UpdateLaunch.skip(
                        com.framemark.native.shell.PlatformUpdateSkipStorage(),
                        manifest.versionName,
                    )
                        FmLog.info("UPDATE_PROMPT") { "action=skip version=${manifest.versionName}" }
                    },
                )
            }
            DesktopHelpOverlay(open = helpOpen, onDismiss = { helpOpen = false })
            DesktopAboutOverlay(open = aboutOpen, onDismiss = { aboutOpen = false })
            // W-02: the window close handler refused to exit; this is the only place
            // that can retry, save a copy, or leave with the edits knowingly dropped.
            DesktopExitBlockedDialog(
                block = AppExitGate.block,
                onRetry = { AppExitGate.retry(projects.flush(), projects.saveError) },
                onSaveCopy = {
                    val path = projects.pickProjectCopyPath()
                    if (path != null) {
                        if (projects.saveProjectCopy(path)) {
                            AppExitGate.copySaved(path)
                        } else {
                            AppExitGate.copyFailed(projects.saveError)
                        }
                    }
                },
                onRevealFolder = projects.projectFilePath()?.let { path ->
                    { WorkflowEnv.platform?.revealInFolder(path) }
                },
                onDiscard = { AppExitGate.discardAndExit() },
                onDismiss = { AppExitGate.dismiss() },
            )
            DesktopSavePresetDialog(
                open = savePresetOpen,
                name = savePresetName,
                onNameChange = { savePresetName = it },
                onDismiss = { savePresetOpen = false },
                onSave = { name -> saveDesignPreset(name) },
            )
            DesktopResetDesignDialog(
                open = resetConfirmOpen,
                onDismiss = { resetConfirmOpen = false },
                onConfirm = { resetCurrentDesign() },
            )
            if (presetSheetOpen) {
                ProjectPresetSheet(
                    projects = projects,
                    cardLookup = { id ->
                        (libraryState as? LibraryLoadState.Ready)?.snapshot?.cards?.firstOrNull { it.id == id }
                    },
                    onDismiss = { presetSheetOpen = false },
                )
            }
            DesktopExportSheet(
                open = exportSheetOpen,
                session = session,
                model = model,
                panelModel = panelModel,
                selectedCard = selectedCard,
                projects = projects,
                cards = (libraryState as? LibraryLoadState.Ready)?.snapshot?.cards,
                onDismiss = { exportSheetOpen = false },
            )
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

            DesktopRemovePhotoDialog(
                target = removeTarget,
                onDismiss = { removeTarget = null },
                onRemove = { target -> projects.removePhoto(target.id) },
            )
            if (projects.projectWritesBlocked) {
                ProjectReadRecoveryBlock(
                    loading = !projects.loaded,
                    error = projects.loadError ?: projects.saveError,
                    onRetry = {
                        FmLog.info("FM_PROJECT_RETRY_REQUESTED") { "surface=desktop" }
                        val recovered = projects.retryProjectLoad()
                        FmLog.info("FM_PROJECT_RETRY_RESULT") { "recovered=$recovered error=${projects.loadError ?: "-"}" }
                    },
                )
            }

            // 新手教程盖在所有东西之上：首次启动自动弹，之后从齿轮弹层手动打开。
            if (onboardingOpen) {
                OnboardingOverlay(
                    page = onboardingPage,
                    onNext = {
                        onboardingPage = (onboardingPage + 1).coerceAtMost(OnboardingText.PAGES.lastIndex)
                    },
                    onSkip = { closeOnboarding(skipped = true) },
                    onDone = { closeOnboarding(skipped = false) },
                )
            }
        }
        }
    }
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
            FmLog.info("FM_FIRST_FRAME") { "elapsedMs=${now - start}" }
        }
    }
}
