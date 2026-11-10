package com.framemark.native.workflow

import com.framemark.native.library.Json
import com.framemark.native.product.ExportFormat
import com.framemark.native.product.ExportSettings
import com.framemark.native.library.jsonInt
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString

/**
 * A saved design: the selected border plus every control value and the export
 * choices. Nothing here is rendered; loading a preset only restores local state.
 */
data class Preset(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val cardId: String,
    val cardName: String,
    val adjustedCount: Int,
    val values: Map<String, Any>,
    val export: ExportSettings,
)

/** The preset document is plain JSON, written next to the app's private data. */
internal object PresetJson {
    fun encode(presets: List<Preset>): String {
        val body = presets.joinToString(",\n") { preset ->
            "  {" +
                "\"id\":${quote(preset.id)}," +
                "\"name\":${quote(preset.name)}," +
                "\"updatedAt\":${preset.updatedAt}," +
                "\"cardId\":${quote(preset.cardId)}," +
                "\"cardName\":${quote(preset.cardName)}," +
                "\"adjustedCount\":${preset.adjustedCount}," +
                "\"export\":${encodeExport(preset.export)}," +
                "\"values\":${encodeValues(preset.values)}" +
                "}"
        }
        return "{\"version\":1,\"presets\":[\n$body\n]}\n"
    }

    fun decode(text: String): List<Preset> {
        val root = Json.parse(text).jsonMap()
        return root["presets"].jsonList().mapNotNull { raw ->
            val m = raw.jsonMap()
            val id = m.jsonString("id") ?: return@mapNotNull null
            val cardId = m.jsonString("cardId") ?: return@mapNotNull null
            val exportRaw = m["export"].jsonMap()
            val values = LinkedHashMap<String, Any>()
            m["values"].jsonMap().forEach { (key, value) ->
                when (value) {
                    is Boolean, is String -> values[key] = value
                    is Number -> values[key] = value.toFloat()
                    else -> Unit
                }
            }
            Preset(
                id = id,
                name = m.jsonString("name") ?: "未命名预设",
                updatedAt = (m["updatedAt"] as? Number)?.toLong() ?: 0L,
                cardId = cardId,
                cardName = m.jsonString("cardName") ?: "",
                adjustedCount = m.jsonInt("adjustedCount") ?: 0,
                values = values,
                export = ExportSettings(
                    format = when (exportRaw.jsonString("format")) {
                        "PNG" -> ExportFormat.Png
                        "WebP" -> ExportFormat.WebP
                        else -> ExportFormat.Jpeg
                    },
                    quality = exportRaw.jsonInt("quality")?.coerceIn(60, 100) ?: 100,
                    // The legacy document has no size mode; a stored numeric long edge
                    // is kept as data, a missing one stays "not chosen" (null).
                    longEdge = exportRaw.jsonInt("longEdge")?.coerceIn(400, 4096),
                    filenamePattern = exportRaw.jsonString("filename") ?: "{name}-{date}",
                    webpLossless = exportRaw["webpLossless"] as? Boolean ?: true,
                ),
            )
        }
    }

    private fun encodeExport(export: ExportSettings): String =
        "{\"format\":${quote(export.format.label)}," +
            "\"quality\":${export.quality}," +
            "\"longEdge\":${export.longEdge}," +
            "\"webpLossless\":${export.webpLossless}," +
            "\"filename\":${quote(export.filenamePattern)}}"

    private fun encodeValues(values: Map<String, Any>): String =
        values.entries.joinToString(",", "{", "}") { (key, value) ->
            quote(key) + ":" + encodeValue(value)
        }

    private fun encodeValue(value: Any): String = when (value) {
        is Boolean -> value.toString()
        is Number -> value.toString()
        else -> quote(value.toString())
    }

    private fun quote(text: String): String {
        val sb = StringBuilder(text.length + 8)
        sb.append('"')
        text.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
