package com.framemark.native.inspector

import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.isEditableTextSlot

/**
 * WP-C §4.6 / UX-RESEARCH A-03 (inspector part): a photo without EXIF, or with
 * some fields missing, must not look like an empty design.
 *
 * The engine renders the border text and owns what appears there; this package
 * only fixes the **inspector**:
 *  - a text row whose renderer content is empty shows `—` instead of a blank
 *    line that reads as "nothing to see here";
 *  - the panel says `未读到拍摄参数` once, not on every row.
 *
 * Detection is honest about what the UI knows: a current text row with no drawn
 * text and no raw source is a missing field. The hint is shown once per card,
 * because the rows of one card share the photo's metadata.
 */
internal object ExifGapNotice {

    /** Placeholder for a field the renderer could not fill. */
    const val MISSING_FIELD = "—"

    /** Text of the one-time light hint. */
    const val HINT = "未读到拍摄参数"

    /** A row counts as missing when it has neither drawn text nor raw content. */
    fun isMissing(slot: TextSlot): Boolean =
        slot.isEditableTextSlot() && slot.text.isBlank() && slot.raw.isBlank()

    /** Missing rows of this render, in report order. */
    fun missingFields(slots: List<TextSlot>): List<TextSlot> =
        slots.filter(::isMissing)

    /** Whether the panel should say [HINT] for these rows. */
    fun shouldShowHint(slots: List<TextSlot>): Boolean = missingFields(slots).isNotEmpty()

    /** Line shown above the text input: the drawn text, or the missing marker. */
    fun rowSummary(text: String, raw: String): String {
        val joined = text.ifBlank { raw }
            .lineSequence()
            .map { it.trim() }
            .filter(String::isNotEmpty)
            .joinToString(" ")
        return joined.ifBlank { MISSING_FIELD }
    }
}
