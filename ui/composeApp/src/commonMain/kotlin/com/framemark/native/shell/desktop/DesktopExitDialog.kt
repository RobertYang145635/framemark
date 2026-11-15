package com.framemark.native.shell.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.ExitBlock

/**
 * W-02: the window refused to close because the last save failed.
 *
 * Three honest exits, in the order a user should try them:
 *  - 「重试保存」 writes again and closes the window on success;
 *  - 「另存副本」 writes the in-memory project to a path the user picks — useful
 *    when the data directory (not the disk) is the problem — and keeps the
 *    window open with the copy's location;
 *  - 「放弃并退出」 is the only way to leave with unsaved edits, and it is a
 *    deliberate click, not the default close behaviour.
 *
 * 「打开工程文件夹」 helps a user back up the last good document by hand.
 */
@Composable
internal fun DesktopExitBlockedDialog(
    block: ExitBlock?,
    onRetry: () -> Unit,
    onSaveCopy: () -> Unit,
    onRevealFolder: (() -> Unit)?,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (block == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("工程还没保存") },
        text = {
            Column {
                Text(
                    "关闭窗口前最后一次保存没有成功，磁盘上的工程不是最新版本。直接退出会丢掉这次修改。",
                    style = FmType.bodyMedium,
                    color = FmPalette.onSurfaceMuted,
                )
                Spacer(Modifier.height(FmSpacing.sm))
                Text(
                    text = block.reason,
                    style = FmType.bodyMedium,
                    color = FmPalette.error,
                )
                block.copyPath?.let { path ->
                    Spacer(Modifier.height(FmSpacing.sm))
                    Text(
                        "已另存工程副本：$path",
                        style = FmType.bodyMedium,
                        color = FmPalette.primaryStrong,
                    )
                }
                block.copyError?.let { reason ->
                    Spacer(Modifier.height(FmSpacing.sm))
                    Text(
                        "另存副本失败：$reason",
                        style = FmType.bodyMedium,
                        color = FmPalette.error,
                    )
                }
                Spacer(Modifier.height(FmSpacing.md))
                // Secondary actions live in the body: AlertDialog only lays out one
                // confirm and one dismiss button, and all three escape hatches must
                // stay visible without a second dialog.
                KeptActionRow("另存副本", Modifier.fillMaxWidth()) { onSaveCopy() }
                if (onRevealFolder != null) {
                    KeptActionRow("打开工程文件夹", Modifier.fillMaxWidth()) { onRevealFolder() }
                }
                KeptActionRow("放弃修改并退出", Modifier.fillMaxWidth()) { onDiscard() }
            }
        },
        confirmButton = {
            TextButton(onClick = onRetry) { Text("重试保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** One full-width secondary action, 44 dp tall so it is a real target. */
@Composable
private fun KeptActionRow(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(FmPalette.surfaceMuted)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = FmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = FmType.bodyMedium, color = FmPalette.onSurface)
    }
}
