package com.framemark.native.product

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 用真机 HEIC 复核 `Heif`：合成字节只能证明盒子读得对，证明不了 OPPO/Apple 的真实
 * 写入顺序。样本不进仓库（每张 2–6 MB），所以这里走外部目录：
 *
 *  - `FM_HEIC_SAMPLES` 环境变量，或
 *  - 审计目录 `E:\FrameMark\audit-20261115\device-qa\heic-samples`（本轮从手机拉下来的）。
 *
 * 目录不存在时整类跳过 —— 不把"没有样本"伪装成通过。
 */
class HeifRealSampleTest {

    private fun samples(): List<File> {
        val roots = listOfNotNull(
            System.getenv("FM_HEIC_SAMPLES")?.takeIf { it.isNotBlank() }?.let(::File),
            File("E:/FrameMark/audit-20261115/device-qa/heic-samples"),
        )
        return roots.flatMap { root ->
            root.listFiles { f -> f.isFile && f.extension.equals("heic", ignoreCase = true) }?.toList() ?: emptyList()
        }.sortedBy { it.name }
    }

    @Test
    fun everyRealHeicYieldsAnExifBlockAndASidecar() {
        val files = samples()
        if (files.isEmpty()) {
            println("HEIF_REAL_SKIP no samples")
            return
        }
        for (file in files) {
            val bytes = file.readBytes()
            assertTrue(Heif.isHeif(bytes), "${file.name} must be recognised as HEIF")
            val block = assertNotNull(Heif.exifBlock(bytes), "${file.name}: Exif item missing")
            assertTrue(
                block.orientation in 1..8,
                "${file.name}: orientation ${block.orientation}",
            )
            assertTrue(
                block.tiff.size > 8 &&
                    ((block.tiff[0] == 'I'.code.toByte() && block.tiff[1] == 'I'.code.toByte()) ||
                        (block.tiff[0] == 'M'.code.toByte() && block.tiff[1] == 'M'.code.toByte())),
                "${file.name}: TIFF header not at the declared offset",
            )
            val sidecar = Heif.exifSidecarJpeg(block.tiff, orientation = 1)
            val prefix = "Exif\u0000\u0000".encodeToByteArray()
            assertTrue(
                sidecar.size > prefix.size + 8 &&
                    sidecar.copyOfRange(6, 6 + prefix.size).contentEquals(prefix),
                "${file.name}: sidecar is not an Exif APP1 JPEG",
            )
        }
        println("HEIF_REAL files=${files.size}")
    }

    @Test
    fun theKnownTenBitSamplesAreFlaggedAsHdr() {
        val files = samples()
        if (files.isEmpty()) {
            println("HEIF_REAL_SKIP no samples")
            return
        }
        val depths = files.associate { file ->
            file.name to assertNotNull(Heif.hdrInfo(file.readBytes()), "${file.name}: no hdr info").bitDepth
        }
        println("HEIF_REAL_DEPTHS=$depths")
        // 从 PGEM10 拉下来的样本里同时有 8-bit 与 10-bit；至少一张 10-bit 必须被判 HDR，
        // 8-bit 样本则不能被误判（否则导出会假装能保留 HDR）。
        assertTrue(depths.values.any { it >= 10 }, "no 10-bit sample found: $depths")
        val tenBit = files.first { depths.getValue(it.name) >= 10 }
        assertTrue(assertNotNull(Heif.hdrInfo(tenBit.readBytes())).isHdr, "${tenBit.name} must be HDR")
        files.filter { depths.getValue(it.name) < 10 }.forEach { file ->
            assertEquals(false, assertNotNull(Heif.hdrInfo(file.readBytes())).isHdr, "${file.name} is 8-bit")
        }
    }
}
