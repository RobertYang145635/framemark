package com.framemark.native.inspector

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 用**真实**的 `logos.json` 校验品牌键解析：`PhotoDesign.logo` 存的是品牌键
 * （`laika` / `panasionic` / 英文别名），面板必须能把它落回目录条目，
 * 否则已经生效的品牌会显示成「未收录」（WP-R 现场复现的 P2/D1 症状）。
 */
class LogoCatalogLookupTest {

    private fun catalog(): List<LogoBrand> {
        val path = listOf(
            "../ui/composeApp/src/commonMain/composeResources/files/data/logos.json",
            "ui/composeApp/src/commonMain/composeResources/files/data/logos.json",
            "../../ui/composeApp/src/commonMain/composeResources/files/data/logos.json",
        ).map(::File).firstOrNull(File::isFile)
            ?: error("logos.json not found from ${File(".").absolutePath}")
        return LogoBrandCatalog.parse(path.readText())
    }

    @Test
    fun realCatalogResolvesBrandKeysAndCutKeys() {
        val brands = catalog()
        assertTrue(brands.size >= 60, "expected the full catalog, got ${brands.size}")

        val laika = logoBrandForKeyValue(brands, "laika")
        assertNotNull(laika, "brand key 'laika' must resolve")
        assertEquals("Leica", laika.label)

        assertEquals("panasionic", logoBrandForKeyValue(brands, "panasonic")?.key)
        assertEquals("panasionic", logoBrandForKeyValue(brands, "lumix")?.key)
        assertEquals("hasu", logoBrandForKeyValue(brands, "hasselblad")?.key)
        assertEquals("fushi", logoBrandForKeyValue(brands, "fujifilm")?.key)
        assertEquals("laika", logoBrandForKeyValue(brands, "laika_black")?.key)

        // 精确 cut 键仍走原来的路径，两者互不替代。
        val cut = laika.versions.flatMap { it.cuts }.first()
        assertEquals("laika", logoBrandForAssetKey(brands, cut.assetKey)?.key)
        assertEquals(null, logoBrandForKeyValue(brands, "auto"))
    }

    @Test
    fun everyAutoMatchedBrandKeyIsActuallyInTheCatalog() {
        val brands = catalog()
        // EXIF Make → 品牌键 → 目录条目：三档都要通，否则自动匹配就退回「未收录」。
        mapOf(
            "PANASONIC" to "panasionic",
            "LEICA CAMERA AG" to "laika",
            "Canon" to "canon",
            "NIKON CORPORATION" to "nikon",
            "Hasselblad" to "hasu",
            "FUJIFILM" to "fushi",
            "SONY" to "sony",
        ).forEach { (make, key) ->
            val resolved = LogoPhotoBrand.brandKeyForMake(make)
            assertEquals(key, resolved, "make '$make'")
            assertNotNull(
                brands.firstOrNull { it.key == resolved },
                "matched key '$key' is missing from the catalog",
            )
        }
    }

    @Test
    fun theShippedCatalogDrawsThePhotoBrandForAnUnsetLogo() {
        // 缺陷 1 的端到端落点，用的是**发出去的** logos.json：设计里没有显式选择时
        // （空 / auto），OPPO 照片该画的那一枚字标必须真的在这本目录里。
        //
        // 取值不是「猜的」：`autoCutKey` 先找 `<key>_black` / `<key>_white`，找不到才退回
        // canonical version 的第一枚 cut —— 所以 Panasonic 落到 `ver:panasionic:panasonic`，
        // Leica 那一刻的 assetKey 本身就是 `laika`。这三条路径都要在这条测试里走过一遍。
        val brands = catalog()
        listOf(
            "OPPO" to "oppo_black",
            "OPPO CPH2505" to "oppo_black",
            "PANASONIC" to "ver:panasionic:panasonic",
            "Canon" to "canon_black",
            "LEICA CAMERA AG" to "laika",
            "Hasselblad" to "hasu_black",
        ).forEach { (make, expected) ->
            assertEquals(expected, LogoPhotoBrand.cutKeyFor(null, make, brands), "make=$make")
            assertEquals(expected, LogoPhotoBrand.cutKeyFor("auto", make, brands), "make=$make auto")
            assertNotNull(
                logoBrandForAssetKey(brands, expected),
                "auto-matched cut '$expected' must exist in the shipped catalog",
            )
        }
        // 没有品牌 / 品牌不在库里：null = 引擎用卡片自带字标（现状，不报错、不空白）。
        assertNull(LogoPhotoBrand.cutKeyFor(null, null, brands))
        assertNull(LogoPhotoBrand.cutKeyFor(null, "Some Unknown Camera", brands))
    }
}
