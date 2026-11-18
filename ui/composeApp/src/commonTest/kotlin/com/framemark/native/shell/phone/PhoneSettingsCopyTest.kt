package com.framemark.native.shell.phone

import com.framemark.native.theme.AppearancePanelText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 设置页文案（WP-PV1 §4.2 + 配色方案 §8）：组标题、外观三段的用词与顺序、
 * 版本行与页脚。外观面板本身是双端共用的，所以标签直接从
 * [AppearancePanelText] 读，设置页不再自存一份。
 */
class PhoneSettingsCopyTest {

    @Test
    fun theSettingsPageKeepsItsTwoGroupTitles() {
        assertEquals("设置", PhoneSettingsText.PAGE_TITLE)
        assertEquals("外观", PhoneSettingsText.GROUP_APPEARANCE)
        assertEquals("版本", PhoneSettingsText.ROW_VERSION)
    }

    @Test
    fun theAppearanceSectionsAreTheDocumentedThree() {
        assertEquals("界面配色", AppearancePanelText.PALETTE_SECTION)
        assertEquals("明暗模式", AppearancePanelText.MODE_SECTION)
        assertEquals("画布背景", AppearancePanelText.STAGE_SECTION)
        assertEquals("恢复默认外观", AppearancePanelText.RESET)
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
        assertEquals(
            listOf("跟随系统", "浅色", "深色"),
            AppearancePanelText.MODE_ORDER.map { AppearancePanelText.modeLabel(it) },
        )
    }

    @Test
    fun theThreeStageTonesAreDarkMidLight() {
        assertEquals(
            listOf("深灰", "中灰", "浅灰"),
            AppearancePanelText.TONE_ORDER.map { AppearancePanelText.toneLabel(it) },
        )
    }

    @Test
    fun eachPaletteHasItsDocumentedNameAndTagline() {
        assertEquals(
            listOf("暖灰墨绿", "石墨琥珀", "瓷白靛青", "纸白陶土", "经典蓝", "青瓷竹影", "夜航琥珀"),
            com.framemark.native.theme.PaletteId.entries.map { it.label },
        )
        assertEquals(
            listOf("自然安静", "沉稳精确", "清爽有序", "温暖手作", "经典直接", "清透安静", "温暖克制"),
            com.framemark.native.theme.PaletteId.entries.map { AppearancePanelText.tagline(it) },
        )
    }
}
