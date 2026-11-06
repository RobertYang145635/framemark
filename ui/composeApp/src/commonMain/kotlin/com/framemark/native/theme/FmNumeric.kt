package com.framemark.native.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 数值字体族（PARITY-SPEC §1 / §4）。
 *
 * 演示稿把**所有数字**（尺寸、百分比、编号、时间、缩放值）排成 `--num`：
 * `Bahnschrift → DIN Alternate → HarmonyOS Sans SC → Segoe UI`，并且是
 * `font-variant-numeric: tabular-nums` + `font-feature-settings: "tnum"`。
 * 拉丁字面的 Bahnschrift 是 Windows 自带的 DIN 风格窄体，演示稿里 "2667 × 2000"、
 * "10.5px" 编号、顶栏 `Ctrl E` 那种感觉就是它给的。
 *
 * 桌面用 Skia 的系统字体管理器按名字取 Bahnschrift；安卓没有这个字族，退到系统
 * 无衬线 + `tnum`（宽度对齐仍然成立，只是字形不同）。取不到时一律回退，不抛异常：
 * 字体是装饰，不能成为启动失败的原因。
 */
expect fun fmNumericFontFamily(): FontFamily

/**
 * 数值文本样式（规格页 `--num` 的三档 + 列表用的 12.5）。
 *
 * 只有真正"读数字"的地方用这里：舞台缩放值、导出尺寸、照片原尺寸、胶片条编号、
 * 右栏计数。中文正文仍走 [FmType]（`--font`）。
 */
object FmNumeric {
    /** 12 sp：舞台缩放值、原尺寸 / 导出尺寸、顶栏快捷键提示。 */
    val value: TextStyle get() = Value

    /** 11 sp：分组计数、分类计数这类跟在标签后面的小数字。 */
    val caption: TextStyle get() = Caption

    /** 10.5 sp：胶片条编号（`styles.css` 424 行 `500 10.5px/1 var(--num)`）。 */
    val frameNumber: TextStyle get() = FrameNumber

    /** 12.5 sp：chip / 段控这类控件里的短数字。 */
    val control: TextStyle get() = Control

    private val family: FontFamily by lazy { fmNumericFontFamily() }

    private fun numeric(size: Float, weight: FontWeight) = TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        fontWeight = weight,
        // 1.3 是演示稿 `--num` 出现在行内时的实际行高（`--num` 元素各自给 line-height，
        // 数字文本都是单行，1.3 让上下留白与 `line-height: normal` 视觉一致。
        lineHeight = (size * 1.3f).sp,
        // tabular-nums：数字等宽，缩放百分比变化时不会左右跳动。
        fontFeatureSettings = NUMERIC_FEATURES,
    )

    private val Value = numeric(12f, FontWeight.Normal)
    private val Caption = numeric(11f, FontWeight.Medium)
    private val FrameNumber = numeric(10.5f, FontWeight.Medium)
    private val Control = numeric(12.5f, FontWeight.Medium)

    /** `font-feature-settings: "tnum"`（styles.css 31 行 `.num`）。 */
    const val NUMERIC_FEATURES = "tnum"
}
