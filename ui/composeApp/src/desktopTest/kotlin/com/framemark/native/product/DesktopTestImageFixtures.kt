package com.framemark.native.product

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Small deterministic images used by desktop tests without external workspace assets. */
internal object DesktopTestImageFixtures {
    private val root: Path = Files.createTempDirectory("framemark-desktop-test-images-")

    init {
        writeJpeg("ratio-study/4x3.jpg", 400, 300, Color(38, 126, 190))
        writeJpeg("vertical.jpg", 300, 400, Color(190, 72, 54))
        writeJpeg("p1170883.jpg", 640, 480, Color(64, 152, 78))
        writeWatermark("root-import/水印_白色.png", Color.WHITE)
        writeWatermark("root-import/水印_黑色.png", Color.BLACK)

        Runtime.getRuntime().addShutdownHook(
            Thread({ root.toFile().deleteRecursively() }, "framemark-desktop-test-image-cleanup"),
        )
    }

    fun path(relative: String): String {
        val file = root.resolve(relative).normalize()
        require(file.startsWith(root) && Files.isRegularFile(file)) { "Unknown desktop image fixture: $relative" }
        return file.toAbsolutePath().toString()
    }

    private fun writeJpeg(relative: String, width: Int, height: Int, base: Color) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val delta = ((x / 16 + y / 16) % 5) * 5
                image.setRGB(
                    x,
                    y,
                    Color(
                        (base.red + delta).coerceAtMost(255),
                        (base.green + delta).coerceAtMost(255),
                        (base.blue + delta).coerceAtMost(255),
                    ).rgb,
                )
            }
        }
        check(ImageIO.write(image, "jpg", file.toFile())) { "No JPEG writer is available" }
    }

    private fun writeWatermark(relative: String, color: Color) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        val image = BufferedImage(64, 40, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val insideMark = x in 8..55 && y in 8..31 && ((x + y) % 7 != 0)
                val alpha = if (insideMark) 210 else 0
                image.setRGB(x, y, Color(color.red, color.green, color.blue, alpha).rgb)
            }
        }
        check(ImageIO.write(image, "png", file.toFile())) { "No PNG writer is available" }
    }
}
