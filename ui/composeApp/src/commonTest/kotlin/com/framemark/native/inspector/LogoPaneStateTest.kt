package com.framemark.native.inspector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Logo 面板的视图合同（复测反馈：落地不是品牌库列表，选完 / 取消回到同一块面板）。
 *
 * 三条要钉的东西：
 *  1. **落地** = 调整 + 三个动作；任何已生效值都不会让面板落在列表页上；
 *  2. **返回** = 覆盖层选中 / 取消都回到同一块正文，取消一个字都不写；
 *  3. **动作** = 更换 Logo 是唯一能进品牌库的一下，自动匹配就地写 `auto` 并留在面板上。
 *
 * 纯函数，没有 Compose、没有资源、没有总表数据。
 */
class LogoPaneStateTest {

    private fun cut(assetKey: String) =
        LogoCut(assetKey = assetKey, file = "$assetKey.webp", preview = "${assetKey}__preview.webp")

    private fun version(id: String, cuts: List<LogoCut>, era: String = "current") = LogoVersion(
        id = id,
        name = id,
        era = era,
        years = "2010–2020",
        surface = "light",
        cuts = cuts,
    )

    private val laika = LogoBrand(
        key = "laika",
        label = "Leica",
        cn = "徕卡",
        group = "camera",
        canonicalVersion = "leica-m3",
        versions = listOf(
            version("leica-m3", listOf(cut("laika_black"), cut("laika_white"))),
            version("leica-q", listOf(cut("laika_q")), era = "classic"),
        ),
        merged = emptyList(),
    )

    private val brands = listOf(laika)

    // -- 落地 -----------------------------------------------------------------

    @Test
    fun landingBodyIsThePanelNeverAList() {
        // 打开工具的第一帧就是「调整 + 动作」；两个列表都不在落地态里。
        assertEquals(LogoPaneBody.Panel, LogoPaneState.Landing.body)
        assertFalse(LogoPaneState.Landing.versionPage)
        assertNull(LogoPaneState.Landing.browseBrandKey)
    }

    @Test
    fun appliedCutLandsOnThePanelAndNamesThatCut() {
        // 已经选了一枚字标的工程：落地照样是面板，说明行就是那一枚（不是品牌库列表）。
        val summary = logoSummary("laika_white", brands)
        assertEquals(LogoSummaryKind.Applied, summary.kind)
        assertEquals("Leica · 徕卡 · leica-m3", summary.title)
        assertEquals("当前：Leica · 徕卡 · leica-m3", logoCaption(summary))
        assertEquals(LogoPaneBody.Panel, LogoPaneState.Landing.body)
    }

    @Test
    fun autoAndUnknownValuesBothSayAutoMatch() {
        // `auto`、空值、老工程里手改出来库里没有的值：引擎都落到自动解析，
        // 说明行也就都写「自动匹配」，不谎报「未收录」。
        listOf("auto", "", "   ", "sony_1999").forEach { raw ->
            val summary = logoSummary(raw, brands)
            assertEquals(LogoSummaryKind.Auto, summary.kind, "raw=$raw")
            assertEquals("自动匹配", summary.title, "raw=$raw")
            assertNull(summary.brand, "raw=$raw")
        }
    }

    @Test
    fun autoMatchSaysWhichPhotoBrandItFollows() {
        val summary = logoSummary("auto", brands, photoBrandLabel = "Leica")
        assertEquals("当前：自动匹配 · 按照片品牌 Leica", logoCaption(summary))
        // 这一帧没有照片品牌行（舞台还没报 / 边框自己画）：说「按边框自己的品牌」。
        assertEquals(
            "当前：自动匹配 · 按边框自己的品牌",
            logoCaption(logoSummary("auto", brands)),
        )
    }

    @Test
    fun appliedUserLogoLandsOnThePanelAndSaysMyLogo() {
        val summary = logoSummary("user:abc123", brands)
        assertEquals(LogoSummaryKind.User, summary.kind)
        assertEquals("当前：我的 Logo · 保存在全局 Logo 库", logoCaption(summary))
        assertEquals(LogoPaneBody.Panel, LogoPaneState.Landing.body)
    }

    @Test
    fun brandKeyValueResolvesToTheBrandsCanonicalVersion() {
        // 取值域里的品牌键（`laika`）不是 cut 键：落回最新版本，而不是显示「未收录」。
        val summary = logoSummary("laika", brands)
        assertEquals(LogoSummaryKind.Applied, summary.kind)
        assertEquals("Leica · 徕卡 · leica-m3", summary.title)
        assertEquals("laika", summary.brand?.key)
    }

    // -- 返回 -----------------------------------------------------------------

    @Test
    fun pickingACutReturnsToThePanelWithTheNewValue() {
        val picked = logoPickerResult(LogoPaneState.Landing, "laika_q")
        assertEquals(LogoPaneBody.Panel, picked.state.body)
        assertEquals("laika_q", picked.write)
        assertFalse(picked.openPicker)
        // 选中的那一枚就是面板接下来要说「当前：…」的那一枚。
        assertEquals("当前：Leica · 徕卡 · leica-q", logoCaption(logoSummary("laika_q", brands)))
    }

    @Test
    fun cancellingReturnsToThePanelAndWritesNothing() {
        listOf(null, "", "   ").forEach { cancelled ->
            val result = logoPickerResult(LogoPaneState.Landing, cancelled)
            assertEquals(LogoPaneBody.Panel, result.state.body, "picked=$cancelled")
            assertNull(result.write, "picked=$cancelled")
            assertFalse(result.openPicker, "picked=$cancelled")
        }
    }

    @Test
    fun pickingFromADeepBodyAlsoLandsBackOnThePanel() {
        // 覆盖层 / 版本页都可以从列表深处选：回来的是面板，不是列表。
        val deep = LogoPaneState(
            body = LogoPaneBody.LibraryList,
            browseBrandKey = "laika",
            versionPage = true,
        )
        val picked = logoPickCut(deep, "laika_white")
        assertEquals(LogoPaneBody.Panel, picked.state.body)
        assertFalse(picked.state.versionPage)
        assertEquals("laika_white", picked.write)
    }

    @Test
    fun backFromTheUserLibraryLandsOnThePanel() {
        val library = logoPanelAction(LogoPaneState.Landing, LogoPanelAction.User, phonePicker = true).state
        assertEquals(LogoPaneBody.UserLibrary, library.body)
        val back = logoPaneBack(library)
        assertEquals(LogoPaneBody.Panel, back.body)
        assertFalse(back.versionPage)
    }

    // -- 动作 -----------------------------------------------------------------

    @Test
    fun replaceIsTheOnlyWayIntoTheBrandLibrary() {
        // 手机端：只发「开覆盖层」的请求，正文不动（覆盖层关掉 / 选完都回得到面板）。
        val onPhone = logoPanelAction(LogoPaneState.Landing, LogoPanelAction.Replace, phonePicker = true)
        assertTrue(onPhone.openPicker)
        assertEquals(LogoPaneBody.Panel, onPhone.state.body)
        assertNull(onPhone.write)

        // 桌面端：进内联品牌页，同样不写值。
        val onDesktop = logoPanelAction(LogoPaneState.Landing, LogoPanelAction.Replace, phonePicker = false)
        assertFalse(onDesktop.openPicker)
        assertEquals(LogoPaneBody.LibraryList, onDesktop.state.body)
        assertNull(onDesktop.write)
        assertFalse(onDesktop.state.versionPage)
    }

    @Test
    fun brandPageNavigationNeverWritesTheAppliedValue() {
        val list = logoPanelAction(LogoPaneState.Landing, LogoPanelAction.Replace, phonePicker = false).state
        val versions = logoBrowseBrand(list, "laika")
        assertEquals(LogoPaneBody.LibraryList, versions.body)
        assertEquals("laika", versions.browseBrandKey)
        assertTrue(versions.versionPage)
        // 「返回品牌」只在列表内部后退。
        val back = logoVersionBack(versions)
        assertFalse(back.versionPage)
        assertEquals(LogoPaneBody.LibraryList, back.body)
    }

    @Test
    fun autoMatchWritesAutoAndStaysOnThePanel() {
        // 列表深处点「自动匹配」也回到面板；写入的就是取值域里的 `auto`。
        val deep = LogoPaneState(body = LogoPaneBody.LibraryList, versionPage = true)
        val result = logoPanelAction(deep, LogoPanelAction.Auto, phonePicker = true)
        assertEquals("auto", result.write)
        assertEquals(LOGO_SOURCE_AUTO, result.write)
        assertEquals(LogoPaneBody.Panel, result.state.body)
        assertFalse(result.state.versionPage)
        assertFalse(result.openPicker)
    }

    @Test
    fun myLogoOpensTheUserLibraryOnBothPlatforms() {
        listOf(true, false).forEach { phone ->
            val result = logoPanelAction(LogoPaneState.Landing, LogoPanelAction.User, phonePicker = phone)
            assertEquals(LogoPaneBody.UserLibrary, result.state.body, "phone=$phone")
            assertNull(result.write, "phone=$phone")
            // 本机库不是品牌库：这一下不开品牌覆盖层。
            assertFalse(result.openPicker, "phone=$phone")
        }
    }

    @Test
    fun everyPanelActionKeepsTheBodyReachable() {
        // 三个动作点完之后，正文永远是面板 / 品牌列表 / 用户库之一，没有第四种。
        listOf(true, false).forEach { phone ->
            LogoPanelAction.values().forEach { action ->
                val next = logoPanelAction(LogoPaneState.Landing, action, phone).state
                assertTrue(next.body in LogoPaneBody.values(), "$action phone=$phone")
                assertTrue(next.body != LogoPaneBody.LibraryList || !phone, "$action phone=$phone")
            }
        }
    }
}
