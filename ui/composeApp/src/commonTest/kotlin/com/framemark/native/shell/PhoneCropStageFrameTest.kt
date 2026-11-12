package com.framemark.native.shell

import com.framemark.native.product.PhotoCrop
import com.framemark.native.shell.phone.cropApplies
import com.framemark.native.shell.phone.cropFrameBounds
import com.framemark.native.shell.phone.cropStageFrame
import com.framemark.native.workflow.ImageRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stage half of 裁切: where the photo is drawn, where the keep frame lands on
 * it, and when the entry point exists at all.
 *
 * These are the plain functions behind the compose layer, so the mapping from
 * source pixels to stage pixels is testable without a device — a frame that is
 * drawn where the export crops is the whole point of the feature.
 */
class PhoneCropStageFrameTest {

    @Test
    fun a_wide_photo_is_letterboxed_inside_the_stage_band() {
        // 6000x4000 (3:2) inside a square band: full width, height centred.
        val frame = cropStageFrame(bandWidth = 400f, bandHeight = 400f, sourceWidth = 6000, sourceHeight = 4000)
        assertEquals(0f, frame.left, 1e-4f)
        assertEquals(400f, frame.width, 1e-4f)
        assertEquals(400f / 1.5f, frame.height, 1e-3f)
        assertEquals((400f - frame.height) / 2f, frame.top, 1e-3f)
    }

    @Test
    fun a_tall_photo_is_letterboxed_the_other_way_round() {
        val frame = cropStageFrame(bandWidth = 400f, bandHeight = 400f, sourceWidth = 4000, sourceHeight = 6000)
        assertEquals(0f, frame.top, 1e-4f)
        assertEquals(400f, frame.height, 1e-4f)
        assertEquals(400f / 1.5f, frame.width, 1e-3f)
        assertEquals((400f - frame.width) / 2f, frame.left, 1e-3f)
    }

    @Test
    fun an_empty_band_or_an_unknown_photo_draws_nothing() {
        assertTrue(cropStageFrame(0f, 400f, 4000, 3000).isEmpty)
        assertTrue(cropStageFrame(400f, 0f, 4000, 3000).isEmpty)
        assertTrue(cropStageFrame(400f, 400f, 0, 3000).isEmpty)
        assertTrue(cropStageFrame(400f, 400f, 4000, 0).isEmpty)
    }

    @Test
    fun the_keep_frame_is_the_exported_region_mapped_onto_the_fitted_photo() {
        // 4000x3000 fitted into a square band: 400 x 300 at the vertical centre.
        val frame = cropStageFrame(bandWidth = 400f, bandHeight = 400f, sourceWidth = 4000, sourceHeight = 3000)
        assertEquals(300f, frame.height, 1e-3f)
        val box = ImageRect(0f, 0f, 1000f, 1000f)

        // The engine's 1:1 crop keeps 3000x3000 at x = 500, i.e. 75 % of the width
        // and, because it fills the height, all of it.
        val whole = assertNotNull(cropFrameBounds(frame, 4000, 3000, box, PhotoCrop.Default))
        assertEquals(frame.left + frame.width * 0.125f, whole[0], 1e-3f)
        assertEquals(frame.top, whole[1], 1e-3f)
        assertEquals(frame.width * 0.75f, whole[2], 1e-3f)
        assertEquals(frame.height, whole[3], 1e-3f)

        // A deeper zoom keeps the centre and half the extent.
        val zoomed = assertNotNull(cropFrameBounds(frame, 4000, 3000, box, PhotoCrop(0.5, 0.5, 2.0)))
        assertEquals(whole[2] / 2f, zoomed[2], 1e-3f)
        assertEquals(whole[3] / 2f, zoomed[3], 1e-3f)
        assertEquals(whole[0] + whole[2] / 4f, zoomed[0], 1e-3f)

        // Without an engine photo box there is no frame to draw (and the caller
        // must not open the crop editor either).
        assertNull(cropFrameBounds(frame, 4000, 3000, null, PhotoCrop.Default))
    }

    @Test
    fun the_crop_entry_exists_only_when_the_box_really_cuts_the_photo() {
        assertFalse(cropApplies(6000, 4000, ImageRect(0f, 0f, 600f, 400f)), "same aspect, nothing to choose")
        assertFalse(cropApplies(6000, 4000, null), "no reported box yet")
        assertFalse(cropApplies(0, 4000, ImageRect(0f, 0f, 400f, 400f)), "unmeasured photo")
        assertTrue(cropApplies(6000, 4000, ImageRect(0f, 0f, 400f, 400f)), "a square box cuts a 3:2 photo")
        assertTrue(cropApplies(4000, 6000, ImageRect(0f, 0f, 400f, 400f)))
    }

    @Test
    fun a_frame_is_never_reported_outside_the_band_it_belongs_to() {
        val frame = cropStageFrame(400f, 300f, 4000, 3000)
        assertFalse(frame.isEmpty)
        // The fits are exact, so the frame sits inside the band on both axes.
        assertTrue(frame.left >= -1e-3f && frame.top >= -1e-3f, frame.toString())
        assertTrue(frame.left + frame.width <= 400f + 1e-3f, frame.toString())
        assertTrue(frame.top + frame.height <= 300f + 1e-3f, frame.toString())
        assertEquals(0f, frame.left, 1e-3f)
        assertEquals(0f, frame.top, 1e-3f)
        assertEquals(400f, frame.width, 1e-3f)
        assertEquals(300f, frame.height, 1e-3f)
    }
}
