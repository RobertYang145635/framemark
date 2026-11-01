package com.framemark.native.product

import com.framemark.native.workflow.DesktopWorkflowPlatform
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalExportPreferencesTest {
    @Test fun qualityPersistsLocallyAndRejectsInvalidValues() {
        val root = Files.createTempDirectory("fm-local-prefs").toFile()
        try {
            val platform = DesktopWorkflowPlatform(root.path, root.resolve("exports").path)
            assertEquals(100, platform.preferredJpegQuality())
            assertNull(platform.setPreferredJpegQuality(72))
            assertEquals(72, DesktopWorkflowPlatform(root.path).preferredJpegQuality())
            assertTrue(platform.setPreferredJpegQuality(59) != null)
            assertEquals(72, platform.preferredJpegQuality())
            assertFalse(root.resolve("presets.json").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun concurrentCommitNeverOverwritesAnExistingOrCompetingOutput() {
        val root = Files.createTempDirectory("fm-export-commit").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val platform = DesktopWorkflowPlatform(root.path, root.path)
            val first = root.resolve("first.tmp").apply { writeBytes(ByteArray(128 * 1024) { 1 }) }
            val second = root.resolve("second.tmp").apply { writeBytes(ByteArray(128 * 1024) { 2 }) }
            val target = root.resolve("final.png")
            val start = CountDownLatch(1)
            val results = listOf(first, second).map { source ->
                executor.submit<Boolean> { start.await(); platform.commitExport(source.path, target.path) }
            }
            start.countDown()
            assertEquals(1, results.count { it.get() })
            val committed = target.readBytes()
            assertTrue(committed.contentEquals(first.readBytes()) || committed.contentEquals(second.readBytes()))
            assertFalse(platform.commitExport(first.path, target.path))
            assertTrue(committed.contentEquals(target.readBytes()))
        } finally { executor.shutdownNow(); root.deleteRecursively() }
    }
}
