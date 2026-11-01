package com.framemark.native.engine

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * Evidence-only batch renderer for the Android IR set (all styles in the runtime
 * bundle). Triggered by `am start … --ez fmRenderAll true`; it does not touch the
 * UI besides keeping the screen on while it runs.
 *
 * Writes `<cache>/ir-batch/<id>.png` (+ the engine's `<png>.geom.json`) and logs
 * one `FM_IR_BATCH` line per style for the host harness.
 */
object IrBatchRunner {
    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastSummary: String? = null
        private set

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    fun startHand(context: Context, maxEdge: Int) {
        if (running) return
        running = true
        val app = context.applicationContext
        Thread({
            val startedAt = SystemClock.elapsedRealtime()
            var ok = 0
            var fail = 0
            try {
                val data = EngineData.snapshot(app)
                val cardsJson = data.handCardsJson ?: error("no hand bundle")
                val handDir = data.handDir ?: error("no hand dir")
                val extra = data.handExtraFonts ?: error("no hand extra fonts")
                val outDir = File(app.cacheDir, "hand-batch")
                outDir.deleteRecursively()
                outDir.mkdirs()
                val list = data.handCardIds
                println("FM_HAND_BATCH_START cards=${list.size} edge=$maxEdge dir=${outDir.absolutePath}")
                val indexRows = mutableListOf<String>()
                list.forEachIndexed { index, id ->
                    val out = File(outDir, "$id.png")
                    val t0 = SystemClock.elapsedRealtime()
                    val error = NativeEngineProbe.renderHand(
                        cardsPath = cardsJson,
                        cardId = id,
                        handDir = handDir,
                        fontsDir = data.fonts,
                        extraFontsDir = extra,
                        fallbackCjk = File(data.fallback, "cjk.ttf").absolutePath,
                        // Batch capture keeps the bundled sample (same as before).
                        photoPath = "",
                        outPng = out.absolutePath,
                        maxEdge = maxEdge,
                    )
                    val ms = SystemClock.elapsedRealtime() - t0
                    if (error == null) {
                        ok++
                        println("FM_HAND_BATCH i=${index + 1}/${list.size} id=$id ms=$ms png=${out.absolutePath}")
                        indexRows += """{"id":"$id","ms":$ms,"png":"${out.name}"}"""
                    } else {
                        fail++
                        println("FM_HAND_BATCH i=${index + 1}/${list.size} id=$id ms=$ms ERR=$error")
                        indexRows += """{"id":"$id","ms":$ms,"error":"${escape(error)}"}"""
                    }
                }
                val total = SystemClock.elapsedRealtime() - startedAt
                File(outDir, "index.json").writeText(
                    """{"edge":$maxEdge,"ok":$ok,"fail":$fail,"totalMs":$total,"cards":[${indexRows.joinToString(",")}]}""",
                )
                println("FM_HAND_BATCH_DONE ok=$ok fail=$fail totalMs=$total dir=${outDir.absolutePath}")
            } catch (t: Throwable) {
                println("FM_HAND_BATCH_ABORT ${t.message}")
            } finally {
                running = false
            }
        }, "fm-hand-batch").start()
    }

    fun start(context: Context, maxEdge: Int, filter: String? = null) {
        if (running) return
        running = true
        val app = context.applicationContext
        Thread({
            val startedAt = SystemClock.elapsedRealtime()
            var ok = 0
            var fail = 0
            try {
                val data = EngineData.snapshot(app)
                val outDir = File(app.cacheDir, "ir-batch")
                outDir.deleteRecursively()
                outDir.mkdirs()
                val needles = filter?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                val list = data.styles.values.filter { s -> needles.isEmpty() || needles.any { s.id.contains(it) } }
                println("FM_IR_BATCH_START styles=${list.size} edge=$maxEdge dir=${outDir.absolutePath}")
                val indexRows = mutableListOf<String>()
                list.forEachIndexed { index, style ->
                    val safe = style.id.replace(':', '_').replace('/', '_')
                    val out = File(outDir, "$safe.png")
                    val t0 = SystemClock.elapsedRealtime()
                    val error = NativeEngineProbe.renderDesign(
                        style = style.style,
                        photo = data.photo,
                        exif = data.exif,
                        assetsJson = style.assetsJson,
                        fontsDir = data.fonts,
                        brandsDir = data.brands,
                        fallbackDir = data.fallback,
                        theme = "",
                        ratio = "",
                        outPng = out.absolutePath,
                        maxEdge = maxEdge,
                    )
                    val ms = SystemClock.elapsedRealtime() - t0
                    if (error == null) {
                        ok++
                        println(
                            "FM_IR_BATCH i=${index + 1}/${list.size} id=${style.id} ms=$ms " +
                                "png=${out.absolutePath} geom=${out.absolutePath}.geom.json",
                        )
                        indexRows += """{"id":"${escape(style.id)}","ms":$ms,"png":"${escape(out.name)}"}"""
                    } else {
                        fail++
                        println("FM_IR_BATCH i=${index + 1}/${list.size} id=${style.id} ms=$ms ERR=$error")
                        indexRows += """{"id":"${escape(style.id)}","ms":$ms,"error":"${escape(error)}"}"""
                    }
                }
                val total = SystemClock.elapsedRealtime() - startedAt
                lastSummary = "ok=$ok fail=$fail totalMs=$total"
                File(outDir, "index.json").writeText(
                    """{"edge":$maxEdge,"ok":$ok,"fail":$fail,"totalMs":$total,"styles":[${indexRows.joinToString(",")}]}""",
                )
                println("FM_IR_BATCH_DONE ok=$ok fail=$fail totalMs=$total dir=${outDir.absolutePath}")
            } catch (t: Throwable) {
                println("FM_IR_BATCH_ABORT ${t.message}")
            } finally {
                running = false
            }
        }, "fm-ir-batch").start()
    }
}