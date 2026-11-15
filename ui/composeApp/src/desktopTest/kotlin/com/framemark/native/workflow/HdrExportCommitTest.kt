package com.framemark.native.workflow

import com.framemark.native.product.ExportSettings
import com.framemark.native.product.UltraHdr
import com.framemark.native.product.UltraHdrFixture
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B-01 regression: a failing Ultra HDR write must never be reported as a
 * successful export, and the (already valid) SDR bytes must still be committed.
 *
 * The old code wrote the wrapped container **over** the SDR temporary, with a
 * truncate-then-write `writeBytes`: a disk-full/short write left a few bytes on
 * disk, `fileSize > 0` passed, and `NativeEngine.exportCard` returned success
 * for an undecodable file (reproduced by the audit with a 32-byte result).
 *
 * Each case below injects exactly that platform I/O behaviour and asserts the
 * commit decision, which is the part the audit proved broken.
 */
class HdrExportCommitTest {

    @Test
    fun a_failed_hdr_write_commits_the_untouched_sdr_file() {
        val result = withExport { platform ->
            // Truncate-then-fail: the 32 bytes land on disk, the write reports failure.
            object : WorkflowPlatform by platform {
                override fun writeBytes(path: String, bytes: ByteArray): Boolean {
                    File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes.copyOf(32))
                    return false
                }
            }
        }
        val committed = File(result.path).readBytes()
        assertTrue(committed.size > 32, "the 32-byte partial HDR payload must not be committed")
        assertNotNull(decode(committed), "the committed export must still be a decodable JPEG")
        assertEquals(null, UltraHdr.locate(committed), "a failed HDR write must fall back to plain SDR")
        assertEquals(committed.size, result.bytes, "the reported size must be the committed file's size")
    }

    @Test
    fun a_short_hdr_write_that_claims_success_is_still_rejected() {
        val result = withExport { platform ->
            // The return value is a lie only the on-disk length can catch.
            object : WorkflowPlatform by platform {
                override fun writeBytes(path: String, bytes: ByteArray): Boolean {
                    File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes.copyOf(64))
                    return true
                }
            }
        }
        val committed = File(result.path).readBytes()
        assertEquals(null, UltraHdr.locate(committed), "a short write must not be committed as HDR")
        assertNotNull(decode(committed), "the SDR fallback must remain decodable")
        assertEquals(committed.size, result.bytes)
    }

    @Test
    fun a_zero_byte_hdr_write_that_claims_success_is_still_rejected() {
        val result = withExport { platform ->
            object : WorkflowPlatform by platform {
                override fun writeBytes(path: String, bytes: ByteArray): Boolean {
                    File(path).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(0))
                    return true
                }
            }
        }
        val committed = File(result.path).readBytes()
        assertEquals(null, UltraHdr.locate(committed))
        assertNotNull(decode(committed), "a zero-byte write must leave the SDR export intact")
        assertEquals(committed.size, result.bytes)
    }

    @Test
    fun a_normal_hdr_export_commits_a_well_formed_ultra_hdr_file() {
        val result = withExport { platform -> platform }
        val committed = File(result.path).readBytes()
        val gainMap = assertNotNull(UltraHdr.locate(committed), "a successful wrap must produce a readable gain map")
        assertTrue(gainMap.size > 0, "the gain map payload must not be empty")
        val gainBytes = committed.copyOfRange(
            gainMap.offset.toInt(),
            (gainMap.offset + gainMap.size).toInt(),
        )
        assertNotNull(decode(gainBytes), "the appended gain map must be a decodable JPEG")
        assertTrue(result.bytes > 32)
        assertNotNull(decode(committed), "the Ultra HDR file must still decode as a plain JPEG")
    }

    /**
     * Runs one export through the real [NativeEngine.exportCard] commit pipeline,
     * with the engine replaced by a controllable SDR writer and the platform by
     * [faulty]. Returns the successful [ExportResult].
     */
    private fun withExport(faulty: (WorkflowPlatform) -> WorkflowPlatform): ExportResult {
        val root = Files.createTempDirectory("fm-hdr-commit-").toFile()
        val base = DesktopWorkflowPlatform(
            dataDir = File(root, "data").path,
            exportDir = File(root, "out").path,
        )
        val photo = File(root, "photo.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(UltraHdrFixture.build(width = 160, height = 120))
        }
        assertNotNull(UltraHdr.locate(photo.readBytes()), "fixture must be a real Ultra HDR source")

        val oldPlatform = WorkflowEnv.platform
        val oldEngine = WorkflowEnv.nativeRender
        try {
            WorkflowEnv.platform = faulty(base)
            WorkflowEnv.nativeRender = object : NativeRenderApi {
                override fun supports(cardId: String) = true

                override suspend fun render(
                    cardId: String,
                    design: DesignSnapshot,
                    mode: String,
                    maxEdge: Int?,
                    format: SampleFormat?,
                    quality: Int?,
                    longEdge: Int?,
                    outputPath: String?,
                    webpLossless: Boolean,
                    overlay: com.framemark.native.workflow.OverlaySpec?,
                    photoPath: String?,
                    interactive: Boolean,
                ): NativeImage {
                    val target = File(requireNotNull(outputPath))
                    target.parentFile?.mkdirs()
                    ImageIO.write(BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "jpeg", target)
                    return NativeImage(target.path, 120, 80, 1, ImageRect(0f, 0f, 120f, 80f))
                }
            }
            return runBlocking {
                NativeEngine.exportCard(
                    cardId = "hdr-commit-test",
                    cardName = "hdr-commit-test",
                    styleId = null,
                    settings = ExportSettings(preserveHdr = true),
                    design = DesignSnapshot("hdr-commit-test", null, emptyMap(), emptyMap()),
                    photoPath = photo.path,
                    photoDisplayName = "hdr-source.jpg",
                )
            }
        } finally {
            WorkflowEnv.platform = oldPlatform
            WorkflowEnv.nativeRender = oldEngine
        }
    }

    private fun decode(bytes: ByteArray): BufferedImage? =
        runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull()

    @Test
    fun the_fixture_itself_is_a_readable_ultra_hdr_file() {
        val bytes = UltraHdrFixture.build(width = 160, height = 120)
        val gain = assertNotNull(UltraHdr.locate(bytes))
        assertNotNull(decode(bytes))
        assertNotNull(
            decode(bytes.copyOfRange(gain.offset.toInt(), (gain.offset + gain.size).toInt())),
            "the fixture gain map must decode for the composer tests",
        )
        assertEquals(160, UltraHdr.frameSize(bytes)?.first)
        assertEquals(120, UltraHdr.frameSize(bytes)?.second)
    }

    @Test
    fun the_fixture_can_carry_an_exif_orientation_that_does_not_break_its_index() {
        val bytes = UltraHdrFixture.build(width = 160, height = 120, orientation = 6)
        val gain = assertNotNull(UltraHdr.locate(bytes), "injecting APP1 must keep the MPF/index addressable")
        assertNotNull(
            decode(bytes.copyOfRange(gain.offset.toInt(), (gain.offset + gain.size).toInt())),
        )
        assertFalse(gain.size <= 0)
    }
}
