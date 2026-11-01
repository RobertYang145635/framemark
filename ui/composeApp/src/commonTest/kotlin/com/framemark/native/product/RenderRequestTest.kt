package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RenderRequestTest {
    private val photo = PhotoRef(
        id = "p1",
        path = "/photos/a.jpg",
        displayName = "a.jpg",
        widthPx = 6000,
        heightPx = 4000,
    )

    @Test
    fun an_untouched_design_produces_the_pre_p1b_request_shape() {
        val design = PhotoDesign.base("simple-1", "simple-1")
        val request = RenderRequestFactory.build(design, photo, RenderMode.Preview, maxEdge = 1024)
        assertEquals(
            """{"schemaVersion":1,"design":{"cardId":"simple-1","styleId":"simple-1","params":{},"text":{}},""" +
                """"photo":{"path":"/photos/a.jpg","w":6000,"h":4000},"mode":"preview","maxEdge":1024}""",
            request.toJson(),
        )
    }

    @Test
    fun optional_design_fields_are_written_only_when_set() {
        val design = PhotoDesign.base("wm-classic", "wm-classic")
            .withTheme("white")
            .withRatio("4:5")
            .withFontScale(1.25)
            .withLogo("leica")
            .withParam("footerPct", 12.5)
            .withText("model", "X-T5")
        val json = RenderRequestFactory.build(design, photo, RenderMode.Export).toJson()
        assertTrue(json.contains("\"theme\":\"white\""))
        assertTrue(json.contains("\"ratio\":\"4:5\""))
        assertTrue(json.contains("\"fontScale\":1.25"))
        assertTrue(json.contains("\"logo\":\"leica\""))
        assertTrue(json.contains("\"params\":{\"footerPct\":12.5}"))
        assertTrue(json.contains("\"text\":{\"model\":\"X-T5\"}"))
        assertTrue(!json.contains("maxEdge"), "export 请求不得带 maxEdge")
    }

    @Test
    fun request_carries_the_watermark_rect_and_opacity() {
        val asset = WatermarkAsset.create("sig.png", "image/png", 800, 200, ByteArray(32) { it.toByte() })
        val spec = WatermarkSpec(asset, anchor = 8, width = 0.3f, opacity = 0.65f)
        val design = PhotoDesign.base("simple-1", "simple-1").withOverlay(spec)
        val request = RenderRequestFactory.build(
            design = design,
            photo = photo,
            mode = RenderMode.Preview,
            maxEdge = 512,
            overlayPath = "/tmp/sig.png",
            photoAspect = 1.5f,
        )
        val rect = spec.rect(1.5f)
        val json = request.toJson()
        assertTrue(json.contains("\"overlay\":{\"path\":\"/tmp/sig.png\""))
        assertTrue(json.contains("\"x\":${ProductJson.number(rect.x)}"))
        assertTrue(json.contains("\"opacity\":0.65"))
    }

    @Test
    fun a_watermark_without_a_materialised_file_is_rejected() {
        val asset = WatermarkAsset.create("sig.png", "image/png", 10, 10, ByteArray(8))
        val design = PhotoDesign.base("simple-1", "simple-1").withOverlay(WatermarkSpec(asset))
        assertFailsWith<IllegalArgumentException> {
            RenderRequestFactory.build(design, photo, RenderMode.Preview, maxEdge = 512, photoAspect = 1.5f)
        }
    }

    @Test
    fun invalid_requests_fail_loudly() {
        val design = PhotoDesign.base("simple-1", "simple-1")
        assertFailsWith<IllegalArgumentException> {
            RenderRequestFactory.build(design.copy(styleId = null), photo, RenderMode.Preview)
        }
        assertFailsWith<IllegalArgumentException> {
            RenderRequestFactory.build(design, photo.copy(widthPx = 0), RenderMode.Preview)
        }
        assertFailsWith<IllegalArgumentException> {
            RenderRequestFactory.build(design, photo, RenderMode.Export, maxEdge = 1024)
        }
        assertFailsWith<IllegalArgumentException> {
            RenderRequestFactory.build(design, photo, RenderMode.Preview, maxEdge = 32)
        }
        assertFailsWith<IllegalArgumentException> {
            RenderRequestFactory.build(design.withFontScale(0.0), photo, RenderMode.Preview)
        }
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
