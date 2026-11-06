package com.framemark.native.shell.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.inspector.withNumericDigits
import com.framemark.native.library.LibraryPreviewLoader
import com.framemark.native.product.BatchJob
import com.framemark.native.product.BatchJobStatus
import com.framemark.native.product.ExportSettings as ProductExportSettings
import com.framemark.native.product.PhotoRef
import com.framemark.native.theme.FmElevation
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.theme.fmElevated
import com.framemark.native.workflow.ProjectHolder
import com.framemark.native.workflow.WorkflowEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * V2 批量导出工作区（报告 §6 / `desktop.js` 的 batch 模式）：左照片勾选、中成品
 * 联系表、右导出设置摘要 + 队列。
 *
 * 执行与恢复**没有另写一套**：入队、开始、取消、重试、重载都走
 * [ProjectHolder] 上已有的 `enqueueBatch / startBatch / cancelBatch / retryBatch /
 * loadBatch`，队列文档与断点恢复由 `product.BatchQueue` + `recoverBatchQueue` 负责。
 * 这里只补三件 V2 要求的事：勾选规则（跳过已导出、挡住未选边框）、联系表、
 * 队列的进度与失败原因。
 */
@Composable
fun BatchWorkspace(
    projects: ProjectHolder,
    cardName: (String) -> String?,
    onOpenExportSettings: () -> Unit,
    onBackToEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val platform = WorkflowEnv.platform
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var initialized by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { projects.loadBatch() }

    val jobs = projects.batchJobs
    val settings = batchSettings(
        projects.project.exportSettings,
        platform?.preferredJpegQuality() ?: ExportRules.QUALITY_DEFAULT,
    )
    val rows = batchPhotoRows(projects.photos, projects.project.designs, settings, jobs, cardName)
    val summary = BatchQueueView.summary(jobs)
    val queueRows = BatchQueueView.rows(jobs)
    val directory = platform?.exportDir ?: "未设置"

    LaunchedEffect(rows) {
        if (!initialized && projects.loaded) {
            selected = BatchSelection.defaultSelection(rows)
            initialized = true
        }
    }

    fun enqueueAndStart() {
        val chosen = rows.filter { it.photoId in selected }
        val designed = chosen.filter { it.designed }.map { it.photoId }
        if (designed.isEmpty()) {
            notice = "先在左侧勾选要导出的照片。没应用边框的照片不会导出。"
            return
        }
        // 手动勾回来的“已导出且设计没变”要真的再导一次：先摘掉那条旧任务，
        // 否则 enqueueBatch 会按同一幂等键判成重复，用户点了却什么也没发生。
        chosen.filter { it.unchangedExport != null }.forEach { row ->
            row.unchangedExport?.let { old -> projects.removeBatchJob(old.id) }
        }
        val preflight = projects.preflightBatch(designed)
        if (preflight.allUndesigned) {
            notice = "选中的 ${preflight.undesignedCount} 张都还没有应用边框，未创建任何任务。"
            return
        }
        val toEnqueue = if (preflight.hasUndesigned) preflight.designedPhotoIds else designed
        if (preflight.hasUndesigned) {
            notice = "有 ${preflight.undesignedCount} 张还没有应用边框，已跳过：${preflight.undesignedSummary}。"
        } else {
            notice = null
        }
        val added = projects.enqueueBatch(toEnqueue)
        projects.startBatch()
        println("FM_DESKTOP_BATCH_START selected=${selected.size} designed=$designed added=$added")
    }

    fun cancelRemaining() {
        projects.cancelBatch()
        notice = "已取消剩余任务；已完成的不受影响。"
    }

    fun retryFailed() {
        val ids = BatchQueueView.retryableIds(projects.batchJobs)
        if (ids.isEmpty()) return
        ids.forEach { projects.retryBatch(it) }
        projects.startBatch()
        notice = "重试 ${ids.size} 项。"
    }

    Row(modifier = modifier.fillMaxSize()) {
        BatchPhotoList(
            rows = rows,
            projects = projects,
            selected = selected,
            onToggle = { row ->
                selected = BatchSelection.toggle(selected, row)
                notice = null
            },
            onSelectAll = { selected = BatchSelection.selectAll(rows) },
            onSelectNone = { selected = emptySet() },
            onCreateDesign = onBackToEdit,
            // `styles.css:513` `.batch { grid-template-columns: 296px … 336px }`
            modifier = Modifier.width(296.dp).fillMaxHeight(),
        )
        Box(Modifier.width(1.dp).fillMaxHeight().background(FmPalette.outline))
        BatchContactSheet(
            photos = projects.photos,
            rows = rows,
            jobs = jobs,
            projects = projects,
            onOpenPhoto = onBackToEdit,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        Box(Modifier.width(1.dp).fillMaxHeight().background(FmPalette.outline))
        BatchQueuePanel(
            summary = summary,
            queueRows = queueRows,
            jobs = jobs,
            settings = settings,
            directory = directory,
            selectedCount = selected.size,
            notice = notice,
            running = projects.batchRunning,
            onOpenExportSettings = onOpenExportSettings,
            onStart = { enqueueAndStart() },
            onCancelRemaining = { cancelRemaining() },
            onRetryFailed = { retryFailed() },
            onOpenDirectory = {
                val path = jobs.lastOrNull { it.outputPath != null }?.outputPath
                notice = when {
                    path == null -> "队列里还没有已完成的导出。"
                    else -> platform?.revealInFolder(path) ?: notice
                }
            },
            modifier = Modifier.width(336.dp).fillMaxHeight(),
        )
    }
}

/** 左列：照片勾选列表。没选边框的不能勾，已导出且设计没变的默认不勾。 */
@Composable
private fun BatchPhotoList(
    rows: List<BatchPhotoRow>,
    projects: ProjectHolder,
    selected: Set<String>,
    onToggle: (BatchPhotoRow) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onCreateDesign: (String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "照片",
                    style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                    color = FmPalette.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${selected.size} / ${rows.size} 已选",
                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp).withNumericDigits(),
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "全选",
                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                    color = FmPalette.onSurfaceMuted,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onSelectAll)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
                Text(
                    text = "清空",
                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                    color = FmPalette.onSurfaceMuted,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onSelectNone)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
        if (rows.isEmpty()) {
            Text(
                text = "还没有导入照片。先到「编辑」用顶栏的「导入」添加照片。",
                style = FmType.bodyMedium,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier.padding(FmSpacing.lg),
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(rows, key = { it.photoId }) { row ->
                    BatchPhotoListRow(
                        row = row,
                        projects = projects,
                        checked = row.photoId in selected,
                        onToggle = { onToggle(row) },
                        onCreateDesign = { onCreateDesign(row.photoId) },
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
        Text(
            text = "没选边框的照片不会导出。每张照片按它自己的设计与水印导出。",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
            modifier = Modifier.padding(FmSpacing.md),
        )
    }
}

@Composable
private fun BatchPhotoListRow(
    row: BatchPhotoRow,
    projects: ProjectHolder,
    checked: Boolean,
    onToggle: () -> Unit,
    onCreateDesign: () -> Unit,
) {
    val interactive = { if (row.designed) onToggle() else onCreateDesign() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Checkbox, onClick = interactive)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) FmPalette.primary else FmPalette.surfaceMuted)
                .border(1.dp, if (checked) FmPalette.primary else FmPalette.outlineStrong, RoundedCornerShape(4.dp))
                .semantics { contentDescription = if (checked) "已勾选 ${row.displayName}" else "未勾选 ${row.displayName}" },
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Text("✓", style = FmType.labelSmall, color = FmPalette.onPrimary)
        }
        Spacer(Modifier.width(10.dp))
        BatchThumbnail(
            photoPath = row.photoPath,
            projects = projects,
            modifier = Modifier.width(52.dp).height(40.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.displayName,
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                    .withNumericDigits(),
                color = if (row.designed) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.detail,
                style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
                color = if (row.designed) FmPalette.onSurfaceMuted else FmPalette.warning,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!row.designed) {
            Text(
                text = "去选边框",
                style = FmType.labelSmall,
                color = FmPalette.primaryStrong,
                maxLines = 1,
            )
        }
    }
}

/** 缩略图：项目里的照片或已导出的成片；解不出来就留一个中性占位，不假装有图。 */
@Composable
private fun BatchThumbnail(
    photoPath: String?,
    projects: ProjectHolder? = null,
    placeholder: String = "片",
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val path = photoPath
    val holder = projects
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path, holder) {
        value = if (path == null || holder == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                val bytes = holder.thumbnail(path, maxEdge = 320)
                bytes?.let { runCatching { LibraryPreviewLoader.decode(it) }.getOrNull() }
            }
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(FmPalette.surfaceMuted)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.chip)),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(placeholder, style = FmType.labelSmall, color = FmPalette.onSurfaceFaint)
        }
    }
}

/** 队列整体进度条（引擎串行导出，单张没有百分比可报）。 */
@Composable
private fun BatchProgressBar(percent: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.outline),
    ) {
        Box(
            Modifier
                .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.primary),
        )
    }
}

/** 中间：成品联系表。导出前是源图 + 边框名，导出后换成真成片缩略图。 */
@Composable
private fun BatchContactSheet(
    photos: List<PhotoRef>,
    rows: List<BatchPhotoRow>,
    jobs: List<BatchJob>,
    projects: ProjectHolder,
    onOpenPhoto: (String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.background(FmPalette.surfaceMuted)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
        if (photos.isEmpty()) {
            Text(
                text = "没有照片可以拼进联系表。",
                style = FmType.bodyMedium,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier.padding(FmSpacing.lg),
            )
        } else {
            LazyVerticalGrid(
                // `.sheetgrid`：minmax(240px, 1fr)、gap 22、padding 24、底 `--seg`。
                columns = GridCells.Adaptive(240.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(24.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                gridItems(photos, key = { it.id }) { photo ->
                    ContactSheetCell(
                        photo = photo,
                        row = rows.firstOrNull { it.photoId == photo.id },
                        job = jobs.lastOrNull { it.photoPath == photo.path },
                        projects = projects,
                        onOpenPhoto = { onOpenPhoto(photo.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactSheetCell(
    photo: PhotoRef,
    row: BatchPhotoRow?,
    job: BatchJob?,
    projects: ProjectHolder,
    onOpenPhoto: () -> Unit,
) {
    val status = when (job?.status) {
        BatchJobStatus.Done -> "完成"
        BatchJobStatus.Failed -> "失败"
        BatchJobStatus.Running -> "导出中"
        BatchJobStatus.Queued -> "待导出"
        BatchJobStatus.Cancelled -> "已取消"
        null -> if (row?.designed == true) "未导出" else "缺少边框"
    }
    val statusColor = when (job?.status) {
        BatchJobStatus.Done -> FmPalette.success
        BatchJobStatus.Failed -> FmPalette.error
        BatchJobStatus.Running, BatchJobStatus.Queued -> FmPalette.primaryStrong
        else -> FmPalette.onSurfaceMuted
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(photo.id) { detectTapGestures(onDoubleTap = { onOpenPhoto() }) },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .fmElevated(FmElevation.card, RoundedCornerShape(6.dp))
                .background(FmPalette.surface)
                .padding(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            BatchThumbnail(
                photoPath = job?.outputPath ?: photo.path,
                projects = projects,
                placeholder = "…",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            // `.sheetcell .st`：左上角徽标，h22、圆角 6、白底 95%、11.5/600。
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-8).dp, y = (-8).dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(FmPalette.surface)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = status,
                    style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
                    color = statusColor,
                    maxLines = 1,
                )
            }
        }
        // `.sheetcell .cap`：文件名（numeric 500）左、边框名右。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = photo.displayName,
                style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
                    .withNumericDigits(),
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = row?.cardName ?: "未选边框",
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
            )
        }
    }
}

/** 右列：导出设置摘要 + 队列（进度、失败原因、重试 / 取消剩余）。 */
@Composable
private fun BatchQueuePanel(
    summary: BatchQueueSummary,
    queueRows: List<BatchQueueRow>,
    jobs: List<BatchJob>,
    settings: ProductExportSettings,
    directory: String,
    selectedCount: Int,
    notice: String?,
    running: Boolean,
    onOpenExportSettings: () -> Unit,
    onStart: () -> Unit,
    onCancelRemaining: () -> Unit,
    onRetryFailed: () -> Unit,
    onOpenDirectory: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        // `.bq .sum`：标题 + 修改 link + 4 行 kv（64 px 标签列）。
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "导出设置",
                    style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                    color = FmPalette.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "修改",
                    style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
                    color = FmPalette.primaryStrong,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button, onClick = onOpenExportSettings)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            batchSettingsSummary(settings, directory).forEach { (key, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        key,
                        style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
                        color = FmPalette.onSurfaceMuted,
                        maxLines = 1,
                        modifier = Modifier.width(64.dp),
                    )
                    Text(
                        text = value,
                        style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp).withNumericDigits(),
                        color = FmPalette.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            DlgNote("批量与单张共用这份设置；单张在「导出」对话框里改。")
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
        // `.qlist`：导出队列（每行 = 文件名 + 状态 + 失败原因/成片名）。
        if (queueRows.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                Text(
                    text = "队列为空。勾选照片后点下面的「开始导出」。",
                    style = FmType.bodyMedium,
                    color = FmPalette.onSurfaceMuted,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
                items(queueRows, key = { it.id }) { row -> BatchQueueRowView(row) }
            }
        }
        notice?.let {
            Box(Modifier.padding(horizontal = 16.dp)) { DlgNote(it) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
        // `.qfoot`：全队列进度 + 一个主操作。
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "完成 ${summary.done} · 失败 ${summary.failed} · 共 ${summary.total}",
                    style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp).withNumericDigits(),
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${summary.percent}%" + if (summary.isRunning) " · 正在导出" else "",
                    style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp).withNumericDigits(),
                    color = if (summary.isRunning) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(6.dp))
            BatchProgressBar(summary.percent)
            Spacer(Modifier.height(10.dp))
            val action = BatchQueueView.primaryAction(running, jobs)
            val retryCount = queueRows.count {
                it.state == BatchRowState.Failed || it.state == BatchRowState.Cancelled
            }
            when (action) {
                BatchPrimaryAction.CancelRemaining -> DlgButton(
                    text = "取消剩余",
                    style = DlgButtonStyle.Line,
                    onClick = onCancelRemaining,
                    modifier = Modifier.fillMaxWidth(),
                )
                BatchPrimaryAction.RetryFailed -> Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
                    DlgButton(text = "打开目录", style = DlgButtonStyle.Line, onClick = onOpenDirectory)
                    DlgButton(
                        text = "重试 $retryCount 张",
                        style = DlgButtonStyle.Primary,
                        onClick = onRetryFailed,
                        modifier = Modifier.weight(1f),
                    )
                }
                BatchPrimaryAction.ExportAgain -> Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm)) {
                    DlgButton(text = "打开目录", style = DlgButtonStyle.Line, onClick = onOpenDirectory)
                    DlgButton(
                        text = "再次导出",
                        style = DlgButtonStyle.Primary,
                        enabled = selectedCount > 0,
                        onClick = onStart,
                        modifier = Modifier.weight(1f),
                    )
                }
                BatchPrimaryAction.Start -> DlgButton(
                    text = "开始导出 $selectedCount 张",
                    style = DlgButtonStyle.Primary,
                    enabled = selectedCount > 0,
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BatchQueueRowView(row: BatchQueueRow) {
    val color = when (row.state) {
        BatchRowState.Done -> FmPalette.success
        BatchRowState.Failed -> FmPalette.error
        BatchRowState.Running -> FmPalette.primaryStrong
        BatchRowState.Queued -> FmPalette.onSurfaceSecondary
        BatchRowState.Cancelled -> FmPalette.onSurfaceMuted
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.displayName,
                style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
                    .withNumericDigits(),
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            if (row.state == BatchRowState.Running) {
                Text("导出中…", style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp), color = color, maxLines = 1)
            } else {
                Text(
                    BatchQueueView.statusLabel(row.state),
                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                    color = color,
                    maxLines = 1,
                )
            }
        }
        val detail = row.reason ?: row.outputPath?.substringAfterLast('/')?.substringAfterLast('\\')
        if (detail != null) {
            Text(
                text = detail,
                style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp).withNumericDigits(),
                color = if (row.reason != null) FmPalette.error else FmPalette.onSurfaceFaint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))
    }
}



