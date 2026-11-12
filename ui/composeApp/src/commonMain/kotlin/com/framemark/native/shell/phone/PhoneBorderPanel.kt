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
import androidx.compose.runtime.remember
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
import com.framemark.native.workflow.PrimaryButton

/**
 * 手机端 边框 工具的**落地面板**：边框页本身不是库。
 *
 * 和 Logo 页同一套做法（`CuratedLogoPane` 的落地块）：一屏三件事——
 *  1. 当前边框：缩略图（当前照片套这张边框，量不出尺寸时退回落包里那张样张，
 *     见 [PhoneFramePreview]）+ 名称 + 一句数据自带的类别 / 品牌 / 系列；
 *  2. 一个主按钮：`选择一个边框` / `换一个边框`（[PhoneBorderPageState.actionLabel]）；
 *  3. 一行说明：库有多大（分类数 / 张数来自目录，不写死）。
 *
 * 库（分类翻页 + 2 列网格 + 搜索）只在用户点这个按钮时出现，而且是盖在画布上的
 * 覆盖层 [PhoneBorderLibraryOverlay]，不是把编辑器换掉的另一页。
 *
 * **没有滚动容器**：缩略图边长由 [PhoneBorderPageState.panelLayout] 按这块面板的实际
 * 高度在这个阶梯里挑（128 / 112 / 96 / 84 / 72 / 60 / 48 dp，从库里那张卡自己的
 * 128 dp 起步），说明行在窗口最矮时让位。所以缩略图、按钮、说明永远都在这一屏里，
 * 用户不需要滚一下才够得到按钮。
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
    val categoryCount = remember(index) { index.categories().size }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 这块面板拿到的就是「底板高度 − 把手行 − 标题行」，所以预算直接对着它算。
        val layout = PhoneBorderPageState.panelLayout(
            availableHeightDp = maxHeight.value,
            fontScale = LocalDensity.current.fontScale,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.sm),
        ) {
            CurrentBorderRow(card = card, tileDp = layout.tileDp.dp)
            Spacer(Modifier.height(FmSpacing.sm))
            PrimaryButton(
                text = PhoneBorderPageState.actionLabel(card),
                onClick = onOpenLibrary,
                modifier = Modifier.fillMaxWidth(),
            )
            if (layout.showsNote) {
                Spacer(Modifier.height(FmSpacing.xs))
                Text(
                    text = PhoneBorderPageState.libraryNote(categoryCount, index.cards.size),
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 面板里的当前边框：方图 + 名称 + 类别/系列（数据自带，不猜）。 */
@Composable
private fun CurrentBorderRow(card: LibraryCard?, tileDp: Dp) {
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
            Text(
                text = "重试",
                style = FmType.labelMedium,
                color = FmPalette.primaryStrong,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.primarySoft)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = FmSpacing.md, vertical = 10.dp),
            )
        }
    }
}
