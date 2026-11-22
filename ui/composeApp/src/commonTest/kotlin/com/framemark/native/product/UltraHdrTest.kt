package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gain-map reader is where an Ultra HDR export either works or silently
 * produces a wrong-looking HDR image, so every offset assumption is pinned here.
 *
 * The two XMP fixtures are verbatim copies of the gain map packets of real
 * files: one from OPPO's own gallery "HDR with border" export, one from
 * Lightroom Classic. The OPPO file's border was sampled pixel by pixel and is a
 * single flat colour: **R=97 G=93 B=92** — which is exactly what [UltraHdr.sentinel]
 * computes from its metadata. That equality is the whole point of this test:
 * a border filled with 0 would be about one stop too dark.
 */
class UltraHdrTest {

    @Test
    fun oppo_border_sentinel_matches_the_pixels_measured_in_the_file () {
        val properties = assertNotNull(UltraHdr.parseHdrgm(OPPO_GAIN_MAP_XMP))
        assertEquals(listOf(97, 93, 92), UltraHdr.sentinel(properties))
    }

    @Test
    fun lightroom_sentinel_is_per_channel_too () {
        val properties = assertNotNull(UltraHdr.parseHdrgm(LIGHTROOM_GAIN_MAP_XMP))
        assertEquals(listOf(98, 95, 98), UltraHdr.sentinel(properties))
    }

    @Test
    fun a_real_file_never_encodes_zero_db_as_either_extreme () {
        // 0 or 255 would mean "apply the minimum / maximum gain" to the border,
        // which is visible on an HDR display. Real camera metadata must not.
        for (xmp in listOf(OPPO_GAIN_MAP_XMP, LIGHTROOM_GAIN_MAP_XMP)) {
            val sentinel = UltraHdr.sentinel(assertNotNull(UltraHdr.parseHdrgm(xmp)))!!
            for (value in sentinel) {
                assertTrue(value in 1..254, "0 dB encoded as $value in:\n$xmp")
            }
        }
    }

    @Test
    fun a_single_real_property_broadcasts_to_all_three_channels () {
        val properties = assertNotNull(
            UltraHdr.parseHdrgm("hdrgm:GainMapMin=\"0\" hdrgm:GainMapMax=\"2\" hdrgm:Gamma=\"1\""),
        )
        assertEquals(listOf(0, 0, 0), UltraHdr.sentinel(properties))
        assertEquals(1, properties["GainMapMax"]!!.size)
    }

    @Test
    fun gamma_defaults_to_one_and_min_defaults_to_zero () {
        // Only GainMapMax is required by the spec; absent Gamma means 1.0.
        val properties = assertNotNull(UltraHdr.parseHdrgm("hdrgm:GainMapMax=\"1.5\""))
        assertEquals(listOf(0, 0, 0), UltraHdr.sentinel(properties))
    }

    @Test
    fun metadata_that_cannot_produce_a_sentinel_is_rejected () {
        assertNull(UltraHdr.sentinel(assertNotNull(UltraHdr.parseHdrgm("hdrgm:GainMapMin=\"1\""))))
        assertNull(
            UltraHdr.sentinel(
                assertNotNull(
                    UltraHdr.parseHdrgm("hdrgm:GainMapMin=\"2\" hdrgm:GainMapMax=\"2\" hdrgm:Gamma=\"1\""),
                ),
            ),
            "max == min leaves no 0 dB position",
        )
        assertNull(
            UltraHdr.sentinel(
                assertNotNull(
                    UltraHdr.parseHdrgm("hdrgm:GainMapMin=\"0\" hdrgm:GainMapMax=\"2\" hdrgm:Gamma=\"0\""),
                ),
            ),
            "gamma must be > 0",
        )
    }

    @Test
    fun the_version_is_read_verbatim_and_the_private_oplus_field_is_ignored () {
        assertEquals("1.0", UltraHdr.versionOf(OPPO_GAIN_MAP_XMP))
        val properties = assertNotNull(UltraHdr.parseHdrgm(OPPO_GAIN_MAP_XMP))
        assertTrue("OplusScale" !in properties, "vendor properties must not leak into the numeric model")
        assertTrue("Version" !in properties, "Version is text, not a gain-map number")
    }

    @Test
    fun the_container_item_length_is_read_from_the_primary_xmp () {
        val primary = jpeg(listOf(xmpSegment(CONTAINER_XMP)))
        assertEquals(3_849_052L, UltraHdr.containerLength(primary))
        assertNull(UltraHdr.containerLength(jpeg(emptyList())), "a file without XMP declares no length")
    }

    @Test
    fun locate_reads_the_appended_gain_map_end_to_end () {
        val bytes = ultraHdrJpeg(OPPO_GAIN_MAP_XMP)
        val gain = assertNotNull(UltraHdr.locate(bytes))
        assertEquals(oracleGainOffset(bytes), gain.offset)
        assertEquals(oracleGainSize(bytes), gain.size)
        assertEquals(listOf(97, 93, 92), gain.sentinel)
        assertEquals("1.0", gain.version)
        assertEquals(3, gain.channels.size)
        assertEquals(-0.4471404f, gain.channels[0].min, 1e-6f)
        assertEquals(2.6610718f, gain.channels[0].max, 1e-6f)
        assertEquals(0.49685037f, gain.channels[0].gamma, 1e-6f)
        assertTrue(gain.isMonochrome.not(), "the OPPO fixture carries three different channels")
    }

    @Test
    fun locate_rejects_files_that_are_not_ultra_hdr () {
        assertNull(UltraHdr.locate(ByteArray(0)))
        assertNull(UltraHdr.locate(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
        assertNull(UltraHdr.locate(jpeg(emptyList())), "plain JPEG, no MPF")
        assertNull(UltraHdr.locate(jpeg(listOf(xmpSegment(CONTAINER_XMP)))), "MPF missing, XMP alone is not enough")
    }

    @Test
    fun locate_rejects_a_gain_map_that_runs_past_the_end_of_the_file () {
        val bytes = ultraHdrJpeg(OPPO_GAIN_MAP_XMP)
        val truncated = bytes.copyOf(bytes.size - 8)
        assertNull(UltraHdr.locate(truncated), "a truncated appended image must not be trusted")
    }

    @Test
    fun the_assembler_terminates_xmp_and_carries_the_source_icc () {
        // XMP APP1 must be "ns.adobe.com/xap/1.0/\0" + packet; OPPO's reader ignores
        // a missing NUL. The source ICC (Display P3) must survive the re-wrap.
        val icc = byteArrayOf(0x00, 0x01, 0x02, 0x7F, 0x10, 0x20, 0x30, 0x40)
        val source = jpeg(listOf(iccSegment(icc)))
        val sdr = jpeg(emptyList())
        val gain = jpeg(emptyList())
        val metadata = UltraHdr.Metadata(xmp = OPPO_GAIN_MAP_XMP, isoPayload = null)
        val out = assertNotNull(
            UltraHdrAssembler.assemble(sdr, gain, metadata, UltraHdr.iccProfile(source)),
        )
        val xmpPrefix = "http://ns.adobe.com/xap/1.0/\u0000".encodeToByteArray()
        assertTrue(
            out.toList().windowed(xmpPrefix.size).any { it == xmpPrefix.toList() },
            "primary XMP must start with the NUL-terminated namespace",
        )
        assertEquals(icc.toList(), UltraHdr.iccProfile(out)?.toList(), "source ICC must be copied")
    }

    /**
     * 2026-11 审查：36.9 MB 主图在 128 MiB 堆上 OOM —— 封装器把整张 JPEG 逐字节塞进
     * `ArrayList<Byte>`（每字节一个装箱对象）。现在只扫段结构，再按段复制进输出缓冲区。
     * 这条用例用一张多兆的主图走同一条路径，确认输出仍然正确。
     */
    @Test
    fun a_large_primary_is_copied_segment_by_segment() {
        val metadata = UltraHdr.Metadata(xmp = OPPO_GAIN_MAP_XMP, isoPayload = null)
        val com = comSegment(0xFFFF - 2)
        val sos = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 1, 1, 0, 0, 0x3F, 0x00)
        val entropy = ByteArray(4 * 1024 * 1024) { (it * 31).toByte() }
        val sdr = ByteArray(2 + 64 * com.size + sos.size + entropy.size + 2).also { out ->
            out[0] = 0xFF.toByte(); out[1] = 0xD8.toByte()
            var at = 2
            repeat(64) { com.copyInto(out, at); at += com.size }
            sos.copyInto(out, at); at += sos.size
            entropy.copyInto(out, at); at += entropy.size
            out[at] = 0xFF.toByte(); out[at + 1] = 0xD9.toByte()
        }
        assertTrue(sdr.size > 8 * 1024 * 1024, "构造出来的主图要够大")

        val out = assertNotNull(UltraHdrAssembler.assemble(sdr, jpeg(emptyList()), metadata), "大图也必须能封装")
        val located = assertNotNull(UltraHdr.locate(out))
        assertEquals(out.size.toLong(), located.offset + located.size, "增益图必须落在文件尾")
        // 熵数据原样保留：大图没有被截断。
        val at = indexOfRange(out, entropy)
        assertTrue(at > 0, "主图的熵数据必须完整复制")
        assertTrue(entropy.contentEquals(out.copyOfRange(at, at + entropy.size)), "熵数据必须原样保留")
    }

    /**
     * 源文件自带 ICC 时换用新配置：旧 ICC 段必须被丢弃，不能出现两份配置
     * （两份会让解码器取到哪一个不确定）。
     */
    @Test
    fun replacing_the_icc_profile_never_leaves_two_profiles_behind() {
        val oldIcc = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)
        val newIcc = byteArrayOf(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16)
        val metadata = UltraHdr.Metadata(xmp = OPPO_GAIN_MAP_XMP, isoPayload = null)
        val sdr = jpeg(listOf(iccSegment(oldIcc)))
        val out = assertNotNull(
            UltraHdrAssembler.assemble(sdr, jpeg(emptyList()), metadata, newIcc),
        )
        assertEquals(newIcc.toList(), UltraHdr.iccProfile(out)?.toList(), "只有新配置")
    }

    // ------------------------------------------------------------------ builders

    /** `FFFE` + [size] bytes of 0x5A — a valid COM segment of a chosen size. */
    private fun comSegment(size: Int): ByteArray {
        val payload = ByteArray(size) { 0x5A }
        val length = size + 2
        return ByteArray(4 + size).also {
            it[0] = 0xFF.toByte(); it[1] = 0xFE.toByte()
            it[2] = (length ushr 8).toByte(); it[3] = length.toByte()
            payload.copyInto(it, 4)
        }
    }

    /** First index where [needle] appears in [haystack], or -1. */
    private fun indexOfRange(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        var i = 0
        while (i <= haystack.size - needle.size) {
            var hit = true
            for (k in needle.indices) {
                if (haystack[i + k] != needle[k]) { hit = false; break }
            }
            if (hit) return i
            i++
        }
        return -1
    }

    /** APP2 ICC chunk with the standard `ICC_PROFILE\0` + sequence/count header. */
    private fun iccSegment(profile: ByteArray): ByteArray {
        val payload = "ICC_PROFILE\u0000".encodeToByteArray() + byteArrayOf(1, 1) + profile
        val length = payload.size + 2
        val out = ByteArray(4 + payload.size)
        out[0] = 0xFF.toByte(); out[1] = 0xE2.toByte()
        out[2] = (length ushr 8).toByte(); out[3] = length.toByte()
        payload.copyInto(out, 4)
        return out
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

    /**
     * A minimal but structurally real Ultra HDR file: a primary JPEG carrying an
     * MPF index for two images, followed by the gain map JPEG.
     *
     * Layout is fixed so the expected offsets can be computed by hand:
     * SOI(2) + APP2 MPF(2 + 72) + EOI(2) = 78 bytes of primary, MPF TIFF starts
     * at byte 10, so entry[1].offset is stored as `gain start - 10`.
     */
    private fun ultraHdrJpeg(gainMapXmp: String): ByteArray {
        val gain = jpeg(listOf(xmpSegment(gainMapXmp)))
        val tiffSize = 66
        val tiffStart = 10 // SOI + marker + length + "MPF\0"
        val primarySize = 78
        val gainOffsetField = primarySize - tiffStart

        val tiff = ByteArray(tiffSize)
        tiff[0] = 0x4D; tiff[1] = 0x4D; tiff[3] = 0x2A.toByte()
        put32(tiff, 4, 8)              // IFD0 at tiff+8
        put16(tiff, 8, 2)              // two tags
        put16(tiff, 10, 0xB001); put16(tiff, 12, 4); put32(tiff, 14, 1); put32(tiff, 18, 2)
        put16(tiff, 22, 0xB002); put16(tiff, 24, 7); put32(tiff, 26, 32); put32(tiff, 30, 34)
        // entry[0]: primary, offset 0 means "the file itself"
        put32(tiff, 34, 0x20030000); put32(tiff, 38, primarySize); put32(tiff, 42, 0)
        // entry[1]: gain map, offset relative to the TIFF header
        put32(tiff, 50, 0); put32(tiff, 54, gain.size); put32(tiff, 58, gainOffsetField)

        val mpfPayload = "MPF".encodeToByteArray() + byteArrayOf(0) + tiff
        val mpfLength = mpfPayload.size + 2
        val mpf = ByteArray(4 + mpfPayload.size)
        mpf[0] = 0xFF.toByte(); mpf[1] = 0xE2.toByte()
        mpf[2] = (mpfLength ushr 8).toByte(); mpf[3] = mpfLength.toByte()
        mpfPayload.copyInto(mpf, 4)

        val out = ByteArray(primarySize + gain.size)
        out[0] = 0xFF.toByte(); out[1] = 0xD8.toByte()
        mpf.copyInto(out, 2)
        out[2 + mpf.size] = 0xFF.toByte()
        out[2 + mpf.size + 1] = 0xD9.toByte()
        gain.copyInto(out, primarySize)
        return out
    }

    private fun oracleGainOffset(bytes: ByteArray): Long = 78L
    private fun oracleGainSize(bytes: ByteArray): Long = (bytes.size - 78).toLong()

    private fun put16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 8).toByte(); b[at + 1] = v.toByte()
    }

    private fun put32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    private companion object {
        /** Gain map packet of `E:\hdr\新建文件夹\IMG_20261111_035845.jpg` (OPPO gallery). */
        const val OPPO_GAIN_MAP_XMP = """
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="XMP Core 5.5.0">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
        hdrgm:Version="1.0"
        hdrgm:OffsetSDR="0.015625"
        hdrgm:OffsetHDR="0.015625"
        hdrgm:HDRCapacityMin="0"
        hdrgm:HDRCapacityMax="2.4724426"
        hdrgm:OplusScale="6"
        hdrgm:BaseRenditionIsHDR="False">
      <hdrgm:GainMapMin>
        <rdf:Seq>
          <rdf:li>-0.4471404</rdf:li>
          <rdf:li>-0.43734297</rdf:li>
          <rdf:li>-0.70648009</rdf:li>
        </rdf:Seq>
      </hdrgm:GainMapMin>
      <hdrgm:GainMapMax>
        <rdf:Seq>
          <rdf:li>2.6610718</rdf:li>
          <rdf:li>2.1029968</rdf:li>
          <rdf:li>1.5587463</rdf:li>
        </rdf:Seq>
      </hdrgm:GainMapMax>
      <hdrgm:Gamma>
        <rdf:Seq>
          <rdf:li>0.49685037</rdf:li>
          <rdf:li>0.57529247</rdf:li>
          <rdf:li>0.87276894</rdf:li>
        </rdf:Seq>
      </hdrgm:Gamma>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
"""

        /** Gain map packet of `E:\hdr\IMG20261107123023.jpg` (Lightroom Classic 15.2). */
        const val LIGHTROOM_GAIN_MAP_XMP = """
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 7.0-c000 1.000000, 0000/00/00-00:00:00">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
   hdrgm:Version="1.0"
   hdrgm:BaseRenditionIsHDR="False"
   hdrgm:HDRCapacityMin="0"
   hdrgm:HDRCapacityMax="2.982971"
   hdrgm:OffsetSDR="0.015625"
   hdrgm:OffsetHDR="0.015625">
   <hdrgm:GainMapMin>
    <rdf:Seq>
     <rdf:li>-0.959767</rdf:li>
     <rdf:li>-0.884779</rdf:li>
     <rdf:li>-0.886733</rdf:li>
    </rdf:Seq>
   </hdrgm:GainMapMin>
   <hdrgm:GainMapMax>
    <rdf:Seq>
     <rdf:li>3.293304</rdf:li>
     <rdf:li>2.583466</rdf:li>
     <rdf:li>2.498566</rdf:li>
    </rdf:Seq>
   </hdrgm:GainMapMax>
   <hdrgm:Gamma>
    <rdf:Seq>
     <rdf:li>0.639572</rdf:li>
     <rdf:li>0.722711</rdf:li>
     <rdf:li>0.71343</rdf:li>
    </rdf:Seq>
   </hdrgm:Gamma>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
"""

        /** Primary XMP of the OPPO export (verbatim GContainer directory). */
        const val CONTAINER_XMP = """
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.2">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description
        xmlns:Container="http://ns.google.com/photos/1.0/container/"
        xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
        xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
        hdrgm:Version="1.0">
      <Container:Directory>
        <rdf:Seq>
          <rdf:li rdf:parseType="Resource">
            <Container:Item
             Item:Semantic="Primary"
             Item:Mime="image/jpeg"/>
          </rdf:li>
          <rdf:li rdf:parseType="Resource">
            <Container:Item
             Item:Semantic="GainMap"
             Item:Mime="image/jpeg"
             Item:Length="3849052"/>
          </rdf:li>
        </rdf:Seq>
      </Container:Directory>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
"""
    }
}
