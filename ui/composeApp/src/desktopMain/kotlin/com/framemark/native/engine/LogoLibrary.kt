package com.framemark.native.engine

import com.framemark.native.product.Base64Codec
import com.framemark.native.product.WatermarkAsset
import com.framemark.native.product.readFileBytesBounded
import com.framemark.native.library.Json
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString
import com.framemark.native.workflow.PhotoMetadata
import java.io.File
import java.security.MessageDigest

/** A brand logo as the hand engine wants it: `design.logo = {name,width,height,dataUrl}`. */
data class EngineLogo(
    val name: String,
    val width: Int,
    val height: Int,
    val dataUrl: String,
)

/**
 * Resolves a selected catalog cut through the runtime's exact asset map. Legacy
 * brand-only values keep the older `<data>/logos/<brand>_<cut>.png` behavior.
 *
 * The cut choice is deterministic: the brand's `black` cut first, then `white`,
 * then `color`, then the alphabetically first file. Picking a different brand must
 * change the render, and the same brand must always render the same way.
 */
class LogoLibrary(val dir: File = File(RuntimePaths.layout().dataRoot, "logos")) {
    private data class VersionAsset(val engineKey: String, val pngSha256: String)

    private val cache = HashMap<String, EngineLogo?>()
    private val versionMapFile = File(dir.parentFile, "registry/logo-versions.json")
    private val versionMap: Map<String, VersionAsset> by lazy { readVersionMap() }

    /** Renderer-facing key; selected catalog cuts map to collision-safe staged PNG stems. */
    fun engineKey(brandKey: String?, exactAssetKey: Boolean = false): String? {
        val key = brandKey?.trim()?.takeIf { it.isNotEmpty() && !it.equals("auto", ignoreCase = true) } ?: return null
        val mapped = versionMap[key]
        if (mapped != null) {
            if (loadExact(mapped) == null) {
                throw EngineConfigException(
                    "FM-E-LOGO-002",
                    "所选 Logo 版本的运行时图片缺失或损坏",
                    expectedPath = File(dir, "${mapped.engineKey}.png").absolutePath,
                )
            }
            return mapped.engineKey
        }
        if (exactAssetKey || key.startsWith("ver:")) {
            throw EngineConfigException("FM-E-LOGO-001", "所选 Logo 版本未包含在当前运行时中", expectedPath = versionMapFile.absolutePath)
        }
        return key
    }

    fun resolve(brandKey: String?, exactAssetKey: Boolean = false): EngineLogo? {
        val key = brandKey?.trim()?.takeIf { it.isNotEmpty() && !it.equals("auto", ignoreCase = true) } ?: return null
        val mapped = versionMap[key]
        if ((exactAssetKey || key.startsWith("ver:")) && mapped == null) {
            throw EngineConfigException("FM-E-LOGO-001", "所选 Logo 版本未包含在当前运行时中", expectedPath = versionMapFile.absolutePath)
        }
        if (cache.containsKey(key)) return cache[key]
        val logo = if (mapped != null) {
            loadExact(mapped) ?: throw EngineConfigException(
                "FM-E-LOGO-002",
                "所选 Logo 版本的运行时图片缺失或损坏",
                expectedPath = File(dir, "${mapped.engineKey}.png").absolutePath,
            )
        } else {
            runCatching { load(key) }.getOrNull()
        }
        cache[key] = logo
        return logo
    }

    private fun loadExact(asset: VersionAsset): EngineLogo? {
        return runCatching {
            val stem = asset.engineKey
            val file = File(dir, "$stem.png")
            if (!file.isFile) return null
            // Bound before loading: a staged logo larger than the product asset limit
            // is corrupt by definition and must not be read into memory.
            val bytes = readFileBytesBounded(file.path, WatermarkAsset.MAX_BYTES) ?: return null
            if (!sha256(bytes).equals(asset.pngSha256, ignoreCase = true)) return null
            val size = PhotoMetadata.read(file) ?: return null
            EngineLogo(
                name = stem,
                width = size.width,
                height = size.height,
                dataUrl = "data:image/png;base64," + Base64Codec.encode(bytes),
            )
        }.getOrNull()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun readVersionMap(): Map<String, VersionAsset> {
        if (!versionMapFile.isFile) return emptyMap()
        return try {
            val root = Json.parse(versionMapFile.readText()).jsonMap()
            require(root.jsonString("schema") == "framemark-logo-version-map/v1") { "Unsupported logo version map schema" }
            val rows = root["entries"].jsonList()
            rows.map { row ->
                val entry = row.jsonMap()
                val key = entry.jsonString("assetKey")?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Logo version map entry is missing assetKey")
                val stem = entry.jsonString("engineKey")
                    ?: throw IllegalArgumentException("Logo version map entry is missing engineKey")
                val pngSha256 = entry.jsonString("pngSha256")
                    ?: throw IllegalArgumentException("Logo version map entry is missing pngSha256")
                if (!stem.matches(Regex("fmlogo_[a-f0-9]{64}"))) throw IllegalArgumentException("Invalid engine logo key")
                if (!pngSha256.matches(Regex("[a-fA-F0-9]{64}"))) throw IllegalArgumentException("Invalid logo PNG hash")
                key to VersionAsset(stem, pngSha256)
            }.also { entries ->
                if (entries.isEmpty()) throw IllegalArgumentException("Logo version map has no entries")
                if (entries.map { it.first }.toSet().size != entries.size || entries.map { it.second.engineKey }.toSet().size != entries.size) {
                    throw IllegalArgumentException("Logo version map contains duplicate asset keys or engine keys")
                }
            }.toMap()
        } catch (error: Exception) {
            throw EngineConfigException(
                "FM-E-LOGO-003",
                "Logo 版本资源清单损坏",
                expectedPath = versionMapFile.absolutePath,
                cause = error,
            )
        }
    }

    private fun load(key: String): EngineLogo? {
        val files = dir.listFiles { f ->
            f.isFile && f.name.lowercase().endsWith(".png") &&
                (f.name.substringBeforeLast('.') == key || f.name.startsWith("$key"))
        }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return null
        fun bySuffix(suffix: String) = files.firstOrNull { it.nameWithoutExtension.lowercase() == "$key$suffix" }
        val pick = bySuffix("_black") ?: bySuffix("_white") ?: bySuffix("_color") ?: files.first()
        val bytes = readFileBytesBounded(pick.path, WatermarkAsset.MAX_BYTES) ?: return null
        val size = PhotoMetadata.read(pick) ?: return null
        return EngineLogo(
            name = pick.nameWithoutExtension,
            width = size.width,
            height = size.height,
            dataUrl = "data:image/png;base64," + Base64Codec.encode(bytes),
        )
    }

    /** Brand keys the runtime can actually render with; used by the evidence entry. */
    fun availableBrands(): List<String> = dir.listFiles()
        ?.filter { it.isFile && it.name.lowercase().endsWith(".png") }
        ?.map { it.name.substringBefore('_') }
        ?.distinct()
        ?.sorted()
        .orEmpty()
}
