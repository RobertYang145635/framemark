package com.framemark.native.shell

import com.framemark.native.shell.phone.PhoneBorderPageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 2026-11-15（配色方案 §4.1）：边框落地板从「128 dp 卡片 + 两行名称 + 按钮 + 说明」
 * 收成一行当前项。收起 sheet 因此不再由卡片堆叠决定，而是 **固定 chrome + 面板高度**：
 *
 *   sheet = 16（把手）+ 44（标题）+ panel（无边框 144 / 有边框 88）
 *
 * 无边框时的 144 是这套预算的上界，所以 sheet 固定 204；有边框时面板只占 88，
 * 余量是在同一块底板里的留白，但**不会再出现旧的 260–315 dp 空画框**。
 *
 * 这里钉住的是「sheet 与面板不互相裁切、舞台仍 ≥ 窗口一半、照片条只从舞台拿高度」。
 */
class PhoneEditorCompactSheetTest {

    private data class Screen(val width: Float, val height: Float)
    private data class Insets(val status: Float, val nav: Float)

    private val screens = listOf(
        Screen(390f, 844f),
        Screen(360f, 800f),
        Screen(412f, 915f),
    )

    /** Every combination of the inset ranges the brief allows. */
    private val allInsets = buildList {
        listOf(24f, 32f, 48f).forEach { status ->
            listOf(16f, 24f, 48f).forEach { nav -> add(Insets(status, nav)) }
        }
    }

    private fun available(screen: Screen, insets: Insets): Float =
        screen.height - insets.status - insets.nav

    private fun borderHeights(screen: Screen, insets: Insets, photosOpen: Boolean = false): PhoneEditorHeights =
        computePhoneEditorHeights(
            availableHeightDp = available(screen, insets),
            fontScale = 1f,
            imeVisible = false,
            tool = EditorTool.Border,
            photosOpen = photosOpen,
        )

    private val chrome = PHONE_BORDER_SHEET_GRABBER_DP + PHONE_BORDER_SHEET_HEAD_DP

    private val emptyPanel = PhoneBorderPageState.panelHeight(
        tileDp = PhoneBorderPageState.PANEL_TILE_DP,
        hasCard = false,
    )

    private val filledPanel = PhoneBorderPageState.panelHeight(
        tileDp = PhoneBorderPageState.PANEL_TILE_DP,
        hasCard = true,
    )

    @Test
    fun the_border_sheet_is_the_compact_panel_plus_its_fixed_chrome() {
        val h = borderHeights(Screen(390f, 844f), Insets(32f, 24f))
        assertFalse(h.fullPage)
        assertEquals(chrome + emptyPanel, h.sheet, 0.01f)
        assertEquals(844f - 32f - 24f - 52f - 62f - h.sheet, h.stage, 0.01f)

        val levels = assertNotNull(phoneSheetLevels(844f, EditorTool.Border, photosOpen = false))
        assertEquals(chrome + emptyPanel, levels.compact, 0.01f)
        assertTrue(levels.expanded > levels.compact, "expanded=${levels.expanded} compact=${levels.compact}")
    }

    @Test
    fun both_panel_states_fit_inside_the_border_sheet() {
        // 2026-11：没有边框时面板里不再有第二条全宽按钮，两种状态都是**一行当前项**，
        // 所以恒同高 —— sheet 在点选边框前后不跳，舞台上的照片也不会被顶一下。
        assertEquals(emptyPanel, filledPanel, 0.01f)
        assertEquals(chrome + emptyPanel, chrome + filledPanel, 0.01f)
        assertEquals(emptyPanel, PhoneBorderPageState.panelHeight(72f, hasCard = false), 0.01f)
        assertEquals(filledPanel, PhoneBorderPageState.panelHeight(72f, hasCard = true), 0.01f)
    }

    @Test
    fun the_stage_keeps_half_of_the_app_height_on_every_screen_and_inset() {
        screens.forEach { screen ->
            allInsets.forEach { insets ->
                val available = available(screen, insets)
                val h = borderHeights(screen, insets)
                assertFalse(h.fullPage, "fullPage on ${screen.width}x${screen.height} $insets")
                assertTrue(
                    h.stage >= available * 0.5f - 0.01f,
                    "stage=${h.stage} < 50% of $available on ${screen.width}x${screen.height} $insets",
                )
                val sum = h.topBar + h.stage + h.strip + h.sheet + h.toolBar
                assertTrue(sum <= available + 0.01f, "overflow ${sum} > $available")
            }
        }
    }

    @Test
    fun the_border_sheet_stays_a_sheet_at_every_carried_inset() {
        screens.forEach { screen ->
            allInsets.forEach { insets ->
                val h = borderHeights(screen, insets)
                assertEquals(chrome + emptyPanel, h.sheet, 0.01f, "sheet on ${screen.width}x${screen.height} $insets")
                assertTrue(
                    h.sheet <= available(screen, insets) * 0.34f,
                    "border sheet=${h.sheet} > 34% on ${screen.width}x${screen.height} $insets",
                )
            }
        }
    }

    @Test
    fun the_photo_strip_only_takes_height_from_the_stage() {
        val screen = Screen(390f, 844f)
        val insets = Insets(32f, 24f)
        val closed = borderHeights(screen, insets, photosOpen = false)
        val open = borderHeights(screen, insets, photosOpen = true)
        assertEquals(0f, closed.strip)
        assertEquals(64f, open.strip)
        assertEquals(closed.sheet, open.sheet, 0.01f)
        assertEquals(64f, closed.stage - open.stage, 0.01f)
        assertTrue(open.stage >= available(screen, insets) * 0.5f - 0.01f)
    }

    @Test
    fun a_window_too_short_for_a_usable_sheet_keeps_the_full_parameter_page() {
        // 390 dp: 16+44+88 = 148 dp of sheet does not fit above a usable stage.
        assertTrue(computePhoneEditorHeights(390f, 1f, false, EditorTool.Border, false).fullPage)
        assertTrue(phoneSheetLevels(390f, EditorTool.Border, photosOpen = false) == null)
        assertTrue(phoneSheetLevels(390f, EditorTool.Params, photosOpen = false) == null)
    }
}
