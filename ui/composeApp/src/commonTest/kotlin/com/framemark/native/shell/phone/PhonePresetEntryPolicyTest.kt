package com.framemark.native.shell.phone

import com.framemark.native.shell.EditorTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 手机「预设」入口与两个主操作的纯契约。
 *
 * 验收人反馈「保存预设和使用预设我没看到」：旧入口只在 **边框** 页头的折叠箭头
 * 长按菜单里，换个工具就完全看不到。这里的断言锁住三件事：
 *  1. 入口对每个工具都出现（不再和 边框 绑定）；
 *  2. 「保存为预设」只在当前照片有设计时可点；
 *  3. 「套用」要求有一张活动照片，且保存成功后有名字 + 内嵌水印大小的回音。
 */
class PhonePresetEntryPolicyTest {

    @Test
    fun theEntryIsOfferedOnEveryToolNotOnlyOnBorder() {
        assertEquals("预设", PhonePresetEntryPolicy.ACTION_LABEL, "验收人要找的就是这两个字")
        EditorTool.entries.forEach { tool ->
            assertTrue(
                PhonePresetEntryPolicy.showsAction(tool),
                "$tool 页上也必须有预设入口：保存的是整套设计，不该要求先切回 边框",
            )
        }
    }

    @Test
    fun saveIsEnabledOnlyWhenTheActivePhotoHasADesign() {
        assertFalse(
            PhonePresetEntryPolicy.saveEnabled(hasActiveDesign = false),
            "没有设计就没有可保存的东西",
        )
        assertTrue(PhonePresetEntryPolicy.saveEnabled(hasActiveDesign = true))
        assertEquals("保存为预设", PhonePresetEntryPolicy.SAVE_LABEL)
        assertEquals("应用预设", PhonePresetEntryPolicy.APPLY_TITLE)
    }

    @Test
    fun applyRequiresAnActivePhoto() {
        assertFalse(
            PhonePresetEntryPolicy.applyEnabled(hasActivePhoto = false),
            "没有活动照片时套用按钮必须禁用，而不是点了没反应",
        )
        assertTrue(PhonePresetEntryPolicy.applyEnabled(hasActivePhoto = true))
    }

    @Test
    fun savedMessageNamesThePresetAndItsEmbeddedWatermark() {
        assertEquals(
            "已保存「白边 + 签名」（无水印）",
            PhonePresetEntryPolicy.savedMessage("白边 + 签名", watermarkBytes = 0),
        )
        assertEquals(
            "已保存「白边 + 签名」，含水印 512 B",
            PhonePresetEntryPolicy.savedMessage("白边 + 签名", watermarkBytes = 512),
        )
        assertEquals(
            "已保存「白边 + 签名」，含水印 2 KB",
            PhonePresetEntryPolicy.savedMessage("白边 + 签名", watermarkBytes = 2048),
        )
    }
}
