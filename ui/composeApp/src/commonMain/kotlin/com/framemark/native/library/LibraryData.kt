package com.framemark.native.library

import com.framemark.native.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * One border card from the frozen E1a bundle (`engine/data/taxonomy.json` +
 * `previews.json`). All source fields are kept verbatim: nothing is derived,
 * normalised or guessed. `brand` is the vendor chip used for grouping (D-010),
 * `vendor`/`family`/`fidelity`/`badge`/`systemStyle` keep their original values.
 */
data class LibraryCard(
    val id: String,
    val name: String,
    val category: String,
    val categoryName: String,
    val brand: String?,
    val vendor: String?,
    val family: String?,
    val fidelity: String?,
    val badge: String?,
    val en: String?,
    val systemStyle: String?,
    /**
     * taxonomy `templateCategory`：非厂商卡的子类（如“文字边框 → 厂商水印”）。现有分组不读这个字段，
     * 新边框库用它做非厂商类别的子类分组（报告 §6“其他类别是各自的子类”、§7 搜索示例“文字 · 厂商水印”）。
     * 有默认值，两处现有构造点不受影响。
     */
    val templateCategory: String? = null,
    /**
     * D-035 三级族（落盘后由 `engine/data/taxonomy.json` 提供）：`styleFamily` 是带命名空间的族键
     * （`"<厂商> / <固件中文族>"`），`styleFamilyLabel` 是固件中文族名，`familyNameProvenance` 是来路等级
     * （verified-in-file / name-exists-in-package / key-token-derived / unverified / no-family-by-design）。
     * UI 只读这些字段本身，不写死族数或族名。
     */
    val styleFamily: String? = null,
    val styleFamilyLabel: String? = null,
    val familyNameProvenance: String? = null,
    /** 来路的人话标注（仅无族档用于卡片组说明）。 */
    val styleFamilyLabelSource: String? = null,
    /** Original E1a repo-relative image path (`previews.rows[].path`). */
    val previewSourcePath: String,
    /** File name inside `composeResources/files/previews/`; empty when unmapped. */
    val previewFile: String,
)

data class LibraryCategory(
    val key: String,
    val name: String,
    val count: Int,
)

data class LibrarySnapshot(
    val cards: List<LibraryCard>,
    val categories: List<LibraryCategory>,
    /** Number of cards that have a mapped preview row. */
    val mappedPreviews: Int,
    /** Cards whose preview row is missing/empty — reported, never silently blank. */
    val unmappedPreviewIds: List<String>,
    val generatedAt: String,
)

data class LibraryLoadResult(
    val snapshot: LibrarySnapshot,
    /**
     * `null` when the bundle loaded; otherwise the sentence the error screen shows.
     * The exception text is not copy — it lives in [diagnostic] (audit item 5).
     */
    val error: String? = null,
    /** Raw exception text for support, never rendered. */
    val diagnostic: String? = null,
)

sealed interface LibraryLoadState {
    data object Loading : LibraryLoadState
    data class Ready(val snapshot: LibrarySnapshot) : LibraryLoadState
    data class Failed(val message: String) : LibraryLoadState
}

/**
 * Loads the E1a JSON exactly as shipped. The two files are copies of
 * `framemark/engine/data/{taxonomy,previews}.json` under
 * `composeResources/files/data/` (sha256 recorded in `_research/native/u2/`).
 */
object LibraryDataSource {
    const val TAXONOMY_RESOURCE = "files/data/taxonomy.json"
    const val PREVIEWS_RESOURCE = "files/data/previews.json"
    const val PREVIEW_DIR = "files/previews/"

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(): LibraryLoadResult {
        return try {
            val taxonomyText = Res.readBytes(TAXONOMY_RESOURCE).decodeToString()
            val previewsText = Res.readBytes(PREVIEWS_RESOURCE).decodeToString()
            LibraryLoadResult(parse(taxonomyText, previewsText))
        } catch (t: Throwable) {
            LibraryLoadResult(
                snapshot = emptySnapshot(),
                error = "边框库数据读取失败，请重试。",
                diagnostic = t.message ?: t::class.simpleName ?: "unknown error",
            )
        }
    }

    fun parse(taxonomyText: String, previewsText: String): LibrarySnapshot {
        val taxonomy = Json.parse(taxonomyText).jsonMap()
        val previewsDoc = Json.parse(previewsText).jsonMap()

        val previewRows = previewsDoc["rows"].jsonList()
            .map { it.jsonMap() }
            .associateBy { it.jsonString("cardId").orEmpty() }

        val cards = taxonomy["cards"].jsonList().mapNotNull { raw ->
            val c = raw.jsonMap()
            val id = c.jsonString("id") ?: return@mapNotNull null
            val row = previewRows[id]
            val sourcePath = row?.jsonString("path").orEmpty()
            val file = sourcePath.substringAfterLast('/').takeIf {
                it.isNotBlank() && it.endsWith(".webp", ignoreCase = true)
            }.orEmpty()
            LibraryCard(
                id = id,
                name = c.jsonString("name") ?: id,
                category = c.jsonString("category") ?: "",
                categoryName = c.jsonString("categoryName") ?: "",
                brand = c.jsonString("brand"),
                vendor = c.jsonString("vendor"),
                family = c.jsonString("family"),
                fidelity = c.jsonString("fidelity"),
                badge = c.jsonString("badge"),
                en = c.jsonString("en"),
                systemStyle = c.jsonString("systemStyle"),
                templateCategory = c.jsonString("templateCategory"),
                styleFamily = c.jsonString("styleFamily"),
                styleFamilyLabel = c.jsonString("styleFamilyLabel"),
                familyNameProvenance = c.jsonString("familyNameProvenance"),
                styleFamilyLabelSource = c.jsonString("styleFamilyLabelSource"),
                previewSourcePath = sourcePath,
                previewFile = file,
            )
        }

        val declaredCategories = taxonomy["categories"].jsonList().map { raw ->
            val c = raw.jsonMap()
            LibraryCategory(
                key = c.jsonString("key").orEmpty(),
                name = c.jsonString("name").orEmpty(),
                count = c.jsonInt("actualCount") ?: 0,
            )
        }.filter { it.key.isNotBlank() }

        // Counts are recomputed from the loaded cards so a drift is visible instead
        // of silently trusting a stale header.
        val categories = declaredCategories.map { cat ->
            cat.copy(count = cards.count { it.category == cat.key })
        }

        return LibrarySnapshot(
            cards = cards,
            categories = categories,
            mappedPreviews = cards.count { it.previewFile.isNotBlank() },
            unmappedPreviewIds = cards.filter { it.previewFile.isBlank() }.map { it.id },
            generatedAt = taxonomy["generatedAt"] as? String ?: "",
        )
    }

    private fun emptySnapshot() = LibrarySnapshot(
        cards = emptyList(),
        categories = emptyList(),
        mappedPreviews = 0,
        unmappedPreviewIds = emptyList(),
        generatedAt = "",
    )
}
