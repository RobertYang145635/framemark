package com.framemark.native.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V06 high-DPI window fit: pure arithmetic only, no AWT, no window.
 *
 * A window larger than the work area is the reported "the inspector is off
 * screen" bug, so the desired size is clamped to what the desktop actually has;
 * a window the user asked to be small is never enlarged past its request (only
 * the shared floor applies), and an unreadable work area leaves the launch size
 * alone.
 */
class DesktopWindowFitTest {

    @Test
    fun default_launch_size_is_fitted_to_a_shorter_work_area() {
        val fit = fitWindowToScreen(1280f, 800f, 1280f, 720f)
        assertEquals(1280f, fit.width, "width already fits")
        assertEquals(720f, fit.height, "height is clamped to the work area")
    }

    @Test
    fun oversized_window_is_clamped_to_the_work_area() {
        val fit = fitWindowToScreen(2560f, 1600f, 1920f, 1080f)
        assertEquals(1920f, fit.width)
        assertEquals(1080f, fit.height)
    }

    @Test
    fun work_area_at_150_and_200_percent_still_opens_the_default_size() {
        // Logical work areas of a 1920×1080 panel at 150 % / 200 %.
        val at150 = fitWindowToScreen(1280f, 800f, 1280f, 720f)
        assertTrue(at150.width <= 1280f && at150.height <= 720f, "150 % keeps the window inside")

        val at200 = fitWindowToScreen(1280f, 800f, 960f, 540f)
        assertEquals(960f, at200.width)
        assertEquals(540f, at200.height)
    }

    @Test
    fun desired_size_inside_a_larger_work_area_is_unchanged() {
        val fit = fitWindowToScreen(1024f, 700f, 1920f, 1080f)
        assertEquals(1024f, fit.width, "a small request must not be enlarged")
        assertEquals(700f, fit.height)
    }

    @Test
    fun unreadable_work_area_keeps_the_desired_size() {
        assertEquals(WindowFit(1280f, 800f), fitWindowToScreen(1280f, 800f, 0f, 0f))
        assertEquals(WindowFit(1280f, 800f), fitWindowToScreen(1280f, 800f, -1f, -1f))
        assertEquals(WindowFit(1280f, 800f), fitWindowToScreen(1280f, 800f, Float.NaN, Float.NaN))
    }

    @Test
    fun unusable_desired_size_falls_back_to_the_default() {
        assertEquals(WindowFit(1280f, 800f), fitWindowToScreen(0f, 0f, 1920f, 1080f))
        assertEquals(WindowFit(1280f, 800f), fitWindowToScreen(Float.NaN, Float.NaN, 1920f, 1080f))
        assertEquals(WindowFit(1280f, 800f), fitWindowToScreen(Float.POSITIVE_INFINITY, 0f, 1920f, 1080f))
    }

    @Test
    fun minimum_window_size_is_enforced_when_the_work_area_allows_it() {
        val fit = fitWindowToScreen(400f, 300f, 1920f, 1080f)
        assertEquals(DESKTOP_WINDOW_MIN_WIDTH, fit.width, "the shell floor still applies")
        assertEquals(DESKTOP_WINDOW_MIN_HEIGHT, fit.height)
        // AU4-02 (design change, registered in WP-X3-REPORT §1): the floor used to
        // sit *above* the three-column floor, which made the folded single-pane
        // branch unreachable. It now sits below it on purpose.
        assertTrue(DESKTOP_WINDOW_MIN_WIDTH < desktopColumnMinWidth(),
            "the floor window must be narrow enough for the folded single-pane layout to be reachable")
    }

    @Test
    fun au4_02_requested_narrow_widths_are_honoured_verbatim() {
        // AU4-02 acceptance: 700 / 720 / 800 / 1280 / 1440 must all open at the
        // requested size on a 2560×1356 work area (the old 720 floor turned a 700
        // request into 720 and the top bar was clipped).
        listOf(700f, 720f, 800f, 1280f, 1440f).forEach { requested ->
            val fit = fitWindowToScreen(requested, 868f, 2560f, 1356f)
            assertEquals(requested, fit.width, "requested ${requested}dp must not be rewritten")
        }
    }

    @Test
    fun a_window_at_the_floor_shows_the_folded_single_pane_layout() {
        val fit = fitWindowToScreen(320f, 868f, 2560f, 1356f)
        assertEquals(DESKTOP_WINDOW_MIN_WIDTH, fit.width)
        val columns = computeDesktopColumns(
            availableWidthDp = fit.width,
            requestedLeft = desktopLibraryMaxWidth(),
            requestedRight = desktopInspectorMaxWidth(),
            leftCollapsed = false,
            rightCollapsed = false,
        )
        assertTrue(columns.compact, "the floor window must fold to one pane, not squeeze three")
    }

    @Test
    fun au4_02_top_bar_switches_form_exactly_where_it_stops_fitting() {
        // Narrow windows (the five AU4-02 sizes) that cannot hold the full bar.
        listOf(DESKTOP_WINDOW_MIN_WIDTH, 700f, 720f, 800f).forEach { width ->
            assertTrue(desktopTopBarCompact(width), "content ${width}dp must use the compact top bar")
        }
        // The demo widths keep the full bar.
        listOf(1280f, 1440f, DESKTOP_TOP_BAR_FULL_WIDTH).forEach { width ->
            assertTrue(!desktopTopBarCompact(width), "content ${width}dp must keep the full top bar")
        }
        // Unreadable width: no room claimed, so the compact form is used.
        assertTrue(desktopTopBarCompact(Float.NaN))
        assertTrue(desktopTopBarCompact(0f))
        assertTrue(desktopTopBarCompact(-1f))
        // The compact bar must fit every window the shell can open at.
        assertTrue(DESKTOP_TOP_BAR_COMPACT_MIN_WIDTH <= DESKTOP_WINDOW_MIN_WIDTH,
            "compact bar needs ${DESKTOP_TOP_BAR_COMPACT_MIN_WIDTH}dp but the floor window is ${DESKTOP_WINDOW_MIN_WIDTH}dp")
    }

    @Test
    fun floor_never_pushes_the_window_past_a_small_work_area() {
        // WP-X3: the work area has to be smaller than the *current* floor
        // (600 dp) for this test to test what it says; the old 640×480 case stopped
        // being "smaller than the floor" the moment the floor moved below it.
        val fit = fitWindowToScreen(400f, 300f, 520f, 480f)
        assertEquals(520f, fit.width, "a work area smaller than the floor wins")
        assertEquals(480f, fit.height)
    }

    @Test
    fun the_fitted_default_still_shows_three_columns() {
        val fit = fitWindowToScreen(1280f, 800f, 1280f, 720f)
        val content = fit.width - 12f * 2f
        assertTrue(content >= desktopColumnMinWidth(), "content width $content must fit the columns")
    }
}
