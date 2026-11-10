package com.framemark.native.shell

import com.framemark.native.product.FakeFs
import com.framemark.native.product.ProductFileSystem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The phone store owns a scope, a save channel and one long-lived autosave worker.
 * A re-install used to drop the previous store without stopping any of them, so the
 * old worker kept flushing a store nobody reads. These tests pin the lifecycle:
 * `dispose()` flushes the pending edit, then stops scheduling and writing; `install()`
 * disposes the store it replaces; and neither path can throw.
 */
class PhoneProjectStoreDisposeTest {

    /** Counts project-document writes (the temp file, which precedes the replace). */
    private class CountingFs(private val base: ProductFileSystem) : ProductFileSystem by base {
        var projectWrites = 0
            private set

        override fun writeBytes(path: String, bytes: ByteArray): Boolean {
            if (path.endsWith("project.json.tmp")) projectWrites++
            return base.writeBytes(path, bytes)
        }
    }

    @Test
    fun dispose_flushes_the_pending_save_and_stops_the_autosave_worker() = runBlocking {
        val fs = CountingFs(FakeFs())
        val store = PhoneProjectStore(fs, io = null, platform = null)
        store.load()

        val beforeMutation = fs.projectWrites
        store.adoptSample()
        assertEquals(beforeMutation, fs.projectWrites, "编辑只登记待保存，不立即写盘")

        store.dispose()
        assertEquals(beforeMutation + 1, fs.projectWrites, "dispose 必须同步落盘一次待保存的修改")
        val afterDispose = fs.projectWrites

        // A disposed store refuses work instead of throwing, and reschedules nothing.
        store.adoptPaths(listOf("/photos/after-dispose.jpg"), activePath = null)
        assertFalse(store.saveNow(), "已 dispose 的 store 不再写盘")
        delay(700) // longer than the 400 ms autosave debounce
        assertEquals(afterDispose, fs.projectWrites, "dispose 之后 worker 不应再落盘")

        store.dispose() // idempotent
        assertFalse(store.saveNow())
    }

    @Test
    fun installing_a_new_store_disposes_the_previous_holder() {
        val previousFs = CountingFs(FakeFs())
        val previous = PhoneProjectStore(previousFs, io = null, platform = null)
        previous.load()
        previous.adoptSample()
        PhoneProjectRuntime.install(previous)

        val next = PhoneProjectStore(CountingFs(FakeFs()), io = null, platform = null)
        PhoneProjectRuntime.install(next)
        // The replaced store was flushed by dispose and now refuses writes.
        assertTrue(previousFs.projectWrites >= 1, "install 前应把上一份待保存修改落盘")
        assertFalse(previous.saveNow())

        // Installing the same store twice must not dispose the live one.
        PhoneProjectRuntime.install(next)
        assertTrue(next.saveNow(), "同一个 store 再次 install 不应被 dispose")

        next.dispose() // leave no live worker behind in the shared runtime
    }
}
