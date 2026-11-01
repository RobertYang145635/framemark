package com.framemark.native.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framemark.native.library.LibraryCard
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val LONG_EDGES = listOf(4096, 3072, 2048, 1600, 1200, 800)

/**
 * Export sheet: format, size mode, filename and output folder, then the result with
 * 打开所在目录 / 分享. Platform-local output and JPEG preferences are applied
 * at submission; a failed open/share is shown in the result block.
 */
@Composable
fun ExportSheet(
    session: DesignSession,
    card: LibraryCard?,
    onDismiss: () -> Unit,
    autoGenerate: Boolean = false,
    design: DesignSnapshot? = null,
    /** Current photo watermark, read at export time; null keeps the old request. */
    overlay: () -> OverlaySpec? = { null },
    /** Per-call source photo from the project; null keeps the manifest sample. */
    photoPath: String? = null,
    /** 1-based position of [photoPath] in the project, used by `{index}`. */
    photoIndex: Int = 1,
    photoDisplayName: String? = null,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var actionNote by remember { mutableStateOf<String?>(null) }
    var publishingGallery by remember { mutableStateOf(false) }
    var preparingShare by remember { mutableStateOf(false) }
    var qualitySaveError by remember { mutableStateOf<String?>(null) }
    val settings = session.export
    val platform = WorkflowEnv.platform
    var outputDirectory by remember { mutableStateOf(platform?.exportDir ?: "未设置") }
    var qualityText by remember { mutableStateOf((platform?.preferredJpegQuality() ?: 100).toString()) }
    val validQuality = settings.format != SampleFormat.Jpeg || qualityText.toIntOrNull()?.let { it in 60..100 } == true
    var longEdgeText by remember(settings.longEdge) { mutableStateOf(settings.longEdge.toString()) }
    val validLongEdge = longEdgeText.toIntOrNull()?.let { it in 1..20000 } == true
    val native = card != null && NativeEngine.supportsExport(card.id)
    val webpAvailable = native && NativeEngine.supportsWebpExport()
    val formats = if (webpAvailable) SampleFormat.entries.toList() else listOf(SampleFormat.Jpeg, SampleFormat.Png)
    fun busyNow() = session.exporting || publishingGallery || preparingShare
    val busy = busyNow()
    val qualityReady = validQuality && (settings.format != SampleFormat.Jpeg || qualitySaveError == null)
    val visibleResult = session.lastExport?.takeIf { result ->
        val target = card ?: return@takeIf false
        result.source == ExportSource(photoPath,
            design ?: DesignSnapshot(target.id, target.systemStyle, emptyMap(), emptyMap()),
            settings.copy(quality = platform?.preferredJpegQuality() ?: 100), overlay(), photoIndex,
            outputDirectory, photoDisplayName)
    }

    fun update(next: ExportSettings) {
        session.export = next
        error = null
    }

    // A preset saved on desktop may carry WebP; fall back where the encoder is absent.
    LaunchedEffect(webpAvailable) {
        if (!webpAvailable && settings.format == SampleFormat.WebP) {
            update(settings.copy(format = SampleFormat.Jpeg))
        }
    }

    fun generate() {
        if (busyNow() || !validQuality || (session.export.format == SampleFormat.Jpeg && qualitySaveError != null)) return
        session.lastExport = null
        val target = card ?: return
        error = null
        session.exporting = true
        scope.launch {
            try {
                val snapshot = design ?: DesignSnapshot(target.id, target.systemStyle, emptyMap(), emptyMap())
                val effectiveSettings = settings.copy(quality = platform?.preferredJpegQuality() ?: 100)
                session.lastExport = NativeEngine.export(target, effectiveSettings, snapshot, overlay(), photoPath, photoIndex,
                    photoDisplayName = photoDisplayName, outputDirectory = outputDirectory)
                actionNote = null
            } catch (t: Throwable) {
                val failure = t as? EngineFailure
                error = failure?.let { "${it.userMessage}（${it.code}）" }
                    ?: "导出没有成功，请重试。"
                println("FM_EXPORT_FAIL ${failure?.code ?: "FM-E-INTERNAL-001"} ${t.message}")
            } finally {
                session.exporting = false
            }
        }
    }

    // Evidence-only: generate once the preview has reported the photo box, so
    // the overlay rect is built with the engine aspect instead of the default.
    LaunchedEffect(Unit) {
        if (!autoGenerate) return@LaunchedEffect
        val id = card?.id
        var waited = 0
        while (id != null && PreviewGeometry.aspectOf(id) == null && waited < 4000) {
            kotlinx.coroutines.delay(100)
            waited += 100
        }
        generate()
    }

    val contentScroll = rememberScrollState()
    LaunchedEffect(visibleResult, error) {
        if (visibleResult != null || error != null) {
            kotlinx.coroutines.delay(80)
            contentScroll.animateScrollTo(contentScroll.maxValue)
        }
    }

    fun dismissWhenIdle() { if (!busyNow()) onDismiss() }
    FmSheet(onDismiss = ::dismissWhenIdle) {
        SheetTitle(
            title = "导出",
            subtitle = when {
                !native -> "这张边框暂时无法导出，请选择其他边框或检查渲染组件。"
                settings.format == SampleFormat.Png -> "保存为无损 PNG 图片。"
                settings.format == SampleFormat.WebP && settings.webpLossless ->
                    "原生引擎渲染：WebP VP8L 无损 + ICC + EXIF，写入照片目录。"
                settings.format == SampleFormat.WebP ->
                    "原生引擎渲染：WebP VP8 q100 有损 + ICC + EXIF，写入照片目录。"
                else -> "JPEG 质量 ${platform?.preferredJpegQuality() ?: 100} · ICC / EXIF 随成片保存。"
            },
            trailing = { SheetTag(if (native) "原生导出" else "渲染不可用") },
        )
        Spacer(Modifier.height(FmSpacing.lg))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(contentScroll),
            verticalArrangement = Arrangement.spacedBy(FmSpacing.lg),
        ) {
            Column {
                SheetFieldLabel("格式")
                Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
                    formats.forEach { format ->
                        ChoiceChip(
                            label = format.label,
                            selected = settings.format == format,
                            onClick = { update(settings.copy(format = format)) },
                        )
                    }
                }
            }

            Column {
                SheetFieldLabel("尺寸")
                Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
                    ChoiceChip(
                        label = "原尺寸",
                        selected = settings.sizeMode == ExportSizeMode.Original,
                        onClick = { update(settings.copy(sizeMode = ExportSizeMode.Original)) },
                    )
                    ChoiceChip(
                        label = "自定义长边",
                        selected = settings.sizeMode == ExportSizeMode.LongEdge,
                        onClick = { update(settings.copy(sizeMode = ExportSizeMode.LongEdge)) },
                    )
                }
                Spacer(Modifier.height(FmSpacing.xs))
                if (settings.sizeMode == ExportSizeMode.Original) {
                    SheetNote(
                        "保留照片原始像素，画布随边框扩展。少数不支持完整原尺寸的模板会在结果中提示裁切。",
                    )
                } else {
                    Spacer(Modifier.height(FmSpacing.sm))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
                    ) {
                        LONG_EDGES.forEach { edge ->
                            ChoiceChip(
                                label = "$edge px",
                                selected = settings.longEdge == edge,
                                onClick = { update(settings.copy(longEdge = edge)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(FmSpacing.sm))
                    SheetTextField(
                        value = longEdgeText,
                        onValueChange = { raw ->
                            longEdgeText = raw
                            raw.toIntOrNull()?.takeIf { it in 1..20000 }?.let {
                                update(settings.copy(longEdge = it))
                            }
                        },
                        placeholder = "输入长边像素（1–20000）",
                    )
                    if (!validLongEdge) SheetNote("请输入 1–20000 的整数像素。")
                    SheetNote("把长边缩到所选像素后再编码。")
                }
            }

            if (settings.format == SampleFormat.Jpeg) {
                Column {
                    SheetFieldLabel("JPEG 质量")
                    if (platform?.jpegQualityAdjustable == true) {
                        SheetTextField(
                            value = qualityText,
                            onValueChange = { raw ->
                                qualityText = raw
                                raw.toIntOrNull()?.takeIf { it in 60..100 }?.let { value ->
                                    qualitySaveError = platform.setPreferredJpegQuality(value)
                                    error = qualitySaveError
                                    if (qualitySaveError == null) update(settings.copy(quality = value))
                                }
                            },
                            placeholder = "60–100（默认 100）",
                        )
                        SheetNote(if (validQuality) "仅保存在本机，不写入跨端预设。" else "请输入 60–100 的整数。")
                    } else {
                        SheetNote("固定为 100")
                    }
                }
            }

            if (settings.format == SampleFormat.WebP) {
                Column {
                    SheetFieldLabel("WebP 编码")
                    Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
                        ChoiceChip(
                            label = "无损（默认）",
                            selected = settings.webpLossless,
                            onClick = { update(settings.copy(webpLossless = true)) },
                        )
                        ChoiceChip(
                            label = "有损 q100",
                            selected = !settings.webpLossless,
                            onClick = { update(settings.copy(webpLossless = false)) },
                        )
                    }
                    Spacer(Modifier.height(FmSpacing.xs))
                    SheetNote(
                        if (settings.webpLossless) {
                            "VP8L 无损：像素与渲染结果一致；ICC/EXIF 与 JPEG 同策略，文件大小取决于画面。"
                        } else {
                            "VP8 有损 q100：不是无损；ICC/EXIF 与 JPEG 同策略，文件更小。"
                        },
                    )
                    Spacer(Modifier.height(FmSpacing.xs))
                    SheetNote(
                        "大图使用原尺寸导出可能需要较长时间。可选择长边尺寸，减少等待和文件大小。",
                    )
                }
            }

            Column {
                SheetFieldLabel("文件名")
                SheetTextField(
                    value = settings.filenamePattern,
                    onValueChange = { update(settings.copy(filenamePattern = it)) },
                    placeholder = "{photo}-{index}",
                )
                Spacer(Modifier.height(FmSpacing.xs))
                SheetNote("可用 {photo} 原照片名 · {index} 序号 · {name} 边框名 · {date} {time}")
            }

            Column {
                SheetFieldLabel("输出位置")
                Text(
                    text = outputDirectory,
                    style = FmType.bodyMedium,
                    color = FmPalette.onSurfaceSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FmRadii.input))
                        .background(FmPalette.surfaceMuted)
                        .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input))
                        .padding(horizontal = FmSpacing.md, vertical = 10.dp),
                )
                Spacer(Modifier.height(FmSpacing.xs))
                SheetNote(if (native) "导出文件写入上面的目录。" else "这张边框的渲染组件不可用，暂时无法导出。")
                if (platform?.canChooseExportDirectory == true) {
                    GhostButton(text = "选择输出目录", onClick = {
                        runCatching { platform.chooseExportDirectory() }
                            .onSuccess { selected -> if (selected != null) outputDirectory = selected }
                            .onFailure { error = "选择输出目录失败：${it.message}" }
                    })
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FmRadii.card))
                    .background(FmPalette.surfaceMuted)
                    .padding(FmSpacing.lg),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (native) "将导出 1 张照片" else "暂时无法导出",
                        style = FmType.bodyMedium,
                        color = FmPalette.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${settings.format.label} · " +
                            if (settings.sizeMode == ExportSizeMode.Original) {
                                "原尺寸"
                            } else {
                                "长边 ${settings.longEdge} px"
                            } +
                            if (settings.format == SampleFormat.WebP) {
                                if (settings.webpLossless) " · 无损" else " · 有损 q100"
                            } else {
                                ""
                            },
                        style = FmType.labelSmall,
                        color = FmPalette.onSurfaceMuted,
                    )
                }
                Spacer(Modifier.height(FmSpacing.xs))
                Text(
                    text = ExportNaming.previewName(
                        pattern = settings.filenamePattern,
                        cardName = card?.name ?: "示例",
                        photoName = ExportNaming.photoNameFor(
                            settings.filenamePattern,
                            ExportNaming.photoStem(photoDisplayName ?: photoPath),
                            card?.id ?: "sample",
                        ),
                        index = photoIndex,
                    ) + ".${settings.format.extension}",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val result = visibleResult
            if (result != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FmRadii.card))
                        .background(FmPalette.surface)
                        .border(1.dp, FmPalette.primary.copy(alpha = 0.35f), RoundedCornerShape(FmRadii.card))
                        .padding(FmSpacing.lg),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已导出", style = FmType.titleSmall, color = FmPalette.onSurface)
                        Spacer(Modifier.weight(1f))
                        SheetTag(if (result.note == "预览图副本") "预览副本" else "已生成")
                    }
                    Spacer(Modifier.height(FmSpacing.xs))
                    Text(
                        text = result.fileName,
                        style = FmType.bodyMedium,
                        color = FmPalette.onSurfaceSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(FmSpacing.xxs))
                    Text(
                        text = resultMeta(result),
                        style = FmType.labelSmall,
                        color = FmPalette.onSurfaceMuted,
                    )
                    Spacer(Modifier.height(FmSpacing.xs))
                    Text(
                        text = result.path,
                        style = FmType.labelSmall,
                        color = FmPalette.onSurfaceFaint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (result.note?.contains("相册写入失败") == true) {
                        GhostButton(text = if (publishingGallery) "正在写入相册…" else "重试写入相册", enabled = !busy, onClick = {
                            if (busyNow()) return@GhostButton
                            publishingGallery = true
                            scope.launch {
                                try {
                                    val note = WorkflowEnv.nativeRender?.exportCommitted(result.path)
                                    if (session.lastExport?.path == result.path) {
                                        session.lastExport = result.copy(note = note ?: result.note)
                                    }
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    actionNote = "相册写入没有成功：${failure.message ?: "请重试"}"
                                } finally {
                                    publishingGallery = false
                                }
                            }
                        })
                    }
                    Spacer(Modifier.height(FmSpacing.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GhostButton(
                            text = "打开所在目录",
                            onClick = {
                                actionNote = if (platform == null) "存储不可用" else platform.revealInFolder(result.path)
                            },
                        )
                        Spacer(Modifier.width(FmSpacing.sm))
                        GhostButton(
                            text = if (preparingShare) "准备分享…" else "分享",
                            enabled = !busy,
                            onClick = {
                                if (busyNow()) return@GhostButton
                                preparingShare = true
                                scope.launch {
                                    try {
                                        actionNote = withContext(Dispatchers.IO) {
                                            if (platform == null) "存储不可用" else platform.shareFile(result.path)
                                        }
                                    } finally { preparingShare = false }
                                }
                            },
                        )
                        if (actionNote != null) {
                            Spacer(Modifier.width(FmSpacing.sm))
                            Text(
                                text = actionNote.orEmpty(),
                                style = FmType.labelSmall,
                                color = FmPalette.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (error != null) {
                Text(
                    text = error.orEmpty(),
                    style = FmType.bodyMedium,
                    color = FmPalette.error,
                )
            }
        }

        Spacer(Modifier.height(FmSpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
            GhostButton(text = "关闭", enabled = !busy, onClick = ::dismissWhenIdle)
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                text = when {
                    session.exporting -> if (native) "正在导出…" else "正在生成…"
                    preparingShare -> "准备分享…"
                    publishingGallery -> "正在写入相册…"
                    native -> if (visibleResult != null) "再导出一张" else "导出"
                    else -> "渲染不可用"
                },
                enabled = !busy && card != null && native && qualityReady &&
                    (settings.sizeMode != ExportSizeMode.LongEdge || validLongEdge),
                modifier = Modifier.probeBounds("export.action"),
                onClick = { generate() },
            )
        }
    }
}

private fun resultMeta(result: ExportResult): String {
    val parts = mutableListOf<String>()
    if (result.width != null && result.height != null) parts.add("${result.width} × ${result.height}")
    parts.add(result.formatLabel)
    parts.add(formatBytes(result.bytes))
    result.note?.let { parts.add(it) }
    return parts.joinToString(" · ")
}

private fun formatBytes(bytes: Int): String {
    val kb = bytes / 1024.0
    return if (kb >= 1024) {
        val mb = (kb / 1024.0 * 10).roundToInt() / 10.0
        "$mb MB"
    } else {
        val rounded = (kb * 10).roundToInt() / 10.0
        "$rounded KB"
    }
}
