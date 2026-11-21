package com.framemark.native.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.produceState
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
import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.PhoneLogoPickerOverlay
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
import com.framemark.native.product.PhotoCrop
import com.framemark.native.product.PhotoCropParams
import com.framemark.native.product.PhotoOrientation
import com.framemark.native.product.photoSourceSize
import com.framemark.native.shell.phone.PhoneBorderLibraryOverlay
import com.framemark.native.shell.phone.PhoneCropUi
import com.framemark.native.shell.phone.PhoneEditorTopBar
import com.framemark.native.shell.phone.PhoneParameterPage
import com.framemark.native.shell.phone.phoneLibraryTraversal
import com.framemark.native.shell.phone.phoneLoadRecentFrames
import com.framemark.native.shell.phone.phonePageControls
import com.framemark.native.shell.phone.phoneRecordRecentFrame
import com.framemark.native.shell.phone.phoneSwipeStep
import com.framemark.native.shell.phone.phoneToolTab
import com.framemark.native.shell.phone.phoneToolsFor
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmSpring
import com.framemark.native.theme.FmType
import com.framemark.native.theme.fmOverlayEnter
import com.framemark.native.theme.fmOverlayExit
import com.framemark.native.theme.fmSpringSpec
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.PhotoTray
import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.TrayPhoto
import com.framemark.native.workflow.WatermarkSpec
import com.framemark.native.workflow.WorkflowEnv
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.framemark.native.product.FmLog
import com.framemark.native.shell.phone.photoIndexOf
import com.framemark.native.shell.phone.panelTitle
import com.framemark.native.shell.phone.StageArea
import com.framemark.native.shell.phone.PreviewGeo
import com.framemark.native.shell.phone.PhotoStrip
import com.framemark.native.shell.phone.PhoneSheetHost
import com.framemark.native.shell.phone.PhoneSheetPagePolicy
import com.framemark.native.shell.phone.ParameterPageHeader
import com.framemark.native.shell.phone.cropApplies

/**
 * V2 phone editor, photo first (report §7): a dark 52 dp top bar (返回 · 照片切换 ·
 * 撤销 / 重做 · 导出), the photo stage taking every remaining dp with the border
 * name and 按住看原图 floating over it, the two-level parameter sheet, and the
 * 62 dp five-tool bar (边框 / 样式 / 文字 / Logo / 水印, dots from WP-C's page model)
 * with a trailing 预设 action that opens the preset sheet on any tool.
 *
 * The border tool is the WP-L driven panel (`PhoneBorderPanel`): the current border
 * plus one action, never the library itself. The library is an overlay above the
 * editor (`PhoneBorderLibraryOverlay`), opened only by that action, so the canvas
 * stays on screen while the user browses. The four page tools render WP-C's pages
 * ([PhoneParameterPage]). Design values still flow through [session] → the shell's
 * write-back: this file changes layout and routing only, never persistence.
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
    /** 「已移除 · 撤销」的题目（刚删掉那张的文件名）；null = 提示位空着。 */
    removeNoticeLabel: String? = null,
    onUndoRemove: () -> Unit = {},
    /** 4 秒到了 / 用户换了照片：把快照丢掉，这条就不再可撤销。 */
    onRemoveNoticeExpired: () -> Unit = {},
    /** Border browser tap: applies the card and keeps the browser open. */
    onOpenCard: (LibraryCard) -> Unit,
    onRetryLibrary: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenExport: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRetryInspector: () -> Unit,
    onQuickSavePreset: (String) -> Unit,
    watermark: WatermarkSpec? = null,
    onWatermarkChange: (WatermarkSpec?) -> Unit = {},
    onPickWatermark: () -> Unit = {},
    /**
     * The photo's chosen crop, written into the design through the same store path
     * the other design edits use (so autosave and the batch queue see it). Only
     * called on 完成, never per gesture sample.
     */
    onCropChange: (PhotoCrop) -> Unit = {},
    customizedPhotoIds: Set<Int> = emptySet(),
    /**
     * 「把当前设计用到全部 N 张」的同一个动作：导出表单里一处、边框面板的当前边框
     * 那一行右侧一处（UX 审查 P1-3）。null 表示这张工程只有一张照片，入口不画。
     */
    onApplyAll: (() -> Unit)? = null,
    /** 工程里的照片总数，只用来写入口那枚按钮的「全部 N 张」。 */
    applyToAllCount: Int = 0,
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
    // 裁切: the pending crop is UI state until 完成 writes it into the design, so a
    // drag can neither spam the project nor leave a half-edited document behind.
    var cropActive by remember { mutableStateOf(false) }
    var pendingCrop by remember { mutableStateOf(PhotoCrop.Default) }
    // V2 sheet: three stops — 收起 (0, the card strip), 半开 (1) and 展开 (2, the grid).
    // WP-X3 §5: the demo's `.psheet` is a freely dragged `height: var(--sh)`, so the
    // stop count is ours to pick; the task book asks for three (WP-F2 D3 measured
    // only two stable heights). Index instead of a Boolean so the drag snap and the
    // chevron cannot disagree about "which of the three am I on".
    var sheetLevel by rememberSaveable { mutableStateOf(0) }
    var sheetDragPx by remember { mutableStateOf<Float?>(null) }
    var photosOpen by rememberSaveable { mutableStateOf(false) }
    // 照片条的滚动位置：跨收起/展开与键盘开关保持（WP-Q2⑥ 摩擦二）。
    val photoStripScroll = androidx.compose.foundation.rememberScrollState()
    var focusMode by rememberSaveable { mutableStateOf(false) }
    // §2.8: stable TextSlot key of the row being edited. UI only — never written
    // into the project, a preset, a cache key or an export request.
    var focusedTextSlotKey by remember { mutableStateOf<String?>(null) }
    var fullParametersDismissed by rememberSaveable { mutableStateOf(false) }
    var fullParametersManuallyOpen by rememberSaveable { mutableStateOf(false) }
    // 品牌选择器是「盖在画布上的一层」，不是新页面：它必须画在整个编辑器之上
    // （底板只会画在自己的高度里），所以开关由编辑器持有，Logo 页只负责请求打开。
    // 舞台顶部留在遮罩下，用户挑品牌时仍看得见画布。
    var logoPickerOpen by remember { mutableStateOf(false) }
    // 换照片 / 换工具时选择器不再属于这一拍：收起来，绝不把它带到别的页面上。
    LaunchedEffect(model?.cardId, tool) { logoPickerOpen = false }
    // 边框库覆盖层和品牌选择器是同一层的东西：画在整个编辑器之上（底板只会画在自己的
    // 高度里），所以开关由编辑器持有，边框面板只负责发请求。边框页的落地态是面板，
    // 库只有点「选择一个边框 / 换一个边框」才出现——工具切换、返回、系统返回都不开它。
    var borderLibraryOpen by remember { mutableStateOf(false) }
    // Border sheet browsing state, kept across tool switches (showcase `ftab`).
    var borderCategory by rememberSaveable { mutableStateOf(LibraryIndex.CATEGORY_COMMON) }
    var borderQuery by rememberSaveable { mutableStateOf("") }
    var recentFrames by remember { mutableStateOf(emptyList<String>()) }
    // A-08: one short "已撤销" notice with its 重做 action after an undo.
    var undoNotice by remember { mutableStateOf(false) }
    // P0-2: 换边框现在也是一条可撤销的历史，换完在同一个提示位说一句「已换边框 · 撤销」。
    var borderNotice by remember { mutableStateOf(false) }
    // 缺参数占位是否还画（2026-11-20 用户定）：**按照片**记「看过了」——进过一次
    // 「文字」页就撤掉；但**换照片或换边框都会重置**，所以同一张照片换到别的边框再换
    // 回来，占位仍然出现（那时用户面对的是另一套排版，需要重新看见缺口）。
    // `remember(photoId, cardId)` 正好就是这条语义：任一变化 → 回到「没看过」。
    var exifHintsAcknowledged by remember(tray.selectedId, card?.id) { mutableStateOf(false) }
    val exifHintsVisible = !exifHintsAcknowledged && textSlots.isNotEmpty()
    // 缺值行的示例文案（标准参数档）：按文字页的角色取，与探针渲染用的是同一份。
    val exifSamples = remember(textSlots, textOutputWidth, textOutputHeight) {
        val roles = com.framemark.native.inspector.textSlotNavEntries(
            textSlots,
            textOutputWidth,
            textOutputHeight,
        ).associate { it.navKey to it.role }
        val declared = card?.declaredText
        com.framemark.native.inspector.ExifGapNotice.missingFields(textSlots)
            .mapNotNull { slot ->
                val key = slot.key ?: return@mapNotNull null
                val sample = com.framemark.native.product.GhostSamples.forRole(roles[key])
                    .ifEmpty {
                        if (declared != null) {
                            com.framemark.native.product.GhostSamples.forDeclaredRows(
                                make = declared.make,
                                model = declared.model,
                                exif = declared.exif,
                                date = declared.date,
                            )
                        } else {
                            ""
                        }
                    }
                if (sample.isEmpty()) null else key to sample
            }
            .toMap()
    }
    LaunchedEffect(tool) {
        if (tool == EditorTool.Text) exifHintsAcknowledged = true
    }
    val density = LocalDensity.current

    // 裁切: everything below works in the **display** frame. The preview bitmap the
    // crop layer draws is the platform thumbnail, which already has EXIF orientation
    // applied; the engine lays the photo out in that same frame and reads
    // `photoCropX/Y/Zoom` in it. Probing the file's stored raster here (the old bug,
    // A-01) made a portrait JPEG's crop frame and gestures disagree with the photo
    // underneath by one whole rotation.
    val cropSourcePath = tray.selected?.path?.takeIf { it.isNotBlank() }
    val cropSourceSize by produceState<IntArray?>(initialValue = null, key1 = cropSourcePath) {
        value = withContext(Dispatchers.IO) {
            val path = cropSourcePath ?: return@withContext null
            val raw = photoSourceSize(path) ?: return@withContext null
            if (raw.size < 2 || raw[0] <= 0 || raw[1] <= 0) return@withContext null
            val orientation = WorkflowEnv.platform?.photoOrientation(path) ?: 1
            val display = PhotoOrientation.displaySize(raw[0], raw[1], orientation)
            intArrayOf(display.width, display.height)
        }
    }
    val cropSourceWidth = cropSourceSize?.getOrNull(0) ?: 0
    val cropSourceHeight = cropSourceSize?.getOrNull(1) ?: 0
    val storedCrop = remember(activeDesign) { PhotoCropParams.read(activeDesign?.params.orEmpty()) }
    val cropBox = previewGeo?.photoRect
    // The crop choice only exists where the engine would cut something away: an
    // aspect-equal box shows the whole photo and gets no button and no mode.
    val cropAvailable = cropBox != null && cropSourceWidth > 0 && cropSourceHeight > 0 &&
        cropApplies(cropSourceWidth, cropSourceHeight, cropBox)
    // A crop editor without a photo box has nothing to frame; drop out instead of
    // leaving the stage in a mode that cannot be drawn.
    LaunchedEffect(cropActive, cropAvailable) {
        if (cropActive && !cropAvailable) cropActive = false
    }
    // Switching border, photo or tool re-frames the box the crop was chosen for, so
    // the editor closes rather than silently applying a region to a new layout.
    LaunchedEffect(card?.id, tray.selectedId, tool) { cropActive = false }
    val cropUi = cropBox?.takeIf { cropAvailable }?.let { box ->
        PhoneCropUi(
            active = cropActive,
            crop = if (cropActive) pendingCrop else storedCrop,
            sourceWidth = cropSourceWidth,
            sourceHeight = cropSourceHeight,
            box = box,
            photoPath = cropSourcePath,
            onCropChange = { pendingCrop = it },
            onEnter = {
                pendingCrop = storedCrop
                cropActive = true
                // The crop layer is drawn inside the photo band, so the stage zoom/pan
                // must be neutral or the frame would be scaled by the stage transform.
                zoom = 1f
                pan = Offset.Zero
            },
            onReset = { pendingCrop = PhotoCrop.Default },
            onDone = {
                cropActive = false
                onCropChange(pendingCrop)
            },
        )
    }

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
    LaunchedEffect(undoNotice) {
        if (undoNotice) {
            delay(4000)
            undoNotice = false
        }
    }
    // 换边框那一步：`lastStepIsBorder` 是历史自己给的，所以只有「换边框」会弹，
    // 拖一下滑杆不会；撤销 / 重做把它清掉（那一下自己的反馈是「已撤销」。
    LaunchedEffect(session.history.undoCount, session.history.lastStepIsBorder) {
        borderNotice = session.history.lastStepIsBorder && session.history.canUndo
    }
    LaunchedEffect(borderNotice) {
        if (borderNotice) {
            delay(4000)
            borderNotice = false
        }
    }
    // 「已移除 · 撤销」与上面两条共用同一个提示位，但它那一拍更长一点：删除是破坏性的
    // 一下，4 秒是「手已经离开屏幕之后还能补救」的窗口。
    LaunchedEffect(removeNoticeLabel) {
        if (removeNoticeLabel != null) {
            delay(4000)
            onRemoveNoticeExpired()
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
    // 没有边框时工具条只剩「边框」一格（`phoneToolsFor`）：样式 / 文字 / 水印改的都是
    // 一张还不存在的边框，那三格点开只能回一句「先选择边框」——三次无效点击。
    val tools = phoneToolsFor(tabModel, hasBorder = card != null)
    LaunchedEffect(tools) {
        if (tool !in tools) onToolChange(EditorTool.Border)
    }

    fun pickBorder(picked: LibraryCard) {
        recentFrames = phoneRecordRecentFrame(recentFramesStore, libraryIndex, picked.id)
        onOpenCard(picked)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        val small = maxWidth < 380.dp
        // 窗口高度取一次：`maxHeight` 是 BoxWithConstraintsScope 的成员，进到更深的作用域
        // 里就不能隐式读了（底板预算与分页预算读的是同一个数）。
        val windowHeightDp = maxHeight.value
        // 面板高度是**固定画框**：`clamp(0.34 × 窗口, 280, 330)`，四个工具、每一组
        // 都是同一个数（`PhonePageLayout.kt` 已删，见 `PhonePanelLayout.kt`）。
        val budget = computePhoneEditorHeights(
            availableHeightDp = windowHeightDp,
            fontScale = density.fontScale,
            imeVisible = imeVisible,
            tool = tool,
            photosOpen = photosOpen,
        )
        // The expanded level of the sheet (null when the window is too short for the
        // fixed panel above a usable stage, in which case the budget keeps the full page).
        val levels = phoneSheetLevels(
            availableHeightDp = maxHeight.value,
            tool = tool,
            photosOpen = photosOpen,
            fontScale = density.fontScale,
        )
        val autoFullParameterPage = budget.fullPage
        LaunchedEffect(autoFullParameterPage) {
            if (!autoFullParameterPage) fullParametersDismissed = false
        }
        // The full parameter page is the constrained-window fallback only: V2 has
        // no separate "全部参数" entry because every page is a tool. 工具不参与决定
        // —— Logo 也是普通底板页（`PhoneSheetPagePolicy`），舞台始终留在它上面，
        // 所以调 Logo 的每个参数都能在画布上立刻看见。
        val fullParameterPage = PhoneSheetPagePolicy.usesFullHeightPage(
            tool = tool,
            constrainedWindow = autoFullParameterPage,
            fullPageDismissed = fullParametersDismissed,
        )
        // 全高参数页（矮窗口的回退页）接管屏幕时，底板里不留在背后的覆盖层；它把屏幕
        // 交还底板时，库也不跟着回来。规则与 rc14 P3-1 修的那一条同源（见
        // `PhoneSheetPagePolicy`），只是现在「库」不是底板里的页，而是外壳的一层。
        LaunchedEffect(fullParameterPage) {
            borderLibraryOpen = if (fullParameterPage) {
                PhoneSheetPagePolicy.pageUnderFullHeightPage()
            } else {
                PhoneSheetPagePolicy.pageAfterFullHeightPageReturn()
            }
        }
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
        // 底板顶部的两行（把手 + 标题行）。边框页与参数页都读 `phoneSheetChrome`
        // （`PhonePanelLayout.kt`）：边框页固定 16 + 44 dp，参数页按字体缩放走自己的阶梯。
        // 参数页只画这两行，若按边框页的固定值算会凭空多要几十 dp，那正是「开太高、
        // 还是放不下」的一半原因。预算与绘制读同一个函数，所以两边的数字不可能分叉。
        val chrome = phoneSheetChrome(density.fontScale, tool)
        val fullHeaderH = (52f * density.fontScale.coerceIn(1f, 1.5f)).dp
        val keyboardController = LocalSoftwareKeyboardController.current
        fun closeFullParametersOrHideKeyboard() {
            if (imeVisible) {
                keyboardController?.hide()
            } else if (!autoFullParameterPage || canShowForcedPreview(maxHeight.value)) {
                fullParametersDismissed = autoFullParameterPage
            } else {
                // A landscape/short window cannot render a usable preview above
                // the panel, so dismissing the full page would leave a zero-height
                // shell. It used to simply do nothing — a dead "返回预览" button
                // that trapped the user on the parameter page. Leave the editor
                // instead: the design is already written through, and going home
                // is the only exit that keeps a valid layout.
                onRequestHome?.invoke()
            }
        }
        BackHandler(enabled = fullParameterPage) {
            closeFullParametersOrHideKeyboard()
        }
        // The crop editor is the innermost layer while it is open: back leaves it
        // without writing anything (完成 is the only exit that persists).
        BackHandler(enabled = cropUi?.active == true) { cropActive = false }
        // Focus viewing hides every chrome layer; back leaves it before anything else.
        BackHandler(enabled = focusMode) { focusMode = false }
        BackHandler(enabled = sheetLevel > 0 && !fullParameterPage) { sheetLevel = 0 }
        // 品牌选择器叠在编辑器最上层：它开着时先关它，结果与标题行的「关闭」相同。
        // 后面注册的处理器优先，所以这一条排在上面四条之后。
        BackHandler(enabled = logoPickerOpen) { logoPickerOpen = false }
        // 边框库覆盖层同理：返回 / 系统返回都是「取消」（什么都不改，回到面板）。
        BackHandler(enabled = borderLibraryOpen) { borderLibraryOpen = false }

        // Three levels: compact (heights.sheet from the budget), 半开, expanded.
        val compactLevel = heights.sheet.dp
        val expandedLevel = maxOf(heights.sheet, levels?.expanded ?: heights.sheet).dp
        val halfLevel = maxOf(heights.sheet, levels?.half ?: heights.sheet).dp
        val snapHeights = listOf(compactLevel, halfLevel, expandedLevel)
        val draggedH = if (fullParameterPage) null else sheetDragPx?.let { with(density) { it.toDp() } }
        // Sheet motion (motion spec §1): the *resting* height is animated, the dragged
        // height never is. While the finger is down `sheetDragPx` is the height — an
        // animation on the same property would turn 1:1 tracking into lag — so the
        // animatable is snapped to the finger on every drag frame instead. That makes the
        // release point the start of the settle; without it the sheet would jump back to
        // the old stop and then animate to the new one.
        val restingPx = with(density) {
            snapHeights[sheetLevel.coerceIn(0, snapHeights.lastIndex)].toPx()
        }
        val settleSpec = fmSpringSpec<Float>(FmSpring.settle)
        val sheetPx = remember { Animatable(restingPx) }
        var sheetEverSettled by remember { mutableStateOf(false) }
        LaunchedEffect(sheetDragPx) { sheetDragPx?.let { sheetPx.snapTo(it) } }
        LaunchedEffect(restingPx, fullParameterPage) {
            if (fullParameterPage) return@LaunchedEffect
            if (!sheetEverSettled) {
                // First composition only: land on the current stop, never replay it.
                sheetPx.snapTo(restingPx)
                sheetEverSettled = true
            } else {
                sheetPx.animateTo(restingPx, settleSpec)
            }
        }
        val sheetHeight = if (fullParameterPage) maxHeight else with(density) {
            (sheetDragPx ?: sheetPx.value).toDp()
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
                        closeLabel = when {
                            imeVisible -> "收起键盘"
                            canShowForcedPreview(windowHeightDp) -> "返回预览"
                            else -> "返回首页"
                        },
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
                                        cropActive -> cropActive = false
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
                            undoNotice = undoNotice,
                            borderNotice = borderNotice,
                            removeNoticeLabel = removeNoticeLabel,
                            // 缺拍摄参数的行给一圈虚线占位；进过「文字」页就不再画。
                            showExifSlotHints = exifHintsVisible,
                            textSlots = textSlots,
                            exifSamples = exifSamples,
                            onUndoRemove = {
                                onUndoRemove()
                                onRemoveNoticeExpired()
                            },
                            onRedo = {
                                onRedo()
                                undoNotice = false
                            },
                            onUndo = {
                                onUndo()
                                // 换边框那一步撤销了，就给回「已撤销 · 重做」这条提示。
                                undoNotice = session.history.canRedo
                                borderNotice = false
                            },
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
                            onFocusToggle = { focusMode = !focusMode },
                            // 无边框这一屏**唯一**的入口：画布上那枚「选择一个边框」。
                            // 它开的就是库覆盖层（以前是面板上那枚按钮做的事），而不是
                            // 「切到边框工具」——工具本来就在边框，那样点一下什么都不发生。
                            onOpenBorder = {
                                borderLibraryOpen =
                                    PhoneSheetPagePolicy.pageAfterLibraryAction(fullParameterPage)
                            },
                            // 舞台没有「批量 / 应用到全部 / 设置」这三枚控件：以前这里四个
                            // 回调声明了却没人调，签名在撒谎（UX 审查 P1-3）。「用到全部」
                            // 真正接上的位置是边框面板当前边框那一行，见 `PhoneSheetHost`。
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
                            // 水印位置改在画布上：九个格子里点一格，写回的就是旧面板
                            // 九宫格写回的那个 anchor（同一份 `watermarkSpecForAnchor`）。
                            showWatermarkAnchorLayer = showsWatermarkAnchorLayer(tool, watermark != null),
                            onWatermarkAnchorPick = { anchor ->
                                watermark?.let { spec -> onWatermarkChange(watermarkSpecForAnchor(spec, anchor)) }
                            },
                            crop = cropUi,
                        )
                        if (!focusMode && photosOpen) {
                            PhotoStrip(
                                tray = tray,
                                onSelect = onSelectPhoto,
                                onRemove = onRemovePhoto,
                                onAdd = onPickPhoto,
                                customIds = customizedPhotoIds,
                                // 滚动位置挂在这一层：收起/展开、键盘弹起都不会回到开头。
                                scroll = photoStripScroll,
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
                    snapHeights = snapHeights,
                    sheetLevel = sheetLevel,
                    onSheetLevelChange = { sheetLevel = it },
                    sheetDragPx = sheetDragPx,
                    onSheetDragPx = { sheetDragPx = it },
                    grabberHeight = chrome.grabberDp.dp,
                    headHeight = chrome.headDp.dp,
                    density = density,
                    tool = tool,
                    tabModel = tabModel,
                    card = card,
                    libraryIndex = libraryIndex,
                    onRetryLibrary = onRetryLibrary,
                    onOpenPresets = onOpenPresets,
                    onToolChange = { next ->
                        // 换工具就不再属于这一拍：库覆盖层先收起来，再换页。规则在
                        // `PhoneSheetPagePolicy` 里（换工具永远不开库、库只属于 边框）。
                        borderLibraryOpen =
                            PhoneSheetPagePolicy.pageAfterToolPick(tool, next, borderLibraryOpen)
                        onToolChange(next)
                    },
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
                    // 手机端的 选择品牌 开在编辑器这一层（见下面那片覆盖层），而不是
                    // 底板里的一页：选择器必须能盖住舞台，才能「舞台仍在，挑的字标
                    // 立刻反映到画布」。logoPickerOpen 属于外壳，Logo 页只发请求。
                    onOpenBrandPicker = { logoPickerOpen = true },
                    // 边框库同一套做法：面板上的「选择一个边框 / 换一个边框」只把请求
                    // 递上来，库覆盖层由外壳画（下面那片），所以打开它不移动底板档位。
                    // 全高参数页上不存在这块面板，规则因此也写在 policy 里。
                    onOpenBorderLibrary = {
                        borderLibraryOpen = PhoneSheetPagePolicy.pageAfterLibraryAction(fullParameterPage)
                    },
                    // 边框面板当前边框那一行右侧的「用到全部 N 张」：和导出表单里那一枚
                    // 是同一个动作（同一个确认弹窗）。只有一个动作、两个入口。
                    onApplyToAll = onApplyAll,
                    applyToAllCount = applyToAllCount,
                )
            }
        }
        // 品牌选择器：一层盖在编辑器上，不是新页面。它画在 Column 之后，所以底板、
        // 工具条都在它下面；顶部条带是 FmPalette.scrim 下的舞台，用户仍看得见画布。
        // 打开 / 关闭都不改工具、不移动底板档位，选完字标只是把 cut 键写进设计，
        // 关掉就精确回到进来之前的那一页。
        val logoBrandControl = model?.controls?.firstOrNull { it.type == ControlType.Brand }
        // Motion spec §4: the overlay fades its scrim in and lifts the panel, and the exit
        // is shorter than the entrance. `AnimatedVisibility` keeps the layer composed while
        // it leaves, so the content re-checks its own precondition instead of assuming the
        // control is still there (switching tool closes the picker).
        val overlayEnter = fmOverlayEnter()
        val overlayExit = fmOverlayExit()
        AnimatedVisibility(
            visible = !focusMode && logoPickerOpen && logoBrandControl != null,
            enter = overlayEnter,
            exit = overlayExit,
        ) {
            val control = logoBrandControl
            if (control != null) {
                PhoneLogoPickerOverlay(
                    appliedAssetKey = session.value(control) as? String ?: "",
                    onDismiss = { logoPickerOpen = false },
                    onApply = { assetKey ->
                        session.set(control, assetKey)
                        logoPickerOpen = false
                    },
                )
            }
        }
        // 边框库覆盖层：和品牌选择器同一层、同一套呈现（遮罩 + 0.88 高的圆角底部面板）。
        // 点一张卡就是应用（走同一个 `pickBorder`）并关闭，所以回到的是面板——上面
        // 已经是新的那一张；遮罩 / 返回 / 系统返回是取消，什么都不改。
        val borderOverlayIndex = libraryIndex
        AnimatedVisibility(
            visible = !focusMode && borderLibraryOpen && borderOverlayIndex != null,
            enter = overlayEnter,
            exit = overlayExit,
        ) {
            val openIndex = borderOverlayIndex
            if (openIndex != null) {
                PhoneBorderLibraryOverlay(
                    index = openIndex,
                    appliedCard = card,
                    categoryKey = borderCategory,
                    onCategoryChange = { borderCategory = it },
                    query = borderQuery,
                    onQueryChange = { borderQuery = it },
                    inUse = inUseCardIds,
                    recent = recentFrames,
                    onApply = ::pickBorder,
                    onDismiss = { borderLibraryOpen = false },
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
