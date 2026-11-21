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
        // 品牌行画成图片时那一行没有文字，退回机型行（机型里带着厂商名）。
        assertEquals(
            "OPPO Find X6 Pro",
            LogoPhotoBrand.makeText(listOf(slot("make", ""), slot("model", "OPPO Find X6 Pro"))),
        )
        assertNull(LogoPhotoBrand.makeText(listOf(slot("exif", "23mm  F2.2  1/33s  ISO250"))))
        assertNull(LogoPhotoBrand.makeText(null))
    }

    /**
     * 真机 玻璃 04：品牌行画成字标图片（文字为空），机型行是「OPPO Find X6 Pro」。
     * 摘要要说「按照片品牌 OPPO」，不能因为品牌行没字就退回「按边框自己的品牌」。
     */
    @Test
    fun blankBrandRowFallsBackToTheModelRow() {
        val oppo = brand(
            key = "oppo",
            label = "OPPO",
            canonical = "oppo-black",
            versions = listOf(
                LogoVersion(
                    id = "oppo-black",
                    name = "OPPO black",
                    era = "current",
                    years = "2004–",
                    surface = "light",
                    cuts = listOf(cut("oppo_black")),
                ),
            ),
        )
        val slots = listOf(
            slot("make", ""),
            slot("model", "OPPO Find X6 Pro"),
            slot("exif", "23mm  F2.2  1/33s  ISO250"),
        )
        val match = LogoPhotoBrand.resolve(slots, listOf(oppo))
        assertNotNull(match)
        assertEquals("oppo", match.brand.key)
        assertEquals("oppo_black", match.assetKey)
        // 机型里没有厂商名（且品牌行也没字）仍然是「未收录」，不猜。
        assertNull(LogoPhotoBrand.resolve(listOf(slot("model", "X-T5")), listOf(oppo)))
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

    /**
     * 说明行只认渲染器按片解析的品牌（WP-Q2 中项，真机 2026-11-21）：
     * 没 EXIF 的照片 + 手填品牌「QA54-ALPHA」，旧逻辑读品牌行文字，写出
     * 「按照片品牌 Sony」而屏上画的是边框自带的 Panasonic。品牌行文字不是照片品牌。
     */
    @Test
    fun the_caption_never_takes_the_brand_row_text_as_the_photo_brand() {
        val brands = listOf(
            com.framemark.native.inspector.LogoBrand(
                key = "sony",
                label = "Sony",
                cn = "索尼",
                group = "相机",
                canonicalVersion = null,
                versions = emptyList(),
            ),
        )
        // 没有渲染器报告（照片没有 EXIF Make）→ 不给标签，说明行说「按边框自己的品牌」。
        assertEquals(null, LogoPhotoBrand.captionLabel(null, brands))
        assertEquals(null, LogoPhotoBrand.captionLabel("  ", brands))
        // 只有渲染器真的解析出品牌键时才报品牌名。
        assertEquals("Sony", LogoPhotoBrand.captionLabel("sony", brands))
        // 库里没有的键也不猜。
        assertEquals(null, LogoPhotoBrand.captionLabel("unknown", brands))
    }
}
