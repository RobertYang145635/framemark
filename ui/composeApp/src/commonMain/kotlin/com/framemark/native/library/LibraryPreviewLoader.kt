package com.framemark.native.library

import androidx.compose.ui.graphics.ImageBitmap
import com.framemark.native.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

private const val MAX_CACHED_PREVIEW_BYTES = 24L * 1024L * 1024L
private const val MAX_CACHED_PREVIEW_COUNT = 36
private const val MAX_CONCURRENT_PREVIEW_LOADS = 4
private const val ESTIMATED_BYTES_PER_PIXEL = 4L

private data class CachedPreview(
    val bitmap: ImageBitmap,
    val bytes: Long,
)

/**
 * The one code path that turns a [LibraryCard] into a bitmap. The UI tile uses it
 * per visible card, and the desktop `LibraryVerify` entry point uses it for all
 * 175 cards so evidence and runtime cannot drift apart.
 */
object LibraryPreviewLoader {
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val decodeSlots = Semaphore(MAX_CONCURRENT_PREVIEW_LOADS)
    private val cacheMutex = Mutex()
    private val cache = LinkedHashMap<String, CachedPreview>()
    private val inFlight = mutableMapOf<String, Deferred<ImageBitmap>>()
    private var cachedBytes = 0L

    /**
     * Reuses recently decoded previews and coalesces concurrent requests for the
     * same card. The retained decoded pixels are capped at 24 MiB / 36 images;
     * loaded bitmaps referenced by visible tiles are naturally kept by Compose.
     */
    suspend fun load(card: LibraryCard): ImageBitmap {
        require(card.previewFile.isNotBlank()) { "no preview row for ${card.id}" }

        cacheMutex.withLock { takeCached(card.id) }?.let { return it.bitmap }

        val pending = cacheMutex.withLock {
            inFlight[card.id] ?: run {
                lateinit var created: Deferred<ImageBitmap>
                created = workerScope.async(start = CoroutineStart.LAZY) {
                    try {
                        decodeSlots.withPermit {
                            val bitmap = decode(loadBytes(card))
                            cacheMutex.withLock { putCached(card.id, bitmap) }
                            bitmap
                        }
                    } finally {
                        cacheMutex.withLock {
                            if (inFlight[card.id] === created) inFlight.remove(card.id)
                        }
                    }
                }
                inFlight[card.id] = created
                created.start()
                created
            }
        }
        return pending.await()
    }

    @OptIn(ExperimentalResourceApi::class)
    suspend fun loadBytes(card: LibraryCard): ByteArray {
        require(card.previewFile.isNotBlank()) { "no preview row for ${card.id}" }
        return Res.readBytes(LibraryDataSource.PREVIEW_DIR + card.previewFile)
    }

    suspend fun decode(bytes: ByteArray): ImageBitmap = bytes.decodeToImageBitmap()

    private fun takeCached(cardId: String): CachedPreview? {
        val entry = cache.remove(cardId) ?: return null
        // Reinsert a cache hit so LinkedHashMap iteration order remains LRU.
        cache[cardId] = entry
        return entry
    }

    private fun putCached(cardId: String, bitmap: ImageBitmap) {
        cache.remove(cardId)?.let { cachedBytes -= it.bytes }

        val bytes = bitmap.width.toLong() *
            bitmap.height.toLong() *
            ESTIMATED_BYTES_PER_PIXEL
        if (bytes > MAX_CACHED_PREVIEW_BYTES) return

        cache[cardId] = CachedPreview(bitmap, bytes)
        cachedBytes += bytes

        val iterator = cache.entries.iterator()
        while (
            (cachedBytes > MAX_CACHED_PREVIEW_BYTES || cache.size > MAX_CACHED_PREVIEW_COUNT) &&
            iterator.hasNext()
        ) {
            val eldest = iterator.next()
            cachedBytes -= eldest.value.bytes
            iterator.remove()
        }
    }
}
