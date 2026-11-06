package com.framemark.native.inspector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.workflow.ControlProbe
import com.framemark.native.workflow.probeBounds
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.TextSlot

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
) {
    val controls = tab.controls
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
 * 文字页 (`core.js:738 paneClassicText` / `837 paneIrText`): one row per editable
 * text, the show/hide switches, then 文字大小.
 *
 * An IR card with no text row gets the showcase's empty state instead of a blank
 * page (`paneIrText` 的「这个边框没有可编辑文字」).
 */
@Composable
internal fun CuratedTextPane(
    controls: List<InspectorControl>,
    session: DesignSession,
    isIr: Boolean,
    modifier: Modifier = Modifier,
    /** 舞台最近一次渲染报告的四行 slot；非空时走「品牌 / 机型 / 拍摄参数 / 日期」版式。 */
    textSlots: CuratedTextSlots? = null,
) {
    val slotRows = classicTextRows(textSlots)
    // AU2 A-05: 注册表把 `text.fontScale` 归一成 consumer `fontScale`，桌面与手机
    // 必须用同一个判定（[fontScaleControlOf]），否则手机那页永远少一条滑杆。
    val fontScale = fontScaleControlOf(controls)
    if (slotRows.isNotEmpty()) {
        CuratedSlotTextPane(
            rows = slotRows,
            canvas = textSlots!!,
            controls = controls,
            session = session,
            fontScale = fontScale,
            modifier = modifier,
        )
        return
    }
    val textRows = controls.filter { it.type == ControlType.Text }
    val switches = controls.filter { it.type == ControlType.Switch }
    val sliders = controls.filter { it.type == ControlType.Slider }
    val others = controls.filter { it.type == ControlType.Select || it.type == ControlType.Color }
    Column(modifier = modifier.fillMaxWidth()) {
        if (textRows.isEmpty() && switches.isEmpty()) {
            CuratedEmptyPane(
                title = if (isIr) "这个边框没有可编辑文字" else "这个边框没有可编辑文字",
                body = "文字和位置由厂商规格固定。",
            )
        } else {
            if (textRows.isNotEmpty()) {
                CuratedGroup(
                    title = "文字内容",
                    right = { CuratedCount(if (isIr) "${textRows.size} 行 · 按版面位置" else "按版面位置排序") },
                ) {
                    textRows.forEachIndexed { index, control ->
                        if (index > 0) CuratedRule()
                        CuratedTextRow(control, session)
                    }
                }
            }
            if (switches.isNotEmpty()) {
                if (textRows.isNotEmpty()) CuratedRule()
                CuratedGroup("显示") {
                    switches.forEach { control ->
                        CuratedSwitchRow(control, session)
                    }
                }
            }
            if (sliders.isNotEmpty()) {
                CuratedRule()
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
                CuratedRule()
                CuratedGroup("其他") {
                    others.forEach { GenericControlRow(it, session) }
                }
            }
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

/** 稳定身份的最后一段：`hand:wm-classic:brand` / `wm-classic#brand` 都取 `brand`。 */
private fun slotLogicalName(slot: TextSlot): String =
    (slot.key ?: slot.id).substringAfterLast(':').substringAfterLast('#').lowercase()

/**
 * 把渲染器报告的四行按演示稿顺序挑出来。IR 卡的 path key（`b0/g0`）不会命中，
 * 于是那一页仍走原来的控件列表 —— 只有经典双行这类 hand 模板才有这四行。
 */
internal fun classicTextRows(info: CuratedTextSlots?): List<ClassicTextRow> {
    if (info == null) return emptyList()
    val categorized = info.slots.map { it to slotLogicalName(it) }
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

/** 四行文字页：文字内容（每行 slot）+ 文字大小（整体 fontScale）。 */
@Composable
private fun CuratedSlotTextPane(
    rows: List<ClassicTextRow>,
    canvas: CuratedTextSlots,
    controls: List<InspectorControl>,
    session: DesignSession,
    fontScale: InspectorControl?,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        CuratedGroup(
            title = "文字内容",
            right = { CuratedCount("按版面位置排序") },
        ) {
            rows.forEachIndexed { index, row ->
                if (index > 0) CuratedRule()
                CuratedSlotTextRow(
                    row = row,
                    boxes = rows.map { it.slot },
                    canvas = canvas,
                    controls = controls,
                    session = session,
                )
            }
        }
        if (fontScale != null) {
            CuratedRule()
            CuratedGroup("文字大小") {
                CuratedSliderRow(
                    control = fontScale,
                    session = session,
                    label = "整体文字大小",
                    hint = "四行同比缩放",
                    unit = fontScale.unit ?: "×",
                    decimalPlaces = 2,
                )
            }
        }
    }
}

/**
 * 一行 slot：位置示意图 · 标签 + 输入框 · 眼睛开关。输入框占位就是渲染器报告的
 * 原文，所以「清空输入框 / 点恢复原文」都会回到原文（`encodeTextSlotOverrides`
 * 里空文本等于删除覆盖）。
 */
@Composable
private fun CuratedSlotTextRow(
    row: ClassicTextRow,
    boxes: List<TextSlot>,
    canvas: CuratedTextSlots,
    controls: List<InspectorControl>,
    session: DesignSession,
) {
    val key = row.slot.key
    val textControl = if (key != null) {
        controls.firstOrNull { it.type == ControlType.Text && it.param == "params.slotText:$key" }
    } else {
        null
    }
    val showControl = controls.firstOrNull { control ->
        control.type == ControlType.Switch &&
            (control.param == row.showParam || control.param.endsWith(".${row.showParam}"))
    }
    val offControl = if (key != null) {
        controls.firstOrNull { it.type == ControlType.Switch && it.param == "params.slotOff:$key" }
    } else {
        null
    }
    val raw = row.slot.raw.ifBlank { row.slot.text }
    val override = textControl?.let { (session.value(it) as? String).orEmpty() }.orEmpty()
    val modified = textControl != null && session.isModified(textControl)
    val hidden = when {
        showControl != null -> (session.value(showControl) as? Boolean)?.not() ?: false
        offControl != null -> (session.value(offControl) as? Boolean)?.not() ?: row.slot.hidden
        else -> row.slot.hidden
    }
    var text by remember(textControl?.key, key) { mutableStateOf(override) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextSlotDiagram(boxes = boxes, current = row.slot, canvas = canvas)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.label,
                    style = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp),
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (modified && textControl != null) {
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
                                session.resetControl(textControl)
                                text = ""
                            }
                            .padding(horizontal = 4.dp),
                    )
                }
            }
            if (textControl == null) {
                Text(
                    text = raw.ifBlank { "（空）" },
                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = if (hidden) FmPalette.onSurfaceFaint else FmPalette.onSurface,
                        textDecoration = if (hidden) TextDecoration.LineThrough else null,
                    ),
                    cursorBrush = SolidColor(FmPalette.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 26.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(FmPalette.surface)
                        .semantics { contentDescription = row.label }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    text = raw.ifBlank { "（空）" },
                                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
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
        }
        if (showControl != null || offControl != null) {
            Spacer(Modifier.width(8.dp))
            TextSlotEye(
                visible = !hidden,
                label = row.label,
                onToggle = {
                    when {
                        showControl != null -> session.set(showControl, !(session.value(showControl) as? Boolean ?: true))
                        offControl != null -> session.set(offControl, !(session.value(offControl) as? Boolean ?: true))
                    }
                },
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
