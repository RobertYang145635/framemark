package com.framemark.native.shell

import com.framemark.native.product.PhotoRef
import com.framemark.native.product.Project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The background fingerprint pass must not be able to undo a user edit.
 *
 * `adoptPaths` registers photos with `fingerprint = null` and hashes them on the
 * store's IO scope; the decision of *what* to hash and the patch that writes the
 * result back are pure functions, pinned here. The properties that matter: a photo
 * that is gone (or has no file path) is never hashed, an existing digest is never
 * overwritten, and a late patch cannot resurrect a photo removed meanwhile.
 */
class PhoneProjectFingerprintTest {

    private fun photo(id: String, path: String = "/photos/$id.jpg", fingerprint: String? = null) =
        PhotoRef(id = id, path = path, displayName = "$id.jpg", fingerprint = fingerprint)

    private fun project(vararg photos: PhotoRef) = Project(id = "phone", photos = photos.toList())

    @Test
    fun only_photos_that_are_present_and_unhashed_need_a_fingerprint() {
        val project = project(
            photo("a"),
            photo("b", fingerprint = "fp-b"),
            PhotoRef(SAMPLE_PHOTO_ID, "", "示例照片"),
        )

        val targets = photosNeedingFingerprint(project, listOf("a", "b", SAMPLE_PHOTO_ID))

        assertEquals(listOf("a"), targets.map { it.id })
    }

    @Test
    fun a_removed_photo_is_never_hashed_and_never_comes_back() {
        val before = project(photo("a"), photo("b"))
        val removedId = before.photos.last().id
        val afterRemoval = before.removePhoto(removedId)

        assertEquals(
            0,
            photosNeedingFingerprint(afterRemoval, listOf(removedId)).size,
            "已被移除的照片不应再被哈希",
        )
        val patched = afterRemoval.withFingerprints(mapOf(removedId to "fp-late", "a" to "fp-a"))
        assertEquals(listOf("a"), patched.photos.map { it.id }, "补丁不能复活已移除的照片")
        assertEquals("fp-a", patched.photos.single().fingerprint)
    }

    @Test
    fun a_late_patch_does_not_overwrite_an_existing_fingerprint() {
        val project = project(photo("a", fingerprint = "first"))

        val patched = project.withFingerprints(mapOf("a" to "second"))

        assertSame(project, patched, "没有字段变化时应返回同一个对象")
        assertEquals("first", patched.photos.single().fingerprint)
    }

    @Test
    fun a_photo_without_a_file_path_is_never_hashed() {
        val project = project(PhotoRef(SAMPLE_PHOTO_ID, "", "示例照片"))
        assertTrue(photosNeedingFingerprint(project, listOf(SAMPLE_PHOTO_ID)).isEmpty())
    }

    @Test
    fun an_id_outside_the_project_is_ignored() {
        val project = project(photo("a"))
        assertTrue(photosNeedingFingerprint(project, listOf("not-in-project")).isEmpty())
        assertTrue(photosNeedingFingerprint(project, emptyList()).isEmpty())
    }
}
