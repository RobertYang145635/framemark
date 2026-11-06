package com.framemark.native.shell.desktop

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryPreview
import com.framemark.native.library.LibraryPreviewLoader
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.NativeEngine
import com.framemark.native.workflow.WorkflowEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 卡片缩略图 = **当前舞台照片**套上该卡片的边框（演示稿 `D.thumbs[c.id]` 的等价物）。
 *
 * 走工程里已有的预览通道：`NativeEngine.preview`（与舞台同一渲染器）+ `LibraryPreviewLoader`
 * 解码，不碰引擎、数据模型或渲染契约。渲染参数用卡片自带默认值（`styleId` + 空覆盖），
 * 和演示稿缩略图“默认设计 + 当前照片”的语义一致。
 *
 * 失败 / 没有照片 / 平台不支持时退回随包预览图（[LibraryPreview]），不拿别的照片凑。
 */
internal object DesktopLibraryPhotoThumbs {
    /** 缩略图渲染边长；卡片在 1520 宽窗口里约 131dp，168 覆盖 1.2x。 */
    const val MAX_EDGE = 168

    private const val MAX_ENTRIES = 64

    /**
     * 引擎一次缩略图渲染约 0.8–2.3 s（实测日志见报告）。串行 + 冷启动退让，
     * 避免左栏一百多张卡片把舞台的首次渲染挤到后面。
     */
    private val slots = Semaphore(1)

    private val cache = object : LinkedHashMap<String, ImageBitmap>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MAX_ENTRIES
    }

    /** 渲染失败的卡片只记一次，避免每次滚动都重试同一张。 */
    private val failures = LinkedHashSet<String>()

    private fun key(cardId: String, photoPath: String): String = "$cardId|$photoPath"

    fun cached(cardId: String, photoPath: String): ImageBitmap? =
        synchronized(cache) { cache[key(cardId, photoPath)] }

    /**
     * 开工前的等待：冷缓存时让舞台先渲染完（约 1.4 s），热缓存时只为滚动去抖。
     */
    fun delayBeforeRender(): Long {
        val cold = synchronized(cache) { cache.isEmpty() }
        return if (cold) COLD_START_DELAY_MS else WARM_DELAY_MS
    }

    private fun failed(cardId: String): Boolean = synchronized(failures) { cardId in failures }

    private fun recordFailure(cardId: String) {
        synchronized(failures) { failures.add(cardId) }
    }

    suspend fun load(card: LibraryCard, photoPath: String): ImageBitmap? {
        cached(card.id, photoPath)?.let { return it }
        if (failed(card.id)) return null
        if (!NativeEngine.supports(card.id)) return null
        return slots.withPermit {
            cached(card.id, photoPath)?.let { return@withPermit it }
            if (failed(card.id)) return@withPermit null
            val rendered = try {
                NativeEngine.preview(
                    card = card,
                    design = defaultDesign(card),
                    maxEdge = MAX_EDGE,
                    overlay = null,
                    photoPath = photoPath,
                )
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                recordFailure(card.id)
                println("FM_LIB_THUMB_FAIL card=${card.id} reason=${t.message}")
                null
            } ?: return@withPermit null
            val bytes = try {
                WorkflowEnv.platform?.readBytes(rendered.path)
            } catch (t: Throwable) {
                recordFailure(card.id)
                println("FM_LIB_THUMB_FAIL card=${card.id} reason=read:${t.message}")
                null
            }
            if (bytes == null) recordFailure(card.id)
            if (bytes == null) return@withPermit null
            val bitmap = try {
                withContext(Dispatchers.Default) { LibraryPreviewLoader.decode(bytes) }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                recordFailure(card.id)
                println("FM_LIB_THUMB_FAIL card=${card.id} reason=decode:${t.message}")
                null
            }
            if (bitmap != null) {
                synchronized(cache) { cache[key(card.id, photoPath)] = bitmap }
            }
            bitmap
        }
    }

    /** 卡片自身的默认设计（与 `NativeEngine.designOf` 未改动时给引擎的输入同构）。 */
    private fun defaultDesign(card: LibraryCard): DesignSnapshot = DesignSnapshot(
        cardId = card.id,
        styleId = card.systemStyle,
        params = emptyMap(),
        text = emptyMap(),
    )
}

/**
 * 缩略图内容：有照片且引擎支持时渲染“照片 + 该边框”，否则回退随包预览。
 * `photoPath` 变化（切照片、导入照片）会重新渲染。
 */
@Composable
internal fun DesktopLibraryThumb(
    card: LibraryCard,
    photoPath: String?,
    modifier: Modifier = Modifier,
) {
    if (photoPath.isNullOrBlank()) {
        LibraryPreview(card = card, modifier = modifier)
        return
    }
    val bitmap by produceState<ImageBitmap?>(
        initialValue = DesktopLibraryPhotoThumbs.cached(card.id, photoPath),
        card.id,
        photoPath,
    ) {
        // 快速滚过卡片时不再为每一张排队一次引擎渲染；冷启动先让舞台渲染完。
        delay(DesktopLibraryPhotoThumbs.delayBeforeRender())
        value = DesktopLibraryPhotoThumbs.cached(card.id, photoPath)
            ?: DesktopLibraryPhotoThumbs.load(card, photoPath)
    }
    val ready = bitmap
    if (ready == null) {
        LibraryPreview(card = card, modifier = modifier)
    } else {
        Image(
            bitmap = ready,
            contentDescription = card.name,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    }
}

/** 缩略图渲染去抖（ms）：冷启动让舞台先出图，热缓存只为滚动去抖。 */
internal const val COLD_START_DELAY_MS = 1400L
internal const val WARM_DELAY_MS = 250L

/** 当前舞台照片路径；工程还没有照片时为 null（缩略图退回随包预览）。 */
@Composable
internal fun desktopActivePhotoPath(): String? =
    WorkflowEnv.projectHolder?.activePhoto?.path?.takeIf { it.isNotBlank() }

/** 缩略图里边框图的投影（styles.css `.card .th img`：0 1px 2px rgba(16,24,40,.16)）。 */
internal val DesktopCardImageShadow = 2.dp
