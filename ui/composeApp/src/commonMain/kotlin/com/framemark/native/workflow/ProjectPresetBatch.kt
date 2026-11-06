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
import androidx.compose.foundation.layout.fillMaxSize
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
import com.framemark.native.product.BatchJob
import com.framemark.native.product.BatchJobStatus
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

/**
 * Desktop batch panel over `product.BatchQueue`.
 *
 * Items are paired **per photo**: each queued job snapshots that photo's own
 * design and embedded watermark, so a theme-only row and a watermark-only row
 * are two different jobs and both survive. The queue is written to disk before
 * and after every transition, so a force-killed run resumes where it stopped.
 */
@Composable
fun ProjectBatchPanel(projects: ProjectHolder, modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var notice by remember { mutableStateOf<String?>(null) }
    // M01 rule 2: a mixed 有设计/无设计 selection waits for an explicit
    // 「仅导出已设计的 X 张」 before anything reaches the queue.
    var pendingBatch by remember { mutableStateOf<Pair<BatchPreflight, List<String>>?>(null) }

    projects.loadBatch()
    val jobs = projects.batchJobs
    val photos = projects.photos
    // 本次提交的对账行必须随队列表态重新计算，否则任务完成或失败后仍停留在
    // 入队时的「待导出 / 失败 0」，让用户漏看本轮结果。
    val submitLines = projects.submitLines()
    val total = jobs.size
    val settled = projects.batchDone + projects.batchFailed + jobs.count {
        it.status == BatchJobStatus.Cancelled
    }

    Column(modifier = modifier.fillMaxSize().padding(FmSpacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("批量导出", style = FmType.titleMedium, color = FmPalette.onSurface)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (projects.batchRunning) "正在导出 ${projects.batchDone}/$total" else "$total 项任务",
                style = FmType.labelMedium,
                color = FmPalette.onSurfaceMuted,
            )
        }
        Spacer(Modifier.height(FmSpacing.sm))
        if (total > 0) {
            FmProgress(if (total == 0) 0f else settled.toFloat() / total)
            Spacer(Modifier.height(FmSpacing.md))
        }

        SheetFieldLabel("选择照片（每张按它自己的设计与水印导出）")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm),
        ) {
            ChoiceChip(
                label = "全选 ${photos.size}",
                selected = photos.isNotEmpty() && selected.size == photos.size,
                onClick = {
                    selected = photos.map { it.id }.toSet()
                    pendingBatch = null
                },
            )
            ChoiceChip(
                label = "清空选择",
                selected = false,
                onClick = {
                    selected = emptySet()
                    pendingBatch = null
                },
            )
        }
        Spacer(Modifier.height(FmSpacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
            photos.forEach { photo ->
                val on = photo.id in selected
                ChoiceChip(
                    label = photo.displayName.take(14),
                    selected = on,
                    onClick = {
                        selected = if (on) selected - photo.id else selected + photo.id
                        pendingBatch = null
                    },
                )
            }
        }
        Spacer(Modifier.height(FmSpacing.md))

        Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                text = "加入队列",
                enabled = selected.isNotEmpty() && !projects.batchRunning,
                onClick = {
                    val ids = selected.toList()
                    val preflight = projects.preflightBatch(ids)
                    when {
                        preflight.allUndesigned -> {
                            pendingBatch = null
                            notice = "选中的 ${preflight.undesignedCount} 张都还没有应用边框，未创建任何任务。请先「去编辑」为照片应用边框。"
                        }
                        preflight.hasUndesigned -> {
                            notice = null
                            pendingBatch = preflight to ids
                        }
                        else -> {
                            pendingBatch = null
                            projects.enqueueBatch(ids)
                        }
                    }
                },
            )
            PrimaryButton(
                text = if (projects.batchRunning) "正在导出…" else "开始导出",
                enabled = !projects.batchRunning && jobs.any { it.status == BatchJobStatus.Queued },
                onClick = { projects.startBatch() },
            )
            GhostButton(
                text = "取消",
                onClick = { projects.cancelBatch() },
            )
            GhostButton(
                text = "清空队列",
                onClick = { jobs.filter { it.status != BatchJobStatus.Running }.forEach { projects.removeBatchJob(it.id) } },
            )
        }
        pendingBatch?.let { (preflight, pendingIds) ->
            Spacer(Modifier.height(FmSpacing.sm))
            SheetNote(
                "有 ${preflight.undesignedCount} 张还没有应用边框，不会加入导出：${preflight.undesignedSummary}。" +
                    "可只导出已设计的 ${preflight.designedCount} 张。",
            )
            Spacer(Modifier.height(FmSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
                PrimaryButton(
                    text = "仅导出已设计的 ${preflight.designedCount} 张",
                    enabled = !projects.batchRunning,
                    onClick = {
                        // The panel was computed for `pendingIds`; re-validate it against
                        // the selection and bindings as they are right now (P1-01).
                        val current = projects.preflightBatch(pendingIds)
                        when (batchConfirmGate(pendingIds, selected, preflight, current)) {
                            BatchConfirmGate.SelectionChanged -> {
                                pendingBatch = null
                                notice = "选择已改变，本次未加入任何任务。请重新点「加入队列」预检。"
                            }
                            BatchConfirmGate.DesignChanged -> {
                                pendingBatch = null
                                notice = "这些照片的边框状态已改变，本次未加入任何任务。请重新点「加入队列」预检。"
                            }
                            BatchConfirmGate.Submit -> {
                                projects.enqueueBatch(current.designedPhotoIds)
                                pendingBatch = null
                            }
                        }
                    },
                )
                GhostButton(
                    text = "去编辑",
                    onClick = { pendingBatch = null; notice = "已取消入队，请先为无设计的照片应用边框。" },
                )
                GhostButton(
                    text = "取消",
                    onClick = { pendingBatch = null; notice = null },
                )
            }
        }
        if (submitLines.isNotEmpty()) {
            Spacer(Modifier.height(FmSpacing.sm))
            submitLines.forEach { line ->
                Text(
                    text = line,
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
            }
        }
        notice?.let {
            Spacer(Modifier.height(FmSpacing.sm))
            SheetNote(it)
        }
        projects.batchError?.let {
            Spacer(Modifier.height(FmSpacing.sm))
            Text(it, style = FmType.bodyMedium, color = FmPalette.error)
        }

        Spacer(Modifier.height(FmSpacing.lg))
        if (jobs.isEmpty()) {
            Text(
                text = "队列为空。勾选照片后「加入队列」；重启后未导出的任务会留在列表里继续。",
                style = FmType.bodyMedium,
                color = FmPalette.onSurfaceMuted,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(FmSpacing.sm),
            ) {
                items(jobs, key = { it.id }) { job ->
                    BatchJobRow(
                        job = job,
                        onRetry = { projects.retryBatch(job.id) },
                        onRemove = { projects.removeBatchJob(job.id) },
                        onReveal = { path ->
                            projects.revealInFolder(path)?.let { notice = it }
                        },
                        retryEnabled = !projects.batchRunning,
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchJobRow(
    job: BatchJob,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onReveal: (String) -> Unit,
    retryEnabled: Boolean,
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
            Text(
                text = job.displayName,
                style = FmType.titleSmall,
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SheetTag(batchStatusLabel(job.status))
        }
        Spacer(Modifier.height(FmSpacing.xxs))
        Text(
            text = jobMeta(job),
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        job.outputPath?.let { path ->
            Spacer(Modifier.height(FmSpacing.xs))
            Text(
                text = path,
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        job.error?.let {
            Spacer(Modifier.height(FmSpacing.xs))
            Text(
                text = "${it.code} ${it.message}",
                style = FmType.labelSmall,
                color = FmPalette.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(FmSpacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            if (job.status == BatchJobStatus.Failed || job.status == BatchJobStatus.Cancelled) {
                GhostButton(text = "重试", onClick = onRetry)
            }
            if (job.status == BatchJobStatus.Done) {
                job.outputPath?.let { out -> GhostButton(text = "打开所在目录", onClick = { onReveal(out) }) }
            }
            Spacer(Modifier.weight(1f))
            if (job.status != BatchJobStatus.Running) {
                Text(
                    text = "移除",
                    style = FmType.labelMedium,
                    color = FmPalette.onSurfaceMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.surfaceMuted)
                        .clickable(enabled = retryEnabled, onClick = onRemove)
                        .padding(horizontal = FmSpacing.md, vertical = FmSpacing.sm),
                )
            }
        }
    }
}

private fun batchStatusLabel(status: BatchJobStatus): String = when (status) {
    BatchJobStatus.Queued -> "排队中"
    BatchJobStatus.Running -> "导出中"
    BatchJobStatus.Done -> "已完成"
    BatchJobStatus.Failed -> "失败"
    BatchJobStatus.Cancelled -> "已取消"
}

private fun jobMeta(job: BatchJob): String {
    val parts = mutableListOf(job.design.cardId)
    val edits = job.design.params.size + job.design.text.size +
        (if (job.design.theme != null) 1 else 0) +
        (if (job.design.fontScale != null) 1 else 0)
    parts.add(if (edits > 0) "$edits 项调整" else "默认参数")
    parts.add(if (job.overlay != null) "带水印" else "无水印")
    parts.add(job.exportSettings.format.label)
    parts.add(
        if (job.exportSettings.sizeMode == com.framemark.native.product.ExportSizeMode.Original) {
            "原尺寸"
        } else {
            "长边 ${job.exportSettings.longEdge}"
        },
    )
    if (job.attempts > 1) parts.add("第 ${job.attempts} 次")
    return parts.joinToString(" · ")
}


/** 本次「加入队列」的对账行；尚未提交过时为空。 */
private fun ProjectHolder.submitLines(): List<String> {
    val report = batchLastSubmit ?: return emptyList()
    return submitFeedbackLines(
        selected = report.selected,
        added = report.added,
        undesigned = report.undesigned,
        duplicates = report.duplicates,
        rejected = report.rejected.size,
        tally = SubmitTally.of(batchJobs, report.addedIds),
    )
}
