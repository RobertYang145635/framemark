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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.framemark.native.inspector.FmSlider
import com.framemark.native.library.LibraryCard
import com.framemark.native.product.PhotoRef
import com.framemark.native.theme.FmElevation
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.theme.fmElevated
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.EngineFailure
import com.framemark.native.workflow.ExportNaming
import com.framemark.native.workflow.ExportResult
import com.framemark.native.workflow.ExportSettings
import com.framemark.native.workflow.ExportSizeMode
import com.framemark.native.workflow.ImageRect
import com.framemark.native.workflow.NativeEngine
import com.framemark.native.workflow.NativeStagePreview
import com.framemark.native.workflow.OverlaySpec
import com.framemark.native.workflow.PreviewGeometry
import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.WorkflowEnv
import com.framemark.native.workflow.DesignSnapshot
import kotlinx.coroutines.launch

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
    val shape = RoundedCornerShape(FmRadii.pill)
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

/** 两段式选择（格式 / 尺寸）。 */
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
            .clip(RoundedCornerShape(FmRadii.pill))
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
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(if (on) FmPalette.surface else Color.Transparent)
                    .border(1.dp, if (on) FmPalette.outlineStrong else Color.Transparent, RoundedCornerShape(FmRadii.pill))
                    .clickable(role = Role.RadioButton, onClick = { onSelect(key) })
                    .padding(horizontal = FmSpacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = FmType.labelMedium,
                    color = if (on) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun DlgLabel(text: String) {
    Text(
        text = text,
        style = FmType.labelSmall,
        color = FmPalette.onSurfaceMuted,
        modifier = Modifier.padding(bottom = 6.dp),
    )
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
internal fun DlgTag(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.surfaceMuted)
            .padding(horizontal = FmSpacing.sm, vertical = 3.dp),
    ) {
        Text(text, style = FmType.labelSmall, color = FmPalette.onSurfaceMuted, maxLines = 1)
    }
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
    val shape = RoundedCornerShape(FmRadii.input)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = enabled,
        textStyle = FmType.bodyMedium.copy(color = if (enabled) FmPalette.onSurface else FmPalette.onSurfaceFaint),
        cursorBrush = SolidColor(FmPalette.primary),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .clip(shape)
            .background(FmPalette.surfaceMuted)
            .border(1.dp, FmPalette.outline, shape)
            .then(if (aria != null) Modifier.semantics { contentDescription = aria } else Modifier)
            .padding(horizontal = FmSpacing.sm, vertical = 8.dp),
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
) {
    val scope = rememberCoroutineScope()
    val platform = WorkflowEnv.platform
    val settings = session.export
    var qualityText by remember(settings.quality) { mutableStateOf(settings.quality.toString()) }
    var longEdgeText by remember(settings.longEdge) { mutableStateOf(settings.longEdge.toString()) }
    var outputDirectory by remember { mutableStateOf(platform?.exportDir ?: "未设置") }
    var result by remember { mutableStateOf<ExportResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionNote by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var frame by remember(card?.id, design, photo?.path) { mutableStateOf<PreviewFrame?>(null) }

    val webpAvailable = card != null && NativeEngine.supportsWebpExport()
    val formats = if (webpAvailable) SampleFormat.entries.toList() else listOf(SampleFormat.Jpeg, SampleFormat.Png)
    val qualityApplies = ExportRules.qualityApplies(settings.format, settings.webpLossless)
    val qualityError = if (qualityApplies) ExportRules.qualityError(qualityText) else null
    val longEdgeError = if (settings.sizeMode == ExportSizeMode.LongEdge) ExportRules.longEdgeError(longEdgeText) else null
    val effectiveLongEdge = ExportRules.parseLongEdge(longEdgeText) ?: settings.longEdge
    val editable = card != null && NativeEngine.supportsExport(card.id)
    val canExport = editable && qualityError == null && longEdgeError == null && !exporting
    val qualityValue = ExportRules.parseQuality(qualityText) ?: ExportRules.QUALITY_DEFAULT
    val canvas = ExportEstimate.canvas(
        photoWidthPx = photo?.widthPx ?: 0,
        photoHeightPx = photo?.heightPx ?: 0,
        previewPhotoBox = frame?.box,
        previewWidthPx = frame?.width ?: 0,
        previewHeightPx = frame?.height ?: 0,
    )
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
                val effective = settings.copy(
                    quality = ExportRules.effectiveQuality(qualityText, settings.format, settings.webpLossless),
                    longEdge = effectiveLongEdge,
                )
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
                println("FM_DESKTOP_EXPORT path=${produced.path} ${produced.width}x${produced.height} bytes=${produced.bytes}")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                val failure = t as? EngineFailure
                error = failure?.let { "${it.userMessage}（${it.code}）" } ?: "导出没有成功，请重试。"
                println("FM_DESKTOP_EXPORT_FAIL ${failure?.code ?: "FM-E-INTERNAL-001"} ${t.message}")
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
            val dialogWidth = minOf(maxWidth * 0.92f, 880.dp)
            val dialogHeight = minOf(maxHeight * 0.9f, 640.dp)
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
                Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
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
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        ExportPreviewColumn(
                            card = card,
                            design = design,
                            overlay = overlay,
                            photo = photo,
                            settings = settings,
                            qualityValue = qualityValue,
                            previewPixels = outPixels,
                            modifier = Modifier.width(300.dp).fillMaxHeight(),
                            onFrame = { width, height, box -> frame = PreviewFrame(width, height, box) },
                        )
                        Box(Modifier.width(1.dp).fillMaxSize().background(FmPalette.outline))
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
                            cardName = card?.name ?: "边框",
                            photoStem = ExportNaming.photoStem(photo?.displayName ?: photo?.path),
                            photoIndex = photoIndex,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            onChange = { next -> session.export = next; error = null },
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
                                    .onFailure { error = "选择输出目录失败：${it.message}" }
                            },
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
                if (result == null || exporting) {
                    ExportDialogFooter(
                        exporting = exporting,
                        canExport = canExport,
                        error = error,
                        qualityNote = "Windows 质量可调 ${ExportRules.QUALITY_MIN}–${ExportRules.QUALITY_MAX}，默认 ${ExportRules.QUALITY_DEFAULT}；Android 固定 100。",
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = FmSpacing.lg, vertical = FmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("导出照片", style = FmType.titleMedium, color = FmPalette.onSurface, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(FmRadii.input))
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = "关闭导出对话框" },
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", style = FmType.labelMedium, color = FmPalette.onSurfaceMuted)
        }
    }
}

/** 左列：成品预览 + 原图像素 + 估算尺寸与体积 + 当前格式徽标。 */
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
    Column(
        modifier = modifier.padding(FmSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(FmSpacing.sm),
    ) {
        Text("成品预览", style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .clip(RoundedCornerShape(FmRadii.card))
                .background(FmPalette.stageDark)
                .padding(FmSpacing.sm),
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
        Text(
            text = if (photo != null) "原图 ${photo.widthPx} × ${photo.heightPx} px" else "示例照片（引擎样张）",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
        )
        Text(
            text = ExportEstimate.previewLine(previewPixels, settings.format, qualityValue, settings.webpLossless),
            style = FmType.labelMedium,
            color = FmPalette.onSurfaceSecondary,
        )
        DlgTag(formatBadge(settings, qualityValue))
        Text(
            text = "预览就是成片版式：同一模板、同一照片，导出后像素尺寸在结果页复述。",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
        )
    }
}

private fun formatBadge(settings: ExportSettings, quality: Int): String = when (settings.format) {
    SampleFormat.Jpeg -> "JPG · 质量 $quality"
    SampleFormat.Png -> "PNG · 无损"
    SampleFormat.WebP -> if (settings.webpLossless) "WebP · 无损" else "WebP · 质量 $quality"
}

/** 右列：格式 / 尺寸 / 质量 / 文件名 / 保存位置 + 拍摄信息去留。 */
@Composable
private fun ExportFormColumn(
    settings: ExportSettings,
    formats: List<SampleFormat>,
    qualityText: String,
    qualityError: String?,
    qualityValue: Int,
    longEdgeText: String,
    longEdgeError: String?,
    directory: String,
    pixels: ExportPixels?,
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
        modifier = modifier.verticalScroll(rememberScrollState()).padding(FmSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(FmSpacing.md),
    ) {
        Column {
            DlgLabel("格式")
            DlgSegment(
                options = formats.map { it.extension to it.label },
                selected = settings.format.extension,
                onSelect = { key ->
                    formats.firstOrNull { it.extension == key }?.let { onChange(settings.copy(format = it)) }
                },
                description = "导出格式",
            )
            Spacer(Modifier.height(6.dp))
            DlgNote(ExportCopy.encoderLine(settings.format, qualityValue, settings.webpLossless))
        }

        Column {
            DlgLabel("尺寸")
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
                    DlgTextField(
                        value = longEdgeText,
                        onValueChange = onLongEdgeText,
                        placeholder = "2048",
                        modifier = Modifier.width(120.dp),
                        aria = "长边像素",
                    )
                    Text("px", style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
                    ExportRules.LONG_EDGE_PRESETS.forEach { preset ->
                        DlgButton(
                            text = preset.toString(),
                            style = DlgButtonStyle.Ghost,
                            onClick = { onLongEdgeText(preset.toString()) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                DlgNote(longEdgeError ?: "把长边缩到所选像素后再编码。", error = longEdgeError != null)
            } else {
                Spacer(Modifier.height(6.dp))
                DlgNote("保留照片原始像素，画布随边框扩展。")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "成片 " + (pixels?.let { "约 $it" } ?: "尺寸在导出后显示") + "（估算）",
                style = FmType.labelMedium,
                color = FmPalette.onSurfaceSecondary,
            )
        }

        if (ExportRules.qualityApplies(settings.format, settings.webpLossless)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DlgLabel("质量")
                    Spacer(Modifier.weight(1f))
                    Text("$qualityValue", style = FmType.labelMedium, color = FmPalette.onSurface)
                }
                FmSlider(
                    value = qualityValue.toFloat(),
                    onValueChange = { onQualityText(it.toInt().toString()) },
                    valueRange = ExportRules.QUALITY_MIN.toFloat()..ExportRules.QUALITY_MAX.toFloat(),
                    step = 1f,
                    defaultValue = ExportRules.QUALITY_DEFAULT.toFloat(),
                    modified = qualityValue != ExportRules.QUALITY_DEFAULT,
                )
                DlgNote(qualityError ?: "默认 ${ExportRules.QUALITY_DEFAULT}。只保存在本机，不写入跨端预设。", error = qualityError != null)
            }
        }

        Column {
            DlgLabel("文件名")
            DlgTextField(
                value = settings.filenamePattern,
                onValueChange = { onChange(settings.copy(filenamePattern = it)) },
                placeholder = "{photo}-{index}",
                aria = "文件名模板",
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.xs)) {
                ExportNameToken.entries.forEach { token ->
                    DlgButton(
                        text = token.label,
                        style = DlgButtonStyle.Ghost,
                        onClick = {
                            val next = ExportFileName.insertToken(
                                pattern = settings.filenamePattern,
                                tokenKey = token.key,
                                selectionStart = settings.filenamePattern.length,
                                selectionEnd = settings.filenamePattern.length,
                            )
                            onChange(settings.copy(filenamePattern = next))
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "将保存为 " + ExportFileName.previewFile(
                    pattern = settings.filenamePattern,
                    cardName = cardName,
                    photoStem = photoStem,
                    index = photoIndex,
                    extension = settings.format.extension,
                ),
                style = FmType.labelMedium,
                color = FmPalette.onSurfaceSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column {
            DlgLabel("保存位置")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(FmRadii.input))
                        .background(FmPalette.surfaceMuted)
                        .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input))
                        .padding(horizontal = FmSpacing.sm, vertical = 8.dp),
                ) {
                    Text(
                        text = directory,
                        style = FmType.bodyMedium,
                        color = FmPalette.onSurfaceSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DlgButton(text = "更改…", onClick = onChooseDirectory)
            }
            Spacer(Modifier.height(6.dp))
            DlgNote("目录会记住，下次仍用它；同名文件不会被覆盖（自动加 -2）。")
        }

        ExportCopy.metadataLines(settings.format).forEach { line -> DlgNote(line) }
    }
}

@Composable
private fun ExportDialogFooter(
    exporting: Boolean,
    canExport: Boolean,
    error: String?,
    qualityNote: String,
    onCancel: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = FmSpacing.lg, vertical = FmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
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
            style = FmType.bodyMedium,
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
            style = FmType.labelMedium,
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




