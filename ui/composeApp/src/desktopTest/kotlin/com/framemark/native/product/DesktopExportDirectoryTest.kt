package com.framemark.native.product

import com.framemark.native.workflow.DesktopWorkflowPlatform
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B2-B / A: the export directory is a **machine preference**, not part of a design.
 *
 * These cases pin what the brief asks to verify without a GUI: the folder survives a
 * restart, a folder that disappeared is recreated on commit instead of failing silently,
 * and a job queued under one folder keeps that folder when the preference later changes.
 */
class DesktopExportDirectoryTest {
    /** Writes the file the way the product does: `Properties.store`, so escapes match. */
    private fun prefs(dataDir: File, directory: String?) = File(dataDir, "local-export.properties").apply {
        parentFile.mkdirs()
        val properties = java.util.Properties().apply {
            directory?.let { setProperty("directory", it) }
            setProperty("jpegQuality", "100")
        }
        outputStream().use { properties.store(it, "FrameMark local export preferences") }
    }

    @Test
    fun the_recent_directory_survives_a_restart() {
        val root = Files.createTempDirectory("fm-export-dir").toFile()
        try {
            val chosen = File(root, "相册导出").apply { mkdirs() }
            prefs(root, chosen.path)
            // A new process reads the same preference file.
            val restarted = DesktopWorkflowPlatform(root.path)
            assertEquals(chosen.path, restarted.exportDir)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun a_directory_that_disappeared_is_recreated_on_commit() {
        val root = Files.createTempDirectory("fm-export-gone").toFile()
        try {
            val gone = File(root, "被删掉的目录/子目录")
            prefs(root, gone.path)
            val platform = DesktopWorkflowPlatform(root.path)
            assertEquals(gone.path, platform.exportDir)

            val temporary = File(root, "out.tmp").apply { writeBytes(ByteArray(4096) { 7 }) }
            val target = File(gone, "photo.jpg")
            assertTrue(platform.commitExport(temporary.path, target.path), "目录消失后应重建并写入")
            assertTrue(target.isFile && target.length() == 4096L)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun a_job_keeps_the_directory_it_was_queued_with() {
        val root = Files.createTempDirectory("fm-export-snapshot").toFile()
        try {
            val first = File(root, "第一次").apply { mkdirs() }
            val platform = DesktopWorkflowPlatform(root.path, first.path)
            assertEquals(first.path, platform.exportDir)

            // The preference changes afterwards (as if the user picked another folder).
            val second = File(root, "第二次").apply { mkdirs() }
            prefs(root, second.path)

            // The queued job carries its own directory; a later preference never rewrites it.
            val job = BatchJob.forPhoto(
                id = "job-snapshot",
                photoPath = "/photos/a.jpg",
                photoFingerprint = "fp-a",
                displayName = "a.jpg",
                design = PhotoDesign.base("simple-4"),
                exportSettings = ExportSettings(),
                now = 1,
                nameIndex = 1,
                outputDirectory = platform.exportDir,
            )
            assertEquals(first.path, job.outputDirectory)
            // Reading the preference again must not reach into the already queued job.
            assertEquals(second.path, DesktopWorkflowPlatform(root.path).exportDir)
            assertEquals(first.path, job.outputDirectory)
        } finally { root.deleteRecursively() }
    }
}
