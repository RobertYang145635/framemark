package com.framemark.native.shell

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.shell.phone.PhoneTestControls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 工具页版式政策（`PhonePageLayout.kt`）的验收：
 *
 *  1. **参数不滚**：每一页的每一组都在 `PHONE_PAGE_GROUP_BUDGET_DP` 以内，而且每页
 *     在把它分页的那个底板高度里放得下（四页 × 五个 carried 窗口）。
 *  2. **分组覆盖**：每一页的控件恰好出现在一组里，一次不多一次不少。
 *  3. **高度由内容决定**：矮页比高页矮，永远不低于 120 dp 的绝对下限、不高于舞台
 *     50 % 地板给出的上限；放不进时返回 null（走整页回退），而不是偷偷加滚动条。
 *  4. **预算常数的来路**：150 dp 是用「360×800 + 照片条打开」这一档重新推出来的，
 *     改 chrome 阶梯或舞台地板而忘了改它，这条测试先报错。
 */
class PhonePageLayoutTest {

    // -----------------------------------------------------------------------
    // 预算常数：最紧的一档 carried 窗口
    // -----------------------------------------------------------------------

    @Test
    fun theGroupBudgetIsTheTightestCarriedViewport() {
        // 360×800 + 照片条：800 − 52 − 62 − 64 − 800·50% = 222 dp 底板；
        // 参数页 chrome（12 + 26）+ 公共区 34 之后还剩 150 dp 给一页。
        val available = 800f
        val chrome = TOP_BAR_DP + TOOL_BAR_DP + STRIP_DP
        val compactMax = available - chrome - available * STAGE_MIN_FRACTION
        assertEquals(222f, compactMax, 0.01f)
        val sheetChrome = phoneParameterSheetChrome(compactMax)
        assertEquals(38f, sheetChrome.totalDp, 0.01f)
        assertEquals(
            PHONE_PAGE_GROUP_BUDGET_DP,
            compactMax - sheetChrome.totalDp - PHONE_PARAMETER_SUMMARY_DP,
            0.01f,
            "预算必须等于最紧窗口留给一页的净高",
        )
    }

    // -----------------------------------------------------------------------
    // 分组覆盖：每个控件恰好一次
    // -----------------------------------------------------------------------

    private fun keysOf(groups: List<List<String>>): List<String> = groups.flatten()

    @Test
    fun theStylePageGroupsCoverEveryControlExactlyOnce() {
        val controls = PhoneTestControls.styleControls()
        val groups = pageGroups(EditorTool.Params, controls)
        // 页面顺序是「底色 → 版式 → 其余」，所以比集合而不是比声明顺序。
        assertEquals(
            controls.map { it.key }.sorted(),
            keysOf(groups).sorted(),
            "样式页的分组必须覆盖每个控件且不重复",
        )
        assertTrue(groups.all { it.isNotEmpty() })
    }

    @Test
    fun theTextPageGroupsTheSelectorAndTheScaleOnTheirOwnPages() {
        val controls = PhoneTestControls.textControls()
        val groups = pageGroups(EditorTool.Text, controls, hasTextReport = true)
        assertEquals(2, groups.size, "选择器一页、文字大小一页：$groups")
        assertTrue(PhonePageKeys.TEXT_SLOTS in groups[0])
        assertEquals(listOf("card::text:fontScale"), groups[1])
        // 没有渲染器报告的卡退回声明行：每一条都在分组里，再次恰好一次。
        val staticGroups = pageGroups(EditorTool.Text, controls, hasTextReport = false)
        assertEquals(controls.map { it.key }.sorted(), keysOf(staticGroups).sorted())
    }

    @Test
    fun theLogoPageGroupsEveryAdjustmentAndTheActionsOnce() {
        val controls = PhoneTestControls.logoControls()
        val groups = pageGroups(EditorTool.Logo, controls)
        assertEquals(controls.map { it.key }.sorted(), keysOf(groups).sorted())
        assertEquals("card::design.logo", groups.last().single(), "三个动作单独一页，排最后")
        assertTrue(groups.size >= 3, "四行调整 + 动作至少三页：$groups")
    }

    @Test
    fun theWatermarkPageIsOneGroupPerPart() {
        val groups = pageGroups(EditorTool.Watermark, emptyList())
        assertEquals(
            listOf(
                PhonePageKeys.WATERMARK_LAYER,
                PhonePageKeys.WATERMARK_POSITION,
                PhonePageKeys.WATERMARK_MARGINS,
                PhonePageKeys.WATERMARK_WIDTH,
                PhonePageKeys.WATERMARK_OPACITY,
                PhonePageKeys.WATERMARK_ROTATION,
            ),
            keysOf(groups),
        )
        // 空态是一整页（说明 + 上传），不是 6 个空组。
        assertEquals(
            listOf(PhonePageKeys.WATERMARK_EMPTY),
            keysOf(pageGroups(EditorTool.Watermark, emptyList(), hasWatermarkLayer = false)),
        )
    }

    @Test
    fun aVendorAnchorGridIsSplitIntoTwoPagesThatBothFit() {
        val anchor = PhoneTestControls.control(
            id = "params:stockAnchor",
            param = "params.stockAnchor",
            label = "水印位置",
            type = ControlType.Select,
            kind = "anchorGrid",
            initial = com.framemark.native.inspector.ControlValue.Str("leftTop"),
            options = listOf(
                "leftTop", "centerTop", "rightTop", "leftCenter", "center",
                "rightCenter", "leftBottom", "centerBottom", "rightBottom",
            ).map { com.framemark.native.inspector.ControlOption(it, it) },
        )
        val groups = pageGroups(EditorTool.Params, listOf(anchor), isIr = true)
        assertTrue(groups.any { PhonePageKeys.ANCHOR_GRID in it })
        assertTrue(groups.any { PhonePageKeys.ANCHOR_FOOTER in it })
        // 整块 254 dp 放不下一页；拆开后每块都在预算内。
        val heights = pageGroupHeights(EditorTool.Params, listOf(anchor), isIr = true)
        assertTrue(heights.all { it <= PHONE_PAGE_GROUP_BUDGET_DP }, "heights=$heights")
        assertTrue(heights.contains(140f), "九宫格那页是 132 + 上下 8 = 140 dp：$heights")
    }

    // -----------------------------------------------------------------------
    // 每一页在每一个 carried 窗口都放得下
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

    private val pages: List<Pair<String, Triple<EditorTool, List<InspectorControl>, Boolean>>> = listOf(
        "样式" to Triple(EditorTool.Params, PhoneTestControls.styleControls(), false),
        "文字" to Triple(EditorTool.Text, PhoneTestControls.textControls(), true),
        "Logo" to Triple(EditorTool.Logo, PhoneTestControls.logoControls(), false),
        "水印" to Triple(EditorTool.Watermark, emptyList(), false),
    )

    @Test
    fun everyGroupFitsTheLevelThePolicySelectsOnEveryCarriedWindow() {
        carriedWindows.forEach { window ->
            pages.forEach { (name, page) ->
                val (tool, controls, hasTextReport) = page
                val level = assertNotNull(
                    defaultSheetLevelFor(
                        tool = tool,
                        controls = controls,
                        availableHeightDp = window.height,
                        photosOpen = window.photosOpen,
                        hasTextReport = hasTextReport,
                    ),
                    "$name @ $window: the page must fit a level",
                )
                assertTrue(
                    fitsWithoutScroll(
                        tool = tool,
                        controls = controls,
                        sheetLevelDp = level,
                        hasTextReport = hasTextReport,
                    ),
                    "$name @ $window: level=$level does not hold the tallest group",
                )
                // 每一组也都不超过预算（预算本身就是最紧窗口的净高）。
                pageGroupHeights(tool, controls, hasTextReport = hasTextReport).forEach { height ->
                    assertTrue(
                        height <= PHONE_PAGE_GROUP_BUDGET_DP + 0.01f,
                        "$name @ $window: group $height > budget",
                    )
                }
            }
        }
    }

    @Test
    fun theDefaultLevelNeverLeavesTheFloorOrTheCeiling() {
        carriedWindows.forEach { window ->
            pages.forEach { (name, page) ->
                val (tool, controls, hasTextReport) = page
                val level = assertNotNull(
                    defaultSheetLevelFor(
                        tool = tool,
                        controls = controls,
                        availableHeightDp = window.height,
                        photosOpen = window.photosOpen,
                        hasTextReport = hasTextReport,
                    ),
                )
                val ceiling = window.height - TOP_BAR_DP - TOOL_BAR_DP -
                    (if (window.photosOpen) STRIP_DP else 0f) -
                    window.height * STAGE_MIN_FRACTION
                assertTrue(level >= MIN_SHEET_DP, "$name @ $window: level=$level < floor")
                assertTrue(level <= ceiling + 0.01f, "$name @ $window: level=$level > ceiling=$ceiling")
            }
        }
    }

    // -----------------------------------------------------------------------
    // 高度由内容决定
    // -----------------------------------------------------------------------

    @Test
    fun aShortPageOpensShorterThanALongPage() {
        val light = assertNotNull(
            defaultSheetLevelFor(
                tool = EditorTool.Params,
                controls = PhoneTestControls.lightStyleControls(),
                availableHeightDp = 844f,
                photosOpen = false,
            ),
        )
        val typical = assertNotNull(
            defaultSheetLevelFor(
                tool = EditorTool.Params,
                controls = PhoneTestControls.styleControls(),
                availableHeightDp = 844f,
                photosOpen = false,
            ),
        )
        val tall = assertNotNull(
            defaultSheetLevelFor(
                tool = EditorTool.Logo,
                controls = PhoneTestControls.logoControls(),
                availableHeightDp = 844f,
                photosOpen = false,
            ),
        )
        // 一页只有底色（75 dp）→ 147；样式页四组（最高 92）→ 164；Logo 四行调整（148）→ 226。
        assertEquals(147f, light, 0.01f)
        assertEquals(164f, typical, 0.01f)
        assertEquals(226f, tall, 0.01f)
        assertTrue(light < typical, "light=$light typical=$typical")
        assertTrue(typical < tall, "typical=$typical tall=$tall")
        // 都比旧的固定 34 %（287 dp）矮：底板把画布让出来。
        assertTrue(tall < 844f * 0.34f, "tall=$tall")
    }

    @Test
    fun theLevelIsExactlyTheTallestGroupPlusChromeAndSlack() {
        val controls = PhoneTestControls.styleControls()
        val level = assertNotNull(
            defaultSheetLevelFor(EditorTool.Params, controls, 886f, photosOpen = false),
        )
        val chrome = phoneParameterSheetChrome(level)
        val tallest = pageGroupHeights(EditorTool.Params, controls).max()
        assertEquals(
            chrome.totalDp + PHONE_PARAMETER_SUMMARY_DP + tallest + PHONE_PAGE_LEVEL_SLACK_DP,
            level,
            0.01f,
        )
    }

    // -----------------------------------------------------------------------
    // 放不下就回退整页，不留隐藏滚动
    // -----------------------------------------------------------------------

    @Test
    fun aPageTooTallForTheWindowFallsBackInsteadOfScrolling() {
        // 640 dp 窗口里舞台地板只允许 206 dp 底板：chrome 38 + 公共区 34 之后剩 134，
        // 放不下 Logo 页的 148 dp 那一组 → null（整页回退），而不是裁剪或滚动。
        val controls = PhoneTestControls.logoControls()
        assertNull(defaultSheetLevelFor(EditorTool.Logo, controls, 640f, photosOpen = false))
        assertTrue(
            computePhoneEditorHeights(
                availableHeightDp = 640f,
                fontScale = 1f,
                imeVisible = false,
                tool = EditorTool.Logo,
                photosOpen = false,
                controls = controls,
            ).fullPage,
        )
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
            "the scaled level must still hold the scaled groups",
        )
    }
}
