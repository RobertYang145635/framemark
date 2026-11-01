package com.framemark.native.engine

import com.framemark.native.product.Base64Codec
import com.framemark.native.workflow.PhotoMetadata
import java.io.File

/** A brand logo as the hand engine wants it: `design.logo = {name,width,height,dataUrl}`. */
data class EngineLogo(
    val name: String,
    val width: Int,
    val height: Int,
    val dataUrl: String,
)

/**
 * Resolves the brand-picker key onto a PNG from the runtime's own logo set
 * (`<data>/logos/<brand>_<cut>.png`), so a desktop render never needs an absolute
 * asset path from the workspace.
 *
 * The cut choice is deterministic: the brand's `black` cut first, then `white`,
 * then `color`, then the alphabetically first file. Picking a different brand must
 * change the render, and the same brand must always render the same way.
 */
class LogoLibrary(val dir: File = File(RuntimePaths.layout().dataRoot, "logos")) {
    private val cache = HashMap<String, EngineLogo?>()

    fun resolve(brandKey: String?): EngineLogo? {
        val key = brandKey?.trim()?.takeIf { it.isNotEmpty() && it != "auto" } ?: return null
        if (cache.containsKey(key)) return cache[key]
        val logo = runCatching { load(key) }.getOrNull()
        cache[key] = logo
        return logo
    }

    private fun load(key: String): EngineLogo? {
        val files = dir.listFiles { f ->
            f.isFile && f.name.lowercase().endsWith(".png") &&
                (f.name.substringBeforeLast('.') == key || f.name.startsWith("$key"))
        }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return null
        fun bySuffix(suffix: String) = files.firstOrNull { it.nameWithoutExtension.lowercase() == "$key$suffix" }
        val pick = bySuffix("_black") ?: bySuffix("_white") ?: bySuffix("_color") ?: files.first()
        val bytes = pick.readBytes()
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
