package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryIndex
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.GhostButton
import com.framemark.native.workflow.PrimaryButton

/**
 * 手机端 边框 工具的**落地面板**：边框页本身不是库。
 *
 * 配色方案 §4.1（2026-11-15）把这里收成一块**当前项**：
 *  1. 小缩略图 + 名称 + 数据自带的类别 / 品牌 / 系列 + 右侧次级「更换」；
 *  2. 还没有边框时：当前项写「未选边框」，下面才是全宽主按钮「选择一个边框」；
 *  3. 库有多少分类 / 多少张卡不再占面板，只在容器的库页头出现。
 *
 * 这样同一屏上「当前边框」的名称只有这一处；画布 chip 只保留手势提示，页头不再
 * 重复名字。库（分类翻页 + 2 列网格 + 搜索）仍只由「更换 / 选择一个边框」打开，
 * 是盖在画布上的覆盖层 [PhoneBorderLibraryOverlay]。
 *
 * **没有滚动容器**：缩略图边长由 [PhoneBorderPageState.panelLayout] 按这块面板的
 * 实际高度挑（72 / 64 / 56 dp），所以当前项和动作永远都在这一屏里。
 */
@Composable
fun PhoneBorderPanel(
    index: LibraryIndex?,
    /** 当前已应用的边框；面板与覆盖层的「当前」都读它。 */
    card: LibraryCard?,
    onRetry: (() -> Unit)?,
    /** 打开边框库覆盖层（[PhoneBorderLibraryOverlay] 由外壳画在整个编辑器之上）。 */
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (index == null) {
        LibraryUnavailable(onRetry = onRetry, modifier = modifier)
        return
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 这块面板拿到的就是「底板高度 − 把手行 − 标题行」，所以预算直接对着它算。
        val layout = PhoneBorderPageState.panelLayout(
            availableHeightDp = maxHeight.value,
            hasCard = card != null,
            fontScale = LocalDensity.current.fontScale,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.sm),
            // 底板高度按「还没选边框」的 144 dp 固定（选了边框后也不跳），而已选边框
            // 那一行只有 88 dp：内容居中，剩下的 56 dp 就均分成上下留白，不会像
            // 顶部对齐时那样在「更换」下面挂一条空带。
            verticalArrangement = Arrangement.Center,
        ) {
            CurrentBorderRow(
                card = card,
                tileDp = layout.tileDp.dp,
                onOpenLibrary = onOpenLibrary,
            )
            if (card == null) {
                Spacer(Modifier.height(FmSpacing.sm))
                PrimaryButton(
                    text = PhoneBorderPageState.actionLabel(null),
                    onClick = onOpenLibrary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 面板里的当前边框：小方图 + 名称 + 类别/系列 + 右侧「更换」（只有已选时才有）。 */
@Composable
private fun CurrentBorderRow(
    card: LibraryCard?,
    tileDp: Dp,
    onOpenLibrary: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(tileDp)
                .clip(RoundedCornerShape(FmRadii.tile))
                .background(FmPalette.stage)
                .padding(FmSpacing.xs),
            contentAlignment = Alignment.Center,
        ) {
            if (card != null) {
                PhoneFramePreview(card = card, modifier = Modifier.fillMaxSize())
            } else {
                Text("—", style = FmType.titleMedium, color = FmPalette.stageMuted)
            }
        }
        Spacer(Modifier.width(FmSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = PhoneBorderPageState.currentName(card),
                style = FmType.titleSmall,
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(FmSpacing.xxs))
            Text(
                text = PhoneBorderPageState.currentDescription(
                    card = card,
                    description = card?.let { phoneCardDescription(it) },
                ),
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (card != null) {
            Spacer(Modifier.width(FmSpacing.sm))
            GhostButton(
                text = PhoneBorderPageState.actionLabel(card),
                onClick = onOpenLibrary,
                minHeight = PhoneBorderPageState.PANEL_CHANGE_ACTION_HEIGHT_DP.dp,
            )
        }
    }
}

@Composable
private fun LibraryUnavailable(onRetry: (() -> Unit)?, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(FmSpacing.md),
        verticalArrangement = Arrangement.Center,
    ) {
        PhoneLibraryNote(if (onRetry == null) "正在载入边框库…" else "暂时无法载入边框库。", Modifier.fillMaxWidth())
        if (onRetry != null) {
            Spacer(Modifier.height(FmSpacing.sm))
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.primarySoft)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = FmSpacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "重试",
                    style = FmType.labelMedium,
                    color = FmPalette.primaryStrong,
                )
            }
        }
    }
}
