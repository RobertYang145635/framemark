package com.framemark.native.engine

import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.NativeImage
import com.framemark.native.workflow.NativeRenderApi
import com.framemark.native.workflow.OverlaySpec
import com.framemark.native.workflow.SampleFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Desktop [NativeRenderApi]: runs the render-v1 bridge on the IO dispatcher.
 * Installed by `main.kt`; a launch without engine binaries simply reports that
 * no card is supported and the workflow keeps the placeholder export.
 */
class DesktopNativeRender(
    private val bridge: DesktopEngineBridge = DesktopEngineBridge(),
    private val logos: LogoLibrary = LogoLibrary(),
) : NativeRenderApi {

    private val supported = bridge.cardIds().toSet()

    init {
        println("FM_ENGINE_READY cards=${supported.size} bridge=${bridge.catalog.manifestPath}")
    }

    override fun supports(cardId: String): Boolean = cardId in supported

    override fun supportsWebpExport(): Boolean = true

    override suspend fun render(
        cardId: String,
        design: DesignSnapshot,
        mode: String,
        maxEdge: Int?,
        format: SampleFormat?,
        quality: Int?,
        longEdge: Int?,
        outputPath: String?,
        webpLossless: Boolean,
        overlay: OverlaySpec?,
        photoPath: String?,
    ): NativeImage = runInterruptible(Dispatchers.IO) {
        val logo = logos.resolve(design.logoBrand)
        if (design.logoBrand != null && logo == null) {
            println("FM_LOGO_UNRESOLVED brand=${design.logoBrand} dir=${logos.dir.absolutePath}")
        } else if (logo != null) {
            println("FM_LOGO brand=${design.logoBrand} asset=${logo.name} ${logo.width}x${logo.height}")
        }
        val result = bridge.render(
            cardId = cardId,
            params = design.params,
            text = design.text,
            mode = mode,
            maxEdge = maxEdge,
            format = format?.extension,
            quality = quality,
            longEdge = longEdge,
            outputPath = outputPath,
            webpLossless = webpLossless,
            overlay = overlay,
            photoPath = photoPath,
            logo = logo,
            brandPick = design.logoBrand,
        )
        println(
            "FM_ENGINE_RENDER card=$cardId mode=$mode ${result.width}x${result.height} " +
                "raw=${result.rawWidth}x${result.rawHeight} ms=${result.ms} path=${result.path}",
        )
        NativeImage(
            result.path,
            result.width,
            result.height,
            result.ms,
            note = result.warnings.firstOrNull { it.contains("不支持完整原尺寸") },
        )
    }
}
