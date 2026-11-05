package com.framemark.native.product

import com.framemark.native.shell.PhoneProjectStore
import com.framemark.native.shell.SAMPLE_PHOTO_ID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectReadFailureProtectionTest {
    @Test
    fun unreadable_corrupt_and_future_projects_are_never_overwritten() {
        val validBytes = ProjectCodec.encode(sampleProject()).encodeToByteArray()
        val futureBytes =
            """{"document":"framemark.project","schemaVersion":999,"id":"future","photos":[]}"""
                .encodeToByteArray()
        val cases = listOf(
            Triple("corrupt", "{not json".encodeToByteArray(), null),
            Triple("future", futureBytes, null),
            Triple("io", validBytes, "io"),
        )

        cases.forEach { (name, original, unreadable) ->
            val fs = FakeFs("/fm-$name")
            fs.files[fs.projectPath] = original.copyOf()
            if (unreadable != null) fs.unreadablePath = fs.projectPath
            val store = PhoneProjectStore(fs, io = null, platform = null)

            store.load()
            assertTrue(store.projectWritesBlocked, "$name: read failure did not block writes")
            assertNotNull(store.loadError, "$name: read failure was not visible")
            assertNotNull(store.saveError, "$name: protection state was not visible")

            // Two ordinary edit paths; neither may schedule or perform a replacement.
            store.adoptSample()
            store.setDesign(SAMPLE_PHOTO_ID, PhotoDesign.base("simple-4"))
            assertFalse(store.saveNow(), "$name: explicit save bypassed the guard")
            assertFalse(store.flushBlocking(), "$name: flush bypassed the guard")
            assertFalse(store.retryProjectLoad(), "$name: unchanged bad file became writable")

            assertContentEquals(original, fs.files.getValue(fs.projectPath), "$name: original bytes changed")
            assertTrue(fs.renames.isEmpty(), "$name: project file was replaced")
            assertFalse(fs.files.containsKey("${fs.projectPath}.tmp"), "$name: a write was attempted")
        }
    }

    @Test
    fun a_missing_project_still_creates_and_saves_normally() {
        val fs = FakeFs()
        val store = PhoneProjectStore(fs, io = null, platform = null)

        store.load()
        assertFalse(store.projectWritesBlocked)
        assertNull(store.loadError)

        store.adoptSample()
        store.setDesign(SAMPLE_PHOTO_ID, PhotoDesign.base("simple-4"))
        assertTrue(store.saveNow())
        val saved = SessionStore(fs).loadProject().ok()
        assertEquals(listOf(SAMPLE_PHOTO_ID), saved.photos.map { it.id })
        assertTrue(saved.photos.single().path.isNotBlank(), "sample needs a schema-valid persisted path")

        val reopened = PhoneProjectStore(fs, io = null, platform = null)
        reopened.load()
        assertFalse(reopened.projectWritesBlocked)
        assertEquals(SAMPLE_PHOTO_ID, reopened.activePhotoId)
        assertTrue(reopened.activePhoto!!.path.isEmpty())
        assertEquals("simple-4", reopened.activeDesign!!.cardId)
    }

    @Test
    fun explicit_retry_recovers_after_the_source_file_is_fixed() {
        val fs = FakeFs()
        val original = "{not json".encodeToByteArray()
        fs.files[fs.projectPath] = original.copyOf()
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.load()
        assertTrue(store.projectWritesBlocked)

        fs.files[fs.projectPath] = ProjectCodec.encode(sampleProject()).encodeToByteArray()
        assertTrue(store.retryProjectLoad())
        assertFalse(store.projectWritesBlocked)
        assertNull(store.loadError)
        assertEquals(sampleProject(), store.project)
    }

    @Test
    fun applying_a_preset_reports_a_failed_final_save() {
        val fs = FakeFs()
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.load()
        val photoId = store.idForPath("/photos/preset.jpg")
        store.adoptPaths(listOf("/photos/preset.jpg"), activePath = "/photos/preset.jpg")
        store.setDesign(photoId, PhotoDesign.base("simple-4"))
        assertTrue(store.saveNow())
        val before = fs.files.getValue(fs.projectPath).copyOf()

        fs.failWritePath = "${fs.projectPath}.tmp"
        assertFalse(store.applyPreset(samplePreset(), styleId = "simple-4"))
        assertNotNull(store.presetError)
        assertContentEquals(before, fs.files.getValue(fs.projectPath))
    }
}
