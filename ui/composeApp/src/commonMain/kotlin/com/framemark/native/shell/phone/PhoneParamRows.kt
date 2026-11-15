package com.framemark.native.shell.phone

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.framemark.native.inspector.ControlOption
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.FmSlider
import com.framemark.native.inspector.FmSliderDragBubble
import com.framemark.native.inspector.FmTouchBubbleGap
import com.framemark.native.inspector.FmTouchBubbleHeight
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.fractionOf
import com.framemark.native.shell.PHONE_PANEL_TAB_ROW_DP
import com.framemark.native.shell.phoneGroupTabRowVisible
import com.framemark.native.shell.PHONE_PARAM_LABEL_LINE_DP
import com.framemark.native.shell.PHONE_PARAM_LABEL_SP
import com.framemark.native.shell.PHONE_PARAM_ROW_MIN_TOUCH_DP
import com.framemark.native.shell.PHONE_PARAM_TRACK_LINE_DP
import com.framemark.native.shell.PHONE_PARAM_VALUE_SP
import com.framemark.native.shell.PhoneParamGroup
import com.framemark.native.shell.phoneParamOptionLabel
import com.framemark.native.shell.phoneParamOptions
import com.framemark.native.theme.FmDuration
import com.framemark.native.theme.FmEasing
import com.framemark.native.theme.FmMotionRules
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmRhythm
import com.framemark.native.theme.LocalFmMotionScale
import com.framemark.native.theme.fmTweenSpec
import com.framemark.native.workflow.DesignSession
import kotlinx.coroutines.delay
import com.framemark.native.workflow.trackTextEditing

/**
 * 固定画框里的参数行（2026-11-10，照 Lightroom 手机版量出来的两行结构）。
 *
 * 一行 = 第一行「标签 15 sp 在左、数值 15 sp 在右」，第二行「整宽轨道」。数值可点
 * （输入精确值）、可双击（回默认）；轨道就是整条内容宽（411 dp 屏 = 379 dp ≥ 85 %）。
 *
 * 这里**没有**「两条滑杆一行」的形态：量到的行是整宽的，并排会让轨道掉到 50 % 以下。
 * 开关 / 分段 / 色板 / 文本这些非数值行也照同一套两行节奏排（标签行 + 控件行），
 * 所以面板从一行到下一行的节奏是统一的。
 */

private val LabelLine = PHONE_PARAM_LABEL_LINE_DP.dp
private val TrackLine = PHONE_PARAM_TRACK_LINE_DP.dp

/**
 * 一条数值参数的两行行：标签 + 数值（点 = 输入精确值，双击 = 回默认）+ 整宽轨道。
 *
 * [value] / [range] / [defaultValue] 都是**显示单位**（百分比、度数、× 倍），
 * [onValueChange] 也收显示单位；换算由调用方（政策层）负责，这一层只画。
 */
@Composable
fun PhoneSliderParamRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    defaultValue: Float?,
    step: Float?,
    unit: String?,
    decimals: Int,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 置灰时下面那句原因（「只有位置 = 居中时水平位置才生效」之类）。 */
    disabledReason: String? = null,
    /** 拖动气泡与无障碍读出来的文本。 */
    valueText: String = phoneParamNumberText(value, decimals, unit),
    onReset: (() -> Unit)? = null,
) {
    val min = range.start
    val max = range.endInclusive
    val clamped = if (value.isFinite()) value.coerceIn(min, max) else min
    var editing by remember { mutableStateOf(false) }
    // 拖动气泡：显示值每帧跟手，写回按 100 ms 封顶并在松手时补一笔。一次 60–120 Hz
    // 的拖动因此从「每个样本一次 session.set + 一次引擎预览请求」变成最多 10 次/秒。
    val commit = rememberDragCommit(clamped) { next -> onValueChange(next) }
    val shown = if (commit.dragging) commit.live else clamped
    val shownText = if (commit.dragging) phoneParamNumberText(shown, decimals, unit) else valueText
    // 拖动气泡的外观（motion batch 2, 2026-11-14）：只动不透明度——气泡位置每帧由 `shown`
    // 直接算（`FmSliderDragBubble` 是 direct read），数字本身从不做补间。90 ms 进、松手后
    // 140 ms 停留、90 ms 出（motion spec §3.1 #6）；scale 0 时 `fmTweenSpec` 变 snap、停留
    // 也按同一比例压到 0，所以「无动效」下气泡不会多挂 140 ms。
    val bubbleAlpha = remember { Animatable(0f) }
    var bubbleVisible by remember { mutableStateOf(false) }
    val bubbleIn = fmTweenSpec<Float>(FmDuration.press, FmEasing.enter)
    val bubbleOut = fmTweenSpec<Float>(FmDuration.press, FmEasing.exit)
    val bubbleLingerMs = FmMotionRules.durationMs(FmDuration.fast, LocalFmMotionScale.current)
    LaunchedEffect(commit.dragging) {
        if (commit.dragging) {
            bubbleVisible = true
            bubbleAlpha.animateTo(1f, bubbleIn)
        } else if (bubbleVisible) {
            if (bubbleLingerMs > 0) delay(bubbleLingerMs.toLong())
            bubbleAlpha.animateTo(0f, bubbleOut)
            bubbleVisible = false
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        // ---- 第一行：标签在左、数值在右 ----
        Row(
            modifier = Modifier.fillMaxWidth().height(LabelLine),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = TextStyle(fontSize = PHONE_PARAM_LABEL_SP.sp, lineHeight = 20.sp),
                color = if (enabled) FmPalette.onSurface else FmPalette.onSurfaceFaint,
                maxLines = 1,
                // 量到的行里没有省略号：放不下就换更短的标签（`phoneParamLabel`）。
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(FmRhythm.inlineLoose))
            Text(
                text = shownText,
                style = TextStyle(
                    fontSize = PHONE_PARAM_VALUE_SP.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = if (enabled) FmPalette.onSurface else FmPalette.onSurfaceFaint,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(FmRadii.chip))
                    .then(
                        if (enabled) {
                            Modifier.combinedClickable(
                                onClick = { editing = true },
                                onDoubleClick = { onReset?.invoke() ?: onDefaultDoubleTap(defaultValue, onValueChange) },
                                role = Role.Button,
                                onLongClick = null,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .semantics {
                        contentDescription = "$label：$shownText"
                        role = Role.Button
                        if (enabled) stateDescription = "可输入精确值，双击恢复默认"
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        // ---- 第二行：整宽轨道 ----
        Box(
            modifier = Modifier.fillMaxWidth().height(TrackLine),
            contentAlignment = Alignment.CenterStart,
        ) {
            FmSlider(
                value = shown,
                onValueChange = { next ->
                    commit.sample(if (next.isFinite()) next.coerceIn(min, max) else min)
                },
                onValueChangeFinished = { commit.release() },
                valueRange = range,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                modified = defaultValue != null && kotlin.math.abs(clamped - defaultValue) > 1e-4f,
                defaultValue = defaultValue,
                step = step,
                onReset = onReset,
                touch = true,
                valueText = { next ->
                    if (commit.dragging) phoneParamNumberText(next, decimals, unit) else valueText
                },
            )
            // 气泡画在轨道上方（底边离轨道 2 dp，压在标签行的下半截）：位置是 `shown` 的
            // 直接读数，只有不透明度走补间；数字本身从不做补间（motion spec §3.1 #6）。
            // 向上越出行高是刻意的——Box 不裁剪，它才浮得起来。
            if (bubbleVisible) {
                FmSliderDragBubble(
                    text = shownText,
                    fraction = fractionOf(shown, min, max),
                    alpha = { bubbleAlpha.value },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(y = -(FmTouchBubbleHeight + FmTouchBubbleGap)),
                )
            }
        }
        if (!enabled && !disabledReason.isNullOrBlank()) {
            Text(
                text = disabledReason,
                style = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp),
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            )
        }
    }
    if (editing) {
        PhoneParamValueDialog(
            label = label,
            initialText = phoneParamNumberText(clamped, decimals, null),
            unit = unit,
            onDismiss = { editing = false },
            onConfirm = { typed ->
                phoneTypedValue(typed, min, max)?.let(onValueChange)
                editing = false
            },
        )
    }
}

/** 双击数值但没有默认值时：什么都不做（不假装恢复了一个不存在的默认）。 */
private fun onDefaultDoubleTap(defaultValue: Float?, onValueChange: (Float) -> Unit) {
    if (defaultValue != null) onValueChange(defaultValue)
}

/** `-0.13` / `47%` / `12.0°`：数值 + 单位，小数位由 step 决定。 */
internal fun phoneParamNumberText(value: Float, decimals: Int, unit: String?): String =
    phoneFormatValue(value, decimals) + (unit ?: "")

/**
 * 输入框里的文本 → 写回的值：接受 `,` 当小数点、忽略单位，越界夹到滑杆区间，
 * 解析不了就返回 null（不写）。
 */
internal fun phoneTypedValue(text: String, min: Float, max: Float): Float? {
    val parsed = text.trim().replace(',', '.').toFloatOrNull() ?: return null
    if (!parsed.isFinite()) return null
    val lo = min.coerceAtMost(max)
    val hi = max.coerceAtLeast(min)
    return parsed.coerceIn(lo, hi)
}

/**
 * 精确值输入。数值位的可点面只有标签行那么高（26 dp，行本身 52 dp ≥ 44 dp 触控地板），
 * 所以输入放在一个小对话框里：点外面 / 取消什么都不改，确定才写回去。
 */
@Composable
private fun PhoneParamValueDialog(
    label: String,
    initialText: String,
    unit: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(FmRadii.card))
                .background(FmPalette.surface)
                .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.card))
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Text(
                text = label,
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                color = FmPalette.onSurfaceMuted,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(FmRadii.chip))
                    .background(FmPalette.surfaceMuted)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { typed -> text = typed.filter { it.isDigit() || it == '.' || it == '-' } },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = FmPalette.onSurface),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(FmPalette.primary),
                    modifier = Modifier.weight(1f).trackTextEditing().focusRequester(focus),
                )
                if (!unit.isNullOrBlank()) {
                    Text(
                        text = unit,
                        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                        color = FmPalette.onSurfaceMuted,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FmRhythm.row, Alignment.End),
            ) {
                DialogAction(text = "取消", primary = false) {
                    keyboard?.hide()
                    onDismiss()
                }
                DialogAction(text = "确定", primary = true) {
                    keyboard?.hide()
                    onConfirm(text)
                }
            }
        }
    }
}

@Composable
private fun DialogAction(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = PHONE_PARAM_ROW_MIN_TOUCH_DP.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(if (primary) FmPalette.primary else FmPalette.surfaceMuted)
            .combinedClickable(onClick = onClick, onDoubleClick = null, role = Role.Button)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            color = if (primary) FmPalette.onPrimary else FmPalette.onSurfaceSecondary,
        )
    }
}

/**
 * 开关行：标签 + 当前值（开 / 关）+ 开关轨道。整行仍是一条 [PHONE_PARAM_ROW_MIN_TOUCH_DP]
 * 以上的触控目标，点整行切换。
 */
@Composable
fun PhoneSwitchParamRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PHONE_PARAM_ROW_MIN_TOUCH_DP.dp)
            .combinedClickable(
                onClick = { onCheckedChange(!checked) },
                onDoubleClick = null,
                role = Role.Switch,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = if (checked) "已开启" else "已关闭"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = PHONE_PARAM_LABEL_SP.sp, lineHeight = 20.sp),
            color = FmPalette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(FmRhythm.inlineLoose))
        Text(
            text = if (checked) "开" else "关",
            style = TextStyle(fontSize = PHONE_PARAM_VALUE_SP.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
        )
        Spacer(Modifier.width(FmRhythm.inlineLoose))
        PhoneSwitchTrack(checked = checked)
    }
}

/** 46 × 26 的开关轨道（与旧 `.sw.touch` 同一个图形，不再是独立控件）。 */
@Composable
private fun PhoneSwitchTrack(checked: Boolean) {
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(if (checked) FmPalette.primary else FmPalette.outlineStrong),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .offset(x = if (checked) 20.dp else 0.dp)
                .size(22.dp)
                .clip(RoundedCornerShape(FmRadii.pill))
                .background(FmPalette.surface),
        )
    }
}

/**
 * 组 tab 行（钉在页头下面，**不跟参数滚**）：横滑只在放不下时才有必要，所以它永远
 * 包在 `horizontalScroll` 里 —— 裁字 / 换行都不是选项。
 *
 * **只有 0 或 1 组时不画这一行。** 一个 tab 没有可切换的对象，点它什么也不会发生；
 * 而页头已经写着同一个名字（文字页的「文字」、空水印页的「图层」、只有一组参数时的
 * 「规格 / 留白」），于是看起来像"页头下面又多了一个点了没用的东西"。多组页面的 tab
 * 行保持原样——那里的点击是真的会换下面的参数。
 */
@Composable
fun PhoneGroupTabRow(
    groups: List<PhoneParamGroup>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!phoneGroupTabRowVisible(groups.size)) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(PHONE_PANEL_TAB_ROW_DP.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        groups.forEach { group ->
            val active = group.id == selectedId
            Box(
                modifier = Modifier
                    .heightIn(min = PHONE_PARAM_ROW_MIN_TOUCH_DP.dp)
                    .clip(RoundedCornerShape(FmRadii.chip))
                    .background(if (active) FmPalette.surfaceMuted else androidx.compose.ui.graphics.Color.Transparent)
                    .combinedClickable(
                        onClick = { onSelect(group.id) },
                        onDoubleClick = null,
                        role = Role.Tab,
                    )
                    .semantics {
                        contentDescription = group.label
                        stateDescription = if (active) "已选" else "未选"
                    }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = group.label,
                    style = TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (active) FmPalette.onSurface else FmPalette.onSurfaceMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 声明控件 → 两行行（数值 / 开关 / 分段 / 色板 / 文本）
// ---------------------------------------------------------------------------

/** 一条声明的数值滑杆 → 两行行（标签与显示单位都由控件决定）。 */
@Composable
fun PhoneDeclaredSliderRow(
    control: InspectorControl,
    session: DesignSession,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledReason: String? = null,
    unit: String? = control.unit,
    decimals: Int = phoneDecimalsOf(control.step?.let { step -> step * control.scale() }),
    /** 生效下限（例如底部留白的短边 8.5 % 地板）。 */
    effectiveMin: Float? = null,
) {
    val raw = (session.value(control) as? Number)?.toFloat()
        ?: (control.initial as? ControlValue.Num)?.value
        ?: 0f
    val declaredMin = control.min ?: 0f
    val max = control.max ?: 100f
    val min = effectiveMin?.takeIf { it > declaredMin }?.coerceAtMost(max) ?: declaredMin
    val scale = control.scale()
    val displayValue = raw.coerceIn(min, max) * scale
    val declaredDefault = (control.initial as? ControlValue.Num)?.value
    val modified = session.isModified(control)
    PhoneSliderParamRow(
        label = label,
        value = displayValue,
        range = (min * scale)..(max * scale),
        defaultValue = declaredDefault?.let { it.coerceIn(min, max) * scale },
        step = control.step?.let { it * scale },
        unit = unit,
        decimals = decimals,
        onValueChange = { next ->
            // 输入的是显示单位，写回的是控件单位（旧滑杆的 `unitScale` 口径）。
            session.set(control, if (scale > 0f) next / scale else next)
        },
        modifier = modifier,
        enabled = enabled,
        disabledReason = disabledReason,
        onReset = if (modified) ({ session.resetControl(control) }) else null,
    )
}

/** 控件显示比例（`unitScale`，0 或未声明时是 1）。 */
internal fun InspectorControl.scale(): Float = unitScale.takeIf { it > 0f } ?: 1f

/** 分段选择行：标签 + 当前值 + 整宽分段（≤ 4 项时用分段，否则退回色板）。 */
@Composable
fun PhoneSegmentedParamRow(
    label: String,
    options: List<ControlOption>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(LabelLine),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = TextStyle(fontSize = PHONE_PARAM_LABEL_SP.sp, lineHeight = 20.sp),
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = phoneParamOptionLabel(options.firstOrNull { it.value == selected }?.display ?: selected),
                style = TextStyle(
                    fontSize = PHONE_PARAM_VALUE_SP.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
            )
        }
        PhoneSegmented(
            options = phoneParamOptions(options).map { it.value to it.display },
            selected = selected,
            onSelect = onSelect,
            label = label,
        )
    }
}
