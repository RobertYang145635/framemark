package com.framemark.native.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * 安卓实际实现（PARITY-SPEC §4）：没有 Bahnschrift，退到系统无衬线。
 *
 * 数字仍然等宽——那是 [FmNumeric] 文本样式上的 `tnum` 特性，与字族无关；
 * 所以安卓上"百分比变化时不抖动"这条行为与桌面一致，只有字形不同。
 */
actual fun fmNumericFontFamily(): FontFamily = FontFamily.SansSerif
