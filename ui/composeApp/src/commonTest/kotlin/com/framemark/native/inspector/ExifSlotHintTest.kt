package com.framemark.native.inspector

import com.framemark.native.shell.shouldDrawExifHints
import com.framemark.native.workflow.TextSlot
import kotlin.test.Test
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
}
