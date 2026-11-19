package com.framemark.native.shell.phone

import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.OutputPixels
import com.framemark.native.inspector.OutputSize
import com.framemark.native.product.ExportFormat
import com.framemark.native.product.ExportSettings
import com.framemark.native.product.ExportSizeMode
import kotlin.math.abs

/**
 * The phone export options of report §7.6: 格式, 尺寸 with the three fixed steps
 * (原尺寸 / 长边 2048 / 长边 4096) plus — when a project carries a long edge the
 * three steps cannot express — one dynamic "自定义 N px" chip, and a quality that
 * is **fixed at 100**: the phone sheet has no quality control at all, unlike the
 * desktop dialog.
 *
 * Everything here is pure, so the size mapping, the fixed quality, the Ultra HDR
 * gate and the format mapping are unit-testable without a composition (WP-P2 §4.2).
 */
enum class PhoneExportSize(val label: String, val longEdge: Int?) {
    Original("原尺寸", null),
    Long2048("长边 2048", 2048),
    Long4096("长边 4096", 4096),
}

/**
 * One chip of the 尺寸 row. [id] is what the segmented control reports back and
 * [isCustom] marks the project's own long edge — a value the three fixed steps
 * cannot represent, so it gets its own chip instead of silently borrowing a step.
 */
data class PhoneExportSizeChoice(val id: String, val label: String, val longEdge: Int?) {
    val isCustom: Boolean get() = id == PhoneExportOptions.CUSTOM_SIZE_ID
}

object PhoneExportOptions {
    /** Hand-off WP-P2: 质量固定 100. The submit path forces this, not just the copy. */
    const val QUALITY: Int = 100

    /** 尺寸三档, in the demo's order. */
    val SIZES: List<PhoneExportSize> = PhoneExportSize.entries

    /** 格式: the three the native encoder can write on Android. */
    val FORMATS: List<ExportFormat> = listOf(ExportFormat.Jpeg, ExportFormat.Png, ExportFormat.WebP)

    /** Id of the dynamic chip carrying a project long edge that is not in [SIZES]. */
    const val CUSTOM_SIZE_ID: String = "custom"

    /**
     * Which fixed step [settings] is closest to.
     *
     * `Original` only when no long edge is requested at all. A project exported at
     * 3000 px must not be shown as 原尺寸 while the engine keeps using 3000: a
     * value between two steps resolves to the nearest one (3000 → 2048), so a chip
     * is always selected, and [customLongEdge]/[sizeCaption] surface the exact
     * value instead of the step's label.
     */
    fun sizeOf(settings: ExportSettings): PhoneExportSize {
        val longEdge = settings.effectiveLongEdge ?: return PhoneExportSize.Original
        return SIZES.firstOrNull { it.longEdge == longEdge } ?: nearestStep(longEdge)
    }

    /** The project's own long edge when it is not one of the three steps; else null. */
    fun customLongEdge(settings: ExportSettings): Int? {
        val longEdge = settings.effectiveLongEdge ?: return null
        return longEdge.takeIf { edge -> SIZES.none { it.longEdge == edge } }
    }

    /** The chips the 尺寸 row shows for [settings]: the three steps, plus a custom one. */
    fun sizeChoices(settings: ExportSettings): List<PhoneExportSizeChoice> {
        val steps = SIZES.map { PhoneExportSizeChoice(it.name, it.label, it.longEdge) }
        val custom = customLongEdge(settings) ?: return steps
        // The chip stays as short as "长边 2048" so it does not ellipsize at phone
        // width; [sizeCaption] prints the exact "自定义 N px" value in the field.
        return steps + PhoneExportSizeChoice(CUSTOM_SIZE_ID, "自定义 $custom", custom)
    }

    /** The highlighted chip: the custom value when one is set, else the nearest step. */
    fun selectedSizeId(settings: ExportSettings): String =
        if (customLongEdge(settings) != null) CUSTOM_SIZE_ID else sizeOf(settings).name

    /**
     * Applies one chip. The custom chip is already the selected project edge, so
     * tapping it is a no-op apart from re-pinning the quality — the next export
     * keeps the engine's real long edge instead of silently changing it.
     */
    fun applySizeChoice(settings: ExportSettings, id: String): ExportSettings {
        if (id == CUSTOM_SIZE_ID) return settings.copy(quality = QUALITY)
        val step = SIZES.firstOrNull { it.name == id } ?: return settings
        return applySize(settings, step)
    }

    fun formatOf(settings: ExportSettings): ExportFormat =
        if (settings.format in FORMATS) settings.format else ExportFormat.Jpeg

    /** Applies one size step and pins the quality to [QUALITY]. */
    fun applySize(settings: ExportSettings, size: PhoneExportSize): ExportSettings =
        settings.copy(
            sizeMode = if (size.longEdge == null) ExportSizeMode.Original else ExportSizeMode.LongEdge,
            longEdge = size.longEdge ?: settings.longEdge,
            quality = QUALITY,
        )

    /** Applies one format and pins the quality to [QUALITY]. */
    fun applyFormat(settings: ExportSettings, format: ExportFormat): ExportSettings {
        val normalized = formatOf(settings)
        return settings.copy(
            format = format,
            quality = QUALITY,
            // WebP on the phone is lossless by default; keep the flag coherent.
            webpLossless = if (format == ExportFormat.WebP) {
                if (normalized == ExportFormat.WebP) settings.webpLossless else true
            } else {
                settings.webpLossless
            },
        )
    }

    /**
     * Whether the Ultra HDR switch may be used: the export must be JPEG and the
     * active photo must really carry a gain map ([com.framemark.native.product.UltraHdr.locate]).
     * Anything else stays visible but disabled — no silent no-op and no fake HDR.
     */
    fun hdrEnabled(format: ExportFormat, hasGainMap: Boolean): Boolean =
        format == ExportFormat.Jpeg && hasGainMap

    /** The one-line reason the Ultra HDR switch is disabled; null when it can be used. */
    fun hdrReason(format: ExportFormat, hasGainMap: Boolean): String? = when {
        !hasGainMap -> "这张照片没有 HDR 增益图，只能导出 SDR"
        format != ExportFormat.Jpeg -> "Ultra HDR 仅在 JPEG 格式下可用"
        else -> null
    }

    /** Writes the HDR switch through the same copy path as 格式 / 尺寸 (no new JSON key). */
    fun applyPreserveHdr(settings: ExportSettings, enabled: Boolean): ExportSettings =
        settings.copy(preserveHdr = enabled, quality = QUALITY)

    /** The settings actually handed to the engine: quality always 100, format always valid. */
    fun normalized(settings: ExportSettings): ExportSettings =
        settings.copy(quality = QUALITY, format = formatOf(settings))

    /**
     * A-01 (UX-RESEARCH N-02) / 返修 P2-2: what the chosen size really does.
     *
     * The wording is deliberately per-format: the engine does encode the finished
     * canvas **once** at q100 4:4:4, so a promise that the file is never encoded
     * again would be false — and 画质 is the first-ranked trust issue in
     * UX-RESEARCH. 原尺寸 promises no rescaling; the encoder is named for what it is.
     *
     * A custom long edge is not 原尺寸: it must say what the engine really does, so
     * it never uses the "原分辨率，不缩放" wording.
     */
    fun resolutionNote(
        size: PhoneExportSize,
        format: ExportFormat,
        webpLossless: Boolean = true,
    ): String = resolutionNoteFor(size.longEdge, format, webpLossless)

    /** Settings-aware note: a custom long edge is described by its real value. */
    fun resolutionNote(settings: ExportSettings): String =
        resolutionNoteFor(settings.effectiveLongEdge, formatOf(settings), settings.webpLossless)

    private fun resolutionNoteFor(
        longEdge: Int?,
        format: ExportFormat,
        webpLossless: Boolean,
    ): String = when {
        longEdge != null -> "长边缩到 $longEdge px 后再编码。"
        format == ExportFormat.Jpeg -> "原分辨率，不缩放；JPEG 质量 100、4:4:4（会重新编码一次）"
        format == ExportFormat.Png -> "原分辨率，不缩放；无损编码"
        webpLossless -> "原分辨率，不缩放；无损编码"
        else -> "原分辨率，不缩放；WebP 质量 100（有损，会重新编码一次）"
    }

    /** The short form of the same promise, only shown on the 原尺寸 step. */
    const val ORIGINAL_RESOLUTION_BADGE: String = "原分辨率 · 质量 100"

    /** The encoder note under 格式: what the file really is. */
    fun formatNote(format: ExportFormat, webpLossless: Boolean = true): String = when (format) {
        ExportFormat.Jpeg -> "JPEG 质量固定 100、4:4:4"
        ExportFormat.Png -> "PNG 无损，质量选项不起作用。"
        ExportFormat.WebP -> if (webpLossless) {
            "WebP 无损（VP8L），质量固定 100。"
        } else {
            "WebP 质量 100（有损），会重新编码一次。"
        }
    }

    /** 尺寸 chip caption: 原尺寸 has no number to print. */
    fun sizeCaption(size: PhoneExportSize): String =
        size.longEdge?.let { "长边 $it px" } ?: "原尺寸"

    /**
     * 导出表单上要印的**真实成片像素**。
     *
     * 表单以前把 `outputWidth/outputHeight` 写死 0，于是永远退回去印「原尺寸」这一句，
     * 用户看不到 3024 × 4032（UX 审查 P1）。这里读的是舞台那张照片的像素 + 这张边框
     * 声明的参数，走的是右栏「导出尺寸」/ 顶栏 / 手机样式页 / 引擎桥接共用的那一条
     * [OutputSize] 链：先按参数算画布（页脚会加高），再套长边档。所以同一份设计在
     * 表单和样式页上印同一个数字。
     *
     * 取不到照片尺寸时返回 null —— 宁可不写，也不写一个猜的数字（表单退回尺寸档文案）。
     */
    fun outputPixels(
        photoWidthPx: Int,
        photoHeightPx: Int,
        controls: List<InspectorControl>,
        settings: ExportSettings,
        value: (InspectorControl) -> Any?,
    ): OutputPixels? = OutputSize.applyLongEdge(
        pixels = OutputSize.canvas(
            photoWidthPx = photoWidthPx,
            photoHeightPx = photoHeightPx,
            controls = controls,
            value = value,
        ),
        mode = settings.sizeMode,
        longEdge = settings.effectiveLongEdge,
    )

    /**
     * Caption of the real selection. A custom edge prints its exact value, so the
     * preview never repeats a step label the engine is not going to use.
     */
    fun sizeCaption(settings: ExportSettings): String {
        val longEdge = settings.effectiveLongEdge ?: return PhoneExportSize.Original.label
        return if (customLongEdge(settings) != null) "自定义 $longEdge px" else "长边 $longEdge px"
    }

    private fun nearestStep(longEdge: Int): PhoneExportSize {
        val steps = SIZES.filter { it.longEdge != null }
        return steps.minByOrNull { abs(it.longEdge!! - longEdge) } ?: PhoneExportSize.Original
    }
}
