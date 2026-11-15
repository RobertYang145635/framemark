package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The single orientation authority (B-02 / A-01).
 *
 * The engines decode with EXIF applied (Skia m156 `Image::from_encoded`, and both
 * Android bridges apply it explicitly), so every geometry the app sends or
 * receives — `photoCropX/Y/Zoom`, `photo_src`, the crop UI's frame, the export
 * canvas — lives in the **display** frame. These tests pin the conversion between
 * that frame and the file's stored raster; get one of them wrong and a portrait
 * photo is cropped one whole rotation away.
 */
class PhotoOrientationTest {

    private val all = 1..8

    @Test
    fun only_orientations_5_to_8_swap_the_axes() {
        for (orientation in 0..9) {
            val size = PhotoOrientation.displaySize(800, 600, orientation)
            if (orientation in 5..8) {
                assertEquals(600, size.width, "orientation=$orientation")
                assertEquals(800, size.height, "orientation=$orientation")
                assertTrue(PhotoOrientation.isTransposed(orientation))
            } else {
                assertEquals(800, size.width, "orientation=$orientation")
                assertEquals(600, size.height, "orientation=$orientation")
            }
        }
    }

    @Test
    fun the_rotated_corners_match_the_exif_table() {
        // Raw 800x600 stored landscape; orientation 6 displays it 90° clockwise.
        val w = 800
        val h = 600
        // The raw top-left pixel becomes the display top-right.
        assertEquals((h - 1) to 0, PhotoOrientation.displayPixel(6, w, h, 0, 0))
        // ...and reading it back must find the same source pixel.
        assertEquals(0 to 0, PhotoOrientation.sourcePixel(6, w, h, h - 1, 0))
        // Orientation 8 is the counter-clockwise twin.
        assertEquals(0 to (w - 1), PhotoOrientation.displayPixel(8, w, h, 0, 0))
        // Orientation 3 is a half turn; the two mirrors stay on their axes.
        assertEquals((w - 1) to (h - 1), PhotoOrientation.displayPixel(3, w, h, 0, 0))
        assertEquals((w - 1) to 0, PhotoOrientation.displayPixel(2, w, h, 0, 0))
        assertEquals(0 to (h - 1), PhotoOrientation.displayPixel(4, w, h, 0, 0))
    }

    @Test
    fun source_and_display_pixel_mappings_are_inverses_for_all_orientations() {
        val w = 7
        val h = 5
        for (orientation in all) {
            val display = PhotoOrientation.displaySize(w, h, orientation)
            for (sy in 0 until h) {
                for (sx in 0 until w) {
                    val (dx, dy) = PhotoOrientation.displayPixel(orientation, w, h, sx, sy)
                    assertTrue(dx in 0 until display.width, "orientation=$orientation")
                    assertTrue(dy in 0 until display.height, "orientation=$orientation")
                    assertEquals(
                        sx to sy,
                        PhotoOrientation.sourcePixel(orientation, w, h, dx, dy),
                        "orientation=$orientation pixel ($sx,$sy)",
                    )
                }
            }
        }
    }

    @Test
    fun every_destination_row_is_one_source_line_and_matches_the_pixel_mapping() {
        val w = 7
        val h = 5
        for (orientation in all) {
            val display = PhotoOrientation.displaySize(w, h, orientation)
            for (dy in 0 until display.height) {
                val row = PhotoOrientation.rowSource(orientation, w, h, dy)
                // A row read picks a source y (0..height-1); a column read picks a
                // source x (0..width-1).
                val lineCount = if (row.column) w else h
                assertTrue(row.line in 0 until lineCount, "orientation=$orientation dy=$dy line=${row.line}")
                // The along-line coordinate is x for rows and y for columns.
                val along = if (row.column) h else w
                for (dx in 0 until display.width) {
                    val index = if (row.reversed) along - 1 - dx else dx
                    val expected = if (row.column) row.line to index else index to row.line
                    assertEquals(
                        expected,
                        PhotoOrientation.sourcePixel(orientation, w, h, dx, dy),
                        "orientation=$orientation row=$dy col=$dx",
                    )
                }
            }
        }
    }

    @Test
    fun a_rectangle_round_trips_between_the_two_spaces() {
        val w = 8192
        val h = 6144
        for (orientation in all) {
            val display = PhotoOrientation.displaySize(w, h, orientation)
            val fullDisplay = doubleArrayOf(0.0, 0.0, display.width.toDouble(), display.height.toDouble())
            val wholeRaw = assertNotNull(PhotoOrientation.displayRectToRaw(fullDisplay, orientation, w, h))
            assertRectEquals(doubleArrayOf(0.0, 0.0, w.toDouble(), h.toDouble()), wholeRaw, "orientation=$orientation whole")

            val displayRects = listOf(
                doubleArrayOf(1.0, 2.0, display.width / 3.0, display.height / 4.0),
                doubleArrayOf(
                    display.width / 2.0,
                    display.height / 2.0,
                    display.width / 4.0,
                    display.height / 8.0,
                ),
                doubleArrayOf(display.width - 100.0, display.height - 50.0, 99.0, 49.0),
            )
            for (displayRect in displayRects) {
                val raw = assertNotNull(
                    PhotoOrientation.displayRectToRaw(displayRect, orientation, w, h),
                    "orientation=$orientation rect=${displayRect.toList()}",
                )
                assertTrue(raw[0] >= 0.0 && raw[1] >= 0.0, "orientation=$orientation raw=${raw.toList()}")
                assertTrue(raw[0] + raw[2] <= w + 1e-6, "orientation=$orientation raw=${raw.toList()}")
                assertTrue(raw[1] + raw[3] <= h + 1e-6, "orientation=$orientation raw=${raw.toList()}")
                // Rotated crops swap the extents; the round trip is the real contract.
                val back = assertNotNull(PhotoOrientation.rawRectToDisplay(raw, orientation, w, h))
                assertRectEquals(displayRect, back, "orientation=$orientation display=${displayRect.toList()}")
            }
        }
    }

    @Test
    fun a_display_crop_on_orientation_6_lands_on_the_matching_raw_pixels() {
        // Orientation 6: the display is the raw raster rotated 90° clockwise, so the
        // display's left column is the raw **bottom** rows (the left edge becomes the
        // top edge). Pixel mapping: display (dx,dy) ← raw (dy, H-1-dx).
        val w = 8192
        val h = 6144
        val displayCrop = doubleArrayOf(0.0, 0.0, 600.0, 6144.0)
        val raw = assertNotNull(PhotoOrientation.displayRectToRaw(displayCrop, 6, w, h))
        assertRectEquals(doubleArrayOf(0.0, 5544.0, 6144.0, 600.0), raw, "left column of the display")
    }

    @Test
    fun an_unknown_orientation_is_the_identity() {
        for (orientation in listOf(0, 9, -3, 255)) {
            assertEquals(
                PhotoOrientation.displaySize(800, 600, 1),
                PhotoOrientation.displaySize(800, 600, orientation),
            )
            val rect = doubleArrayOf(10.0, 20.0, 100.0, 50.0)
            assertRectEquals(rect, assertNotNull(PhotoOrientation.displayRectToRaw(rect, orientation, 800, 600)))
        }
    }

    private fun assertRectEquals(expected: DoubleArray, actual: DoubleArray, message: String = "") {
        assertEquals(expected.size, actual.size, message)
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], 1e-6, "$message index=$i expected=${expected.toList()} actual=${actual.toList()}")
        }
    }
}
