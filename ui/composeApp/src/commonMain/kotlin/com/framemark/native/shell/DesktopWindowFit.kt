package com.framemark.native.shell

/** A window size in dp, after fitting a desired size to the screen work area. */
data class WindowFit(val width: Float, val height: Float)

/** First-launch size used when `FM_WINDOW` is unset or unusable. */
const val DESKTOP_WINDOW_DEFAULT_WIDTH: Float = 1280f
const val DESKTOP_WINDOW_DEFAULT_HEIGHT: Float = 800f

/**
 * Smallest window the desktop shell opens at.
 *
 * AU4-02: the old floor was 720 dp, which is *above* the three-column floor
 * ([desktopColumnMinWidth], 640 dp of content, and V2 draws the columns flush to
 * the window edge so content == window width). A window that may never be
 * narrower than the three-column floor can never reach the folded single-pane
 * branch in `computeDesktopColumns`, so the "narrow window folds the rails" rule
 * was dead code — a 720 dp window kept all three columns and clipped the top bar.
 *
 * The floor therefore sits [DESKTOP_WINDOW_FOLD_MARGIN] dp *below* the
 * three-column floor: a 600–639 dp window shows the single-pane layout chosen by
 * the pane chips, and every wider window shows three columns. The floor is
 * derived from the tokens, so moving a rail minimum moves this with it. Height
 * stays at 520 dp: that keeps a usable stage under the 52 dp bar plus the photo
 * strip.
 */
const val DESKTOP_WINDOW_FOLD_MARGIN: Float = 40f
val DESKTOP_WINDOW_MIN_WIDTH: Float get() = desktopColumnMinWidth() - DESKTOP_WINDOW_FOLD_MARGIN
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
