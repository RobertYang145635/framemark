package com.framemark.native.engine

import android.content.Context
import android.os.SystemClock
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.OverlaySpec
import com.framemark.native.workflow.SampleFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Evidence-only harness for the render-v1 overlay bridge: renders one card with
 * and without an overlay (preview + export), exercises the error paths, and
 * exports three known cards without overlay for the byte-identity regression.
 */
object OverlayAbRunner {

    @Volatile
    private var running = false

    fun start(
        context: Context,
        renderer: AndroidNativeRender,
        cardId: String,
        overlayPath: String,
        rect: FloatArray,
        opacity: Float,
    ) {
        if (running) {
            println("FM_OVERLAY_SKIP already-running")
            return
        }
        running = true
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val dir = File(app.getExternalFilesDir(null), "x1-overlay")
            dir.deleteRecursively()
            dir.mkdirs()
            val out = File(dir, "overlay.jsonl")
            out.writeText("")
            val spec = OverlaySpec(overlayPath, rect[0], rect[1], rect[2], rect[3], opacity)
            val design = DesignSnapshot(cardId, null, emptyMap(), emptyMap())
            AndroidNativeRender.skipGalleryPublish = true
            val wall = SystemClock.elapsedRealtime()
            try {
                for (mode in listOf("preview", "export")) {
                    for (variant in listOf("base", "overlay")) {
                        val row = JSONObject().put("kind", "ab").put("card", cardId).put("mode", mode).put("variant", variant)
                        val started = SystemClock.elapsedRealtime()
                        try {
                            val image = renderer.render(
                                cardId = cardId,
                                design = design,
                                mode = mode,
                                maxEdge = if (mode == "preview") 1280 else null,
                                format = if (mode == "export") SampleFormat.Jpeg else null,
                                quality = if (mode == "export") 100 else null,
                                longEdge = if (mode == "export") 1600 else null,
                                outputPath = if (mode == "export") File(dir, "$variant.jpg").absolutePath else null,
                                overlay = if (variant == "overlay") spec else null,
                            )
                            row.put("ok", true)
                                .put("w", image.width)
                                .put("h", image.height)
                                .put("ms", image.ms)
                                .put("bytes", File(image.path).length())
                                .put("path", image.path)
                            image.photoRect?.let {
                                row.put("photoRect", listOf(it.x, it.y, it.w, it.h))
                            }
                            overlayDst(image.path)?.let { row.put("overlayDst", it) }
                        } catch (t: Throwable) {
                            row.put("ok", false)
                                .put("ms", SystemClock.elapsedRealtime() - started)
                                .put("error", (t.message ?: t::class.simpleName ?: "error").take(400))
                        }
                        out.appendText(row.toString() + "\n")
                        println("FM_OVERLAY_ROW $row")
                    }
                }

                // Error paths: missing file and an undecodable file must fail.
                val badText = File(dir, "not-an-image.txt").apply { writeText("not an image") }
                val errorCases = listOf(
                    "missing" to File(dir, "no-such-overlay.png").absolutePath,
                    "undecodable" to badText.absolutePath,
                )
                for ((name, path) in errorCases) {
                    val row = JSONObject().put("kind", "error").put("case", name).put("path", path)
                    try {
                        renderer.render(
                            cardId = cardId, design = design, mode = "preview", maxEdge = 640,
                            format = null, quality = null, longEdge = null, outputPath = null,
                            overlay = spec.copy(path = path),
                        )
                        row.put("ok", true).put("error", "<no error>")
                    } catch (t: Throwable) {
                        row.put("ok", false).put("error", (t.message ?: "error").take(400))
                    }
                    out.appendText(row.toString() + "\n")
                    println("FM_OVERLAY_ROW $row")
                }

                // No-overlay regression exports for the three known cards.
                for (regCard in listOf("stock-xiaomi-tag-9", "blur-classic", "vendor-hasselblad-h")) {
                    val row = JSONObject().put("kind", "regression").put("card", regCard)
                    try {
                        val image = renderer.render(
                            cardId = regCard,
                            design = DesignSnapshot(regCard, null, emptyMap(), emptyMap()),
                            mode = "export",
                            maxEdge = null,
                            format = SampleFormat.Jpeg,
                            quality = 100,
                            longEdge = 1600,
                            outputPath = File(dir, "reg-${regCard.replace(Regex("[^A-Za-z0-9._-]"), "_")}.jpg").absolutePath,
                            overlay = null,
                        )
                        row.put("ok", true).put("w", image.width).put("h", image.height)
                            .put("bytes", File(image.path).length()).put("path", image.path)
                    } catch (t: Throwable) {
                        row.put("ok", false).put("error", (t.message ?: "error").take(400))
                    }
                    out.appendText(row.toString() + "\n")
                    println("FM_OVERLAY_ROW $row")
                }
            } finally {
                AndroidNativeRender.skipGalleryPublish = false
                println("FM_OVERLAY_DONE wallMs=${SystemClock.elapsedRealtime() - wall} dir=${dir.absolutePath}")
                running = false
            }
        }
    }

    /** Reads the overlay dst from either geom sidecar shape (IR or hand). */
    private fun overlayDst(pngPath: String): Any? = runCatching {
        val geom = File("$pngPath.geom.json")
        if (!geom.isFile) return null
        val doc = JSONObject(geom.readText())
        doc.optJSONObject("overlay")?.optJSONArray("dst")?.let { d ->
            return listOf(d.getDouble(0), d.getDouble(1), d.getDouble(2), d.getDouble(3))
        }
        val images = doc.optJSONObject("report")?.optJSONArray("images") ?: return null
        for (i in 0 until images.length()) {
            val item = images.optJSONObject(i) ?: continue
            if (item.optString("key") == "overlay") {
                val d = item.optJSONObject("dst") ?: continue
                return listOf(d.optDouble("x"), d.optDouble("y"), d.optDouble("w"), d.optDouble("h"))
            }
        }
        null
    }.getOrNull()
}
