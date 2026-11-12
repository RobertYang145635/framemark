package com.framemark.native.inspector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmRhythm
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.ControlProbe
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.isCurrentTextSlot
import com.framemark.native.workflow.probeBounds
import com.framemark.native.workflow.textSlotControls
import com.framemark.native.product.FmLog

/**
 * 文字页的选择式控件（用户 2026-11-07 口径）：
 *
 * 「文字页现在上下滑动很麻烦，想做成选择性控件：这个边框有左上、左下、右侧三个
 * 文本框，就做三个导航，点击一个修改一个；只有一个文本框时就只需要一个导航。」
 *
 * 位置名按**边框内部的行列**来定（用户 2026-11-09 口径：「右侧有两个文本框，上面
 * 那个叫右上，下面那个叫右下，而不是根据图片来定位」）：
 *  - **列** = 文本框中心 x 相对边框画布宽度分 左 / 中 / 右（<1/3、1/3–2/3、>2/3）；
 *  - **行** = 同一列内按 y 排：两个 → 上 / 下；三个及以上 → 上 / 中 / 下；只有一个
 *    时按它自己的高度带（上/中/下三分之一）取 上 / 中 / 下；
 *  - 标签 = 列 + 行（`左上` `左下` `右上` `右下` `中上`）；同列只有一行且落在中部
 *    时不写「中中」，用现有词表里的「居中」；
 *  - 角色（品牌 / 机型 / 拍摄参数 / 日期）是导航 chip 的**第二行小字**，不进主标签；
 *  - 顺序 = 列（左→右）→ 行（上→下）。
 *
 * 舞台高亮用不到名字，所以 `TextSlotPosition` 的九宫格只在别处（旧的声明行列表、
 * 分组数据）保留，不再产出文字页的标签。
 */
internal data class TextSlotSemantic(
    /** 演示稿的四行口径：品牌 / 机型 / 拍摄参数 / 日期。 */
    val label: String,
    /** 演示稿 `F[k][1]`：这一行的显示开关（`showMake/showModel/showExif/showDate`）。 */
    val showParam: String,
)

/**
 * 一个文本框在**边框内部**的行列坐标（0 = 上/左，2 = 下/右）。
 *
 * [column] / [row] 为 null 表示报告没给可信矩形（位置名退回「待定位」）。
 */
internal data class TextSlotAxis(
    val column: Int?,
    val row: Int?,
) {
    val located: Boolean get() = column != null && row != null
}

private val AxisColumnLabels = listOf("左", "中", "右")
private val AxisRowLabels = listOf("上", "中", "下")

private fun TextSlot.hasUsableRect(): Boolean =
    located && w > 0.0 && h > 0.0 &&
        x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite()

/** 列的判定：文本框**中心 x** 相对边框画布宽度的三分。 */
internal fun textSlotColumn(slot: TextSlot, outputWidth: Int): Int? {
    if (outputWidth <= 0 || !slot.hasUsableRect()) return null
    val centerX = (slot.x + slot.w / 2.0) / outputWidth
    return when {
        centerX < 1.0 / 3.0 -> 0
        centerX < 2.0 / 3.0 -> 1
        else -> 2
    }
}

/** 单行时的高度带：框中心 y 相对画布高度的上/中/下三分之一。 */
private fun textSlotHeightBand(slot: TextSlot, outputHeight: Int): Int? {
    if (outputHeight <= 0 || !slot.hasUsableRect()) return null
    val centerY = (slot.y + slot.h / 2.0) / outputHeight
    return when {
        centerY < 1.0 / 3.0 -> 0
        centerY < 2.0 / 3.0 -> 1
        else -> 2
    }
}

/**
 * 每个文本框在边框内部的 [TextSlotAxis]。关键在**行**：同一列内先按 y 排序，
 * 两行 → 上/下、三行及以上 → 上/中/下、只有一行 → 它自己的高度带。
 */
internal fun textSlotAxes(
    slots: List<TextSlot>,
    outputWidth: Int,
    outputHeight: Int,
): Map<String, TextSlotAxis> {
    val byKey = LinkedHashMap<String, TextSlot>(slots.size)
    slots.forEach { byKey[it.key ?: it.id] = it }
    val preliminary = LinkedHashMap<String, TextSlotAxis>(byKey.size)
    byKey.forEach { (navKey, slot) ->
        val column = textSlotColumn(slot, outputWidth)
        preliminary[navKey] = TextSlotAxis(column = column, row = null)
    }
    // 每一列单独排行：行号只和同列的邻居比，跟整张画布无关。
    for (column in 0..2) {
        val members = byKey
            .filter { (navKey, _) -> preliminary[navKey]?.column == column }
            .toList()
            .sortedWith(compareBy<Pair<String, TextSlot>>({ it.second.y }, { it.second.x }, { it.first }))
        if (members.isEmpty()) continue
        val rows = when (members.size) {
            1 -> listOf(textSlotHeightBand(members[0].second, outputHeight) ?: 0)
            2 -> listOf(0, 2)
            else -> members.indices.map { index ->
                when (index) {
                    0 -> 0
                    members.lastIndex -> 2
                    else -> 1
                }
            }
        }
        members.forEachIndexed { index, pair ->
            preliminary[pair.first] = TextSlotAxis(column = column, row = rows[index])
        }
    }
    return preliminary
}

/** 列 + 行的位置名；未定位的行叫「待定位」。 */
internal fun textSlotAxisLabel(axis: TextSlotAxis): String {
    if (!axis.located) return "待定位"
    val column = AxisColumnLabels[axis.column!!]
    val row = AxisRowLabels[axis.row!!]
    // 「中中」不是人话：同列单行又落在中部时用现有词表的「居中」。
    return if (column == "中" && row == "中") "居中" else column + row
}

/** 一个可点选的文本框（导航项）。 */
internal data class TextSlotNavEntry(
    val slot: TextSlot,
    /** 边框内部的列 / 行（null = 报告没有可信矩形）。 */
    val axis: TextSlotAxis,
    /** 列 + 行的位置名（`左上` / `右下` / `待定位` …）。 */
    val positionLabel: String,
    /** 角色：经典双行是 品牌 / 机型 / 拍摄参数 / 日期，其他卡为 null。 */
    val role: String?,
    /** 经典双行的显示开关参数；null 时用 `params.slotOff:<key>`。 */
    val showParam: String?,
    /** 引擎没有给出可写身份：能看、不能改。 */
    val readonly: Boolean,
    /** 从 1 开始的版面顺序（列→行）。 */
    val order: Int,
) {
    /** 高亮 / 记忆用的稳定身份：优先渲染器 key，没有 key 的行退回报告 id。 */
    val navKey: String get() = slot.key ?: slot.id

    /** 主标签就是位置名；角色是第二行小字，不拼进来。 */
    val label: String get() = positionLabel

    /** 无障碍 / 探针用的一句话：`左上 品牌`。 */
    val fullLabel: String get() = if (role.isNullOrBlank()) positionLabel else "$positionLabel $role"
}

/** 经典双行四行的语义文案，按 slot key 索引（其他卡为空表）。 */
internal fun classicTextSemantics(
    slots: List<TextSlot>,
    widthPx: Int,
    heightPx: Int,
): Map<String, TextSlotSemantic> =
    classicTextRows(CuratedTextSlots(slots, widthPx, heightPx))
        .mapNotNull { row -> row.slot.key?.let { key -> key to TextSlotSemantic(row.label, row.showParam) } }
        .toMap()

/** 单选式文字页的导航项，按「列（左→右）→ 行（上→下）」排。 */
internal fun textSlotNavEntries(
    slots: List<TextSlot>,
    outputWidth: Int,
    outputHeight: Int,
): List<TextSlotNavEntry> = textSlotNavEntries(
    slots = slots,
    outputWidth = outputWidth,
    outputHeight = outputHeight,
    semantics = classicTextSemantics(slots, outputWidth, outputHeight),
)

internal fun textSlotNavEntries(
    slots: List<TextSlot>,
    outputWidth: Int,
    outputHeight: Int,
    semantics: Map<String, TextSlotSemantic>,
): List<TextSlotNavEntry> {
    // 与旧的声明行列表同一套「这一帧算不算一个文本框」判据：可写身份 or 画出来/显式隐藏。
    val eligible = slots
        .filter { it.isCurrentTextSlot() || it.present || it.hidden }
        .distinctBy { it.key ?: it.id }
    if (eligible.isEmpty()) return emptyList()
    val axes = textSlotAxes(eligible, outputWidth, outputHeight)
    // 列（左→右）→ 行（上→下）→ 同格再按 y/x 兜底；未定位的排最后。
    val ordered = eligible.sortedWith(
        compareBy<TextSlot>(
            { axes[it.key ?: it.id]?.column ?: Int.MAX_VALUE },
            { axes[it.key ?: it.id]?.row ?: Int.MAX_VALUE },
            { it.y },
            { it.x },
            { it.key.orEmpty() },
        ),
    )
    return ordered.mapIndexed { index, slot ->
        val navKey = slot.key ?: slot.id
        val axis = axes[navKey] ?: TextSlotAxis(null, null)
        val semantic = slot.key?.let(semantics::get)
        TextSlotNavEntry(
            slot = slot,
            axis = axis,
            positionLabel = textSlotAxisLabel(axis),
            role = semantic?.label,
            showParam = semantic?.showParam,
            readonly = !slot.isCurrentTextSlot(),
            order = index + 1,
        )
    }
}

/** 只有多个文本框时才需要导航条；一个的时候直接给那张编辑卡。 */
internal fun showsTextSlotNavigation(entries: List<TextSlotNavEntry>): Boolean = entries.size > 1

/**
 * 导航项按**列**分组（左→右）：给导航带排序，也是舞台高亮「按列」分组的口径。
 * 导航带本身是一条平铺的 chip 横带，不再拿它当视觉层级。
 */
internal fun textSlotNavRows(entries: List<TextSlotNavEntry>): List<List<TextSlotNavEntry>> {
    val rows = ArrayList<MutableList<TextSlotNavEntry>>()
    entries.forEach { entry ->
        val last = rows.lastOrNull()
        if (last != null && last.first().axis.column == entry.axis.column) {
            last += entry
        } else {
            rows += mutableListOf(entry)
        }
    }
    return rows
}

/** 选中的文本框：保存的 key 还在就沿用它，否则回到第一个（切边框 / 换照片）。 */
internal fun resolveTextSlotSelection(
    entries: List<TextSlotNavEntry>,
    savedKey: String?,
): TextSlotNavEntry? = entries.firstOrNull { it.navKey == savedKey } ?: entries.firstOrNull()

/** 文字页该给哪一种版式。 */
internal enum class TextPageMode {
    /** 有渲染器报告的文本框：导航 + 单卡。 */
    Selector,

    /** 没有报告（这个边框不是实时渲染的）：保留声明出来的文字行。 */
    StaticRows,

    /** 报告到了但一个文本框都没有：空态。 */
    Empty,
}

internal fun textPageMode(
    hasReport: Boolean,
    entries: List<TextSlotNavEntry>,
    staticTextControls: List<InspectorControl>,
): TextPageMode = when {
    entries.isNotEmpty() -> TextPageMode.Selector
    hasReport -> TextPageMode.Empty
    staticTextControls.isNotEmpty() -> TextPageMode.StaticRows
    else -> TextPageMode.Empty
}

/**
 * 「文字大小」（`fontScale`）的副说明。四行口径沿用演示稿，其他数量说人话。
 */
internal fun textScaleHint(boxCount: Int): String =
    if (boxCount == 4) "四行同比缩放" else "所有文本框同比缩放"

/**
 * 选中的文本框，按 **卡片 id**（`rememberSaveable` 的输入）记住 slot key。
 *
 * 卡片换了 → 输入变了 → 状态回到 null → 选中第一个（brief 的「切换边框时回到
 * 第一个」）。状态由宿主（`CuratedPage` / `PhoneParameterPage`）持有，所以切页签
 * 回来仍然是同一个文本框；只在文字页内部时由 [TextSlotSelector] 自带的兜底持有。
 */
@Stable
internal class TextSlotSelectionState internal constructor(
    private val saved: MutableState<String?>,
) {
    var selectedKey: String?
        get() = saved.value
        set(value) {
            saved.value = value
        }
}

@Composable
internal fun rememberTextSlotSelectionState(
    cardId: String?,
    slotSignature: String = "",
): TextSlotSelectionState {
    val saved = rememberSaveable(cardId.orEmpty(), slotSignature) { mutableStateOf<String?>(null) }
    return remember(saved) { TextSlotSelectionState(saved) }
}

/** 渲染器给的文本框一个都没有（也没有声明行）时的空态。 */
@Composable
internal fun TextSlotEmptyPane(dense: Boolean, modifier: Modifier = Modifier) {
    if (!dense) {
        CuratedEmptyPane(
            title = "这个边框没有可编辑文字",
            body = "文字和位置由厂商规格固定。",
        )
        return
    }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("这个边框没有可编辑文字", style = FmType.titleSmall, color = FmPalette.onSurface, maxLines = 2)
        Spacer(Modifier.height(FmRhythm.inlineLoose))
        Text("文字和位置由厂商规格固定。", style = FmType.bodyMedium, color = FmPalette.onSurfaceMuted, maxLines = 3)
    }
}

/**
 * 导航条 + 单个编辑卡。桌面（`dense = false`）导航换行，手机（`dense = true`）横向
 * 可滚、卡片紧凑。
 */
@Composable
internal fun TextSlotSelector(
    cardId: String,
    slots: List<TextSlot>,
    outputWidth: Int,
    outputHeight: Int,
    controls: List<InspectorControl>,
    session: DesignSession,
    dense: Boolean,
    modifier: Modifier = Modifier,
    /** 宿主保存的选择；null 时本组件自己记住（页签内有效）。 */
    selection: TextSlotSelectionState? = null,
    onTextSlotFocus: ((String?) -> Unit)? = null,
) {
    val entries = remember(slots, outputWidth, outputHeight) {
        textSlotNavEntries(slots, outputWidth, outputHeight)
    }
    // 取证：这一页给了几个导航项、按什么顺序、每个是哪个身份。内容签名变了才打一行
    // （每次渲染报告都会给一份新 list，按对象身份会让同一页重复打印）。
    val navSignature = remember(entries) {
        entries.joinToString("|") { "${it.navKey}#${it.label}#${it.readonly}" }
    }
    LaunchedEffect(navSignature) {
        FmLog.info("FM_TEXT_NAV") {
            "card=$cardId boxes=${entries.size} canvas=${outputWidth}x${outputHeight} " +
                entries.joinToString(" ") { entry ->
                    val slot = entry.slot
                    "${entry.order}:${entry.navKey}[${entry.fullLabel}]" +
                        "${if (entry.readonly) "ro" else "rw"}" +
                        "@${slot.x.toInt()},${slot.y.toInt()},${slot.w.toInt()}x${slot.h.toInt()}"
                }
        }
    }
    val fallback = rememberTextSlotSelectionState(
        cardId = cardId,
        slotSignature = entries.joinToString("|", transform = TextSlotNavEntry::navKey),
    )
    val state = selection ?: fallback
    val selected = resolveTextSlotSelection(entries, state.selectedKey)
    if (selected == null) {
        TextSlotEmptyPane(dense = dense, modifier = modifier)
        return
    }
    // 选中即高亮：桌面走 `highlightedTextSlotKey`，手机走同一个 `focusedTextSlotKey`
    // 链路（两边都是 NativeStagePreview 的同一个高亮框）。只读行没有可写 key，用报告
    // id 兜底。`FM_CONTROL_PROBE` 下再报一次「现在选的是哪一个」，给脚本取证用。
    LaunchedEffect(selected.navKey) {
        ControlProbe.state(
            key = "text-slot-nav:$cardId",
            value = selected.navKey,
            type = if (selected.readonly) "readonly" else "editable",
        )
        onTextSlotFocus?.invoke(selected.navKey)
    }
    val textControls = remember(entries, cardId, controls) {
        entries.associate { entry -> entry.navKey to editableTextControl(cardId, entry.slot, controls) }
    }
    val canvas = CuratedTextSlots(slots, outputWidth, outputHeight)
    Column(modifier = modifier.fillMaxWidth()) {
        if (showsTextSlotNavigation(entries)) {
            TextSlotNavBar(
                entries = entries,
                selected = selected,
                dense = dense,
                modifiedOf = { entry ->
                    val control = textControls[entry.navKey]
                    control != null && session.isModified(control)
                },
                onSelect = { entry -> state.selectedKey = entry.navKey },
            )
            Spacer(Modifier.height(if (dense) FmRhythm.inlineWide else FmRhythm.row))
        }
        TextSlotEditCard(
            entry = selected,
            boxes = slots,
            canvas = canvas,
            cardId = cardId,
            controls = controls,
            session = session,
            dense = dense,
        )
    }
}

/**
 * `params.slotText:<key>` 的写入口。模型里带着就用模型那一条（它可能挂了
 * `disabledReason`），否则按同一套生成规则现造 —— key 是确定性的，两边等价。
 */
private fun editableTextControl(
    cardId: String,
    slot: TextSlot,
    controls: List<InspectorControl>,
): InspectorControl? {
    val key = slot.key?.takeIf { slot.isCurrentTextSlot() } ?: return null
    val param = "params.slotText:$key"
    return controls.firstOrNull { it.param == param }
        ?: textSlotControls(cardId, slot).firstOrNull { it.param == param }
}

private fun slotVisibilityControls(
    cardId: String,
    entry: TextSlotNavEntry,
    controls: List<InspectorControl>,
): Pair<InspectorControl?, InspectorControl?> {
    val showParam = entry.showParam
    val showSwitch = if (showParam == null) {
        null
    } else {
        controls.firstOrNull { control ->
            control.type == ControlType.Switch &&
                (control.param == showParam || control.param.endsWith(".$showParam"))
        }
    }
    val offSwitch = entry.slot.key?.takeIf { entry.slot.isCurrentTextSlot() }?.let { key ->
        val param = "params.slotOff:$key"
        controls.firstOrNull { it.param == param }
            ?: textSlotControls(cardId, entry.slot).firstOrNull { it.param == param }
    }
    return showSwitch to offSwitch
}

/**
 * 导航带：**一个文本框一个 chip**（用户原话「三个文本框就做三个导航」），一条可换行
 * 的横带，放不下整条横向滚动（不折成两条，避免高度抖动）。顺序仍是版面阅读顺序
 * （`TextSlotPosition.row/column` 再按 `y/x`），由 [textSlotNavRows] 给出。
 */
@Composable
private fun TextSlotNavBar(
    entries: List<TextSlotNavEntry>,
    selected: TextSlotNavEntry,
    dense: Boolean,
    modifiedOf: (TextSlotNavEntry) -> Boolean,
    onSelect: (TextSlotNavEntry) -> Unit,
) {
    val ordered = remember(entries) { textSlotNavRows(entries).flatten() }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        // 桌面 4 个 chip 一条带子刚好放得下；chip↔chip 走 FmRhythm.inlineWide（TY1 定义
        // 就是「两个可点元素之间的最小间隙」），手机再用一档触控间距。
        horizontalArrangement = Arrangement.spacedBy(
            if (dense) FmRhythm.inlineLoose else FmRhythm.inlineWide,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ordered.forEach { entry ->
            TextSlotNavChip(
                entry = entry,
                selected = entry.navKey == selected.navKey,
                modified = modifiedOf(entry),
                dense = dense,
                onSelect = { onSelect(entry) },
            )
        }
    }
}

/**
 * 一个导航项：**第一行位置名**（`左上` / `右下`），**第二行角色小字**（品牌 / 机型 /
 * 拍摄参数 / 日期；只读行再标一个「只读」）。键盘 Tab 走得到，回车/空格切换。
 */
@Composable
private fun TextSlotNavChip(
    entry: TextSlotNavEntry,
    selected: Boolean,
    modified: Boolean,
    dense: Boolean,
    onSelect: () -> Unit,
) {
    val shape = RoundedCornerShape(FmRadii.chip)
    val description = buildString {
        append("文本框 ")
        append(entry.order)
        append("：")
        append(entry.fullLabel)
        if (entry.readonly) append("，只读")
    }
    val mainColor = when {
        entry.readonly && !selected -> FmPalette.onSurfaceFaint
        selected -> FmPalette.primaryStrong
        else -> FmPalette.onSurfaceSecondary
    }
    Row(
        modifier = Modifier
            // 两行小字：桌面 36 dp、手机 44 dp（触控档，不撑高底板预算）；
            // 最小宽度让「右上/拍摄参数」这类四字角色不被挤扁。
            .heightIn(min = if (dense) 44.dp else 36.dp)
            .widthIn(min = if (dense) 72.dp else 64.dp)
            .clip(shape)
            .probeBounds("text-slot-nav:${entry.navKey}")
            .background(if (selected) FmPalette.primarySoft else FmPalette.surfaceMuted)
            .border(1.dp, if (selected) FmPalette.primary else FmPalette.outline, shape)
            .clickable(role = Role.RadioButton, onClick = onSelect)
            .semantics {
                contentDescription = description
                this.selected = selected
                stateDescription = if (selected) "已选中" else "未选中"
            }
            .padding(horizontal = if (dense) FmRhythm.rowLoose else FmRhythm.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (modified) {
            Box(Modifier.size(FmRhythm.inline).clip(CircleShape).background(FmPalette.primary))
            Spacer(Modifier.width(FmRhythm.inlineLoose))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = entry.label,
                style = if (dense) FmType.labelMedium else TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                color = mainColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = when {
                entry.readonly && entry.role.isNullOrBlank() -> "只读"
                entry.readonly -> "${entry.role} · 只读"
                else -> entry.role
            }
            if (!sub.isNullOrBlank()) {
                Text(
                    text = sub,
                    style = if (dense) {
                        FmType.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 13.sp)
                    } else {
                        TextStyle(fontSize = 10.sp, lineHeight = 13.sp)
                    },
                    color = if (selected) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 单卡：输入框（占位 = 原文）+「已改 / 恢复原文」+ 隐藏开关 + 位置示意。
 * 只读行不出输入框：给一段只读文字和一句「改不了」的说明，不假装可编辑。
 */
@Composable
private fun TextSlotEditCard(
    entry: TextSlotNavEntry,
    boxes: List<TextSlot>,
    canvas: CuratedTextSlots,
    cardId: String,
    controls: List<InspectorControl>,
    session: DesignSession,
    dense: Boolean,
) {
    val slot = entry.slot
    val raw = slot.raw.ifBlank { slot.text }
    val textControl = editableTextControl(cardId, slot, controls)
    val (showControl, offControl) = slotVisibilityControls(cardId, entry, controls)
    val modified = textControl != null && session.isModified(textControl)
    val hidden = when {
        showControl != null -> (session.value(showControl) as? Boolean)?.not() ?: false
        offControl != null -> (session.value(offControl) as? Boolean)?.not() ?: slot.hidden
        else -> slot.hidden
    }
    // 由整体文字显示开关控制的行（例如 showDate 关掉的那一行）：这里放一个开关也
    // 只会看起来像坏的，直接说清楚谁在管。
    val controlledElsewhere = showControl == null && offControl != null &&
        slot.hidden && (session.value(offControl) as? Boolean != false)
    val override = textControl?.let { (session.value(it) as? String).orEmpty() }.orEmpty()
    var text by remember(textControl?.key, entry.navKey) { mutableStateOf(override) }
    if (dense) {
        // 2026-11 密度过：标签 / 输入 / 眼睛（与「已改」时的恢复）挤进**一行 44 dp**。
        // 原来标签行 + 输入行是 72 dp；文字页因此从「选择器一页 + 大小一页」变成一页。
        // 触控目标没有变小：眼睛与恢复都是 44 × 44，输入框 34 高在 44 的行里。
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = FmDenseRowHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (modified) {
                Box(
                    Modifier
                        .padding(end = 4.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(FmPalette.primary),
                )
            }
            Column(Modifier.widthIn(max = 96.dp)) {
                Text(
                    text = entry.label,
                    style = FmType.labelMedium,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!entry.role.isNullOrBlank()) {
                    Text(
                        text = entry.role,
                        style = FmType.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                        color = FmPalette.onSurfaceFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(FmRhythm.inlineLoose))
            if (textControl == null) {
                Text(
                    text = ExifGapNotice.rowSummary(slot.text, slot.raw),
                    style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                    color = FmPalette.onSurfaceSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 34.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(FmPalette.surfaceMuted)
                        .semantics { contentDescription = "${entry.fullLabel}，只读" }
                        .padding(
                            horizontal = FmRhythm.inlineLoose,
                            vertical = FmRhythm.inlineLoose,
                        ),
                )
            } else {
                BasicTextField(
                    value = text,
                    onValueChange = { next ->
                        text = next
                        if (next == raw) session.resetControl(textControl) else session.set(textControl, next)
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = if (hidden) FmPalette.onSurfaceFaint else FmPalette.onSurface,
                        textDecoration = if (hidden) TextDecoration.LineThrough else null,
                    ),
                    cursorBrush = SolidColor(FmPalette.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 34.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(FmPalette.surface)
                        .semantics { contentDescription = entry.fullLabel }
                        .padding(
                            horizontal = FmRhythm.inlineLoose,
                            vertical = FmRhythm.inlineLoose,
                        ),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    text = raw.ifBlank { "（空）" },
                                    style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                                    color = FmPalette.onSurfaceFaint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            if (entry.readonly) {
                Text(
                    text = "只读",
                    style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
                    color = FmPalette.onSurfaceFaint,
                    modifier = Modifier.padding(start = FmRhythm.inlineLoose),
                )
            } else if (textControl != null && modified) {
                Box(
                    modifier = Modifier
                        .size(FmDenseRowHeight)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .clickable(role = Role.Button) {
                            session.resetControl(textControl)
                            text = ""
                        }
                        .semantics { contentDescription = "恢复原文" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "↺",
                        style = TextStyle(fontSize = 15.sp, lineHeight = 18.sp),
                        color = FmPalette.primaryStrong,
                    )
                }
            }
            when {
                (showControl != null || offControl != null) && !controlledElsewhere ->
                    TextSlotEye(
                        visible = !hidden,
                        label = entry.label,
                        dense = true,
                        onToggle = {
                            when {
                                showControl != null ->
                                    session.set(showControl, !(session.value(showControl) as? Boolean ?: true))
                                offControl != null ->
                                    session.set(offControl, !(session.value(offControl) as? Boolean ?: true))
                            }
                        },
                    )
                controlledElsewhere -> Text(
                    text = "全局控制",
                    style = TextStyle(fontSize = 10.5.sp, lineHeight = 13.sp),
                    color = FmPalette.onSurfaceFaint,
                    modifier = Modifier.padding(start = FmRhythm.inlineLoose),
                )
            }
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FmRhythm.inlineLoose),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.label,
                    style = if (dense) FmType.labelMedium else TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp),
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                )
                if (!entry.role.isNullOrBlank()) {
                    Spacer(Modifier.width(FmRhythm.inline))
                    Text(
                        text = entry.role,
                        style = if (dense) {
                            FmType.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp)
                        } else {
                            TextStyle(fontSize = 11.sp, lineHeight = 15.sp)
                        },
                        color = FmPalette.onSurfaceFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (entry.readonly) {
                Text(
                    text = "只读",
                    style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
                    color = FmPalette.onSurfaceFaint,
                )
            } else if (textControl != null && modified) {
                Text(
                    text = "已改",
                    style = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp),
                    color = FmPalette.primaryStrong,
                )
                Spacer(Modifier.width(FmRhythm.inlineLoose))
                Text(
                    text = "恢复原文",
                    style = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp),
                    color = FmPalette.primaryStrong,
                    modifier = Modifier
                        .heightIn(min = if (dense) 28.dp else 22.dp)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .clickable(role = Role.Button) {
                            session.resetControl(textControl)
                            text = ""
                        }
                        .padding(horizontal = FmRhythm.inline),
                )
            }
        }
        Spacer(Modifier.height(if (dense) FmRhythm.inlineLoose else FmRhythm.inline))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextSlotDiagram(boxes = boxes, current = slot, canvas = canvas)
            Spacer(Modifier.width(FmRhythm.rowLoose))
            if (textControl == null) {
                Text(
                    text = ExifGapNotice.rowSummary(slot.text, slot.raw),
                    style = TextStyle(
                        fontSize = if (dense) 15.sp else 13.sp,
                        lineHeight = if (dense) 22.sp else 18.sp,
                    ),
                    color = FmPalette.onSurfaceSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = if (dense) 34.dp else 28.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(FmPalette.surfaceMuted)
                        .semantics { contentDescription = "${entry.fullLabel}，只读" }
                        .padding(
                            horizontal = FmRhythm.inlineLoose,
                            vertical = if (dense) FmRhythm.inlineLoose else FmRhythm.inline,
                        ),
                )
            } else {
                BasicTextField(
                    value = text,
                    onValueChange = { next ->
                        text = next
                        // 输入回到原文就删掉覆盖键，与演示稿 `v == orig ? null : v` 一致。
                        if (next == raw) session.resetControl(textControl) else session.set(textControl, next)
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = if (dense) 15.sp else 13.sp,
                        lineHeight = if (dense) 22.sp else 18.sp,
                        color = if (hidden) FmPalette.onSurfaceFaint else FmPalette.onSurface,
                        textDecoration = if (hidden) TextDecoration.LineThrough else null,
                    ),
                    cursorBrush = SolidColor(FmPalette.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = if (dense) 34.dp else 26.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(FmPalette.surface)
                        .semantics { contentDescription = entry.fullLabel }
                        .padding(
                            horizontal = FmRhythm.inlineLoose,
                            vertical = if (dense) FmRhythm.inlineLoose else FmRhythm.inline,
                        ),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    text = raw.ifBlank { "（空）" },
                                    style = TextStyle(
                                        fontSize = if (dense) 15.sp else 13.sp,
                                        lineHeight = if (dense) 22.sp else 18.sp,
                                    ),
                                    color = FmPalette.onSurfaceFaint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            if (!entry.readonly && (showControl != null || offControl != null) && !controlledElsewhere) {
                Spacer(Modifier.width(FmRhythm.row))
                TextSlotEye(
                    visible = !hidden,
                    label = entry.label,
                    onToggle = {
                        when {
                            showControl != null ->
                                session.set(showControl, !(session.value(showControl) as? Boolean ?: true))
                            offControl != null ->
                                session.set(offControl, !(session.value(offControl) as? Boolean ?: true))
                        }
                    },
                )
            }
        }
        // 手机底板一页一组的预算只有 150 dp，行头已经有「只读」这个 chip，再单独占
        // 一行说明就会把这一块顶出预算；桌面（dense = false）保留整句。
        if (entry.readonly && !dense) {
            Spacer(Modifier.height(FmRhythm.inline))
            Text(
                text = "这一行只读：渲染器没有给出可写的身份。",
                style = CuratedNote,
                color = FmPalette.onSurfaceFaint,
                maxLines = 2,
            )
        } else if (controlledElsewhere) {
            Spacer(Modifier.height(FmRhythm.inline))
            Text(
                text = "当前行由整体文字显示开关控制",
                style = if (dense) FmType.labelSmall else CuratedNote,
                color = FmPalette.onSurfaceMuted,
                maxLines = 2,
            )
        }
    }
}

/** `.trow .pos` — 26 × 20 的版式示意图：当前行高亮，其余行淡显。 */
@Composable
private fun TextSlotDiagram(
    boxes: List<TextSlot>,
    current: TextSlot,
    canvas: CuratedTextSlots,
) {
    val line = FmPalette.onSurfaceFaint
    val accent = FmPalette.primary
    Canvas(Modifier.width(26.dp).height(20.dp)) {
        val stroke = 1.dp.toPx()
        drawRoundRect(
            color = line.copy(alpha = 0.5f),
            cornerRadius = CornerRadius(2.2.dp.toPx()),
            style = Stroke(width = stroke),
        )
        if (canvas.widthPx <= 0 || canvas.heightPx <= 0) return@Canvas
        boxes.forEach { box ->
            if (!box.located || box.w <= 0.0 || box.h <= 0.0) return@forEach
            val x = (box.x / canvas.widthPx).toFloat().coerceIn(0f, 1f) * size.width
            val y = (box.y / canvas.heightPx).toFloat().coerceIn(0f, 1f) * size.height
            val w = (box.w / canvas.widthPx).toFloat().coerceIn(0f, 1f) * size.width
            val h = (box.h / canvas.heightPx).toFloat().coerceIn(0f, 1f) * size.height
            val highlight = box.id == current.id
            drawRoundRect(
                color = if (highlight) accent else line.copy(alpha = 0.32f),
                topLeft = Offset(x, y),
                size = Size(w.coerceAtLeast(1.4.dp.toPx()), h.coerceAtLeast(1.1.dp.toPx())),
                cornerRadius = CornerRadius(0.45.dp.toPx()),
            )
        }
    }
}

/** `.trow .eye` — 15 px 眼睛；隐藏态加一道斜线，语义上是开关。 */
@Composable
private fun TextSlotEye(
    visible: Boolean,
    label: String,
    onToggle: () -> Unit,
    /** 手机密度档：44 × 44 的触控格（桌面 28）。 */
    dense: Boolean = false,
) {
    val ink = if (visible) FmPalette.onSurfaceSecondary else FmPalette.onSurfaceFaint
    Box(
        modifier = Modifier
            .size(if (dense) FmDenseRowHeight else 28.dp)
            .clip(RoundedCornerShape(FmRadii.chip))
            .clickable(role = Role.Switch, onClick = onToggle)
            .semantics {
                contentDescription = if (visible) "隐藏$label" else "显示$label"
                stateDescription = if (visible) "显示中" else "已隐藏"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val stroke = 1.1.dp.toPx()
            drawOval(
                color = ink,
                topLeft = Offset(stroke, size.height * 0.22f),
                size = Size(size.width - stroke * 2, size.height * 0.56f),
                style = Stroke(width = stroke),
            )
            drawCircle(color = ink, radius = size.height * 0.11f, center = center)
            if (!visible) {
                drawLine(ink, Offset(stroke, size.height - stroke), Offset(size.width - stroke, stroke), stroke)
            }
        }
    }
}
