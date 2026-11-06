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
 * The desktop keeps `workflow/ExportSheet.kt`; this sheet is the phone one, so the
 * phone can drop the quality field and the long-edge text field without changing
 * the desktop dialog D1/D2 own.
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

    FmSheet(onDismiss = { if (!busyNow) onDismiss() }) {
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

private val PreviewWidth = 96.dp
private val PreviewHeight = 80.dp

/** Preview + the three facts the demo prints next to it (size, format, border). */
@Composable
private fun ExportPreviewRow(
    card: LibraryCard?,
    design: DesignSnapshot?,
    overlay: () -> OverlaySpec?,
    photoPath: String?,
    /** 返修 P2-2: the badge only claims 原分辨率 on the 原尺寸 step. */
    resolutionLabel: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.card))
            .background(FmPalette.surfaceMuted)
            .padding(FmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = PreviewWidth, height = PreviewHeight)
                .clip(RoundedCornerShape(FmRadii.tile))
                .background(FmPalette.stage),
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
        Spacer(Modifier.width(FmSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = card?.name ?: "未选边框",
                style = FmType.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = resolutionLabel,
                style = FmType.labelSmall.copy(fontSize = 11.5.sp),
                color = FmPalette.onSurfaceSecondary,
            )
            Text(
                text = "质量 100 · 写入相册后可直接分享",
                style = FmType.labelSmall.copy(fontSize = 11.5.sp),
                color = FmPalette.onSurfaceMuted,
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
        modifier = Modifier
            .weight(1f)
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
            color = FmPalette.onSurfaceFaint,
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

@Composable
private fun ColumnScope.ExportFormContent(
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
    SheetTitle(
        title = "导出",
        subtitle = if (native) "预览就是导出的样子；保存后写入相册。" else "这张边框的渲染组件不可用，暂时无法导出。",
        trailing = { SheetTag(if (native) "原生导出" else "渲染不可用") },
    )
    Spacer(Modifier.height(FmSpacing.lg))
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(FmSpacing.lg),
    ) {
        ExportPreviewRow(
            card = card,
            design = design,
            overlay = overlay,
            photoPath = photoPath,
            resolutionLabel = if (size == PhoneExportSize.Original) {
                PhoneExportOptions.ORIGINAL_RESOLUTION_BADGE
            } else {
                "${PhoneExportOptions.sizeCaption(size)} · 质量 100"
            },
        )
        Column {
            SheetFieldLabel("格式")
            Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
                PhoneExportOptions.FORMATS.forEach { candidate ->
                    ChoiceChip(
                        label = candidate.label,
                        selected = format == candidate,
                        onClick = { onFormat(candidate) },
                    )
                }
            }
        }
        Column {
            SheetFieldLabel("尺寸")
            Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
                PhoneExportOptions.SIZES.forEach { candidate ->
                    ChoiceChip(
                        label = candidate.label,
                        selected = size == candidate,
                        onClick = { onSize(candidate) },
                    )
                }
            }
            Spacer(Modifier.height(FmSpacing.sm))
            // 返修 P2-2: per-format and per-size wording; the encoder is named.
            SheetNote(PhoneExportOptions.resolutionNote(size, format, settings.webpLossless))
        }
        SheetNote(PhoneExportOptions.formatNote(format, settings.webpLossless))
        if (canApplyToAll) {
            GhostButton(text = "把当前设计用到全部照片", enabled = !busy, onClick = onApplyToAll)
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
    Spacer(Modifier.height(FmSpacing.md))
    Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
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
    Spacer(Modifier.height(FmSpacing.xs))
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = "关闭",
            style = FmType.labelMedium,
            color = FmPalette.onSurfaceMuted,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .clickable(enabled = !busy, onClick = onClose)
                .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.md),
        )
    }
}
