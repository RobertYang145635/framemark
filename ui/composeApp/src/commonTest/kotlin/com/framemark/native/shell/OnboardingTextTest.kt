package com.framemark.native.shell

import com.framemark.native.shell.phone.PhoneSettingsText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 新手教程的文案（四页、入口、按钮）。纯常量，所以不用起界面就能钉住：
 * 页数与顺序、每页都有标题和说明、按钮用词、以及两端入口读的是同一份名字。
 */
class OnboardingTextTest {

    @Test
    fun thereAreFourPagesInTheOrderOfTheRealWorkflow() {
        assertEquals(
            listOf("导入照片", "套用边框", "调参数", "导出"),
            OnboardingText.PAGES.map { it.title },
        )
        assertEquals(4, OnboardingText.PAGES.size)
    }

    @Test
    fun everyPageCarriesATitleAndAShortBody() {
        OnboardingText.PAGES.forEach { page ->
            assertTrue(page.title.isNotBlank(), "空标题：$page")
            assertTrue(page.body.isNotBlank(), "空说明：$page")
            // 一句话的教程页不需要长段；超过 80 字就该拆页而不是挤在卡片里。
            assertTrue(page.body.length <= 80, "说明太长（${page.body.length} 字）：${page.title}")
        }
    }

    @Test
    fun theCopyKeepsOneSentencePerIdeaWithoutExclamationMarks() {
        // 要求：中文、简短、不要感叹号堆叠。
        OnboardingText.PAGES.forEach { page ->
            assertFalse(page.body.contains("！"), "不该用感叹号：${page.title}")
            assertFalse(page.body.contains("!"), "不该用感叹号：${page.title}")
            assertTrue(page.body.endsWith("。"), "说明该是完整的句子：${page.title}")
        }
        listOf(OnboardingText.TITLE, OnboardingText.SKIP, OnboardingText.NEXT, OnboardingText.DONE)
            .forEach { assertFalse(it.contains("！")) }
    }

    @Test
    fun theBottomEntriesAreTheDocumentedFour() {
        assertEquals("新手教程", OnboardingText.TITLE)
        assertEquals("跳过", OnboardingText.SKIP)
        assertEquals("下一步", OnboardingText.NEXT)
        assertEquals("开始使用", OnboardingText.DONE)
        assertEquals("1 / 4", OnboardingText.counter(0, OnboardingText.PAGES.size))
        assertEquals("4 / 4", OnboardingText.counter(3, OnboardingText.PAGES.size))
    }

    @Test
    fun phoneAndDesktopEntriesReadTheSameName() {
        // 手机设置页那一行、关于页那一行、桌面齿轮弹层那一行，都是这一个名字。
        assertEquals(OnboardingText.ENTRY, PhoneSettingsText.ONBOARDING_ROW)
        assertEquals(OnboardingText.ENTRY_HINT, PhoneSettingsText.ONBOARDING_HINT)
        assertEquals("新手教程", OnboardingText.ENTRY)
    }

    @Test
    fun eachStepNamesAButtonThatActuallyExists() {
        // 文案里提到的入口都必须是产品里真有的按钮名，别写一个点不到的东西。
        val copy = OnboardingText.PAGES.joinToString(" ") { it.body }
        listOf("相册", "系列", "边框", "样式", "文字", "Logo", "水印", "导出", "批量").forEach { word ->
            assertTrue(copy.contains(word), "四页说明里少了真实按钮「$word」")
        }
    }
}
