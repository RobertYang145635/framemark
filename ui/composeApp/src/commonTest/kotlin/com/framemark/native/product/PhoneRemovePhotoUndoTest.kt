package com.framemark.native.product

import com.framemark.native.shell.PhoneProjectStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 手机删照片不弹确认框（2026-11-20 决定）：原件留在 Originals 里，丢的是这张照片的
 * 设计，所以这一下必须能被舞台那条「已移除 · 撤销」原样救回来 —— 同一个位置、同一份
 * 设计（边框 + 参数 + 文字 + Logo）、同一枚内嵌水印。
 */
class PhoneRemovePhotoUndoTest {

    private fun store(vararg paths: String): Pair<PhoneProjectStore, List<String>> {
        val fs = FakeFs()
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.load()
        val ids = store.adoptPaths(paths.toList(), activePath = paths.last())
        return store to ids
    }

    @Test
    fun undoPutsThePhotoBackWithItsDesignAndWatermark() {
        val (store, ids) = store("/photos/a.jpg", "/photos/b.jpg")
        val design = PhotoDesign.base("glass-card-4", styleId = "glass")
            .withTheme("cream")
            .withRatio("4:5")
            .withFontScale(1.2)
            .withLogo("oppo_black")
            .withParam("footerPct", 27)
            .withText("model", "OPPO Find X6 Pro")
            .withOverlay(watermarkSpec(seed = 3, size = 1024))
        store.setDesign(ids[1], design)

        val removed = assertNotNull(store.removeWithSnapshot(ids[1]))
        assertEquals(removed.design, design, "快照必须带着被删那张的设计")
        assertEquals(1, store.photos.size)

        store.restoreRemoved(removed)

        assertEquals(2, store.photos.size, "撤销把照片放回工程")
        assertEquals(2, store.photos.indexOfFirst { it.id == ids[1] } + 1, "回到原来的位置（第 2 张）")
        assertEquals(design, store.designOf(ids[1]), "边框 / 参数 / 文字 / Logo / 水印一模一样")
        assertEquals(ids[1], store.activePhotoId, "放回后重新选上这张")
        assertEquals(
            design,
            SessionStore(store.fileSystem).loadProject().ok()?.designs?.get(ids[1]),
            "撤销同样落盘：重启之后还是这一份",
        )
    }

    @Test
    fun removingAPhotoWithoutADesignStillRoundTrips() {
        val (store, ids) = store("/photos/a.jpg", "/photos/b.jpg")
        val removed = assertNotNull(store.removeWithSnapshot(ids[0]))
        assertNull(removed.design, "没设计过的照片：快照里就没有设计")

        store.restoreRemoved(removed)
        assertEquals(2, store.photos.size)
        assertEquals(0, store.photos.indexOfFirst { it.id == ids[0] }, "放回第 1 位")
        assertNull(store.designOf(ids[0]), "不凭空造一份设计")
    }

    @Test
    fun removingTheOnlyPhotoLeavesAnEmptyProjectAndUndoRestoresIt() {
        val (store, ids) = store("/photos/a.jpg")
        store.setDesign(ids[0], PhotoDesign.base("wm-classic", styleId = null))

        val removed = assertNotNull(store.removeWithSnapshot(ids[0]))
        assertTrue(store.photos.isEmpty(), "删光了就是空工程（首屏回到「选择一个照片」）")
        assertNull(store.activePhotoId)

        store.restoreRemoved(removed)
        assertEquals(1, store.photos.size)
        assertEquals(ids[0], store.activePhotoId)
    }

    @Test
    fun removingAnUnknownPhotoChangesNothing() {
        val (store, _) = store("/photos/a.jpg")
        assertNull(store.removeWithSnapshot("photo-does-not-exist"))
        assertEquals(1, store.photos.size)
    }
}
