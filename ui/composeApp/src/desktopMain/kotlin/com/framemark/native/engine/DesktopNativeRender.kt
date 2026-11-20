package com.framemark.native.engine

import com.framemark.native.inspector.LogoBrandCatalog
import com.framemark.native.product.Base64Codec
import com.framemark.native.product.PhotoBrandHint
import com.framemark.native.product.PhotoBrandKeys
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
import com.framemark.native.product.FmLog
import java.io.File

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

    /**
     * 照片 → EXIF `Make`：键是 `路径@mtime`，值是厂商名（`""` = 读过了但没有）。
     * 预览会反复问同一张照片，不能每帧重读文件头；换照片 / 重新导入 = 另一个键。
     */
    private val photoMakeCache = LinkedHashMap<String, String>(8, 0.75f, true)
    private val photoMakeLock = Any()

    private fun photoMake(photo: File): String? {
        val cacheKey = photo.absolutePath + "@" + photo.lastModified()
        synchronized(photoMakeLock) { photoMakeCache[cacheKey] }?.let { return it.ifEmpty { null } }
        val make = JpegExif.make(photo)
        synchronized(photoMakeLock) {
            photoMakeCache[cacheKey] = make.orEmpty()
            while (photoMakeCache.size > 8) photoMakeCache.remove(photoMakeCache.keys.first())
        }
        return make
    }

    init {
        FmLog.info("FM_ENGINE_READY") { "cards=${supported.size} bridge=${bridge.catalog.manifestPath}" }
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
        interactive: Boolean,
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
        // 自动匹配（空 / `auto`）在**渲染时**按照片品牌解析：只在点按钮时写一次值，
        // 换一张照片就不跟随了 —— 那正是「选完边框还是样片字标」的成因。
        val autoLogo = backend == "hand" && PhotoBrandKeys.isAuto(design.logoBrand)
        val autoMake = if (autoLogo) {
            photoPath?.let { path -> File(path).takeIf { it.isFile }?.let { photoMake(it) } }
        } else {
            null
        }
        val autoLogoKey = if (autoLogo) {
            LogoBrandCatalog.autoCutForBrandKey(PhotoBrandKeys.brandKeyFor(autoMake))
        } else {
            null
        }
        if (autoLogo) {
            // Logo 页的说明行读这里（与手机端同一个来源）：卡片把品牌画成图片时，
            // 文字 slot 里没有厂商名，说明行不能凭空写「按边框自己的品牌」。
            PhotoBrandHint.mark(autoMake, PhotoBrandKeys.brandKeyFor(autoMake))
        }
        if (autoLogo) {
            FmLog.info("FM_LOGO") {
                "mode=auto photo=${photoPath?.substringAfterLast('/') ?: "<none>"} " +
                    "make=${autoMake ?: "<none>"} key=${autoLogoKey ?: "<none>"} " +
                    "(no explicit design.logo, following the photo)"
            }
        }
        return runInterruptible(Dispatchers.IO) {
            val userLogo = if (backend == "hand") loadedUserLogo else null
            // 自动解析失败（运行时缺这枚字标）只是「没有自动结果」：退回引擎的卡片自带
            // 字标，不能因为字标解析把整帧渲染打掉。
            val autoResolved = autoLogoKey?.let { key ->
                runCatching { logos.resolve(key, exactAssetKey = false) }
                    .onFailure { FmLog.info("FM_LOGO") { "mode=auto-fail key=$key ${it.message}" } }
                    .getOrNull()
            }
            val logo = userLogo ?: autoResolved
                ?: if (backend == "hand") logos.resolve(design.logoBrand, isExactLogoCut) else null
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
                FmLog.info("FM_LOGO_UNRESOLVED") { "brand=${design.logoBrand} dir=${logos.dir.absolutePath}" }
            } else if (logo != null) {
                FmLog.info("FM_LOGO") { "brand=${design.logoBrand} asset=${logo.name} ${logo.width}x${logo.height}" }
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
                interactive = interactive,
            )
            FmLog.info("FM_ENGINE_RENDER") {
                "card=$cardId mode=$mode ${result.width}x${result.height} " +
                    "raw=${result.rawWidth}x${result.rawHeight} ms=${result.ms} path=${result.path}"
            }
            NativeImage(
                result.path,
                result.width,
                result.height,
                result.ms,
                photoRect = result.photoRect.takeIf { it.size >= 4 }?.let {
                    ImageRect(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat())
                },
                note = result.warnings.firstOrNull { it.contains("不支持完整原尺寸") },
                // WP-V4 A: 桌面渲染结果必须把报告里的文本行带回 UI；丢掉它，右栏
                // 文字页只能退化成「页脚行选择」一条静态控件。
                textSlots = result.textSlots,
                // The engine's own crop answer: the export skips its pre-crop when
                // this is present, so preview and export agree by construction.
                photoSrc = result.photoSrc?.takeIf { it.size >= 4 }?.let {
                    ImageRect(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat())
                },
            )
        }
    }
}
