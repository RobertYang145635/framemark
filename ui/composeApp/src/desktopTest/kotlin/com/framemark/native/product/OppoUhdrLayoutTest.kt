package com.framemark.native.product

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OPPO ProXDR（`local.uhdr.*`）布局的读取（2026-11-21）。
 *
 * 真机反馈：相册里显示 ProXDR 的照片，导出面板却说「没有 HDR 增益图」。那份文件尾部挂着
 * 增益图 + 80 字节浮点参数 + JSON 目录（`jxrs` 魔数），**没有** MPF / APP2 / XMP，所以
 * 标准 Ultra HDR 读取器看不到它。这里用合成文件把这个布局钉住：
 *
 *  - 定位：增益图 JPEG 的绝对偏移与长度必须与目录一致；
 *  - 参数：80 字节里 `[0..2]` 是线性增益下限、`[4..6]` 上限、`[7..9]` gamma，
 *    读出来要换算成 hdrgm 的 log2 口径；
 *  - 元数据：源文件没有 XMP，`metadata()` 要按 channels 合成一份，封装链路才走得通；
 *  - 往返：用它封装出来的文件，标准读取器（MPF + XMP）要能再次找到增益图。
 */
class OppoUhdrLayoutTest {

    @Test
    fun locatesTheGainMapInsideTheOppoTrailer() {
        val file = fixture()
        val gainMap = assertNotNull(UltraHdr.locate(file), "OPPO 尾挂布局要能识别出增益图")

        assertEquals(fixturePrimarySize, gainMap.offset.toInt(), "增益图紧跟在主图之后")
        assertEquals(fixtureGainSize, gainMap.size.toInt())
        // 4.926 的线性上限 → hdrgm 的 log2 口径约 2.301
        assertEquals(2.3013f, gainMap.channels[0].max, 0.01f)
        assertEquals(0f, gainMap.channels[0].min, 0.01f)
        assertEquals(1f, gainMap.channels[0].gamma, 0.01f)
        assertEquals("oppo-uhdr", gainMap.version)
        assertTrue(gainMap.sentinel.size == 3)
    }

    @Test
    fun synthesizesHdrgmMetadataForTheWrapper() {
        val file = fixture()
        val gainMap = assertNotNull(UltraHdr.locate(file))
        val metadata = assertNotNull(UltraHdr.metadata(file, gainMap), "没有 XMP 时要合成一份")

        assertTrue(metadata.xmp.contains("hdrgm:Version"), metadata.xmp)
        assertTrue(metadata.xmp.contains("GainMapMax"), metadata.xmp)
        val properties = assertNotNull(UltraHdr.parseHdrgm(metadata.xmp))
        assertEquals(2.3013f, properties["GainMapMax"]!!.first(), 0.01f)
        assertNull(metadata.isoPayload, "OPPO 源没有 ISO 21496 的二进制 payload")
    }

    @Test
    fun theWrappedExportIsReadableByTheStandardReader() {
        val file = fixture()
        val gainMap = assertNotNull(UltraHdr.locate(file))
        val metadata = assertNotNull(UltraHdr.metadata(file, gainMap))
        val gain = file.copyOfRange(gainMap.offset.toInt(), (gainMap.offset + gainMap.size).toInt())

        val wrapped = assertNotNull(
            UltraHdrAssembler.assemble(
                sdrJpeg = jpeg(40, 30, Color(0x6A, 0x8A, 0xAA)),
                gainMapJpeg = gain,
                metadata = metadata,
            ),
            "带合成元数据的封装要成功",
        )
        val reread = assertNotNull(UltraHdr.locate(wrapped), "封装结果要能被标准读取器认出来")
        assertEquals("1.0", reread.version, "重新封装后走标准 Ultra HDR 口径")
        assertTrue(reread.size > 0)
    }

    @Test
    fun aPlainJpegIsStillNotHdr() {
        assertNull(UltraHdr.locate(jpeg(40, 30, Color.GRAY)), "没有尾挂目录的 JPEG 不能被当成 HDR")
    }

    // ------------------------------------------------------------------ fixture

    private var fixturePrimarySize = 0
    private var fixtureGainSize = 0

    /** [主图][增益图][80 字节参数][JSON 目录][NUL][jxrs 01 00 00]，与真机文件同构。 */
    private fun fixture(): ByteArray {
        val primary = jpeg(64, 48, Color(0x8C, 0x6A, 0x4A))
        val gain = jpeg(32, 24, Color(100, 100, 100))
        fixturePrimarySize = primary.size
        fixtureGainSize = gain.size

        val info = ByteArray(80)
        putFloat(info, 0, 1.0f)
        putFloat(info, 4, 1.0f)
        putFloat(info, 8, 1.0f)
        putFloat(info, 12, 1.0f)
        putFloat(info, 16, 4.926108f)
        putFloat(info, 20, 4.926108f)
        putFloat(info, 24, 4.926108f)
        putFloat(info, 28, 1.0f)
        putFloat(info, 32, 1.0f)
        putFloat(info, 36, 1.0f)

        val dirStart = primary.size + gain.size + info.size
        val dataOffset = dirStart - primary.size
        val infoOffset = dirStart - (primary.size + gain.size)
        val json = "[{\"length\":4300,\"name\":\"hdr.transform.data\",\"offset\":${dataOffset + 4300}," +
            "\"version\":1},{\"length\":${gain.size},\"name\":\"local.uhdr.gainmap.data\"," +
            "\"offset\":$dataOffset,\"version\":1},{\"length\":${info.size}," +
            "\"name\":\"local.uhdr.gainmap.info\",\"offset\":$infoOffset,\"version\":1}]"

        val out = ByteArrayOutputStream()
        out.write(primary)
        out.write(gain)
        out.write(info)
        out.write(json.toByteArray(Charsets.UTF_8))
        out.write(byteArrayOf(0, 'j'.code.toByte(), 'x'.code.toByte(), 'r'.code.toByte(), 's'.code.toByte(), '-'.code.toByte(), 1, 0, 0))
        return out.toByteArray()
    }

    private fun putFloat(target: ByteArray, at: Int, value: Float) {
        val bits = value.toRawBits()
        target[at] = (bits and 0xFF).toByte()
        target[at + 1] = ((bits shr 8) and 0xFF).toByte()
        target[at + 2] = ((bits shr 16) and 0xFF).toByte()
        target[at + 3] = ((bits shr 24) and 0xFF).toByte()
    }

    private fun jpeg(width: Int, height: Int, color: Color): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        val bytes = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", bytes)
        return bytes.toByteArray()
    }
}
