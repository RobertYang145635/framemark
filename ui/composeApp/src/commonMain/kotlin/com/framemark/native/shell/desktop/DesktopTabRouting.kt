package com.framemark.native.shell.desktop

import com.framemark.native.inspector.InspectorPage

/**
 * Maps the `FM_OPEN_TAB` QA/evidence string onto WP-C's four inspector pages.
 *
 * The launch hook predates the four-page model (it used `watermark` when the
 * desktop rail had six `DesktopTab`s), so a straight equality check would miss
 * `style` / `text` / `logo`. Unknown values return null and leave the default
 * page alone; both the English keys and the on-screen Chinese labels work.
 */
fun desktopPageForLaunchTab(raw: String?): InspectorPage? {
    val key = raw?.trim()?.lowercase() ?: return null
    return when (key) {
        "style", "样式" -> InspectorPage.Style
        "text", "文字" -> InspectorPage.Text
        "logo" -> InspectorPage.Logo
        "watermark", "水印" -> InspectorPage.Watermark
        else -> null
    }
}
