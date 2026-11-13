package com.framemark.native.product

/**
 * Display copy for failure text that was assembled out of engine diagnostics.
 *
 * Two rules from the UI copy audit (2026-11-13) live here:
 *
 *  - **Codes are not copy.** Several layers prefix a structured code to a user
 *    message — the phone shell (`"${error.code} ${error.message}"`), the project and
 *    preset stores (`store.presetError`, `store.saveError`), the photo queue
 *    (`BatchItem.error`) and the batch workspace (`failureText`). `FM-E-PRESET-001`
 *    is a support identifier: it stays in the log and in the structured failure
 *    object, and [human] strips it from the sentence that reaches the screen.
 *  - **Raw exception text is not copy either.** [failure] writes the throwable to the
 *    log under the caller's existing tag and returns a fixed Chinese sentence, so a
 *    Java message (`java.nio.file.AccessDeniedException: …`, `Cannot run program …
 *    e2b.exe`) can never become the line a user reads.
 *
 * Display only: nothing here rewrites a stored value, and the caller keeps its code.
 */
object NoticeText {

    /**
     * `FM-<X>-<AREA>` or `FM-<X>-<AREA>-<NNN>` at the start of a notice. The area is
     * upper case (`PRESET`, `ENGINE`, `WM`, `IO`, `INPUT`); the numeric suffix is
     * optional because the stores also emit area-only codes.
     */
    private val LEADING_CODE = Regex("^\\s*FM-[A-Z]-[A-Z]+(?:-[0-9A-Z]+)?\\s*[:：]?\\s*")

    /** A code the text carries further in, in parentheses: `…（FM-E-EXPORT-001）`. */
    private val INLINE_CODE = Regex("\\s*[（(]\\s*FM-[A-Z]-[A-Z]+(?:-[0-9A-Z]+)?\\s*[)）]\\s*")

    /**
     * What the user reads for [raw]: the sentence with any support code removed. Null
     * when nothing readable is left, so callers can hide the line instead of
     * rendering a bare identifier.
     */
    fun human(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val cleaned = INLINE_CODE.replace(LEADING_CODE.replace(text, ""), "").trim()
        return cleaned.ifBlank { null }
    }

    /**
     * A fixed sentence for a caught [t], with the raw text logged under [tag].
     *
     * [tag] is the caller's existing evidence tag as a literal — no new `FM_` literal
     * is introduced for copy fixes.
     */
    fun failure(tag: String, t: Throwable?, fallback: String): String {
        if (t != null) {
            FmLog.warn(tag) { "failure ${t::class.simpleName}: ${t.message}" }
        }
        return fallback
    }
}
