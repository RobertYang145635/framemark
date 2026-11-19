package com.framemark.native.workflow

import com.framemark.native.product.ExportSettings
import com.framemark.native.product.ExportSizeMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P1 一行级错误：批量导出页的页脚写过 `"当前编辑的导出设置：JPG · 长边 ${settings.longEdge}"`，
 * 而默认档是「原尺寸」、`longEdge` 这时是 null —— 页脚印出「长边 null」。
 *
 * 现在页脚与页面上每一行的 `sizeLabel` 是同一个函数、同一句话，也就是导出表单里
 * `PhoneExportOptions.sizeCaption` 的写法：有长边就 `长边 N px`，没有就是 `原尺寸`。
 */
class PhotoBatchSizeLabelTest {

    @Test
    fun the_default_settings_print_original_size_not_null() {
        assertEquals("原尺寸", sizeLabel(ExportSettings()))
    }

    @Test
    fun a_long_edge_step_prints_its_pixel_count() {
        val settings = ExportSettings(sizeMode = ExportSizeMode.LongEdge, longEdge = 2048)
        assertEquals("长边 2048 px", sizeLabel(settings))
    }

    @Test
    fun a_long_edge_mode_without_a_number_is_really_original_size() {
        // 引擎在没有数字时不缩放，所以印「原尺寸」是真话；印「长边 null px」不是。
        assertEquals("原尺寸", sizeLabel(ExportSettings(sizeMode = ExportSizeMode.LongEdge, longEdge = null)))
    }

    @Test
    fun a_stored_long_edge_is_ignored_while_the_mode_is_original() {
        assertEquals("原尺寸", sizeLabel(ExportSettings(sizeMode = ExportSizeMode.Original, longEdge = 4096)))
    }

    @Test
    fun the_footer_uses_the_same_sentence_as_the_rows() {
        // 页脚与每行同一个函数，所以两处不可能再各写一套（曾经就是两套）。
        val settings = ExportSettings(sizeMode = ExportSizeMode.LongEdge, longEdge = 4096)
        assertEquals("长边 4096 px", sizeLabel(settings))
    }
}
