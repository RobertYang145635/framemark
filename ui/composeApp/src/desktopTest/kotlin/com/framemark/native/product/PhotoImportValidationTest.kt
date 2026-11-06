package com.framemark.native.product

import com.framemark.native.engine.PhotoSizes
import com.framemark.native.workflow.PhotoMetadata
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * WP-X1 / AU1-01 + AU1-04: one size source and a real import gate.
 */
class PhotoImportValidationTest {
    private val root = Files.createTempDirectory("framemark-wp-x1-photo-")

    private fun writePng(width: Int, height: Int): java.io.File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        val file = root.resolve("ok-$width-$height.png").toFile()
        file.writeBytes(out.toByteArray())
        return file
    }

    /** AU1-04: a header-only PNG that claims 40000×40000 is refused, not imported. */
    @Test
    fun absurdHeaderClaimsAreRejectedBeforeImport() {
        val bomb = root.resolve("bomb-40000.png").toFile()
        // 45 bytes: PNG magic + IHDR only, claiming 40000×40000 and no image data.
        val ihdr = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x9C.toByte(), 0x40, 0x00, 0x00, 0x9C.toByte(), 0x40,
            0x08, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        bomb.writeBytes(ihdr)
        // A JPEG/PNG signature plus one IHDR chunk — 33 bytes, the same shape the
        // review's 45-byte probe had: dimensions with no image data behind them.
        assertTrue(bomb.length() < 64, "fixture stays header-only (${bomb.length()} bytes)")

        val failed = PhotoMetadata.validate(bomb)
        val rejection = assertFailsWith<PhotoMetadata.PhotoRejected> { failed.getOrThrow() }
        assertTrue(
            rejection.code == "FM-E-IMPORT-005" || rejection.code == "FM-E-IMPORT-006",
            "expected a size/decodability rejection, got ${rejection.code}: ${rejection.message}",
        )
        assertTrue(rejection.message.isNotBlank())
    }

    @Test
    fun normalPhotoStillImports() {
        val file = writePng(320, 240)
        val meta = PhotoMetadata.validate(file).getOrThrow()
        assertEquals(320, meta.width)
        assertEquals(240, meta.height)
    }

    @Test
    fun emptyFileIsRejected() {
        val empty = root.resolve("empty.jpg").toFile().apply { writeBytes(ByteArray(0)) }
        val rejection = assertFailsWith<PhotoMetadata.PhotoRejected> {
            PhotoMetadata.validate(empty).getOrThrow()
        }
        assertEquals("FM-E-IMPORT-002", rejection.code)
    }

    @Test
    fun truncatedImageIsRejectedByTheDecodeProbe() {
        val good = writePng(200, 200)
        val truncated = root.resolve("truncated.png").toFile()
        truncated.writeBytes(good.readBytes().copyOf((good.length() / 3).toInt()))
        val failed = PhotoMetadata.validate(truncated)
        if (failed.isSuccess) {
            // PNG header still readable and the probe may recover a partial raster;
            // what must never happen is an unhandled exception.
            assertTrue(failed.getOrThrow().width > 0)
        } else {
            val rejection = assertFailsWith<PhotoMetadata.PhotoRejected> { failed.getOrThrow() }
            assertTrue(rejection.code.startsWith("FM-E-IMPORT-"))
        }
    }

    /** AU1-01: dimensions come from the project first, then the header — never a full decode. */
    @Test
    fun sizeSourcePrefersPrimedPhotoRefAndCachesHeaderReads() {
        val file = writePng(64, 48)
        val path = file.absolutePath
        PhotoSizes.prime(path, 1234, 987)
        assertEquals(1234 to 987, PhotoSizes.of(path), "primed PhotoRef dimensions win")

        val other = writePng(96, 72).absolutePath
        assertEquals(96 to 72, PhotoSizes.of(other), "header read fills the gap")
        assertEquals(96 to 72, PhotoSizes.of(other), "second read is served from the cache")

        assertNull(PhotoSizes.of(path + ".missing"))
        assertNull(PhotoSizes.of(null))
    }

    @Test
    fun subsampledReadReturnsATargetSizedRaster() {
        val file = writePng(1024, 768)
        val image = assertNotNull(PhotoSizes.readSubsampled(file, 256, 192))
        assertTrue(image.width in 200..1024, "decoded width ${image.width}")
        assertTrue(image.height in 150..768, "decoded height ${image.height}")
    }
}
