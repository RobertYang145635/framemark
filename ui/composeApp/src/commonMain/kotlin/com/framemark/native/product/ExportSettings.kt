package com.framemark.native.product

/** Export formats the engine can encode (PRD US-07: JPEG q100, PNG lossless, WebP VP8L). */
enum class ExportFormat(val id: String, val label: String, val extension: String) {
    Jpeg("jpeg", "JPG", "jpg"),
    Png("png", "PNG", "png"),
    WebP("webp", "WebP", "webp");

    companion object {
        fun fromId(id: String): ExportFormat? = entries.firstOrNull { it.id == id }
    }
}

/** Original size is the default (PRD §8 decision 3); a custom long edge is opt-in. */
enum class ExportSizeMode(val id: String) {
    Original("original"),
    LongEdge("longEdge");

    companion object {
        fun fromId(id: String): ExportSizeMode? = entries.firstOrNull { it.id == id }
    }
}

data class ExportSettings(
    val format: ExportFormat = ExportFormat.Jpeg,
    val quality: Int = 100,
    val sizeMode: ExportSizeMode = ExportSizeMode.Original,
    val longEdge: Int? = null,
    val filenamePattern: String = "{photo}-{index}",
    val webpLossless: Boolean = true,
    /**
     * Keep the source photo's HDR rendition by re-encapsulating its gain map as an
     * Ultra HDR JPEG. Only meaningful for [ExportFormat.Jpeg] and only when the
     * photo actually carries a gain map; otherwise the export is plain SDR.
     */
    val preserveHdr: Boolean = false,
) {
    /** Long edge handed to the engine; null means "keep the source size". */
    val effectiveLongEdge: Int? get() = if (sizeMode == ExportSizeMode.LongEdge) longEdge else null
}
