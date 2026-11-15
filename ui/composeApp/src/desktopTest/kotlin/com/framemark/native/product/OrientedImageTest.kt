package com.framemark.native.product

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The desktop pixel rotation behind the gain-map composition (B-02) and the
 * photo-strip thumbnails.
 *
 * Pins the transform against an independently written EXIF table: for every
 * orientation, destination pixel (dx,dy) must come from exactly the source pixel
 * the tag says, and the extents must swap for 5..8.
 */
class OrientedImageTest {

    @Test
    fun orientation_one_returns_the_same_surface() {
        val image = labelled(5, 3)
        assertSame(image, image.orientedImage(1))
        assertSame(image, image.orientedImage(0))
        assertSame(image, image.orientedImage(9))
    }

    @Test
    fun every_orientation_matches_the_independent_exif_table() {
        for (orientation in 1..8) {
            val source = labelled(5, 3)
            val actual = source.orientedImage(orientation)
            val expected = expectedByTable(source, orientation)
            assertEquals(expected.width, actual.width, "orientation=$orientation width")
            assertEquals(expected.height, actual.height, "orientation=$orientation height")
            for (y in 0 until actual.height) {
                for (x in 0 until actual.width) {
                    assertEquals(
                        expected.getRGB(x, y),
                        actual.getRGB(x, y),
                        "orientation=$orientation pixel ($x,$y)",
                    )
                }
            }
        }
    }

    @Test
    fun an_exif_rotation_round_trips_through_its_inverse() {
        val original = labelled(5, 3)
        // 2..5 and 7 are self-inverse pixel maps; 6 and 8 are each other's inverse.
        for (orientation in listOf(2, 3, 4, 5, 7)) {
            assertImagesEqual(original, original.orientedImage(orientation).orientedImage(orientation), "orientation=$orientation")
        }
        assertImagesEqual(original, original.orientedImage(6).orientedImage(8), "6 then 8")
        assertImagesEqual(original, original.orientedImage(8).orientedImage(6), "8 then 6")
    }

    /** A 0xRRGGBB encoding of (x,y) so a moved pixel is unmistakable. */
    private fun labelled(width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, (0xFF shl 24) or (x shl 8) or y)
            }
        }
        return image
    }

    /** The EXIF table written independently of `PhotoOrientation`. */
    private fun expectedByTable(source: BufferedImage, orientation: Int): BufferedImage {
        val w = source.width
        val h = source.height
        val swap = orientation in 5..8
        val out = BufferedImage(if (swap) h else w, if (swap) w else h, BufferedImage.TYPE_INT_ARGB)
        for (dy in 0 until out.height) {
            for (dx in 0 until out.width) {
                val (sx, sy) = when (orientation) {
                    2 -> (w - 1 - dx) to dy
                    3 -> (w - 1 - dx) to (h - 1 - dy)
                    4 -> dx to (h - 1 - dy)
                    5 -> dy to dx
                    6 -> dy to (h - 1 - dx)
                    7 -> (w - 1 - dy) to (h - 1 - dx)
                    8 -> (w - 1 - dy) to dx
                    else -> dx to dy
                }
                out.setRGB(dx, dy, source.getRGB(sx, sy))
            }
        }
        return out
    }

    private fun assertImagesEqual(expected: BufferedImage, actual: BufferedImage, message: String) {
        assertEquals(expected.width, actual.width, "$message width")
        assertEquals(expected.height, actual.height, "$message height")
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                assertEquals(expected.getRGB(x, y), actual.getRGB(x, y), "$message pixel ($x,$y)")
            }
        }
    }
}
