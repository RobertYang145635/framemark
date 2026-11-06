package com.framemark.native.inspector

import androidx.compose.ui.text.TextStyle
import com.framemark.native.theme.FmNumeric

/**
 * WP-V4 B：只把数字字族与 `tnum` 换成 `--num` 那一套，字号 / 行高 / 字重保持调用方
 * 原样。演示稿里尺寸、百分比、计数、编号全部是 Bahnschrift + tabular-nums
 * （PARITY-SPEC §1），但同一个 `Text` 里可能混着中文（「已修改 N 项」），所以不能
 * 直接换成 12/11 这些令牌尺寸，只能在原样式上补字族与特性。
 */
internal fun TextStyle.withNumericDigits(): TextStyle = copy(
    fontFamily = FmNumeric.value.fontFamily,
    fontFeatureSettings = FmNumeric.NUMERIC_FEATURES,
)
