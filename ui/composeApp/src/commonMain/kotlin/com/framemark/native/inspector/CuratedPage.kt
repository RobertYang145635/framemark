package com.framemark.native.inspector

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.workflow.DesignSession

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
            InspectorPage.Text -> CuratedTextPane(controls = controls, session = session, isIr = !tunable)
            // Logo 页由 Shell 直接调用 `LogoBrandPanel`（已转发到 LogoPanes.kt）。
            InspectorPage.Logo -> Unit
            InspectorPage.Watermark -> Unit
        }
    }
}

/**
 * 导出尺寸 for a live card: `core.js:203 outSize` — the photo width, and the photo
 * height plus the footer band (unless the layout overlays the text on the photo).
 * Null when the photo's pixel size has not been wired into the rail yet.
 */
@Composable
internal fun curatedOutputSize(
    controls: List<InspectorControl>,
    session: DesignSession,
): Pair<Int, Int>? {
    val photo = LocalCuratedPhotoSize.current ?: return null
    val mode = footerModeControlOf(controls)?.let { session.value(it).toString() }
    val pct = footerPctControlOf(controls)
        ?.let { (session.value(it) as? Number)?.toFloat() }
        ?: 0f
    val height = if (mode == "overlay") photo.heightPx else {
        (photo.heightPx + photo.widthPx * pct / 100f).toInt()
    }
    return photo.widthPx to height
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
) {
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
        style = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, color = FmPalette.onSurfaceFaint),
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
 * `.trow` — editable text with 「已改 / 恢复原文」 underneath the label. The
 * value box is borderless until hovered/focused (showcase `.tx input`).
 */
@Composable
internal fun CuratedTextRow(control: InspectorControl, session: DesignSession) {
    val current = session.value(control).toString()
    val original = control.declaredDefault.orEmpty()
    val modified = session.isModified(control)
    var text by remember(control.key) { mutableStateOf(current) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = control.label.ifBlank { "文字" },
                style = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp),
                color = FmPalette.onSurfaceFaint,
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
                            color = FmPalette.onSurfaceFaint,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
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
