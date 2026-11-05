package com.framemark.native.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Area-budget rules of the V06 phone layout (pure arithmetic, no Compose). */
class PhoneEditorHeightsTest {

    private fun sum(h: PhoneEditorHeights): Float =
        h.topBar + h.stage + h.strip + h.sheet + h.toolBar

    @Test
    fun border_panel_fits_on_390x844() {
        val h = computePhoneEditorHeights(
            availableHeightDp = 844f,
            fontScale = 1f,
            imeVisible = false,
            tool = EditorTool.Border,
            photosOpen = false,
            small = false,
        )
        assertFalse(h.fullPage)
        assertEquals(48f, h.topBar)
        assertEquals(52f, h.toolBar)
        assertEquals(0f, h.strip)
        assertTrue(h.sheet >= 300f, "border sheet=${h.sheet}")
        assertTrue(h.stage >= 844f * 0.28f, "stage=${h.stage}")
        assertTrue(sum(h) <= 844f + 0.01f, "sum=${sum(h)}")
    }

    @Test
    fun border_panel_fits_on_360x800_with_the_photo_strip_open() {
        val h = computePhoneEditorHeights(
            availableHeightDp = 800f,
            fontScale = 1f,
            imeVisible = false,
            tool = EditorTool.Border,
            photosOpen = true,
            small = true,
        )
        assertFalse(h.fullPage)
        assertEquals(64f, h.strip)
        assertTrue(h.sheet >= 300f, "border sheet on the small layout=${h.sheet}")
        assertTrue(h.stage >= 800f * 0.28f, "stage=${h.stage}")
        assertTrue(sum(h) <= 800f + 0.01f, "sum=${sum(h)}")
    }

    @Test
    fun short_landscape_window_falls_back_to_the_full_parameter_page() {
        val h = computePhoneEditorHeights(
            availableHeightDp = 360f,
            fontScale = 1f,
            imeVisible = false,
            tool = EditorTool.Border,
            photosOpen = false,
            small = false,
        )
        assertTrue(h.fullPage)
        assertEquals(0f, h.topBar)
        assertEquals(0f, h.strip)
        assertEquals(0f, h.toolBar)
        assertEquals(0f, h.stage)
        assertEquals(360f, h.sheet)
        assertTrue(sum(h) <= 360f + 0.01f)
    }

    @Test
    fun large_font_or_short_window_uses_the_full_parameter_page() {
        assertTrue(
            computePhoneEditorHeights(800f, 1.5f, imeVisible = false, tool = EditorTool.Text, photosOpen = false, small = false).fullPage,
        )
        // V06 UX feedback: IME alone keeps the imePadding()-measured preview;
        // only a genuinely short remaining window falls back to the full page.
        assertFalse(
            computePhoneEditorHeights(800f, 1f, imeVisible = true, tool = EditorTool.Text, photosOpen = false, small = false).fullPage,
        )
        assertTrue(
            computePhoneEditorHeights(400f, 1f, imeVisible = true, tool = EditorTool.Text, photosOpen = false, small = false).fullPage,
        )
        assertFalse(
            computePhoneEditorHeights(800f, 1.49f, imeVisible = false, tool = EditorTool.Text, photosOpen = false, small = false).fullPage,
        )
    }

    @Test
    fun every_tool_stays_inside_the_window_and_never_goes_negative() {
        val tools = enumValues<EditorTool>().toList()
        val windows = listOf(844f, 800f, 640f, 480f, 390f)
        tools.forEach { tool ->
            windows.forEach { window ->
                listOf(false, true).forEach { strip ->
                    val h = computePhoneEditorHeights(window, 1f, false, tool, strip, window < 400f)
                    assertTrue(
                        h.topBar >= 0f && h.stage >= 0f && h.strip >= 0f && h.sheet >= 0f && h.toolBar >= 0f,
                        "negative budget tool=$tool window=$window strip=$strip -> $h",
                    )
                    assertTrue(
                        sum(h) <= window + 0.01f,
                        "overflow tool=$tool window=$window strip=$strip sum=${sum(h)}",
                    )
                    if (!h.fullPage) {
                        assertTrue(
                            h.stage >= window * 0.28f - 0.01f,
                            "stage below 28% tool=$tool window=$window stage=${h.stage}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun non_border_tools_take_34_to_42_percent_of_the_window() {
        listOf(EditorTool.Params, EditorTool.Text, EditorTool.Logo, EditorTool.Watermark).forEach { tool ->
            val h = computePhoneEditorHeights(844f, 1f, imeVisible = false, tool = tool, photosOpen = false, small = false)
            assertFalse(h.fullPage, "tool=$tool should keep the preview layout at 844dp")
            val share = h.sheet / 844f
            assertTrue(share >= 0.33f && share <= 0.43f, "tool=$tool sheet share=$share")
        }
    }

    @Test
    fun photo_strip_only_costs_height_when_it_is_open() {
        val closed = computePhoneEditorHeights(800f, 1f, false, EditorTool.Params, photosOpen = false, small = false)
        val open = computePhoneEditorHeights(800f, 1f, false, EditorTool.Params, photosOpen = true, small = false)
        assertEquals(0f, closed.strip)
        assertEquals(64f, open.strip)
        assertTrue(open.stage < closed.stage, "open strip must come out of the stage, not the panel")
        assertEquals(closed.sheet, open.sheet)
    }

    @Test
    fun leaving_a_constrained_full_page_keeps_real_chrome_and_a_scrollable_panel() {
        // Font scale >= 1.5 (or a short window) starts on the full parameter page;
        // "返回预览" used to leave a zero-height shell. The forced-preview budget
        // must keep the real top/tool bars and a reachable stage.
        val h = forcedPreviewHeights(905f)
        assertFalse(h.fullPage)
        assertEquals(48f, h.topBar)
        assertEquals(52f, h.toolBar)
        assertEquals(0f, h.strip)
        assertTrue(h.stage >= 60f, "stage=${h.stage}")
        assertTrue(h.sheet >= 60f, "sheet=${h.sheet}")
        assertTrue(sum(h) <= 905f + 0.01f, "sum=${sum(h)}")

        assertTrue(canShowForcedPreview(FORCED_PREVIEW_MIN_DP))
        assertFalse(canShowForcedPreview(FORCED_PREVIEW_MIN_DP - 1f))
        assertTrue(
            forcedPreviewHeights(FORCED_PREVIEW_MIN_DP - 1f).fullPage,
            "a window too short for any valid preview must stay on the full page",
        )
    }
}
