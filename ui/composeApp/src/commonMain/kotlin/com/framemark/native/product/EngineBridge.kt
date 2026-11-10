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

fun ProjectWatermarkSpec.toOverlaySpec(materializedPath: String, photoAspect: Float): OverlaySpec {
    val r = rect(photoAspect)
    return OverlaySpec(
        path = materializedPath,
        x = r.x,
        y = r.y,
        w = r.w,
        h = r.h,
        opacity = opacity,
        angle = rotation.coerceIn(-ProjectWatermarkSpec.MAX_ROTATION, ProjectWatermarkSpec.MAX_ROTATION),
    )
}
