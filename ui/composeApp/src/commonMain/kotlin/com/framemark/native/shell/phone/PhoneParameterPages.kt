package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorLoadState
import com.framemark.native.inspector.InspectorPage
import com.framemark.native.inspector.InspectorPageContent
import com.framemark.native.inspector.InspectorTabInfo
import com.framemark.native.inspector.LogoBrandPanel
import com.framemark.native.inspector.NativeTextSlotControls
import com.framemark.native.library.LibraryCard
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.isNativeTextSlotControl
import com.framemark.native.workflow.isUsableStaticTextControl

/**
 * The phone's parameter page for one WP-C tab ([InspectorTabInfo]).
 *
 * The showcase's phone sheet is not a control list but four hand-built panes
 * (`core.js` `paneClassicStyle` / `paneClassicText` / `lgPaneLive` / `paneWm`), so
 * this page renders the same block structure from the same data:
 *
 *  - 样式: 底色 swatches, 版式 segmented + its one-line note, 底部留白 slider and the
 *    导出尺寸 line for the adjustable card; a read-only 边框规格 block for a
 *    vendor-spec card (`paneIrStyle`); otherwise the declared controls in `.touchui`
 *    chrome (switch rows, touch sliders, segmented selects, swatches);
 *  - 文字: the renderer's slot rows ([NativeTextSlotControls] — input + 已改 /
 *    恢复原文 + 隐藏开关) plus 文字大小 when the card declares a scale;
 *  - Logo: [LogoBrandPanel] on both platforms (the same brand/version data the
 *    desktop right rail uses — no second copy here);
 *  - 水印: [watermarkPane], owned by the editor because the layer lives there.
 */
@Composable
fun PhoneParameterPage(
    tab: InspectorTabInfo?,
    page: InspectorPage,
    model: InspectorCardModel?,
    session: DesignSession,
    inspectorState: InspectorLoadState,
    onRetry: () -> Unit,
    textSlots: List<TextSlot> = emptyList(),
    textOutputWidth: Int = 0,
    textOutputHeight: Int = 0,
    onTextSlotFocus: ((String?) -> Unit)? = null,
    watermarkPane: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    /** Current card, for the 边框规格 block's 来源 row. */
    card: LibraryCard? = null,
    /** Pixels the chosen export step produces; 0 while the stage has no geometry yet. */
    outputWidth: Int = 0,
    outputHeight: Int = 0,
) {
    if (tab == null) {
        PanelNote(
            text = when (inspectorState) {
                InspectorLoadState.Idle, InspectorLoadState.Loading -> "正在载入可调参数…"
                is InspectorLoadState.Failed -> "暂时无法加载可调参数。"
                is InspectorLoadState.Ready -> "这套边框没有这一页的参数。"
            },
            modifier = modifier,
            onRetry = if (inspectorState is InspectorLoadState.Failed) onRetry else null,
        )
        return
    }
    if (page == InspectorPage.Watermark) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            watermarkPane?.invoke()
        }
        return
    }
    val hasRows = page == InspectorPage.Text && textSlots.isNotEmpty() && textOutputWidth > 0
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        when {
            page == InspectorPage.Logo && model != null ->
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    LogoBrandPanel(model = model, session = session, dense = true)
                }
            page == InspectorPage.Style && model != null -> PhoneStylePane(
                model = model,
                session = session,
                card = card,
                tab = tab,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
            )
            hasRows && model != null -> Column {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    NativeTextSlotControls(
                        model = model,
                        slots = textSlots,
                        outputWidth = textOutputWidth,
                        outputHeight = textOutputHeight,
                        session = session,
                        onTextSlotFocus = onTextSlotFocus,
                        dense = true,
                    )
                }
                PhoneTextScaleGroup(controls = tab.controls, session = session)
            }
            else -> Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                InspectorPageContent(
                    tab = tab.copy(controls = phonePageControls(tab)),
                    session = session,
                    dense = true,
                )
            }
        }
        footer?.invoke()
    }
}

/**
 * 样式 page of the showcase (`paneClassicStyle`): the theme swatches, the footer
 * layout segmented control with its one-line explanation, the footer-height touch
 * slider and the 导出尺寸 line. `paneIrStyle` (vendor-spec cards) is the read-only
 * 边框规格 block, and any other card falls back to its declared controls, rendered
 * with the same `.touchui` chrome rather than a form of drop-downs.
 */
@Composable
private fun PhoneStylePane(
    model: InspectorCardModel,
    session: DesignSession,
    card: LibraryCard?,
    tab: InspectorTabInfo,
    outputWidth: Int,
    outputHeight: Int,
) {
    val controls = phonePageControls(tab)
    val theme = controls.firstOrNull { it.param == "theme" || it.kind == "themeSwatches" }
    val mode = controls.firstOrNull { it.param == "params.footerMode" || it.kind == "modeSegment" }
    val footerPct = controls.firstOrNull { it.param == "params.footerPct" }
    // A vendor-spec card declares no adjustable template parameters at all: the
    // showcase shows the fixed spec block instead of an empty form.
    if (model.isIr || (theme == null && mode == null && controls.none { it.type == ControlType.Slider })) {
        PhoneSpecPane(card = card, slotCount = model.controls.count { isNativeTextSlotControl(it) })
        return
    }
    val handled = setOfNotNull(theme?.key, mode?.key, footerPct?.key)
    val rest = controls.filterNot { it.key in handled }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (theme != null) {
            PhoneGroup(title = "底色") {
                PhoneSwatchRow(
                    options = theme.options.map { it.value to it.display },
                    selected = session.value(theme)?.toString() ?: "",
                    onSelect = { value -> session.set(theme, value) },
                )
            }
        }
        if (mode != null) {
            PhoneGroup(title = "版式") {
                PhoneSegmented(
                    options = phoneModeOptions(mode.options.map { it.value to phoneModeLabel(it.display) }),
                    selected = session.value(mode)?.toString() ?: "",
                    onSelect = { value -> session.set(mode, value) },
                    label = "版式",
                )
                PhoneSubNote(phoneModeNote(session.value(mode)?.toString() ?: ""))
                if (footerPct != null) {
                    PhoneControlSlider(
                        control = footerPct,
                        session = session,
                        hint = if (session.value(mode)?.toString() == "overlay") "文字区高度" else "占照片宽度",
                    )
                }
                if (outputWidth > 0 && outputHeight > 0) {
                    Spacer(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(FmPalette.outline),
                    )
                    PhoneKeyValueRow(label = "导出尺寸", value = "$outputWidth × $outputHeight px")
                }
            }
        } else if (footerPct != null) {
            PhoneGroup(title = "版式") {
                PhoneControlSlider(control = footerPct, session = session, hint = "占照片宽度")
            }
        }
        if (rest.isNotEmpty()) {
            rest.groupBy { it.section }.forEach { (section, rows) ->
                PhoneGroup(title = section.title) {
                    rows.forEachIndexed { index, control ->
                        if (index > 0) Spacer(Modifier.height(4.dp))
                        PhoneControlRow(control = control, session = session)
                    }
                }
            }
        }
    }
}

/** Showcase `paneIrStyle`: the fixed geometry of a vendor-spec border. */
@Composable
private fun PhoneSpecPane(card: LibraryCard?, slotCount: Int) {
    PhoneGroup(title = "边框规格") {
        PhoneSpecRows(
            rows = buildList {
                add("文字" to "$slotCount 行")
                val source = listOfNotNull(
                    card?.brand?.takeIf { it.isNotBlank() },
                    card?.family?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (source.isNotBlank()) add("来源" to source)
            },
        )
        PhoneSubNote("厂商规格边框的尺寸、留白和文字位置都按规格固定，可以改文字内容和加图片水印。")
    }
}

/** The 文字 page's 文字大小 group (showcase `grp('文字大小', fmSlider(...))`). */
@Composable
private fun PhoneTextScaleGroup(controls: List<InspectorControl>, session: DesignSession) {
    val scale = controls.firstOrNull { it.param == "text.fontScale" } ?: return
    PhoneGroup(title = "文字大小") {
        PhoneControlSlider(control = scale, session = session, hint = "四行同比缩放")
    }
}

/** One declared control in the showcase's touch chrome. */
@Composable
private fun PhoneControlRow(control: InspectorControl, session: DesignSession) {
    when (control.type) {
        ControlType.Switch -> PhoneSwitchRow(
            label = phoneControlLabel(control),
            checked = session.value(control) as? Boolean ?: false,
            onCheckedChange = { session.set(control, it) },
        )
        ControlType.Slider -> PhoneControlSlider(control = control, session = session)
        ControlType.Color -> PhoneSwatchRow(
            options = control.options.map { it.value to it.display },
            selected = session.value(control)?.toString() ?: "",
            onSelect = { value -> session.set(control, value) },
        )
        ControlType.Select -> if (control.options.size <= 4) {
            PhoneSegmented(
                options = control.options.map { it.value to phoneModeLabel(it.display) },
                selected = session.value(control)?.toString() ?: "",
                onSelect = { value -> session.set(control, value) },
                label = phoneControlLabel(control),
            )
        } else {
            PhoneSwatchRow(
                options = control.options.map { it.value to it.display },
                selected = session.value(control)?.toString() ?: "",
                onSelect = { value -> session.set(control, value) },
            )
        }
        ControlType.Text -> PhoneTextValueRow(control = control, session = session)
        // 品牌字标 is owned by the Logo page on both platforms; showing it here too
        // would duplicate that data (brief: reuse, never re-implement).
        ControlType.Brand -> Unit
    }
}

/** A slider row bound to one declared control (display scale applied). */
@Composable
private fun PhoneControlSlider(
    control: InspectorControl,
    session: DesignSession,
    hint: String? = null,
) {
    val raw = (session.value(control) as? Number)?.toFloat()
        ?: (control.initial as? ControlValue.Num)?.value
        ?: 0f
    val min = control.min ?: 0f
    val max = control.max ?: 100f
    val scale = control.unitScale.takeIf { it > 0f } ?: 1f
    val modified = session.isModified(control)
    PhoneSliderRow(
        label = phoneControlLabel(control),
        value = raw,
        valueRange = min..max,
        defaultValue = phoneDeclaredDefault(control),
        step = control.step,
        unit = control.unit,
        modified = modified,
        onValueChange = { next -> session.set(control, next) },
        onReset = if (modified) {
            { session.resetControl(control) }
        } else {
            null
        },
        hint = hint,
        scale = scale,
        decimals = phoneDecimalsOf(control.step?.let { it * scale }),
    )
}

/** A declared free-text row: input + 已改 / 恢复原文 (showcase `.trow`). */
@Composable
private fun PhoneTextValueRow(control: InspectorControl, session: DesignSession) {
    val current = session.value(control)?.toString().orEmpty()
    val original = (control.initial as? ControlValue.Str)?.value.orEmpty()
    val modified = session.isModified(control)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            text = phoneControlLabel(control) + if (modified) "　已改" else "",
            style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
            color = if (modified) FmPalette.primaryStrong else FmPalette.onSurfaceMuted,
            maxLines = 1,
        )
        androidx.compose.foundation.text.BasicTextField(
            value = current,
            onValueChange = { text -> session.set(control, text) },
            singleLine = true,
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = FmPalette.onSurface,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(FmPalette.primary),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        if (modified) {
            Text(
                text = "恢复原文",
                style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
                color = FmPalette.primaryStrong,
                modifier = Modifier.clickable { session.set(control, original) }.padding(vertical = 4.dp),
            )
        }
    }
}

/** Showcase `RELABEL`: the inspector's shorter label for a known parameter. */
internal fun phoneControlLabel(control: InspectorControl): String = when (control.label) {
    "显示标志" -> "显示 Logo"
    "标志位置" -> "Logo 位置"
    "字标缩放" -> "Logo 大小"
    "字标水平中心" -> "Logo 水平位置"
    "品牌标志" -> "Logo"
    "底色主题" -> "底色"
    "显示日期行" -> "显示日期"
    "整卡字号缩放" -> "整体文字大小"
    "页脚版式" -> "版式"
    "页脚自适应" -> "页脚自适应"
    else -> control.label
}

/** Showcase `MODE_OPTS`: 经典 / 简约 / 叠加. */
internal fun phoneModeLabel(display: String): String = when (display.lowercase()) {
    "classic" -> "经典"
    "simple" -> "简约"
    "overlay" -> "叠加"
    else -> display
}

/**
 * The 版式 choices in the showcase's order (`MODE_OPTS` = classic, simple, overlay),
 * whatever order the metadata declares. Options outside that set keep their own
 * order and are appended, so a card with a fourth mode still shows it.
 */
internal fun phoneModeOptions(options: List<Pair<String, String>>): List<Pair<String, String>> {
    val order = listOf("classic", "simple", "overlay")
    val known = order.mapNotNull { key -> options.firstOrNull { it.first == key } }
    val rest = options.filterNot { it.first in order }
    return known + rest
}

/** Showcase `MODE_NOTE`: the one line under the 版式 segmented control. */
internal fun phoneModeNote(value: String): String = when (value) {
    "classic" -> "左侧品牌与机型，右侧参数与日期，中间放 Logo。"
    "simple" -> "单行居中：Logo · 品牌机型 · 参数 · 日期。"
    "overlay" -> "文字压在照片底部，画布不加高。"
    else -> ""
}

/** The declared default of a numeric control, used for the slider's tick and reset. */
internal fun phoneDeclaredDefault(control: InspectorControl): Float? =
    (control.initial as? ControlValue.Num)?.value
/**
 * Controls the phone page actually shows: no hidden rows, no blank labels, no
 * internal `params.slots` row, and no per-slot generated controls (those are drawn
 * by [NativeTextSlotControls] under their own row). Static text controls are only
 * kept when the card has no reliable renderer rows (same rule as WP-C).
 */
internal fun phonePageControls(tab: InspectorTabInfo): List<InspectorControl> =
    tab.controls
        .filterNot { it.hidden }
        .filter { it.label.isNotBlank() }
        .filterNot { it.param == "params.slots" }
        .filterNot { isNativeTextSlotControl(it) }
        .filter { tab.page != InspectorPage.Text || isUsableStaticTextControl(it) }
        .distinctBy { it.key }

@Composable
private fun PanelNote(text: String, modifier: Modifier, onRetry: (() -> Unit)?) {
    Column(
        modifier = modifier.fillMaxWidth().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = text, style = FmType.bodyMedium, color = FmPalette.onSurfaceMuted)
        if (onRetry != null) {
            androidx.compose.material3.TextButton(onClick = onRetry) {
                Text("重新加载参数", style = FmType.labelMedium, color = FmPalette.primaryStrong)
            }
        }
    }
}


