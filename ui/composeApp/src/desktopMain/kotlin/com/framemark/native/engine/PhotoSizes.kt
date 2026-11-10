package com.framemark.native.engine

import com.framemark.native.workflow.PhotoMetadata
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import java.awt.image.BufferedImage
import com.framemark.native.product.FmLog

/**
 * The **single** source of photo dimensions for the desktop renderer (WP-X1 /
 * AU1-01).
 *
 * `DesktopEngineBridge` used to call `ImageIO.read(photo)` only to fill the
 * contract document's `photo.w/h`: a full decode per render (8192×6144 = 233 MB /
 * 718–849 ms; 11648×8736 = 463 MB / 1.1–1.4 s), repeated independently by the
 * stage preview, the card thumbnail and the export chains.
 *
 * There is now one function and one cache:
 * 1. dimensions already known from the project's `PhotoRef` (primed on import /
 *    select) are used first — no I/O at all;
 * 2. otherwise the header is read (`ImageIO` reader width/height, WebP RIFF
 *    fallback) — 3 MB / 105–184 ms on the same files;
 * 3. the result is memoised per `path@lastModified@length` so a second chain in
 *    the same session reads nothing.
 */
object PhotoSizes {
    private data class Key(val path: String, val modified: Long, val length: Long)

    private val cache = LinkedHashMap<Key, Pair<Int, Int>>()
    private val primed = HashMap<String, Pair<Int, Int>>()
    private const val MAX_CACHE = 64

    /** Record dimensions the project already knows (a `PhotoRef`), keyed by path. */
    @Synchronized
    fun prime(path: String?, widthPx: Int?, heightPx: Int?) {
        if (path.isNullOrBlank()) return
        if (widthPx == null || heightPx == null || widthPx <= 0 || heightPx <= 0) return
        primed[File(path).absolutePath] = widthPx to heightPx
    }

    /** Dimensions for [path]: primed first, then header, then `null`. */
    @Synchronized
    fun of(path: String?): Pair<Int, Int>? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        val abs = file.absoluteFile.path
        val key = Key(abs, runCatching { file.lastModified() }.getOrDefault(0L), runCatching { file.length() }.getOrDefault(0L))
        cache[key]?.let { return it }
        primed[abs]?.let { known ->
            remember(key, known)
            return known
        }
        val read = runCatching { PhotoMetadata.read(file)?.let { it.width to it.height } }.getOrNull()
            ?: return null
        remember(key, read)
        return read
    }

    private fun remember(key: Key, value: Pair<Int, Int>) {
        if (cache.size >= MAX_CACHE) {
            val oldest = cache.keys.firstOrNull() ?: return
            cache.remove(oldest)
        }
        cache[key] = value
    }

    /**
     * Decode [file] at no more than roughly the target size (WP-X1 / AU1-03).
     *
     * The old export path did `ImageIO.read(raw)` — a full-size canvas in the JVM
     * heap (233 MB for 8192×6144) — and then shrank it. `sourceSubsampling` reads
     * only every n-th row/column, so an 8192×6144 canvas exported at 4096 becomes
     * a ~2048×1536 raster instead of a 50 MP one.
     */
    fun readSubsampled(file: File, targetWidth: Int, targetHeight: Int): BufferedImage? {
        val start = System.nanoTime()
        var reader: javax.imageio.ImageReader? = null
        try {
            ImageIO.createImageInputStream(file)?.use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) return ImageIO.read(file)
                reader = readers.next()
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                val step = subsampleStep(width, height, targetWidth, targetHeight)
                val param: ImageReadParam = reader.defaultReadParam
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0)
                val image = reader.read(0, param)
                FmLog.info("FM_PHOTO_READ") {
                    "path=${file.name} source=${width}x${height} " +
                        "read=${image.width}x${image.height} step=$step " +
                        "ms=${(System.nanoTime() - start) / 1_000_000}"
                }
                return image
            }
        } catch (e: Throwable) {
            FmLog.info("FM_PHOTO_READ_FALLBACK") { "path=${file.name} error=${e.message ?: e::class.simpleName}" }
        } finally {
            runCatching { reader?.dispose() }
        }
        return ImageIO.read(file)
    }

    private fun subsampleStep(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
        val factor = minOf(width.toDouble() / targetWidth, height.toDouble() / targetHeight)
        // Keep one extra pixel of headroom so the final bicubic pass downscales
        // rather than upscales; never subsample when it would lose the target.
        return if (factor < 1.5) 1 else (factor - 0.5).toInt().coerceAtLeast(1)
    }
}
