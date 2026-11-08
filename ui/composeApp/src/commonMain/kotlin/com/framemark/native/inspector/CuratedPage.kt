package com.framemark.native.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.InsetRule
import com.framemark.native.workflow.ControlProbe
import com.framemark.native.workflow.probeBounds
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.isCurrentTextSlot

/**
 * WP-V3 curated page dispatcher — the desktop rail's body.
 *
 * Mirrors `core.js:851 buildPane`: a **live** (可调) card gets the styled panes;
 * an IR card gets the read-only 「边框规格」 block plus its editable text rows;
 * the 水印 page is the shell's own `WatermarkPane`.
 *
 * Page membership still comes from `InspectorTabModel` (WP-C), so the phone sheet
 * keeps its layout: this dispatcher only runs when `dense == false`.
 */
@Composable
internal fun CuratedPage(
    tab: InspectorTabInfo,
    session: DesignSession,
    modifier: Modifier = Modifier,
    /** 这一页属于哪张卡：文字页的选择状态按它重置（`rememberSaveable` 的输入）。 */
    cardId: String? = null,
    /** 文字页选中框 → 舞台高亮（`highlightedTextSlotKey` 链路）。 */
    onTextSlotFocus: ((String?) -> Unit)? = null,
) {
    val controls = tab.controls
    // 选择状态挂在分页之上：切到样式/Logo 再回来，仍是同一个文本框（brief §2）。
    val textSelection = rememberTextSlotSelectionState(cardId)
    // 参数存在性判据（不写死卡片 id）：同时有 底色 / 版式 / 底部留白 才是可调边框，
    // 否则是厂商规格卡（`core.js:854` 的 ir 分支）。
    val tunable = themeControlOf(controls) != null &&
        footerModeControlOf(controls) != null &&
        footerPctControlOf(controls) != null
    Column(modifier = modifier.fillMaxWidth()) {
        when (tab.page) {
            InspectorPage.Style ->
                if (tunable) {
                    CuratedStylePane(
                        controls = controls,
                        session = session,
                        outputSize = curatedOutputSize(controls, session),
                    )
                } else {
                    CuratedSpecPane(textRows = controls.count { it.type == ControlType.Text })
                    if (controls.isNotEmpty()) {
                        CuratedRule()
                        CuratedGroup("可调参数") {
                            controls.forEach { GenericControlRow(it, session) }
                        }
                    }
                }
            InspectorPage.Text -> CuratedTextPane(
                controls = controls,
                session = session,
                isIr = !tunable,
                textSlots = LocalCuratedTextSlots.current,
                cardId = cardId,
                selection = textSelection,
                onTextSlotFocus = onTextSlotFocus,
            )
            // Logo 页由 Shell 直接调用 `LogoBrandPanel`（已转发到 LogoPanes.kt）。
            InspectorPage.Logo -> Unit
            InspectorPage.Watermark -> Unit
        }
    }
}

/**
 * 导出尺寸 for a live card — `core.js:203 outSize`, and the **only** place the UI
 * turns a design into pixels.
 *
 * AU2 A-06/A-07: this used to re-derive the height inline (`toInt()` truncation,
 * no 8.5 % short-side floor, no `borderPct`), so the rail said `2666` where the
 * engine wrote `2667` and `2667` where a personal-sign card writes `2774`. It now
 * delegates to [OutputSize], which mirrors the engine's own `layout()`; the top
 * bar and the phone use the same function, so all three read the same number.
 *
 * Null when the photo's pixel size has not been wired into the rail yet.
 */
@Composable
internal fun curatedOutputSize(
    controls: List<InspectorControl>,
    session: DesignSession,
): Pair<Int, Int>? = curatedOutputPixels(controls, session)?.let { it.width to it.height }

/** [curatedOutputSize] without the tuple unpacking (phone/rail both want pixels). */
@Composable
internal fun curatedOutputPixels(
    controls: List<InspectorControl>,
    session: DesignSession,
): OutputPixels? {
    val photo = LocalCuratedPhotoSize.current ?: return null
    return OutputSize.canvas(
        photoWidthPx = photo.widthPx,
        photoHeightPx = photo.heightPx,
        controls = controls,
        value = { control -> session.value(control) },
    )
}

/** 边框规格（厂商规格卡）— read-only: 画布 / 照片区 / 文字 / 来源 + 说明。 */
@Composable
internal fun CuratedSpecPane(
    textRows: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        CuratedGroup("边框规格") {
            if (textRows > 0) {
                SpecRow("文字", "$textRows 行")
            }
            CuratedSubNote("厂商规格边框的尺寸、留白和文字位置都按规格固定，可以改文字内容和加图片水印。")
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = CuratedNote,
            color = FmPalette.onSurfaceMuted,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            style = CuratedNumeric,
            color = FmPalette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 文字页 (`core.js:738 paneClassicText` / `837 paneIrText`)，2026-11-07 用户口径：
 *
 *  - 渲染器报告了几个文本框，就出几个导航项，顺序按版面阅读顺序；点一个只显示
 *    这一个文本框的编辑卡（[TextSlotSelector]）；
 *  - 只有一个文本框时不显示导航条，直接给那张卡；一个都没有给空态
 *    （「这个边框没有可编辑文字」）；
 *  - 「文字大小」（`fontScale`）保持全局一项，放在导航 + 编辑卡下方。
 *
 * 没有报告可用的卡（非实时渲染的厂商规格卡）仍然走声明出来的文字行，否则那些
 * 边框的文字就彻底没法改了。
 */
@Composable
internal fun CuratedTextPane(
    controls: List<InspectorControl>,
    session: DesignSession,
    isIr: Boolean,
    modifier: Modifier = Modifier,
    /** 舞台最近一次渲染报告的四行 slot；非空时走「品牌 / 机型 / 拍摄参数 / 日期」版式。 */
    textSlots: CuratedTextSlots? = null,
    /** 卡片 id：文字页的选择状态以它为准。 */
    cardId: String? = null,
    /** 宿主保存的选择（切页签保持）。 */
    selection: TextSlotSelectionState? = null,
    /** 选中框 → 舞台高亮。 */
    onTextSlotFocus: ((String?) -> Unit)? = null,
) {
    val slots = textSlots?.slots.orEmpty()
    val slotWidth = textSlots?.widthPx ?: 0
    val slotHeight = textSlots?.heightPx ?: 0
    val entries = remember(slots, slotWidth, slotHeight) {
        textSlotNavEntries(slots, slotWidth, slotHeight)
    }
    // AU2 A-05: 注册表把 `text.fontScale` 归一成 consumer `fontScale`，桌面与手机
    // 必须用同一个判定（[fontScaleControlOf]），否则手机那页永远少一条滑杆。
    val fontScale = fontScaleControlOf(controls)
    val textRows = controls.filter { it.type == ControlType.Text }
    val switches = controls.filter { it.type == ControlType.Switch }
    val sliders = controls.filter { it.type == ControlType.Slider }
    val others = controls.filter { it.type == ControlType.Select || it.type == ControlType.Color }
    when (
        textPageMode(
            hasReport = slotWidth > 0,
            entries = entries,
            staticTextControls = textRows + switches,
        )
    ) {
        TextPageMode.Selector -> Column(modifier = modifier.fillMaxWidth()) {
            CuratedGroup(
                title = "文字内容",
                right = if (showsTextSlotNavigation(entries)) {
                    { CuratedCount("按版面位置排序") }
                } else {
                    null
                },
            ) {
                TextSlotSelector(
                    cardId = cardId.orEmpty(),
                    slots = slots,
                    outputWidth = slotWidth,
                    outputHeight = slotHeight,
                    controls = controls,
                    session = session,
                    dense = false,
                    selection = selection,
                    onTextSlotFocus = onTextSlotFocus,
                )
            }
            if (fontScale != null) {
                CuratedGroup("文字大小") {
                    CuratedSliderRow(
                        control = fontScale,
                        session = session,
                        label = "整体文字大小",
                        hint = textScaleHint(entries.count { !it.readonly }),
                        unit = fontScale.unit ?: "×",
                        decimalPlaces = 2,
                    )
                }
            }
        }
        TextPageMode.StaticRows -> Column(modifier = modifier.fillMaxWidth()) {
            if (textRows.isNotEmpty()) {
                CuratedGroup(
                    title = "文字内容",
                    right = { CuratedCount(if (isIr) "${textRows.size} 行 · 按版面位置" else "按版面位置排序") },
                ) {
                    textRows.forEachIndexed { index, control ->
                        if (index > 0) InsetRule()
                        CuratedTextRow(control, session)
                    }
                }
            }
            if (switches.isNotEmpty()) {
                CuratedGroup("显示") {
                    switches.forEach { control ->
                        CuratedSwitchRow(control, session)
                    }
                }
            }
            if (sliders.isNotEmpty()) {
                CuratedGroup("文字大小") {
                    sliders.forEach { control ->
                        CuratedSliderRow(
                            control = control,
                            session = session,
                            label = control.label.ifBlank { "文字大小" },
                            hint = null,
                            unit = control.unit ?: "×",
                            decimalPlaces = 2,
                        )
                    }
                }
            }
            if (others.isNotEmpty()) {
                CuratedGroup("其他") {
                    others.forEach { GenericControlRow(it, session) }
                }
            }
        }
        TextPageMode.Empty -> Column(modifier = modifier.fillMaxWidth()) {
            CuratedEmptyPane(
                title = "这个边框没有可编辑文字",
                body = "文字和位置由厂商规格固定。",
            )
        }
    }
}

/** `.ghead .count` — 11 px `ink-4`. */
@Composable
internal fun CuratedCount(text: String) {
    Text(
        text = text,
        style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, color = FmPalette.onSurfaceMuted)
            .withNumericDigits(),
        maxLines = 1,
    )
}

/** `.empty-pane` — centred title + one sentence. */
@Composable
internal fun CuratedEmptyPane(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 36.dp, bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = CuratedGroupTitle, color = FmPalette.onSurface, maxLines = 2)
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = CuratedNote,
            color = FmPalette.onSurfaceMuted,
            maxLines = 3,
        )
    }
}

/**
 * WP-F1 §4 item 2 / WP-X3 §6: `FM_CONTROL_PROBE` used to print nothing on the
 * desktop because [com.framemark.native.workflow.ControlProbe] was only attached
 * to the old `ControlItem` path (phone / legacy panel). The desktop inspector is
 * this curated panel, so the same probe is attached here.
 *
 * Key == `control:<control.key>`, the same shape `ControlItem` publishes, so a
 * harness that was written against the old path keeps working. Reader rule is
 * unchanged: take the last line for a key.
 */
@Composable
internal fun curatedProbeState(control: InspectorControl, value: Any?) {
    LaunchedEffect(value) {
        ControlProbe.state("control:${control.key}", value, control.type.name)
    }
}

/**
 * `.trow` — editable text with 「已改 / 恢复原文」 underneath the label. The
 * value box is borderless until hovered/focused (showcase `.tx input`).
 */
@Composable
internal fun CuratedTextRow(control: InspectorControl, session: DesignSession) {
    val current = session.value(control).toString()
    val original = control.declaredDefault.orEmpty()
    val modified = session.isModified(control)
    var text by remember(control.key) { mutableStateOf(current) }
    curatedProbeState(control, current)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .probeBounds("control:${control.key}")
            .padding(vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = control.label.ifBlank { "文字" },
                style = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp),
                color = FmPalette.onSurfaceMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (modified) {
                Text(
                    text = "已改",
                    style = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp),
                    color = FmPalette.primaryStrong,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "恢复原文",
                    style = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp),
                    color = FmPalette.primaryStrong,
                    modifier = Modifier
                        .heightIn(min = 22.dp)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .clickable(role = Role.Button) {
                            session.resetControl(control)
                            text = control.declaredDefault.orEmpty()
                        }
                        .padding(horizontal = 4.dp),
                )
            }
        }
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                session.set(control, it)
            },
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = FmPalette.onSurface),
            cursorBrush = SolidColor(FmPalette.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 26.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(FmPalette.surface)
                .semantics { contentDescription = control.label }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = original.ifBlank { "（空）" },
                            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                            color = FmPalette.onSurfaceMuted,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/** `.row` + `sw` — label left, switch right; the whole row toggles. */
@Composable
internal fun CuratedSwitchRow(control: InspectorControl, session: DesignSession) {
    val on = session.value(control) as? Boolean ?: (control.initial as? ControlValue.Flag)?.value ?: false
    curatedProbeState(control, on)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .probeBounds("control:${control.key}")
            .clickable(role = Role.Switch) { session.set(control, !on) }
            .semantics { contentDescription = control.label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = control.label,
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            color = FmPalette.onSurfaceSecondary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = on,
            onCheckedChange = { session.set(control, it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = FmPalette.onPrimary,
                checkedTrackColor = FmPalette.primary,
                uncheckedThumbColor = FmPalette.surface,
                uncheckedTrackColor = FmPalette.track,
                uncheckedBorderColor = FmPalette.outlineStrong,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// 经典双行的文字页：四行 slot（`core.js:738 paneClassicText`）
// ---------------------------------------------------------------------------

/**
 * 一行文字页 slot：展示标签、可覆盖的文本、隐藏开关。
 *
 * [showParam] 是演示稿 `F[k][1]`（`showMake/showModel/showExif/showDate`）：眼睛开关
 * 写的是这几条既有参数，而不是另造一个"槽位可见性"字段。渲染器报告的
 * `params.slotOff:<key>` 只是兜底，两者都没有时按只读行处理。
 */
internal data class ClassicTextRow(
    val slot: TextSlot,
    val label: String,
    val showParam: String,
)

/** `core.js:744` 的行序：品牌(make) / 机型(model) / 拍摄参数(exif) / 日期(date)。 */
private val ClassicTextSpecs = listOf(
    Triple("brand", "品牌", "showMake"),
    Triple("make", "品牌", "showMake"),
    Triple("model", "机型", "showModel"),
    Triple("exif", "拍摄参数", "showExif"),
    Triple("date", "日期", "showDate"),
)

/**
 * 稳定身份的最后一段：`hand:wm-classic:brand` / `wm-classic#brand` 都取 `brand`；
 * 冻结数据里的 `wm-classic#make#0` 这种带行号的老写法也认（行号不是身份）。
 */
private fun slotLogicalName(slot: TextSlot): String =
    (slot.key ?: slot.id)
        .replace(Regex("#\\d+$"), "")
        .substringAfterLast(':')
        .substringAfterLast('#')
        .lowercase()

/**
 * 把渲染器报告的四行按演示稿顺序挑出来。IR 卡的 path key（`b0/g0`）不会命中，
 * 于是那一页仍走原来的控件列表 —— 只有经典双行这类 hand 模板才有这四行。
 *
 * 目录里的声明 id（`wm-classic#make#0`）和渲染器报告的 `hand:wm-classic:brand`
 * 拼法不同，桥接会为前者留一条「声明但没画」的占位行；同一个逻辑名两行都在时，
 * **画出来的那一行优先**，否则「机型 / 拍摄参数 / 日期」会绑到空占位行上。
 */
internal fun classicTextRows(info: CuratedTextSlots?): List<ClassicTextRow> {
    if (info == null) return emptyList()
    val categorized = info.slots
        .sortedByDescending { it.isCurrentTextSlot() }
        .map { it to slotLogicalName(it) }
    val picked = mutableSetOf<String>()
    val rows = ArrayList<ClassicTextRow>(4)
    for ((logical, label, showParam) in ClassicTextSpecs) {
        if (rows.any { it.label == label }) continue
        val slot = categorized.firstOrNull { (slot, name) -> name == logical && slot.id !in picked }?.first ?: continue
        picked += slot.id
        rows += ClassicTextRow(slot, label, showParam)
    }
    return rows
}
