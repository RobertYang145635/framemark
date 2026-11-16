package com.framemark.native.shell

import com.framemark.native.inspector.ControlOption
import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorPage
import com.framemark.native.inspector.StylePageBody
import com.framemark.native.inspector.fontScaleControlOf
import com.framemark.native.inspector.footerModeControlOf
import com.framemark.native.inspector.footerPctControlOf
import com.framemark.native.inspector.phoneStylePageBody
import com.framemark.native.inspector.themeControlOf
import com.framemark.native.inspector.withoutDuplicateStockAnchors
import com.framemark.native.inspector.WatermarkZones
import com.framemark.native.product.StockWatermarkControls
import com.framemark.native.workflow.WatermarkSpec
import kotlin.math.max

/**
 * 手机参数面板的**固定画框**（2026-11-10，照 Adobe Lightroom 手机版在本机上的量法）。
 *
 * ## 量到的三件事（411×905 dp，竖屏）
 *
 *  1. 编辑态的参数面板是**固定高度**，约窗口高的 34 %；画布是上面剩下的全部，照片
 *     按 contain 画在画布里 —— 横照片有上下黑边，**画布本身不随工具变**。
 *  2. 参数行是**两行一组**：第一行标签在左、数值在右（`曝光度 …… -0.13`）；第二行是
 *     **整宽轨道**（≈ 屏宽的 90 %）。行距 ≈ 64 dp。
 *  3. 参数列表在放不下时**竖向滚动**（Lr 的光学 2 行、亮度 6 行都会滚）；参数列表
 *     下面是**固定的工具组 tab 行**（`自动｜亮度｜颜色｜模糊｜效果｜细节｜光学｜配置`），
 *     它不跟着参数滚。
 *
 * ## 于是这一层的规则
 *
 *  - 面板高度 = `clamp(0.34 × 窗口高, 280, 330)`，**每个工具、每一页、每一组都同一个数**
 *    （[phonePanelHeightDp]）。它不看内容、不看工具、不看页签；切换 样式/文字/Logo/水印
 *    或点任何一个组 tab，面板顶边一个 dp 都不动。
 *  - 参数行 = 标签行（15 sp）+ 全宽轨道行（[PHONE_PARAM_ROW_DP] 的 64 dp 行距）。
 *    **没有任何两条滑杆并排**。（原「两条滑杆一行」的密度档已删除。）
 *  - 组不是横滑分页，是**固定的 tab 行**（[phonePanelGroups]）；tab 行与页头钉在面板
 *    顶部，只有 tab 行下面、面板底边上面的参数区竖向滚动（[phoneParameterAreaHeightDp]）。
 *  - **每个组 2..3 行**（[PHONE_GROUP_MIN_ROWS] / [PHONE_GROUP_MAX_ROWS]）：一行的组
 *    会在固定画框下面空出 ~150 dp（2026-11-11 的缺陷 2），四行的组又放不下。分组只
 *    重排这一页已有的行，不新增控件；这一页本来就超过三条、而且只有一组时，那一组就是
 *    会滚动的尾巴（面板高度不动）。
 *  - 面板放不下时（窗口 < [FORCED_PREVIEW_MIN_DP]，或大字号）退回整页参数页 —— 那是
 *    窗口的决定，不是某个工具的决定。
 *
 * ## 这一版删掉的机器（旧「分页」政策）
 *
 * `partitionPages` / `pageGroups` / `pageGroupHeights` / `defaultSheetLevelFor` /
 * `pageAllowanceForWindow` / `pageBudgetForLevel` / `fitsWithoutScroll` /
 * `PHONE_PAGE_GROUP_BUDGET_DP` / `phoneBlockHeightDp` 一族：它们的存在意义是「按内容
 * 算每页多高、放不下就多分一页」。固定画框之后没有「一页」这个概念了，所以整组删除，
 * 不再用「内容变高 → 面板长高」换「页数变少」。
 */

// ---------------------------------------------------------------------------
// 1. 固定面板高度
// ---------------------------------------------------------------------------

/** 量到的比例：参数面板 = 窗口高的 34 %。 */
const val PHONE_PANEL_FRACTION = 0.34f

/** 比例的下夹：再矮的窗口也不把面板压到读不出两行。 */
const val PHONE_PANEL_MIN_DP = 280f

/** 比例的上夹：再高的窗口也不让面板吃掉画布。 */
const val PHONE_PANEL_MAX_DP = 330f

/**
 * 画布地板：固定面板 + 顶栏 + 工具条之后，照片至少还要有这么高，否则整页参数页接管。
 * 它是「固定画框在这个窗口里放不下」的判据，不是面板的第二个尺寸。
 */
const val PHONE_PANEL_MIN_STAGE_DP = 176f

/**
 * 固定画框的面板高度，dp。非有限 / 非正数的窗口高返回 0（调用方按放不下处理）。
 *
 * 同一个窗口里，样式 / 文字 / Logo / 水印 四个工具读到的是**同一个数**
 * （`PhonePanelFrameTest.aFixedPanelHeightForEveryTool` 逐个断言）。
 */
fun phonePanelHeightDp(appHeightDp: Float): Float {
    if (!appHeightDp.isFinite() || appHeightDp <= 0f) return 0f
    return (appHeightDp * PHONE_PANEL_FRACTION).coerceIn(PHONE_PANEL_MIN_DP, PHONE_PANEL_MAX_DP)
}

/** 面板里页头两行的高度：拖拽把手 + 标题行（固定，不按内容 / 档位取）。 */
const val PHONE_PANEL_GRABBER_DP = 16f
const val PHONE_PANEL_HEAD_DP = 28f

/** 固定的组 tab 行（钉在页头下面，参数区在它下面滚）。 */
const val PHONE_PANEL_TAB_ROW_DP = 44f

/** 标题行至少放得下一行 16 sp 字（字号放大时标题行跟着长，行数不因此变）。 */
private const val SHEET_HEAD_LINE_DP = 24f

/** 底板顶部两行的高度：拖拽把手 + 标题行。 */
data class PhoneSheetChrome(val grabberDp: Float, val headDp: Float) {
    val totalDp: Float get() = grabberDp + headDp
}

/** 参数面板的 chrome：固定 16 + 28（大字号时标题行跟着长）。 */
fun phoneParameterSheetChrome(fontScale: Float = 1f): PhoneSheetChrome {
    val scale = fontScale.coerceIn(1f, 1.5f)
    return PhoneSheetChrome(
        grabberDp = PHONE_PANEL_GRABBER_DP,
        headDp = max(PHONE_PANEL_HEAD_DP, SHEET_HEAD_LINE_DP * scale),
    )
}

/**
 * 边框落地板的固定 chrome：16 dp 把手 + 44 dp 标题行。
 *
 * 2026-11-15（配色方案 §4.1）：边框页收成一行「当前项」，不再需要旧卡片堆叠的
 * 22/30/38/42 四段 chrome；标题行取 44 dp 保证「调整样式 / 展开」仍有触控地板。
 */
const val PHONE_BORDER_SHEET_GRABBER_DP = 16f
const val PHONE_BORDER_SHEET_HEAD_DP = 44f

/**
 * 任意工具的把手行 + 标题行。边框页用固定 chrome（[PHONE_BORDER_SHEET_GRABBER_DP] /
 * [PHONE_BORDER_SHEET_HEAD_DP]），四个参数工具读 [phoneParameterSheetChrome]。
 */
fun phoneSheetChrome(fontScale: Float, tool: EditorTool): PhoneSheetChrome =
    if (tool == EditorTool.Border) {
        PhoneSheetChrome(
            grabberDp = PHONE_BORDER_SHEET_GRABBER_DP,
            headDp = PHONE_BORDER_SHEET_HEAD_DP,
        )
    } else {
        phoneParameterSheetChrome(fontScale)
    }

/**
 * 参数区的净高：面板 − chrome − 组 tab 行。tab 行与页头钉住，只这一块滚。
 */
fun phoneParameterAreaHeightDp(panelHeightDp: Float, fontScale: Float = 1f): Float {
    if (!panelHeightDp.isFinite() || panelHeightDp <= 0f) return 0f
    return (panelHeightDp - phoneParameterSheetChrome(fontScale).totalDp - PHONE_PANEL_TAB_ROW_DP)
        .coerceAtLeast(0f)
}

// ---------------------------------------------------------------------------
// 2. 一行参数的两行框
// ---------------------------------------------------------------------------

/** 参数行左右内边距：411 dp 屏 → 379 dp 内容宽（量到的「轨道 ≈ 屏宽 90 %」）。 */
const val PHONE_PARAM_INSET_DP = 16f

/** 行距（pitch）：标签行 + 轨道行 + 行间 12。量到的是 ~64 dp。 */
const val PHONE_PARAM_ROW_DP = 64f

/** 一行自身的高度（不含行间）：标签行 26 + 轨道行 26。 */
const val PHONE_PARAM_ROW_CONTENT_DP = 52f

/** 两行之间的间距。 */
const val PHONE_PARAM_ROW_GAP_DP = 12f

/** 第一行（标签 + 数值）的高度。 */
const val PHONE_PARAM_LABEL_LINE_DP = 26f

/** 第二行（整宽轨道）的高度。 */
const val PHONE_PARAM_TRACK_LINE_DP = 26f

/** 标签与数值的字号（量到的 15 sp）。 */
const val PHONE_PARAM_LABEL_SP = 15f
const val PHONE_PARAM_VALUE_SP = 15f

/** 触控地板：一行 52 dp ≥ 44（WCAG 2.5.5 AAA，与 `PhoneTouchTarget` 同一个数）。 */
const val PHONE_PARAM_ROW_MIN_TOUCH_DP = 44f

/**
 * 轨道至少要占内容宽的 85 %（验收口径）。固定画框里轨道就是**整条**内容宽，
 * 所以这个比例是 1.0；常量留在这里给测试重新推导这个下界。
 */
const val PHONE_PARAM_TRACK_MIN_FRACTION = 0.85f

/** 第一行右端数值位的宽度下限（数值框 / 输入框要放得下 `-0.13` 与 `100%`）。 */
const val PHONE_PARAM_VALUE_BAND_DP = 88f

/** 参数行的几何：内容宽、标签带宽、数值带宽、轨道宽（纯函数，测试直接量）。 */
data class PhoneParamRowFrame(
    val contentWidthDp: Float,
    val labelWidthDp: Float,
    val valueWidthDp: Float,
    val trackWidthDp: Float,
) {
    /** 轨道 / 内容宽。固定画框下是 1.0。 */
    val trackShare: Float
        get() = if (contentWidthDp > 0f) trackWidthDp / contentWidthDp else 0f
}

/** 屏宽 → 参数行的几何（411 dp 屏：内容 379、数值 88、标签 291、轨道 379）。 */
fun phoneParamRowFrame(screenWidthDp: Float): PhoneParamRowFrame {
    val screen = if (screenWidthDp.isFinite()) screenWidthDp.coerceAtLeast(0f) else 0f
    val content = (screen - 2f * PHONE_PARAM_INSET_DP).coerceAtLeast(0f)
    val value = if (content > PHONE_PARAM_VALUE_BAND_DP * 2f) PHONE_PARAM_VALUE_BAND_DP else 0f
    return PhoneParamRowFrame(
        contentWidthDp = content,
        labelWidthDp = (content - value).coerceAtLeast(0f),
        valueWidthDp = value,
        trackWidthDp = content,
    )
}

/** 内容宽（411 dp 屏 = 379 dp）。 */
fun phoneParamContentWidthDp(screenWidthDp: Float): Float = phoneParamRowFrame(screenWidthDp).contentWidthDp

/** 轨道宽（= 内容宽；411 dp 屏 = 379 dp，占屏宽 92.2 %）。 */
fun phoneParamTrackWidthDp(screenWidthDp: Float): Float = phoneParamRowFrame(screenWidthDp).trackWidthDp

// ---------------------------------------------------------------------------
// 3. 组（tab）与行
// ---------------------------------------------------------------------------

/**
 * 组 tab 的稳定 id（2026-11-11：按「每组 2..3 行」重新分组后的这一套）。
 *
 * 组 id 只活在这一次渲染里（`rememberSaveable` 的页内选中项），不进项目文件，
 * 所以改名不是持久化格式变化；旧 id 找不到组时自动退回第一组。
 */
object PhoneGroupIds {
    // 样式：底色 + 版式合成「外观」；厂商卡的水印位置单独一组；留白是页脚那几条
    const val STYLE_APPEARANCE = "style.appearance"
    const val STYLE_POSITION = "style.position"
    const val STYLE_FOOTER = "style.footer"
    const val STYLE_SPEC = "style.spec"

    // 文字：文本框选择 + 字号只有两条，合成一组（两组各一行会把面板留成空的）
    const val TEXT_CONTENT = "text.content"

    // Logo：显示 / 位置一组，大小 + 微调 + 品牌字标一组
    const val LOGO_DISPLAY = "logo.display"
    const val LOGO_ADJUST = "logo.adjust"

    // 水印：图层卡片 + 两条边距一组，宽度 / 不透明度 / 旋转一组
    const val WATERMARK_LAYER = "wm.layer"
    const val WATERMARK_APPEARANCE = "wm.appearance"
}

/** 合成行的稳定键（不是声明控件）。 */
object PhoneRowKeys {
    const val TEXT_SLOTS = "row:text-slots"
    const val SPEC = "row:border-spec"
    const val LOGO_NOTICE = "row:logo-notice"
    const val EXPORT_SIZE = "row:export-size"
    const val ANCHOR_GRID = "row:stock-anchor-grid"
    const val ANCHOR_FOOTER = "row:stock-anchor-footer"
    const val EMPTY = "row:empty"

    const val WATERMARK_LAYER = "row:watermark-layer"
    const val WATERMARK_EMPTY = "row:watermark-empty"
    const val WATERMARK_MARGIN_X = "row:watermark-margin-x"
    const val WATERMARK_MARGIN_Y = "row:watermark-margin-y"
    const val WATERMARK_PINNED = "row:watermark-pinned"
    const val WATERMARK_WIDTH = "row:watermark-width"
    const val WATERMARK_OPACITY = "row:watermark-opacity"
    const val WATERMARK_ROTATION = "row:watermark-rotation"
}

/** 一行画成什么。数值行是唯一有「全宽轨道」的形态（[PhoneRowKind.Slider]）。 */
enum class PhoneRowKind {
    /** 数值参数：标签 + 数值（可输入 / 双击复位）+ 整宽轨道。 */
    Slider,

    /** 开关：标签 + 数值（开 / 关）+ 开关轨道。 */
    Switch,

    /** 分段选择：标签 + 当前值 + 分段控件（整内容宽）。 */
    Segmented,

    /** 色板：一行六列（整内容宽）。 */
    Swatches,

    /** 自由文本：标签 + 输入框。 */
    Text,

    /** 渲染器报告的文本框选择器（整块）。 */
    TextSlots,

    /** 厂商 `stockAnchor` 的九宫格。 */
    AnchorGrid,

    /** 厂商 `stockAnchor` 的「按规格」+ 说明。 */
    AnchorFooter,

    /** 单行文字方位（`slotGravity`）：3×3 矩阵，不是十项色片。 */
    SlotGravity,

    /** 只读边框规格。 */
    Spec,

    /** Logo 的三个动作（更换 Logo / 自动匹配 / 我的 Logo）。 */
    LogoActions,

    /** Logo 页的说明块（没有可替换字标时）。 */
    LogoNotice,

    /** 水印图层卡片（缩略 + 名称 + 更换 / 移除）。 */
    WatermarkLayer,

    /** 水印空态（说明 + 上传）。 */
    WatermarkEmpty,

    /** 只读说明 / 固定档。 */
    Note,

    /** 公共信息行：导出尺寸。 */
    ExportSize,
}

/** 参数行行高（含行距）：数值行是量到的 64，其余按各自形态。 */
object PhoneRowHeights {
    const val SLIDER = PHONE_PARAM_ROW_DP
    const val SWITCH = PHONE_PARAM_ROW_DP
    const val SEGMENTED = 82f
    const val SWATCHES = 71f
    const val TEXT = 82f
    const val TEXT_SLOTS = 104f
    const val ANCHOR_GRID = 144f
    const val ANCHOR_FOOTER = 76f
    const val SPEC = 64f
    const val LOGO_ACTIONS = 84f
    const val NOTICE = 96f
    const val WATERMARK_LAYER = 56f
    const val WATERMARK_EMPTY = 112f
    const val NOTE = 48f
    const val EXPORT_SIZE = 46f
}

/**
 * 一行参数。[label] 是**缩短过**的短标签（量到的行里没有省略号：短标签 + 右端数值，
 * 放不下就改文案，不改省略号），[control] 是这一行绑定的声明控件（合成行为 null）。
 */
data class PhoneParamRow(
    val key: String,
    val kind: PhoneRowKind,
    val label: String = "",
    val control: InspectorControl? = null,
    val heightDp: Float = PhoneRowHeights.SLIDER,
)

/** 一个组 tab：固定顺序、固定标签、它的行。 */
data class PhoneParamGroup(
    val id: String,
    val label: String,
    val rows: List<PhoneParamRow>,
)

// ---------------------------------------------------------------------------
// 3b. 分组规则：每组 2..3 行（缺陷 2）
// ---------------------------------------------------------------------------

/**
 * 一个组 tab 的行数下限：**一行的组不是组**。固定画框里参数区 219.7 dp，一行
 * 64 dp 的组下面会空出 ~150 dp（水印「图层」组原来的样子）。两个功能行起步。
 */
const val PHONE_GROUP_MIN_ROWS = 2

/**
 * 一个组 tab 的行数上限：三条 64 dp 的行（192 dp）在 411×905 dp 的窗口里正好完整
 * 露出；第四条开始就得滚。所以默认切到三行为止，只有「这一页的控件本来就比三条多、
 * 而且它已经是最后一组」时才允许继续往上叠（那时滚动是唯一的选择，不是布局失败）。
 */
const val PHONE_GROUP_MAX_ROWS = 3

/** 量到的那台机器（411×905 dp）的参数区净高：分组「放不放得下」的判据。 */
val PHONE_PARAM_AREA_MEASURED_DP: Float = phoneParameterAreaHeightDp(phonePanelHeightDp(905f))

/** 参数区上下各 8 dp 的内边距（`PhoneParameterRows` 的 `padding(vertical = 8.dp)`）。 */
const val PHONE_PARAM_AREA_INSET_DP = 8f

/**
 * 合并单行组时允许的溢出：两条行距（24 dp）。
 *
 * 三行**混合形态**的组比三条 64 dp 的数值行高：色板 71 + 分段 82 + 滑杆 64 = 205 dp，
 * 两个分段 + 一个滑杆 = 216 dp，而参数区扣掉上下内边距的净高是 203.7 dp。为了这几 dp
 * 留一个单行的 tab 是本末倒置：并成三行、让这一组滚十几 dp 更好。超过两条行距的溢出
 * 就不并了（厂商卡里 144 dp 的九宫格 + 规格行 = 272 dp，那种合并会让整页都要滚）。
 */
const val PHONE_GROUP_MERGE_SLACK_DP = 2f * PHONE_PARAM_ROW_GAP_DP

/** 一组行**真正**占的高度：行高之和 − 最后一行的行距（行距只加在行与行之间）。 */
fun phoneGroupHeightDp(rows: List<PhoneParamRow>): Float =
    if (rows.isEmpty()) {
        0f
    } else {
        rows.fold(0f) { sum, row -> sum + row.heightDp } - PHONE_PARAM_ROW_GAP_DP
    }

/**
 * 这一组在量到的 411×905 dp 窗口里能不能**不滚动**完整露出。
 *
 * 判据用的是组真正占的高度（[phoneGroupHeightDp]，最后一行不带行距）与参数区去掉
 * 上下各 8 dp 内边距之后的净高。三行 64 dp 的数值行 = 180 dp ≤ 203.7 dp，放得下；
 * 第四行开始就放不下，必须滚。
 */
fun phoneGroupFitsTheMeasuredArea(rows: List<PhoneParamRow>): Boolean =
    rows.isNotEmpty() && phoneGroupHeightDp(rows) <= PHONE_PARAM_AREA_MEASURED_DP - 2f * PHONE_PARAM_AREA_INSET_DP

/**
 * 把一页的组规整到「每组 2..3 行」：
 *
 *  - 空组已经在外层被丢掉；
 *  - 只有 1 行的组并进相邻的一组：优先进前面那组，前面满 3 行（或者在页首没有前面
 *    那组）就把后面那组并进来；两边的合并都会超过 24 dp 的余量时保持原样 —— 那是一张
 *    真的只有一两个控件的卡，编不出第二行来；
 *  - 整页只剩一组时不动它：一组 4 行以上只可能是「这一页的控件本来就多」，
 *    那时候它就是一个会滚动的尾巴（面板高度仍然是固定的那个数）。
 */
internal fun balancedParamGroups(groups: List<PhoneParamGroup>): List<PhoneParamGroup> {
    val out = groups.filter { it.rows.isNotEmpty() }.toMutableList()
    if (out.size <= 1) return out
    var guard = 0
    while (guard++ <= groups.size * 2) {
        val index = out.indexOfFirst { it.rows.size < PHONE_GROUP_MIN_ROWS }
        if (index < 0) break
        val group = out[index]
        val prev = out.getOrNull(index - 1)
        val next = out.getOrNull(index + 1)
        val intoPrev = prev != null && fitsTogether(prev, group)
        when {
            intoPrev -> {
                out[index - 1] = prev!!.copy(rows = prev.rows + group.rows)
                out.removeAt(index)
            }
            next != null && fitsTogether(group, next) -> {
                // 后面那组并进来（保留当前这组的名字与顺序：组的名字跟着它最前面的行走）。
                out[index] = group.copy(rows = group.rows + next.rows)
                out.removeAt(index + 1)
            }
            else -> break
        }
    }
    return out
}

/** 合并之后还是不是「2..3 行、并且（算上一条行距的余量）在 411 dp 里放得下」。 */
private fun fitsTogether(host: PhoneParamGroup, guest: PhoneParamGroup): Boolean {
    val rows = host.rows + guest.rows
    if (rows.size > PHONE_GROUP_MAX_ROWS) return false
    val available = PHONE_PARAM_AREA_MEASURED_DP - 2f * PHONE_PARAM_AREA_INSET_DP
    return phoneGroupHeightDp(rows) <= available + PHONE_GROUP_MERGE_SLACK_DP
}

/**
 * 有序的行 → 2..3 行的块，首块先取 [firstSize] 行。
 *
 * Logo 用首块把「显示 / 位置」放在一起，其余行（大小、微调、品牌动作）按三行续块；
 * 剩下的尾块只有 1 行时从上一块借一行，所以永远不会切出一个单行组。
 */
internal fun chunkParamRows(rows: List<PhoneParamRow>, firstSize: Int = PHONE_GROUP_MIN_ROWS): List<List<PhoneParamRow>> {
    if (rows.isEmpty()) return emptyList()
    if (rows.size <= PHONE_GROUP_MAX_ROWS) return listOf(rows)
    val out = mutableListOf<List<PhoneParamRow>>()
    var index = minOf(firstSize, PHONE_GROUP_MAX_ROWS).coerceAtLeast(PHONE_GROUP_MIN_ROWS)
    out += rows.subList(0, index)
    while (rows.size - index > PHONE_GROUP_MAX_ROWS) {
        out += rows.subList(index, index + PHONE_GROUP_MAX_ROWS)
        index += PHONE_GROUP_MAX_ROWS
    }
    val tail = rows.size - index
    if (tail >= PHONE_GROUP_MIN_ROWS) {
        out += rows.subList(index, rows.size)
    } else {
        // 尾巴只剩 1 行：从上一块借 1 行，凑成 2 行（上一块仍有 ≥ 2 行）。
        val previous = out.removeAt(out.lastIndex)
        out += previous.subList(0, previous.size - 1)
        out += previous.subList(previous.size - 1, previous.size) + rows.subList(index, rows.size)
    }
    return out
}

/**
 * 一页参数面板的完整输入。[controls] 必须是**这个页签自己的**控件
 * （`phonePageControls(tab)`）：`InspectorTabs` 已经把每个控件分到恰好一页。
 */
data class PhonePageSpec(
    val tool: EditorTool,
    val controls: List<InspectorControl> = emptyList(),
    /** 厂商规格卡（`InspectorCardModel.isIr`）：样式页带只读边框规格。 */
    val isIr: Boolean = false,
    /** 舞台渲染报告里有没有文本框（决定文字页是选择器还是声明行）。 */
    val hasTextReport: Boolean = false,
    /** 水印图层存在（`watermark != null`）；false = 空态 + 上传。 */
    val hasWatermarkLayer: Boolean = true,
    /** 照片像素尺寸已知时，样式页多一行「导出尺寸」（公共信息，不是参数）。 */
    val hasExportSize: Boolean = false,
)

// ---------------------------------------------------------------------------
// 4. 工具 → 组 → 行
// ---------------------------------------------------------------------------

private fun row(
    key: String,
    kind: PhoneRowKind,
    heightDp: Float,
    label: String = "",
    control: InspectorControl? = null,
): PhoneParamRow = PhoneParamRow(key = key, kind = kind, label = label, control = control, heightDp = heightDp)

/** 声明控件 → 行（形态由控件的类型决定；品牌字标归 Logo 动作，不在这里）。 */
private fun declaredRow(control: InspectorControl): PhoneParamRow? = when (control.type) {
    ControlType.Slider -> row(
        key = control.key,
        kind = PhoneRowKind.Slider,
        heightDp = PhoneRowHeights.SLIDER,
        label = phoneParamLabel(control),
        control = control,
    )
    ControlType.Switch -> row(
        key = control.key,
        kind = PhoneRowKind.Switch,
        heightDp = PhoneRowHeights.SWITCH,
        label = phoneParamLabel(control),
        control = control,
    )
    ControlType.Color -> row(
        key = control.key,
        kind = PhoneRowKind.Swatches,
        heightDp = PhoneRowHeights.SWATCHES,
        label = phoneParamLabel(control),
        control = control,
    )
    ControlType.Select -> when {
        // 单行文字方位（slotGravity）也是九宫格：10 个厂商枚举值不能排成一条色片。
        isSlotGravity(control) -> row(
            key = control.key,
            kind = PhoneRowKind.SlotGravity,
            heightDp = PhoneRowHeights.ANCHOR_GRID,
            label = phoneParamLabel(control),
            control = control,
        )
        // 厂商水印位置用九宫格，不用十项下拉 / 色片。
        StockWatermarkControls.isAnchorParam(control.param) -> row(
            key = control.key,
            kind = PhoneRowKind.AnchorGrid,
            heightDp = PhoneRowHeights.ANCHOR_GRID,
            label = phoneParamLabel(control),
            control = control,
        )
        control.options.size <= 4 -> row(
            key = control.key,
            kind = PhoneRowKind.Segmented,
            heightDp = PhoneRowHeights.SEGMENTED,
            label = phoneParamLabel(control),
            control = control,
        )
        else -> row(
            key = control.key,
            kind = PhoneRowKind.Swatches,
            heightDp = PhoneRowHeights.SWATCHES,
            label = phoneParamLabel(control),
            control = control,
        )
    }
    ControlType.Text -> row(
        key = control.key,
        kind = PhoneRowKind.Text,
        heightDp = PhoneRowHeights.TEXT,
        label = phoneParamLabel(control),
        control = control,
    )
    // 品牌字标由 Logo 页的「动作」组画（三个动作走同一个写入点），不单算一行。
    ControlType.Brand -> null
}

/**
 * 样式页：`[外观｜位置｜留白｜规格]`（空组不出现，最后按「每组 2..3 行」规整）。
 *
 *  - **外观**：底色 + 版式。两张都在，正好 2 行（旧版是「底色」「版式」两个一组一行
 *    的 tab，两组各自下面空一半）。
 *  - **位置**：厂商卡可移动水印的九宫格 + 「按规格」两行（也是 2 行）。
 *  - **留白**：页脚自适应 + 底部留白 + 其余声明行；普通卡的「导出尺寸」并进来
 *    （2 + 1 = 3 行，正好放得下）。
 *  - **规格**：厂商规格卡（`isIr`）的只读规格 + 导出尺寸，单独 2 行；只有一行时
 *    [balancedParamGroups] 会把它并回留白，不会留一个单行 tab。
 */
/** 单行文字方位：`slotGravity` 或 9 个方位值里至少 8 个的 Select（`stockAnchor` 除外）。 */
internal fun isSlotGravity(control: InspectorControl): Boolean =
    !StockWatermarkControls.isAnchorParam(control.param) &&
        control.param != "stockAnchor" &&
        (
            control.param.equals("slotGravity", ignoreCase = true) ||
                control.options.count { it.value in SLOT_GRAVITY_CELLS } >= 8
            )

private val SLOT_GRAVITY_CELLS = listOf(
    "leftTop", "centerTop", "rightTop",
    "leftCenter", "center", "rightCenter",
    "leftBottom", "centerBottom", "rightBottom",
)

private fun styleGroups(spec: PhonePageSpec): List<PhoneParamGroup> {
    val controls = spec.controls.filterNot { it.type == ControlType.Brand }
    val specOnly = phoneStylePageBody(spec.isIr, controls) == StylePageBody.SpecOnly
    if (specOnly) {
        // 什么都没声明的厂商规格卡：一页只读规格 + 说明。
        val rows = buildList {
            add(row(PhoneRowKeys.SPEC, PhoneRowKind.Spec, PhoneRowHeights.SPEC))
            if (spec.hasExportSize) {
                add(row(PhoneRowKeys.EXPORT_SIZE, PhoneRowKind.ExportSize, PhoneRowHeights.EXPORT_SIZE))
            }
        }
        return listOf(PhoneParamGroup(PhoneGroupIds.STYLE_SPEC, "规格", rows))
    }
    val theme = themeControlOf(controls)
    val mode = footerModeControlOf(controls)
    val footerPct = footerPctControlOf(controls)
    val anchor = controls.firstOrNull { StockWatermarkControls.isAnchorParam(it.param) }
    val gravity = controls.filter { isSlotGravity(it) }
    val footerSwitch = controls.firstOrNull {
        it.type == ControlType.Switch && it.param.contains("footer", ignoreCase = true)
    }
    val handled = setOfNotNull(theme?.key, mode?.key, footerPct?.key, footerSwitch?.key, anchor?.key) +
        gravity.map { it.key }
    val rest = withoutDuplicateStockAnchors(controls.filterNot { it.key in handled })

    // 外观：底色 + 版式（旧版的两个单行 tab 合成一组）。
    val appearanceRows = listOfNotNull(
        theme?.let { declaredRow(it) },
        mode?.let { declaredRow(it) },
    )
    // 位置：厂商卡的水印九宫格 +「按规格」；单行文字方位也放这一组。
    val positionRows = buildList {
        gravity.forEach { control -> add(declaredRow(control)!!) }
        anchor?.let { control ->
            add(
                row(
                    key = PhoneRowKeys.ANCHOR_GRID,
                    kind = PhoneRowKind.AnchorGrid,
                    heightDp = PhoneRowHeights.ANCHOR_GRID,
                    label = phoneParamLabel(control),
                    control = control,
                ),
            )
            add(
                row(
                    key = PhoneRowKeys.ANCHOR_FOOTER,
                    kind = PhoneRowKind.AnchorFooter,
                    heightDp = PhoneRowHeights.ANCHOR_FOOTER,
                    control = control,
                ),
            )
        }
    }
    // 留白：页脚自适应 + 底部留白 + 其余声明行。
    val paddingRows = buildList {
        // 先开关后滑杆：用户先看到「页脚自适应」这一句是非题。
        footerSwitch?.let { control -> declaredRow(control)?.let { add(it) } }
        footerPct?.let { control -> declaredRow(control)?.let { add(it) } }
        rest.forEach { control -> declaredRow(control)?.let { add(it) } }
    }
    // 规格：厂商卡的只读规格 + 导出尺寸。普通卡只有导出尺寸一行，并进留白凑成 3 行。
    val specRows = buildList {
        if (spec.isIr) add(row(PhoneRowKeys.SPEC, PhoneRowKind.Spec, PhoneRowHeights.SPEC))
        if (spec.hasExportSize) {
            add(row(PhoneRowKeys.EXPORT_SIZE, PhoneRowKind.ExportSize, PhoneRowHeights.EXPORT_SIZE))
        }
    }
    return listOf(
        PhoneParamGroup(PhoneGroupIds.STYLE_APPEARANCE, "外观", appearanceRows),
        PhoneParamGroup(PhoneGroupIds.STYLE_POSITION, "位置", positionRows),
        PhoneParamGroup(
            PhoneGroupIds.STYLE_FOOTER,
            "留白",
            if (spec.isIr) paddingRows else paddingRows + specRows,
        ),
        // 厂商卡的只读规格 + 导出尺寸单独一组（2 行）；普通卡的规格是空的，
        // 导出尺寸已经并进上面的留白（不并就会留一个单行 tab）。
        PhoneParamGroup(
            PhoneGroupIds.STYLE_SPEC,
            "规格",
            if (spec.isIr) specRows else emptyList(),
        ),
    )
}

/**
 * 文字页：**只有一组**「文字」。
 *
 * 旧版是 `[选择｜大小]`：有渲染器报告时两组各只有一行（文本框选择器 / 字号），
 * 面板下面空一大片。两条合成一组正好 2 行；没有报告时声明出来的 7 条文字行
 * （5 个开关 + 自定义行 + 字号）是一页本来就超过三条的情形，那一组就是一个会滚动的
 * 尾巴 —— 面板高度仍然是固定的那个数。
 *
 * 只有一组 ⇒ 页面不画组 tab 行（`phoneGroupTabRowVisible`），页头的「文字」下面
 * 不会再多一个点了没反应的「文字」。
 */
private fun textGroups(spec: PhonePageSpec): List<PhoneParamGroup> {
    val controls = spec.controls.filterNot { it.type == ControlType.Brand }
    val scale = fontScaleControlOf(controls)
    val others = controls.filterNot { it.key == scale?.key }
    val rows = buildList {
        if (spec.hasTextReport) {
            // 渲染器报告的文本框选择器：一整块（导航 + 单卡编辑）。
            add(row(PhoneRowKeys.TEXT_SLOTS, PhoneRowKind.TextSlots, PhoneRowHeights.TEXT_SLOTS))
        } else {
            others.forEach { control -> declaredRow(control)?.let { add(it) } }
        }
        scale?.let { control -> declaredRow(control)?.let { add(it) } }
    }.ifEmpty { listOf(row(PhoneRowKeys.EMPTY, PhoneRowKind.Note, PhoneRowHeights.NOTICE)) }
    return listOf(PhoneParamGroup(PhoneGroupIds.TEXT_CONTENT, "文字", rows))
}

/**
 * Logo 页：`[显示｜调整]`。
 *
 * 旧版 `[调整｜动作]` 把 4 条调整行塞进一个 tab（放不下，要滚），动作只有品牌一行。
 * 现在前两行（显示 Logo / Logo 位置）归「显示」，后三行（Logo 大小 / 水平位置 /
 * 品牌字标）归「调整」：2 + 3 = 5 行，两组都在 411 dp 里完整露出。
 * 行的条数随卡片变：[chunkParamRows] 按 2..3 行切块，切不出单行组。
 */
private fun logoGroups(spec: PhonePageSpec): List<PhoneParamGroup> {
    val brand = spec.controls.firstOrNull { it.type == ControlType.Brand }
    val adjustments = spec.controls.filterNot { it.type == ControlType.Brand }
    val adjustRows = buildList {
        adjustments.forEach { control -> declaredRow(control)?.let { add(it) } }
        if (adjustments.isEmpty() && brand == null) {
            add(row(PhoneRowKeys.LOGO_NOTICE, PhoneRowKind.LogoNotice, PhoneRowHeights.NOTICE))
        }
    }
    val actionRows = listOfNotNull(
        brand?.let {
            row(
                key = it.key,
                kind = PhoneRowKind.LogoActions,
                heightDp = PhoneRowHeights.LOGO_ACTIONS,
                label = phoneParamLabel(it),
                control = it,
            )
        },
    )
    val chunks = chunkParamRows(adjustRows + actionRows)
    // 标签：第一块是「显示」（显示 Logo / Logo 位置），第二块是「调整」（大小 / 微调 /
    // 品牌字标）。只有一块时按内容取名（没有调整行就是「动作」，只有通知就是「调整」）。
    val labels = when {
        chunks.size <= 1 -> listOf(if (adjustRows.isEmpty() && actionRows.isNotEmpty()) "动作" else "调整")
        else -> listOf("显示", "调整", "调整 2", "调整 3")
    }
    return chunks.mapIndexed { index, rows ->
        PhoneParamGroup(
            id = when {
                index == 0 && chunks.size > 1 -> PhoneGroupIds.LOGO_DISPLAY
                index <= 1 -> PhoneGroupIds.LOGO_ADJUST
                else -> "${PhoneGroupIds.LOGO_ADJUST}.$index"
            },
            label = labels.getOrElse(index) { "调整 ${index + 1}" },
            rows = rows,
        )
    }
}

/**
 * 水印页：`[图层｜外观]`（有图层时）。
 *
 *  - **图层**：图层卡片 + 距左 + 距上 —— 3 行。旧版「图层」只有图层卡片一行，
 *    面板下面空 ~150 dp，这一版把两条边距并进来。
 *  - **外观**：宽度 + 不透明度 + 旋转 —— 3 行（和旧版一样）。
 *
 * 没有水印图层时只有「图层」一组（上传入口），那是空态不是参数组。
 */
private fun watermarkGroups(spec: PhonePageSpec): List<PhoneParamGroup> {
    if (!spec.hasWatermarkLayer) {
        return listOf(
            PhoneParamGroup(
                id = PhoneGroupIds.WATERMARK_LAYER,
                label = "图层",
                rows = listOf(
                    row(
                        PhoneRowKeys.WATERMARK_EMPTY,
                        PhoneRowKind.WatermarkEmpty,
                        PhoneRowHeights.WATERMARK_EMPTY,
                        label = "图层",
                    ),
                ),
            ),
        )
    }
    return listOf(
        PhoneParamGroup(
            PhoneGroupIds.WATERMARK_LAYER,
            "图层",
            listOf(
                row(
                    PhoneRowKeys.WATERMARK_LAYER,
                    PhoneRowKind.WatermarkLayer,
                    PhoneRowHeights.WATERMARK_LAYER,
                    label = "图层",
                ),
                row(PhoneRowKeys.WATERMARK_MARGIN_X, PhoneRowKind.Slider, PhoneRowHeights.SLIDER, label = "距左"),
                row(PhoneRowKeys.WATERMARK_MARGIN_Y, PhoneRowKind.Slider, PhoneRowHeights.SLIDER, label = "距上"),
            ),
        ),
        PhoneParamGroup(
            PhoneGroupIds.WATERMARK_APPEARANCE,
            "外观",
            listOf(
                row(PhoneRowKeys.WATERMARK_WIDTH, PhoneRowKind.Slider, PhoneRowHeights.SLIDER, label = "宽度"),
                row(PhoneRowKeys.WATERMARK_OPACITY, PhoneRowKind.Slider, PhoneRowHeights.SLIDER, label = "不透明度"),
                row(PhoneRowKeys.WATERMARK_ROTATION, PhoneRowKind.Slider, PhoneRowHeights.SLIDER, label = "旋转"),
            ),
        ),
    )
}

/**
 * 这一页的组（固定的 tab 顺序，空的组不出现，**每组 2..3 行**）。
 *
 * 2026-11-11 的重新分组（缺陷 2：一行的组会在固定画框下面空出一大片）：
 *
 *  | 工具 | 组 | 行数 |
 *  |------|----|------|
 *  | 样式 | 外观 / 位置 / 留白 / 规格 | 2 / 2 / 2–3 / 2（空组不出现） |
 *  | 文字 | 文字 | 2（有报告）或 7（没有报告时是一个会滚动的尾巴） |
 *  | Logo | 显示 / 调整 | 2 / 3 |
 *  | 水印 | 图层 / 外观 | 3 / 3 |
 *
 * 边框工具的面板是另一块落地面板（`PhoneBorderPanel`），没有组。
 */
fun phonePanelGroups(spec: PhonePageSpec): List<PhoneParamGroup> {
    val groups = when (spec.tool) {
        EditorTool.Border -> emptyList()
        EditorTool.Params -> styleGroups(spec)
        EditorTool.Text -> textGroups(spec)
        EditorTool.Logo -> logoGroups(spec)
        EditorTool.Watermark -> watermarkGroups(spec)
    }
    return balancedParamGroups(groups)
}

/** 这一页所有组 tab 的标签（按顺序）。 */
fun phoneGroupTabLabels(spec: PhonePageSpec): List<String> = phonePanelGroups(spec).map { it.label }

/**
 * 组 tab 行该不该画：**至少两组才画**。
 *
 * 只有一组时那一行是一个不可能切换的 tab：点它没有任何反应，而页头已经写着同一个
 * 名字（文字页的「文字」、没有图层时水印页的「图层」、只有一组参数时的「规格」/
 * 「留白」），用户读到的就是"页头下面又多了一个没用的东西"。组的行本身照常画，
 * 隐藏的只是这一行导航。
 */
fun phoneGroupTabRowVisible(groupCount: Int): Boolean = groupCount >= 2

/** 这一页要画的全部行（按 tab 顺序展开）——测试用它断言「每个控件恰好一次」。 */
fun phonePanelRows(spec: PhonePageSpec): List<PhoneParamRow> = phonePanelGroups(spec).flatMap { it.rows }

// ---------------------------------------------------------------------------
// 5. tab 行会不会溢出（溢出就必须横滑）
// ---------------------------------------------------------------------------

/** 一个组 tab 的宽度：文字 2 × 12 dp 内边距 + 每个汉字 15 dp（15 sp 的字）。 */
private const val TAB_PAD_DP = 24f
private const val TAB_GLYPH_DP = 15f

/** tab 行左右内边距（与参数行同一个 16 dp 内边距）。 */
const val PHONE_TAB_INSET_DP = PHONE_PARAM_INSET_DP

/** tab 行放不放得下：放不下时 composable 一定挂 `horizontalScroll`（不裁字、不换行）。 */
data class PhoneTabRowFit(
    val tabWidthDp: Float,
    val availableWidthDp: Float,
    val needsScroll: Boolean,
)

/** 单个 tab 的宽度（中文标签 2 字 = 标题宽 + 内边距；英文按字符数估）。 */
fun phoneTabWidthDp(label: String): Float {
    val glyphs = label.sumOf { char -> if (char.code > 0x2E80) 1.0 else 0.55 }.toFloat()
    return TAB_PAD_DP + glyphs * TAB_GLYPH_DP
}

/** [labels] 这几个 tab 在 [screenWidthDp] 屏上放不放得下（放不下 → 横滑）。 */
fun phoneTabRowFit(screenWidthDp: Float, labels: List<String>): PhoneTabRowFit {
    val screen = if (screenWidthDp.isFinite()) screenWidthDp.coerceAtLeast(0f) else 0f
    val available = (screen - 2f * PHONE_TAB_INSET_DP).coerceAtLeast(0f)
    var total = 0f
    labels.forEachIndexed { index, label ->
        if (index > 0) total += 8f
        total += phoneTabWidthDp(label)
    }
    return PhoneTabRowFit(
        tabWidthDp = total,
        availableWidthDp = available,
        needsScroll = total > available + 0.01f,
    )
}

// ---------------------------------------------------------------------------
// 6. 滚动前能看几行
// ---------------------------------------------------------------------------

/**
 * 参数区在 [panelHeightDp] 这个面板里能**完整**露出几行（半行不算）。
 * 报告的「滚动前能看几行」就是它对每个工具的组跑出来的最小值。
 */
fun phoneVisibleRowCount(panelHeightDp: Float, rows: List<PhoneParamRow>, fontScale: Float = 1f): Int {
    val available = phoneParameterAreaHeightDp(panelHeightDp, fontScale)
    if (available <= 0f) return 0
    var used = 0f
    var count = 0
    rows.forEach { row ->
        used += row.heightDp
        if (used <= available + 0.01f) count++ else return count
    }
    return count
}

// ---------------------------------------------------------------------------
// 7. 水印九宫格：画布上的点击 → 同一个 anchor 值
// ---------------------------------------------------------------------------

/**
 * 照片矩形里的相对位置（0..1）→ 九宫格 index（`row * 3 + col`，左上 = 0）。
 *
 * 这正是 `AnchorCellsGrid` / `WatermarkZoneGrid` 用的顺序（也是 `WatermarkZones`
 * 的 `rowOf` / `columnOf` 的逆），所以画布上点一格写进去的 `anchor` 与旧面板九宫格
 * 写进去的值完全相同；点在照片外返回 null（不写）。
 */
fun watermarkAnchorAt(xFraction: Float, yFraction: Float): Int? {
    if (!xFraction.isFinite() || !yFraction.isFinite()) return null
    if (xFraction < 0f || xFraction > 1f || yFraction < 0f || yFraction > 1f) return null
    val col = (xFraction * 3f).toInt().coerceIn(0, 2)
    val row = (yFraction * 3f).toInt().coerceIn(0, 2)
    return row * 3 + col
}

/**
 * 画布上的九宫格锚点在不在：只有 水印 工具、并且真的有一层水印时才出现。
 * 其余任何工具（样式 / 文字 / Logo / 边框）都不画，也不吃手势。
 */
fun showsWatermarkAnchorLayer(tool: EditorTool, hasWatermark: Boolean): Boolean =
    tool == EditorTool.Watermark && hasWatermark

/**
 * 点一格之后写回的水印：**与旧面板九宫格同一个写入顺序** ——
 * 换格 → 两个方向的距离归零（每个格子从模型默认边距开始）→ 宽度超过新列上限时收到上限。
 *
 * 所以画布上点左上角写进去的 `WatermarkSpec`，与在旧面板九宫格里点同一格完全相同；
 * 写入通道也仍然是外壳的 `onWatermarkChange(spec)`。
 */
fun watermarkSpecForAnchor(spec: WatermarkSpec, anchor: Int): WatermarkSpec {
    val next = anchor.coerceIn(0, 8)
    val cap = WatermarkZones.widthCapFor(next)
    return spec.copy(
        anchor = next,
        dx = 0f,
        dy = 0f,
        width = if (spec.width > cap) cap else spec.width,
    )
}

// ---------------------------------------------------------------------------
// 8. 短标签（量到的行里没有省略号：放不下就换短文案）
// ---------------------------------------------------------------------------

/** Showcase `RELABEL`：inspector 的长标签在手机面板上的短标签。 */
fun phoneParamLabel(control: InspectorControl): String = when (control.label) {
    "显示标志" -> "显示 Logo"
    "标志位置" -> "Logo 位置"
    "字标缩放" -> "Logo 大小"
    "字标水平中心" -> "水平位置"
    "品牌标志" -> "Logo"
    "底色主题" -> "底色"
    "显示日期行" -> "显示日期"
    "整卡字号缩放" -> "整体文字大小"
    "页脚版式" -> "版式"
    "页脚自适应" -> "页脚自适应"
    "底部留白" -> "底部留白"
    else -> control.label
}

/** 分段选项的短文案（`classic / simple / overlay` → 经典 / 简约 / 叠加）。 */
fun phoneParamOptionLabel(display: String): String = when (display.lowercase()) {
    "classic" -> "经典"
    "simple" -> "简约"
    "overlay" -> "叠加"
    "left" -> "居左"
    "center" -> "居中"
    "right" -> "居右"
    else -> display
}

/** 分段选项的展示顺序（版式：经典 / 简约 / 叠加；Logo 位置：居左 / 居中 / 居右）。 */
fun phoneParamOptions(options: List<ControlOption>): List<ControlOption> {
    val order = listOf("classic", "simple", "overlay", "left", "center", "right")
    fun rank(value: String): Int = order.indexOf(value).let { if (it < 0) order.size else it }
    return options.sortedBy { rank(it.value) }.map { ControlOption(it.value, phoneParamOptionLabel(it.display)) }
}

/** 版式那一句说明（showcase `MODE_NOTE`）：跟着选中的版式变。 */
fun phoneParamModeNote(value: String): String = when (value) {
    "classic" -> "左侧品牌与机型，右侧参数与日期，中间放 Logo。"
    "simple" -> "单行居中：Logo · 品牌机型 · 参数 · 日期。"
    "overlay" -> "文字压在照片底部，画布不加高。"
    else -> ""
}

/** `InspectorPage` → 面板工具：样式页就是 样式 工具，其余一一对应。 */
fun phonePanelToolFor(page: InspectorPage): EditorTool = when (page) {
    InspectorPage.Style -> EditorTool.Params
    InspectorPage.Text -> EditorTool.Text
    InspectorPage.Logo -> EditorTool.Logo
    InspectorPage.Watermark -> EditorTool.Watermark
}
