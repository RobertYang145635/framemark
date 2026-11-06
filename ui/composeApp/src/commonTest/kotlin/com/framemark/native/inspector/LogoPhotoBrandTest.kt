package com.framemark.native.inspector

import com.framemark.native.workflow.TextSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 自动匹配 = 按照片品牌（演示稿 `logo.js` `lgAutoBrand`）。这一层是纯映射，
 * 所以直接测表：照片 EXIF 的 Make → 品牌库键 → 目录条目 → 该画哪枚字标。
 */
class LogoPhotoBrandTest {

    private fun slot(name: String, text: String, raw: String = text) = TextSlot(
        id = "wm-classic#$name#0",
        key = "wm-classic#$name#0",
        text = text,
        x = 0.0,
        y = 0.0,
        w = 0.0,
        h = 0.0,
        fontSize = null,
        baseline = null,
        lines = 1,
        raw = raw,
        stable = true,
    )

    private fun cut(assetKey: String) =
        LogoCut(assetKey = assetKey, file = "$assetKey.webp", preview = "${assetKey}__preview.webp")

    private fun brand(
        key: String,
        label: String,
        canonical: String?,
        versions: List<LogoVersion>,
    ) = LogoBrand(
        key = key,
        label = label,
        cn = "",
        group = "camera",
        canonicalVersion = canonical,
        versions = versions,
    )

    private fun panasonic() = brand(
        key = "panasionic",
        label = "Panasonic",
        canonical = "panasonic-blue",
        versions = listOf(
            LogoVersion(
                id = "panasionic-legacy-black",
                name = "Panasonic Panasionic Black",
                era = "current",
                years = "2006–",
                surface = "both",
                cuts = listOf(cut("panasionic_black"), cut("panasionic_white")),
            ),
            LogoVersion(
                id = "panasonic-blue",
                name = "Panasonic blue wordmark",
                era = "current",
                years = "2006–",
                surface = "light",
                cuts = listOf(cut("ver:panasionic:panasonic-blue")),
            ),
        ),
    )

    @Test
    fun matchTableFollowsTheRendererOrder() {
        // 与 `core/brands.ts matchBrand()` / `brand.rs match_brand()` 同序。
        assertEquals("panasionic", LogoPhotoBrand.brandKeyForMake("Panasonic"))
        assertEquals("panasionic", LogoPhotoBrand.brandKeyForMake("PANASONIC"))
        assertEquals("panasionic", LogoPhotoBrand.brandKeyForMake("LUMIX DC-S1"))
        assertEquals("laika", LogoPhotoBrand.brandKeyForMake("LEICA CAMERA AG"))
        assertEquals("canon", LogoPhotoBrand.brandKeyForMake("Canon"))
        assertEquals("nikon", LogoPhotoBrand.brandKeyForMake("NIKON CORPORATION"))
        assertEquals("sony", LogoPhotoBrand.brandKeyForMake("ILCE-7M4"))
        assertEquals("hasu", LogoPhotoBrand.brandKeyForMake("Hasselblad"))
        assertNull(LogoPhotoBrand.brandKeyForMake(""))
        assertNull(LogoPhotoBrand.brandKeyForMake("未知品牌"))
    }

    @Test
    fun brandRowSlotIsThePhotoBrand() {
        val slots = listOf(
            slot("exif", "366mm  F6.3  1/400s  ISO125"),
            slot("make", "PANASONIC"),
            slot("model", "DC-S1"),
        )
        assertEquals("PANASONIC", LogoPhotoBrand.makeText(slots))
        // 用户改过的品牌行不算照片品牌：原文（raw）才是引擎从 EXIF 画出来的那行。
        assertEquals(
            "PANASONIC",
            LogoPhotoBrand.makeText(listOf(slot("make", "我的相机", raw = "PANASONIC"))),
        )
        assertNull(LogoPhotoBrand.makeText(listOf(slot("model", "DC-S1"))))
        assertNull(LogoPhotoBrand.makeText(null))
    }

    @Test
    fun resolveMapsMakeToCatalogBrandAndItsAppLogo() {
        val brands = listOf(panasonic())
        val match = LogoPhotoBrand.resolve(listOf(slot("make", "PANASONIC")), brands)
        assertNotNull(match)
        assertEquals("Panasonic", match.brand.label)
        assertEquals("PANASONIC", match.make)
        // 引擎画的是 `logos/panasionic_black`，不是 canonical version 的蓝版本。
        assertEquals("panasionic_black", match.assetKey)
        assertEquals("panasonic-blue", match.version?.id)
    }

    @Test
    fun unknownBrandResolvesToNothing() {
        assertNull(LogoPhotoBrand.resolve(listOf(slot("make", "未知品牌")), listOf(panasonic())))
        // 照片品牌在库里找不到条目（61 个品牌之外的键）同样算未收录。
        assertNull(LogoPhotoBrand.resolve(listOf(slot("make", "Panasonic")), emptyList()))
    }

    @Test
    fun brandKeyValuesResolveToTheirBrand() {
        // `PhotoDesign.logo` 的取值域里既有品牌键也有精确 cut 键，两者都要认。
        val brands = listOf(panasonic())
        assertEquals("panasionic", logoBrandForKeyValue(brands, "panasionic")?.key)
        assertEquals("panasionic", logoBrandForKeyValue(brands, "panasonic")?.key)
        assertEquals("panasionic", logoBrandForKeyValue(brands, "panasionic_black")?.key)
        assertEquals("panasionic", logoBrandForKeyValue(brands, " LUMIX ")?.key)
        assertNull(logoBrandForAssetKey(brands, "panasonic"))
        assertNull(logoBrandForKeyValue(brands, "auto"))
        assertNull(logoBrandForKeyValue(brands, ""))
    }

    @Test
    fun autoCutFallsBackToTheCanonicalVersion() {        val noAppLogo = brand(
            key = "hasu",
            label = "Hasselblad",
            canonical = "h-wordmark",
            versions = listOf(
                LogoVersion(
                    id = "h-wordmark",
                    name = "H wordmark",
                    era = "current",
                    years = "2006–",
                    surface = "both",
                    cuts = listOf(cut("ver:hasu:h-wordmark")),
                ),
            ),
        )
        assertEquals("ver:hasu:h-wordmark", LogoPhotoBrand.autoCutKey(noAppLogo))
        assertEquals("panasionic_black", LogoPhotoBrand.autoCutKey(panasonic()))
    }
}
