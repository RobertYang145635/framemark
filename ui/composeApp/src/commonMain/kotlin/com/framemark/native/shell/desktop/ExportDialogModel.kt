package com.framemark.native.shell.desktop

import com.framemark.native.workflow.ExportNaming
import com.framemark.native.product.ExportFormat
import com.framemark.native.product.ExportSizeMode
import com.framemark.native.workflow.ImageRect
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
    // Kept in step with the document codec (`EXPORT_LONG_EDGE_FLOOR`): a value the
    // UI accepts must survive a save and a reload. These two used to disagree
    // (1 vs 64), so typing 1 produced a project that could not be opened again.
    const val LONG_EDGE_MIN = com.framemark.native.product.EXPORT_LONG_EDGE_FLOOR
    const val LONG_EDGE_MAX = 20000

    /**
     * 界面初值：文档里还没有长边（`ExportSettings.longEdge == null`）时，表单显示这个数字，
     * 与产品文档此前的会话默认值一致。产品语义仍是「未选择」（null），这里只决定输入框内容。
     */
    const val LONG_EDGE_DEFAULT = 1600

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
    fun qualityApplies(format: ExportFormat, webpLossless: Boolean): Boolean = when (format) {
        ExportFormat.Jpeg -> true
        ExportFormat.Png -> false
        ExportFormat.WebP -> !webpLossless
    }

    /** 提交给引擎的质量；不适用时按 100（成片与来源一致的无损档）。 */
    fun effectiveQuality(text: String, format: ExportFormat, webpLossless: Boolean): Int =
        if (qualityApplies(format, webpLossless)) parseQuality(text) ?: QUALITY_DEFAULT else QUALITY_DEFAULT

    /** 当前表单能否提交：只有真实用到的字段才拦。 */
    fun canSubmit(
        sizeMode: ExportSizeMode,
        longEdgeText: String,
        qualityText: String,
        format: ExportFormat,
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
        capturedAt: String? = null,
    ): String = ExportNaming.previewName(
        pattern = pattern,
        cardName = cardName,
        photoName = photoStem,
        index = index,
        capturedAt = capturedAt,
    )

    fun previewFile(
        pattern: String,
        cardName: String,
        photoStem: String?,
        index: Int,
        extension: String,
        capturedAt: String? = null,
    ): String = previewName(pattern, cardName, photoStem, index, capturedAt) + "." + extension

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
    fun encoderLine(format: ExportFormat, quality: Int, webpLossless: Boolean): String = when (format) {
        ExportFormat.Jpeg -> "JPEG 质量 $quality、4:4:4（会重新编码一次）"
        ExportFormat.Png -> "无损编码"
        ExportFormat.WebP -> if (webpLossless) "无损编码" else "WebP 质量 $quality（有损，会重新编码一次）"
    }

    /** 尺寸那一句；长边档不写「原分辨率」。 */
    fun resolutionLine(sizeMode: ExportSizeMode, longEdge: Int): String = when (sizeMode) {
        ExportSizeMode.Original -> "原分辨率，不缩放"
        // D-1：长边档放大与缩小都算（桥接与这里同一个 [OutputSize.applyLongEdge]），
        // 所以文案写「归一」而不是「缩到」，避免小图时界面承诺与成片不符。
        ExportSizeMode.LongEdge -> "长边归一到 $longEdge px 后再编码"
    }

    /** 尺寸 + 编码，一句话摘要（预览列、结果页、批量设置摘要共用）。 */
    fun summaryLine(
        sizeMode: ExportSizeMode,
        longEdge: Int,
        format: ExportFormat,
        quality: Int,
        webpLossless: Boolean,
    ): String = resolutionLine(sizeMode, longEdge) + "；" + encoderLine(format, quality, webpLossless)

    /**
     * 命名里的「拍摄日期 / 拍摄时间」没有 EXIF 可依时的说明。
     *
     * 有拍摄时间就返回 null（界面不多话）；没有时必须说清用的是导出那一刻，
     * 否则用户会以为文件名记的是拍摄日 —— 实际上归档会分错日期。
     */
    fun captureTimeNote(pattern: String, capturedAt: String?): String? {
        // 文件名不用日期/时间时不多话：这一句只在它真的影响文件名时才出现。
        if (!pattern.contains("{date}") && !pattern.contains("{time}")) return null
        if (capturedAt != null && com.framemark.native.product.ExifCaptureDate.normalize(capturedAt) != null) {
            return null
        }
        return "这张照片没有拍摄时间，文件名用导出时间"
    }

    /** 拍摄信息（EXIF/GPS）的去留，按格式给准确说法。 */
    fun metadataLines(format: ExportFormat): List<String> = when (format) {
        ExportFormat.Png -> listOf(GPS_NOTE, PNG_EXIF_NOTE)
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

    /**
     * 画布真值优先级：引擎 `fit.source_canvas` → Shell 的同一份公式结果 → 预览帧测量。
     *
     * 第一档是唯一能覆盖「模板按自己的版式裁照片」的数字：预览画的就是它，导出写的
     * 也是它（原尺寸不再向引擎要 `--fit native`）。后两档只是取不到引擎报告时的兜底
     * ——早先只有测量档，方片预览被算成 1600 × 1600，成片却是 1600 × 1226。
     */
    fun canvasOf(
        engineCanvasPx: Pair<Int, Int>?,
        canonical: ExportPixels?,
        measured: ExportPixels?,
    ): ExportPixels? = engineCanvasPx?.let { ExportPixels(it.first, it.second) } ?: canonical ?: measured

    /** 指定长边档：把画布长边归一到所选值（放大与缩小同式，D-1）。 */
    fun output(canvas: ExportPixels?, sizeMode: ExportSizeMode, longEdge: Int?): ExportPixels? {
        if (canvas == null) return null
        // 公式只有一处：与右栏/顶栏/手机/桥接同一个 [OutputSize.applyLongEdge]。
        val scaled = com.framemark.native.inspector.OutputSize.applyLongEdge(
            com.framemark.native.inspector.OutputPixels(canvas.width, canvas.height),
            sizeMode,
            longEdge,
        ) ?: return null
        return ExportPixels(scaled.width, scaled.height)
    }

    /**
     * 原尺寸档会不会裁照片（AU2 A-19）。
     *
     * 预览帧报告的照片框与照片原图宽高比不一致，就说明这个模板按自己的版式铺满
     * 画幅（`personal-simple` 这类固定比例模板），`--fit native` 会被引擎拒绝
     * （`E4H-FIT-NATIVE-LOCKED … would crop Npx`）并回退到裁切。返回被裁掉的像素
     * 数与方向，供对话框在**导出前**预告，而不是等结果页。
     *
     * 例如 2667 × 2000 的照片铺进 2667 × 1790 的照片框：`约 210 px（上下）`。
     */
    fun cropEstimate(photoWidthPx: Int, photoHeightPx: Int, previewPhotoBox: ImageRect?): CropEstimate? {
        val box = previewPhotoBox ?: return null
        if (photoWidthPx <= 0 || photoHeightPx <= 0) return null
        if (box.w <= 0f || box.h <= 0f || !box.w.isFinite() || !box.h.isFinite()) return null
        val photoAspect = photoWidthPx.toFloat() / photoHeightPx
        val boxAspect = box.w / box.h
        if (photoAspect <= 0f || boxAspect <= 0f) return null
        if (kotlin.math.abs(photoAspect - boxAspect) / photoAspect < 0.005f) return null
        return if (boxAspect > photoAspect) {
            // 框更宽 → 照片上下被裁。
            val keep = photoWidthPx / boxAspect
            CropEstimate(pixels = (photoHeightPx - keep).roundToInt(), vertical = true)
        } else {
            // 框更高（窄）→ 照片左右被裁。
            val keep = photoHeightPx * boxAspect
            CropEstimate(pixels = (photoWidthPx - keep).roundToInt(), vertical = false)
        }
    }

    fun estimatedBytes(
        pixels: ExportPixels?,
        format: ExportFormat,
        quality: Int,
        webpLossless: Boolean,
    ): Long? {
        val pixelCount = pixels?.area ?: return null
        if (pixelCount <= 0L) return null
        val q = quality.coerceIn(ExportRules.QUALITY_MIN, ExportRules.QUALITY_MAX) / 100.0
        val megabytes = when (format) {
            ExportFormat.Png -> pixelCount * PNG_PIXEL_BYTES / 1_000_000.0
            ExportFormat.Jpeg -> pixelCount * (JPEG_BASE + JPEG_SLOPE * q * q * q) / 1_000_000.0
            ExportFormat.WebP ->
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
        format: ExportFormat,
        quality: Int,
        webpLossless: Boolean,
    ): String {
        val size = pixels?.let { "约 $it" } ?: "尺寸在导出后显示"
        val bytes = estimatedBytes(pixels, format, quality, webpLossless)?.let { " · 约 " + formatBytes(it) }
            ?: ""
        return size + bytes
    }
}

/** 原尺寸档下模板会裁掉的像素（[vertical] 为真表示上下裁）。 */
data class CropEstimate(val pixels: Int, val vertical: Boolean) {
    val text: String
        get() = "该模板按版式裁切：照片${if (vertical) "上下" else "左右"}会被裁掉约 $pixels px。"
}
