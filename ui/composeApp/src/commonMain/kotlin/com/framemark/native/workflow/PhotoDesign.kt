package com.framemark.native.workflow

/**
 * One tray photo's own design: which border it uses, the control overrides that
 * border was tuned with, and its watermark. Photos are paired independently so a
 * batch of photos can share a base border and still carry per-photo edits.
 */
data class PhotoDesign(
    val cardId: String? = null,
    val values: Map<String, Any> = emptyMap(),
    val watermark: WatermarkSpec? = null,
) {
    /** True when this photo differs from the design last applied to all photos. */
    fun customizedAgainst(base: PhotoDesign?): Boolean {
        if (base == null) return false
        return cardId != base.cardId ||
            values != base.values ||
            watermark != base.watermark
    }
}
