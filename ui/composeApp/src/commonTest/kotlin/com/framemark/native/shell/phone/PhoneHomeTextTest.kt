package com.framemark.native.shell.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * WP-PV1 §4.2 首页文案：`phone.js buildHome()` 的句子与行格式逐条钉住，界面只读
 * [PhoneHomeText]，所以"文案和演示稿不一样"在 commonTest 里就能挡住。
 */
class PhoneHomeTextTest {

    @Test
    fun theHomeCopyIsTheDemoCopy() {
        assertEquals("框印", PhoneHomeText.WORDMARK)
        assertEquals("给照片加上相机边框与参数水印", PhoneHomeText.SUBTITLE)
        assertEquals("从相册选择照片", PhoneHomeText.HERO_TITLE)
        assertEquals("可多选，每张照片单独保存设计", PhoneHomeText.HERO_SUBTITLE)
        assertEquals("选照片", PhoneHomeText.HERO_ACTION)
        assertEquals("批量", PhoneHomeText.BATCH_TITLE)
        assertEquals("多张照片一起加边框", PhoneHomeText.BATCH_SUBTITLE)
        assertEquals("预设", PhoneHomeText.PRESETS_TITLE)
        assertEquals("最近编辑", PhoneHomeText.RECENT_TITLE)
        assertEquals("继续最近编辑", PhoneHomeText.RECENT_ALL)
        assertEquals("设置", PhoneHomeText.SETTINGS_LABEL)
    }

    @Test
    fun thePresetSubtitleCountsTheSavedCombinations() {
        assertEquals("4 个常用组合", PhoneHomeText.presetSubtitle(4))
        assertEquals("1 个常用组合", PhoneHomeText.presetSubtitle(1))
        // 0 个时不能印「0 个常用组合」，退回标题词。
        assertEquals("常用组合", PhoneHomeText.presetSubtitle(0))
    }

    @Test
    fun theThemeNameComesFromTheCardVocabulary() {
        assertEquals("白色", PhoneHomeText.themeName("white"))
        assertEquals("墨色", PhoneHomeText.themeName("ink"))
        assertEquals("墨色", PhoneHomeText.themeName(" ink "))
        // 未知 id 不印 id，也不印「未知主题（x）」。
        assertNull(PhoneHomeText.themeName("no-such-theme"))
        assertNull(PhoneHomeText.themeName(null))
        assertNull(PhoneHomeText.themeName("   "))
    }

    @Test
    fun theRecentRowReadsBorderThenTheme() {
        val live = PhoneRecentEdit(
            photoId = "p1",
            path = "C:/p1.jpg",
            displayName = "P1070512.jpg",
            borderName = "经典双行",
            index = 1,
            count = 2,
            themeKey = "white",
        )
        assertEquals("经典双行 · 白色", PhoneHomeText.recentSubtitle(live))
        val ink = live.copy(index = 2, themeKey = "ink")
        assertEquals("经典双行 · 墨色", PhoneHomeText.recentSubtitle(ink))
    }

    @Test
    fun theRecentRowOmitsWhatItDoesNotKnow() {
        // 没有主题覆盖：只印边框名。
        val noTheme = PhoneRecentEdit("p2", "C:/p2.jpg", "IMG_1.jpg", borderName = "白边")
        assertEquals("白边", PhoneHomeText.recentSubtitle(noTheme))
        // 未知主题：也不把 id 印出来。
        assertEquals("白边", PhoneHomeText.recentSubtitle(noTheme.copy(themeKey = "mystery")))
        // 没有边框：退回既有措辞，绝不空着。
        val noBorder = PhoneRecentEdit("p3", null, "IMG_2.jpg")
        assertEquals("未应用边框", PhoneHomeText.recentSubtitle(noBorder))
    }

    @Test
    fun theRightColumnIsThePositionOnlyWhenThereAreSeveralPhotos() {
        val second = PhoneRecentEdit("p1", "C:/p1.jpg", "A.jpg", index = 2, count = 3)
        assertEquals("2/3", PhoneHomeText.recentPosition(second))
        val single = PhoneRecentEdit("p1", "C:/p1.jpg", "A.jpg", index = 1, count = 1)
        assertEquals("", PhoneHomeText.recentPosition(single))
    }
}
