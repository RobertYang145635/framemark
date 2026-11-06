package com.framemark.native.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * §4.1 / §5.2: the two-level sheet must leave the photo at least half of the
 * window at the 收起 (compact) level on 390×844, 360×800 and 412×915, and the
 * 展开 level must still keep a usable stage. Pure arithmetic, no Compose.
 */
class PhoneEditorSheetHeightsTest {

    private data class Screen(val width: Float, val height: Float, val small: Boolean)

    private val screens = listOf(
        Screen(390f, 844f, small = false),
        Screen(360f, 800f, small = true),
        Screen(412f, 915f, small = false),
    )

    private val tools = enumValues<EditorTool>().toList()

    @Test
    fun the_v2_chrome_is_52_dp_top_bar_and_62_dp_tool_bar() {
        val h = computePhoneEditorHeights(844f, 1f, false, EditorTool.Border, false, false)
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
                    small = screen.small,
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
        // WP-P1-2 changed the compact border sheet from "the 326 dp design target capped
        // by the floor" to "the card stack's own height": 844 - 52 - 62 - 300 = 430 dp of
        // stage. The stage still keeps more than half the window and the sheet no longer
        // carries a dead band (was 308 dp, i.e. 11 dp short of the 319 dp stack, which is
        // what cut the card names in half on the device).
        val h = computePhoneEditorHeights(844f, 1f, false, EditorTool.Border, false, false)
        assertEquals(300f, h.sheet, 0.01f)
        assertEquals(430f, h.stage, 0.01f)
        assertTrue(
            phoneCompactSheetMetrics(h.sheet).contentHeight <= h.sheet + 0.01f,
            "the sheet must hold the 128 dp card and its name",
        )

        val levels = assertNotNull(phoneSheetLevels(844f, EditorTool.Border, false, false))
        assertEquals(300f, levels.compact, 0.01f)
        assertTrue(levels.expanded > levels.compact, "expanded=${levels.expanded} compact=${levels.compact}")
        val expandedStage = 844f - 52f - 62f - levels.expanded
        assertTrue(expandedStage >= 844f * 0.26f - 0.01f, "expanded stage=$expandedStage")
    }

    @Test
    fun the_expanded_level_is_taller_than_the_compact_level_on_every_screen() {
        screens.forEach { screen ->
            val levels = assertNotNull(
                phoneSheetLevels(screen.height, EditorTool.Border, photosOpen = false, small = screen.small),
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
    fun the_photo_strip_comes_out_of_the_sheet_and_never_below_the_half_screen_stage() {
        val closed = computePhoneEditorHeights(800f, 1f, false, EditorTool.Params, photosOpen = false, small = false)
        val open = computePhoneEditorHeights(800f, 1f, false, EditorTool.Params, photosOpen = true, small = false)
        assertEquals(0f, closed.strip)
        assertEquals(64f, open.strip)
        assertTrue(open.stage >= 800f * 0.5f - 0.01f, "open stage=${open.stage}")
        // With the 50 % floor the strip is paid for by the sheet, not by the photo.
        assertTrue(open.sheet < closed.sheet, "closed=${closed.sheet} open=${open.sheet}")
    }

    @Test
    fun a_window_too_short_for_a_usable_sheet_keeps_the_full_parameter_page() {
        // 390 dp tall: 390 - 52 - 62 - 195 < MIN_SHEET_DP, so no usable compact
        // sheet fits above the half-screen floor.
        assertTrue(computePhoneEditorHeights(390f, 1f, false, EditorTool.Border, false, false).fullPage)
        assertTrue(phoneSheetLevels(390f, EditorTool.Border, photosOpen = false, small = true) == null)
    }
}
