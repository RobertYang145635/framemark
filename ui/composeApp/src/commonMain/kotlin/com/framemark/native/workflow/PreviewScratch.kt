package com.framemark.native.workflow

import com.framemark.native.product.FmLog

/**
 * A-02: release one render's scratch files after the caller has the bytes.
 *
 * A native render returns a file path; the stage preview and the library hover
 * copy the bytes and then have no use for the file. On the phone there is no
 * janitor and no cache purge, so those per-request PNGs (and the `.geom.json`
 * sidecar the renderer writes next to them) accumulated forever: the audit found
 * 5,324 files / 1.94 GiB in `cache/x1`. Deletion is best-effort — a file locked
 * or already gone is not an error — and never throws into the render pipeline.
 */
object PreviewScratch {

    /** Deletes [path] and its geom sidecar; a null path/delete is a no-op. */
    fun release(platform: WorkflowPlatform?, path: String?) {
        val target = path?.takeIf { it.isNotBlank() } ?: return
        if (platform == null) return
        deleteFiles(platform, target)
    }

    private fun deleteFiles(platform: WorkflowPlatform, path: String) {
        delete(platform, path)
        delete(platform, "$path.geom.json")
    }

    private fun delete(platform: WorkflowPlatform, path: String) {
        // Non-`FM_` tag: a new `FM_*` literal would be treated as an evidence hook
        // by the arch gate (whose allowlist only shrinks). A failed best-effort
        // cleanup must still be visible, so it logs under its own name.
        runCatching { platform.deleteExportTemporary(path) }
            .onFailure { FmLog.warn("TEMP_DELETE_FAILED") { "path=$path reason=${it.message}" } }
    }
}
