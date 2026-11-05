package com.framemark.native.shell

/** A window size in dp, after fitting a desired size to the screen work area. */
data class WindowFit(val width: Float, val height: Float)

/** First-launch size used when `FM_WINDOW` is unset or unusable. */
const val DESKTOP_WINDOW_DEFAULT_WIDTH: Float = 1280f
const val DESKTOP_WINDOW_DEFAULT_HEIGHT: Float = 800f

/**
 * Smallest window the desktop shell opens at. 720 dp of window leaves 720 − 2 ×
 * 12 = 696 dp of content, still above [desktopColumnMinWidth] (664 dp), so a
 * window opened at the floor can show all three columns; 520 dp keeps a usable
 * stage under the 72 dp bar plus the photo strip.
 */
const val DESKTOP_WINDOW_MIN_WIDTH: Float = 720f
const val DESKTOP_WINDOW_MIN_HEIGHT: Float = 520f

/**
 * Fits a desired window size into the screen work area.
 *
 * - a desired edge larger than the work area is clamped to the work area
 *   (1280×800 on a 1280×720 work area → 1280×720; 2560×1600 on 1920×1080 →
 *   1920×1080);
 * - a desired edge smaller than the work area is kept — the minimisation is not
 *   used to enlarge a window the user asked for;
 * - the floor ([DESKTOP_WINDOW_MIN_WIDTH] × [DESKTOP_WINDOW_MIN_HEIGHT]) applies,
 *   but never pushes the window past a smaller work area: on a 640×480 desktop
 *   the work area wins, because opening a window larger than the screen is the
 *   bug this function exists to prevent;
 * - an unreadable work area (0, negative, NaN or infinite) keeps the desired
 *   size, so a platform that cannot report bounds still opens normally.
 */
fun fitWindowToScreen(
    desiredWidth: Float,
    desiredHeight: Float,
    screenWidth: Float,
    screenHeight: Float,
): WindowFit = WindowFit(
    width = fitWindowEdge(
        desired = desiredWidth,
        screen = screenWidth,
        floor = DESKTOP_WINDOW_MIN_WIDTH,
        fallback = DESKTOP_WINDOW_DEFAULT_WIDTH,
    ),
    height = fitWindowEdge(
        desired = desiredHeight,
        screen = screenHeight,
        floor = DESKTOP_WINDOW_MIN_HEIGHT,
        fallback = DESKTOP_WINDOW_DEFAULT_HEIGHT,
    ),
)

private fun fitWindowEdge(desired: Float, screen: Float, floor: Float, fallback: Float): Float {
    val want = if (desired.isFinite() && desired > 0f) desired else fallback
    if (!screen.isFinite() || screen <= 0f) return maxOf(want, floor)
    val capped = minOf(want, screen)
    return maxOf(capped, minOf(floor, screen))
}
