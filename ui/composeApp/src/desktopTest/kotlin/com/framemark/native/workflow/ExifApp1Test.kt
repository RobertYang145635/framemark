package com.framemark.native.workflow

import com.framemark.native.product.ExifMake
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 转码导入的拍摄信息搬运（审计 2026-11-20）：TIFF / WebP 导入后工程里只有一份重新编码
 * 的 JPEG，相机厂商、机型、光圈全没了 —— 字标跟不到品牌，机型行与参数行是空的。
 *
 * 这里钉三件事：能把源图的 EXIF 组装成 APP1 插进 JPEG（下游读得到）、插入是幂等的、
 * 16 位灰度/带 alpha 的像素能转成 JPEG 写得出的 8 位 RGB（以前直接写失败、报「文件不存在」）。
 */
class ExifApp1Test {

    // -- 夹具：手写一个最小但合法的单像素灰阶 TIFF（小端） ----------------------

    /** 小端 TIFF：1×1 灰阶像素 + IFD0（Make/Model）+ ExifIFD（FNumber/ExposureTime）。 */
    private fun minimalTiff(make: String, model: String): ByteArray {
        val entries = mutableListOf<ByteArray>()
        fun entry(tag: Int, type: Int, count: Int, value: ByteArray) {
            require(value.size <= 4)
            entries += le16(tag) + le16(type) + le32(count.toLong()) + value + ByteArray(4 - value.size)
        }
        // IFD0 的布局：头(8) + count(2) + entries + next(4) + 数据区
        val ifd0Count = 10
        var dataOffset = 8 + 2 + ifd0Count * 12 + 4
        val data = java.io.ByteArrayOutputStream()

        fun asciiOnHeap(text: String): ByteArray {
            val bytes = text.toByteArray(Charsets.ISO_8859_1) + 0
            val offset = dataOffset + data.size()
            data.write(bytes)
            if (data.size() and 1 == 1) data.write(0)
            return le32(offset.toLong())
        }
        fun rationalOnHeap(num: Long, den: Long): ByteArray {
            val offset = dataOffset + data.size()
            data.write(le32(num)); data.write(le32(den))
            return le32(offset.toLong())
        }

        val makeBytes = make.toByteArray(Charsets.ISO_8859_1) + 0
        val modelBytes = model.toByteArray(Charsets.ISO_8859_1) + 0
        val makeOffset = asciiOnHeap(make)
        val modelOffset = asciiOnHeap(model)
        val fnumberOffset = rationalOnHeap(28, 10)
        val exposureOffset = rationalOnHeap(1, 200)
        // ExifIFD 放在数据区之后
        val exifIfdOffset = dataOffset + data.size()
        val exifIfd = java.io.ByteArrayOutputStream().apply {
            write(le16(2))
            write(le16(0x829D)); write(le16(5)); write(le32(1L)); write(fnumberOffset)      // FNumber 2.8
            write(le16(0x829A)); write(le16(5)); write(le32(1L)); write(exposureOffset)    // 1/200
            write(le32(0))
        }.toByteArray()
        data.write(exifIfd)

        entry(0x010F, 2, makeBytes.size, makeOffset)                       // Make
        entry(0x0110, 2, modelBytes.size, modelOffset)                     // Model
        entry(0x0112, 3, 1, le16(1))                                       // Orientation 1
        entry(0x8769, 4, 1, le32(exifIfdOffset.toLong()))                           // ExifIFD 指针
        entry(0x0100, 4, 1, le32(1L))                                       // ImageWidth
        entry(0x0101, 4, 1, le32(1L))                                       // ImageLength
        entry(0x0102, 3, 1, le16(8))                                       // BitsPerSample
        entry(0x0103, 3, 1, le16(1))                                       // Compression none
        entry(0x0106, 3, 1, le16(1))                                       // Photometric BlackIsZero
        entry(0x0111, 4, 1, le32((dataOffset + data.size()).toLong()))                 // StripOffsets

        val ifd = java.io.ByteArrayOutputStream().apply {
            write(le16(entries.size))
            entries.forEach { write(it) }
            write(le32(0))
        }
        val stripOffset = dataOffset + data.size()
        // 重新算：StripOffsets 已经写进 entries（在 data 之前），这里只补像素字节
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0, 8, 0, 0, 0))
        out.write(ifd.toByteArray())
        out.write(data.toByteArray())
        check(out.size() <= stripOffset) { "layout: ${out.size()} <= $stripOffset" }
        out.write(byteArrayOf(0x7F)) // 1 个像素
        return out.toByteArray()
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(value: Long) = byteArrayOf(
        (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte(),
    )

    private fun tempFile(name: String, bytes: ByteArray): java.io.File {
        val dir = java.nio.file.Files.createTempDirectory("fm-exif-app1").toFile()
        return java.io.File(dir, name).apply { writeBytes(bytes) }
    }

    private fun tinyJpeg(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        val out = java.io.ByteArrayOutputStream()
        assertTrue(javax.imageio.ImageIO.write(image, "jpg", out), "fixture jpeg")
        return out.toByteArray()
    }

    // -- 用例 -----------------------------------------------------------------

    @Test
    fun aTiffIsCarriedIntoTheTranscodedJpeg() {
        val tiff = tempFile("photo.tiff", minimalTiff("OPPO", "Find X6 Pro"))
        val app1 = assertNotNull(ExifApp1.fromSource(tiff), "TIFF 的拍摄信息应该读得出来")
        val jpeg = ExifApp1.inject(tinyJpeg(), app1)
        assertEquals("OPPO", ExifMake.read(jpeg), "转码副本上应该读得到厂商")
    }

    @Test
    fun injectingTwiceDoesNotStackTwoExifBlocks() {
        val tiff = tempFile("photo2.tiff", minimalTiff("Panasonic", "DC-S1"))
        val app1 = assertNotNull(ExifApp1.fromSource(tiff))
        val once = ExifApp1.inject(tinyJpeg(), app1)
        val twice = ExifApp1.inject(once, app1)
        assertEquals(once.size, twice.size, "已经带 EXIF 的 JPEG 不再插第二段")
        assertEquals("Panasonic", ExifMake.read(twice))
    }

    @Test
    fun aWebpExifChunkIsCarriedAsIs() {
        // RIFF/WEBP + 一个 EXIF 块（内容是一段小端 TIFF）
        val tiff = minimalTiff("NIKON CORPORATION", "NIKON Z 8")
        val chunk = tiff
        val riffSize = 4 + 8 + chunk.size + (chunk.size and 1)
        val body = java.io.ByteArrayOutputStream().apply {
            write("WEBP".toByteArray(Charsets.US_ASCII))
            write("EXIF".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(
                (chunk.size and 0xFF).toByte(), ((chunk.size shr 8) and 0xFF).toByte(),
                ((chunk.size shr 16) and 0xFF).toByte(), ((chunk.size shr 24) and 0xFF).toByte(),
            ))
            write(chunk)
            if (chunk.size and 1 == 1) write(0)
        }.toByteArray()
        require(body.size == riffSize)
        val webp = java.io.ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(
                (riffSize and 0xFF).toByte(), ((riffSize shr 8) and 0xFF).toByte(),
                ((riffSize shr 16) and 0xFF).toByte(), ((riffSize shr 24) and 0xFF).toByte(),
            ))
            write(body)
        }.toByteArray()
        val file = tempFile("photo.webp", webp)
        val app1 = assertNotNull(ExifApp1.fromSource(file), "WebP 的 EXIF 块应该搬得出来")
        val jpeg = ExifApp1.inject(tinyJpeg(), app1)
        assertEquals("NIKON CORPORATION", ExifMake.read(jpeg))
    }

    @Test
    fun aFileWithoutMetadataYieldsNoBlock() {
        val plain = tempFile("plain.tiff", byteArrayOf(0, 1, 2, 3))
        assertNull(ExifApp1.fromSource(plain), "坏文件不能抛，返回 null")
        assertNull(ExifApp1.fromSource(tempFile("x.png", tinyJpeg())))
    }

    @Test
    fun sixteenBitGreyAndArgbBecomeJpegWritableRgb() {
        val grey = BufferedImage(4, 4, BufferedImage.TYPE_USHORT_GRAY)
        assertEquals(BufferedImage.TYPE_INT_RGB, ExifApp1.toRgb(grey).type)
        val argb = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        val rgb = ExifApp1.toRgb(argb)
        assertEquals(BufferedImage.TYPE_INT_RGB, rgb.type)
        // 两种都能被 JPEG 写出去了（以前 ImageIO.write 返回 false、不留文件）
        val out = java.io.ByteArrayOutputStream()
        assertTrue(javax.imageio.ImageIO.write(rgb, "jpg", out))
        assertTrue(out.size() > 0)
    }
}
