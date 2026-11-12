package com.framemark.native.shell

import com.framemark.native.shell.phone.PhoneTestControls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Area-budget rules of the V2 phone layout (pure arithmetic, no Compose).
 *
 * **2026-11 版式过**：非边框工具的收起高度由这一页的内容决定
 * （`PhonePageLayout.defaultSheetLevelFor`），不再是固定的 34–42 %。原来的
 * `non_border_tools_take_34_to_42_percent_of_the_window` 因此被删掉、换成
 * `non_border_tools_open_at_their_content_height`：它断言的是新政策本身（矮页开得矮、
 * 高页开得高、都低于旧的 34 % 固定值），而不是一个已经不存在的常数。
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
        // WP-PV2: with the card's 11 sp description line the stack needs 300 dp at this
        // window (tier 1), so the border sheet is exactly as tall as its content.
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
        val windows = listOf(844f, 800f, 640f, 480f, 390f)
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
    fun non_border_tools_open_at_their_content_height() {
        // 密度过之后：样式页两页（最高 137）→ 209；文字页一页（148）→ 220；
        // Logo 一整页（217）→ 303。三页 / 两页 / 六页的旧数字全部作废。
        val style = computePhoneEditorHeights(
            844f, 1f, false, EditorTool.Params, false,
            controls = PhoneTestControls.styleControls(),
        )
        val text = computePhoneEditorHeights(
            844f, 1f, false, EditorTool.Text, false,
            controls = PhoneTestControls.textControls(),
            hasTextReport = true,
        )
        val logo = computePhoneEditorHeights(
            844f, 1f, false, EditorTool.Logo, false,
            controls = PhoneTestControls.logoControls(),
        )
        assertFalse(style.fullPage)
        assertFalse(text.fullPage)
        assertFalse(logo.fullPage)
        assertEquals(209f, style.sheet, 0.01f)
        assertEquals(220f, text.sheet, 0.01f)
        assertEquals(303f, logo.sheet, 0.01f)
        // 样式与文字都低于旧的固定 34 %（287 dp）；Logo 这一页现在是**一整页**（217 dp
        // 的内容），所以它要 303 dp 的底板 —— 仍然低于舞台 50 % 地板给到的上限。
        assertTrue(style.sheet < 844f * 0.34f, "style=${style.sheet}")
        assertTrue(text.sheet < 844f * 0.34f, "text=${text.sheet}")
        assertTrue(logo.sheet <= 844f - 52f - 62f - 844f * 0.5f + 0.01f, "logo=${logo.sheet}")
        // 矮页比高页矮，高页比矮页高 ——「内容决定高度」照旧。
        assertTrue(style.sheet < text.sheet, "style=${style.sheet} text=${text.sheet}")
        assertTrue(text.sheet < logo.sheet, "text=${text.sheet} logo=${logo.sheet}")
        // 舞台因此比 50 % 地板给出的更多。
        assertTrue(style.stage > 844f * 0.5f, "style stage=${style.stage}")
        assertTrue(logo.stage >= 844f * 0.5f - 0.01f, "logo stage=${logo.stage}")
        // 每一页在它自己的高度里都放得下（没有任何一页需要竖向滚动）。
        listOf(
            EditorTool.Params to PhoneTestControls.styleControls(),
            EditorTool.Text to PhoneTestControls.textControls(),
            EditorTool.Logo to PhoneTestControls.logoControls(),
        ).forEach { (tool, controls) ->
            val h = computePhoneEditorHeights(844f, 1f, false, tool, false, controls = controls)
            assertTrue(
                fitsWithoutScroll(
                    tool = tool,
                    controls = controls,
                    sheetLevelDp = h.sheet,
                    hasTextReport = tool == EditorTool.Text,
                ),
                "tool=$tool sheet=${h.sheet} overflows",
            )
        }
    }

    @Test
    fun photo_strip_only_costs_height_when_it_is_open() {
        // 照片条仍然只在打开时占 64 dp；因为底板高度由内容决定，它从舞台那一侧出现。
        // 样式页在 800 与 844 的许可都装下同一组两页，所以底板不变。
        val controls = PhoneTestControls.styleControls()
        val closed = computePhoneEditorHeights(800f, 1f, false, EditorTool.Params, photosOpen = false, controls = controls)
        val open = computePhoneEditorHeights(800f, 1f, false, EditorTool.Params, photosOpen = true, controls = controls)
        assertEquals(0f, closed.strip)
        assertEquals(64f, open.strip)
        assertTrue(open.stage >= 800f * 0.5f - 0.01f, "open stage=${open.stage}")
        assertEquals(closed.sheet, open.sheet, 0.01f)
    }

    @Test
    fun leaving_a_constrained_full_page_keeps_real_chrome_and_a_scrollable_panel() {
        // Font scale >= 1.5 (or a short window) starts on the full parameter page;
        // "返回预览" used to leave a zero-height shell. The forced-preview budget
        // must keep the real top/tool bars and a reachable stage.
        val h = forcedPreviewHeights(905f)
        assertFalse(h.fullPage)
        assertEquals(52f, h.topBar)
        assertEquals(62f, h.toolBar)
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
