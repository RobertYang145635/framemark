package com.framemark.native.shell.desktop

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framemark.native.theme.FmElevation
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.theme.fmElevated

/** 空状态里可沿用的其他照片设计。 */
data class OtherPhotoDesign(
    val photoId: String,
    val photoLabel: String,
    val cardId: String,
    val styleId: String?,
)

/**
 * 舞台空状态（报告 §6）：照片还没有边框时，提供“沿用其他照片的设计”。
 *
 * 只用现有接口：点一条就读那张照片存的 `cardId` / `styleId` 调
 * `ProjectHolder.applyCard`。选中照片自己的参数覆盖（params/text）没有接口可搬，
 * 这一点写进报告，不动数据模型。
 */
@Composable
fun DesktopStageEmptyState(
    candidates: List<OtherPhotoDesign>,
    onReuse: (String, String?) -> Unit,
    onPickBorder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .clip(RoundedCornerShape(FmRadii.panel))
                .fmElevated(FmElevation.card, RoundedCornerShape(FmRadii.panel))
                .background(FmPalette.surface)
                .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.panel))
                .padding(FmSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("这张照片还没有边框", style = FmType.titleSmall, color = FmPalette.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "从左侧选一款，或沿用其他照片已经调好的设计。",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
            )
            Spacer(Modifier.height(FmSpacing.md))
            Text(
                "选择边框",
                style = FmType.labelMedium,
                color = FmPalette.onPrimary,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.primary)
                    .clickable(onClick = onPickBorder)
                    .padding(vertical = FmSpacing.sm),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (candidates.isNotEmpty()) {
                Spacer(Modifier.height(FmSpacing.md))
                Text(
                    "沿用其他照片的设计",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceFaint,
                    modifier = Modifier.fillMaxWidth(),
                )
                candidates.take(3).forEach { candidate ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(FmRadii.chip))
                            .background(FmPalette.surfaceMuted)
                            .clickable { onReuse(candidate.cardId, candidate.styleId) }
                            .padding(horizontal = FmSpacing.sm, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FmSpacing.xs),
                    ) {
                        Text(
                            text = candidate.photoLabel,
                            style = FmType.labelSmall,
                            color = FmPalette.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text("沿用", style = FmType.labelSmall, color = FmPalette.primaryStrong)
                    }
                }
            }
        }
    }
}
