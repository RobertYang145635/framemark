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
