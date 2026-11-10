package com.framemark.native.shell.phone

import com.framemark.native.product.ExportFormat
import com.framemark.native.product.ExportSettings
import com.framemark.native.product.ExportSizeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WP-P2 §4.2 导出选项: exactly three size steps, quality fixed at 100, and the
 * format mapping the native encoder can actually write.
 */
class PhoneExportOptionsTest {

    @Test
    fun thereAreExactlyThreeSizeSteps() {
        assertEquals(3, PhoneExportOptions.SIZES.size)
        assertEquals(
            listOf("原尺寸", "长边 2048", "长边 4096"),
            PhoneExportOptions.SIZES.map { it.label },
        )
        assertEquals(listOf(null, 2048, 4096), PhoneExportOptions.SIZES.map { it.longEdge })
    }

    @Test
    fun applyingEachStepRoundTripsThroughTheSettings() {
        var settings = ExportSettings()
        assertEquals(PhoneExportSize.Original, PhoneExportOptions.sizeOf(settings))

        PhoneExportOptions.SIZES.forEach { step ->
            settings = PhoneExportOptions.applySize(settings, step)
            assertEquals(step, PhoneExportOptions.sizeOf(settings))
            assertEquals(step.longEdge, settings.effectiveLongEdge)
            assertEquals(
                if (step.longEdge == null) ExportSizeMode.Original else ExportSizeMode.LongEdge,
                settings.sizeMode,
            )
        }
    }

    @Test
    fun qualityIsPinnedToHundredForEveryPath() {
        val low = ExportSettings(quality = 60)
        assertEquals(100, PhoneExportOptions.QUALITY)
        PhoneExportOptions.SIZES.forEach { step ->
            assertEquals(100, PhoneExportOptions.applySize(low, step).quality)
        }
        PhoneExportOptions.FORMATS.forEach { format ->
            assertEquals(100, PhoneExportOptions.applyFormat(low, format).quality)
        }
        assertEquals(100, PhoneExportOptions.normalized(low).quality)
        assertEquals(100, PhoneExportOptions.normalized(ExportSettings(quality = 87)).quality)
    }

    @Test
    fun formatsAreTheThreeTheEncoderWrites() {
        assertEquals(
            listOf(ExportFormat.Jpeg, ExportFormat.Png, ExportFormat.WebP),
            PhoneExportOptions.FORMATS,
        )
        val jpeg = ExportSettings(format = ExportFormat.Jpeg)
        val png = PhoneExportOptions.applyFormat(jpeg, ExportFormat.Png)
        assertEquals(ExportFormat.Png, png.format)
        assertEquals(ExportFormat.Png, PhoneExportOptions.formatOf(png))
        val webp = PhoneExportOptions.applyFormat(png, ExportFormat.WebP)
        assertEquals(ExportFormat.WebP, webp.format)
        assertEquals(ExportFormat.WebP, PhoneExportOptions.formatOf(webp))
    }

    @Test
    fun webpKeepsLosslessDefaultButRemembersAnExplicitChoice() {
        val lossy = ExportSettings(format = ExportFormat.WebP, webpLossless = false)
        val toPng = PhoneExportOptions.applyFormat(lossy, ExportFormat.Png)
        assertFalse(toPng.webpLossless, "a non-WebP format keeps the stored flag untouched")
        val backToWebp = PhoneExportOptions.applyFormat(toPng, ExportFormat.WebP)
        assertTrue(backToWebp.webpLossless, "returning to WebP starts from the lossless default")
        val kept = PhoneExportOptions.applyFormat(lossy, ExportFormat.WebP)
        assertFalse(kept.webpLossless, "re-selecting WebP must not silently switch to lossless")
    }

    @Test
    fun anUnknownLongEdgeFallsBackToTheOriginalStep() {
        val custom = ExportSettings(sizeMode = ExportSizeMode.LongEdge, longEdge = 1600)
        assertEquals(PhoneExportSize.Original, PhoneExportOptions.sizeOf(custom))
        assertEquals("原尺寸", PhoneExportOptions.sizeCaption(PhoneExportOptions.sizeOf(custom)))
    }

    @Test
    fun theResolutionNoteIsAccuratePerFormat() {
        // 返修 P2-2: the badge and the 原尺寸 note may not claim "不重新压缩" — the
        // engine always encodes the finished canvas once.
        assertEquals("原分辨率 · 质量 100", PhoneExportOptions.ORIGINAL_RESOLUTION_BADGE)

        val jpeg = PhoneExportOptions.resolutionNote(PhoneExportSize.Original, ExportFormat.Jpeg)
        assertTrue(jpeg.contains("原分辨率，不缩放"), jpeg)
        assertTrue(jpeg.contains("4:4:4"), jpeg)
        assertTrue(jpeg.contains("会重新编码一次"), jpeg)

        val png = PhoneExportOptions.resolutionNote(PhoneExportSize.Original, ExportFormat.Png)
        assertTrue(png.contains("原分辨率，不缩放"), png)
        assertTrue(png.contains("无损编码"), png)

        val webpLossless = PhoneExportOptions.resolutionNote(
            PhoneExportSize.Original,
            ExportFormat.WebP,
            webpLossless = true,
        )
        assertTrue(webpLossless.contains("原分辨率，不缩放"), webpLossless)
        assertTrue(webpLossless.contains("无损编码"), webpLossless)

        val webpLossy = PhoneExportOptions.resolutionNote(
            PhoneExportSize.Original,
            ExportFormat.WebP,
            webpLossless = false,
        )
        assertTrue(webpLossy.contains("有损"), webpLossy)
        assertTrue(webpLossy.contains("会重新编码一次"), webpLossy)
    }

    @Test
    fun aLongEdgeStepNeverMentionsTheOriginalResolution() {
        listOf(PhoneExportSize.Long2048, PhoneExportSize.Long4096).forEach { step ->
            ExportFormat.entries.forEach { format ->
                val note = PhoneExportOptions.resolutionNote(step, format)
                assertTrue(note.contains("长边缩到 ${step.longEdge} px 后再编码"), note)
                assertFalse(note.contains("原分辨率"), note)
            }
        }
        assertEquals("长边 2048 px", PhoneExportOptions.sizeCaption(PhoneExportSize.Long2048))
    }

    @Test
    fun noCopyClaimsTheFileIsNotRecompressed() {
        val copy = buildList {
            add(PhoneExportOptions.ORIGINAL_RESOLUTION_BADGE)
            PhoneExportSize.entries.forEach { size ->
                PhoneExportOptions.FORMATS.forEach { format ->
                    listOf(true, false).forEach { lossless ->
                        add(PhoneExportOptions.resolutionNote(size, format, lossless))
                        add(PhoneExportOptions.formatNote(format, lossless))
                    }
                }
            }
        }
        copy.forEach { line ->
            assertFalse(line.contains("不重新压缩"), line)
            assertFalse(line.contains("不二次压缩"), line)
        }
    }

    @Test
    fun theFormatNoteSaysWhatTheFileReallyIs() {
        val jpeg = PhoneExportOptions.formatNote(ExportFormat.Jpeg)
        assertTrue(jpeg.contains("100"), jpeg)
        assertTrue(jpeg.contains("4:4:4"), jpeg)
        assertTrue(PhoneExportOptions.formatNote(ExportFormat.Png).contains("无损"))
        assertTrue(PhoneExportOptions.formatNote(ExportFormat.WebP, webpLossless = true).contains("无损"))
        assertTrue(PhoneExportOptions.formatNote(ExportFormat.WebP, webpLossless = false).contains("有损"))
    }
}
