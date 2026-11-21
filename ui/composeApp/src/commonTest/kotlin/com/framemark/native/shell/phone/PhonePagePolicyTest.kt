package com.framemark.native.shell.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 页面状态 (supervisor fix, MID-APK-REPORT §6 item 1).
 *
 * The shell used to pick 编辑页 whenever the tray had a photo, so 返回 and the system
 * Back key had no outermost target and the user could not get back to 首页 / 设置.
 * The page state and every transition now live in [PhonePagePolicy]; these tests
 * walk the exact routes the report asks for.
 */
class PhonePagePolicyTest {

    @Test
    fun theAppStartsOnTheHomePage() {
        assertEquals(PhonePage.Home, PhonePagePolicy.initial())
    }

    @Test
    fun homeToEditorToHomeIsTheFullRoundTrip() {
        var page = PhonePagePolicy.initial()
        assertEquals(PhonePage.Home, page)

        // 选照片大卡片 / 最近编辑 → 编辑页.
        page = PhonePagePolicy.afterPhotosAdded()
        assertEquals(PhonePage.Editor, page)

        // 返回（顶栏或系统 Back）→ 首页.
        val back = PhonePagePolicy.back(page)
        assertEquals(PhonePage.Home, back)
        page = back!!
        assertEquals(PhonePage.Home, page)
    }

    @Test
    fun aProjectThatAlreadyHasPhotosStillGetsBackToTheHome() {
        // The policy never looks at the photo count, so a restored project with
        // photos walks the same route the empty one does.
        val projectHasPhotos = true
        var page = if (projectHasPhotos) PhonePagePolicy.afterPhotosAdded() else PhonePagePolicy.initial()
        assertEquals(PhonePage.Editor, page)
        assertTrue(PhonePagePolicy.handlesBack(page), "the editor must intercept Back")
        page = PhonePagePolicy.back(page)!!
        assertEquals(PhonePage.Home, page)
        assertFalse(PhonePagePolicy.handlesBack(page), "Back on 首页 leaves the app as usual")
        assertNull(PhonePagePolicy.back(page), "nothing left to close on 首页")
    }

    @Test
    fun settingsGoesBackToTheHome() {
        val fromSettings = PhonePagePolicy.back(PhonePage.Settings)
        assertEquals(PhonePage.Home, fromSettings)
        assertTrue(PhonePagePolicy.handlesBack(PhonePage.Settings))
    }

    @Test
    fun aRecentRowThatIsGoneKeepsTheUserOnTheHome() {
        assertEquals(PhonePage.Editor, PhonePagePolicy.afterOpenRecent(photoExists = true))
        assertEquals(PhonePage.Home, PhonePagePolicy.afterOpenRecent(photoExists = false))
    }

    @Test
    fun backOnlyExitsOnTheHomePage() {
        assertNull(PhonePagePolicy.back(PhonePage.Home))
        assertFalse(PhonePagePolicy.handlesBack(PhonePage.Home))
        assertEquals(PhonePage.Home, PhonePagePolicy.back(PhonePage.Editor))
        assertEquals(PhonePage.Home, PhonePagePolicy.back(PhonePage.Settings))
        assertEquals(PhonePage.Settings, PhonePagePolicy.back(PhonePage.About))
        assertTrue(PhonePagePolicy.handlesBack(PhonePage.Editor))
        assertTrue(PhonePagePolicy.handlesBack(PhonePage.Settings))
        assertTrue(PhonePagePolicy.handlesBack(PhonePage.About))
    }

    @Test
    fun theAboutPageGoesBackToSettings() {
        // 关于页是从设置页进来的子页：返回落到设置，而不是一路退回首页。
        assertEquals(PhonePage.Settings, PhonePagePolicy.back(PhonePage.About))
    }

    @Test
    fun thePrivacyPageIsASettingsSubPage() {
        // 设置 → 隐私与权限 → 返回设置。它不再是「关于」里的一段正文。
        assertEquals(PhonePage.Settings, PhonePagePolicy.back(PhonePage.Privacy))
        assertTrue(PhonePagePolicy.handlesBack(PhonePage.Privacy))
    }

    @Test
    fun thereAreExactlyFivePages() {
        assertEquals(5, PhonePage.entries.size)
        assertEquals(
            listOf(
                PhonePage.Home,
                PhonePage.Editor,
                PhonePage.Settings,
                PhonePage.About,
                PhonePage.Privacy,
            ),
            PhonePage.entries.toList(),
        )
    }
}
