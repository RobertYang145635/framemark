package com.framemark.native.shell

import com.framemark.native.shell.desktop.ExportCopy
import com.framemark.native.shell.desktop.ExportFileName
import com.framemark.native.shell.desktop.ExportNameToken
import com.framemark.native.shell.desktop.ExportPixels
import com.framemark.native.shell.desktop.ExportRules
import com.framemark.native.shell.desktop.ExportEstimate
import com.framemark.native.workflow.ExportNaming
import com.framemark.native.workflow.ExportSizeMode
import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.WorkflowPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP-D2 导出对话框的纯逻辑：文件名变量（每个变量 / 非法字符 / 重名）、参数校验
 * （质量 60–100、长边 1–20000）、按格式写准确的文案、尺寸与体积估算。
 */
class DesktopExportDialogTest {

    // ---- 文件名变量 ---------------------------------------------------------

    @Test
    fun every_filename_token_resolves() {
        assertEquals("IMG_1", ExportFileName.previewName("{photo}", "任意边框", "IMG_1", 1))
        assertEquals("边框", ExportFileName.previewName("{name}", "边框", "IMG_1", 1))
        assertEquals("7", ExportFileName.previewName("{index}", "边框", "IMG_1", 7))
        val dated = ExportFileName.previewName("{date}", "边框", "IMG_1", 1)
        assertTrue(dated.matches(Regex("\\d{8}")), "拍摄日期是 8 位数字：$dated")
        val timed = ExportFileName.previewName("{time}", "边框", "IMG_1", 1)
        assertTrue(timed.matches(Regex("\\d{6}")), "拍摄时间是 6 位数字：$timed")
    }

    @Test
    fun five_tokens_are_offered_as_chips() {
        assertEquals(
            listOf("photo", "index", "name", "date", "time"),
            ExportNameToken.entries.map { it.key },
            "报告 §6 的五个变量，一个不多一个不少",
        )
        assertEquals("{index}", ExportNameToken.Index.placeholder)
    }

    @Test
    fun a_combined_pattern_keeps_the_extension_and_order() {
        val file = ExportFileName.previewFile("{photo}-{index}-{name}", "理光 GR", "IMG_0042", 3, "jpg")
        assertEquals("IMG_0042-3-理光 GR.jpg", file)
    }

    @Test
    fun illegal_characters_are_replaced_not_written() {
        val name = ExportFileName.previewName("{name}", "a/b:c*d?e\"f<g>h|i", null, 1)
        assertEquals("a-b-c-d-e-f-g-h-i", name)
    }

    @Test
    fun a_taken_name_gets_a_collision_suffix() {
        val platform = FakePlatform("/out")
        val base = ExportNaming.candidatePath(platform, "{photo}-{index}", "卡", "IMG_1", "jpg", 3, 1, "/out")
        assertEquals("/out/IMG_1-3.jpg", base)
        val second = ExportNaming.candidatePath(platform, "{photo}-{index}", "卡", "IMG_1", "jpg", 3, 2, "/out")
        assertEquals("/out/IMG_1-3-2.jpg", second, "重名时自动加序号，不覆盖已有文件")
    }

    @Test
    fun inserting_a_token_lands_at_the_cursor() {
        assertEquals("a{photo}b", ExportFileName.insertToken("ab", "photo", 1, 1))
        assertEquals("{name}", ExportFileName.insertToken("x", "name", 0, 1))
    }

    // ---- 参数校验 -----------------------------------------------------------

    @Test
    fun quality_is_60_to_100_and_defaults_to_100() {
        assertEquals(60, ExportRules.parseQuality("60"))
        assertEquals(100, ExportRules.parseQuality(" 100 "))
        assertNull(ExportRules.parseQuality("59"), "低于 60 不接受")
        assertNull(ExportRules.parseQuality("101"), "高于 100 不接受")
        assertNull(ExportRules.parseQuality(""))
        assertNull(ExportRules.parseQuality("高"))
        assertNotNull(ExportRules.qualityError("59"))
        assertNull(ExportRules.qualityError("100"))
        assertEquals(100, ExportRules.QUALITY_DEFAULT)
    }

    @Test
    fun long_edge_is_1_to_20000() {
        assertEquals(1, ExportRules.parseLongEdge("1"))
        assertEquals(20000, ExportRules.parseLongEdge("20000"))
        assertNull(ExportRules.parseLongEdge("0"))
        assertNull(ExportRules.parseLongEdge("20001"))
        assertNull(ExportRules.parseLongEdge("-5"))
        assertNotNull(ExportRules.longEdgeError("0"))
    }

    @Test
    fun quality_only_blocks_the_formats_that_use_it() {
        // JPEG 用质量：非法值拦住提交。
        assertFalse(
            ExportRules.canSubmit(ExportSizeMode.Original, "1600", "59", SampleFormat.Jpeg, true),
        )
        // PNG / 无损 WebP 不用质量：同一串非法值也不拦。
        assertTrue(
            ExportRules.canSubmit(ExportSizeMode.Original, "1600", "59", SampleFormat.Png, true),
        )
        assertTrue(
            ExportRules.canSubmit(ExportSizeMode.Original, "1600", "59", SampleFormat.WebP, true),
        )
        // 有损 WebP 用质量，又拦回来。
        assertFalse(
            ExportRules.canSubmit(ExportSizeMode.Original, "1600", "59", SampleFormat.WebP, false),
        )
        // 长边档才校验长边。
        assertFalse(ExportRules.canSubmit(ExportSizeMode.LongEdge, "0", "100", SampleFormat.Jpeg, true))
        assertTrue(ExportRules.canSubmit(ExportSizeMode.Original, "0", "100", SampleFormat.Jpeg, true))
    }

    // ---- 文案（P2-2 的写法） ------------------------------------------------

    @Test
    fun jpeg_says_quality_and_that_it_re_encodes_once() {
        val line = ExportCopy.summaryLine(ExportSizeMode.Original, 1600, SampleFormat.Jpeg, 100, true)
        assertTrue(line.contains("原分辨率，不缩放"), line)
        assertTrue(line.contains("JPEG 质量 100"), line)
        assertTrue(line.contains("会重新编码一次"), line)
    }

    @Test
    fun png_and_lossless_webp_say_lossless_and_never_mention_quality() {
        val png = ExportCopy.summaryLine(ExportSizeMode.Original, 1600, SampleFormat.Png, 100, true)
        assertTrue(png.contains("无损编码"), png)
        assertFalse(png.contains("质量"), png)
        assertFalse(png.contains("重新编码"), png)
        val webp = ExportCopy.summaryLine(ExportSizeMode.Original, 1600, SampleFormat.WebP, 100, true)
        assertTrue(webp.contains("无损编码"), webp)
        assertFalse(webp.contains("有损"), webp)
    }

    @Test
    fun lossy_webp_is_not_called_lossless() {
        val line = ExportCopy.summaryLine(ExportSizeMode.Original, 1600, SampleFormat.WebP, 100, false)
        assertTrue(line.contains("有损"), line)
        assertTrue(line.contains("会重新编码一次"), line)
        assertFalse(line.contains("无损编码"), line)
    }

    @Test
    fun a_downscaled_export_never_claims_original_resolution() {
        val line = ExportCopy.summaryLine(ExportSizeMode.LongEdge, 2048, SampleFormat.Jpeg, 100, true)
        assertFalse(line.contains("原分辨率"), "缩小尺寸时不写「原分辨率」：$line")
        // D-1 设计变化（WP-X2）：长边档是「归一」，目标比画布大时也会放大，所以
        // 文案从「长边缩到」改成「长边归一到」——否则小图导出时界面承诺与成片不符。
        assertTrue(line.contains("长边归一到 2048 px"), line)
    }

    @Test
    fun metadata_copy_matches_the_pipeline_that_exists_today() {
        assertEquals(listOf(ExportCopy.GPS_NOTE), ExportCopy.metadataLines(SampleFormat.Jpeg))
        assertEquals(listOf(ExportCopy.GPS_NOTE), ExportCopy.metadataLines(SampleFormat.WebP))
        assertEquals(
            listOf(ExportCopy.GPS_NOTE, ExportCopy.PNG_EXIF_NOTE),
            ExportCopy.metadataLines(SampleFormat.Png),
            "桌面 PNG 走 ImageIO，不写 EXIF",
        )
        assertTrue(ExportCopy.GPS_NOTE.contains("去掉 GPS"))
    }

    @Test
    fun no_copy_line_promises_that_the_file_is_not_recompressed() {
        val everyFormat = SampleFormat.entries.flatMap { format ->
            listOf(true, false).flatMap { lossless ->
                ExportSizeMode.entries.map { mode ->
                    ExportCopy.summaryLine(mode, 4096, format, 100, lossless)
                }
            }
        } + SampleFormat.entries.flatMap { ExportCopy.metadataLines(it) }
        everyFormat.forEach { line ->
            assertFalse(line.contains("不重新压缩"), line)
            assertFalse(line.contains("不二次压缩"), line)
        }
    }

    // ---- 尺寸与体积估算 -----------------------------------------------------

    @Test
    fun the_export_canvas_is_derived_from_the_preview_photo_box() {
        val canvas = ExportEstimate.canvas(
            photoWidthPx = 4000,
            photoHeightPx = 3000,
            previewPhotoBox = com.framemark.native.workflow.ImageRect(0f, 60f, 1000f, 750f),
            previewWidthPx = 1000,
            previewHeightPx = 800,
        )
        assertEquals(ExportPixels(4000, 3200), canvas, "照片框占 75/80，画布高 = 3000 ÷ 0.9375")
    }

    @Test
    fun no_photo_box_means_no_invented_size() {
        assertNull(
            ExportEstimate.canvas(4000, 3000, null, 1000, 800),
            "预览没报照片框时，界面应写「尺寸在导出后显示」而不是编一个数",
        )
        assertNull(
            ExportEstimate.canvas(4000, 3000, com.framemark.native.workflow.ImageRect(0f, 0f, 0f, 0f), 1000, 800),
        )
        assertNull(ExportEstimate.canvas(0, 0, com.framemark.native.workflow.ImageRect(0f, 0f, 10f, 10f), 10, 10))
    }

    @Test
    fun the_long_edge_mode_normalises_both_directions() {
        val canvas = ExportPixels(4000, 3200)
        assertEquals(canvas, ExportEstimate.output(canvas, ExportSizeMode.Original, 2048))
        val scaled = ExportEstimate.output(canvas, ExportSizeMode.LongEdge, 2048)
        assertEquals(2048, scaled?.longEdge)
        assertEquals(ExportPixels(2048, 1638), scaled)
        // D-1 设计变化（WP-X2）：所选长边大于成片长边时放大，不再静默返回原尺寸
        // —— 旧行为让「选 2048 导出仍是 1600×1245」，顶栏/弹层/成片三处互相矛盾。
        assertEquals(
            ExportPixels(8000, 6400),
            ExportEstimate.output(canvas, ExportSizeMode.LongEdge, 8000),
            "所选长边大于成片长边时按同一比例放大",
        )
    }

    @Test
    fun the_byte_estimate_follows_the_format() {
        val pixels = ExportPixels(4000, 3000)
        val jpeg100 = ExportEstimate.estimatedBytes(pixels, SampleFormat.Jpeg, 100, true)!!
        val jpeg60 = ExportEstimate.estimatedBytes(pixels, SampleFormat.Jpeg, 60, true)!!
        val png = ExportEstimate.estimatedBytes(pixels, SampleFormat.Png, 100, true)!!
        val webp = ExportEstimate.estimatedBytes(pixels, SampleFormat.WebP, 100, false)!!
        assertTrue(jpeg60 < jpeg100, "低质量体积更小")
        assertTrue(png > jpeg100, "无损 PNG 比 q100 JPEG 大")
        assertTrue(webp < jpeg100, "有损 WebP 最小")
        assertTrue(ExportEstimate.formatBytes(jpeg100).endsWith("MB"))
        assertEquals("1.0 KB", ExportEstimate.formatBytes(1024))
        assertEquals("—", ExportEstimate.formatBytes(0))
    }

    @Test
    fun the_preview_line_admits_it_is_an_estimate() {
        val line = ExportEstimate.previewLine(ExportPixels(4000, 3200), SampleFormat.Jpeg, 100, true)
        assertTrue(line.startsWith("约 4000 × 3200 px"), line)
        assertTrue(line.contains("约"), line)
        assertEquals(
            "尺寸在导出后显示",
            ExportEstimate.previewLine(null, SampleFormat.Jpeg, 100, true),
        )
    }

    // ---- 平台兜底 -----------------------------------------------------------

    private class FakePlatform(override val exportDir: String) : WorkflowPlatform {
        override val dataDir: String = "/data"
        override fun readTextFile(path: String): String? = null
        override fun writeTextFile(path: String, text: String) = Unit
        override fun writeBytes(path: String, bytes: ByteArray): Boolean = true
        override fun exists(path: String): Boolean = false
        override fun fileSize(path: String): Long = 0L
        override fun readBytes(path: String): ByteArray? = null
        override fun encodeSampleImage(width: Int, height: Int, format: SampleFormat, quality: Int): ByteArray? = null
        override fun dateStamp(): String = "20261030"
        override fun timeStamp(): String = "120000"
        override fun formatDateTime(epochMillis: Long): String = ""
        override fun nowMillis(): Long = 0L
    }
}
