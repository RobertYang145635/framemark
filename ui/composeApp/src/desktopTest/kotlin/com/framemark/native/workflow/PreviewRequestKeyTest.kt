package com.framemark.native.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PreviewRequestKeyTest {
    private fun design(
        params: Map<String, Any?> = emptyMap(),
        text: Map<String, Any?> = emptyMap(),
        styleId: String? = "style-a",
        cardId: String = "card-a",
        logoBrand: String? = "brand-a",
    ) = DesignSnapshot(cardId, styleId, params, text, logoBrand)

    private fun key(
        design: DesignSnapshot = design(),
        cardId: String = "card-a",
        photoPath: String? = "/picked/photo.jpg",
        selectedPhotoPath: String? = "/picked/photo.jpg",
        launchPhotoPath: String? = null,
        photoSelectionVersion: Int = 1,
        maxEdge: Int = 1280,
        overlay: OverlaySpec? = null,
        designJsonOverride: String? = null,
        userLogoRevision: Int = 0,
    ) = PreviewRequestKey.create(
        cardId = cardId,
        design = design,
        photoPath = photoPath,
        selectedPhotoPath = selectedPhotoPath,
        launchPhotoPath = launchPhotoPath,
        photoSelectionVersion = photoSelectionVersion,
        maxEdge = maxEdge,
        overlay = overlay,
        designJsonOverride = designJsonOverride,
        userLogoRevision = userLogoRevision,
    )

    @Test
    fun user_logo_library_revision_invalidates_the_preview_key() {
        val before = key(design = design(logoBrand = "user:ul-1"), userLogoRevision = 1)
        val after = key(design = design(logoBrand = "user:ul-1"), userLogoRevision = 2)
        assertNotEquals(before, after, "deleting/importing user logos must invalidate cached previews")
    }

    @Test
    fun full_design_values_distinguish_real_string_hash_collisions() {
        val aa = "Aa"
        val bb = "BB"
        assertEquals(aa.hashCode(), bb.hashCode(), "test requires a real JVM String hash collision")

        val first = key(design = design(params = mapOf("title" to aa)))
        val second = key(design = design(params = mapOf("title" to bb)))

        assertNotEquals(first, second, "complete design values must participate in request equality")

        val cache = PreviewRenderCache()
        val firstBytes = byteArrayOf(1, 2, 3)
        val secondBytes = byteArrayOf(4, 5, 6)
        cache.put(first, firstBytes, ImageRect(1f, 2f, 3f, 4f), emptyList())
        cache.put(second, secondBytes, ImageRect(5f, 6f, 7f, 8f), emptyList())

        assertEquals(listOf<Byte>(1, 2, 3), cache.get(first)?.bytes?.toList())
        assertEquals(ImageRect(1f, 2f, 3f, 4f), cache.get(first)?.photoRect)
        assertEquals(listOf<Byte>(4, 5, 6), cache.get(second)?.bytes?.toList())
        assertEquals(ImageRect(5f, 6f, 7f, 8f), cache.get(second)?.photoRect)
    }

    @Test
    fun key_captures_every_stage_render_input() {
        val baseDesign = design(
            params = mapOf("ratio" to "4:5", "margin" to 0.1f),
            text = mapOf("title" to "X-T5"),
        )
        val baseOverlay = OverlaySpec("/watermarks/a.png", 0.1f, 0.2f, 0.3f, 0.4f, 0.8f)
        val base = key(design = baseDesign, overlay = baseOverlay)

        val variants = listOf(
            key(design = baseDesign.copy(params = baseDesign.params + ("margin" to 0.2f)), overlay = baseOverlay),
            key(design = baseDesign.copy(text = mapOf("title" to "X-T6")), overlay = baseOverlay),
            key(design = baseDesign.copy(styleId = "style-b"), overlay = baseOverlay),
            key(design = baseDesign.copy(logoBrand = "brand-b"), overlay = baseOverlay),
            key(design = baseDesign, cardId = "card-b", overlay = baseOverlay),
            key(design = baseDesign, photoPath = "/picked/other.jpg", overlay = baseOverlay),
            key(design = baseDesign, selectedPhotoPath = "/picked/other.jpg", overlay = baseOverlay),
            key(design = baseDesign, launchPhotoPath = "/qa/other.jpg", overlay = baseOverlay),
            key(design = baseDesign, photoSelectionVersion = 2, overlay = baseOverlay),
            key(design = baseDesign, maxEdge = 1024, overlay = baseOverlay),
            key(design = baseDesign, overlay = baseOverlay.copy(x = 0.15f)),
            key(design = baseDesign, overlay = baseOverlay.copy(angle = 45f)),
            key(design = baseDesign, designJsonOverride = "{\"design\":{\"text\":{\"title\":\"QA\"}}}"),
        )

        variants.forEachIndexed { index, variant ->
            assertNotEquals(base, variant, "render input variant $index was omitted from the key")
        }
    }

    @Test
    fun key_and_render_design_are_snapshots_of_mutable_maps() {
        val nested = linkedMapOf<String, Any?>("label" to "before")
        val mutableParams = linkedMapOf<String, Any?>("nested" to nested, "ratio" to "original")
        val source = design(params = mutableParams)
        val capturedDesign = snapshotPreviewDesign(source)
        val capturedKey = key(design = capturedDesign)

        nested["label"] = "after"
        mutableParams["ratio"] = "4:5"

        assertEquals("before", (capturedDesign.params["nested"] as Map<*, *>)["label"])
        assertEquals("original", capturedDesign.params["ratio"])
        assertEquals(capturedKey, key(design = capturedDesign))
        assertNotEquals(capturedKey, key(design = snapshotPreviewDesign(source)))
    }

    @Test
    fun cached_bytes_and_photo_rect_are_returned_from_one_entry_and_copied() {
        val cache = PreviewRenderCache()
        val request = key()
        val sourceBytes = byteArrayOf(10, 20)
        val rect = ImageRect(11f, 12f, 13f, 14f)
        cache.put(request, sourceBytes, rect, emptyList())
        sourceBytes[0] = 99

        val result = cache.get(request) ?: error("expected cached render")
        assertEquals(listOf<Byte>(10, 20), result.bytes.toList())
        assertEquals(rect, result.photoRect)

        result.bytes[0] = 88
        assertEquals(listOf<Byte>(10, 20), cache.get(request)?.bytes?.toList())
    }

    @Test
    fun late_success_and_error_are_rejected_but_the_final_request_can_publish() {
        val gate = PreviewRequestGate(key())
        val initial = key()
        val intermediate = key(design = design(text = mapOf("title" to "intermediate")))
        val final = key(design = design(text = mapOf("title" to "final")))
        val published = mutableListOf<String>()

        gate.update(intermediate)
        gate.update(final)

        assertFalse(gate.publishIfCurrent(initial) { published += "late initial success" })
        assertFalse(gate.publishIfCurrent(intermediate) { published += "late intermediate error" })
        assertTrue(gate.publishIfCurrent(final) { published += "final result" })
        assertEquals(listOf("final result"), published)
    }
    @Test
    fun failure_slot_identity_is_scoped_to_card_and_photo_only() {
        val base = key(photoPath = null, selectedPhotoPath = "/picked/photo.jpg", launchPhotoPath = null)
        assertTrue(
            base.sameTextSlotInputs(
                key(
                    design = design(params = mapOf("title" to "changed"), text = mapOf("date" to "changed")),
                    photoPath = null,
                    selectedPhotoPath = "/picked/photo.jpg",
                    launchPhotoPath = null,
                    maxEdge = 640,
                    overlay = OverlaySpec("/watermarks/a.png", 0.1f, 0.2f, 0.3f, 0.4f, 1f),
                ),
            ),
        )
        assertFalse(base.sameTextSlotInputs(key(cardId = "card-b", photoPath = null)))
        assertFalse(base.sameTextSlotInputs(key(design = design(cardId = "card-b"), photoPath = null)))
        assertFalse(base.sameTextSlotInputs(key(photoPath = "/picked/other.jpg", selectedPhotoPath = "/picked/photo.jpg")))
        assertFalse(base.sameTextSlotInputs(key(photoPath = null, selectedPhotoPath = "/picked/other.jpg")))
        assertFalse(base.sameTextSlotInputs(key(photoPath = null, selectedPhotoPath = "/picked/photo.jpg", launchPhotoPath = "/qa/other.jpg")))
        assertFalse(base.sameTextSlotInputs(key(photoPath = null, selectedPhotoPath = "/picked/photo.jpg", photoSelectionVersion = 2)))
    }

}
