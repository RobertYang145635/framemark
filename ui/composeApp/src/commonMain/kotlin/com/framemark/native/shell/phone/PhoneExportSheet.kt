package com.framemark.native.shell.phone

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.library.LibraryCard
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.OutputPixels
import com.framemark.native.shell.SAMPLE_EXPORT_REFUSAL
import com.framemark.native.theme.FmDuration
import com.framemark.native.theme.FmEasing
import com.framemark.native.theme.FmMotionRules
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.theme.LocalFmMotionScale
import com.framemark.native.theme.fmTweenSpec
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.EngineFailure
import com.framemark.native.workflow.ExportResult
import com.framemark.native.workflow.ExportStage
import com.framemark.native.workflow.FmProgress
import com.framemark.native.workflow.FmSheet
import com.framemark.native.workflow.ChoiceChip
import com.framemark.native.workflow.GhostButton
import com.framemark.native.workflow.NativeEngine
import com.framemark.native.workflow.NativeStagePreview
import com.framemark.native.workflow.OverlaySpec
import com.framemark.native.workflow.PreviewGeometry
import com.framemark.native.workflow.PrimaryButton
import com.framemark.native.product.ExportFormat
import com.framemark.native.product.UltraHdr
import com.framemark.native.workflow.SheetFieldLabel
import com.framemark.native.workflow.SheetNote
import com.framemark.native.workflow.SheetTag
import com.framemark.native.workflow.SheetTitle
import com.framemark.native.workflow.WorkflowEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.framemark.native.product.FmLog
import com.framemark.native.product.NoticeText

/**
 * Phone export sheet (report §7.6, demo `06-phone-export.png`).
 *
 * Form → 正在保存 → 已保存, all inside one bottom sheet: the report's §3.5 complaint
 * was that the result used to be stacked under a long form. The form carries the
 * preview, 格式, the three 尺寸 steps (plus the project's own "自定义 N px" edge when
 * it has one), the fixed quality, the Ultra HDR switch and the A-01 note
 * (per format: 原尺寸 promises no rescaling, and JPEG says it re-encodes once —
 * 返修 P2-2); the result page repeats the real pixel size and offers
 * 分享 / 完成 and 「重试写入相册」.
 *
 * This sheet is the phone export entry (PhoneShell); the desktop uses
 * `shell/desktop/ExportDialog.kt`. `workflow/ExportSheet.kt` was deleted in
 * WP-FIX2 (zero call sites), so the phone can drop the quality field and the
 * long-edge text field without changing the desktop dialog D1/D2 own.
 */
@Composable
fun PhoneExportSheet(
    session: DesignSession,
    card: LibraryCard?,
    design: DesignSnapshot?,
    overlay: () -> OverlaySpec?,
    photoPath: String?,
    photoIndex: Int,
    photoDisplayName: String?,
    canApplyToAll: Boolean,
    onApplyToAll: () -> Unit,
    onDismiss: () -> Unit,
    /** Debug evidence hook (`fmRunExport` / `FM_RUN_EXPORT`): export as soon as it opens. */
    autoGenerate: Boolean = false,
    /**
     * 舞台当前照片的像素尺寸。表单靠它把「原尺寸 / 长边 N」换算成**真实的成片像素**；
     * 以前这里写死 0，于是表单永远退回去印「原尺寸」那一句，用户看不到 3024 × 4032。
     */
    photoWidthPx: Int = 0,
    photoHeightPx: Int = 0,
    /** 照片自己的拍摄时间（EXIF `DateTimeOriginal`），命名的 `{date}`/`{time}` 用它。 */
    capturedAt: String? = null,
    /**
     * 当前边框声明的参数行。`OutputSize` 靠它判断画布公式族（页脚 / 四边同宽），
     * 与手机样式页的「导出尺寸」是同一份输入，所以两处印同一个数字。
     */
    controls: List<InspectorControl> = emptyList(),
    /**
     * 当前照片是内置样张（没有文件）。导出过了这里就写出应用自带的山景照 ——
     * 外壳的 `openExport()` 已经先拒了一次（P0-1），这里是最后一道闸：
     * 证据钩子（直接开表单、不等外壳收口）是绕过外壳的那条路。
     */
    isSamplePhoto: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val platform = WorkflowEnv.platform
    var result by remember { mutableStateOf<ExportResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var publishing by remember { mutableStateOf(false) }
    // Motion batch 2 (2026-11-14): the export bar is **determinate** — it moves only
    // when the pipeline really completes a stage (`ExportStage`, "stages completed /
    // 4", never a time guess), and each move is a 200 ms linear tween. Failure leaves
    // the bar and shows the error text; nothing shakes (spec §5.1).
    var stage by remember { mutableStateOf(ExportStage.Started) }
    val exportProgress by animateFloatAsState(
        targetValue = if (busy) stage.fraction else 0f,
        animationSpec = fmTweenSpec<Float>(FmDuration.standard, FmEasing.linear),
        label = "fm.export.progress",
    )
    val settings = PhoneExportOptions.normalized(session.export)
    val native = card != null && NativeEngine.supportsExport(card.id)
    val busyNow = busy || sharing || publishing
    // 成片像素：右栏 / 顶栏 / 手机样式页 / 引擎桥接共用同一条 [OutputSize] 链 ——
    // 先按该边框声明的参数算画布（页脚会加高），再套长边档。取不到照片尺寸时是
    // null，表单退回「尺寸档」那一句，绝不印一个猜的数字。
    // 引擎报的 `fit.source_canvas`（预览行那次渲染带回来）是画布真值；固定比例
    // 模板按自己的版式裁照片，Kotlin 侧反推不出来。只有它缺位时才退回公式。
    var engineCanvasPx by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(photoPath, card?.id, design) { engineCanvasPx = null }
    val outputPixels = PhoneExportOptions.outputPixels(
        photoWidthPx = photoWidthPx,
        photoHeightPx = photoHeightPx,
        controls = controls,
        settings = settings,
        value = { control -> session.value(control) },
        engineCanvasPx = engineCanvasPx,
    )

    // Whether the active photo really carries an Ultra HDR gain map. Read off the UI
    // thread: UltraHdr.locate() takes the whole buffer, and a photo is tens of MB.
    var hdrGainMap by remember(photoPath) { mutableStateOf(false) }
    // HEIC 导入时把原始文件留成 `<照片>.hdrsrc`：增益图在里面，但当前版本还不会用它合成。
    var hdrHeifSource by remember(photoPath) { mutableStateOf(false) }
    LaunchedEffect(photoPath) {
        val path = photoPath
        if (path.isNullOrBlank()) {
            hdrGainMap = false
            hdrHeifSource = false
        } else {
            val (gainMap, heifSource) = withContext(Dispatchers.IO) {
                val bytes = runCatching { platform?.readBytes(path) }.getOrNull()
                val hasGainMap = bytes != null && UltraHdr.locate(bytes) != null
                val hasHeif = !hasGainMap && runCatching { platform?.exists("$path.hdrsrc") == true }
                    .getOrDefault(false)
                hasGainMap to hasHeif
            }
            hdrGainMap = gainMap
            hdrHeifSource = heifSource
            // 照片自带增益图时**默认开启**保留 HDR：用户不必自己发现这个开关。
            if (PhoneExportOptions.hdrDefaultOn(gainMap) && !settings.preserveHdr) {
                session.export = PhoneExportOptions.applyPreserveHdr(settings, true)
            }
        }
    }

    fun update(next: com.framemark.native.product.ExportSettings) {
        session.export = PhoneExportOptions.normalized(next)
        error = null
    }

    fun share(path: String) {
        val store = platform ?: run {
            note = "存储不可用"
            return
        }
        sharing = true
        scope.launch {
            try {
                note = withContext(Dispatchers.IO) { store.shareFile(path) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                note = NoticeText.failure("FM_EXPORT_FAIL", failure, "分享没有成功，请重试。")
            } finally {
                sharing = false
            }
        }
    }

    /**
     * 「查看相册」= 打开系统相册并定位到这张导出图。平台返回一句话时写进 [note]，
     * 所以相册没打开时不会假装打开（配色方案 §4.4）。
     */
    fun openGallery(path: String) {
        val store = platform ?: run {
            note = "存储不可用"
            return
        }
        note = store.openGallery(path)
    }

    fun generate(shareAfter: Boolean) {
        if (busyNow) return
        // P0-1：样张没有文件，引擎会拿内置样张渲染 —— 导出出来的是应用自带的照片。
        // 这里给一句人话并停手，绝不写文件、也不假装成功。
        if (isSamplePhoto) {
            error = SAMPLE_EXPORT_REFUSAL
            FmLog.warn("FM_EXPORT_FAIL") { "sample photo refused" }
            return
        }
        val target = card ?: return
        error = null
        note = null
        result = null
        stage = ExportStage.Started
        busy = true
        session.exporting = true
        scope.launch {
            try {
                val snapshot = design ?: DesignSnapshot(target.id, target.systemStyle, emptyMap(), emptyMap())
                val exported = NativeEngine.export(
                    card = target,
                    settings = PhoneExportOptions.normalized(settings),
                    design = snapshot,
                    overlay = overlay(),
                    photoPath = photoPath,
                    nameIndex = photoIndex,
                    photoDisplayName = photoDisplayName,
                    outputDirectory = platform?.exportDir,
                    onStage = { next -> stage = next },
                    capturedAt = capturedAt,
                )
                session.lastExport = exported
                result = exported
                FmLog.info("FM_PHONE_EXPORT") { "card=${target.id} path=${exported.path} note=${exported.note ?: "-"}" }
                if (shareAfter) share(exported.path)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val engineFailure = failure as? EngineFailure
                // Code in the log line below; the sheet shows the sentence only.
                error = engineFailure?.userMessage ?: "导出没有成功，请重试。"
                FmLog.warn("FM_EXPORT_FAIL") { "${engineFailure?.code ?: "FM-E-INTERNAL-001"} ${failure.message}" }
            } finally {
                busy = false
                session.exporting = false
            }
        }
    }

    /** 「重试写入相册」: republishes the committed file, never re-renders it. */
    fun retryPublish(path: String) {
        if (busyNow) return
        publishing = true
        scope.launch {
            try {
                val published = WorkflowEnv.nativeRender?.exportCommitted(path)
                val current = result
                if (current != null && current.path == path) {
                    result = current.copy(note = published ?: current.note)
                    session.lastExport = result
                }
                note = published
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                note = NoticeText.failure("FM_EXPORT_FAIL", failure, "相册写入没有成功，请重试。")
            } finally {
                publishing = false
            }
        }
    }

    // Evidence-only: generate once the preview has reported the photo box, so the
    // overlay rect is built with the engine aspect instead of the default.
    LaunchedEffect(Unit) {
        if (!autoGenerate) return@LaunchedEffect
        val id = card?.id
        var waited = 0
        while (id != null && PreviewGeometry.aspectOf(id) == null && waited < 4000) {
            kotlinx.coroutines.delay(100)
            waited += 100
        }
        generate(shareAfter = false)
    }

    FmSheet(
        onDismiss = { if (!busyNow) onDismiss() },
        // `06-phone-export.png` is a content-height sheet: the summary card, 格式 / 尺寸,
        // the two notes and the link decide the height, then the action row follows.
        // The container still caps itself at 90 % of the window (FmSheetMetrics), so a
        // short window scrolls the body instead of pushing 分享 / 保存 out of reach.
        wrapContent = true,
    ) {
        val current = result
        if (current != null) {
            ExportDoneContent(
                result = current,
                note = note,
                busy = busyNow,
                sharing = sharing,
                publishing = publishing,
                onShare = { share(current.path) },
                onOpenGallery = { openGallery(current.path) },
                onRetryPublish = { retryPublish(current.path) },
                onDone = onDismiss,
            )
        } else {
            ExportFormContent(
                card = card,
                design = design,
                overlay = overlay,
                photoPath = photoPath,
                native = native,
                settings = settings,
                busy = busyNow,
                sharing = sharing,
                error = error,
                note = note,
                // The engine reports the real canvas only after a render/export; before
                // that the sheet prints the chosen step instead of inventing pixels.
                // (The caller now hands in the photo's pixel size, so the form usually
                // has the real numbers — 3024 × 4032 — before anything is written.)
                onCanvas = { width, height -> engineCanvasPx = width to height },
                capturedAt = capturedAt,
                outputWidth = outputPixels?.width ?: 0,
                outputHeight = outputPixels?.height ?: 0,
                hdrGainMap = hdrGainMap,
                hdrHeifSource = hdrHeifSource,
                progress = exportProgress,
                canApplyToAll = canApplyToAll,
                onApplyToAll = onApplyToAll,
                onPreserveHdr = { update(PhoneExportOptions.applyPreserveHdr(settings, it)) },
                onFormat = { update(PhoneExportOptions.applyFormat(settings, it)) },
                onSize = { id -> update(PhoneExportOptions.applySizeChoice(settings, id)) },
                onShare = { if (busyNow) Unit else generate(shareAfter = true) },
                onSave = { if (busyNow) Unit else generate(shareAfter = false) },
                onClose = { if (!busyNow) onDismiss() },
            )
        }
    }
}

private val PreviewWidth = 112.dp
private val PreviewHeight = 96.dp

/**
 * The export sheet's height contract (the sheet wraps its content since the
 * "full-height with an empty middle" report).
 *
 * Every row of the form has a fixed height except the resolution note: its text
 * depends on the chosen 格式 / 尺寸 (JPEG's promise wraps to two lines at phone
 * width, PNG's fits one). Reserving the height of the longest promise keeps the
 * sheet's edge still — switching a segmented option must not resize the sheet.
 * `PhoneExportSheetRenderTest` measures the sheet edge for every option state.
 */
internal object PhoneExportSheetLayout {
    /** `ExportNote` is 12.5 sp on an 18 sp line box. */
    val NoteLineHeight: Dp = 18.dp

    /** The 原分辨率 ... JPEG ... promise is two lines; reserve for the longest one. */
    const val NoteShelfLines: Int = 2

    /** Height reserved for the option-dependent note, in dp. */
    val NoteShelfHeight: Dp get() = NoteLineHeight * NoteShelfLines
}

/** Showcase `.bs-h b`: the sheet title is 18 px / 700 with only a close cross beside it. */
@Composable
private fun ExportSheetHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "导出",
            style = androidx.compose.material3.LocalTextStyle.current.copy(
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = FmPalette.onSurface,
            modifier = Modifier.weight(1f),
        )
        // 命中区 48 dp（图标仍由 PhoneCloseGlyph 自己决定大小）；此前只有 36 dp 且
        // 没有可读名称，读屏会读成一个无名按钮（UI 评审 P0-3）。
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .clickable(role = Role.Button, onClick = onClose)
                .semantics {
                    contentDescription = "关闭导出"
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            PhoneCloseGlyph(tint = FmPalette.onSurfaceSecondary)
        }
    }
}

/** 返修 P2-2: the promise the chosen format really keeps, in the demo's `.pexp-prev` line. */
private fun formatQualityLabel(format: ExportFormat, webpLossless: Boolean): String = when (format) {
    ExportFormat.Jpeg -> "JPG · 质量 100"
    ExportFormat.Png -> "PNG · 无损"
    ExportFormat.WebP -> if (webpLossless) "WebP · 无损" else "WebP · 质量 100"
}

/**
 * `.pexp-prev`: a 112 × 96 dark preview well in a `--muted-bg` card, then the real
 * pixel size (15/500), the format + quality line and the border name (12.5).
 */
@Composable
private fun ExportPreviewRow(
    card: LibraryCard?,
    design: DesignSnapshot?,
    overlay: () -> OverlaySpec?,
    photoPath: String?,
    /** Real export pixels when the engine has reported them, else the step caption. */
    sizeLabel: String,
    formatLabel: String,
    /** Engine canvas from this row's own preview render (the size truth). */
    onCanvas: ((Int, Int) -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.card))
            .background(FmPalette.surfaceMuted)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = PreviewWidth, height = PreviewHeight)
                .clip(RoundedCornerShape(FmRadii.tile))
                .background(FmPalette.stage)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (card != null && design != null) {
                NativeStagePreview(
                    card = card,
                    design = design,
                    overlay = overlay(),
                    photoPath = photoPath,
                    modifier = Modifier.fillMaxSize(),
                    onCanvas = onCanvas,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sizeLabel,
                style = androidx.compose.material3.LocalTextStyle.current.copy(
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = FmPalette.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatLabel,
                style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
            )
            Text(
                text = card?.name ?: "原图",
                style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The 完成 page: what was written, the real pixel size, and the safe retries. */
@Composable
private fun ColumnScope.ExportDoneContent(
    result: ExportResult,
    note: String?,
    busy: Boolean,
    sharing: Boolean,
    publishing: Boolean,
    onShare: () -> Unit,
    onOpenGallery: () -> Unit,
    onRetryPublish: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        // Same sizing rule as the form: content height by default (`fill = false`
        // lets the sheet wrap), the height the action pair leaves free on a short
        // window, and the buttons keep their own 48 dp either way.
        modifier = Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(FmSpacing.xl))
        // Motion batch 2: the success check scales up and fades in over 200 ms with
        // no overshoot and no shake — the mark is a confirmation, not a celebration.
        // At reduced motion it is drawn at its final state on the first frame, and a
        // failed export never reaches this page (failures are text, spec §5.1).
        val motionEnabled = FmMotionRules.motionEnabled(LocalFmMotionScale.current)
        val checkSpec = fmTweenSpec<Float>(FmDuration.standard, FmEasing.enter)
        val checkAppear = remember { Animatable(if (motionEnabled) 0f else 1f) }
        LaunchedEffect(Unit) {
            if (motionEnabled) checkAppear.animateTo(targetValue = 1f, animationSpec = checkSpec)
        }
        Box(
            modifier = Modifier
                .size(56.dp)
                .graphicsLayer {
                    alpha = checkAppear.value
                    val scale = 0.86f + 0.14f * checkAppear.value
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.success.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", style = FmType.titleLarge, color = FmPalette.success)
        }
        Spacer(Modifier.height(FmSpacing.md))
        Text(
            text = "已保存到相册",
            style = FmType.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            color = FmPalette.onSurface,
        )
        Spacer(Modifier.height(FmSpacing.xs))
        Text(
            text = result.fileName,
            style = FmType.bodyMedium,
            color = FmPalette.onSurfaceSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(FmSpacing.xs))
        // A-01 second half: the result repeats the exported size.
        Text(
            text = buildString {
                if (result.width != null && result.height != null) {
                    append("${result.width} × ${result.height} px · ")
                }
                append(result.formatLabel)
                append(" · 质量 100")
            },
            style = FmType.labelSmall.copy(fontSize = 12.sp),
            color = FmPalette.onSurfaceMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(FmSpacing.xxs))
        Text(
            text = "相册 / FrameMark",
            style = FmType.labelSmall.copy(fontSize = 11.5.sp),
            color = FmPalette.onSurfaceMuted,
        )
        val message = listOfNotNull(result.note, note).distinct().joinToString("；").ifBlank { null }
        if (message != null) {
            Spacer(Modifier.height(FmSpacing.sm))
            Text(
                text = message,
                style = FmType.labelSmall.copy(fontSize = 11.5.sp),
                color = FmPalette.onSurfaceMuted,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(FmSpacing.lg))
        Box(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .clickable(enabled = !busy, onClick = onRetryPublish)
                .padding(horizontal = FmSpacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (publishing) "正在重试写入相册…" else "相册里没看到？重试写入相册",
                style = FmType.labelSmall.copy(fontSize = 12.sp),
                color = FmPalette.primaryStrong,
            )
        }
    }
    Spacer(Modifier.height(FmSpacing.md))
    Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
        GhostButton(
            text = if (sharing) "准备分享…" else "分享",
            enabled = !busy,
            modifier = Modifier.weight(1f),
            onClick = onShare,
        )
        // 配色方案 §4.4 / InkFrame 复盘第 9 条：导出完成页同时给出「看成片」和
        // 「分享」，不是只有一句「已保存」。相册打不开时由 note 说话。
        GhostButton(
            text = "查看相册",
            enabled = !busy,
            modifier = Modifier.weight(1f),
            onClick = onOpenGallery,
        )
        PrimaryButton(
            text = "完成",
            enabled = true,
            modifier = Modifier.weight(1f),
            onClick = onDone,
        )
    }
}
/**
 * The export form of `06-phone-export.png`: 导出 + ✕, the preview card (size, format
 * + quality, border name), 格式 and 尺寸 as touch segmented controls (the three steps
 * plus the project's own 自定义 N px edge), the Ultra HDR switch, the fixed-quality
 * note and the [分享] / [保存到相册] pair.
 *
 * The two notes keep 返修 P2-2's wording: the demo's single line only says the
 * quality is fixed, and that is not the whole truth per format, so the resolution
 * line states whether the file is re-encoded and whether 原尺寸 is really un-resized.
 */
@Composable
internal fun ColumnScope.ExportFormContent(
    card: LibraryCard?,
    design: DesignSnapshot?,
    overlay: () -> OverlaySpec?,
    photoPath: String?,
    native: Boolean,
    settings: com.framemark.native.product.ExportSettings,
    busy: Boolean,
    sharing: Boolean,
    error: String?,
    note: String?,
    /** Engine canvas from this row's own preview render (the size truth). */
    onCanvas: ((Int, Int) -> Unit)? = null,
    /** 照片自己的拍摄时间；命名用它，缺省时表单说明用了导出时间。 */
    capturedAt: String? = null,
    outputWidth: Int,
    outputHeight: Int,
    /** The active photo really carries an Ultra HDR gain map (read off the UI thread). */
    hdrGainMap: Boolean,
    hdrHeifSource: Boolean,
    /**
     * Determinate export progress in `0..1` ([ExportStage] fractions). One of the
     * four pipeline stops at a time — the sheet animates the bar's width, it does not
     * invent a percentage between stops.
     */
    progress: Float = 0f,
    canApplyToAll: Boolean,
    onApplyToAll: () -> Unit,
    onPreserveHdr: (Boolean) -> Unit,
    onFormat: (ExportFormat) -> Unit,
    onSize: (String) -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val format = PhoneExportOptions.formatOf(settings)
    val sizeChoices = PhoneExportOptions.sizeChoices(settings)
    val hdrEnabled = PhoneExportOptions.hdrEnabled(format, hdrGainMap)
    ExportSheetHeader(onClose = { if (!busy) onClose() })
    Spacer(Modifier.height(10.dp))
    Column(
        // `fill = false`: the body may stay at its content height (the sheet wraps),
        // but the row still gets the height the action pair leaves free. Column lays
        // the unweighted children out first, so 分享 / 保存到相册 always keep their
        // 48 dp inside the sheet; only this body shrinks and scrolls on a short window.
        modifier = Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ExportPreviewRow(
            card = card,
            design = design,
            overlay = overlay,
            photoPath = photoPath,
            onCanvas = onCanvas,
            sizeLabel = if (outputWidth > 0 && outputHeight > 0) {
                "$outputWidth × $outputHeight"
            } else {
                PhoneExportOptions.sizeCaption(settings)
            },
            formatLabel = formatQualityLabel(format, settings.webpLossless),
        )
        Column {
            ExportFieldLabel(label = "格式")
            PhoneSegmented(
                options = PhoneExportOptions.FORMATS.map { it.name to phoneFormatLabel(it) },
                selected = format.name,
                onSelect = { name ->
                    PhoneExportOptions.FORMATS.firstOrNull { it.name == name }?.let(onFormat)
                },
                label = "格式",
            )
        }
        Column {
            ExportFieldLabel(
                label = "尺寸",
                value = when {
                    outputWidth > 0 && outputHeight > 0 -> "$outputWidth × $outputHeight"
                    // A project long edge outside the three steps is surfaced as itself:
                    // the nearest-step chip alone would contradict the engine's value.
                    PhoneExportOptions.customLongEdge(settings) != null ->
                        PhoneExportOptions.sizeCaption(settings)
                    else -> null
                },
            )
            PhoneSegmented(
                options = sizeChoices.map { it.id to it.label },
                selected = PhoneExportOptions.selectedSizeId(settings),
                onSelect = onSize,
                label = "尺寸",
            )
        }
        // Ultra HDR: the same switch as the desktop export dialog. Visible even when
        // unusable, but disabled with the one-line reason — never a silent no-op.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
        ) {
            androidx.compose.material3.Switch(
                checked = settings.preserveHdr && hdrEnabled,
                onCheckedChange = { if (!busy) onPreserveHdr(it) },
                enabled = hdrEnabled && !busy,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(FmSpacing.xxs),
            ) {
                Text(
                    text = "保留 HDR（Ultra HDR）",
                    style = FmType.bodyMedium,
                    color = if (hdrEnabled) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                )
                Text(
                    text = PhoneExportOptions.hdrReason(format, hdrGainMap, hdrHeifSource)
                        ?: if (settings.preserveHdr) {
                            "已开启：成片仍可在 HDR 屏幕上还原高光"
                        } else {
                            "照片自带增益图，可保留 HDR 高光"
                        },
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ExportNote("Android 导出质量固定为 100")
            // The one option-dependent row keeps a two-line shelf (see
            // PhoneExportSheetLayout): 格式 / 尺寸 may change its text but not the
            // sheet's height.
            ExportNote(
                text = PhoneExportOptions.resolutionNote(settings),
                minHeight = PhoneExportSheetLayout.NoteShelfHeight,
            )
            // 命名用照片自己的拍摄时间；没有时必须说清用的是导出那一刻。
            com.framemark.native.shell.desktop.ExportCopy.captureTimeNote(capturedAt)
                ?.let { ExportNote(it) }
            if (!native) ExportNote("这张边框的渲染组件不可用，暂时无法导出。")
            if (canApplyToAll) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .clickable(enabled = !busy, onClick = onApplyToAll)
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "把当前设计用到全部照片",
                        style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                        color = FmPalette.primaryStrong,
                    )
                }
            }
            if (error != null) {
                Text(
                    text = error,
                    style = FmType.bodyMedium,
                    color = FmPalette.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (note != null) {
                Text(text = note, style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
            }
            if (busy) {
                Text(
                    text = if (sharing) "正在准备分享…" else "正在保存到相册…",
                    style = FmType.bodyMedium,
                    color = FmPalette.onSurfaceSecondary,
                )
                // A determinate bar that only moves on a completed pipeline stage —
                // 4 dp tall, `FmProgress` paints it in the shared tokens.
                Spacer(Modifier.height(FmSpacing.xxs))
                FmProgress(progress = progress, height = 4, label = "导出进度")
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton(
            text = if (sharing) "准备分享…" else "分享",
            enabled = !busy && card != null && native,
            modifier = Modifier.weight(1f),
            onClick = onShare,
        )
        PrimaryButton(
            text = if (busy) "正在保存…" else "保存到相册",
            enabled = !busy && card != null && native,
            modifier = Modifier.weight(1f),
            onClick = onSave,
        )
    }
}

/** `.pfield > label`: 13 px ink-3 on the left, the current value 12.5 px on the right. */
@Composable
private fun ExportFieldLabel(label: String, value: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                color = FmPalette.onSurfaceSecondary,
                maxLines = 1,
            )
        }
    }
}

/** `.pnote`: the ⓘ + one-line explanation under the fields. */
@Composable
private fun ExportNote(text: String, minHeight: Dp = 0.dp) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "ⓘ",
            style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 18.sp),
            color = FmPalette.onSurfaceFaint,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
            color = FmPalette.onSurfaceMuted,
        )
    }
}

/** `ExportFormat.label` is the enum's display name; the sheet prints it verbatim. */
private fun phoneFormatLabel(format: ExportFormat): String = when (format) {
    ExportFormat.Jpeg -> "JPG"
    ExportFormat.Png -> "PNG"
    ExportFormat.WebP -> "WebP"
}

