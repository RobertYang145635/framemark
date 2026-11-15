package com.framemark.native.workflow

import com.framemark.native.product.PreviewHandoff
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * P2 (audit): a preview render that is cancelled *after* the engine wrote its PNG
 * and geom, but before the value reaches the consumer, used to leak both files —
 * the renderer's own `catch` had already left and the consumer never received a
 * NativeImage. [PreviewHandoff.withPreviewHandoff] is the ownership contract
 * AndroidNativeRender now uses; this probe cancels a real coroutine while it is
 * suspended after offering the files and asserts they are gone.
 */
class PreviewScratchHandoffTest {

    private fun pngFile(): File =
        Files.createTempFile("preview-probe-", ".png").toFile().apply { writeText("png-bytes") }

    @Test
    fun aCancelledPreviewDeletesTheFilesItWrote() = runBlocking {
        val png = pngFile()
        val geom = File("${png.path}.geom.json").apply { writeText("{}") }
        val released = mutableListOf<String>()
        val job = launch {
            PreviewHandoff.withPreviewHandoff(
                release = { path ->
                    released += path
                    File(path).delete()
                    File("$path.geom.json").delete()
                },
            ) { offer ->
                // The renderer writes the pair, offers it, and then awaits the
                // engine/handoff. Cancellation lands while it is suspended.
                offer(png.path)
                awaitCancellation()
            }
        }
        yield()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(png.exists(), "cancelled preview PNG must be deleted")
        assertFalse(geom.exists(), "cancelled preview geom must be deleted")
        assertEquals(listOf(png.path), released)
    }

    @Test
    fun aSuccessfulPreviewKeepsTheFilesForTheConsumer() = runBlocking {
        val png = pngFile()
        var released = false
        val result = PreviewHandoff.withPreviewHandoff(release = { released = true }) { offer ->
            offer(png.path)
            "image"
        }
        assertEquals("image", result)
        assertTrue(png.exists(), "the consumer still owns a successful preview's files")
        assertFalse(released)
    }

    @Test
    fun aBlockThatFailsBeforeOfferingReleasesNothing() = runBlocking {
        var released = false
        val failure = runCatching {
            PreviewHandoff.withPreviewHandoff(release = { released = true }) { _ ->
                throw CancellationException("no files were written")
            }
        }
        assertTrue(failure.isFailure)
        assertFalse(released, "nothing was offered, so nothing may be deleted")
    }
}
