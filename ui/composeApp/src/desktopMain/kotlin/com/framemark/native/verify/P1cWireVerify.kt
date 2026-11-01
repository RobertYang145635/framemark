package com.framemark.native.verify

import com.framemark.native.engine.DesktopEngineBridge
import com.framemark.native.engine.EngineCatalog
import com.framemark.native.engine.EngineConfigException
import com.framemark.native.engine.RuntimePaths
import com.framemark.native.library.Json
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString
import com.framemark.native.workflow.OverlaySpec
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * P1c wire evidence: drive the **app bridge** (DesktopEngineBridge) over the
 * 175-card runtime and write machine-readable evidence.
 *
 *   java -cp <desktop classpath> com.framemark.native.verify.P1cWireVerifyKt \
 *        --out _research/native/p1c-wire
 *
 * A: 175/175 render, raw SHA compared with p1c/coverage.json (all + 20 sample)
 * B: three photos on one IR + one hand card
 * C: watermark A/B (none / rect+opacity A / B) on IR + hand
 * D: untouched design no-op vs p1c coverage raw and vs the P1b baseline
 * E: structured failures (unknown card / missing photo / missing overlay)
 */
private fun arg(args: Array<String>, name: String): String? {
    val i = args.indexOf("--$name")
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun q(text: String): String = buildString {
    append('"')
    text.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
    }
    append('"')
}

private fun jsonField(name: String, value: String): String = q(name) + ":" + q(value)
private fun jsonField(name: String, value: Number): String = q(name) + ":" + value
private fun jsonField(name: String, value: Boolean): String = q(name) + ":" + value

private data class RenderRow(
    val cardId: String,
    val backend: String,
    val ms: Long,
    val width: Int,
    val height: Int,
    val sha: String,
)

private data class PhotoRow(
    val photo: String,
    val sha: String,
    val width: Int,
    val height: Int,
)

private data class OverlayRow(
    val baseSha: String,
    val overlayASha: String,
    val overlayBSha: String,
) {
    val overlayChanged: Boolean get() = overlayASha != baseSha
    val positionChanged: Boolean get() = overlayBSha != baseSha && overlayBSha != overlayASha
}

private data class NoopRow(
    val cardId: String,
    val coverageSha: String?,
    val bridgeSha: String,
    val p1bSha: String?,
)

private data class FailureRow(
    val case: String,
    val code: String,
    val message: String,
    val outputCreated: Boolean,
)

fun main(args: Array<String>) {
    val root = File("").absoluteFile
    val out = File(arg(args, "out") ?: "_research/native/p1c-wire").let {
        if (it.isAbsolute) it else File(root, it.path)
    }
    val skipCoverage = args.contains("--skip-coverage")
    out.mkdirs()
    val renderDir = File(out, "render").apply { mkdirs() }
    val tmpDir = File(out, "tmp").apply { mkdirs() }
    val browserWork = File(out, "engine-work").apply { mkdirs() }
    val failDir = File(out, "fail").apply { mkdirs() }
    failDir.listFiles()?.forEach { it.delete() }

    RuntimePaths.resetForVerify()
    val layout = RuntimePaths.layout()
    val catalog = EngineCatalog.load(layout)
    val bridge = DesktopEngineBridge(catalog = catalog, workDir = browserWork.path)
    println("P1C_RUNTIME cards=${catalog.cards().size} data=${catalog.dataRoot} manifest=${catalog.manifestPath}")

    val coverage = Json.parse(File(root, "_research/native/p1c/coverage.json").readText()).jsonMap()
    val coverageSha = HashMap<String, String>()
    val coverageDims = HashMap<String, Pair<Int, Int>>()
    coverage["cards"].jsonList().forEach { raw ->
        val map = raw.jsonMap()
        val id = map.jsonString("cardId") ?: return@forEach
        val rawImage = map["raw"].jsonMap()
        val sha = rawImage.jsonString("sha256") ?: return@forEach
        coverageSha[id] = sha
        val w = (rawImage["w"] as? Number)?.toInt() ?: 0
        val h = (rawImage["h"] as? Number)?.toInt() ?: 0
        coverageDims[id] = w to h
    }
    val p1bSha = HashMap<String, String>()
    val p1bFile = File(root, "_research/native/p1/noop/noop-sha.json")
    if (p1bFile.isFile) {
        val doc = Json.parse(p1bFile.readText()).jsonMap()
        doc["cards"].jsonList().forEach { raw ->
            val map = raw.jsonMap()
            val id = map.jsonString("cardId") ?: return@forEach
            map.jsonString("baselineSha256")?.let { p1bSha[id] = it }
        }
    }

    // ---- A: 175/175 through the app bridge ---------------------------------
    val rows = mutableListOf<RenderRow>()
    val failuresA = mutableListOf<String>()
    if (!skipCoverage) {
        val startedAll = System.currentTimeMillis()
        catalog.cards().forEachIndexed { index, card ->
            val target = File(tmpDir, "${card.cardId}.png")
            try {
                val result = bridge.render(
                    cardId = card.cardId,
                    params = emptyMap(),
                    text = emptyMap(),
                    mode = "preview",
                    maxEdge = 20_000,
                    format = null,
                    quality = null,
                    longEdge = null,
                    outputPath = target.path,
                )
                val digest = sha256(target)
                rows += RenderRow(card.cardId, card.backend, result.ms, result.width, result.height, digest)
                if ((index + 1) % 25 == 0) println("P1C_A progress=${index + 1}/175")
            } catch (t: Throwable) {
                failuresA += "${card.cardId}: ${t.message}"
                println("P1C_A FAIL ${card.cardId}: ${t.message}")
            } finally {
                target.delete()
                browserWork.listFiles()?.forEach { it.delete() }
            }
        }
        println("P1C_A done rows=${rows.size} failures=${failuresA.size} ms=${System.currentTimeMillis() - startedAll}")
    } else {
        println("P1C_A skipped")
    }

    val matched = rows.count { coverageSha[it.cardId] == it.sha }
    val dimsMatched = rows.count { coverageDims[it.cardId] == (it.width to it.height) }
    val sample = rows.filter { it.backend == "ir" }.take(10) + rows.filter { it.backend == "hand" }.take(10)
    val sampleMatched = sample.count { coverageSha[it.cardId] == it.sha }

    // ---- B: three photos ----------------------------------------------------
    val photoPaths = listOf(
        File(root, "_research/test-assets/ratio-study/4x3.jpg"),
        File(root, "_research/test-assets/vertical.jpg"),
        File(root, "_research/test-assets/p1170883.jpg"),
    )
    require(photoPaths.all { it.isFile }) { "photo fixtures missing: $photoPaths" }

    fun renderPhotos(cardId: String): List<PhotoRow> = photoPaths.mapIndexed { index, photo ->
        val target = File(renderDir, "photo-$cardId-$index.png")
        val result = bridge.render(
            cardId = cardId,
            params = emptyMap(),
            text = emptyMap(),
            mode = "preview",
            maxEdge = 1200,
            format = null,
            quality = null,
            longEdge = null,
            outputPath = target.path,
            photoPath = photo.path,
        )
        PhotoRow(photo.name, sha256(target), result.width, result.height)
    }
    val photosIr = renderPhotos("stock-oppo-hassel_style_1")
    val photosHand = renderPhotos("simple-1")
    val photosDistinctIr = photosIr.map { it.sha }.toSet().size == photoPaths.size
    val photosDistinctHand = photosHand.map { it.sha }.toSet().size == photoPaths.size

    // ---- C: watermark A/B ---------------------------------------------------
    val wmSource = File(root, "_research/test-assets/root-import/水印_白色.png")
    require(wmSource.isFile) { "watermark fixture missing: $wmSource" }
    val wm = File(out, "assets/watermark-white.png").apply {
        parentFile.mkdirs()
        wmSource.copyTo(this, overwrite = true)
    }

    fun renderOverlay(cardId: String, spec: OverlaySpec?, tag: String): String {
        val target = File(renderDir, "wm-$cardId-$tag.png")
        bridge.render(
            cardId = cardId,
            params = emptyMap(),
            text = emptyMap(),
            mode = "preview",
            maxEdge = 1200,
            format = null,
            quality = null,
            longEdge = null,
            outputPath = target.path,
            overlay = spec,
        )
        return sha256(target)
    }

    fun overlayRun(cardId: String): OverlayRow {
        val base = renderOverlay(cardId, null, "none")
        val a = renderOverlay(
            cardId,
            OverlaySpec(path = wm.path, x = 0.62f, y = 0.74f, w = 0.30f, h = 0.18f, opacity = 0.60f),
            "a",
        )
        val b = renderOverlay(
            cardId,
            OverlaySpec(path = wm.path, x = 0.05f, y = 0.05f, w = 0.20f, h = 0.15f, opacity = 0.35f),
            "b",
        )
        return OverlayRow(base, a, b)
    }
    val wmIr = overlayRun("stock-oppo-hassel_style_1")
    val wmHand = overlayRun("simple-1")

    // ---- D: no-op -----------------------------------------------------------
    val noopCards = listOf(
        "stock-oppo-lumo_style_1" to "ir",
        "simple-1" to "hand",
        "card-1" to "hand",
    )
    val noop = noopCards.map { (cardId, _) ->
        val target = File(renderDir, "noop-$cardId.png")
        bridge.render(
            cardId = cardId,
            params = emptyMap(),
            text = emptyMap(),
            mode = "preview",
            maxEdge = 20_000,
            format = null,
            quality = null,
            longEdge = null,
            outputPath = target.path,
        )
        NoopRow(cardId, coverageSha[cardId], sha256(target), p1bSha[cardId])
    }

    // ---- E: structured failures --------------------------------------------
    val failuresE = mutableListOf<FailureRow>()
    fun failureCase(case: String, block: () -> String) {
        val expected = try {
            val output = block()
            failuresE += FailureRow(case, "NO_ERROR", "expected a failure, got $output", true)
            return
        } catch (t: Throwable) {
            t
        }
        val code = if (expected is EngineConfigException) expected.code else "UNSTRUCTURED"
        val outputCreated = File(failDir, "$case.png").isFile
        failuresE += FailureRow(case, code, expected.message ?: expected::class.simpleName ?: "?", outputCreated)
    }

    failureCase("unknown-card") {
        val target = File(failDir, "unknown-card.png")
        bridge.render(
            cardId = "not-a-real-card",
            params = emptyMap(),
            text = emptyMap(),
            mode = "preview",
            maxEdge = 480,
            format = null,
            quality = null,
            longEdge = null,
            outputPath = target.path,
        )
        target.path
    }
    failureCase("missing-photo") {
        val target = File(failDir, "missing-photo.png")
        bridge.render(
            cardId = "stock-oppo-hassel_style_1",
            params = emptyMap(),
            text = emptyMap(),
            mode = "preview",
            maxEdge = 480,
            format = null,
            quality = null,
            longEdge = null,
            outputPath = target.path,
            photoPath = File(out, "does-not-exist.jpg").path,
        )
        target.path
    }
    failureCase("missing-overlay") {
        val target = File(failDir, "missing-overlay.png")
        bridge.render(
            cardId = "stock-oppo-hassel_style_1",
            params = emptyMap(),
            text = emptyMap(),
            mode = "preview",
            maxEdge = 480,
            format = null,
            quality = null,
            longEdge = null,
            outputPath = target.path,
            overlay = OverlaySpec(
                path = File(out, "does-not-exist.png").path,
                x = 0.6f,
                y = 0.7f,
                w = 0.2f,
                h = 0.2f,
                opacity = 0.8f,
            ),
        )
        target.path
    }

    // ---- write evidence -----------------------------------------------------
    val bundlePhoto = File(catalog.dataRoot, "photo.png")
    val p1bPhoto = File(root, "_research/native/t1b/assets/img/photo-pil.png")
    val summary = buildString {
        append("{\n")
        append("  \"runtime\": {")
        append(jsonField("root", layout.root.path)).append(',')
        append(jsonField("dataRoot", layout.dataRoot.path)).append(',')
        append(jsonField("manifest", layout.manifest.path)).append(',')
        append(jsonField("e2b", layout.e2b?.path ?: "")).append(',')
        append(jsonField("e4hand", layout.e4hand?.path ?: "")).append(',')
        append(jsonField("cards175", layout.isCards175)).append("},\n")
        append("  \"catalog\": {")
        append(jsonField("cards", catalog.cards().size)).append(',')
        append(jsonField("ir", catalog.cards().count { it.backend == "ir" })).append(',')
        append(jsonField("hand", catalog.cards().count { it.backend == "hand" })).append(',')
        append(jsonField("supportsAll", catalog.cards().all { bridge.supports(it.cardId) })).append("},\n")
        append("  \"A\": {")
        append(jsonField("rendered", rows.size)).append(',')
        append(jsonField("renderFailures", failuresA.size)).append(',')
        append(jsonField("coverageShaMatched", matched)).append(',')
        append(jsonField("coverageDimsMatched", dimsMatched)).append(',')
        append(jsonField("sample20Matched", sampleMatched)).append(',')
        append(jsonField("sample20Total", sample.size)).append("},\n")
        append("  \"A_failures\": [")
        append(failuresA.joinToString(",") { q(it) }).append("],\n")
        append("  \"A_cards\": [\n")
        append(rows.joinToString(",\n") { row ->
            "    {" + jsonField("cardId", row.cardId) + "," + jsonField("backend", row.backend) + "," +
                jsonField("ms", row.ms) + "," + jsonField("w", row.width) + "," + jsonField("h", row.height) + "," +
                jsonField("sha256", row.sha) + "," + jsonField("coverageSha256", coverageSha[row.cardId] ?: "") + "," +
                jsonField("match", coverageSha[row.cardId] == row.sha) + "}"
        })
        append("\n  ],\n")
        fun photoJson(rows: List<PhotoRow>): String =
            rows.joinToString(",", "[", "]") { row ->
                "{" + jsonField("photo", row.photo) + "," + jsonField("sha256", row.sha) + "," +
                    jsonField("w", row.width) + "," + jsonField("h", row.height) + "}"
            }
        append("  \"B\": {\"ir\":").append(photoJson(photosIr))
        append(",\"hand\":").append(photoJson(photosHand))
        append(",\"irDistinct\":").append(photosDistinctIr)
        append(",\"handDistinct\":").append(photosDistinctHand).append("},\n")
        fun overlayJson(row: OverlayRow): String =
            "{" + jsonField("base", row.baseSha) + "," + jsonField("overlayA", row.overlayASha) + "," +
                jsonField("overlayB", row.overlayBSha) + "," + jsonField("overlayChanged", row.overlayChanged) + "," +
                jsonField("positionChanged", row.positionChanged) + "}"
        append("  \"C\": {\"ir\":").append(overlayJson(wmIr))
        append(",\"hand\":").append(overlayJson(wmHand)).append("},\n")
        append("  \"D\": [")
        append(noop.joinToString(",") { row ->
            "{" + jsonField("cardId", row.cardId) + "," + jsonField("coverageSha", row.coverageSha ?: "") + "," +
                jsonField("bridgeSha", row.bridgeSha) + "," + jsonField("matchCoverage", row.coverageSha == row.bridgeSha) + "," +
                jsonField("p1bSha", row.p1bSha ?: "") + "," + jsonField("matchP1b", row.p1bSha == row.bridgeSha) + "}"
        })
        append("],\n")
        append("  \"D_photoFixture\": {")
        append(jsonField("bundlePhotoSha", if (bundlePhoto.isFile) sha256(bundlePhoto) else "")).append(',')
        append(jsonField("p1bPhotoSha", if (p1bPhoto.isFile) sha256(p1bPhoto) else "")).append("},\n")
        append("  \"E\": [")
        append(failuresE.joinToString(",") { row ->
            "{" + jsonField("case", row.case) + "," + jsonField("code", row.code) + "," +
                jsonField("message", row.message.take(300)) + "," + jsonField("outputCreated", row.outputCreated) + "}"
        })
        append("]\n}\n")
    }
    File(out, "wire-summary.json").writeText(summary)
    println("P1C_RESULT " + summary.replace("\n", " ").take(1600))
    println("P1C_EVIDENCE ${File(out, "wire-summary.json").path}")

    val ok = failuresA.isEmpty() &&
        rows.isNotEmpty() &&
        matched == rows.size &&
        sampleMatched == sample.size &&
        photosDistinctIr && photosDistinctHand &&
        wmIr.overlayChanged && wmIr.positionChanged &&
        wmHand.overlayChanged && wmHand.positionChanged &&
        failuresE.all { it.code != "NO_ERROR" && it.code != "UNSTRUCTURED" && !it.outputCreated } &&
        noop.all { it.coverageSha == it.bridgeSha }
    if (!ok) {
        println("P1C_WIRE FAIL")
        exitProcess(1)
    }
    println("P1C_WIRE PASS")
}