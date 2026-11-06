package com.framemark.native.shell.phone

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WP-PV1 §4.2 设置页文案：`phone.js buildSettings()` 的组标题、说明与页脚。
 * 与 `PhoneSettingsTextTest` 分开，避免在同一个文件里既改断言又加断言。
 */
class PhoneSettingsCopyTest {

    @Test
    fun theSettingsCopyIsTheDemoCopy() {
        assertEquals("设置", PhoneSettingsText.PAGE_TITLE)
        assertEquals("外观", PhoneSettingsText.GROUP_APPEARANCE)
        assertEquals("主题", PhoneSettingsText.ROW_THEME)
        assertEquals(
            "跟随系统时，系统切换到深色模式后立即跟着变，不用刷新；" +
                "手动选择浅色或深色后不再跟随系统。",
            PhoneSettingsText.THEME_NOTE,
        )
        assertEquals("版本", PhoneSettingsText.ROW_VERSION)
    }

    @Test
    fun theFooterIsTheRealNoteNotTheDemoSelfDescription() {
        // 演示稿这里是「演示稿的设置页：只有外观和版本号」——设计稿对自己的说明，
        // 产品里换成真实的落地说明（见报告 §3 差异登记）。
        assertEquals("外观只保存在这台设备，不写入项目和预设。", PhoneSettingsText.FOOT_NOTE)
    }

    @Test
    fun theThreeSegmentsKeepTheModeOrder() {
        assertEquals(
            listOf("跟随系统", "浅色", "深色"),
            PhoneSettingsText.MODE_ORDER.map { PhoneSettingsText.themeLabel(it) },
        )
    }
}
