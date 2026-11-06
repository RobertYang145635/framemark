package com.framemark.native.shell.desktop

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmNumeric

/**
 * 左栏字号（演示稿 styles.css 287-340 / 895-916 的实测值）。
 *
 * WP-V4 B：数值文本用 `--num` 字族（`FmNumeric.value.fontFamily`，桌面是
 * Bahnschrift）+ `tnum`。字号、行高、字重仍是这里锁定的演示稿值，不改层级。
 */
internal object DesktopLibraryType {
    /** `.utabs button`：14px，未选中 500、选中 600。 */
    val tabLabel = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)

    /** `.utabs button small`：`500 11px var(--num)`。 */
    val tabCount = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FmNumeric.value.fontFamily,
        fontFeatureSettings = FmNumeric.NUMERIC_FEATURES,
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
        fontFamily = FmNumeric.value.fontFamily,
        fontFeatureSettings = FmNumeric.NUMERIC_FEATURES,
    )

    /** `.gsec-h b`：12.5/600。 */
    val groupTitle = TextStyle(fontSize = 12.5.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.SemiBold)

    /** `.gsec-h .note`：11px。 */
    val groupNote = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Normal)

    /** `.card .nm`：12px，行高 1.35。 */
    val cardName = TextStyle(fontSize = 12.sp, lineHeight = 16.2.sp, fontWeight = FontWeight.Normal)

    /** `.card .ds`：11px；副行里的「N 张照片在用」是数字，走 `--num`（中文回退不变）。 */
    val cardDesc = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FmNumeric.value.fontFamily,
        fontFeatureSettings = FmNumeric.NUMERIC_FEATURES,
    )

    /** `.card .bdg`：10.5/600。 */
    val badge = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)

    /** `.search input`：13px。 */
    val searchInput = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
}
