package com.framemark.native.shell

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.StylePageBody
import com.framemark.native.inspector.fontScaleControlOf
import com.framemark.native.inspector.footerModeControlOf
import com.framemark.native.inspector.footerPctControlOf
import com.framemark.native.inspector.logoAdjustmentRows
import com.framemark.native.inspector.phoneStylePageBody
import com.framemark.native.inspector.themeControlOf
import com.framemark.native.inspector.withoutDuplicateStockAnchors
import com.framemark.native.product.StockWatermarkControls
import kotlin.math.abs
import kotlin.math.max

/**
 * 工具页（样式 / 文字 / Logo / 水印）的版式政策：一页分几组、每组多高、默认底板开多高。
 *
 * ## owner 的两版口径
 *
 * 2026-11 第一版：「所有页还是有排版问题，最好不要让用户去滑动屏幕再调整」→ 参数不滚、
 * 放不下就分页。那一版把每个工具拆成了 4–6 页（样式 4、水印 6、Logo 3、文字 2）。
 *
 * 2026-11 第二版（当前）：「分页也不对，太散了」—— 六页翻起来比滚动还烦。于是政策改成
 * **先加密、再分页**：
 *
 *  1. **先加密**：一页里的行本身就是触控目标（[PHONE_PAGE_ROW_DP] = 44 dp），所以
 *     标签、数值、开关、轨道**并排**在一行里；两条短滑杆并排也只占一行；色板保持
 *     一行六列；每页最多留一句引导语。放开那 60 dp 组头让给内容，加密幅度见下。
 *  2. **预算抬到舞台地板**：一页不再按写死的 150 dp 分，而是按**这个窗口的整块底板
 *     许可**（[pageAllowanceForWindow]：舞台 50 % 地板允许的底板减去 chrome 与公共区）。
 *     一页可以吃满它，装得下就**绝不**多分一页；[PHONE_PAGE_GROUP_BUDGET_DP] 只是最紧
 *     一档 carried 窗口的那个值（没有窗口可读的纯函数用它当默认）。
 *  3. **只在装不下时才分页**，并且分在用户认得的语义边界上（[PhonePageBlock.section]）：
 *     水印是「图层+位置 | 边距+宽度+外观」，不是六段等宽。
 *
 * 触控高度是硬约束：**没有任何一行被压到 44 dp 以下来「挤进去」**。一行的所有尺寸来自
 * composable 自己的数字，测试用本文件的常量重新断言它们 ≥ [PHONE_PAGE_MIN_TOUCH_DP]。
 *
 * ## 一页有多高
 *
 * 一页 = 上下留白（[PHONE_PAGE_BLOCK_PAD_DP]）+ 各行高之和 + 行间距
 * （[PHONE_PAGE_BLOCK_GAP_DP]）。行高（fontScale 1）：
 *
 * | 行 | 依据 | dp |
 * |----|------|----|
 * | 底色色板 | `PhoneSwatchRow` 38 圆 + 5 + 16 标签（一行六列） | 59 |
 * | 版式 | `PhoneSegmented` 44 + `PhoneSubNote` 4+18 | 66 |
 * | 开关 | `PhoneSwitchRow` `heightIn(min = 44)` | 44 |
 * | 滑杆（密度档） | `PhoneSliderRow(compact)`：标签列 + 40 轨道 + 数值，一行 | 44 |
 * | 两条滑杆一行 | 两个 44 的格子并排 | 44 |
 * | 分段（≤4 选项） | `PhoneSegmented` 44 | 44 |
 * | 文本行 | `PhoneTextValueRow`：6 + 16 + 34 | 56 |
 * | 边框规格 | `PhoneSpecRows` 2 × 18 + 6 + `PhoneSubNote` 22 | 64 |
 * | 文字框选择器 | 导航 44 + 4 + 编辑卡 44（标签/输入/眼睛一行） | 92 |
 * | Logo 调整行 | `CuratedSwitchRow` / `CuratedSegmented(touch)` / `CuratedSliderRow(compact)` | 44 |
 * | Logo 三个动作 | 三个 44 dp 按钮一行 + 4 + 说明行 17 | 65 |
 * | 水印 图层 | 48×36 缩略 + 上下 4 | 44 |
 * | 水印 九宫格 | `AnchorCellsGrid` 3 × 40 + 2 × 3 间隙 + 6 内边距 | 132 |
 * | 水印 边距 / 宽度 / 外观 | 一行（两条并排，或一条 + 数值） | 44 |
 * | 厂商 水印位置 说明 | 标签 + 当前值 + 按规格 chip 一行 44 + 一句 22 | 66 |
 *
 * ## 分页算法（纯函数，无 Compose）
 *
 * [partitionPages] 枚举**连续**切分（一行不能跨页，因为页是横滑的），先取页数最少，
 * 再取「切开同一 [PhonePageBlock.section] 的次数」最少（语义边界），再取最高的一页最矮，
 * 最后取最均衡。一行自己就超过预算时它独占一页 —— 测试会把它抓出来，不会悄悄留滚动条。
 */
internal data class PhonePageBlock(
    /** 这一行的稳定 id：控件行就是控件 key，合成行见 [PhonePageKeys]。 */
    val key: String,
    /** 这一行画哪些键（两条并排的滑杆一行两个）。 */
    val keys: List<String>,
    val heightDp: Float,
    /** 语义段：只有同一段的相邻行才「应该」留在同一页。 */
    val section: String,
)

/** 没有 `InspectorControl` 的行用这些稳定键（分页器与页面按它们取行）。 */
object PhonePageKeys {
    /** 文字页：渲染器报告的文本框选择器（一整块）。 */
    const val TEXT_SLOTS = "page:text-slots"

    /** 样式页：只读 边框规格 块。 */
    const val SPEC = "page:border-spec"

    /** Logo 页：没有可替换字标时的说明块。 */
    const val LOGO_NOTICE = "page:logo-notice"

    /** 样式页：厂商 `anchorGrid` 控件拆成九宫格 + 说明/按规格 两块。 */
    const val ANCHOR_GRID = "page:stock-anchor-grid"
    const val ANCHOR_FOOTER = "page:stock-anchor-footer"

    /** 水印页：一段一块（`WatermarkPanePart` 的键）。 */
    const val WATERMARK_EMPTY = "page:watermark-empty"
    const val WATERMARK_LAYER = "page:watermark-layer"
    const val WATERMARK_POSITION = "page:watermark-position"
    const val WATERMARK_MARGINS = "page:watermark-margins"
    const val WATERMARK_WIDTH = "page:watermark-width"
    const val WATERMARK_APPEARANCE = "page:watermark-appearance"

    /** 页里什么都没有时的空态（正常卡片永远有至少一块）。 */
    const val EMPTY = "page:empty"
}

/** 语义段的名字（只用于分页时「不要从中间切开」）。 */
internal object PhonePageSections {
    const val SPEC = "spec"
    const val THEME = "theme"
    const val LAYOUT = "layout"
    const val FOOTER = "footer"
    const val ANCHOR = "anchor"
    const val TEXT = "text"
    const val SLOTS = "slots"
    const val SCALE = "scale"
    const val NOTICE = "notice"
    const val ADJUST = "adjust"
    const val ACTIONS = "actions"
    const val WATERMARK = "watermark-layer"
    const val POSITION = "watermark-position"
    const val MARGINS = "watermark-margins"
    const val WIDTH = "watermark-width"
    const val APPEARANCE = "watermark-appearance"
}

/**
 * 一页工具页的完整输入。控件列表必须是**这一页自己的**（`phonePageControls`），
 * 不是整张卡的：`InspectorTabs` 已经把每个控件分到恰好一页。
 */
data class PhonePageSpec(
    val tool: EditorTool,
    val controls: List<InspectorControl> = emptyList(),
    /** 厂商规格卡（`InspectorCardModel.isIr`）：样式页先画只读规格块。 */
    val isIr: Boolean = false,
    /** 舞台渲染报告里有没有文本框（决定文字页是选择器还是声明行）。 */
    val hasTextReport: Boolean = false,
    /** 水印图层存在（`watermark != null`）；false = 空态 + 上传。 */
    val hasWatermarkLayer: Boolean = true,
)

// ---------------------------------------------------------------------------
// 尺寸：全部来自 composable 的数字（fontScale 1）
// ---------------------------------------------------------------------------

/** 页内上下留白（4 + 4）。分页后没有组头，行自己贴边。 */
const val PHONE_PAGE_BLOCK_PAD_DP = 8f

/** 同一页里两行之间的间距。 */
const val PHONE_PAGE_BLOCK_GAP_DP = 4f

/**
 * 一行控件的触控高度下限：44 dp（WCAG 2.5.5 AAA，与 `PhoneTouchTarget` 同一个数）。
 * 密度档的每一行都按它画，测试断言本文件所有行常量 ≥ 它。
 */
const val PHONE_PAGE_ROW_DP = 44f

/** 同上，给测试读的名字：触控地板。 */
const val PHONE_PAGE_MIN_TOUCH_DP = PHONE_PAGE_ROW_DP

/** 底色色板一行：38 圆 + 5 + 16 标签（一行六列，换行时行间 +6）。 */
const val PHONE_PAGE_SWATCH_ROW_DP = 59f

/** 分段行（`PhoneSegmented`，按钮就是 44 dp）。 */
const val PHONE_PAGE_SEGMENT_DP = PHONE_PAGE_ROW_DP

/** 密度档滑杆一行（标签列 + 40 dp 轨道 + 数值）。 */
const val PHONE_PAGE_SLIDER_ROW_DP = PHONE_PAGE_ROW_DP

/** 两条密度档滑杆并排的一行（Logo 大小 / 水平位置，水印边距、不透明度 / 旋转）。 */
const val PHONE_PAGE_SLIDER_PAIR_ROW_DP = PHONE_PAGE_ROW_DP

/** 版式那一行的说明（`PhoneSubNote` 4 + 18）。 */
const val PHONE_PAGE_LAYOUT_NOTE_DP = 22f

/** 声明出来的自由文本行：6 + 16 标签 + 34 输入（改过时的「恢复原文」不再多占一行）。 */
const val PHONE_PAGE_TEXT_ROW_DP = 56f

/** 只读 边框规格：两行 12.5 sp（2 × 18 + 6）+ 一句说明（4 + 18）。 */
const val PHONE_PAGE_SPEC_DP = 64f

/** 文字框选择器：导航 44 + 间距 4 + 编辑卡 44。 */
const val PHONE_PAGE_TEXT_SLOTS_DP = 92f

/** Logo 三个动作：三个 44 dp 按钮并排 + 4 + 一行「当前：…」。 */
const val PHONE_PAGE_LOGO_ACTIONS_DP = 65f

/** 水印 图层卡片：48×36 缩略 + 上下 4。 */
const val PHONE_PAGE_WATERMARK_LAYER_DP = 44f

/** 水印 九宫格：3 × 40 + 2 × 3 + 上下 3（格子是既有的 40 dp，本版没有缩）。 */
const val PHONE_PAGE_WATERMARK_GRID_DP = 132f

/** 水印 边距 / 宽度 / 外观：一行（两条并排，或一条 + 数值）。 */
const val PHONE_PAGE_WATERMARK_ROW_DP = PHONE_PAGE_ROW_DP

/** 厂商 水印位置 的说明行：44 的一行（标签 + 当前值 + 按规格 chip）+ 一句（4 + 18）。 */
const val PHONE_PAGE_ANCHOR_FOOTER_DP = PHONE_PAGE_ROW_DP + 22f

/** 空态说明块（Logo 说明 / 文字空态 / 水印空态），带字号的固定 + 行高部分。 */
private const val EMPTY_PANE_FIXED_DP = 38f
private const val EMPTY_PANE_LINE_DP = 51f
private const val TEXT_EMPTY_FIXED_DP = 54f
private const val TEXT_EMPTY_LINE_DP = 40f
private const val WATERMARK_EMPTY_FIXED_DP = 78f
private const val WATERMARK_EMPTY_LINE_DP = 51f

/**
 * 一组的高度上限的**最紧一档**：360×800 + 照片条打开。
 *
 * 它现在只是 `pageGroups` / `fitsWithoutScroll` 在没有窗口时的默认值（帧外测试、
 * 纯函数签名），真实窗口用的是 [pageBudgetForLevel]。由
 * `PhonePageLayoutTest.theTightestBudgetIsTheTightestCarriedWindow` 重新推导：
 * 改 chrome 阶梯或舞台地板而忘了改这里，测试先报错。
 */
const val PHONE_PAGE_GROUP_BUDGET_DP = 150f

/**
 * 底板比「内容 + chrome + 公共区」多开的高度：内容贴顶，底部留一点呼吸。
 * 也是 [defaultSheetLevelFor] 固定点里唯一一个自由量。
 */
const val PHONE_PAGE_LEVEL_SLACK_DP = 6f

// 参数页 chrome 阶梯（grabber + 标题行），按底板高度取。
private const val CHROME_LARGE_MIN_DP = 300f
private const val CHROME_MID_MIN_DP = 250f
private const val CHROME_LOW_MIN_DP = 200f
private const val CHROME_LARGE_GRABBER_DP = 22f
private const val CHROME_MID_GRABBER_DP = 18f
private const val CHROME_LOW_GRABBER_DP = 12f
private const val CHROME_FLOOR_GRABBER_DP = 8f
private const val CHROME_LARGE_HEAD_DP = 30f
private const val CHROME_MID_HEAD_DP = 28f
private const val CHROME_LOW_HEAD_DP = 26f
private const val CHROME_FLOOR_HEAD_DP = 24f

/** 「标题行 = 一行 16 sp 标题」的最小可读高度，再乘字号缩放。 */
private const val SHEET_HEAD_LINE_DP = 24f

// 密度档行里仍然随字号长的那几行（其余行是触控高度约束的，44 不随字号变）。
private const val SWATCH_DISC_AND_GAP_DP = 43f // 38 圆 + 5 间距
private const val SWATCH_LABEL_LINE_DP = 16f
private const val TEXT_ROW_LABEL_LINE_DP = 16f
private const val ANCHOR_NOTE_LINE_DP = 18f

/**
 * 一页里某一行的高度（不含页留白与行距）。[control] 非 null 时用控件形态；
 * 否则按键查表（合成行）。
 */
fun phoneBlockHeightDp(key: String, control: InspectorControl?, fontScale: Float = 1f): Float {
    val scale = fontScale.coerceIn(1f, 1.5f)
    if (control != null) return phoneControlBlockHeightDp(control, scale)
    return when (key) {
        PhonePageKeys.TEXT_SLOTS -> PHONE_PAGE_TEXT_SLOTS_DP
        PhonePageKeys.SPEC -> PHONE_PAGE_SPEC_DP
        PhonePageKeys.LOGO_NOTICE -> EMPTY_PANE_FIXED_DP + EMPTY_PANE_LINE_DP * scale
        PhonePageKeys.ANCHOR_GRID -> PHONE_PAGE_WATERMARK_GRID_DP
        PhonePageKeys.ANCHOR_FOOTER -> PHONE_PAGE_ROW_DP + ANCHOR_NOTE_LINE_DP * scale
        PhonePageKeys.WATERMARK_EMPTY -> WATERMARK_EMPTY_FIXED_DP + WATERMARK_EMPTY_LINE_DP * scale
        PhonePageKeys.WATERMARK_LAYER -> PHONE_PAGE_WATERMARK_LAYER_DP
        PhonePageKeys.WATERMARK_POSITION -> PHONE_PAGE_WATERMARK_GRID_DP
        PhonePageKeys.WATERMARK_MARGINS -> PHONE_PAGE_WATERMARK_ROW_DP
        PhonePageKeys.WATERMARK_WIDTH -> PHONE_PAGE_WATERMARK_ROW_DP
        PhonePageKeys.WATERMARK_APPEARANCE -> PHONE_PAGE_WATERMARK_ROW_DP
        PhonePageKeys.EMPTY -> TEXT_EMPTY_FIXED_DP + TEXT_EMPTY_LINE_DP * scale
        else -> 0f
    }
}

/**
 * `PhoneSwatchRow` 的高度：六列一行（38 圆 + 5 + 16 标签），换行时行间 6。
 * 选项多于六个时是两行 —— 主题色板按**真实选项数**算，不能写死一行。
 */
fun swatchBlockHeightDp(optionCount: Int, fontScale: Float = 1f): Float {
    val scale = fontScale.coerceIn(1f, 1.5f)
    val rows = ((optionCount.coerceAtLeast(1) + 5) / 6)
    val cell = SWATCH_DISC_AND_GAP_DP + SWATCH_LABEL_LINE_DP * scale
    return rows * cell + (rows - 1) * 6f
}

/**
 * 手机页把一个声明控件画成一行时的高度（密度档的四种形态）。
 *
 * 三种「一行就是触控目标」的控件（开关 / 滑杆 / 分段）恒为 [PHONE_PAGE_ROW_DP]；
 * 色板与自由文本行按内容算。**没有任何一种低于 [PHONE_PAGE_MIN_TOUCH_DP]**。
 */
fun phoneControlBlockHeightDp(control: InspectorControl, fontScale: Float = 1f): Float {
    val scale = fontScale.coerceIn(1f, 1.5f)
    return when (control.type) {
        ControlType.Switch -> PHONE_PAGE_ROW_DP
        ControlType.Slider -> PHONE_PAGE_SLIDER_ROW_DP
        ControlType.Select -> if (control.options.size <= 4) {
            PHONE_PAGE_SEGMENT_DP
        } else {
            swatchBlockHeightDp(control.options.size, scale)
        }
        ControlType.Color -> swatchBlockHeightDp(control.options.size, scale)
        ControlType.Text -> PHONE_PAGE_TEXT_ROW_DP + (TEXT_ROW_LABEL_LINE_DP * (scale - 1f))
        // Brand 行由 Logo 页自己画（三个动作那一块），样式页不画
        ControlType.Brand -> 0f
    }.coerceAtLeast(0f)
}

/**
 * Logo 页一行的高度：这一页画的是 curator 控件（`CuratedSwitchRow` /
 * `CuratedSegmented(touch = true)` / `CuratedSliderRow(compact = true)`），密度档下
 * 每一行都是 [PHONE_PAGE_ROW_DP]；两条滑杆并排也还是一行。
 *
 * 居左时那句「左侧文字会消失」的说明在手机密度档不再单占一行：画布就在底板上面，
 * 选完立刻看得见（桌面右栏保留整句）。所以这一页没有随状态变化的高度。
 */
fun logoControlHeightDp(control: InspectorControl, fontScale: Float = 1f): Float {
    fontScale.coerceIn(1f, 1.5f)
    return when (control.type) {
        ControlType.Brand -> 0f
        // 开关 / 分段 / 滑杆在密度档都是 44 dp 的一行（触控地板），标签在 44 里永远放得下。
        else -> PHONE_PAGE_ROW_DP
    }
}

// ---------------------------------------------------------------------------
// 一页 → 若干行
// ---------------------------------------------------------------------------

private fun block(
    key: String,
    section: String,
    heightDp: Float,
    keys: List<String> = listOf(key),
): PhonePageBlock = PhonePageBlock(key = key, keys = keys, heightDp = heightDp, section = section)

/**
 * 这一页要渲染的行，按屏幕顺序。[PhonePageBlock.section] 是语义段
 * （底色 / 版式 / 页脚 / 文字大小 / Logo 调整 / 水印各段），分页时只在段边界上切。
 */
internal fun pageBlocks(spec: PhonePageSpec, fontScale: Float = 1f): List<PhonePageBlock> {
    val scale = fontScale.coerceIn(1f, 1.5f)
    val controls = spec.controls
    fun controlRow(control: InspectorControl, section: String): PhonePageBlock =
        block(
            key = control.key,
            section = section,
            heightDp = phoneControlBlockHeightDp(control, scale),
        )

    return when (spec.tool) {
        EditorTool.Border -> emptyList()

        EditorTool.Params -> {
            if (phoneStylePageBody(spec.isIr, controls) == StylePageBody.SpecOnly) {
                listOf(block(PhonePageKeys.SPEC, PhonePageSections.SPEC, PHONE_PAGE_SPEC_DP))
            } else {
                val theme = themeControlOf(controls)
                val mode = footerModeControlOf(controls)
                val footerPct = footerPctControlOf(controls)
                val handled = setOfNotNull(theme?.key, mode?.key)
                buildList {
                    theme?.let {
                        add(
                            block(
                                key = it.key,
                                section = PhonePageSections.THEME,
                                heightDp = swatchBlockHeightDp(it.options.size, scale),
                            ),
                        )
                    }
                    mode?.let {
                        // PhoneSegmented 44 + PhoneSubNote（4 + 18）
                        add(
                            block(
                                key = it.key,
                                section = PhonePageSections.LAYOUT,
                                heightDp = PHONE_PAGE_SEGMENT_DP + PHONE_PAGE_LAYOUT_NOTE_DP,
                            ),
                        )
                    }
                    // 其余声明控件按声明顺序：页脚自适应（开关）通常排在底部留白（滑杆）前，
                    // 两行高度一样，但用户先看到开关是对的。
                    withoutDuplicateStockAnchors(controls.filterNot { it.key in handled })
                        .forEach { control ->
                            if (StockWatermarkControls.isAnchorParam(control.param)) {
                                // 九宫格（132）与「按规格 + 一句说明」（62）各自都远小于预算，
                                // 放到同一段里让分页器决定要不要合页；格子本身一个像素没缩。
                                add(block(PhonePageKeys.ANCHOR_GRID, PhonePageSections.ANCHOR, PHONE_PAGE_WATERMARK_GRID_DP))
                                add(block(PhonePageKeys.ANCHOR_FOOTER, PhonePageSections.ANCHOR, PHONE_PAGE_ANCHOR_FOOTER_DP))
                            } else if (control.key == footerPct?.key || control.param.contains("footer")) {
                                add(controlRow(control, PhonePageSections.FOOTER))
                            } else {
                                add(controlRow(control, "section:${control.section}"))
                            }
                        }
                    // 厂商规格卡的只读「边框规格」排**最后**：这一页能调的是水印位置，
                    // 规格说明是补充信息。放在最前面会让最紧的窗口多出一页（68 + 140 + 70），
                    // 放到最后时九宫格与「按规格」一页、规格一页 —— 2 页，任何窗口都不再多。
                    if (spec.isIr) add(block(PhonePageKeys.SPEC, PhonePageSections.SPEC, PHONE_PAGE_SPEC_DP))
                }
            }
        }

        EditorTool.Text -> {
            val fontScaleControl = fontScaleControlOf(controls)
            val others = controls.filterNot { it.key == fontScaleControl?.key }
            buildList {
                if (spec.hasTextReport) {
                    add(block(PhonePageKeys.TEXT_SLOTS, PhonePageSections.SLOTS, PHONE_PAGE_TEXT_SLOTS_DP))
                } else {
                    others.forEach { add(controlRow(it, PhonePageSections.TEXT)) }
                }
                fontScaleControl?.let {
                    add(controlRow(it, PhonePageSections.SCALE))
                }
                if (isEmpty()) {
                    add(
                        block(
                            PhonePageKeys.EMPTY,
                            PhonePageSections.TEXT,
                            TEXT_EMPTY_FIXED_DP + TEXT_EMPTY_LINE_DP * scale,
                        ),
                    )
                }
            }
        }

        EditorTool.Logo -> {
            val brand = controls.firstOrNull { it.type == ControlType.Brand }
            val adjustments = controls.filterNot { it.type == ControlType.Brand }
            buildList {
                if (brand == null) {
                    add(
                        block(
                            PhonePageKeys.LOGO_NOTICE,
                            PhonePageSections.NOTICE,
                            EMPTY_PANE_FIXED_DP + EMPTY_PANE_LINE_DP * scale,
                        ),
                    )
                }
                logoAdjustmentRows(adjustments).forEach { row ->
                    add(
                        block(
                            key = row.first().key,
                            section = PhonePageSections.ADJUST,
                            heightDp = if (row.size > 1) {
                                PHONE_PAGE_SLIDER_PAIR_ROW_DP
                            } else {
                                logoControlHeightDp(row.first(), scale)
                            },
                            keys = row.map { it.key },
                        ),
                    )
                }
                if (brand != null) {
                    add(
                        block(
                            key = brand.key,
                            section = PhonePageSections.ACTIONS,
                            heightDp = PHONE_PAGE_LOGO_ACTIONS_DP,
                        ),
                    )
                }
                if (isEmpty()) {
                    add(
                        block(
                            PhonePageKeys.LOGO_NOTICE,
                            PhonePageSections.NOTICE,
                            EMPTY_PANE_FIXED_DP + EMPTY_PANE_LINE_DP * scale,
                        ),
                    )
                }
            }
        }

        EditorTool.Watermark -> if (!spec.hasWatermarkLayer) {
            listOf(
                block(
                    PhonePageKeys.WATERMARK_EMPTY,
                    PhonePageSections.WATERMARK,
                    WATERMARK_EMPTY_FIXED_DP + WATERMARK_EMPTY_LINE_DP * scale,
                ),
            )
        } else {
            listOf(
                block(PhonePageKeys.WATERMARK_LAYER, PhonePageSections.WATERMARK, PHONE_PAGE_WATERMARK_LAYER_DP),
                block(PhonePageKeys.WATERMARK_POSITION, PhonePageSections.POSITION, PHONE_PAGE_WATERMARK_GRID_DP),
                block(PhonePageKeys.WATERMARK_MARGINS, PhonePageSections.MARGINS, PHONE_PAGE_WATERMARK_ROW_DP),
                block(PhonePageKeys.WATERMARK_WIDTH, PhonePageSections.WIDTH, PHONE_PAGE_WATERMARK_ROW_DP),
                block(PhonePageKeys.WATERMARK_APPEARANCE, PhonePageSections.APPEARANCE, PHONE_PAGE_WATERMARK_ROW_DP),
            )
        }
    }
}

/** 一页的高度：上下留白 + 行高之和 + 行间距。 */
internal fun groupHeightDp(blocks: List<PhonePageBlock>): Float {
    if (blocks.isEmpty()) return 0f
    val content = blocks.fold(0f) { sum, block -> sum + block.heightDp }
    return PHONE_PAGE_BLOCK_PAD_DP + content + PHONE_PAGE_BLOCK_GAP_DP * (blocks.size - 1)
}

/**
 * 把行切成**连续**的页：页数最少 → 切开语义段的次数最少 → 最高的一页最矮 → 最均衡。
 *
 * 一行自己就超过 [budgetDp] 时它独占一页（调用方与测试会看到这一页超高，而不是
 * 悄悄给它一个滚动条）。[phonePageBudget] 之外没有别的上限。
 */
internal fun partitionPages(blocks: List<PhonePageBlock>, budgetDp: Float): List<List<PhonePageBlock>> {
    if (blocks.isEmpty()) return listOf(emptyList())
    val budget = budgetDp.takeIf { it.isFinite() && it > 0f } ?: 0f
    val n = blocks.size
    if (n == 1) return listOf(blocks)
    val prefix = FloatArray(n + 1)
    blocks.forEachIndexed { index, block -> prefix[index + 1] = prefix[index] + block.heightDp }
    fun heightOf(start: Int, endExclusive: Int): Float {
        val count = endExclusive - start
        return PHONE_PAGE_BLOCK_PAD_DP + (prefix[endExclusive] - prefix[start]) +
            PHONE_PAGE_BLOCK_GAP_DP * (count - 1)
    }

    data class Candidate(
        val cuts: IntArray,
        val pages: Int,
        val sectionSplits: Int,
        val tallest: Float,
        val imbalance: Float,
    )

    var best: Candidate? = null
    val totalMasks = 1 shl (n - 1)
    for (mask in 0 until totalMasks) {
        val bounds = ArrayList<Int>(n + 1)
        bounds += 0
        for (bit in 0 until (n - 1)) {
            if (mask and (1 shl bit) != 0) bounds += bit + 1
        }
        bounds += n
        var feasible = true
        var tallest = 0f
        var sectionSplits = 0
        var sum = 0f
        for (pageIndex in 0 until bounds.size - 1) {
            val start = bounds[pageIndex]
            val end = bounds[pageIndex + 1]
            val height = heightOf(start, end)
            val single = end - start == 1
            if (!single && height > budget + 0.01f) {
                feasible = false
                break
            }
            if (pageIndex > 0 && blocks[start - 1].section == blocks[start].section) sectionSplits++
            tallest = max(tallest, height)
            sum += height
        }
        if (!feasible) continue
        val pages = bounds.size - 1
        val mean = sum / pages
        var imbalance = 0f
        for (pageIndex in 0 until pages) {
            val height = heightOf(bounds[pageIndex], bounds[pageIndex + 1])
            imbalance += (height - mean) * (height - mean)
        }
        val candidate = Candidate(bounds.toIntArray(), pages, sectionSplits, tallest, imbalance)
        val current = best
        val better = current == null ||
            candidate.pages < current.pages ||
            (candidate.pages == current.pages && candidate.sectionSplits < current.sectionSplits) ||
            (
                candidate.pages == current.pages &&
                    candidate.sectionSplits == current.sectionSplits &&
                    candidate.tallest < current.tallest - 0.01f
                ) ||
            (
                candidate.pages == current.pages &&
                    candidate.sectionSplits == current.sectionSplits &&
                    abs(candidate.tallest - current.tallest) <= 0.01f &&
                    candidate.imbalance < current.imbalance - 0.01f
                )
        if (better) best = candidate
    }
    val chosen = best ?: return blocks.map { listOf(it) }
    val pages = ArrayList<List<PhonePageBlock>>(chosen.pages)
    for (index in 0 until chosen.pages) {
        pages += blocks.subList(chosen.cuts[index], chosen.cuts[index + 1]).toList()
    }
    return pages
}

/** 切好的页（完整行信息：键、高度、段）。 */
internal fun partitionSpec(
    spec: PhonePageSpec,
    fontScale: Float = 1f,
    budgetDp: Float = PHONE_PAGE_GROUP_BUDGET_DP,
): List<List<PhonePageBlock>> = partitionPages(pageBlocks(spec, fontScale), budgetDp)

/**
 * 这一页怎么分组成可横滑的页：页的列表，每页是这一页要画的**键**（按屏幕顺序）。
 *
 * [budgetDp] 是「这个窗口允许一页吃到的净高」——默认是最紧一档 carried 窗口
 * （[PHONE_PAGE_GROUP_BUDGET_DP]），真实窗口传 [pageBudgetForLevel]。
 */
fun pageGroups(
    spec: PhonePageSpec,
    fontScale: Float = 1f,
    budgetDp: Float = PHONE_PAGE_GROUP_BUDGET_DP,
): List<List<String>> = partitionSpec(spec, fontScale, budgetDp).map { page ->
    page.flatMap { it.keys }
}

/** 每页的实际高度（与 [pageGroups] 同一份行表，所以两者不可能对不上）。 */
fun pageGroupHeights(
    spec: PhonePageSpec,
    fontScale: Float = 1f,
    budgetDp: Float = PHONE_PAGE_GROUP_BUDGET_DP,
): List<Float> = partitionSpec(spec, fontScale, budgetDp).map { groupHeightDp(it) }

/** 任务书签名：`pageGroups(tool, controls)`。 */
fun pageGroups(
    tool: EditorTool,
    controls: List<InspectorControl>,
    isIr: Boolean = false,
    hasTextReport: Boolean = false,
    hasWatermarkLayer: Boolean = true,
    fontScale: Float = 1f,
    budgetDp: Float = PHONE_PAGE_GROUP_BUDGET_DP,
): List<List<String>> = pageGroups(
    PhonePageSpec(tool, controls, isIr, hasTextReport, hasWatermarkLayer),
    fontScale,
    budgetDp,
)

/** 任务书签名：`pageGroupHeights(tool, controls)`。 */
fun pageGroupHeights(
    tool: EditorTool,
    controls: List<InspectorControl>,
    isIr: Boolean = false,
    hasTextReport: Boolean = false,
    hasWatermarkLayer: Boolean = true,
    fontScale: Float = 1f,
    budgetDp: Float = PHONE_PAGE_GROUP_BUDGET_DP,
): List<Float> = pageGroupHeights(
    PhonePageSpec(tool, controls, isIr, hasTextReport, hasWatermarkLayer),
    fontScale,
    budgetDp,
)

// ---------------------------------------------------------------------------
// 参数页的 chrome（把手行 + 标题行）
// ---------------------------------------------------------------------------

/** 底板顶部两行的高度：拖拽把手 + 标题行。 */
data class PhoneSheetChrome(val grabberDp: Float, val headDp: Float) {
    val totalDp: Float get() = grabberDp + headDp
}

private data class PhoneSheetChromeTier(val minLevelDp: Float, val grabberDp: Float, val headDp: Float)

/**
 * 参数页的 chrome 阶梯。**按底板高度选**，不按内容选：底板越矮，把手与标题行越紧
 * （和边框页的 `compactTiers` 同一手法），但标题行永远留得下一行 16 sp 字的高度
 * （[SHEET_HEAD_LINE_DP] × fontScale）。
 *
 * 为什么参数页不直接用 `phoneCompactSheetMetrics`：那张阶梯是按「128 dp 卡片 + 名称 +
 * 说明 + 类别行 + 分组行」算的，参数页只画把手和标题，用它会凭空多要 100+ dp 的组高，
 * 正是「开太高、内容还是放不下」的一半原因。
 */
private val ParameterChromeTiers = listOf(
    PhoneSheetChromeTier(CHROME_LARGE_MIN_DP, CHROME_LARGE_GRABBER_DP, CHROME_LARGE_HEAD_DP),
    PhoneSheetChromeTier(CHROME_MID_MIN_DP, CHROME_MID_GRABBER_DP, CHROME_MID_HEAD_DP),
    PhoneSheetChromeTier(CHROME_LOW_MIN_DP, CHROME_LOW_GRABBER_DP, CHROME_LOW_HEAD_DP),
    PhoneSheetChromeTier(0f, CHROME_FLOOR_GRABBER_DP, CHROME_FLOOR_HEAD_DP),
)

/** 参数页在 [levelDp] 这个底板高度下的把手行与标题行。 */
fun phoneParameterSheetChrome(levelDp: Float, fontScale: Float = 1f): PhoneSheetChrome {
    val level = if (levelDp.isFinite()) levelDp.coerceAtLeast(0f) else 0f
    val tier = ParameterChromeTiers.firstOrNull { level >= it.minLevelDp } ?: ParameterChromeTiers.last()
    val scale = fontScale.coerceIn(1f, 1.5f)
    return PhoneSheetChrome(
        grabberDp = tier.grabberDp,
        headDp = max(tier.headDp, SHEET_HEAD_LINE_DP * scale),
    )
}

/** 任意工具在 [levelDp] 下的把手行 + 标题行（边框页读卡片阶梯，其余读参数阶梯）。 */
fun phoneSheetChrome(levelDp: Float, fontScale: Float, tool: EditorTool): PhoneSheetChrome =
    if (tool == EditorTool.Border) {
        val metrics = phoneCompactSheetMetrics(levelDp, fontScale)
        PhoneSheetChrome(grabberDp = metrics.grabber, headDp = metrics.head)
    } else {
        phoneParameterSheetChrome(levelDp, fontScale)
    }

// ---------------------------------------------------------------------------
// 一页能吃掉多少：舞台地板给到的整块底板
// ---------------------------------------------------------------------------

/**
 * [sheetLevelDp] 这个底板高度里，一页可以吃到的净高：**整块底板**减去参数页 chrome
 * 与公共区。这正是 owner 说的「一页可以占满底板许可」——分页只在内容仍然超过它时发生。
 *
 * 与 `PHONE_PAGE_GROUP_BUDGET_DP`（最紧一档 carried 窗口的值 = 150 dp）是同一个公式：
 * 360×800 + 照片条 → `222 - 38 - 34 = 150`。
 */
fun pageBudgetForLevel(sheetLevelDp: Float, fontScale: Float = 1f): Float {
    if (!sheetLevelDp.isFinite() || sheetLevelDp <= 0f) return 0f
    val chrome = phoneParameterSheetChrome(sheetLevelDp, fontScale)
    val pageHeight = sheetLevelDp - chrome.totalDp
    if (pageHeight <= 0f) return 0f
    val band = if (showsPhoneParameterSummary(pageHeight)) PHONE_PARAMETER_SUMMARY_DP else 0f
    return (pageHeight - band).coerceAtLeast(0f)
}

/**
 * 窗口 [availableHeightDp] 在舞台 50 % 地板之下允许的**最高**底板，再换算成一页的净高
 * （= 这个窗口的预算上限）。`defaultSheetLevelFor` 用它决定「装得下就一页」。
 */
fun pageAllowanceForWindow(
    availableHeightDp: Float,
    photosOpen: Boolean,
    fontScale: Float = 1f,
): Float {
    val available = if (availableHeightDp.isFinite()) availableHeightDp.coerceAtLeast(0f) else 0f
    val strip = if (photosOpen) STRIP_DP else 0f
    val compactMax = available - TOP_BAR_DP - TOOL_BAR_DP - strip - available * STAGE_MIN_FRACTION
    if (compactMax <= 0f) return 0f
    return pageBudgetForLevel(compactMax, fontScale)
}

// ---------------------------------------------------------------------------
// 装得下吗 / 默认开多高
// ---------------------------------------------------------------------------

/**
 * 这一页在 [sheetLevelDp] 这个底板高度下是否**完全不用滚**：最高的一页要落在
 * [pageBudgetForLevel] 里。
 *
 * 公共区只在页面高度够 [showsPhoneParameterSummary] 时出现；[pageBudgetForLevel] 与这里
 * 读同一个判据，所以两种分支都不会漏。
 */
fun fitsWithoutScroll(spec: PhonePageSpec, sheetLevelDp: Float, fontScale: Float = 1f): Boolean {
    if (spec.tool == EditorTool.Border) return false
    val budget = pageBudgetForLevel(sheetLevelDp, fontScale)
    if (budget <= 0f) return false
    val tallest = pageGroupHeights(spec, fontScale, budget).maxOrNull() ?: 0f
    return budget >= tallest - 0.01f
}

/** 任务书签名：`fitsWithoutScroll(tool, controls, level)`。 */
fun fitsWithoutScroll(
    tool: EditorTool,
    controls: List<InspectorControl>,
    sheetLevelDp: Float,
    isIr: Boolean = false,
    hasTextReport: Boolean = false,
    hasWatermarkLayer: Boolean = true,
    fontScale: Float = 1f,
): Boolean = fitsWithoutScroll(
    PhonePageSpec(tool, controls, isIr, hasTextReport, hasWatermarkLayer),
    sheetLevelDp,
    fontScale,
)

/**
 * 这一页在 [availableHeightDp] 窗口里应该开多高（收起档）。
 *
 * 规则：高度 = 参数页 chrome + 公共区 + **最高的一页** + [PHONE_PAGE_LEVEL_SLACK_DP]，
 * 再夹到 [`MIN_SHEET_DP`, 舞台 50 % 地板定出的上限]。分页用的是**这个窗口的预算**
 * （[pageAllowanceForWindow]）——它能吃满地板，所以「加密」直接换来了更少的页数。
 *
 * 阶梯的选法：从**最紧的一档**开始，取第一个「按它自己的 chrome 算出来的高度既够得着
 * 这一档、又真的放得下」的候选。第一版是从最松的一档开始找（会为了坐满 300 dp 而多开
 * 90 dp 的空白），现在是**够用就好**。
 *
 * 返回 `null` 表示「这个窗口里连最紧的一档也放不下这一页」——调用方照旧用整页参数页
 * （`useFullPhoneParameterPage` 那条约束窗口回退），绝不偷偷塞一个滚动容器。
 */
fun defaultSheetLevelFor(
    spec: PhonePageSpec,
    availableHeightDp: Float,
    photosOpen: Boolean,
    fontScale: Float = 1f,
): Float? {
    val available = if (availableHeightDp.isFinite()) availableHeightDp.coerceAtLeast(0f) else 0f
    val strip = if (photosOpen) STRIP_DP else 0f
    val chrome = TOP_BAR_DP + TOOL_BAR_DP + strip
    val compactMax = available - chrome - available * STAGE_MIN_FRACTION
    if (compactMax < MIN_SHEET_DP) return null
    if (spec.tool == EditorTool.Border) {
        // 边框页保持原样：128 dp 卡片那一摞就是它的内容高（`phoneCompactSheetMetrics`）。
        return phoneCompactSheetMetrics(compactMax, fontScale).contentHeight.coerceAtMost(compactMax)
    }
    val budget = pageAllowanceForWindow(available, photosOpen, fontScale)
    val content = pageGroupHeights(spec, fontScale, budget).maxOrNull() ?: 0f
    val scale = fontScale.coerceIn(1f, 1.5f)
    fun needed(tier: PhoneSheetChromeTier): Float {
        val head = max(tier.headDp, SHEET_HEAD_LINE_DP * scale)
        return tier.grabberDp + head + PHONE_PARAMETER_SUMMARY_DP + content + PHONE_PAGE_LEVEL_SLACK_DP
    }
    // 候选 = 每一档按自己的 chrome 算出来的高度；只保留「够得着这一档」且「夹到舞台
    // 地板之后，**这同一个分组**仍然放得下」的那些，从最紧的开始取第一个。
    //
    // 判据必须是**分组不变**：如果拿候选高度再分一次组，矮预算会靠多分一页「放得下」，
    // 于是底板越算越矮、页数反而变多（owner 说的「太散了」就是这样来的）。
    val level = ParameterChromeTiers
        .reversed()
        .mapNotNull { tier ->
            val candidate = needed(tier)
            if (candidate < tier.minLevelDp) return@mapNotNull null
            val levelForCheck = candidate.coerceAtMost(compactMax)
            candidate.takeIf { pageBudgetForLevel(levelForCheck, fontScale) >= content - 0.01f }
        }
        .minOrNull()
        ?: needed(ParameterChromeTiers.last())
    val chosen = level.coerceIn(MIN_SHEET_DP, compactMax)
    return chosen.takeIf { pageBudgetForLevel(it, fontScale) >= content - 0.01f }
}

/** 任务书签名：`defaultSheetLevelFor(tool, …)`。 */
fun defaultSheetLevelFor(
    tool: EditorTool,
    controls: List<InspectorControl>,
    availableHeightDp: Float,
    photosOpen: Boolean,
    isIr: Boolean = false,
    hasTextReport: Boolean = false,
    hasWatermarkLayer: Boolean = true,
    fontScale: Float = 1f,
): Float? = defaultSheetLevelFor(
    PhonePageSpec(tool, controls, isIr, hasTextReport, hasWatermarkLayer),
    availableHeightDp,
    photosOpen,
    fontScale,
)
