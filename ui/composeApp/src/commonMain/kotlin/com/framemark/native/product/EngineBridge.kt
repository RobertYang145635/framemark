package com.framemark.native.product

import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.EngineFailure
import com.framemark.native.workflow.OverlaySpec

/**
 * The only seam to the existing engine contract. The product core owns the design
 * state; render-v1 still takes a [DesignSnapshot] plus an [OverlaySpec] whose
 * `path` points at a file, so the caller materialises the embedded watermark
 * bytes once and passes that path in.
 *
 * `theme`, `ratio`, and `fontScale` are folded into params/text because that is
 * where the engines read them (`e2b --theme` / `design.text.fontScale`, hand
 * `design.params` / `design.text`). Leaving them out made a design-only change
 * export as if untouched. The logo selection remains in [DesignSnapshot.logoBrand].
 */
fun PhotoDesign.toDesignSnapshot(): DesignSnapshot {
    val paramsOut = LinkedHashMap<String, Any?>(params)
    theme?.let { paramsOut["theme"] = it }
    ratio?.let { paramsOut["ratio"] = it }
    val textOut = LinkedHashMap<String, Any?>(text)
    fontScale?.let { textOut["fontScale"] = it }
    val brand = logo?.takeIf { it.isNotBlank() && it != "auto" }
    return DesignSnapshot(cardId, styleId, paramsOut, textOut, brand)
}

/**
 * Resolves the per-render photo override. Null means the caller intentionally
 * requested the current/default photo; a supplied path must be usable and must
 * never silently turn into a different photo.
 */
fun requireReadablePhotoOverride(
    photoPath: String?,
    isReadableFile: (String) -> Boolean,
): String? {
    if (photoPath == null) return null
    if (photoPath.isBlank() || !isReadableFile(photoPath)) {
        throw EngineFailure(
            "FM-E-INPUT-001",
            "显式照片路径无效或不可读取，已中止而不是改用当前照片或示例照片",
        )
    }
    return photoPath
}

fun WatermarkSpec.toOverlaySpec(materializedPath: String, photoAspect: Float): OverlaySpec {
    val r = rect(photoAspect)
    return OverlaySpec(
        path = materializedPath,
        x = r.x,
        y = r.y,
        w = r.w,
        h = r.h,
        opacity = opacity,
    )
}

enum class RenderMode(val wire: String) {
    Preview("preview"),
    Export("export"),
}

data class RenderOverlay(
    val path: String,
    val rect: OverlayRect,
    val opacity: Float,
)

/** Design payload of a render-v1 request; optional fields are omitted when unset. */
data class RenderDesign(
    val cardId: String,
    val styleId: String,
    val theme: String? = null,
    val ratio: String? = null,
    val fontScale: Double? = null,
    /** Keys are the ControlRegistry consumer keys (`slotText:<node>`, `footerPct`, …). */
    val params: Map<String, Any?> = emptyMap(),
    val text: Map<String, Any?> = emptyMap(),
    val logo: String? = null,
)

/**
 * The single render-v1 request document both platforms produce.
 *
 * An untouched design carries no theme/ratio/fontScale/logo/overlay keys and empty
 * params/text, so the document is exactly the pre-P1b shape: building a request
 * never injects a default and therefore never changes what the engine renders.
 */
data class RenderRequest(
    val design: RenderDesign,
    val photoPath: String,
    val photoWidth: Int,
    val photoHeight: Int,
    val mode: RenderMode,
    val maxEdge: Int? = null,
    val overlay: RenderOverlay? = null,
) {
    fun toJson(): String = buildString {
        append("{\"schemaVersion\":1,\"design\":{")
        append("\"cardId\":").append(ProductJson.quote(design.cardId))
        append(",\"styleId\":").append(ProductJson.quote(design.styleId))
        design.theme?.let { append(",\"theme\":").append(ProductJson.quote(it)) }
        design.ratio?.let { append(",\"ratio\":").append(ProductJson.quote(it)) }
        design.fontScale?.let { append(",\"fontScale\":").append(ProductJson.number(it)) }
        append(",\"params\":").append(ProductJson.encodeValues(design.params))
        append(",\"text\":").append(ProductJson.encodeValues(design.text))
        design.logo?.let { append(",\"logo\":").append(ProductJson.quote(it)) }
        append("},\"photo\":{\"path\":").append(ProductJson.quote(photoPath))
        append(",\"w\":").append(photoWidth)
        append(",\"h\":").append(photoHeight)
        append("},\"mode\":").append(ProductJson.quote(mode.wire))
        maxEdge?.let { append(",\"maxEdge\":").append(it) }
        overlay?.let {
            append(",\"overlay\":{\"path\":").append(ProductJson.quote(it.path))
            append(",\"rect\":{\"x\":").append(ProductJson.number(it.rect.x))
            append(",\"y\":").append(ProductJson.number(it.rect.y))
            append(",\"w\":").append(ProductJson.number(it.rect.w))
            append(",\"h\":").append(ProductJson.number(it.rect.h))
            append("},\"opacity\":").append(ProductJson.number(it.opacity))
            append('}')
        }
        append('}')
    }
}

object RenderRequestFactory {
    /**
     * Builds the request from design + photo. Strict on purpose: a watermark without
     * a materialised file, an unknown photo size or an export with [maxEdge] would
     * silently render something else, so they fail instead.
     */
    fun build(
        design: PhotoDesign,
        photo: PhotoRef,
        mode: RenderMode,
        maxEdge: Int? = null,
        overlayPath: String? = null,
        photoAspect: Float? = null,
    ): RenderRequest {
        require(design.cardId.isNotBlank()) { "design.cardId 为空" }
        val styleId = design.styleId?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("design.styleId 缺失（引擎按 styleId 选样式）")
        require(photo.path.isNotBlank()) { "照片路径为空" }
        require(photo.widthPx > 0 && photo.heightPx > 0) {
            "照片尺寸未知（${photo.widthPx}x${photo.heightPx}），请求必须带真实 w/h"
        }
        if (mode == RenderMode.Export) {
            require(maxEdge == null) { "export 请求不能带 maxEdge" }
        }
        if (maxEdge != null) require(maxEdge in 64..20_000) { "maxEdge 超出 64..20000：$maxEdge" }
        design.theme?.let { require(it.isNotBlank()) { "theme 为空" } }
        design.ratio?.let { require(it.isNotBlank()) { "ratio 为空" } }
        design.logo?.let { require(it.isNotBlank()) { "logo 为空" } }
        design.fontScale?.let { require(it.isFinite() && it > 0.0) { "fontScale 必须为正数" } }

        val overlay = when {
            design.overlay == null && overlayPath != null ->
                throw IllegalArgumentException("设计没有水印，却传入了 overlayPath")
            design.overlay == null -> null
            overlayPath.isNullOrBlank() ->
                throw IllegalArgumentException("水印素材未落到文件：请先写出内嵌 PNG 再构造请求")
            photoAspect == null ->
                throw IllegalArgumentException("带水印的请求必须提供 photoAspect（照片绘制区宽高比）")
            else -> RenderOverlay(
                path = overlayPath!!,
                rect = design.overlay!!.rect(photoAspect),
                opacity = design.overlay!!.opacity,
            )
        }

        return RenderRequest(
            design = RenderDesign(
                cardId = design.cardId,
                styleId = styleId,
                theme = design.theme,
                ratio = design.ratio,
                fontScale = design.fontScale,
                params = design.params,
                text = design.text,
                logo = design.logo,
            ),
            photoPath = photo.path,
            photoWidth = photo.widthPx,
            photoHeight = photo.heightPx,
            mode = mode,
            maxEdge = maxEdge,
            overlay = overlay,
        )
    }
}
