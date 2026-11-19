package com.framemark.native.shell

import android.os.Build

/**
 * Android blurs a recorded backdrop with `RenderEffect`, which exists from API 31.
 *
 * Below that the capsule must **not** draw the copy: an unblurred, offset duplicate
 * of the photo inside the capsule is a ghost, not glass. `FmNavGlass.decide` then
 * picks the tint-only fill.
 */
internal actual fun fmBackdropBlurSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
