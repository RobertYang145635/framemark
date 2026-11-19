package com.framemark.native.shell

import com.framemark.native.product.FakeFs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * P0-1（UX 审查）：内置样张不是用户的照片。
 *
 * 它没有文件路径，`NativeEngine.export(photoPath = null)` 于是渲染应用自带的山景照 —
 * 用户以为导出了自己的图，拿到的却是别人的照片。这一组测试把出口钉住：
 * 空工程与样张都在导出之前被一句人话拒绝，真实照片不受影响。
 */
class SamplePhotoExportGuardTest {

    private fun store(): PhoneProjectStore {
        val store = PhoneProjectStore(FakeFs(), io = null, platform = null)
        store.load()
        return store
    }

    @Test
    fun an_empty_project_says_pick_a_photo_first() {
        assertEquals("先选择一张照片，再导出。", store().exportRefusal())
    }

    @Test
    fun the_sample_photo_is_refused_in_words_a_user_can_act_on() {
        val store = store()
        store.adoptSampleFixture()
        store.select(SAMPLE_PHOTO_ID)

        assertEquals(SAMPLE_EXPORT_REFUSAL, store.exportRefusal())
        assertEquals("当前是示例照片，请先导入自己的照片再导出。", SAMPLE_EXPORT_REFUSAL)
    }

    @Test
    fun a_real_photo_is_not_refused() {
        val store = store()
        store.adoptPaths(listOf("/photos/mine.jpg"), activePath = "/photos/mine.jpg")

        assertNull(store.exportRefusal())
    }

    @Test
    fun a_real_photo_never_drags_the_sample_into_the_project() {
        val store = store()
        store.adoptPaths(listOf("/photos/mine.jpg"), activePath = "/photos/mine.jpg")

        assertEquals(0, store.photos.count { it.id == SAMPLE_PHOTO_ID })
        assertEquals(listOf("/photos/mine.jpg"), store.photos.map { it.path })
    }
}
