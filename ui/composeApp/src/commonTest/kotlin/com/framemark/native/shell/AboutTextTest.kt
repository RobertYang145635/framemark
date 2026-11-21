package com.framemark.native.shell

import com.framemark.native.shell.phone.PhoneSettingsText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 关于页文案（颜色方案 §4.4 / InkFrame 复盘第 9–10 条）。
 *
 * 这里只钉两件事：每一节都有内容；文案里的事实陈述和安装包一致 —— Android 清单没有
 * 联网 / 相册 / 存储权限，所以「不联网、不上传」不是一句口号，而是没有权限可用。
 */
class AboutTextTest {

    @Test
    fun everySectionHasCopy() {
        assertEquals("关于 FrameMark", AboutText.TITLE)
        assertEquals("版本", AboutText.VERSION_ROW)
        assertEquals("隐私", AboutText.PRIVACY_TITLE)
        assertEquals("权限", AboutText.PERMISSIONS_TITLE)
        assertEquals("许可与版权", AboutText.LICENSE_TITLE)
        assertTrue(AboutText.PRIVACY.isNotBlank())
        assertTrue(AboutText.PERMISSIONS.isNotBlank())
        assertTrue(AboutText.LICENSE_NOTE.isNotBlank())
    }

    @Test
    fun privacyAndPermissionsStateTheActualBehaviour() {
        // 清单只有 INTERNET（检查更新），没有 READ_MEDIA_*/READ_EXTERNAL_STORAGE。
        assertTrue("检查更新" in AboutText.PRIVACY, AboutText.PRIVACY)
        assertTrue("不会上传" in AboutText.PRIVACY, AboutText.PRIVACY)
        assertTrue("系统照片选择器" in AboutText.PERMISSIONS, AboutText.PERMISSIONS)
        assertTrue("网络权限" in AboutText.PERMISSIONS, AboutText.PERMISSIONS)
        assertTrue("不申请相册" in AboutText.PERMISSIONS, AboutText.PERMISSIONS)
    }

    @Test
    fun everyLicenseNamesItsComponentAndLicense() {
        assertTrue(AboutText.LICENSES.isNotEmpty())
        AboutText.LICENSES.forEach { entry ->
            assertTrue(entry.name.isNotBlank(), "empty name in $entry")
            assertTrue(entry.license.isNotBlank(), "empty license in $entry")
            assertTrue(entry.purpose.isNotBlank(), "empty purpose in $entry")
        }
        assertTrue(AboutText.LICENSES.any { "Compose Multiplatform" in it.name })
        assertTrue(AboutText.LICENSES.any { "Apache" in it.license })
    }

    @Test
    fun theSettingsEntryAndBackLabelAreTheDocumentedCopy() {
        // 「关于」不再把隐私权限一起吞进去：两行入口，各自标题就是名字。
        assertEquals("关于与许可", PhoneSettingsText.ABOUT_ROW)
        assertEquals("隐私与权限", PhoneSettingsText.PRIVACY_ROW)
        assertEquals("隐私与权限", PhoneSettingsText.PRIVACY_PAGE_TITLE)
        assertEquals(AboutText.PRIVACY_ENTRY, PhoneSettingsText.PRIVACY_ROW)
        assertEquals("返回设置", PhoneSettingsText.BACK_TO_SETTINGS)
        assertEquals("返回", AboutText.SUB_BACK)
    }

    /**
     * 返回键先关「关于」里的二级页（打赏 / 支持与反馈 / 隐私与权限），再退页面。
     * 以前这些二级页只是 `AboutContent` 内部的布尔量，外壳按 `PhonePage.About`
     * 一步退回设置首页。
     */
    @Test
    fun aSubPageOfAboutConsumesOneBackPress() {
        assertEquals(AboutSubPage.None, AboutPolicy.back(AboutSubPage.Feedback))
        assertEquals(AboutSubPage.None, AboutPolicy.back(AboutSubPage.Donate))
        assertEquals(AboutSubPage.None, AboutPolicy.back(AboutSubPage.Privacy))
        assertNull(AboutPolicy.back(AboutSubPage.None), "根上要交给页面栈，不是再吞一次")
    }
}
