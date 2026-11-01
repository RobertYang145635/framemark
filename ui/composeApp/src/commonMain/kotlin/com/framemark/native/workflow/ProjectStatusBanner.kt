package com.framemark.native.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val message = projects.loadError ?: projects.saveError
    val failures = projects.importFailures
    if (message == null && failures.isEmpty() && notice == null) return
    val text = message
        ?: if (failures.isNotEmpty()) {
            failures.size.toString() + " 张照片导入失败：" +
                failures.take(2).joinToString("；") { it.reason }
        } else {
            notice.orEmpty()
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
