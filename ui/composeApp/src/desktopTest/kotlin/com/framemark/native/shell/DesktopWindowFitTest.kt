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
        assertTrue(DESKTOP_WINDOW_MIN_WIDTH > desktopColumnMinWidth(),
            "the floor window must leave room for the three columns plus both edges")
    }

    @Test
    fun floor_never_pushes_the_window_past_a_small_work_area() {
        val fit = fitWindowToScreen(400f, 300f, 640f, 480f)
        assertEquals(640f, fit.width, "a work area smaller than the floor wins")
        assertEquals(480f, fit.height)
    }

    @Test
    fun the_fitted_default_still_shows_three_columns() {
        val fit = fitWindowToScreen(1280f, 800f, 1280f, 720f)
        val content = fit.width - 12f * 2f
        assertTrue(content >= desktopColumnMinWidth(), "content width $content must fit the columns")
    }
}
