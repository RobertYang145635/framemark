package com.framemark.native.shell.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 展开照片条时的定位（WP-Q2⑥ 摩擦二）：16 张照片、当前是第 16 张，展开只看得到
 * 第 1–7 张；滑到第 15 张编辑文字、关掉键盘后照片条又回到开头。
 *
 * 规则：当前照片已完整可见就不动（不打扰手动滑动）；否则把它居中。
 */
class PhotoStripScrollTest {

    private val stride = 58f
    private val viewport = 7 * 58f

    @Test
    fun an_already_visible_photo_does_not_move_the_strip() {
        assertNull(photoStripScrollTarget(index = 2, stride = stride, viewport = viewport, current = 0f, maxValue = 2000f))
        assertNull(photoStripScrollTarget(index = 0, stride = stride, viewport = viewport, current = 0f, maxValue = 2000f))
    }

    @Test
    fun the_last_photo_is_centred_when_the_strip_opens() {
        // 第 16 张（index 15）离视口很远时展开，滚动值要把它带进来（居中）。
        val target = photoStripScrollTarget(index = 15, stride = stride, viewport = viewport, current = 0f, maxValue = 2000f)
        assertEquals(15 * stride - (viewport - stride) / 2f, target)
        // 内容已经到头时就停在末尾，不会滚出内容范围。
        val clamped = photoStripScrollTarget(index = 15, stride = stride, viewport = viewport, current = 0f, maxValue = 600f)
        assertEquals(600f, clamped, "滚动值不能超过内容末尾")
    }

    @Test
    fun a_photo_just_outside_the_viewport_keeps_its_neighbour_visible() {
        val target = photoStripScrollTarget(index = 8, stride = stride, viewport = viewport, current = 0f, maxValue = 2000f)
        assertEquals(8 * stride - (viewport - stride) / 2f, target)
    }
}
