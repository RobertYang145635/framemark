package com.framemark.native.shell.phone

import com.framemark.native.product.ExportFormat
import com.framemark.native.product.ExportSettings
import com.framemark.native.product.ExportSizeMode

/**
 * The phone export options of report §7.6: 格式, 尺寸 with exactly three choices
 * (原尺寸 / 长边 2048 / 长边 4096) and a quality that is **fixed at 100** — the
 * phone sheet has no quality control at all, unlike the desktop dialog.
 *
 * Everything here is pure, so the three size steps, the fixed quality and the
 * format mapping are unit-testable without a composition (WP-P2 §4.2).
 */
enum class PhoneExportSize(val label: String, val longEdge: Int?) {
    Original("原尺寸", null),
    Long2048("长边 2048", 2048),
    Long4096("长边 4096", 4096),
}

object PhoneExportOptions {
    /** Hand-off WP-P2: 质量固定 100. The submit path forces this, not just the copy. */
    const val QUALITY: Int = 100

    /** 尺寸三档, in the demo's order. */
    val SIZES: List<PhoneExportSize> = PhoneExportSize.entries

    /** 格式: the three the native encoder can write on Android. */
    val FORMATS: List<ExportFormat> = listOf(ExportFormat.Jpeg, ExportFormat.Png, ExportFormat.WebP)

    /** Which of the three steps [settings] currently asks for. */
    fun sizeOf(settings: ExportSettings): PhoneExportSize {
        if (settings.sizeMode != ExportSizeMode.LongEdge) return PhoneExportSize.Original
        // An older project may hold a long edge that is not one of the three steps;
        // keep it on the nearest step so the sheet always shows a selected chip.
        return SIZES.firstOrNull { it.longEdge == settings.longEdge } ?: PhoneExportSize.Original
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
     */
    fun resolutionNote(
        size: PhoneExportSize,
        format: ExportFormat,
        webpLossless: Boolean = true,
    ): String = when {
        size != PhoneExportSize.Original -> "长边缩到 ${size.longEdge} px 后再编码。"
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
}
