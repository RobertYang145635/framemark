package com.framemark.native.workflow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 缺失行的「标准参数档」与它们的真实位置（2026-11-21）。
 *
 * 问题：照片缺 EXIF 时，引擎不会为没画出来的行上报矩形，于是文字页把这一行叫「待定位」、
 * 点它舞台也不出高亮框 —— 可这一行在边框里的位置其实是固定的。
 *
 * 做法：**量一次**。把这些行的文案临时换成标准示例（[textSlotSample]），离屏渲染一次只取
 * 报告里的矩形，画面丢弃；矩形按 (边框, 照片, 画布) 缓存。之后：
 *  - 舞台在这些位置画淡灰的示例文字（[GhostSamples]），进文字页后撤掉；
 *  - 文字页用真实行列命名（右上 / 右下…），点选高亮画在同一矩形上。
 *
 * 只读、只在编辑器里用：不写工程、不进导出。
 */
/**
 * 把探针渲染出来的矩形换算到**舞台预览**的像素空间。
 *
 * 探针那一次渲染的分辨率由 `maxEdge = min(画布长边, 1600)` 决定，而主预览的分辨率由
 * 舞台决定，两者经常不同（例如主预览 2000 宽、探针封顶 1600 宽）。`TextSlot` 的坐标
 * 是**各自渲染图**的像素，直接拿去按舞台尺寸映射，示例就会整体缩到左上、字也偏小
 * ——真机上看着就是「占位位置偏了」。
 */
internal fun normaliseProbeSlots(
    slots: List<TextSlot>,
    probeWidth: Int,
    targetWidth: Int,
): List<TextSlot> {
    if (probeWidth <= 0 || targetWidth <= 0 || probeWidth == targetWidth) return slots
    val k = targetWidth.toDouble() / probeWidth
    if (!k.isFinite() || k <= 0.0) return slots
    return slots.map { slot ->
        slot.copy(
            x = slot.x * k,
            y = slot.y * k,
            w = slot.w * k,
            h = slot.h * k,
            fontSize = slot.fontSize?.times(k),
            baseline = slot.baseline?.times(k),
        )
    }
}

object SlotGeometryProbe {

    private data class Key(val card: String, val photo: String, val width: Int, val height: Int)

    private val cache = HashMap<Key, Map<String, TextSlot>>()

    fun cached(cardId: String?, photoPath: String?, width: Int, height: Int): Map<String, TextSlot>? {
        val card = cardId?.takeIf { it.isNotBlank() } ?: return null
        val photo = photoPath.orEmpty()
        if (width <= 0 || height <= 0) return null
        return cache[Key(card, photo, width, height)]
    }

    /**
     * 给 [design] 里这些 key 的文本行填上示例文案，渲染一次取矩形。
     *
     * 返回 key → 带几何的 [TextSlot]；失败（引擎缺失、卡不支持、报告为空）返回空表，
     * 调用方保持原样（仍显示角色名，不假装知道位置）。
     */
    suspend fun measure(
        cardId: String,
        styleId: String?,
        photoPath: String?,
        design: DesignSnapshot,
        canvasWidth: Int,
        canvasHeight: Int,
        samples: Map<String, String>,
        outputDirectory: String?,
    ): Map<String, TextSlot> {
        if (samples.isEmpty() || canvasWidth <= 0 || canvasHeight <= 0) return emptyMap()
        val api = WorkflowEnv.nativeRender ?: return emptyMap()
        if (!api.supports(cardId)) return emptyMap()
        val params = HashMap<String, Any?>(design.params)
        for ((key, sample) in samples) {
            params["slotText:$key"] = sample
        }
        val probe = design.copy(params = params)
        val stamp = System.currentTimeMillis()
        val target = (outputDirectory?.trimEnd('/', '\\') ?: return emptyMap()) + "/probe-$stamp.png"
        val image = withContext(Dispatchers.Default) {
            runCatching {
                api.render(
                    cardId = cardId,
                    design = probe,
                    mode = "preview",
                    maxEdge = maxOf(canvasWidth, canvasHeight).coerceAtMost(1600),
                    format = null,
                    quality = null,
                    longEdge = null,
                    outputPath = target,
                    photoPath = photoPath,
                )
            }.getOrNull()
        } ?: return emptyMap()
        // 探针图的分辨率通常不等于舞台预览：先换算到舞台那一套像素，再交给示例层。
        val normalised = normaliseProbeSlots(image.textSlots, image.width, canvasWidth)
        val rects = normalised
            .filter { it.key != null && it.located && it.w > 0.0 && it.h > 0.0 }
            .associateBy { it.key!! }
            .filterKeys { key -> samples.containsKey(key) }
        if (rects.isNotEmpty()) {
            cache[Key(cardId, photoPath.orEmpty(), canvasWidth, canvasHeight)] = rects
        }
        return rects
    }
}
