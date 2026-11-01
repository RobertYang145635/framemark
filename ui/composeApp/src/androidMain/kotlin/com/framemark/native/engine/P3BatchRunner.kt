package com.framemark.native.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.ExportSettings
import com.framemark.native.workflow.NativeEngine
import com.framemark.native.workflow.OverlaySpec
import com.framemark.native.workflow.PhotoSelection
import com.framemark.native.workflow.SampleFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Evidence-only harness for the phone P3 batch: three photos through the real
 * batch entry point ([NativeEngine.exportCard], one row at a time), the overlay
 * A/B on the classic-22 and multi-band vivo-postcard cards, the old P3 source
 * control (a photo that already carries a baked watermark), and the three-card
 * byte-identity regression.
 */
object P3BatchRunner {

    @Volatile
    private var running = false
    private var cacheX1: File? = null

    private data class BatchRow(
        val label: String,
        val cardId: String,
        val photo: String,
        val overlay: OverlaySpec?,
        val kind: String,
    )

    fun start(
        context: Context,
        renderer: AndroidNativeRender,
        photo1: String,
        photo2: String,
        photo3: String,
        photoOld: String,
        overlayPath: String,
    ) {
        if (running) {
            println("FM_P3_SKIP already-running")
            return
        }
        running = true
        val app = context.applicationContext
        cacheX1 = File(app.cacheDir, "x1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val dir = File(app.getExternalFilesDir(null), "x1-p3")
            dir.deleteRecursively()
            dir.mkdirs()
            val out = File(dir, "p3.jsonl")
            out.writeText("")
            val settings = ExportSettings()
            // Same rect the UI builds for a top-left 15% watermark at a 5% margin:
            // height follows the photo aspect so contain() has no letterboxing.
            fun wmFor(photo: String): OverlaySpec {
                val aspect = sourceDims(File(photo))?.let { it[0].toFloat() / it[1].coerceAtLeast(1) } ?: 1.5f
                return OverlaySpec(overlayPath, 0.05f, 0.05f, 0.15f, 0.15f * aspect / 2f, 0.5f)
            }
            val wm = wmFor(photo3)
            val classic = "stock-xiaomi-classic-22"
            val tag = "stock-xiaomi-tag-9"
            val multiBand = "stock-vivo-postcard-frame"
            val design = { card: String -> DesignSnapshot(card, null, emptyMap(), emptyMap()) }
            val globalBefore = PhotoSelection.path
            AndroidNativeRender.skipGalleryPublish = true
            val wall = SystemClock.elapsedRealtime()
            try {
                // The P3 batch itself: 2 borders + 1 watermark, one row per photo,
                // through the same exportCard() call the batch screen uses.
                val batch = listOf(
                    BatchRow("P3-A", classic, photo1, null, "batch"),
                    BatchRow("P3-B", tag, photo2, null, "batch"),
                    BatchRow("P3-C", classic, photo3, wm, "batch"),
                )
                for (row in batch) exportCard(out, dir, row, settings, design(row.cardId))

                // Same photo/card without the watermark: the diff baseline for C.
                exportCard(out, dir, BatchRow("P3-C0", classic, photo3, null, "ab"), settings, design(classic))
                // Multi-band IR card (vivo-postcard/frame) with and without overlay.
                exportCard(out, dir, BatchRow("MB0", multiBand, photo1, null, "multiband"), settings, design(multiBand))
                exportCard(out, dir, BatchRow("MB1", multiBand, photo1, wmFor(photo1), "multiband"), settings, design(multiBand))
                // Old P3 source photo (an earlier export with a baked watermark):
                // no overlay shows whatever is already in its pixels.
                exportCard(out, dir, BatchRow("OLD0", classic, photoOld, null, "oldsource"), settings, design(classic))
                exportCard(out, dir, BatchRow("OLD1", classic, photoOld, wmFor(photoOld), "oldsource"), settings, design(classic))

                // Preview shots for the visual sheet.
                preview(out, dir, renderer, "PRE-C", classic, photo3, wm)
                preview(out, dir, renderer, "PRE-C0", classic, photo3, null)
                preview(out, dir, renderer, "PRE-MB1", multiBand, photo1, wmFor(photo1))
                preview(out, dir, renderer, "PRE-MB0", multiBand, photo1, null)
                preview(out, dir, renderer, "PRE-OLD0", classic, photoOld, null)

                // Three no-overlay exports that must stay byte-identical to
                // X1-ANDROID7/11/12.
                for (regCard in listOf("stock-xiaomi-tag-9", "blur-classic", "vendor-hasselblad-h")) {
                    val row = JSONObject().put("kind", "regression").put("label", "REG-${regCard}")
                        .put("card", regCard).put("mode", "export")
                    try {
                        val image = renderer.render(
                            cardId = regCard,
                            design = design(regCard),
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
                            .put("sha256", sha256(File(image.path)))
                            .put("photoRect", rectJson(image.photoRect?.let { listOf(it.x, it.y, it.w, it.h) }))
                    } catch (t: Throwable) {
                        row.put("ok", false).put("error", (t.message ?: "error").take(400))
                    }
                    out.appendText(row.toString() + "\n")
                    println("FM_P3_ROW $row")
                }
            } finally {
                AndroidNativeRender.skipGalleryPublish = false
                println(
                    "FM_P3_DONE wallMs=${SystemClock.elapsedRealtime() - wall} dir=${dir.absolutePath} " +
                        "globalBefore=${globalBefore ?: "<sample>"} globalAfter=${PhotoSelection.path ?: "<sample>"}",
                )
                running = false
            }
        }
    }

    private suspend fun exportCard(
        out: File,
        dir: File,
        row: BatchRow,
        settings: ExportSettings,
        design: DesignSnapshot,
    ) {
        val json = JSONObject().put("kind", row.kind).put("label", row.label)
            .put("card", row.cardId).put("mode", "export")
            .put("photo", row.photo).put("photoSha", sha256(File(row.photo)))
            .put("overlay", row.overlay?.path ?: "<none>")
            .put("rect", row.overlay?.let { listOf(it.x, it.y, it.w, it.h) })
            .put("opacity", row.overlay?.opacity)
        sourceDims(File(row.photo))?.let { json.put("sourceDims", it) }
        val started = SystemClock.elapsedRealtime()
        try {
            val result = NativeEngine.exportCard(
                cardId = row.cardId,
                cardName = row.label,
                styleId = null,
                settings = settings,
                design = design,
                overlay = row.overlay,
                photoPath = row.photo,
            )
            val copy = File(dir, "${row.label}.jpg")
            File(result.path).copyTo(copy, overwrite = true)
            json.put("ok", true)
                .put("w", result.width).put("h", result.height)
                .put("bytes", result.bytes).put("path", result.path)
                .put("copy", copy.absolutePath).put("sha256", sha256(copy))
            val geom = newestGeom()
            geom?.let {
                json.put("geomPhoto", it.optJSONArray("photo"))
                    .put("geomCanvas", it.optJSONArray("canvas"))
                    .put("geomOutput", it.optJSONArray("output"))
                it.optJSONObject("overlay")?.optJSONArray("dst")?.let { d -> json.put("overlayDst", d) }
            }
            json.put("ms", SystemClock.elapsedRealtime() - started)
        } catch (t: Throwable) {
            json.put("ok", false).put("ms", SystemClock.elapsedRealtime() - started)
                .put("error", (t.message ?: t::class.simpleName ?: "error").take(400))
        }
        out.appendText(json.toString() + "\n")
        println("FM_P3_ROW $json")
    }

    private suspend fun preview(
        out: File,
        dir: File,
        renderer: AndroidNativeRender,
        label: String,
        card: String,
        photo: String,
        overlay: OverlaySpec?,
    ) {
        val json = JSONObject().put("kind", "preview").put("label", label)
            .put("card", card).put("mode", "preview")
            .put("photo", photo).put("overlay", overlay?.path ?: "<none>")
        val started = SystemClock.elapsedRealtime()
        try {
            val image = renderer.render(
                cardId = card,
                design = DesignSnapshot(card, null, emptyMap(), emptyMap()),
                mode = "preview",
                maxEdge = 1280,
                format = null,
                quality = null,
                longEdge = null,
                outputPath = null,
                overlay = overlay,
                photoPath = photo,
            )
            val copy = File(dir, "$label.png")
            File(image.path).copyTo(copy, overwrite = true)
            json.put("ok", true).put("w", image.width).put("h", image.height)
                .put("bytes", copy.length()).put("copy", copy.absolutePath)
                .put("sha256", sha256(copy))
                .put("photoRect", rectJson(image.photoRect?.let { listOf(it.x, it.y, it.w, it.h) }))
            newestGeom()?.optJSONObject("overlay")?.optJSONArray("dst")?.let { d ->
                json.put("overlayDst", d)
            }
        } catch (t: Throwable) {
            json.put("ok", false).put("error", (t.message ?: t::class.simpleName ?: "error").take(400))
        }
        json.put("ms", SystemClock.elapsedRealtime() - started)
        out.appendText(json.toString() + "\n")
        println("FM_P3_ROW $json")
    }

    /** Newest geom sidecar in the renderer's cache dir. */
    private fun newestGeom(): JSONObject? = runCatching {
        val geom = cacheX1?.listFiles { f -> f.name.endsWith(".geom.json") }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        JSONObject(geom.readText())
    }.getOrNull()

    private fun sourceDims(file: File): List<Int>? {
        if (!file.isFile) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return if (opts.outWidth > 0) listOf(opts.outWidth, opts.outHeight) else null
    }

    private fun rectJson(r: List<Float>?): Any? = r

    private fun sha256(file: File): String? = runCatching {
        if (!file.isFile) return null
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                md.update(buffer, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
