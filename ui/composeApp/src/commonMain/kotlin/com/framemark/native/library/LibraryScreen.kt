package com.framemark.native.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType

/**
 * 边框库的「载入中 / 载入失败」界面。
 *
 * 生效路径只有两条：桌面左栏在 `libraryIndex == null`（即 Loading / Failed）时调用；
 * 手机端用 `PhoneBorderPanel` 的 选择一个边框（库是覆盖层
 * `PhoneBorderLibraryOverlay`），从不调用本函数。数据就绪后桌面换 `DesktopLibraryPanel`。
 *
 * 因此原来的 `Ready` 分支（`LibraryReady` 及其分类 chip / 厂商筛选 / 搜索框 /
 * 卡片网格 / 族索引等约 800 行）**任何一端都不可达**——`LibraryIndex.of` 永远返回
 * 非空，`libraryState is Ready` 时 `libraryIndex != null`，调用方不会进来。该分支已在
 * WP-FIX2 删除；判定与证据见 `reports\WP-FIX2-REPORT.md`。
 *
 * 备注：WP-F1 判「`LibraryScreen` 不是死代码」指的是本文件 Loading / Failed 这两个
 * 仍在使用的分支，与 AU1 判「Ready 分支不可达」并不冲突（报告 §2.2）。
 */
@Composable
fun LibraryScreen(
    loadState: LibraryLoadState,
    onRetry: () -> Unit,
) {
    when (loadState) {
        LibraryLoadState.Loading -> LibraryLoading()
        is LibraryLoadState.Failed -> LibraryFailed(message = loadState.message, onRetry = onRetry)
        // Ready 不可达，保留分支只为让 when 穷尽（sealed class 新增成员时会编译失败提醒）。
        is LibraryLoadState.Ready -> Unit
    }
}

// ---------------------------------------------------------------------------
// Loading / error
// ---------------------------------------------------------------------------

@Composable
private fun LibraryLoading() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(FmSpacing.screen),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("正在载入边框库…", style = FmType.titleSmall, color = FmPalette.onSurface)
        Spacer(Modifier.height(FmSpacing.xs))
        Text(
            "读取 bundle 的 taxonomy.json / previews.json",
            style = FmType.bodyMedium,
            color = FmPalette.onSurfaceMuted,
        )
    }
}

@Composable
private fun LibraryFailed(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(FmSpacing.screen),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("边框库加载失败", style = FmType.titleSmall, color = FmPalette.error)
        Spacer(Modifier.height(FmSpacing.sm))
        Text(
            text = message,
            style = FmType.bodyMedium,
            color = FmPalette.onSurfaceMuted,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(FmSpacing.md))
        Text(
            text = "重试",
            style = FmType.labelMedium,
            color = FmPalette.primaryStrong,
            modifier = Modifier
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.primarySoft)
                .clickable(onClick = onRetry)
                .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.sm),
        )
    }
}
