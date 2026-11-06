package com.framemark.native.product

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.workflow.DesktopProjectController
import com.framemark.native.workflow.InMemoryProjectHolder
import com.framemark.native.workflow.JvmProductFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WP-D1 phase 2: `ProjectHolder.applyDesignFromPhoto`.
 *
 * The interface method is backwards compatible (default returns -1); the desktop
 * controller copies `cardId` / `styleId` / `params` / `text` onto the target
 * photos and keeps every target's own watermark and theme.
 */
class DesktopApplyDesignFromPhotoTest {

    private fun photo(name: String): String = DesktopTestImageFixtures.path(name)

    private fun controller(dir: Path): DesktopProjectController = DesktopProjectController(
        dataDir = dir.toString(),
        store = SessionStore(JvmProductFileSystem(dir.toString())),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        clock = { 1_700_000_000_000 },
    )

    private fun model(cardId: String): InspectorCardModel {
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
    fun copies_border_params_and_text_to_selected_photos() = runBlocking {
        val dir = Files.createTempDirectory("fm-d1-copy-design-")
        val controller = controller(dir)
        try {
            controller.load()
            val report = controller.importPhotos(
                listOf(photo("ratio-study/4x3.jpg"), photo("vertical.jpg"), photo("p1170883.jpg")),
            )
            assertEquals(3, report.added)

            // Source photo: a card with a slider and a text override.
            val sourceId = controller.activePhotoId!!
            controller.applyCard("simple-1", "simple-1")
            controller.applyControlOverrides(
                model("simple-1"),
                mapOf("simple-1::footer" to 42f, "simple-1::showModel" to false),
            )
            val source = controller.project.designs[sourceId]!!
            assertEquals(42.0, source.params["footerPct"])
            assertEquals(false, source.text["showModel"])

            // Target photo: another card, its own watermark, no params/text.
            val targetId = controller.photos[1].id
            controller.selectPhoto(targetId)
            controller.applyCard("minimal-1", "minimal-1")
            controller.importWatermark(photo("root-import/水印_白色.png"))
            controller.updateWatermark(anchor = 2, width = 0.3f, opacity = 0.55f)
            val targetOverlay = controller.activeDesign!!.overlay
            assertNotNull(targetOverlay)

            val changed = controller.applyDesignFromPhoto(
                sourcePhotoId = sourceId,
                targetPhotoIds = listOf(targetId),
            )
            assertEquals(1, changed)
            val copied = controller.project.designs[targetId]!!
            assertEquals("simple-1", copied.cardId)
            assertEquals("simple-1", copied.styleId)
            assertEquals(source.params, copied.params)
            assertEquals(source.text, copied.text)
            // Target keeps its own signature: only border/params/text move.
            assertEquals(targetOverlay, copied.overlay)

            // Unsupported holders keep the default (backwards compatibility).
            assertEquals(-1, InMemoryProjectHolder().applyDesignFromPhoto(sourceId, listOf(targetId)))

            // No-op cases.
            assertEquals(0, controller.applyDesignFromPhoto(sourceId, listOf(sourceId)))
            assertEquals(0, controller.applyDesignFromPhoto(sourceId, emptyList()))

            // The copy is persisted now (saveNow inside the operation).
            assertTrue(controller.saveNow())
            controller.dispose()
            val reopened = controller(dir)
            try {
                reopened.load()
                val persisted = reopened.project.designs[targetId]!!
                assertEquals("simple-1", persisted.cardId)
                assertEquals(42.0, persisted.params["footerPct"])
                assertEquals(false, persisted.text["showModel"])
                assertNotNull(persisted.overlay)
                assertFalse(persisted.overlay == null)
            } finally {
                reopened.dispose()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
