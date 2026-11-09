package com.framemark.native.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryPreview
import com.framemark.native.product.Preset
import com.framemark.native.workflow.PresetTransfer
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Preset sheet backed by `product.Preset` + [com.framemark.native.product.SessionStore].
 *
 * A preset captures the active photo's whole design **including the watermark's
 * embedded PNG bytes**, so applying it on another photo or after a restart brings
 * the watermark back without any external file. A failed write is shown as a
 * dialog — the sheet never reports success it did not get.
 */
@Composable
fun ProjectPresetSheet(
    projects: ProjectHolder,
    cardLookup: (String) -> LibraryCard?,
    onDismiss: () -> Unit,
) {
    var draftName by remember { mutableStateOf("我的预设 ${projects.presets.size + 1}") }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<Preset?>(null) }

    projects.loadPresets()
    projects.presetError?.let { failure ->
        AlertDialog(
            onDismissRequest = { projects.clearPresetError() },
            title = { Text("预设操作没有成功") },
            text = { Text(failure) },
            confirmButton = {
                TextButton(onClick = {
                    projects.clearPresetError()
                    dialog = null
                }) { Text("知道了") }
            },
        )
    }
    dialog?.let { message ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("预设") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("好") } },
        )
    }
    pendingImport?.let { incoming ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("预设 ID 已存在") },
            text = {
                Text(
                    "「${incoming.name}」与本机预设 ID 冲突。选择覆盖、另存为副本，或取消；确认前不会修改预设库。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pendingImport?.id != incoming.id) return@TextButton
                    pendingImport = null
                    val imported = projects.importPreset(incoming, replaceExisting = true)
                    if (imported) dialog = "已覆盖预设「${incoming.name}」"
                    else if (projects.presetError == null) dialog = "预设没有写入；原预设保持不变。"
                }) { Text("覆盖") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        if (pendingImport?.id != incoming.id) return@TextButton
                        pendingImport = null
                        val copy = runCatching {
                            PresetTransfer.copyWithNewId(
                                incoming,
                                projects.presets.mapTo(mutableSetOf()) { it.id },
                            )
                        }
                        if (copy.isFailure) {
                            dialog = "无法另存预设副本：${copy.exceptionOrNull()?.message ?: "请重试"}"
                            return@TextButton
                        }
                        val duplicate = copy.getOrThrow()
                        if (projects.importPreset(duplicate)) {
                            dialog = "已另存预设副本「${duplicate.name}」"
                        } else if (projects.presetError == null) {
                            dialog = "预设没有写入；原预设保持不变。"
                        }
                    }) { Text("另存为副本") }
                    TextButton(onClick = { pendingImport = null }) { Text("取消") }
                }
            },
        )
    }

    FmSheet(onDismiss = onDismiss) {
        SheetTitle(
            title = "预设",
            subtitle = "保存整套边框、参数与内嵌水印；套用时替换当前照片的设计。",
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
                placeholder = "例如：白边 + 我的签名",
            )
            Spacer(Modifier.height(FmSpacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
                PrimaryButton(
                    text = "保存当前设计",
                    enabled = projects.activeDesign != null,
                    onClick = {
                        val saved = projects.savePreset(draftName)
                        if (saved == null) {
                            dialog = "预设没有写入磁盘（工程存储不可用或当前照片没有设计）。"
                        } else {
                            draftName = "我的预设 ${projects.presets.size + 1}"
                            dialog = "已保存「${saved.name}」" +
                                if (saved.embeddedWatermarkBytes > 0) {
                                    "，含内嵌水印 ${formatPresetBytes(saved.embeddedWatermarkBytes)}。"
                                } else {
                                    "（无水印）。"
                                }
                        }
                    },
                )
                GhostButton(
                    text = "导入预设文件",
                    onClick = {
                        val path = projects.pickPresetImportPath()
                        if (path != null) {
                            val imported = projects.readPresetFile(path)
                            if (projects.presetError != null) return@GhostButton
                            if (imported == null) {
                                dialog = "这个文件不是可读的预设。"
                            } else if (cardLookup(imported.design.cardId) == null) {
                                dialog = "该预设使用的边框当前不可用，预设库未修改。"
                            } else if (projects.presets.any { it.id == imported.id }) {
                                pendingImport = imported
                            } else if (projects.importPreset(imported)) {
                                dialog = "已导入「${imported.name}」"
                            } else if (projects.presetError == null) {
                                dialog = "预设没有写入；现有预设保持不变。"
                            }
                        }
                    },
                )
            }
            if (projects.activeDesign == null) {
                Spacer(Modifier.height(FmSpacing.sm))
                SheetNote("先添加照片并选择一套边框，再保存预设。")
            }
        }

        Spacer(Modifier.height(FmSpacing.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已保存 ${projects.presets.size} 套",
                style = FmType.labelMedium,
                color = FmPalette.onSurfaceMuted,
            )
            Spacer(Modifier.weight(1f))
            Text("新保存的排在最前", style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
        }
        Spacer(Modifier.height(FmSpacing.sm))

        if (projects.presets.isEmpty()) {
            Text(
                text = "还没有保存的预设。",
                style = FmType.bodyMedium,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FmRadii.card))
                    .background(FmPalette.surfaceMuted)
                    .padding(FmSpacing.lg),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(FmSpacing.sm),
            ) {
                items(projects.presets, key = { it.id }) { preset ->
                    ProjectPresetRow(
                        preset = preset,
                        card = cardLookup(preset.design.cardId),
                        renaming = renamingId == preset.id,
                        renameDraft = renameDraft,
                        onRenameDraft = { renameDraft = it },
                        onStartRename = {
                            renamingId = preset.id
                            renameDraft = preset.name
                        },
                        onCancelRename = { renamingId = null },
                        onCommitRename = {
                            if (projects.renamePreset(preset.id, renameDraft)) renamingId = null
                        },
                        onApply = {
                            if (projects.applyPreset(preset.id)) {
                                onDismiss()
                            } else {
                                dialog = "预设「${preset.name}」没有套用到当前照片。"
                            }
                        },
                        onExport = {
                            val suggested = "framemark-preset-" + preset.name
                            val target = projects.pickPresetExportPath(suggested)
                            if (target != null && !projects.exportPresetFile(preset.id, target)) {
                                dialog = "预设文件没有写出去。"
                            }
                        },
                        onDelete = {
                            renamingId = null
                            if (!projects.deletePreset(preset.id)) dialog = "预设删除失败。"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectPresetRow(
    preset: Preset,
    card: LibraryCard?,
    renaming: Boolean,
    renameDraft: String,
    onRenameDraft: (String) -> Unit,
    onStartRename: () -> Unit,
    onCancelRename: () -> Unit,
    onCommitRename: () -> Unit,
    onApply: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.card))
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.card))
            .padding(FmSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(FmRadii.chip))
                    .background(FmPalette.surfaceMuted),
            ) {
                if (card != null) {
                    LibraryPreview(
                        card = card,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                    )
                }
            }
            Spacer(Modifier.width(FmSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                if (renaming) {
                    SheetTextField(
                        value = renameDraft,
                        onValueChange = onRenameDraft,
                        placeholder = "预设名称",
                    )
                } else {
                    Text(
                        text = preset.name,
                        style = FmType.titleSmall,
                        color = FmPalette.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(FmSpacing.xxs))
                    Text(
                        text = presetMeta(preset),
                        style = FmType.labelSmall,
                        color = FmPalette.onSurfaceMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(FmSpacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (renaming) {
                PrimaryButton(text = "保存名称", onClick = onCommitRename)
                Spacer(Modifier.width(FmSpacing.sm))
                GhostButton(text = "取消", onClick = onCancelRename)
            } else {
                PrimaryButton(text = "套用", onClick = onApply)
                Spacer(Modifier.width(FmSpacing.sm))
                GhostButton(text = "重命名", onClick = onStartRename)
                Spacer(Modifier.width(FmSpacing.sm))
                GhostButton(text = "导出文件", onClick = onExport)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "删除",
                    style = FmType.labelMedium,
                    color = FmPalette.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.surfaceMuted)
                        .clickable(onClick = onDelete)
                        .padding(horizontal = FmSpacing.md, vertical = FmSpacing.sm),
                )
            }
        }
    }
}

private fun presetMeta(preset: Preset): String {
    val parts = mutableListOf(preset.design.cardId)
    val design = preset.design
    val edits = design.params.size + design.text.size +
        (if (design.theme != null) 1 else 0) +
        (if (design.fontScale != null) 1 else 0)
    parts.add(if (edits > 0) "$edits 项调整" else "默认参数")
    parts.add(
        if (preset.embeddedWatermarkBytes > 0) {
            "内嵌水印 ${formatPresetBytes(preset.embeddedWatermarkBytes)}"
        } else {
            "无水印"
        },
    )
    val date = WorkflowEnv.platform?.formatDateTime(preset.updatedAt).orEmpty()
    if (date.isNotBlank()) parts.add(date)
    return parts.joinToString(" · ")
}

private fun formatPresetBytes(bytes: Int): String {
    val kb = bytes / 1024.0
    return if (kb >= 1024) {
        "${(kb / 1024.0 * 10).roundToInt() / 10.0} MB"
    } else {
        "${(kb * 10).roundToInt() / 10.0} KB"
    }
}
