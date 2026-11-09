package com.framemark.native.inspector

import com.framemark.native.workflow.ExportSizeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AU2 A-06/A-07：画布公式的单一实现。
 *
 * 期望值全部来自**引擎实测**（`e4hand.exe render` 的 report.canvas，证据见
 * `evidence\\X2\\engine\\`）与 AU2 报告 §3.2 的表，不是照抄 UI 旧实现：
 *
 * - 1600 × 1067 的照片（WP-F1 fixtures）在 wm-classic 上：默认 footerPct 11.1 →
 *   1600 × 1245（就是 F1 那次导出的成片），pct 5 / 8 / 25 → 1158 / 1195 / 1467；
 * - 2667 × 2000 在 wm-classic 上：pct 5 / 8 / 25 / 11.1 → 2170 / 2213 / 2667 / 2296；
 * - 2667 × 2000 在 personal-simple（borderPct 2 / 10）→ 2774 × 2107 / 3200 × 2533。
 */
class OutputSizeTest {

    private fun slider(
        param: String,
        value: Float,
        min: Float? = 0f,
        max: Float? = 100f,
        default: Float? = null,
    ) = InspectorControl(
        key = "wm-classic::$param",
        controlId = param,
        param = param,
        label = param,
        kind = "slider",
        section = InspectorSection.Theme,
        type = ControlType.Slider,
        initial = ControlValue.Num(value),
        min = min,
        max = max,
        step = 0.5f,
        options = emptyList(),
        multiline = false,
        declaredDefault = default?.toString(),
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )

    private fun toggle(param: String, value: Boolean) = InspectorControl(
        key = "wm-classic::$param",
        controlId = param,
        param = param,
        label = param,
        kind = "toggle",
        section = InspectorSection.Theme,
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

    private fun segment(param: String, value: String, options: List<String>) = InspectorControl(
        key = "wm-classic::$param",
        controlId = param,
        param = param,
        label = param,
        kind = "gravitySegment",
        section = InspectorSection.Theme,
        type = ControlType.Select,
        initial = ControlValue.Str(value),
        min = null,
        max = null,
        step = null,
        options = options.map { ControlOption(it, it) },
        multiline = false,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )

    /** wm-classic 的参数族：theme + footerMode + footerPct（footerAuto 被 gate）。 */
    private fun classicControls(
        mode: String = "classic",
        pct: Float = 11.1f,
        auto: Boolean? = null,
    ): List<InspectorControl> = buildList {
        add(segment("params.footerMode", mode, listOf("classic", "simple", "overlay")))
        add(slider("params.footerPct", pct, 5f, 25f, 11.1f))
        if (auto != null) add(toggle("params.footerAuto", auto))
    }

    private fun size(controls: List<InspectorControl>, w: Int = 1600, h: Int = 1067): OutputPixels? =
        OutputSize.canvas(w, h, controls) { control ->
            when (val initial = control.initial) {
                is ControlValue.Num -> initial.value
                is ControlValue.Flag -> initial.value
                is ControlValue.Str -> initial.value
            }
        }

    // ---- 与引擎逐像素对齐 -------------------------------------------------

    @Test
    fun classic_footer_matches_the_engine_at_1600x1067() {
        assertEquals(OutputPixels(1600, 1245), size(classicControls()))
        assertEquals(OutputPixels(1600, 1158), size(classicControls(pct = 5f)))
        assertEquals(OutputPixels(1600, 1195), size(classicControls(pct = 8f)))
        assertEquals(OutputPixels(1600, 1467), size(classicControls(pct = 25f)))
    }

    @Test
    fun classic_footer_matches_the_engine_at_2667x2000() {
        assertEquals(OutputPixels(2667, 2296), size(classicControls(), 2667, 2000))
        assertEquals(OutputPixels(2667, 2170), size(classicControls(pct = 5f), 2667, 2000))
        assertEquals(OutputPixels(2667, 2213), size(classicControls(pct = 8f), 2667, 2000))
        assertEquals(OutputPixels(2667, 2667), size(classicControls(pct = 25f), 2667, 2000))
    }

    @Test
    fun footer_floor_uses_the_short_side_and_not_a_truncation() {
        // 2667 × 25% = 666.75 → 引擎 round = 2667（旧 UI 的 toInt() 得到 2666）。
        val pixels = assertNotNull(size(classicControls(pct = 25f), 2667, 2000))
        assertEquals(2667, pixels.height)
        // pct 5 的 133.35 低于地板 2000 × 8.5% = 170，画布按地板算。
        assertEquals(170f, OutputSize.FOOTER_FLOOR_OF_SHORT_SIDE * 2000f)
    }

    @Test
    fun overlay_layout_keeps_the_photo_canvas() {
        assertEquals(OutputPixels(1600, 1067), size(classicControls(mode = "overlay")))
        assertEquals(OutputPixels(2667, 2000), size(classicControls(mode = "overlay", pct = 25f), 2667, 2000))
    }

    @Test
    fun simple_layout_uses_the_engine_footer_metrics() {
        // wm-simple：footerAuto 为 true 且 solid 在控件里。
        assertEquals(OutputPixels(1600, 1263), size(classicControls(mode = "simple", auto = true)))
        // footerAuto 为 false 时引擎掉回经典公式（A-01 的引擎侧成因，公式照抄）。
        assertEquals(OutputPixels(1600, 1187), size(classicControls(mode = "simple", pct = 7.5f, auto = false)))
        // 没有 footerAuto 控件 → 设计默认 false（wm-classic 等 6 张卡）→ 经典公式。
        assertEquals(OutputPixels(1600, 1245), size(classicControls(mode = "simple")))
    }

    @Test
    fun simple_footer_height_scales_with_footer_text_scale() {
        val controls = buildList {
            addAll(classicControls(mode = "simple", auto = true))
            add(slider("params.footerTextScale", 2f, 0.5f, 2f, 1f))
        }
        // 引擎实测 footerTextScale=2 → 1600 × 1458。
        assertEquals(OutputPixels(1600, 1458), size(controls))
    }

    @Test
    fun personal_border_adds_the_border_on_both_axes() {
        // personal_border.rs layout(): (pw + 2b, ph + 2b)，b = pw·borderPct/100。
        val controls = listOf(
            slider("params.borderPct", 2f, 0f, 10f, 2f),
            slider("params.footerPct", 9.5f, 5f, 25f, 9.5f),
        )
        assertEquals(OutputSize.Family.PersonalBorder, OutputSize.familyOf(controls))
        assertEquals(OutputPixels(2774, 2107), size(controls, 2667, 2000))
        assertEquals(OutputPixels(3200, 2533), size(listOf(slider("params.borderPct", 10f)), 2667, 2000))
    }

    @Test
    fun frame_border_inside_the_frame_json_is_part_of_the_canvas() {
        val controls = listOf(
            segment("params.footerMode", "classic", listOf("classic")),
            slider("params.footerPct", 11.1f),
            InspectorControl(
                key = "card::params:frame",
                controlId = "params:frame",
                param = "params.frame",
                label = "相框文字",
                kind = "text",
                section = InspectorSection.Theme,
                type = ControlType.Text,
                initial = ControlValue.Str("""{"borderPct":2,"borderTopPct":1}"""),
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
            ),
        )
        // border = 1600 × 2% = 32（两边）→ 宽 1664；高 = 1067 + topInset 16 + 页脚 177.6。
        assertEquals(OutputPixels(1664, 1261), size(controls))
    }

    // ---- 长边档（D-1）----------------------------------------------------

    @Test
    fun long_edge_normalises_both_directions() {
        val canvas = OutputPixels(1600, 1245)
        // 放大：F1 的 1600 × 1245 选 2048 → 2048 × 1594（1245 × 1.28 = 1593.6）。
        assertEquals(OutputPixels(2048, 1594), OutputSize.applyLongEdge(canvas, ExportSizeMode.LongEdge, 2048))
        // 缩小：选 900 → 900 × 700。
        assertEquals(OutputPixels(900, 700), OutputSize.applyLongEdge(canvas, ExportSizeMode.LongEdge, 900))
        // 原尺寸档不看长边。
        assertEquals(canvas, OutputSize.applyLongEdge(canvas, ExportSizeMode.Original, 2048))
        // 目标与当前长边相同 → 不动；非法目标 → 不动。
        assertEquals(canvas, OutputSize.applyLongEdge(canvas, ExportSizeMode.LongEdge, 1600))
        assertEquals(canvas, OutputSize.applyLongEdge(canvas, ExportSizeMode.LongEdge, 0))
        assertEquals(canvas, OutputSize.applyLongEdge(canvas, ExportSizeMode.LongEdge, null))
        assertNull(OutputSize.applyLongEdge(null, ExportSizeMode.LongEdge, 2048))
    }

    // ---- 拍照 / 未知族 ---------------------------------------------------

    @Test
    fun family_detection_prefers_the_personal_border_formula() {
        assertEquals(OutputSize.Family.PersonalBorder, OutputSize.familyOf(listOf("params.borderPct", "params.footerPct")))
        assertEquals(OutputSize.Family.BrandFooter, OutputSize.familyOf(listOf("params.footerMode", "params.footerPct")))
        assertEquals(OutputSize.Family.PhotoOnly, OutputSize.familyOf(listOf("params.marginPct", "params.radiusPct")))
        assertEquals(OutputSize.Family.PhotoOnly, OutputSize.familyOf(emptyList<String>()))
    }

    @Test
    fun unknown_photo_size_yields_null_instead_of_a_guess() {
        assertNull(OutputSize.canvas(0, 1067, OutputSize.Family.PhotoOnly))
        assertNull(OutputSize.canvas(1600, 0, OutputSize.Family.BrandFooter))
        assertNull(OutputSize.canvas(0, 0, controls = classicControls()) { null })
    }

    @Test
    fun measured_fallback_only_reproduces_the_rendered_frame_aspect() {
        // 只有参数族未知的卡片才用它：1600 × (1245 / 1600)。
        assertEquals(OutputPixels(1600, 1245), OutputSize.measuredCanvas(1600, 1600, 1245))
        assertNull(OutputSize.measuredCanvas(0, 1600, 1245))
        assertNull(OutputSize.measuredCanvas(1600, 0, 1245))
    }

    @Test
    fun floor_slider_limit_never_exceeds_the_declared_range() {
        // 4:3 照片的地板 = 8.5 × 1067 / 1600 = 5.67%，落在 5–25% 区间内。
        val floor = footerPctFloor(1600, 1067)
        assertNotNull(floor)
        assertTrue(floor != null && floor > 5f && floor < 6f)
        // 竖图（短边 = 宽）的地板 = 8.5%，仍然 ≤ 25%。
        assertEquals(8.5f, footerPctFloor(1600, 2400)!!, 0.001f)
        assertNull(footerPctFloor(0, 1067))
    }

    // ---- FIX1：EXIF 方向 → 显示尺寸 ---------------------------------------

    @Test
    fun display_pixels_swaps_the_axes_only_for_quarter_turns() {
        // 1..4 不转轴（2/3/4 是镜像 / 180°，尺寸不变）。
        for (orientation in listOf(0, 1, 2, 3, 4)) {
            assertEquals(
                OutputPixels(2000, 2667),
                OutputSize.displayPixels(2000, 2667, orientation),
                "orientation=$orientation 不应换轴",
            )
        }
        // 5..8 是 ±90°，两个轴互换。
        for (orientation in listOf(5, 6, 7, 8)) {
            assertEquals(
                OutputPixels(2667, 2000),
                OutputSize.displayPixels(2000, 2667, orientation),
                "orientation=$orientation 必须换轴",
            )
        }
    }

    @Test
    fun display_pixels_never_invents_a_size_for_unknown_dimensions() {
        assertEquals(OutputPixels(0, 2667), OutputSize.displayPixels(0, 2667, 6))
        assertEquals(OutputPixels(2000, 0), OutputSize.displayPixels(2000, 0, 6))
        assertEquals(OutputPixels(0, 0), OutputSize.displayPixels(0, 0, 8))
    }

    @Test
    fun the_engine_canvas_of_a_rotated_photo_matches_the_reported_numbers() {
        // 引擎实测（`e4hand render --card wm-classic --fit native` 的 `canvas=`）：
        // 容器 2000 × 2667、页脚 11.1 %
        //   orientation 1 → 2000 × 2889；orientation 6/8 → 2667 × 2296。
        val controls = classicControls(pct = 11.1f)
        val upright = OutputSize.displayPixels(2000, 2667, 1)
        val rotated = OutputSize.displayPixels(2000, 2667, 6)
        assertEquals(OutputPixels(2000, 2667), upright)
        assertEquals(OutputPixels(2667, 2000), rotated)
        assertEquals(OutputPixels(2000, 2889), size(controls, upright.width, upright.height))
        assertEquals(OutputPixels(2667, 2296), size(controls, rotated.width, rotated.height))
        assertEquals(OutputPixels(2667, 2296), size(controls, rotated.width, rotated.height))
    }
}
