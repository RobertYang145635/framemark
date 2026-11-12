package com.framemark.native.shell.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A frame tile that renders nothing is worse than a tile showing a stranger's
 * sample: the user cannot tell the frames apart at all. These cases pin when the
 * grid falls back to the bundled photo.
 */
class PhoneBorderPreviewTest {

    @Test
    fun a_measured_photo_is_used() {
        assertEquals(
            "/photos/me.jpg",
            PhoneBorderPreview.photoPath(null, "/photos/me.jpg", 6000, 4000, "/sample.jpg"),
        )
    }

    @Test
    fun a_photo_without_a_readable_size_falls_back_to_the_sample() {
        // A file whose header could not be read measures 0x0 and renders as an
        // empty tile; this is the reported "预览什么都没有" case.
        assertEquals(
            "/sample.jpg",
            PhoneBorderPreview.photoPath(null, "/photos/broken.heic", 0, 0, "/sample.jpg"),
        )
        assertEquals(
            "/sample.jpg",
            PhoneBorderPreview.photoPath(null, "/photos/broken.heic", 6000, 0, "/sample.jpg"),
        )
    }

    @Test
    fun a_missing_photo_uses_the_sample() {
        assertEquals("/sample.jpg", PhoneBorderPreview.photoPath(null, null, 0, 0, "/sample.jpg"))
        assertEquals("/sample.jpg", PhoneBorderPreview.photoPath(null, "  ", 0, 0, "/sample.jpg"))
    }

    @Test
    fun nothing_to_show_stays_null_instead_of_inventing_a_path() {
        assertNull(PhoneBorderPreview.photoPath(null, null, 0, 0, null))
        assertNull(PhoneBorderPreview.photoPath(null, null, 0, 0, "   "))
    }

    @Test
    fun the_desktop_active_photo_wins() {
        assertEquals(
            "/photos/desktop.jpg",
            PhoneBorderPreview.photoPath("/photos/desktop.jpg", "/photos/phone.jpg", 10, 10, "/sample.jpg"),
        )
    }
}
