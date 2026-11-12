package com.framemark.native.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Area-budget rules of the V2 phone layout (pure arithmetic, no Compose).
 *
 * **2026-11-10 固定画框**：非边框工具的收起高度是 `clamp(0.34 × 窗口, 280, 330)`
 * （`phonePanelHeightDp`），不再由内容决定。原来的
 * `non_border_tools_open_at_their_content_height`（它断言「矮页开得矮、高页开得高」）
 * 随之换成 `non_border_tools_open_at_the_fixed_panel_height`：后者断言的是新政策本身
 * —— 同一窗口里三个工具读到**同一个**面板高度。
 */
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
        )
        assertFalse(h.fullPage)
        // WP-P1 §4.1/§4.2: the V2 chrome is a 52 dp dark top bar and a 62 dp bar.
        assertEquals(52f, h.topBar)
        assertEquals(62f, h.toolBar)
        assertEquals(0f, h.strip)
        // 边框页没有被固定画框接管：它的落地面板仍然是 128 dp 卡片那一摞（tier 1 = 300）。
        assertEquals(300f, h.sheet, 0.01f)
        // WP-P1 §4.1: the compact sheet leaves the stage at least half the window.
        assertTrue(h.stage >= 844f * 0.5f - 0.01f, "stage=${h.stage}")
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
        )
        assertFalse(h.fullPage)
        assertEquals(64f, h.strip)
        // WP-P1 §4.1: with the 50 % stage floor the open strip comes out of the
        // sheet (800 - 52 - 62 - 64 - 400 = 222), not out of the photo.
        assertTrue(h.sheet >= 220f, "border sheet on the small layout=${h.sheet}")
        assertTrue(h.stage >= 800f * 0.5f - 0.01f, "stage=${h.stage}")
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
            computePhoneEditorHeights(800f, 1.5f, imeVisible = false, tool = EditorTool.Text, photosOpen = false).fullPage,
        )
        // V06 UX feedback: IME alone keeps the imePadding()-measured preview;
        // only a genuinely short remaining window falls back to the full page.
        assertFalse(
            computePhoneEditorHeights(800f, 1f, imeVisible = true, tool = EditorTool.Text, photosOpen = false).fullPage,
        )
        assertTrue(
            computePhoneEditorHeights(400f, 1f, imeVisible = true, tool = EditorTool.Text, photosOpen = false).fullPage,
        )
        assertFalse(
            computePhoneEditorHeights(800f, 1.49f, imeVisible = false, tool = EditorTool.Text, photosOpen = false).fullPage,
        )
    }

    @Test
    fun every_tool_stays_inside_the_window_and_never_goes_negative() {
        val tools = enumValues<EditorTool>().toList()
        val windows = listOf(915f, 844f, 800f, 700f, 640f, 570f, 480f, 390f)
        tools.forEach { tool ->
            windows.forEach { window ->
                listOf(false, true).forEach { strip ->
                    val h = computePhoneEditorHeights(window, 1f, false, tool, strip)
                    assertTrue(
                        h.topBar >= 0f && h.stage >= 0f && h.strip >= 0f && h.sheet >= 0f && h.toolBar >= 0f,
                        "negative budget tool=$tool window=$window strip=$strip -> $h",
                    )
                    assertTrue(
                        sum(h) <= window + 0.01f,
                        "overflow tool=$tool window=$window strip=$strip sum=${sum(h)}",
                    )
                    if (!h.fullPage) {
                        // 固定画框只在「照片还站得住」的窗口里出现。
                        assertTrue(
                            h.stage >= PHONE_PANEL_MIN_STAGE_DP - 0.01f,
                            "stage below the floor tool=$tool window=$window stage=${h.stage}",
                        )
                        if (tool != EditorTool.Border) {
                            assertEquals(
                                phonePanelHeightDp(window),
                                h.sheet,
                                0.01f,
                                "tool=$tool window=$window",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun non_border_tools_open_at_the_fixed_panel_height() {
        // 同一个窗口里，样式 / 文字 / Logo 拿到的是**同一个**面板高度（内容不再参与）。
        val style = computePhoneEditorHeights(844f, 1f, false, EditorTool.Params, false)
        val text = computePhoneEditorHeights(844f, 1f, false, EditorTool.Text, false)
        val logo = computePhoneEditorHeights(844f, 1f, false, EditorTool.Logo, false)
        assertFalse(style.fullPage)
        assertFalse(text.fullPage)
        assertFalse(logo.fullPage)
        val expected = phonePanelHeightDp(844f)
        assertEquals(expected, style.sheet, 0.01f)
        assertEquals(expected, text.sheet, 0.01f)
        assertEquals(expected, logo.sheet, 0.01f)
        // 844 的 34 % 是 287（落在 [280, 330] 里，没有夹到边）。
        assertEquals(844f * 0.34f, expected, 0.01f)
        assertEquals(expected, style.stage.let { 844f - 52f - 62f - it }, 0.01f)
        assertTrue(style.stage > 844f * 0.5f, "style stage=${style.stage}")
    }

    @Test
    fun photo_strip_only_costs_the_stage() {
        // 照片条打开只从画布那一侧扣 64 dp：面板高度一个 dp 都不动（固定画框）。
        val closed = computePhoneEditorHeights(800f, 1f, false, EditorTool.Params, photosOpen = false)
        val open = computePhoneEditorHeights(800f, 1f, false, EditorTool.Params, photosOpen = true)
        assertEquals(0f, closed.strip)
        assertEquals(64f, open.strip)
        assertEquals(closed.sheet, open.sheet, 0.01f)
        assertEquals(64f, closed.stage - open.stage, 0.01f)
        assertTrue(closed.stage >= 800f * 0.5f - 0.01f, "closed stage=${closed.stage}")
        // 打开照片条时画布会低于 50 % —— 那是照片条自己的 64 dp，不是面板长高了。
        assertEquals(phonePanelHeightDp(800f), open.sheet, 0.01f)
    }

    @Test
    fun leaving_a_constrained_full_page_keeps_the_same_fixed_panel() {
        // Font scale >= 1.5 (or a short window) starts on the full parameter page;
        // "返回预览" used to leave a zero-height shell. It now comes back to exactly
        // the same fixed panel every other state uses.
        val h = forcedPreviewHeights(905f)
        assertFalse(h.fullPage)
        assertEquals(52f, h.topBar)
        assertEquals(62f, h.toolBar)
        assertEquals(0f, h.strip)
        assertEquals(phonePanelHeightDp(905f), h.sheet, 0.01f)
        assertEquals(905f - 52f - 62f - h.sheet, h.stage, 0.01f)
        assertTrue(h.stage >= PHONE_PANEL_MIN_STAGE_DP, "stage=${h.stage}")
        assertTrue(sum(h) <= 905f + 0.01f, "sum=${sum(h)}")

        assertTrue(canShowForcedPreview(FORCED_PREVIEW_MIN_DP))
        assertFalse(canShowForcedPreview(FORCED_PREVIEW_MIN_DP - 1f))
        assertTrue(
            forcedPreviewHeights(FORCED_PREVIEW_MIN_DP - 1f).fullPage,
            "a window too short for the fixed panel must stay on the full page",
        )
    }
}
