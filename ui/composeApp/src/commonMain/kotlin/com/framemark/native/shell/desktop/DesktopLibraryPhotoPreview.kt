package com.framemark.native.shell.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryPreview
import com.framemark.native.library.LibraryPreviewLoader
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.NativeEngine
import com.framemark.native.workflow.PreviewScratch
import com.framemark.native.workflow.PreviewSuspension
import com.framemark.native.workflow.WorkflowEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.framemark.native.product.FmLog

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
    /**
     * 缩略图渲染边长下限 / 上限（像素）。
     *
     * 旧实现写死 168 px（桌面左栏 131 dp 的 1.2 倍），但手机边框库的卡片宽 200 dp 左右、
     * 3x 屏就是 ~600 px，168 的图被放大 3–4 倍，边框上的品牌、F/MM/S 全部糊成一片。
     * 现在按卡片实际像素尺寸取 `[MIN_EDGE, MAX_EDGE]`，同一张卡在不同界面上各用各的档位。
     */
    const val MIN_EDGE = 192
    const val MAX_EDGE = 640

    /** 缩略图缓存预算：手机一张 640×480 的 ARGB 图约 1.2 MB，24 MB 约 20 张。 */
    private const val MAX_ENTRIES = 48
    private const val MAX_BYTES = 24L * 1024 * 1024

    /** 把布局给出的边长折成渲染档位（像素）。 */
    fun edgeFor(targetPx: Int): Int =
        if (targetPx <= 0) MIN_EDGE else targetPx.coerceIn(MIN_EDGE, MAX_EDGE)

    /**
     * 引擎一次缩略图渲染约 0.8–2.3 s（实测日志见报告）。串行 + 冷启动退让，
     * 避免左栏一百多张卡片把舞台的首次渲染挤到后面。
     */
    private val slots = Semaphore(1)

    private val cache = LinkedHashMap<String, ImageBitmap>(MAX_ENTRIES, 0.75f, true)
    private var cacheBytes = 0L

    /** 渲染失败的卡片只记一次，避免每次滚动都重试同一张。 */
    private val failures = LinkedHashSet<String>()

    private fun key(cardId: String, photoPath: String, edge: Int): String = "$cardId|$photoPath|$edge"

    fun cached(cardId: String, photoPath: String, edge: Int): ImageBitmap? =
        synchronized(cache) { cache[key(cardId, photoPath, edge)] }

    private fun remember(cardId: String, photoPath: String, edge: Int, bitmap: ImageBitmap) {
        synchronized(cache) {
            val k = key(cardId, photoPath, edge)
            cache.remove(k)?.let { cacheBytes -= it.byteSize() }
            cache[k] = bitmap
            cacheBytes += bitmap.byteSize()
            val it = cache.entries.iterator()
            while ((cache.size > MAX_ENTRIES || cacheBytes > MAX_BYTES) && it.hasNext()) {
                val eldest = it.next()
                if (eldest.key == k) continue
                cacheBytes -= eldest.value.byteSize()
                it.remove()
            }
        }
    }

    private fun ImageBitmap.byteSize(): Long = width.toLong() * height.toLong() * 4L

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

    suspend fun load(card: LibraryCard, photoPath: String, edge: Int): ImageBitmap? {
        cached(card.id, photoPath, edge)?.let { return it }
        if (failed(card.id)) return null
        if (!NativeEngine.supports(card.id)) return null
        return slots.withPermit {
            cached(card.id, photoPath, edge)?.let { return@withPermit it }
            if (failed(card.id)) return@withPermit null
            // A batch export owns the engine for its whole run: a library thumbnail is a
            // prefetch, never worth taking a core (or the GPU context) from it. The wait
            // happens inside the one render slot, so at most one thumbnail is parked on
            // the gate and the rest keep queueing behind it exactly as before.
            PreviewSuspension.awaitResumed()
            val rendered = try {
                NativeEngine.preview(
                    card = card,
                    design = defaultDesign(card),
                    maxEdge = edge,
                    overlay = null,
                    photoPath = photoPath,
                )
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                recordFailure(card.id)
                FmLog.warn("FM_LIB_THUMB_FAIL") { "card=${card.id} reason=${t.message}" }
                null
            } ?: return@withPermit null
            try {
            val bytes = try {
                WorkflowEnv.platform?.readBytes(rendered.path)
            } catch (t: Throwable) {
                recordFailure(card.id)
                FmLog.warn("FM_LIB_THUMB_FAIL") { "card=${card.id} reason=read:${t.message}" }
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
                FmLog.warn("FM_LIB_THUMB_FAIL") { "card=${card.id} reason=decode:${t.message}" }
                null
            }
            if (bitmap != null) {
                remember(card.id, photoPath, edge, bitmap)
            }
            bitmap
            } finally {
                // A-02: the hover preview owns this file only until its bytes are
                // decoded; leaving it behind is what let the phone's cache grow.
                PreviewScratch.release(WorkflowEnv.platform, rendered.path)
            }
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
 * 边框库缩略图：**这张边框自己的样子**（随包预览图），不套用户自己的照片。
 *
 * 2026-11-20 改的（两个真机问题一起解决）：
 *  1. 以前用「当前照片 + 该边框」实时渲染。照片没有 EXIF 或只缺几项时，凡是只差参数行的
 *     边框都渲染成一模一样，用户既看不出边框能画什么，也分不出这些边框的区别；而随包
 *     预览图是用带完整拍摄信息的样片渲染的，参数行、字标、日期都在。
 *  2. 每张卡一次引擎渲染、还带 250–1400 ms 去抖，所以「划到了才开始渲染」。随包预览是
 *     APK 里的文件（480×360，共 1.5 MB），解码走 [LibraryPreviewLoader] 的受限缓存，
 *     打开即有。
 *
 * `photoPath` 只对**没有随包预览**的少数卡兜底（用内置样片渲染），不再参与常规路径。
 */
@Composable
internal fun DesktopLibraryThumb(
    card: LibraryCard,
    photoPath: String?,
    modifier: Modifier = Modifier,
) {
    if (card.previewFile.isNotBlank() || photoPath.isNullOrBlank()) {
        LibraryPreview(card = card, modifier = modifier)
        return
    }
    BoxWithConstraints(modifier = modifier) {
        // 卡片实际占多少像素，就按那个尺寸渲染：旧实现固定 168 px，
        // 手机边框库（3x 屏、卡片约 200 dp）会把它放大 3–4 倍，边框细节全糊掉。
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
        val heightPx = if (constraints.hasBoundedHeight) {
            with(LocalDensity.current) { maxHeight.toPx() }.toInt()
        } else {
            0
        }
        val edge = DesktopLibraryPhotoThumbs.edgeFor(maxOf(widthPx, heightPx))
        val bitmap by produceState<ImageBitmap?>(
            initialValue = DesktopLibraryPhotoThumbs.cached(card.id, photoPath, edge),
            card.id,
            photoPath,
            edge,
        ) {
            // 快速滚过卡片时不再为每一张排队一次引擎渲染；冷启动先让舞台渲染完。
            delay(DesktopLibraryPhotoThumbs.delayBeforeRender())
            value = DesktopLibraryPhotoThumbs.cached(card.id, photoPath, edge)
                ?: DesktopLibraryPhotoThumbs.load(card, photoPath, edge)
        }
        val ready = bitmap
        if (ready == null) {
            LibraryPreview(card = card, modifier = Modifier.fillMaxSize())
        } else {
            Image(
                bitmap = ready,
                contentDescription = card.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
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
