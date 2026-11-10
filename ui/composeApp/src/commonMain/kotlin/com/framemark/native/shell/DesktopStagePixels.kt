package com.framemark.native.shell

import androidx.compose.ui.unit.IntSize
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.OutputPixels
import com.framemark.native.inspector.OutputSize
import com.framemark.native.product.ExportSettings
import com.framemark.native.product.PhotoRef
import com.framemark.native.workflow.DesignSession

/**
 * The one canvas-size formula chain for the desktop surfaces: the stage title row's
 * 「→ 导出 W × H」, the inspector's 导出尺寸, the export sheet's 「约 W × H」 and the
 * phone's style page all resolve to the same numbers here.
 *
 * [stageExportPixels] applies the export long edge to [stageCanvasPixels], which is
 * the unscaled canvas; [PhotoRef.displayPixels] normalises a photo reference to the
 * EXIF-applied display size first, so a portrait photo cannot disagree with its
 * export. Returns null when a number would have to be guessed.
 *
 * Lives in this package (not `shell.desktop`) because the desktop filmstrip, the
 * export dialog and `DesktopExportSizeParityTest` all import these symbols from
 * `com.framemark.native.shell`; moving the package would be an API change, not a
 * file move.
 */

/**
 * 舞台顶栏的导出像素。
 *
 * AU2 A-06：这里过去按**渲染帧的宽高比**反推（`photo.width × frameAspect`），
 * 与右栏 `curatedOutputSize` 的公式各算一遍，于是同一屏上出现 2666 / 2667 两种说法。
 * 现在已知参数族（`brand_footer` / `personal_border`）的卡片一律走 [OutputSize]，
 * 与右栏、手机、导出长边换算同式；只有参数族未知的卡片（厂商规格 / 比例模板）
 * 才退回渲染帧的**测量**宽高比 —— 那是实测值，不是第二套公式。
 *
 * 取不到照片尺寸或渲染帧时返回 null —— 宁可不写，也不写一个猜的数字。
 */
internal fun stageExportPixels(
    model: InspectorCardModel?,
    session: DesignSession,
    photo: PhotoRef?,
    rendered: IntSize,
    settings: ExportSettings,
): OutputPixels? = OutputSize.applyLongEdge(
    stageCanvasPixels(model, session, photo, rendered),
    settings.sizeMode,
    settings.effectiveLongEdge,
)

/**
 * 同一张卡的**未缩放**画布像素：右栏「导出尺寸」、顶栏、手机样式页与导出弹层
 * 共用它。已知参数族走 [OutputSize]，未知族才用渲染帧的测量宽高比兜底。
 *
 * FIX1: 入口的 `photo.widthPx/heightPx` 是**容器**尺寸（桌面导入器只读文件头，
 * 方向单列在 `PhotoRef.orientation`），而引擎按解码后（EXIF 已应用）的画面排版。
 * 所以这里先用 [PhotoRef.displayPixels] 归一，再交给 [OutputSize] —— 竖拍照片
 * 才不会出现「界面 2000 × 2889、成片 2667 × 2296」。
 */
internal fun stageCanvasPixels(
    model: InspectorCardModel?,
    session: DesignSession,
    photo: PhotoRef?,
    rendered: IntSize,
): OutputPixels? {
    if (photo == null) return null
    val display = photo.displayPixels()
    if (display.width <= 0) return null
    val controls = model?.controls.orEmpty()
    return if (OutputSize.familyOf(controls) != OutputSize.Family.PhotoOnly) {
        OutputSize.canvas(display.width, display.height, controls) { session.value(it) }
    } else {
        OutputSize.measuredCanvas(display.width, rendered.width, rendered.height)
    }
}

/**
 * `PhotoRef` 的**显示**像素尺寸（EXIF 方向已应用）。
 *
 * 桌面导入器（`DesktopProjectController.importPhotos` → `PhotoMetadata.read`）存的是
 * 容器尺寸，方向单独存；引擎解码时 Skia 把方向烘进光栅，排版用的是显示尺寸。
 * 显示侧必须用同一个口径，否则竖拍照片会差出一整个旋转。
 *
 * 只给桌面显示侧用：手机导入器（`AndroidPhoneAssetIo.photoMeta`）已经把方向换进
 * `widthPx/heightPx` 了，那边不要再调本函数。
 */
internal fun PhotoRef.displayPixels(): OutputPixels =
    OutputSize.displayPixels(widthPx, heightPx, orientation)
