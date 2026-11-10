package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * P1-1 rename freeze: `product.Preset` / `product.WatermarkSpec` are now
 * `product.ProjectPreset` / `product.ProjectWatermarkSpec`, and the persisted format
 * must stay byte-for-byte what the old build wrote.
 *
 * Both literals below were captured from the pre-rename build (d4c432c) by running
 * `PresetCodec.encodeDocument(...)` / `ProjectCodec.encode(...)` on [frozenPreset] /
 * [frozenProject]. The codec is a hand-written JSON writer keyed on literal field
 * names plus the `framemark.*` document ids, so no Kotlin type name reaches the file;
 * these fixtures prove that. If the rename — or any future `@SerialName`-style change —
 * altered a key, decoding would fail or the re-encoded bytes would differ, and an
 * existing user project or exported `.fmpreset` would stop loading.
 */
class WireFormatFreezeTest {
    @Test
    fun project_document_written_before_the_rename_still_decodes() {
        val decoded = ProjectCodec.decode(ProductJson.parseRoot(PROJECT_DOCUMENT))

        assertEquals("proj-1", decoded.id)
        assertEquals("a", decoded.activePhotoId)
        assertEquals("simple-4", decoded.selectedCardId)
        assertEquals(100, decoded.createdAt)
        assertEquals(200, decoded.updatedAt)
        assertEquals(1, decoded.photos.size)
        val photo = decoded.photos.single()
        assertEquals("a", photo.id)
        assertEquals("/p/a.jpg", photo.path)
        assertEquals("a.jpg", photo.displayName)
        assertEquals(6000, photo.widthPx)
        assertEquals(4000, photo.heightPx)
        assertEquals("fp-a", photo.fingerprint)

        // The embedded watermark is the part the rename touched: every field of the
        // old `product.WatermarkSpec` must still arrive under its old JSON key.
        val overlay = decoded.designs.getValue("a").overlay
        assertNotNull(overlay)
        assertFrozenOverlayFields(overlay)
        assertEquals("paper", decoded.designs.getValue("a").theme)
        assertEquals("4:5", decoded.designs.getValue("a").ratio)
        assertEquals("leica", decoded.designs.getValue("a").logo)
        assertEquals(1.1, decoded.designs.getValue("a").fontScale)

        val shared = assertNotNull(decoded.sharedDesign)
        assertEquals("glass-white-1", shared.cardId)
        assertNull(shared.overlay, "共享设计不得携带水印")
        assertEquals(0.12, shared.params["inset"])

        assertEquals(ExportFormat.Jpeg, decoded.exportSettings.format)
        assertEquals(ExportSizeMode.LongEdge, decoded.exportSettings.sizeMode)
        assertEquals(6000, decoded.exportSettings.longEdge)

        // Byte-identical re-encode: the rename must not move a single key or reorder one.
        assertEquals(PROJECT_DOCUMENT, ProjectCodec.encode(decoded))
    }

    @Test
    fun preset_document_written_before_the_rename_still_decodes() {
        val decoded = PresetCodec.decodeDocument(ProductJson.parseRoot(PRESET_DOCUMENT))

        assertEquals("preset-1", decoded.id)
        assertEquals("我的签名", decoded.name)
        assertEquals(10, decoded.createdAt)
        assertEquals(20, decoded.updatedAt)
        assertEquals("simple-4", decoded.design.cardId)
        assertEquals("paper", decoded.design.theme)
        assertEquals("4:5", decoded.design.ratio)
        assertEquals(1.1, decoded.design.fontScale)
        assertEquals("leica", decoded.design.logo)
        assertEquals(0.1, decoded.design.params["inset"])
        assertEquals("X-T5", decoded.design.text["model"])
        assertFrozenOverlayFields(assertNotNull(decoded.design.overlay))

        // A portable preset deliberately drops the machine-local JPEG quality.
        assertEquals(100, decoded.exportSettings.quality)
        assertEquals(ExportFormat.Jpeg, decoded.exportSettings.format)
        assertEquals(ExportSizeMode.LongEdge, decoded.exportSettings.sizeMode)
        assertEquals(6000, decoded.exportSettings.longEdge)
        assertEquals("{photo}-{index}", decoded.exportSettings.filenamePattern)

        assertEquals(PRESET_DOCUMENT, PresetCodec.encodeDocument(decoded))
        assertEquals(frozenPreset(), decoded)
    }

    private fun assertFrozenOverlayFields(overlay: ProjectWatermarkSpec) {
        assertEquals(8, overlay.anchor)
        assertEquals(0.3f, overlay.width)
        assertEquals(0.65f, overlay.opacity)
        assertEquals(0.125f, overlay.dx)
        assertEquals(-0.0625f, overlay.dy)
        assertEquals(33f, overlay.rotation)
        assertEquals(1.25f, overlay.photoAspect)
        assertEquals("sig.png", overlay.asset.name)
        assertEquals("image/png", overlay.asset.mimeType)
        assertEquals(8, overlay.asset.widthPx)
        assertEquals(4, overlay.asset.heightPx)
        assertEquals("47e4ee7f211f73265dd17658f6e21c1318bd6c81f37598e20a2756299542efcf", overlay.asset.sha256)
        assertContentEquals(ByteArray(9) { (it + 1).toByte() }, overlay.asset.bytes)
    }

    private fun frozenAsset(): WatermarkAsset =
        WatermarkAsset.create("sig.png", "image/png", 8, 4, ByteArray(9) { (it + 1).toByte() })

    private fun frozenOverlay(): ProjectWatermarkSpec = ProjectWatermarkSpec(
        asset = frozenAsset(),
        anchor = 8,
        width = 0.3f,
        opacity = 0.65f,
        dx = 0.125f,
        dy = -0.0625f,
        rotation = 33f,
        photoAspect = 1.25f,
    )

    private fun frozenDesign(): PhotoDesign = PhotoDesign.base("simple-4")
        .withParam("inset", 0.1)
        .withText("model", "X-T5")
        .withTheme("paper")
        .withRatio("4:5")
        .withFontScale(1.1)
        .withLogo("leica")
        .withOverlay(frozenOverlay())

    private fun frozenPreset(): ProjectPreset = ProjectPreset(
        id = "preset-1",
        name = "我的签名",
        design = frozenDesign(),
        exportSettings = ExportSettings(
            format = ExportFormat.Jpeg,
            quality = 100,
            sizeMode = ExportSizeMode.LongEdge,
            longEdge = 6000,
        ),
        createdAt = 10,
        updatedAt = 20,
    )

    private fun frozenProject(): Project = Project(
        id = "proj-1",
        photos = listOf(
            PhotoRef(
                id = "a",
                path = "/p/a.jpg",
                displayName = "a.jpg",
                widthPx = 6000,
                heightPx = 4000,
                fingerprint = "fp-a",
            ),
        ),
        activePhotoId = "a",
        designs = mapOf("a" to frozenDesign()),
        sharedDesign = PhotoDesign.base("glass-white-1").withParam("inset", 0.12),
        selectedCardId = "simple-4",
        exportSettings = ExportSettings(sizeMode = ExportSizeMode.LongEdge, longEdge = 6000),
        createdAt = 100,
        updatedAt = 200,
    )

    private companion object {
        /** Captured from d4c432c (pre-rename) via `ProjectCodec.encode(frozenProject())`. */
        const val PROJECT_DOCUMENT =
            """{"document":"framemark.project","schemaVersion":1,"id":"proj-1","activePhotoId":"a","selectedCardId":"simple-4","createdAt":100,"updatedAt":200,"export":{"format":"jpeg","quality":100,"sizeMode":"longEdge","longEdge":6000,"filenamePattern":"{photo}-{index}","webpLossless":true},"photos":[{"id":"a","path":"/p/a.jpg","displayName":"a.jpg","orientation":1,"widthPx":6000,"heightPx":4000,"fingerprint":"fp-a"}],"designs":{"a":{"cardId":"simple-4","styleId":null,"theme":"paper","ratio":"4:5","fontScale":1.1,"logo":"leica","baseDesign":false,"params":{"inset":0.1},"text":{"model":"X-T5"},"overlay":{"anchor":8,"width":0.3,"opacity":0.65,"dx":0.125,"dy":-0.0625,"rotation":33,"photoAspect":1.25,"asset":{"name":"sig.png","mimeType":"image/png","widthPx":8,"heightPx":4,"sha256":"47e4ee7f211f73265dd17658f6e21c1318bd6c81f37598e20a2756299542efcf","data":"AQIDBAUGBwgJ"}}}},"sharedDesign":{"cardId":"glass-white-1","styleId":null,"baseDesign":false,"params":{"inset":0.12},"text":{},"overlay":null}}"""

        /** Captured from d4c432c (pre-rename) via `PresetCodec.encodeDocument(frozenPreset())`. */
        const val PRESET_DOCUMENT =
            """{"document":"framemark.preset","schemaVersion":1,"preset":{"id":"preset-1","name":"我的签名","createdAt":10,"updatedAt":20,"design":{"cardId":"simple-4","styleId":null,"theme":"paper","ratio":"4:5","fontScale":1.1,"logo":"leica","baseDesign":false,"params":{"inset":0.1},"text":{"model":"X-T5"},"overlay":{"anchor":8,"width":0.3,"opacity":0.65,"dx":0.125,"dy":-0.0625,"rotation":33,"photoAspect":1.25,"asset":{"name":"sig.png","mimeType":"image/png","widthPx":8,"heightPx":4,"sha256":"47e4ee7f211f73265dd17658f6e21c1318bd6c81f37598e20a2756299542efcf","data":"AQIDBAUGBwgJ"}}},"export":{"format":"jpeg","sizeMode":"longEdge","longEdge":6000,"filenamePattern":"{photo}-{index}","webpLossless":true}}}"""
    }
}
