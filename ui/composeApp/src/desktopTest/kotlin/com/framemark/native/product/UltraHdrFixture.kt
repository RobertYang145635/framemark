package com.framemark.native.product

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * A **decodable** Ultra HDR JPEG for tests: a real ImageIO primary, a real
 * ImageIO gain map that the compositor can actually decode, and a real MPF
 * index (CIPA DC-x 007) joining them — plus an optional EXIF Orientation tag on
 * the primary.
 *
 * The byte-skeleton helper in `UltraHdrExportSourceRectTest` is enough for the
 * reader (`UltraHdr.locate`), but a test of the *commit* path or of the gain-map
 * compositor needs an appended image `ImageIO.read` accepts. This fixture is
 * that file: everything is synthesized in memory, so the tests do not depend on
 * `E:\hdr` or any other machine-local sample directory.
 */
internal object UltraHdrFixture {

    /**
     * Ultra HDR JPEG of [width] x [height] pixels with a half-size gain map.
     *
     * [orientation] 1..8 injects that EXIF Orientation into the primary (null or
     * 1 leaves the file orientation-free). The primary's SOF dimensions are the
     * *raw* raster, exactly like a camera file: a caller that reads Orientation
     * has to swap them for the display frame, which is what the export path is
     * expected to do.
     */
    fun build(width: Int = 120, height: Int = 80, orientation: Int? = null): ByteArray {
        require(width >= 8 && height >= 8) { "fixture must be large enough for a gain map" }
        val rawPrimary = encodeJpeg(width, height, Color(0xB4, 0x8C, 0x64))
        val gainMap = injectAfterSoi(
            encodeJpeg(width / 2, height / 2, Color(128, 128, 128)),
            xmpSegment(GAIN_MAP_XMP),
        )
        val exif = orientation
            ?.takeIf { it in 2..8 }
            ?.let { exifOrientationSegment(it) }
        val head = if (exif != null) byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + exif
        else byteArrayOf(0xFF.toByte(), 0xD8.toByte())

        // The MPF index needs the final primary length and the gain map's absolute
        // start before it can be written; both are known once the segment sizes are
        // fixed (the TIFF block has a constant size for two images).
        val tiffSize = 82
        val mpfSegmentSize = 4 + 4 + tiffSize // marker+length, "MPF\0", TIFF
        val primarySize = head.size + mpfSegmentSize + (rawPrimary.size - 2)
        // TIFF header starts after SOI + injected EXIF + the APP2 marker/length and
        // the four "MPF\0" bytes: head(SOI+EXIF) + 4 + 4.
        val tiffStart = head.size + 8
        val gainOffset = primarySize - tiffStart
        val mpf = mpfSegment(primarySize, gainMap.size, gainOffset, tiffSize)

        val out = ByteArray(primarySize + gainMap.size)
        head.copyInto(out, 0)
        mpf.copyInto(out, head.size)
        rawPrimary.copyInto(out, head.size + mpf.size, 2, rawPrimary.size)
        gainMap.copyInto(out, primarySize)
        return out
    }

    /** Frame header of a real JPEG, as the export reader would see it. */
    fun encodeJpeg(width: Int, height: Int, color: Color): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = color
            g.fillRect(0, 0, width, height)
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpeg", out)
        return out.toByteArray()
    }

    private fun injectAfterSoi(jpeg: ByteArray, segment: ByteArray): ByteArray {
        require(jpeg.size > 2) { "not a JPEG" }
        return byteArrayOf(jpeg[0], jpeg[1]) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    /**
     * APP2 MPF index with two entries: the primary (offset 0 = the file itself)
     * and the appended gain map. Offsets are relative to the TIFF header, which
     * `UltraHdr.mpfEntries` pins.
     */
    private fun mpfSegment(
        primarySize: Int,
        gainSize: Int,
        gainOffset: Int,
        tiffSize: Int,
    ): ByteArray {
        val tiff = ByteArray(tiffSize)
        tiff[0] = 'M'.code.toByte()
        tiff[1] = 'M'.code.toByte()
        put16(tiff, 2, 42)
        put32(tiff, 4, 8)             // IFD0 at tiff+8
        put16(tiff, 8, 3)             // three tags
        // 0xB000 MPFVersion ("0100", UNDEFINED 4)
        put16(tiff, 10, 0xB000); put16(tiff, 12, 7); put32(tiff, 14, 4)
        tiff[18] = '0'.code.toByte(); tiff[19] = '1'.code.toByte()
        tiff[20] = '0'.code.toByte(); tiff[21] = '0'.code.toByte()
        // 0xB001 NumberOfImages
        put16(tiff, 22, 0xB001); put16(tiff, 24, 4); put32(tiff, 26, 1); put32(tiff, 30, 2)
        // 0xB002 MPEntry list (2 × 16 bytes) at tiff+50
        put16(tiff, 34, 0xB002); put16(tiff, 36, 7); put32(tiff, 38, 32); put32(tiff, 42, 50)
        // entry[0]: primary, absolute offset 0 means "this file"
        put32(tiff, 54, primarySize)
        // entry[1]: gain map, offset relative to the TIFF header
        put32(tiff, 66, 0x00030000);
        put32(tiff, 70, gainSize)
        put32(tiff, 74, gainOffset)

        val payload = "MPF".encodeToByteArray() + byteArrayOf(0) + tiff
        val length = payload.size + 2
        val segment = ByteArray(4 + payload.size)
        segment[0] = 0xFF.toByte(); segment[1] = 0xE2.toByte()
        put16(segment, 2, length)
        payload.copyInto(segment, 4)
        return segment
    }

    /** APP1 XMP (`http://ns.adobe.com/xap/1.0/` + packet) for the gain map. */
    private fun xmpSegment(xmp: String): ByteArray {
        val body = "http://ns.adobe.com/xap/1.0/".encodeToByteArray() + xmp.encodeToByteArray()
        val segment = ByteArray(4 + body.size)
        segment[0] = 0xFF.toByte(); segment[1] = 0xE1.toByte()
        put16(segment, 2, body.size + 2)
        body.copyInto(segment, 4)
        return segment
    }

    /** APP1 `Exif\0\0` + little-endian TIFF whose IFD0 carries Orientation. */
    fun exifOrientationSegment(orientation: Int): ByteArray {
        val tiff = ByteArray(26)
        tiff[0] = 'I'.code.toByte(); tiff[1] = 'I'.code.toByte()
        putLe16(tiff, 2, 42)
        putLe32(tiff, 4, 8)
        putLe16(tiff, 8, 1)                  // one tag
        putLe16(tiff, 10, 0x0112)            // Orientation
        putLe16(tiff, 12, 3)                 // SHORT
        putLe32(tiff, 14, 1)                 // count
        putLe16(tiff, 18, orientation)       // value inline, padded to 4 bytes
        // next-IFD offset at 22 stays 0
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val segment = ByteArray(4 + payload.size)
        segment[0] = 0xFF.toByte(); segment[1] = 0xE1.toByte()
        put16(segment, 2, payload.size + 2)
        payload.copyInto(segment, 4)
        return segment
    }

    private fun put16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 8).toByte(); b[at + 1] = v.toByte()
    }

    private fun put32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    private fun putLe16(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte(); b[at + 1] = (v ushr 8).toByte()
    }

    private fun putLe32(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte(); b[at + 1] = (v ushr 8).toByte()
        b[at + 2] = (v ushr 16).toByte(); b[at + 3] = (v ushr 24).toByte()
    }

    /** Minimal `hdrgm` packet: one Real per channel is enough for the reader. */
    private val GAIN_MAP_XMP = """
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
""".trimIndent()
}
