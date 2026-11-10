package com.framemark.native.shell

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.OutputPixels
import com.framemark.native.inspector.OutputSize
import com.framemark.native.workflow.DesignSession
import com.framemark.native.product.ExportSettings
import com.framemark.native.product.ExportSizeMode
import com.framemark.native.product.PhotoRef
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * AU2 A-06 的验收口径：**同一份设计，任何窗口宽度 / 任何手机宽度，显示同一个数字**。
 *
 * 顶栏「→ 导出 W × H」、右栏「导出尺寸」、手机样式页三处现在都调 [OutputSize]，
 * 所以「窗口宽度」在这条链上根本没有入口。为了证明这一点，这个测试拿**真实**的
 * 布局策略算出 1440 / 1280 两种窗口下的舞台宽度（两种窗口确实不同），再用不同
 * 的渲染帧尺寸（预览长边跟舞台走）走一遍顶栏入口，断言数字不变；手机那侧用
 * 360 / 393 / 430 dp 三种宽度走同一公式。
 */
class DesktopExportSizeParityTest {

    private fun control(param: String, value: Float): InspectorControl = InspectorControl(
        key = "wm-classic::$param",
        controlId = param,
        param = param,
        label = param,
        kind = "slider",
        section = InspectorSection.Theme,
        type = ControlType.Slider,
        initial = ControlValue.Num(value),
        min = 5f,
        max = 25f,
        step = 0.5f,
        options = emptyList(),
        multiline = false,
        declaredDefault = "11.1",
        currentText = null,
        autoMatch = null,
        unit = "%",
        hidden = false,
    )

    private fun modeControl(value: String): InspectorControl = InspectorControl(
        key = "wm-classic::params:footerMode",
        controlId = "params:footerMode",
        param = "params.footerMode",
        label = "版式",
        kind = "gravitySegment",
        section = InspectorSection.Theme,
        type = ControlType.Select,
        initial = ControlValue.Str(value),
        min = null,
        max = null,
        step = null,
        options = listOf("classic", "simple", "overlay").map {
            com.framemark.native.inspector.ControlOption(it, it)
        },
        multiline = false,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )

    private fun model(controls: List<InspectorControl>) = InspectorCardModel(
        cardId = "wm-classic",
        cardName = "经典双行",
        audited = true,
        isIr = false,
        effectiveCount = controls.size,
        gatedCount = 0,
        declaredCount = controls.size,
        effectiveIds = controls.map { it.controlId },
        gatedIds = emptyList(),
        visibleIds = controls.map { it.controlId },
        hiddenIds = emptyList(),
        controls = controls,
        sections = emptyList(),
    )

    private fun photo(orientation: Int = 1) = PhotoRef(
        id = "p1",
        path = "photo.jpg",
        displayName = "photo.jpg",
        orientation = orientation,
        widthPx = 1600,
        heightPx = 1067,
    )

    /**
     * FIX1 竖拍夹具：容器里存 2000 × 2667（真机的竖拍文件就是这样），方向单列在
     * [PhotoRef.orientation]；显示尺寸由 [OutputSize.displayPixels] 归一。
     */
    private fun portraitPhoto(orientation: Int) = PhotoRef(
        id = "p6",
        path = "portrait.jpg",
        displayName = "portrait.jpg",
        orientation = orientation,
        widthPx = 2000,
        heightPx = 2667,
    )

    /** 1440 / 1280 两种窗口下真实的舞台宽度（用产品自己的布局策略算）。 */
    private fun stageWidthAt(windowWidthDp: Float): Float = computeDesktopColumns(
        availableWidthDp = windowWidthDp,
        requestedLeft = desktopLibraryMinWidth(),
        requestedRight = desktopInspectorMinWidth(),
        leftCollapsed = false,
        rightCollapsed = false,
    ).stage

    @Test
    fun the_stage_size_label_is_identical_at_1440_and_1280() {
        val controls = listOf(modeControl("classic"), control("params.footerPct", 11.1f))
        val cardModel = model(controls)
        val session = DesignSession()
        val settings = ExportSettings(sizeMode = ExportSizeMode.Original)

        val stage1440 = stageWidthAt(1440f)
        val stage1280 = stageWidthAt(1280f)
        assertNotEquals(stage1440, stage1280, "两种窗口的舞台宽度必须真的不同，否则这条测试没有意义")

        // 预览长边跟着舞台走，所以两种窗口拿到的渲染帧尺寸不同（这里取 3:4 的常见量级）。
        val rendered1440 = IntSize(1536, 1195)
        val rendered1280 = IntSize(1200, 934)
        val at1440 = stageExportPixels(cardModel, session, photo(), rendered1440, settings)
        val at1280 = stageExportPixels(cardModel, session, photo(), rendered1280, settings)

        assertEquals(OutputPixels(1600, 1245), at1440)
        assertEquals(at1440, at1280)
        assertEquals("1600 × 1245", at1440.toString())
    }

    @Test
    fun the_long_edge_mode_is_identical_at_1440_and_1280() {
        val controls = listOf(modeControl("classic"), control("params.footerPct", 11.1f))
        val cardModel = model(controls)
        val session = DesignSession()
        val settings = ExportSettings(sizeMode = ExportSizeMode.LongEdge, longEdge = 2048)

        val at1440 = stageExportPixels(cardModel, session, photo(), IntSize(1536, 1195), settings)
        val at1280 = stageExportPixels(cardModel, session, photo(), IntSize(1200, 934), settings)
        assertEquals(OutputPixels(2048, 1594), at1440)
        assertEquals(at1440, at1280)
    }

    @Test
    fun the_same_design_reads_the_same_number_on_three_phone_widths() {
        val controls = listOf(modeControl("classic"), control("params.footerPct", 11.1f))
        val desktop = stageExportPixels(
            model(controls),
            DesignSession(),
            photo(),
            IntSize(1536, 1195),
            ExportSettings(),
        )
        // 手机样式页没有窗口入参：它把同一个 [OutputSize.canvas] 的结果交给
        // `OutputSize.applyLongEdge`。三种常见手机宽度（360 / 393 / 430 dp）的
        // 底板与参数页排版不同，但尺寸数字必须一致。
        val phoneWidths = listOf(360f, 393f, 430f)
        val sizes = phoneWidths.map { width ->
            // 底板可用宽度随手机宽度变，但画布公式不读它。
            val sheetWidth = width - 2 * 18f
            check(sheetWidth > 0f)
            OutputSize.applyLongEdge(
                OutputSize.canvas(
                    photoWidthPx = 1600,
                    photoHeightPx = 1067,
                    controls = controls,
                ) { control ->
                    (control.initial as? ControlValue.Num)?.value
                        ?: (control.initial as? ControlValue.Str)?.value
                        ?: (control.initial as? ControlValue.Flag)?.value
                },
                ExportSizeMode.Original,
                null,
            )
        }
        assertEquals(listOf(desktop, desktop, desktop), sizes)
        assertEquals("1600 × 1245", sizes.first().toString())
    }

    @Test
    fun an_unknown_family_still_falls_back_to_the_rendered_frame() {
        // 厂商规格卡（没有任何画布参数）用渲染帧兜底；两种窗口的比例相同，
        // 于是数字也相同（1 px 级别的取整差异不会出现，因为比例是同一份设计）。
        val irModel = model(emptyList())
        val a = stageExportPixels(irModel, DesignSession(), photo(), IntSize(1200, 1000), ExportSettings())
        val b = stageExportPixels(irModel, DesignSession(), photo(), IntSize(600, 500), ExportSettings())
        assertEquals(OutputPixels(1600, 1333), a)
        assertEquals(a, b)
    }

    // ---- FIX1：竖拍（EXIF Orientation 1 / 6 / 8）三处同数 ----------------

    /**
     * 期望值不是推出来的，是引擎实测（`e4hand render --card wm-classic --fit native`
     * 的 `canvas=` 字段，照片容器 2000 × 2667）：
     *
     * | EXIF Orientation | 引擎 `canvas=` | 说明 |
     * |---|---|---|
     * | 1 | `2000x2889` | 不转，页脚 11.1 % = 222 |
     * | 6 | `2667x2296` | 转 90°，页脚 11.1 % = 296 |
     * | 8 | `2667x2296` | 转 90°，同上 |
     *
     * 修之前界面读的是容器尺寸，orientation=6 时印出 `2000 × 2889`（等于
     * orientation=1 的成片），与真正的成片 `2667 × 2296` 差一整个旋转。
     */
    @Test
    fun a_portrait_photo_reads_the_engine_canvas_for_orientations_1_6_and_8() {
        val controls = listOf(modeControl("classic"), control("params.footerPct", 11.1f))
        val cardModel = model(controls)
        val session = DesignSession()
        val cases = listOf(
            1 to OutputPixels(2000, 2889),
            6 to OutputPixels(2667, 2296),
            8 to OutputPixels(2667, 2296),
        )
        for ((orientation, expected) in cases) {
            val photo = portraitPhoto(orientation)
            // ① 舞台标题行 / 顶栏 / 导出弹层的同一条链（stageCanvasPixels）。
            val stage = stageExportPixels(cardModel, session, photo, IntSize(1536, 2048), ExportSettings())
            assertEquals(expected, stage, "orientation=$orientation 舞台尺寸必须等于成片")
            // ② 右栏「导出尺寸」与手机样式页：同一个 [OutputSize.canvas] + 同一份显示尺寸。
            val display = OutputSize.displayPixels(photo.widthPx, photo.heightPx, photo.orientation)
            val rail = OutputSize.applyLongEdge(
                OutputSize.canvas(display.width, display.height, controls) { control ->
                    (control.initial as? ControlValue.Num)?.value
                        ?: (control.initial as? ControlValue.Str)?.value
                        ?: (control.initial as? ControlValue.Flag)?.value
                },
                ExportSizeMode.Original,
                null,
            )
            assertEquals(expected, rail, "orientation=$orientation 右栏尺寸必须等于成片")
            // ③ 容器尺寸保持原样（导入器只读文件头），方向只影响显示侧。
            assertEquals(2000, photo.widthPx, "orientation=$orientation 容器宽度不应被改写")
            assertEquals(2667, photo.heightPx, "orientation=$orientation 容器高度不应被改写")
            if (orientation == 1) {
                assertEquals(OutputPixels(2000, 2667), display, "orientation=1 不换轴")
            } else {
                assertEquals(OutputPixels(2667, 2000), display, "orientation=$orientation 必须换轴")
            }
        }
    }
}
