package com.framemark.native.product

import com.framemark.native.shell.toPhoneSnapshot
import com.framemark.native.workflow.EngineFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EngineBridgeTest {
    @Test
    fun design_snapshot_preserves_every_engine_design_field() {
        val snapshot = PhotoDesign.base("simple-1", "simple-1")
            .withTheme("ink")
            .withRatio("4:5")
            .withFontScale(1.25)
            .withLogo("leica")
            .withParam("footerPct", 12.5)
            .withText("model", "X-T5")
            .toDesignSnapshot()

        assertEquals("simple-1", snapshot.cardId)
        assertEquals("simple-1", snapshot.styleId)
        assertEquals(
            mapOf("footerPct" to 12.5, "theme" to "ink", "ratio" to "4:5"),
            snapshot.params,
        )
        assertEquals(mapOf("model" to "X-T5", "fontScale" to 1.25), snapshot.text)
        assertEquals("leica", snapshot.logoBrand)
    }

    @Test
    fun default_logo_stays_implicit_and_existing_engine_values_survive() {
        val snapshot = PhotoDesign.base("simple-1")
            .withParam("brandLogoFile", "legacy-mark")
            .withText("fontScale", 1.1)
            .withLogo("auto")
            .toDesignSnapshot()

        assertEquals("legacy-mark", snapshot.params["brandLogoFile"])
        assertEquals(1.1, snapshot.text["fontScale"])
        assertNull(snapshot.logoBrand)
    }

    @Test
    fun phone_single_render_matches_the_shared_conversion_and_keeps_legacy_logo_param() {
        val design = PhotoDesign.base("simple-1", "simple-1")
            .withTheme("ink")
            .withRatio("4:5")
            .withFontScale(1.2)
            .withLogo("leica")
            .withParam("footerPct", 12.5)
            .withText("model", "X-T5")
        val shared = design.toDesignSnapshot()

        assertEquals(
            shared.copy(params = shared.params + ("brandLogoFile" to "leica")),
            design.toPhoneSnapshot(),
        )
    }

    @Test
    fun only_null_photo_path_means_use_the_current_or_default_photo() {
        var checkedPath: String? = null
        assertNull(requireReadablePhotoOverride(null) { path ->
            checkedPath = path
            true
        })
        assertNull(checkedPath)

        val explicit = requireReadablePhotoOverride("/photos/a.jpg") { it == "/photos/a.jpg" }
        assertEquals("/photos/a.jpg", explicit)

        val missing = assertFailsWith<EngineFailure> {
            requireReadablePhotoOverride("/photos/missing.jpg") { false }
        }
        assertEquals("FM-E-INPUT-001", missing.code)

        val blank = assertFailsWith<EngineFailure> {
            requireReadablePhotoOverride("   ") { true }
        }
        assertEquals("FM-E-INPUT-001", blank.code)
    }
}
