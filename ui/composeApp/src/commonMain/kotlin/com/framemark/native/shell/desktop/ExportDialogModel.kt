package com.framemark.native.shell.desktop

import com.framemark.native.workflow.ExportNaming
import com.framemark.native.workflow.ExportSizeMode
import com.framemark.native.workflow.ImageRect
import com.framemark.native.workflow.SampleFormat
import kotlin.math.roundToInt

/**
 * WP-D2 导出对话框的纯逻辑：文件名变量、参数校验、文案和尺寸/体积估算。
 *
 * 这里不碰引擎、不碰导出管线：引擎调用仍是 `NativeEngine.export(...)`，本文只决定
 * “界面上显示什么、允许提交什么”。所以它能被 commonTest 直接覆盖，也不需要平台。
 */

/** 文件名变量 chips（UI 报告 §6：照片名、序号、边框名、拍摄日期、拍摄时间）。 */
enum class ExportNameToken(val key: String, val label: String) {
    Photo("photo", "照片名"),
    Index("index", "序号"),
    Name("name", "边框名"),
    Date("date", "拍摄日期"),
    Time("time", "拍摄时间"),
    ;

    val placeholder: String get() = "{$key}"

    companion object {
        fun fromKey(key: String): ExportNameToken? = entries.firstOrNull { it.key == key }
    }
}

/** 导出画布的像素尺寸。 */
data class ExportPixels(val width: Int, val height: Int) {
    val longEdge: Int get() = maxOf(width, height)
    val area: Long get() = width.toLong() * height.toLong()

    override fun toString(): String = "$width × $height px"
}

/**
 * 参数范围（HANDOFF §4 WP-D2 那一行、UI 报告 §6）：质量 60–100 默认 100，
 * 长边 1–20000。解析失败返回 null，界面据此禁用「导出」并给出原因。
 */
object ExportRules {
    const val QUALITY_MIN = 60
    const val QUALITY_MAX = 100
    const val QUALITY_DEFAULT = 100
    const val LONG_EDGE_MIN = 1
    const val LONG_EDGE_MAX = 20000

    /** 常用长边（演示稿 `desktop.js` 的三个 chip 同款）。 */
    val LONG_EDGE_PRESETS = listOf(2048, 3000, 4096)

    fun parseQuality(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in QUALITY_MIN..QUALITY_MAX }

    fun parseLongEdge(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in LONG_EDGE_MIN..LONG_EDGE_MAX }

    fun qualityError(text: String): String? =
        if (parseQuality(text) == null) "质量应为 $QUALITY_MIN–$QUALITY_MAX 的整数。" else null

    fun longEdgeError(text: String): String? =
        if (parseLongEdge(text) == null) "长边应为 $LONG_EDGE_MIN–$LONG_EDGE_MAX 的整数像素。" else null

    /** 质量只对真正用它的编码器起作用：JPEG 与有损 WebP。 */
    fun qualityApplies(format: SampleFormat, webpLossless: Boolean): Boolean = when (format) {
        SampleFormat.Jpeg -> true
        SampleFormat.Png -> false
        SampleFormat.WebP -> !webpLossless
    }

    /** 提交给引擎的质量；不适用时按 100（成片与来源一致的无损档）。 */
    fun effectiveQuality(text: String, format: SampleFormat, webpLossless: Boolean): Int =
        if (qualityApplies(format, webpLossless)) parseQuality(text) ?: QUALITY_DEFAULT else QUALITY_DEFAULT

    /** 当前表单能否提交：只有真实用到的字段才拦。 */
    fun canSubmit(
        sizeMode: ExportSizeMode,
        longEdgeText: String,
        qualityText: String,
        format: SampleFormat,
        webpLossless: Boolean,
    ): Boolean {
        if (sizeMode == ExportSizeMode.LongEdge && parseLongEdge(longEdgeText) == null) return false
        if (qualityApplies(format, webpLossless) && parseQuality(qualityText) == null) return false
        return true
    }
}

/** 文件名实时预览；非法字符与空模式的最终结果都由 [ExportNaming] 决定。 */
object ExportFileName {
    fun previewName(
        pattern: String,
        cardName: String,
        photoStem: String?,
        index: Int,
    ): String = ExportNaming.previewName(pattern = pattern, cardName = cardName, photoName = photoStem, index = index)

    fun previewFile(
        pattern: String,
        cardName: String,
        photoStem: String?,
        index: Int,
        extension: String,
    ): String = previewName(pattern, cardName, photoStem, index) + "." + extension

    /** 插入一个变量到光标处（UI 的 chips 用）；未知 key 原样返回。 */
    fun insertToken(pattern: String, tokenKey: String, selectionStart: Int, selectionEnd: Int): String {
        val token = ExportNameToken.fromKey(tokenKey) ?: return pattern
        val start = selectionStart.coerceIn(0, pattern.length)
        val end = selectionEnd.coerceIn(start, pattern.length)
        return pattern.substring(0, start) + token.placeholder + pattern.substring(end)
    }
}

/**
 * 导出文案：照 P2-2 的写法“按格式写准确”，不把「不重新压缩」挂在无损档上，
 * 缩小尺寸时不提「原分辨率」（UX-RESEARCH A-01 / N-02：导出画质是这个品类的
 * 第 2 名痛点，文案不能夸大）。
 *
 * 现状来自 WP-P2 报告 §1.6（查的是代码，不是文档猜测）：桌面 JPEG / WebP 保留
 * EXIF 但移除 GPS；桌面 PNG 由 ImageIO 直接编码，不写 EXIF。
 */
object ExportCopy {
    const val GPS_NOTE = "导出会去掉 GPS 定位信息。"
    const val PNG_EXIF_NOTE = "PNG 不含拍摄信息（EXIF）。"

    /** 编码那一句。 */
    fun encoderLine(format: SampleFormat, quality: Int, webpLossless: Boolean): String = when (format) {
        SampleFormat.Jpeg -> "JPEG 质量 $quality、4:4:4（会重新编码一次）"
        SampleFormat.Png -> "无损编码"
        SampleFormat.WebP -> if (webpLossless) "无损编码" else "WebP 质量 $quality（有损，会重新编码一次）"
    }

    /** 尺寸那一句；长边档不写「原分辨率」。 */
    fun resolutionLine(sizeMode: ExportSizeMode, longEdge: Int): String = when (sizeMode) {
        ExportSizeMode.Original -> "原分辨率，不缩放"
        ExportSizeMode.LongEdge -> "长边缩到 $longEdge px 后再编码"
    }

    /** 尺寸 + 编码，一句话摘要（预览列、结果页、批量设置摘要共用）。 */
    fun summaryLine(
        sizeMode: ExportSizeMode,
        longEdge: Int,
        format: SampleFormat,
        quality: Int,
        webpLossless: Boolean,
    ): String = resolutionLine(sizeMode, longEdge) + "；" + encoderLine(format, quality, webpLossless)

    /** 拍摄信息（EXIF/GPS）的去留，按格式给准确说法。 */
    fun metadataLines(format: SampleFormat): List<String> = when (format) {
        SampleFormat.Png -> listOf(GPS_NOTE, PNG_EXIF_NOTE)
        else -> listOf(GPS_NOTE)
    }
}

/**
 * 尺寸与体积估算。
 *
 * 引擎只在**导出后**才给成片像素（`ExportResult.width/height`），所以表单一栏写明
 * “约”。原尺寸档的画布值由预览帧反推：`NativeStagePreview` 回调里带引擎报告的
 * 照片框（`ImageRect`，单位是这张预览图的像素），照片原始像素 ÷ 照片框占比 =
 * 成片画布像素。模板按版式比例裁切时这里会有 1 % 量级误差，所以一律加“约”。
 */
object ExportEstimate {
    /** 体积口径沿用 V2 演示稿 `desktop.js` 的经验系数，只用于“约 X MB”。 */
    private const val JPEG_BASE = 0.18
    private const val JPEG_SLOPE = 0.9
    private const val WEBP_LOSSY_BASE = 0.12
    private const val WEBP_LOSSY_SLOPE = 0.5
    private const val WEBP_LOSSLESS_PIXEL_BYTES = 1.2
    private const val PNG_PIXEL_BYTES = 2.6

    /** 原尺寸档的成片画布；预览没报告照片框时返回 null（界面显示“导出后显示”）。 */
    fun canvas(
        photoWidthPx: Int,
        photoHeightPx: Int,
        previewPhotoBox: ImageRect?,
        previewWidthPx: Int,
        previewHeightPx: Int,
    ): ExportPixels? {
        val box = previewPhotoBox ?: return null
        if (photoWidthPx <= 0 || photoHeightPx <= 0) return null
        if (previewWidthPx <= 0 || previewHeightPx <= 0) return null
        if (box.w <= 0f || box.h <= 0f || !box.w.isFinite() || !box.h.isFinite()) return null
        val fractionW = box.w / previewWidthPx
        val fractionH = box.h / previewHeightPx
        if (fractionW <= 0.01f || fractionH <= 0.01f || fractionW > 1.01f || fractionH > 1.01f) return null
        return ExportPixels(
            width = (photoWidthPx / fractionW).roundToInt().coerceAtLeast(1),
            height = (photoHeightPx / fractionH).roundToInt().coerceAtLeast(1),
        )
    }

    /** 指定长边档：只有成片长边大于所选值时才缩（与引擎 `scale` 分支一致）。 */
    fun output(canvas: ExportPixels?, sizeMode: ExportSizeMode, longEdge: Int): ExportPixels? {
        if (canvas == null) return null
        if (sizeMode == ExportSizeMode.Original) return canvas
        if (longEdge <= 0) return null
        val scale = if (longEdge < canvas.longEdge) longEdge.toDouble() / canvas.longEdge else 1.0
        return ExportPixels(
            width = (canvas.width * scale).roundToInt().coerceAtLeast(1),
            height = (canvas.height * scale).roundToInt().coerceAtLeast(1),
        )
    }

    fun estimatedBytes(
        pixels: ExportPixels?,
        format: SampleFormat,
        quality: Int,
        webpLossless: Boolean,
    ): Long? {
        val pixelCount = pixels?.area ?: return null
        if (pixelCount <= 0L) return null
        val q = quality.coerceIn(ExportRules.QUALITY_MIN, ExportRules.QUALITY_MAX) / 100.0
        val megabytes = when (format) {
            SampleFormat.Png -> pixelCount * PNG_PIXEL_BYTES / 1_000_000.0
            SampleFormat.Jpeg -> pixelCount * (JPEG_BASE + JPEG_SLOPE * q * q * q) / 1_000_000.0
            SampleFormat.WebP ->
                if (webpLossless) {
                    pixelCount * WEBP_LOSSLESS_PIXEL_BYTES / 1_000_000.0
                } else {
                    pixelCount * (WEBP_LOSSY_BASE + WEBP_LOSSY_SLOPE * q * q * q) / 1_000_000.0
                }
        }
        return (megabytes * 1_000_000.0).toLong()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val kb = bytes / 1024.0
        return if (kb >= 1024) {
            val mb = (kb / 1024.0 * 10).roundToInt() / 10.0
            "$mb MB"
        } else {
            val rounded = (kb * 10).roundToInt() / 10.0
            "$rounded KB"
        }
    }

    /** 预览列的那一行：“约 4000 × 3000 px · 约 3.2 MB”。 */
    fun previewLine(
        pixels: ExportPixels?,
        format: SampleFormat,
        quality: Int,
        webpLossless: Boolean,
    ): String {
        val size = pixels?.let { "约 $it" } ?: "尺寸在导出后显示"
        val bytes = estimatedBytes(pixels, format, quality, webpLossless)?.let { " · 约 " + formatBytes(it) }
            ?: ""
        return size + bytes
    }
}
