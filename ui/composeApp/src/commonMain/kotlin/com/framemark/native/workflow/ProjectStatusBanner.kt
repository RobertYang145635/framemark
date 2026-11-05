package com.framemark.native.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType

/** Non-blocking status strip: load/save failures and per-file import errors. */
@Composable
fun ProjectStatusBanner(
    projects: ProjectHolder,
    onRetryImports: () -> Unit,
    notice: String? = null,
) {
    val projectBlocked = projects.projectWritesBlocked
    val message = projects.loadError ?: projects.saveError
    val failures = projects.importFailures
    if (message == null && failures.isEmpty() && notice == null) return
    val baseText = message
        ?: if (failures.isNotEmpty()) {
            failures.size.toString() + " 张照片导入失败：" +
                failures.take(2).joinToString("；") { it.reason }
        } else {
            notice.orEmpty()
        }
    val text = if (projectBlocked && projects.loadError != null) {
        "$baseText 已暂停工程写入，原工程文件未被覆盖。"
    } else {
        baseText
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.xs)
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(FmPalette.surfaceMuted)
            .border(1.dp, FmPalette.error.copy(alpha = 0.5f), RoundedCornerShape(FmRadii.chip))
            .padding(horizontal = FmSpacing.md, vertical = FmSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = FmType.labelSmall,
            color = if (message != null || failures.isNotEmpty()) FmPalette.error else FmPalette.onSurfaceSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (projectBlocked) {
            Text(
                "重试读取工程",
                style = FmType.labelSmall,
                color = FmPalette.primaryStrong,
                modifier = Modifier
                    .clickable {
                        println("FM_PROJECT_RETRY_REQUESTED surface=status-banner")
                        val recovered = projects.retryProjectLoad()
                        println("FM_PROJECT_RETRY_RESULT recovered=$recovered error=${projects.loadError ?: "-"}")
                    }
                    .padding(4.dp),
            )
        }
        if (failures.isNotEmpty()) {
            Text(
                "重试",
                style = FmType.labelSmall,
                color = FmPalette.primaryStrong,
                modifier = Modifier.clickable(onClick = onRetryImports).padding(4.dp),
            )
            Text(
                "忽略",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier.clickable { projects.clearImportFailures() }.padding(4.dp),
            )
        }
    }
}

/**
 * Full-surface recovery gate used while a project is loading or an existing
 * document could not be read. It intercepts pointer input so edit/import/export
 * controls underneath cannot create changes that will not be persisted.
 */
@Composable
fun ProjectReadRecoveryBlock(
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FmPalette.canvas.copy(alpha = 0.97f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(FmSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .clip(RoundedCornerShape(FmRadii.card))
                .background(FmPalette.surface)
                .border(1.dp, FmPalette.error.copy(alpha = 0.55f), RoundedCornerShape(FmRadii.card))
                .padding(FmSpacing.lg),
        ) {
            Text(
                text = if (loading) "正在读取工程" else "工程读取失败",
                style = FmType.titleLarge,
                color = if (loading) FmPalette.onSurface else FmPalette.error,
            )
            Spacer(Modifier.height(FmSpacing.sm))
            Text(
                text = if (loading) {
                    "正在校验本机工程文件，请稍候。"
                } else {
                    "已停止所有工程写入，原工程文件未被覆盖。修复或恢复原文件后再重试。"
                },
                style = FmType.bodyMedium,
                color = FmPalette.onSurfaceSecondary,
            )
            error?.let {
                Spacer(Modifier.height(FmSpacing.sm))
                Text(
                    text = it,
                    style = FmType.labelSmall,
                    color = FmPalette.error,
                )
            }
            if (!loading && error != null) {
                Spacer(Modifier.height(FmSpacing.md))
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("重试读取工程", style = FmType.labelMedium)
                }
            }
        }
    }
}
