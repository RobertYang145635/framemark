package com.framemark.native.workflow

import com.framemark.native.product.samplePreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PresetTransferTest {
    @Test
    fun rejects_a_document_with_tampered_embedded_watermark() {
        val preset = samplePreset()
        val document = PresetTransfer.encodeDocument(preset)
        val originalHash = preset.design.overlay!!.asset.sha256
        val corrupted = document.replace(originalHash, "0".repeat(64))

        assertNotEquals(document, corrupted)
        assertFailsWith<Exception> {
            PresetTransfer.decodeDocument(corrupted, setOf(preset.design.cardId))
        }
    }

    @Test
    fun rejects_a_preset_that_references_an_unknown_card() {
        val original = samplePreset()
        val preset = original.copy(design = original.design.copy(cardId = "removed-card"))

        assertFailsWith<IllegalArgumentException> {
            PresetTransfer.decodeDocument(PresetTransfer.encodeDocument(preset), setOf("simple-4"))
        }
    }

    @Test
    fun save_as_copy_assigns_an_unoccupied_id_without_changing_the_preset() {
        val preset = samplePreset()
        val occupiedIds = setOf(preset.id, "another-existing-id")

        val copy = PresetTransfer.copyWithNewId(preset, occupiedIds)

        assertNotEquals(preset.id, copy.id)
        assertFalse(copy.id in occupiedIds)
        assertTrue(copy.id.startsWith("import-"))
        assertEquals(preset.copy(id = copy.id), copy)
    }
}
