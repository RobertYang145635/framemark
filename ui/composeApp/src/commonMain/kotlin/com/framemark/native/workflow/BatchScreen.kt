package com.framemark.native.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framemark.native.shell.HintCard
import com.framemark.native.shell.PageList
import com.framemark.native.shell.TitleBlock
import com.framemark.native.product.NoticeText
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * Closing hint of the batch sheet. User copy only: it states the export properties
 * (JPEG quality 100, 4:4:4) and says where files go. It must not name the internal
 * work item of the export encoder (`E5`) — that tag used to be on screen, and
 * `BatchScreenCopyTest` pins it out.
 */
internal const val BATCH_EXPORT_HINT: String =
    "导出用本机渲染（JPEG 质量 100，4:4:4），文件写在导出目录；从队列移除任务不会删除已导出的文件。"

/**
 * Batch queue page: real exports through the native engine, persisted to the app
 * data dir and resumable after a restart. The legacy tick loop below only drives
 * the U4 verifier's no-engine fixtures; real items advance from the export job.
 */
@Composable
fun BatchScreen(state: LazyListState, runner: BatchRunner, onClose: (() -> Unit)? = null) {
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(90)
            runner.tick()
        }
    }

    PageList(state) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                Box(modifier = Modifier.weight(1f)) {
                    TitleBlock(
                        title = "批量",
                        subtitle = "本机导出 · 队列保存在本机，重启后可从停下的位置继续",
                    )
                }
                if (onClose != null) {
                    GhostButton(text = "关闭", onClick = onClose)
                }
            }
        }
        item { BatchSummary(runner) }
        if (runner.items.isEmpty()) {
            item {
                Text(
                    text = "队列为空。点「添加一张」，按当前照片和库里可导出的边框建一条任务。",
                    style = FmType.bodyMedium,
                    color = FmPalette.onSurfaceMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FmRadii.card))
                        .background(FmPalette.surfaceMuted)
                        .padding(FmSpacing.lg),
                )
            }
        } else {
            items(runner.items, key = { it.id }) { item ->
                BatchRow(item = item, runner = runner, onRemove = { runner.remove(item) })
            }
        }
        item {
            // No milestone id (`E5`) and no "engine" vocabulary here: this card is
            // user copy. The export properties it states are the real ones.
            HintCard(BATCH_EXPORT_HINT)
        }
    }
}

@Composable
private fun BatchSummary(runner: BatchRunner) {
    val total = runner.items.size
    val percent = (runner.overallProgress * 100).roundToInt()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.card))
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.card))
            .padding(FmSpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("整体进度", style = FmType.labelMedium, color = FmPalette.onSurfaceMuted)
            Spacer(Modifier.weight(1f))
            Text("$percent%", style = FmType.labelMedium, color = FmPalette.onSurfaceSecondary)
        }
        Spacer(Modifier.height(FmSpacing.sm))
        FmProgress(progress = runner.overallProgress, height = 8)

        Spacer(Modifier.height(FmSpacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildString {
                    append("已完成 ${runner.completedCount} / $total")
                    if (runner.failedCount > 0) append(" · 失败 ${runner.failedCount}")
                },
                style = FmType.bodyMedium,
                color = if (runner.failedCount > 0) FmPalette.onSurface else FmPalette.onSurfaceSecondary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stateLabel(runner.runState),
                style = FmType.labelSmall,
                color = if (runner.runState == BatchRunState.Running) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
            )
        }

        Spacer(Modifier.height(FmSpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            GhostButton(
                text = "添加一张",
                enabled = total < 10,
                onClick = { runner.addSample() },
            )
            if (runner.failedCount > 0 && runner.runState != BatchRunState.Running) {
                GhostButton(
                    text = "重试失败项",
                    onClick = { runner.items.filter { it.status == BatchItemStatus.Failed }.forEach { runner.retry(it) } },
                )
            }
            Spacer(Modifier.weight(1f))
            if (runner.runState == BatchRunState.Running) {
                PrimaryButton(text = "暂停", onClick = { runner.pause() })
            } else if (runner.runState == BatchRunState.Paused) {
                PrimaryButton(text = "继续", onClick = { runner.resume() })
            } else {
                PrimaryButton(
                    text = if (total > 0 && runner.completedCount == total) "已全部完成" else "开始",
                    enabled = runner.hasPending,
                    onClick = { runner.start() },
                )
            }
            if (runner.runState == BatchRunState.Running || runner.runState == BatchRunState.Paused) {
                GhostButton(text = "取消", onClick = { runner.cancel() })
            }
        }
    }
}

@Composable
private fun BatchRow(item: BatchItem, runner: BatchRunner, onRemove: () -> Unit) {
    val percent = (item.progress * 100).roundToInt()
    val failed = item.status == BatchItemStatus.Failed
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
                    .width(44.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(FmRadii.chip))
                    .background(
                        Brush.linearGradient(
                            listOf(FmPalette.surfaceMuted, FmPalette.outline),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("片", style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
            }
            Spacer(Modifier.width(FmSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = FmType.bodyLarge,
                    color = FmPalette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(FmSpacing.xxs))
                Text(
                    text = itemStatusLabel(item, runner.runState),
                    style = FmType.labelSmall,
                    color = if (failed) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(FmSpacing.sm))
            if (failed) {
                Text(
                    text = "重试",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.surfaceMuted)
                        .clickable { runner.retry(item) }
                        .padding(horizontal = FmSpacing.sm, vertical = 5.dp),
                )
            } else {
                Text(
                    text = if (item.status == BatchItemStatus.Done) "100%" else "$percent%",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceSecondary,
                )
            }
            Spacer(Modifier.width(FmSpacing.md))
            Text(
                text = "移除",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.surfaceMuted)
                    .clickable(onClick = onRemove)
                    .padding(horizontal = FmSpacing.sm, vertical = 5.dp),
            )
        }
        Spacer(Modifier.height(FmSpacing.sm))
        FmProgress(progress = item.progress, height = 4)
    }
}

private fun stateLabel(state: BatchRunState): String = when (state) {
    BatchRunState.Idle -> "待开始"
    BatchRunState.Running -> "进行中"
    BatchRunState.Paused -> "已暂停"
    BatchRunState.Finished -> "已完成"
    BatchRunState.Cancelled -> "已取消"
}

private fun itemStatusLabel(item: BatchItem, runState: BatchRunState): String = when (item.status) {
    BatchItemStatus.Queued -> if (item.attempts > 0) "等待重试" else "等待"
    BatchItemStatus.Running -> if (runState == BatchRunState.Paused) "已暂停" else "导出中"
    BatchItemStatus.Done -> item.outputPath?.substringAfterLast('/')?.substringAfterLast('\\') ?: "已完成"
    BatchItemStatus.Failed -> "失败：" + (NoticeText.human(item.error) ?: "未知原因")
    BatchItemStatus.Cancelled -> "已取消"
}
