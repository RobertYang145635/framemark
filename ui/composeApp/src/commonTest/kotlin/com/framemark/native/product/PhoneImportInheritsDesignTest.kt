package com.framemark.native.product

import com.framemark.native.shell.PhoneProjectStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 往已有工程里再导入照片：新照片拿到**工程现在的样子**（2026-11-20 决定）。
 *
 * 工程本身的意思就是「一套照片一个样子」：`用到全部 N 张`、批量导出都建立在这个前提上。
 * 不继承的话，同一场拍的 5 张要在边框库里点 5 次 —— 正是最该省掉的那次重复劳动。
 * 唯一的例外是工程里本来就没有设计（用户把边框设成「未选边框」）：那时新照片也不带。
 */
class PhoneImportInheritsDesignTest {

    @Test
    fun theSecondPhotoStartsFromTheDesignOnScreen() {
        val store = PhoneProjectStore(FakeFs(), io = null, platform = null)
        store.load()
        val ids = store.adoptPaths(listOf("/photos/a.jpg"), activePath = "/photos/a.jpg")
        val design = PhotoDesign.base("glass-card-4", styleId = "glass")
            .withTheme("cream")
            .withRatio("4:5")
            .withFontScale(1.18)
            .withLogo("oppo_black")
            .withParam("footerPct", 27)
            .withText("model", "OPPO Find X6 Pro")
        store.setDesign(ids[0], design)

        store.adoptPaths(listOf("/photos/b.jpg"), activePath = "/photos/b.jpg")
        val second = assertNotNull(store.activePhotoId)
        assertEquals(
            design.withoutOverlay(),
            store.designOf(second),
            "同一场拍的第二张：边框、参数、文字、Logo 都一样（水印按 US-04 不继承）",
        )
    }

    @Test
    fun aBorderlessProjectDoesNotHandOutABorder() {
        val store = PhoneProjectStore(FakeFs(), io = null, platform = null)
        store.load()
        val ids = store.adoptPaths(listOf("/photos/a.jpg"), activePath = "/photos/a.jpg")
        // 「未选边框」= 这张照片没有设计（工程里也没有 selectedCardId）。
        store.remove(ids[0])

        val ids2 = store.adoptPaths(listOf("/photos/b.jpg", "/photos/c.jpg"), activePath = "/photos/c.jpg")
        assertNull(store.designOf(ids2.first()), "空工程导入：没有边框可继承，首屏还是「选择一个边框」")
        assertNull(store.designOf(ids2.last()))
    }
}
