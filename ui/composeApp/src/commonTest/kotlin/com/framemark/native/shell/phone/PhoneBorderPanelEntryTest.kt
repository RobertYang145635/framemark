package com.framemark.native.shell.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * P1-3（UX 审查）：「把当前设计用到全部 N 张」以前只存在于导出表单里 —— 用户在边框页
 * 挑完边框，想一次性套到全部照片时找不到入口。
 *
 * 现在边框面板「当前边框」那一行的右侧也有一枚，文案与可见性由这里钉住：
 * 只有一张照片时不画（「用到全部 1 张」是自相矛盾的），两张以上才写出来。
 */
class PhoneBorderPanelEntryTest {

    @Test
    fun the_entry_names_the_real_photo_count() {
        assertEquals("用到全部 12 张", applyToAllLabel(12))
        assertEquals("用到全部 2 张", applyToAllLabel(2))
    }

    @Test
    fun a_single_photo_project_draws_no_entry() {
        assertNull(applyToAllLabel(1))
        assertNull(applyToAllLabel(0))
    }
}
