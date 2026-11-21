package com.framemark.native.product

import com.framemark.native.shell.PhoneProjectStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 往已有工程里再导入照片：新照片沿用**边框**，但设置回到该边框的默认值（2026-11-21 决定）。
 *
 * 变更原因（用户反馈）：以前新照片整套复制上一张的设计（参数、文字、主题、Logo、字号），
 * 结果「导入一张新照片，它自动带上了上一张改过的东西」，看起来像 bug。
 *
 * 现在的规则：
 *  - 边框（cardId + styleId）沿用 —— 同一场拍的多张不用再点一次「选择一个边框」；
 *  - 参数 / 文字 / 主题 / 字号 / Logo 一律回到**该边框的默认值**，用户改过的值不会悄悄复制；
 *  - 水印从不继承（US-04，只经「应用到全部」传播）；
 *  - 工程里没有边框时（用户把边框设成「未选边框」）新照片也不带 —— 除非外壳注入了
 *    兜底边框（[PhoneProjectStore.fallbackCard]），那时用兜底那张。
 */
class PhoneImportInheritsDesignTest {

    @Test
    fun theSecondPhotoKeepsTheBorderButStartsFromItsDefaults() {
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
        val inherited = assertNotNull(store.designOf(second))
        assertEquals("glass-card-4", inherited.cardId, "边框沿用：导入就有边框可看")
        assertEquals("glass", inherited.styleId)
        assertNull(inherited.theme, "主题回到该边框默认值")
        assertNull(inherited.fontScale, "字号回到默认")
        assertNull(inherited.logo, "Logo 回到默认（跟随照片品牌那条路照旧）")
        assertNull(inherited.params["footerPct"], "参数不复制上一张改过的值")
        assertNull(inherited.text["model"], "文字不复制上一张改过的值")
        assertNull(inherited.overlay, "水印按 US-04 不继承")
        assertNull(inherited.ratio, "输出比例也属于上一张的设置，回到默认（原比例）")
    }

    @Test
    fun aFallbackBorderFillsInWhenNothingIsChosenYet() {
        val store = PhoneProjectStore(FakeFs(), io = null, platform = null)
        store.load()
        store.fallbackCard = { "stock-xiaomi-classic-22" to null }
        val ids = store.adoptPaths(listOf("/photos/a.jpg"), activePath = "/photos/a.jpg")

        val design = assertNotNull(store.designOf(ids.first()), "全新工程导入：自动给兜底边框，不落在空态")
        assertEquals("stock-xiaomi-classic-22", design.cardId)
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
