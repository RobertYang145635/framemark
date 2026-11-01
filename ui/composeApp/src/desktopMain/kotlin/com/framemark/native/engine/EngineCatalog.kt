package com.framemark.native.engine

import com.framemark.native.library.Json
import com.framemark.native.library.jsonInt
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString
import java.io.File

/** One text node declaration on a card.
 *
 * - [nodePath] is the engine's own node address (`b<band>/g<child>…`, the value
 *   e2b writes as `items[].path`); null means the manifest only has the legacy
 *   string ids (bound by array index).
 * - [sampleText] is the frozen probe text for hand cards. e4hand reports no
 *   path, so the bridge aligns draws to slots by this text.
 * - [optional] marks IR nodes the engine may drop when their token is empty;
 *   it never removes the slot from the declaration.
 */
data class EngineSlot(
    val slotId: String,
    val nodePath: String? = null,
    val optional: Boolean = false,
    val label: String? = null,
    val sampleText: String? = null,
    val legacy: Boolean = false,
)

/** One UI card backed by a real engine binary. */
data class EngineCard(
    val cardId: String,
    /** `ir` (e2b/framemark_engine) or `hand` (e4hand). */
    val backend: String,
    val engineId: String,
    val name: String,
    val style: String?,
    val cardsFile: String?,
    val design: String?,
    /** Text node declarations; see [EngineSlot]. Never cropped to one photo. */
    val slots: List<EngineSlot>,
)

/**
 * The 175-card desktop manifest: which UI card is rendered by which engine,
 * plus the bundled sample photo. Every manifest path is package-relative
 * (`data/…`); [abs] re-roots it at the runtime data root, so the dev workspace,
 * the portable folder and an installed copy resolve the same document.
 *
 * Loading validates the whole set (no silent per-card fallback): a missing style,
 * design or hand table aborts with an [EngineConfigException] naming the card,
 * the file and the expected path.
 */
class EngineCatalog private constructor(
    val root: String,
    val dataRoot: String,
    val manifestPath: String,
    val photoPath: String,
    val photoW: Int,
    val photoH: Int,
    val exifSource: String,
    /** The manifest strings, kept so engines can receive package-relative args. */
    val photoPackagePath: String,
    val exifPackagePath: String,
    val hasIrCards: Boolean,
    val hasHandCards: Boolean,
    private val byId: Map<String, EngineCard>,
) {
    fun card(cardId: String): EngineCard? = byId[cardId]

    fun supports(cardId: String): Boolean = byId.containsKey(cardId)

    fun cards(): List<EngineCard> = byId.values.toList()

    /** Absolute path of a package-relative manifest path (`data/…` → dataRoot). */
    fun abs(path: String): String {
        requirePackagePath(path)
        return when {
            path == "data" -> dataRoot
            path.startsWith("data/") -> File(dataRoot, path.removePrefix("data/")).path
            else -> File(root, path).path
        }
    }

    /**
     * Argument for an engine whose CWD is [dataRoot]: `data/…` becomes relative,
     * anything else stays absolute. Used for styles/photo/fonts/hand dirs so the
     * packaged runtime can run entirely on package-relative arguments.
     */
    fun engineArg(path: String): String {
        requirePackagePath(path)
        return if (path.startsWith("data/")) path.removePrefix("data/") else abs(path)
    }

    fun isPackageRelative(path: String): Boolean = path.startsWith("data/")

    private fun requirePackagePath(path: String) {
        if (path.isBlank()) throw EngineConfigException("FM-E-RES-001", "manifest 路径为空")
        if (path.contains('\\')) {
            throw EngineConfigException(
                "FM-E-RES-001",
                "manifest 路径必须是 POSIX 相对路径（不允许反斜杠）",
                fileName = path,
            )
        }
        if (path.length >= 2 && path[1] == ':' || path.startsWith("/") || path.startsWith("\\\\")) {
            throw EngineConfigException(
                "FM-E-RES-001",
                "manifest 不允许绝对路径",
                fileName = path,
            )
        }
        if (path.contains("..")) {
            throw EngineConfigException("FM-E-RES-001", "manifest 路径不允许 .. 越界", fileName = path)
        }
    }

    private fun requireFile(cardId: String, label: String, packagePath: String?): File {
        if (packagePath.isNullOrBlank()) {
            throw EngineConfigException("FM-E-RES-001", "卡片缺少 $label", cardId = cardId)
        }
        val file = File(abs(packagePath))
        if (!file.isFile) {
            throw EngineConfigException(
                "FM-E-RES-001",
                "$label 缺失",
                cardId = cardId,
                fileName = packagePath,
                expectedPath = file.path,
            )
        }
        return file
    }

    companion object {
        private val SLOT_PATH = Regex("^b\\d+(/g\\d+)*$")

        /**
         * cards-175.json emits slot objects. The legacy 10-card `x1-cards.json`
         * still lists plain ids; those stay readable and are bound by array
         * order exactly as before ([EngineSlot.legacy]).
         */
        private fun parseSlot(cardId: String, entry: Any?): EngineSlot {
            when (entry) {
                is String -> return EngineSlot(slotId = entry, legacy = true)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val m = entry as Map<String, Any?>
                    val slotId = (m["slotId"] as? String)?.takeIf { it.isNotBlank() }
                        ?: throw EngineConfigException(
                            "FM-E-RES-001",
                            "slot 缺少 slotId（旧字符串数组仍兼容）",
                            cardId = cardId,
                        )
                    val nodePath = (m["nodePath"] as? String)?.takeIf { it.isNotBlank() }
                    if (nodePath != null && !SLOT_PATH.matches(nodePath)) {
                        throw EngineConfigException(
                            "FM-E-RES-001",
                            "slot nodePath 非法：$nodePath",
                            cardId = cardId,
                            fileName = nodePath,
                        )
                    }
                    return EngineSlot(
                        slotId = slotId,
                        nodePath = nodePath,
                        optional = m["optional"] as? Boolean ?: false,
                        label = m["label"] as? String,
                        sampleText = m["sampleText"] as? String,
                    )
                }
                else -> throw EngineConfigException("FM-E-RES-001", "slot 必须是对象或字符串", cardId = cardId)
            }
        }

        fun load(layout: RuntimePaths.Layout = RuntimePaths.layout()): EngineCatalog {
            val manifest = layout.manifest
            if (!manifest.isFile) {
                throw EngineConfigException(
                    "FM-E-RES-001",
                    "卡片清单缺失",
                    fileName = manifest.name,
                    expectedPath = manifest.path,
                )
            }
            val doc = try {
                Json.parse(manifest.readText()).jsonMap()
            } catch (t: Throwable) {
                throw EngineConfigException(
                    "FM-E-RES-001",
                    "卡片清单无法解析：${t.message}",
                    fileName = manifest.path,
                    cause = t,
                )
            }
            val photo = doc["photo"].jsonMap()
            val photoPath = photo.jsonString("path")
                ?: throw EngineConfigException("FM-E-RES-001", "清单缺少 photo.path", fileName = manifest.path)
            val exifSource = photo.jsonString("exifSource") ?: photoPath
            val cards = LinkedHashMap<String, EngineCard>()
            doc["cards"].jsonList().forEach { entry ->
                val map = entry.jsonMap()
                val cardId = map.jsonString("cardId") ?: return@forEach
                val card = EngineCard(
                    cardId = cardId,
                    backend = map.jsonString("backend")
                        ?: throw EngineConfigException("FM-E-RES-001", "卡片缺少 backend", cardId = cardId),
                    engineId = map.jsonString("engineId")
                        ?: throw EngineConfigException("FM-E-RES-001", "卡片缺少 engineId", cardId = cardId),
                    name = map.jsonString("name") ?: cardId,
                    style = map.jsonString("style"),
                    cardsFile = map.jsonString("cardsFile"),
                    design = map.jsonString("design"),
                    slots = map["slots"].jsonList().map { slotEntry ->
                        parseSlot(cardId, slotEntry)
                    },
                )
                if (cards.put(cardId, card) != null) {
                    throw EngineConfigException("FM-E-RES-001", "卡片 id 重复", cardId = cardId)
                }
            }
            if (cards.isEmpty()) {
                throw EngineConfigException("FM-E-RES-001", "卡片清单没有卡片", fileName = manifest.path)
            }

            val catalog = EngineCatalog(
                root = layout.root.path,
                dataRoot = layout.dataRoot.path,
                manifestPath = manifest.path,
                photoPath = File(catalogAbs(layout, photoPath)).path,
                photoW = photo.jsonInt("w") ?: 0,
                photoH = photo.jsonInt("h") ?: 0,
                exifSource = File(catalogAbs(layout, exifSource)).path,
                photoPackagePath = photoPath,
                exifPackagePath = exifSource,
                hasIrCards = cards.values.any { it.backend == "ir" },
                hasHandCards = cards.values.any { it.backend == "hand" },
                byId = cards,
            )
            catalog.requireFile("", "photo", photoPath)
            catalog.requireFile("", "exif source", exifSource)
            if (catalog.hasIrCards) {
                if (layout.decodedDir == null) {
                    throw EngineConfigException(
                        "FM-E-RES-001",
                        "IR 卡需要 data/decoded 资产缓存",
                        expectedPath = File(layout.dataRoot, "decoded").path,
                    )
                }
                if (layout.fontsDir == null) {
                    throw EngineConfigException(
                        "FM-E-RES-001",
                        "IR 卡需要 data/fonts",
                        expectedPath = File(layout.dataRoot, "fonts").path,
                    )
                }
            }
            if (catalog.hasHandCards) {
                if (layout.handDir == null) {
                    throw EngineConfigException(
                        "FM-E-RES-001",
                        "hand 卡需要 data/hand",
                        expectedPath = File(layout.dataRoot, "hand").path,
                    )
                }
                if (layout.fontsDir == null) {
                    throw EngineConfigException(
                        "FM-E-RES-001",
                        "hand 卡需要 data/fonts",
                        expectedPath = File(layout.dataRoot, "fonts").path,
                    )
                }
            }
            cards.values.forEach { card ->
                when (card.backend) {
                    "ir" -> catalog.requireFile(card.cardId, "style", card.style)
                    "hand" -> {
                        catalog.requireFile(card.cardId, "cardsFile", card.cardsFile)
                        catalog.requireFile(card.cardId, "design", card.design)
                    }
                    else -> throw EngineConfigException(
                        "FM-E-RES-001",
                        "未知 backend '${card.backend}'",
                        cardId = card.cardId,
                    )
                }
            }
            return catalog
        }

        private fun catalogAbs(layout: RuntimePaths.Layout, packagePath: String): String = when {
            packagePath == "data" -> layout.dataRoot.path
            packagePath.startsWith("data/") -> File(layout.dataRoot, packagePath.removePrefix("data/")).path
            else -> File(layout.root, packagePath).path
        }
    }
}
