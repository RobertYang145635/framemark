package com.framemark.native.product

/**
 * A-02: which files in a render cache directory are *scratch* the app owns, and
 * which of them are old enough (or numerous enough) to delete.
 *
 * Renders write a per-request PNG plus a sidecar `.geom.json` (Android) and,
 * for exports, a `.report.json`. Those are transient by contract: the UI reads
 * the bytes once and never needs the file again. The policy is pure so the age
 * and count rules are unit-tested without touching a filesystem; the platform
 * leader only lists and deletes.
 */
object PreviewScratchPolicy {

    data class Entry(val name: String, val sizeBytes: Long, val modifiedMillis: Long)

    /** File-name prefixes the renderer creates for a preview/export request. */
    val PREFIXES: List<String> = listOf("preview-", "export-")

    /** Sidecars the renderer writes next to its output. */
    val SUFFIXES: List<String> = listOf(".geom.json", ".report.json")

    /** Leftovers older than this belong to no live request. */
    const val DEFAULT_STALE_MILLIS: Long = 24L * 60 * 60 * 1000

    /** Hard cap on scratch files kept per cache directory. */
    const val DEFAULT_MAX_FILES: Int = 200

    fun isScratch(name: String): Boolean =
        PREFIXES.any { name.startsWith(it) } || SUFFIXES.any { name.endsWith(it) }

    /**
     * Scratch entries safe to delete: everything older than [staleMillis], plus the
     * oldest survivors until at most [maxFiles] remain. Non-scratch files (a QA
     * runner's own outputs, the evidence log) are never selected.
     */
    fun expired(
        entries: List<Entry>,
        nowMillis: Long,
        staleMillis: Long = DEFAULT_STALE_MILLIS,
        maxFiles: Int = DEFAULT_MAX_FILES,
    ): List<Entry> {
        val scratch = entries.filter { isScratch(it.name) }
        if (scratch.isEmpty()) return emptyList()
        val cutoff = nowMillis - staleMillis.coerceAtLeast(0L)
        val (stale, fresh) = scratch.partition { it.modifiedMillis <= cutoff }
        val cap = maxFiles.coerceAtLeast(0)
        val overflow = (fresh.size - cap).coerceAtLeast(0)
        if (overflow == 0) return stale
        return stale + fresh.sortedBy { it.modifiedMillis }.take(overflow)
    }
}
