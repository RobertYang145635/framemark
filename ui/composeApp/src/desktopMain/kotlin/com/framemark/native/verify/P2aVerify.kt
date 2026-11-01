package com.framemark.native.verify

import com.framemark.native.engine.DesktopNativeRender
import com.framemark.native.product.ProductFileSystem
import com.framemark.native.product.SessionStore
import com.framemark.native.workflow.DesktopProjectController
import com.framemark.native.workflow.DesktopWorkflowPlatform
import com.framemark.native.workflow.ExportSettings
import com.framemark.native.workflow.ExportSizeMode
import com.framemark.native.workflow.JvmProductFileSystem
import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.WorkflowEnv
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * P2a headless journey: import three photos, give each its own card + watermark,
 * export all three, restart from disk, export again and compare.
 *
 *   FM_RUNTIME_ROOT=… java -cp <classpath> com.framemark.native.verify.P2aVerifyKt \
 *        --out _research/native/p2a
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

private fun dims(file: File): Pair<Int, Int> = runCatching {
    val image = ImageIO.read(file) ?: return@runCatching 0 to 0
    image.width to image.height
}.getOrDefault(0 to 0)

private data class ExportRow(val photo: String, val card: String, val path: String, val sha: String, val w: Int, val h: Int)

private class FailingTempFs(private val delegate: ProductFileSystem) : ProductFileSystem by delegate {
    override fun writeBytes(path: String, bytes: ByteArray): Boolean =
        if (path.endsWith(".tmp")) false else delegate.writeBytes(path, bytes)
}

fun main(args: Array<String>) {
    val root = File("").absoluteFile
    val out = File(arg(args, "out") ?: "_research/native/p2a").let { if (it.isAbsolute) it else File(root, it.path) }
    out.mkdirs()
    val data = File(out, "tmp/data").apply { deleteRecursively(); mkdirs() }
    val exports = File(out, "exports").apply { mkdirs() }

    WorkflowEnv.platform = DesktopWorkflowPlatform(dataDir = data.path, exportDir = exports.path)
    WorkflowEnv.nativeRender = runCatching { DesktopNativeRender() }
        .onFailure { println("P2A_ENGINE_UNAVAILABLE ${it.message}") }
        .getOrNull()
    if (WorkflowEnv.nativeRender == null) {
        println("P2A FAIL: no desktop engine")
        exitProcess(1)
    }
    println("P2A_RUNTIME data=${data.path} exports=${exports.path}")

    fun photo(name: String) = File(root, "_research/test-assets/$name")
    val photos = listOf(
        photo("ratio-study/4x3.jpg"),
        photo("vertical.jpg"),
        photo("p1170883.jpg"),
    )
    require(photos.all { it.isFile }) { "photo fixtures missing" }
    val watermark = photo("root-import/水印_白色.png")
    require(watermark.isFile) { "watermark fixture missing" }

    val controller = DesktopProjectController(data.path)
    controller.load()
    val imported = kotlinx.coroutines.runBlocking { controller.importPhotos(photos.map { it.path }) }
    println("P2A_IMPORT added=${imported.added} failed=${imported.failures.size}")
    check(imported.added == 3) { "expected 3 imported photos, got ${imported.added}" }

    val settings = ExportSettings(
        format = SampleFormat.Jpeg,
        quality = 100,
        sizeMode = ExportSizeMode.LongEdge,
        longEdge = 1200,
        filenamePattern = "{photo}-{name}-{index}",
    )
    val cards = listOf("simple-1" to "极简 01", "card-1" to "卡片 01", "wm-classic" to "水印经典")
    val anchors = listOf(2, 8, 0)
    val opacities = listOf(0.55f, 0.80f, 0.90f)
    val widths = listOf(0.22f, 0.30f, 0.36f)

    val watermarked = mutableListOf<ExportRow>()
    val plain = mutableListOf<ExportRow>()
    photos.forEachIndexed { index, _ ->
        val photoId = controller.photos[index].id
        controller.selectPhoto(photoId)
        controller.applyCard(cards[index].first, cards[index].first)
        val result = kotlinx.coroutines.runBlocking { controller.importWatermark(watermark.path) }
        check(result.isSuccess) { "watermark import failed: ${result.exceptionOrNull()?.message}" }
        controller.updateWatermark(anchor = anchors[index], opacity = opacities[index], width = widths[index])
        val row = kotlinx.coroutines.runBlocking { controller.exportActive(cards[index].second, settings) }
        val (w, h) = dims(File(row.path))
        watermarked += ExportRow(controller.photos[index].displayName, cards[index].first, row.path, sha256(File(row.path)), w, h)

        controller.clearWatermark()
        val plainRow = kotlinx.coroutines.runBlocking { controller.exportActive(cards[index].second, settings) }
        val (pw, ph) = dims(File(plainRow.path))
        plain += ExportRow(controller.photos[index].displayName, cards[index].first, plainRow.path, sha256(File(plainRow.path)), pw, ph)

        // Restore this photo's watermark for the restart check.
        kotlinx.coroutines.runBlocking { controller.importWatermark(watermark.path) }
        controller.updateWatermark(anchor = anchors[index], opacity = opacities[index], width = widths[index])
        controller.saveNow()
    }

    // Same photo, different anchor ⇒ different pixels.
    controller.selectPhoto(controller.photos[0].id)
    controller.updateWatermark(anchor = 6)
    val moved = kotlinx.coroutines.runBlocking { controller.exportActive(cards[0].second, settings) }
    val anchorMovedDiffers = sha256(File(moved.path)) != watermarked[0].sha
    controller.updateWatermark(anchor = anchors[0], opacity = opacities[0], width = widths[0])
    controller.saveNow()

    // Restart from disk.
    val activeBefore = controller.activePhotoId
    val projectBefore = controller.project
    controller.dispose()
    val restarted = DesktopProjectController(data.path)
    restarted.load()
    val restored = restarted.project
    val restartPhotos = restored.photos == projectBefore.photos
    val restartActive = restored.activePhotoId == activeBefore
    val restartCards = photos.indices.all { i ->
        restored.designs[projectBefore.photos[i].id]?.cardId == projectBefore.designs[projectBefore.photos[i].id]?.cardId
    }
    val perPhotoWatermarks = photos.indices.all { i ->
        val ov = projectBefore.designs[projectBefore.photos[i].id]?.overlay
        ov != null && ov.anchor == anchors[i] && ov.width == widths[i] && ov.opacity == opacities[i]
    }
    val restartWatermarks = photos.indices.all { i ->
        val a = restored.designs[projectBefore.photos[i].id]?.overlay
        val b = projectBefore.designs[projectBefore.photos[i].id]?.overlay
        a != null && b != null && a.asset.sha256 == b.asset.sha256 && a.anchor == b.anchor &&
            a.width == b.width && a.opacity == b.opacity
    }
    restarted.selectPhoto(restored.photos[0].id)
    val reExport = kotlinx.coroutines.runBlocking { restarted.exportActive(cards[0].second, settings) }
    val restartExportSame = sha256(File(reExport.path)) == watermarked[0].sha

    // Bad image: fails alone, project untouched.
    val bad = File(out, "tmp/broken.jpg").apply { writeText("not an image") }
    val badReport = kotlinx.coroutines.runBlocking { restarted.importPhotos(listOf(bad.path)) }
    val badIsolated = badReport.added == 0 && badReport.failures.size == 1 && restarted.photos.size == 3

    // Atomic write failure is surfaced, not swallowed.
    val failDir = File(out, "tmp/fail-data").apply { mkdirs() }
    val failing = DesktopProjectController(
        dataDir = failDir.path,
        store = SessionStore(FailingTempFs(JvmProductFileSystem(failDir.path))),
    )
    failing.load()
    failing.setExportSettings(ExportSettings(format = SampleFormat.Png))
    val writeOk = failing.saveNow()
    val writeFailureVisible = !writeOk && failing.saveError?.contains("FM-E-IO-003") == true
    failing.dispose()

    val distinct = watermarked.map { it.sha }.toSet().size == 3
    val overlayChanged = photos.indices.all { watermarked[it].sha != plain[it].sha }

    val ok = distinct && overlayChanged && anchorMovedDiffers && perPhotoWatermarks && restartPhotos && restartActive &&
        restartCards && restartWatermarks && restartExportSame && badIsolated && writeFailureVisible

    val json = buildString {
        append("{\n  \"photos\": [")
        append(photos.joinToString(",") { q(it.name) }).append("],\n")
        append("  \"exportsWatermarked\": [")
        append(watermarked.joinToString(",") { row ->
            "{" + q("photo") + ":" + q(row.photo) + "," + q("card") + ":" + q(row.card) + "," +
                q("sha256") + ":" + q(row.sha) + "," + q("w") + ":" + row.w + "," + q("h") + ":" + row.h + "}"
        }).append("],\n")
        append("  \"exportsPlain\": [")
        append(plain.joinToString(",") { row ->
            "{" + q("photo") + ":" + q(row.photo) + "," + q("sha256") + ":" + q(row.sha) + "," +
                q("w") + ":" + row.w + "," + q("h") + ":" + row.h + "}"
        }).append("],\n")
        append("  \"checks\": {")
        append(q("distinct")).append(":").append(distinct).append(',')
        append(q("overlayChanged")).append(":").append(overlayChanged).append(',')
        append(q("anchorMovedDiffers")).append(":").append(anchorMovedDiffers).append(',')
        append(q("perPhotoWatermarks")).append(":").append(perPhotoWatermarks).append(',')
        append(q("restartPhotos")).append(":").append(restartPhotos).append(',')
        append(q("restartActive")).append(":").append(restartActive).append(',')
        append(q("restartCards")).append(":").append(restartCards).append(',')
        append(q("restartWatermarks")).append(":").append(restartWatermarks).append(',')
        append(q("restartExportSame")).append(":").append(restartExportSame).append(',')
        append(q("badImageIsolated")).append(":").append(badIsolated).append(',')
        append(q("writeFailureVisible")).append(":").append(writeFailureVisible)
        append("},\n")
        append("  \"saveError\": ").append(q(failing.saveError ?: "")).append(",\n")
        append("  \"activePhotoId\": ").append(q(activeBefore ?: "")).append("\n}\n")
    }
    File(out, "p2a.json").writeText(json)
    File(out, "journey.md").writeText(
        buildString {
            append("| 照片 | 卡 | 带水印 SHA-256 | 无水印 SHA-256 | 尺寸 |\n|---|---|---|---|---|\n")
            photos.indices.forEach { i ->
                append("| ").append(watermarked[i].photo).append(" | ").append(watermarked[i].card).append(" | `")
                    .append(watermarked[i].sha.take(20)).append("…` | `").append(plain[i].sha.take(20))
                    .append("…` | ").append(watermarked[i].w).append("×").append(watermarked[i].h).append(" |\n")
            }
            append("\nchecks: distinct=").append(distinct).append(", overlayChanged=").append(overlayChanged)
                .append(", anchorMovedDiffers=").append(anchorMovedDiffers).append(", perPhotoWatermarks=").append(perPhotoWatermarks).append(", restartPhotos=").append(restartPhotos)
                .append(", restartActive=").append(restartActive).append(", restartCards=").append(restartCards)
                .append(", restartWatermarks=").append(restartWatermarks).append(", restartExportSame=").append(restartExportSame)
                .append(", badImageIsolated=").append(badIsolated).append(", writeFailureVisible=").append(writeFailureVisible)
                .append("\n")
        },
    )
    println("P2A_RESULT " + File(out, "p2a.json").readText().replace("\n", " ").take(1400))
    println(if (ok) "P2A PASS" else "P2A FAIL")
    if (!ok) exitProcess(1)
}
