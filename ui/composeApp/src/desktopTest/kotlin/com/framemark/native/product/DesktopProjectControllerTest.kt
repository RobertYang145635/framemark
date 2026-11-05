package com.framemark.native.product

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.workflow.DesktopProjectController
import com.framemark.native.workflow.ExportSettings
import com.framemark.native.workflow.JvmProductFileSystem
import com.framemark.native.workflow.SampleFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopProjectControllerTest {
    private fun photo(name: String): String = DesktopTestImageFixtures.path(name)

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
            control("showModel", "text.showModel", ControlType.Switch, ControlValue.Flag(true), InspectorSection.Text),
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

    @Test
    fun photos_designs_and_watermarks_stay_per_photo_and_survive_restart() = runBlocking {
        val dir = Files.createTempDirectory("fm-p2a-controller-")
        try {
            val controller = newController(dir)
            controller.load()
            assertTrue(controller.photos.isEmpty())

            val report = controller.importPhotos(
                listOf(
                    photo("ratio-study/4x3.jpg"),
                    photo("vertical.jpg"),
                    photo("p1170883.jpg"),
                ),
            )
            assertEquals(3, report.added)
            assertTrue(report.failures.isEmpty())
            assertEquals(3, controller.photos.size)
            assertEquals(controller.photos.first().id, controller.activePhotoId)
            assertTrue(controller.saveNow())

            // A: card + params + watermark
            val cardA = model("simple-1")
            controller.applyCard("simple-1", "simple-1")
            controller.applyControlOverrides(
                cardA,
                mapOf(
                    "simple-1::footer" to 42f,
                    "simple-1::theme" to "ink",
                    "simple-1::showModel" to false,
                ),
            )
            val wmA = controller.importWatermark(photo("root-import/水印_白色.png"))
            assertTrue(wmA.isSuccess, wmA.exceptionOrNull()?.message)
            controller.updateWatermark(anchor = 2, opacity = 0.55f)
            val photoA = controller.activePhotoId!!

            // B: different card, no watermark
            controller.selectPhoto(controller.photos[1].id)
            val cardB = model("card-1")
            controller.applyCard("card-1", "card-1")
            controller.applyControlOverrides(cardB, mapOf("card-1::footer" to 70f))
            val photoB = controller.activePhotoId!!

            // C: third card, own watermark
            controller.selectPhoto(controller.photos[2].id)
            val cardC = model("wm-classic")
            controller.applyCard("wm-classic", "wm-classic")
            controller.importWatermark(photo("root-import/水印_黑色.png"))
            controller.updateWatermark(anchor = 0, width = 0.35f, opacity = 0.9f)
            val photoC = controller.activePhotoId!!

            // A survives B and C without leaking.
            controller.selectPhoto(photoA)
            val designA = controller.activeDesign!!
            assertEquals("simple-1", designA.cardId)
            assertEquals(42.0, designA.params["footerPct"])
            assertEquals("ink", designA.theme)
            assertEquals(false, designA.text["showModel"])
            assertEquals(2, designA.overlay!!.anchor)
            assertEquals(0.55f, designA.overlay!!.opacity)

            val designB = controller.project.designs[photoB]!!
            assertEquals("card-1", designB.cardId)
            assertEquals(70.0, designB.params["footerPct"])
            assertNull(designB.overlay)

            val designC = controller.project.designs[photoC]!!
            assertEquals("wm-classic", designC.cardId)
            assertEquals(0, designC.overlay!!.anchor)
            assertEquals("水印_黑色.png", designC.overlay!!.asset.name)

            // Apply-to-all copies A's watermark, then clearing only C keeps the rest.
            controller.applyWatermarkToAll()
            val shared = controller.watermark()!!
            assertEquals(shared, controller.project.designs[photoB]!!.overlay)
            controller.selectPhoto(photoC)
            controller.clearWatermark()
            assertNull(controller.activeDesign!!.overlay)
            assertEquals(shared, controller.project.designs[photoA]!!.overlay)
            assertEquals(shared, controller.project.designs[photoB]!!.overlay)

            controller.setExportSettings(ExportSettings(format = SampleFormat.Png, longEdge = 2048))
            controller.selectPhoto(photoA)
            assertTrue(controller.saveNow())
            val savedActive = controller.activePhotoId

            // Restart: a new controller over the same directory restores everything.
            val before = controller.project
            controller.dispose()
            val restarted = newController(dir)
            restarted.load()
            assertEquals(3, restarted.photos.size)
            assertEquals(savedActive, restarted.activePhotoId)
            assertEquals(before.photos, restarted.project.photos)
            assertEquals(photoA, restarted.project.activePhotoId)
            val restoredA = restarted.activeDesign!!
            assertEquals(42.0, restoredA.params["footerPct"])
            assertEquals("ink", restoredA.theme)
            assertEquals(2, restoredA.overlay!!.anchor)
            assertTrue(restoredA.overlay!!.asset.isIntact())
            assertEquals("card-1", restarted.project.designs[photoB]!!.cardId)
            assertNull(restarted.project.designs[photoB]!!.params["theme"])
            assertEquals(SampleFormat.Png, restarted.exportSettingsForUi().format)
            assertEquals(2048, restarted.exportSettingsForUi().longEdge)
            restarted.dispose()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun removing_a_photo_drops_its_design_and_selects_another() = runBlocking {
        val dir = Files.createTempDirectory("fm-p2a-remove-")
        try {
            val controller = newController(dir)
            controller.load()
            controller.importPhotos(listOf(photo("ratio-study/4x3.jpg"), photo("vertical.jpg")))
            val first = controller.photos[0].id
            val second = controller.photos[1].id
            controller.selectPhoto(first)
            controller.applyCard("simple-1", "simple-1")
            controller.importWatermark(photo("root-import/水印_白色.png"))
            controller.removePhoto(first)
            assertEquals(1, controller.photos.size)
            assertEquals(second, controller.activePhotoId)
            assertNull(controller.project.designs[first])
            assertTrue(controller.saveNow())
            controller.dispose()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_corrupt_project_document_blocks_writes_until_an_explicit_retry_succeeds() = runBlocking {
        val dir = Files.createTempDirectory("fm-p2a-corrupt-")
        try {
            val projectFile = dir.resolve("project.json")
            val original = "{not json".encodeToByteArray()
            Files.write(projectFile, original)
            val controller = newController(dir)
            controller.load()
            assertNotNull(controller.loadError)
            assertNotNull(controller.saveError)
            assertTrue(controller.photos.isEmpty())
            assertTrue(controller.projectWritesBlocked)

            val report = controller.importPhotos(listOf(photo("ratio-study/4x3.jpg")))
            assertEquals(0, report.added)
            controller.setExportSettings(ExportSettings(format = SampleFormat.WebP))
            Thread.sleep(650) // Cross the debounced autosave window while the source is invalid.
            assertContentEquals(original, Files.readAllBytes(projectFile))
            assertFalse(controller.saveNow())
            controller.flush()
            assertFalse(controller.retryProjectLoad())
            assertContentEquals(original, Files.readAllBytes(projectFile))
            assertTrue(Files.list(dir).use { paths -> paths.noneMatch { it.fileName.toString().endsWith(".tmp") } })

            Files.writeString(projectFile, ProjectCodec.encode(sampleProject()))
            assertTrue(controller.retryProjectLoad())
            assertNull(controller.loadError)
            assertFalse(controller.projectWritesBlocked)
            assertEquals(2, controller.photos.size)
            assertTrue(controller.saveNow())
            controller.dispose()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_bad_image_fails_alone_and_does_not_block_the_batch() = runBlocking {
        val dir = Files.createTempDirectory("fm-p2a-bad-")
        try {
            val bad = dir.resolve("broken.jpg")
            Files.writeString(bad, "this is not an image")
            val controller = newController(dir)
            controller.load()
            val report = controller.importPhotos(listOf(photo("ratio-study/4x3.jpg"), bad.toString()))
            assertEquals(1, report.added)
            assertEquals(1, report.failures.size)
            assertEquals(bad.toString(), report.failures.first().path)
            assertTrue(report.failures.first().reason.contains("无法解码"))
            assertEquals(1, controller.importFailures.size)
            assertEquals(1, controller.photos.size)
            // The good photo still has a usable design.
            controller.applyCard("simple-1", "simple-1")
            assertEquals("simple-1", controller.activeDesign!!.cardId)
            controller.dispose()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_failed_atomic_write_is_visible_and_keeps_the_old_document() = runBlocking {
        val fs = FakeFs("/fm-p2a")
        val controller = newController(Path.of("unused"), fs)
        controller.load()
        controller.setExportSettings(ExportSettings(format = SampleFormat.Png))
        assertTrue(controller.saveNow())
        val old = fs.files["/fm-p2a/project.json"]!!.copyOf()

        fs.failWritePath = "/fm-p2a/project.json.tmp"
        controller.setExportSettings(ExportSettings(format = SampleFormat.WebP))
        assertFalse(controller.saveNow())
        val error = controller.saveError
        assertNotNull(error)
        assertTrue(error.contains("FM-E-IO-003"), error)
        assertContentEquals(old, fs.files["/fm-p2a/project.json"])
        controller.dispose()
    }

    @Test
    fun applying_a_preset_does_not_claim_success_when_its_final_save_fails() = runBlocking {
        val fs = FakeFs("/fm-preset-apply")
        SessionStore(fs).saveProject(sampleProject()).ok()
        SessionStore(fs).savePresets(listOf(samplePreset())).ok()
        val controller = newController(Path.of("unused"), fs)
        controller.load()
        controller.loadPresets()
        val before = fs.files.getValue(fs.projectPath).copyOf()

        fs.failWritePath = "${fs.projectPath}.tmp"
        assertFalse(controller.applyPreset("preset-1"))
        assertNotNull(controller.presetError)
        assertContentEquals(before, fs.files.getValue(fs.projectPath))
        controller.dispose()
    }

    @Test
    fun switching_photos_flushes_before_loading_the_next_one() = runBlocking {
        val dir = Files.createTempDirectory("fm-p2a-switch-")
        try {
            val controller = newController(dir)
            controller.load()
            controller.importPhotos(listOf(photo("ratio-study/4x3.jpg"), photo("vertical.jpg")))
            controller.applyCard("simple-1", "simple-1")
            val first = controller.photos[0].id
            val second = controller.photos[1].id
            controller.selectPhoto(second)
            val persisted = (SessionStore(JvmProductFileSystem(dir.toString())).loadProject() as StoreResult.Ok).value
            assertEquals(second, persisted.activePhotoId)
            assertNotNull(persisted.designs[first])
            controller.dispose()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
