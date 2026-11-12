package com.framemark.native.shell

import com.framemark.native.inspector.ControlOption
import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.shell.phone.PhoneTestControls
import com.framemark.native.shell.phone.PhoneTouchTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 工具页版式政策（`PhonePageLayout.kt`）的验收 —— 第二版（2026-11「分页也不对，太散了」）。
 *
 *  1. **页数达标**：样式 ≤ 2、文字 1、Logo ≤ 2、水印 ≤ 3、厂商 水印位置 ≤ 2，五个
 *     carried 窗口逐个断言。页数会随窗口变（舞台地板许可不同），但**永远不超过**目标。
 *  2. **覆盖**：每一页的控件恰好出现一次，不多不少。
 *  3. **装得下**：每一页都在**这个窗口的许可**（[pageAllowanceForWindow]）里；分页用的
 *     预算与底板高度用的是同一个许可（`defaultSheetLevelFor` 的 levelBudget 必须装得下
 *     最高的那一页，否则底板会「越算越矮、页数反而变多」）。
 *  4. **触控不缩**：密度档每一行 ≥ 44 dp，断言直接读政策的高度常量。
 *  5. **量过的表**：[theDensifiedPageTableIsTheMeasuredOne] 把五个窗口的「页数 + 每页
 *     高度 + 底板高度」逐个钉住 —— 报告里的 before/after 表就是它。
 */
class PhonePageLayoutTest {

    // -----------------------------------------------------------------------
    // 预算常数：最紧的一档 carried 窗口
    // -----------------------------------------------------------------------

    @Test
    fun theTightestBudgetIsTheTightestCarriedWindow() {
        // 360×800 + 照片条：800 − 52 − 62 − 64 − 800·50% = 222 dp 底板；
        // 参数页 chrome（12 + 26）+ 公共区 34 之后还剩 150 dp 给一页。
        val available = 800f
        val compactMax = available - TOP_BAR_DP - TOOL_BAR_DP - STRIP_DP -
            available * STAGE_MIN_FRACTION
        assertEquals(222f, compactMax, 0.01f)
        assertEquals(38f, phoneParameterSheetChrome(compactMax).totalDp, 0.01f)
        assertEquals(
            PHONE_PAGE_GROUP_BUDGET_DP,
            pageAllowanceForWindow(available, photosOpen = true),
            0.01f,
            "默认预算必须等于最紧窗口的许可",
        )
        assertEquals(
            compactMax - phoneParameterSheetChrome(compactMax).totalDp - PHONE_PARAMETER_SUMMARY_DP,
            PHONE_PAGE_GROUP_BUDGET_DP,
            0.01f,
        )
        // 更宽的窗口许可更大，所以同一张卡在那里可能少一页。
        assertTrue(pageAllowanceForWindow(844f, photosOpen = false) > PHONE_PAGE_GROUP_BUDGET_DP)
        assertTrue(
            pageAllowanceForWindow(915f, photosOpen = false) >
                pageAllowanceForWindow(844f, photosOpen = false),
        )
    }

    // -----------------------------------------------------------------------
    // 每个控件恰好一次
    // -----------------------------------------------------------------------

    private fun keysOf(groups: List<List<String>>): List<String> = groups.flatten()

    private val anchor = PhoneTestControls.control(
        id = "params:stockAnchor",
        param = "params.stockAnchor",
        label = "水印位置",
        type = ControlType.Select,
        kind = "anchorGrid",
        initial = ControlValue.Str("leftTop"),
        options = listOf(
            "leftTop", "centerTop", "rightTop", "leftCenter", "center",
            "rightCenter", "leftBottom", "centerBottom", "rightBottom",
        ).map { ControlOption(it, it) },
    )

    @Test
    fun theStylePageCoversEveryControlExactlyOnce() {
        val controls = PhoneTestControls.styleControls()
        val groups = pageGroups(EditorTool.Params, controls)
        assertEquals(
            controls.map { it.key }.sorted(),
            keysOf(groups).sorted(),
            "样式页的分组必须覆盖每个控件且不重复",
        )
        assertTrue(groups.all { it.isNotEmpty() })
    }

    @Test
    fun theTextPageIsOnePageWithTheSelectorAndTheScale() {
        val controls = PhoneTestControls.textControls()
        val groups = pageGroups(EditorTool.Text, controls, hasTextReport = true)
        assertEquals(1, groups.size, "选择器 + 文字大小必须一页：$groups")
        assertEquals(
            listOf(PhonePageKeys.TEXT_SLOTS, "card::text:fontScale"),
            groups.single(),
        )
        // 没有渲染器报告的卡退回声明行：每一条都在分组里，再次恰好一次。
        // （7 行 = 5 个显示开关 + 1 行自定义文字 + 文字大小；这不是「一页」的目标形状，
        // 目标页数只对 Selector 形态成立。）
        val staticGroups = pageGroups(EditorTool.Text, controls, hasTextReport = false)
        assertEquals(controls.map { it.key }.sorted(), keysOf(staticGroups).sorted())
        assertTrue(staticGroups.size <= 3, "static rows: ${staticGroups.size} pages")
    }

    @Test
    fun theLogoPageCoversEveryAdjustmentAndTheActionsOnce() {
        val controls = PhoneTestControls.logoControls()
        val groups = pageGroups(EditorTool.Logo, controls)
        assertEquals(controls.map { it.key }.sorted(), keysOf(groups).sorted())
        assertEquals("card::design.logo", groups.last().single(), "三个动作单独一页，排最后")
        // 一条滑杆一行之外，两条滑杆并成一行：四行调整 = 开关 / 位置 / 大小+水平位置。
        val atReference = pageGroups(EditorTool.Logo, controls, budgetDp = 222f)
        assertEquals(1, atReference.size, "390×844 的许可里 Logo 一整页：$atReference")
        val atTightest = pageGroups(EditorTool.Logo, controls, budgetDp = 150f)
        assertEquals(2, atTightest.size, "最紧窗口两页：$atTightest")
    }

    @Test
    fun theWatermarkPageGroupsLayersPositionAndAppearance() {
        val atReference = pageGroups(EditorTool.Watermark, emptyList(), budgetDp = 222f)
        assertEquals(
            listOf(
                listOf(PhonePageKeys.WATERMARK_LAYER, PhonePageKeys.WATERMARK_POSITION),
                listOf(
                    PhonePageKeys.WATERMARK_MARGINS,
                    PhonePageKeys.WATERMARK_WIDTH,
                    PhonePageKeys.WATERMARK_APPEARANCE,
                ),
            ),
            atReference,
            "390×844：图层+位置 | 边距+宽度+外观",
        )
        val atTightest = pageGroups(EditorTool.Watermark, emptyList(), budgetDp = 150f)
        assertEquals(3, atTightest.size, "最紧窗口三页：$atTightest")
        assertEquals(
            listOf(PhonePageKeys.WATERMARK_LAYER),
            atTightest.first(),
        )
        // 空态是一整页（说明 + 上传），不是 5 个空组。
        assertEquals(
            listOf(PhonePageKeys.WATERMARK_EMPTY),
            keysOf(pageGroups(EditorTool.Watermark, emptyList(), hasWatermarkLayer = false)),
        )
    }

    @Test
    fun aVendorAnchorGridAndItsSpecAreTwoPagesThatBothFit() {
        val groups = pageGroups(EditorTool.Params, listOf(anchor), isIr = true, budgetDp = 150f)
        assertEquals(2, groups.size, "九宫格一页、按规格+只读规格一页：$groups")
        assertEquals(listOf(PhonePageKeys.ANCHOR_GRID), groups.first())
        assertEquals(
            listOf(PhonePageKeys.ANCHOR_FOOTER, PhonePageKeys.SPEC),
            groups.last(),
            "「按规格」与只读规格合在最后一页",
        )
        val heights = pageGroupHeights(EditorTool.Params, listOf(anchor), isIr = true, budgetDp = 150f)
        assertEquals(listOf(140f, 142f), heights, "两页都在 150 dp 以内")
        // 更宽的窗口里，九宫格与「按规格」一页、规格一页。
        val roomy = pageGroups(EditorTool.Params, listOf(anchor), isIr = true, budgetDp = 222f)
        assertEquals(
            listOf(
                listOf(PhonePageKeys.ANCHOR_GRID, PhonePageKeys.ANCHOR_FOOTER),
                listOf(PhonePageKeys.SPEC),
            ),
            roomy,
        )
    }

    // -----------------------------------------------------------------------
    // 每一页在每一个 carried 窗口都放得下，页数不超过目标
    // -----------------------------------------------------------------------

    private data class Window(val width: Float, val height: Float, val photosOpen: Boolean) {
        override fun toString(): String = "${width}x$height${if (photosOpen) "+strip" else ""}"
    }

    private val carriedWindows = listOf(
        Window(390f, 844f, false),
        Window(360f, 800f, false),
        Window(412f, 915f, false),
        Window(886f, 886f, false),
        Window(360f, 800f, true),
    )

    /** 任务书的目标页数（五个窗口里最差的一档也不能超过）。 */
    private val targets = mapOf(
        "样式" to 2,
        "文字" to 1,
        "Logo" to 2,
        "水印" to 3,
        "厂商水印位置" to 2,
    )

    private data class Page(val name: String, val spec: PhonePageSpec)

    private val pages = listOf(
        Page("样式", PhonePageSpec(EditorTool.Params, PhoneTestControls.styleControls())),
        Page("文字", PhonePageSpec(EditorTool.Text, PhoneTestControls.textControls(), hasTextReport = true)),
        Page("Logo", PhonePageSpec(EditorTool.Logo, PhoneTestControls.logoControls())),
        Page("水印", PhonePageSpec(EditorTool.Watermark, emptyList())),
        Page("厂商水印位置", PhonePageSpec(EditorTool.Params, listOf(anchor), isIr = true)),
    )

    @Test
    fun everyPageHitsItsTargetOnEveryCarriedWindow() {
        carriedWindows.forEach { window ->
            pages.forEach { page ->
                val budget = pageAllowanceForWindow(window.height, window.photosOpen)
                val groups = pageGroups(page.spec, 1f, budget)
                val target = targets.getValue(page.name)
                assertTrue(
                    groups.size <= target,
                    "${page.name} @ $window: ${groups.size} pages > target $target",
                )
                assertTrue(groups.all { it.isNotEmpty() })
            }
        }
    }

    @Test
    fun everyPageFitsTheWindowAllowanceAndTheDecidedLevel() {
        carriedWindows.forEach { window ->
            pages.forEach { page ->
                val budget = pageAllowanceForWindow(window.height, window.photosOpen)
                val heights = pageGroupHeights(page.spec, 1f, budget)
                heights.forEach { height ->
                    assertTrue(
                        height <= budget + 0.01f,
                        "${page.name} @ $window: page $height > allowance $budget",
                    )
                }
                val level = assertNotNull(
                    defaultSheetLevelFor(page.spec, window.height, window.photosOpen),
                    "${page.name} @ $window: the window must leave a usable level",
                )
                assertTrue(level >= MIN_SHEET_DP - 0.01f, "${page.name} @ $window: level=$level < floor")
                // 底板高度必须装得下**这个分组**里最高的一页：分页与开多高读同一个许可。
                assertTrue(
                    pageBudgetForLevel(level) >= heights.max() - 0.01f,
                    "${page.name} @ $window: level=$level holds " +
                        "${pageBudgetForLevel(level)} < tallest ${heights.max()}",
                )
                // 舞台 50 % 地板照旧。
                val stage = window.height - TOP_BAR_DP - TOOL_BAR_DP -
                    (if (window.photosOpen) STRIP_DP else 0f) - level
                assertTrue(stage >= window.height * STAGE_MIN_FRACTION - 0.01f, "${page.name} @ $window: stage=$stage")
            }
        }
    }

    /** 报告里的 after 表：每页高度 + 这一页的底板高度。 */
    private val densifiedTable: Map<String, Pair<List<Float>, Float>> = mapOf(
        "390x844|样式" to (listOf(137f, 100f) to 209f),
        "390x844|文字" to (listOf(148f) to 220f),
        "390x844|Logo" to (listOf(217f) to 303f),
        "390x844|水印" to (listOf(188f, 148f) to 274f),
        "390x844|厂商水印位置" to (listOf(210f, 72f) to 296f),
        "360x800|样式" to (listOf(137f, 100f) to 209f),
        "360x800|文字" to (listOf(148f) to 220f),
        "360x800|Logo" to (listOf(148f, 73f) to 220f),
        "360x800|水印" to (listOf(188f, 148f) to 274f),
        "360x800|厂商水印位置" to (listOf(140f, 142f) to 214f),
        "412x915|样式" to (listOf(233f) to 319f),
        "412x915|文字" to (listOf(148f) to 220f),
        "412x915|Logo" to (listOf(217f) to 303f),
        "412x915|水印" to (listOf(188f, 148f) to 274f),
        "412x915|厂商水印位置" to (listOf(210f, 72f) to 296f),
        "886x886|样式" to (listOf(233f) to 319f),
        "886x886|文字" to (listOf(148f) to 220f),
        "886x886|Logo" to (listOf(217f) to 303f),
        "886x886|水印" to (listOf(188f, 148f) to 274f),
        "886x886|厂商水印位置" to (listOf(210f, 72f) to 296f),
        "360x800+strip|样式" to (listOf(137f, 100f) to 209f),
        "360x800+strip|文字" to (listOf(148f) to 220f),
        "360x800+strip|Logo" to (listOf(148f, 73f) to 220f),
        "360x800+strip|水印" to (listOf(52f, 140f, 148f) to 220f),
        "360x800+strip|厂商水印位置" to (listOf(140f, 142f) to 214f),
    )

    @Test
    fun theDensifiedPageTableIsTheMeasuredOne() {
        carriedWindows.forEach { window ->
            pages.forEach { page ->
                val key = "${window.width.toInt()}x${window.height.toInt()}" +
                    (if (window.photosOpen) "+strip" else "") + "|${page.name}"
                val expected = assertNotNull(densifiedTable[key], "no measured row for $key")
                val budget = pageAllowanceForWindow(window.height, window.photosOpen)
                val heights = pageGroupHeights(page.spec, 1f, budget)
                assertEquals(expected.first, heights, "$key heights")
                val level = assertNotNull(defaultSheetLevelFor(page.spec, window.height, window.photosOpen))
                assertEquals(expected.second, level, 0.01f, "$key compact level")
            }
        }
    }

    // -----------------------------------------------------------------------
    // 触控地板：密度档的每一行 ≥ 44 dp
    // -----------------------------------------------------------------------

    @Test
    fun theDensifiedRowsKeepThe44DpTouchFloor() {
        assertEquals(44f, PHONE_PAGE_MIN_TOUCH_DP)
        assertEquals(PhoneTouchTarget.value, PHONE_PAGE_MIN_TOUCH_DP, "与 PhoneTouchTarget 同一个数")
        listOf(
            "slider" to PHONE_PAGE_SLIDER_ROW_DP,
            "slider pair" to PHONE_PAGE_SLIDER_PAIR_ROW_DP,
            "segment" to PHONE_PAGE_SEGMENT_DP,
            "switch" to phoneControlBlockHeightDp(
                PhoneTestControls.switch(id = "params:showLogo", label = "显示 Logo"),
            ),
            "logo actions" to PHONE_PAGE_LOGO_ACTIONS_DP,
            "watermark layer" to PHONE_PAGE_WATERMARK_LAYER_DP,
            "watermark margins" to PHONE_PAGE_WATERMARK_ROW_DP,
            "watermark appearance" to PHONE_PAGE_WATERMARK_ROW_DP,
            "anchor footer" to PHONE_PAGE_ROW_DP,
        ).forEach { (name, height) ->
            assertTrue(
                height >= PHONE_PAGE_MIN_TOUCH_DP,
                "$name row is ${height}dp < ${PHONE_PAGE_MIN_TOUCH_DP}dp touch floor",
            )
        }
        // Logo 的每一行（开关 / 位置 / 滑杆）都在地板之上。
        PhoneTestControls.logoControls()
            .filterNot { it.type == ControlType.Brand }
            .forEach { control ->
                assertTrue(
                    logoControlHeightDp(control) >= PHONE_PAGE_MIN_TOUCH_DP,
                    "Logo row for ${control.key}",
                )
            }
        // 色板一格（38 圆 + 5 + 16 标签）比地板高。
        assertTrue(swatchBlockHeightDp(6) >= PHONE_PAGE_MIN_TOUCH_DP)
        assertTrue(PHONE_PAGE_SWATCH_ROW_DP >= PHONE_PAGE_MIN_TOUCH_DP)
    }

    @Test
    fun theWatermarkPageIsFiveRowsAtTheirDesignHeights() {
        // 一页的行高之和就是各行的常量之和：没有任何一行被压扁（policy 表读的就是常量）。
        val spec = PhonePageSpec(EditorTool.Watermark, emptyList())
        val blocks = pageBlocks(spec)
        assertEquals(
            listOf(
                PHONE_PAGE_WATERMARK_LAYER_DP,
                PHONE_PAGE_WATERMARK_GRID_DP,
                PHONE_PAGE_WATERMARK_ROW_DP,
                PHONE_PAGE_WATERMARK_ROW_DP,
                PHONE_PAGE_WATERMARK_ROW_DP,
            ),
            blocks.map { it.heightDp },
        )
    }

    // -----------------------------------------------------------------------
    // 高度由内容决定；放不下就回退整页
    // -----------------------------------------------------------------------

    @Test
    fun aShortPageOpensShorterThanALongPage() {
        val light = assertNotNull(
            defaultSheetLevelFor(EditorTool.Params, PhoneTestControls.lightStyleControls(), 844f, photosOpen = false),
        )
        val typical = assertNotNull(
            defaultSheetLevelFor(EditorTool.Params, PhoneTestControls.styleControls(), 844f, photosOpen = false),
        )
        val tall = assertNotNull(
            defaultSheetLevelFor(EditorTool.Logo, PhoneTestControls.logoControls(), 844f, photosOpen = false),
        )
        // 一页只有底色（59 + 8）→ 139；样式页两页（最高 137）→ 209；Logo 一整页（217）→ 303。
        assertEquals(139f, light, 0.01f)
        assertEquals(209f, typical, 0.01f)
        assertEquals(303f, tall, 0.01f)
        assertTrue(light < typical, "light=$light typical=$typical")
        assertTrue(typical < tall, "typical=$typical tall=$tall")
        // 最紧窗口（360×800）里 Logo 换成两页，底板反而更矮 —— 页数多的那一档不靠「开更高」。
        val logoSmall = assertNotNull(
            defaultSheetLevelFor(EditorTool.Logo, PhoneTestControls.logoControls(), 800f, photosOpen = false),
        )
        assertEquals(220f, logoSmall, 0.01f)
    }

    @Test
    fun theLevelLeavesRoomForTheTallestPagePlusChromeAndTheSummaryBand() {
        val spec = PhonePageSpec(EditorTool.Params, PhoneTestControls.styleControls())
        val level = assertNotNull(defaultSheetLevelFor(spec, 886f, photosOpen = false))
        val chrome = phoneParameterSheetChrome(level)
        val tallest = pageGroupHeights(spec, 1f, pageAllowanceForWindow(886f, photosOpen = false)).max()
        val pageBudget = level - chrome.totalDp - PHONE_PARAMETER_SUMMARY_DP
        assertTrue(
            pageBudget >= tallest - 0.01f,
            "page budget $pageBudget < tallest page $tallest",
        )
        // 呼吸留白只有 6 dp，多出来的部分已经被 chrome 阶梯吃掉，不允许再开高。
        assertTrue(pageBudget <= tallest + PHONE_PAGE_LEVEL_SLACK_DP + 0.01f, "page budget $pageBudget")
        assertEquals(233f, tallest, 0.01f)
    }

    @Test
    fun aWindowTooTallForAUsableSheetFallsBackInsteadOfScrolling() {
        // 430 dp 窗口里舞台地板只允许 101 dp 底板（< MIN_SHEET_DP）→ null（整页回退），
        // 而不是裁剪或滚动。加密之后 640 / 560 都已经装得下，所以门槛也随之下移。
        val controls = PhoneTestControls.logoControls()
        assertNull(defaultSheetLevelFor(EditorTool.Logo, controls, 430f, photosOpen = false))
        assertTrue(
            computePhoneEditorHeights(
                availableHeightDp = 430f,
                fontScale = 1f,
                imeVisible = false,
                tool = EditorTool.Logo,
                photosOpen = false,
                controls = controls,
            ).fullPage,
        )
        assertNotNull(defaultSheetLevelFor(EditorTool.Logo, controls, 520f, photosOpen = false))
    }

    @Test
    fun aLargerFontScaleIsCountedInTheLevel() {
        val controls = PhoneTestControls.textControls()
        val normal = assertNotNull(defaultSheetLevelFor(EditorTool.Text, controls, 844f, false, hasTextReport = true))
        val large = assertNotNull(
            defaultSheetLevelFor(
                tool = EditorTool.Text,
                controls = controls,
                availableHeightDp = 844f,
                photosOpen = false,
                hasTextReport = true,
                fontScale = 1.3f,
            ),
        )
        assertTrue(large > normal, "normal=$normal large=$large")
        assertTrue(
            fitsWithoutScroll(EditorTool.Text, controls, large, hasTextReport = true, fontScale = 1.3f),
            "the scaled level must still hold the scaled pages",
        )
    }

    @Test
    fun thePhotoStripShortensTheWatermarkSheetBecauseTheAllowanceShrinks() {
        val closed = assertNotNull(defaultSheetLevelFor(EditorTool.Watermark, emptyList(), 800f, false))
        val open = assertNotNull(defaultSheetLevelFor(EditorTool.Watermark, emptyList(), 800f, true))
        // 没有照片条：许可 206 → 两页（最高 188）→ 274。
        assertEquals(274f, closed, 0.01f)
        // 照片条打开：许可 150 → 三页（最高 148）→ 220。底板因此更矮，不是更高。
        assertEquals(220f, open, 0.01f)
        assertTrue(open < closed, "open=$open closed=$closed")
        assertFalse(
            pageGroups(EditorTool.Watermark, emptyList(), budgetDp = 150f).size ==
                pageGroups(EditorTool.Watermark, emptyList(), budgetDp = 222f).size,
        )
    }
}
