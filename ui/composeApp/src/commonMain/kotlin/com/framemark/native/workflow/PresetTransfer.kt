package com.framemark.native.workflow

import com.framemark.native.product.Documents
import com.framemark.native.product.PRESET_SCHEMA_VERSION
import com.framemark.native.product.ProjectPreset
import com.framemark.native.product.PresetCodec
import com.framemark.native.product.ProductJson
import kotlin.random.Random

/** Portable single-preset document validation and copy-ID generation. */
internal object PresetTransfer {
    // A 32 MiB embedded watermark expands to about 43 MiB in base64 JSON.
    const val MAX_DOCUMENT_BYTES = 50 * 1024 * 1024
    private val invalidFilenameChars = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    fun decodeDocument(json: String, knownCardIds: Set<String>): ProjectPreset {
        requireWithinLimit(json)
        val root = ProductJson.parseRoot(json)
        require(root["document"] == Documents.PRESET) { "文件不是单项 FrameMark 预设" }
        val version = (root["schemaVersion"] as? Number)?.toDouble()
            ?: error("预设文件缺少有效版本号")
        require(version == PRESET_SCHEMA_VERSION.toDouble()) {
            if (version > PRESET_SCHEMA_VERSION) "预设文件版本较新，请升级 FrameMark 后再导入"
            else "预设文件版本不受支持"
        }

        val preset = PresetCodec.decodeDocument(root)
        require(preset.design.cardId in knownCardIds) {
            "预设引用了当前版本不存在的边框：${preset.design.cardId}"
        }
        return preset
    }

    fun encodeDocument(preset: ProjectPreset): String = PresetCodec.encodeDocument(preset).also { requireWithinLimit(it) }

    fun copyWithNewId(preset: ProjectPreset, occupiedIds: Set<String>): ProjectPreset {
        repeat(16) {
            val id = "import-${randomHexId()}"
            if (id !in occupiedIds) return preset.copy(id = id)
        }
        error("无法为导入副本分配唯一 ID，请重试")
    }

    fun suggestedFileName(preset: ProjectPreset): String {
        val base = preset.name
            .filter { it >= ' ' && it !in invalidFilenameChars }
            .trim()
            .trimEnd('.')
            .take(80)
            .ifBlank { "FrameMark 预设" }
        return "$base.preset.json"
    }

    private fun randomHexId(): String {
        val bytes = Random.Default.nextBytes(ByteArray(16))
        return bytes.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun requireWithinLimit(text: String) {
        var byteCount = 0L
        var index = 0
        while (index < text.length && byteCount <= MAX_DOCUMENT_BYTES) {
            val char = text[index]
            byteCount += when {
                char.code <= 0x7f -> 1
                char.code <= 0x7ff -> 2
                char.code in 0xd800..0xdbff && index + 1 < text.length && text[index + 1].code in 0xdc00..0xdfff -> {
                    index++
                    4
                }
                else -> 3
            }
            index++
        }
        require(byteCount <= MAX_DOCUMENT_BYTES) { "预设文件超过 50 MiB 上限" }
    }
}
