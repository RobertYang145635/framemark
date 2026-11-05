package com.framemark.native.inspector

/**
 * WP-C §4 / UX-RESEARCH A-04 (the inspector part): text that does not fit must
 * behave the same way every time.
 *
 * Rule: shrink to at most [MIN_SCALE] of the available width, then truncate with
 * one ellipsis. Nothing is ever dropped silently and nothing is allowed to grow
 * over the neighbouring rows. The border's own typography (scaling inside the
 * rendered frame, "永不覆盖 Logo 区域") belongs to the engine and is unchanged by
 * this package — the WP-C report records that boundary.
 */
internal object FmTextFit {

    /** Smallest scale the UI is willing to shrink to before truncating. */
    const val MIN_SCALE = 0.8f

    const val ELLIPSIS = "…"

    /** Scale a font may use so the measured text fits [availableWidth]. */
    fun scaleFor(intrinsicWidth: Float, availableWidth: Float): Float {
        if (intrinsicWidth <= 0f || availableWidth <= 0f) return 1f
        return (availableWidth / intrinsicWidth).coerceIn(MIN_SCALE, 1f)
    }

    /** True when the text fits, either directly or after the allowed shrink. */
    fun fits(textWidth: Float, availableWidth: Float): Boolean =
        textWidth <= availableWidth || textWidth * MIN_SCALE <= availableWidth

    /**
     * Truncates to at most [maxChars] characters (ellipsis included) on a single
     * line. A shorter string is returned unchanged; blank input stays blank.
     */
    fun ellipsize(text: String, maxChars: Int): String {
        val singleLine = text.replace('\n', ' ').replace('\r', ' ').trim()
        if (maxChars <= 0) return ELLIPSIS
        if (singleLine.length <= maxChars) return singleLine
        if (maxChars == 1) return ELLIPSIS
        return singleLine.take(maxChars - 1).trimEnd() + ELLIPSIS
    }
}
