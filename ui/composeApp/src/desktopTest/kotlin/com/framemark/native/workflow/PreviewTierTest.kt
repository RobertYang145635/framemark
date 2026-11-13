package com.framemark.native.workflow

import com.framemark.native.product.PreviewQuality
import com.framemark.native.product.PreviewTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The two stage-preview tiers are a pure request decision: while the pointer is
 * down the canvas is bounded and the source photo may be decoded smaller; the
 * frame after release goes back to the full-quality request. These tests pin the
 * decision without a renderer, so a future change cannot silently make the tiers
 * the same size (or make the final frame use a reduced decode).
 */
class PreviewTierTest {
    @Test
    fun dragging_uses_a_smaller_bounded_edge_than_release() {
        val final = PreviewTier.request(finalEdge = 1280, interactive = false)
        val drag = PreviewTier.request(finalEdge = 1280, interactive = true)

        assertEquals(PreviewQuality.Final, final.quality)
        assertEquals(PreviewQuality.Interactive, drag.quality)
        assertTrue(drag.edge < final.edge, "interactive edge must be smaller than the final edge")
        assertTrue(drag.edge <= PreviewTier.INTERACTIVE_MAX_EDGE, "interactive edge must stay bounded")
        assertEquals(480, drag.edge, "the measured 8192x6144 photo needs the 480 px tier")

        // Releasing switches back: the same call with interactive = false is the
        // final request, at the full canvas and with no reduced source decode.
        assertEquals(1280, final.edge)
        assertEquals(0, final.sourceEdge, "the final frame must keep the full-resolution source")
        assertEquals(PreviewTier.sourceEdge(480), drag.sourceEdge)
        assertEquals(960, drag.sourceEdge)
    }

    @Test
    fun a_small_stage_still_keeps_the_interactive_edge_below_the_final_edge() {
        // The final edge floor is 640 (the old PREVIEW_MIN_EDGE); the interactive
        // tier must still be smaller there.
        val final = PreviewTier.request(finalEdge = 640, interactive = false)
        val drag = PreviewTier.request(finalEdge = 640, interactive = true)
        assertEquals(640, final.edge)
        assertEquals(480, drag.edge)
        assertTrue(drag.edge < final.edge)
    }

    @Test
    fun final_edge_matches_the_old_adaptive_rule() {
        // Same numbers as the pre-tier adaptivePreviewEdge(): displayEdge * 1.25,
        // rounded and clamped to 640..1280.
        assertEquals(1280, PreviewTier.finalEdge(displayEdge = 1328, supersample = 1.25f))
        assertEquals(1280, PreviewTier.finalEdge(displayEdge = 2000, supersample = 1.25f))
        assertEquals(640, PreviewTier.finalEdge(displayEdge = 400, supersample = 1.25f))
        assertEquals(1000, PreviewTier.finalEdge(displayEdge = 800, supersample = 1.25f))
    }

    @Test
    fun the_two_tiers_describe_the_same_design() {
        val design = DesignSnapshot(
            cardId = "card-a",
            styleId = "style-a",
            params = linkedMapOf("inset" to 0.12f, "stockAnchor" to "centerBottom"),
            text = linkedMapOf("title" to "X-T5"),
            logoBrand = "leica",
        )
        val overlay = OverlaySpec("/watermarks/sign.png", 0.2725f, 0.4272f, 0.455f, 0.1456f, 0.8f)
        val finalKey = PreviewRequestKey.create(
            cardId = "card-a",
            design = design,
            photoPath = "/picked/photo.jpg",
            selectedPhotoPath = "/picked/photo.jpg",
            launchPhotoPath = null,
            photoSelectionVersion = 3,
            maxEdge = PreviewTier.request(1280, interactive = false).edge,
            overlay = overlay,
            designJsonOverride = null,
        )
        val interactiveKey = finalKey.withMaxEdge(
            PreviewTier.request(1280, interactive = true).edge,
        )

        // Only the preview size differs; card, style, params, text, logo, photo,
        // crop and watermark inputs are the same value in both requests.
        assertTrue(
            interactiveKey.sameStageInputs(finalKey),
            "the interactive tier must not change any design input",
        )
        assertNotEquals(interactiveKey, finalKey, "the tiers must be distinct cache entries")

        // The guard is real: a tier that changed a design value would be caught.
        val changed = PreviewRequestKey.create(
            cardId = "card-a",
            design = design.copy(params = design.params + ("inset" to 0.2f)),
            photoPath = "/picked/photo.jpg",
            selectedPhotoPath = "/picked/photo.jpg",
            launchPhotoPath = null,
            photoSelectionVersion = 3,
            maxEdge = PreviewTier.request(1280, interactive = true).edge,
            overlay = overlay,
            designJsonOverride = null,
        )
        assertFalse(changed.sameStageInputs(finalKey), "a geometry change must break the same-design check")
    }
}
