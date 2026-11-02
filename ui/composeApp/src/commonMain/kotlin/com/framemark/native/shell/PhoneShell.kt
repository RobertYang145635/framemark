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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryDataSource
import com.framemark.native.library.LibraryFilterState
import com.framemark.native.library.LibraryLoadState
import com.framemark.native.library.LibraryScreen
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.BatchRunner
import com.framemark.native.workflow.BatchCandidate
import com.framemark.native.workflow.BatchPreflight
import com.framemark.native.workflow.batchPreflight
import com.framemark.native.workflow.BatchScreen
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.ExportSheet
import com.framemark.native.workflow.NativeEngine
import com.framemark.native.workflow.OverlaySpec
import com.framemark.native.workflow.PhotoBatchScreen
import com.framemark.native.workflow.PhonePhotoBatchQueue
import com.framemark.native.workflow.PhotoSelection
import com.framemark.native.workflow.PhotoTray
import com.framemark.native.workflow.PreviewGeometry
import com.framemark.native.workflow.PresetStore
import com.framemark.native.workflow.TrayPhoto
import com.framemark.native.workflow.WatermarkSelection
import com.framemark.native.workflow.WatermarkSpec
import com.framemark.native.workflow.WorkflowEnv

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
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PhoneShell() {
    val filters = remember { LibraryFilterState() }
    val libraryGrid = remember { LazyGridState() }
    val panelState = rememberLazyListState()
    val batchScroll = rememberLazyListState()
    val settingsScroll = rememberLazyListState()
    // P4: product.Project is the authority for photos, one design per photo, the
    // embedded watermarks and the export settings, and SessionStore writes it
    // atomically — a force-stop restores all of it. The tray below is a projection.
    val store = remember {
        PhoneProjectRuntime.ensure {
            PhoneProjectStore(InMemoryProductFileSystem(), null, WorkflowEnv.platform)
        }
    }
    LaunchedEffect(Unit) { store.load() }
    val tray = remember { PhotoTray() }
    // One design session (values + undo history) hydrated from the active design.
    val session = remember { DesignSession() }
    var photoBatchOpen by remember { mutableStateOf(false) }
    var confirmApplyAll by remember { mutableStateOf(false) }
    // M01 rule 2: 「按照片批量」预检——有未应用边框的照片时先确认，再入队。
    var pendingPhotoBatch by remember { mutableStateOf<BatchPreflight?>(null) }
    var watermarkError by remember { mutableStateOf<String?>(null) }
    // A watermark picked before any border exists yet; applied once the card does.
    var pendingWatermark by remember { mutableStateOf<String?>(null) }
    // Home「先逛边框」: picking a card drops the sample photo into the editor.
    var browsingBorders by remember { mutableStateOf(false) }
    var pairDemoSeeded by remember { mutableStateOf(false) }
    val batch = remember(store, LaunchOptions.openBatch) {
        createPhoneBatchDemoRunner(LaunchOptions.openBatch) {
            BatchRunner(store.fileSystem, store.batchInputs())
        }
    }
    val shortcutFocus = remember { FocusRequester() }
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

    var tool by rememberSaveable { mutableStateOf(EditorTool.Border) }
    var tab by rememberSaveable { mutableStateOf(InspectorSection.Logo) }
    var librarySheetOpen by remember { mutableStateOf(false) }
    var presetSheetOpen by remember { mutableStateOf(false) }
    var exportSheetOpen by remember { mutableStateOf(false) }
    var batchSheetOpen by remember { mutableStateOf(false) }
    var settingsSheetOpen by remember { mutableStateOf(false) }

    val libraryCards = (libraryState as? LibraryLoadState.Ready)?.snapshot?.cards.orEmpty()

    fun cardOf(id: String?): LibraryCard? = libraryCards.firstOrNull { it.id == id }

    /** The engine style id of a card; the design must carry it or the renderer picks the wrong style. */
    fun styleOf(id: String?): String? = cardOf(id)?.systemStyle

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
            println("FM_QUEUE_AUTOSTART pending=${batchQueue.pendingCount}")
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
        return store.uiWatermark(id, aspect)?.toOverlay(aspect)
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
        println("FM_APPLY_ALL photos=${store.photos.size} card=${store.activeDesign?.cardId} wm=${store.activeDesign?.overlay?.asset?.sha256?.take(8) ?: "<none>"}")
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
        println(
            "FM_PHOTO_BATCH open photos=${store.photos.size} selected=${report.selected} " +
                "added=${report.added} duplicate=${report.duplicates} " +
                "undesigned=${report.undesigned} rejected=${report.rejected.size} " +
                "reconciled=${report.reconciled} rows=${batchQueue.rows().size}",
        )
    }

    // The project drives the strip; every path list change re-projects it.
    LaunchedEffect(store.photos, store.activePhotoId, libraryState) { syncTray() }

    // A pick (single or multi) lands asynchronously from the system picker; the
    // project registers every path of the batch, the strip follows.
    LaunchedEffect(PhotoSelection.version) {
        if (PhotoSelection.version == 0) return@LaunchedEffect
        val path = PhotoSelection.path ?: return@LaunchedEffect
        store.adoptPaths(PhotoSelection.batch, path, PhotoSelection.displayNamesByPath)
        println("FM_TRAY photos=${tray.count} picked=${PhotoSelection.batch.size} selected=${tray.selected?.label}")
    }

    // The engine always renders the active photo, so a restore points it there.
    LaunchedEffect(store.activePhotoId) {
        val path = store.activePhoto?.path?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (PhotoSelection.path != path) {
            PhotoSelection.set(path)
            println("FM_PROJECT_PHOTO $path")
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
        println(
            "FM_WATERMARK_APPLY photo=$photoId path=$path pending=${pendingWatermark != null} " +
                "wm=${store.overlayOf(photoId)?.asset?.sha256?.take(12) ?: "<none>"} err=${error?.code ?: "none"}",
        )
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
        println("FM_BATCH_DEMO items=${demoBatch.items.size} pending=${demoBatch.hasPending} reset=${LaunchOptions.batchReset}")
    }

    // Evidence-only: open a workflow sheet once the editor model is ready (so the
    // demo edits and the seeded preset have already happened).
    LaunchedEffect(selectedCard?.id, store.activeDesign?.cardId, inspectorState) {
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
                if (activeDesignSnapshot() != null) {
                    exportSheetOpen = true
                    LaunchOptions.sheetsApplied = true
                    println("FM_OPEN_SHEET export card=${card.id}")
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
            println(
                "FM_LIB_READY cards=${snapshot.cards.size} categories=${snapshot.categories.size} " +
                    "previews=${snapshot.mappedPreviews} unmapped=${snapshot.unmappedPreviewIds.size} " +
                    "generatedAt=${snapshot.generatedAt}",
            )
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
            println(
                "FM_INSPECTOR_READY cards=${result.snapshot.capabilityCards} " +
                    "audited=${result.snapshot.auditedCards} platformRows=${result.snapshot.platformRowCount} " +
                    "metaRecords=${result.snapshot.metaRecords} metaControls=${result.snapshot.metaControls}",
            )
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
            println("FM_CARD_RESTORE no_design photo=${store.activePhotoId ?: "-"}; waiting for explicit card selection")
            return@LaunchedEffect
        }
        val wanted = cards.firstOrNull { it.id == designCard }
        println(
            "FM_CARD_RESTORE design=${if (store.activeDesign == null) "none" else "own"} " +
                "designCard=${designCard ?: "-"} projectCard=${store.project.selectedCardId ?: "-"} " +
                "resolved=${wanted?.id ?: "-"} cards=${cards.size} active=${store.activePhotoId?.takeLast(22) ?: "-"}",
        )
        if (wanted != null) {
            if (wanted.id != selectedCard?.id) {
                selectedCard = wanted
                println("FM_PROJECT_CARD_RESTORE card=${wanted.id} photo=${store.activePhotoId}")
            }
            return@LaunchedEffect
        }
        // The photo does have a design but no card can be resolved for it. Assigning
        // the built-in default here would write that default over the user's saved
        // design a moment later, so stand down: 宁可不改不存.
        if (store.activeDesign != null) {
            val near = cards.filter { designCard != null && it.id.take(20) == designCard.take(20) }.map { it.id }.take(3)
            println(
                "FM_CARD_RESTORE_UNRESOLVED keep=$designCard keepLen=${designCard?.length ?: 0} " +
                    "exact=${cards.count { it.id == designCard }} libCount=${libraryCards.size} " +
                    "near=$near first3=${cards.take(3).map { it.id }}",
            )
            return@LaunchedEffect
        }
        selectedCard = null
        println("FM_CARD_RESTORE_UNRESOLVED no card matched saved photo design=$designCard")
    }

    // Border changes are not undoable; the active photo owns the card it shows.
    LaunchedEffect(selectedCard?.id, store.activePhotoId) {
        session.onCardChanged()
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
            println("FM_WM_PENDING_APPLIED photo=$photoId sha=${store.overlayOf(photoId)?.asset?.sha256?.take(12) ?: "-"}")
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
        println(
            "FM_PAIR_DEMO photos=${store.photos.size} A=${c1.id} B=${c2.id} C=${c1.id} " +
                "wm=${store.photoEntries().getOrNull(2)?.design?.overlay?.asset?.sha256?.take(8) ?: "<none>"}",
        )
    }

    // The inspector model drives the export snapshot; building it here keeps the
    // export's theme/ratio/override values identical to the stage preview.
    val inspectorSnapshot = (inspectorState as? InspectorLoadState.Ready)?.snapshot
    val model = remember(selectedCard?.id, inspectorSnapshot) {
        val card = selectedCard
        val snapshot = inspectorSnapshot
        if (card != null && snapshot != null) InspectorModelFactory.build(card, snapshot) else null
    }

    // P4 write-through: the session is a view of the active photo's design. Every
    // edit is addressed to the photo that is active *now*, so a photo switch cannot
    // re-key a snapshot onto the wrong photo (the old shell's 串图 defect).
    val currentModel by rememberUpdatedState(model)
    LaunchedEffect(Unit) {
        session.onValuesChanged = { _, overrides ->
            currentModel?.let { store.writeActiveValues(it, styleOf(it.cardId), overrides) }
        }
        session.onExportChanged = { store.setExportSettings(it) }
    }

    // Hydrate the editor from the project: card defaults + this photo's overrides.
    LaunchedEffect(model?.cardId, store.activePhotoId, hydrateToken) {
        val built = model ?: return@LaunchedEffect
        val photoId = store.activePhotoId ?: return@LaunchedEffect
        session.seed(built.controls)
        session.replaceValues(sessionValuesFor(built, store.designOf(photoId)))
        session.setExportSilently(store.exportSettingsForUi())
        if (!exportHookApplied) {
            val ui = session.export
            val mapped = ui.withLaunchExportOverrides()
            if (mapped != ui) {
                exportHookApplied = true
                session.export = mapped
                println("FM_EXPORT_LAUNCH_OVERRIDE format=${mapped.format.label} size=${mapped.sizeMode} longEdge=${mapped.longEdge}")
            }
        }
        println(
            "FM_PHOTO_DESIGN load=$photoId card=${built.cardId} " +
                "wm=${store.overlayOf(photoId)?.asset?.sha256?.take(8) ?: "-"} values=${session.values.size}",
        )
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
        if (migrated > 0) println("FM_PRESET_MIGRATED count=$migrated legacy_kept=${PresetStore.items.size}")
    }

    // Evidence-only: open a specific card from FM_OPEN_CARD once the library is ready.
    LaunchedEffect(libraryState) {
        val id = LaunchOptions.openCardId ?: return@LaunchedEffect
        val ready = libraryState as? LibraryLoadState.Ready ?: return@LaunchedEffect
        if (autoOpened) return@LaunchedEffect
        val card = ready.snapshot.cards.firstOrNull { it.id == id }
        if (card != null) {
            selectedCard = card
            if (tray.isEmpty) store.adoptSample()
            autoOpened = true
            println("FM_OPEN_CARD ${card.id} name=${card.name}")
        } else {
            println("FM_OPEN_CARD_MISSING $id")
            autoOpened = true
        }
    }

    val anySheet = librarySheetOpen || presetSheetOpen || exportSheetOpen || batchSheetOpen || settingsSheetOpen || photoBatchOpen || confirmApplyAll || pendingPhotoBatch != null
    fun openExport() {
        if (activeDesignSnapshot() == null) {
            watermarkError = "请先选择并应用边框，再导出这张照片。"
        } else {
            exportSheetOpen = true
        }
    }
    BackHandler(enabled = anySheet || tool == EditorTool.Watermark) {        when {
            pendingPhotoBatch != null -> pendingPhotoBatch = null
            confirmApplyAll -> confirmApplyAll = false
            photoBatchOpen -> photoBatchOpen = false
            librarySheetOpen -> librarySheetOpen = false
            presetSheetOpen -> presetSheetOpen = false
            exportSheetOpen -> exportSheetOpen = false
            batchSheetOpen -> batchSheetOpen = false
            settingsSheetOpen -> settingsSheetOpen = false
            else -> tool = EditorTool.Border
        }
    }

    val quickSavePreset: (String) -> Unit = { name ->
        val saved = store.savePreset(name)
        if (saved == null) watermarkError = store.presetError ?: "FM-E-PRESET-001 预设保存失败"
        hydrateToken++
    }

    fun selectPhoto(photo: TrayPhoto) {
        val id = trayToPhotoId(photo)
        // 先存后载：the outgoing design already lives in the project, so switching
        // persists the selection instead of copying a stale snapshot onto it.
        store.select(id)
        val current = PhotoSelection.path
        if (current != photo.path) {
            PhotoSelection.set(photo.path)
            println("FM_TRAY_SELECT ${photo.label} id=$id")
        }
        syncTray()
    }

    fun removePhoto(photo: TrayPhoto) {
        val id = trayToPhotoId(photo)
        store.remove(id)
        val next = store.activePhoto
        if (store.photos.isEmpty()) {
            PhotoSelection.set(null)
            selectedCard = null
            tool = EditorTool.Border
            println("FM_TRAY_EMPTY")
        } else {
            if (next != null && PhotoSelection.path != next.path) PhotoSelection.set(next.path)
            println("FM_TRAY_REMOVE photos=${store.photos.size} next=${next?.displayName}")
        }
        syncTray()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FmPalette.canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .focusRequester(shortcutFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Z -> {
                        val handled = if (event.isShiftPressed) session.redo() else session.undo()
                        if (handled) println("FM_SHORTCUT ${if (event.isShiftPressed) "redo" else "undo"}")
                        handled
                    }
                    Key.Y -> {
                        val handled = session.redo()
                        if (handled) println("FM_SHORTCUT redo")
                        handled
                    }
                    else -> false
                }
            },
    ) {
        if (tray.isEmpty) {
            PhoneHomeScreen(
                libraryState = libraryState,
                onPickPhoto = { WorkflowEnv.photoPicker?.invoke() },
                onPickMany = WorkflowEnv.multiPhotoPicker,
                onUseSample = {
                    store.adoptSample()
                    PhotoSelection.set(null)
                    println("FM_TRAY_SAMPLE")
                },
                onBrowseBorders = {
                    browsingBorders = true
                    librarySheetOpen = true
                    println("FM_BROWSE_BORDERS open")
                },
            )
        } else {
            PhoneEditorScreen(
                card = selectedCard,
                inspectorState = inspectorState,
                session = session,
                tray = tray,
                tool = tool,
                onToolChange = { tool = it },
                tab = tab,
                onTabChange = { tab = it },
                onPickPhoto = { WorkflowEnv.photoPicker?.invoke() },
                onSelectPhoto = ::selectPhoto,
                onRemovePhoto = ::removePhoto,
                onOpenLibrary = {
                    librarySheetOpen = true
                    println("FM_OPEN_SHEET library")
                },
                onOpenPresets = { presetSheetOpen = true },
                onOpenExport = ::openExport,
                onOpenBatch = { batchSheetOpen = true },
                onOpenSettings = { settingsSheetOpen = true },
                onUndo = { session.undo() },
                onRedo = { session.redo() },
                onRetryInspector = { inspectorReload++ },
                onQuickSavePreset = quickSavePreset,
                panelState = panelState,
                watermark = activeUiWatermark(),
                onWatermarkChange = { spec ->
                    val id = store.activePhotoId ?: return@PhoneEditorScreen
                    val next = store.projectWatermark(store.overlayOf(id), spec)
                    store.setOverlay(id, next)
                    if (spec == null) println("FM_WM_CLEARED photo=$id")
                },
                onPickWatermark = { WorkflowEnv.watermarkPicker?.invoke() },
                customizedPhotoIds = tray.items
                    .filter { store.customized(trayToPhotoId(it)) }
                    .map { it.id }
                    .toSet(),
                onApplyAll = { confirmApplyAll = true },
                onOpenPhotoBatch = {
                    val preflight = store.project.batchPreflight(store.photos.map { it.id })
                    when {
                        preflight.allUndesigned -> {
                            pendingPhotoBatch = null
                            watermarkError = "还没有为任何照片应用边框，未创建导出任务。请先在编辑页选择边框。"
                        }
                        preflight.hasUndesigned -> {
                            pendingPhotoBatch = preflight
                        }
                        else -> {
                            pendingPhotoBatch = null
                            submitAndOpenPhotoBatch()
                        }
                    }
                    println(
                        "FM_PHOTO_BATCH_PREFLIGHT photos=${store.photos.size} designed=${preflight.designedCount} " +
                            "undesigned=${preflight.undesignedCount}",
                    )
                },
            )
        }

        watermarkError?.let { message ->
            PhoneNotice(text = message, onClose = { watermarkError = null })
        }

        if (librarySheetOpen) {
            PhoneOverlay(onDismiss = { librarySheetOpen = false }) {
                LibraryScreen(
                    loadState = libraryState,
                    filters = filters,
                    gridState = libraryGrid,
                    onRetry = { libraryReload++ },
                    onOpenCard = { card ->
                        selectedCard = card
                        librarySheetOpen = false
                        tool = EditorTool.Border
                        if (tray.isEmpty) {
                            store.adoptSample()
                            PhotoSelection.set(null)
                            println("FM_TRAY_SAMPLE browse")
                        }
                        println("FM_OPEN_CARD ${card.id} name=${card.name} browse=$browsingBorders")
                        browsingBorders = false
                    },
                    headerTrailing = { CloseChip { librarySheetOpen = false } },
                    selectedCardId = selectedCard?.id,
                    sheet = true,
                )
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

        if (settingsSheetOpen) {
            PhoneOverlay(onDismiss = { settingsSheetOpen = false }) {
                Column(modifier = Modifier.fillMaxSize()) {
                    OverlayHeader(
                        title = "设置",
                        subtitle = "FrameMark 0.1.0",
                        onClose = { settingsSheetOpen = false },
                    )
                    SettingsContent(state = settingsScroll, session = session, store = store)
                }
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
            ExportSheet(
                session = session,
                card = selectedCard,
                autoGenerate = LaunchOptions.runSampleExport,
                design = activeDesignSnapshot(),
                overlay = { activeOverlay() },
                photoPath = when (val activePhoto = store.activePhoto) {
                    null -> "" // Fail closed when no sample or real project photo is active.
                    else -> if (activePhoto.id == SAMPLE_PHOTO_ID) null else activePhoto.path
                },
                photoIndex = (store.photos.indexOfFirst { it.id == store.activePhotoId } + 1)
                    .coerceAtLeast(1),
                photoDisplayName = store.activePhoto?.displayName,
                onDismiss = { exportSheetOpen = false },
            )
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

@Composable
private fun CloseChip(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("✕", style = FmType.labelMedium, color = FmPalette.onSurfaceSecondary)
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

// ---------------------------------------------------------------------------
// Settings (overflow menu → full-screen sheet)
// ---------------------------------------------------------------------------

@Composable
private fun SettingsContent(state: LazyListState, session: DesignSession, store: PhoneProjectStore) {
    val export = session.export
    val project = store.project
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = FmSpacing.xxl,
            end = FmSpacing.xxl,
            top = FmSpacing.sm,
            bottom = FmSpacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(FmSpacing.sm),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
                SettingsSectionLabel("外观")
                SettingsRow("主题", "浅色")
                SettingsRow("字号", "跟随系统")
                SettingsSectionLabel("导出")
                SettingsRow("格式", export.format.label)
                // The native encoder writes JPEG at q100 4:4:4 regardless of the
                // quality control, so the settings page says what will be produced.
                SettingsRow("画质", if (export.format.label == "JPG") "固定 q100 (4:4:4)" else "—")
                SettingsRow(
                    "尺寸",
                    if (export.sizeMode == com.framemark.native.workflow.ExportSizeMode.Original) {
                        "原尺寸"
                    } else {
                        "长边 ${export.longEdge} px"
                    },
                )
                SettingsSectionLabel("存储")
                SettingsRow("照片", "${project.photos.size} 张 · 设计 ${project.designs.size} 套")
                SettingsRow("预设", "${store.presets.size} 套")
                SettingsRow("工程落盘", if (store.saveCount > 0) "已写 ${store.saveCount} 次" else "未写入")
                store.saveError?.let { SettingsRow("写入错误", it) }
                SettingsRow("最近导出", session.lastExport?.fileName ?: "无")
                SettingsSectionLabel("关于")
                SettingsRow("版本", com.framemark.native.appVersionLabel())
                SettingsRow("构建", "Compose MP · Desktop/Android")
            }
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        style = FmType.labelMedium,
        color = FmPalette.onSurfaceMuted,
        modifier = Modifier.padding(top = FmSpacing.md, bottom = FmSpacing.xxs),
    )
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(FmRadii.card))
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.card))
            .padding(horizontal = FmSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = FmType.bodyLarge, color = FmPalette.onSurface)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = FmType.bodyMedium,
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(200.dp),
        )
    }
}
