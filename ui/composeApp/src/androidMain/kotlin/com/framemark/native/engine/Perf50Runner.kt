package com.framemark.native.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.SampleFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * P4c evidence-only harness: repeats real 50 MP exports through the app's own
 * renderer and records per-run timing, output size and digest. It is reachable
 * only from an explicit `fmPerf50` launch extra — nothing in the UI calls it, and
 * gallery publishing stays off so a perf sweep cannot write into the user's album.
 */
object Perf50Runner {

    @Volatile
    private var running = false

    fun start(
        context: Context,
        renderer: AndroidNativeRender,
        cards: List<String>,
        photo: String,
        formats: List<String>,
        edge: Int?,
        runs: Int,
        webpLossless: Boolean,
    ) {
        if (running) {
            println("FM_PERF50_SKIP already-running")
            return
        }
        if (cards.isEmpty() || !File(photo).isFile) {
            println("FM_PERF50_BADARGS cards=${cards.size} photo=$photo")
            return
        }
        running = true
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val dir = File(app.getExternalFilesDir(null), "x1-perf")
            dir.mkdirs()
            val log = File(dir, "perf50.jsonl")
            log.writeText("")
            AndroidNativeRender.skipGalleryPublish = true
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val env = JSONObject()
                .put("kind", "env")
                .put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("pid", android.os.Process.myPid())
                .put("heapMaxMb", Runtime.getRuntime().maxMemory() / 1024 / 1024)
                .put("dalvikLimitMb", Runtime.getRuntime().maxMemory() / 1024 / 1024)
                .put("deviceRamMb", mem.totalMem / 1024 / 1024)
                .put("availMemMb", mem.availMem / 1024 / 1024)
                .put("lowRam", mem.lowMemory)
                .put("photo", photo)
                .put("photoBytes", File(photo).length())
                .put("edge", edge ?: "native")
                .put("runs", runs)
            log.appendText(env.toString() + "\n")
            println("FM_PERF50_ENV $env")
            var n = 0
            val total = cards.size * formats.size * runs
            try {
                for (card in cards) {
                    for (format in formats) {
                        for (run in 1..runs) {
                            val ext = when (format) {
                                "png" -> "png"
                                "webp" -> "webp"
                                else -> "jpg"
                            }
                            val target = File(dir, "p50-$card-$format-$run.$ext")
                            val started = SystemClock.elapsedRealtime()
                            val wall0 = System.currentTimeMillis()
                            val row = JSONObject()
                                .put("card", card)
                                .put("format", format)
                                .put("run", run)
                                .put("edge", edge ?: "native")
                                .put("photoBytes", File(photo).length())
                            try {
                                val image = renderer.render(
                                    cardId = card,
                                    design = DesignSnapshot(card, null, emptyMap(), emptyMap()),
                                    mode = "export",
                                    maxEdge = null,
                                    format = sampleFormat(format),
                                    quality = 100,
                                    longEdge = edge,
                                    outputPath = target.absolutePath,
                                    webpLossless = webpLossless,
                                    overlay = null,
                                    photoPath = photo,
                                )
                                val file = File(image.path)
                                row.put("ok", true)
                                    .put("w", image.width)
                                    .put("h", image.height)
                                    .put("engineMs", image.ms)
                                    .put("bytes", file.length())
                                    .put("sha256", shaOf(file))
                                    .put("out", file.absolutePath)
                                // 原尺寸口径要说"照片有没有被重采样"，就得量引擎自己写的
                                // photo 绘制框：它是 canvas 空间里的像素矩形。
                                geomOf(app)?.let { (k, v) -> row.put(k, v) }
                            } catch (t: Throwable) {
                                row.put("ok", false)
                                    .put("error", (t.message ?: t::class.simpleName ?: "error").take(240))
                            }
                            row.put("wallMs", SystemClock.elapsedRealtime() - started)
                            row.put("wallStartEpochMs", wall0)
                            row.put("wallEndEpochMs", System.currentTimeMillis())
                            row.put("availMemMbAfter", ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / 1024 / 1024)
                            log.appendText(row.toString() + "\n")
                            println("FM_PERF50_ROW $row")
                            n++
                            println("FM_PERF50_PROGRESS $n/$total")
                            delay(BETWEEN_RUN_MS)
                        }
                    }
                }
            } finally {
                AndroidNativeRender.skipGalleryPublish = false
                println("FM_PERF50_DONE rows=$n dir=${dir.absolutePath}")
                running = false
            }
        }
    }

    @Volatile
    private var lastGeomPath: String? = null

    /** The engine's own geometry dump for the render that just happened, if new. */
    private fun geomOf(app: Context): Pair<String, JSONObject>? {
        val dir = File(app.cacheDir, "x1")
        val newest = dir.listFiles { f -> f.name.endsWith(".geom.json") }?.maxByOrNull { it.lastModified() } ?: return null
        if (newest.absolutePath == lastGeomPath) return null
        lastGeomPath = newest.absolutePath
        val doc = runCatching { JSONObject(newest.readText()) }.getOrNull() ?: return null
        val geom = JSONObject()
            .put("file", newest.name)
            .put("canvas", doc.optJSONArray("canvas"))
            .put("photo", doc.optJSONArray("photo"))
            .put("photoRect", doc.optJSONArray("photo_rect"))
            .put("output", doc.optJSONArray("output"))
        return "geom" to geom
    }

    private fun sampleFormat(name: String): SampleFormat = when (name) {        "png" -> SampleFormat.Png
        "webp" -> SampleFormat.WebP
        else -> SampleFormat.Jpeg
    }

    private fun shaOf(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("unreadable")

    private const val BETWEEN_RUN_MS = 1800L
}
