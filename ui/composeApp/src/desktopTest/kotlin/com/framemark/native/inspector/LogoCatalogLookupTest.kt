package com.framemark.native.inspector

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
}
