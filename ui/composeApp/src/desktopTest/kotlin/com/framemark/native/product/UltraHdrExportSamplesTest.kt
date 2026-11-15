package com.framemark.native.product

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end check against a real camera file: source photo → recomposited gain
 * map → assembled Ultra HDR JPEG.
 *
 * The sample lives outside the repository (they are 13–45 MB files), so the test
 * skips itself unless `FM_HDR_SAMPLES` points at a directory containing the OPPO
 * gallery export. The written file is then checked independently with
 * `scripts/native/hdr-probe.cjs`, which is a second implementation of the reader.
 */
class UltraHdrExportSamplesTest {

    private fun sample(): File? {
        val root = System.getenv("FM_HDR_SAMPLES")?.takeIf { it.isNotBlank() } ?: return null
        return File(root, "新建文件夹/IMG_20261111_035845.jpg").takeIf { it.isFile }
    }

    @Test
    fun a_real_oppo_export_survives_the_whole_wrap () {
        val file = sample() ?: return
        val source = file.readBytes()
        val frame = assertNotNull(UltraHdr.frameSize(source))

        // The source doubles as the SDR base: what this test pins is the container —
        // offsets, lengths, the copied metadata and the border sentinel.
        val wrapped = assertNotNull(
            UltraHdrExport.wrap(
                source = source,
                sdrJpeg = source,
                canvasWidth = frame.first,
                canvasHeight = frame.second,
                photoRect = doubleArrayOf(0.0, 0.0, frame.first.toDouble(), frame.second.toDouble()),
                orientation = 1,
            ),
            "wrap must succeed for an uncropped, aspect-matched layout",
        )

        val gainMap = assertNotNull(UltraHdr.locate(wrapped))
        assertEquals(gainMap.size, UltraHdr.containerLength(wrapped), "GContainer length must match")
        assertEquals(3, gainMap.sentinel.distinct().size, "OPPO's file carries three distinct channel values")
        assertTrue(wrapped.size > source.size, "the appended gain map makes the file larger")

        val out = File("build/tmp/ultrahdr/selftest.jpg").apply { parentFile.mkdirs() }
        out.writeBytes(wrapped)
        println("FM_ULTRAHDR_SELFTEST=${out.absolutePath} bytes=${wrapped.size}")
    }

    @Test
    fun a_cropped_photo_box_wraps_with_a_non_uniform_gain_map () {
        val file = sample() ?: return
        val source = file.readBytes()
        val frame = assertNotNull(UltraHdr.frameSize(source))
        // A 1:1 border around a portrait photo crops the sides. The renderer's crop
        // is centre-anchored, so the export can carry the gain map instead of
        // falling back to SDR: the result must be a valid Ultra HDR file whose
        // gain map still varies (a flat one would mean the crop landed outside the
        // photo, or that the source rectangle was not rescaled to the gain map's
        // own resolution).
        val side = minOf(frame.first, frame.second)
        val wrapped = assertNotNull(
            UltraHdrExport.wrap(
                source = source,
                sdrJpeg = source,
                canvasWidth = side,
                canvasHeight = side,
                photoRect = doubleArrayOf(0.0, 0.0, side.toDouble(), side.toDouble()),
                orientation = 1,
            ),
            "a cropped photo box must still export Ultra HDR",
        )

        val gainMap = assertNotNull(UltraHdr.locate(wrapped))
        assertEquals(gainMap.size, UltraHdr.containerLength(wrapped), "GContainer length must match")
        val gainImage = assertNotNull(
            ImageIO.read(ByteArrayInputStream(wrapped, gainMap.offset.toInt(), gainMap.size.toInt())),
            "the appended gain map must decode",
        )
        val values = distinctGainValues(gainImage)
        assertTrue(values > 8, "the cropped gain map must not be flat (distinct values: $values)")

        val out = File("build/tmp/ultrahdr/cropped.jpg").apply { parentFile.mkdirs() }
        out.writeBytes(wrapped)
        println("FM_ULTRAHDR_CROPPED=${out.absolutePath} bytes=${wrapped.size}")
    }

    /**
     * B-02 on a real camera file: inject Orientation=6 (the phone portrait case)
     * and check the export composes the gain map in the **display** frame. Before
     * the fix the engine-reported full-photo rectangle was clamped against the raw
     * SOF size (8192×6144 instead of 6144×8192), so the whole bottom of the frame
     * was silently cropped out of the gain map.
     */
    @Test
    fun a_real_rotated_export_wraps_in_the_display_frame() {
        val file = sample() ?: return
        val source = file.readBytes()
        val raw = assertNotNull(UltraHdr.frameSize(source))
        val oriented = source.copyOfRange(0, 2) +
            UltraHdrFixture.exifOrientationSegment(6) +
            source.copyOfRange(2, source.size)
        // The MPF index is relative to its TIFF header, so an inserted APP1 keeps the
        // appended gain map addressable (pinned by UltraHdrTest / the audit probe).
        assertNotNull(UltraHdr.locate(oriented), "inserting the EXIF tag must not break the container")
        val display = PhotoOrientation.displaySize(raw.first, raw.second, 6)
        assertTrue(display.width != raw.first, "a portrait orientation must swap the frame")

        val wrapped = assertNotNull(
            UltraHdrExport.wrap(
                source = oriented,
                sdrJpeg = source,
                canvasWidth = display.width,
                canvasHeight = display.height,
                photoRect = doubleArrayOf(0.0, 0.0, display.width.toDouble(), display.height.toDouble()),
                orientation = 6,
            ),
            "an EXIF-rotated source must still export Ultra HDR",
        )

        val gainMap = assertNotNull(UltraHdr.locate(wrapped))
        val gainImage = assertNotNull(
            ImageIO.read(ByteArrayInputStream(wrapped, gainMap.offset.toInt(), gainMap.size.toInt())),
            "the appended gain map must decode",
        )
        // The compositor writes one gain-map pixel per 2 canvas pixels, and the
        // rotated gain map gives that canvas the display aspect — not the raw one.
        assertEquals(display.width / 2, gainImage.width, "gain map width must be half the display canvas")
        assertEquals(display.height / 2, gainImage.height, "gain map height must be half the display canvas")
        assertTrue(distinctGainValues(gainImage) > 8, "a rotated gain map must not collapse to a flat image")
    }

    /** Rough measure of gain-map variety: distinct RGB triples on a coarse grid. */
    private fun distinctGainValues(image: BufferedImage): Int {
        val step = maxOf(1, maxOf(image.width, image.height) / 64)
        val seen = HashSet<Int>()
        for (y in 0 until image.height step step) {
            for (x in 0 until image.width step step) {
                seen += image.getRGB(x, y) and 0xFFFFFF
            }
        }
        return seen.size
    }
}
