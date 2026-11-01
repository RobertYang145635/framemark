package com.framemark.native.product

import com.framemark.native.workflow.ExportNaming
import kotlin.test.Test
import kotlin.test.assertEquals

class ExportNamingTest {
    @Test
    fun photo_name_is_kept_and_sanitised() {
        assertEquals("IMG_0042", ExportNaming.photoStem("/photos/2026/IMG_0042.JPG"))
        assertEquals("水印", ExportNaming.photoStem("C:\\照片\\水印.png"))
        assertEquals(null, ExportNaming.photoStem(null))
    }

    @Test
    fun the_default_pattern_keeps_the_previous_file_name() {
        val name = ExportNaming.previewName("{name}-{date}", "极简 01", null)
        assertEquals(true, name.startsWith("极简 01-"))
        assertEquals(false, name.contains("{"))
    }

    @Test
    fun the_photo_placeholder_uses_the_source_stem() {
        val name = ExportNaming.previewName("{photo}-{name}", "边框", "IMG_0042")
        assertEquals("IMG_0042-边框", name)
        val without = ExportNaming.previewName("{name}{photo}", "边框", null)
        assertEquals("边框", without)
    }

    @Test
    fun illegal_characters_are_replaced() {
        val name = ExportNaming.previewName("{name}", "a/b:c", null)
        assertEquals("a-b-c", name)
    }
}
