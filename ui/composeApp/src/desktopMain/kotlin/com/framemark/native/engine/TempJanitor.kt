package com.framemark.native.engine

import java.io.File
import com.framemark.native.product.FmLog

/**
 * Engine scratch-directory janitor (WP-X1 / AU1-02).
 *
 * Every render used to leave `raw-*.png` + `report-*.json` (and every export a
 * canvas-sized raw PNG) behind forever because nothing ever called `delete()`.
 * The bridge now deletes its own files in a `finally`, and this sweeps what a
 * crashed or killed process could not: anything under the work directory with an
 * engine scratch prefix that is older than [staleMillis].
 *
 * Kept as its own object so the policy is testable without a renderer or a
 * running desktop shell.
 */
internal object TempJanitor {
    /** Prefixes the engine and the export encoder create inside the work dir. */
    val PREFIXES: List<String> = listOf("raw-", "report-", "preview-", "e5-")

    /** Default age after which a leftover is considered abandoned. */
    const val DEFAULT_STALE_MS: Long = 24L * 60 * 60 * 1000

    data class SweepResult(val removed: Int, val bytes: Long, val kept: Int)

    fun isScratch(name: String): Boolean = PREFIXES.any { name.startsWith(it) }

    /**
     * Deletes scratch files in [dir] whose last-modified time is older than
     * `now - staleMillis`. Files that are younger may belong to a live render in
     * another window, so only age decides.
     */
    fun sweep(
        dir: File,
        now: Long = System.currentTimeMillis(),
        staleMillis: Long = DEFAULT_STALE_MS,
    ): SweepResult {
        if (!dir.isDirectory) return SweepResult(0, 0, 0)
        val cutoff = now - staleMillis
        var removed = 0
        var bytes = 0L
        var kept = 0
        dir.listFiles()?.forEach { file ->
            if (!file.isFile || !isScratch(file.name)) return@forEach
            if (file.lastModified() > cutoff) {
                kept++
                return@forEach
            }
            val size = file.length()
            if (file.delete()) {
                removed++
                bytes += size
            }
        }
        return SweepResult(removed, bytes, kept)
    }

    /** Deletes one file; a failure is reported, never thrown. */
    fun discard(file: File): Boolean {
        if (!file.exists()) return true
        if (file.delete()) return true
        FmLog.warn("FM_TEMP_DELETE_FAILED") { "path=${file.absolutePath}" }
        return false
    }
}
