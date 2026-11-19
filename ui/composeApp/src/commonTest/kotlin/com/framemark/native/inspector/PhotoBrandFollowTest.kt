package com.framemark.native.inspector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 缺陷 1 的合同：**选中边框之后，字标必须自动跟随照片品牌**，不需要再点「自动匹配」。
 *
 * 这一层是渲染器的决策函数（[LogoPhotoBrand.cutKeyFor]），安卓 `AndroidNativeRender`
 * 与桌面 `DesktopNativeRender` 都调它 —— 两端不会对同一张照片给出不同答案。
 *
 * 三条要钉住的行为（都是上一轮复测里的原话）：
 *  1. 空 / `auto` → 按照片 EXIF 品牌解析出该画的字标（OPPO 照片就是 OPPO）；
 *  2. 解析不到（没有 EXIF、品牌不在库里、目录没载入）→ null，引擎用卡片自带字标，
 *     不报错也不空白；
 *  3. 用户显式选过的品牌 → 自动解析**不覆盖**它。
 */
class PhotoBrandFollowTest {

    private fun cut(assetKey: String) =
        LogoCut(assetKey = assetKey, file = "$assetKey.webp", preview = "${assetKey}__preview.webp")

    private fun brand(key: String, label: String, cuts: List<String>) = LogoBrand(
        key = key,
        label = label,
        cn = label,
        group = "phone",
        canonicalVersion = "$key-current",
        versions = listOf(
            LogoVersion(
                id = "$key-current",
                name = "$label wordmark",
                era = "current",
                years = "2022–",
                surface = "both",
                cuts = cuts.map(::cut),
            ),
        ),
    )

    private val brands = listOf(
        brand("oppo", "OPPO", listOf("oppo_black", "oppo_white")),
        brand("laika", "Leica", listOf("laika_black", "laika_white")),
        // 库里没有 `<key>_black` 的品牌：自动匹配落回 canonical version 的第一枚 cut。
        brand("hasu", "Hasselblad", listOf("ver:hasu:h-wordmark")),
    )

    // -- 1. 空 / auto → 按照片品牌 ---------------------------------------------

    @Test
    fun unsetLogoFollowsThePhotoBrandWithoutAnyClick() {
        // 用户从没点过任何东西（`toDesignSnapshot` 把「没选」写成 null）：OPPO 照片
        // 挑任何一张手写卡，这一帧画的就该是 OPPO 的字标。
        assertEquals("oppo_black", LogoPhotoBrand.cutKeyFor(null, "OPPO", brands))
        // 点过「自动匹配」的工程写的是 `auto`，读出来同样是按照片走。
        assertEquals("oppo_black", LogoPhotoBrand.cutKeyFor("auto", "OPPO", brands))
        assertEquals("oppo_black", LogoPhotoBrand.cutKeyFor("", "OPPO", brands))
        // 大小写 / 厂商名的其余写法都过同一张 matchBrand 表。
        assertEquals("oppo_black", LogoPhotoBrand.cutKeyFor(null, "OPPO CPH2505", brands))
        assertEquals("laika_black", LogoPhotoBrand.cutKeyFor(null, "LEICA CAMERA AG", brands))
        // 没有 `<key>_black` 的条目落回 canonical version 的第一枚（引擎画的就是它）。
        assertEquals("ver:hasu:h-wordmark", LogoPhotoBrand.cutKeyFor(null, "Hasselblad", brands))
    }

    @Test
    fun autoValuesAreExactlyBlankAndAuto() {
        // 取值域只有「空 / auto」代表没选；其余任何非空值都是用户的显式选择。
        assertTrue(LogoPhotoBrand.isAutoValue(null))
        assertTrue(LogoPhotoBrand.isAutoValue(""))
        assertTrue(LogoPhotoBrand.isAutoValue("   "))
        assertTrue(LogoPhotoBrand.isAutoValue("auto"))
        assertTrue(LogoPhotoBrand.isAutoValue("AUTO"))
        assertFalse(LogoPhotoBrand.isAutoValue("oppo_black"))
        assertFalse(LogoPhotoBrand.isAutoValue("user:abc123"))
    }

    // -- 2. 解析不到 → 卡片自带字标（现状） --------------------------------------

    @Test
    fun missingOrUnknownPhotoBrandFallsBackToTheCardLogo() {
        // 没有 EXIF 品牌（截图、转存、老照片）：null = 不改设计、引擎画卡片自带的那一枚。
        assertNull(LogoPhotoBrand.cutKeyFor(null, null, brands))
        assertNull(LogoPhotoBrand.cutKeyFor("auto", "", brands))
        assertNull(LogoPhotoBrand.cutKeyFor(null, "   ", brands))
        // 有厂牌但不在 61 个品牌里。
        assertNull(LogoPhotoBrand.cutKeyFor(null, "Some Unknown Camera", brands))
        // 品牌库还没载入 / 载入失败（两平台都是异步载入）：同样退回现状，不空白。
        assertNull(LogoPhotoBrand.cutKeyFor(null, "OPPO", emptyList()))
    }

    // -- 3. 显式品牌不被覆盖 ----------------------------------------------------

    @Test
    fun anExplicitBrandIsNeverOverriddenByThePhoto() {
        // 用户手动选了 Leica：OPPO 照片也继续画 Leica —— 解析函数返回 null 表示
        // 「这次不要让自动结果参与决策」，显式值走原来的严格解析路径。
        assertNull(LogoPhotoBrand.cutKeyFor("laika_black", "OPPO", brands))
        assertNull(LogoPhotoBrand.cutKeyFor("laika", "OPPO", brands))
        assertNull(LogoPhotoBrand.cutKeyFor("ver:hasu:h-wordmark", "OPPO", brands))
        // 用户 Logo 与「未收录但显式写死的值」同样不能被自动解析顶掉。
        assertNull(LogoPhotoBrand.cutKeyFor("user:abc123", "OPPO", brands))
        assertNull(LogoPhotoBrand.cutKeyFor("sony_1999", "OPPO", brands))
    }
}
