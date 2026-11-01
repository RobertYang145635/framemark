package com.framemark.native.verify

import com.framemark.native.engine.DesktopNativeRender
import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.product.BatchJobStatus
import com.framemark.native.product.BatchQueue
import com.framemark.native.product.SessionStore
import com.framemark.native.product.StoreResult
import com.framemark.native.workflow.DesktopProjectController
import com.framemark.native.workflow.DesktopWorkflowPlatform
import com.framemark.native.workflow.ExportSettings
import com.framemark.native.workflow.ExportSizeMode
import com.framemark.native.workflow.JvmProductFileSystem
import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.WorkflowEnv
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * P2b headless journey: presets with embedded watermarks, the per-photo batch
 * queue with a simulated force-kill and resume, and original-size export.
 *
 *   java -cp <classpath> com.framemark.native.verify.P2bVerifyKt --out _research/native/p2b
 */
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

private fun dims(file: File): Pair<Int, Int> = runCatching {
    val image = ImageIO.read(file) ?: return@runCatching 0 to 0
    image.width to image.height
}.getOrDefault(0 to 0)

private fun jq(text: String): String = buildString {
    append('"')
    text.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
    }
    append('"')
}

private fun stem(name: String): String = name.substringBeforeLast('.')

private class P2bExportRow(val name: String, val sha: String, val w: Int, val h: Int)

private class P2bRenderLine(
    val card: String,
    val w: String,
    val h: String,
    val rawW: String,
    val rawH: String,
)

/** Captures the bridge's own `FM_ENGINE_*` lines so the request shape is evidence. */
private class P2bLogCapture {
    private val buffer = ByteArrayOutputStream()
    private val original: PrintStream = System.out
    private val stream = PrintStream(buffer, true)

    fun <T> around(block: () -> T): T {
        System.setOut(stream)
        return try {
            block()
        } finally {
            stream.flush()
            System.setOut(original)
        }
    }

    fun text(): String = buffer.toString(Charsets.UTF_8)
}

private fun themeModel(cardId: String): InspectorCardModel {
    val control = InspectorControl(
        key = "$cardId::theme",
        controlId = "theme.theme",
        param = "theme",
        label = "主题",
        kind = "themeSwatches",
        section = InspectorSection.Theme,
        type = ControlType.Select,
        initial = ControlValue.Str("white"),
        min = null,
        max = null,
        step = null,
        options = emptyList(),
        multiline = false,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )
    return InspectorCardModel(
        cardId = cardId,
        cardName = cardId,
        audited = true,
        effectiveCount = 1,
        gatedCount = 0,
        declaredCount = 1,
        effectiveIds = listOf(control.controlId),
        gatedIds = emptyList(),
        visibleIds = listOf(control.controlId),
        hiddenIds = emptyList(),
        controls = listOf(control),
        sections = emptyList(),
    )
}

fun main(args: Array<String>) {
    val root = File("").absoluteFile
    val out = File(args.getOrNull(args.indexOf("--out") + 1) ?: "_research/native/p2b")
        .let { if (it.isAbsolute) it else File(root, it.path) }
    out.mkdirs()
    val data = File(out, "tmp/data").apply { deleteRecursively(); mkdirs() }
    val exports = File(out, "exports").apply { deleteRecursively(); mkdirs() }
    val resumeExports = File(out, "exports-resume").apply { deleteRecursively(); mkdirs() }
    val scaledExports = File(out, "exports-scaled").apply { deleteRecursively(); mkdirs() }
    val secondProject = File(out, "tmp/data-import").apply { deleteRecursively(); mkdirs() }

    fun installPlatform(exportDir: File) {
        WorkflowEnv.platform = DesktopWorkflowPlatform(dataDir = data.path, exportDir = exportDir.path)
    }

    installPlatform(exports)
    WorkflowEnv.nativeRender = runCatching { DesktopNativeRender() }
        .onFailure { println("P2B_ENGINE_UNAVAILABLE ${it.message}") }
        .getOrNull()
    if (WorkflowEnv.nativeRender == null) {
        println("P2B FAIL: no desktop engine")
        exitProcess(1)
    }

    fun asset(name: String) = File(root, "_research/test-assets/$name")
    val photos = listOf(asset("ratio-study/4x3.jpg"), asset("vertical.jpg"), asset("p1170883.jpg"))
    require(photos.all { it.isFile }) { "photo fixtures missing" }
    val watermark = asset("root-import/水印_白色.png")
    require(watermark.isFile) { "watermark fixture missing" }

    val log = P2bLogCapture()
    val checks = linkedMapOf<String, Boolean>()
    val notes = linkedMapOf<String, String>()

    fun controllerAt(dir: File) = DesktopProjectController(dir.path).also { it.load() }

    val controller = controllerAt(data)
    val imported = kotlinx.coroutines.runBlocking { controller.importPhotos(photos.map { it.path }) }
    check(imported.added == 3) { "expected 3 photos, got ${imported.added}" }
    val photoIds = controller.photos.map { it.id }
    val photoNames = controller.photos.map { it.displayName }
    val photoDims = controller.photos.map { "${it.widthPx}x${it.heightPx}" }

    // ---- three deliberately different designs -----------------------------
    // A: watermark only. B: theme only. C: untouched default design.
    val cards = listOf("simple-1" to "极简 01", "stock-xiaomi-classic-17" to "小米经典 17", "wm-classic" to "水印经典")
    controller.selectPhoto(photoIds[0])
    controller.applyCard(cards[0].first, cards[0].first)
    check(kotlinx.coroutines.runBlocking { controller.importWatermark(watermark.path) }.isSuccess)
    val watermarkOnly = controller.activeDesign!!

    controller.selectPhoto(photoIds[1])
    controller.applyCard(cards[1].first, cards[1].first)
    controller.applyControlOverrides(themeModel(cards[1].first), mapOf("${cards[1].first}::theme" to "black"))
    val themeOnly = controller.activeDesign!!

    controller.selectPhoto(photoIds[2])
    controller.applyCard(cards[2].first, cards[2].first)
    val untouched = controller.activeDesign!!

    checks["designsDiffer"] = watermarkOnly.overlay != null && themeOnly.overlay == null &&
        themeOnly.theme == "black" && untouched.overlay == null && untouched.theme == null

    controller.setExportSettings(
        ExportSettings(
            format = SampleFormat.Jpeg,
            sizeMode = ExportSizeMode.Original,
            filenamePattern = "{photo}-{index}",
        ),
    )

    // ---- batch: enqueue, duplicate guard, run ------------------------------
    checks["enqueueThree"] = controller.enqueueBatch(photoIds) == 3
    checks["requeueIsDuplicate"] = controller.enqueueBatch(photoIds) == 0 && controller.batchJobs.size == 3
    checks["threeDistinctKeys"] = controller.batchJobs.map { it.idempotencyKey }.distinct().size == 3
    checks["themeAndWatermarkBothQueued"] = controller.batchJobs.count { it.overlay != null } == 1 &&
        controller.batchJobs.count { it.design.theme == "black" } == 1 &&
        controller.batchJobs.count { it.design.theme == null && it.overlay == null } == 1

    log.around { kotlinx.coroutines.runBlocking { controller.runBatch() } }
    val execLog = log.text()
    checks["batchAllDone"] = controller.batchJobs.size == 3 && controller.batchDone == 3 && controller.batchFailed == 0
    checks["themeReachedEngineArgv"] = Regex("""--theme\s+(\S+)""").containsMatchIn(execLog)
    notes["themeArgv"] = Regex("""--theme\s+(\S+)""").find(execLog)?.groupValues?.getOrNull(1) ?: "none"

    val rows = exports.listFiles()?.filter { it.isFile }.orEmpty().map { file ->
        val (w, h) = dims(file)
        P2bExportRow(file.name, sha256(file), w, h)
    }.sortedBy { it.name }
    checks["threeOutputsNamedByPhoto"] = rows.size == 3 &&
        photoNames.all { name -> rows.any { it.name.startsWith(stem(name)) } }
    checks["outputsDistinct"] = rows.map { it.sha }.distinct().size == 3
    checks["filenamesCarryIndex"] = (1..3).all { index -> rows.any { it.name.contains("-$index.") } }

    // ---- original size means the engine canvas is not rescaled -------------
    val renderLines = Regex("""FM_ENGINE_RENDER card=(\S+) mode=export (\d+)x(\d+) raw=(\d+)x(\d+)""")
        .findAll(execLog)
        .map { match -> P2bRenderLine(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4], match.groupValues[5]) }
        .toList()
    checks["exportAtNativeCanvas"] = renderLines.size >= 3 &&
        renderLines.all { line -> line.w == line.rawW && line.h == line.rawH }
    notes["exportCanvasLines"] = renderLines.joinToString("; ") {
        "${it.card} ${it.w}x${it.h} raw ${it.rawW}x${it.rawH}"
    }
    notes["photoDims"] = photoNames.zip(photoDims).joinToString("; ") { (n, d) -> "$n $d" }
    notes["outputs"] = rows.joinToString("; ") { "${it.name} ${it.w}x${it.h}" }

    // A custom long edge must actually scale, otherwise the mode is not wired.
    installPlatform(scaledExports)
    controller.setExportSettings(
        controller.exportSettingsForUi().copy(sizeMode = ExportSizeMode.LongEdge, longEdge = 1200),
    )
    val scaled = kotlinx.coroutines.runBlocking {
        controller.exportActive(cards[0].second, controller.exportSettingsForUi())
    }
    val (sw, sh) = dims(File(scaled.path))
    val nativeOfSamePhoto = rows.firstOrNull { it.name.startsWith(stem(photoNames[0])) }
    checks["longEdgeActuallyScales"] = nativeOfSamePhoto != null &&
        maxOf(sw, sh) < maxOf(nativeOfSamePhoto.w, nativeOfSamePhoto.h)
    notes["longEdgeSample"] = "longEdge 1200 -> ${sw}x$sh; native -> " +
        "${nativeOfSamePhoto?.w}x${nativeOfSamePhoto?.h}"
    controller.setExportSettings(
        controller.exportSettingsForUi().copy(sizeMode = ExportSizeMode.Original),
    )
    installPlatform(exports)

    // A theme must move pixels, not just the queue key: same IR card, same photo,
    // only `--theme` differs.
    val themeDir = File(out, "exports-theme").apply { deleteRecursively(); mkdirs() }
    installPlatform(themeDir)
    controller.selectPhoto(photoIds[1])
    controller.applyControlOverrides(themeModel(cards[1].first), mapOf("${cards[1].first}::theme" to "black"))
    val themeBlack = kotlinx.coroutines.runBlocking {
        controller.exportActive(cards[1].second, controller.exportSettingsForUi())
    }
    controller.applyControlOverrides(themeModel(cards[1].first), mapOf("${cards[1].first}::theme" to "white"))
    val themeWhite = kotlinx.coroutines.runBlocking {
        controller.exportActive(cards[1].second, controller.exportSettingsForUi())
    }
    checks["themeChangesPixels"] = sha256(File(themeBlack.path)) != sha256(File(themeWhite.path))
    notes["themePair"] = "${File(themeBlack.path).name} ${sha256(File(themeBlack.path)).take(12)} vs " +
        "${File(themeWhite.path).name} ${sha256(File(themeWhite.path)).take(12)}"
    controller.applyControlOverrides(themeModel(cards[1].first), mapOf("${cards[1].first}::theme" to "black"))
    installPlatform(exports)

    // ---- force-kill + resume ----------------------------------------------
    // Row 0 finished, row 1 was mid-export when the process died, row 2 never
    // started. The resumed run must export exactly rows 1 and 2 — no loss, no
    // repeat of row 0, and never the wrong photo.
    val persisted = (SessionStore(JvmProductFileSystem(data.path)).loadBatch() as StoreResult.Ok).value
    check(persisted.jobs.size == 3)
    SessionStore(JvmProductFileSystem(data.path)).saveBatch(
        BatchQueue(
            persisted.jobs.mapIndexed { index, job ->
                when (index) {
                    0 -> job
                    1 -> job.copy(status = BatchJobStatus.Running, outputPath = null)
                    else -> job.copy(status = BatchJobStatus.Queued, outputPath = null)
                }
            },
        ),
    )
    val row0Output = persisted.jobs[0].outputPath.orEmpty()
    val row0ShaBefore = File(row0Output).takeIf { it.isFile }?.let { sha256(it) }
    controller.dispose()

    installPlatform(resumeExports)
    val resumedController = controllerAt(data)
    resumedController.loadBatch()
    checks["interruptedRowBackToQueue"] = resumedController.batchJobs[1].status == BatchJobStatus.Queued &&
        resumedController.batchJobs[0].status == BatchJobStatus.Done &&
        resumedController.batchJobs[2].status == BatchJobStatus.Queued
    kotlinx.coroutines.runBlocking { resumedController.runBatch() }
    checks["resumedWithoutLoss"] = resumedController.batchDone == 3 && resumedController.batchFailed == 0

    val resumeFiles = resumeExports.listFiles()?.filter { it.isFile }.orEmpty()
    val pendingPhotos = listOf(photoNames[1], photoNames[2])
    checks["resumeExportedExactlyThePendingRows"] = resumeFiles.size == 2 &&
        pendingPhotos.all { name -> resumeFiles.any { it.name.startsWith(stem(name)) } } &&
        resumeFiles.none { it.name.startsWith(stem(photoNames[0])) }
    checks["finishedRowNotReExported"] = row0ShaBefore != null &&
        File(row0Output).isFile && sha256(File(row0Output)) == row0ShaBefore
    checks["resumedPhotoDesignPaired"] = resumedController.batchJobs
        .map { "${it.displayName}|${it.design.cardId}" }.sorted() ==
        photoNames.mapIndexed { index, name -> "$name|${cards[index].first}" }.sorted()
    notes["resumedQueue"] = resumedController.batchJobs.joinToString("; ") {
        "${it.displayName}/${it.design.cardId}=${it.status}"
    }

    // ---- preset: save with watermark, restart, reapply ---------------------
    resumedController.selectPhoto(photoIds[0])
    val preset = resumedController.savePreset("P2b 签名预设")
    checks["presetSavedWithWatermark"] = preset != null && (preset!!.embeddedWatermarkBytes > 0)
    val presetSha = preset?.design?.overlay?.asset?.sha256
    resumedController.dispose()

    val reopened = controllerAt(data)
    reopened.loadPresets()
    checks["presetSurvivesRestart"] = reopened.presets.size == 1 &&
        reopened.presets.first().design.overlay?.asset?.sha256 == presetSha
    reopened.selectPhoto(photoIds[2])
    checks["applyPresetOnOtherPhoto"] = reopened.applyPreset(reopened.presets.first().id) &&
        reopened.activeDesign?.overlay?.asset?.sha256 == presetSha &&
        reopened.activeDesign?.cardId == cards[0].first
    val carried = reopened.watermarkBytes()
    checks["watermarkBytesIntact"] = carried != null && carried.size == preset?.embeddedWatermarkBytes

    // ---- preset single-file round trip ------------------------------------
    val presetFile = File(out, "tmp/carry.json")
    checks["presetFileExported"] = reopened.exportPresetFile(reopened.presets.first().id, presetFile.path) &&
        presetFile.isFile
    checks["presetFileCarriesNoAbsolutePath"] =
        !presetFile.readText().contains(Regex("[A-Za-z]:\\\\|_research/native"))
    reopened.dispose()

    val target = controllerAt(secondProject)
    kotlinx.coroutines.runBlocking { target.importPhotos(listOf(photos[1].path)) }
    target.applyCard(cards[2].first, cards[2].first)
    val importedPreset = target.importPresetFile(presetFile.path)
    checks["presetFileImported"] = importedPreset != null &&
        importedPreset!!.design.overlay?.asset?.sha256 == presetSha
    checks["importedPresetApplies"] = importedPreset != null && target.applyPreset(importedPreset.id) &&
        target.activeDesign?.overlay?.asset?.sha256 == presetSha
    target.dispose()

    val ok = checks.values.all { it }
    val json = buildString {
        append("{\n")
        append("  \"photos\": [").append(photos.map { jq(it.name) }.joinToString(",")).append("],\n")
        append("  \"photoDims\": [")
        append(photoNames.mapIndexed { i, n -> "{" + jq("photo") + ":" + jq(n) + "," + jq("dims") + ":" + jq(photoDims[i]) + "}" }.joinToString(","))
        append("],\n")
        append("  \"exports\": [")
        append(rows.map { row ->
            "{" + jq("file") + ":" + jq(row.name) + "," + jq("sha256") + ":" + jq(row.sha) +
                "," + jq("w") + ":" + row.w + "," + jq("h") + ":" + row.h + "}"
        }.joinToString(","))
        append("],\n")
        append("  \"checks\": {").append(checks.entries.joinToString(",") { jq(it.key) + ":" + it.value }).append("},\n")
        append("  \"notes\": {").append(notes.entries.joinToString(",") { jq(it.key) + ":" + jq(it.value) }).append("}\n}\n")
    }
    File(out, "p2b.json").writeText(json)
    File(out, "journey.md").writeText(
        buildString {
            append("| 照片 | 照片像素 | 输出文件 | 输出像素 |\n|---|---|---|---|\n")
            photoNames.forEachIndexed { i, name ->
                val row = rows.firstOrNull { it.name.startsWith(stem(name)) }
                append("| ").append(name).append(" | ").append(photoDims[i]).append(" | `")
                    .append(row?.name ?: "-").append("` | ")
                    .append(row?.let { "${it.w}×${it.h}" } ?: "-").append(" |\n")
            }
            append("\n")
            checks.forEach { (k, v) -> append("- ").append(k).append(" = ").append(v).append('\n') }
            notes.forEach { (k, v) -> append("  - ").append(k).append(": ").append(v).append('\n') }
        },
    )
    checks.forEach { (k, v) -> println("P2B_CHECK $k=$v") }
    println("P2B_RESULT ${json.replace("\n", " ")}")
    println(if (ok) "P2B PASS" else "P2B FAIL")
    if (!ok) exitProcess(1)
}
