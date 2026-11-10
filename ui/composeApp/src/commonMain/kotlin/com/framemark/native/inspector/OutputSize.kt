package com.framemark.native.inspector

import com.framemark.native.product.ExportSizeMode
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 成片画布像素 —— 导出后的真实像素尺寸。
 *
 * 这是**唯一**的尺寸口径：右栏「导出尺寸」、顶栏「→ 导出 W × H」、手机样式页、
 * 导出弹层的长边换算全部走这里，所以同一份设计在任何窗口宽度 / 任何设备上给出的
 * 数字相同（不含任何 dp / 窗口 / 渲染帧尺寸）。
 */
data class OutputPixels(val width: Int, val height: Int) {
    val longEdge: Int get() = max(width, height)
    val area: Long get() = width.toLong() * height.toLong()

    override fun toString(): String = "$width × $height"
}

/**
 * 画布公式（AU2 A-06/A-07 的修复点）。
 *
 * 与引擎**同式**，逐行对应：
 *
 *  - `engine/e4-hand/src/templates/brand_footer.rs:118 layout()`：经典版式
 *    画布 = `(pw + 2·border, ph + topInset + max(pw·pct/100, s·0.085))`，`s` 是短边；
 *    简约版式（且 `footerAuto` 不为 false）画布 = `(pw, ph + footer_h)`，`footer_h`
 *    来自 `draw.rs:2271 simple_footer_metrics`；叠加版式画布 = `(pw, ph)`。
 *  - `engine/e4-hand/src/templates/personal_border.rs:12 layout()`：四边同宽
 *    画布 = `(pw + 2b, ph + 2b)`，`b = pw·borderPct/100`（默认 2%）。
 *  - 舍入：引擎 `jsval::js_round`（Math.round，半向上），不是截断 —— AU2 A-06 的
 *    「2666 vs 2667」就是 `.toInt()` 截断造成的，这里统一 [roundToInt]。
 *
 * 尺寸**只由照片像素 + 该卡实际声明的参数**决定，与窗口宽度、UI 缩放、预览帧的
 * 采样长边都无关；预览帧只用来在参数族未知的卡片上兜底（见 [measuredCanvas]）。
 */
object OutputSize {

    /** 引擎 `brand_footer.rs:133` 的底部留白地板：短边 × 8.5%。 */
    const val FOOTER_FLOOR_OF_SHORT_SIDE = 0.085f

    /** 引擎 `pct(params, "footerPct", 7.5)` 的缺省值。 */
    const val FOOTER_DEFAULT_PCT = 7.5f

    /** 引擎 `personal_border` 的 `borderPct` 缺省值。 */
    const val BORDER_DEFAULT_PCT = 2.0f

    /** 引擎 `footerTextScale` 缺省值（0 也按 1 处理，见 `simple_footer_metrics`）。 */
    const val FOOTER_TEXT_SCALE_DEFAULT = 1.0f

    /**
     * `footerAuto` 在**卡片没有把它暴露成控件**时的取值。
     *
     * 引擎的 `layout()` 把「参数缺失」当成 true，但引擎拿到的是
     * `design JSON（卡片默认值）+ 用户改过的参数`：`capabilities.json` 里 7 张带
     * `footerMode` 的卡中，6 张的 `footerAuto` 审计当前值是 false 且被 gate 掉
     * （`wm-classic/wm-canon/wm-wide/wm-black/wm-right/wm-left`），只有 `wm-simple`
     * 是 true 并且以控件暴露。所以「没有控件」= 设计默认 false = 走经典公式；
     * 这条不变量由 `OutputSizeDataTest` 对着 `capabilities.json` + `variants.json`
     * 逐卡锁住。
     */
    const val FOOTER_AUTO_WHEN_UNDECLARED = false

    /**
     * 哪一套画布公式。判定只看该卡**声明了哪些参数**（注册表 consumer 名），
     * 不看卡片 id，所以新增同族卡片自动成立。
     */
    enum class Family {
        /** `brand_footer`（经典 / 简约 / 叠加）+ `params.frame` 的边框内缩。 */
        BrandFooter,

        /** `personal_border`：四边等宽细边框，固定比例裁切照片。 */
        PersonalBorder,

        /** 未知族：画布就是照片本身（顶栏可用渲染帧兜底测量）。 */
        PhotoOnly,
    }

    /**
     * 参数名决定族：出现 `borderPct` 即 `personal_border`；否则出现
     * `footerMode` / `footerPct` 即 `brand_footer`；都没有就是 [Family.PhotoOnly]。
     *
     * `borderPct` 优先：`personal_border` 的 `layout()` 只读它，即使同卡还声明了
     * `footerPct`（`personal-simple` 就是这种），画布也不能按页脚留白加高。
     */
    fun familyOf(paramNames: Iterable<String>): Family {
        var brandFooter = false
        for (raw in paramNames) {
            when (raw.substringAfterLast('.')) {
                "borderPct" -> return Family.PersonalBorder
                "footerMode", "footerPct" -> brandFooter = true
            }
        }
        return if (brandFooter) Family.BrandFooter else Family.PhotoOnly
    }

    fun familyOf(controls: List<InspectorControl>): Family = familyOf(controls.map { it.param })

    /**
     * EXIF orientation 应用到**容器**像素尺寸后的显示尺寸（FIX1）。
     *
     * 引擎解码走 Skia `SkImages::DeferredFromEncodedData`，EXIF 方向在那一步就被
     * 烘进光栅，随后 `templates::layout()` 读的是**解码后**的 `pw/ph`。所以一个
     * 文件里存 `2000 × 2667`、Orientation=6 的照片，引擎按 `2667 × 2000` 排版：
     * `2667 × 2296`（`e4hand.card=wm-classic template=brandFooter canvas=2667x2296`）。
     *
     * 显示侧过去直接拿 `PhotoRef.widthPx/heightPx`（容器尺寸）喂 [canvas]，于是同一
     * 张照片印出 `2000 × 2889`，与成片 `2667 × 2296` 对不上（WP-Q2 D1 / AU3-1）。
     * 方向 5..8 是把画面转 90°，两个轴互换；1..4 与 0（读不到）不换。
     *
     * **只用在容器尺寸这一侧**：桌面导入器存的是容器尺寸（`PhotoMetadata.read`
     * 只读头，方向单列在 `PhotoRef.orientation`），手机导入器
     * （`AndroidPhoneAssetIo.photoMeta`）已经存了显示尺寸，那边不要再过一次本函数，
     * 否则会换两次。
     */
    fun displayPixels(widthPx: Int, heightPx: Int, orientation: Int): OutputPixels {
        if (widthPx <= 0 || heightPx <= 0) return OutputPixels(widthPx, heightPx)
        return if (orientation in 5..8) OutputPixels(heightPx, widthPx) else OutputPixels(widthPx, heightPx)
    }

    /**
     * 画布像素；照片尺寸未知时返回 null（宁可不写，也不写一个猜的数字）。
     *
     * [footerMode] 等参数用 `null` 表示「这张卡没有声明该参数」，此时取引擎缺省值。
     */
    fun canvas(
        photoWidthPx: Int,
        photoHeightPx: Int,
        family: Family,
        footerMode: String? = null,
        footerPct: Float? = null,
        footerAuto: Boolean? = null,
        footerTextScale: Float? = null,
        borderPct: Float? = null,
        frameBorderPct: Float? = null,
        frameBorderTopPct: Float? = null,
    ): OutputPixels? {
        if (photoWidthPx <= 0 || photoHeightPx <= 0) return null
        val pw = photoWidthPx.toFloat()
        val ph = photoHeightPx.toFloat()
        return when (family) {
            Family.PhotoOnly -> OutputPixels(photoWidthPx, photoHeightPx)
            Family.PersonalBorder -> {
                val b = pw * (borderPct ?: BORDER_DEFAULT_PCT) / 100f
                OutputPixels(roundPx(pw + 2f * b), roundPx(ph + 2f * b))
            }
            Family.BrandFooter -> {
                when (footerMode ?: "classic") {
                    "overlay" -> OutputPixels(photoWidthPx, photoHeightPx)
                    "simple" -> {
                        // 引擎：`if mode == "simple" && footer_auto` 走 simple 分支，
                        // 关掉 footerAuto 会掉回经典公式（A-01 的引擎侧问题，公式照抄）。
                        // 卡片没有这个控件时按设计默认 false 处理，见
                        // [FOOTER_AUTO_WHEN_UNDECLARED]。
                        if (footerAuto ?: FOOTER_AUTO_WHEN_UNDECLARED) {
                            val footer = simpleFooterHeight(pw, ph, footerTextScale ?: FOOTER_TEXT_SCALE_DEFAULT)
                            OutputPixels(photoWidthPx, photoHeightPx + footer)
                        } else {
                            classicCanvas(pw, ph, footerPct, frameBorderPct, frameBorderTopPct)
                        }
                    }
                    else -> classicCanvas(pw, ph, footerPct, frameBorderPct, frameBorderTopPct)
                }
            }
        }
    }

    /**
     * 按控件表 + 取值函数算画布。取值函数给的是**引擎单位**的原始值
     * （`DesignSession.value(control)`）。
     */
    fun canvas(
        photoWidthPx: Int,
        photoHeightPx: Int,
        controls: List<InspectorControl>,
        value: (InspectorControl) -> Any?,
    ): OutputPixels? {
        if (photoWidthPx <= 0 || photoHeightPx <= 0) return null
        val family = familyOf(controls)
        if (family == Family.PhotoOnly) return OutputPixels(photoWidthPx, photoHeightPx)
        val byName = controls.groupBy { it.param.substringAfterLast('.') }
        fun control(name: String): InspectorControl? = byName[name]?.firstOrNull()
        fun number(name: String): Float? = control(name)?.let { numberValue(value(it)) }
        fun string(name: String): String? = control(name)?.let { value(it)?.toString() }
        fun bool(name: String): Boolean? = control(name)?.let { value(it) as? Boolean }
        val frame = string("frame")?.let { parseFrameBorder(it) }
        return canvas(
            photoWidthPx = photoWidthPx,
            photoHeightPx = photoHeightPx,
            family = family,
            footerMode = string("footerMode"),
            footerPct = number("footerPct"),
            footerAuto = bool("footerAuto"),
            footerTextScale = number("footerTextScale"),
            borderPct = number("borderPct"),
            frameBorderPct = frame?.first,
            frameBorderTopPct = frame?.second,
        )
    }

    /**
     * 指定长边档：把画布缩放到长边 = [longEdge]，**放大与缩小同式**
     * （引擎桥接 `DesktopEngineBridge.render()` 的 `scale` 与这里逐字一致）。
     *
     * 早先这里（和桥接）只允许缩小，于是「选 2048 导出仍是 1600×1245」——D-1。
     */
    fun applyLongEdge(pixels: OutputPixels?, mode: ExportSizeMode, longEdge: Int?): OutputPixels? {
        if (pixels == null) return null
        if (mode != ExportSizeMode.LongEdge) return pixels
        val target = longEdge?.takeIf { it > 0 } ?: return pixels
        val longest = pixels.longEdge
        if (longest <= 0 || longest == target) return pixels
        val scale = target.toDouble() / longest
        return OutputPixels(
            width = (pixels.width * scale).roundToInt().coerceAtLeast(1),
            height = (pixels.height * scale).roundToInt().coerceAtLeast(1),
        )
    }

    /**
     * 只有参数族未知的卡片（厂商规格 / 比例模板）才用的兜底：用渲染帧的宽高比
     * 反推画布高度。渲染帧是**测量值**，不参与「同一设计同一数字」的公式口径；
     * 已知族的卡片永远走 [canvas]，所以窗口变化不会改数字。
     */
    fun measuredCanvas(photoWidthPx: Int, renderedWidth: Int, renderedHeight: Int): OutputPixels? {
        if (photoWidthPx <= 0 || renderedWidth <= 0 || renderedHeight <= 0) return null
        return OutputPixels(photoWidthPx, roundPx(photoWidthPx.toFloat() * renderedHeight / renderedWidth))
    }

    /**
     * 引擎 `draw.rs:2271 simple_footer_metrics(...).footer_h` 的复刻：
     * `ceil(max(min_h, auto_h) · text_scale) + 1`。
     */
    fun simpleFooterHeight(widthPx: Float, heightPx: Float, textScale: Float = FOOTER_TEXT_SCALE_DEFAULT): Int {
        val long = max(widthPx, heightPx)
        val aspect = widthPx / max(heightPx, 1f)
        val scale = long / 3000f
        val scaleText = if (textScale == 0f) 1f else textScale
        val autoH = 0.1f * long - 5f + 80f * max(aspect - 1f, 0f)
        val minH = max(60f, (54f + 2f * 96f * 0.6f) * scale)
        return ceil(max(minH, autoH) * scaleText).toInt() + 1
    }

    private fun classicCanvas(
        pw: Float,
        ph: Float,
        footerPct: Float?,
        frameBorderPct: Float?,
        frameBorderTopPct: Float?,
    ): OutputPixels {
        val shortSide = min(pw, ph)
        val footer = (pw * (footerPct ?: FOOTER_DEFAULT_PCT) / 100f).coerceAtLeast(shortSide * FOOTER_FLOOR_OF_SHORT_SIDE)
        val border = (frameBorderPct ?: 0f).let { pw * it / 100f }
        val topInset = (frameBorderTopPct ?: frameBorderPct ?: 0f).let { pw * it / 100f }
        return OutputPixels(roundPx(pw + 2f * border), roundPx(ph + topInset + footer))
    }

    /** 引擎 `jsval::js_round`：Math.round（半向上），最小 2 px（引擎 `.max(2)`）。 */
    private fun roundPx(value: Float): Int = max(2, value.roundToInt())

    /** `params.frame` 是 JSON 字符串，只取 `borderPct` / `borderTopPct` 两个数。 */
    private fun parseFrameBorder(raw: String): Pair<Float?, Float?>? {
        val text = raw.trim()
        if (!text.startsWith("{")) return null
        fun field(name: String): Float? =
            Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toFloatOrNull()
        val border = field("borderPct")
        val top = field("borderTopPct")
        return if (border == null && top == null) null else border to top
    }

    /** 取值函数返回的数字可能是 Int / Float / Double / 字符串，统一成 Float。 */
    fun numberValue(value: Any?): Float? = when (value) {
        is Number -> value.toFloat()
        is String -> value.trim().toFloatOrNull()
        else -> null
    }
}
