package com.framemark.native.inspector

import com.framemark.native.library.Json
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 目录 → 打包资源的完整性闸门（WP-V4 Logo 合并的回归测试）。
 *
 * 选择页并库时新增了 160 个版本 / 240 个 cut；只要 `logos.json` 里还有一条 cut 指向
 * `files/logov/` 里不存在的 webp，打包后的桌面端在选中该字标时就会得到
 * `FM-E-LOGO-001`（运行时清单不含该版本）或引擎端空白。这个测试逐条把目录钉在
 * 实际发布的资源上，并固定两条取值规则：
 *  - 版本专属字标必须是 `ver:<brandKey>:` 键，且同版本里唯一；
 *  - 品牌级字标（`laika` / `agfa_red` / …）必须有一份 `files/logos/<assetKey>.png`
 *    品牌基线，解析器在运行时清单缺项时可以回退到它（`LogoLibrary.resolve`）。
 */
class LogoCatalogAssetIntegrityTest {

    private val catalogFile = listOf(
        "../ui/composeApp/src/commonMain/composeResources/files/data/logos.json",
        "ui/composeApp/src/commonMain/composeResources/files/data/logos.json",
        "../../ui/composeApp/src/commonMain/composeResources/files/data/logos.json",
    ).map(::File).firstOrNull(File::isFile)
        ?: error("logos.json not found from ${File(".").absolutePath}")

    /** `…/composeResources/files` — the packaged Compose resource root for Logo art. */
    private val filesRoot = catalogFile.parentFile.parentFile
    private val logov = File(filesRoot, "logov")
    private val logos = File(filesRoot, "logos")

    private val repoRoot: File = generateSequence(catalogFile.absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "engine/data/logos.json").isFile }
        ?: error("repository root not found above ${catalogFile.absolutePath}")

    private fun packagedName(resolvedPath: String): String {
        val marker = "/assets/"
        val index = resolvedPath.indexOf(marker)
        require(index > 0 && !File(resolvedPath).isAbsolute) { "unsafe resolvedPath: $resolvedPath" }
        return resolvedPath.substring(index + marker.length).replace("/", "__")
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    /**
     * WebP canvas size from the container header (VP8X / VP8 / VP8L).
     * `PhotoMetadata.read` only understands the lossy VP8 bitstream; the curated
     * cuts are VP8L, so the integrity test reads the header itself.
     */
    private fun webpSize(file: File): Pair<Int, Int> {
        val bytes = file.readBytes()
        fun u8(i: Int) = bytes[i].toInt() and 0xff
        require(bytes.size >= 30 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") { "${file.name} is not a WebP" }
        return when (String(bytes, 12, 4, Charsets.US_ASCII)) {
            "VP8X" -> Pair(
                1 + (u8(24) or (u8(25) shl 8) or (u8(26) shl 16)),
                1 + (u8(27) or (u8(28) shl 8) or (u8(29) shl 16)),
            )
            "VP8 " -> Pair(
                (u8(26) or (u8(27) shl 8)) and 0x3fff,
                (u8(28) or (u8(29) shl 8)) and 0x3fff,
            )
            "VP8L" -> {
                require(u8(20) == 0x2f) { "${file.name}: bad VP8L signature" }
                val b1 = u8(21)
                val b2 = u8(22)
                val b3 = u8(23)
                val b4 = u8(24)
                Pair(
                    1 + (b1 or ((b2 and 0x3f) shl 8)),
                    1 + ((b2 shr 6) or (b3 shl 2) or ((b4 and 0x0f) shl 10)),
                )
            }
            else -> error("${file.name}: unsupported WebP chunk ${String(bytes, 12, 4, Charsets.US_ASCII)}")
        }
    }

    @Test
    fun everySelectedCutResolvesToAPackagedWebpWithMatchingBytesAndPixels() {
        val catalog = Json.parse(catalogFile.readText()).jsonMap()
        val versions = catalog["versions"].jsonList()
        val filesMap = catalog["files"].jsonMap()
        val assetKeys = HashSet<String>()
        val referencedWebp = HashSet<String>()
        var cutCount = 0

        for (rawVersion in versions) {
            val version = rawVersion.jsonMap()
            val versionId = version.jsonString("id") ?: error("version without id")
            val brandKey = version.jsonString("brandKey") ?: error("version $versionId without brandKey")
            assertEquals(true, version["selected"], "selected version $brandKey/$versionId must ship")
            val cuts = version["cuts"].jsonList()
            assertTrue(cuts.isNotEmpty(), "selected version $brandKey/$versionId has no cuts")
            val cutIds = cuts.mapNotNull { it.jsonMap().jsonString("id") }
            assertTrue(
                version.jsonString("primaryCut") in cutIds,
                "primaryCut of $brandKey/$versionId must exist: ${version.jsonString("primaryCut")} vs $cutIds",
            )
            val inks = HashSet<String>()
            for (rawCut in cuts) {
                val cut = rawCut.jsonMap()
                cutCount++
                val cutId = cut.jsonString("id") ?: error("cut without id in $versionId")
                val assetKey = cut.jsonString("assetKey") ?: error("cut $cutId without assetKey")
                assertTrue(assetKeys.add(assetKey), "duplicate assetKey $assetKey")
                val resolved = cut.jsonString("resolvedPath") ?: error("cut $cutId without resolvedPath")
                val packaged = packagedName(resolved)
                referencedWebp += packaged

                val file = File(logov, packaged)
                assertTrue(file.isFile, "cut $brandKey/$versionId/$cutId is not packaged: files/logov/$packaged")
                assertEquals(
                    cut.jsonString("sha256")?.lowercase(),
                    sha256(file),
                    "catalog sha256 does not match files/logov/$packaged",
                )
                assertNotNull(filesMap[resolved], "cut $assetKey is missing from the catalog files map")
                val (fileWidth, fileHeight) = webpSize(file)
                val meta = filesMap[resolved].jsonMap()
                assertEquals((meta["w"] as Number).toInt(), fileWidth, "$packaged files-map width")
                assertEquals((meta["h"] as Number).toInt(), fileHeight, "$packaged files-map height")
                assertEquals((cut["actualWidth"] as Number).toInt(), fileWidth, "$packaged actualWidth")
                assertEquals((cut["actualHeight"] as Number).toInt(), fileHeight, "$packaged actualHeight")

                // 取值规则：版本专属必须带品牌前缀；品牌级必须有品牌基线 PNG。
                if (assetKey.startsWith("ver:")) {
                    assertTrue(
                        assetKey.startsWith("ver:$brandKey:"),
                        "version cut $assetKey under $brandKey/$versionId must start with 'ver:$brandKey:'",
                    )
                } else {
                    assertTrue(
                        File(logos, "$assetKey.png").isFile,
                        "brand-key cut $assetKey has no baseline files/logos/$assetKey.png for the resolver fallback",
                    )
                }
                assertTrue(
                    inks.add(cut.jsonString("ink").orEmpty()),
                    "version $brandKey/$versionId carries two cuts with the same ink; only surface variants are allowed",
                )
            }
        }

        val onDisk = logov.listFiles()
            ?.filter { it.isFile && it.name.lowercase().endsWith(".webp") }
            ?.map { it.name }
            ?.toSet()
            .orEmpty()
        assertEquals(
            emptySet(),
            onDisk - referencedWebp,
            "files/logov contains packaged webp files that no selected cut references",
        )

        val counts = catalog["counts"].jsonMap()
        assertEquals(versions.size, (counts["versions"] as Number).toInt(), "counts.versions is stale")
        assertEquals(cutCount, (counts["cuts"] as Number).toInt(), "counts.cuts is stale")
        assertEquals(catalog["brands"].jsonList().size, (counts["brands"] as Number).toInt(), "counts.brands is stale")
        assertEquals(cutCount, (counts["cutsResolved"] as Number).toInt(), "counts.cutsResolved is stale")
        assertEquals(0, (counts["cutsMissing"] as Number).toInt(), "counts.cutsMissing must stay 0")
        assertTrue(
            catalog["selectedVersionsWithoutResolvableCut"].jsonList().isEmpty(),
            "every selected version must resolve",
        )

        // Shared-data contract: the UI catalog and the engine mirror stay byte identical.
        val mirror = File(repoRoot, "engine/data/logos.json")
        assertTrue(mirror.isFile, "engine mirror is missing")
        assertTrue(
            mirror.readBytes().contentEquals(catalogFile.readBytes()),
            "engine/data/logos.json is not byte identical to the UI catalog",
        )
    }

    @Test
    fun parsedCatalogStillResolvesTheBrandKeyLeicaCutAndItsSurfaceVariant() {
        val brands = LogoBrandCatalog.parse(catalogFile.readText())
        assertTrue(brands.size >= 80, "expected the full catalog, got ${brands.size}")
        assertTrue(brands.all { it.versions.isNotEmpty() }, "a parsed brand has no versions")
        assertTrue(brands.all { b -> b.versions.all { it.cuts.isNotEmpty() } }, "a parsed version has no cuts")

        val laika = assertNotNull(brands.firstOrNull { it.key == "laika" }, "brand key laika is missing")
        val colorCut = assertNotNull(
            laika.versions.flatMap { it.cuts }.firstOrNull { it.assetKey == "laika" },
            "the brand-key Leica color cut must remain selectable",
        )
        assertEquals("logos__laika.webp", colorCut.preview)
        assertTrue(File(logov, colorCut.preview!!).isFile, "Leica color cut is not packaged")

        val lightCut = assertNotNull(
            laika.versions.flatMap { it.cuts }.firstOrNull { it.assetKey == "ver:laika:leica-dot-2006--light" },
            "the curated light cut of leica-dot-2006 is missing",
        )
        assertEquals("logo-versions__laika__leica-dot-2006--light.webp", lightCut.preview)
        assertTrue(File(logov, lightCut.preview!!).isFile, "Leica light cut is not packaged")
    }
}
