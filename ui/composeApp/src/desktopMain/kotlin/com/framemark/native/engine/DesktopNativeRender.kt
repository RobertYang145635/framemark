package com.framemark.native.engine

import com.framemark.native.inspector.LogoBrandCatalog
import com.framemark.native.product.Base64Codec
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.ImageRect
import com.framemark.native.workflow.NativeImage
import com.framemark.native.workflow.NativeRenderApi
import com.framemark.native.workflow.OverlaySpec
import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.UserLogoEnv
import com.framemark.native.workflow.UserLogoLibrary
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

    private suspend fun userLogo(designValue: String): EngineLogo {
        val id = UserLogoLibrary.idOf(designValue)
            ?: throw EngineConfigException("FM-E-LOGO-004", "用户 Logo 标识无效")
        val asset = UserLogoEnv.repository?.load(id)
            ?: throw EngineConfigException(
                "FM-E-LOGO-005",
                "所选用户 Logo 未收录；请重新导入或选择其它 Logo",
            )
        return EngineLogo(
            name = "user-${asset.logo.sha256.take(12)}",
            width = asset.logo.width,
            height = asset.logo.height,
            dataUrl = "data:${asset.logo.mime};base64,${Base64Codec.encode(asset.bytes)}",
        )
    }

    override fun supports(cardId: String): Boolean = cardId in supported

    override fun supportsWebpExport(): Boolean = true

    fun supportsUserLogo(cardId: String): Boolean = bridge.catalog.card(cardId)?.backend == "hand"

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
    ): NativeImage {
        val backend = bridge.catalog.card(cardId)?.backend
        val requestedLogo = design.params["brandLogoFile"] as? String
        if (backend == "ir" && (UserLogoLibrary.isUserLogo(design.logoBrand) ||
                UserLogoLibrary.isUserLogo(requestedLogo))
        ) {
            throw EngineConfigException(
                "FM-E-LOGO-007",
                "用户 Logo 目前仅支持 hand 边框；IR 模板请从品牌库选择字标",
            )
        }
        val loadedUserLogo = if (UserLogoLibrary.isUserLogo(design.logoBrand)) {
            userLogo(design.logoBrand!!)
        } else {
            null
        }
        val isExactLogoCut = design.logoBrand?.let { LogoBrandCatalog.cutForAssetKey(it) != null } ?: false
        return runInterruptible(Dispatchers.IO) {
            val userLogo = if (backend == "hand") loadedUserLogo else null
            val logo = userLogo ?: if (backend == "hand") logos.resolve(design.logoBrand, isExactLogoCut) else null
            val irLogoKey = if (backend == "ir") logos.engineKey(design.logoBrand, isExactLogoCut) else null
            val renderParams = if (backend == "ir") {
                val isAuto = design.logoBrand?.equals("auto", ignoreCase = true) == true ||
                    requestedLogo?.equals("auto", ignoreCase = true) == true
                val baseParams = if (isAuto) design.params - "brandLogoFile" else design.params
                if (irLogoKey != null) baseParams + ("brandLogoFile" to irLogoKey) else baseParams
            } else {
                design.params
            }
            if (backend == "hand" && design.logoBrand != null && logo == null) {
                println("FM_LOGO_UNRESOLVED brand=${design.logoBrand} dir=${logos.dir.absolutePath}")
            } else if (logo != null) {
                println("FM_LOGO brand=${design.logoBrand} asset=${logo.name} ${logo.width}x${logo.height}")
            }
            val result = bridge.render(
                cardId = cardId,
                params = renderParams,
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
                brandPick = irLogoKey,
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
                photoRect = result.photoRect.takeIf { it.size >= 4 }?.let {
                    ImageRect(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat())
                },
                note = result.warnings.firstOrNull { it.contains("不支持完整原尺寸") },
            )
        }
    }
}
