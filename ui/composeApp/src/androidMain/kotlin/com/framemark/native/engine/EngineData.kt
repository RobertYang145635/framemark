package com.framemark.native.engine

import android.content.Context
import com.framemark.native.library.Json
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonInt
import com.framemark.native.library.jsonString
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Runtime bundle for the Android IR renderer: `assets/x1ir.zip` holds all 106 IR
 * styles, their assets, the brand wordmarks, the fonts tree, the sample photo and
 * the EXIF source. First use extracts it into the app's files dir; later launches
 * reuse the extraction (keyed by the zip's byte size).
 */
object EngineData {
    private const val ASSET_ZIP = "x1ir.zip"
    private const val DIR = "x1ir"
    /** Bump when the bundled zip changes; drives the one-time extraction. */
    private const val BUNDLE_VERSION = "ir-20261029-3"

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
        val marker = File(root, ".version")
        val current = marker.takeIf { it.isFile }?.readText()
        val manifestFile = File(root, "manifest.json")
        if (current != BUNDLE_VERSION || !manifestFile.isFile) {
            root.deleteRecursively()
            root.mkdirs()
            extract(context, root)
            marker.writeText(BUNDLE_VERSION)
        }
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
        println("FM_ANDROID_ENGINE_DATA root=${root.absolutePath} styles=${styles.size}")
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