package com.framemark.native.workflow

import com.framemark.native.product.FakeFs
import com.framemark.native.product.PhotoDesign
import com.framemark.native.product.Project
import com.framemark.native.product.SessionStore
import com.framemark.native.product.StoreResult
import com.framemark.native.product.photoRef
import com.framemark.native.shell.PhoneProjectStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M01 batch contract (red proof): an already-existing photo whose own binding is
 * missing is "未应用边框" and must never borrow the shared / selected / active design
 * when the 「按照片批量」 entry builds its candidates.
 *
 * `PhoneProjectStore.photoEntries()` is the source of those candidates for the phone
 * (`PhoneShell.buildPhotoBatch()` turns each non-null design into a [BatchCandidate]),
 * so a photo whose own design is missing must surface with `design == null`.
 *
 * This file only touches APIs that exist on the unfixed baseline, so the failing case
 * is a real assertion failure rather than a compile error.
 */
class PhotoBatchDesignPreflightTest {
    private fun storeWith(aBound: Boolean): PhoneProjectStore {
        val project = Project(
            id = "m01",
            photos = listOf(photoRef("a"), photoRef("b", name = "b.jpg")),
            activePhotoId = "a",
            // photo "a" is explicitly bound; photo "b" is deliberately left unbound.
            designs = if (aBound) mapOf("a" to PhotoDesign.base("card-A")) else emptyMap(),
            // Both a shared design and a selected card exist — a batch export must NOT
            // lend either to photo "b", which the user left without a border.
            sharedDesign = PhotoDesign.base("card-SHARED"),
            selectedCardId = "card-SELECTED",
        )
        val fs = FakeFs()
        assertTrue(SessionStore(fs).saveProject(project) is StoreResult.Ok)
        return PhoneProjectStore(fs, io = null, platform = null).also { it.load() }
    }

    /** Candidate ids the 「按照片批量」 entry would actually export. */
    private fun candidateIds(store: PhoneProjectStore): List<String> =
        store.photoEntries().filter { it.design != null }.map { it.photo.id }

    @Test
    fun photo_without_its_own_binding_is_not_a_batch_candidate() {
        val store = storeWith(aBound = true)
        assertNull(store.designOf("b"), "照片 b 没有自己的设计绑定")

        val candidates = candidateIds(store)

        assertTrue("a" in candidates, "有绑定的 a 必须可批量导出，实际=$candidates")
        assertFalse(
            "b" in candidates,
            "无绑定的 b 不得借用 sharedDesign/selectedCardId 进入批量候选，实际=$candidates",
        )
    }

    @Test
    fun every_bound_photo_is_a_candidate() {
        val project = Project(
            id = "m01-all",
            photos = listOf(photoRef("a"), photoRef("b", name = "b.jpg")),
            activePhotoId = "a",
            designs = mapOf("a" to PhotoDesign.base("card-A"), "b" to PhotoDesign.base("card-B")),
            selectedCardId = "card-SELECTED",
        )
        val fs = FakeFs()
        assertTrue(SessionStore(fs).saveProject(project) is StoreResult.Ok)
        val store = PhoneProjectStore(fs, io = null, platform = null).also { it.load() }

        assertEquals(listOf("a", "b"), candidateIds(store))
    }
}
