package com.framemark.native.product

import com.framemark.native.library.Json
import com.framemark.native.library.jsonInt
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 品牌字标「黑白双色」闸门。
 *
 * 「字标跟随照片品牌」这条路径（`LogoPhotoBrand.autoCutKey` → `<key>_black` / `<key>_white`）
 * 要能同时画在浅色底（白 / 纸白 / 奶油 / 石色）和深色底（墨色 / 黑色）上：每个品牌至少要有
 * 一枚 `surface` 允许浅底的 cut，和一枚允许深底的 cut。缺一侧的品牌在深色模板上就是
 * 一条看不见的字标（或者干脆回退到别的字标）。
 *
 * 另外钉住两件事：
 *  - 标准命名的 cut（`<key>_black` / `<key>_white`）必须有 `files/logos/<assetKey>.png`
 *    品牌基线，且 PNG 像素尺寸与 cut 的 `actualWidth/actualHeight` 一致 —— 解析器在运行时
 *    清单缺项时会回退到这张 PNG（`LogoLibrary.resolve`）；
 *  - 所有 version cut 的 `declared*` 必须等于 `actual*`，否则版面按声明尺寸摆放字标，
 *    出来的字标会被拉伸或留白（审计里 121 条就是这么暴露的）。
 */
class LogoBrandPairCoverageTest {

    private val catalogFile: Path = RealData.root
        .resolve("ui/composeApp/src/commonMain/composeResources/files/data/logos.json")
    private val logos: Path = catalogFile.parent.parent.resolve("logos")

    private val catalog: Map<String, Any?> = Json.parse(Files.readString(catalogFile)).jsonMap()
    private val brands: List<Map<String, Any?>> = catalog["brands"].jsonList().map { it.jsonMap() }
    private val versions: List<Map<String, Any?>> = catalog["versions"].jsonList().map { it.jsonMap() }

    private fun cutsOf(brandKey: String): List<Map<String, Any?>> = versions
        .filter { it.jsonString("brandKey") == brandKey }
        .flatMap { it["cuts"].jsonList() }
        .map { it.jsonMap() }

    private fun Map<String, Any?>.int(key: String): Int = jsonInt(key) ?: -1

    private fun Map<String, Any?>.allows(surface: String): Boolean =
        jsonString("surface").let { it == surface || it == "both" }

    @Test
    fun everyBrandCanDrawOnALightAndOnADarkSurface() {
        assertTrue(brands.size >= 100, "expected the full brand catalogue, got ${brands.size}")
        val failures = ArrayList<String>()
        for (brand in brands) {
            val key = brand.jsonString("key") ?: error("brand without key")
            val cuts = cutsOf(key)
            assertTrue(cuts.isNotEmpty(), "brand '$key' has no cuts at all")
            if (cuts.none { it.allows("light") }) {
                failures += "brand '$key' has no cut usable on a light surface (surface=light|both)"
            }
            if (cuts.none { it.allows("dark") }) {
                failures += "brand '$key' has no cut usable on a dark surface (surface=dark|both)"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun everyStandardNamedCutHasAPackagedPngOfTheDeclaredSize() {
        val problems = ArrayList<String>()
        var checked = 0
        for (version in versions) {
            val brandKey = version.jsonString("brandKey") ?: error("version without brandKey")
            for (cut in version["cuts"].jsonList().map { it.jsonMap() }) {
                val assetKey = cut.jsonString("assetKey") ?: error("cut without assetKey")
                if (assetKey != "${brandKey}_black" && assetKey != "${brandKey}_white") continue
                checked++
                val png = logos.resolve("$assetKey.png")
                if (!Files.isRegularFile(png)) {
                    problems += "cut '$assetKey' has no packaged brand baseline files/logos/$assetKey.png"
                    continue
                }
                val image = ImageIO.read(png.toFile())
                if (image == null) {
                    problems += "files/logos/$assetKey.png is not a readable bitmap"
                    continue
                }
                val width = cut.int("actualWidth")
                val height = cut.int("actualHeight")
                if (image.width != width || image.height != height) {
                    problems += "files/logos/$assetKey.png is ${image.width}x${image.height} " +
                        "but the cut is ${width}x$height"
                }
            }
        }
        assertTrue(
            checked >= 80,
            "expected the standard-named black/white cuts of every brand pair, found $checked",
        )
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun noVersionCutDeclaresDimensionsThatDifferFromTheBitmap() {
        val problems = ArrayList<String>()
        for (version in versions) {
            val brandKey = version.jsonString("brandKey") ?: error("version without brandKey")
            val versionId = version.jsonString("id") ?: error("version without id")
            for (cut in version["cuts"].jsonList().map { it.jsonMap() }) {
                val assetKey = cut.jsonString("assetKey") ?: error("cut without assetKey")
                val declaredWidth = cut.int("declaredWidth")
                val declaredHeight = cut.int("declaredHeight")
                val actualWidth = cut.int("actualWidth")
                val actualHeight = cut.int("actualHeight")
                if (declaredWidth != actualWidth || declaredHeight != actualHeight) {
                    problems += "brand '$brandKey' version '$versionId' cut '$assetKey' declares " +
                        "${declaredWidth}x$declaredHeight but the bitmap is ${actualWidth}x$actualHeight"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
