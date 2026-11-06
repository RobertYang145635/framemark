package com.framemark.native.shell

import com.framemark.native.inspector.InspectorPage
import com.framemark.native.shell.desktop.desktopPageForLaunchTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `FM_OPEN_TAB` → WP-C four-page mapping. The QA hook predates the four-page
 * model, so this pins the English keys and the on-screen Chinese labels used by
 * the screenshot commands.
 */
class DesktopTabRoutingTest {

    @Test
    fun english_keys_map_to_the_four_pages() {
        assertEquals(InspectorPage.Style, desktopPageForLaunchTab("style"))
        assertEquals(InspectorPage.Text, desktopPageForLaunchTab("text"))
        assertEquals(InspectorPage.Logo, desktopPageForLaunchTab("logo"))
        assertEquals(InspectorPage.Watermark, desktopPageForLaunchTab("watermark"))
    }

    @Test
    fun chinese_labels_and_case_and_spaces_also_work() {
        assertEquals(InspectorPage.Style, desktopPageForLaunchTab(" 样式 "))
        assertEquals(InspectorPage.Text, desktopPageForLaunchTab("文字"))
        assertEquals(InspectorPage.Logo, desktopPageForLaunchTab(" LOGO "))
        assertEquals(InspectorPage.Watermark, desktopPageForLaunchTab("水印"))
    }

    @Test
    fun unknown_and_missing_values_leave_the_default_page() {
        assertNull(desktopPageForLaunchTab(null))
        assertNull(desktopPageForLaunchTab(""))
        assertNull(desktopPageForLaunchTab("border"))
    }
}
