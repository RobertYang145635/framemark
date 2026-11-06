package com.framemark.native.shell.desktop

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 左栏字号（演示稿 styles.css 287-340 / 895-916 的实测值）。
 *
 * 数值一律 `tabular-nums`；Bahnschrift 字族要经 expect/actual 暴露，属 WP-V1 的
 * `theme` 包，本包不能改 `Tokens.kt`，所以这里只锁字号、行高、字重与 tnum，
 * 字族仍是系统默认（差异已登记在 WP-V2-REPORT 的“缺令牌”一节）。
 */
internal object DesktopLibraryType {
    /** `.utabs button`：14px，未选中 500、选中 600。 */
    val tabLabel = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)

    /** `.utabs button small`：`500 11px var(--num)`。 */
    val tabCount = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )

    /** `.ltabs button`：13px。 */
    val categoryLabel = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)

    /** `.anchors button`：12.5px。 */
    val chipLabel = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)

    /** `.anchors button small` / `.gsec-h small`：`500 11px var(--num)`。 */
    val chipCount = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )

    /** `.gsec-h b`：12.5/600。 */
    val groupTitle = TextStyle(fontSize = 12.5.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.SemiBold)

    /** `.gsec-h .note`：11px。 */
    val groupNote = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Normal)

    /** `.card .nm`：12px，行高 1.35。 */
    val cardName = TextStyle(fontSize = 12.sp, lineHeight = 16.2.sp, fontWeight = FontWeight.Normal)

    /** `.card .ds`：11px。 */
    val cardDesc = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = "tnum",
    )

    /** `.card .bdg`：10.5/600。 */
    val badge = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)

    /** `.search input`：13px。 */
    val searchInput = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
}
