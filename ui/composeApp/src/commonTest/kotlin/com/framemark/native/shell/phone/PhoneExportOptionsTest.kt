package com.framemark.native.shell.phone

import com.framemark.native.product.ExportFormat
import com.framemark.native.product.ExportSettings
import com.framemark.native.product.ExportSizeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun aLongEdgeThatIsNotAStepResolvesToTheNearestStepAndShowsItself() {
        // The old behaviour returned 原尺寸 for every custom edge, so a project
        // exported at 3000 px showed "原尺寸" while the engine still used 3000.
        val at3000 = ExportSettings(sizeMode = ExportSizeMode.LongEdge, longEdge = 3000)
        assertEquals(PhoneExportSize.Long2048, PhoneExportOptions.sizeOf(at3000), "3000 更接近 2048")
        assertEquals(3000, PhoneExportOptions.customLongEdge(at3000))
        assertEquals("自定义 3000 px", PhoneExportOptions.sizeCaption(at3000))
        assertEquals(PhoneExportOptions.CUSTOM_SIZE_ID, PhoneExportOptions.selectedSizeId(at3000))
        assertEquals(
            "自定义 3000",
            PhoneExportOptions.sizeChoices(at3000).last().label,
            "自定义值必须有自己的 chip，不能借用最近一档的文案",
        )

        val at5000 = ExportSettings(sizeMode = ExportSizeMode.LongEdge, longEdge = 5000)
        assertEquals(PhoneExportSize.Long4096, PhoneExportOptions.sizeOf(at5000))

        // 原尺寸 keeps its own caption and offers no custom chip.
        assertEquals("原尺寸", PhoneExportOptions.sizeCaption(ExportSettings()))
        assertNull(PhoneExportOptions.customLongEdge(ExportSettings()))
        assertTrue(PhoneExportOptions.sizeChoices(ExportSettings()).none { it.isCustom })
    }

    @Test
    fun tappingTheSelectedCustomChipKeepsTheRealLongEdge() {
        val custom = ExportSettings(sizeMode = ExportSizeMode.LongEdge, longEdge = 3000)
        val afterTap = PhoneExportOptions.applySizeChoice(custom, PhoneExportOptions.selectedSizeId(custom))
        assertEquals(3000, afterTap.effectiveLongEdge, "点击已选中的自定义 chip 不得改变导出尺寸")
        assertEquals(100, afterTap.quality)

        val switched = PhoneExportOptions.applySizeChoice(custom, PhoneExportSize.Long2048.name)
        assertEquals(2048, switched.effectiveLongEdge, "点固定档必须切到该档")
        // An unknown id is ignored instead of silently resetting the size.
        assertEquals(3000, PhoneExportOptions.applySizeChoice(custom, "nope").effectiveLongEdge)
    }

    @Test
    fun aCustomLongEdgeNeverPromisesNoScaling() {
        val custom = ExportSettings(sizeMode = ExportSizeMode.LongEdge, longEdge = 3000)
        val note = PhoneExportOptions.resolutionNote(custom)
        assertTrue(note.contains("3000"), note)
        assertFalse(note.contains("不缩放"), note)
        assertFalse(note.contains("原分辨率"), note)
        // The real 原尺寸 promise is unchanged.
        val original = PhoneExportOptions.resolutionNote(ExportSettings())
        assertTrue(original.contains("原分辨率，不缩放"), original)
    }

    @Test
    fun ultraHdrIsOfferedOnlyForJpegWithAGainMap() {
        assertTrue(PhoneExportOptions.hdrEnabled(ExportFormat.Jpeg, hasGainMap = true))
        assertFalse(PhoneExportOptions.hdrEnabled(ExportFormat.Jpeg, hasGainMap = false))
        assertFalse(PhoneExportOptions.hdrEnabled(ExportFormat.Png, hasGainMap = true))
        assertFalse(PhoneExportOptions.hdrEnabled(ExportFormat.WebP, hasGainMap = true))
    }

    @Test
    fun theDisabledHdrSwitchExplainsItselfInOneLine() {
        val noGainMap = PhoneExportOptions.hdrReason(ExportFormat.Jpeg, hasGainMap = false)
        assertNotNull(noGainMap)
        assertTrue(noGainMap.contains("HDR"), noGainMap)
        assertFalse(noGainMap.contains('\n'), noGainMap)

        val png = PhoneExportOptions.hdrReason(ExportFormat.Png, hasGainMap = true)
        assertNotNull(png)
        assertTrue(png.contains("JPEG"), png)
        assertFalse(png.contains('\n'), png)

        assertNull(PhoneExportOptions.hdrReason(ExportFormat.Jpeg, hasGainMap = true))
    }

    @Test
    fun theHdrSwitchWritesThroughTheSameSettingsCopy() {
        val base = ExportSettings(format = ExportFormat.Jpeg, quality = 87)
        val enabled = PhoneExportOptions.applyPreserveHdr(base, true)
        assertTrue(enabled.preserveHdr)
        assertEquals(100, enabled.quality, "HDR 开关也必须把质量固定在 100")
        assertFalse(PhoneExportOptions.applyPreserveHdr(enabled, false).preserveHdr)
        // An explicit HDR choice survives a format change; the engine only honours it
        // for JPEG, exactly like the desktop dialog.
        assertTrue(PhoneExportOptions.applyFormat(enabled, ExportFormat.Png).preserveHdr)
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
