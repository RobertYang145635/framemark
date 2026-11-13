package com.framemark.native.shell.desktop

import com.framemark.native.product.BatchJob
import com.framemark.native.product.BatchJobStatus
import com.framemark.native.product.NoticeText
import com.framemark.native.product.hasCjk
import com.framemark.native.product.ExportFormat
import com.framemark.native.product.ExportSettings
import com.framemark.native.product.ExportSizeMode
import com.framemark.native.product.PhotoDesign
import com.framemark.native.product.PhotoRef

/**
 * WP-D2 批量导出工作区的纯逻辑：勾选规则、队列状态、失败原因。
 *
 * 执行与恢复**全部复用**现有链路（`ProjectHolder.enqueueBatch / startBatch /
 * cancelBatch / retryBatch`，底层是 `product.BatchQueue` + `recoverBatchQueue`），
 * 这里只回答三个界面问题：默认勾哪些、每项现在是什么状态、失败要怎么说。
 */

/** 一张照片在批量列表里的样子。 */
data class BatchPhotoRow(
    val photoId: String,
    val displayName: String,
    /** 缩略图/联系表用的源文件路径。 */
    val photoPath: String,
    /** 这张照片自己的边框（`project.designs[photoId]`）；null 表示还没应用边框。 */
    val cardId: String?,
    val cardName: String?,
    /** 已导出过、而且这批参数与设计都没变：默认不勾，但可以手动勾回来。 */
    val unchangedExport: BatchJob?,
) {
    val designed: Boolean get() = cardId != null
    val skipByDefault: Boolean get() = designed && unchangedExport != null

    /** 列表里第二行的小字：没边框 / 已导出过 / 边框名。 */
    val detail: String
        get() = when {
            !designed -> "未选边框，不会导出"
            unchangedExport != null -> "已导出过，设计未变 · ${cardName ?: cardId}"
            else -> cardName ?: cardId.orEmpty()
        }
}

/**
 * 勾选规则（HANDOFF §4 WP-D2 / UX-RESEARCH A-05）：
 *  - 没应用边框的照片不能勾（`项目` 的 pre-flight 也会拒绝，这里先挡住）；
 *  - 已导出且设计没变的默认不勾，手动勾回来表示“再导一次”。
 */
object BatchSelection {
    fun defaultSelection(rows: List<BatchPhotoRow>): Set<String> =
        rows.filter { it.designed && it.unchangedExport == null }.map { it.photoId }.toSet()

    fun selectable(row: BatchPhotoRow): Boolean = row.designed

    fun toggle(selected: Set<String>, row: BatchPhotoRow): Set<String> = when {
        !selectable(row) -> selected
        row.photoId in selected -> selected - row.photoId
        else -> selected + row.photoId
    }

    fun selectAll(rows: List<BatchPhotoRow>): Set<String> = rows.filter { selectable(it) }.map { it.photoId }.toSet()

    /** 底部那一行：`共 N 张 · 已选 M · 跳过 K`。 */
    fun summaryLine(rows: List<BatchPhotoRow>, selected: Set<String>): String {
        val designed = rows.count { it.designed }
        val unchanged = rows.count { it.skipByDefault }
        val undesigned = rows.size - designed
        val parts = mutableListOf("共 ${rows.size} 张", "可选 $designed 张", "已选 ${selected.size} 张")
        if (unchanged > 0) parts.add("跳过已导出 $unchanged 张")
        if (undesigned > 0) parts.add("未选边框 $undesigned 张")
        return parts.joinToString(" · ")
    }
}

/** 队列里一项的状态；与 `product.BatchJobStatus` 一一对应，便于界面直接画。 */
enum class BatchRowState { Queued, Running, Done, Failed, Cancelled }

data class BatchQueueRow(
    val id: String,
    val displayName: String,
    val state: BatchRowState,
    val outputPath: String?,
    /** 失败原因（已经过 [BatchQueueView.failureText] 的人话版本）；其它状态为 null。 */
    val reason: String?,
    val attempts: Int,
)

data class BatchQueueSummary(
    val total: Int,
    val queued: Int,
    val running: Int,
    val done: Int,
    val failed: Int,
    val cancelled: Int,
) {
    val settled: Int get() = done + failed + cancelled
    /** 整体进度：已结算项 / 全部项（引擎串行导出，没有单张百分比）。 */
    val percent: Int get() = if (total <= 0) 0 else (settled * 100 / total)
    val isRunning: Boolean get() = running > 0 || queued > 0
}

/** 右侧队列底部的按钮，按队列状态取一个主操作（演示稿 `paintBatch` 的三态）。 */
enum class BatchPrimaryAction { Start, CancelRemaining, RetryFailed, ExportAgain }

object BatchQueueView {
    fun rows(jobs: List<BatchJob>): List<BatchQueueRow> = jobs.map { job ->
        BatchQueueRow(
            id = job.id,
            displayName = job.displayName,
            state = job.status.toRowState(),
            outputPath = job.outputPath,
            reason = if (job.status == BatchJobStatus.Failed) {
                failureText(job.error?.code, job.error?.message)
            } else {
                null
            },
            attempts = job.attempts,
        )
    }

    fun summary(jobs: List<BatchJob>): BatchQueueSummary = BatchQueueSummary(
        total = jobs.size,
        queued = jobs.count { it.status == BatchJobStatus.Queued },
        running = jobs.count { it.status == BatchJobStatus.Running },
        done = jobs.count { it.status == BatchJobStatus.Done },
        failed = jobs.count { it.status == BatchJobStatus.Failed },
        cancelled = jobs.count { it.status == BatchJobStatus.Cancelled },
    )

    /** 「取消剩余」的语义：排队的会被取消，正在导出的那项跑完为止（现有 runBatch 的行为）。 */
    fun cancelableCount(jobs: List<BatchJob>): Int =
        jobs.count { it.status == BatchJobStatus.Queued || it.status == BatchJobStatus.Running }

    fun retryableIds(jobs: List<BatchJob>): List<String> =
        jobs.filter { it.status == BatchJobStatus.Failed || it.status == BatchJobStatus.Cancelled }.map { it.id }

    fun primaryAction(running: Boolean, jobs: List<BatchJob>): BatchPrimaryAction = when {
        running -> BatchPrimaryAction.CancelRemaining
        jobs.any { it.status == BatchJobStatus.Failed || it.status == BatchJobStatus.Cancelled } ->
            BatchPrimaryAction.RetryFailed
        summary(jobs).done > 0 -> BatchPrimaryAction.ExportAgain
        else -> BatchPrimaryAction.Start
    }

    fun statusLabel(state: BatchRowState): String = when (state) {
        BatchRowState.Queued -> "待导出"
        BatchRowState.Running -> "导出中"
        BatchRowState.Done -> "完成"
        BatchRowState.Failed -> "失败"
        BatchRowState.Cancelled -> "已取消"
    }

    /**
     * 失败原因的人话版本。引擎/系统抛出的原文常常是英文 Java 异常（例如文件被占用、
     * 拒绝访问），直接摆给用户没有信息量；认得的归成一类，认不得的给一句通用文案。
     *
     * 支撑码（`FM-E-…`）不进这句话：它已经在队列执行器的失败日志行和
     * `BatchJobError.code` 里，用户看到的只是句子（审计 item 4）。
     */
    fun failureText(code: String?, message: String?): String {
        val raw = message?.trim().orEmpty()
        if (raw.isBlank()) return "导出没有成功，原因未知。"
        val stripped = NoticeText.human(stripCode(raw)).orEmpty()
        val reason = when {
            containsAny(stripped, "占用", "being used", "in use", "另一个程序") ->
                "文件被占用：另一个程序正在使用它。"
            containsAny(stripped, "拒绝访问", "access is denied", "access denied", "permission denied", "没有权限", "权限不足") ->
                "没有写入权限，导出目录不可写。"
            containsAny(stripped, "找不到指定的路径", "系统找不到", "no such file", "not a directory", "不是目录", "路径不存在") ->
                "输出路径不存在或不可用。"
            containsAny(stripped, "磁盘空间", "not enough space", "disk full", "空间不足") ->
                "磁盘空间不足。"
            stripped.isNotBlank() && stripped.none { it in '一'..'鿿' } ->
                // An untranslatable English diagnostic is still not copy.
                "导出没有成功，请重试。"
            stripped.isNotBlank() -> stripped
            else -> "导出没有成功，请重试。"
        }
        // The code is the support handle, not the sentence: the queue runner already
        // logs it where the item actually failed, and this mapper runs every frame, so
        // it never logs (and never names the evidence tag) itself.
        return reason.take(160)
    }

    private fun stripCode(message: String): String =
        message.replace(Regex("^FM-E-[A-Z0-9-]+\\s*"), "").trim()

    private fun containsAny(text: String, vararg needles: String): Boolean {
        val lowered = text.lowercase()
        return needles.any { lowered.contains(it.lowercase()) }
    }

    private fun BatchJobStatus.toRowState(): BatchRowState = when (this) {
        BatchJobStatus.Queued -> BatchRowState.Queued
        BatchJobStatus.Running -> BatchRowState.Running
        BatchJobStatus.Done -> BatchRowState.Done
        BatchJobStatus.Failed -> BatchRowState.Failed
        BatchJobStatus.Cancelled -> BatchRowState.Cancelled
    }
}

/**
 * 批量真正用的设置：与 `enqueueBatch` 一致，JPEG 质量取本机偏好
 * （`local-export.properties` 里的 `jpegQuality`，桌面可调 60–100）。
 */
fun batchSettings(settings: ExportSettings, preferredQuality: Int): ExportSettings =
    settings.copy(quality = preferredQuality.coerceIn(ExportRules.QUALITY_MIN, ExportRules.QUALITY_MAX))

/**
 * 用公开的 `BatchJob.forPhoto` 造一条“探针”任务，只为拿它的 `idempotencyKey`：
 * 键里覆盖照片指纹、设计（含水印）和导出设置，与 `enqueueBatch` 判重用的是同一个键，
 * 所以“已导出且设计没变”的判定与队列自身的去重完全一致，不会出现两套口径。
 */
fun probeJob(photo: PhotoRef, design: PhotoDesign, settings: ExportSettings): BatchJob =
    BatchJob.forPhoto(
        id = "probe-${photo.id}",
        photoPath = photo.path,
        photoFingerprint = photo.fingerprint,
        displayName = photo.displayName,
        design = design,
        exportSettings = settings,
        nameIndex = 1,
        outputDirectory = "",
    )

/** 照片勾选列表的行；`jobs` 用来看“是否已导出过、设计还没变”。 */
fun batchPhotoRows(
    photos: List<PhotoRef>,
    designs: Map<String, PhotoDesign>,
    settings: ExportSettings,
    jobs: List<BatchJob>,
    cardName: (String) -> String?,
): List<BatchPhotoRow> = photos.map { photo ->
    val design = designs[photo.id]
    val unchanged = design?.let { current ->
        val key = probeJob(photo, current, settings).idempotencyKey
        jobs.lastOrNull { it.status == BatchJobStatus.Done && it.idempotencyKey == key }
    }
    BatchPhotoRow(
        photoId = photo.id,
        displayName = photo.displayName,
        photoPath = photo.path,
        cardId = design?.cardId,
        cardName = design?.cardId?.let(cardName),
        unchangedExport = unchanged,
    )
}

/** 右侧“导出设置摘要”的四行（报告 §6：设置摘要 + 队列）。 */
fun batchSettingsSummary(settings: ExportSettings, directory: String): List<Pair<String, String>> {
    val format = when (settings.format) {
        ExportFormat.Jpeg -> "JPG"
        ExportFormat.Png -> "PNG"
        ExportFormat.WebP -> if (settings.webpLossless) "WebP 无损" else "WebP 有损"
    }
    val size = when (settings.sizeMode) {
        ExportSizeMode.Original -> "原尺寸"
        ExportSizeMode.LongEdge -> "长边 ${settings.longEdge ?: 0} px"
    }
    val quality = when (settings.format) {
        ExportFormat.Jpeg -> "质量 ${settings.quality}"
        ExportFormat.Png -> "无损编码"
        ExportFormat.WebP ->
            if (settings.webpLossless) "无损编码" else "质量 ${settings.quality}"
    }
    return listOf(
        "格式" to "$format · $quality",
        "尺寸" to size,
        "文件名" to (settings.filenamePattern + "." + settings.format.extension),
        "位置" to directory,
    )
}
