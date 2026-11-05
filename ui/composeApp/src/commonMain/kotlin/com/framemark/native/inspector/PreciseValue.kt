package com.framemark.native.inspector

/**
 * Parses the text typed into a slider's precise-entry dialog.
 *
 * The contract is deliberately strict: no rounding, no clamping and no silent
 * coercion. A slider writes only a value the user could also reach with the
 * track, so anything that is empty, not a number, `NaN`/`Infinity`, outside
 * `[min, max]`, or described by non-finite bounds is rejected with `null` and
 * the caller keeps the old value.
 */
fun parsePreciseNumber(raw: String, min: Float, max: Float): Float? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val value = text.toFloatOrNull() ?: return null
    if (!value.isFinite()) return null
    if (!min.isFinite() || !max.isFinite() || min > max) return null
    if (value < min || value > max) return null
    return value
}
