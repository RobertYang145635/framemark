package com.framemark.native.shell.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WP-P2 §4.2 首页入口路由: every entry of the new home decides its destination from
 * the project state alone, so the rules are checked here instead of on a device.
 */
class PhoneHomePolicyTest {

    @Test
    fun heroAlwaysPicksPhotos() {
        assertEquals(PhoneHomeDestination.PickPhotos, PhoneHomePolicy.hero())
    }

    @Test
    fun batchOnAnEmptyProjectPicksPhotos() {
        assertEquals(PhoneHomeDestination.PickPhotos, PhoneHomePolicy.batch(photoCount = 0))
    }

    @Test
    fun batchWithPhotosOpensTheBatchExport() {
        assertEquals(PhoneHomeDestination.BatchExport, PhoneHomePolicy.batch(photoCount = 1))
        assertEquals(PhoneHomeDestination.BatchExport, PhoneHomePolicy.batch(photoCount = 12))
    }

    @Test
    fun presetsNeedAPhotoFirst() {
        assertEquals(PhoneHomeDestination.PickPhotosFirst, PhoneHomePolicy.presets(photoCount = 0))
        assertEquals(PhoneHomeDestination.Presets, PhoneHomePolicy.presets(photoCount = 3))
    }

    @Test
    fun gearOpensSettings() {
        assertEquals(PhoneHomeDestination.Settings, PhoneHomePolicy.settings())
    }

    @Test
    fun recentRowOpensTheEditorAndItsAbsenceFallsBackToThePicker() {
        assertEquals(PhoneHomeDestination.Editor, PhoneHomePolicy.recent(rowExists = true))
        assertEquals(PhoneHomeDestination.PickPhotos, PhoneHomePolicy.recent(rowExists = false))
    }

    @Test
    fun recentKeepsTheNewestFirstAndCapsAtTheLimit() {
        val rows = (1..5).map { index ->
            PhoneRecentEdit(photoId = "p$index", path = "C:/p$index.jpg", displayName = "P$index.jpg")
        }
        assertEquals(PhoneHomePolicy.RECENT_LIMIT, PhoneHomePolicy.recentEdits(rows).size)
        assertEquals(listOf("p5", "p4", "p3"), PhoneHomePolicy.recentEdits(rows).map { it.photoId })
        // A shorter list is returned as is, in reverse order.
        assertEquals(listOf("p2", "p1"), PhoneHomePolicy.recentEdits(rows.take(2)).map { it.photoId })
        assertTrue(PhoneHomePolicy.recentEdits(emptyList()).isEmpty())
    }

    @Test
    fun recentSubtitleAlwaysSaysSomething() {
        val withBorder = PhoneRecentEdit("p1", "C:/p1.jpg", "P1070512.jpg", borderName = "经典双行", index = 2, count = 3)
        assertEquals("经典双行 · 2/3", withBorder.subtitle())
        val noBorder = PhoneRecentEdit("p2", null, "IMG_0001.jpg")
        assertEquals("未应用边框", noBorder.subtitle())
        val single = PhoneRecentEdit("p3", "C:/p3.jpg", "P3.jpg", borderName = "白边", index = 1, count = 1)
        assertEquals("白边", single.subtitle())
        val blankBorder = PhoneRecentEdit("p4", "C:/p4.jpg", "P4.jpg", borderName = "   ", index = 1, count = 1)
        assertEquals("未应用边框", blankBorder.subtitle())
    }

    @Test
    fun thePickFirstHintIsTheCopyTheShellShows() {
        assertEquals("先选择照片，再使用这一项。", PhoneHomePolicy.PICK_PHOTOS_FIRST_HINT)
    }
}
