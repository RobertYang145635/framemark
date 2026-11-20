package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 「当前：自动匹配 · 按照片品牌 X」里那个 X 的兜底来源（真机 2026-11-20）：
 * 玻璃 04 把品牌画成字标图片，文字 slot 里只有机型（`Find X6 Pro`，没有厂商名），
 * 说明行于是永远说「按边框自己的品牌」，而屏上画的是 OPPO。
 */
class PhotoBrandHintTest {

    @Test
    fun markKeepsTheBrandTheRendererJustResolved() {
        PhotoBrandHint.mark("OPPO", "oppo")
        assertEquals("OPPO", PhotoBrandHint.make)
        assertEquals("oppo", PhotoBrandHint.brandKey)
    }

    @Test
    fun aPhotoWithoutExifClearsTheHint() {
        PhotoBrandHint.mark(null, null)
        assertNull(PhotoBrandHint.make, "没有 EXIF 的照片：说明行必须回到「按边框自己的品牌」")
        assertNull(PhotoBrandHint.brandKey)
    }

    @Test
    fun blankValuesAreNotABrand() {
        PhotoBrandHint.mark("  ", "")
        assertNull(PhotoBrandHint.make)
        assertNull(PhotoBrandHint.brandKey)
    }
}
