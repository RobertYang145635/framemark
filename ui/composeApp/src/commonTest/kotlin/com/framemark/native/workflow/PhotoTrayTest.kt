package com.framemark.native.workflow

import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoTrayTest {
    @Test
    fun adopted_photo_uses_project_display_name_and_keeps_internal_path() {
        val tray = PhotoTray()
        val path = "/private/app/Pictures/Originals/user-123.jpg"

        val added = tray.adopt(listOf(path), path, mapOf(path to "原始照片.jpg"))

        assertEquals(1, added)
        assertEquals(path, tray.selected?.path)
        assertEquals("原始照片.jpg", tray.selected?.label)
    }

    @Test
    fun updating_same_path_label_does_not_add_or_replace_selected_entry() {
        val tray = PhotoTray()
        val path = "/private/app/Pictures/Originals/user-123.jpg"
        tray.adopt(listOf(path), path, mapOf(path to "原始照片.jpg"))
        val originalEntry = tray.selected!!

        val added = tray.adopt(listOf(path), path, mapOf(path to "改名后的照片.jpg"))

        assertEquals(0, added)
        assertEquals(1, tray.count)
        assertEquals(originalEntry.id, tray.selected?.id)
        assertEquals("改名后的照片.jpg", tray.selected?.label)
    }

    @Test
    fun legacy_adopt_call_falls_back_to_path_basename() {
        val tray = PhotoTray()
        val path = "C:\\qa\\user-123.jpg"

        tray.adopt(listOf(path), path)

        assertEquals(path, tray.selected?.path)
        assertEquals("user-123.jpg", tray.selected?.label)
    }

    @Test
    fun updating_labels_from_project_does_not_change_selection() {
        val tray = PhotoTray()
        val firstPath = "/private/app/user-1.jpg"
        val secondPath = "/private/app/user-2.jpg"
        tray.adopt(listOf(firstPath, secondPath), firstPath)
        val selectedId = tray.selected?.id

        tray.updateDisplayNames(mapOf(firstPath to "photo-one.jpg", secondPath to "photo-two.jpg"))

        assertEquals(selectedId, tray.selected?.id)
        assertEquals("photo-one.jpg", tray.selected?.label)
        assertEquals("photo-two.jpg", tray.items[1].label)
    }
}
