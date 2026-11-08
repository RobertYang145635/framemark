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
import com.framemark.native.workflow.TextSlotPosition
import com.framemark.native.workflow.groupTextSlots
import com.framemark.native.workflow.isCurrentTextSlot
import com.framemark.native.workflow.probeBounds
import com.framemark.native.workflow.textSlotControls

/**
 * 文字页的选择式控件（用户 2026-11-07 口径）：
 *
 * 「文字页现在上下滑动很麻烦，想做成选择性控件：这个边框有左上、左下、右侧三个
 * 文本框，就做三个导航，点击一个修改一个；只有一个文本框时就只需要一个导航。」
 *
 * 数据一律来自渲染器报告的 [TextSlot]：位置分类与排序复用
 * [groupTextSlots]（`TextSlotPosition.row/column` + 组内 `y/x`），这里只做
 * **导航项**的展示规则 —— 位置名 + 语义副标签（品牌 / 机型 / 拍摄参数 / 日期，其
 * 次是同一位置内的序号）、只读标记、以及「选中哪一个」。桌面与手机共用同一套
 * 渲染（`dense` 只改尺寸与导航的换行方式），不各写一份。
 */
internal data class TextSlotSemantic(
    /** 演示稿的四行口径：品牌 / 机型 / 拍摄参数 / 日期。 */
    val label: String,
    /** 演示稿 `F[k][1]`：这一行的显示开关（`showMake/showModel/showExif/showDate`）。 */
    val showParam: String,
)

/** 一个可点选的文本框（导航项）。 */
internal data class TextSlotNavEntry(
    val slot: TextSlot,
    val position: TextSlotPosition?,
    /** `TextSlotPosition.label`，未定位时是「待定位」。 */
    val positionLabel: String,
    /** 同一位置多行时的语义副标签或序号。 */
    val subLabel: String?,
    /** 经典双行的显示开关参数；null 时用 `params.slotOff:<key>`。 */
    val showParam: String?,
    /** 引擎没有给出可写身份：能看、不能改。 */
    val readonly: Boolean,
    /** 从 1 开始的版面顺序。 */
    val order: Int,
) {
    /** 高亮 / 记忆用的稳定身份：优先渲染器 key，没有 key 的行退回报告 id。 */
    val navKey: String get() = slot.key ?: slot.id

    /** 导航 chip 与卡片标题同一口径：位置 + 角色（`左下 品牌`）。 */
    val label: String get() = if (subLabel.isNullOrBlank()) positionLabel else "$positionLabel $subLabel"
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

/** 单选式文字页的导航项，按版面阅读顺序（上→下、左→右）。 */
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
    val groups = groupTextSlots(slots, outputWidth, outputHeight, includeReadonly = true)
    val seen = HashSet<String>(groups.sumOf { it.rows.size })
    val entries = ArrayList<TextSlotNavEntry>(groups.sumOf { it.rows.size })
    var order = 0
    groups.forEach { group ->
        group.rows.forEach { row ->
            val slot = row.slot
            val navKey = slot.key ?: slot.id
            // 同一身份在报告里出现两次时只留第一行：两个导航项写同一个 key 会
            // 显示同一份值，用户点哪个都一样，反而是缺陷。
            if (!seen.add(navKey)) return@forEach
            val semantic = slot.key?.let(semantics::get)
            order += 1
            entries += TextSlotNavEntry(
                slot = slot,
                position = group.position,
                positionLabel = group.label,
                subLabel = semantic?.label ?: if (group.rows.size > 1) "第${row.order}行" else null,
                showParam = semantic?.showParam,
                readonly = !slot.isCurrentTextSlot(),
                order = order,
            )
        }
    }
    return entries
}

/** 只有多个文本框时才需要导航条；一个的时候直接给那张编辑卡。 */
internal fun showsTextSlotNavigation(entries: List<TextSlotNavEntry>): Boolean = entries.size > 1

/**
 * 导航条的行：**同一个位置的文本框排在一起**。wm-classic 这种「左下 2 + 右下 2」
 * 于是读成两行（= 两个位置导航），每行里逐个是那一行的文本框；每个位置只有一个
 * 文本框时每行一个，桌面上合成一排 chip。
 */
internal fun textSlotNavRows(entries: List<TextSlotNavEntry>): List<List<TextSlotNavEntry>> {
    val rows = ArrayList<MutableList<TextSlotNavEntry>>()
    entries.forEach { entry ->
        val last = rows.lastOrNull()
        if (last != null && last.first().position == entry.position) {
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
        println(
            "FM_TEXT_NAV card=$cardId boxes=${entries.size} canvas=${outputWidth}x${outputHeight} " +
                entries.joinToString(" ") { entry ->
                    val slot = entry.slot
                    "${entry.order}:${entry.navKey}[${entry.label}]" +
                        "${if (entry.readonly) "ro" else "rw"}" +
                        "@${slot.x.toInt()},${slot.y.toInt()},${slot.w.toInt()}x${slot.h.toInt()}"
                },
        )
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
            Spacer(Modifier.height(if (dense) FmRhythm.rowLoose else FmRhythm.row))
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
        // 桌面 4 个 chip 一条带子刚好放得下；手机保持触控间距。
        horizontalArrangement = Arrangement.spacedBy(
            if (dense) FmRhythm.inlineLoose else FmRhythm.inline,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ordered.forEach { entry ->
            TextSlotNavChip(
                entry = entry,
                // 位置 + 角色（`左下 品牌` / `右下 拍摄参数`）：两种密度同一个标签。
                label = entry.label,
                selected = entry.navKey == selected.navKey,
                modified = modifiedOf(entry),
                dense = dense,
                onSelect = { onSelect(entry) },
            )
        }
    }
}

/** 一个导航项：位置名 + 副标签；键盘 Tab 走得到，回车/空格切换。 */
@Composable
private fun TextSlotNavChip(
    entry: TextSlotNavEntry,
    label: String,
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
        append(entry.label)
        if (entry.readonly) append("，只读")
    }
    Row(
        modifier = Modifier
            // 触控档：手机 ≥36 dp、桌面 28 dp（次要控件，不撑高底板预算）。
            .heightIn(min = if (dense) 36.dp else 28.dp)
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
        Text(
            text = label,
            style = if (dense) FmType.labelMedium else TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
            color = when {
                entry.readonly && !selected -> FmPalette.onSurfaceFaint
                selected -> FmPalette.primaryStrong
                else -> FmPalette.onSurfaceSecondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.readonly) {
            Spacer(Modifier.width(FmRhythm.inline))
            Text(
                text = "只读",
                style = TextStyle(fontSize = 10.sp, lineHeight = 13.sp),
                color = FmPalette.onSurfaceFaint,
                maxLines = 1,
            )
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (dense) FmRhythm.row else FmRhythm.inlineLoose),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.label,
                style = if (dense) FmType.labelMedium else TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp),
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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
                        .semantics { contentDescription = "${entry.label}，只读" }
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
                        .semantics { contentDescription = entry.label }
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
        if (entry.readonly) {
            Spacer(Modifier.height(FmRhythm.inline))
            Text(
                text = "这一行只读：渲染器没有给出可写的身份。",
                style = if (dense) FmType.labelSmall else CuratedNote,
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
private fun TextSlotEye(visible: Boolean, label: String, onToggle: () -> Unit) {
    val ink = if (visible) FmPalette.onSurfaceSecondary else FmPalette.onSurfaceFaint
    Box(
        modifier = Modifier
            .size(28.dp)
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
