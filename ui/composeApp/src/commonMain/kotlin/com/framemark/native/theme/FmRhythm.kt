package com.framemark.native.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 界面节奏（WP-TY1）——把「有的地方密、有的地方松」收成一套可测量的等级。
 *
 * 用户原话：「排版问题，有的地方很密，有的地方又太稀疏了。」不是整体放大或缩小，
 * 而是**该紧的紧、该松的松**并且成体系。所以这里不引入新的像素，只把演示稿
 * （`styles.css` / `10-spec.png`）里已经在用的间距归位到四个语义档位：
 *
 * | 档位 | 用途 | 取值 | 演示稿出处 |
 * |---|---|---|---|
 * | 行内 | 图标↔文字、数字↔单位、chip 文字↔计数 | 2 / 4 / 6 | `.status{gap:7}` `.bitem .cb→.th` 10、`.card{gap:6}`、`.card .ds{margin-top:-4}` |
 * | 行 | 同一分组里的相邻行、卡片缩略图↔名称 | 8 / 10 | `.qlist` 行 9+9、`.frames{gap:10}`、`.rail-filters{gap:10}` |
 * | 分组 | 分组标题↔内容、页头↔页签 | 12 / 14 / 16 | `.ihead{14 14 10 18}`、`.dlg-f{padding:14 22}`、`.igrp{top:14}` |
 * | 大节 | 滚动区首尾、三栏内的大块留白 | 24 / 32 | `.sheetgrid{padding:24}`、`.empty-pane{padding:36 24}` |
 *
 * **分隔线三条规则**（AU4 §A2 第 4 条：右栏用 1 px 线分段、左栏用底色块分段，视觉相近
 * 语义不同 → 本轮统一成一套）：
 *
 * 1. **栏级**（左栏 / 舞台 / 右栏 / 胶片条之间，以及页头 / 页签 / 页脚与内容之间）
 *    = 1 dp 通栏发线，左右**不内缩**（[columnRule]）。整个界面只有这一种线允许贴边。
 * 2. **分组级**（同一滚动区里的两个分组）用**留白**分开，**不画线**：上 16 / 下 12，
 *    由分组自己承担（[groupGap] / [group]）。
 * 3. **组内分隔**（同一分组里语义不同的两段，例如「导出设置」摘要与「导出队列」）
 *    = 1 dp 发线，左右各内缩 12 dp，**不贴边**（[insetRule]）。
 *
 * 判据（报告里逐条核对）：`grep` 一遍 `height(1.dp).background(FmPalette.outline)`，
 * 每一个都必须能归到规则 1 或规则 3，归不进去的就是漏改。
 */
object FmRhythm {
    // ---- 行内 ---------------------------------------------------------------
    /** 紧挨的两个字形（`数字`+`单位`、`+` 与文字）。 */
    val inline = 2.dp

    /** 两个可点元素之间的最小间隙（chip↔chip、工具条按钮）。 */
    val inlineWide = 4.dp

    /** 图标↔文字、卡片缩略图↔名称、页码与状态点。 */
    val inlineLoose = 6.dp

    // ---- 行 ----------------------------------------------------------------
    /** 同一分组里的相邻行。 */
    val row = 8.dp

    /** 跨小节但仍属同一组的行（列表行↔说明、搜索框↔类别行）。 */
    val rowLoose = 10.dp

    // ---- 分组 --------------------------------------------------------------
    /** 分组标题↔分组内容。 */
    val group = 12.dp

    /** 分组与分组之间（用留白，不画线）。 */
    val groupGap = 16.dp

    // ---- 大节 --------------------------------------------------------------
    /** 滚动区首尾留白、三栏内容与栏边之间的距离。 */
    val section = 24.dp

    // ---- 线 ----------------------------------------------------------------
    /** 发线粗细。**不是间距档位**：2 dp 不再出现在任何 `FmSpacing` 里（AU4-18）。 */
    val hairline = 1.dp

    /** 组内分隔线左右内缩（规则 3）；栏级线不内缩（规则 1）。 */
    val columnEdge = 12.dp

    // ---- 批量页联系表（AU4 §A2：只有 1 张时右侧约 500 px 全空）-------------
    /** 联系表少于这么多张时，改走紧凑行列表（1 张排不满一行网格，2 张也不成表）。 */
    const val CONTACT_SHEET_COMPACT_BELOW = 3

    /** 紧凑列表的内容宽度上限：居中后左右留白对称，右栏不再被推远。 */
    val compactListMaxWidth = 640.dp

    /** 紧凑列表行高清高（缩略图 72 × 54 + 行内 12 × 2）。 */
    val compactRowThumbWidth = 72.dp
    val compactRowThumbHeight = 54.dp
}

/**
 * 规则 1：栏级发线，通栏、贴边。只用在"两块不同的区域之间"。
 *
 * 这与 `Box(Modifier.fillMaxWidth().height(1.dp).background(FmPalette.outline))`
 * 完全等价，写成扩展函数是为了让每一处线都能被 `grep` 出语义
 * （`grep -rn 'columnRule\|insetRule'` 得到的就是全套分隔线清单）。
 */
@Composable
fun Modifier.columnRule(): Modifier =
    fillMaxWidth().height(FmRhythm.hairline).background(FmPalette.outline)

/**
 * 规则 3：组内发线，左右各内缩 [inset]（默认 [FmRhythm.columnEdge]）。
 *
 * 不需要分隔线的地方一律用留白（规则 2），所以调用这个函数的每一处都应当在报告
 * 里能指出"它分开的是同一组里语义不同的两段"。
 */
@Composable
fun Modifier.insetRule(inset: Dp = FmRhythm.columnEdge): Modifier =
    fillMaxWidth()
        .padding(horizontal = inset)
        .height(FmRhythm.hairline)
        .background(FmPalette.outline)
