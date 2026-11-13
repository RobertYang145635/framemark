package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UI copy audit 2026-11-13, items 4 and 5: a support code or a raw exception message
 * must never be the line a user reads.
 *
 * [NoticeText.human] is the display mapping the shells, the batch queue and the
 * project banner call; [NoticeText.failure] is the "fixed sentence + log" wrapper the
 * import/export/share failures use.
 */
class NoticeTextTest {

    @Test
    fun aLeadingCodeIsNotCopy() {
        assertEquals("预设保存失败", NoticeText.human("FM-E-PRESET-001 预设保存失败"))
        assertEquals("水印应用失败", NoticeText.human("FM-E-WM-003: 水印应用失败"))
        assertEquals("工程修改未保存", NoticeText.human("FM-E-STORAGE 工程修改未保存"))
        assertEquals("导出没有成功", NoticeText.human("  FM-E-IO-003   导出没有成功  "))
    }

    @Test
    fun anInlineCodeSuffixIsNotCopyEither() {
        assertEquals("照片文件读不到：C:/x.jpg", NoticeText.human("照片文件读不到：C:/x.jpg（FM-E-INPUT-001）"))
        assertEquals("导出没有成功", NoticeText.human("导出没有成功 (FM-E-EXPORT-001)"))
    }

    @Test
    fun aBareCodeHasNothingToShow() {
        assertNull(NoticeText.human("FM-E-EXPORT-001"))
        assertNull(NoticeText.human("   "))
        assertNull(NoticeText.human(null))
    }

    @Test
    fun ordinaryCopyIsUntouched() {
        assertEquals("边框库加载失败", NoticeText.human("边框库加载失败"))
        assertEquals("已有 3 张照片", NoticeText.human("已有 3 张照片"))
    }

    @Test
    fun aFailureReturnsTheFixedSentenceAndLogsTheRawText() {
        val logged = mutableListOf<String>()
        val previousVerbose = FmLog.verbose
        val previousSink = FmLog.sink
        FmLog.verbose = true
        FmLog.sink = { line, _ -> logged += line }
        val shown = try {
            NoticeText.failure(
                "FM_EXPORT_FAIL",
                IllegalStateException("java.nio.file.AccessDeniedException: C:\\Users\\me\\out\\a.jpg"),
                "分享没有成功，请重试。",
            )
        } finally {
            FmLog.verbose = previousVerbose
            FmLog.sink = previousSink
        }
        assertEquals("分享没有成功，请重试。", shown)
        assertFalse(shown.contains("AccessDenied"), shown)
        assertTrue(logged.any { it.startsWith("FM_EXPORT_FAIL") }, "raw text must be logged: $logged")
        assertTrue(logged.any { it.contains("AccessDenied") }, "raw text must be logged: $logged")
        // Paths are redacted by FmLog itself; the class name and message stay.
        assertEquals(
            "分享没有成功，请重试。",
            NoticeText.failure("FM_EXPORT_FAIL", null, "分享没有成功，请重试。"),
        )
    }
}
