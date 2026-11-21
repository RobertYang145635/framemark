package com.framemark.native.inspector

import com.framemark.native.shell.shouldDrawExifHints
import com.framemark.native.workflow.TextSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 舞台上的「缺参数」虚线占位（2026-11-20 用户定）：缺行时画一圈极淡虚线，告诉用户
 * 这里本来能填；**进过一次「文字」页就不再画**。
 *
 * 两条边界钉在这里：有缺行才画、看过就不画；已经填过的行不算缺。
 */
class ExifSlotHintTest {

    private fun editable(text: String, raw: String = text) = TextSlot(
        id = "hand:wm-classic:exif",
        key = "hand:wm-classic:exif",
        text = text,
        x = 10.0,
        y = 20.0,
        w = 120.0,
        h = 18.0,
        fontSize = 12.0,
        baseline = null,
        lines = 1,
        raw = raw,
        stable = true,
    )

    @Test
    fun aPhotoWithoutParametersDrawsTheHint() {
        assertTrue(shouldDrawExifHints(listOf(editable("")), cardSeen = false))
    }

    @Test
    fun aFilledRowNeedsNoHint() {
        assertFalse(shouldDrawExifHints(listOf(editable("35mm F8 ISO640 1/125s")), cardSeen = false))
    }

    @Test
    fun visitingTheTextPageStopsTheHint() {
        assertFalse(
            shouldDrawExifHints(listOf(editable("")), cardSeen = true),
            "用户已经打开过「文字」页：知道能改，就别再画占位",
        )
    }

    @Test
    fun aCardWithoutTextRowsDrawsNothing() {
        assertFalse(shouldDrawExifHints(emptyList(), cardSeen = false))
    }

    @Test
    fun aRowWithNoRectangleKeepsTheRoleNameInsteadOfAPlaceholderWord() {
        // 位置由边框决定；这一帧没画出来时不写「待定位」（用户 2026-11-21 明确反感），
        // 只显示角色名，等探针量出矩形后再补上「右上 / 右下」。
        assertEquals("", textSlotAxisLabel(TextSlotAxis(null, null)))
        assertEquals("右上", textSlotAxisLabel(TextSlotAxis(column = 2, row = 0)))
    }

    @Test
    fun ghostSamplesLookLikeRealWatermarkText() {
        val exif = com.framemark.native.product.GhostSamples.forRole("拍摄参数")
        assertTrue(exif.contains("mm") && exif.contains("ISO"), exif)
        val date = com.framemark.native.product.GhostSamples.forRole("日期")
        assertTrue(date.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""")), date)
        assertEquals("OPPO", com.framemark.native.product.GhostSamples.forRole("品牌"))
        assertEquals("Sony", com.framemark.native.product.GhostSamples.forRole("品牌", make = "Sony"))
    }
}
