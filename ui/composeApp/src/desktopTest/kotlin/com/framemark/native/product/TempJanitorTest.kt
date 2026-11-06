package com.framemark.native.product

import com.framemark.native.engine.TempJanitor
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WP-X1 / AU1-02: engine scratch files are deleted, and abandoned ones are swept.
 *
 * The review measured 26 leftover files (6.97 MB) in `%TEMP%\framemark-x1` from
 * real product runs, because nothing in the bridge ever called `delete()`.
 */
class TempJanitorTest {
    private val dir = Files.createTempDirectory("fm-wp-x1-temp-").toFile()

    private fun touch(name: String, ageMillis: Long, bytes: Int = 32): java.io.File {
        val file = dir.resolve(name)
        file.writeBytes(ByteArray(bytes))
        file.setLastModified(System.currentTimeMillis() - ageMillis)
        return file
    }

    @Test
    fun scratchPrefixesAreRecognised() {
        assertTrue(TempJanitor.isScratch("raw-1a2b3c4d.png"))
        assertTrue(TempJanitor.isScratch("report-1a2b3c4d.json"))
        assertTrue(TempJanitor.isScratch("preview-wm-classic-1a2b3c4d.png"))
        assertTrue(TempJanitor.isScratch("e5-out-1a2b3c4d.jpg"))
        assertFalse(TempJanitor.isScratch("project.json"))
        assertFalse(TempJanitor.isScratch("screenshot.png"))
    }

    @Test
    fun sweepRemovesOnlyAbandonedScratch() {
        val hour = 60L * 60 * 1000
        val old = touch("raw-old.png", 30 * hour)
        val oldReport = touch("report-old.json", 30 * hour)
        val fresh = touch("raw-fresh.png", 1 * hour)
        val userFile = touch("project.json", 30 * hour)

        val result = TempJanitor.sweep(dir)

        assertEquals(2, result.removed, "only the two abandoned scratch files go")
        // `kept` counts scratch files that are still young enough to be live; a
        // non-scratch file is not the janitor's business at all.
        assertEquals(1, result.kept, "the fresh scratch file is kept")
        assertFalse(old.exists())
        assertFalse(oldReport.exists())
        assertTrue(fresh.exists(), "a scratch file younger than the window may be live")
        assertTrue(userFile.exists(), "non-scratch files are never touched")
    }

    @Test
    fun discardRemovesOneFileAndIsIdempotent() {
        val file = touch("raw-abc.png", 0)
        assertTrue(TempJanitor.discard(file))
        assertFalse(file.exists())
        assertTrue(TempJanitor.discard(file), "discarding a missing file is not an error")
    }
}
