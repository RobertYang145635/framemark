package com.framemark.native.engine

import android.content.Context
import com.framemark.native.library.Json
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonInt
import com.framemark.native.library.jsonString
import java.io.File
import java.util.zip.ZipInputStream
import com.framemark.native.product.FmLog

/**
 * Runtime bundle for the Android IR renderer: `assets/x1ir.zip` holds all 111 IR
 * styles, their assets, the brand wordmarks, the fonts tree, the sample photo and
 * the EXIF source.
 *
 * The extraction is keyed by the bundle's own manifest identity
 * ([ShippedEngineBundle.identityOf]), never by an app constant, so an APK that ships a
 * different zip always replaces the extraction an earlier install left behind.
 *
 * A bundle is only ever replaced by a *verified* one: it is unpacked into a sibling
 * staging directory, checked against the shipped identity (manifest identity + every
 * declared style/photo/exif/hand file present), and swapped in by rename. If any step
 * fails the previous extraction stays in place — the app never ends up with neither.
 */
object EngineData {
    private const val ASSET_ZIP = ShippedEngineBundle.ANDROID_ASSET
    private const val DIR = "x1ir"

    /** Sibling directories used by the extract → verify → swap sequence. */
    private const val STAGING_DIR = DIR + ".new"
    private const val PREVIOUS_DIR = DIR + ".old"

    /** Identity cache (`<apk metadata>\n<identity>`) so the bundle is not re-read every launch. */
    private const val IDENTITY_CACHE = DIR + ".shipped"

    /** 解包目录里的身份标记（含随包 zip 的 CRC/大小），与 [IDENTITY_CACHE] 不是一回事。 */
    private const val IDENTITY_MARKER = ".fm-bundle-identity"

    /** Upper bound for the scan that looks for the zip's `manifest.json`. */
    private const val MAX_SCAN_BYTES = 256L * 1024 * 1024

    data class Style(
        val id: String,
        val uiCardId: String?,
        val style: String,
        val assetsJson: String,
    )

    class Snapshot(
        val root: File,
        val photo: String,
        val exif: String,
        val fonts: String,
        val brands: String,
        val fallback: String,
        val icc: String?,
        val styles: Map<String, Style>,
        val handCardsJson: String?,
        val handDir: String?,
        val handExtraFonts: String?,
        val handCardIds: List<String>,
    )

    @Volatile
    private var cached: Snapshot? = null

    fun snapshot(context: Context): Snapshot = cached ?: synchronized(this) {
        cached ?: load(context).also { cached = it }
    }

    private fun load(context: Context): Snapshot {
        val root = File(context.filesDir, DIR)
        recoverInterruptedSwap(root)
        val extracted = extractionIdentity(root)
        val complete = extracted != null && bundleComplete(root)
        // Only a complete extraction is worth comparing against the bundle: on a first
        // launch there is nothing to be stale, so the zip is not scanned at all.
        val shipped = if (complete) shippedIdentity(context) else null
        if (!ShippedEngineBundle.canReuse(extracted, shipped, complete)) {
            replaceBundle(context, root, shipped)
        }
        val manifestFile = File(root, ShippedEngineBundle.MANIFEST_ENTRY)
        check(manifestFile.isFile) { "engine bundle missing after extraction: ${manifestFile.path}" }
        val manifest = Json.parse(manifestFile.readText()).jsonMap()
        val styles = LinkedHashMap<String, Style>()
        manifest["styles"].jsonList().forEach { entry ->
            val map = entry.jsonMap()
            val id = map.jsonString("id") ?: return@forEach
            val rel = map.jsonString("style") ?: return@forEach
            val assets = LinkedHashMap<String, String>()
            map["assets"].jsonMap().forEach { (k, v) -> (v as? String)?.let { assets[k] = abs(root, it) } }
            styles[id] = Style(id, map.jsonString("uiCardId"), abs(root, rel), jsonObject(assets))
        }
        val hand = manifest["hand"].jsonMap()
        val handCardsRel = hand.jsonString("cards")
        val handCardsJson = if (handCardsRel != null) {
            rewriteHandCards(root, File(root, handCardsRel))
        } else {
            null
        }
        val handCardIds = handCardsJson?.let { ids(it) } ?: emptyList()
        val snap = Snapshot(
            root = root,
            photo = abs(root, manifest.jsonString("photo") ?: "photo.png"),
            exif = abs(root, manifest.jsonString("exif") ?: "exif.jpg"),
            fonts = abs(root, manifest.jsonString("fonts") ?: "fonts"),
            brands = abs(root, manifest.jsonString("brands") ?: "logos"),
            fallback = abs(root, manifest.jsonString("fallback") ?: "fallback"),
            icc = manifest.jsonString("icc")?.let { abs(root, it) },
            styles = styles,
            handCardsJson = handCardsJson,
            handDir = hand.jsonString("hand")?.let { abs(root, it) },
            handExtraFonts = hand.jsonString("extraFonts")?.let { abs(root, it) },
            handCardIds = handCardIds,
        )
        FmLog.info("FM_ANDROID_ENGINE_DATA") { "root=${root.absolutePath} styles=${styles.size}" }
        return snap
    }

    private fun extract(context: Context, root: File) {
        context.assets.open(ASSET_ZIP).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(root, entry.name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> zip.copyTo(out) }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Unpack the shipped bundle into a sibling staging directory, verify it, then swap it
     * in. [root] is untouched until the swap, so a failed or truncated unpack can never cost
     * the user the extraction they already had.
     *
     * The two renames are atomic individually, not together: a kill between them leaves
     * [root] absent with the old copy under `.old`, which [recoverInterruptedSwap] puts back
     * on the next launch. Nothing here fsyncs the unpacked files, so a hard power loss can
     * still lose the tail of the *new* copy — the identity check on the next launch catches
     * that and unpacks again.
     */
    private fun replaceBundle(context: Context, root: File, expected: String?) {
        val staging = File(root.parentFile, STAGING_DIR)
        val previous = File(root.parentFile, PREVIOUS_DIR)
        staging.deleteRecursively()
        previous.deleteRecursively()
        val staged = runCatching {
            staging.mkdirs()
            extract(context, staging)
            // 把「这次解的是哪一个 zip」写进解包目录：身份里含 zip 的 CRC/大小，
            // 下次启动才能判断磁盘上的数据是不是同一份。
            //
            // 首次安装时 [expected] 是 null（完整解包之前不扫 zip），这时用刚解出的
            // manifest + 当前 zip 指纹现算一次，否则下次启动会判成"身份不符"再解一遍。
            val marker = expected ?: run {
                val manifestFile = File(staging, ShippedEngineBundle.MANIFEST_ENTRY)
                if (manifestFile.isFile) {
                    ShippedEngineBundle.identityOf(manifestFile.readText(), bundleStamp(context))
                } else {
                    null
                }
            }
            marker?.let { File(staging, IDENTITY_MARKER).writeText(it) }
            extractionIdentity(staging)?.takeIf { bundleComplete(staging) }
        }.getOrElse { error ->
            FmLog.info("FM_ANDROID_ENGINE_DATA") { "bundle extract failed: ${error.message}" }
            null
        }
        if (staged == null || (expected != null && staged != expected)) {
            staging.deleteRecursively()
            FmLog.info("FM_ANDROID_ENGINE_DATA") {
                "bundle staging rejected staged=${staged ?: "unreadable"} expected=${expected ?: "unscanned"}"
            }
            keepPrevious(context, root, "staging rejected")
            return
        }
        if (!installBundle(root, staging, previous)) {
            staging.deleteRecursively()
            FmLog.info("FM_ANDROID_ENGINE_DATA") { "bundle swap failed" }
            keepPrevious(context, root, "swap failed")
            return
        }
        previous.deleteRecursively()
        cacheIdentity(context, staged)
        FmLog.info("FM_ANDROID_ENGINE_DATA") { "bundle replaced identity=$staged" }
    }

    /**
     * A replacement that could not be installed leaves the previous bundle in place. Cache
     * its identity against this APK so the next launch does not repeat a 100 MB unpack: the
     * cache key is the APK file metadata, so the next install retries with the new bundle.
     */
    private fun keepPrevious(context: Context, root: File, reason: String) {
        if (!bundleComplete(root)) {
            throw IllegalStateException("engine bundle unavailable ($reason): no usable extraction at ${root.path}")
        }
        extractionIdentity(root)?.let { cacheIdentity(context, it) }
    }

    /** Two renames on the same filesystem; the second one is undone if it fails. */
    private fun installBundle(root: File, staging: File, previous: File): Boolean {
        val hadPrevious = root.isDirectory
        if (hadPrevious && !root.renameTo(previous)) return false
        if (!staging.renameTo(root)) {
            if (hadPrevious) previous.renameTo(root)
            return false
        }
        return true
    }

    /** Finish a swap a kill left half done, and drop a staging directory nobody verified. */
    private fun recoverInterruptedSwap(root: File) {
        val staging = File(root.parentFile, STAGING_DIR)
        val previous = File(root.parentFile, PREVIOUS_DIR)
        staging.deleteRecursively()
        if (root.isDirectory) {
            previous.deleteRecursively()
        } else if (previous.isDirectory && previous.renameTo(root)) {
            FmLog.info("FM_ANDROID_ENGINE_DATA") { "restored the previous extraction after an interrupted swap" }
        }
    }

    /** Identity of the extraction on disk; null when it has no readable manifest. */
    /**
     * 解包目录的身份：优先读解包时写进去的标记（含随包 zip 的 CRC/大小），
     * 老解包没有标记时退回 manifest 身份 —— 那一定和带 CRC 的随包身份不同，
     * 于是升级安装会重解一次，而不是继续用旧数据。
     */
    private fun extractionIdentity(root: File): String? {
        val marker = File(root, IDENTITY_MARKER)
        if (marker.isFile) {
            return runCatching { marker.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        val manifest = File(root, ShippedEngineBundle.MANIFEST_ENTRY)
        if (!manifest.isFile) return null
        return runCatching { ShippedEngineBundle.identityOf(manifest.readText()) }.getOrNull()
    }

    /** Everything the renderer opens is on disk: every style file, the photo, EXIF, hand table. */
    private fun bundleComplete(root: File): Boolean = runCatching {
        val manifest = Json.parse(File(root, ShippedEngineBundle.MANIFEST_ENTRY).readText()).jsonMap()
        val styles = manifest["styles"].jsonList().map { it.jsonMap() }
        if (styles.isEmpty()) return@runCatching false
        if (styles.any { entry -> entry.jsonString("style")?.let { File(root, it).isFile } != true }) {
            return@runCatching false
        }
        fun declared(key: String): Boolean = manifest.jsonString(key)?.let { File(root, it).isFile } == true
        if (!declared("photo") || !declared("exif")) return@runCatching false
        val handCards = manifest["hand"].jsonMap().jsonString("cards")
        handCards == null || File(root, handCards).isFile
    }.getOrDefault(false)

    /**
     * Identity of the bundle the APK ships, read from the zip's own `manifest.json`.
     *
     * Looking it up inside the zip means walking every entry before it (68 MB compressed at
     * 2026-11), so the result is cached against the APK file's own metadata: the asset
     * cannot change without a new install, and every install changes that metadata. Null
     * means "could not be read" — callers keep whatever extraction is on disk.
     */
    private fun shippedIdentity(context: Context): String? {
        val key = identityCacheKey(context) ?: return null
        val cache = File(context.filesDir, IDENTITY_CACHE)
        val cached = runCatching { cache.readText() }.getOrNull()
        if (cached != null) {
            val lines = cached.split('\n', limit = 2)
            if (lines.size == 2 && lines[0] == key) {
                return lines[1].trim().takeIf { it.isNotBlank() }
            }
        }
        val scanned = scanShippedIdentity(context)
        runCatching { cache.writeText(key + "\n" + (scanned ?: "")) }
        return scanned
    }

    private fun scanShippedIdentity(context: Context): String? = runCatching {
        val stamp = bundleStamp(context)
        context.assets.open(ASSET_ZIP).use { raw ->
            ZipInputStream(raw.buffered()).use { zip -> readManifestEntry(zip, stamp) }
        }
    }.getOrNull()

    /**
     * 随包 zip 在 APK 中央目录里的 `size + CRC32`：只读目录，不解压 70 MB。
     * 拿不到（APK 结构异常）时返回 null，身份退化成 `crc=- size=-`。
     */
    private fun bundleStamp(context: Context): ShippedEngineBundle.BundleStamp? = runCatching {
        java.util.zip.ZipFile(File(context.applicationInfo.sourceDir)).use { apk ->
            val entry = apk.getEntry(ASSET_ZIP) ?: return@use null
            ShippedEngineBundle.BundleStamp(size = entry.size, crc = entry.crc)
        }
    }.getOrNull()

    private fun readManifestEntry(
        zip: ZipInputStream,
        stamp: ShippedEngineBundle.BundleStamp?,
    ): String? {
        var scanned = 0L
        while (scanned <= MAX_SCAN_BYTES) {
            val entry = zip.nextEntry ?: return null
            if (entry.name == ShippedEngineBundle.MANIFEST_ENTRY) {
                return ShippedEngineBundle.identityOf(zip.readBytes().decodeToString(), stamp)
            }
            scanned += entry.size.coerceAtLeast(0L)
            zip.closeEntry()
        }
        return null
    }

    private fun identityCacheKey(context: Context): String? = runCatching {
        val apk = File(context.applicationInfo.sourceDir)
        "${apk.absolutePath}|${apk.lastModified()}|${apk.length()}"
    }.getOrNull()

    private fun cacheIdentity(context: Context, identity: String) {
        val key = identityCacheKey(context) ?: return
        runCatching { File(context.filesDir, IDENTITY_CACHE).writeText(key + "\n" + identity) }
    }

    /**
     * The bundled hand cards.json stores root-relative paths; the engine loads
     * them as given, so a rewritten absolute copy is produced once per extract.
     * org.json is used so the whole document round-trips untouched.
     */
    private fun rewriteHandCards(root: File, relative: File): String? {
        if (!relative.isFile) return null
        return runCatching {
            val doc = org.json.JSONObject(relative.readText())
            val cards = doc.optJSONArray("cards") ?: return null
            for (i in 0 until cards.length()) {
                val card = cards.optJSONObject(i) ?: continue
                card.optString("design").takeIf { it.isNotBlank() }?.let { card.put("design", abs(root, it)) }
                card.optJSONObject("photo")?.let { photo ->
                    photo.optString("path").takeIf { it.isNotBlank() }?.let { photo.put("path", abs(root, it)) }
                }
                card.optJSONObject("logo")?.let { logo ->
                    logo.optString("path").takeIf { it.isNotBlank() }?.let { logo.put("path", abs(root, it)) }
                }
                card.optJSONArray("extras")?.let { extras ->
                    for (j in 0 until extras.length()) {
                        extras.optJSONObject(j)?.let { ex ->
                            ex.optString("png").takeIf { it.isNotBlank() }?.let { ex.put("png", abs(root, it)) }
                        }
                    }
                }
            }
            val out = File(relative.parentFile, "cards.abs.json")
            out.writeText(doc.toString())
            out.absolutePath
        }.getOrNull()
    }

    private fun ids(cardsJson: String): List<String> = runCatching {
        val doc = org.json.JSONObject(File(cardsJson).readText())
        val cards = doc.getJSONArray("cards")
        (0 until cards.length()).mapNotNull { cards.getJSONObject(it).optString("card").takeIf { id -> id.isNotBlank() } }
    }.getOrDefault(emptyList())

    private fun abs(root: File, rel: String): String = File(root, rel).absolutePath

    private fun jsonObject(map: Map<String, String>): String =
        map.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"${escape(k)}\":\"${escape(v)}\""
        }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}