package com.framemark.native.verify

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.product.BatchJobStatus
import com.framemark.native.workflow.JvmProductFileSystem
import com.framemark.native.product.LegacyDocuments
import com.framemark.native.product.SessionStore
import com.framemark.native.product.StoreResult
import com.framemark.native.shell.PhoneProjectStore
import com.framemark.native.shell.legacyToPreset
import com.framemark.native.workflow.DesktopProjectController
import com.framemark.native.workflow.DesktopWorkflowPlatform
import com.framemark.native.workflow.PresetStore
import com.framemark.native.workflow.WorkflowEnv
import java.io.File
import kotlin.system.exitProcess

/**
 * P2c headless evidence: the legacy desktop documents upgrade into the same product
 * documents the phone writes, the upgrade survives a re-read byte-for-byte, and one
 * document parses on both shells.
 *
 *   java -cp <classpath> com.framemark.native.verify.P2cVerifyKt --out _research/native/p2c
 */
private fun cJson(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "") + "\""

private fun footerModel(cardId: String): InspectorCardModel {
    val control = InspectorControl(
        key = "$cardId::footer",
        controlId = "footer",
        param = "params.footerPct",
        label = "footer",
        kind = "slider",
        section = InspectorSection.Layout,
        type = ControlType.Slider,
        initial = ControlValue.Num(10f),
        min = 0f, max = 100f, step = 1f,
        options = emptyList(),
        multiline = false,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )
    return InspectorCardModel(
        cardId = cardId, cardName = cardId, audited = true,
        effectiveCount = 1, gatedCount = 0, declaredCount = 1,
        effectiveIds = listOf("footer"), gatedIds = emptyList(),
        visibleIds = listOf("footer"), hiddenIds = emptyList(),
        controls = listOf(control), sections = emptyList(),
    )
}

fun main(args: Array<String>) {
    val root = File("").absoluteFile
    val outIndex = args.indexOf("--out")
    val out = File(if (outIndex >= 0 && outIndex + 1 < args.size) args[outIndex + 1] else "_research/native/p2c")
        .let { if (it.isAbsolute) it else File(root, it.path) }
    val fixtures = File(out, "fixtures")
    out.mkdirs()

    val oldPlatform = WorkflowEnv.platform
    val checks = linkedMapOf<String, Boolean>()
    val notes = linkedMapOf<String, String>()
    try {
        val data = File(out, "tmp/data").apply { deleteRecursively(); mkdirs() }
        WorkflowEnv.platform = DesktopWorkflowPlatform(dataDir = data.path, exportDir = data.path)

        // ---- legacy presets -------------------------------------------------
        val legacyPresets = File(fixtures, "legacy-presets.json").readText()
        File(data, "presets.json").writeText(legacyPresets)
        PresetStore.resetForVerify()
        File(data, "presets.json").writeText(legacyPresets)
        PresetStore.resetLoadedForVerify()
        PresetStore.load()
        checks["legacyPresetsReadable"] = PresetStore.items.size == 2
        notes["legacyPresetIds"] = PresetStore.items.joinToString(",") { it.id }

        val desktop = DesktopProjectController(data.path).also { it.load() }
        desktop.loadPresets()
        checks["productReaderIgnoresLegacy"] = desktop.presets.isEmpty()
        // Emptiness alone cannot tell "refused" from "silently parsed to nothing", so
        // the store must report a structured failure for a foreign document.
        val foreign = SessionStore(JvmProductFileSystem(data.path)).loadPresets()
        checks["productReaderReportsLegacy"] = foreign is StoreResult.Failed &&
            foreign.error.code == "FM-E-PRESET-001" &&
            foreign.error.message.contains("framemark.presets")
        notes["legacyPresetRejection"] = (foreign as? StoreResult.Failed)?.error?.message ?: foreign.toString()
        val converted = PresetStore.items.map { legacyToPreset(it, it.cardId, it.cardId, footerModel(it.cardId)) }
        val migrated = desktop.importLegacyPresets(converted)
        checks["presetsMigrated"] = migrated == 2 && desktop.presets.size == 2
        val presetDoc = File(data, "presets.json").readText()
        checks["presetDocIsProduct"] = LegacyDocuments.isProductPresetLibrary(presetDoc)
        checks["presetValuesFolded"] = desktop.presets.map {
            (it.design.params["footerPct"] as? Number)?.toDouble() ?: -1.0
        }.sorted() == listOf(44.0, 61.0)
        checks["presetLegacyKept"] = presetDoc != legacyPresets

        val reread = DesktopProjectController(data.path).also { it.load() }
        reread.loadPresets()
        checks["presetRoundTripIdentical"] = reread.presets == desktop.presets

        // ---- legacy batch ---------------------------------------------------
        val legacyBatch = File(fixtures, "legacy-batch-queue.json").readText()
        File(data, "batch-queue.json").writeText(legacyBatch)
        val batchReader = DesktopProjectController(data.path).also { it.load() }
        batchReader.loadBatch()
        checks["legacyBatchDetected"] = batchReader.legacyBatchPending && batchReader.batchJobs.size == 3
        checks["batchMigrated"] = batchReader.migrateLegacyBatch() == 3
        val batchDoc = File(data, "batch-queue.json").readText()
        checks["batchDocIsProduct"] = LegacyDocuments.isProductBatch(batchDoc)

        val batchAgain = DesktopProjectController(data.path).also { it.load() }
        batchAgain.loadBatch()
        checks["batchRoundTripIdentical"] = batchAgain.batchJobs.size == 3 &&
            batchAgain.batchJobs.map {
            "${it.id}|${it.displayName}|${it.design.cardId}|${it.photoFingerprint}|${it.exportSettings}"
        } == batchReader.batchJobs.map {
            "${it.id}|${it.displayName}|${it.design.cardId}|${it.photoFingerprint}|${it.exportSettings}"
        }
        checks["batchStatusMapHonest"] = listOf(
            "4x3.jpg" to BatchJobStatus.Done,
            "vertical.jpg" to BatchJobStatus.Queued,
            "p1170883.jpg" to BatchJobStatus.Cancelled,
        ).all { (name, expected) -> batchAgain.batchJobs.firstOrNull { it.displayName == name }?.status == expected }
        checks["batchNothingInvented"] = batchAgain.batchJobs.isNotEmpty() && batchAgain.batchJobs.all {
            it.overlay == null && it.design.params.isEmpty() && it.design.theme == null
        }

        // ---- one document, both shells --------------------------------------
        val phoneDir = File(out, "tmp/phone").apply { deleteRecursively(); mkdirs() }
        File(phoneDir, "presets.json").writeText(presetDoc)
        val phone = PhoneProjectStore(JvmProductFileSystem(phoneDir.path), null, null)
        phone.loadPresets()
        checks["phoneReadsDesktopDocument"] = phone.presets.map { it.id } == desktop.presets.map { it.id } &&
            phone.presets.map { it.name } == desktop.presets.map { it.name } &&
            phone.presets.map { it.design.params } == desktop.presets.map { it.design.params }

        // A phone-shaped document (same codec, fresh store) reads on the desktop too.
        val viaPhone = SessionStore(JvmProductFileSystem(phoneDir.path))
        val mirrored = (viaPhone.loadPresets() as StoreResult.Ok).value
        val crossDir = File(out, "tmp/cross").apply { deleteRecursively(); mkdirs() }
        File(crossDir, "presets.json").writeText(presetDoc)
        val crossReader = DesktopProjectController(crossDir.path).also { it.load() }
        crossReader.loadPresets()
        checks["desktopReadsSameBytes"] = crossReader.presets == mirrored

        File(out, "migrated-presets.json").writeText(presetDoc)
        File(out, "migrated-batch-queue.json").writeText(batchDoc)
        notes["presetDocHead"] = presetDoc.take(90)
        notes["batchDocHead"] = batchDoc.take(90)

        val ok = checks.values.all { it }
        File(out, "p2c.json").writeText(
            buildString {
                append("{\n  \"checks\": {")
                append(checks.entries.joinToString(",\n    ") { cJson(it.key) + ": " + it.value })
                append("},\n  \"notes\": {")
                append(notes.entries.joinToString(",\n    ") { cJson(it.key) + ": " + cJson(it.value) })
                append("}\n}\n")
            },
        )
        checks.forEach { (k, v) -> println("P2C_CHECK $k=$v") }
        println(if (ok) "P2C PASS" else "P2C FAIL")
        if (!ok) exitProcess(1)
    } finally {
        WorkflowEnv.platform = oldPlatform
    }
}
