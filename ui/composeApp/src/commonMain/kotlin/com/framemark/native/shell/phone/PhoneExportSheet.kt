package com.framemark.native.shell.phone

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.library.LibraryCard
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.EngineFailure
import com.framemark.native.workflow.ExportResult
import com.framemark.native.workflow.FmSheet
import com.framemark.native.workflow.ChoiceChip
import com.framemark.native.workflow.GhostButton
import com.framemark.native.workflow.NativeEngine
import com.framemark.native.workflow.NativeStagePreview
import com.framemark.native.workflow.OverlaySpec
import com.framemark.native.workflow.PreviewGeometry
import com.framemark.native.workflow.PrimaryButton
import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.SheetFieldLabel
import com.framemark.native.workflow.SheetNote
import com.framemark.native.workflow.SheetTag
import com.framemark.native.workflow.SheetTitle
import com.framemark.native.workflow.WorkflowEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone export sheet (report §7.6, demo `06-phone-export.png`).
 *
 * Form → 正在保存 → 已保存, all inside one bottom sheet: the report's §3.5 complaint
 * was that the result used to be stacked under a long form. The form carries the
 * preview, 格式, exactly three 尺寸 steps, the fixed quality and the A-01 note
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
) {
    val scope = rememberCoroutineScope()
    val platform = WorkflowEnv.platform
    var result by remember { mutableStateOf<ExportResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var publishing by remember { mutableStateOf(false) }
    val settings = PhoneExportOptions.normalized(session.export)
    val native = card != null && NativeEngine.supportsExport(card.id)
    val busyNow = busy || sharing || publishing

    fun update(next: com.framemark.native.workflow.ExportSettings) {
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
                note = "分享没有成功：${failure.message ?: "请重试"}"
            } finally {
                sharing = false
            }
        }
    }

    fun generate(shareAfter: Boolean) {
        if (busyNow) return
        val target = card ?: return
        error = null
        note = null
        result = null
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
                )
                session.lastExport = exported
                result = exported
                println("FM_PHONE_EXPORT card=${target.id} path=${exported.path} note=${exported.note ?: "-"}")
                if (shareAfter) share(exported.path)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val engineFailure = failure as? EngineFailure
                error = engineFailure?.let { "${it.userMessage}（${it.code}）" }
                    ?: "导出没有成功，请重试。"
                println("FM_EXPORT_FAIL ${engineFailure?.code ?: "FM-E-INTERNAL-001"} ${failure.message}")
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
                note = "相册写入没有成功：${failure.message ?: "请重试"}"
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
                // The engine reports the real canvas only after a render/export; until
                // then the sheet prints the chosen step instead of inventing pixels.
                outputWidth = 0,
                outputHeight = 0,
                canApplyToAll = canApplyToAll,
                onApplyToAll = onApplyToAll,
                onFormat = { update(PhoneExportOptions.applyFormat(settings, it)) },
                onSize = { update(PhoneExportOptions.applySize(settings, it)) },
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
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            PhoneCloseGlyph(tint = FmPalette.onSurfaceSecondary)
        }
    }
}

/** 返修 P2-2: the promise the chosen format really keeps, in the demo's `.pexp-prev` line. */
private fun formatQualityLabel(format: SampleFormat, webpLossless: Boolean): String = when (format) {
    SampleFormat.Jpeg -> "JPG · 质量 100"
    SampleFormat.Png -> "PNG · 无损"
    SampleFormat.WebP -> if (webpLossless) "WebP · 无损" else "WebP · 质量 100"
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
        Box(
            modifier = Modifier
                .size(56.dp)
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
        Text(
            text = if (publishing) "正在重试写入相册…" else "相册里没看到？重试写入相册",
            style = FmType.labelSmall.copy(fontSize = 12.sp),
            color = FmPalette.primaryStrong,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .clickable(enabled = !busy, onClick = onRetryPublish)
                .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.md),
        )
    }
    Spacer(Modifier.height(FmSpacing.md))
    Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
        GhostButton(
            text = if (sharing) "准备分享…" else "分享",
            enabled = !busy,
            modifier = Modifier.weight(1f),
            onClick = onShare,
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
 * + quality, border name), 格式 and 尺寸 as touch segmented controls, the fixed-quality
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
    settings: com.framemark.native.workflow.ExportSettings,
    busy: Boolean,
    sharing: Boolean,
    error: String?,
    note: String?,
    outputWidth: Int,
    outputHeight: Int,
    canApplyToAll: Boolean,
    onApplyToAll: () -> Unit,
    onFormat: (SampleFormat) -> Unit,
    onSize: (PhoneExportSize) -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val size = PhoneExportOptions.sizeOf(settings)
    val format = PhoneExportOptions.formatOf(settings)
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
            sizeLabel = if (outputWidth > 0 && outputHeight > 0) {
                "$outputWidth × $outputHeight"
            } else {
                PhoneExportOptions.sizeCaption(size)
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
                value = if (outputWidth > 0 && outputHeight > 0) "$outputWidth × $outputHeight" else null,
            )
            PhoneSegmented(
                options = PhoneExportOptions.SIZES.map { it.name to it.label },
                selected = size.name,
                onSelect = { name ->
                    PhoneExportOptions.SIZES.firstOrNull { it.name == name }?.let(onSize)
                },
                label = "尺寸",
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ExportNote("Android 导出质量固定为 100")
            // The one option-dependent row keeps a two-line shelf (see
            // PhoneExportSheetLayout): 格式 / 尺寸 may change its text but not the
            // sheet's height.
            ExportNote(
                text = PhoneExportOptions.resolutionNote(size, format, settings.webpLossless),
                minHeight = PhoneExportSheetLayout.NoteShelfHeight,
            )
            if (!native) ExportNote("这张边框的渲染组件不可用，暂时无法导出。")
            if (canApplyToAll) {
                Text(
                    text = "把当前设计用到全部照片",
                    style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                    color = FmPalette.primaryStrong,
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .clickable(enabled = !busy, onClick = onApplyToAll)
                        .padding(vertical = 6.dp),
                )
            }
            if (error != null) {
                Text(text = error, style = FmType.bodyMedium, color = FmPalette.error)
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

/** `SampleFormat.label` is the enum's display name; the sheet prints it verbatim. */
private fun phoneFormatLabel(format: SampleFormat): String = when (format) {
    SampleFormat.Jpeg -> "JPG"
    SampleFormat.Png -> "PNG"
    SampleFormat.WebP -> "WebP"
}

