package com.framemark.native.workflow

import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryPreviewLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Inputs associated with a completed file so actions stay on the right photo. */
data class ExportSource(
    val photoPath: String?,
    val design: DesignSnapshot,
    val settings: ExportSettings,
    val overlay: OverlaySpec?,
    val nameIndex: Int,
    val outputDirectory: String,
    val photoDisplayName: String?,
)

/** Result of one committed export; [note] includes platform publication status. */
data class ExportResult(
    val path: String,
    val fileName: String,
    val bytes: Int,
    val width: Int?,
    val height: Int?,
    val formatLabel: String,
    /** Non-null when the platform could not encode and the preview was copied. */
    val note: String?,
    val source: ExportSource? = null,
)

/**
 * Placeholder export: draws a neutral image at the requested long edge and writes
 * it into the temporary export folder. Used only when the platform has no native
 * renderer (Android this round); it never opens a photo and never reads border
 * parameters, and the sheet labels it 示例.
 */
object FakeEngine {
    suspend fun export(card: LibraryCard, settings: ExportSettings): ExportResult =
        withContext(Dispatchers.Default) {
            val platform = WorkflowEnv.platform
                ?: throw IllegalStateException("no local storage")
            val longEdge = settings.longEdge.coerceIn(400, 4096)
            val width = longEdge
            val height = (longEdge * 3) / 4

            var bytes = if (settings.format == SampleFormat.WebP) {
                // No placeholder WebP encoder: fall through to the honest
                // preview-copy path instead of writing JPEG bytes as .webp.
                null
            } else {
                platform.encodeSampleImage(width, height, settings.format, settings.quality)
            }
            var formatLabel = settings.format.label
            var extension = settings.format.extension
            var resultWidth: Int? = width
            var resultHeight: Int? = height
            var note: String? = null

            if (bytes == null) {
                // Platform has no encoder for this format: copy the border preview
                // and label the file honestly.
                bytes = LibraryPreviewLoader.loadBytes(card)
                formatLabel = "WebP"
                extension = "webp"
                resultWidth = null
                resultHeight = null
                note = "预览图副本"
            }

            val path = ExportNaming.exportPath(platform, settings.filenamePattern, card.name, null, extension)
            check(platform.writeBytes(path, bytes)) { "cannot write sample file" }
            println("FM_FAKE_EXPORT path=$path bytes=${bytes.size} format=$formatLabel")
            ExportResult(
                path = path,
                fileName = path.substringAfterLast('/').substringAfterLast('\\'),
                bytes = bytes.size,
                width = resultWidth,
                height = resultHeight,
                formatLabel = formatLabel,
                note = note,
            )
        }

    /** Filename shown in the sheet before the sample is generated. */
    fun previewName(pattern: String, cardName: String): String = ExportNaming.previewName(pattern, cardName)
}
