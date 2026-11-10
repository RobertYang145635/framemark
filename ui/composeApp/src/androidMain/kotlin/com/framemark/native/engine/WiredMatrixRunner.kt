package com.framemark.native.engine

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.SampleFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import com.framemark.native.product.FmLog

/**
 * Evidence-only harness: drives [AndroidNativeRender.render] over every wired
 * card (all 106 IR + all 69 hand), preview and export, and writes one JSONL row
 * per call. Failures are recorded with their exact reason and the run continues.
 */
object WiredMatrixRunner {

    @Volatile
    private var running = false

    fun start(
        context: Context,
        renderer: AndroidNativeRender,
        previewEdge: Int,
        exportEdge: Int,
        kinds: String,
    ) {
        if (running) {
            FmLog.info("FM_MATRIX_SKIP") { "already-running" }
            return
        }
        running = true
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val data = EngineData.snapshot(app)
            val ir = data.styles.values
                .filter { !it.uiCardId.isNullOrBlank() }
                .sortedBy { it.id }
                .map { Triple(it.uiCardId!!, "ir", it.id) }
            val hand = data.handCardIds.sorted().map { Triple(it, "hand", it) }
            val cards = when (kinds) {
                "ir" -> ir
                "hand" -> hand
                else -> ir + hand
            }
            val dir = File(app.getExternalFilesDir(null), "x1-matrix")
            dir.mkdirs()
            val out = File(dir, "matrix.jsonl")
            out.writeText("")
            val meta = JSONObject()
                .put("kind", "meta")
                .put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("previewEdge", previewEdge)
                .put("exportEdge", exportEdge)
                .put("cards", cards.size)
                .put("ir", ir.size)
                .put("hand", hand.size)
            out.appendText(meta.toString() + "\n")
            FmLog.info("FM_MATRIX_META") { "$meta" }

            AndroidNativeRender.skipGalleryPublish = true
            val wall = SystemClock.elapsedRealtime()
            var rows = 0
            try {
                for ((cardId, kind, styleId) in cards) {
                    for (mode in listOf("preview", "export")) {
                        val started = SystemClock.elapsedRealtime()
                        val row = JSONObject()
                            .put("card", cardId)
                            .put("kind", kind)
                            .put("style", styleId)
                            .put("mode", mode)
                        try {
                            val image = if (mode == "preview") {
                                renderer.render(
                                    cardId = cardId,
                                    design = DesignSnapshot(cardId, styleId, emptyMap(), emptyMap()),
                                    mode = "preview",
                                    maxEdge = previewEdge,
                                    format = null,
                                    quality = null,
                                    longEdge = null,
                                    outputPath = null,
                                )
                            } else {
                                val target = File(dir, "$kind-${safe(cardId)}.jpg")
                                renderer.render(
                                    cardId = cardId,
                                    design = DesignSnapshot(cardId, styleId, emptyMap(), emptyMap()),
                                    mode = "export",
                                    maxEdge = null,
                                    format = SampleFormat.Jpeg,
                                    quality = 100,
                                    longEdge = exportEdge,
                                    outputPath = target.absolutePath,
                                )
                            }
                            row.put("ok", true)
                                .put("w", image.width)
                                .put("h", image.height)
                                .put("ms", image.ms)
                                .put("bytes", File(image.path).length())
                                .put("path", image.path)
                        } catch (t: Throwable) {
                            row.put("ok", false)
                                .put("ms", SystemClock.elapsedRealtime() - started)
                                .put("error", (t.message ?: t::class.simpleName ?: "error").take(400))
                        }
                        out.appendText(row.toString() + "\n")
                        FmLog.info("FM_MATRIX_ROW") { "$row" }
                        rows++
                        if (rows % 20 == 0) FmLog.info("FM_MATRIX_PROGRESS") { "$rows/${cards.size * 2}" }
                    }
                }
            } finally {
                AndroidNativeRender.skipGalleryPublish = false
                FmLog.info("FM_MATRIX_DONE") {
                    "rows=$rows wallMs=${SystemClock.elapsedRealtime() - wall} " +
                        "dir=${dir.absolutePath}"
                }
                running = false
            }
        }
    }

    private fun safe(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
