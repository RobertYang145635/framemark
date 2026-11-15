package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The source rectangle the renderer's centre-anchored `cover` crop implies.
 *
 * A gain map is only valid on the pixels it was computed from, so an export that
 * crops the photo must reuse exactly the cropped part. These are the aspect
 * cases the engine can produce (`engine/e2b/src/main.rs` cover): equal, taller
 * box, wider box, plus the rounding edges where a naive `round(width)` could
 * leave the image or collapse to zero.
 */
class UltraHdrExportSourceRectTest {

    @Test
    fun box_aspect_equal_to_the_source_uses_the_whole_image () {
        assertContentEquals(
            doubleArrayOf(0.0, 0.0, 6000.0, 4000.0),
            assertNotNull(UltraHdrExport.sourceRect(6000, 4000, 6000.0, 4000.0)),
        )
        // The box may be smaller than the photo: only the aspect matters, the
        // rectangle stays in source pixels.
        assertContentEquals(
            doubleArrayOf(0.0, 0.0, 6000.0, 4000.0),
            assertNotNull(UltraHdrExport.sourceRect(6000, 4000, 600.0, 400.0)),
        )
    }

    @Test
    fun a_box_narrower_than_the_source_keeps_the_full_height_and_crops_the_sides () {
        // 4000x3000 (4:3) into a 1:1 box: the tallest square wins, centred.
        assertContentEquals(
            doubleArrayOf(500.0, 0.0, 3000.0, 3000.0),
            assertNotNull(UltraHdrExport.sourceRect(4000, 3000, 1000.0, 1000.0)),
        )
    }

    @Test
    fun a_box_wider_than_the_source_keeps_the_full_width_and_crops_top_and_bottom () {
        // 4000x3000 (4:3) into a 2:1 box: the full width wins, centred.
        assertContentEquals(
            doubleArrayOf(0.0, 500.0, 4000.0, 2000.0),
            assertNotNull(UltraHdrExport.sourceRect(4000, 3000, 2000.0, 1000.0)),
        )
    }

    @Test
    fun rounding_never_leaves_the_image_or_produces_a_zero_dimension () {
        val cases = listOf(
            // odd source, odd box: 3001 * (1001/999) has a non-integer edge
            (4001 to 3001) to (1001.0 to 999.0),
            // 1 px boxes: the short axis has no room and must clamp to one pixel
            (640 to 480) to (1.0 to 1.0),
            (640 to 480) to (1.0 to 2000.0),
            (640 to 480) to (2000.0 to 1.0),
            // a one-pixel source cannot be cropped at all
            (1 to 1) to (1.0 to 1.0),
            (3 to 3) to (4.0 to 2.0),
            (3 to 3) to (2.0 to 4.0),
        )
        for ((source, box) in cases) {
            val (sourceWidth, sourceHeight) = source
            val (boxWidth, boxHeight) = box
            val rect = assertNotNull(
                UltraHdrExport.sourceRect(sourceWidth, sourceHeight, boxWidth, boxHeight),
                "$sourceWidth x $sourceHeight into $boxWidth x $boxHeight",
            )
            val x = rect[0].toInt()
            val y = rect[1].toInt()
            val w = rect[2].toInt()
            val h = rect[3].toInt()
            assertEquals(rect[0], x.toDouble(), "x must stay integral")
            assertEquals(rect[1], y.toDouble(), "y must stay integral")
            assertEquals(rect[2], w.toDouble(), "w must stay integral")
            assertEquals(rect[3], h.toDouble(), "h must stay integral")
            assertTrue(w >= 1 && h >= 1, "a crop must never be empty: ${rect.toList()}")
            assertTrue(x >= 0 && y >= 0, "a crop must not start outside: ${rect.toList()}")
            assertTrue(x + w <= sourceWidth && y + h <= sourceHeight, "a crop must stay inside: ${rect.toList()}")
            // The engine's crop is centre-anchored: both margins differ by at
            // most the one pixel rounding can eat.
            assertTrue(kotlin.math.abs(x - (sourceWidth - w - x)) <= 1, "x must be centred: ${rect.toList()}")
            assertTrue(kotlin.math.abs(y - (sourceHeight - h - y)) <= 1, "y must be centred: ${rect.toList()}")
        }
    }

    @Test
    fun an_unusable_box_or_source_is_refused () {
        assertNull(UltraHdrExport.sourceRect(0, 4000, 100.0, 100.0))
        assertNull(UltraHdrExport.sourceRect(6000, 0, 100.0, 100.0))
        assertNull(UltraHdrExport.sourceRect(6000, 4000, 0.0, 100.0))
        assertNull(UltraHdrExport.sourceRect(6000, 4000, 100.0, -1.0))
        assertNull(UltraHdrExport.sourceRect(6000, 4000, Double.NaN, 100.0))
        assertNull(UltraHdrExport.sourceRect(6000, 4000, 100.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun eligibility_accepts_a_cropped_box_but_still_requires_a_gain_map () {
        val source = minimalUltraHdrJpeg(6000, 4000)

        // Uncropped: the same result the aspect guard used to allow.
        val whole = assertNotNull(
            UltraHdrExport.eligible(source, doubleArrayOf(0.0, 0.0, 6000.0, 4000.0)),
        )
        assertEquals(6000, whole.sourceWidth)
        assertEquals(4000, whole.sourceHeight)
        assertContentEquals(doubleArrayOf(0.0, 0.0, 6000.0, 4000.0), whole.sourceRect)

        // A 1:1 box crops a 3:2 photo. That is now a computed crop, not a refusal:
        // the centre-anchored 1:1 rectangle is the one the renderer uses.
        val cropped = assertNotNull(
            UltraHdrExport.eligible(source, doubleArrayOf(0.0, 0.0, 4000.0, 4000.0)),
            "a cropped photo box must still be exportable as Ultra HDR",
        )
        assertContentEquals(doubleArrayOf(1000.0, 0.0, 4000.0, 4000.0), cropped.sourceRect)

        val narrow = assertNotNull(
            UltraHdrExport.eligible(source, doubleArrayOf(0.0, 0.0, 4000.0, 6000.0)),
            "a tall cropped box must still be exportable as Ultra HDR",
        )
        // 6000x4000 into a 2:3 box keeps the full height and cuts the sides.
        assertContentEquals(doubleArrayOf(1667.0, 0.0, 2667.0, 4000.0), narrow.sourceRect)

        // Still refused: a JPEG with no appended gain map.
        assertNull(
            UltraHdrExport.eligible(plainJpeg(6000, 4000), doubleArrayOf(0.0, 0.0, 4000.0, 4000.0)),
            "no gain map means SDR",
        )

        // Still refused: a photo box without area.
        assertNull(UltraHdrExport.eligible(source, doubleArrayOf(0.0, 0.0, 0.0, 4000.0)))
        assertNull(UltraHdrExport.eligible(source, doubleArrayOf(0.0, 0.0, 4000.0)))
    }

    @Test
    fun a_half_size_gain_map_gets_the_crop_rescaled_into_its_own_pixels () {
        // OPPO's gallery exports append a half-size gain map (6485x10889 photo,
        // 3242x5444 gain map). The source rectangle is in photo pixels, so the
        // compositor has to rescale it before sampling.
        val job = GainMapJob(
            source = ByteArray(0),
            offset = 0,
            size = 0,
            canvasWidth = 6485,
            canvasHeight = 6485,
            photoRect = doubleArrayOf(0.0, 0.0, 6485.0, 6485.0),
            sourceRect = doubleArrayOf(0.0, 2202.0, 6485.0, 6485.0),
            sourceWidth = 6485,
            sourceHeight = 10889,
            orientation = 1,
            sentinel = listOf(97, 93, 92),
            divisor = 2,
        )
        assertContentEquals(
            intArrayOf(0, 1101, 3242, 4343),
            job.gainMapRect(3242, 5444),
        )
        // An odd scale still yields a non-empty source inside the bitmap.
        val scaled = job.gainMapRect(101, 170)
        assertTrue(scaled[2] > scaled[0] && scaled[3] > scaled[1], scaled.toList().toString())
        assertTrue(scaled[0] >= 0 && scaled[1] >= 0, scaled.toList().toString())
        assertTrue(scaled[2] <= 101 && scaled[3] <= 170, scaled.toList().toString())
    }

    @Test
    fun the_engine_reported_rect_wins_and_a_missing_one_falls_back() {
        val source = minimalUltraHdrJpeg(6000, 4000)
        val box = doubleArrayOf(0.0, 0.0, 4000.0, 4000.0)

        // Kotlin's own derivation for this box would be 1000,0,4000,4000. The
        // engine says it sampled a corner window: that is what reached the SDR
        // pixels, so the gain map must be cut there.
        val reported = assertNotNull(
            UltraHdrExport.eligible(source, box, PhotoCrop.Default, doubleArrayOf(0.0, 0.0, 2000.0, 2000.0)),
        )
        assertContentEquals(doubleArrayOf(0.0, 0.0, 2000.0, 2000.0), reported.sourceRect)

        // No report (an older engine): the Kotlin computation still runs.
        val derived = assertNotNull(UltraHdrExport.eligible(source, box))
        assertContentEquals(doubleArrayOf(1000.0, 0.0, 4000.0, 4000.0), derived.sourceRect)

        // A report that cannot describe this file also falls back instead of
        // silently exporting SDR.
        for (bogus in listOf(
            doubleArrayOf(7000.0, 0.0, 100.0, 100.0),   // entirely outside the photo
            doubleArrayOf(0.0, 0.0, 0.0, 100.0),        // empty
            doubleArrayOf(0.0, 0.0, Double.NaN, 100.0), // not a number
            doubleArrayOf(0.0, 0.0, 100.0),             // short
        )) {
            val fallback = assertNotNull(UltraHdrExport.eligible(source, box, photoSrc = bogus), bogus.toList().toString())
            assertContentEquals(doubleArrayOf(1000.0, 0.0, 4000.0, 4000.0), fallback.sourceRect)
        }
    }

    @Test
    fun an_engine_rect_in_display_pixels_is_not_clipped_by_the_raw_sof_size() {
        // B-02 regression, exactly the audit's case: an 8192x6144 camera JPEG stored
        // landscape with Orientation=6 displays (and is rendered) as 6144x8192. The
        // engine reports the whole display frame; clamping that against the raw SOF
        // trimmed it to 6144x6144 and dropped one fifth of the photo.
        val rawWidth = 8192
        val rawHeight = 6144
        val source = withExifOrientation(minimalUltraHdrJpeg(rawWidth, rawHeight), 6)
        val engineRect = doubleArrayOf(0.0, 0.0, rawHeight.toDouble(), rawWidth.toDouble())
        val display = PhotoOrientation.displaySize(rawWidth, rawHeight, 6)
        assertEquals(6144, display.width)
        assertEquals(8192, display.height)

        val eligible = assertNotNull(
            UltraHdrExport.eligible(
                source = source,
                photoRect = doubleArrayOf(0.0, 0.0, 600.0, 800.0),
                photoSrc = engineRect,
                orientation = 6,
            ),
            "an orientation-6 source must still export Ultra HDR",
        )
        assertContentEquals(engineRect, eligible.sourceRect, "the whole display frame must survive unclipped")
        // The reported display frame, not the stored raster.
        assertEquals(6144, eligible.sourceWidth)
        assertEquals(8192, eligible.sourceHeight)
        assertEquals(6, eligible.orientation)
    }

    @Test
    fun an_unreported_rect_is_derived_in_the_display_frame() {
        val rawWidth = 8192
        val rawHeight = 6144
        val source = withExifOrientation(minimalUltraHdrJpeg(rawWidth, rawHeight), 6)
        // A photo box with the display aspect keeps the whole display image. Computing
        // this against the raw size would have returned 8192x6144 — a region that does
        // not exist in the file the renderer sampled.
        val eligible = assertNotNull(
            UltraHdrExport.eligible(
                source = source,
                photoRect = doubleArrayOf(0.0, 0.0, 6144.0, 8192.0),
                orientation = 6,
            ),
        )
        assertContentEquals(doubleArrayOf(0.0, 0.0, 6144.0, 8192.0), eligible.sourceRect)
    }

    @Test
    fun a_reported_rect_is_clamped_into_the_frame() {        // The engine works in float pixels of the raster; the frame is integral.
        assertContentEquals(
            doubleArrayOf(0.0, 0.0, 6000.0, 4000.0),
            assertNotNull(UltraHdrExport.reportedSourceRect(doubleArrayOf(-0.4, -0.2, 6000.4, 4000.2), 6000, 4000)),
        )
        assertContentEquals(
            doubleArrayOf(1000.0, 500.0, 3000.0, 2000.0),
            assertNotNull(UltraHdrExport.reportedSourceRect(doubleArrayOf(1000.0, 500.0, 3000.0, 2000.0), 6000, 4000)),
        )
        assertNull(UltraHdrExport.reportedSourceRect(null, 6000, 4000))
        assertNull(UltraHdrExport.reportedSourceRect(doubleArrayOf(0.0, 0.0, 10.0, 10.0), 0, 4000))
        assertNull(UltraHdrExport.reportedSourceRect(doubleArrayOf(0.0, 0.0, -10.0, 10.0), 6000, 4000))
    }

    // ------------------------------------------------------------------ builders

    /**
     * A structurally valid Ultra HDR file whose primary declares [width] x
     * [height]: SOI + MPF (two images) + SOF0 + EOI, followed by the gain map
     * JPEG that the MPF index points at. The gain map carries the minimal
     * `hdrgm` packet [UltraHdr.locate] needs.
     */
    private fun minimalUltraHdrJpeg(width: Int, height: Int): ByteArray {
        val gain = jpeg(listOf(xmpSegment(GAIN_MAP_XMP)))
        val tiffSize = 66
        val tiffStart = 10 // SOI + APP2 marker/length + "MPF\0"
        val mpfTotal = 4 + 4 + tiffSize // marker/length + ("MPF\0" + TIFF)
        val sofTotal = 4 + 15 // marker/length + SOF0 payload
        val primarySize = 2 + mpfTotal + sofTotal + 2

        val tiff = ByteArray(tiffSize)
        tiff[0] = 0x4D; tiff[1] = 0x4D; tiff[3] = 0x2A.toByte()
        put32(tiff, 4, 8)              // IFD0 at tiff+8
        put16(tiff, 8, 2)              // two tags
        put16(tiff, 10, 0xB001); put16(tiff, 12, 4); put32(tiff, 14, 1); put32(tiff, 18, 2)
        put16(tiff, 22, 0xB002); put16(tiff, 24, 7); put32(tiff, 26, 32); put32(tiff, 30, 34)
        // entry[0]: primary, offset 0 means "the file itself"
        put32(tiff, 34, 0x20030000); put32(tiff, 38, primarySize); put32(tiff, 42, 0)
        // entry[1]: gain map, offset relative to the TIFF header
        put32(tiff, 50, 0); put32(tiff, 54, gain.size); put32(tiff, 58, primarySize - tiffStart)

        val mpfPayload = "MPF".encodeToByteArray() + byteArrayOf(0) + tiff
        val mpfLength = mpfPayload.size + 2
        val mpf = ByteArray(4 + mpfPayload.size)
        mpf[0] = 0xFF.toByte(); mpf[1] = 0xE2.toByte()
        mpf[2] = (mpfLength ushr 8).toByte(); mpf[3] = mpfLength.toByte()
        mpfPayload.copyInto(mpf, 4)

        val sof = ByteArray(sofTotal)
        sof[0] = 0xFF.toByte(); sof[1] = 0xC0.toByte()
        put16(sof, 2, 17)              // marker length: 8 + 3 components
        sof[4] = 8                     // precision
        put16(sof, 5, height)
        put16(sof, 7, width)
        sof[9] = 3                     // components
        for (c in 0 until 3) {
            sof[10 + c * 3] = (c + 1).toByte()
            sof[11 + c * 3] = 0x11
            sof[12 + c * 3] = 0
        }

        val out = ByteArray(primarySize + gain.size)
        out[0] = 0xFF.toByte(); out[1] = 0xD8.toByte()
        mpf.copyInto(out, 2)
        sof.copyInto(out, 2 + mpf.size)
        out[2 + mpf.size + sof.size] = 0xFF.toByte()
        out[2 + mpf.size + sof.size + 1] = 0xD9.toByte()
        gain.copyInto(out, primarySize)
        return out
    }

    /** A JPEG with a frame header and nothing else: no gain map, no MPF. */
    private fun plainJpeg(width: Int, height: Int): ByteArray {
        val sof = ByteArray(19)
        sof[0] = 0xFF.toByte(); sof[1] = 0xC0.toByte()
        put16(sof, 2, 17)
        sof[4] = 8
        put16(sof, 5, height)
        put16(sof, 7, width)
        sof[9] = 3
        return jpeg(listOf(sof))
    }

    /** `FFD8` + [segments] + `FFD9` — a valid-enough JPEG for the segment reader. */
    private fun jpeg(segments: List<ByteArray>): ByteArray {
        val out = ArrayList<Byte>(16 + segments.sumOf { it.size })
        out += 0xFF.toByte(); out += 0xD8.toByte()
        for (s in segments) out += s.toList()
        out += 0xFF.toByte(); out += 0xD9.toByte()
        return out.toByteArray()
    }

    /** APP1 XMP segment for [xmp]. */
    private fun xmpSegment(xmp: String): ByteArray {
        val body = "http://ns.adobe.com/xap/1.0/".encodeToByteArray() + xmp.encodeToByteArray()
        val length = body.size + 2
        val out = ByteArray(4 + body.size)
        out[0] = 0xFF.toByte(); out[1] = 0xE1.toByte()
        out[2] = (length ushr 8).toByte(); out[3] = length.toByte()
        body.copyInto(out, 4)
        return out
    }

    /** Insert an EXIF APP1 with [orientation] right after the SOI. */
    private fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = ByteArray(26)
        tiff[0] = 'I'.code.toByte(); tiff[1] = 'I'.code.toByte()
        putLe16(tiff, 2, 42)
        putLe32(tiff, 4, 8)
        putLe16(tiff, 8, 1)
        putLe16(tiff, 10, 0x0112)
        putLe16(tiff, 12, 3)
        putLe32(tiff, 14, 1)
        putLe16(tiff, 18, orientation)
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val segment = ByteArray(4 + payload.size)
        segment[0] = 0xFF.toByte(); segment[1] = 0xE1.toByte()
        put16(segment, 2, payload.size + 2)
        payload.copyInto(segment, 4)
        return byteArrayOf(jpeg[0], jpeg[1]) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun putLe16(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte(); b[at + 1] = (v ushr 8).toByte()
    }

    private fun putLe32(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte(); b[at + 1] = (v ushr 8).toByte()
        b[at + 2] = (v ushr 16).toByte(); b[at + 3] = (v ushr 24).toByte()
    }

    private fun put16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 8).toByte(); b[at + 1] = v.toByte()
    }

    private fun put32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    private companion object {
        /** Minimal `hdrgm` packet: one Real per channel is enough for the reader. */
        val GAIN_MAP_XMP = """
<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
        hdrgm:Version="1.0"
        hdrgm:GainMapMin="0"
        hdrgm:GainMapMax="2"
        hdrgm:Gamma="1"/>
  </rdf:RDF>
</x:xmpmeta>
"""
    }
}
