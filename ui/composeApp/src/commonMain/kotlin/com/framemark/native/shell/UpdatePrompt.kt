package com.framemark.native.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.framemark.native.product.update.UpdateAsset
import com.framemark.native.product.update.UpdateManifest
import com.framemark.native.product.update.UpdateUrls
import com.framemark.native.product.update.installUpdate
import com.framemark.native.theme.FmPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 启动时的「发现新版本」弹窗（2026-11-20）。
 *
 * 一句话标题 + 一条更新说明 + 两个按钮：**下载并安装** / **跳过这个版本**。
 * 「跳过」记在 [com.framemark.native.product.update.UpdateLaunch] 里，同一个版本不再
 * 重复打扰；下载失败时才出现第三个入口（打开发布页手动下）。
 */
@Composable
fun UpdatePromptDialog(
    manifest: UpdateManifest,
    asset: UpdateAsset?,
    onDismiss: () -> Unit,
    onSkipVersion: () -> Unit,
) {
    var busy by remember(manifest.versionName) { mutableStateOf(false) }
    var progress by remember(manifest.versionName) { mutableStateOf(0f) }
    var error by remember(manifest.versionName) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val firstNote = manifest.notes.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                text = "新版本 ${manifest.versionName}",
                color = FmPalette.onSurface,
            )
        },
        text = {
            Column {
                if (firstNote.isNotEmpty()) {
                    Text(firstNote, color = FmPalette.onSurfaceSecondary)
                }
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "正在下载 ${(progress * 100).toInt()}%",
                        color = FmPalette.onSurfaceMuted,
                    )
                }
                error?.let { message ->
                    Spacer(Modifier.height(10.dp))
                    Text(message, color = FmPalette.onSurfaceSecondary)
                }
            }
        },
        confirmButton = {
            if (asset == null) {
                TextButton(onClick = onDismiss) { Text("知道了") }
            } else {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        error = null
                        progress = 0f
                        scope.launch {
                            val message = withContext(Dispatchers.IO) {
                                installUpdate(asset, manifest.versionName) { done, total ->
                                    if (total > 0) {
                                        progress = (done.toFloat() / total).coerceIn(0f, 1f)
                                    }
                                }
                            }
                            busy = false
                            if (message == null) onDismiss() else error = message
                        }
                    },
                ) { Text("下载并安装") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (error != null) {
                    val releasePage = remember(manifest.versionName) {
                        UpdateUrls.releasePage(UpdateUrls.RELEASE_PAGE, com.framemark.native.product.update.UpdateSource.GitHub) +
                            "/tag/v${manifest.versionName}"
                    }
                    val handler = androidx.compose.ui.platform.LocalUriHandler.current
                    TextButton(onClick = { runCatching { handler.openUri(releasePage) } }) { Text("打开发布页") }
                }
                TextButton(
                    enabled = !busy,
                    onClick = {
                        onSkipVersion()
                        onDismiss()
                    },
                ) { Text("跳过这个版本") }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
