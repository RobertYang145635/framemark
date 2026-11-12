package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryIndex
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.PrimaryButton

/**
 * 手机端 边框 工具在底板里的内容：**不再是**两轴滚动的卡片条。
 *
 * 挑边框发生在整页的 [PhoneBorderPageDialog]（进入 边框 工具即自动打开），所以这里
 * 只剩「当前边框 + 再进一次」的入口：一屏放得下，没有任何滚动容器，也就没有
 * 「竖着滚的容器套着横着滚的列表」。
 *
 * 关掉整页后回到的正是这一屏——底板档位（收起 / 半开 / 展开）与当前工具都没有被
 * 改过：整页只是盖在上面的一层，打开和关闭都不写设计、不移动底板，和品牌选择器
 * 关闭后回到原来的页面是同一套做法。
 */
@Composable
fun PhoneLibrarySheet(
    index: LibraryIndex?,
    /** 当前已应用的边框；整页里的初始选中就是它。 */
    card: LibraryCard?,
    onRetry: (() -> Unit)?,
    /** 打开整页挑边框（[PhoneBorderPageDialog] 由调用方挂在工具切换的那一层）。 */
    onOpenPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (index == null) {
        LibraryUnavailable(onRetry = onRetry, modifier = modifier)
        return
    }
    val categoryCount = remember(index) { index.categories().size }
    Column(
        modifier = modifier.fillMaxSize().padding(FmSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(FmSpacing.md),
    ) {
        CurrentBorderRow(card = card)
        PrimaryButton(
            text = if (card == null) "打开边框库挑一个" else "换一个边框",
            onClick = onOpenPage,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "整页挑边框：$categoryCount 个分类左右滑动，共 ${index.cards.size} 个边框。",
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
            maxLines = 2,
        )
    }
}

/** 底板里的当前边框：方图 + 名称 + 类别/系列（数据自带，不猜）。 */
@Composable
private fun CurrentBorderRow(card: LibraryCard?) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(96.dp)
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
                text = card?.name ?: "还没有选边框",
                style = FmType.titleSmall,
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(FmSpacing.xxs))
            Text(
                text = card?.let { phoneCardDescription(it) }.takeUnless { it.isNullOrBlank() }
                    ?: "选一张边框后，样式 / 文字 / Logo / 水印才会出现。",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                maxLines = 2,
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
