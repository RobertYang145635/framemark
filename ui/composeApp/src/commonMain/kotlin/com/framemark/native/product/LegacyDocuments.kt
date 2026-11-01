package com.framemark.native.product

import com.framemark.native.library.Json
import com.framemark.native.library.jsonInt
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString

/**
 * Readers for the pre-product documents both shells used before `SessionStore`
 * existed, so an upgraded install keeps its queue instead of starting empty.
 *
 * The legacy shapes were hand-written and unversioned:
 * `{"version":1,"items":[…]}` for the batch queue and
 * `{"version":1,"presets":[…]}` for the preset library. A product document is
 * recognised by its `document` id, so the two never parse as each other.
 */
object LegacyDocuments {
    const val BATCH_DOCUMENT = "framemark.batch"
    const val PRESET_DOCUMENT = "framemark.presets"

    private fun documentId(text: String): String? = runCatching {
        Json.parse(text).jsonMap().jsonString("document")
    }.getOrNull()

    fun isProductBatch(text: String): Boolean = documentId(text) == BATCH_DOCUMENT

    fun isProductPresetLibrary(text: String): Boolean = documentId(text) == PRESET_DOCUMENT

    /** True only for the old, unversioned-product preset envelope we can migrate. */
    fun isLegacyPresetLibrary(text: String): Boolean = runCatching {
        val root = Json.parse(text).jsonMap()
        root["document"] == null && isLegacyVersion(root["version"]) && root["presets"] is List<*>
    }.getOrDefault(false)

    /** Only this exact empty legacy envelope may unlock the first preset save. */
    fun isEmptyLegacyPresetLibrary(text: String): Boolean = runCatching {
        val root = Json.parse(text).jsonMap()
        root.keys == setOf("version", "presets") &&
            isLegacyVersion(root["version"]) &&
            (root["presets"] as? List<*>)?.isEmpty() == true
    }.getOrDefault(false)

    /** Ordered legacy IDs, or null unless every row has both a stable ID and card ID. */
    fun legacyPresetIds(text: String): List<String>? {
        return try {
            val root = Json.parse(text).jsonMap()
            if (root["document"] != null || !isLegacyVersion(root["version"])) return null
            val rows = root["presets"] as? List<*> ?: return null
            val ids = ArrayList<String>(rows.size)
            for (raw in rows) {
                val row = runCatching { raw.jsonMap() }.getOrNull() ?: return null
                val id = row.jsonString("id")?.takeIf { it.isNotBlank() } ?: return null
                if (row.jsonString("cardId").isNullOrBlank()) return null
                ids += id
            }
            ids
        } catch (_: Throwable) {
            null
        }
    }

    /** Returns the original row count only when every legacy row has a usable key. */
    fun legacyPresetCount(text: String): Int? = legacyPresetIds(text)?.size

    private fun isLegacyVersion(raw: Any?): Boolean =
        (raw as? Number)?.let { it.toInt() == 1 && it.toDouble() == 1.0 } == true

    /**
     * A legacy queue, converted to product jobs. What the old document never stored
     * is not invented: it carried no control values and no watermark, so a migrated
     * job keeps the card, the photo, the export settings and the status, and renders
     * with the card's declared defaults.
     */
    fun legacyBatch(text: String): List<BatchJob>? {
        val root = runCatching { Json.parse(text).jsonMap() }.getOrNull() ?: return null
        if (root.jsonString("document") != null) return null
        val items = root["items"].jsonList()
        if (items.isEmpty()) return null
        val jobs = items.mapNotNull { raw ->
            val map = raw.jsonMap()
            // The old writer stored the row number as a JSON number.
            val id = (map["id"] as? Number)?.toInt()?.toString()
                ?: map.jsonString("id")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val cardId = map.jsonString("cardId").orEmpty()
            if (cardId.isBlank()) return@mapNotNull null
            val format = when (map.jsonString("format")) {
                "PNG" -> ExportFormat.Png
                "WebP" -> ExportFormat.WebP
                else -> ExportFormat.Jpeg
            }
            val status = when (map.jsonString("status")) {
                "Done" -> BatchJobStatus.Done
                "Failed" -> BatchJobStatus.Failed
                "Cancelled" -> BatchJobStatus.Cancelled
                else -> BatchJobStatus.Queued
            }
            BatchJob(
                id = "legacy-$id",
                photoPath = map.jsonString("photoPath"),
                photoFingerprint = map.jsonString("photoFingerprint"),
                displayName = map.jsonString("name") ?: cardId,
                design = PhotoDesign.base(cardId, map.jsonString("styleId")),
                overlay = null,
                exportSettings = ExportSettings(
                    format = format,
                    quality = map.jsonInt("quality") ?: 100,
                    // The old queue always encoded a fixed long edge.
                    sizeMode = ExportSizeMode.LongEdge,
                    longEdge = map.jsonInt("longEdge") ?: 1600,
                    filenamePattern = map.jsonString("filenamePattern") ?: "{name}-{date}",
                    webpLossless = map["webpLossless"] as? Boolean ?: true,
                ),
                status = status,
                attempts = map.jsonInt("attempts") ?: 0,
                outputPath = map.jsonString("outputPath"),
                error = map.jsonString("error")?.let { BatchJobError("FM-E-EXPORT-001", it) },
            )
        }
        return jobs.ifEmpty { null }
    }
}
