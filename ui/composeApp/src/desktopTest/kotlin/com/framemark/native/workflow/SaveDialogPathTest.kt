package com.framemark.native.workflow

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * P2 regression: the "另存工程副本 / 导出预设" dialogs only read `FileDialog.file`,
 * so a relative name resolved against the process working directory and ignored
 * the folder the user picked. [resolveSaveDialogPath] must compose
 * `FileDialog.directory` + `FileDialog.file`, while still accepting an absolute
 * `file` from launchers that report the full path.
 */
class SaveDialogPathTest {
    @Test
    fun theChosenDirectoryIsCombinedWithTheFileName() {
        val dir = File(System.getProperty("java.io.tmpdir"), "framemark-save-probe").path
        assertEquals(
            File(dir, "framemark-project-copy.json").path,
            resolveSaveDialogPath(dir, "framemark-project-copy.json"),
        )
    }

    @Test
    fun anAbsoluteFileNameWinsOverTheDirectory() {
        val absolute = File(System.getProperty("java.io.tmpdir"), "absolute-copy.json").absolutePath
        assertEquals(absolute, resolveSaveDialogPath("/tmp/elsewhere", absolute))
    }

    @Test
    fun aMissingOrBlankFileNameCancelsTheDialog() {
        assertNull(resolveSaveDialogPath("/tmp", null))
        assertNull(resolveSaveDialogPath("/tmp", "   "))
    }

    @Test
    fun aRelativeNameWithoutDirectoryStillResolves() {
        // Some platforms report no directory; the file name must not be dropped.
        assertEquals(File("copy.json").absolutePath, resolveSaveDialogPath(null, "copy.json"))
        assertEquals(File("copy.json").absolutePath, resolveSaveDialogPath("", "copy.json"))
    }
}
