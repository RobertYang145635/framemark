package com.framemark.native.workflow

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.framemark.native.library.LibraryPreviewLoader
import com.framemark.native.theme.FmRadii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One entry of the phone photo strip; [path] null is the bundled sample photo. */
class TrayPhoto(val id: Int, val path: String?, displayName: String? = null) {
    private var displayName by mutableStateOf(displayName)

    val label: String
        get() = if (path == null) {
            "示例照片"
        } else {
            displayName?.takeIf { it.isNotBlank() }
                ?: path.substringAfterLast('/').substringAfterLast('\\').ifBlank { "照片" }
        }

    internal fun updateDisplayName(displayName: String?) {
        this.displayName = displayName
    }
}

/**
 * UI-side list of the photos the editor can show. The engine still renders one
 * photo at a time — [selected] is what the shell writes into [PhotoSelection].
 */
class PhotoTray {
    val items = mutableStateListOf<TrayPhoto>()

    var selectedId by mutableIntStateOf(NO_SELECTION)
        private set

    private var nextId = 1

    val selected: TrayPhoto? get() = items.firstOrNull { it.id == selectedId }
    val isEmpty: Boolean get() = items.isEmpty()
    val count: Int get() = items.size

    /** Adds [paths] not already in the tray, applies display names, and selects [selectPath] (or the last one). */
    fun adopt(
        paths: List<String>,
        selectPath: String?,
        displayNamesByPath: Map<String, String> = emptyMap(),
    ): Int {
        var added = 0
        paths.forEach { path ->
            val existing = items.firstOrNull { it.path == path }
            if (existing == null) {
                items += TrayPhoto(nextId++, path, displayNamesByPath[path])
                added++
            } else if (displayNamesByPath.containsKey(path)) {
                existing.updateDisplayName(displayNamesByPath[path])
            }
        }
        val target = selectPath?.let { select -> items.firstOrNull { it.path == select } }
            ?: items.lastOrNull()
        if (target != null) selectedId = target.id
        return added
    }

    /** Updates labels from the project projection without changing tray order or selection. */
    fun updateDisplayNames(displayNamesByPath: Map<String, String>) {
        items.forEach { photo ->
            val path = photo.path ?: return@forEach
            if (displayNamesByPath.containsKey(path)) {
                photo.updateDisplayName(displayNamesByPath[path])
            }
        }
    }

    fun addSample(): TrayPhoto {
        val existing = items.firstOrNull { it.path == null }
        val entry = existing ?: TrayPhoto(nextId++, null).also { items += it }
        selectedId = entry.id
        return entry
    }

    fun select(id: Int) {
        if (items.any { it.id == id }) selectedId = id
    }

    /** Removes [id]; returns the entry that becomes selected, or null when the tray empties. */
    fun remove(id: Int): TrayPhoto? {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return selected
        items.removeAt(index)
        if (items.isEmpty()) {
            selectedId = NO_SELECTION
            return null
        }
        if (selectedId == id) {
            val next = items.getOrNull((index - 1).coerceAtLeast(0)) ?: items.first()
            selectedId = next.id
        }
        return selected
    }

    fun clear() {
        items.clear()
        selectedId = NO_SELECTION
    }

    companion object {
        const val NO_SELECTION = -1
    }
}

private object PhotoThumbCache {
    private val entries = object : LinkedHashMap<String, ImageBitmap>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > 16
    }

    fun get(key: String): ImageBitmap? = synchronized(entries) { entries[key] }

    fun put(key: String, bitmap: ImageBitmap) = synchronized(entries) { entries[key] = bitmap }
}

/**
 * Decodes a downscaled thumbnail for the photo strip / import hero. The platform
 * does the sampling (a 50 MP JPEG must never be decoded at full size); a missing
 * platform or an unreadable file falls back to the neutral placeholder.
 */
@Composable
fun PhotoThumbnail(
    path: String?,
    modifier: Modifier = Modifier,
    maxEdge: Int = 256,
    cornerRadius: Dp = FmRadii.tile,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val key = "$path@$maxEdge"
    val bitmap by produceState(initialValue = PhotoThumbCache.get(key), key) {
        if (value == null && path != null) {
            value = runCatching {
                withContext(Dispatchers.Default) {
                    val bytes = WorkflowEnv.platform?.thumbnail(path, maxEdge) ?: return@withContext null
                    LibraryPreviewLoader.decode(bytes)
                }
            }.getOrNull()?.also { PhotoThumbCache.put(key, it) }
        }
    }

    val shape = RoundedCornerShape(cornerRadius)
    Box(modifier = modifier.clip(shape).background(PhotoPlaceholder)) {
        val ready = bitmap
        if (ready != null) {
            Image(
                bitmap = ready,
                contentDescription = path?.substringAfterLast('/'),
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private val PhotoPlaceholder = Brush.linearGradient(
    listOf(Color(0xFFDCE6EE), Color(0xFFF2F5F7)),
)
