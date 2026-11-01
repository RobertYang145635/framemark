package com.framemark.native.product

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.shell.PhoneProjectStore
import com.framemark.native.shell.legacyToPreset
import com.framemark.native.workflow.DesktopProjectController
import com.framemark.native.workflow.DesktopWorkflowPlatform
import com.framemark.native.workflow.ExportSettings
import com.framemark.native.workflow.JvmProductFileSystem
import com.framemark.native.workflow.PresetStore
import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.WorkflowEnv
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P2c: the desktop's `presets.json` / `batch-queue.json` must be the same product
 * document the phone writes, and an install that still holds the old hand-written
 * files must read, upgrade and re-read them without losing content.
 */
class LegacyDocumentMigrationTest {
    private fun controllerAt(dir: Path, fsOverride: ProductFileSystem? = null): DesktopProjectController =
        DesktopProjectController(
            dataDir = dir.toString(),
            store = SessionStore(fsOverride ?: JvmProductFileSystem(dir.toString())),
            clock = { 1_700_000_000_000 },
        )

    private fun model(cardId: String = "simple-1"): InspectorCardModel {
        val control = InspectorControl(
            key = "$cardId::footer",
            controlId = "footer",
            param = "params.footerPct",
            label = "footer",
            kind = "slider",
            section = InspectorSection.Layout,
            type = ControlType.Slider,
            initial = ControlValue.Num(10f),
            min = 0f,
            max = 100f,
            step = 1f,
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
            effectiveIds = listOf("footer"),
            gatedIds = emptyList(),
            visibleIds = listOf("footer"),
            hiddenIds = emptyList(),
            controls = listOf(control),
            sections = emptyList(),
        )
    }

    private val legacyPresetText = """
        {"version":1,"presets":[
          {"id":"p1","name":"旧版白边","updatedAt":1700000000000,"cardId":"simple-1","cardName":"极简 01",
           "adjustedCount":1,"export":{"format":"JPG","quality":100,"longEdge":1600,"webpLossless":true,"filename":"{name}-{date}"},
           "values":{"simple-1::footer":44}}
        ]}
    """.trimIndent() + "\n"

    private val emptyLegacyPresetText = """{"version":1,"presets":[]}"""

    private val legacyBatchText = """
        {"version":1,"items":[
          {"id":1,"name":"4x3.jpg","cardId":"simple-1","cardName":"极简 01","styleId":"simple-1",
           "photoPath":"D:/x/4x3.jpg","photoFingerprint":"abc123","designKey":"d1",
           "format":"JPG","quality":100,"longEdge":1600,"filenamePattern":"{name}-{date}","webpLossless":true,
           "status":"Done","progress":1,"outputPath":"D:/out/a.jpg","error":null,"attempts":1},
          {"id":2,"name":"vertical.jpg","cardId":"wm-classic","cardName":"水印经典","styleId":"wm-classic",
           "photoPath":"D:/x/vertical.jpg","photoFingerprint":"def456","designKey":"d2",
           "format":"PNG","quality":100,"longEdge":1200,"filenamePattern":"{name}-{date}","webpLossless":true,
           "status":"Running","progress":0.5,"outputPath":null,"error":null,"attempts":1}
        ]}
    """.trimIndent() + "\n"

    private fun convertedLegacyPreset(id: String = "p1"): Preset = Preset(
        id = id,
        name = "旧版白边",
        design = PhotoDesign(cardId = "simple-1", params = mapOf("footerPct" to 44.0)),
        exportSettings = com.framemark.native.product.ExportSettings(quality = 100),
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_000_000,
    )

    @Test
    fun a_legacy_preset_document_upgrades_and_the_re_read_is_identical() {
        val dir = Files.createTempDirectory("fm-p2c-preset-legacy-")
        val oldPlatform = WorkflowEnv.platform
        try {
            val originalBytes = legacyPresetText.encodeToByteArray()
            File(dir.toString(), "presets.json").writeBytes(originalBytes)
            WorkflowEnv.platform = DesktopWorkflowPlatform(dataDir = dir.toString(), exportDir = dir.toString())
            PresetStore.resetForVerify()
            File(dir.toString(), "presets.json").writeText(legacyPresetText)
            PresetStore.resetLoadedForVerify()
            PresetStore.load()
            assertEquals(1, PresetStore.items.size, "旧文档必须能被旧读取器解析")

            val controller = controllerAt(dir)
            controller.load()
            controller.loadPresets()
            assertEquals(0, controller.presets.size, "旧文档不能被当成产品文档读出一半")

            val converted = PresetStore.items.map {
                legacyToPreset(it, it.cardId, it.cardId, model(it.cardId))
            }
            assertEquals(1, controller.importLegacyPresets(converted))
            assertEquals(44.0, controller.presets.first().design.params["footerPct"])
            assertEquals(100, controller.presets.first().exportSettings.quality)

            val backup = File(dir.toString(), "presets.json.legacy-${Sha256.hex(originalBytes)}.bak")
            assertTrue(backup.isFile, "原始预设字节备份必须在迁移前创建")
            assertContentEquals(originalBytes, backup.readBytes(), "SHA 备份必须逐字节保留旧库")

            val written = File(dir.toString(), "presets.json").readText()
            assertTrue(written.startsWith("""{"document":"framemark.presets","schemaVersion":1"""), written.take(80))

            val reopened = controllerAt(dir)
            reopened.load()
            reopened.loadPresets()
            assertEquals(controller.presets, reopened.presets, "升完再读一遍必须全等")
            reopened.dispose()
            controller.dispose()
        } finally {
            WorkflowEnv.platform = oldPlatform
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun an_exact_empty_legacy_preset_library_migrates_and_unlocks_first_save_on_both_shells() {
        val desktop = Files.createTempDirectory("fm-empty-legacy-desktop-")
        val phone = Files.createTempDirectory("fm-empty-legacy-phone-")
        val sourceBytes = emptyLegacyPresetText.encodeToByteArray()
        try {
            File(desktop.toString(), "presets.json").writeBytes(sourceBytes)
            val controller = controllerAt(desktop)
            controller.load()
            controller.loadPresets()
            assertTrue(controller.presets.isEmpty())
            assertNull(controller.presetError)
            assertTrue(controller.importPreset(convertedLegacyPreset("desktop-first")), "桌面迁移后应可保存首个预设")
            assertEquals(listOf("desktop-first"), controller.presets.map { it.id })
            val desktopBackup = File(desktop.toString(), "presets.json.legacy-${Sha256.hex(sourceBytes)}.bak")
            assertTrue(desktopBackup.isFile)
            assertContentEquals(sourceBytes, desktopBackup.readBytes())

            File(phone.toString(), "presets.json").writeBytes(sourceBytes)
            val phoneStore = PhoneProjectStore(JvmProductFileSystem(phone.toString()), null, null)
            phoneStore.load()
            assertTrue(phoneStore.presets.isEmpty())
            assertNull(phoneStore.presetError)
            assertTrue(phoneStore.importPreset(convertedLegacyPreset("phone-first")), "手机迁移后应可保存首个预设")
            assertEquals(listOf("phone-first"), phoneStore.presets.map { it.id })
            val phoneBackup = File(phone.toString(), "presets.json.legacy-${Sha256.hex(sourceBytes)}.bak")
            assertTrue(phoneBackup.isFile)
            assertContentEquals(sourceBytes, phoneBackup.readBytes())

            assertTrue(File(desktop.toString(), "presets.json").readText().startsWith("""{"document":"framemark.presets"""))
            assertTrue(File(phone.toString(), "presets.json").readText().startsWith("""{"document":"framemark.presets"""))
            controller.dispose()
        } finally {
            desktop.toFile().deleteRecursively()
            phone.toFile().deleteRecursively()
        }
    }

    @Test
    fun damaged_empty_legacy_preset_envelopes_remain_read_only_on_both_shells() {
        val damaged = listOf(
            "unsupported-version" to """{"version":2,"presets":[]}""",
            "unknown-field" to """{"version":1,"presets":[],"unexpected":true}""",
        )
        for ((label, text) in damaged) {
            val desktop = Files.createTempDirectory("fm-damaged-empty-desktop-$label-")
            val phone = Files.createTempDirectory("fm-damaged-empty-phone-$label-")
            val sourceBytes = text.encodeToByteArray()
            try {
                File(desktop.toString(), "presets.json").writeBytes(sourceBytes)
                val controller = controllerAt(desktop)
                controller.load()
                controller.loadPresets()
                assertFalse(controller.importPreset(convertedLegacyPreset("desktop-first")), "桌面损坏文件仍应禁止保存：$label")
                assertContentEquals(sourceBytes, File(desktop.toString(), "presets.json").readBytes())
                assertFalse(File(desktop.toString()).listFiles().orEmpty().any { it.name.startsWith("presets.json.legacy-") })

                File(phone.toString(), "presets.json").writeBytes(sourceBytes)
                val phoneStore = PhoneProjectStore(JvmProductFileSystem(phone.toString()), null, null)
                phoneStore.load()
                assertFalse(phoneStore.importPreset(convertedLegacyPreset("phone-first")), "手机损坏文件仍应禁止保存：$label")
                assertContentEquals(sourceBytes, File(phone.toString(), "presets.json").readBytes())
                assertFalse(File(phone.toString()).listFiles().orEmpty().any { it.name.startsWith("presets.json.legacy-") })
                controller.dispose()
            } finally {
                desktop.toFile().deleteRecursively()
                phone.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun a_legacy_batch_document_upgrades_and_the_re_read_is_identical() {
        val dir = Files.createTempDirectory("fm-p2c-batch-legacy-")
        try {
            File(dir.toString(), "batch-queue.json").writeText(legacyBatchText)
            val controller = controllerAt(dir)
            controller.load()
            controller.loadBatch()
            assertTrue(controller.legacyBatchPending, "旧队列必须被认出来")
            assertEquals(2, controller.batchJobs.size)

            assertEquals(2, controller.migrateLegacyBatch())
            assertFalse(controller.legacyBatchPending)
            val written = File(dir.toString(), "batch-queue.json").readText()
            assertTrue(written.startsWith("""{"document":"framemark.batch","schemaVersion":1"""), written.take(80))

            val reopened = controllerAt(dir)
            reopened.load()
            reopened.loadBatch()
            val before = controller.batchJobs
            val after = reopened.batchJobs
            assertEquals(before.map { it.displayName }, after.map { it.displayName })
            assertEquals(before.map { it.design.cardId }, after.map { it.design.cardId })
            assertEquals(before.map { it.photoFingerprint }, after.map { it.photoFingerprint })
            assertEquals(before.map { it.exportSettings }, after.map { it.exportSettings })
            // The interrupted row goes back to the queue; the finished one stays finished.
            assertEquals(BatchJobStatus.Done, after.first { it.displayName == "4x3.jpg" }.status)
            assertEquals(BatchJobStatus.Queued, after.first { it.displayName == "vertical.jpg" }.status)
            // Nothing was invented: the legacy document carried no watermark or values.
            assertTrue(after.all { it.overlay == null && it.design.params.isEmpty() })
            reopened.dispose()
            controller.dispose()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun one_preset_document_parses_on_both_shells() {
        val desktop = Files.createTempDirectory("fm-p2c-doc-desktop-")
        val phone = Files.createTempDirectory("fm-p2c-doc-phone-")
        try {
            val writer = controllerAt(desktop)
            writer.load()
            writer.loadPresets()
            val source = SessionStore(JvmProductFileSystem(phone.toString()))
            val preset = Preset(
                id = "p-shared",
                name = "两端共用预设",
                design = PhotoDesign(
                    cardId = "simple-1",
                    styleId = "simple-1",
                    params = mapOf("footerPct" to 44.0),
                    overlay = WatermarkSpec(
                        asset = WatermarkAsset.create("sig.png", "image/png", 8, 4, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)),
                        anchor = 8,
                        width = 0.3f,
                        opacity = 0.7f,
                    ),
                ),
                exportSettings = ExportSettings(format = ExportFormat.WebP),
                createdAt = 5,
                updatedAt = 5,
            )
            assertTrue(source.savePresets(listOf(preset)) is StoreResult.Ok)
            val shared = File(phone.toString(), "presets.json").readText()

            // The desktop reads the phone-written document unchanged.
            File(desktop.toString(), "presets.json").writeText(shared)
            val desktopReader = controllerAt(desktop)
            desktopReader.load()
            desktopReader.loadPresets()
            assertEquals(listOf(preset), desktopReader.presets)

            // The phone store reads the desktop-written document unchanged.
            val desktopText = File(desktop.toString(), "presets.json").readText()
            File(phone.toString(), "presets.json").writeText(desktopText)
            val phoneStore = PhoneProjectStore(JvmProductFileSystem(phone.toString()), null, null)
            phoneStore.loadPresets()
            assertEquals(1, phoneStore.presets.size)
            val seen = phoneStore.presets.first()
            assertEquals(preset.id, seen.id)
            assertEquals(preset.name, seen.name)
            assertEquals(preset.design.params, seen.design.params)
            assertEquals(preset.design.overlay?.asset?.sha256, seen.design.overlay?.asset?.sha256)
            assertEquals(preset.exportSettings, seen.exportSettings)
            // Same model in, same bytes out on both sides.
            assertEquals(desktopText, shared, "同一份预设两端写出的文档必须逐字节相同")
            desktopReader.dispose()
            writer.dispose()
        } finally {
            desktop.toFile().deleteRecursively()
            phone.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_product_document_is_never_mistaken_for_a_legacy_one() {
        val dir = Files.createTempDirectory("fm-p2c-not-legacy-")
        try {
            val controller = controllerAt(dir)
            controller.load()
            controller.loadBatch()
            assertFalse(controller.legacyBatchPending)
            File(dir.toString(), "batch-queue.json").writeText(legacyBatchText)
            assertNotNull(LegacyDocuments.legacyBatch(legacyBatchText))
            assertNull(LegacyDocuments.legacyBatch("""{"document":"framemark.batch","schemaVersion":1,"jobs":[]}"""))
            assertFalse(LegacyDocuments.isProductBatch(legacyBatchText))
            assertTrue(LegacyDocuments.isProductPresetLibrary("""{"document":"framemark.presets","schemaVersion":1,"presets":[]}"""))
            assertFalse(LegacyDocuments.isProductPresetLibrary(legacyPresetText))
            controller.dispose()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_failed_migration_write_keeps_the_legacy_file_and_reports() {
        val dir = Files.createTempDirectory("fm-p2c-migrate-fail-")
        val oldPlatform = WorkflowEnv.platform
        try {
            File(dir.toString(), "presets.json").writeText(legacyPresetText)
            WorkflowEnv.platform = DesktopWorkflowPlatform(dataDir = dir.toString(), exportDir = dir.toString())
            PresetStore.resetForVerify()
            File(dir.toString(), "presets.json").writeText(legacyPresetText)
            PresetStore.resetLoadedForVerify()
            PresetStore.load()
            val converted = PresetStore.items.map { legacyToPreset(it, it.cardId, it.cardId, model(it.cardId)) }

            val controller = controllerAt(dir, FailingWriteFs(dir.toString()))
            controller.load()
            assertEquals(0, controller.importLegacyPresets(converted))
            assertNotNull(controller.presetError)
            // The user's legacy file is untouched, so nothing was lost.
            assertEquals(legacyPresetText, File(dir.toString(), "presets.json").readText())
            controller.dispose()
        } finally {
            WorkflowEnv.platform = oldPlatform
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun legacy_migration_rejects_incomplete_or_damaged_rows_and_keeps_writes_blocked() {
        val cases = listOf(
            Triple("mismatched-id", legacyPresetText, listOf(convertedLegacyPreset("other-id"))),
            Triple("mismatched-count", legacyPresetText, listOf(convertedLegacyPreset(), convertedLegacyPreset("extra-id"))),
            Triple("blank-legacy-id", legacyPresetText.replace("\"id\":\"p1\"", "\"id\":\"\""), listOf(convertedLegacyPreset())),
        )
        for ((label, sourceText, candidates) in cases) {
            val dir = Files.createTempDirectory("fm-preset-legacy-reject-$label-")
            try {
                val sourceBytes = sourceText.encodeToByteArray()
                File(dir.toString(), "presets.json").writeBytes(sourceBytes)
                val controller = controllerAt(dir)
                controller.load()
                controller.loadPresets()

                assertEquals(0, controller.importLegacyPresets(candidates), label)
                assertFalse(controller.importPreset(convertedLegacyPreset("new-user-preset")), "普通预设写入仍应受旧库读取失败保护")
                assertContentEquals(sourceBytes, File(dir.toString(), "presets.json").readBytes(), "失败不得改写旧库：$label")
                assertFalse(File(dir.toString()).listFiles().orEmpty().any { it.name.startsWith("presets.json.legacy-") }, "拒绝迁移前不应生成备份：$label")
                assertFalse(File(dir.toString(), "presets.json.tmp").exists(), "拒绝迁移前不应生成暂存文件：$label")
                controller.dispose()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun a_failed_legacy_backup_write_keeps_original_and_does_not_stage_migration() {
        val dir = Files.createTempDirectory("fm-preset-legacy-backup-fail-")
        try {
            val originalBytes = legacyPresetText.encodeToByteArray()
            File(dir.toString(), "presets.json").writeBytes(originalBytes)
            val controller = controllerAt(dir, BackupFailingFs(dir.toString()))
            controller.load()
            controller.loadPresets()

            assertEquals(0, controller.importLegacyPresets(listOf(convertedLegacyPreset())))
            assertContentEquals(originalBytes, File(dir.toString(), "presets.json").readBytes())
            assertFalse(File(dir.toString(), "presets.json.tmp").exists(), "备份失败时不能进入暂存替换阶段")
            assertFalse(File(dir.toString()).listFiles().orEmpty().any { it.name.startsWith("presets.json.legacy-") })
            assertFalse(controller.importPreset(convertedLegacyPreset("ordinary-write")), "备份失败后仍须禁止普通保存")
            assertContentEquals(originalBytes, File(dir.toString(), "presets.json").readBytes())
            controller.dispose()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_tampered_legacy_migration_stage_is_rejected_and_original_is_preserved() {
        val dir = Files.createTempDirectory("fm-preset-legacy-stage-tamper-")
        try {
            val originalBytes = legacyPresetText.encodeToByteArray()
            File(dir.toString(), "presets.json").writeBytes(originalBytes)
            val controller = controllerAt(dir, TamperingTempFs(dir.toString()))
            controller.load()
            controller.loadPresets()

            assertEquals(0, controller.importLegacyPresets(listOf(convertedLegacyPreset())))
            assertContentEquals(originalBytes, File(dir.toString(), "presets.json").readBytes())
            val backup = File(dir.toString(), "presets.json.legacy-${Sha256.hex(originalBytes)}.bak")
            assertTrue(backup.isFile)
            assertContentEquals(originalBytes, backup.readBytes())
            assertFalse(File(dir.toString(), "presets.json.tmp").exists(), "篡改暂存文件应被清理")
            assertFalse(controller.importPreset(convertedLegacyPreset("ordinary-write")), "暂存校验失败后普通保存仍须被阻止")
            assertContentEquals(originalBytes, File(dir.toString(), "presets.json").readBytes())
            controller.dispose()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private class FailingWriteFs(dataDir: String) : ProductFileSystem {
        private val delegate = JvmProductFileSystem(dataDir)
        override val dataDir: String get() = delegate.dataDir
        override fun exists(path: String): Boolean = delegate.exists(path)
        override fun readText(path: String): String? = delegate.readText(path)
        override fun writeBytes(path: String, bytes: ByteArray): Boolean =
            if (path.endsWith("presets.json.tmp")) false else delegate.writeBytes(path, bytes)
        override fun fileSize(path: String): Long = delegate.fileSize(path)
        override fun rename(from: String, to: String): Boolean = delegate.rename(from, to)
        override fun delete(path: String): Boolean = delegate.delete(path)
    }

    private class BackupFailingFs(dataDir: String) : ProductFileSystem by JvmProductFileSystem(dataDir) {
        private val delegate = JvmProductFileSystem(dataDir)
        override fun writeBytesIfAbsent(path: String, bytes: ByteArray): Boolean =
            if (".legacy-" in path) false else delegate.writeBytesIfAbsent(path, bytes)
    }

    private class TamperingTempFs(dataDir: String) : ProductFileSystem by JvmProductFileSystem(dataDir) {
        private val delegate = JvmProductFileSystem(dataDir)
        override fun writeBytes(path: String, bytes: ByteArray): Boolean {
            if (!path.endsWith("presets.json.tmp")) return delegate.writeBytes(path, bytes)
            val tampered = bytes.copyOf()
            if (tampered.isNotEmpty()) tampered[0] = '['.code.toByte()
            return delegate.writeBytes(path, tampered)
        }
    }
}
