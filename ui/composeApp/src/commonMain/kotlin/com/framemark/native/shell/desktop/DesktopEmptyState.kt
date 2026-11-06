package com.framemark.native.shell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.framemark.native.theme.FmElevation
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmType
import com.framemark.native.theme.fmElevated

/** 空状态里可沿用的其他照片设计。 */
data class OtherPhotoDesign(
    val photoId: String,
    val photoLabel: String,
)

/**
 * 舞台空状态（`styles.css` 413-415 `.empty-print`；演示稿 `desktop.js:102 emptyOverlay`）。
 *
 * 演示稿把这张卡片盖在照片的成品区上：`rgba(12,13,15,.35)` 压暗 + 白色 95 % 圆角卡，
 * 卡内 14/16 内边距、10 间距、居中、`sh-3` 投影；标题 14/700，说明 13，两个按钮
 * 主线「沿用 X 的设计」+「浏览边框库」。
 *
 * 点击「沿用」会调 `ProjectHolder.applyDesignFromPhoto`：把那张照片的
 * `cardId/styleId/params/text` 复制到当前照片。数据模型与项目格式不变；不支持的
 * 平台返回 -1，界面如实说明。
 */
@Composable
fun DesktopStageEmptyState(
    candidates: List<OtherPhotoDesign>,
    onReuse: (String) -> Unit,
    onPickBorder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(FmRadii.tile)
    Box(
        modifier = modifier
            // `.empty-print { background: rgba(12,13,15,.35) }` —— 遮罩令牌本身就是
            // 32 % 的深色压暗，正是演示稿那一层。
            .background(FmPalette.scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .clip(cardShape)
                .fmElevated(FmElevation.card, cardShape)
                .background(FmPalette.surface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "这张照片还没有选边框",
                // `.empty-print b { font-size: 14px }` —— 14 / 700。
                style = FmType.desktopTab.copy(fontWeight = FontWeight.Bold),
                color = FmPalette.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "每张照片单独保存边框和参数。从左侧边框库选一个，或沿用其他照片的设计。",
                // `.empty-print > div { font-size: 13px }`。
                style = FmType.bodyMedium,
                color = FmPalette.onSurfaceMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            val reusable = candidates.firstOrNull()
            if (reusable != null) {
                DeskButton(
                    label = "沿用 ${reusable.photoLabel} 的设计",
                    style = DeskButtonStyle.Primary,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onReuse(reusable.photoId) },
                )
                Spacer(Modifier.height(8.dp))
            }
            DeskButton(
                label = "浏览边框库",
                style = DeskButtonStyle.Line,
                onClick = onPickBorder,
            )
        }
    }
}
