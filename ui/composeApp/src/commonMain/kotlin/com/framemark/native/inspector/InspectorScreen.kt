package com.framemark.native.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.framemark.native.workflow.probeBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framemark.native.FeelProbe
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryPreview
import com.framemark.native.shell.Tag
import com.framemark.native.workflow.DesignSession
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import kotlin.math.roundToInt

/**
 * Control list of one editor tab (Logo / 文字 / 布局 / 主题) for the phone editor's
 * segmented panel. The 布局 tab also carries the card's border-section controls
 * (frame / shadow / inset rows), because the border itself is chosen in the
 * library sheet and has no tab of its own — same split as the old WebView editor.
 *
 * Values live in [DesignSession]; every edit records a history step.
 */
@Composable
fun EditorTabList(
    state: LazyListState,
    tab: InspectorSection,
    model: InspectorCardModel?,
    loadState: InspectorLoadState,
    session: DesignSession,
    onRetry: () -> Unit,
    initialItem: Int? = null,
    modifier: Modifier = Modifier,
    /** Scrolls with the controls: the card head (eyebrow/name/description/reset). */
    header: (@Composable () -> Unit)? = null,
    /** Pinned under the panel grabber: the four tabs. */
    pinnedTabs: (@Composable () -> Unit)? = null,
    /** Scrolls at the bottom: original reference + output size. */
    footer: (@Composable () -> Unit)? = null,
) {
    LaunchedEffect(initialItem) {
        if (initialItem != null && initialItem > 0) state.scrollToItem(initialItem)
    }

    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = FmSpacing.lg,
            end = FmSpacing.lg,
            top = FmSpacing.md,
            bottom = FmSpacing.lg,
        ),
    ) {
        if (header != null) {
            item(key = "panel:header") { header() }
        }
        if (pinnedTabs != null) {
            stickyHeader(key = "panel:tabs") {
                Box(modifier = Modifier.fillMaxWidth().background(FmPalette.surface)) { pinnedTabs() }
            }
        }
        if (tab == InspectorSection.Logo && model != null) {
            item(key = "panel:brandLogo") { BrandLogoArea(model, session) }
        }
        when (loadState) {
            InspectorLoadState.Idle, InspectorLoadState.Loading -> item {
                PanelNote("正在载入可调参数…")
            }
            is InspectorLoadState.Failed -> item {
                Column {
                    PanelNote("暂时无法加载可调参数。")
                    RetryButton(onRetry)
                }
            }
            is InspectorLoadState.Ready -> {
                val sections = tabSections(tab, model)
                val controls = sections.flatMap { it.second }
                if (controls.isEmpty()) {
                    item { PanelNote(emptyTabText(tab)) }
                } else {
                    sections.forEachIndexed { sectionIndex, (section, sectionControls) ->
                        if (tab == InspectorSection.Layout && sectionControls.isNotEmpty()) {
                            item(key = "label:${section.name}") {
                                Text(
                                    text = section.title,
                                    style = FmType.labelMedium,
                                    color = FmPalette.onSurfaceMuted,
                                    modifier = Modifier.padding(
                                        top = if (sectionIndex == 0) 0.dp else FmSpacing.md,
                                        bottom = FmSpacing.xxs,
                                    ),
                                )
                            }
                        }
                        sectionControls.forEachIndexed { index, control ->
                            item(key = "control:${control.key}") {
                                if (index > 0) Divider()
                                ControlItem(control, session)
                            }
                        }
                    }
                }
            }
        }
        if (footer != null) {
            item(key = "panel:footer") { footer() }
        }
    }
}

/** Desktop right-rail 品牌 Logo block: 标题 + 自动匹配 + 当前/未选择 + 更换/清除. */
@Composable
private fun BrandLogoArea(model: InspectorCardModel, session: DesignSession) {
    val control = model.controls.firstOrNull { it.type == ControlType.Brand }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("品牌 Logo", style = FmType.bodyMedium, color = FmPalette.onSurface)
            Spacer(Modifier.weight(1f))
            if (control != null) {
                Text(
                    text = "自动匹配",
                    style = FmType.labelSmall,
                    color = FmPalette.primaryStrong,
                    modifier = Modifier
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .background(FmPalette.primarySoft)
                        .clickable { session.set(control, "") }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(FmSpacing.xs))
        if (control == null) {
            Text(
                text = "这套边框没有 Logo 参数。",
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
            )
            return@Column
        }
        val raw = session.value(control) as? String ?: ""
        var friendly by remember(raw) { mutableStateOf<String?>(null) }
        LaunchedEffect(raw) { friendly = if (raw.isBlank()) null else logoValueLabel(raw) }
        val display = friendly ?: selectedDisplay(control, raw)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(FmRadii.input))
                    .background(FmPalette.surfaceMuted)
                    .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (raw.isBlank()) "—" else display.take(1),
                    style = FmType.labelMedium,
                    color = FmPalette.onSurfaceSecondary,
                )
            }
            Spacer(Modifier.width(FmSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (raw.isBlank()) "自动匹配（按照片品牌）" else display,
                    style = FmType.bodyMedium,
                    color = if (raw.isBlank()) FmPalette.onSurfaceMuted else FmPalette.onSurface,
                )
                Text(
                    text = control.label,
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
            }
            var brandMenu by remember(control.key) { mutableStateOf(false) }
            BrandAction("选择") { brandMenu = true }
            Spacer(Modifier.width(FmSpacing.xs))
            BrandAction("自动匹配") { session.set(control, "") }
            if (brandMenu) {
                BrandLogoLibraryDialog(control = control, session = session, onDismiss = { brandMenu = false })
            }
        }
        Spacer(Modifier.height(FmSpacing.sm))
    }
}

@Composable
private fun BrandAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = FmType.labelSmall,
        color = FmPalette.onSurfaceSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.surfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** Cycles to the next declared brand option (picker without a dialog). */
private fun nextBrandOption(control: InspectorControl, current: String): String {
    val values = control.options.map { it.value }
    if (values.isEmpty()) return current
    return values[(values.indexOf(current) + 1).mod(values.size)]
}

private fun tabSections(
    tab: InspectorSection,
    model: InspectorCardModel?,
): List<Pair<InspectorSection, List<InspectorControl>>> {
    if (model == null) return emptyList()
    fun controls(section: InspectorSection) =
        model.sections.firstOrNull { it.section == section }?.controls.orEmpty()
    return when (tab) {
        InspectorSection.Layout -> listOf(
            InspectorSection.Border to controls(InspectorSection.Border),
            InspectorSection.Layout to controls(InspectorSection.Layout),
        )
        else -> listOf(tab to controls(tab))
    }
}

private fun emptyTabText(tab: InspectorSection): String = when (tab) {
    InspectorSection.Logo -> "这套边框没有可调的 Logo 参数。"
    InspectorSection.Text -> "这套边框没有可调的文字参数。"
    InspectorSection.Layout -> "这套边框没有可调的布局参数。"
    InspectorSection.Theme -> "这套边框没有可调的主题参数。"
    InspectorSection.Border -> "这套边框没有可调的边框参数。"
}

@Composable
private fun PanelNote(text: String) {
    Text(
        text = text,
        style = FmType.bodyMedium,
        color = FmPalette.onSurfaceMuted,
        modifier = Modifier.padding(vertical = FmSpacing.md),
    )
}

// ---------------------------------------------------------------------------
// Border section
// ---------------------------------------------------------------------------

@Composable
internal fun BorderSection(
    card: LibraryCard,
    model: InspectorCardModel?,
    session: DesignSession,
    loadState: InspectorLoadState,
    onRetry: () -> Unit,
) {
    SectionCardShell(title = "边框") {
        SelectedBorderSummary(card = card)
        BorderControlRows(
            model = model,
            session = session,
            loadState = loadState,
            onRetry = onRetry,
        )
    }
}

/**
 * Border-section controls only (no title, no card summary). Shared by the phone
 * editor's border card and the U5 desktop inspector tab.
 */
@Composable
internal fun BorderControlRows(
    model: InspectorCardModel?,
    session: DesignSession,
    loadState: InspectorLoadState,
    onRetry: () -> Unit,
) {
    when (loadState) {
        InspectorLoadState.Idle, InspectorLoadState.Loading -> {
            Divider()
            Text(
                text = "正在载入可调参数…",
                style = FmType.bodyMedium,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier.padding(vertical = FmSpacing.md),
            )
        }
        is InspectorLoadState.Failed -> {
            Divider()
            Text(
                text = "暂时无法加载可调参数。",
                style = FmType.bodyMedium,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier.padding(top = FmSpacing.md),
            )
            RetryButton(onRetry)
        }
        is InspectorLoadState.Ready -> {
            val borderControls = model
                ?.sections
                ?.firstOrNull { it.section == InspectorSection.Border }
                ?.controls
                .orEmpty()
            if (borderControls.isNotEmpty()) {
                borderControls.forEach { control ->
                    Divider()
                    ControlItem(control, session)
                }
            } else if (model == null || model.empty) {
                Text(
                    text = "该边框暂无可调参数",
                    style = FmType.bodyMedium,
                    color = FmPalette.onSurfaceMuted,
                    modifier = Modifier.padding(top = FmSpacing.md),
                )
            }
        }
    }
}

@Composable
internal fun SelectedBorderSummary(card: LibraryCard) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = FmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryPreview(
            card = card,
            modifier = Modifier
                .width(76.dp)
                .aspectRatio(4f / 3f),
        )
        Spacer(Modifier.width(FmSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text("已选边框", style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
            Spacer(Modifier.height(2.dp))
            Text(
                text = card.name,
                style = FmType.titleSmall,
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(FmSpacing.sm))
        Tag("已选")
    }
}

// ---------------------------------------------------------------------------
// Generated control sections
// ---------------------------------------------------------------------------

@Composable
internal fun SectionCard(
    section: InspectorSectionModel,
    session: DesignSession,
) {
    SectionCardShell(title = section.section.title) {
        section.controls.forEachIndexed { index, control ->
            if (index > 0) Divider()
            ControlItem(control, session)
        }
    }
}

@Composable
internal fun SectionCardShell(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.card))
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.card))
            .padding(FmSpacing.lg),
    ) {
        Text(title, style = FmType.titleSmall, color = FmPalette.onSurface)
        Spacer(Modifier.height(FmSpacing.md))
        content()
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FmPalette.outline),
    )
}

@Composable
private fun RetryButton(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(top = FmSpacing.sm)
            .clip(RoundedCornerShape(FmRadii.pill))
            .background(FmPalette.primarySoft)
            .clickable(onClick = onRetry)
            .padding(horizontal = FmSpacing.md, vertical = 6.dp),
    ) {
        Text("重试", style = FmType.labelMedium, color = FmPalette.primaryStrong)
    }
}

// ---------------------------------------------------------------------------
// One control row
// ---------------------------------------------------------------------------

@Composable
internal fun ControlItem(control: InspectorControl, session: DesignSession) {
    val probedValue = session.values[control.key]
    androidx.compose.runtime.LaunchedEffect(probedValue) {
        com.framemark.native.workflow.ControlProbe.state("control:${control.key}", probedValue, control.type.name)
    }
    Column(modifier = Modifier.probeBounds("control:${control.key}").padding(vertical = FmSpacing.md)) {
        when (control.type) {
            ControlType.Switch -> SwitchControl(control, session)
            ControlType.Slider -> SliderControl(control, session)
            ControlType.Select -> SelectControl(control, session)
            ControlType.Text -> TextControl(control, session)
            ControlType.Color -> ColorControl(control, session)
            ControlType.Brand -> BrandControl(control, session)
        }
    }
}

@Composable
private fun ControlLabel(control: InspectorControl, valueText: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = control.label,
            style = FmType.bodyMedium,
            color = FmPalette.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (valueText != null) {
            Spacer(Modifier.width(FmSpacing.sm))
            Text(
                text = valueText,
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceSecondary,
            )
        }
    }
}

@Composable
private fun SwitchControl(control: InspectorControl, session: DesignSession) {
    val checked = session.value(control) as? Boolean ?: false
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = control.label,
            style = FmType.bodyMedium,
            color = FmPalette.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(FmSpacing.md))
        Switch(
            checked = checked,
            modifier = Modifier.probeBounds("hit:${control.key}"),
            onCheckedChange = { session.set(control, it) },
        )
    }
}

@Composable
private fun SliderControl(control: InspectorControl, session: DesignSession) {
    val min = control.min
    val max = control.max
    if (min == null || max == null || max <= min) {
        TextControl(control, session)
        return
    }
    val value = session.value(control) as? Float ?: min
    val step = control.step
    val steps = if (step != null && step > 0f) {
        (((max - min) / step).roundToInt() - 1).coerceIn(0, 200)
    } else {
        0
    }
    Column {
        ControlLabel(
            control = control,
            valueText = formatNumber(value) + (control.unit ?: ""),
        )
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = { raw ->
                FeelProbe.begin("slider", control.key)
                val next = if (steps > 0 && step != null) {
                    val quantized = min + ((raw - min) / step).roundToInt() * step
                    quantized.coerceIn(min, max)
                } else {
                    raw
                }
                session.set(control, next)
            },
            valueRange = min..max,
            steps = steps,
            modifier = Modifier.probeBounds("hit:${control.key}"),
            colors = SliderDefaults.colors(
                thumbColor = FmPalette.primary,
                activeTrackColor = FmPalette.primary,
                inactiveTrackColor = FmPalette.outlineStrong,
            ),
        )
    }
}

@Composable
private fun SelectControl(control: InspectorControl, session: DesignSession) {
    val selected = session.value(control) as? String ?: ""
    var expanded by remember { mutableStateOf(false) }
    Column {
        ControlLabel(control)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(FmRadii.input))
                    .background(FmPalette.surfaceMuted)
                    .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input))
                    .clickable(enabled = control.options.isNotEmpty()) { expanded = true }
                    .padding(horizontal = FmSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedDisplay(control, selected),
                    style = FmType.bodyMedium,
                    color = if (selected.isBlank()) FmPalette.onSurfaceMuted else FmPalette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("▾", style = FmType.labelSmall, color = FmPalette.onSurfaceMuted)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .width(260.dp)
                    .background(FmPalette.surface),
            ) {
                control.options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.display,
                                style = FmType.bodyMedium,
                                color = if (option.value == selected) FmPalette.primaryStrong else FmPalette.onSurface,
                            )
                        },
                        onClick = {
                            session.set(control, option.value)
                            expanded = false
                        },
                        trailingIcon = if (option.value == selected) {
                            { Text("✓", style = FmType.labelMedium, color = FmPalette.primaryStrong) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

private fun selectedDisplay(control: InspectorControl, value: String): String {
    if (value.isBlank()) return "未选择"
    return control.options.firstOrNull { it.value == value }?.display
        ?: HumanLabels.forOption(value)
}

@Composable
private fun TextControl(control: InspectorControl, session: DesignSession) {
    val text = session.value(control) as? String ?: ""
    Column {
        ControlLabel(control)
        BasicTextField(
            value = text,
            onValueChange = { session.set(control, it) },
            singleLine = !control.multiline,
            textStyle = FmType.bodyMedium.copy(color = FmPalette.onSurface),
            cursorBrush = SolidColor(FmPalette.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier
                .probeBounds("hit:${control.key}")
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(FmRadii.input))
                .background(FmPalette.surfaceMuted)
                .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input))
                .padding(horizontal = FmSpacing.md, vertical = 10.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = if (control.type == ControlType.Brand && control.autoMatch == true) {
                                "自动匹配"
                            } else {
                                "未设置"
                            },
                            style = FmType.bodyMedium,
                            color = FmPalette.onSurfaceMuted,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun ColorControl(control: InspectorControl, session: DesignSession) {
    val text = session.value(control) as? String ?: ""
    Column {
        ControlLabel(control)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val parsed = parseHexColor(text)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(FmRadii.input))
                    .background(parsed ?: FmPalette.surfaceMuted)
                    .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input)),
            )
            Spacer(Modifier.width(FmSpacing.sm))
            BasicTextField(
                value = text,
                onValueChange = { session.set(control, it) },
                singleLine = true,
                textStyle = FmType.bodyMedium.copy(color = FmPalette.onSurface),
                cursorBrush = SolidColor(FmPalette.primary),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(FmRadii.input))
                    .background(FmPalette.surfaceMuted)
                    .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input))
                    .padding(horizontal = FmSpacing.md, vertical = 10.dp),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text("未设置", style = FmType.bodyMedium, color = FmPalette.onSurfaceMuted)
                        }
                        inner()
                    }
                },
            )
        }
    }
}

@Composable
private fun BrandControl(control: InspectorControl, session: DesignSession) {
    val raw = session.value(control) as? String ?: ""
    val isAuto = raw.isBlank() || raw == "auto"
    var friendly by remember(raw) { mutableStateOf<String?>(null) }
    LaunchedEffect(raw) { friendly = if (isAuto) null else logoValueLabel(raw) }
    val display = if (isAuto) {
        "自动匹配（按照片品牌）"
    } else {
        friendly ?: control.options.firstOrNull { it.value == raw }?.display ?: raw
    }
    var open by remember(control.key) { mutableStateOf(false) }
    Column {
        ControlLabel(control)
        Spacer(Modifier.height(FmSpacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(FmRadii.input))
                    .background(FmPalette.surfaceMuted)
                    .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input))
                    .clickable { open = true }
                    .padding(horizontal = FmSpacing.md, vertical = FmSpacing.sm),
            ) {
                Text(
                    text = display,
                    style = FmType.bodyMedium,
                    color = if (isAuto) FmPalette.onSurfaceMuted else FmPalette.onSurface,
                )
            }
            Spacer(Modifier.width(FmSpacing.sm))
            BrandAction("选择") { open = true }
        }
    }
    // The library is a real page (grid → versions), not a 64-row dropdown.
    if (open) {
        BrandLogoLibraryDialog(control = control, session = session, onDismiss = { open = false })
    }
}

private fun parseHexColor(text: String): Color? {
    val raw = text.trim().removePrefix("#")
    if (raw.length != 6 && raw.length != 8) return null
    val value = raw.toLongOrNull(16) ?: return null
    return if (raw.length == 6) Color(0xFF000000 or value) else Color(value)
}

private fun formatNumber(value: Float): String {
    val rounded = (value * 1000f).roundToInt() / 1000f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
}

// ---------------------------------------------------------------------------
// Watermark (user layer)
// ---------------------------------------------------------------------------

@Composable
internal fun WatermarkSection() {
    SectionCardShell(title = "水印") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(RoundedCornerShape(FmRadii.input))
                .background(FmPalette.surfaceMuted)
                .border(1.dp, FmPalette.outlineStrong, RoundedCornerShape(FmRadii.input))
                .padding(FmSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("＋", style = FmType.titleMedium, color = FmPalette.onSurfaceMuted)
            Spacer(Modifier.width(FmSpacing.sm))
            Column {
                Text("上传水印", style = FmType.bodyMedium, color = FmPalette.onSurfaceSecondary)
                Text(
                    "为照片添加你的专属水印。",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Evidence-only demo edits (FM_DEMO_EDITS, FM_UNDO)
// ---------------------------------------------------------------------------

internal fun applyDemoEdits(
    session: DesignSession,
    controls: List<InspectorControl>,
    count: Int,
): Int {
    val candidates = controls
        .filterNot { it.hidden }
        .sortedBy { control ->
            when (control.type) {
                ControlType.Switch -> 0
                ControlType.Slider -> 1
                ControlType.Select -> 2
                else -> 3
            }
        }
    var applied = 0
    for (control in candidates) {
        if (applied >= count) break
        val current = session.value(control)
        val next = when (control.type) {
            ControlType.Switch -> !(current as? Boolean ?: false)
            ControlType.Slider -> {
                val lo = control.min ?: 0f
                val hi = control.max ?: 1f
                if (hi <= lo) null else {
                    val raw = (current as? Float ?: lo) + (hi - lo) * 0.12f
                    if (raw > hi) lo else raw
                }
            }
            ControlType.Select -> control.options.firstOrNull { it.value != current }?.value
            else -> null
        } ?: continue
        session.set(control, next)
        applied++
    }
    return applied
}
