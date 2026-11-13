package com.framemark.native.workflow

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UI copy audit 2026-11-13, leak 1: the phone showed the name of its **own** import
 * copy instead of the picked photo.
 *
 * `TrayPhoto.label` used to fall back to the path basename and `PhotoThumbnail` used
 * the same basename as its `contentDescription`, so a picture the user had imported
 * as `IMG_1234.jpg` read as `user-1794418347151-0.jpg` in the top bar — and TalkBack
 * announced that cache name for the strip thumbnail. The rendered-text sweep over the
 * 4 658 device dumps found 87 such nodes (`user-1794418347151-0.jpg` ×28,
 * `user-1794317514761-0.jpg` ×17, `photo.png` ×21, …).
 */
class PhotoTrayLabelTest {

    private val data = "/data/user/0/com.framemark.native.dev/files/photos"

    @Test
    fun a_missing_display_name_never_falls_back_to_the_app_copy() {
        assertEquals("照片", photoLabel("$data/user-1794418347151-0.jpg", null))
        assertEquals("照片", photoLabel("$data/user-1794317514761-0.jpg", ""))
        assertEquals("照片", photoLabel("$data/user-1794422141948-0.png", "   "))
    }

    @Test
    fun the_engine_copy_names_are_internal_too() {
        assertEquals("照片", photoLabel("$data/photo.png", null))
        assertEquals("照片", photoLabel("$data/exif.jpg", null))
        assertEquals("照片", photoLabel("C:\\data\\photos\\photo.png", null))
    }

    @Test
    fun the_picked_file_name_still_shows() {
        // hdr.jpg is the evidence rig's file name; a user's own name behaves the same.
        assertEquals("hdr.jpg", photoLabel("$data/user-1794418347151-0.jpg", "hdr.jpg"))
        assertEquals("IMG_1234.jpg", photoLabel("/sdcard/DCIM/IMG_1234.jpg", null))
        assertEquals("FMTEST_p1170883.jpg", photoLabel("/sdcard/DCIM/FMTEST_p1170883.jpg", null))
    }

    @Test
    fun the_bundled_sample_is_named_in_words() {
        assertEquals("示例照片", photoLabel(null, null))
    }

    @Test
    fun a_tray_entry_uses_the_same_wording_as_the_mapping() {
        val entry = TrayPhoto(id = 1, path = "$data/user-1794216824217-0.jpg")
        assertEquals("照片", entry.label)
        entry.updateDisplayName("假期.jpg")
        assertEquals("假期.jpg", entry.label)
    }
}
