package com.framemark.native.shell.phone

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The dark stage's two horizontal bands (报告 §7.2 / 演示稿 `.pstage`).
 *
 *  - **the photo band**: the photo area, inset `14 / 16 / 58` from the stage (the
 *    showcase's `--pad: 14px 16px 58px`). In focus mode it keeps only 12 dp below;
 *  - **the pill band**: the strip under it where the border-name pill (bottom-left)
 *    and 按住看原图 (bottom-right) live.
 *
 * The editor and the failed-preview placeholder both read these constants, so "the
 * placeholder chip can never sit on the pills" is arithmetic over one shared
 * definition instead of two copies of the same numbers.
 */
object PhoneStageBands {
    /** Photo band: 14 dp above. */
    val PhotoTop: Dp = 14.dp

    /** Photo band: 16 dp on both sides. */
    val PhotoSide: Dp = 16.dp

    /** 12 dp inset from the stage's bottom-left / bottom-right corner. */
    val PillInset: Dp = 12.dp

    /**
     * The pills' band: 按住看原图 keeps a [PhoneTouchTarget] touch box and both pills
     * sit [PillInset] above the bottom, so the band is 56 dp.
     */
    val PillBand: Dp = PhoneTouchTarget + PillInset

    /** The photo band's bottom inset: the pill band plus 2 dp of breathing room. */
    val PhotoBottom: Dp = PillBand + 2.dp

    /** Focus mode hides both pills and keeps 12 dp below the photo. */
    val PhotoBottomFocus: Dp = 12.dp

    /** The photo band's bottom inset for the current stage mode. */
    fun photoBottom(focusMode: Boolean): Dp = if (focusMode) PhotoBottomFocus else PhotoBottom
}
