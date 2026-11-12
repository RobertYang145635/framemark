package com.framemark.native.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * §4.1 / §5.2: the sheet must leave the photo at least half of the window at the
 * 收起 (compact) level on 390×844, 360×800 and 412×915, and the 展开 level must
 * still keep a usable stage. Pure arithmetic, no Compose.
 *
 * **2026-11-10 固定画框**：非边框工具的收起高度是 `clamp(0.34 × 窗口, 280, 330)`
 * （`phonePanelHeightDp`），四个工具、每一组都同一个数。旧的
 * `thePhotoStripShrinksThePageBudgetAndMayAddAPage`（它断言照片条会把「一页的许可」
 * 从 206 压到 150，于是水印多分一页、底板反而变矮）随分页政策一起删除，换成
 * `thePhotoStripOnlyTakesFromTheStage`：照片条只从画布扣，不动面板。
 */
class PhoneEditorSheetHeightsTest {

    private data class Screen(val width: Float, val height: Float)

    private val screens = listOf(
        Screen(390f, 844f),
        Screen(360f, 800f),
        Screen(412f, 915f),
    )

    private val tools = enumValues<EditorTool>().toList()

    @Test
    fun the_v2_chrome_is_52_dp_top_bar_and_62_dp_tool_bar() {
        val h = computePhoneEditorHeights(844f, 1f, false, EditorTool.Border, false)
        assertFalse(h.fullPage)
        assertEquals(52f, h.topBar)
        assertEquals(62f, h.toolBar)
    }

    @Test
    fun the_compact_sheet_keeps_the_stage_at_half_the_window_on_every_carried_screen() {
        screens.forEach { screen ->
            tools.forEach { tool ->
                val h = computePhoneEditorHeights(
                    availableHeightDp = screen.height,
                    fontScale = 1f,
                    imeVisible = false,
                    tool = tool,
                    photosOpen = false,
                )
                assertFalse(h.fullPage, "tool=$tool screen=${screen.height} should keep the preview layout")
                assertTrue(
                    h.stage >= screen.height * 0.5f - 0.01f,
                    "tool=$tool screen=${screen.width}x${screen.height} stage=${h.stage} < 50%",
                )
                val sum = h.topBar + h.stage + h.strip + h.sheet + h.toolBar
                assertTrue(sum <= screen.height + 0.01f, "overflow tool=$tool sum=$sum")
            }
        }
    }

    @Test
    fun the_border_sheet_is_exactly_as_tall_as_its_card_stack() {
        // WP-PV2 re-derived the stack from the showcase: grabber 22, title row 30, 14 px
        // tab row 38, 30 dp chip row 42, a `12 / 10` strip around the 128 dp tile, then
        // the 12 sp name **and** its 11 sp description (`.card .nm` + `.card .ds`).
        // Tier 1 is 300 dp, so 844 - 52 - 62 - 300 = 430 dp of stage: the stage still
        // keeps more than half the window and the sheet carries no dead band.
        val h = computePhoneEditorHeights(844f, 1f, false, EditorTool.Border, false)
        assertEquals(300f, h.sheet, 0.01f)
        assertEquals(430f, h.stage, 0.01f)
        assertTrue(
            phoneCompactSheetMetrics(h.sheet).contentHeight <= h.sheet + 0.01f,
            "the sheet must hold the 128 dp card, its name and its description",
        )

        val levels = assertNotNull(phoneSheetLevels(844f, EditorTool.Border, false))
        assertEquals(300f, levels.compact, 0.01f)
        assertTrue(levels.expanded > levels.compact, "expanded=${levels.expanded} compact=${levels.compact}")
        val expandedStage = 844f - 52f - 62f - levels.expanded
        assertTrue(expandedStage >= 844f * 0.26f - 0.01f, "expanded stage=$expandedStage")
    }

    @Test
    fun the_expanded_level_is_taller_than_the_compact_level_on_every_screen() {
        screens.forEach { screen ->
            tools.forEach { tool ->
                val levels = assertNotNull(
                    phoneSheetLevels(screen.height, tool, photosOpen = false),
                    "levels missing on ${screen.height} for $tool",
                )
                assertTrue(
                    levels.expanded > levels.compact,
                    "screen=${screen.height} tool=$tool expanded=${levels.expanded} compact=${levels.compact}",
                )
                val stage = screen.height - 52f - 62f - levels.expanded
                assertTrue(stage >= screen.height * 0.26f - 0.01f, "screen=${screen.height} stage=$stage")
            }
        }
    }

    @Test
    fun the_parameter_tools_all_open_at_the_same_fixed_level() {
        screens.forEach { screen ->
            val expected = phonePanelHeightDp(screen.height)
            listOf(EditorTool.Params, EditorTool.Text, EditorTool.Logo, EditorTool.Watermark).forEach { tool ->
                val levels = assertNotNull(phoneSheetLevels(screen.height, tool, photosOpen = false))
                assertEquals(expected, levels.compact, 0.01f, "screen=${screen.height} tool=$tool")
                assertEquals(levels, phoneSheetLevels(screen.height, EditorTool.Params, false))
            }
        }
    }

    @Test
    fun thePhotoStripOnlyTakesFromTheStage() {
        // 照片条拿走 64 dp，面板高度一个 dp 都不动（固定画框）。
        val closed = computePhoneEditorHeights(800f, 1f, false, EditorTool.Logo, photosOpen = false)
        val open = computePhoneEditorHeights(800f, 1f, false, EditorTool.Logo, photosOpen = true)
        assertEquals(0f, closed.strip)
        assertEquals(64f, open.strip)
        assertEquals(phonePanelHeightDp(800f), closed.sheet, 0.01f)
        assertEquals(phonePanelHeightDp(800f), open.sheet, 0.01f)
        assertEquals(64f, closed.stage - open.stage, 0.01f)
        // 水印页也是同一个数（旧版这里会因为多分一页而变矮）。
        assertEquals(
            phonePanelHeightDp(800f),
            computePhoneEditorHeights(800f, 1f, false, EditorTool.Watermark, true).sheet,
            0.01f,
        )
        assertEquals(
            computePhoneEditorHeights(800f, 1f, false, EditorTool.Watermark, false).sheet,
            computePhoneEditorHeights(800f, 1f, false, EditorTool.Watermark, true).sheet,
            0.01f,
        )
    }

    @Test
    fun a_window_too_short_for_a_usable_sheet_keeps_the_full_parameter_page() {
        // 390 dp tall: neither the border card stack nor the fixed panel fits above a
        // usable stage, so the full parameter page is the only valid layout.
        assertTrue(computePhoneEditorHeights(390f, 1f, false, EditorTool.Border, false).fullPage)
        assertTrue(phoneSheetLevels(390f, EditorTool.Border, photosOpen = false) == null)
        assertTrue(phoneSheetLevels(390f, EditorTool.Params, photosOpen = false) == null)
    }
}
