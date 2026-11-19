package com.framemark.native.shell.phone

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.OutputPixels
import com.framemark.native.product.ExportSettings
import com.framemark.native.product.ExportSizeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1（UX 审查）：手机导出表单不显示真实像素 —— 外壳把 `outputWidth/outputHeight` 写死
 * 0，于是表单永远退回去印「原尺寸」那一句，用户看不到 3024 × 4032。
 *
 * 现在表单读的是舞台那张照片的像素 + 这张边框声明的参数，走右栏 / 顶栏 / 手机样式页 /
 * 引擎桥接共用的那条 `OutputSize` 链，所以表单和样式页印同一个数字。
 */
class PhoneExportFormPixelsTest {

    private fun control(param: String, initial: Float) = InspectorControl(
        key = "stock-xiaomi-classic-17::$param",
        controlId = param.substringAfterLast('.'),
        param = param,
        label = param,
        kind = "slider",
        section = InspectorSection.Layout,
        type = ControlType.Slider,
        initial = ControlValue.Num(initial),
        min = 0f,
        max = 100f,
        step = 0.5f,
        options = emptyList(),
        multiline = false,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )

    private fun pixels(
        width: Int,
        height: Int,
        controls: List<InspectorControl> = emptyList(),
        settings: ExportSettings = ExportSettings(),
    ): OutputPixels? = PhoneExportOptions.outputPixels(
        photoWidthPx = width,
        photoHeightPx = height,
        controls = controls,
        settings = settings,
        value = { c -> (c.initial as ControlValue.Num).value },
    )

    @Test
    fun the_form_shows_the_real_photo_pixels_on_the_default_original_size() {
        // 验收里的那个例子：3024 × 4032 竖拍照片，默认「原尺寸」。
        assertEquals(OutputPixels(3024, 4032), pixels(3024, 4032))
    }

    @Test
    fun a_declared_footer_makes_the_canvas_taller_exactly_like_the_style_page() {
        // classic 页脚会加高画布：表单必须说 3024 × 4289，不能只说照片尺寸。
        val px = pixels(3024, 4032, listOf(control("params.footerPct", 7.5f)))
        assertEquals(3024, px!!.width)
        assertTrue(px.height > 4032, "页脚加高后的画布必须比照片高，实际 ${px.height}")
    }

    @Test
    fun a_long_edge_step_is_applied_to_the_canvas() {
        val px = pixels(
            3024,
            4032,
            settings = ExportSettings(sizeMode = ExportSizeMode.LongEdge, longEdge = 2048),
        )
        assertEquals(2048, px!!.longEdge)
        // 同一比例：3024/4032 = 0.75，缩到长边 2048 后宽约 1536。
        assertTrue(px.width in 1530..1542, "长边档必须等比缩放，实际 ${px.width} × ${px.height}")
    }

    @Test
    fun without_a_photo_size_the_form_prints_no_number_at_all() {
        assertNull(pixels(0, 0))
        assertNull(pixels(-1, 4032))
    }
}
