package com.framemark.native.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle

/**
 * 桌面实际实现（PARITY-SPEC §4）：用 Skia 的系统字体管理器取 Bahnschrift。
 *
 * 演示稿 `--num` 的第一顺位就是 Bahnschrift —— Windows 10/11 自带的 DIN 风格窄体，
 * 尺寸、编号、缩放值那种"机械感"来自它。取不到（精简过的 Windows、macOS、Linux）
 * 就退回 [FontFamily.SansSerif]，界面照常可用，只是数字变成普通无衬线。
 */
actual fun fmNumericFontFamily(): FontFamily = DesktopNumericFamily

private val DesktopNumericFamily: FontFamily by lazy {
    val skia = runCatching {
        FontMgr.default.matchFamilyStyle(NUMERIC_FAMILY_NAME, FontStyle.NORMAL)
    }.getOrNull()
    if (skia != null) {
        FontFamily(Typeface(skia, NUMERIC_FAMILY_NAME))
    } else {
        FontFamily.SansSerif
    }
}

/** `--num` 的第一顺位（styles.css 5 行）。 */
private const val NUMERIC_FAMILY_NAME = "Bahnschrift"
