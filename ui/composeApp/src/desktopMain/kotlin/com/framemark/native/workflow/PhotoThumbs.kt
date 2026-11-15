package com.framemark.native.workflow

import com.framemark.native.product.orientedImage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Small photo/watermark thumbnails without decoding the full raster. */
object PhotoThumbs {
    fun jpeg(path: String, maxEdge: Int): ByteArray? = render(path, maxEdge, "jpg")

    fun png(path: String, maxEdge: Int): ByteArray? = render(path, maxEdge, "png")

    private fun render(path: String, maxEdge: Int, format: String): ByteArray? = runCatching {
        val file = File(path)
        if (!file.isFile || maxEdge <= 0) return@runCatching null
        ImageIO.createImageInputStream(file).use { input ->
            if (input == null) return@runCatching null
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return@runCatching null
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0) return@runCatching null
                val longEdge = max(width, height)
                val sample = if (longEdge > maxEdge) ceil(longEdge.toDouble() / maxEdge).toInt() else 1
                val param: ImageReadParam = reader.defaultReadParam
                if (sample > 1) param.setSourceSubsampling(sample, sample, 0, 0)
                val raw = reader.read(0, param) ?: return@runCatching null
                val source = raw.orientedImage(PhotoMetadata.orientation(file))
                val scale = if (max(source.width, source.height) > maxEdge) {
                    maxEdge.toDouble() / max(source.width, source.height)
                } else {
                    1.0
                }
                val targetWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
                val scaled = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
                val g = scaled.createGraphics()
                try {
                    g.drawImage(source, 0, 0, targetWidth, targetHeight, null)
                } finally {
                    g.dispose()
                }
                val out = ByteArrayOutputStream()
                if (format == "png") {
                    ImageIO.write(scaled, "png", out)
                } else {
                    val rgb = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
                    val gg = rgb.createGraphics()
                    try {
                        gg.drawImage(scaled, 0, 0, null)
                    } finally {
                        gg.dispose()
                    }
                    ImageIO.write(rgb, "jpg", out)
                }
                out.toByteArray()
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()
}
