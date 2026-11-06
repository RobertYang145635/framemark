package com.framemark.native.shell.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.framemark.native.product.PhotoRef
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType

/**
 * 右栏“⋯ → 应用到其他照片…”：勾选目标照片，把当前照片的边框、参数和文字复制过去。
 *
 * 复制动作走 `ProjectHolder.applyDesignFromPhoto`，它带默认实现（不支持时返回 -1），
 * 因此手机端/测试替身不需要实现这个能力，也不会因为桌面多一个菜单项而编译失败。
 */
@Composable
fun DesktopApplyDesignDialog(
    source: PhotoRef?,
    photos: List<PhotoRef>,
    onDismiss: () -> Unit,
    onApply: (List<String>) -> Unit,
) {
    val targets = remember(photos, source?.id) { photos.filter { it.id != source?.id } }
    var selected by remember(targets) { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("应用到其他照片", style = FmType.titleSmall) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "把「${source?.displayName ?: "当前照片"}」的边框、参数和文字复制到：",
                    style = FmType.bodyMedium,
                    color = FmPalette.onSurfaceMuted,
                )
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    targets.forEach { photo ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (photo.id in selected) selected - photo.id else selected + photo.id
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = photo.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + photo.id else selected - photo.id
                                },
                            )
                            Text(
                                text = photo.displayName,
                                style = FmType.bodyMedium,
                                color = FmPalette.onSurface,
                                modifier = Modifier.padding(start = FmSpacing.xs),
                            )
                        }
                    }
                }
                if (targets.isEmpty()) {
                    Text(
                        text = "项目里还没有其他照片。",
                        style = FmType.bodyMedium,
                        color = FmPalette.onSurfaceMuted,
                        modifier = Modifier.padding(top = FmSpacing.sm),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onApply(selected.toList()) },
            ) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
