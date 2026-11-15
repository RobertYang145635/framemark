package com.framemark.native.engine

import com.framemark.native.product.FmLog
import com.framemark.native.product.PreviewScratchPolicy
import java.io.File

/**
 * A-02: sweep abandoned render scratch files from the phone's cache directory.
 *
 * A preview/export render writes a per-request PNG plus `.geom.json` under
 * `cache/x1`. Successful previews are released by their consumer
 * (`PreviewScratch`), but a crash, a process kill or an exception before the
 * consumer ran leaves files nothing will ever delete — Android has no equivalent
 * of the desktop `TempJanitor`. This runs once per launch, on the IO dispatcher,
 * and removes only [PreviewScratchPolicy.isScratch] names: QA runners keep their
 * own outputs.
 */
internal object RenderScratch {

    /** Sub-directory of `cacheDir` the renderer uses for its per-request files. */
    const val DIRECTORY = "x1"

    /**
     * Deletes stale/overflow scratch files under `cacheDir/x1`.
     *
     * @return how many files were removed (0 when the directory is absent).
     */
    fun sweep(
        cacheDir: File,
        nowMillis: Long = System.currentTimeMillis(),
        staleMillis: Long = PreviewScratchPolicy.DEFAULT_STALE_MILLIS,
        maxFiles: Int = PreviewScratchPolicy.DEFAULT_MAX_FILES,
    ): Int {
        val dir = File(cacheDir, DIRECTORY)
        if (!dir.isDirectory) return 0
        val files = dir.listFiles()?.filter { it.isFile } ?: return 0
        if (files.isEmpty()) return 0
        val entries = files.map {
            PreviewScratchPolicy.Entry(it.name, it.length(), it.lastModified())
        }
        val doomed = PreviewScratchPolicy
            .expired(entries, nowMillis, staleMillis, maxFiles)
            .map { it.name }
            .toHashSet()
        if (doomed.isEmpty()) return 0
        var removed = 0
        var bytes = 0L
        files.forEach { file ->
            if (file.name in doomed) {
                val size = file.length()
                if (file.delete()) {
                    removed++
                    bytes += size
                }
            }
        }
        // Non-`FM_` tag on purpose: the arch gate treats new `FM_*` literals as
        // evidence-hook leakage and its allowlist only shrinks, so a routine
        // maintenance sweep logs under its own name instead.
        FmLog.info("RENDER_SCRATCH_SWEEP") {
            "dir=${dir.absolutePath} scanned=${files.size} removed=$removed bytes=$bytes"
        }
        return removed
    }
}
