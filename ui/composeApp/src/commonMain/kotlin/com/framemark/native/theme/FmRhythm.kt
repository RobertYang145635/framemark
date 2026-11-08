package com.framemark.native.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 间距与发线的**唯一来源**（WP-TY1 的节奏规则；本包在右栏/文字页落地）。
 *
 * 四档语义（间距基数 4/8/12/16/24/32，AU4-18 / `10-spec.png` 间距卡）：
 *  - 行内 [inline] 4 / [inlineLoose] 6：同一行里两段文字、chip 与 chip；
 *  - 行间 [row] 8 / [rowLoose] 10：标签 ↔ 值框、一段内容 ↔ 下一段；
 *  - 分组 [groupTail] 12 / [groupGap] 16：同一滚动区里两个分组之间的留白（**上 16 下 12**）；
 *  - 大节 [section] 24。
 *
 * 发线三条规则（AU4 §A2 第 4 条）：
 *  1. **栏级**（左栏/舞台/右栏/胶片条之间，面板内的页头/页签/页脚）= [ColumnRule]，通栏贴边；
 *  2. **分组级** = 只留白，不画线（[groupGap] + [groupTail]）；
 *  3. **组内**（同一分组里语义不同的两段）= [InsetRule]，左右各内缩 [columnEdge]（12 dp）。
 *
 * 新代码不要再写裸 `dp` 做间距；发线一律走这两个 composable，粗细只有 [hairline]。
 */
object FmRhythm {
    /** 行内最小间距。 */
    val inline: Dp = 4.dp

    /** 行内松一档：chip 之间、行内两段之间。 */
    val inlineLoose: Dp = 6.dp

    /** 行间距。 */
    val row: Dp = 8.dp

    /** 行间距松一档：标签 ↔ 值框、一段内容 ↔ 下一段。 */
    val rowLoose: Dp = 10.dp

    /** 分组收尾留白（规则 2 的「下 12」）。 */
    val groupTail: Dp = 12.dp

    /** 分组间留白（规则 2 的「上 16」）。 */
    val groupGap: Dp = 16.dp

    /** 大节留白。 */
    val section: Dp = 24.dp

    /** 发线粗细：整个界面只有这一种。 */
    val hairline: Dp = 1.dp

    /** 组内发线的左右内缩（规则 3）。 */
    val columnEdge: Dp = 12.dp
}

/** 规则 1：栏级发线，通栏贴边。 */
@Composable
fun ColumnRule(modifier: Modifier = Modifier) {
    FmRuleSegment(inset = 0.dp, modifier = modifier)
}

/** 规则 3：组内发线，左右各内缩 [FmRhythm.columnEdge]。 */
@Composable
fun InsetRule(modifier: Modifier = Modifier) {
    FmRuleSegment(inset = FmRhythm.columnEdge, modifier = modifier)
}

@Composable
private fun FmRuleSegment(inset: Dp, modifier: Modifier) {
    // 颜色在组合期读一次（drawBehind/Canvas 里读 FmPalette 是编译错误，见 Tokens.kt）。
    val color = FmPalette.outline
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(FmRhythm.hairline)
            .background(color),
    )
}
