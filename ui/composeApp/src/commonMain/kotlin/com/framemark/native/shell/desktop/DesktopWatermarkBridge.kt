package com.framemark.native.shell.desktop

import com.framemark.native.product.WatermarkSpec as ProductWatermarkSpec
import com.framemark.native.workflow.WatermarkSpec as WorkflowWatermarkSpec

/**
 * Bridges the persisted watermark (`product.WatermarkSpec`, embedded PNG bytes)
 * to the geometry type the stage and the 水印 page consume
 * (`workflow.WatermarkSpec`).
 *
 * Only the geometry crosses: anchor, width, opacity, offsets, rotation, the asset
 * aspect and the photo aspect. The path is deliberately empty — neither
 * `WatermarkPane` nor `NativeStagePreview`'s drag handle reads it (the rendered
 * overlay comes from `ProjectHolder.overlaySpec()`), so no file is materialised
 * just to paint the controls.
 */
fun desktopWorkflowWatermark(spec: ProductWatermarkSpec?): WorkflowWatermarkSpec? =
    spec?.let {
        WorkflowWatermarkSpec(
            path = "",
            aspect = it.asset.aspect,
            anchor = it.anchor,
            width = it.width,
            opacity = it.opacity,
            dx = it.dx,
            dy = it.dy,
            photoAspect = it.photoAspect,
            rotation = it.rotation,
        )
    }
