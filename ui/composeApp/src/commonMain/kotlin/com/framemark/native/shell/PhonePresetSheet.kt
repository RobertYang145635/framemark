package com.framemark.native.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.framemark.native.library.LibraryCard
import com.framemark.native.product.ProjectPreset
import com.framemark.native.shell.phone.PhonePresetEntryPolicy
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.GhostButton
import com.framemark.native.workflow.PresetTransfer
import com.framemark.native.workflow.PrimaryButton
import com.framemark.native.workflow.FmSheet
import com.framemark.native.workflow.SheetFieldLabel
import com.framemark.native.workflow.SheetNote
import com.framemark.native.workflow.SheetTextField
import com.framemark.native.workflow.SheetTitle
import com.framemark.native.workflow.WorkflowEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.framemark.native.product.FmLog
import com.framemark.native.product.NoticeText

private const val PHONE_JPEG_QUALITY = 100

/**
 * The phone's preset list, reading and writing `product.ProjectPreset` documents.
 *
 * A preset here is the active photo's whole design — border, every control value,
 * the export choices and the watermark **with its PNG bytes inside** — so applying
 * one after a restart reproduces the signature instead of losing it. A rejected
 * write is reported; the list is only updated once the document actually landed.
 */
@Composable
fun PhonePresetSheet(
    store: PhoneProjectStore,
    card: LibraryCard?,
    knownCardIds: Set<String>,
    onApply: (ProjectPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val presets = store.presets
    var draftName by remember(card?.id, presets.size) {
        mutableStateOf("我的预设 ${presets.size + 1}")
    }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var pendingImport by remember { mutableStateOf<ProjectPreset?>(null) }
    var pendingApply by remember { mutableStateOf<ProjectPreset?>(null) }
    var saveNotice by remember { mutableStateOf<String?>(null) }
    var transferMessage by remember { mutableStateOf<String?>(null) }
    var transferBusy by remember { mutableStateOf(false) }
    val transferScope = rememberCoroutineScope()

    fun importPreset(preset: ProjectPreset, replaceExisting: Boolean) {
        if (transferBusy) return
        transferBusy = true
        val imported = try {
            store.importPreset(preset, replaceExisting)
        } catch (error: Throwable) {
            transferBusy = false
            pendingImport = null
            transferMessage = NoticeText.failure("FM_PRESET_SAVE_FAIL", error, "无法导入预设，请确认文件没有损坏。")
            return
        }
        transferBusy = false
        if (imported) {
            transferMessage = if (preset.exportSettings.quality != PHONE_JPEG_QUALITY) {
                "已导入「${preset.name}」。Android JPEG 质量固定为 100，导入后的本机设置已调整；源文件未更改。"
            } else {
                "已导入预设「${preset.name}」"
            }
            pendingImport = null
        } else {
            transferMessage = store.presetError ?: "预设未写入；现有预设保持不变。"
            pendingImport = null
        }
    }

    fun requestApply(preset: ProjectPreset) {
        if (transferBusy) return
        if (preset.exportSettings.quality != PHONE_JPEG_QUALITY) pendingApply = preset
        else onApply(preset)
    }

    fun beginImport() {
        if (transferBusy) return
        val picker = WorkflowEnv.presetDocumentPicker
        if (picker == null) {
            transferMessage = "当前平台没有预设文件导入入口"
            return
        }
        transferBusy = true
        val started = runCatching {
            picker { result ->
                result.fold(
                    onSuccess = { json ->
                        if (json == null) transferBusy = false
                        else transferScope.launch {
                            val decoded = withContext(Dispatchers.Default) {
                                runCatching { PresetTransfer.decodeDocument(json, knownCardIds) }
                            }
                            transferBusy = false
                            decoded.fold(
                                onSuccess = { preset ->
                                    if (store.presets.any { it.id == preset.id }) pendingImport = preset
                                    else importPreset(preset, replaceExisting = false)
                                },
                                onFailure = {
                                    transferMessage = NoticeText.failure("FM_PRESET_SAVE_FAIL", it, "无法导入预设，文件格式不正确。")
                                },
                            )
                        }
                    },
                    onFailure = {
                        transferBusy = false
                        transferMessage = NoticeText.failure("FM_PRESET_SAVE_FAIL", it, "读取预设文件失败，请重试。")
                    },
                )
            }
        }
        started.onFailure {
            transferBusy = false
            transferMessage = NoticeText.failure("FM_PRESET_SAVE_FAIL", it, "无法打开文件选择器，请重试。")
        }
    }

    fun beginExport(preset: ProjectPreset) {
        if (transferBusy) return
        val writer = WorkflowEnv.presetDocumentWriter
        if (writer == null) {
            transferMessage = "当前平台没有预设文件导出入口"
            return
        }
        transferBusy = true
        transferScope.launch {
            val encoded = withContext(Dispatchers.Default) {
                runCatching { PresetTransfer.encodeDocument(preset) }
            }
            if (encoded.isFailure) {
                transferBusy = false
                transferMessage = NoticeText.failure(
                    "FM_PRESET_SAVE_FAIL",
                    encoded.exceptionOrNull(),
                    "无法导出预设，请重试。",
                )
                return@launch
            }
            val started = runCatching {
                writer(PresetTransfer.suggestedFileName(preset), encoded.getOrThrow()) { result ->
                    transferBusy = false
                    result.fold(
                        onSuccess = { saved -> if (saved) transferMessage = "预设文件已保存" },
                        onFailure = {
                            transferMessage = NoticeText.failure("FM_PRESET_SAVE_FAIL", it, "保存预设文件失败，请重试。")
                        },
                    )
                }
            }
            started.onFailure {
                transferBusy = false
                transferMessage = "无法打开预设保存位置：${it.message ?: "未知错误"}"
            }
        }
    }

    pendingImport?.let { incoming ->
        val idConflict = presets.any { it.id == incoming.id }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(if (idConflict) "预设 ID 已存在" else "确认导入预设") },
            text = {
                Column {
                    Text(
                        if (idConflict) {
                            "「${incoming.name}」与本机预设 ID 冲突。选择覆盖、另存为副本，或取消；确认前不会修改预设库。"
                        } else {
                            "准备导入预设「${incoming.name}」。确认后才会写入本机预设库。"
                        },
                    )
                    if (incoming.exportSettings.quality != PHONE_JPEG_QUALITY) {
                        Text(
                            "该预设 JPEG 质量为 ${incoming.exportSettings.quality}；Android 固定为 100，导入后本机设置会调整为 100，所选文件不会被修改。",
                            color = FmPalette.onSurfaceSecondary,
                        )
                    }
                    if (idConflict) {
                        TextButton(
                            enabled = !transferBusy,
                            onClick = {
                                if (transferBusy || pendingImport?.id != incoming.id) return@TextButton
                                // Consume the confirmation before doing any work so a second
                                // queued click cannot create another copy from the same dialog.
                                pendingImport = null
                                val copyResult = runCatching {
                                    PresetTransfer.copyWithNewId(incoming, store.presets.mapTo(mutableSetOf()) { it.id })
                                }
                                if (copyResult.isFailure) {
                                    transferMessage = "无法另存预设副本：${copyResult.exceptionOrNull()?.message ?: "请重试"}"
                                    return@TextButton
                                }
                                importPreset(copyResult.getOrThrow(), replaceExisting = false)
                            },
                        ) { Text("另存为副本") }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !transferBusy,
                    onClick = {
                        if (transferBusy || pendingImport?.id != incoming.id) return@TextButton
                        pendingImport = null
                        importPreset(incoming, replaceExisting = idConflict)
                    },
                ) { Text(if (idConflict) "覆盖" else "导入") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } },
        )
    } ?: pendingApply?.let { preset ->
        AlertDialog(
            onDismissRequest = { pendingApply = null },
            title = { Text("JPEG 质量会调整") },
            text = {
                Text(
                    "该预设保存的 JPEG 质量为 ${preset.exportSettings.quality}；Android 固定为 100。继续套用时本机使用 100，预设文件不会被修改。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pendingApply?.id != preset.id) return@TextButton
                    pendingApply = null
                    onApply(preset)
                }) { Text("继续套用") }
            },
            dismissButton = { TextButton(onClick = { pendingApply = null }) { Text("取消") } },
        )
    } ?: transferMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { transferMessage = null },
            title = { Text("预设文件") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { transferMessage = null }) { Text("知道了") } },
        )
    }

    FmSheet(onDismiss = onDismiss, wrapContent = true) {
        SheetTitle(
            title = "预设",
            subtitle = "保存整套边框、参数与水印（水印图片存在预设文件里）。",
            trailing = { GhostButton("完成", onClick = onDismiss) },
        )
        Spacer(Modifier.height(FmSpacing.lg))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FmRadii.card))
                .background(FmPalette.surfaceMuted)
                .padding(FmSpacing.lg),
        ) {
            SheetFieldLabel("预设名称")
            SheetTextField(
                value = draftName,
                onValueChange = { draftName = it },
                placeholder = "例如：白边 + 签名",
            )
            Spacer(Modifier.height(FmSpacing.md))
            PrimaryButton(
                text = PhonePresetEntryPolicy.SAVE_LABEL,
                enabled = PhonePresetEntryPolicy.saveEnabled(store.activeDesign != null),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val saved = store.savePreset(draftName)
                    if (saved != null) {
                        draftName = "我的预设 ${store.presets.size + 1}"
                        saveNotice = PhonePresetEntryPolicy.savedMessage(saved.name, saved.embeddedWatermarkBytes)
                        FmLog.info("FM_PRESET_SAVED") { "id=${saved.id} wm=${saved.overlay?.asset?.sha256?.take(12) ?: "none"} bytes=${saved.embeddedWatermarkBytes}" }
                    } else {
                        saveNotice = null
                        FmLog.warn("FM_PRESET_SAVE_FAIL") { "msg=${store.presetError ?: "no active design"}" }
                    }
                },
            )
            if (store.activeDesign == null) {
                Spacer(Modifier.height(FmSpacing.sm))
                SheetNote("先选择并应用一套边框，再保存预设。")
            }
            // 验收人「保存预设我没看到」：成功也要有回音，复用面板里的状态文本位置。
            saveNotice?.let { message ->
                Spacer(Modifier.height(FmSpacing.sm))
                Text(
                    text = message,
                    style = FmType.labelSmall,
                    color = FmPalette.success,
                )
            }
            store.presetError?.let { message ->
                Spacer(Modifier.height(FmSpacing.sm))
                Text(
                    text = NoticeText.human(message)?.let { "写入失败：$it" } ?: "写入失败，请重试。",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceSecondary,
                )
            }
        }

        Spacer(Modifier.height(FmSpacing.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = PhonePresetEntryPolicy.APPLY_TITLE,
                style = FmType.titleSmall,
                color = FmPalette.onSurface,
            )
            Spacer(Modifier.weight(1f))
            GhostButton(
                text = if (transferBusy) "处理中…" else "导入预设",
                enabled = !transferBusy,
                onClick = ::beginImport,
            )
        }
        Text(
            text = "已保存 ${presets.size} 套 · 带水印 ${presets.count { it.overlay != null }} 套 · 新保存的排在最前",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
            maxLines = 2,
        )
        Spacer(Modifier.height(FmSpacing.sm))

        if (presets.isEmpty()) {
            FmEmptyState(
                title = "还没有保存的预设",
                body = "填好名称后点「${PhonePresetEntryPolicy.SAVE_LABEL}」，它会出现在这里。",
                modifier = Modifier.fillMaxWidth(),
            )
            return@FmSheet
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
            items(presets, key = { it.id }) { preset ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FmRadii.card))
                        .background(FmPalette.surface)
                        .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.card))
                        .padding(FmSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(FmSpacing.sm),
                ) {
                    if (renamingId == preset.id) {
                        SheetTextField(
                            value = renameDraft,
                            onValueChange = { renameDraft = it },
                            placeholder = "预设名称",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
                            GhostButton("取消", onClick = { renamingId = null })
                            PrimaryButton(
                                text = "确定",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (store.renamePreset(preset.id, renameDraft)) renamingId = null
                                },
                            )
                        }
                    } else {
                        Column {
                            Text(preset.name, style = FmType.bodyLarge, color = FmPalette.onSurface)
                            Text(
                                text = preset.design.cardId + if (preset.overlay != null) " · 含水印" else " · 无水印",
                                style = FmType.labelSmall,
                                color = FmPalette.onSurfaceMuted,
                            )
                            Text(
                                text = "参数 ${preset.design.params.size} · 文字 ${preset.design.text.size}" +
                                    " · 水印 ${preset.embeddedWatermarkBytes / 1024} KB",
                                style = FmType.labelSmall,
                                color = FmPalette.onSurfaceMuted,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
                            GhostButton("改名", onClick = { renamingId = preset.id; renameDraft = preset.name })
                            GhostButton("删除", onClick = { store.deletePreset(preset.id) })
                            PrimaryButton(
                                text = "套用",
                                enabled = PhonePresetEntryPolicy.applyEnabled(store.activePhotoId != null),
                                modifier = Modifier.weight(1f),
                                onClick = { requestApply(preset) },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
                            GhostButton(
                                text = "导出文件",
                                enabled = !transferBusy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { beginExport(preset) },
                            )
                        }
                    }
                }
            }
        }
    }
}
