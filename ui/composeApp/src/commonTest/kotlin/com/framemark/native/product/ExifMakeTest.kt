package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 字标「跟随照片品牌」的第一步：从照片里读出厂牌 [ExifMake]。
 *
 * 这一段是**两个平台共用**的解析（安卓 `ExifProbe.make` / 桌面 `PhotoMetadata.make`
 * 都只负责读文件头），所以在这里用合成字节钉住三种容器的定位，而不是只靠真机截图。
 *
 * 为什么值得单测：上一轮的症状是「选了边框，字标还是 Panasonic」—— 引擎按设计里的
 * 覆盖值画，而覆盖值一直是空的。要让覆盖值来自照片，就必须先能稳定读出 EXIF Make；
 * 读不出来时后端会安静地退回卡片自带字标，所以这条链路的失败模式极其安静，
 * 只能靠这一层拦住。
 */
class ExifMakeTest {

    // -- 合成容器 ---------------------------------------------------------------

    /** 小端/大端都能写的字节缓冲（commonTest 里不用 java.io）。 */
    private class Buf {
        private val out = ArrayList<Int>()
        fun u8(v: Int) { out += v and 0xFF }
        fun u16(v: Int, little: Boolean) {
            if (little) { u8(v); u8(v ushr 8) } else { u8(v ushr 8); u8(v) }
        }
        fun u32(v: Int, little: Boolean) {
            if (little) {
                u8(v); u8(v ushr 8); u8(v ushr 16); u8(v ushr 24)
            } else {
                u8(v ushr 24); u8(v ushr 16); u8(v ushr 8); u8(v)
            }
        }
        fun ascii(text: String) { text.forEach { u8(it.code) } }
        fun raw(bytes: ByteArray) { bytes.forEach { u8(it.toInt()) } }
        fun bytes(): ByteArray = ByteArray(out.size) { out[it].toByte() }
    }

    /** IFD0 里只有一条 `0x010F`(Make)；count ≤ 4 时值内联，否则放到 IFD 后面的偏移上。 */
    private fun tiff(make: String, little: Boolean = true): ByteArray {
        val buf = Buf()
        buf.ascii(if (little) "II" else "MM")
        buf.u16(42, little)
        buf.u32(8, little) // IFD0 紧跟 8 字节头
        buf.u16(1, little) // 一条 entry
        buf.u16(0x010F, little)
        buf.u16(2, little) // ASCII
        val count = make.length + 1
        buf.u32(count, little)
        if (count <= 4) {
            buf.ascii(make)
            repeat(4 - count) { buf.u8(0) }
        } else {
            buf.u32(26, little) // 8 头 + 2 计数 + 12 entry + 4 下一个 IFD
        }
        buf.u32(0, little) // 没有第二个 IFD
        if (count > 4) {
            buf.ascii(make)
            buf.u8(0)
        }
        return buf.bytes()
    }

    private fun jpeg(tiff: ByteArray?, withExifPrefix: Boolean = true): ByteArray {
        val buf = Buf()
        buf.u8(0xFF); buf.u8(0xD8)
        if (tiff != null) {
            val prefix = if (withExifPrefix) 6 else 0
            buf.u8(0xFF); buf.u8(0xE1); buf.u16(2 + prefix + tiff.size, false)
            if (withExifPrefix) { buf.ascii("Exif"); buf.u8(0); buf.u8(0) }
            buf.raw(tiff)
        }
        buf.u8(0xFF); buf.u8(0xD9)
        return buf.bytes()
    }

    private fun webp(tiff: ByteArray): ByteArray {
        val buf = Buf()
        buf.ascii("RIFF")
        buf.u32(4 + 8 + tiff.size + (tiff.size and 1), true)
        buf.ascii("WEBP")
        buf.ascii("EXIF")
        buf.u32(tiff.size, true)
        buf.raw(tiff)
        if (tiff.size and 1 == 1) buf.u8(0)
        return buf.bytes()
    }

    // -- 解析 -------------------------------------------------------------------

    @Test
    fun readsMakeFromJpegApp1() {
        // 手机照片最常见的那一种：JPEG + APP1 `Exif\0\0` + 小端 TIFF。
        assertEquals("OPPO", ExifMake.read(jpeg(tiff("OPPO"))))
        // 大端 TIFF 同样要读得出（老相机导出）。
        assertEquals("Canon", ExifMake.read(jpeg(tiff("Canon", little = false))))
        // 厂商名里带空格 / 填充 NUL 也要规范化成一行。
        assertEquals("HUAWEI", ExifMake.read(jpeg(tiff("HUAWEI"))))
    }

    @Test
    fun readsMakeFromTiffAndWebpContainers() {
        assertEquals("NIKON CORPORATION", ExifMake.read(tiff("NIKON CORPORATION")))
        assertEquals("OPPO", ExifMake.read(webp(tiff("OPPO"))))
    }

    @Test
    fun readsShortMakeStoredInline() {
        // count ≤ 4 的 Make 直接写在 entry 的 value 字段里，不走偏移。
        assertEquals("ABB", ExifMake.read(jpeg(tiff("ABB"))))
        assertEquals("DJI", ExifMake.read(webp(tiff("DJI"))))
    }

    @Test
    fun missingOrBlankMakeReadsAsNothing() {
        // 没有 EXIF 块（截图 / 聊天软件转存过的照片）：不是错误，就是「没有品牌」。
        assertNull(ExifMake.read(jpeg(null)))
        // 只有 APP1 但没有 `Exif\0\0` 前缀。
        assertNull(ExifMake.read(jpeg(tiff("OPPO"), withExifPrefix = false)))
        // Make 只有 NUL：有的机型会写一条空值，必须当成「没有」而不是空字符串。
        assertNull(ExifMake.read(jpeg(tiff(""))))
        // 完全不是图片。
        assertNull(ExifMake.read(ByteArray(0)))
        assertNull(ExifMake.read(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
    }

    @Test
    fun cleanNormalizesTheVendorString() {
        assertEquals("OPPO", ExifMake.clean("  OPPO\u0000  "))
        assertEquals("NIKON CORPORATION", ExifMake.clean("NIKON\u0000  CORPORATION"))
        assertNull(ExifMake.clean(null))
        assertNull(ExifMake.clean("   "))
        assertNull(ExifMake.clean("\u0000\u0000"))
    }
}
