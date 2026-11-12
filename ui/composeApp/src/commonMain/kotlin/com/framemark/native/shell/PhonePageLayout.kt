package com.framemark.native.shell

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.StylePageBody
import com.framemark.native.inspector.fontScaleControlOf
import com.framemark.native.inspector.footerModeControlOf
import com.framemark.native.inspector.footerPctControlOf
import com.framemark.native.inspector.phoneStylePageBody
import com.framemark.native.inspector.themeControlOf
import com.framemark.native.inspector.withoutDuplicateStockAnchors
import com.framemark.native.product.StockWatermarkControls
import kotlin.math.max

/**
 * 工具页（样式 / 文字 / Logo / 水印）的版式政策：一页分几组、每组多高、默认底板开多高。
 *
 * owner 的要求（2026-11）：「所有页还是有排版问题，最好不要让用户去滑动屏幕再调整」。
 * 这一层把它表达成三条可测的规则：
 *
 *  1. **参数不滚**：工具页里没有竖向 scroll。一组放不下就分页（[pageGroups]），
 *     每组自己必须放得下（[fitsWithoutScroll]）。
 *  2. **底板高度由内容决定**（[defaultSheetLevelFor]）：两组滑杆的页开得矮（画布多），
 *     六项控件的页可以开高；下限还是 `MIN_SHEET_DP`，上限还是舞台 50 % 地板
 *     （`STAGE_MIN_FRACTION`），都不新造。
 *  3. **只分组、不缩控件**：块高全部来自 composable 自己的数字（[phoneBlockHeightDp]），
 *     没有任何一个控件被压到 44 dp 触控目标以下来「挤进去」；九宫格这类既不能缩也
 *     不能整块放下的控件，拆成两块两页（见 [PhonePageKeys.ANCHOR_GRID]）。
 *
 * ## 一组有多高
 *
 * 一组 = 上下留白（[PHONE_PAGE_BLOCK_PAD_DP]）+ 块高 + 块间距
 * （[PHONE_PAGE_BLOCK_GAP_DP]）。块的数字直接从 composable 读（fontScale 1）：
 *
 * | 块 | 依据 | dp |
 * |----|------|----|
 * | 底色色板 | `PhoneSwatchRow`：38 圆 + 5 + 16 标签 + 8 内边距 | 67 |
 * | 版式 | `PhoneSegmented` 3+36+3 + `PhoneSubNote` 4+18 | 64 |
 * | 滑杆 | `PhoneSliderRow`：8 + 32 + 40 + 4 | 84 |
 * | 开关 | `PhoneSwitchRow` `heightIn(min = 52.dp)` | 52 |
 * | 分段（≤4 选项） | `PhoneSegmented` 3+36+3 | 42 |
 * | 文本行 | `PhoneTextValueRow`：6 + 16 + 34 | 56 |
 * | 边框规格 | `PhoneSpecRows` 2 × 18 + 6 + `PhoneSubNote` 22 | 64 |
 * | 文字框选择器 | 导航 44 + 10 + 编辑卡 72 | 126 |
 * | 文字大小 | 分页后没有组头，就是一条 `PhoneSliderRow` | 84 |
 * | Logo 开关 / 分段 / 滑杆 | `CuratedSwitchRow` 36 / 分段 59（带说明 82）/ `CuratedSliderRow` 68 | 36/59/68 |
 * | Logo 三个动作 | 主按钮 48 + 8 + 48 + 2 + 说明行 17 | 123 |
 * | 水印 图层 | `CuratedGroup` 内一行 36 + 上下 8 + 8 | 52 |
 * | 水印 九宫格 | `AnchorCellsGrid` 3 × 40 + 2 × 3 间隙 + 6 内边距 | 132 |
 * | 水印 边距 | 2 × `FmTouchSliderRow`（19 标签 + 40 行） | 118 |
 * | 水印 滑杆 | `FmTouchSliderRow` | 59 |
 *
 * [PHONE_PAGE_GROUP_BUDGET_DP] 是这套分组的**规则上限**：最紧的一档 carried 窗口
 * （360×800 + 照片条打开 = 222 dp 底板）减去参数页 chrome（38）与公共区（34）之后
 * 剩下的 150 dp。`PhonePageLayoutTest` 用同一串数字重新推导它：改 chrome 阶梯或舞台
 * 地板而忘了改这里，测试会先报错。
 */

// ---------------------------------------------------------------------------
// 分页块：一个工具页里「不能再拆的最小可视单元」
// ---------------------------------------------------------------------------

/**
 * 一页里最小的一块。普通控件用 `InspectorControl.key`；没有控件的块（厂商规格、
 * 水印的各段、Logo 的说明/动作）用 [PhonePageKeys] 里的合成键。
 *
 * [group] 是**合页标签**：只有同一标签的相邻块才允许放进同一页。参数页的语义分组
 * （底色 / 版式 / 同一个 section 的其余控件 / Logo 的调整 / 水印的某一段）因此
 * 不会被拆到两页，也不会把「底色」和「版式」硬塞进一页。
 */
internal data class PhonePageBlock(
    val key: String,
    val heightDp: Float,
    val group: String,
)

/** 没有 `InspectorControl` 的块用这些稳定键（分页器与页面按它们取块）。 */
object PhonePageKeys {
    /** 文字页：渲染器报告的文本框选择器（一整块）。 */
    const val TEXT_SLOTS = "page:text-slots"

    /** 样式页：只读 边框规格 块。 */
    const val SPEC = "page:border-spec"

    /** Logo 页：没有可替换字标时的说明块。 */
    const val LOGO_NOTICE = "page:logo-notice"

    /** 样式页：厂商 `anchorGrid` 控件拆成九宫格 + 说明/按规格 两块两页。 */
    const val ANCHOR_GRID = "page:stock-anchor-grid"
    const val ANCHOR_FOOTER = "page:stock-anchor-footer"

    /** 水印页：一段一块（`WatermarkPanePart` 的键）。 */
    const val WATERMARK_EMPTY = "page:watermark-empty"
    const val WATERMARK_LAYER = "page:watermark-layer"
    const val WATERMARK_POSITION = "page:watermark-position"
    const val WATERMARK_MARGINS = "page:watermark-margins"
    const val WATERMARK_WIDTH = "page:watermark-width"
    const val WATERMARK_OPACITY = "page:watermark-opacity"
    const val WATERMARK_ROTATION = "page:watermark-rotation"

    /** 页里什么都没有时的空态（正常卡片永远有至少一块）。 */
    const val EMPTY = "page:empty"
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

/** 组内上下留白（4 + 4）。分页后没有组头，块自己贴边。 */
const val PHONE_PAGE_BLOCK_PAD_DP = 8f

/** 同一页里两个块之间的间距。 */
const val PHONE_PAGE_BLOCK_GAP_DP = 4f

/**
 * 一组的高度上限：最紧的 carried 状态（360×800 + 照片条打开）留给一页的净高。
 * 由 `PhonePageLayoutTest.theGroupBudgetIsTheTightestCarriedViewport` 重新推导。
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

// 块高（fontScale 1；带文字的行在 phoneBlockHeightDp 里按字号缩放）。
private const val SWATCH_FIXED_DP = 51f
private const val SWATCH_LABEL_DP = 16f
private const val MODE_FIXED_DP = 46f
private const val MODE_NOTE_DP = 18f
private const val SLIDER_FIXED_DP = 12f
private const val SLIDER_VALUE_DP = 32f
private const val SLIDER_LABEL_DP = 20f
private const val SLIDER_TRACK_DP = 40f
private const val SWITCH_ROW_DP = 52f
private const val SWITCH_LABEL_DP = 20f
private const val SEGMENTED_ROW_DP = 42f
private const val TEXT_ROW_FIXED_DP = 64f
private const val TEXT_ROW_LINE_DP = 16f
private const val SPEC_ROWS_DP = 42f
private const val SPEC_NOTE_FIXED_DP = 4f
private const val SPEC_NOTE_LINE_DP = 18f
private const val TEXT_SLOT_FIXED_DP = 110f
private const val TEXT_SLOT_LABEL_DP = 16f
private const val TEXT_EMPTY_FIXED_DP = 54f
private const val TEXT_EMPTY_LINE_DP = 40f
private const val LOGO_SWITCH_DP = 36f
private const val LOGO_SWITCH_LINE_DP = 18f
private const val LOGO_SELECT_FIXED_DP = 42f
private const val LOGO_SELECT_LABEL_DP = 17f
private const val LOGO_SELECT_NOTE_FIXED_DP = 6f
private const val LOGO_SELECT_NOTE_LINE_DP = 17f
private const val LOGO_SLIDER_ROW_DP = 68f
private const val LOGO_ACTION_FIXED_DP = 108f
private const val LOGO_CAPTION_DP = 17f
private const val LOGO_NOTICE_FIXED_DP = 38f
private const val LOGO_NOTICE_LINE_DP = 51f
private const val WATERMARK_LAYER_FIXED_DP = 16f
private const val WATERMARK_LAYER_LINE_DP = 33f
private const val WATERMARK_GRID_DP = 132f
private const val WATERMARK_SLIDER_FIXED_DP = 40f
private const val WATERMARK_SLIDER_LINE_DP = 19f
private const val WATERMARK_HINT_DP = 14f
private const val WATERMARK_SUB_NOTE_FIXED_DP = 2f
private const val WATERMARK_SUB_NOTE_LINE_DP = 17f
private const val WATERMARK_EMPTY_FIXED_DP = 78f
private const val WATERMARK_EMPTY_LINE_DP = 51f
private const val ANCHOR_FOOTER_FIXED_DP = 72f
private const val ANCHOR_FOOTER_LINE_DP = 36f

/**
 * 一页里某一块的裸高（不含组留白与间距）。[control] 非 null 时用控件形态；
 * 否则按键查表（合成块）。
 */
fun phoneBlockHeightDp(key: String, control: InspectorControl?, fontScale: Float = 1f): Float {
    val scale = fontScale.coerceIn(1f, 1.5f)
    if (control != null) return phoneControlBlockHeightDp(control, scale)
    return when (key) {
        PhonePageKeys.TEXT_SLOTS -> TEXT_SLOT_FIXED_DP + TEXT_SLOT_LABEL_DP * scale
        PhonePageKeys.SPEC -> SPEC_ROWS_DP + SPEC_NOTE_FIXED_DP + SPEC_NOTE_LINE_DP * scale
        PhonePageKeys.LOGO_NOTICE -> LOGO_NOTICE_FIXED_DP + LOGO_NOTICE_LINE_DP * scale
        PhonePageKeys.ANCHOR_GRID -> WATERMARK_GRID_DP
        PhonePageKeys.ANCHOR_FOOTER -> ANCHOR_FOOTER_FIXED_DP + ANCHOR_FOOTER_LINE_DP * scale
        PhonePageKeys.WATERMARK_EMPTY -> WATERMARK_EMPTY_FIXED_DP + WATERMARK_EMPTY_LINE_DP * scale
        PhonePageKeys.WATERMARK_LAYER -> WATERMARK_LAYER_FIXED_DP + WATERMARK_LAYER_LINE_DP * scale
        PhonePageKeys.WATERMARK_POSITION -> WATERMARK_GRID_DP
        PhonePageKeys.WATERMARK_MARGINS ->
            2f * (WATERMARK_SLIDER_FIXED_DP + WATERMARK_SLIDER_LINE_DP * scale)
        PhonePageKeys.WATERMARK_WIDTH ->
            WATERMARK_SLIDER_FIXED_DP + WATERMARK_SLIDER_LINE_DP * scale + WATERMARK_HINT_DP * scale
        PhonePageKeys.WATERMARK_OPACITY -> WATERMARK_SLIDER_FIXED_DP + WATERMARK_SLIDER_LINE_DP * scale
        PhonePageKeys.WATERMARK_ROTATION ->
            WATERMARK_SLIDER_FIXED_DP + WATERMARK_SLIDER_LINE_DP * scale +
                WATERMARK_SUB_NOTE_FIXED_DP + WATERMARK_SUB_NOTE_LINE_DP * scale
        PhonePageKeys.EMPTY -> TEXT_EMPTY_FIXED_DP + TEXT_EMPTY_LINE_DP * scale
        else -> 0f
    }
}

/**
 * `PhoneSwatchRow` 的高度：六列一行（38 圆 + 5 + 16 标签 + 上下 4），行间 6。
 * 选项多于六个时是两行 —— 主题色板按**真实选项数**算，不能写死一行。
 */
fun swatchBlockHeightDp(optionCount: Int, fontScale: Float = 1f): Float {
    val scale = fontScale.coerceIn(1f, 1.5f)
    val rows = ((optionCount.coerceAtLeast(1) + 5) / 6)
    val cell = SWATCH_FIXED_DP + SWATCH_LABEL_DP * scale
    return rows * cell + (rows - 1) * 6f
}

/** 手机页把一个声明控件画成一行时的高度（`PhoneControlRow` 的四种形态）。 */
fun phoneControlBlockHeightDp(control: InspectorControl, fontScale: Float = 1f): Float {
    val scale = fontScale.coerceIn(1f, 1.5f)
    return when (control.type) {
        // PhoneSwitchRow: heightIn(min = 52.dp)
        ControlType.Switch -> max(SWITCH_ROW_DP, SWITCH_LABEL_DP * scale)
        // PhoneSliderRow: padding 8 / 4 + 32 dp value row + 40 dp touch track
        ControlType.Slider ->
            SLIDER_FIXED_DP + max(SLIDER_VALUE_DP, SLIDER_LABEL_DP * scale) + SLIDER_TRACK_DP
        // PhoneSegmented (<= 4 options) or PhoneSwatchRow (> 4)
        ControlType.Select -> if (control.options.size <= 4) {
            SEGMENTED_ROW_DP
        } else {
            swatchBlockHeightDp(control.options.size, scale)
        }
        // PhoneSwatchRow
        ControlType.Color -> swatchBlockHeightDp(control.options.size, scale)
        // PhoneTextValueRow: 6 top + 16 label + 34 input，值被改过时下面还有一行
        // 「恢复原文」（16 + 8），按最坏情况进预算，改过的行不会把同页挤出去。
        ControlType.Text -> TEXT_ROW_FIXED_DP + TEXT_ROW_LINE_DP * scale
        // Brand 行由 Logo 页自己画（三个动作那一块），样式页不画
        ControlType.Brand -> 0f
    }
}

/**
 * Logo 页的行高：这一页画的是 curator 控件（`CuratedSwitchRow` / `CuratedSegmented` /
 * `CuratedSliderRow`），不是 `PhoneControlRow`，所以不能用 [phoneControlBlockHeightDp]。
 *
 * `logoPos` 在「居左」时会多一行后果说明（+6 + 17），这里按最坏情况算，值改成居中只
 * 会让这一页变矮 —— 永远不会因为一行说明冒出来而溢出。
 */
fun logoControlHeightDp(control: InspectorControl, fontScale: Float = 1f): Float {
    val scale = fontScale.coerceIn(1f, 1.5f)
    return when (control.type) {
        ControlType.Switch -> max(LOGO_SWITCH_DP, LOGO_SWITCH_LINE_DP * scale)
        ControlType.Select -> LOGO_SELECT_FIXED_DP + LOGO_SELECT_LABEL_DP * scale +
            LOGO_SELECT_NOTE_FIXED_DP + LOGO_SELECT_NOTE_LINE_DP * scale
        ControlType.Slider -> LOGO_SLIDER_ROW_DP
        else -> phoneControlBlockHeightDp(control, scale)
    }
}

// ---------------------------------------------------------------------------
// 一页 → 若干块
// ---------------------------------------------------------------------------

/**
 * 这一页要渲染的块，按屏幕顺序。语义分组（底色 / 版式 / 文字大小 / Logo 调整 /
 * 水印各段）写在 [PhonePageBlock.group] 里，只有同组相邻块才允许合页。
 */
internal fun pageBlocks(spec: PhonePageSpec, fontScale: Float = 1f): List<PhonePageBlock> {
    val scale = fontScale.coerceIn(1f, 1.5f)
    val controls = spec.controls
    fun block(key: String, group: String, control: InspectorControl? = null, height: Float? = null) =
        PhonePageBlock(
            key = key,
            heightDp = height ?: phoneBlockHeightDp(key, control, scale),
            group = group,
        )

    return when (spec.tool) {
        EditorTool.Border -> emptyList()

        EditorTool.Params -> {
            if (phoneStylePageBody(spec.isIr, controls) == StylePageBody.SpecOnly) {
                listOf(block(PhonePageKeys.SPEC, "spec"))
            } else {
                val theme = themeControlOf(controls)
                val mode = footerModeControlOf(controls)
                val footerPct = footerPctControlOf(controls)
                val handled = setOfNotNull(theme?.key, mode?.key, footerPct?.key)
                buildList {
                    if (spec.isIr) add(block(PhonePageKeys.SPEC, "spec"))
                    theme?.let { add(block(it.key, "theme", height = swatchBlockHeightDp(it.options.size, scale))) }
                    mode?.let {
                        // PhoneSegmented 42 + PhoneSubNote（4 + 18）
                        add(block(it.key, "footer", height = MODE_FIXED_DP + MODE_NOTE_DP * scale))
                    }
                    footerPct?.let { add(block(it.key, "footer", it)) }
                    withoutDuplicateStockAnchors(controls.filterNot { it.key in handled })
                        .forEach { control ->
                            if (StockWatermarkControls.isAnchorParam(control.param)) {
                                // 九宫格 + 按规格/说明 整块 254 dp，一页放不下；格子（132）
                                // 与说明（108）各自都远小于预算，拆成两块两页，控件本身
                                // 一个像素没缩（40 dp 触控格子原样保留）。
                                add(block(PhonePageKeys.ANCHOR_GRID, "anchor:${control.key}"))
                                add(block(PhonePageKeys.ANCHOR_FOOTER, "anchor:${control.key}"))
                            } else {
                                add(block(control.key, "section:${control.section}", control))
                            }
                        }
                }
            }
        }

        EditorTool.Text -> {
            val fontScaleControl = fontScaleControlOf(controls)
            val others = controls.filterNot { it.key == fontScaleControl?.key }
            buildList {
                if (spec.hasTextReport) {
                    add(block(PhonePageKeys.TEXT_SLOTS, "slots"))
                } else {
                    others.forEach { add(block(it.key, "text", it)) }
                }
                fontScaleControl?.let { add(block(it.key, "scale", it)) }
                if (isEmpty()) add(block(PhonePageKeys.EMPTY, "empty"))
            }
        }

        EditorTool.Logo -> {
            val brand = controls.firstOrNull { it.type == ControlType.Brand }
            buildList {
                if (brand == null) add(block(PhonePageKeys.LOGO_NOTICE, "notice"))
                controls.filterNot { it.type == ControlType.Brand }.forEach {
                    add(block(it.key, "logo", height = logoControlHeightDp(it, scale)))
                }
                if (brand != null) {
                    // 三个动作 + 一行「当前：…」：主按钮 48 + 8 + 另一行 48 + 2 + 17。
                    add(
                        block(
                            key = brand.key,
                            group = "actions",
                            height = LOGO_ACTION_FIXED_DP + LOGO_CAPTION_DP * scale,
                        ),
                    )
                }
                if (isEmpty()) add(block(PhonePageKeys.LOGO_NOTICE, "notice"))
            }
        }

        EditorTool.Watermark -> if (!spec.hasWatermarkLayer) {
            listOf(block(PhonePageKeys.WATERMARK_EMPTY, "watermark"))
        } else {
            listOf(
                block(PhonePageKeys.WATERMARK_LAYER, "watermark-layer"),
                block(PhonePageKeys.WATERMARK_POSITION, "watermark-position"),
                block(PhonePageKeys.WATERMARK_MARGINS, "watermark-margins"),
                block(PhonePageKeys.WATERMARK_WIDTH, "watermark-width"),
                block(PhonePageKeys.WATERMARK_OPACITY, "watermark-opacity"),
                block(PhonePageKeys.WATERMARK_ROTATION, "watermark-rotation"),
            )
        }
    }
}

/** 一组的高度：上下留白 + 块高之和 + 块间距。 */
internal fun groupHeightDp(blocks: List<PhonePageBlock>): Float {
    if (blocks.isEmpty()) return 0f
    val content = blocks.fold(0f) { sum, block -> sum + block.heightDp }
    return PHONE_PAGE_BLOCK_PAD_DP + content + PHONE_PAGE_BLOCK_GAP_DP * (blocks.size - 1)
}

/**
 * 这一页怎么分组成可横滑的页：每页一组，每组自己放得下。
 *
 * 规则：按屏幕顺序把块塞进当前页；只有**同一 [PhonePageBlock.group]** 的块才合页，
 * 且合页后的组高不得超过 [PHONE_PAGE_GROUP_BUDGET_DP]。一个块自己就超预算时它独占
 * 一页（测试会把它抓出来，不允许悄悄留一个滚动条）。
 */
fun pageGroups(spec: PhonePageSpec, fontScale: Float = 1f): List<List<String>> {
    val pages = ArrayList<MutableList<PhonePageBlock>>()
    var currentGroup: String? = null
    pageBlocks(spec, fontScale).forEach { block ->
        val current = pages.lastOrNull()
        val canJoin = current != null &&
            currentGroup == block.group &&
            groupHeightDp(current + block) <= PHONE_PAGE_GROUP_BUDGET_DP
        if (canJoin) {
            current!!.add(block)
        } else {
            pages += mutableListOf(block)
            currentGroup = block.group
        }
    }
    return pages.map { page -> page.map { it.key } }
}

/** 每组的实际高度（与 [pageGroups] 同一份块表，所以两者不可能对不上）。 */
fun pageGroupHeights(spec: PhonePageSpec, fontScale: Float = 1f): List<Float> {
    val byKey = pageBlocks(spec, fontScale).associateBy { it.key }
    return pageGroups(spec, fontScale).map { keys ->
        groupHeightDp(keys.mapNotNull(byKey::get))
    }
}

/** 任务书签名：`pageGroups(tool, controls)`。 */
fun pageGroups(
    tool: EditorTool,
    controls: List<InspectorControl>,
    isIr: Boolean = false,
    hasTextReport: Boolean = false,
    hasWatermarkLayer: Boolean = true,
    fontScale: Float = 1f,
): List<List<String>> = pageGroups(
    PhonePageSpec(tool, controls, isIr, hasTextReport, hasWatermarkLayer),
    fontScale,
)

/** 任务书签名：`pageGroupHeights(tool, controls)`。 */
fun pageGroupHeights(
    tool: EditorTool,
    controls: List<InspectorControl>,
    isIr: Boolean = false,
    hasTextReport: Boolean = false,
    hasWatermarkLayer: Boolean = true,
    fontScale: Float = 1f,
): List<Float> = pageGroupHeights(
    PhonePageSpec(tool, controls, isIr, hasTextReport, hasWatermarkLayer),
    fontScale,
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
// 装得下吗 / 默认开多高
// ---------------------------------------------------------------------------

/**
 * 这一页在这个底板高度下是否**完全不用滚**：公共区 + 最高的一组都要落在底板里。
 *
 * 公共区只在页面高度够 [showsPhoneParameterSummary] 时出现；不出现时页面反而更宽裕，
 * 所以这里按真实 pageHeight 判定，两种分支都不会漏。
 */
fun fitsWithoutScroll(spec: PhonePageSpec, sheetLevelDp: Float, fontScale: Float = 1f): Boolean {
    if (spec.tool == EditorTool.Border) return false
    if (!sheetLevelDp.isFinite() || sheetLevelDp <= 0f) return false
    val chrome = phoneParameterSheetChrome(sheetLevelDp, fontScale)
    val pageHeight = sheetLevelDp - chrome.totalDp
    if (pageHeight <= 0f) return false
    val band = if (showsPhoneParameterSummary(pageHeight)) PHONE_PARAMETER_SUMMARY_DP else 0f
    val tallest = pageGroupHeights(spec, fontScale).maxOrNull() ?: 0f
    return pageHeight - band >= tallest - 0.01f
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
 * 规则：高度 = 参数页 chrome + 公共区 + **最高的一组** + [PHONE_PAGE_LEVEL_SLACK_DP]，
 * 再夹到 [`MIN_SHEET_DP`, 舞台 50 % 地板定出的上限]。于是：
 *  - 两组滑杆的页（样式 / 文字大小）开得矮，画布多留一截；
 *  - 六项控件 / 3×3 九宫格那页可以开高，但仍然不越舞台地板；
 *  - 绝对下限还是 120 dp、上限还是 `compactMax`，一个新数字都没造。
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
    val content = pageGroupHeights(spec, fontScale).maxOrNull() ?: 0f
    val scale = fontScale.coerceIn(1f, 1.5f)
    fun needed(tier: PhoneSheetChromeTier): Float {
        val head = max(tier.headDp, SHEET_HEAD_LINE_DP * scale)
        return tier.grabberDp + head + PHONE_PARAMETER_SUMMARY_DP + content + PHONE_PAGE_LEVEL_SLACK_DP
    }
    val roomy = ParameterChromeTiers.firstNotNullOfOrNull { tier ->
        needed(tier).takeIf { it >= tier.minLevelDp }
    } ?: needed(ParameterChromeTiers.last())
    val level = roomy.coerceIn(MIN_SHEET_DP, compactMax)
    return level.takeIf { fitsWithoutScroll(spec, it, fontScale) }
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
