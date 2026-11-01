package com.framemark.native.engine

import android.content.Context
import android.os.SystemClock
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.PhotoSelection
import com.framemark.native.workflow.SampleFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Evidence-only harness for the per-call `photoPath`: in one app session renders
 * two cards (IR + hand) with the global photo and with two explicit overrides,
 * checks PhotoSelection is untouched, then exports three old cards without an
 * override for the byte-identity regression.
 */
object PerCallPhotoRunner {

    @Volatile
    private var running = false

    fun start(
        context: Context,
        renderer: AndroidNativeRender,
        cards: List<String>,
        photoG: String,
        photoA: String,
        photoB: String,
    ) {
        if (running) {
            println("FM_PERPHOTO_SKIP already-running")
            return
        }
        running = true
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val dir = File(app.getExternalFilesDir(null), "x1-percall")
            dir.deleteRecursively()
            dir.mkdirs()
            val out = File(dir, "percall.jsonl")
            out.writeText("")
            AndroidNativeRender.skipGalleryPublish = true
            // Test setup: pin the global photo, then verify the calls leave it alone.
            PhotoSelection.set(photoG)
            val before = PhotoSelection.path
            out.appendText(JSONObject().put("kind", "global_before").put("path", before).toString() + "\n")
            val wall = SystemClock.elapsedRealtime()
            try {
                for ((cardIndex, cardId) in cards.withIndex()) {
                    val design = DesignSnapshot(cardId, null, emptyMap(), emptyMap())
                    for (mode in listOf("preview", "export")) {
                        for ((variant, photo) in listOf(
                            "global" to null,
                            "A" to photoA,
                            "B" to photoB,
                        )) {
                            val started = SystemClock.elapsedRealtime()
                            val row = JSONObject().put("kind", "percall").put("card", cardId)
                                .put("mode", mode).put("variant", variant)
                            try {
                                val target = if (mode == "export")
                                    File(dir, "c$cardIndex-$variant.jpg").absolutePath else null
                                val image = renderer.render(
                                    cardId = cardId,
                                    design = design,
                                    mode = mode,
                                    maxEdge = if (mode == "preview") 1280 else null,
                                    format = if (mode == "export") SampleFormat.Jpeg else null,
                                    quality = if (mode == "export") 100 else null,
                                    longEdge = if (mode == "export") 1600 else null,
                                    outputPath = target,
                                    overlay = null,
                                    photoPath = photo,
                                )
                                row.put("ok", true)
                                    .put("w", image.width).put("h", image.height)
                                    .put("ms", image.ms).put("bytes", File(image.path).length())
                                    .put("path", image.path)
                                    .put("requestedPhoto", photo ?: "<global>")
                                image.photoRect?.let {
                                    row.put("photoRect", listOf(it.x, it.y, it.w, it.h))
                                }
                            } catch (t: Throwable) {
                                row.put("ok", false)
                                    .put("ms", SystemClock.elapsedRealtime() - started)
                                    .put("error", (t.message ?: t::class.simpleName ?: "error").take(400))
                            }
                            out.appendText(row.toString() + "\n")
                            println("FM_PERPHOTO_ROW $row")
                        }
                    }
                }
                val after = PhotoSelection.path
                out.appendText(
                    JSONObject().put("kind", "global_after").put("path", after)
                        .put("unchanged", after == before).toString() + "\n",
                )
                println("FM_PERPHOTO_GLOBAL before=$before after=$after unchanged=${after == before}")

                // Regression: no global photo, no override → bundled sample exports.
                PhotoSelection.set(null)
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
                            photoPath = null,
                        )
                        row.put("ok", true).put("w", image.width).put("h", image.height)
                            .put("bytes", File(image.path).length()).put("path", image.path)
                    } catch (t: Throwable) {
                        row.put("ok", false).put("error", (t.message ?: "error").take(400))
                    }
                    out.appendText(row.toString() + "\n")
                    println("FM_PERPHOTO_ROW $row")
                }
            } finally {
                AndroidNativeRender.skipGalleryPublish = false
                println("FM_PERPHOTO_DONE wallMs=${SystemClock.elapsedRealtime() - wall} dir=${dir.absolutePath}")
                running = false
            }
        }
    }
}
