package com.framemark.native.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.DesignSession

/**
 * WP-C §4.3: the parameter panel's four pages, as data.
 *
 * Source of the names, order, membership and per-page reset: the V2 showcase
 * (`core.js` — `TAB_NAME`, `tabOf`, `tabsFor`, `modCount`, `resetTab`), which
 * the report §6 also spells out: 样式 / 文字 / Logo / 水印.
 *
 * This file is the **interface** for D1 (desktop right rail) and P1 (phone
 * bottom sheet):
 *  - [inspectorTabsFor] answers "which pages, and how many changed items each";
 *  - [resetInspectorPage] restores only that page and returns how many values it
 *    touched (one history step per control, never a whole-card reset);
 *  - [InspectorDesktopTabBar] is the desktop tab strip; [InspectorPageContent]
 *    is the page body both platforms share.
 *
 * The outer rail (title, "⋯" menu, footer) is D1's; the phone toolbar mapping is
 * P1's.
 */
enum class InspectorPage(val label: String) {
    Style("样式"),
    Text("文字"),
    Logo("Logo"),
    Watermark("水印"),
}

/** One page: which controls it owns and how many of them are off their default. */
data class InspectorTabInfo(
    val page: InspectorPage,
    /** Label shown on the tab; the panel may rename it per platform. */
    val label: String = page.label,
    val modified: Int,
    val controls: List<InspectorControl> = emptyList(),
)

/** The whole tab model for one selected card. */
data class InspectorTabModel(
    val cardId: String?,
    val tabs: List<InspectorTabInfo>,
) {
    fun tab(page: InspectorPage): InspectorTabInfo? = tabs.firstOrNull { it.page == page }
    val totalModified: Int get() = tabs.sumOf { it.modified }
    fun has(page: InspectorPage): Boolean = tabs.any { it.page == page }
}

/**
 * Page membership rules, copied from the showcase `tabOf`:
 *  - brand pickers, `logo*` keys, a `vendor` key and the words 标志 / 字标 go to
 *    Logo;
 *  - text controls, `text.*` keys and renderer-backed text rows (`native_text_slot`
 *    / `params.slotText|slotOff|slotScale`) go to 文字;
 *  - everything else goes to 样式.
 */
internal fun inspectorPageOf(control: InspectorControl): InspectorPage {
    val param = control.param
    val label = control.label
    return when {
        control.kind == "brandPicker" ||
            param.contains("logo", ignoreCase = true) ||
            param.endsWith("vendor") ||
            label.contains("标志") ||
            label.contains("字标") -> InspectorPage.Logo
        control.kind == "text" || param.startsWith("text.") ||
            control.kind == "native_text_slot" ||
            param.startsWith("params.slotText:") ||
            param.startsWith("params.slotOff:") ||
            param.startsWith("params.slotScale:") -> InspectorPage.Text
        else -> InspectorPage.Style
    }
}

/** Number of controls on [controls] that differ from their seeded value. */
internal fun modifiedCountOf(controls: List<InspectorControl>, session: DesignSession): Int =
    controls.count { session.isModified(it) }

/**
 * Builds the tab model for one card.
 *
 * [watermarkModified] is the watermark layer's own changed-item count (offset,
 * width, opacity, anchor — see `WatermarkZones.modifiedCount`); it cannot be
 * derived from the card model because the watermark is a per-photo user layer.
 * [hasTextSlots] keeps the 文字 page visible when the card's text lives in
 * renderer rows rather than declared controls.
 */
internal fun inspectorTabsFor(
    model: InspectorCardModel?,
    session: DesignSession,
    hasTextSlots: Boolean = false,
    watermarkModified: Int = 0,
): InspectorTabModel {
    if (model == null) return InspectorTabModel(cardId = null, tabs = emptyList())
    val visible = model.controls.filterNot { it.hidden }
    val byPage = visible.groupBy(::inspectorPageOf)
    val tabs = ArrayList<InspectorTabInfo>(4)
    InspectorPage.entries.forEach { page ->
        when (page) {
            InspectorPage.Style -> {
                val controls = byPage[page].orEmpty()
                tabs += InspectorTabInfo(page, modified = modifiedCountOf(controls, session), controls = controls)
            }
            InspectorPage.Text -> {
                val controls = byPage[page].orEmpty()
                if (controls.isNotEmpty() || hasTextSlots) {
                    tabs += InspectorTabInfo(page, modified = modifiedCountOf(controls, session), controls = controls)
                }
            }
            InspectorPage.Logo -> {
                // Showcase `tabsFor`: IR cards never show a Logo page. They render
                // the vendor mark themselves, so there is nothing to edit.
                val controls = byPage[page].orEmpty()
                if (!model.isIr && controls.isNotEmpty()) {
                    tabs += InspectorTabInfo(page, modified = modifiedCountOf(controls, session), controls = controls)
                }
            }
            InspectorPage.Watermark -> {
                tabs += InspectorTabInfo(page, modified = watermarkModified)
            }
        }
    }
    return InspectorTabModel(cardId = model.cardId, tabs = tabs)
}

/**
 * Restores one page to its declared values; returns how many controls changed.
 *
 * Other pages are untouched — this is the "每页的恢复默认只恢复本页" contract.
 * The watermark page is a user layer, so its caller resets it (see
 * `WatermarkPane`'s reset) and passes the result here only for reporting.
 */
internal fun resetInspectorPage(
    model: InspectorTabModel,
    page: InspectorPage,
    session: DesignSession,
): Int {
    val tab = model.tab(page) ?: return 0
    var changed = 0
    tab.controls.forEach { control ->
        if (session.isModified(control)) {
            session.resetControl(control)
            changed++
        }
    }
    return changed
}

/**
 * Desktop tab strip: one row, four pages, each with its changed-item count.
 * Selected page uses `primarySoft` + `primaryStrong`; the count is only drawn
 * when it is non-zero (report §6 "页签上显示本页已改项数").
 */
@Composable
fun InspectorDesktopTabBar(
    model: InspectorTabModel,
    selected: InspectorPage,
    onSelect: (InspectorPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FmSpacing.md, vertical = FmSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        InspectorPage.entries.forEach { page ->
            val tab = model.tab(page) ?: return@forEach
            val active = page == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(FmRadii.chip))
                    .background(if (active) FmPalette.primarySoft else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(page) }
                    .semantics {
                        contentDescription = if (tab.modified > 0) {
                            "${tab.label}，已改 ${tab.modified} 项"
                        } else {
                            tab.label
                        }
                    }
                    .padding(horizontal = FmSpacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tab.label,
                        style = FmType.labelMedium,
                        color = if (active) FmPalette.primaryStrong else FmPalette.onSurfaceSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (tab.modified > 0) {
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .heightIn(min = 14.dp)
                                .clip(RoundedCornerShape(FmRadii.pill))
                                .background(FmPalette.primary)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = tab.modified.toString(),
                                style = FmType.labelSmall,
                                color = FmPalette.onPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * D1-3: the stable renderer slot key a text control reports focus for. Only the
 * free-text row owns focus (`params.slotText:<key>`); switches and sliders of the
 * same row must not steal the stage frame. Extracted so the routing is testable.
 */
internal fun textSlotFocusKey(control: InspectorControl): String? =
    if (control.type == ControlType.Text && control.param.startsWith("params.slotText:")) {
        control.param.removePrefix("params.slotText:")
    } else {
        null
    }

/**
 * Page body shared by the desktop rail and the phone sheet. Sections keep the
 * engine grouping title only when a page carries more than one section.
 *
 * `watermarkPane` receives the 水印 page; D1/P1 pass their `WatermarkPane` wiring
 * so this file stays free of the photo/watermark plumbing.
 */
@Composable
fun InspectorPageContent(
    tab: InspectorTabInfo,
    session: DesignSession,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    watermarkPane: (@Composable () -> Unit)? = null,
    /**
     * WP-D1 D1-3: optional text-row focus reporter. `ControlType.Text` rows whose
     * param is `params.slotText:<key>` report the stable renderer slot key (null
     * on blur), so the stage can frame the row being edited. Default null keeps
     * every existing caller unchanged.
     */
    onTextSlotFocus: ((String?) -> Unit)? = null,
) {
    if (tab.page == InspectorPage.Watermark) {
        watermarkPane?.invoke()
        return
    }
    val sections = tab.controls.groupBy { it.section }.toList()
    Column(modifier = modifier.fillMaxWidth()) {
        if (tab.controls.isEmpty()) {
            Text(
                text = when (tab.page) {
                    InspectorPage.Style -> "这套边框没有可调的样式参数。"
                    InspectorPage.Text -> "这套边框没有可调的文字参数。"
                    InspectorPage.Logo -> "这套边框没有可调的 Logo 参数。"
                    InspectorPage.Watermark -> ""
                },
                style = FmType.bodyMedium,
                color = FmPalette.onSurfaceMuted,
                modifier = Modifier.padding(vertical = FmSpacing.md),
            )
            return@Column
        }
        sections.forEach { (section, controls) ->
            if (sections.size > 1) {
                Text(
                    text = section.title,
                    style = FmType.labelMedium,
                    color = FmPalette.onSurfaceMuted,
                    modifier = Modifier.padding(top = FmSpacing.sm, bottom = FmSpacing.xxs),
                )
            }
            controls.forEachIndexed { index, control ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(FmPalette.outline),
                    )
                }
                val slotFocusKey = textSlotFocusKey(control)
                val focusReporter: ((Boolean) -> Unit)? = slotFocusKey?.let { key ->
                    { focused: Boolean -> onTextSlotFocus?.invoke(if (focused) key else null) }
                }
                ControlItem(
                    control = control,
                    session = session,
                    dense = dense,
                    onTextFocusChanged = focusReporter,
                )
            }
        }
    }
}
