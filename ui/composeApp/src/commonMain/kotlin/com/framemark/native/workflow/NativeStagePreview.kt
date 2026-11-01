package com.framemark.native.workflow

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import com.framemark.native.LaunchOptions
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryPreview
import com.framemark.native.library.LibraryPreviewLoader
import com.framemark.native.library.PreviewDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

private const val PREVIEW_MAX_EDGE = 1280
private const val DEBOUNCE_MS = 300L

/** Only one preview engine may run at a time; newer requests wait instead of stacking. */
private val previewGate = Mutex()

/** Last few rendered previews so switching cards back and forth is instant. */
private object RenderCache {
    private val entries = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            val evict = size > 6
            if (evict) rects.remove(eldest?.key)
            return evict
        }
    }

    fun get(key: String): ByteArray? = synchronized(entries) { entries[key] }

    private val rects = HashMap<String, ImageRect?>()

    fun put(key: String, bytes: ByteArray, rect: ImageRect? = null) = synchronized(entries) {
        rects[key] = rect
        entries[key] = bytes
    }

    fun rect(key: String): ImageRect? = synchronized(entries) { rects[key] }
}

private sealed interface StageRender {
    data object Loading : StageRender
    data class Ready(val bitmap: ImageBitmap, val photoRect: ImageRect? = null) : StageRender
    data class Failed(val reason: String) : StageRender
}

/**
 * Stage preview for the cards wired to the real engine: the static border image
 * stays visible while the native render runs, then the rendered image replaces
 * it. Edits re-render after a short debounce; leaving a card cancels its render.
 */
@Composable
fun NativeStagePreview(
    card: LibraryCard,
    design: DesignSnapshot,
    modifier: Modifier = Modifier,
    /** Committed watermark; folded into the cache key so a change re-renders. */
    overlay: OverlaySpec? = null,
    /** Per-call source photo; null keeps the manifest sample photo. */
    photoPath: String? = null,
    /** Rendered pixel size + the engine photo box (the overlay anchor). */
    onRendered: ((Int, Int, ImageRect?) -> Unit)? = null,
    /** Reports a render/decode failure; the static card preview stays visible. */
    onFailed: ((String) -> Unit)? = null,
) {
    val key = buildString {
        append(card.id)
        append('|').append(design.params.hashCode())
        append('|').append(design.logoBrand ?: "-")
        append('|').append(design.text.hashCode())
        append('|').append(PhotoSelection.version)
        append('|').append(photoPath ?: "-")
        append('|').append(
            overlay?.let { "${it.path}:${it.x},${it.y},${it.w},${it.h},${it.opacity}" } ?: "-",
        )
    }
    val wallStart = remember(key) { kotlin.time.TimeSource.Monotonic.markNow() }
    var state by remember(card.id) { mutableStateOf<StageRender>(StageRender.Loading) }
    // A re-render must not blank the stage: the previous frame stays until the new
    // one is ready, with a small busy badge on top.
    var busy by remember(card.id) { mutableStateOf(false) }

    LaunchedEffect(key) {
        val cached = RenderCache.get(key)
        if (cached != null) {
            state = runCatching {
                StageRender.Ready(LibraryPreviewLoader.decode(cached), RenderCache.rect(key))
            }.getOrElse { StageRender.Failed(it.message ?: "decode failed") }
            busy = false
            return@LaunchedEffect
        }
        busy = true
        delay(DEBOUNCE_MS)
        try {
            val image = previewGate.withLock {
                coroutineContext.ensureActive()
                NativeEngine.preview(
                    card,
                    design,
                    LaunchOptions.previewMaxEdge ?: PREVIEW_MAX_EDGE,
                    overlay,
                    photoPath,
                )
            }
            if (image == null) {
                state = StageRender.Failed("no native renderer")
                return@LaunchedEffect
            }
            val bytes = withContext(Dispatchers.Default) {
                WorkflowEnv.platform?.readBytes(image.path)
            } ?: throw IllegalStateException("cannot read ${image.path}")
            val bitmap = withContext(Dispatchers.Default) { LibraryPreviewLoader.decode(bytes) }
            RenderCache.put(key, bytes, image.photoRect)
            state = StageRender.Ready(bitmap, image.photoRect)
            busy = false
            image.photoRect?.let { r -> PreviewGeometry.record(card.id, r.w / r.h.coerceAtLeast(1f)) }
            println("FM_PREVIEW_RENDER card=${card.id} ${image.width}x${image.height} ms=${image.ms}")
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val reason = t.message ?: t::class.simpleName ?: "render failed"
            PreviewDiagnostics.record(card.id, reason)
            println("FM_PREVIEW_RENDER_FAIL card=${card.id} reason=$reason")
            state = StageRender.Failed(reason)
            busy = false
            onFailed?.invoke(reason)
        }
    }

    Box(modifier = modifier) {
        val ready = state as? StageRender.Ready
        // The static card stays as the loading placeholder only; once the native
        // frame exists it is the whole stage image (stale preview bands would
        // read as part of the photo).
        if (ready == null) {
            LibraryPreview(card = card, modifier = Modifier.fillMaxSize())
        } else {
            var reported by remember(key) { mutableStateOf(false) }
            var layoutLogged by remember(key) { mutableStateOf(false) }
            LaunchedEffect(key, ready.bitmap) {
                if (!reported) {
                    reported = true
                    onRendered?.invoke(ready.bitmap.width, ready.bitmap.height, ready.photoRect)
                }
            }
            Image(
                bitmap = ready.bitmap,
                contentDescription = card.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coords ->
                        if (!layoutLogged) {
                            layoutLogged = true
                            println(
                                "FM_PREVIEW_LAYOUT card=${card.id} box=${coords.size.width}x${coords.size.height} " +
                                    "bitmap=${ready.bitmap.width}x${ready.bitmap.height} " +
                                    "wallMs=${wallStart.elapsedNow().inWholeMilliseconds}",
                            )
                        }
                    },
            )
        }
        if (busy) {
            // Busy badge instead of a blank stage: the previous frame stays visible
            // while the engine runs (~1.3 s), so an edit never looks like a freeze.
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(22.dp),
                strokeWidth = 2.dp,
                color = Color(0xFF2F75E5),
            )
        }
    }
}
