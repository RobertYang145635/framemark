package com.framemark.native.shell

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.footerPctDisabledReason
import com.framemark.native.inspector.logoCenterXDisabledReason
import com.framemark.native.inspector.logoLeftHidesTextNote
import com.framemark.native.shell.desktop.ExportCopy
import com.framemark.native.shell.desktop.ExportEstimate
import com.framemark.native.shell.desktop.ExportPixels
import com.framemark.native.shell.desktop.ExportRules
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.ExportSizeMode
import com.framemark.native.workflow.ImageRect
import com.framemark.native.workflow.SampleFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AU2 A-02 / A-03 / A-04 / A-07 / A-19 的纯逻辑：哪些控件在哪些状态下不生效、
 * 一句原因怎么说、模板会不会裁照片、长边档文案是否说实话。
 *
 * 这些都是「界面承诺」的单元，Verifier 可以直接从断言里读出用户看到的那句话。
 */
class InactiveControlCopyTest {

    private fun toggle(param: String, value: Boolean) = InspectorControl(
        key = "wm-classic::$param",
        controlId = param,
        param = param,
        label = param,
        kind = "toggle",
        section = InspectorSection.Logo,
        type = ControlType.Switch,
        initial = ControlValue.Flag(value),
        min = null,
        max = null,
        step = null,
        options = emptyList(),
        multiline = false,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )

    // ---- A-03 底部留白 / 叠加版式 ----------------------------------------

    @Test
    fun footer_pct_is_disabled_only_for_the_overlay_layout() {
        assertNull(footerPctDisabledReason("classic"))
        assertNull(footerPctDisabledReason("simple"))
        val reason = assertNotNull(footerPctDisabledReason("overlay"))
        assertTrue(reason.contains("叠加"), "原因里要写清楚是叠加版式：$reason")
        assertTrue(reason.contains("不生效"), "原因里要写清楚不生效：$reason")
    }

    // ---- A-04 Logo 水平位置 ----------------------------------------------

    @Test
    fun logo_center_x_is_disabled_unless_the_position_is_center() {
        assertNull(logoCenterXDisabledReason("center"))
        assertNull(logoCenterXDisabledReason(null))
        val left = assertNotNull(logoCenterXDisabledReason("left"))
        assertEquals(left, logoCenterXDisabledReason("right"))
        assertTrue(left.contains("居中"), "原因要指向生效条件：$left")
    }

    // ---- A-02 Logo 居左吃掉左侧文字 --------------------------------------

    @Test
    fun choosing_logo_left_warns_about_the_two_left_rows() {
        val session = DesignSession()
        val siblings = listOf(toggle("text.showMake", true), toggle("text.showModel", true))
        val note = logoLeftHidesTextNote(siblings, session)
        assertTrue(note.contains("居左"), note)
        assertTrue(note.contains("品牌"), note)
        assertTrue(note.contains("机型"), note)
        assertTrue(note.contains("不显示"), note)
    }

    @Test
    fun the_left_warning_says_only_what_is_still_shown() {
        val session = DesignSession()
        val modelOff = listOf(toggle("text.showMake", false), toggle("text.showModel", true))
        val note = logoLeftHidesTextNote(modelOff, session)
        assertTrue(note.contains("机型"), note)
        assertTrue(!note.contains("品牌"), "关掉的品牌行不该再点名：$note")
        val bothOff = listOf(toggle("text.showMake", false), toggle("text.showModel", false))
        assertTrue(!logoLeftHidesTextNote(bothOff, session).contains("两行"))
    }

    // ---- A-19 模板会裁照片 ------------------------------------------------

    @Test
    fun personal_simple_is_reported_as_cropping_the_photo() {
        // personal_border 的 inner 照片框 (2667 × 1790) 比 4:3 的照片更宽 → 上下裁。
        val crop = assertNotNull(ExportEstimate.cropEstimate(2667, 2000, ImageRect(0f, 0f, 2667f, 1790f)))
        assertEquals(210, crop.pixels)
        assertTrue(crop.vertical)
        assertTrue(crop.text.contains("上下"), crop.text)
        assertTrue(crop.text.contains("210"), crop.text)
        assertTrue(crop.text.contains("按版式裁切"), crop.text)
    }

    @Test
    fun a_template_that_keeps_the_photo_does_not_warn() {
        // 照片框与照片同比例（wm-classic 的原尺寸路径）→ 不裁，不提示。
        assertNull(ExportEstimate.cropEstimate(2667, 2000, ImageRect(0f, 0f, 2667f, 2000f)))
        // 2 % 以内的比例差按「不裁」处理，避免给用户一句无意义的提示。
        assertNull(ExportEstimate.cropEstimate(2667, 2000, ImageRect(0f, 0f, 2667f, 1994f)))
        // 没有预览框 / 没有照片尺寸时不猜。
        assertNull(ExportEstimate.cropEstimate(2667, 2000, null))
        assertNull(ExportEstimate.cropEstimate(0, 2000, ImageRect(0f, 0f, 2667f, 1790f)))
    }

    @Test
    fun a_taller_box_crops_left_and_right() {
        val crop = assertNotNull(ExportEstimate.cropEstimate(2000, 2000, ImageRect(0f, 0f, 1600f, 2000f)))
        assertTrue(!crop.vertical)
        assertEquals(400, crop.pixels)
    }

    // ---- D-1 长边文案 ----------------------------------------------------

    @Test
    fun the_long_edge_copy_does_not_promise_a_shrink_only() {
        val line = ExportCopy.resolutionLine(ExportSizeMode.LongEdge, 2048)
        assertTrue(line.contains("归一"), "长边档放大也生效，文案不能只写「缩」：$line")
        // 原尺寸档仍然是「不缩放」。
        assertEquals("原分辨率，不缩放", ExportCopy.resolutionLine(ExportSizeMode.Original, 2048))
    }

    // ---- D-1 弹层估算与公式同源 ------------------------------------------

    @Test
    fun the_dialog_estimate_upscales_to_the_requested_long_edge() {
        val canvas = ExportPixels(1600, 1245)
        val up = assertNotNull(ExportEstimate.output(canvas, ExportSizeMode.LongEdge, 2048))
        assertEquals(ExportPixels(2048, 1594), up)
        val down = assertNotNull(ExportEstimate.output(canvas, ExportSizeMode.LongEdge, 900))
        assertEquals(ExportPixels(900, 700), down)
        assertEquals(canvas, ExportEstimate.output(canvas, ExportSizeMode.Original, 2048))
    }

    @Test
    fun the_dialog_estimate_matches_the_shared_formula() {
        // 右栏/顶栏/手机/弹层/桥接：同一个 applyLongEdge，同一组数字。
        val canvas = ExportPixels(2667, 2296)
        val shared = com.framemark.native.inspector.OutputSize.applyLongEdge(
            com.framemark.native.inspector.OutputPixels(canvas.width, canvas.height),
            ExportSizeMode.LongEdge,
            2048,
        )
        val dialog = ExportEstimate.output(canvas, ExportSizeMode.LongEdge, 2048)
        assertEquals(shared!!.width, dialog!!.width)
        assertEquals(shared.height, dialog.height)
    }

    @Test
    fun quality_rules_still_treat_png_as_lossless() {
        // D-2 的另一半：质量轴只对 JPEG / 有损 WebP 有意义，PNG 下按 100 提交。
        assertTrue(ExportRules.qualityApplies(SampleFormat.Jpeg, webpLossless = true))
        assertTrue(!ExportRules.qualityApplies(SampleFormat.Png, webpLossless = true))
        assertEquals(60, ExportRules.effectiveQuality("60", SampleFormat.Jpeg, webpLossless = true))
        assertEquals(100, ExportRules.effectiveQuality("60", SampleFormat.Png, webpLossless = true))
    }
}
