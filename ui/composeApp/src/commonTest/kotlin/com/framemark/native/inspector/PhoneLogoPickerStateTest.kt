package com.framemark.native.inspector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 手机端品牌选择器的纯状态契约（无 Compose、无资源）：
 * 分类过滤与计数、两页阶段切换，以及「一个版本的每一枚 cut 都可选」——
 * 最后这条是回归：内联版本页用 `cuts.firstOrNull()`，白色 cut 永远点不到。
 */
class PhoneLogoPickerStateTest {

    private fun cut(assetKey: String, ink: String = "") =
        LogoCut(assetKey = assetKey, file = "$assetKey.webp", preview = "${assetKey}__preview.webp", ink = ink)

    private fun version(
        id: String,
        cuts: List<LogoCut>,
        era: String = "current",
        years: String = "2010–2020",
    ) = LogoVersion(id = id, name = id, era = era, years = years, surface = "light", cuts = cuts)

    private fun brand(
        key: String,
        group: String,
        versions: List<LogoVersion>,
        label: String = key.uppercase(),
        cn: String = "$key 中文",
    ) = LogoBrand(
        key = key,
        label = label,
        cn = cn,
        group = group,
        canonicalVersion = versions.firstOrNull()?.id,
        versions = versions,
    )

    /** 黑色/白色两枚 cut 属于同一个版本——就是内联页丢掉的那一对。 */
    private val canon = brand(
        key = "canon",
        group = "camera",
        label = "Canon",
        cn = "佳能",
        versions = listOf(
            version("canon-1990", listOf(cut("canon_black", "dark"), cut("canon_white", "light")), era = "classic"),
            version("canon-2020", listOf(cut("canon_2020", "color"))),
        ),
    )

    private val vivo = brand(
        key = "vivo",
        group = "phone",
        label = "vivo",
        cn = "维沃",
        versions = listOf(version("vivo-2020", listOf(cut("vivo_2020")))),
    )

    private val oppo = brand(
        key = "oppo",
        group = "phone",
        label = "OPPO",
        cn = "欧珀",
        versions = listOf(version("oppo-2019", listOf(cut("oppo_2019")))),
    )

    private val brands = listOf(canon, vivo, oppo)

    // -- 分类行 ---------------------------------------------------------------

    @Test
    fun categoryRowIsAllPhoneCameraWithCountsFromTheData() {
        val categories = PhoneLogoPickerState.categories(brands)
        // 产品决定：全部 / 手机 / 相机（手机在前），数据里只有这两个分组。
        assertEquals(listOf("全部", "手机", "相机"), categories.map { it.label })
        assertEquals(listOf("all", "phone", "camera"), categories.map { it.key })
        assertEquals(listOf(3, 2, 1), categories.map { it.count })
    }

    @Test
    fun categoryCountsAreComputedInsteadOfHardCoded() {
        // 只有手机时「相机」必须是 0，而不是照抄截图里的 47/16。
        val phonesOnly = PhoneLogoPickerState.categories(listOf(vivo, oppo))
        assertEquals(listOf(2, 2, 0), phonesOnly.map { it.count })
        val camerasOnly = PhoneLogoPickerState.categories(listOf(canon))
        assertEquals(listOf(1, 0, 1), camerasOnly.map { it.count })
        assertEquals(listOf(0, 0, 0), PhoneLogoPickerState.categories(emptyList()).map { it.count })
    }

    @Test
    fun categoryFilterKeepsOnlyThatGroupAndAllKeepsTheCatalogOrder() {
        assertEquals(listOf("canon"), PhoneLogoPickerState.brands(brands, "camera").map { it.key })
        assertEquals(listOf("vivo", "oppo"), PhoneLogoPickerState.brands(brands, "phone").map { it.key })
        assertEquals(
            listOf("canon", "vivo", "oppo"),
            PhoneLogoPickerState.brands(brands, PhoneLogoPickerState.CATEGORY_ALL).map { it.key },
        )
    }

    @Test
    fun anUnknownGroupMatchesNothingSoNoInventedCategoryCanLeakIn() {
        assertTrue(PhoneLogoPickerState.brands(brands, "retro-camera").isEmpty())
        assertTrue(PhoneLogoPickerState.brands(brands, "film").isEmpty())
    }

    // -- 搜索 -----------------------------------------------------------------

    @Test
    fun searchMatchesTheEnglishLabelAndTheChineseNameCaseInsensitively() {
        assertEquals(listOf("canon"), PhoneLogoPickerState.searchBrands(brands, "can").map { it.key })
        assertEquals(listOf("canon"), PhoneLogoPickerState.searchBrands(brands, "CANON").map { it.key })
        assertEquals(listOf("canon"), PhoneLogoPickerState.searchBrands(brands, "佳能").map { it.key })
        assertEquals(listOf("vivo"), PhoneLogoPickerState.searchBrands(brands, "  ViVo  ").map { it.key })
        // 目录键（laika 这类）不参与搜索：搜索只认品牌 label / cn。
        assertTrue(PhoneLogoPickerState.searchBrands(brands, "laika").isEmpty())
    }

    @Test
    fun blankSearchKeepsTheWholeList() {
        assertEquals(brands.map { it.key }, PhoneLogoPickerState.searchBrands(brands, "").map { it.key })
        assertEquals(brands.map { it.key }, PhoneLogoPickerState.searchBrands(brands, "   ").map { it.key })
    }

    @Test
    fun searchAndCategoryFilterCompose() {
        assertEquals(
            listOf("vivo"),
            PhoneLogoPickerState.filteredBrands(brands, "phone", "viv").map { it.key },
        )
        assertTrue(PhoneLogoPickerState.filteredBrands(brands, "camera", "vivo").isEmpty())
        assertEquals(
            listOf("canon"),
            PhoneLogoPickerState.filteredBrands(brands, PhoneLogoPickerState.CATEGORY_ALL, "佳").map { it.key },
        )
    }

    @Test
    fun changingTheQueryDoesNotMoveThePager() {
        val model = PhoneLogoPickerState.pickQuery(PhoneLogoPickerState.initial(), "can")
        assertEquals("can", model.query)
        assertEquals(PhoneLogoPickerStage.Brands, model.stage)
    }

    // -- 最近使用（MRU） -------------------------------------------------------

    @Test
    fun recordRecentMovesToFrontDedupesAndCaps() {
        assertEquals(listOf("canon"), PhoneLogoPickerState.recordRecent(emptyList(), "canon"))
        assertEquals(listOf("vivo", "canon"), PhoneLogoPickerState.recordRecent(listOf("canon"), "vivo"))
        // 重复使用同一个品牌不会产生第二条，只把它提到最前。
        assertEquals(
            listOf("canon", "vivo"),
            PhoneLogoPickerState.recordRecent(listOf("canon", "vivo"), "canon"),
        )
        val capped = PhoneLogoPickerState.recordRecent(listOf("canon", "vivo", "oppo"), "oppo")
        assertEquals(listOf("oppo", "canon", "vivo"), capped)
        assertEquals(PhoneLogoPickerState.RECENT_CAPACITY, capped.size)
        assertEquals(listOf("oppo"), PhoneLogoPickerState.recordRecent(emptyList(), "oppo", capacity = 1))
        assertEquals(listOf("canon"), PhoneLogoPickerState.recordRecent(listOf("canon"), "   "))
    }

    @Test
    fun recentBrandsKeepTheMruOrderAndDropUnknownKeys() {
        assertEquals(
            listOf("vivo", "canon"),
            PhoneLogoPickerState.recentBrands(brands, listOf("vivo", "ghost", "canon")).map { it.key },
        )
        assertTrue(PhoneLogoPickerState.recentBrands(brands, emptyList()).isEmpty())
    }

    // -- 阶段切换 -------------------------------------------------------------

    @Test
    fun thePickerStartsOnTheBrandsPage() {
        val model = PhoneLogoPickerState.initial()
        assertEquals(PhoneLogoPickerStage.Brands, model.stage)
        assertEquals(0, PhoneLogoPickerState.pageForStage(model.stage))
        assertNull(model.brandKey)
        assertNull(model.cutKey)
    }

    @Test
    fun tappingABrandMovesToTheStylesPage() {
        val model = PhoneLogoPickerState.pickBrand(PhoneLogoPickerState.initial(), canon, "")
        assertEquals(PhoneLogoPickerStage.Styles, model.stage)
        assertEquals(1, PhoneLogoPickerState.pageForStage(model.stage))
        assertEquals("canon", model.brandKey)
    }

    @Test
    fun swipingBackToTheLeftPageReturnsToTheBrandsStage() {
        val styles = PhoneLogoPickerState.pickBrand(PhoneLogoPickerState.initial(), canon, "")
        val back = PhoneLogoPickerState.onPage(styles, 0)
        assertEquals(PhoneLogoPickerStage.Brands, back.stage)
        assertEquals(0, PhoneLogoPickerState.pageForStage(back.stage))
        // 品牌与 cut 保留：再划回第 2 页还是同一个品牌。
        assertEquals("canon", back.brandKey)
        assertTrue(back.cutKey != null)
    }

    @Test
    fun theTwoPagerPagesMapToTheTwoStages() {
        assertEquals(PhoneLogoPickerState.PAGE_COUNT, 2)
        assertEquals(PhoneLogoPickerStage.Brands, PhoneLogoPickerState.stageForPage(0))
        assertEquals(PhoneLogoPickerStage.Styles, PhoneLogoPickerState.stageForPage(1))
        assertEquals(1, PhoneLogoPickerState.pageForStage(PhoneLogoPickerStage.Styles))
    }

    @Test
    fun thePage2BackActionOnlyFlipsTheStage() {
        val styles = PhoneLogoPickerState.pickBrand(PhoneLogoPickerState.initial(), canon, "canon_white")
        val back = PhoneLogoPickerState.backToBrands(styles)
        assertEquals(PhoneLogoPickerStage.Brands, back.stage)
        assertEquals("canon_white", back.cutKey)
    }

    @Test
    fun forwardDraggingIsOnlyPossibleAfterABrandWasOpened() {
        assertFalse(PhoneLogoPickerState.canDrag(PhoneLogoPickerState.initial()))
        val styles = PhoneLogoPickerState.pickBrand(PhoneLogoPickerState.initial(), canon, "")
        assertTrue(PhoneLogoPickerState.canDrag(styles))
        // 划回品牌页之后仍可再划到款式页（品牌没丢）。
        assertTrue(PhoneLogoPickerState.canDrag(PhoneLogoPickerState.onPage(styles, 0)))
    }

    @Test
    fun pickingACategoryDoesNotMoveThePager() {
        val model = PhoneLogoPickerState.pickCategory(PhoneLogoPickerState.initial(), "phone")
        assertEquals("phone", model.categoryKey)
        assertEquals(PhoneLogoPickerStage.Brands, model.stage)
    }

    // -- 每一枚 cut 都可选（回归） --------------------------------------------

    @Test
    fun everyCutOfAVersionIsReachableOnTheStylesPage() {
        val rows = PhoneLogoPickerState.styleRows(canon)
        // 两个版本三枚 cut：黑色、白色、2020 彩标。第一版会把白色丢掉。
        assertEquals(listOf("canon_black", "canon_white", "canon_2020"), rows.map { it.cut.assetKey })
        assertEquals(listOf(1, 2, 1), rows.map { it.variantIndex })
        assertEquals(listOf(2, 2, 1), rows.map { it.variantCount })
    }

    @Test
    fun theWhiteCutOfAPairCanBeSelectedAndApplied() {
        val styles = PhoneLogoPickerState.pickBrand(PhoneLogoPickerState.initial(), canon, "")
        val white = PhoneLogoPickerState.pickCut(styles, "canon_white")
        assertEquals("canon_white", white.cutKey)
        assertEquals("canon_white", PhoneLogoPickerState.selectionValue(white, canon))
        // 不是第一枚 cut，也不是版本 id / 品牌键。
        assertNotEquals(PhoneLogoPickerState.styleRows(canon).first().cut.assetKey, white.cutKey)
        assertNotEquals("canon", PhoneLogoPickerState.selectionValue(white, canon))
        assertNotEquals("canon-1990", PhoneLogoPickerState.selectionValue(white, canon))
    }

    @Test
    fun theAppliedValueIsTheExactCutAssetKey() {
        val picked = PhoneLogoPickerState.pickCut(
            PhoneLogoPickerState.pickBrand(PhoneLogoPickerState.initial(), canon, ""),
            "canon_2020",
        )
        assertEquals("canon_2020", PhoneLogoPickerState.selectionValue(picked, canon))
    }

    @Test
    fun aCutThatIsNotInTheBrandIsNeverApplied() {
        val picked = PhoneLogoPickerState.pickCut(
            PhoneLogoPickerState.pickBrand(PhoneLogoPickerState.initial(), canon, ""),
            "vivo_2020",
        )
        assertNull(PhoneLogoPickerState.selectionValue(picked, canon))
        assertNull(
            PhoneLogoPickerState.selectionValue(
                PhoneLogoPickerState.initial().copy(stage = PhoneLogoPickerStage.Styles, cutKey = null),
                canon,
            ),
        )
    }

    @Test
    fun pickingACutIsIgnoredWhileStillOnTheBrandsPage() {
        val model = PhoneLogoPickerState.pickCut(PhoneLogoPickerState.initial(), "canon_white")
        assertNull(model.cutKey)
    }

    // -- 预选 -----------------------------------------------------------------

    @Test
    fun openingABrandPreselectsTheCutAlreadyInUse() {
        val model = PhoneLogoPickerState.pickBrand(PhoneLogoPickerState.initial(), canon, "canon_white")
        assertEquals("canon_white", model.cutKey)
    }

    @Test
    fun openingABrandWithoutAnAppliedCutFallsBackToItsCanonicalCut() {
        // "" / "auto" / 别的品牌的 cut 都落到品牌代表字标（canonical 版本的第一枚），
        // 也就是内联版本页点品牌后实际会写入的那一枚。
        assertEquals(
            "canon_black",
            PhoneLogoPickerState.pickBrand(PhoneLogoPickerState.initial(), canon, "").cutKey,
        )
        assertEquals(
            "canon_black",
            PhoneLogoPickerState.pickBrand(PhoneLogoPickerState.initial(), canon, "auto").cutKey,
        )
        assertEquals(
            "canon_black",
            PhoneLogoPickerState.pickBrand(PhoneLogoPickerState.initial(), canon, "vivo_2020").cutKey,
        )
    }

    @Test
    fun reopeningTheSameBrandKeepsTheChosenCut() {
        val pickedWhite = PhoneLogoPickerState.pickCut(
            PhoneLogoPickerState.pickBrand(PhoneLogoPickerState.initial(), canon, ""),
            "canon_white",
        )
        val back = PhoneLogoPickerState.onPage(pickedWhite, 0)
        val again = PhoneLogoPickerState.pickBrand(back, canon, "")
        assertEquals("canon_white", again.cutKey)
        // 换品牌才重新预选。
        val other = brand(
            key = "sony",
            group = "camera",
            versions = listOf(version("sony-1999", listOf(cut("sony_1999")))),
        )
        assertEquals("sony_1999", PhoneLogoPickerState.pickBrand(again, other, "").cutKey)
    }

    @Test
    fun toneLabelsFollowTheCatalogInkField() {
        assertEquals("黑标", PhoneLogoPickerState.toneLabel(cut("a", "dark")))
        assertEquals("白标", PhoneLogoPickerState.toneLabel(cut("a", "light")))
        assertEquals("彩标", PhoneLogoPickerState.toneLabel(cut("a", "color")))
        assertEquals("字标", PhoneLogoPickerState.toneLabel(cut("a")))
        assertEquals("字标", PhoneLogoPickerState.toneLabel(cut("a", "ambiguous")))
    }

    @Test
    fun thePickerPanelLeavesTheTopOfTheStageVisible() {
        // 选择器是「盖在画布上的一层」，不是新页面：面板 85–90 %，上面那条带就是
        // FmPalette.scrim 下的舞台。容器高度是产品决定，测试只钉住这个区间。
        assertTrue(
            PHONE_LOGO_PICKER_PANEL_FRACTION >= 0.85f,
            "面板不能比 85 % 更矮（放不下网格/款式页的固定 chrome）：$PHONE_LOGO_PICKER_PANEL_FRACTION",
        )
        assertTrue(
            PHONE_LOGO_PICKER_PANEL_FRACTION <= 0.90f,
            "面板不能超过 90 %，否则舞台顶部那条可见的画布就没了",
        )
        assertTrue(PHONE_LOGO_PICKER_PANEL_FRACTION < 1f, "面板不能铺满：那样又变成整页了")
    }
}
