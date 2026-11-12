package com.framemark.native.shell

import com.framemark.native.shell.phone.PhoneTestControls
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
 * **2026-11 版式过**：非边框工具的收起高度不再是一个固定的 34–42 %，而是
 * `PhonePageLayout.defaultSheetLevelFor` 按这一页**最高的一页**算出来的
 * （`chrome + 公共区 + 最高一页 + 6`）。所以下面每一条用到 Params / Text / Logo /
 * 水印 的用例都要传这一页真实的控件表，否则它量的是「空页」。
 *
 * **照片条**：因为高度由内容决定，800 dp 窗口里打开照片条不再自动让底板变矮 ——
 * 它先把「一页的许可」从 206 压到 150；只有许可变化真的让这一页多分一页时，底板
 * 才会变（`thePhotoStripShrinksThePageBudgetAndMayAddAPage` 钉住这条新语义）。
 */
class PhoneEditorSheetHeightsTest {

    private data class Screen(val width: Float, val height: Float)

    private val screens = listOf(
        Screen(390f, 844f),
        Screen(360f, 800f),
        Screen(412f, 915f),
    )

    private val tools = enumValues<EditorTool>().toList()

    /** 每个工具页真实形状的控件表（`PhonePageLayoutTest` 用同一份）。 */
    private fun controlsFor(tool: EditorTool) = when (tool) {
        EditorTool.Border, EditorTool.Watermark -> emptyList()
        EditorTool.Params -> PhoneTestControls.styleControls()
        EditorTool.Text -> PhoneTestControls.textControls()
        EditorTool.Logo -> PhoneTestControls.logoControls()
    }

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
                    controls = controlsFor(tool),
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
            val levels = assertNotNull(
                phoneSheetLevels(screen.height, EditorTool.Border, photosOpen = false),
                "levels missing on ${screen.height}",
            )
            assertTrue(
                levels.expanded > levels.compact,
                "screen=${screen.height} expanded=${levels.expanded} compact=${levels.compact}",
            )
            val stage = screen.height - 52f - 62f - levels.expanded
            assertTrue(stage >= screen.height * 0.26f - 0.01f, "screen=${screen.height} stage=$stage")
        }
    }

    @Test
    fun thePhotoStripShrinksThePageBudgetAndMayAddAPage() {
        // 照片条拿走 64 dp；舞台 50 % 地板把**一页的许可**从 206 压到 150。
        // Logo 页在 206 里是两页（最高 148）→ 底板 220；在 150 里还是两页（148 ≤ 150）
        // → 还是 220：照片条没有让底板变矮，只是把画布让了出来。
        val controls = PhoneTestControls.logoControls()
        val closed = computePhoneEditorHeights(800f, 1f, false, EditorTool.Logo, photosOpen = false, controls = controls)
        val open = computePhoneEditorHeights(800f, 1f, false, EditorTool.Logo, photosOpen = true, controls = controls)
        assertEquals(0f, closed.strip)
        assertEquals(64f, open.strip)
        assertEquals(220f, closed.sheet, 0.01f)
        assertEquals(220f, open.sheet, 0.01f)
        assertTrue(open.stage >= 800f * 0.5f - 0.01f, "open stage=${open.stage}")
        assertTrue(
            fitsWithoutScroll(tool = EditorTool.Logo, controls = controls, sheetLevelDp = open.sheet),
            "the clamped sheet must still hold every page",
        )
        // 水印页的许可被压到 150 时多分一页（2 → 3），底板反而更矮：274 → 220。
        assertEquals(
            274f,
            computePhoneEditorHeights(800f, 1f, false, EditorTool.Watermark, false).sheet,
            0.01f,
        )
        assertEquals(
            220f,
            computePhoneEditorHeights(800f, 1f, false, EditorTool.Watermark, true).sheet,
            0.01f,
        )
        // 样式页两页都装得下，照片条不动它。
        val styleControls = PhoneTestControls.styleControls()
        val styleClosed = computePhoneEditorHeights(844f, 1f, false, EditorTool.Params, false, controls = styleControls)
        val styleOpen = computePhoneEditorHeights(844f, 1f, false, EditorTool.Params, true, controls = styleControls)
        assertEquals(209f, styleClosed.sheet, 0.01f)
        assertEquals(209f, styleOpen.sheet, 0.01f)
    }

    @Test
    fun a_window_too_short_for_a_usable_sheet_keeps_the_full_parameter_page() {
        // 390 dp tall: 390 - 52 - 62 - 195 < MIN_SHEET_DP, so no usable compact
        // sheet fits above the half-screen floor.
        assertTrue(computePhoneEditorHeights(390f, 1f, false, EditorTool.Border, false).fullPage)
        assertTrue(phoneSheetLevels(390f, EditorTool.Border, photosOpen = false) == null)
    }
}
