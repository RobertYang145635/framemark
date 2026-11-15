package com.framemark.native.product

/**
 * Ownership handoff for a preview render's scratch files.
 *
 * Android's preview renderer is a blocking JNI call inside `withContext(IO)`. If
 * its coroutine is cancelled while the engine runs, `withContext` completes the
 * block — the PNG and geom are already written — and *then* throws
 * CancellationException: the block's own `catch` has already left, and the
 * consumer never receives the rendered image to release. Offering the path for
 * the duration of the call, and clearing it only when the value is actually
 * returned, closes that window. The renderer passes a plain delete lambda so this
 * helper stays in `product` and the engine layer keeps its allowed import
 * direction.
 *
 * Lives in `product` (not `workflow`) because `engine` may depend on `product`
 * but not on `workflow` — see the ARCH1 layer ladder test.
 */
object PreviewHandoff {

    suspend fun <T> withPreviewHandoff(
        release: (String) -> Unit,
        block: suspend (offer: (path: String) -> Unit) -> T,
    ): T {
        var offered: String? = null
        try {
            val result = block { path -> offered = path }
            offered = null
            return result
        } catch (t: Throwable) {
            offered?.let { path ->
                FmLog.info("PREVIEW_HANDOFF_RELEASE") {
                    "path=$path reason=${t::class.simpleName ?: "Throwable"}"
                }
                runCatching { release(path) }
            }
            throw t
        }
    }
}
