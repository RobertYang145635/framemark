package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The logger is the boundary between the QA evidence protocol and a release build,
 * so both of its promises are pinned here: a gated-off `info` line costs nothing
 * (not even its message string), and nothing that leaves the device still carries a
 * full path.
 */
class FmLogTest {

    private class Capture {
        val lines = mutableListOf<Pair<String, Boolean>>()
        var verbose = FmLog.verbose
        var sink: (String, Boolean) -> Unit = FmLog.sink

        fun install(verbose: Boolean) {
            FmLog.verbose = verbose
            FmLog.sink = { line, toError -> lines += line to toError }
        }

        fun restore() {
            FmLog.verbose = verbose
            FmLog.sink = sink
        }
    }

    private fun captured(verbose: Boolean, block: () -> Unit): List<Pair<String, Boolean>> {
        val capture = Capture()
        try {
            capture.install(verbose)
            block()
            return capture.lines.toList()
        } finally {
            capture.restore()
        }
    }

    @Test
    fun a_release_build_drops_info_and_keeps_warn_and_error() {
        val lines = captured(verbose = false) {
            FmLog.info("FM_TEST_LIB_QUERY") { "index=3 total=9" }
            FmLog.warn("FM_TEST_THUMB_FAIL") { "card=c1" }
            FmLog.error("FM_TEST_BRIDGE_FAIL") { "code=FM-E-INTERNAL-001" }
        }
        assertEquals(2, lines.size, "release 只保留 warn/error：$lines")
        assertEquals("FM_TEST_THUMB_FAIL card=c1", lines[0].first)
        assertFalse(lines[0].second, "warn 走普通输出")
        assertEquals("FM_TEST_BRIDGE_FAIL code=FM-E-INTERNAL-001", lines[1].first)
        assertTrue(lines[1].second, "error 走错误通道（桌面 bridge 的 stdout 是 JSON 协议）")
    }

    @Test
    fun a_debug_build_keeps_info_and_never_builds_a_gated_message() {
        var built = 0
        val lines = captured(verbose = true) {
            FmLog.info("FM_TEST_PAGE") {
                built++
                "page=editor"
            }
        }
        assertEquals(listOf("FM_TEST_PAGE page=editor" to false), lines)
        assertEquals(1, built)

        // Same call in release: the lambda must not run at all.
        built = 0
        captured(verbose = false) {
            FmLog.info("FM_TEST_PAGE") {
                built++
                "page=editor"
            }
        }
        assertEquals(0, built, "release 不应为被丢弃的 info 拼字符串")
    }

    @Test
    fun a_tag_only_line_is_the_tag_itself() {
        val lines = captured(verbose = true) { FmLog.info("FM_TEST_PROJECT_NONE") }
        assertEquals(listOf("FM_TEST_PROJECT_NONE" to false), lines)
    }

    @Test
    fun absolute_paths_are_reduced_to_the_basename() {
        assertEquals("c.jpg", FmLog.redact("E:\\a\\b\\c.jpg"))
        assertEquals("FM_TEST path=c.jpg", FmLog.redact("FM_TEST path=E:\\a\\b\\c.jpg"))
        // A trailing comma belongs to the line shape, not to the file name.
        assertEquals("FM_TEST path=c.jpg,", FmLog.redact("FM_TEST path=E:\\a\\b\\c.jpg,"))
        assertEquals(
            "FM_TEST overlay=wm-1.png",
            FmLog.redact("FM_TEST overlay=/data/user/0/com.framemark.native/files/Watermarks/wm-1.png"),
        )
    }

    @Test
    fun content_uris_keep_only_scheme_and_last_segment() {
        val redacted = FmLog.redact(
            "FM_TEST pick=content://com.android.providers.media.documents/document/image%3A1000000018",
        )
        assertTrue(redacted.startsWith("FM_TEST pick=content://"), redacted)
        assertTrue(!redacted.contains("com.android"), "authority 不应留在日志里：$redacted")
        assertTrue(!redacted.contains("documents/document"), "目录结构不应留在日志里：$redacted")
        assertTrue(redacted.endsWith("image%3A1000000018"), redacted)
    }

    @Test
    fun over_long_payloads_are_logged_as_their_length() {
        val exif = "4d4d002a".repeat(200) // 1600 chars, the shape of an EXIF dump
        val redacted = FmLog.redact("FM_TEST exif=$exif reason=ok")
        assertEquals("FM_TEST exif=<1600 chars> reason=ok", redacted)
    }

    @Test
    fun the_key_value_shape_survives_redaction() {
        val line = "FM_TEST_SAVE path=/a/b/project.json saves=3 webpLossless=true"
        assertEquals("FM_TEST_SAVE path=project.json saves=3 webpLossless=true", FmLog.redact(line))
    }
}
