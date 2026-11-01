package com.framemark.native.product

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.workflow.DesktopProjectController
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.ExportSettings
import com.framemark.native.workflow.ExportSizeMode
import com.framemark.native.workflow.JvmProductFileSystem
import com.framemark.native.workflow.NativeImage
import com.framemark.native.workflow.NativeRenderApi
import com.framemark.native.workflow.OverlaySpec
import com.framemark.native.workflow.PhotoMetadata
import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.WorkflowEnv
import com.framemark.native.workflow.WorkflowPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P2b: desktop presets (`product.Preset` + `SessionStore`, watermark bytes inside)
 * and the desktop batch queue (`product.BatchQueue`, per-photo pairing, resume).
 */
class DesktopPresetBatchTest {
    private val repo = Path.of(System.getProperty("user.dir")).toAbsolutePath().let {
        var dir: Path? = it
        while (dir != null && !Files.isRegularFile(dir.resolve("framemark/bridge/desktop/cards-175.json"))) {
            dir = dir.parent
        }
        dir
    } ?: error("repo root not found")

    private fun asset(name: String): String = repo.resolve("_research/test-assets/$name").toString()

    private fun newController(dir: Path, fsOverride: ProductFileSystem? = null): DesktopProjectController {
        val store = if (fsOverride != null) SessionStore(fsOverride) else SessionStore(JvmProductFileSystem(dir.toString()))
        return DesktopProjectController(
            dataDir = dir.toString(),
            store = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            clock = { 1_700_000_000_000 },
        )
    }

    private fun model(cardId: String = "simple-1"): InspectorCardModel {
        fun control(
            id: String,
            param: String,
            type: ControlType,
            initial: ControlValue,
            section: InspectorSection = InspectorSection.Layout,
        ) = InspectorControl(
            key = "$cardId::$id",
            controlId = id,
            param = param,
            label = id,
            kind = "slider",
            section = section,
            type = type,
            initial = initial,
            min = if (type == ControlType.Slider) 0f else null,
            max = if (type == ControlType.Slider) 100f else null,
            step = if (type == ControlType.Slider) 1f else null,
            options = emptyList(),
            multiline = false,
            declaredDefault = null,
            currentText = null,
            autoMatch = null,
            unit = null,
            hidden = false,
        )
        val controls = listOf(
            control("footer", "params.footerPct", ControlType.Slider, ControlValue.Num(10f)),
            control("theme", "theme", ControlType.Select, ControlValue.Str("white"), InspectorSection.Theme),
        )
        return InspectorCardModel(
            cardId = cardId,
            cardName = cardId,
            audited = true,
            effectiveCount = controls.size,
            gatedCount = 0,
            declaredCount = controls.size,
            effectiveIds = controls.map { it.controlId },
            gatedIds = emptyList(),
            visibleIds = controls.map { it.controlId },
            hiddenIds = emptyList(),
            controls = controls,
            sections = emptyList(),
        )
    }

    /** Records what each export asked the engine for, and writes a real file. */
    private class RecordingRender : NativeRenderApi {
        class Call(
            val cardId: String,
            val mode: String,
            val longEdge: Int?,
            val outputPath: String?,
            val photoPath: String?,
            val overlay: OverlaySpec?,
            val design: DesignSnapshot,
        )

        val calls = mutableListOf<Call>()

        override fun supports(cardId: String): Boolean = true
        override fun supportsExport(cardId: String): Boolean = true
        override fun supportsWebpExport(): Boolean = true

        override suspend fun render(
            cardId: String,
            design: DesignSnapshot,
            mode: String,
            maxEdge: Int?,
            format: SampleFormat?,
            quality: Int?,
            longEdge: Int?,
            outputPath: String?,
            webpLossless: Boolean,
            overlay: OverlaySpec?,
            photoPath: String?,
        ): NativeImage {
            calls += Call(cardId, mode, longEdge, outputPath, photoPath, overlay, design)
            val path = outputPath ?: error("export must name its output")
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3, cardId.hashCode().toByte(), photoPath?.hashCode()?.toByte() ?: 0))
            return NativeImage(path = path, width = 2609, height = 2000, ms = 1L)
        }
    }

    /** Stands in for a run whose scope goes away mid-export. */
    private class CancellingRender : NativeRenderApi {
        override fun supports(cardId: String): Boolean = true
        override fun supportsExport(cardId: String): Boolean = true
        override suspend fun render(
            cardId: String,
            design: DesignSnapshot,
            mode: String,
            maxEdge: Int?,
            format: SampleFormat?,
            quality: Int?,
            longEdge: Int?,
            outputPath: String?,
            webpLossless: Boolean,
            overlay: OverlaySpec?,
            photoPath: String?,
        ): NativeImage = throw kotlinx.coroutines.CancellationException("The coroutine scope left the composition")
    }

    private fun tempPlatform(dir: Path): WorkflowPlatform = object : WorkflowPlatform {
        override val dataDir = dir.toString()
        override val exportDir = dir.resolve("out").toString()
        override fun readTextFile(path: String): String? = File(path).takeIf { it.isFile }?.readText()
        override fun writeTextFile(path: String, text: String) = File(path).writeText(text)
        override fun writeBytes(path: String, bytes: ByteArray): Boolean {
            File(path).parentFile?.mkdirs()
            File(path).writeBytes(bytes)
            return true
        }
        override fun exists(path: String): Boolean = File(path).exists()
        override fun fileSize(path: String): Long = File(path).length()
        override fun readBytes(path: String): ByteArray? = File(path).readBytes()
        override fun fileFingerprint(path: String): String? =
            File(path).takeIf { it.isFile }?.let(PhotoMetadata::fingerprint)

        override fun commitExport(temporaryPath: String, finalPath: String): Boolean {
            val temporary = File(temporaryPath)
            if (!temporary.isFile) return false
            val final = File(finalPath)
            final.parentFile?.mkdirs()
            return try {
                Files.newOutputStream(
                    final.toPath(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).use { output -> temporary.inputStream().use { it.copyTo(output) } }
                Files.deleteIfExists(temporary.toPath())
                true
            } catch (_: FileAlreadyExistsException) {
                false
            }
        }

        override fun deleteExportTemporary(path: String) {
            Files.deleteIfExists(File(path).toPath())
        }

        override fun encodeSampleImage(width: Int, height: Int, format: SampleFormat, quality: Int): ByteArray? = null
        override fun dateStamp(): String = "20261030"
        override fun timeStamp(): String = "120000"
        override fun formatDateTime(epochMillis: Long): String = ""
        override fun nowMillis(): Long = 1_700_000_000_000
    }

    private suspend fun <T> withEngine(render: NativeRenderApi, dir: Path, block: suspend () -> T): T {
        val oldPlatform = WorkflowEnv.platform
        val oldRender = WorkflowEnv.nativeRender
        WorkflowEnv.platform = tempPlatform(dir)
        WorkflowEnv.nativeRender = render
        return try {
            block()
        } finally {
            WorkflowEnv.platform = oldPlatform
            WorkflowEnv.nativeRender = oldRender
        }
    }

    /** Every photo gets its own design entry; a photo without one cannot be queued. */
    private suspend fun seeded(dir: Path, count: Int = 3): DesktopProjectController {
        val controller = newController(dir)
        controller.load()
        val names = listOf("ratio-study/4x3.jpg", "vertical.jpg", "p1170883.jpg")
        controller.importPhotos(names.take(count).map { asset(it) })
        for (index in 0 until count) {
            controller.selectPhoto(controller.photos[index].id)
            controller.applyCard("simple-1", "simple-1")
        }
        controller.selectPhoto(controller.photos[0].id)
        return controller
    }

    // ---- presets -----------------------------------------------------------

    @Test
    fun a_preset_keeps_its_embedded_watermark_across_a_restart_and_reapply() = runBlocking {
        val dir = Files.createTempDirectory("fm-p2b-preset-")
        try {
            val controller = seeded(dir)
            val photoA = controller.photos[0].id
            val photoB = controller.photos[1].id
            controller.importWatermark(asset("root-import/水印_白色.png"))
            controller.applyControlOverrides(model(), mapOf("simple-1::footer" to 42f))

            val saved = controller.savePreset("我的签名预设")
            assertNotNull(saved)
            assertTrue(saved!!.embeddedWatermarkBytes > 0, "预设必须内嵌水印字节")

            // A second photo with no watermark at all gets the full design back.
            controller.selectPhoto(photoB)
            assertNull(controller.activeDesign?.overlay)
            assertTrue(controller.applyPreset(saved.id))
            val applied = controller.activeDesign
            assertEquals(42.0, applied?.params?.get("footerPct"))
            assertEquals("水印_白色.png", applied?.overlay?.asset?.name)

            // Restart: the library and the watermark bytes come back from disk.
            controller.dispose()
            val restarted = newController(dir)
            restarted.load()
            restarted.loadPresets()
            assertEquals(1, restarted.presets.size)
            val stored = restarted.presets.first()
            assertEquals(saved.design.overlay?.asset?.sha256, stored.design.overlay?.asset?.sha256)
            assertTrue(stored.design.overlay?.asset?.isIntact() == true)

            restarted.selectPhoto(photoA)
            assertTrue(restarted.applyPreset(stored.id))
            val bytes = restarted.watermarkBytes()
            assertNotNull(bytes)
            assertEquals(stored.design.overlay!!.asset.byteSize, bytes!!.size)
            restarted.dispose()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_failed_preset_write_is_reported_and_the_document_is_untouched() = runBlocking {
        val dir = Files.createTempDirectory("fm-p2b-preset-fail-")
        try {
            val controller = seeded(dir)
            controller.importWatermark(asset("root-import/水印_白色.png"))
            assertTrue(controller.savePreset("第一版") != null)
            controller.dispose()

            val reopened = newController(dir)
            reopened.load()
            reopened.loadPresets()
            assertEquals(1, reopened.presets.size)
            val document = File(dir.toString(), "presets.json").readText()

            val failing = newController(dir, FailingPresetFs(dir.toString()))
            failing.load()
            failing.loadPresets()
            assertNull(failing.savePreset("第二版"))
            assertNotNull(failing.presetError)
            assertTrue(failing.deletePreset(reopened.presets.first().id).not())
            // Nothing was rewritten: the on-disk library still holds exactly one preset.
            assertEquals(document, File(dir.toString(), "presets.json").readText())
            reopened.dispose()
            failing.dispose()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_single_preset_file_carries_the_watermark_to_another_project() = runBlocking {
        val dir = Files.createTempDirectory("fm-p2b-preset-file-")
        val targetDir = Files.createTempDirectory("fm-p2b-preset-file-target-")
        try {
            val source = seeded(dir)
            source.importWatermark(asset("root-import/水印_黑色.png"))
            source.applyControlOverrides(model(), mapOf("simple-1::theme" to "glass"))
            val preset = source.savePreset("可携带预设")
            assertNotNull(preset)
            val file = dir.resolve("carry.json").toString()
            assertTrue(source.exportPresetFile(preset!!.id, file))

            val target = newController(targetDir)
            target.load()
            target.importPhotos(listOf(asset("vertical.jpg")))
            target.applyCard("card-1", "card-1")
            val imported = target.importPresetFile(file)
            assertNotNull(imported)
            assertEquals("glass", imported!!.design.theme)
            assertEquals("水印_黑色.png", imported.design.overlay?.asset?.name)
            assertTrue(target.applyPreset(imported.id))
            assertEquals("水印_黑色.png", target.activeDesign?.overlay?.asset?.name)
            source.dispose()
            target.dispose()
        } finally {
            dir.toFile().deleteRecursively()
            targetDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun preset_id_conflict_keeps_the_existing_library_until_replace_is_explicit() = runBlocking {
        val sourceDir = Files.createTempDirectory("fm-p2b-preset-conflict-source-")
        val targetDir = Files.createTempDirectory("fm-p2b-preset-conflict-target-")
        try {
            val source = seeded(sourceDir, count = 1)
            val portable = source.savePreset("可携带预设")
            assertNotNull(portable)
            val presetFile = sourceDir.resolve("conflict.json").toString()
            assertTrue(source.exportPresetFile(portable!!.id, presetFile))

            val target = seeded(targetDir, count = 1)
            val existing = portable.copy(name = "本机已有", design = portable.design.withTheme("black"))
            assertTrue(target.importPreset(existing))
            val before = Files.readAllBytes(targetDir.resolve("presets.json"))

            val pending = target.readPresetFile(presetFile)
            assertNotNull(pending)
            assertTrue(target.presetError == null)
            assertTrue(!target.importPreset(pending), "相同 ID 未经确认不得替换")
            assertContentEquals(before, Files.readAllBytes(targetDir.resolve("presets.json")))
            assertEquals("本机已有", target.presets.single().name)
            assertTrue(target.presetError?.contains("ID 已存在") == true)

            assertTrue(target.importPreset(pending, replaceExisting = true), "显式替换应成功")
            assertEquals(pending, target.presets.single())
            assertEquals(pending, SessionStore(JvmProductFileSystem(targetDir.toString())).loadPresets().ok().single())
            source.dispose()
            target.dispose()
        } finally {
            sourceDir.toFile().deleteRecursively()
            targetDir.toFile().deleteRecursively()
        }
    }

    // ---- batch -------------------------------------------------------------

    @Test
    fun theme_only_and_watermark_only_photos_each_get_their_own_job() = runBlocking {
        val dir = Files.createTempDirectory("fm-p2b-batch-keys-")
        try {
            withEngine(RecordingRender(), dir) {
                val controller = seeded(dir)
                val a = controller.photos[0].id
                val b = controller.photos[1].id
                val c = controller.photos[2].id
                // a: untouched design; b: theme only; c: watermark only.
                controller.selectPhoto(b)
                controller.applyControlOverrides(model(), mapOf("simple-1::theme" to "glass"))
                controller.selectPhoto(c)
                controller.importWatermark(asset("root-import/水印_白色.png"))

                assertEquals(3, controller.enqueueBatch(listOf(a, b, c)))
                val jobs = controller.batchJobs
                assertEquals(3, jobs.size)
                assertEquals(1, jobs.count { it.design.theme == null && it.overlay == null })
                assertEquals(1, jobs.count { it.design.theme == "glass" && it.overlay == null })
                assertEquals(1, jobs.count { it.design.theme == null && it.overlay != null })
                assertEquals(3, jobs.map { it.idempotencyKey }.distinct().size)

                // Re-queueing the same three photos must not duplicate anything.
                assertEquals(0, controller.enqueueBatch(listOf(a, b, c)))
                assertEquals(3, controller.batchJobs.size)
                controller.dispose()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * R1 regression: one photo, two jobs that differ **only** by the watermark (or
     * only by the theme). The photo component of the key is identical, so a key
     * that ignores the overlay silently swallows the second row.
     */
    @Test
    fun same_photo_with_two_watermark_variants_queues_two_rows_not_one() = runBlocking {
        val dir = Files.createTempDirectory("fm-p2b-batch-same-photo-")
        try {
            withEngine(RecordingRender(), dir) {
                val controller = seeded(dir, count = 1)
                val only = controller.photos.single().id
                // Row 1: no watermark.
                assertEquals(1, controller.enqueueBatch(listOf(only)))
                // Row 2: same photo, same design, watermark added.
                check(controller.importWatermark(asset("root-import/水印_白色.png")).isSuccess)
                assertEquals(1, controller.enqueueBatch(listOf(only)), "只改水印必须再入一行")
                // Row 3: same photo + watermark, theme changed.
                controller.applyControlOverrides(model(), mapOf("simple-1::theme" to "glass"))
                assertEquals(1, controller.enqueueBatch(listOf(only)), "只改主题必须再入一行")

                val jobs = controller.batchJobs
                assertEquals(3, jobs.size)
                assertEquals(3, jobs.map { it.idempotencyKey }.distinct().size)
                assertEquals(1, jobs.count { it.overlay == null && it.design.theme == null })
                assertEquals(1, jobs.count { it.overlay != null && it.design.theme == null })
                assertEquals(1, jobs.count { it.overlay != null && it.design.theme == "glass" })
                // Re-queueing the current state is still a duplicate.
                assertEquals(0, controller.enqueueBatch(listOf(only)))
                assertEquals(3, controller.batchJobs.size)
                controller.dispose()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_killed_run_resumes_from_the_queue_without_losing_or_repeating_items() = runBlocking {
        val dir = Files.createTempDirectory("fm-p2b-batch-resume-")
        val render = RecordingRender()
        try {
            withEngine(render, dir) {
                val controller = seeded(dir)
                controller.importWatermark(asset("root-import/水印_白色.png"))
                assertEquals(3, controller.enqueueBatch(controller.photos.map { it.id }))
                controller.dispose()

                // Simulate the force-kill: mark the third row Running in the document.
                val store = SessionStore(JvmProductFileSystem(dir.toString()))
                val interrupted = store.loadBatch().let {
                    (it as StoreResult.Ok).value
                }
                val running = BatchQueue(
                    interrupted.jobs.mapIndexed { index, job ->
                        when (index) {
                            0 -> job.copy(status = BatchJobStatus.Done, outputPath = "done-0")
                            1 -> job.copy(status = BatchJobStatus.Failed, error = BatchJobError("FM-E-EXPORT-001", "boom"))
                            else -> job.copy(status = BatchJobStatus.Running)
                        }
                    },
                )
                assertTrue(store.saveBatch(running) is StoreResult.Ok)

                val restarted = newController(dir)
                restarted.load()
                restarted.loadBatch()
                assertEquals(3, restarted.batchJobs.size)
                assertEquals(BatchJobStatus.Done, restarted.batchJobs[0].status)
                assertEquals(BatchJobStatus.Failed, restarted.batchJobs[1].status)
                assertEquals(BatchJobStatus.Queued, restarted.batchJobs[2].status, "被打断的项必须回到队列")

                restarted.retryBatch(restarted.batchJobs[1].id)
                restarted.runBatch()

                assertEquals(3, restarted.batchDone)
                assertEquals(0, restarted.batchFailed)
                // Only the two non-Done rows were exported: the finished one was not repeated.
                assertEquals(2, render.calls.size)
                assertEquals(listOf("vertical.jpg", "p1170883.jpg"), render.calls.map { File(it.photoPath!!).name })
                assertEquals(
                    listOf("4x3.jpg", "p1170883.jpg", "vertical.jpg"),
                    restarted.batchJobs.filter { it.status == BatchJobStatus.Done }.map { it.displayName }.sorted(),
                )
                restarted.dispose()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_queued_watermark_is_paired_with_its_own_photo_at_export_time() = runBlocking {
        val dir = Files.createTempDirectory("fm-p2b-batch-pair-")
        val render = RecordingRender()
        try {
            withEngine(render, dir) {
                val controller = seeded(dir, count = 2)
                controller.importWatermark(asset("root-import/水印_白色.png"))
                controller.applyWatermarkToAll()
                assertEquals(2, controller.enqueueBatch(controller.photos.map { it.id }))
                // Switching photos after queueing must not change what each job carries.
                controller.clearWatermark()
                controller.runBatch()

                assertEquals(2, render.calls.size)
                assertTrue(render.calls.all { it.overlay != null }, "两张都该带上各自队列里的水印")
                assertTrue(render.calls.all { it.mode == "export" })
                assertEquals(
                    render.calls.map { it.photoPath }.distinct().size,
                    2,
                    "两张必须是各自的照片",
                )
                controller.dispose()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ---- export size / naming ---------------------------------------------

    @Test
    fun original_size_sends_no_long_edge_and_the_name_carries_the_photo_and_index() = runBlocking {
        val dir = Files.createTempDirectory("fm-p2b-size-")
        val render = RecordingRender()
        try {
            withEngine(render, dir) {
                val controller = seeded(dir, count = 2)
                controller.setExportSettings(ExportSettings(format = SampleFormat.Jpeg))
                val firstResult = controller.exportActive("极简 01", controller.exportSettingsForUi())
                controller.selectPhoto(controller.photos[1].id)
                val secondResult = controller.exportActive("极简 01", controller.exportSettingsForUi())

                val first = render.calls[0]
                val second = render.calls[1]
                assertNull(first.longEdge, "原尺寸不能给引擎任何长边")
                assertNull(second.longEdge)
                assertEquals("4x3-1.jpg", firstResult.fileName)
                assertEquals("vertical-2.jpg", secondResult.fileName)
                assertTrue(File(first.outputPath!!).name.contains(".fm-tmp-"), "引擎应写入临时输出路径")
                assertTrue(File(second.outputPath!!).name.contains(".fm-tmp-"), "引擎应写入临时输出路径")
                assertEquals(2, listOf(first.outputPath, second.outputPath).distinct().size, "每次渲染应使用唯一临时路径")
                assertTrue(!File(first.outputPath!!).exists(), "首次提交完成后临时文件应清理")
                assertTrue(!File(second.outputPath!!).exists(), "第二次提交完成后临时文件应清理")
                assertTrue(File(firstResult.path).isFile, "最终成片应提交到最终路径")
                assertTrue(File(secondResult.path).isFile, "最终成片应提交到最终路径")

                controller.setExportSettings(
                    controller.exportSettingsForUi().copy(sizeMode = ExportSizeMode.LongEdge, longEdge = 1200),
                )
                val thirdResult = controller.exportActive("极简 01", controller.exportSettingsForUi())
                assertEquals(1200, render.calls[2].longEdge)
                assertTrue(!File(render.calls[2].outputPath!!).exists(), "第三次提交完成后临时文件应清理")
                assertTrue(File(thirdResult.path).isFile, "第三张成片应提交到最终路径")
                controller.dispose()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_theme_only_design_reaches_the_engine_snapshot() = runBlocking {
        val dir = Files.createTempDirectory("fm-p2b-theme-")
        val render = RecordingRender()
        try {
            withEngine(render, dir) {
                val controller = seeded(dir, count = 1)
                controller.applyControlOverrides(model(), mapOf("simple-1::theme" to "glass"))
                controller.exportActive("极简 01", controller.exportSettingsForUi())
                assertEquals("glass", render.calls.single().design.params["theme"])
                controller.dispose()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * A run cancelled by the UI must put the in-flight row back in the queue. It
     * used to be relabelled as an engine failure, which lost the work permanently.
     */
    @Test
    fun a_cancelled_run_returns_the_row_to_the_queue_instead_of_failing_it() = runBlocking {
        val dir = Files.createTempDirectory("fm-p2b-cancel-")
        try {
            withEngine(CancellingRender(), dir) {
                val controller = seeded(dir, count = 2)
                assertEquals(2, controller.enqueueBatch(controller.photos.map { it.id }))
                val thrown = runCatching { controller.runBatch() }.exceptionOrNull()
                assertNotNull(thrown)
                val jobs = controller.batchJobs
                assertEquals(0, controller.batchDone)
                assertEquals(0, controller.batchFailed, "取消不能记成失败：${jobs.map { it.status }}")
                assertEquals(BatchJobStatus.Queued, jobs[0].status)
                assertEquals(BatchJobStatus.Queued, jobs[1].status)
                controller.dispose()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Refuses the preset document's temp write, so the store must report failure. */
    private class FailingPresetFs(dataDir: String) : ProductFileSystem {
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
}
