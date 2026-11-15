package com.framemark.native.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framemark.native.FeelProbe
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryPreview
import com.framemark.native.product.StockWatermarkControls
import com.framemark.native.shell.Tag
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.groupTextSlots
import com.framemark.native.workflow.isNativeTextSlotControl
import com.framemark.native.workflow.isUsableStaticTextControl
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import kotlin.math.roundToInt
import com.framemark.native.workflow.trackTextEditing

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
    textSlots: List<TextSlot> = emptyList(),
    outputWidth: Int = 0,
    outputHeight: Int = 0,
    /** §3.1: stable key of the text row whose 替换文字 input has focus; null clears. */
    onTextSlotFocus: ((String?) -> Unit)? = null,
    /** Phone density: tighter structure only — type size and touch targets stay. */
    dense: Boolean = false,
    /** Phone 调整 page: sections appended after the tab's own (e.g. Theme). */
    extraSections: List<InspectorSection> = emptyList(),
) {
    LaunchedEffect(initialItem) {
        if (initialItem != null && initialItem > 0) state.scrollToItem(initialItem)
    }
    val logoPanelShown = tab == InspectorSection.Logo && model != null

    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = if (dense) {
            // 12 / 8: horizontal rails and the top/bottom of the phone panel.
            PaddingValues(
                start = FmSpacing.md,
                end = FmSpacing.md,
                top = FmSpacing.sm,
                bottom = FmSpacing.sm,
            )
        } else {
            PaddingValues(
                start = FmSpacing.lg,
                end = FmSpacing.lg,
                top = FmSpacing.md,
                bottom = FmSpacing.lg,
            )
        },
    ) {
        if (header != null) {
            item(key = "panel:header") { header() }
        }
        if (pinnedTabs != null) {
            stickyHeader(key = "panel:tabs") {
                Box(modifier = Modifier.fillMaxWidth().background(FmPalette.surface)) { pinnedTabs() }
            }
        }
        if (logoPanelShown && model != null) {
            // One entry for the whole Logo tab: 调整字标 → 更换 Logo / 自动匹配 / 我的
            // Logo → 当前字标说明. The old raw Brand row underneath was the duplicate
            // users saw twice, and the brand list no longer lands here.
            item(key = "panel:logoBrand") {
                LogoBrandPanel(model = model, session = session, dense = dense)
            }
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
                if (model == null) {
                    item { PanelNote(emptyTabText(tab)) }
                } else {
                    val sections = resolvedSections(tab, model, extraSections)
                        .map { (section, list) ->
                            section to list
                                .filterNot(::isNativeTextSlotControl)
                                .filter { it.label.isNotBlank() }
                                .filter { section != InspectorSection.Text || isUsableStaticTextControl(it) }
                               .distinctBy { it.key }
                        }
                    val textGroups = if (tab == InspectorSection.Text) {
                        groupTextSlots(textSlots, outputWidth, outputHeight)
                    } else {
                        emptyList()
                    }
                    // The Logo panel renders the Logo section itself. Text uses
                    // renderer rows when available and static controls only as the
                    // fallback while no reliable text rows exist for this pair.
                    val renderedSections = when {
                        logoPanelShown -> sections.filterNot { it.first == InspectorSection.Logo }
                        tab == InspectorSection.Text && textGroups.isNotEmpty() ->
                            sections.filterNot { it.first == InspectorSection.Text }
                        else -> sections
                    }
                    // Titles only when more than one section is listed: 布局 keeps
                    // its Border+Layout headings, single-tab panels stay clean.
                    val showSectionTitles = sections.size > 1
                    val hasControls = renderedSections.any { it.second.isNotEmpty() }
                    if (!logoPanelShown && !hasControls && textGroups.isEmpty()) {
                        item { PanelNote(emptyTabText(tab)) }
                    } else {
                        if (textGroups.isNotEmpty()) {
                            item(key = "native-text-slots:${model.cardId}") {
                                NativeTextSlotControls(
                                    model = model,
                                    slots = textSlots,
                                    outputWidth = outputWidth,
                                    outputHeight = outputHeight,
                                    session = session,
                                    onTextSlotFocus = onTextSlotFocus,
                                    dense = dense,
                                )
                            }
                        }
                        // When renderer rows exist, the Text section is omitted so it
                        // cannot duplicate them. With no reliable rows, the same
                        // section remains visible as the current card fallback.
                        renderedSections.forEachIndexed { sectionIndex, (section, sectionControls) ->
                            if (showSectionTitles && sectionControls.isNotEmpty()) {
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
                                    ControlItem(control, session, dense = dense)
                                }
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

/**
 * 文字页的渲染器 slot 区（桌面右栏与手机底板共用 [TextSlotSelector]）。
 *
 * WP-TX1：不再是「一组位置卡片 × 每行三个控件」的滚动列表，而是「有几个文本框
 * 就出几个导航项，点一个只显示这一个的编辑卡」。手机用 `dense = true`：导航横向
 * 可滚、卡片紧凑；桌面由 `CuratedTextPane` 调用同一套渲染。
 */
@Composable
internal fun NativeTextSlotControls(
    model: InspectorCardModel,
    slots: List<TextSlot>,
    outputWidth: Int,
    outputHeight: Int,
    session: DesignSession,
    /** §3.1: stable `TextSlot.key` of the row whose 替换文字 input has focus. */
    onTextSlotFocus: ((String?) -> Unit)? = null,
    dense: Boolean = false,
    /** 宿主保存的选择（切页签保持）；null 时页内自适应。 */
    selection: TextSlotSelectionState? = null,
) {
    // WP-C §4.6 / A-03: say "未读到拍摄参数" once per card, not on every row.
    val showExifHint = remember(model.cardId) { ExifGapNotice.shouldShowHint(slots) }
    if (showExifHint) {
        Text(
            text = ExifGapNotice.HINT,
            style = FmType.labelSmall,
            color = FmPalette.onSurfaceMuted,
            modifier = Modifier.padding(bottom = FmSpacing.xs),
        )
    }
    TextSlotSelector(
        cardId = model.cardId,
        slots = slots,
        outputWidth = outputWidth,
        outputHeight = outputHeight,
        controls = model.controls,
        session = session,
        dense = dense,
        selection = selection,
        onTextSlotFocus = onTextSlotFocus,
    )
}

/**
 * Retained, no longer wired: the Logo tab is rendered by [LogoBrandPanel] on both
 * platforms, which owns the brand entry, auto match and exact-cut list. This old
 * block and [BrandLogoLibraryDialog] stay compiled for the cards' plain brand
 * controls and for reference; they have no entry point in the Logo tab anymore.
 */
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
        }
        // Actions live on their own line: at 1.5x/2x system font the two chips used to
        // squeeze the value into one line and the text got clipped by the fixed box.
        Row(horizontalArrangement = Arrangement.spacedBy(FmSpacing.xs)) {
            var brandMenu by remember(control.key) { mutableStateOf(false) }
            BrandAction("选择") { brandMenu = true }
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

/**
 * [tabSections] plus the phone 调整 page's [extraSections], appended in the given
 * order and de-duplicated by section. A section already listed by the tab (or
 * repeated in [extraSections]) is never rendered twice.
 */
private fun resolvedSections(
    tab: InspectorSection,
    model: InspectorCardModel,
    extraSections: List<InspectorSection>,
): List<Pair<InspectorSection, List<InspectorControl>>> {
    val base = tabSections(tab, model)
    if (extraSections.isEmpty()) return base
    val sections = base.toMutableList()
    val seen = base.mapTo(mutableSetOf()) { it.first }
    extraSections.forEach { section ->
        if (!seen.add(section)) return@forEach
        val controls = model.sections.firstOrNull { it.section == section }?.controls.orEmpty()
        sections += section to controls
    }
    return sections
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
    dense: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FmRadii.card))
            .background(FmPalette.surface)
            .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.card))
            .padding(if (dense) FmSpacing.md else FmSpacing.lg),
    ) {
        Text(title, style = FmType.titleSmall, color = FmPalette.onSurface)
        Spacer(Modifier.height(if (dense) FmSpacing.sm else FmSpacing.md))
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

/** Dense row padding: V06 compresses structure, never type size or touch targets. */
private val DenseControlGap = 6.dp

@Composable
internal fun ControlItem(
    control: InspectorControl,
    session: DesignSession,
    showResetControl: Boolean = false,
    dense: Boolean = false,
    /** Only text inputs call this; true = focus gained, false = focus lost. */
    onTextFocusChanged: ((Boolean) -> Unit)? = null,
) {
    val probedValue = session.values[control.key]
    androidx.compose.runtime.LaunchedEffect(probedValue) {
        com.framemark.native.workflow.ControlProbe.state("control:${control.key}", probedValue, control.type.name)
    }
    Column(
        modifier = Modifier
            .probeBounds("control:${control.key}")
            .padding(vertical = if (dense) DenseControlGap else FmSpacing.md),
    ) {
        // 「这种是需要放开的」: a vendor card's 水印位置 is a 3×3 grid, not the
        // ten-item DropdownMenu `ControlType.Select` would draw. One predicate
        // (`product/StockWatermarkControls`) decides on both platforms.
        val stockAnchor = StockWatermarkControls.isAnchorParam(control.param)
        if (stockAnchor) {
            StockAnchorControl(
                control = control,
                session = session,
                dense = dense,
                // This row already draws its own 恢复默认 for dense hosts; never twice.
                showReset = !(showResetControl || dense),
            )
        } else {
            when (control.type) {
                ControlType.Switch -> SwitchControl(control, session, dense)
                ControlType.Slider -> SliderControl(control, session, dense)
                ControlType.Select -> SelectControl(control, session, dense)
                ControlType.Text -> TextControl(control, session, dense, onTextFocusChanged)
                ControlType.Color -> ColorControl(control, session, dense)
                ControlType.Brand -> BrandControl(control, session, dense)
            }
        }
        // 单项复位: a compact right-aligned row under the control (40 dp minimum),
        // not the old full-width 48 dp TextButton row. Non-dense keeps the old
        // opt-in behavior (only callers that pass showResetControl get it).
        if (!stockAnchor && (showResetControl || dense) && session.isModified(control)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(FmRadii.pill))
                        .clickable { session.resetControl(control) }
                        .semantics { contentDescription = "恢复默认：${control.label}" }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("恢复默认", style = FmType.labelSmall, color = FmPalette.primaryStrong)
                }
            }
        }
    }
}

@Composable
private fun ControlLabel(
    control: InspectorControl,
    valueText: String? = null,
    valueClickable: Boolean = false,
    onClickValue: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = control.label,
            style = FmType.bodyMedium,
            color = FmPalette.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (valueText != null) {
            Spacer(Modifier.width(FmSpacing.sm))
            val action = onClickValue
            if (valueClickable && action != null) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .clickable(role = Role.Button, onClick = action)
                        .semantics { contentDescription = "精确输入：${control.label}" }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(valueText, style = FmType.labelMedium, color = FmPalette.primaryStrong)
                }
            } else {
                Text(
                    text = valueText,
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceSecondary,
                )
            }
        }
    }
}

@Composable
private fun SwitchControl(control: InspectorControl, session: DesignSession, dense: Boolean = false) {
    val checked = session.value(control) as? Boolean ?: false
    Column {
        Row(
            modifier = if (dense) Modifier.heightIn(min = 48.dp) else Modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = control.label,
                style = FmType.bodyMedium,
                color = FmPalette.onSurface,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(FmSpacing.md))
            Switch(
                checked = checked,
                enabled = control.disabledReason == null,
                modifier = Modifier
                    .probeBounds("hit:${control.key}")
                    .semantics {
                        contentDescription = control.label
                        stateDescription = if (checked) "已开启" else "已关闭"
                    },
                onCheckedChange = { session.set(control, it) },
            )
        }
        control.disabledReason?.let { reason ->
            Text(
                text = reason,
                style = FmType.labelSmall,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier.padding(top = FmSpacing.xxs),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderControl(control: InspectorControl, session: DesignSession, dense: Boolean = false) {
    val min = control.min
    val max = control.max
    if (min == null || max == null || max <= min) {
        TextControl(control, session, dense)
        return
    }
    val value = session.value(control) as? Float ?: min
    val step = control.step?.takeIf { it > 0f }
    // Declared default: the tick, the ±1.5 % snap and double-tap reset all use it.
    val default = (control.initial as? ControlValue.Num)?.value
    val reset: () -> Unit = { session.resetControl(control) }
    val change: (Float) -> Unit = { raw ->
        FeelProbe.begin("slider", control.key)
        session.set(control, raw)
    }
    if (dense) {
        FmTouchSliderRow(
            label = com.framemark.native.product.ControlLabels.forControl(
                control.label,
                control.controlId,
                control.param,
            ) ?: control.label,
            value = value.coerceIn(min, max),
            min = min,
            max = max,
            step = step,
            default = default,
            unit = control.unit,
            scale = control.unitScale,
            enabled = control.disabledReason == null,
            disabledReason = control.disabledReason,
            onValueChange = change,
            onReset = reset,
            modifier = Modifier.probeBounds("hit:${control.key}"),
        )
    } else {
        FmDesktopSliderRow(
            label = com.framemark.native.product.ControlLabels.forControl(
                control.label,
                control.controlId,
                control.param,
            ) ?: control.label,
            value = value.coerceIn(min, max),
            min = min,
            max = max,
            step = step,
            default = default,
            unit = control.unit,
            scale = control.unitScale,
            enabled = control.disabledReason == null,
            disabledReason = control.disabledReason,
            onValueChange = change,
            onReset = reset,
            modifier = Modifier.probeBounds("hit:${control.key}"),
        )
    }
}

/** Exact numeric entry for a slider; confirming is the only write path. */
@Composable
private fun PreciseNumberDialog(
    control: InspectorControl,
    min: Float,
    max: Float,
    current: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    // The dialog speaks the display unit (e.g. alpha %): convert both ways here so
    // the stored value stays in engine units (WP-C §4.1).
    val scale = control.unitScale.takeIf { it > 0f } ?: 1f
    val displayMin = min * scale
    val displayMax = max * scale
    var raw by remember(control.key) { mutableStateOf(FmUnits.formatNumber(current * scale)) }
    val parsed = parsePreciseNumber(raw, displayMin, displayMax)
    val unit = control.unit ?: ""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("精确输入 · ${control.label}", style = FmType.titleSmall, color = FmPalette.onSurface)
        },
        text = {
            Column {
                BasicTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    singleLine = true,
                    textStyle = FmType.bodyMedium.copy(color = FmPalette.onSurface),
                    cursorBrush = SolidColor(FmPalette.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(FmRadii.input))
                        .background(FmPalette.surfaceMuted)
                        .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input))
                        .padding(horizontal = FmSpacing.md, vertical = 12.dp)
                        .semantics { contentDescription = "精确输入：${control.label}" }
                        .trackTextEditing(),
                    decorationBox = { inner ->
                        Box {
                            if (raw.isEmpty()) {
                                Text(
                                    text = "例：${FmUnits.formatNumber((displayMin + displayMax) / 2f)}$unit",
                                    style = FmType.bodyMedium,
                                    color = FmPalette.onSurfaceMuted,
                                )
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(FmSpacing.xs))
                Text(
                    text = "范围 ${FmUnits.formatNumber(displayMin)}$unit – ${FmUnits.formatNumber(displayMax)}$unit",
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                )
                if (raw.isNotBlank() && parsed == null) {
                    Text(
                        text = "请输入范围内的数字；空值、非数字和越界值不会被保存。",
                        style = FmType.labelSmall,
                        color = FmPalette.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun SelectControl(control: InspectorControl, session: DesignSession, dense: Boolean = false) {
    val selected = session.value(control) as? String ?: ""
    var expanded by remember { mutableStateOf(false) }
    Column {
        ControlLabel(control)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (dense) 48.dp else 40.dp)
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
private fun TextControl(
    control: InspectorControl,
    session: DesignSession,
    dense: Boolean = false,
    onTextFocusChanged: ((Boolean) -> Unit)? = null,
) {
    val text = session.value(control) as? String ?: ""
    // Report transitions only: Compose re-dispatches focus events, and callers
    // store the focused slot key.
    var hasFocus by remember(control.key) { mutableStateOf(false) }
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
                .heightIn(min = if (dense) 48.dp else 40.dp)
                .clip(RoundedCornerShape(FmRadii.input))
                .background(FmPalette.surfaceMuted)
                .border(1.dp, FmPalette.outline, RoundedCornerShape(FmRadii.input))
                .onFocusChanged { focusState ->
                    if (focusState.isFocused != hasFocus) {
                        hasFocus = focusState.isFocused
                        onTextFocusChanged?.invoke(focusState.isFocused)
                    }
                }
                .semantics { contentDescription = control.label }
                .padding(horizontal = FmSpacing.md, vertical = 10.dp)
                .trackTextEditing(),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = if (control.type == ControlType.Brand && control.autoMatch == true) {
                                "自动匹配"
                            } else {
                                control.currentText?.takeIf(String::isNotBlank) ?: "未设置"
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
private fun ColorControl(control: InspectorControl, session: DesignSession, dense: Boolean = false) {
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
                    .trackTextEditing()
                    .heightIn(min = if (dense) 48.dp else 40.dp)
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
private fun BrandControl(control: InspectorControl, session: DesignSession, dense: Boolean = false) {
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
                    .then(if (dense) Modifier.heightIn(min = 48.dp) else Modifier)
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
