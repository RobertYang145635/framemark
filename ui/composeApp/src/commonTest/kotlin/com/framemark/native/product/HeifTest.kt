package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HEIC / HEIF 元数据读取（`Heif`）。
 *
 * 真机 HEIC 不能进仓库，这里用**合成的 ISO-BMFF** 逐盒覆盖：Exif 项、Orientation、
 * 侧车 JPEG 的形状，以及 HDR 信号（hvcC 位深、nclx 传递函数、Apple 增益图 URN、tmap）。
 * 解析器的每个盒都必须能从这些字节里读出来，否则真机只会表现为「模板 EXIF 全空」。
 */
class HeifTest {

    // ---- synthetic containers --------------------------------------------------

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        be32(out, 0, out.size)
        type.encodeToByteArray().copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun fullBox(type: String, version: Int, payload: ByteArray): ByteArray {
        val head = byteArrayOf(version.toByte(), 0, 0, 0)
        return box(type, head + payload)
    }

    /** 一个最小 TIFF：II 头 + IFD0（Orientation + Make 两个标签）。 */
    private fun tiff(orientation: Int): ByteArray {
        val make = "OPPO".encodeToByteArray()
        val entries = 2
        val ifdSize = 2 + entries * 12 + 4
        val makeOffset = 8 + ifdSize
        val out = ByteArray(makeOffset + make.size + 1)
        out[0] = 'I'.code.toByte(); out[1] = 'I'.code.toByte()
        le16(out, 2, 42)
        le32(out, 4, 8)
        le16(out, 8, entries)
        var p = 10
        // Orientation (SHORT)
        le16(out, p, 0x0112); le16(out, p + 2, 3); le32(out, p + 4, 1)
        le16(out, p + 8, orientation); le16(out, p + 10, 0)
        p += 12
        // Make (ASCII)
        le16(out, p, 0x010F); le16(out, p + 2, 2); le32(out, p + 4, make.size + 1)
        le32(out, p + 8, makeOffset)
        p += 12
        le32(out, p, 0)
        make.copyInto(out, makeOffset)
        out[out.size - 1] = 0
        return out
    }

    private fun exifItem(t: ByteArray): ByteArray {
        // 4 字节偏移（指向 TIFF 头）+ "Exif\0\0" + TIFF。
        val out = ByteArray(4 + 6 + t.size)
        be32(out, 0, 6 + 4 - 4 + 4) // offset = 6? see tiffStart logic below
        "Exif\u0000\u0000".encodeToByteArray().copyInto(out, 4)
        t.copyInto(out, 10)
        // Heif 读的是「从项起始的偏移」，TIFF 在 10，故偏移写 10。
        be32(out, 0, 10)
        return out
    }

    private fun heif(
        exif: ByteArray? = null,
        hvcDepth: Int? = null,
        transfer: Int? = null,
        auxUrn: String? = null,
        tmap: Boolean = false,
    ): ByteArray {
        val idat = exif?.let { box("idat", it) } ?: ByteArray(0)
        val infe = fullBox("infe", 2, buildInfe(100, "Exif"))
        val iinf = fullBox("iinf", 0, u16(1) + infe)
        // iloc v1：construction_method=1（idat），offset 0，length = exif.size。
        val ilocPayload = buildIlocV1(itemId = 100, length = exif?.size ?: 0, idatOffset = 0, idatPresent = exif != null)
        val iloc = fullBox("iloc", 1, ilocPayload)

        val ipcoChildren = ArrayList<ByteArray>()
        if (hvcDepth != null) ipcoChildren += box("hvcC", hvcC(hvcDepth))
        if (transfer != null) ipcoChildren += box("colr", "nclx".encodeToByteArray() + u16(9) + u16(transfer) + u16(9) + byteArrayOf(0))
        if (auxUrn != null) ipcoChildren += fullBox("auxC", 0, auxUrn.encodeToByteArray() + byteArrayOf(0))
        val ipco = if (ipcoChildren.isEmpty()) ByteArray(0) else box("ipco", concat(ipcoChildren))
        val iprp = if (ipco.isEmpty()) ByteArray(0) else box("iprp", ipco)
        val tmapBox = if (tmap) box("tmap", ByteArray(8)) else ByteArray(0)

        val meta = fullBox(
            "meta",
            0,
            box("hdlr", ByteArray(24)) +
                iinf + iloc + iprp + tmapBox + idat,
        )
        return box("ftyp", "heic".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) + "mif1heic".encodeToByteArray()) + meta
    }

    private fun buildInfe(id: Int, type: String): ByteArray =
        u16(id) + u16(0) + type.encodeToByteArray() + byteArrayOf(0) + ByteArray(32)

    private fun buildIlocV1(itemId: Int, length: Int, idatOffset: Int, idatPresent: Boolean): ByteArray {
        // offset_size=4, length_size=4, base_offset_size=0, index_size=0
        val out = ByteArray(0)
        var payload = byteArrayOf(0x44, 0x00) // sizes
        payload += u16(1) // item_count
        payload += u16(itemId)
        payload += u16(1) // construction_method = 1 (idat)
        payload += u16(0) // data_reference_index
        payload += u16(1) // extent_count
        payload += be32Bytes(idatOffset)
        payload += be32Bytes(length)
        return payload
    }

    private fun hvcC(depth: Int): ByteArray {
        val out = ByteArray(23)
        out[0] = 1
        out[17] = ((depth - 8) and 0x07).toByte()
        out[18] = ((depth - 8) and 0x07).toByte()
        return out
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }

    private fun u16(v: Int): ByteArray = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun be32Bytes(v: Int): ByteArray = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun be32(b: ByteArray, at: Int, v: Int) {
        b[at] = ((v shr 24) and 0xFF).toByte()
        b[at + 1] = ((v shr 16) and 0xFF).toByte()
        b[at + 2] = ((v shr 8) and 0xFF).toByte()
        b[at + 3] = (v and 0xFF).toByte()
    }

    private fun le16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun le32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
        b[at + 2] = ((v shr 16) and 0xFF).toByte()
        b[at + 3] = ((v shr 24) and 0xFF).toByte()
    }

    // ---- tests -----------------------------------------------------------------

    @Test
    fun recognisesHeifFromFtyp() {
        assertTrue(Heif.isHeif(heif()))
        assertFalse(Heif.isHeif(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertNull(Heif.hdrInfo(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun readsTheExifItemAndItsOrientation() {
        val bytes = heif(exif = exifItem(tiff(6)))
        val block = assertNotNull(Heif.exifBlock(bytes), "Exif item should be found")
        assertEquals(6, block.orientation)
        assertTrue(block.tiff.size > 8)
        assertEquals('I'.code.toByte(), block.tiff[0])
        assertEquals('I'.code.toByte(), block.tiff[1])
    }

    @Test
    fun exifItemWithoutTiffHeaderIsRejected() {
        // 偏移指到数据外：必须返回 null，而不是把随机字节当 TIFF。
        val bad = ByteArray(12) { 0 }
        assertNull(Heif.exifBlock(heif(exif = bad)))
    }

    @Test
    fun sidecarIsAnApp1OnlyJpegWithOrientationReset() {
        val block = assertNotNull(Heif.exifBlock(heif(exif = exifItem(tiff(6)))))
        val jpeg = Heif.exifSidecarJpeg(block.tiff)
        assertEquals(0xFF, jpeg[0].toInt() and 0xFF)
        assertEquals(0xD8, jpeg[1].toInt() and 0xFF)
        assertEquals(0xFF, jpeg[2].toInt() and 0xFF)
        assertEquals(0xE1, jpeg[3].toInt() and 0xFF)
        val exif = "Exif\u0000\u0000".encodeToByteArray()
        assertTrue(jpeg.copyOfRange(6, 6 + exif.size).contentEquals(exif))
        // 侧车里的 Orientation 必须是 1：像素已在导入时旋正，不能转第二次。
        val tiffStart = 6 + exif.size
        assertEquals(1, Heif.orientationOf(jpeg.copyOfRange(tiffStart, jpeg.size - 2)))
        assertEquals(0xFF, jpeg[jpeg.size - 2].toInt() and 0xFF)
        assertEquals(0xD9, jpeg[jpeg.size - 1].toInt() and 0xFF)
    }

    @Test
    fun detectsTenBitHdrAndTransferFunction() {
        val tenBit = assertNotNull(Heif.hdrInfo(heif(hvcDepth = 10)))
        assertEquals(10, tenBit.bitDepth)
        assertTrue(tenBit.isHdr)

        val eightBit = assertNotNull(Heif.hdrInfo(heif(hvcDepth = 8)))
        assertFalse(eightBit.isHdr)
        assertEquals(8, eightBit.bitDepth)

        val pq = assertNotNull(Heif.hdrInfo(heif(hvcDepth = 10, transfer = 16)))
        assertEquals(16, pq.transfer)
        assertTrue(pq.isHdr)
    }

    @Test
    fun detectsGainMapAuxAndToneMap() {
        val apple = assertNotNull(
            Heif.hdrInfo(heif(auxUrn = "urn:com:apple:photo:2018:aux:hdrgainmap")),
        )
        assertEquals("urn:com:apple:photo:2018:aux:hdrgainmap", apple.gainMapUrn)
        assertTrue(apple.isHdr)

        val iso = assertNotNull(
            Heif.hdrInfo(heif(auxUrn = "urn:iso:std:iso:ts:21496:-1")),
        )
        assertEquals("urn:iso:std:iso:ts:21496:-1", iso.gainMapUrn)

        val toneMap = assertNotNull(Heif.hdrInfo(heif(tmap = true)))
        assertTrue(toneMap.hasToneMap)
        assertTrue(toneMap.isHdr)

        val plain = assertNotNull(Heif.hdrInfo(heif(hvcDepth = 8)))
        assertNull(plain.gainMapUrn)
        assertFalse(plain.hasToneMap)
    }
}
