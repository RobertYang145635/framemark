package com.framemark.native.shell.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.framemark.native.library.LibraryCardGroup
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType

/**
 * 边框底板里「分组 + 卡片」两种排布的条目账本。
 *
 * 两轴滚动的 收起 卡片条与 3 列 展开 网格已经由整页的 [PhoneBorderPageDialog] 取代
 * （一页一个分类、2 列网格、只有一个滚动容器），这里只留下纯下标计算：它们是
 * 「分组跳转落点」的回归契约（`PhoneLibraryJumpIndexTest` / `PhoneLibrarySwipeTest`
 * 直接测这些函数），跳转按钮和卡片条不应当各算一套。
 */

/** Columns of the old 展开 grid (3 cards per row); kept for the jump-index contract. */
const val PHONE_LIBRARY_COLUMNS = 3

/** Items a group occupies in the strip body: the group tile plus one item per card. */
fun phoneStripItems(group: LibraryCardGroup): Int = 1 + group.count

/** Items a group occupies in the 3-column grid: the header plus one row per 3 cards. */
fun phoneGridItems(group: LibraryCardGroup): Int =
    1 + (group.count + PHONE_LIBRARY_COLUMNS - 1) / PHONE_LIBRARY_COLUMNS

/** What a jump chip scrolls the strip to: the group's tile index. */
fun phoneStripJumpIndex(groups: List<LibraryCardGroup>, groupKey: String?): Int =
    phoneLibraryItemIndex(groups, groupKey, ::phoneStripItems)

/** Same for the expanded grid. */
fun phoneGridJumpIndex(groups: List<LibraryCardGroup>, groupKey: String?): Int =
    phoneLibraryItemIndex(groups, groupKey, ::phoneGridItems)

/** Empty/loading/error note for the border sheet body and the full-height border page. */
@Composable
fun PhoneLibraryNote(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(FmSpacing.md),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = text, style = FmType.bodyMedium, color = FmPalette.onSurfaceMuted)
    }
}
