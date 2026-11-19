package com.framemark.native.shell.phone

import com.framemark.native.inspector.InspectorPage
import com.framemark.native.inspector.InspectorTabInfo
import com.framemark.native.inspector.InspectorTabModel
import com.framemark.native.shell.EditorTool
import com.framemark.native.shell.phoneEditorTools

/**
 * P1's mapping between the five-tool bar and WP-C's page model
 * (`inspectorTabsFor`, `InspectorPage`).
 *
 * V2 report §7.3 / the showcase `TOOL_NAME`: 边框 · 样式 · 文字 · Logo · 水印.
 * 边框 is the library browser and owns no inspector page; the other four are
 * exactly [InspectorPage]'s pages, in the model's order. A tool's dot is the page's
 * "已改项数" (the showcase draws it only for the four pages, never for 边框).
 */

/** Label shown under the tool's icon and as the sheet title. */
fun phoneToolLabel(tool: EditorTool): String = when (tool) {
    EditorTool.Border -> "边框"
    else -> phoneToolPage(tool)?.label ?: tool.label
}

/** The inspector page a tool opens; 边框 has none. */
fun phoneToolPage(tool: EditorTool): InspectorPage? = when (tool) {
    EditorTool.Border -> null
    EditorTool.Params -> InspectorPage.Style
    EditorTool.Text -> InspectorPage.Text
    EditorTool.Logo -> InspectorPage.Logo
    EditorTool.Watermark -> InspectorPage.Watermark
}

/** The tool that owns a page (inverse of [phoneToolPage]). */
fun phoneToolForPage(page: InspectorPage): EditorTool = when (page) {
    InspectorPage.Style -> EditorTool.Params
    InspectorPage.Text -> EditorTool.Text
    InspectorPage.Logo -> EditorTool.Logo
    InspectorPage.Watermark -> EditorTool.Watermark
}

/**
 * The tools the card really offers: 边框 plus one cell per page the model has.
 *
 * While the inspector is still loading there is no model, so the bar shows the
 * four tools that need no Logo capability (the showcase's "no card" state does the
 * same). An IR card never gets a Logo page (WP-C `inspectorTabsFor`), so its bar
 * has four cells — the V06 rule the report §7.3 keeps.
 *
 * **[hasBorder] = false collapses the bar to 边框 alone** (2026-11, UX 审查): the
 * other three pages all edit a card that does not exist yet, so their cells could
 * only ever answer "先选择边框" — three dead taps in a row. The empty state has one
 * real next step and it already has one entry on the stage (`CHOOSE_LABEL`), so the
 * bar must not advertise three more. The caller forces `tool` back to 边框 whenever
 * the list no longer contains it (`PhoneEditorScreen`), so this is not a visual
 * trick: there is no state in which 样式/文字/水印 is selected with no card behind it.
 */
fun phoneToolsFor(model: InspectorTabModel?, hasBorder: Boolean): List<EditorTool> {
    if (!hasBorder) return listOf(EditorTool.Border)
    if (model == null) return phoneEditorTools(hasLogoCapability = false)
    val tools = ArrayList<EditorTool>(5)
    tools += EditorTool.Border
    model.tabs.forEach { tab -> tools += phoneToolForPage(tab.page) }
    return tools
}

/** The page a tool edits, or null for 边框. */
fun phoneToolTab(tool: EditorTool, model: InspectorTabModel?): InspectorTabInfo? {
    val page = phoneToolPage(tool) ?: return null
    return model?.tab(page)
}

/** Changed-item count of the page a tool owns; 边框 has none. */
fun phoneToolModified(tool: EditorTool, model: InspectorTabModel?): Int =
    phoneToolTab(tool, model)?.modified ?: 0

/** True when the tool's cell shows the small dot ("有改动的工具带小圆点"). */
fun phoneToolShowsDot(tool: EditorTool, model: InspectorTabModel?): Boolean =
    phoneToolPage(tool) != null && phoneToolModified(tool, model) > 0
