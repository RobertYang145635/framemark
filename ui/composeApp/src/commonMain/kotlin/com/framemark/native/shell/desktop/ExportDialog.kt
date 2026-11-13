package com.framemark.native.shell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.framemark.native.inspector.FmSlider
import com.framemark.native.inspector.withNumericDigits
import com.framemark.native.library.LibraryCard
import com.framemark.native.product.PhotoRef
import com.framemark.native.shell.displayPixels
import com.framemark.native.theme.FmElevation
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.theme.columnRule
import com.framemark.native.theme.fmElevated
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.EngineFailure
import com.framemark.native.workflow.ExportNaming
import com.framemark.native.workflow.ExportResult
import com.framemark.native.product.ExportFormat
import com.framemark.native.product.ExportSettings
import com.framemark.native.product.ExportSizeMode
import com.framemark.native.product.NoticeText
import com.framemark.native.workflow.ImageRect
import com.framemark.native.workflow.NativeEngine
import com.framemark.native.workflow.NativeStagePreview
import com.framemark.native.workflow.OverlaySpec
import com.framemark.native.workflow.PreviewGeometry
import com.framemark.native.workflow.WorkflowEnv
import com.framemark.native.workflow.DesignSnapshot
import kotlinx.coroutines.launch
import com.framemark.native.product.FmLog

/**
 * WP-D2 导出对话框的公共外观件。桌面弹窗里的按钮/分段控件比手机薄一档
 * （32 dp/28 dp），与 52 dp 顶栏同一个密度；颜色全部走令牌，深色下描边由
 * [fmElevated] 负责，没有写死的颜色。
 */
internal enum class DlgButtonStyle { Primary, Line, Ghost }

@Composable
internal fun DlgButton(
    text: String,
    onClick: () -> Unit,
    style: DlgButtonStyle = DlgButtonStyle.Line,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(FmRadii.chip)
    val background = when {
        !enabled -> FmPalette.surfaceMuted
        style == DlgButtonStyle.Primary -> FmPalette.primary
        style == DlgButtonStyle.Line -> FmPalette.surface
        else -> Color.Transparent
    }
    val foreground = when {
        !enabled -> FmPalette.onSurfaceFaint
        style == DlgButtonStyle.Primary -> FmPalette.onPrimary
        style == DlgButtonStyle.Ghost -> FmPalette.onSurfaceMuted
        else -> FmPalette.onSurfaceSecondary
    }
    Box(
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, if (style == DlgButtonStyle.Line && enabled) FmPalette.outline else Color.Transparent, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = FmSpacing.md, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = FmType.labelMedium, color = foreground, maxLines = 1)
    }
}

/** `.seg` 两段式选择（格式 / 尺寸）：槽 `--seg`、2 dp 内边距、圆角 8；按钮 h28、12.5 px。 */
@Composable
internal fun DlgSegment(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(FmPalette.surfaceMuted)
            .semantics { contentDescription = description }
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(FmRadii.input))
                    .background(if (on) FmPalette.surface else Color.Transparent)
                    .clickable(role = Role.RadioButton, onClick = { onSelect(key) })
                    .semantics { stateDescription = if (on) "已选" else "未选" }
                    .padding(horizontal = FmSpacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp),
                    color = if (on) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                    fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Medium else null,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun DlgNote(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = FmType.labelSmall,
        color = if (error) FmPalette.error else FmPalette.onSurfaceMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun DlgTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    aria: String? = null,
) {
    val shape = RoundedCornerShape(FmRadii.chip)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = enabled,
        textStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp).withNumericDigits()
            .copy(color = if (enabled) FmPalette.onSurface else FmPalette.onSurfaceFaint),
        cursorBrush = SolidColor(FmPalette.primary),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outlineStrong, shape)
            .then(if (aria != null) Modifier.semantics { contentDescription = aria } else Modifier)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, style = FmType.bodyMedium, color = FmPalette.onSurfaceFaint, maxLines = 1)
                }
                inner()
            }
        },
    )
}

/** 引擎回调：这张预览图的像素尺寸，以及照片框在这张图里的像素矩形。 */
private data class PreviewFrame(val width: Int, val height: Int, val box: ImageRect?)

/**
 * V2 导出对话框（报告 §6 / `desktop.js` 的导出弹窗）：左成品预览、右格式/尺寸/
 * 质量/文件名/位置，导出完成后切到结果页，给出「打开文件夹 / 再导出 / 回到编辑」。
 *
 * 引擎调用就是既有的 `NativeEngine.export(...)`，导出管线、数据模型、项目和预设
 * 格式都没有改；文案见 [ExportCopy]，参数校验见 [ExportRules]。
 */
@Composable
fun DesktopExportDialog(
    session: DesignSession,
    card: LibraryCard?,
    design: DesignSnapshot?,
    photo: PhotoRef?,
    photoIndex: Int,
    overlay: () -> OverlaySpec?,
    onDismiss: () -> Unit,
    autoRun: Boolean = false,
    /**
     * 成片画布（未按长边缩放），由 Shell 用与右栏/顶栏同一个 [OutputSize] 算好传进来。
     * 为 null 时退回「预览照片框占比」的测量估算（厂商规格卡没有画布参数）。
     */
    canonicalCanvas: com.framemark.native.inspector.OutputPixels? = null,
) {
    val scope = rememberCoroutineScope()
    val platform = WorkflowEnv.platform
    val settings = session.export
    var qualityText by remember(settings.quality) { mutableStateOf(settings.quality.toString()) }
    var longEdgeText by remember(settings.longEdge) {
        mutableStateOf((settings.longEdge ?: ExportRules.LONG_EDGE_DEFAULT).toString())
    }
    var outputDirectory by remember { mutableStateOf(platform?.exportDir ?: "未设置") }
    var result by remember { mutableStateOf<ExportResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionNote by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    // Whether this photo can contribute HDR at all. Read off the UI thread: the file
    // can be tens of megabytes and only its header segments are needed.
    var hdrAvailable by remember(photo?.path) { mutableStateOf(false) }
    LaunchedEffect(photo?.path) {
        val path = photo?.path
        hdrAvailable = if (path.isNullOrBlank()) {
            false
        } else {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val bytes = runCatching { platform?.readBytes(path) }.getOrNull()
                bytes != null && com.framemark.native.product.UltraHdr.locate(bytes) != null
            }
        }
    }
    var frame by remember(card?.id, design, photo?.path) { mutableStateOf<PreviewFrame?>(null) }

    val webpAvailable = card != null && NativeEngine.supportsWebpExport()
    val formats = if (webpAvailable) ExportFormat.entries.toList() else listOf(ExportFormat.Jpeg, ExportFormat.Png)
    val qualityApplies = ExportRules.qualityApplies(settings.format, settings.webpLossless)
    val qualityError = if (qualityApplies) ExportRules.qualityError(qualityText) else null
    val longEdgeError = if (settings.sizeMode == ExportSizeMode.LongEdge) ExportRules.longEdgeError(longEdgeText) else null
    val effectiveLongEdge = ExportRules.parseLongEdge(longEdgeText)
        ?: settings.longEdge
        ?: ExportRules.LONG_EDGE_DEFAULT
    val editable = card != null && NativeEngine.supportsExport(card.id)
    val canExport = editable && qualityError == null && longEdgeError == null && !exporting
    val qualityValue = ExportRules.parseQuality(qualityText) ?: ExportRules.QUALITY_DEFAULT
    // 画布优先用 Shell 传来的同一份公式结果；只有拿不到时才退回测量估算。
    // FIX1: 估算也用**显示**尺寸（EXIF 方向已应用），预览帧画的是解码后的画面。
    val photoDisplay = photo?.displayPixels()
    val measuredCanvas = ExportEstimate.canvas(
        photoWidthPx = photoDisplay?.width ?: 0,
        photoHeightPx = photoDisplay?.height ?: 0,
        previewPhotoBox = frame?.box,
        previewWidthPx = frame?.width ?: 0,
        previewHeightPx = frame?.height ?: 0,
    )
    val canvas = canonicalCanvas?.let { ExportPixels(it.width, it.height) } ?: measuredCanvas
    val outPixels = ExportEstimate.output(canvas, settings.sizeMode, effectiveLongEdge)

    fun runExport() {
        val target = card ?: return
        if (!canExport) return
        error = null
        actionNote = null
        exporting = true
        scope.launch {
            try {
                val snapshot = design ?: DesignSnapshot(target.id, target.systemStyle, emptyMap(), emptyMap())
                // 读**当下**的设置，而不是首次组合时闭包里捕获的那份：导出弹层可以在
                // 项目水合（`session.setExportSilently`）之前就打开（证据钩子
                // `FM_OPEN_EXPORT` 就是这样），用旧值会把「指定长边」当成原尺寸，
                // D-1 复现里就是这么发生的。
                // 长边与质量都不再读闭包里的文本框状态：`onLongEdgeText` /
                // `onQualityText` 每次合法输入都会先写回 `session.export`，那份才是
                // 唯一的当下值（文本状态对象在重组后可能已经被替换）。
                val current = session.export
                val effective = current.copy(
                    quality = ExportRules.effectiveQuality(
                        current.quality.toString(),
                        current.format,
                        current.webpLossless,
                    ),
                    // A document may have chosen 指定长边 before any number was stored
                    // (the product default is null = "not chosen"). Export exactly the
                    // number the form shows instead of silently keeping the native size.
                    longEdge = if (current.sizeMode == ExportSizeMode.LongEdge) {
                        current.longEdge ?: effectiveLongEdge
                    } else {
                        current.longEdge
                    },
                )
                // D-1/D-2 的证据行：一次导出把「界面显示」「实际下单」两套数都打出来，
                // 出片后再用像素/字节/SHA 对账。
                FmLog.info("FM_DESKTOP_EXPORT_RUN") {
                    "card=${target.id} format=${current.format.extension} " +
                        "quality=${effective.quality} sizeMode=${current.sizeMode} " +
                        "longEdge=${effective.effectiveLongEdge ?: "-"} " +
                        "photo=${photo?.widthPx ?: 0}x${photo?.heightPx ?: 0}"
                }
                val produced = NativeEngine.export(
                    card = target,
                    settings = effective,
                    design = snapshot,
                    overlay = overlay(),
                    photoPath = photo?.path,
                    nameIndex = photoIndex,
                    photoDisplayName = photo?.displayName,
                    outputDirectory = outputDirectory,
                )
                result = produced
                session.lastExport = produced
                FmLog.info("FM_DESKTOP_EXPORT") { "path=${produced.path} ${produced.width}x${produced.height} bytes=${produced.bytes}" }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                val failure = t as? EngineFailure
                // The support code stays in the log line below, not in the sentence.
                error = failure?.userMessage ?: "导出没有成功，请重试。"
                FmLog.warn("FM_DESKTOP_EXPORT_FAIL") { "${failure?.code ?: "FM-E-INTERNAL-001"} ${t.message}" }
            } finally {
                exporting = false
            }
        }
    }

    // Evidence-only: `FM_RUN_EXPORT=1` 先等预览报出照片框，再按真实比例写一次导出。
    LaunchedEffect(Unit) {
        if (!autoRun) return@LaunchedEffect
        val id = card?.id
        var waited = 0
        while (id != null && PreviewGeometry.aspectOf(id) == null && waited < 4000) {
            kotlinx.coroutines.delay(100)
            waited += 100
        }
        if (result == null && !exporting) runExport()
    }

    val shape = RoundedCornerShape(FmRadii.sheet)
    Dialog(
        onDismissRequest = { if (!exporting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // `styles.css:466` `.dialog { width: 680px }`；高度按内容收，上限 600。
            val dialogWidth = minOf(maxWidth * 0.92f, 680.dp)
            val dialogHeight = minOf(maxHeight * 0.92f, 600.dp)
            Column(
                modifier = Modifier
                    .width(dialogWidth)
                    .height(dialogHeight)
                    .clip(shape)
                    .fmElevated(FmElevation.nav, shape)
                    .background(FmPalette.surface),
            ) {
                ExportDialogHeader(
                    subtitle = (photo?.displayName ?: "示例照片") + " · " +
                        (card?.name ?: "原图"),
                    onClose = { if (!exporting) onDismiss() },
                )
                if (result != null && !exporting) {
                    ExportResultPage(
                        result = result!!,
                        directory = outputDirectory,
                        actionNote = actionNote,
                        modifier = Modifier.weight(1f),
                        onReveal = {
                            val produced = result ?: return@ExportResultPage
                            actionNote = platform?.revealInFolder(produced.path) ?: "当前平台不能打开所在目录"
                        },
                        onExportAgain = {
                            result = null
                            error = null
                            actionNote = null
                        },
                        onBackToEdit = onDismiss,
                    )
                } else {
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 18.dp),
                    ) {
                        ExportPreviewColumn(
                            card = card,
                            design = design,
                            overlay = overlay,
                            photo = photo,
                            settings = settings,
                            qualityValue = qualityValue,
                            previewPixels = outPixels,
                            modifier = Modifier.width(220.dp).fillMaxHeight(),
                            onFrame = { width, height, box -> frame = PreviewFrame(width, height, box) },
                        )
                        Spacer(Modifier.width(22.dp))
                        ExportFormColumn(
                            settings = settings,
                            formats = formats,
                            qualityText = qualityText,
                            qualityError = qualityError,
                            qualityValue = qualityValue,
                            longEdgeText = longEdgeText,
                            longEdgeError = longEdgeError,
                            directory = outputDirectory,
                            pixels = outPixels,
                            crop = ExportEstimate.cropEstimate(
                                photoWidthPx = photo?.displayPixels()?.width ?: 0,
                                photoHeightPx = photo?.displayPixels()?.height ?: 0,
                                previewPhotoBox = frame?.box,
                            ),
                            cardName = card?.name ?: "边框",
                            photoStem = ExportNaming.photoStem(photo?.displayName ?: photo?.path),
                            photoIndex = photoIndex,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onChange = { next ->
                                // Switching to 指定长边 pins the number shown in the field,
                                // so the session never holds "long edge mode, no value".
                                session.export = if (
                                    next.sizeMode == ExportSizeMode.LongEdge && next.longEdge == null
                                ) {
                                    next.copy(
                                        longEdge = ExportRules.parseLongEdge(longEdgeText)
                                            ?: ExportRules.LONG_EDGE_DEFAULT,
                                    )
                                } else {
                                    next
                                }
                                error = null
                            },
                            onQualityText = { raw ->
                                qualityText = raw
                                ExportRules.parseQuality(raw)?.let { value ->
                                    session.export = settings.copy(quality = value)
                                    platform?.setPreferredJpegQuality(value)
                                }
                            },
                            onLongEdgeText = { raw ->
                                longEdgeText = raw
                                ExportRules.parseLongEdge(raw)?.let { value ->
                                    session.export = settings.copy(longEdge = value)
                                }
                            },
                            onChooseDirectory = {
                                runCatching { platform?.chooseExportDirectory() }
                                    .onSuccess { picked ->
                                        if (picked != null) {
                                            outputDirectory = picked
                                            actionNote = "输出目录已记住，下次导出仍用它。"
                                        }
                                    }
                                    .onFailure {
                                        error = NoticeText.failure(
                                            "FM_DESKTOP_EXPORT_FAIL",
                                            it,
                                            "选择输出目录失败，请重试。",
                                        )
                                    }
                            },
                        )
                    }
                }
                if (result == null || exporting) {
                    ExportDialogFooter(
                        exporting = exporting,
                        canExport = canExport,
                        error = error,
                        qualityNote = "Windows 质量可调 ${ExportRules.QUALITY_MIN}–${ExportRules.QUALITY_MAX}，默认 ${ExportRules.QUALITY_DEFAULT}；Android 固定 100。",
                        hdrAvailable = hdrAvailable && settings.format == ExportFormat.Jpeg,
                        preserveHdr = settings.preserveHdr,
                        onPreserveHdr = { enabled -> session.export = settings.copy(preserveHdr = enabled) },
                        onCancel = { if (!exporting) onDismiss() },
                        onExport = { runExport() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportDialogHeader(subtitle: String, onClose: () -> Unit) {
    // `.dlg-h`：16/700 标题 + 12.5 副题同一行，右侧关闭；padding 16 16 14 22。
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "导出照片",
            style = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold),
            color = FmPalette.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = subtitle,
            style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(FmRadii.chip))
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = "关闭导出对话框" },
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", style = FmType.labelMedium, color = FmPalette.onSurfaceMuted)
        }
    }
}

/** 左列（`.dlg-prev`）：暗底成品预览 + `.dspec` 两行数值 + 原图尺寸。 */
@Composable
private fun ExportPreviewColumn(
    card: LibraryCard?,
    design: DesignSnapshot?,
    overlay: () -> OverlaySpec?,
    photo: PhotoRef?,
    settings: ExportSettings,
    qualityValue: Int,
    previewPixels: ExportPixels?,
    modifier: Modifier,
    onFrame: (Int, Int, ImageRect?) -> Unit,
) {
    val estimate = ExportEstimate.estimatedBytes(
        previewPixels,
        settings.format,
        qualityValue,
        settings.webpLossless,
    )
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(FmPalette.stageDark)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                card != null && design != null && NativeEngine.supports(card.id) -> NativeStagePreview(
                    card = card,
                    design = design,
                    overlay = overlay(),
                    photoPath = photo?.path,
                    modifier = Modifier.fillMaxSize(),
                    onRendered = { width, height, box -> onFrame(width, height, box) },
                )
                card != null -> Text(
                    "这张边框没有接入实时预览，导出仍按同一模板渲染",
                    style = FmType.labelSmall,
                    color = FmPalette.stageMuted,
                    textAlign = TextAlign.Center,
                )
                else -> Text(
                    "这张照片还没有边框",
                    style = FmType.labelSmall,
                    color = FmPalette.stageMuted,
                )
            }
        }
        // `.dlg-prev .dspec`：尺寸 13/600，其余 12/400，全部 `--num`。
        Text(
            text = previewPixels?.let { "约 $it" } ?: "成片尺寸待定",
            style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
                .withNumericDigits(),
            color = FmPalette.onSurface,
        )
        Text(
            text = formatBadge(settings, qualityValue) +
                (estimate?.let { " · 约 " + ExportEstimate.formatBytes(it) } ?: ""),
            style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp).withNumericDigits(),
            color = FmPalette.onSurfaceMuted,
        )
        Text(
            text = if (photo != null) {
                // FIX1: 「原图」写解码后的显示尺寸，与预览 / 成片同口径。
                val display = photo.displayPixels()
                "原图 ${display.width} × ${display.height} px"
            } else {
                "示例照片"
            },
            style = TextStyle(fontSize = 11.sp, lineHeight = 16.sp).withNumericDigits(),
            color = FmPalette.onSurfaceMuted,
        )
        Text(
            text = "预览就是成片版式：同一模板、同一照片；成片像素以结果页为准。",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
        )
    }
}

private fun formatBadge(settings: ExportSettings, quality: Int): String = when (settings.format) {
    ExportFormat.Jpeg -> "JPG · 质量 $quality"
    ExportFormat.Png -> "PNG · 无损"
    ExportFormat.WebP -> if (settings.webpLossless) "WebP · 无损" else "WebP · 质量 $quality"
}

/**
 * 右列（`.form` / `.field`）：72 px 标签列 + 控件列，行距 14。
 * 字段顺序与演示稿一致：格式 / 尺寸 / 质量 / 文件名 / 位置。
 */
@Composable
private fun ExportFormColumn(
    settings: ExportSettings,
    formats: List<ExportFormat>,
    qualityText: String,
    qualityError: String?,
    qualityValue: Int,
    longEdgeText: String,
    longEdgeError: String?,
    directory: String,
    pixels: ExportPixels?,
    /** 原尺寸档会不会被模板裁掉照片（A-19）；null = 不会裁。 */
    crop: CropEstimate?,
    cardName: String,
    photoStem: String?,
    photoIndex: Int,
    modifier: Modifier,
    onChange: (ExportSettings) -> Unit,
    onQualityText: (String) -> Unit,
    onLongEdgeText: (String) -> Unit,
    onChooseDirectory: () -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ExportField("格式") {
            Column {
                DlgSegment(
                    options = formats.map { it.extension to it.label },
                    selected = settings.format.extension,
                    onSelect = { key ->
                        formats.firstOrNull { it.extension == key }?.let { onChange(settings.copy(format = it)) }
                    },
                    description = "导出格式",
                )
                Spacer(Modifier.height(4.dp))
                DlgNote(ExportCopy.encoderLine(settings.format, qualityValue, settings.webpLossless))
            }
        }

        ExportField("尺寸") {
            Column {
                DlgSegment(
                    options = listOf("original" to "原尺寸", "longEdge" to "指定长边"),
                    selected = if (settings.sizeMode == ExportSizeMode.Original) "original" else "longEdge",
                    onSelect = { key ->
                        onChange(
                            settings.copy(
                                sizeMode = if (key == "original") ExportSizeMode.Original else ExportSizeMode.LongEdge,
                            ),
                        )
                    },
                    description = "导出尺寸",
                )
                if (settings.sizeMode == ExportSizeMode.LongEdge) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DlgTextField(
                            value = longEdgeText,
                            onValueChange = onLongEdgeText,
                            placeholder = "2048",
                            modifier = Modifier.width(104.dp),
                            aria = "长边像素",
                        )
                        Text(
                            text = "px",
                            style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp).withNumericDigits(),
                            color = FmPalette.onSurfaceMuted,
                        )
                        ExportRules.LONG_EDGE_PRESETS.forEach { preset ->
                            ExportTokenChip("$preset") { onLongEdgeText(preset.toString()) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    DlgNote(
                        longEdgeError ?: "长边归一到所选像素后再编码（小于该值时放大）；${ExportRules.LONG_EDGE_MIN} – ${ExportRules.LONG_EDGE_MAX}。",
                        error = longEdgeError != null,
                    )
                    if (crop != null) {
                        DlgNote(crop.text)
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                    // AU2 A-19：固定比例模板会按自己的版式裁照片，`--fit native` 被
                    // 引擎拒绝后回退到裁切；这里在**导出前**就说实话，不再只写在结果页。
                    if (crop != null) {
                        DlgNote(crop.text)
                    } else {
                        DlgNote("保留照片原始像素，画布随边框扩展。")
                    }
                }
            }
        }

        if (ExportRules.qualityApplies(settings.format, settings.webpLossless)) {
            ExportField("质量") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FmSlider(
                            value = qualityValue.toFloat(),
                            onValueChange = { onQualityText(it.toInt().toString()) },
                            valueRange = ExportRules.QUALITY_MIN.toFloat()..ExportRules.QUALITY_MAX.toFloat(),
                            step = 1f,
                            defaultValue = ExportRules.QUALITY_DEFAULT.toFloat(),
                            modified = qualityValue != ExportRules.QUALITY_DEFAULT,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .height(26.dp)
                                .width(64.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(FmPalette.surfaceMuted)
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Text(
                                text = "$qualityValue",
                                style = TextStyle(fontSize = 11.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
                                    .withNumericDigits(),
                                color = FmPalette.onSurface,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    DlgNote(
                        qualityError ?: "默认 ${ExportRules.QUALITY_DEFAULT}。只保存在本机，不写入跨端预设。",
                        error = qualityError != null,
                    )
                }
            }
        }

        ExportField("文件名", top = true) {
            Column {
                DlgTextField(
                    value = settings.filenamePattern,
                    onValueChange = { onChange(settings.copy(filenamePattern = it)) },
                    placeholder = "{photo}-{index}",
                    aria = "文件名模板",
                )
                Spacer(Modifier.height(6.dp))
                // `.tokens`：+照片名 / +序号 / …，h24、圆角 6、`--plate`。
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    val tokens = ExportNameToken.entries
                    tokens.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            row.forEach { token ->
                                ExportTokenChip(token.label) {
                                    val next = ExportFileName.insertToken(
                                        pattern = settings.filenamePattern,
                                        tokenKey = token.key,
                                        selectionStart = settings.filenamePattern.length,
                                        selectionEnd = settings.filenamePattern.length,
                                    )
                                    onChange(settings.copy(filenamePattern = next))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "将保存为 ",
                        style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                        color = FmPalette.onSurfaceMuted,
                        maxLines = 1,
                    )
                    Text(
                        text = ExportFileName.previewFile(
                            pattern = settings.filenamePattern,
                            cardName = cardName,
                            photoStem = photoStem,
                            index = photoIndex,
                            extension = settings.format.extension,
                        ),
                        style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
                            .withNumericDigits(),
                        color = FmPalette.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        ExportField("位置") {
            Column {
                // `.inp.ro`：只读路径 + 「更改…」在同一个框里。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(FmPalette.surfaceMuted)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = directory,
                        style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp).withNumericDigits(),
                        color = FmPalette.onSurfaceSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "更改…",
                        style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                        color = FmPalette.primaryStrong,
                        maxLines = 1,
                        modifier = Modifier
                            .heightIn(min = 24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button, onClick = onChooseDirectory)
                            .padding(horizontal = 6.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                DlgNote("目录会记住，下次仍用它；同名文件不会被覆盖（自动加 -2）。")
            }
        }

        ExportCopy.metadataLines(settings.format).forEach { line -> DlgNote(line) }
    }
}

/** `.field`：72 px 标签列 + 控件列。 */
@Composable
private fun ExportField(label: String, top: Boolean = false, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = if (top) Alignment.Top else Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
            modifier = Modifier
                .width(72.dp)
                .then(if (top) Modifier.padding(top = 6.dp) else Modifier),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

/** `.tokens button`：h24、圆角 6、`--plate`、12 px，前缀「+」。 */
@Composable
private fun ExportTokenChip(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(FmPalette.surfaceMuted)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "插入 $label" }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "+",
            style = TextStyle(fontSize = 12.sp, lineHeight = 14.sp).withNumericDigits(),
            color = FmPalette.onSurfaceFaint,
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = label,
            style = TextStyle(fontSize = 12.sp, lineHeight = 14.sp),
            color = FmPalette.onSurfaceSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun ExportDialogFooter(
    exporting: Boolean,
    canExport: Boolean,
    error: String?,
    qualityNote: String,
    hdrAvailable: Boolean,
    preserveHdr: Boolean,
    onPreserveHdr: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit,
) {
    // `.dlg-f`：`--muted-bg` 底 + 1 px `--line` 上边线，padding 14 22。
    Column(Modifier.fillMaxWidth()) {
        // 规则 1：对话框内的条带（标题 / 内容 / 页脚）之间是通栏发线。
        Box(Modifier.columnRule())
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(FmPalette.surfaceMuted)
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                // Ultra HDR is offered only when the photo really carries a gain map
                // and the format is JPEG; otherwise the row stays visible but inert,
                // with the reason spelled out instead of a silent no-op.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.Switch(
                        checked = preserveHdr && hdrAvailable,
                        onCheckedChange = { onPreserveHdr(it) },
                        enabled = hdrAvailable && !exporting,
                    )
                    Column {
                        DlgNote(if (preserveHdr && hdrAvailable) "保留 HDR：已开启" else "保留 HDR（Ultra HDR）")
                        DlgNote(
                            if (hdrAvailable) {
                                "照片自带增益图，成片仍可在 HDR 屏幕上还原高光"
                            } else {
                                "此照片没有 HDR 增益图，或当前格式不是 JPEG，只能导出 SDR"
                            },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                DlgNote(qualityNote)
                if (error != null) {
                    Spacer(Modifier.height(2.dp))
                    DlgNote(error, error = true)
                }
            }
            DlgButton(text = "取消", style = DlgButtonStyle.Ghost, enabled = !exporting, onClick = onCancel)
            DlgButton(
                text = if (exporting) "正在导出…" else "导出",
                style = DlgButtonStyle.Primary,
                enabled = canExport,
                onClick = onExport,
            )
        }
    }
}

/** 结果页：明确的下一步（打开文件夹 / 再导出 / 回到编辑），并复述实际像素尺寸。 */
@Composable
private fun ExportResultPage(
    result: ExportResult,
    directory: String,
    actionNote: String?,
    modifier: Modifier,
    onReveal: () -> Unit,
    onExportAgain: () -> Unit,
    onBackToEdit: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = FmSpacing.xl, vertical = FmSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.primarySoft),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", style = FmType.titleMedium, color = FmPalette.primaryStrong)
        }
        Spacer(Modifier.height(FmSpacing.md))
        Text("导出完成", style = FmType.titleMedium, color = FmPalette.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            text = result.fileName,
            style = FmType.bodyMedium.withNumericDigits(),
            color = FmPalette.onSurfaceSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                if (result.width != null && result.height != null) append("${result.width} × ${result.height} px · ")
                append(result.formatLabel)
                append(" · ")
                append(ExportEstimate.formatBytes(result.bytes.toLong()))
            },
            style = FmType.labelMedium.withNumericDigits(),
            color = FmPalette.onSurfaceSecondary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "保存到 $directory",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        result.note?.takeIf { it.isNotBlank() }?.let { note ->
            Spacer(Modifier.height(6.dp))
            DlgNote(note)
        }
        if (actionNote != null) {
            Spacer(Modifier.height(6.dp))
            DlgNote(actionNote, error = true)
        }
        Spacer(Modifier.height(FmSpacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
            DlgButton(text = "打开文件夹", style = DlgButtonStyle.Line, onClick = onReveal)
            DlgButton(text = "再导出", style = DlgButtonStyle.Line, onClick = onExportAgain)
            DlgButton(text = "回到编辑", style = DlgButtonStyle.Primary, onClick = onBackToEdit)
        }
    }
}




