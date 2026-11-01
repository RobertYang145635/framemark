package com.framemark.native.verify

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.InspectorDataSource
import com.framemark.native.inspector.InspectorModelFactory
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.library.LibraryDataSource
import com.framemark.native.workflow.BatchItemStatus
import com.framemark.native.workflow.BatchRunner
import com.framemark.native.workflow.BatchRunState
import com.framemark.native.workflow.DesignHistory
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.DesktopWorkflowPlatform
import com.framemark.native.workflow.ExportSettings
import com.framemark.native.workflow.FakeEngine
import com.framemark.native.workflow.PresetStore
import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.WorkflowEnv
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * U4 workflow contract entry point (desktop, no UI):
 *
 *  - 25 control edits are recorded as 25 history steps (limit 50 ≥ 20);
 *    undo-all / redo-all round-trips the exact values.
 *  - a preset is saved, decoded back from the JSON document, renamed and deleted.
 *  - FakeEngine writes JPG + PNG placeholders and reports their real dimensions.
 *  - the batch queue runs, pauses, resumes, cancels and removes items.
 *
 * Run with the desktop classpath from `dumpDesktopClasspath`:
 *
 *   java -cp "$(cat composeApp/build/desktop-classpath.txt)" \
 *       com.framemark.native.verify.WorkflowVerifyKt
 */
private var checks = 0
private var failures = 0

fun main(): Unit = runBlocking {
    val root = File(System.getProperty("java.io.tmpdir"), "fm-u4-verify-${System.currentTimeMillis()}")
    val dataDir = File(root, "data").apply { mkdirs() }
    val exportDir = File(root, "exports").apply { mkdirs() }
    WorkflowEnv.platform = DesktopWorkflowPlatform(
        dataDir = dataDir.absolutePath,
        exportDir = exportDir.absolutePath,
    )

    val library = LibraryDataSource.load()
    val inspector = InspectorDataSource.load()
    check("library", library.error == null)
    check("inspector", inspector.error == null)
    if (library.error != null || inspector.error != null) finish()

    val cards = library.snapshot.cards.associateBy { it.id }
    val sampleIds = listOf("wm-classic", "ratio-1", "glass-liquid")
    val session = DesignSession()

    // --- history -----------------------------------------------------------
    var applied = 0
    sampleIds.forEach { id ->
        val card = cards[id] ?: return@forEach
        val model = InspectorModelFactory.build(card, inspector.snapshot)
        session.seed(model.controls)
        applied += changeControls(session, model.controls.filterNot { it.hidden }, max = 25 - applied)
    }
    println("FM_U4_EDITS applied=$applied")
    check("history_steps_$applied", applied >= 20 && session.history.undoCount == applied)

    val edited = session.snapshotValues()
    var undone = 0
    while (session.undo()) undone++
    check("undo_all_$undone", undone == applied && !session.history.canUndo)
    check("redo_available", session.history.redoCount == applied)

    var redone = 0
    while (session.redo()) redone++
    check("redo_all_$redone", redone == applied && session.snapshotValues() == edited)

    val small = DesignHistory(limit = 8)
    repeat(12) { index -> small.record(emptyMap(), "key$index") }
    check("history_cap_${small.undoCount}", small.undoCount == 8)

    // --- presets -----------------------------------------------------------
    val card = cards.getValue("wm-classic")
    val model = InspectorModelFactory.build(card, inspector.snapshot)
    PresetStore.resetForVerify()
    val saved = PresetStore.save(
        name = "合同预设",
        cardId = card.id,
        cardName = card.name,
        adjustedCount = session.adjustedCount(card.id),
        values = session.snapshotValues(),
        export = ExportSettings(format = SampleFormat.Png, quality = 88, longEdge = 1200, filenamePattern = "{name}-{date}"),
    )
    check("preset_saved", PresetStore.items.size == 1 && saved.adjustedCount > 0)

    PresetStore.resetLoadedForVerify()
    PresetStore.load()
    val reloaded = PresetStore.items.firstOrNull()
    check("preset_reload_count", PresetStore.items.size == 1)
    check("preset_reload_fields", reloaded != null && reloaded.name == "合同预设" && reloaded.cardId == card.id)
    check("preset_reload_values", reloaded != null && reloaded.values.size == session.values.size)
    check("preset_reload_export", reloaded != null && reloaded.export.format == SampleFormat.Png && reloaded.export.longEdge == 1200)
    val restored = DesignSession().apply { reloaded?.let { applyPreset(it) } }
    check("preset_apply_values", restored.values.toMap() == session.values.toMap().mapValues { normalize(it.value) })
    check("preset_rename", PresetStore.rename(saved.id, "改名预设") && PresetStore.items.first().name == "改名预设")
    check("preset_delete", PresetStore.delete(saved.id) && PresetStore.items.isEmpty())
    check("preset_json_exists", File(dataDir, "presets.json").isFile)

    // --- sample export -----------------------------------------------------
    val jpeg = FakeEngine.export(card, ExportSettings(format = SampleFormat.Jpeg, quality = 82, longEdge = 1200))
    check("export_jpg_file", File(jpeg.path).isFile && jpeg.bytes > 0)
    check("export_jpg_name", jpeg.fileName.startsWith(card.name) && jpeg.fileName.endsWith(".jpg"))
    check("export_jpg_dims", jpeg.width == 1200 && jpeg.height == 900)
    val jpgImage = ImageIO.read(File(jpeg.path))
    check("export_jpg_decode", jpgImage != null && jpgImage.width == 1200 && jpgImage.height == 900)
    val png = FakeEngine.export(card, ExportSettings(format = SampleFormat.Png, quality = 100, longEdge = 800))
    val pngImage = ImageIO.read(File(png.path))
    check("export_png_decode", pngImage != null && pngImage.width == 800 && pngImage.height == 600)
    check("export_unique", png.path != jpeg.path)
    println("FM_U4_EXPORT jpg=${jpeg.path} bytes=${jpeg.bytes} png=${png.path} bytes=${png.bytes}")

    // --- batch queue -------------------------------------------------------
    val runner = BatchRunner()
    repeat(3) { runner.addSample() }
    runner.start()
    var guard = 0
    while (runner.runState == BatchRunState.Running && guard++ < 2000) runner.tick()
    check("batch_finish", runner.runState == BatchRunState.Finished && runner.completedCount == 3)

    runner.addSample()
    runner.start()
    runner.tick()
    runner.tick()
    val beforePause = runner.items.last().progress
    runner.pause()
    runner.tick()
    check("batch_pause", runner.items.last().progress == beforePause && runner.runState == BatchRunState.Paused)
    runner.resume()
    runner.tick()
    check("batch_resume", runner.items.last().progress > beforePause)
    runner.cancel()
    check(
        "batch_cancel",
        runner.runState == BatchRunState.Cancelled &&
            runner.items.count { it.status == BatchItemStatus.Cancelled } == 1 &&
            runner.items.count { it.status == BatchItemStatus.Done } == 3,
    )
    runner.remove(runner.items.first())
    check("batch_remove", runner.items.size == 3)
    runner.resetForVerify()
    check("batch_reset", runner.items.isEmpty() && runner.runState == BatchRunState.Idle)

    println("FM_U4_VERIFY checks=$checks failures=$failures ok=${failures == 0}")
    println("FM_U4_VERIFY_DIR root=${root.absolutePath}")
    exitProcess(if (failures == 0) 0 else 1)
}

private fun changeControls(session: DesignSession, controls: List<InspectorControl>, max: Int): Int {
    if (max <= 0) return 0
    val ordered = controls.sortedBy { control ->
        when (control.type) {
            ControlType.Switch -> 0
            ControlType.Slider -> 1
            ControlType.Select -> 2
            else -> 3
        }
    }
    var changed = 0
    for (control in ordered) {
        if (changed >= max) break
        val current = session.value(control)
        val next = when (control.type) {
            ControlType.Switch -> !(current as? Boolean ?: false)
            ControlType.Slider -> {
                val lo = control.min ?: 0f
                val hi = control.max ?: 1f
                if (hi <= lo) null else {
                    val raw = (current as? Float ?: lo) + (hi - lo) * 0.2f
                    if (raw > hi) lo else raw
                }
            }
            ControlType.Select -> control.options.firstOrNull { it.value != current }?.value
            else -> null
        } ?: continue
        session.set(control, next)
        changed++
    }
    return changed
}

private fun normalize(value: Any): Any = when (value) {
    is Double -> value.toFloat()
    else -> value
}

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) failures++
    println("FM_U4_CHECK ${if (condition) "PASS" else "FAIL"} $name")
}

private fun finish(): Nothing {
    println("FM_U4_VERIFY checks=$checks failures=$failures ok=false")
    exitProcess(1)
}
