package com.framemark.native.shell

import com.framemark.native.library.LibraryCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 恢复工程时右栏的点亮（WP-Q2⑥ 摩擦一）：重新打开已保存的工程，右栏不该写
 * 「未选择边框」，而底部胶片条已经显示「3/3 张已选边框」。
 */
class DesktopSelectionTest {

    private fun card(id: String) = LibraryCard(
        id = id,
        name = id,
        category = "text",
        categoryName = "文字边框",
        brand = null,
        vendor = null,
        family = null,
        fidelity = null,
        badge = null,
        en = id.uppercase(),
        systemStyle = null,
        previewSourcePath = "previews/$id.webp",
        previewFile = "$id.webp",
    )

    @Test
    fun the_saved_design_selects_its_card_again() {
        val cards = listOf(card("wm-classic"), card("wm-canon"))
        assertEquals("wm-canon", restoredCardOf("wm-canon", cards)?.id)
        assertEquals("wm-classic", restoredCardOf("wm-classic", cards)?.id)
    }

    @Test
    fun no_design_or_a_retired_card_stays_unselected() {
        val cards = listOf(card("wm-classic"))
        assertNull(restoredCardOf(null, cards))
        assertNull(restoredCardOf("", cards))
        // 这张卡下架/改名后不能点亮一张不存在的卡。
        assertNull(restoredCardOf("stock-oppo-retired", cards))
    }
}
