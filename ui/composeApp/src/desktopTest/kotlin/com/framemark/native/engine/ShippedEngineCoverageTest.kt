package com.framemark.native.engine

import com.framemark.native.library.Json
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString
import com.framemark.native.product.RealData
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The border library may only offer cards the shipped engine can actually render, and it
 * must not unpack the same bundle twice.
 *
 * The 2026-11-13 180-card sweep saw five `stock-vivo-disney-*` cards fall back to the
 * packaged static preview. The data was not the problem — `taxonomy.json` declares
 * `vivo-disney:*` and the shipped bundle carries those five styles and their assets — the
 * device was: it read a 106-style bundle extracted on 2026-10-29 while the APK shipped 111,
 * because the extraction was keyed on a hand-maintained constant nobody bumped when the
 * bundle was regenerated. Each half of that failure has a test here:
 *
 *  - [every_taxonomy_card_resolves_to_a_shipped_style] enumerates the cards the picker
 *    offers against the styles and hand cards `assets/x1ir.zip` really ships, in both
 *    directions, and names every mismatch;
 *  - [shipped_identity_is_derived_from_the_bundle_manifest] pins the identity the Android
 *    extraction is keyed on to the bundle's own `manifest.json` — no literal to drift;
 *  - [the_same_bundle_is_not_unpacked_again] pins the reuse rule: an extraction of the same
 *    bundle is never re-unpacked, a different one is, and a half-written or unreadable
 *    bundle never costs the user the extraction already on disk.
 *
 * This is the data side of the promise `NativeEngine.supports` keeps at runtime; it runs
 * before an APK is built rather than on a test device.
 */
class ShippedEngineCoverageTest {

    private val root = RealData.root

    private val zip = ZipFile(
        root.resolve(BUNDLE).toFile().also {
            require(it.isFile) { "随包引擎缺失：$BUNDLE" }
        },
    )

    @AfterTest
    fun closeBundle() = zip.close()

    private val manifestText: String by lazy { zipText("manifest.json") }

    private val manifest: Map<String, Any?> by lazy { Json.parse(manifestText).jsonMap() }

    /** One row per shipped IR style: `id`, `uiCardId` and the bundle-relative `style` path. */
    private val shippedStyles: List<Map<String, Any?>> by lazy {
        manifest["styles"].jsonList().map { it.jsonMap() }
    }

    /** Card ids the shipped hand templates cover (`hand/cards.json`). */
    private val shippedHandIds: List<String> by lazy {
        Json.parse(zipText("hand/cards.json")).jsonMap()["cards"].jsonList()
            .mapNotNull { it.jsonMap().jsonString("card")?.takeIf { id -> id.isNotBlank() } }
    }

    private val taxonomyCards: List<Map<String, Any?>> by lazy {
        Json.parse(Files.readString(root.resolve(TAXONOMY))).jsonMap()["cards"]
            .jsonList().map { it.jsonMap() }
    }

    @Test
    fun every_taxonomy_card_resolves_to_a_shipped_style() {
        val stylesById = shippedStyles.associateBy { it.jsonString("id").orEmpty() }
        val handIds = shippedHandIds.toSet()
        val irCards = taxonomyCards.filter { !it.jsonString("systemStyle").isNullOrBlank() }
        val handCards = taxonomyCards.filter { it.jsonString("systemStyle").isNullOrBlank() }
        val problems = mutableListOf<String>()

        irCards.forEach { card ->
            val cardId = card.jsonString("id").orEmpty()
            val styleId = card.jsonString("systemStyle").orEmpty()
            val style = stylesById[styleId]
            if (style == null) {
                problems += "$cardId：taxonomy 声明 systemStyle=$styleId，随包引擎没有这个样式"
                return@forEach
            }
            val listed = style.jsonString("uiCardId")
            if (listed != cardId) {
                problems += "$cardId：引擎样式 $styleId 绑定的 uiCardId=$listed"
            }
            val stylePath = style.jsonString("style").orEmpty()
            if (stylePath.isBlank() || zip.getEntry(stylePath) == null) {
                problems += "$cardId：引擎样式文件缺失 $stylePath"
            }
        }
        handCards.forEach { card ->
            val cardId = card.jsonString("id").orEmpty()
            if (cardId !in handIds) {
                problems += "$cardId：没有 systemStyle，随包 hand/cards.json 也没有这张卡"
            }
        }
        assertTrue(
            problems.isEmpty(),
            "边框库有 ${problems.size} 张卡没有可渲染的引擎样式（补引擎，或从 taxonomy 摘掉）：\n" +
                problems.joinToString("\n"),
        )

        // The other direction: a card the engine can render but the picker cannot reach.
        val taxonomyIds = taxonomyCards.mapNotNull { it.jsonString("id") }.toSet()
        val unreachable = shippedStyles.mapNotNull { it.jsonString("uiCardId") }.filter { it !in taxonomyIds } +
            shippedHandIds.filter { it !in taxonomyIds }
        assertTrue(
            unreachable.isEmpty(),
            "随包引擎能出帧、边框库却没有的卡（用户永远选不到）：\n" + unreachable.joinToString("\n"),
        )

        assertEquals(
            irCards.size,
            shippedStyles.size,
            "taxonomy 的 IR 卡与随包样式必须一一对应：taxonomy=${irCards.size}，引擎=${shippedStyles.size}",
        )
        assertEquals(
            handCards.size,
            shippedHandIds.size,
            "taxonomy 的 hand 卡与 hand/cards.json 必须一一对应：" +
                "taxonomy=${handCards.size}，引擎=${shippedHandIds.size}",
        )
    }

    @Test
    fun shipped_identity_is_derived_from_the_bundle_manifest() {
        val identity = assertNotNull(
            ShippedEngineBundle.identityOf(manifestText),
            "随包 manifest.json 读不出身份：schema/generatedAt/styles 缺失",
        )
        assertEquals(
            "${manifest.jsonString("schema")} generatedAt=${manifest.jsonString("generatedAt")} " +
                "styles=${shippedStyles.size}",
            identity,
            "身份必须完全由 manifest 本身决定",
        )
        // 同一份 manifest 永远得到同一串：这就是「没换包就不重解」的前提。
        assertEquals(identity, ShippedEngineBundle.identityOf(manifestText))

        assertNull(ShippedEngineBundle.identityOf("not a manifest"))
        assertNull(ShippedEngineBundle.identityOf("{}"))
    }

    @Test
    fun the_same_bundle_is_not_unpacked_again() {
        val shipped = assertNotNull(ShippedEngineBundle.identityOf(manifestText))
        val generatedAt = assertNotNull(manifest.jsonString("generatedAt"))
        val stale = assertNotNull(
            ShippedEngineBundle.identityOf(manifestText.replace(generatedAt, "2026-10-29T14:39:50.539Z")),
            "改了 generatedAt 的 manifest 必须仍是可读的 bundle 身份",
        )
        assertNotEquals(shipped, stale, "不同 bundle 必须有不同身份")

        var extraction: String? = stale
        var unpacked = 0
        repeat(3) {
            if (!ShippedEngineBundle.canReuse(extraction, shipped, complete = true)) {
                unpacked++
                extraction = shipped
            }
        }
        assertEquals(1, unpacked, "旧 bundle 只应重解一次，随后必须收敛")
        repeat(3) {
            if (!ShippedEngineBundle.canReuse(extraction, shipped, complete = true)) {
                unpacked++
                extraction = shipped
            }
        }
        assertEquals(1, unpacked, "同一份 bundle 不能在每次启动时重解")

        assertFalse(ShippedEngineBundle.canReuse(shipped, shipped, complete = false), "半截解包不能复用")
        assertFalse(ShippedEngineBundle.canReuse(null, shipped, complete = false), "没有解包时必须解包")
        assertTrue(
            ShippedEngineBundle.canReuse(shipped, null, complete = true),
            "读不到随包 bundle 时保留磁盘上已有的解包，不能把它拆掉",
        )
    }

    private fun zipText(path: String): String {
        val entry = requireNotNull(zip.getEntry(path)) { "随包引擎 $BUNDLE 缺少 $path" }
        return zip.getInputStream(entry).use { it.readBytes().decodeToString() }
    }

    private companion object {
        /** `bridge/desktop/cards-175.json` describes the desktop runtime; this is the APK asset. */
        const val BUNDLE = "ui/composeApp/src/androidMain/assets/x1ir.zip"
        const val TAXONOMY = "engine/data/taxonomy.json"
    }
}
