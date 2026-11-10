package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Design → engine payload seams, plus the project/preset document round-trips that
 * share this file.
 *
 * WP-FIX2 deleted the dead `RenderRequest` / `RenderRequestFactory` wire model
 * (no production call site: the desktop bridge assembles its own request document,
 * the phone goes through `toPhoneSnapshot`). The five cases that used to assert that
 * model now assert the same invariants on the live seam they were standing in for:
 * `PhotoDesign.toDesignSnapshot()` (theme/ratio/fontScale/params/text/logo folding)
 * and `ProjectWatermarkSpec.toOverlaySpec()` (rect/opacity/angle). Cases that the live seam
 * keeps outside this file are covered by `EngineBridgeTest` / `ProjectDesignTest`.
 */
class DesignDocumentTest {
    @Test
    fun an_untouched_design_carries_no_optional_engine_keys() {
        val snapshot = PhotoDesign.base("simple-1", "simple-1").toDesignSnapshot()
        assertEquals("simple-1", snapshot.cardId)
        assertEquals("simple-1", snapshot.styleId)
        assertTrue(snapshot.params.isEmpty(), "未改动的设计不得注入 params：${snapshot.params}")
        assertTrue(snapshot.text.isEmpty(), "未改动的设计不得注入 text：${snapshot.text}")
        assertNull(snapshot.params["theme"])
        assertNull(snapshot.params["ratio"])
        assertNull(snapshot.text["fontScale"])
        assertNull(snapshot.logoBrand)
    }

    @Test
    fun optional_design_fields_are_folded_into_the_engine_payload() {
        val design = PhotoDesign.base("wm-classic", "wm-classic")
            .withTheme("white")
            .withRatio("4:5")
            .withFontScale(1.25)
            .withLogo("leica")
            .withParam("footerPct", 12.5)
            .withText("model", "X-T5")
        val snapshot = design.toDesignSnapshot()
        // theme / ratio are read by the engines from design.params; fontScale from design.text.
        assertEquals("white", snapshot.params["theme"])
        assertEquals("4:5", snapshot.params["ratio"])
        assertEquals(12.5, snapshot.params["footerPct"])
        assertEquals(1.25, snapshot.text["fontScale"])
        assertEquals("X-T5", snapshot.text["model"])
        assertEquals("leica", snapshot.logoBrand)
    }

    @Test
    fun blank_or_auto_logo_is_not_sent_to_the_engine() {
        assertNull(PhotoDesign.base("simple-1").withLogo("auto").toDesignSnapshot().logoBrand)
        assertNull(PhotoDesign.base("simple-1").withLogo("   ").toDesignSnapshot().logoBrand)
        assertEquals(
            "leica",
            PhotoDesign.base("simple-1").withLogo("leica").toDesignSnapshot().logoBrand,
        )
    }

    @Test
    fun watermark_overlay_carries_the_rect_opacity_and_angle() {
        val asset = WatermarkAsset.create("sig.png", "image/png", 800, 200, ByteArray(32) { it.toByte() })
        val spec = ProjectWatermarkSpec(asset, anchor = 8, width = 0.3f, opacity = 0.65f, rotation = 37f)
        val overlay = spec.toOverlaySpec("/tmp/sig.png", photoAspect = 1.5f)
        val rect = spec.rect(photoAspect = 1.5f)
        assertEquals("/tmp/sig.png", overlay.path)
        assertEquals(rect.x, overlay.x)
        assertEquals(rect.y, overlay.y)
        assertEquals(rect.w, overlay.w)
        assertEquals(rect.h, overlay.h)
        assertEquals(0.65f, overlay.opacity)
        assertEquals(37f, overlay.angle)
    }

    @Test
    fun watermark_rotation_outside_the_engine_limit_is_clamped() {
        val asset = WatermarkAsset.create("sig.png", "image/png", 800, 200, ByteArray(32) { it.toByte() })
        val clockwise = ProjectWatermarkSpec(asset, rotation = 270f)
            .toOverlaySpec("/tmp/sig.png", photoAspect = 1.5f)
        assertEquals(ProjectWatermarkSpec.MAX_ROTATION, clockwise.angle)
        val counterClockwise = ProjectWatermarkSpec(asset, rotation = -270f)
            .toOverlaySpec("/tmp/sig.png", photoAspect = 1.5f)
        assertEquals(-ProjectWatermarkSpec.MAX_ROTATION, counterClockwise.angle)
    }

    @Test
    fun consumers_write_onto_the_design_field_they_declare() {
        val design = PhotoDesign.base("simple-1", "simple-1")
            .applyConsumer("theme", "ink")
            .applyConsumer("ratio", "4:5")
            .applyConsumer("fontScale", 1.2)
            .applyConsumer("logo", "leica")
            .applyConsumer("params.footerPct", 12.0)
            .applyConsumer("params.slotText:b0/bottom0/g2", "自定义")
            .applyConsumer("text.showMake", false)

        assertEquals("ink", design.theme)
        assertEquals("4:5", design.ratio)
        assertEquals(1.2, design.fontScale)
        assertEquals("leica", design.logo)
        assertEquals(12.0, design.params["footerPct"])
        assertEquals("自定义", design.params["slotText:b0/bottom0/g2"])
        assertEquals(false, design.text["showMake"])
        assertFailsWith<IllegalArgumentException> { design.applyConsumer("slotText", "x") }
    }

    @Test
    fun design_optional_fields_survive_the_project_round_trip() {
        val fs = FakeFs()
        val store = SessionStore(fs)
        val design = PhotoDesign.base("wm-classic", "wm-classic")
            .withTheme("white")
            .withRatio("4:5")
            .withFontScale(1.25)
            .withLogo("leica")
            .withParam("footerPct", 12.0)
            .withOverlay(watermarkSpec(1))
        val project = Project(
            id = "p",
            photos = listOf(photoRef("a")),
            activePhotoId = "a",
            designs = mapOf("a" to design),
        )
        assertTrue(store.saveProject(project) is StoreResult.Ok)
        assertEquals(project, store.loadProject().ok())
    }

    @Test
    fun reset_to_base_keeps_ratio_and_watermark() {
        val design = PhotoDesign.base("wm-classic", "wm-classic")
            .withTheme("white")
            .withRatio("4:5")
            .withFontScale(1.25)
            .withLogo("leica")
            .withParam("footerPct", 12.0)
            .withOverlay(watermarkSpec(2))
        val reset = design.resetToBase()
        assertEquals("4:5", reset.ratio)
        assertEquals(watermarkSpec(2), reset.overlay)
        assertEquals(null, reset.theme)
        assertEquals(null, reset.fontScale)
        assertEquals(null, reset.logo)
        assertTrue(reset.params.isEmpty())
        assertTrue(reset.baseDesign)
    }

    @Test
    fun a_document_cannot_claim_default_while_carrying_a_theme() {
        val fs = FakeFs()
        fs.files[fs.projectPath] = """
            {"document":"framemark.project","schemaVersion":1,"id":"p","photos":[],"activePhotoId":null,
             "sharedDesign":{"cardId":"simple-1","baseDesign":true,"theme":"ink","params":{},"text":{}}}
        """.trimIndent().encodeToByteArray()
        val project = SessionStore(fs).loadProject().ok()
        assertEquals(false, project.sharedDesign!!.baseDesign)
        assertEquals("ink", project.sharedDesign!!.theme)
    }
}
