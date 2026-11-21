package com.framemark.native.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framemark.native.LaunchOptions
import com.framemark.native.inspector.InspectorDataSource
import com.framemark.native.inspector.InspectorLoadState
import com.framemark.native.inspector.InspectorModelFactory
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryDataSource
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.LibraryLoadState
import com.framemark.native.product.toDesignSnapshot
import com.framemark.native.shell.phone.PhoneAboutPage
import com.framemark.native.shell.phone.PhoneExportSheet
import com.framemark.native.shell.phone.PhoneHomeDestination
import com.framemark.native.shell.phone.PhoneHomePolicy
import com.framemark.native.shell.phone.PhonePage
import com.framemark.native.shell.phone.PhonePagePolicy
import com.framemark.native.shell.phone.PhoneRecentEdit
import com.framemark.native.shell.phone.PhoneSettingsPage
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.BatchCandidate
import com.framemark.native.workflow.BatchPreflight
import com.framemark.native.workflow.batchPreflight
import com.framemark.native.workflow.BatchRunner
import com.framemark.native.workflow.BatchScreen
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.NativeEngine
import com.framemark.native.workflow.OnboardingState
import com.framemark.native.workflow.OverlaySpec
import com.framemark.native.workflow.PhonePhotoBatchQueue
import com.framemark.native.workflow.PhotoBatchScreen
import com.framemark.native.workflow.PhotoSelection
import com.framemark.native.workflow.PhotoTray
import com.framemark.native.workflow.PresetStore
import com.framemark.native.workflow.PreviewGeometry
import com.framemark.native.workflow.ProjectReadRecoveryBlock
import com.framemark.native.workflow.LocalTextEditFocus
import com.framemark.native.workflow.TextEditFocus
import com.framemark.native.workflow.TextSlotRegistry
import com.framemark.native.workflow.TrayPhoto
import com.framemark.native.workflow.WatermarkSelection
import com.framemark.native.workflow.WatermarkSpec
import com.framemark.native.workflow.withNativeTextSlots
import com.framemark.native.workflow.WorkflowEnv
import com.framemark.native.workflow.WorkflowOnboardingStore
import com.framemark.native.product.FmLog
import com.framemark.native.product.NoticeText

/** The legacy batch sheet is retained only for the explicit QA/demo launch path. */
internal fun createPhoneBatchDemoRunner(
    enabled: Boolean,
    create: () -> BatchRunner,
): BatchRunner? = if (enabled) create() else null

/**
 * Phone shell (width < [com.framemark.native.theme.FmBreakpoints.compact]).
 *
 * Old WebView information architecture, native engine underneath:
 *  - no photo in the tray → the import home (sample hero + three import actions);
 *  - otherwise → the preview-first editor with the 边框 / 水印 segment bar;
 *  - the card library is a full-screen sheet opened from 边框, not a tab;
 *  - 批量 / 设置 / 撤销 / 重做 live in the editor's overflow menu.
 */
/** Process-local page memory: survives rotation, resets on a new process. */
private object PhonePageMemory {
    var page: PhonePage? = null
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PhoneShell() {
    val batchScroll = rememberLazyListState()
    // P4: product.Project is the authority for photos, one design per photo, the
    // embedded watermarks and the export settings, and SessionStore writes it
    // atomically — a force-stop restores all of it. The tray below is a projection.
    val store = remember {
        PhoneProjectRuntime.ensure {
            PhoneProjectStore(InMemoryProductFileSystem(), null, WorkflowEnv.platform)
        }
    }
    LaunchedEffect(Unit) { store.load() }
    if (!store.loaded || store.projectWritesBlocked) {
        ProjectReadRecoveryBlock(
            loading = !store.loaded,
            error = store.loadError ?: store.saveError,
            onRetry = {
                FmLog.info("FM_PROJECT_RETRY_REQUESTED") { "surface=phone" }
                val recovered = store.retryProjectLoad()
                FmLog.info("FM_PROJECT_RETRY_RESULT") { "recovered=$recovered error=${store.loadError ?: "-"}" }
            },
        )
        return
    }
    val tray = remember { PhotoTray() }
    // One design session (values + undo history) hydrated from the active design.
    val session = remember { DesignSession() }
    var photoBatchOpen by remember { mutableStateOf(false) }
    var confirmApplyAll by remember { mutableStateOf(false) }
    // M01 rule 2: 「按照片批量」预检——有未应用边框的照片时先确认，再入队。
    var pendingPhotoBatch by remember { mutableStateOf<BatchPreflight?>(null) }
    var watermarkError by remember { mutableStateOf<String?>(null) }
    // 启动自动检查：有新版本就弹一次（同一版本「跳过」过就不再弹），见 UpdateLaunch。
    var pendingUpdate by remember { mutableStateOf<com.framemark.native.product.update.UpdateManifest?>(null) }
    // 自建相册（Android 有实现；桌面端没有，按钮回退到系统选择器）。
    var galleryOpen by remember { mutableStateOf(false) }
    // 「已移除 · 撤销」要放回去的那一张（null = 提示位空着）。快照自己带着设计和水印。
    var removedPhoto by remember { mutableStateOf<RemovedPhoto?>(null) }
    // A watermark picked before any border exists yet; applied once the card does.
    var pendingWatermark by remember { mutableStateOf<String?>(null) }
    var pairDemoSeeded by remember { mutableStateOf(false) }
    val batch = remember(store, LaunchOptions.openBatch) {
        createPhoneBatchDemoRunner(LaunchOptions.openBatch) {
            BatchRunner(store.fileSystem, store.batchInputs())
        }
    }
    val shortcutFocus = remember { FocusRequester() }
    // W-01: text fields register here while focused so Ctrl+Z/Y stay inside the
    // editor instead of undoing the design behind it.
    val textEditFocus = remember { TextEditFocus() }
    // Bumped when something replaced the active design wholesale (a preset, an
    // apply-to-all), so the editor re-reads it even though the card and photo are
    // the same object identity-wise.
    var hydrateToken by remember { mutableIntStateOf(0) }
    // Evidence-only export format/size hook is applied once, after the first hydrate.
    var exportHookApplied by remember { mutableStateOf(false) }

    var selectedCard by remember { mutableStateOf<LibraryCard?>(null) }
    var libraryState by remember { mutableStateOf<LibraryLoadState>(LibraryLoadState.Loading) }
    var libraryReload by remember { mutableIntStateOf(0) }
    var inspectorState by remember { mutableStateOf<InspectorLoadState>(InspectorLoadState.Idle) }
    var inspectorReload by remember { mutableIntStateOf(0) }
    var autoOpened by remember { mutableStateOf(false) }

    // Replaced on every card/photo switch. A late callback from the outgoing
    // render therefore updates a dead registry instead of the visible text tab.
    val textSlots = remember(selectedCard?.id, store.activePhotoId) { TextSlotRegistry() }

    var tool by rememberSaveable { mutableStateOf(EditorTool.Border) }
    var presetSheetOpen by remember { mutableStateOf(false) }
    var exportSheetOpen by remember { mutableStateOf(false) }
    var batchSheetOpen by remember { mutableStateOf(false) }
    // The top-level page (首页 / 编辑页 / 设置页). This replaced the old
    // `if (tray.isEmpty)` switch, which left a project with photos with no way back
    // to the home, the settings or the batch export (MID-APK-REPORT §6 item 1).
    // Page memory is process-scoped, not Activity-saved state: a rotation keeps the
    // current page, while a fresh process (cold start or OS process death) opens
    // Home instead of restoring the last editor page.
    var page by remember { mutableStateOf(PhonePageMemory.page ?: PhonePage.Home) }
    LaunchedEffect(page) { PhonePageMemory.page = page }
    // 新手教程：一层覆盖，不是第五个 PhonePage。页面栈不动，关掉就回到原来那一页。
    // 首次启动在**首帧之后**才判定（`withFrameNanos`），免得首页先露一下再被盖住。
    val onboardingStore = remember { WorkflowOnboardingStore() }
    var onboardingOpen by remember { mutableStateOf(false) }
    var onboardingPage by remember { mutableIntStateOf(0) }
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
    // Until the user acts, show Home regardless of pages restored by background
    // effects (the startup photo sync also bumps PhotoSelection.version).

    val libraryCards = (libraryState as? LibraryLoadState.Ready)?.snapshot?.cards.orEmpty()

    fun cardOf(id: String?): LibraryCard? = libraryCards.firstOrNull { it.id == id }

    /** The engine style id of a card; the design must carry it or the renderer picks the wrong style. */
    fun styleOf(id: String?): String? = cardOf(id)?.systemStyle

    // 最近编辑: the project's newest photos with the border each one wears. The
    // ordering cap and the row shape live in `PhoneHomePolicy` (tested there).
    val recentEdits = remember(store.project, libraryState) {
        val total = store.photos.size
        val rows = store.photos.mapIndexed { index, photo ->
            PhoneRecentEdit(
                photoId = photo.id,
                path = photo.path.takeIf { it.isNotBlank() },
                displayName = photo.displayName,
                borderName = store.designOf(photo.id)?.cardId?.let { id -> cardOf(id)?.name },
                index = index + 1,
                count = total,
                // 演示稿的「最近编辑」第二行是「边框名 · 主题」，主题取这张照片自己的覆盖。
                themeKey = store.designOf(photo.id)?.theme,
                // 首页扇形按照片自己的长宽比摆，和演示稿的 aspectRatio 一致。
                widthPx = photo.widthPx,
                heightPx = photo.heightPx,
            )
        }
        PhoneHomePolicy.recentEdits(rows)
    }

    val currentCards by rememberUpdatedState(libraryCards)
    // The photo batch is `product.BatchQueue`: every job carries its own photo path,
    // design, watermark bytes and export settings, and the queue lives in its own
    // document, so a resumed run repeats nothing and skips nothing.
    val batchQueue = remember(store) {
        PhonePhotoBatchQueue(store.fileSystem, cardLookup = { id: String -> currentCards.firstOrNull { it.id == id } })
    }
    LaunchedEffect(batchQueue) {
        batchQueue.load()
        // W3 crash-window evidence (debug-only): auto-start the per-photo queue so
        // an existing Queued job drives the commit pipeline without UI interaction.
        // LaunchOptions.queueAutoStart is set only inside `if (BuildConfig.DEBUG)`.
        if (LaunchOptions.queueAutoStart && batchQueue.pendingCount > 0) {
            FmLog.info("FM_QUEUE_AUTOSTART") { "pending=${batchQueue.pendingCount}" }
            batchQueue.start()
        }
    }

    /** Rebuilds the visible strip from the project; only when the set really changed. */
    fun syncTray() {
        val paths = store.photos.map { it.path }.filter { it.isNotBlank() }
        val displayNamesByPath = store.photos
            .filter { it.path.isNotBlank() }
            .associate { it.path to it.displayName }
        val hasSample = store.photos.any { it.path.isBlank() }
        val wanted = paths.toSet() + if (hasSample) setOf<String?>(null) else emptySet<String?>()
        val shown = tray.items.map { it.path }.toSet()
        if (wanted != shown) {
            tray.clear()
            if (paths.isNotEmpty()) tray.adopt(paths, null, displayNamesByPath)
            if (hasSample) tray.addSample()
        } else {
            tray.updateDisplayNames(displayNamesByPath)
        }
        val active = store.activePhoto
        if (active != null) {
            val match = tray.items.firstOrNull { it.path == active.path.takeIf { p -> p.isNotBlank() } }
            if (match != null && match.id != tray.selectedId) tray.select(match.id)
        }
    }

    fun trayToPhotoId(photo: TrayPhoto): String = store.idForPath(photo.path)

    /** The active photo's design, with the card the editor is showing. */
    fun activeDesignSnapshot(): DesignSnapshot? {
        val design = store.activeDesign ?: return null
        return design.toPhoneSnapshot()
    }

    fun activeOverlay(aspectCardId: String? = store.activeDesign?.cardId): OverlaySpec? {
        val id = store.activePhotoId ?: return null
        val aspect = PreviewGeometry.aspectOf(aspectCardId) ?: 1.5f
        val spec = store.uiWatermark(id, aspect) ?: return null
        return spec.toOverlay(spec.photoAspect)
    }

    /** The editor's watermark state for the active photo, read straight from the project. */
    fun activeUiWatermark(): WatermarkSpec? {
        val id = store.activePhotoId ?: return null
        val aspect = PreviewGeometry.aspectOf(store.activeDesign?.cardId) ?: 1.5f
        return store.uiWatermark(id, aspect)
    }

    /** Copies the active photo's border, values and signature onto every photo. */
    fun applyDesignToAll() {
        store.applyActiveDesignToAll()
        hydrateToken++
        FmLog.info("FM_APPLY_ALL") { "photos=${store.photos.size} card=${store.activeDesign?.cardId} wm=${store.activeDesign?.overlay?.asset?.sha256?.take(8) ?: "<none>"}" }
    }

    fun buildPhotoBatch(): List<BatchCandidate> = store.photoEntries().mapNotNull { entry ->
        val design = entry.design ?: return@mapNotNull null
        BatchCandidate(
            photoId = entry.photo.id,
            photoPath = entry.photo.path.takeIf { it.isNotBlank() },
            photoFingerprint = entry.photo.fingerprint,
            displayName = entry.photo.displayName,
            design = design,
            card = cardOf(design.cardId),
            settings = store.project.exportSettings.copy(
                quality = WorkflowEnv.platform?.preferredJpegQuality() ?: 100,
            ),
            nameIndex = store.photos.indexOfFirst { it.id == entry.photo.id }.let { if (it < 0) 1 else it + 1 },
            outputDirectory = WorkflowEnv.platform?.exportDir,
        )
    }

    /**
     * Submits the built (already designed-only) candidates and opens the sheet. Kept
     * separate from the pre-flight gate so the 「仅导出已设计的 X 张」 confirmation can
     * reuse it without re-running the check. `buildPhotoBatch()` reflects the current
     * per-photo bindings only; historical queue rows are untouched (M01 rule 5).
     */
    fun submitAndOpenPhotoBatch(preflight: BatchPreflight? = null) {
        // `selected` is the whole selection the user asked for, not just the designed
        // subset: the sheet must be able to show 无设计未加入 next to what it queued.
        val report = batchQueue.submit(
            buildPhotoBatch(),
            selected = store.photos.size,
            undesigned = preflight?.undesignedCount ?: 0,
        )
        photoBatchOpen = true
        report.rejected.firstOrNull()?.let { (name, why) -> watermarkError = "$name：$why" }
        FmLog.info("FM_PHOTO_BATCH") {
            "open photos=${store.photos.size} selected=${report.selected} " +
                "added=${report.added} duplicate=${report.duplicates} " +
                "undesigned=${report.undesigned} rejected=${report.rejected.size} " +
                "reconciled=${report.reconciled} rows=${batchQueue.rows().size}"
        }
    }

    /**
     * 批量导出 · 按照片: pre-flight first (photos with no border are listed, never
     * queued), then queue the designed ones and open the sheet. The home's 批量
     * tile and the editor's former ⋯ menu share this one entry point.
     */
    fun openPhotoBatch() {
        val preflight = store.project.batchPreflight(store.photos.map { it.id })
        when {
            preflight.allUndesigned -> {
                pendingPhotoBatch = null
                watermarkError = "还没有为任何照片应用边框，未创建导出任务。请先在编辑页选择边框。"
            }
            preflight.hasUndesigned -> pendingPhotoBatch = preflight
            else -> {
                pendingPhotoBatch = null
                submitAndOpenPhotoBatch()
            }
        }
        FmLog.info("FM_PHOTO_BATCH_PREFLIGHT") {
            "photos=${store.photos.size} designed=${preflight.designedCount} " +
                "undesigned=${preflight.undesignedCount}"
        }
    }

    // The project drives the strip; every path list change re-projects it.
    LaunchedEffect(store.photos, store.activePhotoId, libraryState) { syncTray() }

    // A pick (single or multi) lands asynchronously from the system picker; the
    // project registers every path of the batch, the strip follows. A pick always
    // lands in the editor, so the home closes even when it started the pick.
    LaunchedEffect(PhotoSelection.pickVersion) {
        if (PhotoSelection.pickVersion == 0) return@LaunchedEffect
        // A bare `set(path)` (startup restore pointing the engine at the active
        // photo) also bumps the version, but is not a user pick: only a pick with
        // a batch lands in the editor.
        val path = PhotoSelection.path ?: return@LaunchedEffect
        store.adoptPaths(PhotoSelection.batch, path, PhotoSelection.displayNamesByPath)
        // A pick always lands in the editor, whichever page started it.
        page = PhonePagePolicy.afterPhotosAdded()
        FmLog.info("FM_TRAY") { "photos=${tray.count} picked=${PhotoSelection.batch.size} selected=${tray.selected?.label}" }
    }

    // The engine always renders the active photo, so a restore points it there.
    LaunchedEffect(store.activePhotoId) {
        val path = store.activePhoto?.path?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (PhotoSelection.path != path) {
            PhotoSelection.set(path)
            FmLog.info("FM_PROJECT_PHOTO") { "$path" }
        }
    }

    // A picked watermark becomes embedded bytes on the active photo — the document
    // carries the PNG, so a reinstall or a moved temp file cannot lose the sign.
    LaunchedEffect(WatermarkSelection.version) {
        if (WatermarkSelection.version == 0) return@LaunchedEffect
        val path = WatermarkSelection.path ?: return@LaunchedEffect
        val photoId = store.activePhotoId ?: return@LaunchedEffect
        val error = store.importWatermark(
            photoId = photoId,
            path = path,
            widthPx = PhoneWatermarkPick.widthPx.takeIf { it > 0 },
            heightPx = PhoneWatermarkPick.heightPx.takeIf { it > 0 },
            aspect = WatermarkSelection.aspect,
            cardId = selectedCard?.id,
            styleId = selectedCard?.systemStyle,
        )
        if (error == null) {
            pendingWatermark = null
        } else if (error.code == "FM-E-INPUT-001") {
            // A watermark can arrive before any border exists (a cold start that
            // seeds both at once). Keep it pending and land it when the card does.
            pendingWatermark = path
        } else {
            pendingWatermark = null
            watermarkError = "${error.code} ${error.message}"
        }
        FmLog.info("FM_WATERMARK_APPLY") {
            "photo=$photoId path=$path pending=${pendingWatermark != null} " +
                "wm=${store.overlayOf(photoId)?.asset?.sha256?.take(12) ?: "<none>"} err=${error?.code ?: "none"}"
        }
    }

    // Evidence: FM_PHOTO keeps the engine pointed at the same seeded file.
    LaunchedEffect(Unit) {
        val seed = LaunchOptions.userPhotoPath
        if (seed != null && PhotoSelection.path == null) {
            store.adoptPaths(listOf(seed), seed)
            PhotoSelection.set(seed)
        }
    }

    // Evidence-only: batch sheet seeded with engine-wired cards once the library is
    // ready. A persisted queue wins over seeding; FM_BATCH_AUTOSTART=1 resumes.
    LaunchedEffect(LaunchOptions.openBatch, libraryState) {
        if (!LaunchOptions.openBatch || LaunchOptions.batchSeeded) return@LaunchedEffect
        val demoBatch = batch ?: return@LaunchedEffect
        val ready = libraryState as? LibraryLoadState.Ready ?: return@LaunchedEffect
        LaunchOptions.batchSeeded = true
        batchSheetOpen = true
        if (LaunchOptions.batchReset) demoBatch.resetForVerify()
        demoBatch.seedDemo(ready.snapshot.cards, LaunchOptions.batchCount)
        if (WorkflowEnv.platform?.batchAutoStart() == true && demoBatch.hasPending) demoBatch.start()
        FmLog.info("FM_BATCH_DEMO") { "items=${demoBatch.items.size} pending=${demoBatch.hasPending} reset=${LaunchOptions.batchReset}" }
    }

    // 新照片的兜底边框：优先「热门」第一张，其次「常用」第一张，再其次任何一张。
    // 这样全新项目里导入第一张照片就有边框可看，不会落到「点一下选择一个边框」的空态。
    LaunchedEffect(libraryState) {
        val cards = (libraryState as? LibraryLoadState.Ready)?.snapshot?.cards.orEmpty()
        val fallback = cards.firstOrNull { it.badge == LibraryIndex.HOT_BADGE }
            ?: cards.firstOrNull { it.category == LibraryIndex.CATEGORY_COMMON }
            ?: cards.firstOrNull()
        store.fallbackCard = fallback?.let { card -> { card.id to card.systemStyle } }
        // 分享/相册入口可能在库加载完之前就导完了照片，这里补一次默认边框。
        store.applyFallbackDesignToUndesigned()
    }

    // Evidence-only: open a workflow sheet once the editor model is ready (so the
    // demo edits and the seeded preset have already happened).
    LaunchedEffect(selectedCard?.id, store.activeDesign?.cardId, inspectorState) {
        if (LaunchOptions.sheetsApplied) return@LaunchedEffect
        val card = selectedCard ?: return@LaunchedEffect
        if (inspectorState !is InspectorLoadState.Ready) return@LaunchedEffect
        when {
            LaunchOptions.openPresetSheet -> {
                page = PhonePage.Editor
                presetSheetOpen = true
                LaunchOptions.sheetsApplied = true
                FmLog.info("FM_OPEN_SHEET") { "presets card=${card.id}" }
            }
            LaunchOptions.openExportSheet -> {
                if (activeDesignSnapshot() != null) {
                    page = PhonePage.Editor
                    exportSheetOpen = true
                    LaunchOptions.sheetsApplied = true
                    FmLog.info("FM_OPEN_SHEET") { "export card=${card.id}" }
                }
            }
        }
    }

    // Load the frozen E1a bundle once per process (small JSON; parsed off the
    // first frame). Retry only re-runs this effect.
    LaunchedEffect(libraryReload) {
        libraryState = LibraryLoadState.Loading
        val result = LibraryDataSource.load()
        libraryState = if (result.error == null) {
            val snapshot = result.snapshot
            FmLog.info("FM_LIB_READY") {
                "cards=${snapshot.cards.size} categories=${snapshot.categories.size} " +
                    "previews=${snapshot.mappedPreviews} unmapped=${snapshot.unmappedPreviewIds.size} " +
                    "generatedAt=${snapshot.generatedAt}"
            }
            LibraryLoadState.Ready(snapshot)
        } else {
            LibraryLoadState.Failed(result.error)
        }
    }

    // The inspector bundle is 1.3 MB of frozen JSON; parse it once on the first
    // card tap and reuse the snapshot for every later card. A failed load is shown
    // in the panel and only retried on demand.
    LaunchedEffect(selectedCard != null, inspectorReload) {
        if (selectedCard == null) return@LaunchedEffect
        if (inspectorState is InspectorLoadState.Ready && inspectorReload == 0) return@LaunchedEffect
        inspectorState = InspectorLoadState.Loading
        val result = InspectorDataSource.load()
        inspectorState = if (result.error == null) {
            FmLog.info("FM_INSPECTOR_READY") {
                "cards=${result.snapshot.capabilityCards} " +
                    "audited=${result.snapshot.auditedCards} platformRows=${result.snapshot.platformRowCount} " +
                    "metaRecords=${result.snapshot.metaRecords} metaControls=${result.snapshot.metaControls}"
            }
            InspectorLoadState.Ready(result.snapshot)
        } else {
            InspectorLoadState.Failed(result.error)
        }
    }

    // Restore only a border saved for the active photo. Photos without a design stay
    // unmarked until the user explicitly selects a card.
    LaunchedEffect(libraryState, store.activePhotoId, store.photos) {
        if (tray.isEmpty) return@LaunchedEffect
        val ready = libraryState as? LibraryLoadState.Ready ?: return@LaunchedEffect
        val cards = ready.snapshot.cards
        val activeDesign = store.activeDesign
        val designCard = activeDesign?.cardId
        // Resolve against THIS snapshot, not the composition-closed `cardOf`: the effect
        // can run in the frame where `libraryState` just turned Ready, and the closed-over
        // `libraryCards` is still the empty list from the previous composition
        // (measured: `exact=1 libCount=0`), which used to fall through to the default.
        if (activeDesign == null) {
            selectedCard = null
            FmLog.info("FM_CARD_RESTORE") { "no_design photo=${store.activePhotoId ?: "-"}; waiting for explicit card selection" }
            return@LaunchedEffect
        }
        val wanted = cards.firstOrNull { it.id == designCard }
        FmLog.info("FM_CARD_RESTORE") {
            "design=${if (store.activeDesign == null) "none" else "own"} " +
                "designCard=${designCard ?: "-"} projectCard=${store.project.selectedCardId ?: "-"} " +
                "resolved=${wanted?.id ?: "-"} cards=${cards.size} active=${store.activePhotoId?.takeLast(22) ?: "-"}"
        }
        if (wanted != null) {
            if (wanted.id != selectedCard?.id) {
                selectedCard = wanted
                FmLog.info("FM_PROJECT_CARD_RESTORE") { "card=${wanted.id} photo=${store.activePhotoId}" }
            }
            return@LaunchedEffect
        }
        // The photo does have a design but no card can be resolved for it. Assigning
        // the built-in default here would write that default over the user's saved
        // design a moment later, so stand down: 宁可不改不存.
        if (store.activeDesign != null) {
            val near = cards.filter { designCard != null && it.id.take(20) == designCard.take(20) }.map { it.id }.take(3)
            FmLog.info("FM_CARD_RESTORE_UNRESOLVED") {
                "keep=$designCard keepLen=${designCard?.length ?: 0} " +
                    "exact=${cards.count { it.id == designCard }} libCount=${libraryCards.size} " +
                    "near=$near first3=${cards.take(3).map { it.id }}"
            }
            return@LaunchedEffect
        }
        selectedCard = null
        FmLog.info("FM_CARD_RESTORE_UNRESOLVED") { "no card matched saved photo design=$designCard" }
    }

    // 边框切换 = 一条可撤销的历史（UX 审查 P0-2）。`onCardChanged` 把「旧边框 → 新边框」
    // 记成一步，前面所有值编辑的历史一律留着（以前这里 `history.clear()`，换错一次既回
    // 不去也把之前的编辑全丢了）。撤销 / 重做跨边框时 session 通过 `onCardRestored` 把
    // 选中边框指回历史里那一张，走的仍是「改 selectedCard」这一条写入路径。
    LaunchedEffect(selectedCard?.id, store.activePhotoId) {
        session.onCardChanged(selectedCard?.id)
        val card = selectedCard ?: return@LaunchedEffect
        val photoId = store.activePhotoId ?: return@LaunchedEffect
        if (store.designOf(photoId)?.cardId != card.id) store.applyCard(card.id, card.systemStyle)
        // A signature that arrived before any border existed lands now.
        val waiting = pendingWatermark ?: return@LaunchedEffect
        val error = store.importWatermark(
            photoId = photoId,
            path = waiting,
            widthPx = PhoneWatermarkPick.widthPx.takeIf { it > 0 },
            heightPx = PhoneWatermarkPick.heightPx.takeIf { it > 0 },
            aspect = WatermarkSelection.aspect,
            cardId = card.id,
            styleId = card.systemStyle,
        )
        if (error == null) {
            pendingWatermark = null
            FmLog.info("FM_WM_PENDING_APPLIED") { "photo=$photoId sha=${store.overlayOf(photoId)?.asset?.sha256?.take(12) ?: "-"}" }
        } else {
            watermarkError = "${error.code} ${error.message}"
        }
    }

    // Evidence: FM_PAIR_DEMO seeds three paired photos (A classic-22 / B tag-9 /
    // C classic-22 + watermark) and opens the photo batch sheet.
    LaunchedEffect(libraryState) {
        if (!LaunchOptions.pairDemo || pairDemoSeeded) return@LaunchedEffect
        val ready = libraryState as? LibraryLoadState.Ready ?: return@LaunchedEffect
        val cards = ready.snapshot.cards
        val c1 = cards.firstOrNull { it.id == "stock-xiaomi-classic-22" } ?: return@LaunchedEffect
        val c2 = cards.firstOrNull { it.id == "stock-xiaomi-tag-9" } ?: return@LaunchedEffect
        val dir = WorkflowEnv.platform?.dataDir?.trimEnd('/', '\\')?.plus("/Pictures/Originals")
        val paths = listOf(
            "$dir/user-1793294406951-0.jpg",
            "$dir/user-1793302925027-1.jpg",
            "$dir/user-1793303120838-1.jpg",
        )
        store.adoptPaths(paths, paths.first())
        val wmSha = store.activePhotoId?.let { store.overlayOf(it) }
        store.photoEntries().forEachIndexed { index, entry ->
            val photo = entry.photo
            val cardId = if (index == 1) c2.id else c1.id
            val design = store.designOf(photo.id)?.copy(cardId = cardId, styleId = cardOf(cardId)?.systemStyle)
                ?: com.framemark.native.product.PhotoDesign.base(cardId, cardOf(cardId)?.systemStyle)
            store.setDesign(photo.id, if (index == 2 && wmSha != null) design.withOverlay(wmSha) else design.withOverlay(null))
        }
        store.publishSharedDesign(
            (inspectorState as? InspectorLoadState.Ready)?.snapshot?.let { InspectorModelFactory.build(c1, it) },
            c1.systemStyle,
            emptyMap(),
        )
        selectedCard = c1
        hydrateToken++
        pairDemoSeeded = true
        FmLog.info("FM_PAIR_DEMO") {
            "photos=${store.photos.size} A=${c1.id} B=${c2.id} C=${c1.id} " +
                "wm=${store.photoEntries().getOrNull(2)?.design?.overlay?.asset?.sha256?.take(8) ?: "<none>"}"
        }
    }

    // 每次打开都静默检查一次更新：清单读到比当前新的版本就弹窗（IO 线程，失败静默）。
    LaunchedEffect(Unit) {
        val current = com.framemark.native.appVersionLabel()
        pendingUpdate = com.framemark.native.product.update.UpdateLaunch.check(
            currentVersion = current,
            storage = com.framemark.native.shell.PlatformUpdateSkipStorage(),
        )
        FmLog.info("UPDATE_ON_LAUNCH") {
            "current=$current remote=${pendingUpdate?.versionName ?: "<none>"}"
        }
    }

    // The inspector model drives the export snapshot; building it here keeps the
    // export's theme/ratio/override values identical to the stage preview.
    val inspectorSnapshot = (inspectorState as? InspectorLoadState.Ready)?.snapshot
    val model = remember(selectedCard?.id, inspectorSnapshot) {
        val card = selectedCard
        val snapshot = inspectorSnapshot
        if (card != null && snapshot != null) InspectorModelFactory.build(card, snapshot) else null
    }
    // The 文字 tab lists renderer-backed rows by canvas position, so the panel
    // model has to carry the per-row controls for the current render.
    val panelModel = remember(model, textSlots.slots) { model?.withNativeTextSlots(textSlots.slots) }

    // P4 write-through: the session is a view of the active photo's design. Every
    // edit is addressed to the photo that is active *now*, so a photo switch cannot
    // re-key a snapshot onto the wrong photo (the old shell's 串图 defect).
    //
    // The write-back model is the panel model: only it carries the renderer-backed
    // text rows, so a `slotText:`/`slotOff:`/`slotScale:` edit becomes a value the
    // design owns (persisted, exported, re-applied) instead of being dropped as an
    // unknown consumer.
    val currentPanelModel by rememberUpdatedState(panelModel)
    // 撤销走的那条路必须读**当前**的品牌库快照：`LaunchedEffect(Unit)` 里捕获的
    // `cardOf` 是首次组合的那个闭包，那时 libraryState 还是 Loading（空列表），于是
    // 「撤销跨边框」永远解析不到卡片 —— 撤销**消耗了历史**却什么也不变（真机实测：
    // `FM_CARD_RESTORE card=glass-white-1 via=undo unresolved`）。见 [UndoCardTarget]。
    val undoCardTarget = remember { UndoCardTarget { currentCards } }
    // 当前会话里的值是从哪张照片读出来的。同照片的重新水合 = 换了边框（或套了预设 /
    // 样式），它**不能**清撤销栈 —— 换边框这一步就是用户要回去的那一步（P0-2）；
    // 真的换了照片才清，把一张照片的撤销结果搬到另一张上不是一回事。
    var hydratedPhotoId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        session.onValuesChanged = { _, overrides ->
            currentPanelModel?.let { store.writeActiveValues(it, styleOf(it.cardId), overrides) }
        }
        session.onExportChanged = { store.setExportSettings(it) }
        session.onCardRestored = { cardId ->
            // 撤销跨了边框：把选中边框指回去，剩下的写入由上面那个 effect 做。
            // 与「从工程里恢复边框」是同一条路，所以复用同一个证据标签。
            if (cardId == null) {
                selectedCard = null
                FmLog.info("FM_CARD_RESTORE") { "card=- via=undo 未选边框" }
            } else {
                val restored = undoCardTarget.resolve(cardId)
                if (restored != null) {
                    selectedCard = restored
                    FmLog.info("FM_CARD_RESTORE") { "card=${restored.id} via=undo" }
                } else {
                    FmLog.warn("FM_CARD_RESTORE") { "card=$cardId via=undo unresolved" }
                }
            }
        }
    }

    // Hydrate the editor from the project: card defaults + this photo's overrides.
    LaunchedEffect(panelModel?.cardId, store.activePhotoId, hydrateToken) {
        val built = currentPanelModel ?: return@LaunchedEffect
        val photoId = store.activePhotoId ?: return@LaunchedEffect
        val photoChanged = hydratedPhotoId != photoId
        hydratedPhotoId = photoId
        session.seed(built.controls)
        session.replaceValues(
            sessionValuesFor(built, store.designOf(photoId)),
            clearHistory = photoChanged,
        )
        session.setExportSilently(store.exportSettingsForUi())
        if (!exportHookApplied) {
            val ui = session.export
            val mapped = ui.withLaunchExportOverrides()
            if (mapped != ui) {
                exportHookApplied = true
                session.export = mapped
                FmLog.info("FM_EXPORT_LAUNCH_OVERRIDE") { "format=${mapped.format.label} size=${mapped.sizeMode} longEdge=${mapped.longEdge}" }
            }
        }
        FmLog.info("FM_PHOTO_DESIGN") {
            "load=$photoId card=${built.cardId} " +
                "wm=${store.overlayOf(photoId)?.asset?.sha256?.take(8) ?: "-"} values=${session.values.size}"
        }
    }

    // Rows arrive after the first render. Merge their stored values without
    // touching keys the editor already holds, so a report refresh cannot roll back
    // text being typed and cannot clear an existing edit or its undo step. Keyed on
    // the control identities, not on a count, so a different card with the same
    // number of rows still re-hydrates.
    LaunchedEffect(panelModel?.controls?.map { it.key }, store.activePhotoId) {
        val built = currentPanelModel ?: return@LaunchedEffect
        val photoId = store.activePhotoId ?: return@LaunchedEffect
        session.seed(built.controls)
        val stored = sessionValuesFor(built, store.designOf(photoId))
        val missing = stored.filterKeys { it !in session.values }
        if (missing.isNotEmpty()) {
            session.mergeMissingValues(missing)
            FmLog.info("FM_TEXT_ROWS_HYDRATE") { "photo=$photoId keys=${missing.size} total=${session.values.size}" }
        }
    }

    // Legacy presets (no watermark, session-keyed values) are folded into the
    // product document once, per card, as soon as the inspector data is available.
    LaunchedEffect(inspectorState, store.loaded) {
        if (inspectorState !is InspectorLoadState.Ready || !store.loaded) return@LaunchedEffect
        PresetStore.load()
        val legacy = PresetStore.items
        if (legacy.isEmpty()) return@LaunchedEffect
        val snapshot = (inspectorState as InspectorLoadState.Ready).snapshot ?: return@LaunchedEffect
        val converted = legacy.mapNotNull { item ->
            val card = cardOf(item.cardId) ?: return@mapNotNull null
            legacyToPreset(item, card.id, card.systemStyle, InspectorModelFactory.build(card, snapshot))
        }
        val migrated = store.importLegacyPresets(converted, expectedCount = legacy.size)
        if (migrated > 0) FmLog.info("FM_PRESET_MIGRATED") { "count=$migrated legacy_kept=${PresetStore.items.size}" }
    }

    // Evidence-only: open a specific card from FM_OPEN_CARD once the library is ready.
    LaunchedEffect(libraryState) {
        val id = LaunchOptions.openCardId ?: return@LaunchedEffect
        val ready = libraryState as? LibraryLoadState.Ready ?: return@LaunchedEffect
        if (autoOpened) return@LaunchedEffect
        val card = ready.snapshot.cards.firstOrNull { it.id == id }
        if (card != null) {
            // 证据钩子同样不注入样张（P0-1）：拿不到用户的照片就只是把边框选上，
            // 工程文档里一个 `photo:sample` 都不留。
            selectedCard = card
            autoOpened = true
            FmLog.info("FM_OPEN_CARD") { "${card.id} name=${card.name} photos=${store.photos.size}" }
        } else {
            FmLog.info("FM_OPEN_CARD_MISSING") { "$id" }
            autoOpened = true
        }
    }

    val anySheet = presetSheetOpen || exportSheetOpen || batchSheetOpen || photoBatchOpen || confirmApplyAll || pendingPhotoBatch != null
    fun openExport() {
        // P0-1 的第二半：样张没有文件，导出它写出来的是自带的山景照。这里先拒绝，
        // 给人话；「有照片但没有边框」仍然是另一句更具体的话。
        val refusal = store.exportRefusal()
        if (refusal != null) {
            watermarkError = refusal
        } else if (activeDesignSnapshot() == null) {
            watermarkError = "请先选择并应用边框，再导出这张照片。"
        } else {
            exportSheetOpen = true
        }
    }
    // Back order: the editor's own focus/parameter-page handlers compose later and
    // win first; here the tutorial closes (treated as 跳过), then sheets close, then
    // any edit tool falls back to 边框, then the page goes back one step
    // (编辑页 / 设置页 → 首页). On 首页 nothing is enabled, so the platform exits.
    BackHandler(
        enabled = onboardingOpen || anySheet || tool != EditorTool.Border || PhonePagePolicy.handlesBack(page),
    ) {
        when {
            // 返回键等于「跳过」：显式关掉了就别再每次启动都弹（与跳过按钮同一条路）。
            onboardingOpen -> closeOnboarding(skipped = true)
            pendingPhotoBatch != null -> pendingPhotoBatch = null
            confirmApplyAll -> confirmApplyAll = false
            photoBatchOpen -> photoBatchOpen = false
            presetSheetOpen -> presetSheetOpen = false
            exportSheetOpen -> exportSheetOpen = false
            batchSheetOpen -> batchSheetOpen = false
            tool != EditorTool.Border -> tool = EditorTool.Border
            else -> {
                val next = PhonePagePolicy.back(page) ?: return@BackHandler
                FmLog.info("FM_PHONE_PAGE") { "${page.name}->${next.name} viaBack" }
                page = next
            }
        }
    }

    val quickSavePreset: (String) -> Unit = { name ->
        val saved = store.savePreset(name)
        // Plain copy when the store reports nothing: a literal `FM-E-…` code in the
        // user-facing fallback is the exact artefact this audit removes.
        if (saved == null) watermarkError = store.presetError ?: "预设保存失败"
        hydrateToken++
    }

    fun selectPhoto(photo: TrayPhoto) {
        val id = trayToPhotoId(photo)
        // 换了照片，「已移除 · 撤销」这一拍就过去了：撤销要能看出放回到哪儿。
        removedPhoto = null
        // 先存后载：the outgoing design already lives in the project, so switching
        // persists the selection instead of copying a stale snapshot onto it.
        store.select(id)
        val current = PhotoSelection.path
        if (current != photo.path) {
            PhotoSelection.set(photo.path)
            FmLog.info("FM_TRAY_SELECT") { "${photo.label} id=$id" }
        }
        syncTray()
    }

    fun removePhoto(photo: TrayPhoto) {
        val id = trayToPhotoId(photo)
        // 删除是可逆的一步，不是确认框：原件留在 Originals，丢的是这张照片的设计。
        // 快照留给舞台那条约 4 秒的「已移除 · 撤销」用（与「已换边框 · 撤销」同一个提示位）。
        val snapshot = store.removeWithSnapshot(id)
        removedPhoto = snapshot
        val next = store.activePhoto
        if (store.photos.isEmpty()) {
            PhotoSelection.set(null)
            selectedCard = null
            tool = EditorTool.Border
            FmLog.info("FM_TRAY_EMPTY")
        } else {
            if (next != null && PhotoSelection.path != next.path) PhotoSelection.set(next.path)
            FmLog.info("FM_TRAY_REMOVE") { "photos=${store.photos.size} next=${next?.displayName}" }
        }
        syncTray()
    }

    /** 「已移除 · 撤销」：原样放回（同位置、同设计、同水印）并重新选上这张照片。 */
    fun undoRemovePhoto() {
        val snapshot = removedPhoto ?: return
        removedPhoto = null
        store.restoreRemoved(snapshot)
        store.activePhoto?.let { photo -> if (PhotoSelection.path != photo.path) PhotoSelection.set(photo.path) }
        syncTray()
    }

    /**
     * 「先选择照片」：挑边框 / 换边框之前必须有用户的照片。
     *
     * 有系统选片器就**直接打开它**（用户这一下的意图是「做一张图」，不是想看一句
     * 提示）；没有就明说，绝不退回「先塞一张示例照片」那条路。
     */
    fun requestPhotoFirst() {
        val picker = WorkflowEnv.photoPicker
        // 仍是「点了一张边框」这一路，只是去处换成了选片器（同一个证据标签）。
        FmLog.info("FM_OPEN_CARD") { "redirect=photo-picker picker=${if (picker == null) "none" else "open"} photos=${store.photos.size}" }
        if (picker != null) {
            picker()
        } else {
            watermarkError = "先选择一张照片，再挑边框。"
        }
    }

    /**
     * A border picked in the in-panel browser: apply it and stay in the browser,
     * so neighbouring styles can be compared without leaving the row.
     *
     * 空工程上**绝不**注入内置样张（UX 审查 P0-1）：以前这里调 `adoptSample`
     * 把 `PhotoRef(photo:sample)` 写进工程，于是「导出」出来的是应用自带的山景照。
     * 现在没有照片就把这一下转成「先选择照片」，选完之后用户再点边框。
     */
    fun openCardFromBrowser(card: LibraryCard) {
        if (tray.isEmpty) {
            requestPhotoFirst()
            return
        }
        selectedCard = card
        tool = EditorTool.Border
        FmLog.info("FM_OPEN_CARD") { "${card.id} name=${card.name} inPanel=true" }
    }

    CompositionLocalProvider(LocalTextEditFocus provides textEditFocus) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FmPalette.canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .focusRequester(shortcutFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
                if (textEditFocus.isEditing &&
                    TextEditKeys.belongsToTextEditor(event.key, ctrl = true, keyDown = true)
                ) {
                    // A focused text input owns undo/redo; the design history must not
                    // fire from inside a field.
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.Z -> {
                        val handled = if (event.isShiftPressed) session.redo() else session.undo()
                        if (handled) FmLog.info("FM_SHORTCUT") { "${if (event.isShiftPressed) "redo" else "undo"}" }
                        handled
                    }
                    Key.Y -> {
                        val handled = session.redo()
                        if (handled) FmLog.info("FM_SHORTCUT") { "redo" }
                        handled
                    }
                    else -> false
                }
            },
    ) {
        when (page) {
            PhonePage.Home -> PhoneHomeScreen(
                recent = recentEdits,
                presetCount = store.presets.size,
                onPickPhoto = {
                    if (phoneGallerySupported()) {
                        galleryOpen = true
                    } else {
                        WorkflowEnv.photoPicker?.invoke()
                    }
                },
                onOpenSettings = {
                    page = PhonePage.Settings
                    FmLog.info("FM_PHONE_PAGE") { "Home->Settings viaGear" }
                },
                onBatch = {
                    val destination = PhoneHomePolicy.batch(store.photos.size)
                    when (destination) {
                        PhoneHomeDestination.BatchExport -> openPhotoBatch()
                        PhoneHomeDestination.PickPhotos ->
                            WorkflowEnv.multiPhotoPicker?.invoke()
                                ?: WorkflowEnv.photoPicker?.invoke()
                        else -> Unit
                    }
                    FmLog.info("FM_HOME_BATCH") { "$destination photos=${store.photos.size}" }
                },
                onPresets = {
                    when (PhoneHomePolicy.presets(store.photos.size)) {
                        PhoneHomeDestination.Presets -> presetSheetOpen = true
                        else -> watermarkError = PhoneHomePolicy.PICK_PHOTOS_FIRST_HINT
                    }
                },
                onOpenRecent = { edit ->
                    val exists = store.photos.any { it.id == edit.photoId }
                    if (exists) {
                        store.select(edit.photoId)
                        syncTray()
                    }
                    page = PhonePagePolicy.afterOpenRecent(exists)
                    FmLog.info("FM_HOME_RECENT") { "photo=${edit.photoId} exists=$exists -> ${page.name}" }
                },
                // 演示稿的「全部」只是个演示链接；手机上全部照片就在编辑页的胶片条里，
                // 所以这里选中最上面那张并进编辑页，不做成一枚死按钮。
                onOpenAll = {
                    val newest = recentEdits.firstOrNull()
                    val exists = newest != null && store.photos.any { it.id == newest.photoId }
                    if (exists) {
                        store.select(newest!!.photoId)
                        syncTray()
                    }
                    page = PhonePagePolicy.afterOpenRecent(exists)
                    FmLog.info("FM_HOME_ALL") { "photos=${store.photos.size} exists=$exists -> ${page.name}" }
                },
            )

            PhonePage.Settings -> PhoneSettingsPage(
                errorText = NoticeText.human(store.saveError),
                onClose = {
                    page = PhonePagePolicy.back(PhonePage.Settings) ?: PhonePage.Home
                    FmLog.info("FM_PHONE_PAGE") { "Settings->${page.name} viaClose" }
                },
                onOpenAbout = {
                    page = PhonePage.About
                    FmLog.info("FM_PHONE_PAGE") { "Settings->About" }
                },
                onOpenOnboarding = {
                    onboardingPage = 0
                    onboardingOpen = true
                    FmLog.info(ONBOARDING_TAG) { "open from=settings" }
                },
            )

            PhonePage.About -> PhoneAboutPage(
                onClose = {
                    page = PhonePagePolicy.back(PhonePage.About) ?: PhonePage.Settings
                    FmLog.info("FM_PHONE_PAGE") { "About->${page.name} viaClose" }
                },
                onOpenOnboarding = {
                    onboardingPage = 0
                    onboardingOpen = true
                    FmLog.info(ONBOARDING_TAG) { "open from=about" }
                },
            )

            PhonePage.Editor -> PhoneEditorScreen(
                card = selectedCard,
                model = panelModel,
                activeDesign = store.activePhotoId
                    ?.let { store.designOf(it) }
                    ?.takeIf { it.cardId == selectedCard?.id }
                    ?.toDesignSnapshot(),
                inspectorState = inspectorState,
                libraryState = libraryState,
                session = session,
                tray = tray,
                tool = tool,
                onToolChange = { tool = it },
                onPickPhoto = { WorkflowEnv.photoPicker?.invoke() },
                onSelectPhoto = ::selectPhoto,
                onRemovePhoto = ::removePhoto,
                // 「已移除 · 撤销」：提示位由编辑器画，快照留在外壳（那里才拿得到 store）。
                removeNoticeLabel = removedPhoto?.ref?.displayName,
                onUndoRemove = ::undoRemovePhoto,
                onRemoveNoticeExpired = { removedPhoto = null },
                onOpenCard = ::openCardFromBrowser,
                onRetryLibrary = { libraryReload++ },
                onOpenPresets = { presetSheetOpen = true },
                onOpenExport = ::openExport,
                onUndo = { session.undo() },
                onRedo = { session.redo() },
                onRetryInspector = { inspectorReload++ },
                onQuickSavePreset = quickSavePreset,
                watermark = activeUiWatermark(),
                onWatermarkChange = { spec ->
                    val id = store.activePhotoId ?: return@PhoneEditorScreen
                    val next = store.projectWatermark(store.overlayOf(id), spec)
                    store.setOverlay(id, next)
                    if (spec == null) FmLog.info("FM_WM_CLEARED") { "photo=$id" }
                },
                onPickWatermark = { WorkflowEnv.watermarkPicker?.invoke() },
                onCropChange = { crop ->
                    // Same design write path as every other edit: PhotoDesign.params
                    // → commit → autosave, so the batch queue reads the same value.
                    val id = store.activePhotoId ?: return@PhoneEditorScreen
                    store.setCrop(id, crop)
                },
                customizedPhotoIds = tray.items
                    .filter { store.customized(trayToPhotoId(it)) }
                    .map { it.id }
                    .toSet(),
                onApplyAll = { confirmApplyAll = true },
                // 「把当前设计用到全部 N 张」的第二个入口在边框面板的当前边框那一行
                // （UX 审查 P1-3）；两处都走同一个动作 + 同一个确认弹窗。
                applyToAllCount = store.photos.size,
                textSlots = textSlots.slots,
                textOutputWidth = textSlots.width,
                textOutputHeight = textSlots.height,
                photoWidthPx = store.activePhoto?.widthPx ?: 0,
                photoHeightPx = store.activePhoto?.heightPx ?: 0,
                onTextSlots = textSlots::update,
                // 常用 → 本项目在用: every border a photo of this project already uses
                // (WP-L `LibraryIndex.common` expects these ids).
                inUseCardIds = store.photoEntries()
                    .mapNotNull { entry -> entry.design?.cardId }
                    .distinct(),
                // 返回 at the outermost layer leaves the editor for 首页; the inner
                // layers (keyboard / parameter page / focus / sheet) still win first.
                onRequestHome = {
                    val next = PhonePagePolicy.back(PhonePage.Editor) ?: PhonePage.Home
                    FmLog.info("FM_PHONE_PAGE") { "Editor->${next.name} viaTopBarBack" }
                    page = next
                },
            )
        }

        watermarkError?.let { message ->
            // Store errors carry an `FM-E-…` code; the pill shows the sentence only.
            NoticeText.human(message)?.let { text ->
                PhoneNotice(text = text, onClose = { watermarkError = null })
            }
        }

        if (photoBatchOpen) {
            PhoneOverlay(onDismiss = { photoBatchOpen = false }) {
                PhotoBatchScreen(
                    queue = batchQueue,
                    settings = session.export,
                    onClose = { photoBatchOpen = false },
                )
            }
        }

        PhoneGalleryOverlay(
            visible = galleryOpen,
            onCancel = { galleryOpen = false },
            onImport = { uris ->
                galleryOpen = false
                WorkflowEnv.uriImporter?.invoke(uris)
            },
        )

        pendingUpdate?.let { manifest ->
            com.framemark.native.shell.UpdatePromptDialog(
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

        if (confirmApplyAll) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmApplyAll = false },
                title = { androidx.compose.material3.Text("应用到全部照片？") },
                text = {
                    androidx.compose.material3.Text(
                        "会把当前照片的边框、文字与水印复制到全部 ${store.photos.size} 张照片，覆盖它们各自的修改。",
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        confirmApplyAll = false
                        applyDesignToAll()
                    }) { androidx.compose.material3.Text("应用") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmApplyAll = false }) {
                        androidx.compose.material3.Text("取消")
                    }
                },
            )
        }

        pendingPhotoBatch?.let { preflight ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingPhotoBatch = null },
                title = { androidx.compose.material3.Text("批量导出预检") },
                text = {
                    Column {
                        androidx.compose.material3.Text(
                            "有 ${preflight.undesignedCount} 张还没有应用边框，不会加入导出：${preflight.undesignedSummary}。",
                        )
                        androidx.compose.material3.TextButton(onClick = {
                            pendingPhotoBatch = null
                        }) { androidx.compose.material3.Text("去编辑") }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        pendingPhotoBatch = null
                        submitAndOpenPhotoBatch(preflight)
                    }) { androidx.compose.material3.Text("仅导出已设计的 ${preflight.designedCount} 张") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { pendingPhotoBatch = null }) {
                        androidx.compose.material3.Text("取消")
                    }
                },
            )
        }

        if (batchSheetOpen && batch != null) {
            PhoneOverlay(onDismiss = { batchSheetOpen = false }) {
                BatchScreen(
                    state = batchScroll,
                    runner = batch,
                    onClose = { batchSheetOpen = false },
                )
            }
        }

        // Secondary workflow surfaces sit above everything else.
        if (presetSheetOpen) {
            PhonePresetSheet(
                store = store,
                card = selectedCard,
                knownCardIds = libraryCards.mapTo(mutableSetOf()) { it.id },
                onApply = { preset ->
                    val styleId = cardOf(preset.design.cardId)?.systemStyle
                    if (store.applyPreset(preset, styleId)) {
                        val target = cardOf(preset.design.cardId)
                        if (target != null && target.id != selectedCard?.id) selectedCard = target
                        hydrateToken++
                        tool = EditorTool.Border
                    } else {
                        watermarkError = "FM-E-INPUT-001 当前照片无法套用这套预设"
                    }
                    presetSheetOpen = false
                },
                onDismiss = { presetSheetOpen = false },
            )
        }
        if (exportSheetOpen) {
            // WP-P2 §3.3: the phone's own sheet (preview / 格式 / 三档尺寸 / 质量 100 /
            // 保存到相册 + 分享 / 完成页重试). The desktop uses shell/desktop/ExportDialog.kt;
            // workflow/ExportSheet.kt was deleted in WP-FIX2 (zero call sites).
            PhoneExportSheet(
                session = session,
                card = selectedCard,
                design = activeDesignSnapshot(),
                overlay = { activeOverlay() },
                // 样张（没有文件）永远是 null：引擎拿到 null 会退到内置样张，所以这一层
                // 只能「不导出」，真正的拒绝在 `openExport()` 与表单的 `generate()` 里。
                photoPath = when (val activePhoto = store.activePhoto) {
                    null -> "" // Fail closed when no sample or real project photo is active.
                    else -> if (activePhoto.id == SAMPLE_PHOTO_ID) null else activePhoto.path
                },
                photoIndex = (store.photos.indexOfFirst { it.id == store.activePhotoId } + 1)
                    .coerceAtLeast(1),
                photoDisplayName = store.activePhoto?.displayName,
                // 表单上的「真实像素」读的是舞台这张照片的尺寸 + 这张边框声明的参数：
                // 与手机样式页「导出尺寸」同一份输入，所以两处印同一个数字。
                photoWidthPx = store.activePhoto?.widthPx ?: 0,
                photoHeightPx = store.activePhoto?.heightPx ?: 0,
                controls = panelModel?.controls.orEmpty(),
                isSamplePhoto = store.activePhotoId == SAMPLE_PHOTO_ID,
                // §3.4: 应用到全部照片 moved here from the removed stage menu; the
                // project already owns `applyActiveDesignToAll`, so no new engine work.
                canApplyToAll = store.photos.size > 1,
                onApplyToAll = { confirmApplyAll = true },
                // Same evidence hook the previous sheet carried (`fmRunExport`).
                autoGenerate = LaunchOptions.runSampleExport,
                onDismiss = { exportSheetOpen = false },
            )
        }

        // 新手教程盖在所有东西之上（包括上面那些弹层），关掉之后下面的页面原样还在。
        if (onboardingOpen) {
            OnboardingOverlay(
                page = onboardingPage,
                onNext = { onboardingPage = (onboardingPage + 1).coerceAtMost(OnboardingText.PAGES.lastIndex) },
                onSkip = { closeOnboarding(skipped = true) },
                onDone = { closeOnboarding(skipped = false) },
            )
        }
    }
    }
}

/**
 * Border the editor opens with: the old app's 经典 · 17 first, then 经典 · 22
 * (same family, wired to the Android renderer), then any engine-wired card.
 */
private val DEFAULT_CARD_PREFERENCE = listOf(
    "stock-xiaomi-classic-17",
    "stock-xiaomi-classic-22",
)

@Composable
private fun PhoneNotice(text: String, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = FmSpacing.contentBottom),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = FmSpacing.screen)
                .clip(RoundedCornerShape(FmRadii.card))
                .background(FmPalette.surface)
                .border(1.dp, FmPalette.outlineStrong, RoundedCornerShape(FmRadii.card))
                .padding(FmSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = FmType.bodyMedium,
                color = FmPalette.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "知道了",
                style = FmType.labelMedium,
                color = FmPalette.onSurfaceSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.surfaceMuted)
                    .clickable(onClick = onClose)
                    .padding(horizontal = FmSpacing.md, vertical = FmSpacing.xs),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Building blocks shared with the batch page
// ---------------------------------------------------------------------------

@Composable
internal fun PageList(
    state: LazyListState,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = FmSpacing.screen,
            end = FmSpacing.screen,
            top = FmSpacing.xxl + FmSpacing.xs,
            bottom = FmSpacing.contentBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(FmSpacing.lg),
        content = content,
    )
}

@Composable
internal fun TitleBlock(title: String, subtitle: String) {
    Column {
        Text(title, style = FmType.titleLarge, color = FmPalette.onSurface)
        Spacer(Modifier.height(FmSpacing.xs + FmSpacing.xxs))
        Text(subtitle, style = FmType.bodyMedium, color = FmPalette.onSurfaceMuted)
    }
}

@Composable
internal fun HintCard(text: String) {
    Text(
        text = text,
        style = FmType.bodyMedium,
        color = FmPalette.onSurfaceMuted,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.card))
            .background(FmPalette.surfaceMuted)
            .padding(FmSpacing.lg),
    )
}

@Composable
internal fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    onSurface: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(if (onSurface) FmPalette.surface.copy(alpha = 0.86f) else FmPalette.surfaceMuted)
            .padding(horizontal = FmSpacing.sm, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = FmType.labelSmall,
            color = if (onSurface) FmPalette.onSurfaceSecondary else FmPalette.onSurfaceMuted,
            maxLines = 1,
        )
    }
}

