package com.framemark.native.shell.phone

/**
 * Which photo the border grid renders through each frame tile.
 *
 * The grid used to render the active photo unconditionally. A photo whose size
 * could not be measured — the usual case for a file without a readable header —
 * has `0 x 0`, so the engine produced nothing and every tile was blank: the page
 * looked empty and the user could not tell one frame from another.
 *
 * The rule is deliberately boring: use the user's photo only when it is known to
 * have a size, otherwise fall back to the bundled sample, so a frame is always
 * visible. Kept pure so the decision is tested without a composition.
 */
object PhoneBorderPreview {

    /** The photo path to render, or null when there is nothing at all to show. */
    fun photoPath(
        desktopActive: String?,
        activePath: String?,
        activeWidth: Int,
        activeHeight: Int,
        bundledSample: String?,
    ): String? {
        // Desktop already guards its own active photo (imported photos carry size).
        desktopActive?.takeIf { it.isNotBlank() }?.let { return it }
        if (!activePath.isNullOrBlank() && activeWidth > 0 && activeHeight > 0) return activePath
        return bundledSample?.takeIf { it.isNotBlank() }
    }
}
